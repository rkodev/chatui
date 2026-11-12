//! Local persistence boundary used by the backup sync engine.
//!
//! The sync engine lives in `chatui-backup` and must not depend on SQLx
//! directly. This module exposes only the reads and writes the sync protocol
//! needs, so the engine can be tested with an in-memory store while the
//! production implementation stays in `chatui-storage`.

use std::sync::Arc;

use async_trait::async_trait;
use chatui_domain::{Attachment, BackupConfig, Conversation, Message, Tombstone};
use sqlx::SqlitePool;

use crate::StorageError;
use crate::attachments::AttachmentStore;
use crate::repositories::{
    AttachmentRepository, BackupConfigRepository, ConversationRepository, MessageRepository,
    SqliteAttachmentRepository, SqliteBackupConfigRepository, SqliteConversationRepository,
    SqliteMessageRepository, SqliteSyncQueueRepository, SqliteTombstoneRepository,
    SyncQueueRepository, TombstoneRepository,
};

/// Local persistence boundary used by the backup sync engine.
#[async_trait]
pub trait SyncLocalStore: Send + Sync {
    /// Load the single backup configuration row.
    async fn get_backup_config(&self) -> Result<Option<BackupConfig>, StorageError>;
    /// Insert or update the backup configuration.
    async fn save_backup_config(&self, config: &BackupConfig) -> Result<(), StorageError>;
    /// Return every conversation ordered by creation time then ID.
    async fn list_conversations(&self) -> Result<Vec<Conversation>, StorageError>;
    /// Fetch one conversation.
    async fn get_conversation(&self, id: &str) -> Result<Option<Conversation>, StorageError>;
    /// Return every message in a conversation, including soft-deleted rows.
    async fn list_messages(&self, conversation_id: &str) -> Result<Vec<Message>, StorageError>;
    /// Return every attachment metadata row.
    async fn list_attachments(&self) -> Result<Vec<Attachment>, StorageError>;
    /// Return every tombstone ordered by deletion time then ID.
    async fn list_tombstones(&self) -> Result<Vec<Tombstone>, StorageError>;
    /// Read attachment bytes by the bare SHA-256 storage key.
    async fn read_attachment(&self, storage_key: &str) -> Result<Option<Vec<u8>>, StorageError>;
    /// Write attachment bytes under the bare SHA-256 storage key.
    async fn write_attachment(&self, storage_key: &str, bytes: &[u8]) -> Result<(), StorageError>;
    /// Insert or replace one conversation with its messages and attachment
    /// metadata.
    async fn write_conversation(
        &self,
        conversation: &Conversation,
        messages: &[Message],
        attachments: &[Attachment],
    ) -> Result<(), StorageError>;
    /// Remove every local conversation, message, and attachment row.
    async fn clear_conversations(&self) -> Result<(), StorageError>;
    /// Delete every queued sync item and return how many were removed.
    async fn drain_sync_queue(&self) -> Result<u64, StorageError>;
}

/// SQLite-backed [`SyncLocalStore`].
///
/// Conversation writes replace the conversation's messages so a deterministic
/// renumber cannot collide with the unique `(conversation_id, sequence)` index.
/// Attachment bytes are content-addressed and written through
/// [`AttachmentStore`].
pub struct SqliteSyncLocalStore {
    conversations: Arc<dyn ConversationRepository>,
    messages: Arc<dyn MessageRepository>,
    attachments: Arc<dyn AttachmentRepository>,
    tombstones: Arc<dyn TombstoneRepository>,
    backup_config: Arc<dyn BackupConfigRepository>,
    sync_queue: Arc<dyn SyncQueueRepository>,
    attachment_store: AttachmentStore,
}

impl SqliteSyncLocalStore {
    /// Build a store over an existing pool and attachment byte store.
    pub fn new(pool: SqlitePool, attachment_store: AttachmentStore) -> Self {
        Self {
            conversations: Arc::new(SqliteConversationRepository::new(pool.clone())),
            messages: Arc::new(SqliteMessageRepository::new(pool.clone())),
            attachments: Arc::new(SqliteAttachmentRepository::new(pool.clone())),
            tombstones: Arc::new(SqliteTombstoneRepository::new(pool.clone())),
            backup_config: Arc::new(SqliteBackupConfigRepository::new(pool.clone())),
            sync_queue: Arc::new(SqliteSyncQueueRepository::new(pool)),
            attachment_store,
        }
    }

