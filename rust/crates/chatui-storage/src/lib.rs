//! SQLite storage foundation for the ChatUI desktop client.
//!
//! This crate owns the database connection lifecycle, pragmas, versioned
//! migrations, repositories, content-addressed attachment storage, and startup
//! recovery. Export/import arrives in a later M3.5-03 task. UI and provider
//! crates must never issue SQL directly.
//!
//! # Database location
//!
//! The database lives under the platform app-data directory returned by
//! [`directories::ProjectDirs`] for the application name `chatui`:
//!
//! | Platform | App-data directory | Database file |
//! | --- | --- | --- |
//! | Linux | `$XDG_DATA_HOME/chatui` or `~/.local/share/chatui` | `chatui.sqlite3` |
//! | macOS | `~/Library/Application Support/chatui` | `chatui.sqlite3` |
//! | Windows | `%APPDATA%\chatui\data` | `chatui.sqlite3` |
//!
//! [`database_path`] returns the full path. Tests must use
//! [`db::open_in_memory`] or a temporary directory; they must never open the
//! real user database.
//!
//! # Migration policy
//!
//! Migrations are ordered SQL files in `migrations/` and are embedded into the
//! binary with [`sqlx::migrate!`]. SQLx records applied versions and checksums
//! in `_sqlx_migrations`, so running the migrator again is a no-op. Every schema
//! change must add a new migration file; never edit an applied migration.
//!
//! # Non-destructive rule
//!
//! There is no destructive migration fallback. The application never drops or
//! recreates user tables to recover from a schema mismatch. A failed migration
//! is surfaced as an error and the database is left untouched. Release builds
//! must not enable any "recreate on version change" behavior.

use std::path::PathBuf;

use directories::ProjectDirs;
use thiserror::Error;

pub mod attachments;
pub mod db;
pub mod export;
pub mod import;
pub mod mappers;
pub mod paths;
pub mod recovery;
pub mod repositories;
pub mod search;
pub mod sync;

pub use attachments::{AttachmentLease, AttachmentService, AttachmentStore, sha256_hex};
pub use db::{
    BUSY_TIMEOUT_MS, PragmaState, backfill_conversation_storage_keys, open_database,
    open_default_database, open_in_memory, read_pragmas, run_migrations, verify_fts5,
    verify_pragmas,
};
pub use export::{EXPORT_PLAINTEXT_WARNING, ExportOptions, ExportService, encode_export};
pub use import::{
    ImportError, ImportMode, ImportOptions, ImportService, ImportSummary,
    MAX_IMPORT_ATTACHMENT_BYTES, MAX_IMPORT_ATTACHMENT_NAME_LENGTH, MAX_IMPORT_BYTES,
    validate_import_archive,
};
pub use paths::{
    ATTACHMENTS_DIR_NAME, CONVERSATION_STORAGE_KEY_LEN, STORAGE_KEY_LEN, attachment_path,
    attachments_dir, is_valid_conversation_storage_key, is_valid_storage_key,
    new_conversation_storage_key, storage_key_for_sha256, validate_conversation_storage_key,
    validate_storage_key,
};
pub use recovery::{INTERRUPTION_NOTE, RecoveryFailure, RecoveryService, RecoverySummary};
pub use repositories::{
    AttachmentRepository, BackupConfigRepository, ConversationFilter, ConversationRepository,
    MessagePage, MessageRepository, ModelRepository, ProviderRepository, SecretRecord,
    SecretRepository, SettingRecord, SettingsRepository, SqliteAttachmentRepository,
    SqliteBackupConfigRepository, SqliteConversationRepository, SqliteMessageRepository,
    SqliteModelRepository, SqliteProviderRepository, SqliteSecretRepository,
    SqliteSettingsRepository, SqliteSyncQueueRepository, SqliteTombstoneRepository, SyncQueueEntry,
    SyncQueueRepository, TombstoneRepository, default_settings,
};
pub use search::{SearchHit, SearchOptions, SearchRepository, SqliteSearchRepository};
pub use sync::{SqliteSyncLocalStore, SyncLocalStore};

/// Application directory name used for the platform app-data path.
pub const APP_DIR_NAME: &str = "chatui";

/// Database file name under the app-data directory.
pub const DATABASE_FILE_NAME: &str = "chatui.sqlite3";

/// Errors returned by the storage foundation.
#[derive(Debug, Error)]
pub enum StorageError {
    /// The platform did not provide a usable app-data directory.
    #[error("could not determine the platform app-data directory")]
    AppDataDirUnavailable,
    /// Filesystem error while creating the database directory.
    #[error("database I/O error: {0}")]
    Io(#[from] std::io::Error),
    /// SQLite or connection-pool error.
    #[error("SQLite error: {0}")]
    Sqlx(#[from] sqlx::Error),
    /// Migration runner error.
    #[error("migration error: {0}")]
    Migrate(#[from] sqlx::migrate::MigrateError),
    /// A required pragma did not have the expected value.
    #[error("SQLite pragma mismatch: {0}")]
    PragmaMismatch(String),
    /// The linked SQLite build does not expose FTS5.
    #[error("the linked SQLite build does not expose FTS5: {0}")]
    Fts5Unavailable(String),
    /// A database row could not be mapped to or from a domain value.
    #[error("storage mapping error: {0}")]
    Mapping(String),
    /// A requested record does not exist.
    #[error("record not found: {0}")]
    NotFound(String),
    /// A unique constraint was violated.
    #[error("unique constraint violation: {0}")]
    Conflict(String),
    /// A caller supplied an invalid value.
    #[error("invalid input: {0}")]
    InvalidInput(String),
    /// JSON serialization or deserialization failed.
    #[error("JSON error: {0}")]
    Json(#[from] serde_json::Error),
}

/// Returns the platform app-data directory for ChatUI.
///
/// The directory is not created here; [`db::open_database`] creates it when it
/// opens a database file.
pub fn app_data_dir() -> Result<PathBuf, StorageError> {
    ProjectDirs::from("", "", APP_DIR_NAME)
        .map(|dirs| dirs.data_dir().to_path_buf())
        .ok_or(StorageError::AppDataDirUnavailable)
}

/// Returns the default database path under the platform app-data directory.
pub fn database_path() -> Result<PathBuf, StorageError> {
    Ok(app_data_dir()?.join(DATABASE_FILE_NAME))
}
