-- =====================================================================
-- V27__booking_availability.sql
-- Real availability-based booking engine (Stage 1): staff qualification,
-- weekly working schedules + breaks + time-off, and a DB-level guarantee
-- that a staff member can never end up with two overlapping
-- PENDING/CONFIRMED bookings. Supersedes the advisory conflict-warning
-- heuristic shipped in V25/V26, which relied on app-level warnings only.
-- =====================================================================

CREATE EXTENSION IF NOT EXISTS btree_gist;

ALTER TABLE business_team_member ADD COLUMN active BOOLEAN NOT NULL DEFAULT TRUE;

ALTER TABLE business_service ADD COLUMN buffer_minutes INTEGER;

-- Which staff provide which service — only qualified staff are bookable for it.
CREATE TABLE staff_service (
    team_member_id  UUID NOT NULL REFERENCES business_team_member(id) ON DELETE CASCADE,
    service_id      UUID NOT NULL REFERENCES business_service(id) ON DELETE CASCADE,
    PRIMARY KEY (team_member_id, service_id)
);
CREATE INDEX ix_staff_service_service ON staff_service(service_id);

-- One working window + one optional break per staff member per day of week.
CREATE TABLE staff_weekly_schedule (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    team_member_id  UUID NOT NULL REFERENCES business_team_member(id) ON DELETE CASCADE,
    day_of_week     VARCHAR(10) NOT NULL
                        CHECK (day_of_week IN ('MONDAY','TUESDAY','WEDNESDAY','THURSDAY','FRIDAY','SATURDAY','SUNDAY')),
    start_time      TIME NOT NULL,
    end_time        TIME NOT NULL,
    break_start     TIME,
    break_end       TIME,
    CHECK (end_time > start_time),
    CHECK ((break_start IS NULL) = (break_end IS NULL)),
    CHECK (break_start IS NULL OR (break_end > break_start AND break_start >= start_time AND break_end <= end_time)),
    UNIQUE (team_member_id, day_of_week)
);

-- Leave / time-off date ranges — a staff member is unbookable on these dates.
CREATE TABLE staff_time_off (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    team_member_id  UUID NOT NULL REFERENCES business_team_member(id) ON DELETE CASCADE,
    start_date      DATE NOT NULL,
    end_date        DATE NOT NULL,
    reason          VARCHAR(200),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (end_date >= start_date)
);
CREATE INDEX ix_staff_time_off_member ON staff_time_off(team_member_id);

-- Snapshot the service's duration/buffer at booking time (matches the existing
-- name/price snapshot convention) so a later catalog edit never rewrites a past
-- booking's occupied window.
ALTER TABLE business_booking ADD COLUMN duration_minutes_snapshot INTEGER NOT NULL DEFAULT 60;
ALTER TABLE business_booking ADD COLUMN buffer_minutes_snapshot INTEGER NOT NULL DEFAULT 0;
ALTER TABLE business_booking ADD COLUMN auto_confirmed BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE business_booking ADD COLUMN slot_start TIMESTAMP;
ALTER TABLE business_booking ADD COLUMN slot_end TIMESTAMP;
UPDATE business_booking
   SET slot_start = preferred_date + preferred_time,
       slot_end   = preferred_date + preferred_time + INTERVAL '60 minutes'
 WHERE slot_start IS NULL;
ALTER TABLE business_booking ALTER COLUMN slot_start SET NOT NULL;
ALTER TABLE business_booking ALTER COLUMN slot_end SET NOT NULL;

ALTER TABLE business_booking ALTER COLUMN duration_minutes_snapshot DROP DEFAULT;
ALTER TABLE business_booking ALTER COLUMN buffer_minutes_snapshot DROP DEFAULT;

-- Generated, DB-owned — not mapped in the JPA entity.
ALTER TABLE business_booking
    ADD COLUMN busy_range tsrange GENERATED ALWAYS AS (tsrange(slot_start, slot_end, '[)')) STORED;

-- The actual guarantee: two PENDING/CONFIRMED bookings for the same staff member
-- can never have overlapping occupied windows. A NULL staff_id (legacy rows,
-- or never assigned) never conflicts under normal SQL NULL/equality semantics.
ALTER TABLE business_booking
    ADD CONSTRAINT no_staff_double_booking
    EXCLUDE USING gist (staff_id WITH =, busy_range WITH &&)
    WHERE (status IN ('PENDING','CONFIRMED'));

ALTER TABLE business_commerce_settings ADD COLUMN auto_confirm_bookings BOOLEAN NOT NULL DEFAULT FALSE;
