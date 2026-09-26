-- Owner-configurable quick-question shortcuts for the chat widget — same shape
-- family as business_faq (V42): a showcase list with manual ordering. `language`
-- distinguishes the system-seeded English/Bengali variants of a default question
-- (so the widget shows only the visitor's site-language one) from an owner's own
-- entry (language = NULL, shown regardless of site language).
CREATE TABLE business_auto_reply (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    business_id     UUID NOT NULL REFERENCES business(id),
    question        VARCHAR(300) NOT NULL,
    answer          TEXT NOT NULL,
    language        VARCHAR(10),
    system_default  BOOLEAN NOT NULL DEFAULT false,
    sort_order      INTEGER NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_business_auto_reply_business ON business_auto_reply(business_id, sort_order);
