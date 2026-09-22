-- =====================================================================
-- V43__community_post_photos.sql
-- Multiple photo attachments per community post (Facebook-style photo
-- grid on the composer/feed/detail page). community_post.image_url stays
-- as-is (legacy pre-V1 single image, see V33) — new posts attach photos
-- here instead, same one-row-per-photo shape as review_photo/business_photo.
-- =====================================================================
CREATE TABLE community_post_photo (
    id          UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    post_id     UUID NOT NULL REFERENCES community_post(id),
    url         TEXT NOT NULL,
    position    SMALLINT NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_community_post_photo_post ON community_post_photo (post_id, position);
