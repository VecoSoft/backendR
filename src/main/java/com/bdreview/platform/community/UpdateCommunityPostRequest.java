package com.bdreview.platform.community;

import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/** The image itself can't be swapped after posting — only content/mentions are editable. */
public record UpdateCommunityPostRequest(
        @Size(max = 5000) String content,
        List<UUID> mentionedBusinessIds
) {
}
