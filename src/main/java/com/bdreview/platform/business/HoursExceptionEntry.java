package com.bdreview.platform.business;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

/** Response shape for one holiday/exception hours override — see BusinessHoursException. */
public record HoursExceptionEntry(
        UUID id,
        LocalDate startDate,
        LocalDate endDate,
        boolean closed,
        LocalTime openTime,
        LocalTime closeTime,
        String reason
) {
    public static HoursExceptionEntry from(BusinessHoursException e) {
        return new HoursExceptionEntry(e.getId(), e.getStartDate(), e.getEndDate(), e.isClosed(),
                e.getOpenTime(), e.getCloseTime(), e.getReason());
    }
}
