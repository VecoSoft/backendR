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

    /**
     * Optional avatar for the pseudonymous Community identity — deliberately separate from
     * {@link #profilePhotoUrl} (the real account photo, which CommunityAuthorSummary/
     * CommunityProfileResponse never expose). Uploaded through a storage key that embeds a
     * random id instead of this user's real id (see CommunityPostService's image-upload
     * pattern), so the URL itself can't be used to link a Community identity back to this row.
     */
    @Column(name = "community_avatar_url", columnDefinition = "text")
    private String communityAvatarUrl;

    /**
     * Public identity for Community responses (author.id, profile userId, etc.) —
     * deliberately NOT {@link #id}, which the (non-anonymous) Review API also
     * returns alongside the reviewer's real name; reusing that same id in
     * Community would let anyone join the two APIs and deanonymize a user
     * (see V46's migration comment). Random, set once at row creation,
     * present even before communityUsername is ever set.
     */
    @Column(name = "community_profile_id", nullable = false)
    private UUID communityProfileId;

    @Builder.Default
    @Column(name = "preferred_language", nullable = false, length = 10)
    private String preferredLanguage = "en";

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
        // The column default (uuid_generate_v4(), see V46) only covers rows written outside
        // Hibernate (the migration's backfill) — Hibernate always includes every mapped column
        // in its own INSERT, so a new row needs this set here or it would insert NULL and violate
        // the NOT NULL constraint.
        if (this.communityProfileId == null) {
            this.communityProfileId = UUID.randomUUID();
        }
    }
}
