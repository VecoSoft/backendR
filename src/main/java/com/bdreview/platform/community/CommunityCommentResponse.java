package com.bdreview.platform.community;

import java.time.Instant;
import java.util.UUID;

public record CommunityCommentResponse(
        UUID id,
        CommunityAuthorSummary author,
        /** {@link #REMOVED_PLACEHOLDER} when a moderator removed the comment — the thread keeps its place. */
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
        Instant updatedAt,
        // ---- V56 moderation fields (additive) ----
        /** ACTIVE or REMOVED for everyone; the author may also see their own PENDING/HIDDEN comment. */
        CommunityContentStatus status,
        /** Only sent to the comment's own author, and only when status == REMOVED. */
        String removedReason,
        /** V58: non-null when the business replied as itself (only possible on its own posts). */
        BusinessIdentity business
) {
    public static final String REMOVED_PLACEHOLDER = "[Removed by moderators]";
}
