package com.bdreview.platform.promo;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Admin-managed Boost price list (V58). A boost snapshots name/price/impressions when bought. */
@Entity
@Table(name = "boost_package")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class BoostPackage {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false, length = 80)
    private String name;

    @Column(name = "price_bdt", nullable = false, precision = 10, scale = 2)
    private BigDecimal priceBdt;

    @Column(name = "est_impressions", nullable = false)
    private int estImpressions;

    @Column(name = "duration_days", nullable = false)
    private int durationDays;

    @Column(name = "max_radius_km", nullable = false)
    private int maxRadiusKm;

    @Builder.Default
    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

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
}
