-- =====================================================================
-- V26__service_duration.sql
-- Optional duration on a service/offering — used to give the Phase C
-- booking-conflict check a real time window instead of a single instant.
-- Nullable: showcase-only services never need to set it; BookingService
-- falls back to a default when it's unset.
-- =====================================================================
ALTER TABLE business_service ADD COLUMN duration_minutes INTEGER;
