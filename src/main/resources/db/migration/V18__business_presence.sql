-- =====================================================================
-- V18__business_presence.sql
-- "Business presence" (spec: Listing + lightweight mini-website, Step 4).
-- Optional ways for customers to reach a business online. All nullable —
-- a listing that sets none of these renders and behaves exactly as before,
-- and empty values are never shown on the public page.
-- =====================================================================

ALTER TABLE business
    ADD COLUMN website_url     TEXT,
    ADD COLUMN whatsapp_number VARCHAR(20),   -- E.164 normalized, same as contact_number
    ADD COLUMN email           VARCHAR(255),
    ADD COLUMN facebook_url    TEXT,
    ADD COLUMN instagram_url   TEXT;
