package com.bdreview.platform.accountcontrol;

import com.bdreview.platform.auth.RefreshTokenRepository;
import com.bdreview.platform.auth.User;
import com.bdreview.platform.auth.UserRepository;
import com.bdreview.platform.auth.UserRole;
import com.bdreview.platform.common.BadRequestException;
import com.bdreview.platform.common.CurrentUser;
import com.bdreview.platform.common.ResourceNotFoundException;
import com.bdreview.platform.moderation.AuditLogService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Platform-wide account control (V63): suspend / ban / lift, force logout, role change and the
 * login history. Every admin action needs a reason and lands in the audit log with before/after
 * snapshots. Suspending or banning also revokes every refresh token, so the user is signed out
 * as soon as their current access token expires; until then every write is refused by
 * {@link AccountWriteGuard}.
 */
@Service
public class AccountControlService {

    public static final ZoneId DISPLAY_ZONE = ZoneId.of("Asia/Dhaka");
    private static final DateTimeFormatter DISPLAY_FORMAT =
            DateTimeFormatter.ofPattern("d MMM yyyy, h:mm a", Locale.ENGLISH).withZone(DISPLAY_ZONE);
    private static final long CACHE_TTL_MS = 5_000;

    private record Cached(Optional<UserRestriction> restriction, long loadedAt) {
    }

    private final UserRestrictionRepository restrictionRepository;
    private final UserLoginEventRepository loginEventRepository;
    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final AuditLogService auditLogService;
    private final TransactionTemplate independentTx;
    private final Map<UUID, Cached> cache = new ConcurrentHashMap<>();

    public AccountControlService(UserRestrictionRepository restrictionRepository,
                                 UserLoginEventRepository loginEventRepository,
                                 UserRepository userRepository,
                                 RefreshTokenRepository refreshTokenRepository,
                                 AuditLogService auditLogService,
                                 PlatformTransactionManager transactionManager) {
        this.restrictionRepository = restrictionRepository;
        this.loginEventRepository = loginEventRepository;
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.auditLogService = auditLogService;
        // Login events are written in their own transaction so a refused login (which throws and
        // rolls back the caller's transaction) is still recorded.
        this.independentTx = new TransactionTemplate(transactionManager);
        this.independentTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    // -----------------------------------------------------------------
    // Checks
    // -----------------------------------------------------------------

    /** The restriction in force for this user right now (BAN wins over SUSPEND), briefly cached. */
    public Optional<UserRestriction> inEffect(UUID userId) {
        Cached cached = cache.get(userId);
        long now = System.currentTimeMillis();
        if (cached != null && now - cached.loadedAt() < CACHE_TTL_MS
                && cached.restriction().map(r -> r.isInEffect(Instant.now())).orElse(true)) {
            return cached.restriction();
        }
        Optional<UserRestriction> fresh = restrictionRepository.findInEffect(userId, Instant.now()).stream().findFirst();
        cache.put(userId, new Cached(fresh, now));
        return fresh;
    }

    /** Throws {@link AccountRestrictedException} (403, with reason + end date) while the account is suspended/banned. */
    public void assertNotRestricted(UUID userId) {
        inEffect(userId).ifPresent(r -> {
            throw toException(r);
        });
    }

    /** Login gate: refuses a restricted account and records the attempt either way. */
    public void checkLogin(UUID userId, String channel) {
        Optional<UserRestriction> restriction = inEffect(userId);
        if (restriction.isPresent()) {
            recordLogin(userId, channel, "RESTRICTED");
            throw toException(restriction.get());
        }
        recordLogin(userId, channel, "SUCCESS");
    }

    public static AccountRestrictedException toException(UserRestriction r) {
        String message = r.getType() == UserRestriction.Type.BAN
                ? "Your account has been banned. Reason: " + r.getReason()
                : "Your account is suspended until " + DISPLAY_FORMAT.format(r.getEndsAt()) + ". Reason: " + r.getReason();
        return new AccountRestrictedException(r.getType(), r.getReason(), r.getEndsAt(), message);
    }

    public static String formatInstant(Instant instant) {
        return instant == null ? "" : DISPLAY_FORMAT.format(instant);
    }

    // -----------------------------------------------------------------
    // Admin actions
    // -----------------------------------------------------------------

    @Transactional
    public UserRestriction suspend(UUID userId, Instant endsAt, String reason) {
        String why = requireReason(reason);
        User user = targetUser(userId);
        if (endsAt == null || !endsAt.isAfter(Instant.now())) {
            throw new BadRequestException("The suspension end must be in the future.");
        }
        return restrict(user, UserRestriction.Type.SUSPEND, endsAt, why);
    }

    @Transactional
    public UserRestriction ban(UUID userId, String reason) {
        return restrict(targetUser(userId), UserRestriction.Type.BAN, null, requireReason(reason));
    }

    private UserRestriction restrict(User user, UserRestriction.Type type, Instant endsAt, String reason) {
        Map<String, Object> before = standing(user.getId());
        UserRestriction saved = restrictionRepository.save(UserRestriction.builder()
                .userId(user.getId())
                .type(type)
                .reason(reason)
                .endsAt(endsAt)
                .createdBy(CurrentUser.id())
                .build());
        refreshTokenRepository.revokeAllForUser(user.getId());
        cache.remove(user.getId());
        auditLogService.record("USER", user.getId(), type == UserRestriction.Type.BAN ? "USER_BANNED" : "USER_SUSPENDED",
                reason, before, standing(user.getId()));
        return saved;
    }

    /** Lifts every restriction currently in force. */
    @Transactional
    public int lift(UUID userId, String reason) {
        String why = requireReason(reason);
        User user = targetUser(userId);
        Map<String, Object> before = standing(user.getId());
        List<UserRestriction> active = restrictionRepository.findInEffect(user.getId(), Instant.now());
        if (active.isEmpty()) {
            throw new BadRequestException("This account has no active suspension or ban.");
        }
        Instant now = Instant.now();
        UUID actor = CurrentUser.id();
        for (UserRestriction r : active) {
            r.setLiftedAt(now);
            r.setLiftedBy(actor);
            r.setLiftReason(why);
        }
        restrictionRepository.saveAll(active);
        cache.remove(user.getId());
        auditLogService.record("USER", user.getId(), "USER_RESTRICTION_LIFTED", why, before, standing(user.getId()));
        return active.size();
    }

    /** Revokes every refresh token: the user is signed out everywhere once their access token expires. */
    @Transactional
    public void forceLogout(UUID userId, String reason) {
        String why = requireReason(reason);
        User user = targetUser(userId);
        refreshTokenRepository.revokeAllForUser(user.getId());
        auditLogService.record("USER", user.getId(), "USER_FORCE_LOGOUT", why, null, Map.of("refreshTokensRevoked", true));
    }

    @Transactional
    public void changeRole(UUID userId, UserRole newRole, String reason) {
        String why = requireReason(reason);
        User user = targetUser(userId);
        if (newRole == null) {
            throw new BadRequestException("Pick a role.");
        }
        if (user.getRole() == newRole) {
            throw new BadRequestException("The account already has this role.");
        }
        if (userRepository.existsByPhoneNumberAndRole(user.getPhoneNumber(), newRole)) {
            throw new BadRequestException("This phone number already has a separate " + newRole
                    + " account — an account per role is allowed only once.");
        }
        UserRole oldRole = user.getRole();
        user.setRole(newRole);
        // V67: promoted to ADMIN → least-privileged admin role until a SUPER_ADMIN changes it.
        user.setAdminRole(newRole == UserRole.ADMIN ? "SUPPORT" : null);
        try {
            userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            throw new BadRequestException("This phone number already has a " + newRole + " account.");
        }
        // The role travels inside access/refresh-issued JWTs — make the user sign in again.
        refreshTokenRepository.revokeAllForUser(user.getId());
        auditLogService.record("USER", user.getId(), "USER_ROLE_CHANGED", why,
                Map.of("role", oldRole.name()), Map.of("role", newRole.name()));
    }

    /** ACTIVE / SUSPENDED / BANNED per user, for the admin list. */
    public Map<UUID, String> statusFor(java.util.Collection<UUID> userIds) {
        Map<UUID, String> out = new java.util.HashMap<>();
        if (userIds.isEmpty()) {
            return out;
        }
        for (UserRestriction r : restrictionRepository.findInEffectForUsers(userIds, Instant.now())) {
            if (r.getType() == UserRestriction.Type.BAN) {
                out.put(r.getUserId(), "BANNED");
            } else {
                out.putIfAbsent(r.getUserId(), "SUSPENDED");
            }
        }
        return out;
    }

    // -----------------------------------------------------------------
    // History
    // -----------------------------------------------------------------

    public List<UserRestriction> history(UUID userId) {
        return restrictionRepository.findByUserIdOrderByCreatedAtDesc(userId);
    }

    public List<UserLoginEvent> recentLogins(UUID userId, int limit) {
        return loginEventRepository.findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(0, limit));
    }

