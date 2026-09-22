package com.bdreview.platform.business;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.time.LocalTime;

/** closeTime <= openTime (when not closed) means the window crosses midnight — see BusinessHoursException. */
public record HoursExceptionRequest(
        @NotNull LocalDate startDate,
        @NotNull LocalDate endDate,
        boolean closed,
        LocalTime openTime,
        LocalTime closeTime,
        @Size(max = 200) String reason
) {
}
