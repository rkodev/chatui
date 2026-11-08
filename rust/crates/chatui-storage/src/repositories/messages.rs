//! Message repository with sequence-ordered pagination and FTS synchronization.

use std::collections::BTreeSet;

use async_trait::async_trait;
use chatui_domain::{Message, MessageStatus, Timestamp};
use sqlx::SqlitePool;
use sqlx::sqlite::SqliteConnection;

use crate::StorageError;
use crate::mappers::MessageRow;
use crate::repositories::{bounded_limit, map_unique_violation};
use crate::search::{delete_fts_for_message, insert_fts_row, replace_fts_row};

/// A bounded page of messages in ascending sequence order.
#[derive(Debug, Clone, PartialEq)]
pub struct MessagePage {
    /// Messages ordered by ascending sequence.
    pub messages: Vec<Message>,
    /// Whether more messages exist in the requested direction.
    pub has_more: bool,
}

/// Message persistence abstraction.
#[async_trait]
pub trait MessageRepository: Send + Sync {
    /// Insert one message and synchronize its FTS row.
    async fn insert(&self, message: &Message) -> Result<(), StorageError>;
    /// Insert many messages in one transaction.
    async fn insert_many(&self, messages: &[Message]) -> Result<(), StorageError>;
    /// Update an existing message and synchronize its FTS row.
    async fn update(&self, message: &Message) -> Result<(), StorageError>;
    /// Insert or replace a message by ID.
    async fn upsert(&self, message: &Message) -> Result<(), StorageError>;
    /// Fetch one message.
    async fn get(&self, id: &str) -> Result<Option<Message>, StorageError>;
    /// Hard-delete a message, its attachments, and its FTS row.
    async fn delete(&self, id: &str) -> Result<bool, StorageError>;
    /// Soft-delete a message and remove it from the search index.
    async fn soft_delete(&self, id: &str, deleted_at: Timestamp) -> Result<(), StorageError>;
    /// Return the newest page of messages.
    async fn newest_page(
        &self,
        conversation_id: &str,
        limit: u32,
    ) -> Result<MessagePage, StorageError>;
    /// Return the page immediately older than `before_sequence`.
    async fn older_page(
        &self,
        conversation_id: &str,
        before_sequence: u64,
        limit: u32,
    ) -> Result<MessagePage, StorageError>;
    /// Return the page immediately newer than `after_sequence`.
    async fn newer_page(
        &self,
        conversation_id: &str,
        after_sequence: u64,
        limit: u32,
    ) -> Result<MessagePage, StorageError>;
    /// Return the next sequence number for a conversation.
    async fn next_sequence(&self, conversation_id: &str) -> Result<u64, StorageError>;
    /// Return every non-deleted message in sequence order.
    ///
    /// This is intended for export/import and tests. UI code must use the
    /// bounded page methods instead.
    async fn list_all(&self, conversation_id: &str) -> Result<Vec<Message>, StorageError>;
    /// Return every message in a conversation, including soft-deleted rows.
    ///
    /// This is intended for export and tests. UI code must use the bounded page
    /// methods instead.
    async fn list_all_including_deleted(
        &self,
        conversation_id: &str,
    ) -> Result<Vec<Message>, StorageError>;
    /// Return every non-deleted message with the given status.
    ///
    /// Startup recovery uses this to find messages left in `streaming`.
    async fn list_by_status(&self, status: MessageStatus) -> Result<Vec<Message>, StorageError>;
}

/// SQLite-backed message repository.
#[derive(Debug, Clone)]
pub struct SqliteMessageRepository {
    pool: SqlitePool,
}

impl SqliteMessageRepository {
    /// Create a repository over an existing pool.
    pub fn new(pool: SqlitePool) -> Self {
        Self { pool }
    }

    /// Borrow the underlying pool.
    pub fn pool(&self) -> &SqlitePool {
        &self.pool
    }
}

const SELECT_COLUMNS: &str = "id, conversation_id, sequence, role, parts_json, status, \
     provider_id, model_id, model_label, usage_json, finish_reason, error_json, latency_ms, \
     first_token_ms, created_at, updated_at, deleted_at";

#[async_trait]
impl MessageRepository for SqliteMessageRepository {
    async fn insert(&self, message: &Message) -> Result<(), StorageError> {
        let row = MessageRow::from_domain(message)?;
        let mut transaction = self.pool.begin().await?;

        insert_row(&mut transaction, &row).await?;
        insert_fts_row(&mut transaction, message).await?;
        refresh_conversation_stats(&mut transaction, &message.conversation_id).await?;

        transaction.commit().await?;
        Ok(())
    }

