-- =====================================================================
-- V33__community_reddit_v1.sql
-- "Join Community" V1 rewrite: Facebook-style feed -> Reddit-style
-- pseudonymous text discussion. Adds a public community_username on
-- app_user (separate from the private account name), restructures
-- community_post into title+body+topic+type with optional business/area
-- references, collapses the 6-emoji reaction model into a 2-value
-- upvote/downvote (existing community_post_reaction is renamed and its
-- data remapped rather than dropped), adds threaded comment replies +
-- comment voting, and a minimal user-follows-user table for the
-- "Following" feed. No existing rows are destructively deleted.
-- =====================================================================

-- ---------------------------------------------------------------------
-- Community identity: a public, unique, case-insensitive username
-- separate from app_user.name (which stays private/account-only).
-- ---------------------------------------------------------------------
ALTER TABLE app_user ADD COLUMN community_username VARCHAR(20);

CREATE UNIQUE INDEX uq_app_user_community_username
    ON app_user (LOWER(community_username))
    WHERE community_username IS NOT NULL;

-- ---------------------------------------------------------------------
-- community_post: title+body replace the Facebook-style "content" blob;
-- post_type/topic are string-CHECK-backed (same widen-later convention
-- as notification.type) rather than a fixed DB enum; area_id is optional
-- and powers the "Nearby" feed via the existing business.area table.
-- ---------------------------------------------------------------------
ALTER TABLE community_post RENAME COLUMN content TO body;
ALTER TABLE community_post RENAME COLUMN like_count TO upvote_count;
ALTER TABLE community_post ADD COLUMN downvote_count INTEGER NOT NULL DEFAULT 0;
ALTER TABLE community_post
    DROP COLUMN love_count,
    DROP COLUMN haha_count,
    DROP COLUMN wow_count,
    DROP COLUMN sad_count,
    DROP COLUMN angry_count;

ALTER TABLE community_post ADD COLUMN title VARCHAR(150);
ALTER TABLE community_post ADD COLUMN post_type VARCHAR(20) NOT NULL DEFAULT 'DISCUSSION'
    CHECK (post_type IN ('DISCUSSION', 'QUESTION', 'RECOMMENDATION'));
ALTER TABLE community_post ADD COLUMN topic VARCHAR(20) NOT NULL DEFAULT 'GENERAL'
    CHECK (topic IN ('FOOD', 'HEALTHCARE', 'BEAUTY', 'SHOPPING', 'FITNESS', 'LOCAL',
                      'SERVICES', 'JOBS', 'EDUCATION', 'TRAVEL', 'GENERAL'));
ALTER TABLE community_post ADD COLUMN area_id UUID REFERENCES area(id);

CREATE INDEX idx_community_post_topic ON community_post (topic, created_at DESC) WHERE deleted_at IS NULL;
CREATE INDEX idx_community_post_area ON community_post (area_id, created_at DESC)
    WHERE deleted_at IS NULL AND area_id IS NOT NULL;

-- Old rows have title=NULL and rely on body/image (still valid); new rows are
-- expected to carry a title (enforced at the request-DTO layer, not here, so
-- existing rows never violate a NOT NULL retroactively).
ALTER TABLE community_post DROP CONSTRAINT community_post_content_or_image;
ALTER TABLE community_post ADD CONSTRAINT community_post_title_or_body_or_image
    CHECK (title IS NOT NULL OR body IS NOT NULL OR image_url IS NOT NULL);

-- ---------------------------------------------------------------------
-- Reactions -> votes: rename in place (keeps the "one row per user per
-- post" toggle/swap design, which is already exactly Reddit voting) and
-- remap the 6 legacy values down to 2 so existing rows stay readable.
-- ---------------------------------------------------------------------
ALTER TABLE community_post_reaction RENAME TO community_post_vote;
ALTER TABLE community_post_vote RENAME COLUMN reaction_type TO vote_type;
UPDATE community_post_vote
    SET vote_type = CASE WHEN vote_type IN ('LIKE', 'LOVE', 'HAHA', 'WOW') THEN 'UPVOTE' ELSE 'DOWNVOTE' END;
ALTER TABLE community_post_vote ADD CONSTRAINT chk_community_post_vote_type CHECK (vote_type IN ('UPVOTE', 'DOWNVOTE'));

-- ---------------------------------------------------------------------
-- Threaded comment replies (flat storage: parent_comment_id + depth,
-- max depth 5 enforced in CommunityPostService, not here) + comment
-- voting (new — comments had no reactions before).
-- ---------------------------------------------------------------------
ALTER TABLE community_post_comment
    ADD COLUMN parent_comment_id UUID REFERENCES community_post_comment(id),
    ADD COLUMN depth SMALLINT NOT NULL DEFAULT 0,
    ADD COLUMN upvote_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN downvote_count INTEGER NOT NULL DEFAULT 0;

CREATE INDEX idx_community_post_comment_parent ON community_post_comment (parent_comment_id) WHERE deleted_at IS NULL;

CREATE TABLE community_post_comment_vote (
    id          UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    comment_id  UUID NOT NULL REFERENCES community_post_comment(id) ON DELETE CASCADE,
    user_id     UUID NOT NULL REFERENCES app_user(id),
    vote_type   VARCHAR(10) NOT NULL CHECK (vote_type IN ('UPVOTE', 'DOWNVOTE')),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_community_post_comment_vote UNIQUE (comment_id, user_id)
);

-- ---------------------------------------------------------------------
-- Minimal user-follows-user (no topic-follow/business-follow — bookmarks
-- already cover business-saving, and no infra for either exists yet).
-- Powers the "Following" feed tab only.
-- ---------------------------------------------------------------------
CREATE TABLE community_follow (
    id                 UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    follower_user_id   UUID NOT NULL REFERENCES app_user(id),
    followed_user_id   UUID NOT NULL REFERENCES app_user(id),
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_community_follow UNIQUE (follower_user_id, followed_user_id),
    CONSTRAINT chk_community_follow_not_self CHECK (follower_user_id <> followed_user_id)
);
CREATE INDEX idx_community_follow_follower ON community_follow (follower_user_id);
CREATE INDEX idx_community_follow_followed ON community_follow (followed_user_id);

-- ---------------------------------------------------------------------
-- Moderation/notification CHECK widening — same pattern as V31.
-- ---------------------------------------------------------------------
ALTER TABLE report DROP CONSTRAINT report_target_type_check;
ALTER TABLE report ADD CONSTRAINT report_target_type_check
    CHECK (target_type IN ('REVIEW', 'LISTING', 'COMMUNITY_POST', 'COMMUNITY_COMMENT'));

ALTER TABLE notification DROP CONSTRAINT notification_type_check;
ALTER TABLE notification ADD CONSTRAINT notification_type_check
    CHECK (type IN ('REPORT_SUBMITTED', 'REPORT_ACTION_TAKEN', 'REPORT_DISMISSED', 'CONTENT_HIDDEN',
                    'LISTING_FLAGGED', 'FLAG_REVIEW_REQUESTED',
                    'NEW_ORDER', 'ORDER_ACCEPTED', 'ORDER_REJECTED', 'ORDER_STATUS_CHANGED',
                    'NEW_BOOKING', 'BOOKING_CONFIRMED', 'BOOKING_REJECTED', 'BOOKING_STATUS_CHANGED',
                    'COMMUNITY_POST_COMMENT', 'COMMUNITY_POST_REACTION', 'COMMUNITY_POST_MENTION',
                    'COMMUNITY_COMMENT_REPLY'));
