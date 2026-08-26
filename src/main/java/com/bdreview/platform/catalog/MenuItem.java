package com.bdreview.platform.catalog;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * One dish on a RESTAURANT listing's menu showcase (Phase 2). Grouped in the UI
 * by the free-text {@code menuSection} label ("Starters", "Mains", "Drinks") —
 * deliberately a label, not a separate menu-category table. {@code popular}
 * surfaces an item in the "Popular items" strip. Not a POS: no stock, no orders.
 */
@Entity
@Table(name = "business_menu_item")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class MenuItem {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "business_id", nullable = false)
    private UUID businessId;

    /** Free-text group label; null / blank => "Menu" catch-all group. */
    @Column(name = "menu_section", length = 80)
    private String menuSection;

    @Column(nullable = false, length = 160)
    private String name;

    @Column(columnDefinition = "text")
    private String description;

    @Column(name = "price_text", length = 80)
    private String priceText;

    @Column(name = "photo_url", columnDefinition = "text")
    private String photoUrl;

    @Builder.Default
    @Column(name = "is_popular", nullable = false)
    private boolean popular = false;

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
