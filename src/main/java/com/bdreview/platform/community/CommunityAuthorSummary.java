package com.bdreview.platform.community;

import java.time.Instant;
import java.util.UUID;

/**
 * Pseudonymous, public-safe author identity for Community posts/comments —
 * deliberately carries NO real name, phone, email, or real profile photo (see
 * CommunityPostService#toAuthorSummary and V33's account/community identity
 * split). reviewCount/memberSince/verified are anonymous trust signals, not
 * private data — they don't identify the person behind the username.
 *
 * <p>{@code id} is {@code User.communityProfileId}, NOT {@code User.id} (see
 * V46's migration comment) — the Review API returns that real id alongside
 * the reviewer's real name, so reusing it here would let the two APIs be
 * joined to deanonymize a Community user.
 *
 * <p>{@code communityAvatarUrl} (V53) is a genuinely separate, optional avatar the user can
 * upload just for this pseudonymous identity — never {@code User.profilePhotoUrl}, and never
 * derived from it. Null falls back to initials on the client.
 */
public record CommunityAuthorSummary(
        UUID id, String communityUsername,
        long reviewCount, Instant memberSince, boolean verified, String communityAvatarUrl) {
}
