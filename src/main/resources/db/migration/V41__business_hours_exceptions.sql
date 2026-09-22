-- Date-range overrides on top of the recurring weekly hours (V38) — a holiday
-- closure (e.g. 3-day Eid) or modified/special hours on a specific date range.
-- An active exception always takes precedence over that weekday's recurring
-- entry (see lib/business-hours.ts getOpenStatus on the frontend). Distinct
-- from staff_time_off (V27), which is per-staff-member for booking, not
-- business-wide.
CREATE TABLE business_hours_exception (
    id            UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    business_id   UUID NOT NULL REFERENCES business(id) ON DELETE CASCADE,
    start_date    DATE NOT NULL,
    end_date      DATE NOT NULL,
    closed        BOOLEAN NOT NULL DEFAULT TRUE,
    open_time     TIME,
    close_time    TIME,
    reason        VARCHAR(200),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (end_date >= start_date),
    CHECK (closed = TRUE OR (open_time IS NOT NULL AND close_time IS NOT NULL AND close_time <> open_time))
);

CREATE INDEX idx_business_hours_exception_business ON business_hours_exception(business_id, start_date);
