package com.bdreview.platform.community;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

public record UpdateCommunityPostRequest(
        @Size(max = 150) String title,
        @NotBlank @Size(max = 5000) String body,
        @NotNull CommunityTopic topic,
        UUID businessId,
        List<@Size(max = 2048) String> imageUrls
) {
}
