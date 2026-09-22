package com.bdreview.platform.business;

import jakarta.persistence.*;
import lombok.*;
import org.locationtech.jts.geom.Point;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Core listing entity (spec §1). Soft-deleted (never hard-deleted) so admins retain
 * access for disputes/investigations. Rating aggregates (averageRating/reviewCount)
 * are written only via atomic SQL increments in {@link BusinessRepository} — never
 * via read-modify-write on this entity — to avoid colliding with the optimistic-lock
 * {@code version} column under concurrent updates.
 */
@Entity
@Table(name = "business")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class Business {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "owner_user_id", nullable = false)
    private UUID ownerUserId;

    @Column(nullable = false)
    private String name;

    /** Generated at creation, immutable thereafter. On collision, a random suffix is appended. */
    @Column(nullable = false, length = 280)
    private String slug;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "category_id", nullable = false)
    private Category category;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "city_id", nullable = false)
    private City city;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "area_id", nullable = false)
    private Area area;

    @Column(name = "contact_number", nullable = false, length = 20)
    private String contactNumber; // E.164 normalized

    @Column(name = "operating_hours", columnDefinition = "text")
    private String operatingHours;

    @Column(columnDefinition = "text")
    private String description;

    /** Optional trust-building field, "Since {year}" — nullable, no upper/lower bound enforced at the column level (see BusinessService validation). */
    @Column(name = "established_year")
    private Integer establishedYear;

    @Column(name = "cover_photo_url", columnDefinition = "text")
    private String coverPhotoUrl;

    /** Circular profile/logo picture shown on business cards — distinct from the cover/gallery photos. */
    @Column(name = "logo_url", columnDefinition = "text")
    private String logoUrl;

    // -----------------------------------------------------------------
    // "Business presence" (spec Step 4) — optional ways to reach the
    // business online. All nullable; blank input is stored as NULL and
    // an empty value is never rendered on the public page. See V18.
    // -----------------------------------------------------------------
    @Column(name = "website_url", columnDefinition = "text")
    private String websiteUrl;

    /** E.164 normalized, same convention as {@link #contactNumber}. */
    @Column(name = "whatsapp_number", length = 20)
    private String whatsappNumber;

    @Column(length = 255)
    private String email;

    @Column(name = "facebook_url", columnDefinition = "text")
    private String facebookUrl;

    @Column(name = "instagram_url", columnDefinition = "text")
    private String instagramUrl;

    /** PostGIS geography(Point,4326), GiST-indexed — see V1__init.sql. */
    @Column(nullable = false, columnDefinition = "geography(Point,4326)")
    private Point location;

    @Enumerated(EnumType.STRING)
    @Column(name = "price_tier", nullable = false, length = 20)
    private PriceTier priceTier;

    @Builder.Default
    @ManyToMany
    @JoinTable(
            name = "business_attribute_assignment",
            joinColumns = @JoinColumn(name = "business_id"),
            inverseJoinColumns = @JoinColumn(name = "attribute_id")
    )
    private Set<BusinessAttribute> attributes = new HashSet<>();

    @Builder.Default
    @Column(nullable = false)
    private boolean verified = false;

    /**
     * Set when a LISTING report against this business is resolved ACTION_TAKEN
     * (report package) — a visible platform-wide warning with a reason, not a
     * takedown. Stored as a plain string (matching the report reason's name())
     * rather than report.ReportReason, so this package doesn't take on a
     * dependency on the report package's types.
     */
    @Builder.Default
    @Column(nullable = false)
    private boolean flagged = false;

    @Column(name = "flag_reason", length = 20)
    private String flagReason;

    @Column(name = "flagged_at")
    private Instant flaggedAt;

    /** Business-level reaction aggregates (Like/Dislike/Love/Wow on the card) — written only via
     *  atomic SQL increments in {@link BusinessRepository}, same convention as averageRating/reviewCount. */
    @Builder.Default
    @Column(name = "total_like_count", nullable = false)
    private int totalLikeCount = 0;

    @Builder.Default
    @Column(name = "total_dislike_count", nullable = false)
    private int totalDislikeCount = 0;

    @Builder.Default
    @Column(name = "total_love_count", nullable = false)
    private int totalLoveCount = 0;

    @Builder.Default
    @Column(name = "total_wow_count", nullable = false)
    private int totalWowCount = 0;

    @Builder.Default
    @Column(name = "average_rating", nullable = false, precision = 3, scale = 2)
    private BigDecimal averageRating = BigDecimal.ZERO;

    @Builder.Default
    @Column(name = "review_count", nullable = false)
    private int reviewCount = 0;

    /** Backs the atomic average-rating recompute in {@link BusinessRepository}; not surfaced to clients. */
    @Builder.Default
    @Column(name = "rating_sum", nullable = false)
    private int ratingSum = 0;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Version
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }
}