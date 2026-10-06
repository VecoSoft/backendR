package com.bdreview.platform.notification;

/** Kept deliberately small and extensible — add new values (+ a migration widening the CHECK constraint) as needed. */
public enum NotificationType {
    REPORT_SUBMITTED,
    REPORT_ACTION_TAKEN,
    REPORT_DISMISSED,
    CONTENT_HIDDEN,
    LISTING_FLAGGED,
    FLAG_REVIEW_REQUESTED,
    // Commerce (Phase A) — order lifecycle
    NEW_ORDER,
    ORDER_ACCEPTED,
    ORDER_REJECTED,
    ORDER_STATUS_CHANGED,
    // Commerce (Phase C) — booking lifecycle
    NEW_BOOKING,
    BOOKING_CONFIRMED,
    BOOKING_REJECTED,
    BOOKING_STATUS_CHANGED,
    // "Join Community" feed — see community.CommunityPostService
    COMMUNITY_POST_COMMENT,
    COMMUNITY_POST_REACTION,
    COMMUNITY_POST_MENTION,
    COMMUNITY_COMMENT_REPLY,
    COMMUNITY_BEST_ANSWER,
    // Offers — see offer.OfferService/OfferNotifier
    OFFER_CLAIMED,
    OFFER_REDEEMED,
    OFFER_APPROVED,
    OFFER_REJECTED,
    // Community moderation (V56) — see community.moderation.CommunityModerationService
    COMMUNITY_MODERATION,
    COMMUNITY_RESTRICTION,
    // Admin panel Phase 2 (V65) — support/moderation decisions: order or booking cancelled by
    // support, offer ended/hidden, verification and protected-edit outcomes
    ADMIN_NOTICE
}