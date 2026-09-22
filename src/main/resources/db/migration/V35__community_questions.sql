-- =====================================================================
-- V35__community_questions.sql
-- Lightweight Q&A layer for QUESTION posts: an owner-selected Best Answer
-- (at most one per post), a derived Open/Answered/Closed status (Closed is
-- the only stored bit — Answered is derived from a best answer existing,
-- see CommunityPostService), and a top-level-only answer_count (distinct
-- from comment_count, which stays the total thread size including nested
-- replies).
-- =====================================================================

ALTER TABLE community_post
    ADD COLUMN closed_at TIMESTAMPTZ,
    ADD COLUMN answer_count INTEGER NOT NULL DEFAULT 0;

ALTER TABLE community_post_comment
    ADD COLUMN is_best_answer BOOLEAN NOT NULL DEFAULT false;

CREATE UNIQUE INDEX uq_community_post_comment_best_answer
    ON community_post_comment (post_id)
    WHERE is_best_answer = true AND deleted_at IS NULL;

ALTER TABLE notification DROP CONSTRAINT notification_type_check;
ALTER TABLE notification ADD CONSTRAINT notification_type_check
    CHECK (type IN ('REPORT_SUBMITTED', 'REPORT_ACTION_TAKEN', 'REPORT_DISMISSED', 'CONTENT_HIDDEN',
                    'LISTING_FLAGGED', 'FLAG_REVIEW_REQUESTED',
                    'NEW_ORDER', 'ORDER_ACCEPTED', 'ORDER_REJECTED', 'ORDER_STATUS_CHANGED',
                    'NEW_BOOKING', 'BOOKING_CONFIRMED', 'BOOKING_REJECTED', 'BOOKING_STATUS_CHANGED',
                    'COMMUNITY_POST_COMMENT', 'COMMUNITY_POST_REACTION', 'COMMUNITY_POST_MENTION',
                    'COMMUNITY_COMMENT_REPLY', 'COMMUNITY_BEST_ANSWER'));
