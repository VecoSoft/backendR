package com.bdreview.platform.business;

import jakarta.validation.constraints.NotNull;

import java.time.DayOfWeek;
import java.time.LocalTime;

/** closeTime <= openTime (when not closed) means the window crosses midnight — see BusinessOperatingHours. */
public record OperatingHoursEntryRequest(
        @NotNull DayOfWeek dayOfWeek,
        boolean closed,
        LocalTime openTime,
        LocalTime closeTime
) {
}
