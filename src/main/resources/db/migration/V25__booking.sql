-- =====================================================================
-- V25__booking.sql
-- Category-wise Commerce & Fulfillment — Phase C: appointment booking
-- (currently Salon & Beauty). Free-slot, owner-confirmed model: the customer
-- proposes a service (+ optional staff) and a preferred date/time; the
-- business confirms, rejects, or marks it completed/no-show. No real-time
-- slot-availability engine — same "Jachai manages the request, the business
-- controls fulfilment" principle as the order/service-request phases.
--
-- Reuses business_commerce_settings (mode=BOOKING, the already-present
-- booking_enabled flag, and the existing accepting_orders/pause_reason pause
-- switch) — no changes to that table.
-- =====================================================================

CREATE SEQUENCE booking_number_seq START 1000;

CREATE TABLE business_booking (
    id                       UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    business_id              UUID NOT NULL REFERENCES business(id),
    customer_user_id         UUID NOT NULL REFERENCES app_user(id),
    booking_number           VARCHAR(20) NOT NULL UNIQUE,
    status                   VARCHAR(16) NOT NULL DEFAULT 'PENDING'
                                 CHECK (status IN ('PENDING','CONFIRMED','COMPLETED','CANCELLED','REJECTED','NO_SHOW')),
    service_id               UUID,
    service_name_snapshot    VARCHAR(160) NOT NULL,
    staff_id                 UUID,
    staff_name_snapshot      VARCHAR(160),
    preferred_date           DATE NOT NULL,
    preferred_time           TIME NOT NULL,
    customer_name_snapshot   VARCHAR(120) NOT NULL,
    customer_phone_snapshot  VARCHAR(20) NOT NULL,
    customer_note            TEXT,
    rejection_reason         VARCHAR(200),
    created_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at               TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_business_booking_business ON business_booking(business_id, status, preferred_date);
CREATE INDEX ix_business_booking_customer ON business_booking(customer_user_id, created_at DESC);

CREATE TABLE booking_status_event (
    id             UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    booking_id     UUID NOT NULL REFERENCES business_booking(id) ON DELETE CASCADE,
    from_status    VARCHAR(16),
    to_status      VARCHAR(16) NOT NULL,
    actor_user_id  UUID,
    note           VARCHAR(200),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_booking_status_event_booking ON booking_status_event(booking_id, created_at);

-- Notification types for the booking lifecycle (widen-the-CHECK, per V8/V10/V23)
ALTER TABLE notification DROP CONSTRAINT notification_type_check;
ALTER TABLE notification ADD CONSTRAINT notification_type_check
    CHECK (type IN ('REPORT_SUBMITTED', 'REPORT_ACTION_TAKEN', 'REPORT_DISMISSED', 'CONTENT_HIDDEN',
                    'LISTING_FLAGGED', 'FLAG_REVIEW_REQUESTED',
                    'NEW_ORDER', 'ORDER_ACCEPTED', 'ORDER_REJECTED', 'ORDER_STATUS_CHANGED',
                    'NEW_BOOKING', 'BOOKING_CONFIRMED', 'BOOKING_REJECTED', 'BOOKING_STATUS_CHANGED'));
