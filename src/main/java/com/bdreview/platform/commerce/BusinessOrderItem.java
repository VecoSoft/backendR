package com.bdreview.platform.commerce;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One line of an order. {@code sourceItemId} points back at the menu item /
 * product it came from (nullable — the catalog row may be deleted later);
 * name and unit price are frozen snapshots.
 */
@Entity
@Table(name = "business_order_item")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class BusinessOrderItem {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "order_id", nullable = false)
    private UUID orderId;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 16)
    private OrderItemSource sourceType = OrderItemSource.MENU_ITEM;

    @Column(name = "source_item_id")
    private UUID sourceItemId;

    /** The active offer (see V45) that priced this line, if any — lets OrderService track/cap real usage against Offer#redemptionCount. */
    @Column(name = "offer_id")
    private UUID offerId;

    @Column(name = "item_name_snapshot", nullable = false, length = 160)
    private String itemNameSnapshot;

    @Column(name = "unit_price_snapshot", nullable = false, precision = 10, scale = 2)
    private BigDecimal unitPriceSnapshot;

    @Column(nullable = false)
    private int quantity;

    @Column(name = "total_price", nullable = false, precision = 10, scale = 2)
    private BigDecimal totalPrice;
}
