package com.bdreview.platform.catalog;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One dish on a RESTAURANT listing's menu showcase (Phase 2). Grouped in the UI
 * by the free-text {@code menuSection} label ("Starters", "Mains", "Drinks") —
 * deliberately a label, not a separate menu-category table. {@code popular}
 * surfaces an item in the "Popular items" strip.
 *
 * <p>Commerce (Phase A): {@code priceText} stays the freeform showcase label;
 * {@code price} is the optional numeric amount used for cart maths. An item is
 * orderable only when {@code price != null && orderingEnabled && available}.
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

    /** Optional numeric price — required for the item to be orderable (Phase A). */
    @Column(precision = 10, scale = 2)
    private BigDecimal price;

    @Builder.Default
    @Column(nullable = false)
    private boolean available = true;

    @Builder.Default
    @Column(name = "ordering_enabled", nullable = false)
    private boolean orderingEnabled = false;

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
