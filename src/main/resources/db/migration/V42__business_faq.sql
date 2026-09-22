-- Owner-managed FAQ list — business-wide (not category-scoped), same shape
-- family as business_product (V19): a showcase list with manual ordering.
CREATE TABLE business_faq (
    id            UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    business_id   UUID NOT NULL REFERENCES business(id),
    question      VARCHAR(300) NOT NULL,
    answer        TEXT NOT NULL,
    sort_order    INTEGER NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_business_faq_business ON business_faq(business_id, sort_order);
