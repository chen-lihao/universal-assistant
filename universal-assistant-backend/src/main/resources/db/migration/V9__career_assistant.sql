CREATE TABLE career_profiles (
    id UUID PRIMARY KEY,
    name VARCHAR(160) NOT NULL,
    knowledge_base_id UUID NOT NULL REFERENCES knowledge_bases(id) ON DELETE RESTRICT,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE resume_versions (
    id UUID PRIMARY KEY,
    profile_id UUID NOT NULL REFERENCES career_profiles(id) ON DELETE CASCADE,
    knowledge_document_id UUID REFERENCES knowledge_documents(id) ON DELETE SET NULL,
    parent_version_id UUID REFERENCES resume_versions(id) ON DELETE SET NULL,
    version_number INTEGER NOT NULL,
    title VARCHAR(300) NOT NULL,
    content TEXT NOT NULL,
    source_type VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE (profile_id, version_number)
);

CREATE TABLE job_targets (
    id UUID PRIMARY KEY,
    profile_id UUID NOT NULL REFERENCES career_profiles(id) ON DELETE CASCADE,
    knowledge_document_id UUID REFERENCES knowledge_documents(id) ON DELETE SET NULL,
    job_title VARCHAR(200) NOT NULL,
    company VARCHAR(200),
    description TEXT NOT NULL,
    source_uri TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE resume_change_sets (
    id UUID PRIMARY KEY,
    profile_id UUID NOT NULL REFERENCES career_profiles(id) ON DELETE CASCADE,
    resume_version_id UUID NOT NULL REFERENCES resume_versions(id) ON DELETE CASCADE,
    job_target_id UUID NOT NULL REFERENCES job_targets(id) ON DELETE CASCADE,
    status VARCHAR(24) NOT NULL,
    summary TEXT,
    match_score INTEGER,
    requirement_matrix_json TEXT NOT NULL DEFAULT '[]',
    sources_json TEXT NOT NULL DEFAULT '[]',
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE resume_changes (
    id UUID PRIMARY KEY,
    change_set_id UUID NOT NULL REFERENCES resume_change_sets(id) ON DELETE CASCADE,
    change_index INTEGER NOT NULL,
    section VARCHAR(120),
    original_text TEXT NOT NULL,
    suggested_text TEXT NOT NULL,
    reason TEXT,
    evidence_json TEXT NOT NULL DEFAULT '[]',
    evidence_verified BOOLEAN NOT NULL DEFAULT FALSE,
    status VARCHAR(24) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE (change_set_id, change_index)
);

CREATE TABLE interview_sessions (
    id UUID PRIMARY KEY,
    profile_id UUID NOT NULL REFERENCES career_profiles(id) ON DELETE CASCADE,
    resume_version_id UUID NOT NULL REFERENCES resume_versions(id) ON DELETE CASCADE,
    job_target_id UUID NOT NULL REFERENCES job_targets(id) ON DELETE CASCADE,
    mode VARCHAR(24) NOT NULL,
    status VARCHAR(24) NOT NULL,
    plan_json TEXT NOT NULL DEFAULT '[]',
    current_index INTEGER NOT NULL DEFAULT 0,
    max_questions INTEGER NOT NULL,
    final_report_json TEXT,
    started_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE interview_turns (
    id UUID PRIMARY KEY,
    session_id UUID NOT NULL REFERENCES interview_sessions(id) ON DELETE CASCADE,
    turn_index INTEGER NOT NULL,
    question TEXT NOT NULL,
    answer TEXT NOT NULL,
    feedback_json TEXT,
    score INTEGER,
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE (session_id, turn_index)
);

CREATE INDEX idx_resume_versions_profile_version ON resume_versions(profile_id, version_number DESC);
CREATE INDEX idx_job_targets_profile_updated ON job_targets(profile_id, updated_at DESC);
CREATE INDEX idx_resume_change_sets_profile_created ON resume_change_sets(profile_id, created_at DESC);
CREATE INDEX idx_resume_changes_set_index ON resume_changes(change_set_id, change_index);
CREATE INDEX idx_interview_sessions_profile_updated ON interview_sessions(profile_id, updated_at DESC);
CREATE INDEX idx_interview_turns_session_index ON interview_turns(session_id, turn_index);
