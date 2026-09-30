package com.bdreview.platform.promo;

import com.bdreview.platform.promo.PromoEnums.BoostStatus;
import com.bdreview.platform.promo.PromoEnums.PaymentMethod;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A paid boost of one published business post (V58). Lifecycle:
 * PENDING_PAYMENT (owner submits a bKash/Nagad trx id; admin verifies) → PENDING_REVIEW
 * (moderator approves; skipped when the setting says so) → ACTIVE ⇄ PAUSED → ENDED, or
 * REJECTED / REFUNDED. Only ACTIVE boosts inside [startAt, endAt) are ever served.
 */
@Entity
@Table(name = "boost")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class Boost {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "business_id", nullable = false)
    private UUID businessId;

    @Column(name = "post_id", nullable = false)
    private UUID postId;

    @Column(name = "package_id", nullable = false)
    private UUID packageId;

    @Column(name = "package_name", nullable = false, length = 80)
    private String packageName;

    @Column(name = "est_impressions", nullable = false)
    private int estImpressions;

    @Builder.Default
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "target_area_ids", nullable = false, columnDefinition = "uuid[]")
    private List<UUID> targetAreaIds = new ArrayList<>();

    @Column(name = "center_lat")
    private Double centerLat;

    @Column(name = "center_lng")
    private Double centerLng;

    @Column(name = "radius_km")
    private Integer radiusKm;

    @Column(name = "start_at", nullable = false)
    private Instant startAt;

    @Column(name = "end_at", nullable = false)
    private Instant endAt;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private BoostStatus status = BoostStatus.PENDING_PAYMENT;

    @Column(name = "price_bdt", nullable = false, precision = 10, scale = 2)
    private BigDecimal priceBdt;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_method", length = 10)
    private PaymentMethod paymentMethod;

    @Column(name = "payment_ref", length = 60)
    private String paymentRef;

    @Column(name = "payment_submitted_at")
    private Instant paymentSubmittedAt;

    @Column(name = "payment_verified_at")
    private Instant paymentVerifiedAt;

    @Column(name = "paid_amount", precision = 10, scale = 2)
    private BigDecimal paidAmount;

    @Column(name = "approved_by")
    private UUID approvedBy;

    @Column(name = "rejection_reason", columnDefinition = "text")
    private String rejectionReason;

    @Column(name = "refund_reason", columnDefinition = "text")
    private String refundReason;

    @Builder.Default
    @Column(name = "impressions_served", nullable = false)
    private int impressionsServed = 0;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public boolean isRadiusTargeted() {
        return centerLat != null && centerLng != null && radiusKm != null;
    }

    public boolean isLiveAt(Instant now) {
        return status == BoostStatus.ACTIVE && !now.isBefore(startAt) && now.isBefore(endAt);
    }
}
