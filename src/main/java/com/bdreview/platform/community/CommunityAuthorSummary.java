package com.bdreview.platform.community;

import java.time.Instant;
import java.util.UUID;

/**
 * Pseudonymous, public-safe author identity for Community posts/comments —
 * deliberately carries NO real name, phone, email, or profile photo (see
 * CommunityPostService#toAuthorSummary and V33's account/community identity
 * split). reviewCount/memberSince/verified are anonymous trust signals, not
 * private data — they don't identify the person behind the username.
 */
public record CommunityAuthorSummary(
        UUID id, String communityUsername,
        long reviewCount, Instant memberSince, boolean verified) {
}