    async fn insert_many(&self, messages: &[Message]) -> Result<(), StorageError> {
        if messages.is_empty() {
            return Ok(());
        }

        let mut transaction = self.pool.begin().await?;
        let mut conversations = BTreeSet::new();

        for message in messages {
            let row = MessageRow::from_domain(message)?;
            insert_row(&mut transaction, &row).await?;
            insert_fts_row(&mut transaction, message).await?;
            conversations.insert(message.conversation_id.clone());
        }

        for conversation_id in conversations {
            refresh_conversation_stats(&mut transaction, &conversation_id).await?;
        }

        transaction.commit().await?;
        Ok(())
    }

    async fn update(&self, message: &Message) -> Result<(), StorageError> {
        let row = MessageRow::from_domain(message)?;
        let mut transaction = self.pool.begin().await?;

        let result = sqlx::query(
            "UPDATE messages SET \
             sequence = ?, role = ?, parts_json = ?, status = ?, provider_id = ?, model_id = ?, \
             model_label = ?, usage_json = ?, finish_reason = ?, error_json = ?, latency_ms = ?, \
             first_token_ms = ?, created_at = ?, updated_at = ?, deleted_at = ? \
             WHERE id = ?",
        )
        .bind(row.sequence)
        .bind(&row.role)
        .bind(&row.parts_json)
        .bind(&row.status)
        .bind(&row.provider_id)
        .bind(&row.model_id)
        .bind(&row.model_label)
        .bind(&row.usage_json)
        .bind(&row.finish_reason)
        .bind(&row.error_json)
        .bind(row.latency_ms)
        .bind(row.first_token_ms)
        .bind(&row.created_at)
        .bind(&row.updated_at)
        .bind(&row.deleted_at)
        .bind(&row.id)
        .execute(&mut *transaction)
        .await
        .map_err(|error| map_unique_violation(error, "message sequence already exists"))?;

        if result.rows_affected() == 0 {
            return Err(StorageError::NotFound(format!("message {}", message.id)));
        }

        replace_fts_row(&mut transaction, message).await?;
        refresh_conversation_stats(&mut transaction, &message.conversation_id).await?;

        transaction.commit().await?;
        Ok(())
    }

    async fn upsert(&self, message: &Message) -> Result<(), StorageError> {
        let row = MessageRow::from_domain(message)?;
        let mut transaction = self.pool.begin().await?;

        sqlx::query(
            "INSERT INTO messages \
             (id, conversation_id, sequence, role, parts_json, status, provider_id, model_id, \
              model_label, usage_json, finish_reason, error_json, latency_ms, first_token_ms, \
              created_at, updated_at, deleted_at) \
             VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) \
             ON CONFLICT(id) DO UPDATE SET \
             sequence = excluded.sequence, role = excluded.role, parts_json = excluded.parts_json, \
             status = excluded.status, provider_id = excluded.provider_id, \
             model_id = excluded.model_id, model_label = excluded.model_label, \
             usage_json = excluded.usage_json, finish_reason = excluded.finish_reason, \
             error_json = excluded.error_json, latency_ms = excluded.latency_ms, \
             first_token_ms = excluded.first_token_ms, created_at = excluded.created_at, \
             updated_at = excluded.updated_at, deleted_at = excluded.deleted_at",
        )
        .bind(&row.id)
        .bind(&row.conversation_id)
        .bind(row.sequence)
        .bind(&row.role)
        .bind(&row.parts_json)
        .bind(&row.status)
        .bind(&row.provider_id)
        .bind(&row.model_id)
        .bind(&row.model_label)
        .bind(&row.usage_json)
        .bind(&row.finish_reason)
        .bind(&row.error_json)
        .bind(row.latency_ms)
        .bind(row.first_token_ms)
        .bind(&row.created_at)
        .bind(&row.updated_at)
        .bind(&row.deleted_at)
        .execute(&mut *transaction)
        .await
        .map_err(|error| map_unique_violation(error, "message sequence already exists"))?;

        replace_fts_row(&mut transaction, message).await?;
        refresh_conversation_stats(&mut transaction, &message.conversation_id).await?;

        transaction.commit().await?;
        Ok(())
    }

    async fn get(&self, id: &str) -> Result<Option<Message>, StorageError> {
        let query = format!("SELECT {SELECT_COLUMNS} FROM messages WHERE id = ?");
        let row = sqlx::query_as::<_, MessageRow>(&query)
            .bind(id)
            .fetch_optional(&self.pool)
            .await?;

        row.map(MessageRow::into_domain).transpose()
    }

