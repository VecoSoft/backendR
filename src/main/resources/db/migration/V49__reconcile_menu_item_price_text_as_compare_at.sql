-- Before compare_at_price existed (V47), owners had no field for a "was" price, so some
-- typed the old price straight into price_text as a bare number (e.g. "280") while the
-- real order price ("price") already reflected the discount. business-menu.tsx has always
-- ignored price_text once a numeric price is set, so that value was silently never shown
-- to customers at all — the owner saw one number, the customer paid another, with no
-- struck-through price to explain the gap. Reconcile: a purely numeric price_text greater
-- than price was never a real display label, it was a misplaced "was" price — move it into
-- compare_at_price (the one field business-menu.tsx actually reads for this) and clear it.
UPDATE business_menu_item
SET compare_at_price = price_text::numeric,
    price_text = NULL
WHERE price IS NOT NULL
  AND compare_at_price IS NULL
  AND price_text ~ '^[0-9]+(\.[0-9]+)?$'
  AND price_text::numeric > price;
