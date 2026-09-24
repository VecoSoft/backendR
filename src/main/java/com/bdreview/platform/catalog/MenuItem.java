package com.bdreview.platform.catalog;

import com.bdreview.platform.offer.OfferType;
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

    /**
     * Optional "was" price shown struck through next to {@link #price} so a standalone discount
     * (one not backed by a full Offer) is still disclosed on the menu. Only meaningful when greater
     * than {@code price}; ignored/cleared otherwise by {@code CatalogService}.
     */
    @Column(name = "compare_at_price", precision = 10, scale = 2)
    private BigDecimal compareAtPrice;

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

    /**
     * Populated by CatalogService#menu when an active Offer links to this item — never persisted
     * (see V45's migration comment: the item's own {@link #price} is never overwritten, so it's
     * exactly what this reverts to the moment the linked offer ends). Null when no offer applies.
     */
    @Transient
    private UUID activeOfferId;

    /** Set whenever activeOfferId is, even for non-numeric types (e.g. BUY_ONE_GET_ONE) where activeOfferPrice stays null. */
    @Transient
    private OfferType activeOfferType;

    /** Only set for numeric offer types (percentage/fixed discount) — null for BUY_ONE_GET_ONE etc., which change quantity billed, not unit price. */
    @Transient
    private BigDecimal activeOfferPrice;

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }
}