    async fn delete(&self, id: &str) -> Result<bool, StorageError> {
        let mut transaction = self.pool.begin().await?;

        let conversation_id: Option<String> =
            sqlx::query_scalar("SELECT conversation_id FROM messages WHERE id = ?")
                .bind(id)
                .fetch_optional(&mut *transaction)
                .await?;

        let Some(conversation_id) = conversation_id else {
            return Ok(false);
        };

        delete_fts_for_message(&mut transaction, id).await?;
        sqlx::query("DELETE FROM messages WHERE id = ?")
            .bind(id)
            .execute(&mut *transaction)
            .await?;
        refresh_conversation_stats(&mut transaction, &conversation_id).await?;

        transaction.commit().await?;
        Ok(true)
    }

    async fn soft_delete(&self, id: &str, deleted_at: Timestamp) -> Result<(), StorageError> {
        let mut transaction = self.pool.begin().await?;

        let conversation_id: Option<String> =
            sqlx::query_scalar("SELECT conversation_id FROM messages WHERE id = ?")
                .bind(id)
                .fetch_optional(&mut *transaction)
                .await?;

        let Some(conversation_id) = conversation_id else {
            return Err(StorageError::NotFound(format!("message {id}")));
        };

        let result = sqlx::query("UPDATE messages SET deleted_at = ?, updated_at = ? WHERE id = ?")
            .bind(deleted_at.to_rfc3339())
            .bind(deleted_at.to_rfc3339())
            .bind(id)
            .execute(&mut *transaction)
            .await?;

        if result.rows_affected() == 0 {
            return Err(StorageError::NotFound(format!("message {id}")));
        }

        delete_fts_for_message(&mut transaction, id).await?;
        refresh_conversation_stats(&mut transaction, &conversation_id).await?;

        transaction.commit().await?;
        Ok(())
    }

    async fn newest_page(
        &self,
        conversation_id: &str,
        limit: u32,
    ) -> Result<MessagePage, StorageError> {
        let limit = bounded_limit(limit, 500);
        let query = format!(
            "SELECT {SELECT_COLUMNS} FROM messages \
             WHERE conversation_id = ? AND deleted_at IS NULL \
             ORDER BY sequence DESC LIMIT ?"
        );

        let mut rows = sqlx::query_as::<_, MessageRow>(&query)
            .bind(conversation_id)
            .bind(limit + 1)
            .fetch_all(&self.pool)
            .await?;

        let has_more = rows.len() > limit as usize;
        rows.truncate(limit as usize);
        rows.reverse();

        Ok(MessagePage {
            messages: rows
                .into_iter()
                .map(MessageRow::into_domain)
                .collect::<Result<Vec<_>, _>>()?,
            has_more,
        })
    }

    async fn older_page(
        &self,
        conversation_id: &str,
        before_sequence: u64,
        limit: u32,
    ) -> Result<MessagePage, StorageError> {
        let limit = bounded_limit(limit, 500);
        let before_sequence = i64::try_from(before_sequence).map_err(|_| {
            StorageError::InvalidInput("before_sequence does not fit in i64".to_owned())
        })?;
        let query = format!(
            "SELECT {SELECT_COLUMNS} FROM messages \
             WHERE conversation_id = ? AND deleted_at IS NULL AND sequence < ? \
             ORDER BY sequence DESC LIMIT ?"
        );

        let mut rows = sqlx::query_as::<_, MessageRow>(&query)
            .bind(conversation_id)
            .bind(before_sequence)
            .bind(limit + 1)
            .fetch_all(&self.pool)
            .await?;

        let has_more = rows.len() > limit as usize;
        rows.truncate(limit as usize);
        rows.reverse();

        Ok(MessagePage {
            messages: rows
                .into_iter()
                .map(MessageRow::into_domain)
                .collect::<Result<Vec<_>, _>>()?,
            has_more,
        })
    }

    async fn newer_page(
        &self,
        conversation_id: &str,
        after_sequence: u64,
        limit: u32,
    ) -> Result<MessagePage, StorageError> {
        let limit = bounded_limit(limit, 500);
        let after_sequence = i64::try_from(after_sequence).map_err(|_| {
            StorageError::InvalidInput("after_sequence does not fit in i64".to_owned())
        })?;
        let query = format!(
            "SELECT {SELECT_COLUMNS} FROM messages \
             WHERE conversation_id = ? AND deleted_at IS NULL AND sequence > ? \
             ORDER BY sequence ASC LIMIT ?"
        );

        let mut rows = sqlx::query_as::<_, MessageRow>(&query)
            .bind(conversation_id)
            .bind(after_sequence)
            .bind(limit + 1)
            .fetch_all(&self.pool)
            .await?;

        let has_more = rows.len() > limit as usize;
        rows.truncate(limit as usize);

        Ok(MessagePage {
            messages: rows
                .into_iter()
                .map(MessageRow::into_domain)
                .collect::<Result<Vec<_>, _>>()?,
            has_more,
        })
    }

