-- V65: admin panel Phase 2 — commerce oversight, listing integrity, review policy, homepage curation.

-- ---------------------------------------------------------------------------
-- Admin-editable configuration documents (COMMERCE, REVIEW_POLICY, HOMEPAGE).
-- One JSON document per section; a missing row/key means the built-in default.
-- ---------------------------------------------------------------------------
CREATE TABLE admin_config (
    section    VARCHAR(40) PRIMARY KEY,
    settings   JSONB       NOT NULL DEFAULT '{}'::jsonb,
    updated_by UUID,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------------------
-- Commerce: admin dispute resolution on orders
-- ---------------------------------------------------------------------------
ALTER TABLE business_order ADD COLUMN dispute_resolved_at TIMESTAMPTZ;
ALTER TABLE business_order ADD COLUMN dispute_resolved_by UUID;
ALTER TABLE business_order ADD COLUMN dispute_note        TEXT;

CREATE INDEX IF NOT EXISTS idx_business_order_status_created ON business_order (status, created_at);
CREATE INDEX IF NOT EXISTS idx_business_booking_status_slot ON business_booking (status, slot_start);

-- ---------------------------------------------------------------------------
-- Business verification requests (owner asks for the Verified badge)
-- ---------------------------------------------------------------------------
CREATE TABLE business_verification_request (
    id           UUID PRIMARY KEY,
    business_id  UUID        NOT NULL REFERENCES business (id) ON DELETE CASCADE,
    requested_by UUID,
    method       VARCHAR(12) NOT NULL CHECK (method IN ('PHONE', 'DOCUMENT', 'MANUAL')),
    document_ref TEXT,
    note         TEXT,
    status       VARCHAR(12) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'REVOKED')),
    reason       TEXT,
    reviewed_by  UUID,
    reviewed_at  TIMESTAMPTZ,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_verification_request_queue ON business_verification_request (status, created_at);
CREATE INDEX idx_verification_request_business ON business_verification_request (business_id, created_at DESC);

-- ---------------------------------------------------------------------------
-- Protected edits on VERIFIED businesses (name, phone, address, category)
-- ---------------------------------------------------------------------------
CREATE TABLE business_pending_change (
    id           UUID PRIMARY KEY,
    business_id  UUID        NOT NULL REFERENCES business (id) ON DELETE CASCADE,
    requested_by UUID,
    before_json  JSONB       NOT NULL,
    after_json   JSONB       NOT NULL,
    status       VARCHAR(12) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'SUPERSEDED')),
    reason       TEXT,
    reviewed_by  UUID,
    reviewed_at  TIMESTAMPTZ,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_pending_change_queue ON business_pending_change (status, created_at);
CREATE INDEX idx_pending_change_business ON business_pending_change (business_id, created_at DESC);

-- ---------------------------------------------------------------------------
-- Duplicate listings: merged slugs keep resolving; dismissed pairs stay dismissed
-- ---------------------------------------------------------------------------
CREATE TABLE business_slug_redirect (
    old_slug    VARCHAR(200) PRIMARY KEY,
    business_id UUID         NOT NULL REFERENCES business (id) ON DELETE CASCADE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE business_duplicate_dismissal (
    business_a UUID        NOT NULL REFERENCES business (id) ON DELETE CASCADE,
    business_b UUID        NOT NULL REFERENCES business (id) ON DELETE CASCADE,
    created_by UUID,
    reason     TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (business_a, business_b),
    CHECK (business_a < business_b)
);

CREATE INDEX IF NOT EXISTS ix_business_name_trgm ON business USING GIN (name gin_trgm_ops);

-- ---------------------------------------------------------------------------
-- Review filters
-- ---------------------------------------------------------------------------
CREATE INDEX IF NOT EXISTS idx_review_suspicion ON review (suspicion_score);
CREATE INDEX IF NOT EXISTS idx_review_user_created ON review (user_id, created_at);

-- ---------------------------------------------------------------------------
-- Homepage hero image goes through photo moderation
-- ---------------------------------------------------------------------------
ALTER TABLE photo_moderation DROP CONSTRAINT IF EXISTS photo_moderation_source_type_check;
ALTER TABLE photo_moderation ADD CONSTRAINT photo_moderation_source_type_check
    CHECK (source_type IN ('BUSINESS_PHOTO', 'COVER', 'LOGO', 'MENU_ITEM', 'POST', 'REVIEW', 'HERO'));

-- ---------------------------------------------------------------------------
-- Notifications for admin decisions (order/booking cancelled by support, offer
-- ended/hidden, verification and protected-edit outcomes)
-- ---------------------------------------------------------------------------
ALTER TABLE notification DROP CONSTRAINT IF EXISTS notification_type_check;
ALTER TABLE notification ADD CONSTRAINT notification_type_check CHECK (type IN (
    'REPORT_SUBMITTED', 'REPORT_ACTION_TAKEN', 'REPORT_DISMISSED', 'CONTENT_HIDDEN', 'LISTING_FLAGGED',
    'FLAG_REVIEW_REQUESTED', 'NEW_ORDER', 'ORDER_ACCEPTED', 'ORDER_REJECTED', 'ORDER_STATUS_CHANGED',
    'NEW_BOOKING', 'BOOKING_CONFIRMED', 'BOOKING_REJECTED', 'BOOKING_STATUS_CHANGED',
    'COMMUNITY_POST_COMMENT', 'COMMUNITY_POST_REACTION', 'COMMUNITY_POST_MENTION', 'COMMUNITY_COMMENT_REPLY',
    'COMMUNITY_BEST_ANSWER', 'OFFER_CLAIMED', 'OFFER_REDEEMED', 'OFFER_APPROVED', 'OFFER_REJECTED',
    'COMMUNITY_MODERATION', 'COMMUNITY_RESTRICTION', 'ADMIN_NOTICE'));
