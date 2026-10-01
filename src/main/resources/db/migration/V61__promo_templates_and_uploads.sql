-- V61: more Design Studio templates + "Your own design".
--   CUSTOM            — the owner's own uploaded banner, placed into every format (no text drawn on it).
--   SPLIT_PHOTO       — photo + clean text panel.
--   PRICE_SPOTLIGHT   — a big price tag for a menu item or offer.
--   FESTIVE           — Eid / Puja / Pohela Boishakh style greeting or offer.
--   BIG_ANNOUNCEMENT  — huge headline on the brand colour; works with no photo at all.
-- Owner uploads live under promo/<businessId>/uploads/ (see promo.PromoUploads); admins control
-- them with the uploadsEnabled / uploadedImagesRequireApproval promotion settings.
INSERT INTO promo_template (key, name, supported_types, config_json, sort_order) VALUES
    ('CUSTOM', 'Your own design', ARRAY['OFFER', 'MENU_ITEM', 'EVENT', 'ANNOUNCEMENT', 'GENERAL'],
     '{"layout":"custom","slots":["uploadedImage"],"colorRule":"accent-letterbox"}', 5),
    ('SPLIT_PHOTO', 'Photo split', ARRAY['OFFER', 'MENU_ITEM', 'EVENT', 'ANNOUNCEMENT', 'GENERAL'],
     '{"layout":"split-photo","slots":["photo","headline","subline","prices","logo"],"colorRule":"accent-rule"}', 60),
    ('PRICE_SPOTLIGHT', 'Price spotlight', ARRAY['MENU_ITEM', 'OFFER'],
     '{"layout":"price-spotlight","slots":["photo","itemName","priceTag","validUntil","logo"],"colorRule":"accent-background"}', 70),
    ('FESTIVE', 'Festive', ARRAY['GENERAL', 'ANNOUNCEMENT', 'OFFER', 'EVENT'],
     '{"layout":"festive","slots":["headline","subline","logo","offerText"],"colorRule":"deep-with-gold"}', 80),
    ('BIG_ANNOUNCEMENT', 'Big announcement', ARRAY['ANNOUNCEMENT', 'GENERAL', 'EVENT', 'OFFER'],
     '{"layout":"big-announcement","slots":["headline","subline","logo","area"],"colorRule":"accent-background"}', 90)
ON CONFLICT (key) DO NOTHING;
