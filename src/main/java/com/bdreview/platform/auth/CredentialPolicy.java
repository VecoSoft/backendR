package com.bdreview.platform.auth;

import com.bdreview.platform.common.CodedException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * E-mail and password rules for email/password accounts (V70). Every violation is a 400 with a
 * code the app maps to a translated message: INVALID_EMAIL, DISPOSABLE_EMAIL, PASSWORD_TOO_SHORT,
 * PASSWORD_NEEDS_LETTER_AND_NUMBER, PASSWORD_TOO_COMMON, PASSWORD_MISMATCH.
 */
@Component
public class CredentialPolicy {

    public static final int MIN_PASSWORD_LENGTH = 8;
    /** BCrypt only uses the first 72 bytes; longer input would silently be truncated. */
    public static final int MAX_PASSWORD_BYTES = 72;

    // Pragmatic address check (what users actually type): local@domain.tld, no spaces, one @.
    private static final Pattern EMAIL = Pattern.compile(
            "^[a-z0-9.!#$%&'*+/=?^_`{|}~-]{1,64}@(?=.{1,253}$)[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)*\\.[a-z]{2,24}$");

    private final Set<String> disposableDomains;
    private final Set<String> commonPasswords;

    public CredentialPolicy(@Value("${app.auth.blocked-email-domains:}") String extraBlockedDomains) {
        Set<String> domains = readList("auth/disposable-email-domains.txt");
        Arrays.stream(extraBlockedDomains.split(","))
                .map(s -> s.trim().toLowerCase(Locale.ROOT)).filter(s -> !s.isEmpty()).forEach(domains::add);
        this.disposableDomains = Set.copyOf(domains);
        this.commonPasswords = Set.copyOf(readList("auth/common-passwords.txt"));
    }

    /** Trimmed and lowercased, the form every e-mail is stored and compared in. */
    public static String normalizeEmail(String raw) {
        return raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
    }

    /** Normalizes and validates an address for a new account (or a new e-mail on an existing one). */
    public String requireValidEmail(String raw) {
        String email = normalizeEmail(raw);
        if (email.length() > 254 || !EMAIL.matcher(email).matches()) {
            throw bad("INVALID_EMAIL", "Enter a valid e-mail address.");
        }
        if (isDisposable(email)) {
            throw bad("DISPOSABLE_EMAIL", "Please use a permanent e-mail address, not a temporary one.");
        }
        return email;
    }

    public boolean isDisposable(String email) {
        String domain = email.substring(email.indexOf('@') + 1);
        // the domain itself or any parent domain (x.mailinator.com -> mailinator.com)
        for (String d = domain; d.contains("."); d = d.substring(d.indexOf('.') + 1)) {
            if (disposableDomains.contains(d)) {
                return true;
            }
        }
        return false;
    }

    /** The password rules plus the "re-type" check; {@code email} keeps the password from being the address itself. */
    public void requireValidPassword(String password, String confirmPassword, String email) {
        if (password == null || password.length() < MIN_PASSWORD_LENGTH) {
            throw bad("PASSWORD_TOO_SHORT", "Password must be at least " + MIN_PASSWORD_LENGTH + " characters.");
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES) {
            throw bad("PASSWORD_TOO_LONG", "Password is too long (max " + MAX_PASSWORD_BYTES + " characters).");
        }
        boolean letter = password.chars().anyMatch(Character::isLetter);
        boolean digit = password.chars().anyMatch(Character::isDigit);
        if (!letter || !digit) {
            throw bad("PASSWORD_NEEDS_LETTER_AND_NUMBER", "Password must contain at least one letter and one number.");
        }
        String lower = password.toLowerCase(Locale.ROOT);
        if (commonPasswords.contains(lower)
                || (email != null && !email.isBlank() && lower.equals(email.substring(0, Math.max(0, email.indexOf('@')))))
                || lower.equals(email)) {
            throw bad("PASSWORD_TOO_COMMON", "This password is too easy to guess. Choose a different one.");
        }
        if (confirmPassword == null || !confirmPassword.equals(password)) {
            throw bad("PASSWORD_MISMATCH", "The passwords don't match.");
        }
    }

    private static CodedException bad(String code, String message) {
        return new CodedException(HttpStatus.BAD_REQUEST, code, message);
    }

    private static Set<String> readList(String resource) {
        try (var in = new ClassPathResource(resource).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines()
                    .map(String::trim)
                    .filter(l -> !l.isEmpty() && !l.startsWith("#"))
                    .map(l -> l.toLowerCase(Locale.ROOT))
                    .collect(Collectors.toCollection(HashSet::new));
        } catch (IOException e) {
            throw new UncheckedIOException("Missing classpath list " + resource, e);
        }
    }
}
