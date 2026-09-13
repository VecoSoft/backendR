package com.bdreview.platform.commerce;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Order lifecycle. The owner drives every transition except the customer's
 * own cancel while still {@link #PENDING}. {@link #allowedNext} is the single
 * source of truth for legal transitions (enforced in {@code OrderService}).
 */
public enum OrderStatus {
    PENDING,
    ACCEPTED,
    PREPARING,
    READY_FOR_PICKUP,
    OUT_FOR_DELIVERY,
    PICKED_UP,
    DELIVERED,
    COMPLETED,
    REJECTED,
    CANCELLED;

    private static final Map<OrderStatus, Set<OrderStatus>> NEXT = Map.of(
            PENDING,          EnumSet.of(ACCEPTED, REJECTED, CANCELLED),
            ACCEPTED,         EnumSet.of(PREPARING, CANCELLED),
            PREPARING,        EnumSet.of(READY_FOR_PICKUP, OUT_FOR_DELIVERY, CANCELLED),
            READY_FOR_PICKUP, EnumSet.of(PICKED_UP, CANCELLED),
            OUT_FOR_DELIVERY, EnumSet.of(DELIVERED, CANCELLED),
            PICKED_UP,        EnumSet.of(COMPLETED),
            DELIVERED,        EnumSet.of(COMPLETED),
            COMPLETED,        EnumSet.noneOf(OrderStatus.class),
            REJECTED,         EnumSet.noneOf(OrderStatus.class),
            CANCELLED,        EnumSet.noneOf(OrderStatus.class));

    public boolean canTransitionTo(OrderStatus target) {
        return NEXT.getOrDefault(this, Set.of()).contains(target);
    }

    public boolean isTerminal() {
        return NEXT.getOrDefault(this, Set.of()).isEmpty();
    }
}
