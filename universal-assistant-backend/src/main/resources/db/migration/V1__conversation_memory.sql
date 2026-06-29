CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE conversations (
    id UUID PRIMARY KEY,
    title VARCHAR(160) NOT NULL,
    archived BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE messages (
    id UUID PRIMARY KEY,
    conversation_id UUID NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
    role VARCHAR(24) NOT NULL,
    content TEXT NOT NULL,
    model VARCHAR(80),
    realtime_search_used BOOLEAN,
    model_available BOOLEAN,
    sources_json TEXT,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_conversations_updated_at ON conversations(updated_at DESC);
CREATE INDEX idx_messages_conversation_created_at ON messages(conversation_id, created_at ASC);
