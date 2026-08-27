// SummaryGenerationRequestDto.java
package com.bdreview.platform.summary;

import java.util.List;
import java.util.UUID;

public record SummaryGenerationRequestDto(UUID businessId, List<SummaryReviewInputDto> reviews) {
}