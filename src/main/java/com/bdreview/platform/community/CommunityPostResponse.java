package com.bdreview.platform.community;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record CommunityPostResponse(
        UUID id,
        CommunityAuthorSummary author,
        String content,
        String imageUrl,
        int likeCount,
        int loveCount,
        int hahaCount,
        int wowCount,
        int sadCount,
        int angryCount,
        int totalReactionCount,
        /** Null if the current viewer hasn't reacted (or is anonymous). */
        CommunityPostReactionType myReaction,
        int commentCount,
        List<CommunityMentionedBusinessSummary> mentionedBusinesses,
        Instant createdAt,
        Instant updatedAt
) {
}
