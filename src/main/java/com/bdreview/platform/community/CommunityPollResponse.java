package com.bdreview.platform.community;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record CommunityPollResponse(
        UUID id,
        List<CommunityPollOptionResponse> options,
        int totalVotes,
        /** Null if the current viewer hasn't voted (or is anonymous). */
        UUID myVoteOptionId,
        Instant closesAt,
        boolean closed
) {
}
