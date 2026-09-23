CREATE EXTENSION IF NOT EXISTS pg_trgm;

ALTER TABLE memory_items
    ADD COLUMN IF NOT EXISTS semantic_embedding vector(1024),
    ADD COLUMN IF NOT EXISTS embedding_model VARCHAR(120);

CREATE INDEX IF NOT EXISTS idx_memory_items_semantic_embedding
    ON memory_items USING hnsw (semantic_embedding vector_cosine_ops)
    WHERE semantic_embedding IS NOT NULL;

CREATE TABLE knowledge_bases (
    id UUID PRIMARY KEY,
    name VARCHAR(160) NOT NULL,
    description TEXT,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE knowledge_documents (
    id UUID PRIMARY KEY,
    knowledge_base_id UUID NOT NULL REFERENCES knowledge_bases(id) ON DELETE CASCADE,
    title VARCHAR(300) NOT NULL,
    source_uri TEXT,
    full_content TEXT NOT NULL,
    content_hash VARCHAR(64) NOT NULL,
    metadata_json TEXT NOT NULL DEFAULT '{}',
    status VARCHAR(24) NOT NULL,
    chunk_count INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    UNIQUE (knowledge_base_id, content_hash)
);

CREATE TABLE knowledge_chunks (
    id UUID PRIMARY KEY,
    document_id UUID NOT NULL REFERENCES knowledge_documents(id) ON DELETE CASCADE,
    chunk_index INTEGER NOT NULL,
    content TEXT NOT NULL,
    metadata_json TEXT NOT NULL DEFAULT '{}',
    embedding vector(1024),
    embedding_model VARCHAR(120),
    search_vector TSVECTOR GENERATED ALWAYS AS (to_tsvector('simple', content)) STORED,
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE (document_id, chunk_index)
);

INSERT INTO knowledge_bases(id, name, description, enabled, created_at, updated_at)
VALUES (
    '00000000-0000-0000-0000-000000000001',
    '默认知识库',
    'Universal Assistant 默认本地知识库',
    TRUE,
    NOW(),
    NOW()
)
ON CONFLICT (id) DO NOTHING;

CREATE INDEX idx_knowledge_documents_base_updated_at
    ON knowledge_documents(knowledge_base_id, updated_at DESC);
CREATE INDEX idx_knowledge_chunks_document_index
    ON knowledge_chunks(document_id, chunk_index);
CREATE INDEX idx_knowledge_chunks_embedding
    ON knowledge_chunks USING hnsw (embedding vector_cosine_ops)
    WHERE embedding IS NOT NULL;
CREATE INDEX idx_knowledge_chunks_search_vector
    ON knowledge_chunks USING gin(search_vector);
CREATE INDEX idx_knowledge_chunks_content_trgm
    ON knowledge_chunks USING gin(content gin_trgm_ops);
