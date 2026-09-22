-- =====================================================================
-- V34__community_poll.sql
-- Adds a POLL post type to "Join Community": the post's own `body` is the
-- poll question, and it carries 2-6 single-choice options with an
-- auto-close duration (1/3/7 days). Voting is one row per (poll, user),
-- toggled/swapped exactly like community_post_vote — see
-- CommunityPostService#votePoll.
-- =====================================================================

ALTER TABLE community_post DROP CONSTRAINT community_post_post_type_check;
ALTER TABLE community_post ADD CONSTRAINT community_post_post_type_check
    CHECK (post_type IN ('DISCUSSION', 'QUESTION', 'RECOMMENDATION', 'POLL'));

CREATE TABLE community_post_poll (
    id         UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    post_id    UUID NOT NULL UNIQUE REFERENCES community_post(id),
    closes_at  TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE community_post_poll_option (
    id         UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    poll_id    UUID NOT NULL REFERENCES community_post_poll(id),
    label      VARCHAR(80) NOT NULL,
    position   SMALLINT NOT NULL,
    vote_count INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX idx_community_post_poll_option_poll ON community_post_poll_option (poll_id);

CREATE TABLE community_post_poll_vote (
    id         UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    poll_id    UUID NOT NULL REFERENCES community_post_poll(id),
    option_id  UUID NOT NULL REFERENCES community_post_poll_option(id),
    user_id    UUID NOT NULL REFERENCES app_user(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_community_post_poll_vote UNIQUE (poll_id, user_id)
);
CREATE INDEX idx_community_post_poll_vote_poll ON community_post_poll_vote (poll_id);
