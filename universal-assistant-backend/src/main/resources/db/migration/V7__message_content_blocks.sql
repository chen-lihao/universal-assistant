ALTER TABLE messages
    ADD COLUMN content_blocks_json TEXT NOT NULL DEFAULT '[]';
