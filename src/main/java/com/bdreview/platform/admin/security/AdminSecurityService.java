package com.bdreview.platform.admin.security;

import com.bdreview.platform.auth.User;
import com.bdreview.platform.auth.UserRepository;
import com.bdreview.platform.auth.UserRole;
import com.bdreview.platform.common.BadRequestException;
import com.bdreview.platform.common.CurrentUser;
import com.bdreview.platform.common.ResourceNotFoundException;
import com.bdreview.platform.features.PlatformSettingStore;
import com.bdreview.platform.moderation.AuditLogService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Admin roles & security (V67): admin permission roles, optional TOTP 2FA (with one-time recovery
 * codes, enforceable per role by a SUPER_ADMIN), and "sign out all admin sessions". Every change
 * needs a reason and is audited.
 */
@Service
public class AdminSecurityService {

    public static final String ISSUER = "Jachai Admin";
    /** platform_setting: the message column holds the epoch-millis before which every admin session is invalid. */
    public static final String SESSION_EPOCH_KEY = "ADMIN_SESSION_EPOCH";
    private static final String TOTP_REQUIRED_PREFIX = "TOTP_REQUIRED_";
    private static final int RECOVERY_CODES = 10;
    private static final String RECOVERY_ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserRepository userRepository;
    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;
    private final PlatformSettingStore settings;
    private final AuditLogService auditLogService;

    public AdminSecurityService(UserRepository userRepository, JdbcTemplate jdbc, PasswordEncoder passwordEncoder,
                                PlatformSettingStore settings, AuditLogService auditLogService) {
        this.userRepository = userRepository;
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
        this.settings = settings;
        this.auditLogService = auditLogService;
    }

    // ---------------------------------------------------------------- roles

    public List<User> admins() {
        return userRepository.findByRole(UserRole.ADMIN, org.springframework.data.domain.PageRequest.of(0, 500)).getContent();
    }

    @Transactional
    public void setAdminRole(UUID userId, AdminRole role, String reason) {
        String why = requireReason(reason);
        User user = userRepository.findById(userId).orElseThrow(() -> new ResourceNotFoundException("User not found"));
        if (user.getRole() != UserRole.ADMIN) {
            throw new BadRequestException("Only admin accounts have an admin role.");
        }
        if (user.getId().equals(CurrentUser.idOrNull())) {
            throw new BadRequestException("You can't change your own admin role.");
        }
        AdminRole before = AdminRole.parse(user.getAdminRole());
        if (before == AdminRole.SUPER_ADMIN && role != AdminRole.SUPER_ADMIN
                && jdbc.queryForObject("SELECT count(*) FROM app_user WHERE role = 'ADMIN' AND admin_role = 'SUPER_ADMIN'", Long.class) <= 1) {
            throw new BadRequestException("This is the last SUPER_ADMIN — promote someone else first.");
        }
        user.setAdminRole(role.name());
        userRepository.save(user);
        auditLogService.record("USER", userId, "ADMIN_ROLE_CHANGED", why,
                Map.of("adminRole", before.name()), Map.of("adminRole", role.name()));
    }

    // ---------------------------------------------------------------- 2FA enforcement

    public boolean totpRequired(AdminRole role) {
        return settings.get(TOTP_REQUIRED_PREFIX + role.name()).map(PlatformSettingStore.Row::enabled).orElse(false);
    }

    public Map<AdminRole, Boolean> totpRequirements() {
        Map<AdminRole, Boolean> out = new EnumMap<>(AdminRole.class);
        for (AdminRole r : AdminRole.values()) {
            out.put(r, totpRequired(r));
        }
        return out;
    }

    @Transactional
    public void setTotpRequired(Map<AdminRole, Boolean> required, String reason) {
        String why = requireReason(reason);
        Map<String, Object> before = new java.util.LinkedHashMap<>();
        totpRequirements().forEach((k, v) -> before.put(k.name(), v));
        for (AdminRole r : AdminRole.values()) {
            settings.put(TOTP_REQUIRED_PREFIX + r.name(), Boolean.TRUE.equals(required.get(r)), null, CurrentUser.idOrNull());
        }
        Map<String, Object> after = new java.util.LinkedHashMap<>();
        totpRequirements().forEach((k, v) -> after.put(k.name(), v));
        auditLogService.record("ADMIN_SECURITY", null, "TOTP_REQUIREMENT_CHANGED", why, before, after);
    }

    /** True when this admin must enrol in 2FA before using the panel. */
    public boolean mustEnrol(User user) {
        return user.getRole() == UserRole.ADMIN && !user.isTotpEnabled() && totpRequired(AdminRole.parse(user.getAdminRole()));
    }

    // ---------------------------------------------------------------- 2FA self-service

    /** Starts (or restarts) enrolment: a fresh secret, not active until a code confirms it. */
    @Transactional
    public String startEnrolment(UUID userId) {
        User user = self(userId);
        if (user.isTotpEnabled()) {
            throw new BadRequestException("Two-factor authentication is already on.");
        }
        user.setTotpSecret(Totp.newSecret());
        userRepository.save(user);
        return user.getTotpSecret();
    }

