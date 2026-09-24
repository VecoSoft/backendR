-- =====================================================================
-- V46__community_profile_id.sql
-- CRITICAL fix: Community was pseudonymous in name only — every post/comment
-- author and every /community/u/{username} profile response exposed the
-- account's real app_user.id, the SAME id the (non-anonymous) Review API
-- returns alongside the reviewer's real name. Anyone could join the two on
-- that id and deanonymize a Community user ("u/JachaiUser1673 = khalil").
--
-- Fix: a separate, random, per-user community_profile_id — used everywhere
-- Community-facing responses previously serialized the real user id.
-- app_user.id itself is unchanged and keeps working normally for auth,
-- reviews, orders, etc. — only Community's public-facing identity moves off
-- of it. See CommunityPostService (toAuthorSummary, getProfile,
-- follow/unfollow/following/followers/postsByAuthor/commentsByAuthor all now
-- resolve a communityProfileId back to the real id server-side, never the
-- reverse) and UserProfileDto (adds communityProfileId so the frontend can
-- do "is this my own post" comparisons without the real id).
-- =====================================================================

ALTER TABLE app_user
    ADD COLUMN community_profile_id UUID NOT NULL DEFAULT uuid_generate_v4();

CREATE UNIQUE INDEX uq_app_user_community_profile_id ON app_user (community_profile_id);
