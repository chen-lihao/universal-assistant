CREATE TABLE pending_agent_actions (
    id UUID PRIMARY KEY,
    conversation_id UUID NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
    source_message_id UUID REFERENCES messages(id) ON DELETE SET NULL,
    consumed_by_message_id UUID REFERENCES messages(id) ON DELETE SET NULL,
    action_type VARCHAR(80) NOT NULL,
    status VARCHAR(32) NOT NULL,
    question TEXT,
    effective_message TEXT NOT NULL,
    params_json TEXT,
    expires_at TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_pending_agent_actions_active
    ON pending_agent_actions(conversation_id, created_at DESC)
    WHERE status = 'waiting';
