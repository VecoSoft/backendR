package com.bdreview.platform.community;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * parentCommentId is null for a top-level comment on the post; set to reply to another comment
 * (max depth 5). The size cap is an absolute safety ceiling — the admin-tunable min/max comment
 * length is enforced by CommunityPolicyService#checkComment.
 */
public record CreateCommunityCommentRequest(
        @NotBlank @Size(max = 10000) String content,
        UUID parentCommentId,
        /** V58: reply as this business — only its owner, and only on that business's own posts. */
        UUID asBusinessId
) {
    public CreateCommunityCommentRequest(String content, UUID parentCommentId) {
        this(content, parentCommentId, null);
    }
}
