-- Brand → Branches: groups multiple independent Business rows into one chain
-- (e.g. "KFC"). Soft-deleted like business (never hard-deleted), same
-- slug-uniqueness shape as ux_business_slug_live (V1).
CREATE TABLE brand (
    id          UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    name        VARCHAR(255) NOT NULL,
    slug        VARCHAR(280) NOT NULL,
    logo_url    TEXT,
    deleted_at  TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX ux_brand_slug_live ON brand(slug) WHERE deleted_at IS NULL;
