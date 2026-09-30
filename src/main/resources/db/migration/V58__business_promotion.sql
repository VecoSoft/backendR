-- =====================================================================
-- V58 — Business Promotion: posting as a business, Auto Design Studio creatives,
-- offer → promo posts, share links, promotion analytics and paid Boost.
--
-- Design: a business post IS a community_post (author_business_id set) so it reuses votes,
-- comments, reports, pins and the whole V56 moderation stack. Promotion-only fields live in a
-- 1:1 sidecar (business_post) keyed by the post id, so community queries and indexes that
-- don't care about promotion are untouched.
--
-- Trust rules this schema deliberately does NOT touch: review, rating, business.verified and
-- search ranking columns. Nothing here is read by review/rating/search ranking code.
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. Posting as a business
-- ---------------------------------------------------------------------
ALTER TABLE community_post
    ADD COLUMN author_business_id UUID REFERENCES business (id);
CREATE INDEX idx_community_post_author_business ON community_post (author_business_id, created_at DESC)
    WHERE author_business_id IS NOT NULL;

-- A business may comment as itself, but only on its own posts (enforced in the service).
ALTER TABLE community_post_comment
    ADD COLUMN author_business_id UUID REFERENCES business (id);

-- Drafts are kept out of every feed/queue: a DRAFT community status is invisible to everyone but
-- the author (same visibility as PENDING, but not listed in the moderation queue).
ALTER TABLE community_post DROP CONSTRAINT chk_community_post_status;
ALTER TABLE community_post
    ADD CONSTRAINT chk_community_post_status CHECK (status IN ('ACTIVE', 'HIDDEN', 'REMOVED', 'PENDING', 'DRAFT'));

