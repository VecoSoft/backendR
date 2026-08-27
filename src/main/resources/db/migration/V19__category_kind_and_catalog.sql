-- =====================================================================
-- V19__category_kind_and_catalog.sql
-- Phase 2 — category-specific showcase modules.
--
--  1. category.kind : canonical classification driving which modules a
--     listing gets. Free-text category NAME is unchanged. NOT NULL with a
--     GENERAL default + a best-effort name-based backfill; admin-editable
--     afterward (Admin > Reference data > Categories).
--
--  2. Four lightweight, reusable showcase tables. No e-commerce / booking /
--     inventory — just profile content:
--       business_service      services / gym membership plans (section=OFFERING)
--                             and gym facilities (section=FACILITY)   — GENERAL/SALON/CLINIC/GYM
--       business_team_member  doctors / staff / trainers              — CLINIC/SALON/GYM
--       business_menu_item    menu (grouped by free-text menu_section) — RESTAURANT
--       business_product      featured products showcase              — RETAIL
--
--  All child rows are hard-linked to a live business; every read is by
--  business_id + sort_order (indexed). Nothing here is loaded by the main
--  GET /businesses/{slug} response — each public tab fetches its own list.
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. category.kind
-- ---------------------------------------------------------------------
ALTER TABLE category
    ADD COLUMN kind VARCHAR(20) NOT NULL DEFAULT 'GENERAL'
        CHECK (kind IN ('RESTAURANT','CLINIC','SALON','RETAIL','GYM','GENERAL'));

-- Best-effort backfill from the category name. Only touches rows still at the
-- GENERAL default, so the first matching rule wins and an admin override made
-- later is never clobbered by a re-run (idempotent-ish). GENERAL stays as the
-- fallback for anything unmatched.
UPDATE category SET kind = 'RESTAURANT' WHERE kind = 'GENERAL' AND name ~* '(restaurant|cafe|café|coffee|bakery|pizza|burger|biryani|biriyani|kebab|diner|eatery|food|dining|tea\s?house|juice\s?bar|ice\s?cream|dessert|sweets?|bistro|grill|buffet)';
UPDATE category SET kind = 'CLINIC'     WHERE kind = 'GENERAL' AND name ~* '(clinic|hospital|dental|dentist|doctor|medical|physio|diagnostic|health\s?care|pathology|orthodont|eye\s?care|optical|vision\s?care)';
UPDATE category SET kind = 'SALON'      WHERE kind = 'GENERAL' AND name ~* '(salon|parlou?r|spa|barber|beauty|hair|nail|makeup|make-up|grooming|skin\s?care|aesthetic)';
UPDATE category SET kind = 'GYM'        WHERE kind = 'GENERAL' AND name ~* '(gym|fitness|workout|cross\s?fit|yoga|pilates|martial\s?arts|boxing|dance\s?studio|aerobics)';
UPDATE category SET kind = 'RETAIL'     WHERE kind = 'GENERAL' AND name ~* '(shop|store|retail|mart|bazaar|bazar|boutique|electronics|mobile|grocery|super\s?market|fashion|clothing|apparel|hardware|furniture|jewel|book\s?shop|pharmacy|stationery)';

-- ---------------------------------------------------------------------
-- 2. showcase tables
-- ---------------------------------------------------------------------
CREATE TABLE business_service (
    id           UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    business_id  UUID NOT NULL REFERENCES business(id),
    section      VARCHAR(20) NOT NULL DEFAULT 'OFFERING'
                     CHECK (section IN ('OFFERING','FACILITY')),
    name         VARCHAR(160) NOT NULL,
    description  TEXT,
    price_text   VARCHAR(80),            -- freeform: "Starting from ৳500", "৳1,200", "Contact for quote"
    sort_order   INTEGER NOT NULL DEFAULT 0,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_business_service_business ON business_service(business_id, section, sort_order);

CREATE TABLE business_team_member (
    id           UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    business_id  UUID NOT NULL REFERENCES business(id),
    name         VARCHAR(160) NOT NULL,
    role         VARCHAR(160),           -- "Cardiologist", "Senior Stylist", "Head Trainer"
    bio          TEXT,
    photo_url    TEXT,
    sort_order   INTEGER NOT NULL DEFAULT 0,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_business_team_business ON business_team_member(business_id, sort_order);

CREATE TABLE business_menu_item (
    id            UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    business_id   UUID NOT NULL REFERENCES business(id),
    menu_section  VARCHAR(80),           -- free-text group label: "Starters", "Mains", "Drinks"
    name          VARCHAR(160) NOT NULL,
    description   TEXT,
    price_text    VARCHAR(80),
    photo_url     TEXT,
    is_popular    BOOLEAN NOT NULL DEFAULT FALSE,
    sort_order    INTEGER NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_business_menu_business ON business_menu_item(business_id, sort_order);

CREATE TABLE business_product (
    id            UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    business_id   UUID NOT NULL REFERENCES business(id),
    name          VARCHAR(160) NOT NULL,
    description   TEXT,
    price_text    VARCHAR(80),
    photo_url     TEXT,
    sort_order    INTEGER NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_business_product_business ON business_product(business_id, sort_order);
