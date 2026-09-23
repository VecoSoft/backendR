-- =====================================================================
-- V45__offer_menu_item_link.sql
-- An offer can optionally link to one existing menu item (business_menu_item)
-- instead of standing alone — while the offer is ACTIVE, that item's card
-- shows the offer's discounted price (computed at read time in
-- CatalogService#menu, never written back onto business_menu_item.price
-- itself, so the item's own listed price is exactly what it reverts to the
-- moment the offer ends — no revert bookkeeping needed).
-- =====================================================================

ALTER TABLE offer
    ADD COLUMN menu_item_id UUID REFERENCES business_menu_item(id);

CREATE INDEX idx_offer_menu_item ON offer (menu_item_id);
