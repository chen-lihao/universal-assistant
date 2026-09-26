ALTER TABLE resume_versions
    ADD COLUMN source_format VARCHAR(16) NOT NULL DEFAULT 'text';

ALTER TABLE resume_changes
    ADD COLUMN verification_issues_json TEXT NOT NULL DEFAULT '[]';

ALTER TABLE resume_changes
    ADD COLUMN verification_version SMALLINT NOT NULL DEFAULT 0;