    /** Confirms enrolment with a code from the app; returns the one-time recovery codes (shown once). */
    @Transactional
    public List<String> confirmEnrolment(UUID userId, String code) {
        User user = self(userId);
        if (user.getTotpSecret() == null) {
            throw new BadRequestException("Start the setup first.");
        }
        if (!Totp.verify(user.getTotpSecret(), code, Instant.now())) {
            throw new BadRequestException("That code didn't match — check the time on your phone and try again.");
        }
        user.setTotpEnabled(true);
        user.setTotpEnabledAt(Instant.now());
        userRepository.save(user);
        List<String> codes = regenerateRecoveryCodes(userId);
        auditLogService.record("USER", userId, "ADMIN_2FA_ENABLED", "Enrolled in two-factor authentication",
                Map.of("totpEnabled", false), Map.of("totpEnabled", true));
        return codes;
    }

    @Transactional
    public void disable(UUID userId, String code) {
        User user = self(userId);
        if (!user.isTotpEnabled()) {
            throw new BadRequestException("Two-factor authentication is not on.");
        }
        if (totpRequired(AdminRole.parse(user.getAdminRole()))) {
            throw new BadRequestException("Two-factor authentication is required for your role.");
        }
        if (!checkSecondFactor(user, code)) {
            throw new BadRequestException("That code didn't match.");
        }
        clearTotp(user);
        auditLogService.record("USER", userId, "ADMIN_2FA_DISABLED", "Turned off two-factor authentication",
                Map.of("totpEnabled", true), Map.of("totpEnabled", false));
    }

    /** SUPER_ADMIN reset for an admin who lost their phone and recovery codes. */
    @Transactional
    public void reset(UUID userId, String reason) {
        String why = requireReason(reason);
        User user = userRepository.findById(userId).orElseThrow(() -> new ResourceNotFoundException("User not found"));
        clearTotp(user);
        auditLogService.record("USER", userId, "ADMIN_2FA_RESET", why, Map.of("totpEnabled", true), Map.of("totpEnabled", false));
    }

    /** A login's second factor: a current TOTP code, or an unused recovery code (consumed). */
    @Transactional
    public boolean checkSecondFactor(User user, String code) {
        if (code == null || code.isBlank()) {
            return false;
        }
        if (Totp.verify(user.getTotpSecret(), code, Instant.now())) {
            return true;
        }
        String normalized = code.trim().toLowerCase().replace("-", "").replace(" ", "");
        for (var row : jdbc.queryForList("SELECT id, code_hash FROM admin_recovery_code WHERE user_id = ? AND used_at IS NULL", user.getId())) {
            if (passwordEncoder.matches(normalized, (String) row.get("code_hash"))) {
                jdbc.update("UPDATE admin_recovery_code SET used_at = now() WHERE id = ?", row.get("id"));
                auditLogService.recordSystem("USER", user.getId(), "ADMIN_RECOVERY_CODE_USED", "Signed in with a recovery code", null, null);
                return true;
            }
        }
        return false;
    }

    public long unusedRecoveryCodes(UUID userId) {
        return jdbc.queryForObject("SELECT count(*) FROM admin_recovery_code WHERE user_id = ? AND used_at IS NULL", Long.class, userId);
    }

    @Transactional
    public List<String> regenerateRecoveryCodes(UUID userId) {
        jdbc.update("DELETE FROM admin_recovery_code WHERE user_id = ?", userId);
        List<String> codes = new ArrayList<>();
        for (int i = 0; i < RECOVERY_CODES; i++) {
            StringBuilder sb = new StringBuilder();
            for (int j = 0; j < 10; j++) {
                sb.append(RECOVERY_ALPHABET.charAt(RANDOM.nextInt(RECOVERY_ALPHABET.length())));
            }
            String code = sb.toString();
            jdbc.update("INSERT INTO admin_recovery_code (id, user_id, code_hash) VALUES (?, ?, ?)",
                    UUID.randomUUID(), userId, passwordEncoder.encode(code));
            codes.add(code.substring(0, 5) + "-" + code.substring(5));
        }
        return codes;
    }

    // ---------------------------------------------------------------- sessions

    /** Every admin session that started before now is rejected on its next request (this one included). */
    @Transactional
    public void signOutAllSessions(String reason) {
        String why = requireReason(reason);
        long epoch = System.currentTimeMillis();
        settings.put(SESSION_EPOCH_KEY, true, String.valueOf(epoch), CurrentUser.idOrNull());
        auditLogService.record("ADMIN_SECURITY", null, "ADMIN_SESSIONS_REVOKED", why, null, Map.of("epochMillis", epoch));
    }

    public long sessionEpochMillis() {
        return settings.get(SESSION_EPOCH_KEY).map(r -> {
            try {
                return Long.parseLong(r.message());
            } catch (RuntimeException e) {
                return 0L;
            }
        }).orElse(0L);
    }

    public List<Map<String, Object>> adminLogins(int limit) {
        return jdbc.queryForList("""
                SELECT e.created_at, e.outcome, e.ip_address, e.user_agent, u.id AS user_id, u.name, u.phone_number,
                       u.admin_role, u.staff_role
                FROM user_login_event e JOIN app_user u ON u.id = e.user_id
                WHERE e.channel = 'ADMIN'
                ORDER BY e.created_at DESC LIMIT ?
                """, limit);
    }

    // ----------------------------------------------------------------

    private void clearTotp(User user) {
        user.setTotpEnabled(false);
        user.setTotpSecret(null);
        user.setTotpEnabledAt(null);
        userRepository.save(user);
        jdbc.update("DELETE FROM admin_recovery_code WHERE user_id = ?", user.getId());
    }

    private User self(UUID userId) {
        return userRepository.findById(userId).orElseThrow(() -> new ResourceNotFoundException("User not found"));
    }

    private static String requireReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new BadRequestException("A reason is required.");
        }
        return reason.trim();
    }
}
