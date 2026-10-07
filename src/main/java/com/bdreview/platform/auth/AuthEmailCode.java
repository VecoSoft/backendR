package com.bdreview.platform.auth;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/** A six-digit code sent by e-mail (V70 {@code auth_email_code}); only its HMAC is stored. */
@Entity
@Table(name = "auth_email_code")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class AuthEmailCode {

    public enum Purpose { VERIFY_EMAIL, RESET_PASSWORD, ADD_EMAIL }

    @Id
    @GeneratedValue
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Purpose purpose;

    @Column(nullable = false, length = 254)
    private String email;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "code_hash", nullable = false, length = 100)
    private String codeHash;

    @Builder.Default
    @Column(nullable = false)
    private int attempts = 0;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    @Column(name = "request_ip", length = 64)
    private String requestIp;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
