package com.bdreview.platform.community;

import java.util.UUID;

/** voteCount is null until the viewer has voted (or the poll has closed) — see CommunityPostService#assemblePoll. */
public record CommunityPollOptionResponse(
        UUID id,
        String label,
        Integer voteCount
) {
}
