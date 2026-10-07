package com.bdreview.platform.auth;

import com.bdreview.platform.common.CodedException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Six-digit e-mail codes (V70): issue with resend cooldown and hourly caps per address and per IP,
 * check with an attempt limit. Only the HMAC of a code is stored (keyed from the JWT secret), so a
 * database leak doesn't reveal live codes. Only the newest code for an address and purpose counts.
 */
@Service
public class AuthEmailCodeService {

    private static final SecureRandom RANDOM = new SecureRandom();

    public enum IssueResult { SENT, COOLDOWN, LIMITED }

    /** A freshly issued code: the plain value goes into the e-mail, never anywhere else. */
    public record Issued(IssueResult result, String code, long retryAfterSeconds) {
        public boolean sent() {
            return result == IssueResult.SENT;
        }
    }

    private final AuthEmailCodeRepository repository;
    private final TransactionTemplate independentTx;
    private final byte[] hmacKey;
    private final int length;
    private final Duration verifyTtl;
    private final Duration resetTtl;
    private final int maxAttempts;
    private final Duration resendCooldown;
    private final int maxPerHour;
    private final int maxPerHourPerIp;
    private final int maxResetPerHourPerIp;

    public AuthEmailCodeService(AuthEmailCodeRepository repository, PlatformTransactionManager transactionManager,
                                @Value("${app.jwt.secret}") String jwtSecret,
                                @Value("${app.auth.codes.length}") int length,
                                @Value("${app.auth.codes.verify-ttl-minutes}") long verifyTtlMinutes,
                                @Value("${app.auth.codes.reset-ttl-minutes}") long resetTtlMinutes,
                                @Value("${app.auth.codes.max-attempts}") int maxAttempts,
                                @Value("${app.auth.codes.resend-cooldown-seconds}") long resendCooldownSeconds,
                                @Value("${app.auth.codes.max-per-hour}") int maxPerHour,
                                @Value("${app.auth.codes.max-per-hour-per-ip}") int maxPerHourPerIp,
                                @Value("${app.auth.codes.max-reset-per-hour-per-ip}") int maxResetPerHourPerIp) {
        this.repository = repository;
        this.independentTx = new TransactionTemplate(transactionManager);
        this.independentTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        try {
            this.hmacKey = MessageDigest.getInstance("SHA-256").digest(("auth-email-code:" + jwtSecret).getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        this.length = length;
        this.verifyTtl = Duration.ofMinutes(verifyTtlMinutes);
        this.resetTtl = Duration.ofMinutes(resetTtlMinutes);
        this.maxAttempts = maxAttempts;
        this.resendCooldown = Duration.ofSeconds(resendCooldownSeconds);
        this.maxPerHour = maxPerHour;
        this.maxPerHourPerIp = maxPerHourPerIp;
        this.maxResetPerHourPerIp = maxResetPerHourPerIp;
    }

    public long ttlMinutes(AuthEmailCode.Purpose purpose) {
        return ttl(purpose).toMinutes();
    }

    public long resendCooldownSeconds() {
        return resendCooldown.toSeconds();
    }

    /**
     * Creates a code for {@code email} unless the cooldown or an hourly cap applies (then nothing is
     * created and the result says why). Earlier unused codes for the same purpose stop working.
     */
    public Issued issue(UUID userId, String email, AuthEmailCode.Purpose purpose, String ip) {
        Instant now = Instant.now();
        Optional<AuthEmailCode> latest = repository.findFirstByEmailAndPurposeOrderByCreatedAtDesc(email, purpose);
        if (latest.isPresent() && latest.get().getCreatedAt().isAfter(now.minus(resendCooldown))) {
            long wait = Duration.between(now, latest.get().getCreatedAt().plus(resendCooldown)).toSeconds() + 1;
            return new Issued(IssueResult.COOLDOWN, null, wait);
        }
        Instant hourAgo = now.minus(Duration.ofHours(1));
        if (repository.countByEmailAndPurposeAndCreatedAtAfter(email, purpose, hourAgo) >= maxPerHour
                || (ip != null && repository.countByRequestIpAndPurposeAndCreatedAtAfter(ip, purpose, hourAgo)
                        >= (purpose == AuthEmailCode.Purpose.RESET_PASSWORD ? maxResetPerHourPerIp : maxPerHourPerIp))) {
            return new Issued(IssueResult.LIMITED, null, 3600);
        }
        repository.consumeAllFor(userId, purpose, now);
        String code = randomCode();
        repository.save(AuthEmailCode.builder()
                .purpose(purpose)
                .email(email)
                .userId(userId)
                .codeHash(hash(purpose, email, code))
                .expiresAt(now.plus(ttl(purpose)))
                .requestIp(ip)
                .build());
        return new Issued(IssueResult.SENT, code, resendCooldown.toSeconds());
    }

    /**
     * Checks {@code code} against the newest code for this address and purpose, and marks it used.
     * Returns the account it was issued to. A wrong code counts an attempt (committed even though
     * the caller's transaction rolls back); after the last attempt the code is dead.
     */
    public UUID consume(String email, AuthEmailCode.Purpose purpose, String code) {
        AuthEmailCode latest = repository.findFirstByEmailAndPurposeOrderByCreatedAtDesc(email, purpose)
                .filter(c -> c.getConsumedAt() == null)
                .orElseThrow(AuthEmailCodeService::expired);
        if (!latest.getExpiresAt().isAfter(Instant.now())) {
            throw expired();
        }
        if (latest.getAttempts() >= maxAttempts) {
            throw tooManyAttempts();
        }
        String given = code == null ? "" : code.replaceAll("\\s", "");
        if (!MessageDigest.isEqual(hash(purpose, email, given).getBytes(StandardCharsets.US_ASCII),
                latest.getCodeHash().getBytes(StandardCharsets.US_ASCII))) {
            int attempts = independentTx.execute(s -> {
                AuthEmailCode c = repository.findById(latest.getId()).orElseThrow();
                c.setAttempts(c.getAttempts() + 1);
                return repository.save(c).getAttempts();
            });
            int left = Math.max(0, maxAttempts - attempts);
            if (left == 0) {
                throw tooManyAttempts();
            }
            throw new CodedException(HttpStatus.BAD_REQUEST, "CODE_INVALID", "That code isn't right.",
                    Map.of("attemptsLeft", left));
        }
        latest.setConsumedAt(Instant.now());
        repository.save(latest);
        return latest.getUserId();
    }

    private Duration ttl(AuthEmailCode.Purpose purpose) {
        return purpose == AuthEmailCode.Purpose.RESET_PASSWORD ? resetTtl : verifyTtl;
    }

    private String randomCode() {
        int bound = (int) Math.pow(10, length);
        return String.format("%0" + length + "d", RANDOM.nextInt(bound));
    }

    private String hash(AuthEmailCode.Purpose purpose, String email, String code) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(hmacKey, "HmacSHA256"));
            byte[] out = mac.doFinal((purpose.name() + ":" + email + ":" + code).getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static CodedException expired() {
        return new CodedException(HttpStatus.BAD_REQUEST, "CODE_EXPIRED", "This code has expired. Request a new one.");
    }

    private static CodedException tooManyAttempts() {
        return new CodedException(HttpStatus.BAD_REQUEST, "CODE_TOO_MANY_ATTEMPTS", "Too many wrong attempts. Request a new code.");
    }
}
