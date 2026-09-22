package com.bdreview.platform.community;

/** One row of a Following/Followers list — see CommunityPostService#following/followers. */
public record CommunityFollowListItem(
        CommunityAuthorSummary author,
        /** Whether the current viewer (not the list owner) already follows this person. False for an anonymous viewer. */
        boolean isFollowing
) {
}
