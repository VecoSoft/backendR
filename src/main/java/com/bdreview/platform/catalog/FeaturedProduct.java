package com.bdreview.platform.catalog;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * One item in a RETAIL listing's "Featured products" showcase (Phase 2).
 * Showcase only — no checkout, no inventory, no variants.
 */
@Entity
@Table(name = "business_product")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class FeaturedProduct {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "business_id", nullable = false)
    private UUID businessId;

    @Column(nullable = false, length = 160)
    private String name;

    @Column(columnDefinition = "text")
    private String description;

    @Column(name = "price_text", length = 80)
    private String priceText;

    @Column(name = "photo_url", columnDefinition = "text")
    private String photoUrl;

    @Builder.Default
    @Column(name = "sort_order", nullable = false)
    private int sortOrder = 0;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }
}
