package com.bdreview.platform.community;

import java.time.Instant;
import java.util.UUID;

/**
 * Public Community profile ("u/username") — same real-identity exclusion as
 * CommunityAuthorSummary: no name/phone/email, ever.
 */
public record CommunityProfileResponse(
        /** Community-facing pseudonymous id — never the real app_user.id. See V46's migration comment. */
        UUID communityProfileId,
        String communityUsername,
        Instant memberSince,
        boolean verified,
        long reviewCount,
        long postCount,
        long commentCount,
        /** Whether the current viewer follows this person — always false for an anonymous viewer. */
        boolean isFollowing,
        long followerCount,
        long followingCount
) {
}
