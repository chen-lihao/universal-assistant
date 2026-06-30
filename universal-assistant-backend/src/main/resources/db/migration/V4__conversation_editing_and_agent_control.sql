ALTER TABLE messages
    ADD COLUMN status VARCHAR(24) NOT NULL DEFAULT 'completed',
    ADD COLUMN revision INTEGER NOT NULL DEFAULT 1,
    ADD COLUMN edited_at TIMESTAMPTZ,
    ADD COLUMN invalidated_at TIMESTAMPTZ;

ALTER TABLE agent_runs
    ADD COLUMN invalidated_at TIMESTAMPTZ,
    ADD COLUMN pending_tool_name VARCHAR(80),
    ADD COLUMN pending_tool_input_json TEXT;

ALTER TABLE memory_items
    ADD COLUMN invalidated_at TIMESTAMPTZ;

CREATE INDEX idx_messages_conversation_active_created_at
    ON messages(conversation_id, created_at ASC)
    WHERE invalidated_at IS NULL;

CREATE INDEX idx_agent_runs_assistant_message_id
    ON agent_runs(assistant_message_id)
    WHERE assistant_message_id IS NOT NULL;

CREATE INDEX idx_agent_runs_status
    ON agent_runs(status);

CREATE INDEX idx_memory_items_active_conversation_created_at
    ON memory_items(conversation_id, created_at DESC)
    WHERE invalidated_at IS NULL;
