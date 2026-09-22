package com.bdreview.platform.auth;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "app_user")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class User {

    @Id
    @GeneratedValue
    private UUID id;

    /** E.164 normalized everywhere before storage or comparison (spec §5). */
    @Column(name = "phone_number", nullable = false, unique = true, length = 20)
    private String phoneNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private UserRole role;

    @Builder.Default
    @Column(name = "otp_verified", nullable = false)
    private boolean otpVerified = false;

    @Column(name = "password_hash")
    private String passwordHash;

    @Column(length = 120)
    private String name;

    @Column(name = "profile_photo_url", columnDefinition = "text")
    private String profilePhotoUrl;

    /**
     * Public, pseudonymous identity used only inside "Join Community" — kept
     * deliberately separate from {@link #name} (private/account identity).
     * Unique case-insensitively (see V33's functional index), nullable until
     * the user completes the Community username setup flow.
     */
    @Column(name = "community_username", length = 20)
    private String communityUsername;

    @Builder.Default
    @Column(name = "preferred_language", nullable = false, length = 10)
    private String preferredLanguage = "en";

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }
}
