package com.bdreview.platform.auth;

import com.bdreview.platform.accountcontrol.AccountControlService;
import com.bdreview.platform.accountlink.AccountLinkService;
import com.bdreview.platform.common.BadRequestException;
import com.bdreview.platform.common.CodedException;
import com.bdreview.platform.common.ForbiddenException;
import com.bdreview.platform.common.RedisRateLimiter;
import com.bdreview.platform.email.EmailService;
import com.bdreview.platform.features.FeatureFlagService;
import com.bdreview.platform.features.PlatformFeature;
import com.bdreview.platform.notification.NotificationTemplateService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * User sign-in for the app (V70): Google Sign-In and e-mail + password, with e-mail-verified
 * sign-up and forgot-password by e-mail code; plus token issuance/rotation (spec §5). The admin
 * panel's phone + password + TOTP login is separate and unchanged (admin.config).
 *
 * <p>Consumer and business-owner remain two separate accounts linked by {@code accountlink}. The
 * e-mail belongs to the personal (CONSUMER) account; logging in with {@code context=BUSINESS_OWNER}
 * opens its linked business account. Phone + OTP login was removed in V70; phone numbers
 * stay only on admin-panel accounts and on old rows.
 */
@Service
public class AuthService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final DateTimeFormatter NOTICE_TIME =
            DateTimeFormatter.ofPattern("d MMM yyyy, h:mm a", Locale.ENGLISH).withZone(ZoneId.of("Asia/Dhaka"));

    /** Answer to sign-up and "resend code": the app shows the code-entry screen next. */
    public record VerificationPending(String status, String email, long resendAfterSeconds) {
    }

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final JwtService jwtService;
    private final PasswordEncoder passwordEncoder;
    private final AccountLinkService accountLinkService;
    private final AccountControlService accountControl;
    private final CredentialPolicy credentials;
    private final GoogleIdTokenVerifier googleVerifier;
    private final AuthEmailCodeService codes;
    private final EmailService emailService;
    private final RedisRateLimiter rateLimiter;
    private final FeatureFlagService features;
    private final TransactionTemplate independentTx;
    /** Compared against when the e-mail is unknown, so a miss costs the same time as a wrong password. */
    private final String timingDummyHash;
    private final long accessTokenTtlMinutes;
    private final long refreshTokenTtlDays;
    private final int maxFailures;
    private final Duration lockDuration;
    private final int loginPerIpPer15Min;

    public AuthService(UserRepository userRepository,
                       RefreshTokenRepository refreshTokenRepository,
                       JwtService jwtService,
                       PasswordEncoder passwordEncoder,
                       AccountLinkService accountLinkService,
                       AccountControlService accountControl,
                       CredentialPolicy credentials,
                       GoogleIdTokenVerifier googleVerifier,
                       AuthEmailCodeService codes,
                       EmailService emailService,
                       RedisRateLimiter rateLimiter,
                       FeatureFlagService features,
                       PlatformTransactionManager transactionManager,
                       @Value("${app.jwt.access-token-ttl-minutes}") long accessTokenTtlMinutes,
                       @Value("${app.jwt.refresh-token-ttl-days}") long refreshTokenTtlDays,
                       @Value("${app.auth.lockout.max-failures}") int maxFailures,
                       @Value("${app.auth.lockout.lock-minutes}") long lockMinutes,
                       @Value("${app.auth.login-per-ip-per-15-min}") int loginPerIpPer15Min) {
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.jwtService = jwtService;
        this.passwordEncoder = passwordEncoder;
        this.accountLinkService = accountLinkService;
        this.accountControl = accountControl;
        this.credentials = credentials;
        this.googleVerifier = googleVerifier;
        this.codes = codes;
        this.emailService = emailService;
        this.rateLimiter = rateLimiter;
        this.features = features;
        this.independentTx = new TransactionTemplate(transactionManager);
        this.independentTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.timingDummyHash = passwordEncoder.encode(randomToken().substring(0, 32));
        this.accessTokenTtlMinutes = accessTokenTtlMinutes;
        this.refreshTokenTtlDays = refreshTokenTtlDays;
        this.maxFailures = maxFailures;
        this.lockDuration = Duration.ofMinutes(lockMinutes);
        this.loginPerIpPer15Min = loginPerIpPer15Min;
    }

    // =====================================================================================
    // Google Sign-In
    // =====================================================================================

    /**
     * "Continue with Google": verifies the ID token server-side, then finds the account by Google id,
     * else by e-mail (auto-link: Google has verified the address), else creates one.
     */
    @Transactional
    public TokenPairDto google(String idToken, UserRole context) {
        GoogleIdTokenVerifier.GoogleIdentity google = googleVerifier.verify(idToken);
        User user = userRepository.findByGoogleSub(google.sub()).orElse(null);
        if (user == null) {
            user = userRepository.findByEmailNormalized(google.email()).orElse(null);
            if (user != null) {
                linkGoogle(user, google);
            } else {
                if (!features.isEnabled(PlatformFeature.NEW_SIGNUPS)) {
                    throw new CodedException(HttpStatus.FORBIDDEN, "SIGNUPS_CLOSED",
                            "New sign-ups are paused right now. Please try again later.");
                }
                User created = createGoogleAccount(google);
                // The login event is written in its own transaction, which can't see this not-yet-committed
                // account: record it after commit. (A brand-new account has no restriction to check.)
                org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                        new org.springframework.transaction.support.TransactionSynchronization() {
                            @Override
                            public void afterCommit() {
                                accountControl.recordLogin(created.getId(), "APP", "SUCCESS");
                            }
                        });
                return issueFor(created, context);
            }
        }
        accountControl.checkLogin(user.getId(), "APP");
        return issueFor(user, context);
    }

    /**
     * Attaches a verified Google identity to an existing account. If that account was an e-mail
     * sign-up nobody ever verified, its password is dropped: whoever chose it never proved they own
     * the address, so it must not keep working once the real owner signs in with Google.
     */
    private void linkGoogle(User user, GoogleIdTokenVerifier.GoogleIdentity google) {
        if (user.getGoogleSub() != null && !user.getGoogleSub().equals(google.sub())) {
            throw new CodedException(HttpStatus.CONFLICT, "GOOGLE_ACCOUNT_MISMATCH",
                    "This e-mail is already linked to a different Google account.");
        }
        if (user.getAccountStatus() == AccountStatus.EMAIL_UNVERIFIED) {
            user.setPasswordHash(null);
            user.setAuthProvider(AuthProvider.GOOGLE);
            user.setAccountStatus(AccountStatus.ACTIVE);
        } else {
            user.setAuthProvider(user.getAuthProvider().withGoogle());
        }
        user.setGoogleSub(google.sub());
        if (user.getEmailVerifiedAt() == null) {
            user.setEmailVerifiedAt(Instant.now());
        }
        if (user.getName() == null || user.getName().isBlank()) {
            user.setName(google.name());
        }
        if (user.getProfilePhotoUrl() == null && google.picture() != null) {
            user.setProfilePhotoUrl(google.picture());
        }
        userRepository.save(user);
    }

    private User createGoogleAccount(GoogleIdTokenVerifier.GoogleIdentity google) {
        String name = google.name() == null || google.name().isBlank()
                ? google.email().substring(0, google.email().indexOf('@')) : google.name().trim();
        try {
            return userRepository.saveAndFlush(User.builder()
                    .role(UserRole.CONSUMER)
                    .email(google.email())
                    .emailVerifiedAt(Instant.now())
                    .googleSub(google.sub())
                    .authProvider(AuthProvider.GOOGLE)
                    .accountStatus(AccountStatus.ACTIVE)
                    .name(name.length() > 120 ? name.substring(0, 120) : name)
                    .profilePhotoUrl(google.picture())
                    .build());
        } catch (DataIntegrityViolationException e) {
            // A parallel request created it first: sign that one in instead.
            return userRepository.findByGoogleSub(google.sub()).orElseThrow(() -> e);
        }
    }

    // =====================================================================================
    // E-mail + password
    // =====================================================================================

    /**
     * Sign-up: the account starts as EMAIL_UNVERIFIED and a 6-digit code is e-mailed. Signing up
     * again with the same still-unverified address updates the name/password and sends a new code.
     */
    @Transactional
    public VerificationPending register(String rawName, String rawEmail, String password, String confirmPassword,
                                       String language, String ip) {
        String name = rawName == null ? "" : rawName.trim();
        if (name.isEmpty() || name.length() > 120) {
            throw new CodedException(HttpStatus.BAD_REQUEST, "NAME_REQUIRED", "Enter your name.");
        }
        String email = credentials.requireValidEmail(rawEmail);
        credentials.requireValidPassword(password, confirmPassword, email);
        String locale = "bn".equals(language) ? "bn" : "en";

        User user = userRepository.findByEmailNormalized(email).orElse(null);
        if (user != null && user.getAccountStatus() != AccountStatus.EMAIL_UNVERIFIED) {
            throw emailTaken();
        }
        if (user == null) {
            user = User.builder()
                    .role(UserRole.CONSUMER)
                    .email(email)
                    .authProvider(AuthProvider.PASSWORD)
                    .accountStatus(AccountStatus.EMAIL_UNVERIFIED)
                    .build();
        }
        user.setName(name);
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setPreferredLanguage(locale);
        try {
            user = userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            throw emailTaken();
        }
        return sendVerificationCode(user, ip);
    }

    private static CodedException emailTaken() {
        return new CodedException(HttpStatus.CONFLICT, "EMAIL_TAKEN",
                "An account with this e-mail already exists. Log in, or use \"Forgot password\".");
    }

    /** Re-sends the sign-up code. Same answer whether or not the address has a pending sign-up. */
    @Transactional
    public VerificationPending resendVerification(String rawEmail, String ip) {
        String email = CredentialPolicy.normalizeEmail(rawEmail);
        User user = userRepository.findByEmailNormalized(email)
                .filter(u -> u.getAccountStatus() == AccountStatus.EMAIL_UNVERIFIED)
                .orElse(null);
        if (user == null) {
            return new VerificationPending("VERIFICATION_REQUIRED", email, codes.resendCooldownSeconds());
        }
        return sendVerificationCode(user, ip);
    }

    private VerificationPending sendVerificationCode(User user, String ip) {
        AuthEmailCodeService.Issued issued = codes.issue(user.getId(), user.getEmail(), AuthEmailCode.Purpose.VERIFY_EMAIL, ip);
        if (issued.sent()) {
            emailService.sendTemplate(NotificationTemplateService.Key.EMAIL_VERIFY_CODE, user.getPreferredLanguage(),
                    user.getEmail(), Map.of("name", nameOf(user), "code", issued.code(),
                            "minutes", codes.ttlMinutes(AuthEmailCode.Purpose.VERIFY_EMAIL)));
        }
        return new VerificationPending("VERIFICATION_REQUIRED", user.getEmail(), issued.retryAfterSeconds());
    }

    /** The sign-up code: activates the account and signs it in. */
    @Transactional
    public TokenPairDto verifyEmail(String rawEmail, String code) {
        String email = CredentialPolicy.normalizeEmail(rawEmail);
        User user = userRepository.findByEmailNormalized(email)
                .filter(u -> u.getAccountStatus() == AccountStatus.EMAIL_UNVERIFIED)
                .orElseThrow(AuthService::codeExpired);
        UUID owner = codes.consume(email, AuthEmailCode.Purpose.VERIFY_EMAIL, code);
        if (!owner.equals(user.getId())) {
            throw codeExpired();
        }
        user.setAccountStatus(AccountStatus.ACTIVE);
        user.setEmailVerifiedAt(Instant.now());
        userRepository.save(user);
        accountControl.checkLogin(user.getId(), "APP");
        return issueFor(user, null);
    }

    private static CodedException codeExpired() {
        return new CodedException(HttpStatus.BAD_REQUEST, "CODE_EXPIRED", "This code has expired. Request a new one.");
    }

    /**
     * E-mail + password login. Wrong e-mail and wrong password get the same answer. Five wrong
     * passwords in a row lock password login for that account for 15 minutes; each client IP is
     * also limited (Redis). An account that only uses Google is told to continue with Google.
     */
    @Transactional(noRollbackFor = CodedException.class)
    public TokenPairDto login(String rawEmail, String password, UserRole context, String ip) {
        if (ip != null && !rateLimiter.tryAcquire("login-ip:" + ip, loginPerIpPer15Min, Duration.ofMinutes(15))) {
            throw new CodedException(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_ATTEMPTS",
                    "Too many login attempts from this network. Please wait a few minutes.");
        }
        String email = CredentialPolicy.normalizeEmail(rawEmail);
        User user = userRepository.findByEmailNormalized(email).orElse(null);
        // BCrypt only takes 72 bytes (and refuses more); no stored password can be longer than that.
        boolean tooLong = password != null
                && password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > CredentialPolicy.MAX_PASSWORD_BYTES;
        if (user == null || password == null || tooLong) {
            passwordEncoder.matches(tooLong || password == null ? "" : password, timingDummyHash);
            throw invalidCredentials();
        }
        Instant now = Instant.now();
        if (user.getLoginLockedUntil() != null && user.getLoginLockedUntil().isAfter(now)) {
            accountControl.recordLogin(user.getId(), "APP", "LOCKED");
            throw locked(user.getLoginLockedUntil());
        }
        if (user.getPasswordHash() == null) {
            if (user.getGoogleSub() != null) {
                throw new CodedException(HttpStatus.BAD_REQUEST, "USE_GOOGLE_SIGN_IN",
                        "This account signs in with Google. Use \"Continue with Google\", or set a password with \"Forgot password\".");
            }
            throw invalidCredentials();
        }
        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            Instant lockedUntil = recordFailure(user.getId());
            if (lockedUntil != null) {
                throw locked(lockedUntil);
            }
            throw invalidCredentials();
        }
        if (user.getAccountStatus() == AccountStatus.EMAIL_UNVERIFIED) {
            throw new CodedException(HttpStatus.FORBIDDEN, "EMAIL_NOT_VERIFIED",
                    "Verify your e-mail first: enter the code we sent, or ask for a new one.",
                    Map.of("email", user.getEmail(), "canResend", true));
        }
        user.setFailedLoginCount(0);
        user.setLoginLockedUntil(null);
        if (passwordEncoder.upgradeEncoding(user.getPasswordHash())) {
            user.setPasswordHash(passwordEncoder.encode(password)); // an older, cheaper BCrypt cost
        }
        userRepository.save(user);
        // V63: a suspended/banned account is told why and until when (only after the password matched).
        accountControl.checkLogin(user.getId(), "APP");
        return issueFor(user, context);
    }

    /**
     * Counts a wrong password in its own transaction (the login call itself fails) and returns the
     * lock end when this failure locked the account.
     */
    private Instant recordFailure(UUID userId) {
        Instant lockedUntil = independentTx.execute(s -> {
            User u = userRepository.findById(userId).orElseThrow();
            int failures = u.getFailedLoginCount() + 1;
            if (failures >= maxFailures) {
                u.setFailedLoginCount(0);
                u.setLoginLockedUntil(Instant.now().plus(lockDuration));
            } else {
                u.setFailedLoginCount(failures);
            }
            userRepository.save(u);
            return failures >= maxFailures ? u.getLoginLockedUntil() : null;
        });
        accountControl.recordLogin(userId, "APP", lockedUntil != null ? "LOCKED" : "FAILED");
        return lockedUntil;
    }

    private static CodedException invalidCredentials() {
        return new CodedException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Email or password is incorrect");
    }

    private static CodedException locked(Instant until) {
        long seconds = Math.max(1, Duration.between(Instant.now(), until).toSeconds());
        return new CodedException(HttpStatus.TOO_MANY_REQUESTS, "ACCOUNT_LOCKED",
                "Too many wrong passwords. Try again in " + Math.max(1, (seconds + 59) / 60) + " minutes, or reset your password.",
                Map.of("retryAfterSeconds", seconds));
    }

    // =====================================================================================
    // Forgot / reset password
    // =====================================================================================

    /**
     * Always the same answer, whether or not the address has an account (no account enumeration).
     * The per-IP cap applies to every request, so it says nothing about any one address; the
     * per-address limits (cooldown, 5 an hour) silently skip sending instead of answering differently.
     */
    @Transactional
    public void forgotPassword(String rawEmail, String ip) {
        if (ip != null && !rateLimiter.tryAcquire("forgot-ip:" + ip, 5, Duration.ofHours(1))) {
            throw new CodedException(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_REQUESTS",
                    "Too many requests from this network. Please try again later.");
        }
        String email = CredentialPolicy.normalizeEmail(rawEmail);
        User user = userRepository.findByEmailNormalized(email).orElse(null);
        // Admin accounts sign in to the admin panel only; their password is managed there.
        if (user == null || user.getRole() == UserRole.ADMIN) {
            return;
        }
        AuthEmailCodeService.Issued issued = codes.issue(user.getId(), email, AuthEmailCode.Purpose.RESET_PASSWORD, ip);
        if (issued.sent()) {
            emailService.sendTemplate(NotificationTemplateService.Key.EMAIL_RESET_CODE, user.getPreferredLanguage(),
                    email, Map.of("name", nameOf(user), "code", issued.code(),
                            "minutes", codes.ttlMinutes(AuthEmailCode.Purpose.RESET_PASSWORD)));
        }
    }

    /**
     * Sets a new password with the e-mailed code, signs out every device (all refresh tokens of the
     * account and its linked counterpart) and e-mails a "your password was changed" notice. The code
     * also proves the address, so an unverified sign-up becomes active.
     */
    @Transactional
    public void resetPassword(String rawEmail, String code, String password, String confirmPassword) {
        String email = CredentialPolicy.normalizeEmail(rawEmail);
        credentials.requireValidPassword(password, confirmPassword, email);
        User user = userRepository.findByEmailNormalized(email)
                .filter(u -> u.getRole() != UserRole.ADMIN)
                .orElseThrow(AuthService::codeExpired);
        UUID owner = codes.consume(email, AuthEmailCode.Purpose.RESET_PASSWORD, code);
        if (!owner.equals(user.getId())) {
            throw codeExpired();
        }
        Instant now = Instant.now();
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setAuthProvider(user.getAuthProvider().withPassword());
        user.setAccountStatus(AccountStatus.ACTIVE);
        if (user.getEmailVerifiedAt() == null) {
            user.setEmailVerifiedAt(now);
        }
        user.setFailedLoginCount(0);
        user.setLoginLockedUntil(null);
        userRepository.save(user);
        revokeEverywhere(user.getId());
        emailService.sendTemplate(NotificationTemplateService.Key.EMAIL_PASSWORD_CHANGED, user.getPreferredLanguage(),
                email, Map.of("name", nameOf(user), "email", email, "time", NOTICE_TIME.format(now)));
    }

    /** All refresh tokens of the account and of its linked counterpart (the same person). */
    private void revokeEverywhere(UUID userId) {
        refreshTokenRepository.revokeAllForUser(userId);
        accountLinkService.partnerOf(userId).ifPresent(refreshTokenRepository::revokeAllForUser);
    }

    private static String nameOf(User user) {
        return user.getName() == null || user.getName().isBlank() ? "there" : user.getName();
    }

    // =====================================================================================
    // Signed-in account: add an e-mail (phone-only accounts from before V70) or link Google
    // =====================================================================================

    /**
     * A signed-in phone-only account adds an e-mail + password: the password is set now (the caller
     * is already signed in), the e-mail only once the code sent to it is entered.
     */
    @Transactional
    public VerificationPending startAddEmail(UUID userId, String rawEmail, String password, String confirmPassword, String ip) {
        User user = userRepository.findById(userId).orElseThrow(() -> new ForbiddenException("Account no longer exists"));
        if (user.getEmail() != null && user.getEmailVerifiedAt() != null) {
            throw new CodedException(HttpStatus.CONFLICT, "EMAIL_ALREADY_SET", "This account already has an e-mail address.");
        }
        String email = credentials.requireValidEmail(rawEmail);
        credentials.requireValidPassword(password, confirmPassword, email);
        userRepository.findByEmailNormalized(email).filter(other -> !other.getId().equals(userId)).ifPresent(other -> {
            throw new CodedException(HttpStatus.CONFLICT, "EMAIL_TAKEN", "Another account already uses this e-mail.");
        });
        user.setPasswordHash(passwordEncoder.encode(password));
        userRepository.save(user);
        AuthEmailCodeService.Issued issued = codes.issue(userId, email, AuthEmailCode.Purpose.ADD_EMAIL, ip);
        if (issued.result() == AuthEmailCodeService.IssueResult.LIMITED) {
            throw new CodedException(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_REQUESTS", "Too many codes requested. Try again in an hour.");
        }
        if (issued.sent()) {
            emailService.sendTemplate(NotificationTemplateService.Key.EMAIL_VERIFY_CODE, user.getPreferredLanguage(),
                    email, Map.of("name", nameOf(user), "code", issued.code(),
                            "minutes", codes.ttlMinutes(AuthEmailCode.Purpose.ADD_EMAIL)));
        }
        return new VerificationPending("VERIFICATION_REQUIRED", email, issued.retryAfterSeconds());
    }

    @Transactional
    public void confirmAddEmail(UUID userId, String rawEmail, String code) {
        String email = CredentialPolicy.normalizeEmail(rawEmail);
        UUID owner = codes.consume(email, AuthEmailCode.Purpose.ADD_EMAIL, code);
        if (!owner.equals(userId)) {
            throw codeExpired();
        }
        User user = userRepository.findById(userId).orElseThrow(() -> new ForbiddenException("Account no longer exists"));
        userRepository.findByEmailNormalized(email).filter(other -> !other.getId().equals(userId)).ifPresent(other -> {
            throw new CodedException(HttpStatus.CONFLICT, "EMAIL_TAKEN", "Another account already uses this e-mail.");
        });
        user.setEmail(email);
        user.setEmailVerifiedAt(Instant.now());
        user.setAuthProvider(user.getAuthProvider().withPassword());
        user.setAccountStatus(AccountStatus.ACTIVE);
        userRepository.save(user);
    }

    /** Links Google to the signed-in account (also how a phone-only account gets an e-mail in one tap). */
    @Transactional
    public void linkGoogleToCurrent(UUID userId, String idToken) {
        GoogleIdTokenVerifier.GoogleIdentity google = googleVerifier.verify(idToken);
        User user = userRepository.findById(userId).orElseThrow(() -> new ForbiddenException("Account no longer exists"));
        userRepository.findByGoogleSub(google.sub()).filter(other -> !other.getId().equals(userId)).ifPresent(other -> {
            throw new CodedException(HttpStatus.CONFLICT, "GOOGLE_ACCOUNT_IN_USE",
                    "This Google account is already used by another Jachai account.");
        });
        if (user.getGoogleSub() != null && !user.getGoogleSub().equals(google.sub())) {
            throw new CodedException(HttpStatus.CONFLICT, "GOOGLE_ACCOUNT_MISMATCH", "A different Google account is already linked.");
        }
        if (user.getEmail() == null || user.getEmailVerifiedAt() == null) {
            userRepository.findByEmailNormalized(google.email()).filter(other -> !other.getId().equals(userId)).ifPresent(other -> {
                throw new CodedException(HttpStatus.CONFLICT, "EMAIL_TAKEN", "Another account already uses this Google e-mail.");
            });
            user.setEmail(google.email());
            user.setEmailVerifiedAt(Instant.now());
        }
        user.setGoogleSub(google.sub());
        user.setAuthProvider(user.getAuthProvider() == AuthProvider.PHONE
                ? (user.getPasswordHash() != null ? AuthProvider.BOTH : AuthProvider.GOOGLE)
                : user.getAuthProvider().withGoogle());
        user.setAccountStatus(AccountStatus.ACTIVE);
        userRepository.save(user);
    }

    // =====================================================================================
    // Tokens and the two-account switch
    // =====================================================================================

    /**
     * Tokens for the account the person asked for: {@code BUSINESS_OWNER} context opens the linked
     * business account (or the account itself when it is one); otherwise the account itself.
     */
    private TokenPairDto issueFor(User user, UserRole context) {
        if (context == UserRole.BUSINESS_OWNER && user.getRole() != UserRole.BUSINESS_OWNER) {
            UUID partnerId = accountLinkService.partnerOf(user.getId())
                    .orElseThrow(() -> new CodedException(HttpStatus.BAD_REQUEST, "NO_BUSINESS_ACCOUNT",
                            "This account has no business account yet. Log in, then create one from your profile."));
            User partner = userRepository.findById(partnerId)
                    .orElseThrow(() -> new ForbiddenException("Linked account no longer exists"));
            accountControl.assertNotRestricted(partner.getId());
            return issueNewTokenFamily(partner.getId(), partner.getRole());
        }
        return issueNewTokenFamily(user.getId(), user.getRole());
    }

    /**
     * Switches from the caller's current account to its linked counterpart (see
     * {@code accountlink.AccountLink}) with no re-authentication: the established link is the proof.
     */
    @Transactional
    public TokenPairDto switchAccount(UUID currentUserId) {
        UUID partnerId = accountLinkService.partnerOf(currentUserId)
                .orElseThrow(() -> new BadRequestException(
                        "No linked account to switch to — create or link a business account first."));
        User partner = userRepository.findById(partnerId)
                .orElseThrow(() -> new ForbiddenException("Linked account no longer exists"));
        accountControl.assertNotRestricted(partner.getId());
        return issueNewTokenFamily(partner.getId(), partner.getRole());
    }

    /**
     * A signed-in personal account creates its paired business account. The business account has no
     * e-mail or password of its own: it is opened through the personal account (account switch, or
     * login with the business context).
     */
    @Transactional
    public TokenPairDto registerBusinessFromConsumer(UUID consumerUserId, String name) {
        User consumer = userRepository.findById(consumerUserId)
                .orElseThrow(() -> new ForbiddenException("Account no longer exists"));
        if (consumer.getRole() != UserRole.CONSUMER) {
            throw new BadRequestException("Only a personal account can create a linked business account.");
        }
        if (accountLinkService.isLinked(consumerUserId)) {
            throw new BadRequestException("This account is already linked to a business account.");
        }
        if (consumer.getPhoneNumber() != null
                && userRepository.existsByPhoneNumberAndRole(consumer.getPhoneNumber(), UserRole.BUSINESS_OWNER)) {
            throw new BadRequestException(
                    "A business account already exists for this phone number — contact support to link it.");
        }
        if (name == null || name.isBlank()) {
            throw new BadRequestException("Name is required.");
        }
        User business = userRepository.save(User.builder()
                .phoneNumber(consumer.getPhoneNumber())
                .role(UserRole.BUSINESS_OWNER)
                .otpVerified(consumer.isOtpVerified())
                .authProvider(consumer.getAuthProvider())
                .name(name.trim())
                .build());
        accountLinkService.link(consumerUserId, business.getId());
        return issueNewTokenFamily(business.getId(), business.getRole());
    }

    private TokenPairDto issueNewTokenFamily(UUID userId, UserRole role) {
        return issueToken(userId, role, UUID.randomUUID());
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
     * §5 rotation + reuse detection: presenting an already-rotated-out or revoked refresh token is
     * treated as a theft signal — the entire token family is revoked and the caller must log in again.
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
        accountControl.assertNotRestricted(user.getId());
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
