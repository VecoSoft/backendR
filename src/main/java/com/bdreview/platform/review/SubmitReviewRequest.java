package com.bdreview.platform.review;

import jakarta.validation.constraints.*;

import java.util.List;
import java.util.UUID;

public record SubmitReviewRequest(
        @NotNull UUID businessId,
        @Min(1) @Max(5) short rating,
        @NotBlank @Size(max = 4000) String content, // minimum length: admin review policy (ReviewService)
        List<String> photoUrls
) {
}
