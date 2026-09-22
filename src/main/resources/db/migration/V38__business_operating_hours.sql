-- Structured per-day business hours, replacing the free-text-only operating_hours
-- column (business.operating_hours stays as-is for legacy display/back-compat;
-- new/edited businesses populate this table too so "open now" can be computed).
-- Mirrors staff_weekly_schedule (V27) but at business granularity, no breaks.
--
-- close_time <= open_time means the window crosses midnight (e.g. open=20:00,
-- close=02:00 covers 8pm today through 2am the next day) — there's no separate
-- "crosses midnight" flag, it's inferred from the times themselves. Only exact
-- equality is rejected (ambiguous between "closed" and "open 24h"); 24-hour-open
-- is represented as open=00:00/close=23:30, matching the picker's existing preset.
CREATE TABLE business_operating_hours (
    id            UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    business_id   UUID NOT NULL REFERENCES business(id) ON DELETE CASCADE,
    day_of_week   VARCHAR(10) NOT NULL
                    CHECK (day_of_week IN ('MONDAY','TUESDAY','WEDNESDAY','THURSDAY','FRIDAY','SATURDAY','SUNDAY')),
    closed        BOOLEAN NOT NULL DEFAULT FALSE,
    open_time     TIME,
    close_time    TIME,
    CHECK (closed = TRUE OR (open_time IS NOT NULL AND close_time IS NOT NULL AND close_time <> open_time)),
    UNIQUE (business_id, day_of_week)
);

CREATE INDEX idx_business_operating_hours_business ON business_operating_hours(business_id);
