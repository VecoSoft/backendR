-- =====================================================================
-- V31__community_notification_types.sql
-- Extend notification.type for the "Join Community" feature: the post
-- author is notified on a new comment or reaction, and a mentioned
-- business's owner is notified when their listing is tagged in a post.
-- Same widen-the-CHECK-constraint pattern as V8/V10/V23/V25.
-- =====================================================================

ALTER TABLE notification DROP CONSTRAINT notification_type_check;

ALTER TABLE notification ADD CONSTRAINT notification_type_check
    CHECK (type IN ('REPORT_SUBMITTED', 'REPORT_ACTION_TAKEN', 'REPORT_DISMISSED', 'CONTENT_HIDDEN',
                    'LISTING_FLAGGED', 'FLAG_REVIEW_REQUESTED',
                    'NEW_ORDER', 'ORDER_ACCEPTED', 'ORDER_REJECTED', 'ORDER_STATUS_CHANGED',
                    'NEW_BOOKING', 'BOOKING_CONFIRMED', 'BOOKING_REJECTED', 'BOOKING_STATUS_CHANGED',
                    'COMMUNITY_POST_COMMENT', 'COMMUNITY_POST_REACTION', 'COMMUNITY_POST_MENTION'));
