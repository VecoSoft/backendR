package com.bdreview.platform.catalog;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * One line item in a listing's "Services" showcase (Phase 2) — also serves gym
 * membership plans ({@code section=OFFERING}) and gym facilities
 * ({@code section=FACILITY}). Reused by GENERAL / SALON / CLINIC / GYM. Not
 * e-commerce: {@code priceText} is a freeform label, there is no cart.
 */
@Entity
@Table(name = "business_service")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class ServiceOffering {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "business_id", nullable = false)
    private UUID businessId;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ServiceSection section = ServiceSection.OFFERING;

    @Column(nullable = false, length = 160)
    private String name;

    @Column(columnDefinition = "text")
    private String description;

    /** Freeform: "Starting from ৳500", "৳1,200", "Contact for quote". */
    @Column(name = "price_text", length = 80)
    private String priceText;

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
