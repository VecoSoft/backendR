// SummaryGenerationResponseDto.java
package com.bdreview.platform.summary;

import java.util.UUID;

public record SummaryGenerationResponseDto(UUID businessId, String summaryText, int reviewCountConsidered) {
}