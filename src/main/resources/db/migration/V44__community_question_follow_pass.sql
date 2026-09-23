-- =====================================================================
-- V44__community_question_follow_pass.sql
-- "Questions for you" widget (Quora-style): following a specific QUESTION
-- post (distinct from community_follow, which is user-follows-user) powers
-- the Follow button + follower count + "last followed" timestamp on a
-- question card; passing a question permanently excludes it from that
-- viewer's future recommendations. Both are simple per-(user, post) rows,
-- same shape as community_follow.
-- =====================================================================

CREATE TABLE community_question_follow (
    id           UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id      UUID NOT NULL REFERENCES app_user(id),
    post_id      UUID NOT NULL REFERENCES community_post(id),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_community_question_follow UNIQUE (user_id, post_id)
);
CREATE INDEX idx_community_question_follow_user ON community_question_follow (user_id);
CREATE INDEX idx_community_question_follow_post ON community_question_follow (post_id);

CREATE TABLE community_question_pass (
    id           UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id      UUID NOT NULL REFERENCES app_user(id),
    post_id      UUID NOT NULL REFERENCES community_post(id),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_community_question_pass UNIQUE (user_id, post_id)
);
CREATE INDEX idx_community_question_pass_user ON community_question_pass (user_id);
