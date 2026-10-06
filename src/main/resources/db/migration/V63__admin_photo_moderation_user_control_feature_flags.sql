-- V63: admin panel Phase 1 — photo moderation, platform user control, DB-backed feature flags.

-- ---------------------------------------------------------------------------
-- 1. Photo moderation
-- ---------------------------------------------------------------------------

-- Per-row visibility on the three multi-photo tables. Everything that exists today stays public.
ALTER TABLE business_photo       ADD COLUMN moderation_status VARCHAR(12) NOT NULL DEFAULT 'APPROVED';
ALTER TABLE review_photo         ADD COLUMN moderation_status VARCHAR(12) NOT NULL DEFAULT 'APPROVED';
ALTER TABLE community_post_photo ADD COLUMN moderation_status VARCHAR(12) NOT NULL DEFAULT 'APPROVED';

ALTER TABLE business_photo       ADD CONSTRAINT business_photo_moderation_status_chk
    CHECK (moderation_status IN ('PENDING', 'APPROVED', 'REJECTED'));
ALTER TABLE review_photo         ADD CONSTRAINT review_photo_moderation_status_chk
    CHECK (moderation_status IN ('PENDING', 'APPROVED', 'REJECTED'));
ALTER TABLE community_post_photo ADD CONSTRAINT community_post_photo_moderation_status_chk
    CHECK (moderation_status IN ('PENDING', 'APPROVED', 'REJECTED'));

-- The review queue. One row per uploaded photo that needed (or got) a decision, keyed by
-- (source_type, source_id, url):
--   BUSINESS_PHOTO -> source_id = business.id        (row in business_photo)
--   POST           -> source_id = community_post.id  (row in community_post_photo)
--   REVIEW         -> source_id = review.id          (row in review_photo)
--   COVER / LOGO   -> source_id = business.id        (copied into business.cover_photo_url / logo_url on approval)
--   MENU_ITEM      -> source_id = business_menu_item.id (copied into photo_url on approval)
-- A photo with no row here (everything uploaded before V63) is APPROVED.
CREATE TABLE photo_moderation (
    id               UUID PRIMARY KEY,
    source_type      VARCHAR(20) NOT NULL
        CHECK (source_type IN ('BUSINESS_PHOTO', 'COVER', 'LOGO', 'MENU_ITEM', 'POST', 'REVIEW')),
    source_id        UUID        NOT NULL,
    business_id      UUID,
    url              TEXT        NOT NULL,
    -- storage object key behind url (null for external URLs); a REJECTED/DELETED key is never served.
    object_key       TEXT,
    uploader_user_id UUID,
    status           VARCHAR(12) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'DELETED', 'WITHDRAWN')),
    reason           TEXT,
    reviewed_by      UUID,
    reviewed_at      TIMESTAMPTZ,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_photo_moderation_queue    ON photo_moderation (status, created_at DESC);
CREATE INDEX idx_photo_moderation_source   ON photo_moderation (source_type, source_id);
CREATE INDEX idx_photo_moderation_business ON photo_moderation (business_id);
CREATE INDEX idx_photo_moderation_uploader ON photo_moderation (uploader_user_id, created_at DESC);
CREATE INDEX idx_photo_moderation_blocked  ON photo_moderation (object_key) WHERE status IN ('REJECTED', 'DELETED');

-- ---------------------------------------------------------------------------
-- 2. Platform settings / feature flags
-- ---------------------------------------------------------------------------

-- One row per setting an ADMIN has overridden; no row = the deployment default from
-- application.yml (features.*). Keys: NID_VERIFICATION, ORDERING, BOOKINGS, COMMUNITY,
-- PROMOTIONS, OWNER_CHAT, NEW_SIGNUPS, MAINTENANCE_MODE, PHOTO_APPROVAL_REQUIRED.
CREATE TABLE platform_setting (
    setting_key VARCHAR(40) PRIMARY KEY,
    enabled     BOOLEAN     NOT NULL,
    message     TEXT,
    updated_by  UUID,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- The NID override used to live in the community settings document (V56); move it here.
INSERT INTO platform_setting (setting_key, enabled)
SELECT 'NID_VERIFICATION', (settings -> 'features' ->> 'nidVerificationEnabled')::boolean
FROM community_settings
WHERE id = 1 AND settings -> 'features' ->> 'nidVerificationEnabled' IS NOT NULL;

UPDATE community_settings SET settings = settings - 'features' WHERE id = 1;

-- ---------------------------------------------------------------------------
-- 3. Platform user control
-- ---------------------------------------------------------------------------

-- Account-wide suspensions/bans (separate from community-only restrictions). In force while
-- lifted_at IS NULL and (ends_at IS NULL OR ends_at > now()). BAN has no end.
CREATE TABLE user_restriction (
    id          UUID PRIMARY KEY,
    user_id     UUID        NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    type        VARCHAR(10) NOT NULL CHECK (type IN ('SUSPEND', 'BAN')),
    reason      TEXT        NOT NULL,
    starts_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    ends_at     TIMESTAMPTZ,
    created_by  UUID        NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    lifted_at   TIMESTAMPTZ,
    lifted_by   UUID,
    lift_reason TEXT,
    CHECK (type = 'BAN' OR ends_at IS NOT NULL)
);
CREATE INDEX idx_user_restriction_user ON user_restriction (user_id, created_at DESC);

-- Login history for the admin user page (successful logins + attempts refused because of a restriction).
CREATE TABLE user_login_event (
    id         UUID PRIMARY KEY,
    user_id    UUID        NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    channel    VARCHAR(10) NOT NULL, -- APP / ADMIN
    outcome    VARCHAR(12) NOT NULL, -- SUCCESS / RESTRICTED
    ip_address VARCHAR(64),
    user_agent VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_user_login_event_user ON user_login_event (user_id, created_at DESC);
