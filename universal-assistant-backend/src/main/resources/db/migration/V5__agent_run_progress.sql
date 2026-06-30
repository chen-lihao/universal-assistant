ALTER TABLE agent_runs
    ADD COLUMN partial_answer TEXT,
    ADD COLUMN error_message TEXT,
    ADD COLUMN realtime_search_used BOOLEAN,
    ADD COLUMN model_available BOOLEAN,
    ADD COLUMN sources_json TEXT;

CREATE INDEX idx_agent_runs_unresolved
    ON agent_runs(conversation_id, created_at ASC)
    WHERE invalidated_at IS NULL
      AND assistant_message_id IS NULL;
