-- Smart search (com.bdreview.platform.search.SmartSearchRepository) matches query terms against
-- menu item and service names with ILIKE '%term%' / whole-word regex, term-first. pg_trgm GIN
-- indexes serve both operators, so those lookups stay index probes as menus grow instead of
-- scanning every menu row. business.name already has a GiST trigram index (V1).
CREATE INDEX IF NOT EXISTS ix_business_menu_item_name_trgm ON business_menu_item USING GIN (name gin_trgm_ops);
CREATE INDEX IF NOT EXISTS ix_business_service_name_trgm   ON business_service   USING GIN (name gin_trgm_ops);
