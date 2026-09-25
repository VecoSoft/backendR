-- Orders placed through checkout never recorded which offer (if any) priced a line, so an
-- offer's claim_count/redemption_count only ever reflected the separate in-person
-- claim/redeem-code flow — an offer linked to a menu item and used by real online orders
-- still showed "0 redemptions", and maxTotalRedemptions/maxRedemptionsPerUser were never
-- enforced for that path at all. This column lets OrderService track and cap that usage.
ALTER TABLE business_order_item
    ADD COLUMN offer_id UUID;