    public void recordLogin(UUID userId, String channel, String outcome) {
        String ip = null;
        String agent = null;
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            HttpServletRequest request = attrs.getRequest();
            String forwarded = request.getHeader("X-Forwarded-For");
            ip = forwarded != null && !forwarded.isBlank() ? forwarded.split(",")[0].trim() : request.getRemoteAddr();
            agent = request.getHeader("User-Agent");
        }
        UserLoginEvent event = UserLoginEvent.builder()
                .userId(userId)
                .channel(channel)
                .outcome(outcome)
                .ipAddress(truncate(ip, 64))
                .userAgent(truncate(agent, 255))
                .build();
        independentTx.executeWithoutResult(status -> loginEventRepository.save(event));
    }

    // -----------------------------------------------------------------

    private User targetUser(UUID userId) {
        User user = userRepository.findById(userId).orElseThrow(() -> new ResourceNotFoundException("User not found"));
        if (user.getId().equals(CurrentUser.idOrNull())) {
            throw new BadRequestException("You can't apply this action to your own account.");
        }
        return user;
    }

    private Map<String, Object> standing(UUID userId) {
        Map<String, Object> s = new LinkedHashMap<>();
        Optional<UserRestriction> r = restrictionRepository.findInEffect(userId, Instant.now()).stream().findFirst();
        s.put("status", r.map(x -> x.getType() == UserRestriction.Type.BAN ? "BANNED" : "SUSPENDED").orElse("ACTIVE"));
        r.ifPresent(x -> s.put("endsAt", x.getEndsAt() == null ? null : x.getEndsAt().toString()));
        return s;
    }

    private static String requireReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new BadRequestException("A reason is required.");
        }
        String trimmed = reason.trim();
        if (trimmed.length() > 1000) {
            throw new BadRequestException("The reason can be at most 1000 characters.");
        }
        return trimmed;
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
