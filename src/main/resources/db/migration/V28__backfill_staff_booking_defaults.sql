-- =====================================================================
-- V28__backfill_staff_booking_defaults.sql
-- V27 introduced staff-service qualification and weekly working schedules
-- as hard requirements for a booking slot to exist. Before V27, any staff
-- member could perform any service with no schedule at all (the old
-- free-slot model) — so every business that already had booking enabled
-- suddenly has zero qualified/scheduled staff and booking goes silently
-- dead. This backfills sane defaults for exactly those already-live
-- businesses so they keep working; owners can narrow things down for real
-- afterward from the new Staff Schedules page.
-- =====================================================================

-- Every active staff member is assumed to provide every OFFERING service —
-- restores the old "no staff preference needed" behavior.
INSERT INTO staff_service (team_member_id, service_id)
SELECT tm.id, bs.id
FROM business_team_member tm
JOIN business_service bs ON bs.business_id = tm.business_id AND bs.section = 'OFFERING'
JOIN business_commerce_settings bcs ON bcs.business_id = tm.business_id AND bcs.booking_enabled = true
WHERE tm.active = true
ON CONFLICT DO NOTHING;

-- Any active staff member with literally no working-hours row yet gets a
-- wide-open default (every day, 10:00-20:00) rather than being unbookable
-- on every date. Only touches staff who have zero schedule rows already.
INSERT INTO staff_weekly_schedule (team_member_id, day_of_week, start_time, end_time)
SELECT tm.id, d.day, TIME '10:00', TIME '20:00'
FROM business_team_member tm
JOIN business_commerce_settings bcs ON bcs.business_id = tm.business_id AND bcs.booking_enabled = true
CROSS JOIN (VALUES ('MONDAY'), ('TUESDAY'), ('WEDNESDAY'), ('THURSDAY'), ('FRIDAY'), ('SATURDAY'), ('SUNDAY')) AS d(day)
WHERE tm.active = true
  AND NOT EXISTS (SELECT 1 FROM staff_weekly_schedule sw WHERE sw.team_member_id = tm.id);
