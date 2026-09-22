package com.bdreview.platform.business;

import java.time.DayOfWeek;
import java.time.LocalTime;

/** Response shape for one day's structured hours — see BusinessOperatingHours. */
public record OperatingHoursEntry(
        DayOfWeek dayOfWeek,
        boolean closed,
        LocalTime openTime,
        LocalTime closeTime
) {
    public static OperatingHoursEntry from(BusinessOperatingHours h) {
        return new OperatingHoursEntry(h.getDayOfWeek(), h.isClosed(), h.getOpenTime(), h.getCloseTime());
    }
}