    async fn next_sequence(&self, conversation_id: &str) -> Result<u64, StorageError> {
        let next: i64 = sqlx::query_scalar(
            "SELECT COALESCE(MAX(sequence), -1) + 1 FROM messages WHERE conversation_id = ?",
        )
        .bind(conversation_id)
        .fetch_one(&self.pool)
        .await?;

        u64::try_from(next)
            .map_err(|_| StorageError::Mapping("next message sequence is negative".to_owned()))
    }

    async fn list_all(&self, conversation_id: &str) -> Result<Vec<Message>, StorageError> {
        let query = format!(
            "SELECT {SELECT_COLUMNS} FROM messages \
             WHERE conversation_id = ? AND deleted_at IS NULL \
             ORDER BY sequence ASC"
        );

        let rows = sqlx::query_as::<_, MessageRow>(&query)
            .bind(conversation_id)
            .fetch_all(&self.pool)
            .await?;

        rows.into_iter().map(MessageRow::into_domain).collect()
    }

    async fn list_all_including_deleted(
        &self,
        conversation_id: &str,
    ) -> Result<Vec<Message>, StorageError> {
        let query = format!(
            "SELECT {SELECT_COLUMNS} FROM messages \
             WHERE conversation_id = ? \
             ORDER BY sequence ASC, id ASC"
        );

        let rows = sqlx::query_as::<_, MessageRow>(&query)
            .bind(conversation_id)
            .fetch_all(&self.pool)
            .await?;

        rows.into_iter().map(MessageRow::into_domain).collect()
    }

    async fn list_by_status(&self, status: MessageStatus) -> Result<Vec<Message>, StorageError> {
        let status = match serde_json::to_value(status)? {
            serde_json::Value::String(text) => text,
            other => {
                return Err(StorageError::Mapping(format!(
                    "expected a string message status, got {other}"
                )));
            }
        };

        let query = format!(
            "SELECT {SELECT_COLUMNS} FROM messages \
             WHERE status = ? AND deleted_at IS NULL \
             ORDER BY conversation_id ASC, sequence ASC"
        );
        let rows = sqlx::query_as::<_, MessageRow>(&query)
            .bind(status)
            .fetch_all(&self.pool)
            .await?;

        rows.into_iter().map(MessageRow::into_domain).collect()
    }
}

async fn insert_row(
    connection: &mut SqliteConnection,
    row: &MessageRow,
) -> Result<(), StorageError> {
    sqlx::query(
        "INSERT INTO messages \
         (id, conversation_id, sequence, role, parts_json, status, provider_id, model_id, \
          model_label, usage_json, finish_reason, error_json, latency_ms, first_token_ms, \
          created_at, updated_at, deleted_at) \
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
    )
    .bind(&row.id)
    .bind(&row.conversation_id)
    .bind(row.sequence)
    .bind(&row.role)
    .bind(&row.parts_json)
    .bind(&row.status)
    .bind(&row.provider_id)
    .bind(&row.model_id)
    .bind(&row.model_label)
    .bind(&row.usage_json)
    .bind(&row.finish_reason)
    .bind(&row.error_json)
    .bind(row.latency_ms)
    .bind(row.first_token_ms)
    .bind(&row.created_at)
    .bind(&row.updated_at)
    .bind(&row.deleted_at)
    .execute(&mut *connection)
    .await
    .map_err(|error| map_unique_violation(error, "message sequence already exists"))?;

    Ok(())
}

pub(crate) async fn refresh_conversation_stats(
    connection: &mut SqliteConnection,
    conversation_id: &str,
) -> Result<(), StorageError> {
    sqlx::query(
        "UPDATE conversations SET \
         message_count = (SELECT COUNT(*) FROM messages \
                          WHERE conversation_id = ? AND deleted_at IS NULL), \
         last_message_at = (SELECT MAX(created_at) FROM messages \
                            WHERE conversation_id = ? AND deleted_at IS NULL), \
         updated_at = MAX(updated_at, COALESCE((SELECT MAX(updated_at) FROM messages \
                                                WHERE conversation_id = ? AND deleted_at IS NULL), \
                                               updated_at)) \
         WHERE id = ?",
    )
    .bind(conversation_id)
    .bind(conversation_id)
    .bind(conversation_id)
    .bind(conversation_id)
    .execute(&mut *connection)
    .await?;

    Ok(())
}
