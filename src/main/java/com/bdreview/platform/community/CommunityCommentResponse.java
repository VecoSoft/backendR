package com.bdreview.platform.community;

import java.time.Instant;
import java.util.UUID;

public record CommunityCommentResponse(
        UUID id,
        CommunityAuthorSummary author,
        String content,
        /** Null for a top-level comment on the post itself. */
        UUID parentCommentId,
        int depth,
        /** Only meaningful for a top-level (depth 0) comment on a QUESTION post — see CommunityPostService#markBestAnswer. */
        boolean isBestAnswer,
        int upvoteCount,
        int downvoteCount,
        int score,
        /** Null if the current viewer hasn't voted (or is anonymous). */
        CommunityPostVoteType myVote,
        Instant createdAt,
        Instant updatedAt
) {
}
