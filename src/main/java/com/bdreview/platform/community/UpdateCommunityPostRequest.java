package com.bdreview.platform.community;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/** Size ceilings here are absolute safety caps — the real (admin-tunable) limits live in community_settings. */
public record UpdateCommunityPostRequest(
        @Size(max = 150) String title,
        @NotBlank @Size(max = 20000) String body,
        @NotNull @Size(max = 20) String topic,
        UUID businessId,
        List<@Size(max = 2048) String> imageUrls
) {
}
