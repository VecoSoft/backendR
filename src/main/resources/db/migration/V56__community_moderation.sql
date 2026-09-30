-- Community moderation & control system.
--
-- Everything here is additive: existing columns keep their meaning (community_post.deleted_at
-- is still the author's own "delete my post"), existing API responses only gain fields.
--
--  * app_user.staff_role / community_trusted — MODERATOR staff flag on an existing account
--    (role stays the account-type discriminator, see auth.UserRole) + "Approve and trust user".
--  * community_post / community_post_comment moderation state: status (ACTIVE/HIDDEN/REMOVED/
--    PENDING), soft-removal trail (removed_by/removed_reason/removed_at), lock, pin, feature,
--    official announcements, denormalised report_count.
--  * community_post_photo soft removal of a single image.
--  * community_topic — topics move from a Java enum + CHECK constraint to a table (the post's
--    topic column keeps the same string codes, so API payloads are unchanged).
--  * community_settings — one JSONB document, cached in Redis by CommunitySettingsService.
--  * community_restriction — warn / mute / suspend / ban with expiry.
--  * community_announcement — "Jachai Team" announcement targeting + scheduling + banner.
--  * audit_log gains actor role, reason, before/after JSON and IP.
--  * report gains resolver + resolution time and the COMMUNITY_PROFILE target type.

-- ---------------------------------------------------------------------------
-- Staff role + trust
-- ---------------------------------------------------------------------------
ALTER TABLE app_user ADD COLUMN staff_role VARCHAR(20)
    CONSTRAINT chk_app_user_staff_role CHECK (staff_role IN ('MODERATOR'));
ALTER TABLE app_user ADD COLUMN community_trusted BOOLEAN NOT NULL DEFAULT FALSE;
CREATE INDEX idx_app_user_staff_role ON app_user (staff_role) WHERE staff_role IS NOT NULL;

-- ---------------------------------------------------------------------------
-- Topics table (replaces the CommunityTopic enum + CHECK constraint)
-- ---------------------------------------------------------------------------
CREATE TABLE community_topic (
    code        VARCHAR(20) PRIMARY KEY CHECK (code ~ '^[A-Z][A-Z0-9_]{1,19}$'),
    label       VARCHAR(60) NOT NULL,
    label_bn    VARCHAR(60),
    icon        VARCHAR(40),
    color       VARCHAR(20),
    position    INTEGER     NOT NULL DEFAULT 0,
    enabled     BOOLEAN     NOT NULL DEFAULT TRUE,
    is_default  BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_community_topic_default ON community_topic (is_default) WHERE is_default;

-- SERVICES/JOBS/EDUCATION/TRAVEL were already hidden from the composer and the feed filter
-- (frontend lib/community-constants.ts) — seeded disabled so that stays true, and old posts
-- tagged with them keep rendering.
INSERT INTO community_topic (code, label, label_bn, icon, color, position, enabled, is_default) VALUES
    ('FOOD',       'Food',       'খাবার',       'utensils',       '#f97316', 1,  TRUE,  FALSE),
    ('HEALTHCARE', 'Healthcare', 'স্বাস্থ্য',    'stethoscope',    '#ef4444', 2,  TRUE,  FALSE),
    ('BEAUTY',     'Beauty',     'সৌন্দর্য',     'sparkles',       '#ec4899', 3,  TRUE,  FALSE),
    ('SHOPPING',   'Shopping',   'শপিং',        'shopping-bag',   '#8b5cf6', 4,  TRUE,  FALSE),
    ('FITNESS',    'Fitness',    'ফিটনেস',      'dumbbell',       '#10b981', 5,  TRUE,  FALSE),
    ('LOCAL',      'Local',      'স্থানীয়',      'map-pin',        '#0ea5e9', 6,  TRUE,  FALSE),
    ('SERVICES',   'Services',   'সেবা',        'wrench',         '#64748b', 7,  FALSE, FALSE),
    ('JOBS',       'Jobs',       'চাকরি',       'briefcase',      '#64748b', 8,  FALSE, FALSE),
    ('EDUCATION',  'Education',  'শিক্ষা',       'graduation-cap', '#64748b', 9,  FALSE, FALSE),
    ('TRAVEL',     'Travel',     'ভ্রমণ',        'plane',          '#64748b', 10, FALSE, FALSE),
    ('GENERAL',    'General',    'সাধারণ',      'message-circle', '#6b7280', 11, TRUE,  TRUE);

ALTER TABLE community_post DROP CONSTRAINT IF EXISTS community_post_topic_check;
ALTER TABLE community_post ADD CONSTRAINT fk_community_post_topic
    FOREIGN KEY (topic) REFERENCES community_topic (code) ON UPDATE CASCADE;

-- ---------------------------------------------------------------------------
-- Post moderation state
-- ---------------------------------------------------------------------------
ALTER TABLE community_post
    ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'
        CONSTRAINT chk_community_post_status CHECK (status IN ('ACTIVE', 'HIDDEN', 'REMOVED', 'PENDING')),
    ADD COLUMN hold_reason     VARCHAR(40),
    ADD COLUMN removed_by      UUID,
    ADD COLUMN removed_reason  TEXT,
    ADD COLUMN removed_at      TIMESTAMPTZ,
    ADD COLUMN locked          BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN pinned          BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN pin_scope       VARCHAR(10)
        CONSTRAINT chk_community_post_pin_scope CHECK (pin_scope IN ('GLOBAL', 'TOPIC', 'AREA')),
    ADD COLUMN pinned_until    TIMESTAMPTZ,
    ADD COLUMN featured        BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN official        BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN visible_from    TIMESTAMPTZ,
    ADD COLUMN visible_until   TIMESTAMPTZ,
    ADD COLUMN report_count    INTEGER NOT NULL DEFAULT 0;

CREATE INDEX idx_community_post_status ON community_post (status, created_at DESC) WHERE deleted_at IS NULL;
CREATE INDEX idx_community_post_pinned ON community_post (pin_scope) WHERE pinned;

ALTER TABLE community_post_comment
    ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'
        CONSTRAINT chk_community_comment_status CHECK (status IN ('ACTIVE', 'HIDDEN', 'REMOVED', 'PENDING')),
    ADD COLUMN hold_reason     VARCHAR(40),
    ADD COLUMN removed_by      UUID,
    ADD COLUMN removed_reason  TEXT,
    ADD COLUMN removed_at      TIMESTAMPTZ,
    ADD COLUMN report_count    INTEGER NOT NULL DEFAULT 0;

CREATE INDEX idx_community_comment_status ON community_post_comment (status, created_at DESC) WHERE deleted_at IS NULL;

ALTER TABLE community_post_photo
    ADD COLUMN removed_by     UUID,
    ADD COLUMN removed_reason TEXT,
    ADD COLUMN removed_at     TIMESTAMPTZ;

-- ---------------------------------------------------------------------------
-- Settings (single JSONB document — see community.settings.CommunitySettings for the shape
-- and defaults; an empty document means "all defaults")
-- ---------------------------------------------------------------------------
CREATE TABLE community_settings (
    id          SMALLINT PRIMARY KEY CHECK (id = 1),
    settings    JSONB       NOT NULL DEFAULT '{}'::jsonb,
    updated_by  UUID,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
INSERT INTO community_settings (id, settings) VALUES (1, '{}'::jsonb);

-- ---------------------------------------------------------------------------
-- User restrictions
-- ---------------------------------------------------------------------------
CREATE TABLE community_restriction (
    id           UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id      UUID        NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    type         VARCHAR(20) NOT NULL CHECK (type IN ('WARN', 'MUTE', 'SUSPEND', 'BAN')),
    status       VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'EXPIRED', 'LIFTED')),
    reason       TEXT        NOT NULL,
    starts_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    ends_at      TIMESTAMPTZ,
    created_by   UUID        NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    lifted_by    UUID,
    lifted_at    TIMESTAMPTZ,
    lift_reason  TEXT
);
CREATE INDEX idx_community_restriction_user ON community_restriction (user_id, status);
CREATE INDEX idx_community_restriction_active_ends ON community_restriction (ends_at) WHERE status = 'ACTIVE';

-- ---------------------------------------------------------------------------
-- Announcements ("Jachai Team")
-- ---------------------------------------------------------------------------
CREATE TABLE community_announcement (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    post_id         UUID        NOT NULL REFERENCES community_post (id) ON DELETE CASCADE,
    target_scope    VARCHAR(10) NOT NULL CHECK (target_scope IN ('ALL', 'AREA', 'TOPIC')),
    target_area_id  UUID REFERENCES area (id),
    target_topic    VARCHAR(20) REFERENCES community_topic (code) ON UPDATE CASCADE,
    starts_at       TIMESTAMPTZ,
    ends_at         TIMESTAMPTZ,
    show_banner     BOOLEAN     NOT NULL DEFAULT FALSE,
    banner_text     VARCHAR(280),
    created_by      UUID        NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_community_announcement_post UNIQUE (post_id)
);

-- ---------------------------------------------------------------------------
-- Audit log: full admin-action trail
-- ---------------------------------------------------------------------------
ALTER TABLE audit_log
    ADD COLUMN actor_role   VARCHAR(20),
    ADD COLUMN reason       TEXT,
    ADD COLUMN before_json  TEXT,
    ADD COLUMN after_json   TEXT,
    ADD COLUMN ip_address   VARCHAR(64);
CREATE INDEX IF NOT EXISTS idx_audit_log_created ON audit_log (created_at DESC);
CREATE INDEX IF NOT EXISTS idx_audit_log_entity ON audit_log (entity_type, entity_id);

-- ---------------------------------------------------------------------------
-- Reports: resolver trail + reporting a community profile
-- ---------------------------------------------------------------------------
ALTER TABLE report
    ADD COLUMN resolved_by UUID,
    ADD COLUMN resolved_at TIMESTAMPTZ;
ALTER TABLE report DROP CONSTRAINT IF EXISTS report_target_type_check;
ALTER TABLE report ADD CONSTRAINT report_target_type_check
    CHECK (target_type IN ('REVIEW', 'LISTING', 'COMMUNITY_POST', 'COMMUNITY_COMMENT', 'OFFER', 'COMMUNITY_PROFILE'));
CREATE INDEX IF NOT EXISTS idx_report_target ON report (target_type, target_id, status);

-- Denormalised report counters (sort "most reported" without a per-row subquery)
UPDATE community_post p SET report_count = r.cnt
FROM (SELECT target_id, COUNT(*) AS cnt FROM report WHERE target_type = 'COMMUNITY_POST' GROUP BY target_id) r
WHERE p.id = r.target_id;
UPDATE community_post_comment c SET report_count = r.cnt
FROM (SELECT target_id, COUNT(*) AS cnt FROM report WHERE target_type = 'COMMUNITY_COMMENT' GROUP BY target_id) r
WHERE c.id = r.target_id;

-- Posts an admin already removed through the old report flow (deleted_at set by
-- ReportService) stay deleted — nothing to backfill: status defaults to ACTIVE and the
-- deleted_at filter keeps hiding them exactly as before.

-- ---------------------------------------------------------------------------
-- Notifications for moderation outcomes
-- ---------------------------------------------------------------------------
ALTER TABLE notification DROP CONSTRAINT IF EXISTS notification_type_check;
ALTER TABLE notification ADD CONSTRAINT notification_type_check CHECK (type IN (
    'REPORT_SUBMITTED', 'REPORT_ACTION_TAKEN', 'REPORT_DISMISSED', 'CONTENT_HIDDEN', 'LISTING_FLAGGED',
    'FLAG_REVIEW_REQUESTED', 'NEW_ORDER', 'ORDER_ACCEPTED', 'ORDER_REJECTED', 'ORDER_STATUS_CHANGED',
    'NEW_BOOKING', 'BOOKING_CONFIRMED', 'BOOKING_REJECTED', 'BOOKING_STATUS_CHANGED',
    'COMMUNITY_POST_COMMENT', 'COMMUNITY_POST_REACTION', 'COMMUNITY_POST_MENTION', 'COMMUNITY_COMMENT_REPLY',
    'COMMUNITY_BEST_ANSWER', 'OFFER_CLAIMED', 'OFFER_REDEEMED', 'OFFER_APPROVED', 'OFFER_REJECTED',
    'COMMUNITY_MODERATION', 'COMMUNITY_RESTRICTION'));
