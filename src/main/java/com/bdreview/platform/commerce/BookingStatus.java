package com.bdreview.platform.commerce;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Appointment-booking lifecycle. The owner drives every transition except the
 * customer's own cancel while still {@link #PENDING} or {@link #CONFIRMED}.
 * {@link #allowedNext} mirrors {@code OrderStatus}'s transition-map pattern.
 */
public enum BookingStatus {
    PENDING,
    CONFIRMED,
    COMPLETED,
    CANCELLED,
    REJECTED,
    NO_SHOW;

    private static final Map<BookingStatus, Set<BookingStatus>> NEXT = Map.of(
            PENDING,   EnumSet.of(CONFIRMED, REJECTED, CANCELLED),
            CONFIRMED, EnumSet.of(COMPLETED, NO_SHOW, CANCELLED),
            COMPLETED, EnumSet.noneOf(BookingStatus.class),
            CANCELLED, EnumSet.noneOf(BookingStatus.class),
            REJECTED,  EnumSet.noneOf(BookingStatus.class),
            NO_SHOW,   EnumSet.noneOf(BookingStatus.class));

    public boolean canTransitionTo(BookingStatus target) {
        return NEXT.getOrDefault(this, Set.of()).contains(target);
    }
}
