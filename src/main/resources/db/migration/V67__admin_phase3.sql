-- V67: admin panel Phase 3 — admin permission roles + 2FA, analytics (search log), broadcasts and
-- notification templates, scheduled-job history, support inbox, chat moderation, content pages.

-- ---------------------------------------------------------------------------
-- Admin permission roles (only meaningful on role = 'ADMIN' accounts) + TOTP 2FA
-- ---------------------------------------------------------------------------
ALTER TABLE app_user ADD COLUMN admin_role VARCHAR(20)
    CHECK (admin_role IN ('SUPER_ADMIN', 'MODERATOR', 'SUPPORT', 'FINANCE'));
UPDATE app_user SET admin_role = 'SUPER_ADMIN' WHERE role = 'ADMIN';

ALTER TABLE app_user ADD COLUMN totp_secret      TEXT;
ALTER TABLE app_user ADD COLUMN totp_enabled     BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE app_user ADD COLUMN totp_enabled_at  TIMESTAMPTZ;

CREATE TABLE admin_recovery_code (
    id         UUID PRIMARY KEY,
    user_id    UUID        NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    code_hash  TEXT        NOT NULL,
    used_at    TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_admin_recovery_code_user ON admin_recovery_code (user_id);

-- ---------------------------------------------------------------------------
-- Analytics: every public search with its result count (zero-result queries = listings to add)
-- ---------------------------------------------------------------------------
CREATE TABLE search_log (
    id           BIGSERIAL PRIMARY KEY,
    query        TEXT        NOT NULL,
    normalized   TEXT        NOT NULL,
    area_id      UUID,
    area_label   TEXT,
    category_id  UUID,
    result_count INTEGER     NOT NULL,
    source       VARCHAR(20) NOT NULL,
    user_id      UUID,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_search_log_created ON search_log (created_at);
CREATE INDEX idx_search_log_normalized ON search_log (normalized, created_at);

-- ---------------------------------------------------------------------------
-- Broadcasts + editable notification templates
-- ---------------------------------------------------------------------------
CREATE TABLE broadcast (
    id              UUID PRIMARY KEY,
    title           VARCHAR(140) NOT NULL,
    body            TEXT         NOT NULL,
    audience        VARCHAR(12)  NOT NULL CHECK (audience IN ('ALL_USERS', 'ALL_OWNERS', 'USERS', 'OWNERS')),
    area_id         UUID,
    category_id     UUID,
    send_sms        BOOLEAN      NOT NULL DEFAULT false,
    scheduled_at    TIMESTAMPTZ,
    status          VARCHAR(12)  NOT NULL DEFAULT 'SCHEDULED' CHECK (status IN ('SCHEDULED', 'SENDING', 'SENT', 'CANCELLED')),
    sent_at         TIMESTAMPTZ,
    recipient_count INTEGER      NOT NULL DEFAULT 0,
    sms_count       INTEGER      NOT NULL DEFAULT 0,
    reason          TEXT,
    created_by      UUID,
    cancelled_by    UUID,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_broadcast_due ON broadcast (status, scheduled_at);

CREATE TABLE notification_template (
    template_key VARCHAR(40) NOT NULL,
    locale       VARCHAR(5)  NOT NULL CHECK (locale IN ('en', 'bn')),
    title        TEXT        NOT NULL,
    body         TEXT        NOT NULL,
    updated_by   UUID,
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (template_key, locale)
);

CREATE INDEX IF NOT EXISTS idx_notification_related ON notification (related_entity_type, related_entity_id);

-- ---------------------------------------------------------------------------
-- System health: scheduled-job run history
-- ---------------------------------------------------------------------------
CREATE TABLE scheduled_job_run (
    id          BIGSERIAL PRIMARY KEY,
    job_name    VARCHAR(60) NOT NULL,
    started_at  TIMESTAMPTZ NOT NULL,
    duration_ms BIGINT      NOT NULL,
    result      VARCHAR(10) NOT NULL CHECK (result IN ('OK', 'FAILED')),
    message     TEXT,
    manual      BOOLEAN     NOT NULL DEFAULT false
);
CREATE INDEX idx_scheduled_job_run ON scheduled_job_run (job_name, started_at DESC);

-- ---------------------------------------------------------------------------
-- Support inbox
-- ---------------------------------------------------------------------------
CREATE TABLE support_ticket (
    id             UUID PRIMARY KEY,
    user_id        UUID        NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    category       VARCHAR(12) NOT NULL CHECK (category IN ('ACCOUNT', 'ORDER', 'BOOKING', 'LISTING', 'PAYMENT', 'OTHER')),
    subject        VARCHAR(160) NOT NULL,
    screenshot_url TEXT,
    status         VARCHAR(10) NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'PENDING', 'RESOLVED')),
    assignee_id    UUID REFERENCES app_user (id),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_support_ticket_queue ON support_ticket (status, updated_at DESC);
CREATE INDEX idx_support_ticket_user ON support_ticket (user_id, created_at DESC);

CREATE TABLE support_ticket_message (
    id          UUID PRIMARY KEY,
    ticket_id   UUID        NOT NULL REFERENCES support_ticket (id) ON DELETE CASCADE,
    author_id   UUID,
    from_staff  BOOLEAN     NOT NULL DEFAULT false,
    internal    BOOLEAN     NOT NULL DEFAULT false,
    body        TEXT        NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_support_ticket_message ON support_ticket_message (ticket_id, created_at);

-- ---------------------------------------------------------------------------
-- Chat moderation: reports on conversations + messaging blocks
-- ---------------------------------------------------------------------------
CREATE TABLE chat_report (
    id               UUID PRIMARY KEY,
    thread_id        UUID        NOT NULL REFERENCES message_thread (id) ON DELETE CASCADE,
    reporter_id      UUID        NOT NULL REFERENCES app_user (id),
    reported_user_id UUID        NOT NULL REFERENCES app_user (id),
    reason           VARCHAR(20) NOT NULL CHECK (reason IN ('SPAM', 'HARASSMENT', 'SCAM', 'INAPPROPRIATE', 'OTHER')),
    details          TEXT,
    status           VARCHAR(10) NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'ACTIONED', 'DISMISSED')),
    action           VARCHAR(10) CHECK (action IN ('WARN', 'BLOCK', 'DISMISS')),
    resolution_note  TEXT,
    resolved_by      UUID,
    resolved_at      TIMESTAMPTZ,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_chat_report_queue ON chat_report (status, created_at);
CREATE INDEX idx_chat_report_thread ON chat_report (thread_id);

CREATE TABLE messaging_block (
    id         UUID PRIMARY KEY,
    user_id    UUID        NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    reason     TEXT        NOT NULL,
    ends_at    TIMESTAMPTZ NOT NULL,
    report_id  UUID REFERENCES chat_report (id),
    created_by UUID        NOT NULL,
    lifted_at  TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_messaging_block_user ON messaging_block (user_id, ends_at);

-- ---------------------------------------------------------------------------
-- Content pages (Terms, Privacy, FAQ, Help) with version history
-- ---------------------------------------------------------------------------
CREATE TABLE content_page (
    slug       VARCHAR(20) NOT NULL CHECK (slug IN ('terms', 'privacy', 'faq', 'help')),
    locale     VARCHAR(5)  NOT NULL CHECK (locale IN ('en', 'bn')),
    title      VARCHAR(160) NOT NULL,
    body_md    TEXT        NOT NULL,
    version    INTEGER     NOT NULL,
    updated_by UUID,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (slug, locale)
);

CREATE TABLE content_page_version (
    id         UUID PRIMARY KEY,
    slug       VARCHAR(20) NOT NULL,
    locale     VARCHAR(5)  NOT NULL,
    version    INTEGER     NOT NULL,
    title      VARCHAR(160) NOT NULL,
    body_md    TEXT        NOT NULL,
    reason     TEXT,
    updated_by UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (slug, locale, version)
);

-- ---------------------------------------------------------------------------
-- Pending protected edits can be withdrawn by the owner; support screenshots are private photos
-- ---------------------------------------------------------------------------
ALTER TABLE business_pending_change DROP CONSTRAINT IF EXISTS business_pending_change_status_check;
ALTER TABLE business_pending_change ADD CONSTRAINT business_pending_change_status_check
    CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'SUPERSEDED', 'CANCELLED'));

ALTER TABLE photo_moderation DROP CONSTRAINT IF EXISTS photo_moderation_source_type_check;
ALTER TABLE photo_moderation ADD CONSTRAINT photo_moderation_source_type_check
    CHECK (source_type IN ('BUSINESS_PHOTO', 'COVER', 'LOGO', 'MENU_ITEM', 'POST', 'REVIEW', 'HERO', 'SUPPORT'));

ALTER TABLE notification DROP CONSTRAINT IF EXISTS notification_type_check;
ALTER TABLE notification ADD CONSTRAINT notification_type_check CHECK (type IN (
    'REPORT_SUBMITTED', 'REPORT_ACTION_TAKEN', 'REPORT_DISMISSED', 'CONTENT_HIDDEN', 'LISTING_FLAGGED',
    'FLAG_REVIEW_REQUESTED', 'NEW_ORDER', 'ORDER_ACCEPTED', 'ORDER_REJECTED', 'ORDER_STATUS_CHANGED',
    'NEW_BOOKING', 'BOOKING_CONFIRMED', 'BOOKING_REJECTED', 'BOOKING_STATUS_CHANGED',
    'COMMUNITY_POST_COMMENT', 'COMMUNITY_POST_REACTION', 'COMMUNITY_POST_MENTION', 'COMMUNITY_COMMENT_REPLY',
    'COMMUNITY_BEST_ANSWER', 'OFFER_CLAIMED', 'OFFER_REDEEMED', 'OFFER_APPROVED', 'OFFER_REJECTED',
    'COMMUNITY_MODERATION', 'COMMUNITY_RESTRICTION', 'ADMIN_NOTICE', 'BROADCAST', 'SUPPORT_REPLY'));
