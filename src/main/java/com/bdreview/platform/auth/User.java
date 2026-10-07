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

    /**
     * E.164 normalized. Since V70 only admin-panel accounts (phone + password + TOTP) and accounts
     * created before V70 have one: the app no longer collects or shows phone numbers.
     */
    @Column(name = "phone_number", length = 20)
    private String phoneNumber;

    /** V70: sign-in email, stored trimmed + lowercased; unique case-insensitively. Null for phone-only legacy accounts. */
    @Column(length = 254)
    private String email;

    @Column(name = "email_verified_at")
    private Instant emailVerifiedAt;

    /** V70: Google account id (the ID token's {@code sub}), set on the first Google sign-in. */
    @Column(name = "google_sub")
    private String googleSub;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "auth_provider", nullable = false, length = 10)
    private AuthProvider authProvider = AuthProvider.PHONE;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "account_status", nullable = false, length = 20)
    private AccountStatus accountStatus = AccountStatus.ACTIVE;

    /** Password-login lockout (V70): consecutive failures, and the lock set after the 5th. */
    @Builder.Default
    @Column(name = "failed_login_count", nullable = false)
    private int failedLoginCount = 0;

    @Column(name = "login_locked_until")
    private Instant loginLockedUntil;

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
     * V59: "M" or "F", picked alongside the Community username and shown as a small badge next to
     * it. Null only for accounts that set a username before V59 (they're asked before posting).
     */
    @Column(name = "community_gender", length = 1)
    private String communityGender;

    /** V59: when false the badge is hidden — the gender is then never sent to other users. */
    @Builder.Default
    @Column(name = "community_gender_visible", nullable = false)
    private boolean communityGenderVisible = true;

    /** The badge other people see: the gender only when chosen and not hidden, else null. */
    public String publicCommunityGender() {
        return communityGenderVisible ? communityGender : null;
    }

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

    /**
     * Community staff flag on top of the account type in {@link #role} (V56). Only value today is
     * {@link #STAFF_MODERATOR} — a moderator keeps their normal CONSUMER/BUSINESS_OWNER account and
     * just gains access to the admin panel's Community section. ADMIN accounts never need it.
     */
    @Column(name = "staff_role", length = 20)
    private String staffRole;

    /** Set by a moderator's "Approve and trust user" — skips new-user approval and uses the TRUSTED rate-limit tier. */
    @Builder.Default
    @Column(name = "community_trusted", nullable = false)
    private boolean communityTrusted = false;

    /** V67: permission role of an ADMIN account (SUPER_ADMIN / MODERATOR / SUPPORT / FINANCE); null otherwise. */
    @Column(name = "admin_role", length = 20)
    private String adminRole;

    /** V67: TOTP 2FA for admin-panel sign-in (base32 secret; set while enrolling, enabled once a code is confirmed). */
    @Column(name = "totp_secret", columnDefinition = "text")
    private String totpSecret;

    @Builder.Default
    @Column(name = "totp_enabled", nullable = false)
    private boolean totpEnabled = false;

    @Column(name = "totp_enabled_at")
    private Instant totpEnabledAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public static final String STAFF_MODERATOR = "MODERATOR";

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
        // V67: an ADMIN account created without an explicit admin role keeps the historical meaning
        // of ADMIN (full access). Admin accounts created from the panel always pick a role.
        if (this.role == UserRole.ADMIN && this.adminRole == null) {
            this.adminRole = "SUPER_ADMIN";
        }
    }

    public boolean isModerator() {
        return STAFF_MODERATOR.equals(staffRole);
    }
}