CREATE TABLE business_post (
    post_id          UUID PRIMARY KEY REFERENCES community_post (id) ON DELETE CASCADE,
    business_id      UUID        NOT NULL REFERENCES business (id),
    author_user_id   UUID        NOT NULL,
    type             VARCHAR(20) NOT NULL CHECK (type IN ('MENU_ITEM', 'OFFER', 'EVENT', 'ANNOUNCEMENT', 'GENERAL')),
    creative_id      UUID,
    offer_id         UUID REFERENCES offer (id),
    menu_item_id     UUID REFERENCES business_menu_item (id),
    event_start      TIMESTAMPTZ,
    event_end        TIMESTAMPTZ,
    status           VARCHAR(20) NOT NULL DEFAULT 'DRAFT'
        CHECK (status IN ('DRAFT', 'PENDING_REVIEW', 'PUBLISHED', 'REJECTED', 'REMOVED', 'EXPIRED')),
    rejection_reason TEXT,
    expires_at       TIMESTAMPTZ,
    published_at     TIMESTAMPTZ,
    interested_count INTEGER     NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_business_post_business ON business_post (business_id, created_at DESC);
CREATE INDEX idx_business_post_status ON business_post (status, created_at DESC);
CREATE INDEX idx_business_post_offer ON business_post (offer_id) WHERE offer_id IS NOT NULL;

-- "Interested" on EVENT posts — one row per user.
CREATE TABLE business_post_interest (
    post_id    UUID        NOT NULL REFERENCES business_post (post_id) ON DELETE CASCADE,
    user_id    UUID        NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (post_id, user_id)
);

-- ---------------------------------------------------------------------
-- 2. Auto Design Studio
-- ---------------------------------------------------------------------
CREATE TABLE promo_template (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    key             VARCHAR(40)  NOT NULL UNIQUE,
    name            VARCHAR(80)  NOT NULL,
    supported_types TEXT[]       NOT NULL,
    formats         TEXT[]       NOT NULL DEFAULT ARRAY['SQUARE', 'STORY', 'OG'],
    config_json     JSONB        NOT NULL DEFAULT '{}'::jsonb,
    sort_order      INTEGER      NOT NULL DEFAULT 0,
    active          BOOLEAN      NOT NULL DEFAULT TRUE,
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

INSERT INTO promo_template (key, name, supported_types, config_json, sort_order) VALUES
    ('OFFER_BOLD', 'Offer — bold', ARRAY['OFFER'],
     '{"layout":"offer-bold","slots":["offerText","photo","itemName","prices","validUntil","logo"],"colorRule":"accent-background"}', 10),
    ('MENU_HIGHLIGHT', 'Menu highlight', ARRAY['MENU_ITEM', 'OFFER'],
     '{"layout":"menu-highlight","slots":["photo","itemName","price","cta","rating"],"colorRule":"accent-band"}', 20),
    ('MINIMAL', 'Minimal', ARRAY['OFFER', 'MENU_ITEM', 'EVENT', 'ANNOUNCEMENT', 'GENERAL'],
     '{"layout":"minimal","slots":["photo","headline","logo","area"],"colorRule":"scrim"}', 30),
    ('EVENT_POSTER', 'Event poster', ARRAY['EVENT'],
     '{"layout":"event-poster","slots":["title","dateTime","location","photo","cta"],"colorRule":"accent-header"}', 40),
    ('RATING_SHOWCASE', 'Rating showcase', ARRAY['GENERAL', 'ANNOUNCEMENT', 'MENU_ITEM', 'OFFER', 'EVENT'],
     '{"layout":"rating-showcase","slots":["ratingBoxes","ratingLine","quote","logo"],"colorRule":"light"}', 50);

CREATE TABLE promo_creative (
    id           UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    business_id  UUID        NOT NULL REFERENCES business (id),
    template_key VARCHAR(40) NOT NULL,
    data_json    JSONB       NOT NULL DEFAULT '{}'::jsonb,
    square_url   TEXT,
    story_url    TEXT,
    og_url       TEXT,
    created_by   UUID        NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_promo_creative_business ON promo_creative (business_id, created_at DESC);

ALTER TABLE business_post
    ADD CONSTRAINT fk_business_post_creative FOREIGN KEY (creative_id) REFERENCES promo_creative (id);

-- AI caption quota — one row per business per Dhaka day.
CREATE TABLE promo_caption_usage (
    business_id UUID    NOT NULL REFERENCES business (id),
    day         DATE    NOT NULL,
    used        INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (business_id, day)
);

-- ---------------------------------------------------------------------
-- 3. Boost
-- ---------------------------------------------------------------------
CREATE TABLE boost_package (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    name            VARCHAR(80)   NOT NULL,
    price_bdt       NUMERIC(10,2) NOT NULL CHECK (price_bdt > 0),
    est_impressions INTEGER       NOT NULL CHECK (est_impressions > 0),
    duration_days   INTEGER       NOT NULL CHECK (duration_days BETWEEN 1 AND 90),
    max_radius_km   INTEGER       NOT NULL DEFAULT 10 CHECK (max_radius_km BETWEEN 1 AND 50),
    active          BOOLEAN       NOT NULL DEFAULT TRUE,
    sort_order      INTEGER       NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT now()
);

INSERT INTO boost_package (name, price_bdt, est_impressions, duration_days, max_radius_km, sort_order) VALUES
    ('Starter', 300, 1500, 3, 5, 10),
    ('Standard', 500, 4000, 7, 10, 20),
    ('Plus', 1200, 12000, 14, 10, 30);

CREATE TABLE boost (
    id                 UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    business_id        UUID          NOT NULL REFERENCES business (id),
    post_id            UUID          NOT NULL REFERENCES business_post (post_id),
    package_id         UUID          NOT NULL REFERENCES boost_package (id),
    -- Snapshot of the package at purchase time, so later package edits never change a paid boost.
    package_name       VARCHAR(80)   NOT NULL,
    est_impressions    INTEGER       NOT NULL,
    target_area_ids    UUID[]        NOT NULL DEFAULT '{}',
    center_lat         DOUBLE PRECISION,
    center_lng         DOUBLE PRECISION,
    radius_km          INTEGER,
    start_at           TIMESTAMPTZ   NOT NULL,
    end_at             TIMESTAMPTZ   NOT NULL,
    status             VARCHAR(20)   NOT NULL DEFAULT 'PENDING_PAYMENT'
        CHECK (status IN ('PENDING_PAYMENT', 'PENDING_REVIEW', 'ACTIVE', 'PAUSED', 'ENDED', 'REJECTED', 'REFUNDED')),
    price_bdt          NUMERIC(10,2) NOT NULL,
    payment_method     VARCHAR(10)   CHECK (payment_method IN ('BKASH', 'NAGAD', 'MANUAL')),
    payment_ref        VARCHAR(60),
    payment_submitted_at TIMESTAMPTZ,
    payment_verified_at  TIMESTAMPTZ,
    paid_amount        NUMERIC(10,2),
    approved_by        UUID,
    rejection_reason   TEXT,
    refund_reason      TEXT,
    impressions_served INTEGER       NOT NULL DEFAULT 0,
    created_by         UUID          NOT NULL,
    created_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CHECK (end_at > start_at),
    CHECK (cardinality(target_area_ids) > 0 OR (center_lat IS NOT NULL AND center_lng IS NOT NULL AND radius_km IS NOT NULL))
);
CREATE INDEX idx_boost_status_window ON boost (status, start_at, end_at);
CREATE INDEX idx_boost_business ON boost (business_id, created_at DESC);
-- One transaction id can only pay for one boost.
CREATE UNIQUE INDEX ux_boost_payment_ref ON boost (payment_method, payment_ref) WHERE payment_ref IS NOT NULL;

-- ---------------------------------------------------------------------
-- 4. Promotion analytics — no raw user id or IP is ever stored here.
-- ---------------------------------------------------------------------
CREATE TABLE promo_event (
    id           BIGSERIAL PRIMARY KEY,
    post_id      UUID        NOT NULL,
    boost_id     UUID,
    business_id  UUID        NOT NULL,
    event        VARCHAR(20) NOT NULL CHECK (event IN ('IMPRESSION', 'CLICK', 'PROFILE_VISIT', 'CALL', 'DIRECTIONS', 'MESSAGE',
                                                        'ORDER', 'BOOKING', 'OFFER_CLAIM', 'SHARE')),
    session_hash VARCHAR(64),
    source       VARCHAR(12) NOT NULL DEFAULT 'FEED' CHECK (source IN ('FEED', 'HOME', 'SEARCH', 'SHARE_LINK', 'EXTERNAL')),
    ref          VARCHAR(40),
    -- The order/claim/booking this conversion produced (attribution join, never a user id).
    target_id    UUID,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_promo_event_post ON promo_event (post_id, event, created_at);
CREATE INDEX idx_promo_event_boost ON promo_event (boost_id, event, created_at) WHERE boost_id IS NOT NULL;

-- Daily rollup, maintained on write (upsert), so dashboards never scan promo_event.
CREATE TABLE promo_stats_daily (
    day         DATE        NOT NULL,
    post_id     UUID        NOT NULL,
    boost_id    UUID        NOT NULL DEFAULT '00000000-0000-0000-0000-000000000000',
    business_id UUID        NOT NULL,
    event       VARCHAR(20) NOT NULL,
    count       INTEGER     NOT NULL DEFAULT 0,
    PRIMARY KEY (day, post_id, boost_id, event)
);
CREATE INDEX idx_promo_stats_business ON promo_stats_daily (business_id, day);

-- ---------------------------------------------------------------------
-- 5. Admin: suspend a business's promotion rights
-- ---------------------------------------------------------------------
CREATE TABLE promotion_restriction (
    id          UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    business_id UUID        NOT NULL REFERENCES business (id),
    reason      TEXT        NOT NULL,
    ends_at     TIMESTAMPTZ,
    lifted_at   TIMESTAMPTZ,
    created_by  UUID        NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_promotion_restriction_business ON promotion_restriction (business_id) WHERE lifted_at IS NULL;
