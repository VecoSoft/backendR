-- V59: Community gender badge.
-- Members pick Male or Female alongside their Community username; a small M/F badge is shown next
-- to u/username on posts, comments and profiles. Required before posting/commenting (existing
-- members are asked once), but each member can hide the badge — the choice is then never sent to
-- other users. NULL = not chosen yet (accounts that set a username before V59).
ALTER TABLE app_user
    ADD COLUMN community_gender VARCHAR(1),
    ADD COLUMN community_gender_visible BOOLEAN NOT NULL DEFAULT TRUE,
    ADD CONSTRAINT chk_app_user_community_gender CHECK (community_gender IN ('M', 'F'));
