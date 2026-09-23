package com.bdreview.platform.offer;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A time-boxed discount published by a verified business. No soft-delete
 * column — the status lifecycle itself (DRAFT/PENDING_APPROVAL/ACTIVE/
 * CANCELLED/REJECTED, plus derived EXPIRED) is the only "is this visible"
 * signal, same convention BusinessClaim already uses. Counters are written
 * only via atomic SQL increments in {@link OfferRepository} — never
 * read-modify-write — same convention as Business#averageRating/
 * CommunityPost#commentCount.
 */
@Entity
@Table(name = "offer")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class Offer {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "business_id", nullable = false)
    private UUID businessId;

    @Column(nullable = false, length = 150)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(name = "offer_type", nullable = false, length = 30)
    private OfferType offerType;

    /** The raw number: 20 for "20% OFF", 200 for "৳200 OFF". Null for BUY_ONE_GET_ONE/COMBO_DEAL/FREE_ITEM/OTHER — those carry their meaning in the title alone. */
    @Column(name = "discount_value", precision = 10, scale = 2)
    private BigDecimal discountValue;

    @Column(name = "original_price", precision = 10, scale = 2)
    private BigDecimal originalPrice;

    @Column(name = "offer_price", precision = 10, scale = 2)
    private BigDecimal offerPrice;

    @Column(columnDefinition = "text")
    private String description;

    @Column(name = "terms_and_conditions", columnDefinition = "text")
    private String termsAndConditions;

    @Column(name = "image_url", columnDefinition = "text")
    private String imageUrl;

    /** Optional — an existing menu item this offer's discount applies to (must belong to the same business). Null = the offer stands alone. See CatalogService#menu for how this overlays the item's displayed price while the offer is active. */
    @Column(name = "menu_item_id")
    private UUID menuItemId;

    @Column(name = "valid_from", nullable = false)
    private Instant validFrom;

    @Column(name = "valid_until", nullable = false)
    private Instant validUntil;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private OfferAvailability availability = OfferAvailability.BOTH;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OfferStatus status = OfferStatus.DRAFT;

    /** Null = unlimited. */
    @Column(name = "max_total_redemptions")
    private Integer maxTotalRedemptions;

    /** Null = unlimited. */
    @Column(name = "max_redemptions_per_user")
    private Integer maxRedemptionsPerUser;

    @Builder.Default
    @Column(name = "view_count", nullable = false)
    private int viewCount = 0;

    @Builder.Default
    @Column(name = "claim_count", nullable = false)
    private int claimCount = 0;

    @Builder.Default
    @Column(name = "redemption_count", nullable = false)
    private int redemptionCount = 0;

    @Column(name = "rejection_reason", columnDefinition = "text")
    private String rejectionReason;

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

    @Transient
    public boolean isPastValidUntil() {
        return Instant.now().isAfter(validUntil);
    }

    /** ACTIVE and still within its validity window — the only state new claims are allowed against. */
    @Transient
    public boolean isCurrentlyActive() {
        return status == OfferStatus.ACTIVE && !isPastValidUntil();
    }

    /** What the UI should show — EXPIRED overrides a stored ACTIVE once past validUntil, without a scheduled job ever writing it back. */
    @Transient
    public OfferStatus getEffectiveStatus() {
        return status == OfferStatus.ACTIVE && isPastValidUntil() ? OfferStatus.EXPIRED : status;
    }
}