    /// Build a store from explicit repository implementations.
    pub fn from_parts(
        conversations: Arc<dyn ConversationRepository>,
        messages: Arc<dyn MessageRepository>,
        attachments: Arc<dyn AttachmentRepository>,
        tombstones: Arc<dyn TombstoneRepository>,
        backup_config: Arc<dyn BackupConfigRepository>,
        sync_queue: Arc<dyn SyncQueueRepository>,
        attachment_store: AttachmentStore,
    ) -> Self {
        Self {
            conversations,
            messages,
            attachments,
            tombstones,
            backup_config,
            sync_queue,
            attachment_store,
        }
    }
}

#[async_trait]
impl SyncLocalStore for SqliteSyncLocalStore {
    async fn get_backup_config(&self) -> Result<Option<BackupConfig>, StorageError> {
        self.backup_config.load().await
    }

    async fn save_backup_config(&self, config: &BackupConfig) -> Result<(), StorageError> {
        self.backup_config.save(config).await
    }

    async fn list_conversations(&self) -> Result<Vec<Conversation>, StorageError> {
        self.conversations.list_all().await
    }

    async fn get_conversation(&self, id: &str) -> Result<Option<Conversation>, StorageError> {
        self.conversations.get(id).await
    }

    async fn list_messages(&self, conversation_id: &str) -> Result<Vec<Message>, StorageError> {
        self.messages
            .list_all_including_deleted(conversation_id)
            .await
    }

    async fn list_attachments(&self) -> Result<Vec<Attachment>, StorageError> {
        self.attachments.list_all_unbounded().await
    }

    async fn list_tombstones(&self) -> Result<Vec<Tombstone>, StorageError> {
        self.tombstones.list_all().await
    }

    async fn read_attachment(&self, storage_key: &str) -> Result<Option<Vec<u8>>, StorageError> {
        match self.attachment_store.get(storage_key) {
            Ok(bytes) => Ok(Some(bytes)),
            Err(StorageError::NotFound(_)) => Ok(None),
            Err(error) => Err(error),
        }
    }

    async fn write_attachment(&self, storage_key: &str, bytes: &[u8]) -> Result<(), StorageError> {
        let written = self.attachment_store.put(bytes)?;
        if written != storage_key {
            return Err(StorageError::InvalidInput(format!(
                "attachment bytes hash to {written}, expected {storage_key}"
            )));
        }
        Ok(())
    }

    async fn write_conversation(
        &self,
        conversation: &Conversation,
        messages: &[Message],
        attachments: &[Attachment],
    ) -> Result<(), StorageError> {
        // Ensure the conversation exists before messages reference it.
        self.conversations
            .upsert_with_revision(conversation)
            .await?;

        // Replace the message set so a deterministic renumber cannot collide
        // with the unique (conversation_id, sequence) index. Deleting a message
        // cascades its attachment metadata rows.
        let existing = self
            .messages
            .list_all_including_deleted(&conversation.id)
            .await?;
        for message in existing {
            self.messages.delete(&message.id).await?;
        }
        for message in messages {
            self.messages.insert(message).await?;
        }
        for attachment in attachments {
            if self.attachments.get(&attachment.id).await?.is_none() {
                self.attachments.insert(attachment).await?;
            }
        }

        // Message writes refresh conversation stats and bump the revision.
        // Write the conversation again so the merged revision and metadata win.
        self.conversations
            .upsert_with_revision(conversation)
            .await?;
        Ok(())
    }

    async fn clear_conversations(&self) -> Result<(), StorageError> {
        for conversation in self.conversations.list_all().await? {
            self.conversations.delete(&conversation.id).await?;
        }
        Ok(())
    }

    async fn drain_sync_queue(&self) -> Result<u64, StorageError> {
        self.sync_queue.clear().await
    }
}
