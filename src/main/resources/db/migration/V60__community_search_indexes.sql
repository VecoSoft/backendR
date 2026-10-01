-- V60: Community search (posts + people). Matching is ILIKE '%term%' — pg_trgm GIN indexes keep
-- that fast as the community grows. People search matches the pseudonymous community_username
-- only (never the private account name).
CREATE INDEX IF NOT EXISTS ix_community_post_body_trgm  ON community_post USING GIN (body gin_trgm_ops);
CREATE INDEX IF NOT EXISTS ix_community_post_title_trgm ON community_post USING GIN (title gin_trgm_ops);
CREATE INDEX IF NOT EXISTS ix_app_user_community_username_trgm ON app_user USING GIN (community_username gin_trgm_ops);
