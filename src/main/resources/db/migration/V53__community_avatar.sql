-- Separate, optional avatar for the pseudonymous Community identity — deliberately
-- distinct from profile_photo_url (the real account photo), same split rationale as
-- community_username vs name (V33) and community_profile_id vs id (V46): the real
-- profile photo must never surface on a Community post/comment/profile.
ALTER TABLE app_user ADD COLUMN community_avatar_url text;
