CREATE TABLE conversation_summaries (
    id UUID PRIMARY KEY,
    conversation_id UUID NOT NULL UNIQUE REFERENCES conversations(id) ON DELETE CASCADE,
    summary TEXT NOT NULL,
    message_count INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_conversation_summaries_conversation_id ON conversation_summaries(conversation_id);
