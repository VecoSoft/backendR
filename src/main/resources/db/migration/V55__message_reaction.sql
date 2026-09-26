-- One reaction per (message, user) — see MessageReaction.java / MessageService#react.
CREATE TABLE message_reaction (
    id          UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    message_id  UUID NOT NULL REFERENCES message(id),
    user_id     UUID NOT NULL REFERENCES app_user(id),
    emoji       VARCHAR(16) NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (message_id, user_id)
);
CREATE INDEX ix_message_reaction_message ON message_reaction(message_id);
