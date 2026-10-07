package com.bdreview.platform.commerce;

import jakarta.persistence.*;
import lombok.*;
import org.locationtech.jts.geom.Point;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A direct order placed by a customer against a business. Money amounts,
 * customer name/phone, and (via {@code BusinessOrderItem}) item names/prices
 * are all <em>snapshots</em> taken at placement — a later menu edit never
 * rewrites history.
 */
@Entity
@Table(name = "business_order")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class BusinessOrder {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "business_id", nullable = false)
    private UUID businessId;

    @Column(name = "customer_user_id", nullable = false)
    private UUID customerUserId;

    @Column(name = "order_number", nullable = false, length = 20, updatable = false)
    private String orderNumber;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private OrderStatus status = OrderStatus.PENDING;

    @Enumerated(EnumType.STRING)
    @Column(name = "fulfillment_type", nullable = false, length = 16)
    private FulfillmentType fulfillmentType;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal subtotal;

    @Builder.Default
    @Column(name = "delivery_fee", nullable = false, precision = 10, scale = 2)
    private BigDecimal deliveryFee = BigDecimal.ZERO;

    @Builder.Default
    @Column(name = "discount_amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal discountAmount = BigDecimal.ZERO;

    @Column(name = "total_amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal totalAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_method", nullable = false, length = 20)
    private PaymentMethod paymentMethod;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "payment_status", nullable = false, length = 16)
    private PaymentStatus paymentStatus = PaymentStatus.UNPAID;

    @Column(name = "customer_name_snapshot", nullable = false, length = 120)
    private String customerNameSnapshot;

    @Column(name = "customer_phone_snapshot", length = 20)
    private String customerPhoneSnapshot;

    @Column(name = "delivery_address", columnDefinition = "text")
    private String deliveryAddress;

    @Column(name = "delivery_location", columnDefinition = "geography(Point,4326)")
    private Point deliveryLocation;

    @Column(name = "delivery_distance_km", precision = 6, scale = 2)
    private BigDecimal deliveryDistanceKm;

    @Column(name = "delivery_zone_id")
    private UUID deliveryZoneId;

    @Column(name = "customer_note", columnDefinition = "text")
    private String customerNote;

    @Column(name = "rejection_reason", length = 200)
    private String rejectionReason;

    /** Set once the owner accepts the order — createdAt/acceptedAt + the business's default prep time. Null until then. */
    @Column(name = "estimated_ready_at")
    private Instant estimatedReadyAt;

    /** V65: support closed a dispute on this order (Admin → Commerce → Orders → "Mark dispute resolved"). */
    @Column(name = "dispute_resolved_at")
    private Instant disputeResolvedAt;

    @Column(name = "dispute_resolved_by")
    private UUID disputeResolvedBy;

    @Column(name = "dispute_note", columnDefinition = "text")
    private String disputeNote;

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
}
