-- =====================================================================
-- V24__derive_menu_ordering_from_price.sql
-- The per-item `ordering_enabled` flag is no longer a separate owner toggle
-- (it was a hassle: owners turned on "Accept direct orders" for the business
-- and then couldn't see why items still weren't orderable). An item is now
-- orderable whenever it is `available` and has a positive numeric `price`.
--
-- This backfills the column to match that rule so it stays consistent with
-- what the app now does (both the public menu gate and OrderService derive
-- orderability from price + availability, not from this flag).
-- =====================================================================
UPDATE business_menu_item
SET ordering_enabled = (price IS NOT NULL AND price > 0);
