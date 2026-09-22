package com.bdreview.platform.community;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record CommunityPostResponse(
        UUID id,
        CommunityAuthorSummary author,
        String title,
        String body,
        /** The post's photo attachments, in order; a legacy pre-V1 post's single image (if any) comes through as a 1-entry list. Empty if none. */
        List<String> imageUrls,
        CommunityPostType postType,
        CommunityTopic topic,
        CommunityAreaSummary area,
        int upvoteCount,
        int downvoteCount,
        int score,
        /** Null if the current viewer hasn't voted (or is anonymous). */
        CommunityPostVoteType myVote,
        int commentCount,
        /** 0 or 1 entries for a V1 post (single optional business attach); legacy posts may carry more. */
        List<CommunityMentionedBusinessSummary> mentionedBusinesses,
        /** Non-null only when postType == POLL. */
        CommunityPollResponse poll,
        /** Non-null only when postType == QUESTION — derived, see CommunityPostService#questionStatus. */
        CommunityQuestionStatus questionStatus,
        /** Top-level (depth 0) comments only — "Answers" on a QUESTION post. 0 for other post types. */
        int answerCount,
        Instant createdAt,
        Instant updatedAt
) {
}
