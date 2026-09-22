-- =====================================================================
-- V36__offers.sql
-- "Offers / Discounts" — verified businesses publish time-boxed discounts;
-- users discover/claim (in-store, via a unique redemption code) or get
-- deep-linked to the business's existing ordering flow (online). Status
-- lifecycle (DRAFT/PENDING_APPROVAL/ACTIVE/EXPIRED/CANCELLED/REJECTED)
-- mirrors the existing BusinessClaim admin-approval precedent; expiry is
-- derived from valid_until at query/claim time, not cron-flipped — no new
-- scheduled-job infrastructure. Claim and redemption share one row
-- (community_post_comment_vote-style 1:1 relationship, not two tables).
-- =====================================================================

CREATE TABLE offer (
    id                        UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    business_id               UUID NOT NULL REFERENCES business(id),
    title                     VARCHAR(150) NOT NULL,
    offer_type                VARCHAR(30) NOT NULL
        CHECK (offer_type IN ('PERCENTAGE_DISCOUNT', 'FIXED_AMOUNT_DISCOUNT', 'BUY_ONE_GET_ONE',
                               'COMBO_DEAL', 'FREE_ITEM', 'OTHER')),
    discount_value            NUMERIC(10,2),
    original_price            NUMERIC(10,2),
    offer_price               NUMERIC(10,2),
    description               TEXT,
    terms_and_conditions      TEXT,
    image_url                 TEXT,
    valid_from                TIMESTAMPTZ NOT NULL,
    valid_until               TIMESTAMPTZ NOT NULL,
    availability               VARCHAR(10) NOT NULL DEFAULT 'BOTH'
        CHECK (availability IN ('ONLINE', 'IN_STORE', 'BOTH')),
    status                    VARCHAR(20) NOT NULL DEFAULT 'DRAFT'
        CHECK (status IN ('DRAFT', 'PENDING_APPROVAL', 'ACTIVE', 'EXPIRED', 'CANCELLED', 'REJECTED')),
    max_total_redemptions     INTEGER,
    max_redemptions_per_user  INTEGER,
    view_count                INTEGER NOT NULL DEFAULT 0,
    claim_count                INTEGER NOT NULL DEFAULT 0,
    redemption_count          INTEGER NOT NULL DEFAULT 0,
    rejection_reason          TEXT,
    created_at                TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_offer_business ON offer (business_id);
CREATE INDEX idx_offer_status_valid_until ON offer (status, valid_until);

CREATE TABLE offer_claim (
    id                   UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    offer_id             UUID NOT NULL REFERENCES offer(id),
    user_id              UUID NOT NULL REFERENCES app_user(id),
    redemption_code      VARCHAR(16) NOT NULL UNIQUE,
    status               VARCHAR(10) NOT NULL DEFAULT 'CLAIMED'
        CHECK (status IN ('CLAIMED', 'REDEEMED', 'EXPIRED', 'CANCELLED')),
    claimed_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    redeemed_at          TIMESTAMPTZ,
    redeemed_by_user_id  UUID REFERENCES app_user(id)
);
CREATE INDEX idx_offer_claim_offer_user ON offer_claim (offer_id, user_id);

CREATE TABLE offer_save (
    id          UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id     UUID NOT NULL REFERENCES app_user(id),
    offer_id    UUID NOT NULL REFERENCES offer(id),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_offer_save UNIQUE (user_id, offer_id)
);

ALTER TABLE report DROP CONSTRAINT report_target_type_check;
ALTER TABLE report ADD CONSTRAINT report_target_type_check
    CHECK (target_type IN ('REVIEW', 'LISTING', 'COMMUNITY_POST', 'COMMUNITY_COMMENT', 'OFFER'));

ALTER TABLE notification DROP CONSTRAINT notification_type_check;
ALTER TABLE notification ADD CONSTRAINT notification_type_check
    CHECK (type IN ('REPORT_SUBMITTED', 'REPORT_ACTION_TAKEN', 'REPORT_DISMISSED', 'CONTENT_HIDDEN',
                    'LISTING_FLAGGED', 'FLAG_REVIEW_REQUESTED',
                    'NEW_ORDER', 'ORDER_ACCEPTED', 'ORDER_REJECTED', 'ORDER_STATUS_CHANGED',
                    'NEW_BOOKING', 'BOOKING_CONFIRMED', 'BOOKING_REJECTED', 'BOOKING_STATUS_CHANGED',
                    'COMMUNITY_POST_COMMENT', 'COMMUNITY_POST_REACTION', 'COMMUNITY_POST_MENTION',
                    'COMMUNITY_COMMENT_REPLY', 'COMMUNITY_BEST_ANSWER',
                    'OFFER_CLAIMED', 'OFFER_REDEEMED', 'OFFER_APPROVED', 'OFFER_REJECTED'));
