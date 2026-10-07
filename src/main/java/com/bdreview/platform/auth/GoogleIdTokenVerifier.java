package com.bdreview.platform.auth;

import com.bdreview.platform.common.CodedException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Verifies a Google Sign-In ID token server-side (never trusts what the browser says about the user):
 * RS256 signature against Google's published keys (fetched from {@code app.auth.google.jwks-uri}
 * and cached by Nimbus), then issuer, audience (one of GOOGLE_CLIENT_IDS: web/Android/iOS), expiry
 * and {@code email_verified}. The app only asks Google for openid, email and profile.
 */
@Component
public class GoogleIdTokenVerifier {

    private static final Set<String> ISSUERS = Set.of("accounts.google.com", "https://accounts.google.com");

    /** What the app keeps from a verified token. */
    public record GoogleIdentity(String sub, String email, String name, String picture) {
    }

    private final JwtDecoder decoder;
    private final List<String> clientIds;

    public GoogleIdTokenVerifier(@Qualifier("googleIdTokenDecoder") JwtDecoder decoder,
                                 @Value("${app.auth.google.client-ids:}") String clientIds) {
        this.decoder = decoder;
        this.clientIds = Arrays.stream(clientIds.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    public boolean configured() {
        return !clientIds.isEmpty();
    }

    public GoogleIdentity verify(String idToken) {
        if (!configured()) {
            throw new CodedException(HttpStatus.SERVICE_UNAVAILABLE, "GOOGLE_NOT_CONFIGURED", "Google sign-in isn't set up yet.");
        }
        if (idToken == null || idToken.isBlank()) {
            throw invalid();
        }
        Jwt jwt;
        try {
            jwt = decoder.decode(idToken);
        } catch (JwtException e) {
            throw invalid();
        }
        String issuer = jwt.getClaimAsString("iss");
        if (issuer == null || !ISSUERS.contains(issuer)) {
            throw invalid();
        }
        List<String> audience = jwt.getAudience();
        if (audience == null || audience.stream().noneMatch(clientIds::contains)) {
            throw invalid();
        }
        Instant expiresAt = jwt.getExpiresAt();
        if (expiresAt == null || !expiresAt.isAfter(Instant.now())) {
            throw invalid();
        }
        String sub = jwt.getSubject();
        String email = jwt.getClaimAsString("email");
        if (sub == null || sub.isBlank() || email == null || email.isBlank()) {
            throw invalid();
        }
        Object verified = jwt.getClaims().get("email_verified");
        if (!(Boolean.TRUE.equals(verified) || "true".equals(verified))) {
            throw new CodedException(HttpStatus.UNAUTHORIZED, "GOOGLE_EMAIL_NOT_VERIFIED",
                    "Your Google account's e-mail address isn't verified with Google.");
        }
        return new GoogleIdentity(sub, email.trim().toLowerCase(Locale.ROOT),
                jwt.getClaimAsString("name"), jwt.getClaimAsString("picture"));
    }

    private static CodedException invalid() {
        return new CodedException(HttpStatus.UNAUTHORIZED, "INVALID_GOOGLE_TOKEN",
                "Google sign-in didn't work. Please try again.");
    }

    @Configuration
    static class DecoderConfig {
        /** Signature + timestamp checks against Google's keys; the claim checks above run on top. */
        @Bean("googleIdTokenDecoder")
        JwtDecoder googleIdTokenDecoder(@Value("${app.auth.google.jwks-uri}") String jwksUri) {
            return NimbusJwtDecoder.withJwkSetUri(jwksUri).build();
        }
    }
}
