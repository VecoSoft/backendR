package com.bdreview.platform.community;

import java.time.Instant;
import java.util.UUID;

/** One card in the "Questions for you" widget — see CommunityPostService#recommendedQuestions. */
public record CommunityQuestionRecommendationResponse(
        UUID id,
        String headline,
        int answerCount,
        long followerCount,
        /** Null if nobody has followed this question yet. */
        Instant lastFollowedAt
) {
}
