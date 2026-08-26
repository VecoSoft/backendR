-- =====================================================================
-- V20__updates_and_events.sql
-- Phase 3 — three small, additive pieces:
--
--  1. business_update : lightweight owner announcements ("Open until 11 PM",
--     "20% off this weekend"). Text + optional image + published flag.
--     NOT a social feed — no likes / comments / followers / cross-business feed.
--
--  2. business_event : minimal first-party performance tracking. One row per
--     tracked interaction. NO IP, NO device fingerprint, NO user id — just an
--     opaque session_id (nullable) used solely to de-duplicate PROFILE_VIEW.
--     Dashboard reads are aggregate COUNT ... GROUP BY, never row scans.
--
--  (Profile completeness is computed on the fly from existing data — no table.)
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. business_update
-- ---------------------------------------------------------------------
CREATE TABLE business_update (
    id            UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    business_id   UUID NOT NULL REFERENCES business(id),
    body          TEXT NOT NULL,
    image_url     TEXT,
    published     BOOLEAN NOT NULL DEFAULT TRUE,
    published_at  TIMESTAMPTZ,                       -- set whenever published flips to true
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
-- Public list: published rows for one business, newest first.
CREATE INDEX ix_business_update_public
    ON business_update(business_id, published_at DESC)
    WHERE published = TRUE;
-- Owner "manage" list: every row for one business.
CREATE INDEX ix_business_update_owner ON business_update(business_id, created_at DESC);

-- ---------------------------------------------------------------------
-- 2. business_event
-- ---------------------------------------------------------------------
CREATE TABLE business_event (
    id           UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    business_id  UUID NOT NULL REFERENCES business(id),
    event_type   VARCHAR(20) NOT NULL
                     CHECK (event_type IN ('PROFILE_VIEW','PHONE_CLICK','WHATSAPP_CLICK',
                                           'DIRECTIONS_CLICK','WEBSITE_CLICK')),
    session_id   VARCHAR(64),                        -- opaque, client-generated; dedupe only, not PII
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
-- Dashboard aggregation: WHERE business_id = ? AND created_at >= ? GROUP BY event_type.
CREATE INDEX ix_business_event_agg ON business_event(business_id, event_type, created_at);
-- PROFILE_VIEW dedupe probe: (business_id, session_id, event_type, created_at).
CREATE INDEX ix_business_event_dedupe ON business_event(business_id, session_id, created_at)
    WHERE session_id IS NOT NULL;
