-- Initial ChatUI desktop schema.
--
-- Structured fields mirror the Android Room entities from spec section 9.5.
-- JSON payloads are stored as TEXT; indexed columns stay typed. This migration
-- is additive and must never be edited after release. Add a new migration for
-- every schema change.

CREATE TABLE conversations (
    id TEXT PRIMARY KEY NOT NULL,
    title TEXT NOT NULL,
    system_prompt TEXT,
    provider_id TEXT,
    model_id TEXT,
    model_label TEXT,
    params_json TEXT NOT NULL DEFAULT '{}',
    pinned INTEGER NOT NULL DEFAULT 0 CHECK (pinned IN (0, 1)),
    archived INTEGER NOT NULL DEFAULT 0 CHECK (archived IN (0, 1)),
    temporary INTEGER NOT NULL DEFAULT 0 CHECK (temporary IN (0, 1)),
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    last_message_at INTEGER,
    message_count INTEGER NOT NULL DEFAULT 0,
    last_error_json TEXT,
    sync_state TEXT NOT NULL DEFAULT 'local'
);

CREATE TABLE messages (
    id TEXT PRIMARY KEY NOT NULL,
    conversation_id TEXT NOT NULL REFERENCES conversations (id) ON DELETE CASCADE,
    sequence INTEGER NOT NULL,
    role TEXT NOT NULL,
    parts_json TEXT NOT NULL,
    status TEXT NOT NULL,
    provider_id TEXT,
    model_id TEXT,
    model_label TEXT,
    usage_json TEXT,
    finish_reason TEXT,
    error_json TEXT,
    latency_ms INTEGER,
    first_token_ms INTEGER,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    deleted_at INTEGER
);

CREATE TABLE attachments (
    id TEXT PRIMARY KEY NOT NULL,
    message_id TEXT NOT NULL REFERENCES messages (id) ON DELETE CASCADE,
    kind TEXT NOT NULL,
    mime_type TEXT NOT NULL,
    name TEXT NOT NULL,
    size INTEGER NOT NULL,
    sha256 TEXT NOT NULL,
    storage_key TEXT NOT NULL,
    width INTEGER,
    height INTEGER,
    created_at INTEGER NOT NULL
);

CREATE TABLE secrets (
    id TEXT PRIMARY KEY NOT NULL,
    ciphertext BLOB NOT NULL,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL
);

CREATE TABLE providers (
    id TEXT PRIMARY KEY NOT NULL,
    preset_id TEXT,
    label TEXT NOT NULL,
    kind TEXT NOT NULL,
    base_url TEXT,
    auth_type TEXT NOT NULL,
    secret_id TEXT REFERENCES secrets (id) ON DELETE SET NULL,
    headers_json TEXT NOT NULL DEFAULT '{}',
    enabled INTEGER NOT NULL DEFAULT 1 CHECK (enabled IN (0, 1)),
    browser_cors INTEGER NOT NULL DEFAULT 0 CHECK (browser_cors IN (0, 1)),
    metadata_json TEXT NOT NULL DEFAULT '{}',
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL
);

CREATE TABLE models (
    id TEXT PRIMARY KEY NOT NULL,
    provider_id TEXT NOT NULL REFERENCES providers (id) ON DELETE CASCADE,
    remote_id TEXT NOT NULL,
    label TEXT NOT NULL,
    context_window INTEGER,
    max_output_tokens INTEGER,
    capabilities_json TEXT NOT NULL DEFAULT '{}',
    pricing_json TEXT NOT NULL DEFAULT '{}',
    canonical_slug TEXT,
    input_modalities_json TEXT NOT NULL DEFAULT '[]',
    output_modalities_json TEXT NOT NULL DEFAULT '[]',
    supported_parameters_json TEXT NOT NULL DEFAULT '[]',
    metadata_json TEXT NOT NULL DEFAULT '{}',
    hidden INTEGER NOT NULL DEFAULT 0 CHECK (hidden IN (0, 1)),
    pinned INTEGER NOT NULL DEFAULT 0 CHECK (pinned IN (0, 1)),
    source TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL
);

CREATE TABLE settings (
    key TEXT PRIMARY KEY NOT NULL,
    value_json TEXT NOT NULL,
    updated_at INTEGER NOT NULL
);

-- Future backup configuration. The table exists so the schema foundation is
-- stable; backup behavior is owned by M5.
CREATE TABLE backup_config (
    id TEXT PRIMARY KEY NOT NULL,
    enabled INTEGER NOT NULL DEFAULT 0 CHECK (enabled IN (0, 1)),
    config_json TEXT NOT NULL DEFAULT '{}',
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL
);

-- Future sync queue. The table exists so the schema foundation is stable; sync
-- behavior is owned by M5.
CREATE TABLE sync_queue (
    id TEXT PRIMARY KEY NOT NULL,
    entity_type TEXT NOT NULL,
    entity_id TEXT NOT NULL,
    operation TEXT NOT NULL,
    payload_json TEXT NOT NULL DEFAULT '{}',
    attempts INTEGER NOT NULL DEFAULT 0,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL
);

-- FTS5 search index over message text. Repository writes in M3.5-03.2 keep this
-- table in sync with messages.parts_json.
CREATE VIRTUAL TABLE messages_fts USING fts5 (
    message_id UNINDEXED,
    conversation_id UNINDEXED,
    content,
    tokenize = 'unicode61'
);

CREATE UNIQUE INDEX idx_messages_conversation_sequence
    ON messages (conversation_id, sequence);

CREATE UNIQUE INDEX idx_models_provider_remote
    ON models (provider_id, remote_id);

CREATE INDEX idx_attachments_sha256
    ON attachments (sha256);

CREATE INDEX idx_attachments_message_id
    ON attachments (message_id);

CREATE INDEX idx_conversations_updated_at
    ON conversations (updated_at DESC);

CREATE INDEX idx_messages_conversation_created_at
    ON messages (conversation_id, created_at);

CREATE INDEX idx_sync_queue_entity
    ON sync_queue (entity_type, entity_id);
