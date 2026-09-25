-- Nullable, set independently per row — deliberately NOT part of the ownership
-- model (business.owner_user_id is unaffected; two branches of the same brand
-- can legitimately be owned by two different accounts).
ALTER TABLE business ADD COLUMN brand_id UUID REFERENCES brand(id);

CREATE INDEX ix_business_brand_id ON business(brand_id) WHERE deleted_at IS NULL;
