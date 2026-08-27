-- =====================================================================
-- V22__collapse_categories_to_core_kinds.sql
-- Walk V21 back. The category picker is meant to be a SHORT list of broad
-- "kinds", each with its own set of showcase fields in the listing form --
-- not an exhaustive directory taxonomy.
--
-- End state: exactly six categories, one per CategoryKind (see V19). Every
-- existing business is first repointed to the core category matching its
-- current kind, so no listing loses its classification and business.category_id
-- (NOT NULL, the only FK into category) is never orphaned.
-- =====================================================================

-- 1. Make sure the six core categories exist.
INSERT INTO category (name, kind) VALUES
    ('Restaurant & Food',   'RESTAURANT'),
    ('Shop & Retail',       'RETAIL'),
    ('Clinic & Healthcare', 'CLINIC'),
    ('Salon & Beauty',      'SALON'),
    ('Gym & Fitness',       'GYM'),
    ('Services & Other',    'GENERAL')
ON CONFLICT (name) DO NOTHING;

-- 2. Repoint every business off a non-core category onto the core category
--    that shares its kind (GENERAL is the catch-all fallback).
UPDATE business b
SET category_id = core.id
FROM category old_cat, category core
WHERE b.category_id = old_cat.id
  AND old_cat.id <> core.id
  AND core.name = CASE old_cat.kind
        WHEN 'RESTAURANT' THEN 'Restaurant & Food'
        WHEN 'RETAIL'     THEN 'Shop & Retail'
        WHEN 'CLINIC'     THEN 'Clinic & Healthcare'
        WHEN 'SALON'      THEN 'Salon & Beauty'
        WHEN 'GYM'        THEN 'Gym & Fitness'
        ELSE 'Services & Other'
      END;

-- 3. Drop every category that isn't one of the six core rows. Nothing
--    references them any more after step 2.
DELETE FROM category
WHERE name NOT IN (
    'Restaurant & Food', 'Shop & Retail', 'Clinic & Healthcare',
    'Salon & Beauty', 'Gym & Fitness', 'Services & Other'
);
