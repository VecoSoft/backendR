-- =====================================================================
-- V17__two_account_model.sql
-- Consumer and business-owner become genuinely separate accounts (Yelp-
-- style: yelp.com vs biz.yelp.com), instead of one account switching an
-- internal "mode". A phone number may now back at most one CONSUMER row
-- and at most one BUSINESS_OWNER row (never two of the same role), and the
-- two can be linked for a frictionless switch. V16's single-account
-- "owner persona" table is no longer needed — an owner's identity is now
-- simply its own account's name/photo.
-- =====================================================================

ALTER TABLE app_user DROP CONSTRAINT app_user_phone_number_key;
ALTER TABLE app_user ADD CONSTRAINT app_user_phone_role_key UNIQUE (phone_number, role);

CREATE TABLE account_link (
    id                 UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    consumer_user_id   UUID NOT NULL UNIQUE REFERENCES app_user(id),
    business_user_id   UUID NOT NULL UNIQUE REFERENCES app_user(id),
    linked_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

DROP TABLE owner_profile;
