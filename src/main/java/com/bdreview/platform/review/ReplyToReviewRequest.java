package com.bdreview.platform.review;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ReplyToReviewRequest(@NotBlank @Size(max = 2000) String reply) {
}
