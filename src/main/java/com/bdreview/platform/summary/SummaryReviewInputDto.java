// SummaryReviewInputDto.java
package com.bdreview.platform.summary;

import java.util.UUID;

public record SummaryReviewInputDto(UUID id, int rating, String content) {
}