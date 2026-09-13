-- =====================================================================
-- V29__staff_duration_override_and_booking_progress.sql
-- Per-staff duration/buffer override for a service (a staff member may be
-- faster/slower than the service's own default), and a live "in progress"
-- marker on a booking so a waiting customer can see a real queue position
-- and ETA instead of just the static schedule.
-- =====================================================================

ALTER TABLE staff_service ADD COLUMN duration_minutes INTEGER;
ALTER TABLE staff_service ADD COLUMN buffer_minutes INTEGER;

-- Nullable: set only when the owner taps "Start" on a CONFIRMED booking.
-- Not a new BookingStatus — the booking stays CONFIRMED until COMPLETED.
ALTER TABLE business_booking ADD COLUMN started_at TIMESTAMPTZ;
