-- Append-only reaction event log, separate from business_reaction (V12), which
-- is a current-state table: BusinessService#react hard-deletes a row on
-- un-react, so business_reaction.created_at can't answer "how much reaction
-- activity happened in the last N days" — it only reflects reactions still
-- active right now. This table is written once per "add" (never on remove,
-- never updated/deleted), giving an accurate time-windowed count for a
-- "trending this week" sort without disturbing the existing toggle behavior.

CREATE TABLE business_reaction_event (
    id            UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    business_id   UUID NOT NULL REFERENCES business(id),
    user_id       UUID NOT NULL REFERENCES app_user(id),
    reaction_type VARCHAR(10) NOT NULL CHECK (reaction_type IN ('LIKE','DISLIKE','LOVE','WOW')),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_business_reaction_event_business_created ON business_reaction_event(business_id, created_at);
