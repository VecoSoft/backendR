package com.bdreview.platform.auth;

import com.bdreview.platform.accountlink.AccountLinkService;
import com.bdreview.platform.common.BadRequestException;
import com.bdreview.platform.common.ForbiddenException;
import com.bdreview.platform.common.PhoneNumberUtils;
import com.bdreview.platform.otp.OtpService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/**
 * Handles registration, phone+password login, password reset, and
 * token issuance/rotation per spec §5. Registration and password reset both
 * delegate phone-ownership proof to otp.OtpService; plain login needs no OTP.
 *
 * <p>Consumer and business-owner are two genuinely separate accounts (spec
 * update: two-account model, mirroring Yelp's yelp.com vs biz.yelp.com) — a
 * phone number can back at most one CONSUMER row and one BUSINESS_OWNER row
 * (see V17 migration's {@code UNIQUE(phone_number, role)}), optionally
 * paired via {@code accountlink.AccountLink} for a frictionless
 * {@link #switchAccount}.
 */
@Service
public class AuthService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int PASSWORD_MIN_LENGTH = 8;

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final JwtService jwtService;
    private final OtpService otpService;
    private final PasswordEncoder passwordEncoder;
    private final AccountLinkService accountLinkService;
    private final long accessTokenTtlMinutes;
    private final long refreshTokenTtlDays;

    public AuthService(UserRepository userRepository,
                        RefreshTokenRepository refreshTokenRepository,
                        JwtService jwtService,
                        OtpService otpService,
                        PasswordEncoder passwordEncoder,
                        AccountLinkService accountLinkService,
                        @Value("${app.jwt.access-token-ttl-minutes}") long accessTokenTtlMinutes,
                        @Value("${app.jwt.refresh-token-ttl-days}") long refreshTokenTtlDays) {
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.jwtService = jwtService;
        this.otpService = otpService;
        this.passwordEncoder = passwordEncoder;
        this.accountLinkService = accountLinkService;
        this.accessTokenTtlMinutes = accessTokenTtlMinutes;
        this.refreshTokenTtlDays = refreshTokenTtlDays;
    }

    /**
     * Creates a new account. Requires a phone number to have just cleared
     * OTP verification (spec §6) and a role other than ADMIN (§5: admin
     * accounts cannot be self-registered). Auto-logs-in on success. Scoped
     * by role, not just phone — a phone number already registered as
     * CONSUMER can still register a separate BUSINESS_OWNER account (and
     * vice versa); only a duplicate of the *same* role is rejected.
     */
    @Transactional
    public TokenPairDto register(String rawPhoneNumber, String code, String password, UserRole role, String name) {
        if (role == null || role == UserRole.ADMIN) {
            throw new BadRequestException("Invalid role for self-registration.");
        }
        if (name == null || name.isBlank()) {
            throw new BadRequestException("Name is required.");
        }
        String phone = PhoneNumberUtils.normalize(rawPhoneNumber);
        validatePassword(password);
        otpService.verifyCode(phone, code);

        if (userRepository.existsByPhoneNumberAndRole(phone, role)) {
            throw new BadRequestException("This phone number is already registered — please log in instead.");
        }

        User user = userRepository.save(User.builder()
                .phoneNumber(phone)
                .role(role)
                .otpVerified(true)
                .passwordHash(passwordEncoder.encode(password))
                .name(name.trim())
                .build());

        return issueNewTokenFamily(user.getId(), user.getRole());
    }

    /**
     * Ordinary phone+password login — no OTP involved. {@code context} picks
     * which account pool to resolve against for this phone number:
     * {@code BUSINESS_OWNER} logs into the business account only; anything
     * else (including {@code null}, the main site's default) resolves
     * against CONSUMER or ADMIN — mirroring Yelp's separate consumer vs
     * business login surfaces without a second endpoint.
     */
    @Transactional
    public TokenPairDto login(String rawPhoneNumber, String password, UserRole context) {
        String phone = PhoneNumberUtils.normalize(rawPhoneNumber);
        List<UserRole> candidateRoles = context == UserRole.BUSINESS_OWNER
                ? List.of(UserRole.BUSINESS_OWNER)
                : List.of(UserRole.CONSUMER, UserRole.ADMIN);

        User user = userRepository.findAllByPhoneNumberAndRoleIn(phone, candidateRoles).stream()
                .filter(u -> u.getPasswordHash() != null && passwordEncoder.matches(password, u.getPasswordHash()))
                .findFirst()
                .orElseThrow(() -> new BadRequestException("Invalid phone number or password."));

        return issueNewTokenFamily(user.getId(), user.getRole());
    }

    /**
     * Sets a new password after proving phone ownership via OTP, then
     * revokes all existing sessions (any refresh token issued under the old
     * password) and logs the user in with a fresh token family. {@code role}
     * disambiguates which of a phone number's (up to two) accounts to reset.
     */
    @Transactional
    public TokenPairDto resetPassword(String rawPhoneNumber, String code, String newPassword, UserRole role) {
        if (role == null || role == UserRole.ADMIN) {
            throw new BadRequestException("Invalid role for password reset.");
        }
        String phone = PhoneNumberUtils.normalize(rawPhoneNumber);
        validatePassword(newPassword);
        otpService.verifyCode(phone, code);

        User user = userRepository.findByPhoneNumberAndRole(phone, role)
                .orElseThrow(() -> new BadRequestException("No account found for this phone number."));

        user.setPasswordHash(passwordEncoder.encode(newPassword));
        userRepository.save(user);
        refreshTokenRepository.revokeAllForUser(user.getId());

        return issueNewTokenFamily(user.getId(), user.getRole());
    }

    /**
     * Switches from the caller's current account to its linked counterpart
     * (see {@code accountlink.AccountLink}) with no re-authentication — the
     * established link is the proof. Issues a brand-new token family for the
     * target account, exactly like a fresh login; the source account's own
     * session is left untouched (not revoked), since the person may still
     * have it open elsewhere.
     */
    @Transactional
    public TokenPairDto switchAccount(UUID currentUserId) {
        UUID partnerId = accountLinkService.partnerOf(currentUserId)
                .orElseThrow(() -> new BadRequestException(
                        "No linked account to switch to — create or link a business account first."));
        User partner = userRepository.findById(partnerId)
                .orElseThrow(() -> new ForbiddenException("Linked account no longer exists"));
        return issueNewTokenFamily(partner.getId(), partner.getRole());
    }

    /**
     * In-session shortcut for a logged-in CONSUMER account to create its
     * paired BUSINESS_OWNER account. The phone number is already
     * OTP-verified (that's how the caller got their consumer session in the
     * first place), so no fresh OTP is required here — just a new password +
     * display name. Auto-links the two accounts immediately (this one flow
     * already proves the same person controls both) and returns tokens for
     * the new business account, auto-switching into it.
     */
    @Transactional
    public TokenPairDto registerBusinessFromConsumer(UUID consumerUserId, String password, String name) {
        User consumer = userRepository.findById(consumerUserId)
                .orElseThrow(() -> new ForbiddenException("Account no longer exists"));
        if (consumer.getRole() != UserRole.CONSUMER) {
            throw new BadRequestException("Only a personal account can create a linked business account.");
        }
        if (accountLinkService.isLinked(consumerUserId)) {
            throw new BadRequestException("This account is already linked to a business account.");
        }
        if (userRepository.existsByPhoneNumberAndRole(consumer.getPhoneNumber(), UserRole.BUSINESS_OWNER)) {
            throw new BadRequestException(
                    "A business account already exists for this phone number — use the link-accounts flow instead.");
        }
        if (name == null || name.isBlank()) {
            throw new BadRequestException("Name is required.");
        }
        validatePassword(password);

        User business = userRepository.save(User.builder()
                .phoneNumber(consumer.getPhoneNumber())
                .role(UserRole.BUSINESS_OWNER)
                .otpVerified(true)
                .passwordHash(passwordEncoder.encode(password))
                .name(name.trim())
                .build());

        accountLinkService.link(consumerUserId, business.getId());

        return issueNewTokenFamily(business.getId(), business.getRole());
    }

    /**
     * Links the caller's current account with the opposite-role account
     * already registered under the same phone number (the case where both
     * were created independently, not via {@link #registerBusinessFromConsumer}).
     * Requires a fresh OTP to the shared phone as proof, since — unlike the
     * in-session shortcut above — this flow doesn't otherwise establish that
     * the caller controls the other account too.
     */
    @Transactional
    public void linkAccounts(UUID currentUserId, String code) {
        User current = userRepository.findById(currentUserId)
                .orElseThrow(() -> new ForbiddenException("Account no longer exists"));
        UserRole otherRole;
        if (current.getRole() == UserRole.CONSUMER) {
            otherRole = UserRole.BUSINESS_OWNER;
        } else if (current.getRole() == UserRole.BUSINESS_OWNER) {
            otherRole = UserRole.CONSUMER;
        } else {
            throw new BadRequestException("Admin accounts cannot be linked.");
        }

        User other = userRepository.findByPhoneNumberAndRole(current.getPhoneNumber(), otherRole)
                .orElseThrow(() -> new BadRequestException("No matching account found for this phone number."));

        otpService.verifyCode(current.getPhoneNumber(), code);

        UUID consumerId = current.getRole() == UserRole.CONSUMER ? current.getId() : other.getId();
        UUID businessId = current.getRole() == UserRole.BUSINESS_OWNER ? current.getId() : other.getId();
        accountLinkService.link(consumerId, businessId);
    }

    private void validatePassword(String password) {
        if (password == null || password.length() < PASSWORD_MIN_LENGTH) {
            throw new BadRequestException("Password must be at least " + PASSWORD_MIN_LENGTH + " characters.");
        }
    }

    private TokenPairDto issueNewTokenFamily(UUID userId, UserRole role) {
        UUID familyId = UUID.randomUUID();
        return issueToken(userId, role, familyId);
    }

    private TokenPairDto issueToken(UUID userId, UserRole role, UUID familyId) {
        String accessToken = jwtService.generateAccessToken(userId, role);
        String refreshTokenPlain = randomToken();

        refreshTokenRepository.save(RefreshToken.builder()
                .userId(userId)
                .familyId(familyId)
                .tokenHash(hash(refreshTokenPlain))
                .expiresAt(Instant.now().plus(refreshTokenTtlDays, ChronoUnit.DAYS))
                .build());

        return new TokenPairDto(accessToken, refreshTokenPlain, accessTokenTtlMinutes * 60);
    }

    /**
     * §5 rotation + reuse detection: presenting an already-rotated-out or
     * revoked refresh token is treated as a theft signal — the entire token
     * family is revoked and the caller must re-login from scratch.
     */
    @Transactional
    public TokenPairDto refresh(String refreshTokenPlain) {
        String hash = hash(refreshTokenPlain);

        if (refreshTokenRepository.findReusedToken(hash).isPresent()) {
            RefreshToken reused = refreshTokenRepository.findByTokenHash(hash).orElseThrow();
            refreshTokenRepository.revokeFamily(reused.getFamilyId());
            throw new ForbiddenException("Refresh token reuse detected — please log in again.");
        }

        RefreshToken current = refreshTokenRepository.findByTokenHash(hash)
                .orElseThrow(() -> new ForbiddenException("Invalid refresh token"));

        if (!current.isUsable()) {
            throw new ForbiddenException("Refresh token expired or revoked — please log in again.");
        }

        refreshTokenRepository.markRotated(current.getId(), Instant.now());

        User user = userRepository.findById(current.getUserId())
                .orElseThrow(() -> new ForbiddenException("User no longer exists"));

        return issueToken(user.getId(), user.getRole(), current.getFamilyId());
    }

    /** Logout or any caller-detected suspicious activity immediately invalidates all refresh tokens. */
    @Transactional
    public void logout(UUID userId) {
        refreshTokenRepository.revokeAllForUser(userId);
    }

    private static String randomToken() {
        byte[] bytes = new byte[48];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String hash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(value.getBytes());
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hashed);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
