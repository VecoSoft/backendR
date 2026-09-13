-- =====================================================================
-- V30__community_posts.sql
-- "Join Community" feature: a Facebook-style feed inside the app —
-- members post text and/or a single image (never video), react, comment,
-- and can tag/mention business listings in a post.
-- =====================================================================

CREATE TABLE community_post (
    id               UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    author_user_id   UUID NOT NULL REFERENCES app_user(id),
    content          TEXT,
    image_url        TEXT,
    like_count       INTEGER NOT NULL DEFAULT 0,
    love_count       INTEGER NOT NULL DEFAULT 0,
    haha_count       INTEGER NOT NULL DEFAULT 0,
    wow_count        INTEGER NOT NULL DEFAULT 0,
    sad_count        INTEGER NOT NULL DEFAULT 0,
    angry_count      INTEGER NOT NULL DEFAULT 0,
    comment_count    INTEGER NOT NULL DEFAULT 0,
    deleted_at       TIMESTAMPTZ,
    version          BIGINT NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT community_post_content_or_image CHECK (content IS NOT NULL OR image_url IS NOT NULL)
);

CREATE INDEX idx_community_post_feed ON community_post (created_at DESC) WHERE deleted_at IS NULL;
CREATE INDEX idx_community_post_author ON community_post (author_user_id) WHERE deleted_at IS NULL;

CREATE TABLE community_post_reaction (
    id            UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    post_id       UUID NOT NULL REFERENCES community_post(id) ON DELETE CASCADE,
    user_id       UUID NOT NULL REFERENCES app_user(id),
    reaction_type VARCHAR(10) NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_community_post_reaction UNIQUE (post_id, user_id)
);

CREATE TABLE community_post_comment (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    post_id         UUID NOT NULL REFERENCES community_post(id) ON DELETE CASCADE,
    author_user_id  UUID NOT NULL REFERENCES app_user(id),
    content         TEXT NOT NULL,
    deleted_at      TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_community_post_comment_post ON community_post_comment (post_id, created_at) WHERE deleted_at IS NULL;

CREATE TABLE community_post_mention (
    id           UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    post_id      UUID NOT NULL REFERENCES community_post(id) ON DELETE CASCADE,
    business_id  UUID NOT NULL REFERENCES business(id),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_community_post_mention UNIQUE (post_id, business_id)
);

CREATE INDEX idx_community_post_mention_business ON community_post_mention (business_id);
