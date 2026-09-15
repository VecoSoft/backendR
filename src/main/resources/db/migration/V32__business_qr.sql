-- =====================================================================
-- V30__business_qr.sql
-- Business QR V1: one permanent Jachai QR per business. A QR encodes an
-- opaque token that resolves to a business id, never a slug — so the QR
-- keeps working even after the owner/admin changes the business's slug.
-- =====================================================================

CREATE TABLE business_qr (
    id           UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    business_id  UUID NOT NULL UNIQUE REFERENCES business(id),
    qr_token     VARCHAR(32) NOT NULL UNIQUE,
    status       VARCHAR(10) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'INACTIVE')),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX ix_business_qr_token ON business_qr(qr_token);

-- QR_SCAN joins the existing first-party analytics event types (see V20).
ALTER TABLE business_event DROP CONSTRAINT business_event_event_type_check;
ALTER TABLE business_event ADD CONSTRAINT business_event_event_type_check
    CHECK (event_type IN ('PROFILE_VIEW', 'PHONE_CLICK', 'WHATSAPP_CLICK',
                          'DIRECTIONS_CLICK', 'WEBSITE_CLICK', 'QR_SCAN'));
