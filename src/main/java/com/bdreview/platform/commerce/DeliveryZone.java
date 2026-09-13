package com.bdreview.platform.commerce;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One distance band of a business's own delivery reach: "0–2 km, ৳30 fee,
 * ৳150 minimum, ~30 min". PostGIS distance only for now — polygon / area /
 * schedule models are future additions and don't invalidate stored orders
 * (each order keeps its resolved zone id + snapshot fee).
 */
@Entity
@Table(name = "delivery_zone")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class DeliveryZone {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "business_id", nullable = false)
    private UUID businessId;

    @Column(nullable = false, length = 80)
    private String name;

    @Column(name = "min_distance_km", nullable = false, precision = 5, scale = 2)
    private BigDecimal minDistanceKm;

    @Column(name = "max_distance_km", nullable = false, precision = 5, scale = 2)
    private BigDecimal maxDistanceKm;

    @Column(name = "delivery_fee", nullable = false, precision = 10, scale = 2)
    private BigDecimal deliveryFee;

    @Builder.Default
    @Column(name = "minimum_order_amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal minimumOrderAmount = BigDecimal.ZERO;

    @Column(name = "estimated_delivery_minutes")
    private Integer estimatedDeliveryMinutes;

    @Builder.Default
    @Column(nullable = false)
    private boolean active = true;

    @Builder.Default
    @Column(name = "sort_order", nullable = false)
    private int sortOrder = 0;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }

    /** True when {@code distanceKm} falls in [min, max). */
    public boolean covers(double distanceKm) {
        return distanceKm >= minDistanceKm.doubleValue() && distanceKm < maxDistanceKm.doubleValue();
    }
}
