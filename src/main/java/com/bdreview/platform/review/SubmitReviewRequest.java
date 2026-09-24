package com.bdreview.platform.review;

import jakarta.validation.constraints.*;

import java.util.List;
import java.util.UUID;

public record SubmitReviewRequest(
        @NotNull UUID businessId,
        @Min(1) @Max(5) short rating,
        @NotBlank @Size(min = 10, max = 4000) String content,
        List<String> photoUrls
) {
}
