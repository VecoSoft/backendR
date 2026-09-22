package com.bdreview.platform.community;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/** parentCommentId is null for a top-level comment on the post; set to reply to another comment (max depth 5). */
public record CreateCommunityCommentRequest(
        @NotBlank @Size(max = 2000) String content,
        UUID parentCommentId
) {
}
