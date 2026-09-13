package com.bdreview.platform.community;

import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * {@code imageUrl} must be a cdnUrlAfterUpload previously returned from
 * POST /api/v1/community/posts/upload-url (see CommunityPostController).
 * Either content or imageUrl (or both) must be present — enforced in
 * CommunityPostService#createPost, not here, since it's a cross-field rule.
 */
public record CreateCommunityPostRequest(
        @Size(max = 5000) String content,
        String imageUrl,
        List<UUID> mentionedBusinessIds
) {
}
