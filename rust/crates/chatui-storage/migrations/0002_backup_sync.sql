-- Backup and sync metadata.
--
-- This migration is additive. It adds the random conversation storage key and
-- monotonic revision used by the backup manifest, plus the tombstone table that
-- records deletions until sync confirms them.
--
-- SQLite cannot generate random hexadecimal values in a portable default, so
-- `storage_key` starts as an empty string here. `run_migrations` backfills every
-- empty key with 128 random bits from the Rust process immediately after the
-- migration runner finishes. The partial unique index permits the temporary
-- empty value while still guaranteeing uniqueness for real keys.

ALTER TABLE conversations ADD COLUMN storage_key TEXT NOT NULL DEFAULT '';
ALTER TABLE conversations ADD COLUMN revision INTEGER NOT NULL DEFAULT 1;

CREATE TABLE tombstones (
    id TEXT PRIMARY KEY NOT NULL,
    conversation_id TEXT NOT NULL,
    message_id TEXT,
    deleted_at TEXT NOT NULL,
    device_id TEXT NOT NULL
);

CREATE UNIQUE INDEX idx_conversations_storage_key
    ON conversations (storage_key)
    WHERE storage_key <> '';

CREATE INDEX idx_tombstones_conversation_id
    ON tombstones (conversation_id);

CREATE INDEX idx_tombstones_deleted_at
    ON tombstones (deleted_at);
