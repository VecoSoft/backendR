-- =====================================================================
-- V23__commerce_direct_order.sql
-- Category-wise Commerce & Fulfillment — Phase A: RESTAURANT direct ordering.
--
-- Jachai never runs a delivery fleet. A business accepts orders and fulfils
-- them with its own staff/process. This migration adds:
--   * numeric price + availability + ordering toggle on menu items
--     (alongside the existing freeform price_text — showcase is unchanged)
--   * business_commerce_settings : per-business commerce mode + fulfilment +
--     payment options + accepting/paused switch (also carries booking_enabled
--     / service_request_enabled flags that Phases C/D flip on, no schema change)
--   * delivery_zone : distance-band delivery pricing (PostGIS distance only;
--     future polygon/area/schedule models are additive)
--   * business_order / business_order_item / order_status_event : the order
--     itself, with price/name snapshots so a later price change never rewrites
--     history, plus a full status-transition audit trail
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. Menu items gain real ordering fields (showcase behaviour untouched)
-- ---------------------------------------------------------------------
ALTER TABLE business_menu_item
    ADD COLUMN price            NUMERIC(10,2),
    ADD COLUMN available        BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN ordering_enabled BOOLEAN NOT NULL DEFAULT FALSE;

-- ---------------------------------------------------------------------
-- 2. Per-business commerce settings (1:1, lazily created by the owner)
-- ---------------------------------------------------------------------
CREATE TABLE business_commerce_settings (
    business_id              UUID PRIMARY KEY REFERENCES business(id),
    mode                     VARCHAR(20) NOT NULL DEFAULT 'SHOWCASE_ONLY'
                                 CHECK (mode IN ('SHOWCASE_ONLY','DIRECT_ORDER','BOOKING','SERVICE_REQUEST')),
    ordering_enabled         BOOLEAN NOT NULL DEFAULT FALSE,
    booking_enabled          BOOLEAN NOT NULL DEFAULT FALSE,
    service_request_enabled  BOOLEAN NOT NULL DEFAULT FALSE,
    pickup_enabled           BOOLEAN NOT NULL DEFAULT FALSE,
    own_delivery_enabled     BOOLEAN NOT NULL DEFAULT FALSE,
    accepting_orders         BOOLEAN NOT NULL DEFAULT TRUE,
    pause_reason             VARCHAR(200),
    default_prep_minutes     INTEGER,
    payment_cash_on_delivery BOOLEAN NOT NULL DEFAULT TRUE,
    payment_pay_at_business   BOOLEAN NOT NULL DEFAULT TRUE,
    created_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at               TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------------
-- 3. Distance-band delivery zones (the business's own delivery reach)
-- ---------------------------------------------------------------------
CREATE TABLE delivery_zone (
    id                          UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    business_id                 UUID NOT NULL REFERENCES business(id),
    name                        VARCHAR(80) NOT NULL,
    min_distance_km             NUMERIC(5,2) NOT NULL,
    max_distance_km             NUMERIC(5,2) NOT NULL,
    delivery_fee                NUMERIC(10,2) NOT NULL,
    minimum_order_amount        NUMERIC(10,2) NOT NULL DEFAULT 0,
    estimated_delivery_minutes  INTEGER,
    active                      BOOLEAN NOT NULL DEFAULT TRUE,
    sort_order                  INTEGER NOT NULL DEFAULT 0,
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (min_distance_km >= 0 AND max_distance_km > min_distance_km)
);
CREATE INDEX ix_delivery_zone_business ON delivery_zone(business_id, sort_order);

-- ---------------------------------------------------------------------
-- 4. Orders
-- ---------------------------------------------------------------------
CREATE SEQUENCE order_number_seq START 1000;

CREATE TABLE business_order (
    id                       UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    business_id              UUID NOT NULL REFERENCES business(id),
    customer_user_id         UUID NOT NULL REFERENCES app_user(id),
    order_number             VARCHAR(20) NOT NULL UNIQUE,
    status                   VARCHAR(24) NOT NULL DEFAULT 'PENDING'
                                 CHECK (status IN ('PENDING','ACCEPTED','PREPARING','READY_FOR_PICKUP',
                                                   'OUT_FOR_DELIVERY','PICKED_UP','DELIVERED','COMPLETED',
                                                   'REJECTED','CANCELLED')),
    fulfillment_type         VARCHAR(16) NOT NULL CHECK (fulfillment_type IN ('PICKUP','OWN_DELIVERY')),
    subtotal                 NUMERIC(10,2) NOT NULL,
    delivery_fee             NUMERIC(10,2) NOT NULL DEFAULT 0,
    discount_amount          NUMERIC(10,2) NOT NULL DEFAULT 0,
    total_amount             NUMERIC(10,2) NOT NULL,
    payment_method           VARCHAR(20) NOT NULL CHECK (payment_method IN ('CASH_ON_DELIVERY','PAY_AT_BUSINESS')),
    payment_status           VARCHAR(16) NOT NULL DEFAULT 'UNPAID' CHECK (payment_status IN ('UNPAID','PAID')),
    customer_name_snapshot   VARCHAR(120) NOT NULL,
    customer_phone_snapshot  VARCHAR(20) NOT NULL,
    delivery_address         TEXT,
    delivery_location        geography(Point,4326),
    delivery_distance_km     NUMERIC(6,2),
    delivery_zone_id         UUID REFERENCES delivery_zone(id),
    customer_note            TEXT,
    rejection_reason         VARCHAR(200),
    created_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at               TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_business_order_business ON business_order(business_id, status, created_at DESC);
CREATE INDEX ix_business_order_customer ON business_order(customer_user_id, created_at DESC);

CREATE TABLE business_order_item (
    id                   UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    order_id             UUID NOT NULL REFERENCES business_order(id) ON DELETE CASCADE,
    source_type          VARCHAR(16) NOT NULL DEFAULT 'MENU_ITEM'
                             CHECK (source_type IN ('MENU_ITEM','PRODUCT')),
    source_item_id       UUID,
    item_name_snapshot   VARCHAR(160) NOT NULL,
    unit_price_snapshot  NUMERIC(10,2) NOT NULL,
    quantity             INTEGER NOT NULL CHECK (quantity > 0),
    total_price          NUMERIC(10,2) NOT NULL
);
CREATE INDEX ix_business_order_item_order ON business_order_item(order_id);

CREATE TABLE order_status_event (
    id             UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    order_id       UUID NOT NULL REFERENCES business_order(id) ON DELETE CASCADE,
    from_status    VARCHAR(24),
    to_status      VARCHAR(24) NOT NULL,
    actor_user_id  UUID,
    note           VARCHAR(200),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_order_status_event_order ON order_status_event(order_id, created_at);

-- ---------------------------------------------------------------------
-- 5. Notification types for the order lifecycle (widen-the-CHECK, per V8/V10)
-- ---------------------------------------------------------------------
ALTER TABLE notification DROP CONSTRAINT notification_type_check;
ALTER TABLE notification ADD CONSTRAINT notification_type_check
    CHECK (type IN ('REPORT_SUBMITTED', 'REPORT_ACTION_TAKEN', 'REPORT_DISMISSED', 'CONTENT_HIDDEN',
                    'LISTING_FLAGGED', 'FLAG_REVIEW_REQUESTED',
                    'NEW_ORDER', 'ORDER_ACCEPTED', 'ORDER_REJECTED', 'ORDER_STATUS_CHANGED'));
