package com.bdreview.platform.community;

import java.time.Instant;
import java.util.UUID;

public record CommunityCommentResponse(
        UUID id,
        CommunityAuthorSummary author,
        String content,
        Instant createdAt,
        Instant updatedAt
) {
}
