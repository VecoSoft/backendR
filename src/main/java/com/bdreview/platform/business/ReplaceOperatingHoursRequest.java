package com.bdreview.platform.business;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public record ReplaceOperatingHoursRequest(@NotNull List<@Valid OperatingHoursEntryRequest> days) {
}
