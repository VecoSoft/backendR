-- Public owner reply to a review (Google/Yelp-style "Response from the owner"),
-- distinct from the private message thread — a visible trust signal other
-- visitors see, not just a reply channel for the reviewer. Nullable: most
-- reviews will have no reply.
ALTER TABLE review
    ADD COLUMN owner_reply      TEXT,
    ADD COLUMN owner_replied_at TIMESTAMPTZ;
