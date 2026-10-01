-- V62: premium Design Studio templates, listed right after "Your own design".
--   LUXE      — near-black, champagne-gold hairlines, arch-framed photo (fine dining, salons).
--   MAGAZINE  — full-bleed photo "cover" with a masthead, cover lines and a price badge.
--   GLASS     — vivid gradient with a frosted card (offers, launches).
--   POLAROID  — a tilted instant-photo print on warm paper (cafés, bakeries, home kitchens).
-- Facts (prices, offer terms, ratings) still come only from the render model, like every template.
INSERT INTO promo_template (key, name, supported_types, config_json, sort_order) VALUES
    ('LUXE', 'Luxe', ARRAY['OFFER', 'MENU_ITEM', 'EVENT', 'ANNOUNCEMENT', 'GENERAL'],
     '{"layout":"luxe","slots":["archPhoto","headline","subline","price","logo"],"colorRule":"dark-gold"}', 6),
    ('MAGAZINE', 'Magazine cover', ARRAY['OFFER', 'MENU_ITEM', 'EVENT', 'ANNOUNCEMENT', 'GENERAL'],
     '{"layout":"magazine","slots":["photo","masthead","headline","priceBadge"],"colorRule":"photo-scrim"}', 7),
    ('GLASS', 'Glass', ARRAY['OFFER', 'MENU_ITEM', 'EVENT', 'ANNOUNCEMENT', 'GENERAL'],
     '{"layout":"glass","slots":["photo","headline","subline","pricePill","logo"],"colorRule":"vivid-gradient"}', 8),
    ('POLAROID', 'Polaroid', ARRAY['OFFER', 'MENU_ITEM', 'EVENT', 'ANNOUNCEMENT', 'GENERAL'],
     '{"layout":"polaroid","slots":["photo","caption","price","logo"],"colorRule":"warm-paper"}', 9)
ON CONFLICT (key) DO NOTHING;
