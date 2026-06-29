CREATE TABLE agent_runs (
    id UUID PRIMARY KEY,
    conversation_id UUID NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
    user_message_id UUID REFERENCES messages(id) ON DELETE SET NULL,
    assistant_message_id UUID REFERENCES messages(id) ON DELETE SET NULL,
    mode VARCHAR(40) NOT NULL,
    status VARCHAR(24) NOT NULL,
    user_goal TEXT NOT NULL,
    final_answer TEXT,
    model VARCHAR(80),
    max_steps INTEGER NOT NULL,
    reflection_limit INTEGER NOT NULL,
    started_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE agent_steps (
    id UUID PRIMARY KEY,
    run_id UUID NOT NULL REFERENCES agent_runs(id) ON DELETE CASCADE,
    step_index INTEGER NOT NULL,
    step_type VARCHAR(40) NOT NULL,
    title VARCHAR(160) NOT NULL,
    content TEXT,
    status VARCHAR(24) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE tool_calls (
    id UUID PRIMARY KEY,
    run_id UUID NOT NULL REFERENCES agent_runs(id) ON DELETE CASCADE,
    step_id UUID REFERENCES agent_steps(id) ON DELETE SET NULL,
    tool_name VARCHAR(80) NOT NULL,
    input_json TEXT,
    output_text TEXT,
    status VARCHAR(24) NOT NULL,
    duration_ms BIGINT,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE memory_items (
    id UUID PRIMARY KEY,
    conversation_id UUID REFERENCES conversations(id) ON DELETE CASCADE,
    message_id UUID REFERENCES messages(id) ON DELETE SET NULL,
    kind VARCHAR(40) NOT NULL,
    content TEXT NOT NULL,
    metadata_json TEXT,
    embedding vector(384) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_agent_runs_conversation_created_at ON agent_runs(conversation_id, created_at DESC);
CREATE INDEX idx_agent_steps_run_index ON agent_steps(run_id, step_index ASC);
CREATE INDEX idx_tool_calls_run_created_at ON tool_calls(run_id, created_at ASC);
CREATE INDEX idx_memory_items_conversation_created_at ON memory_items(conversation_id, created_at DESC);
CREATE INDEX idx_memory_items_embedding ON memory_items USING hnsw (embedding vector_cosine_ops);
