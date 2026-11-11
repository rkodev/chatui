//! Repository traits and SQLite implementations.
//!
//! UI and provider crates depend on these traits, never on SQLite types. Every
//! SQL statement in the application lives in this module or in [`crate::search`].

pub mod attachments;
pub mod backup;
pub mod conversations;
pub mod messages;
pub mod models;
pub mod providers;
pub mod secrets;
pub mod settings;
pub mod sync_queue;
pub mod tombstones;

#[cfg(test)]
mod tests;

pub use attachments::{AttachmentRepository, SqliteAttachmentRepository};
pub use backup::{BackupConfigRepository, SqliteBackupConfigRepository};
pub use conversations::{ConversationFilter, ConversationRepository, SqliteConversationRepository};
pub use messages::{MessagePage, MessageRepository, SqliteMessageRepository};
pub use models::{ModelRepository, SqliteModelRepository};
pub use providers::{ProviderRepository, SqliteProviderRepository};
pub use secrets::{SecretRepository, SqliteSecretRepository};
pub use settings::{SettingsRepository, SqliteSettingsRepository, default_settings};
pub use sync_queue::{SqliteSyncQueueRepository, SyncQueueRepository};
pub use tombstones::{SqliteTombstoneRepository, TombstoneRepository};

pub use crate::mappers::{SecretRecord, SettingRecord, SyncQueueEntry};

use crate::StorageError;

/// Map a SQLite unique-constraint violation to [`StorageError::Conflict`].
pub(crate) fn map_unique_violation(error: sqlx::Error, context: &str) -> StorageError {
    if let sqlx::Error::Database(ref database_error) = error
        && database_error.is_unique_violation()
    {
        return StorageError::Conflict(context.to_owned());
    }

    StorageError::Sqlx(error)
}

/// Clamp a caller-supplied page size to a bounded range.
pub(crate) fn bounded_limit(limit: u32, maximum: u32) -> i64 {
    i64::from(limit.clamp(1, maximum))
}
