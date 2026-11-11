//! Conversation repository.

use async_trait::async_trait;
use chatui_domain::{Conversation, Timestamp};
use sqlx::SqlitePool;

use crate::StorageError;
use crate::mappers::ConversationRow;
use crate::paths::{new_conversation_storage_key, validate_conversation_storage_key};
use crate::repositories::bounded_limit;
use crate::search::delete_fts_for_conversation;

/// Filters for listing conversations.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct ConversationFilter {
    /// Restrict to pinned or unpinned conversations.
    pub pinned: Option<bool>,
    /// Restrict to archived or unarchived conversations.
    pub archived: Option<bool>,
    /// Maximum number of rows. Defaults to 100 and is capped at 500.
    pub limit: Option<u32>,
}

/// Conversation persistence abstraction.
#[async_trait]
pub trait ConversationRepository: Send + Sync {
    /// Insert a new conversation.
    async fn create(&self, conversation: &Conversation) -> Result<(), StorageError>;
    /// Update an existing conversation.
    async fn update(&self, conversation: &Conversation) -> Result<(), StorageError>;
    /// Insert or replace a conversation by ID.
    async fn upsert(&self, conversation: &Conversation) -> Result<(), StorageError>;
    /// Insert or replace a conversation by ID, preserving the supplied revision.
    ///
    /// Unlike [`upsert`], this does not increment the revision. Sync uses it to
    /// write a merged revision that is already greater than both sides.
    ///
    /// [`upsert`]: ConversationRepository::upsert
    async fn upsert_with_revision(&self, conversation: &Conversation) -> Result<(), StorageError>;
    /// Fetch one conversation.
    async fn get(&self, id: &str) -> Result<Option<Conversation>, StorageError>;
    /// List conversations with pin/archive filters.
    async fn list(&self, filter: &ConversationFilter) -> Result<Vec<Conversation>, StorageError>;
    /// Return every conversation ordered by creation time then ID.
    ///
    /// This is intended for export and tests. UI code must use [`list`] with
    /// filters and a bounded limit.
    ///
    /// [`list`]: ConversationRepository::list
    async fn list_all(&self) -> Result<Vec<Conversation>, StorageError>;
    /// Delete a conversation, its messages, attachments, and FTS rows.
    async fn delete(&self, id: &str) -> Result<bool, StorageError>;
    /// Set the pinned flag.
    async fn set_pinned(&self, id: &str, pinned: bool) -> Result<(), StorageError>;
    /// Set the archived flag.
    async fn set_archived(&self, id: &str, archived: bool) -> Result<(), StorageError>;
    /// Rename a conversation.
    async fn rename(&self, id: &str, title: &str) -> Result<(), StorageError>;
    /// Update the conversation's `updated_at` timestamp.
    async fn touch(&self, id: &str, updated_at: Timestamp) -> Result<(), StorageError>;
}

/// SQLite-backed conversation repository.
#[derive(Debug, Clone)]
pub struct SqliteConversationRepository {
    pool: SqlitePool,
}

impl SqliteConversationRepository {
    /// Create a repository over an existing pool.
    pub fn new(pool: SqlitePool) -> Self {
        Self { pool }
    }

    /// Borrow the underlying pool.
    pub fn pool(&self) -> &SqlitePool {
        &self.pool
    }
}

const SELECT_COLUMNS: &str = "id, title, system_prompt, provider_id, model_id, model_label, \
     params_json, pinned, archived, temporary, created_at, updated_at, last_message_at, \
     message_count, last_error_json, sync_state, storage_key, revision";

fn prepare_for_write(conversation: &Conversation) -> Result<Conversation, StorageError> {
    let mut conversation = conversation.clone();

    if conversation.storage_key.is_empty() {
        conversation.storage_key = new_conversation_storage_key();
    } else {
        validate_conversation_storage_key(&conversation.storage_key)?;
    }

    if conversation.revision == 0 {
        conversation.revision = 1;
    }

    Ok(conversation)
}

#[async_trait]
impl ConversationRepository for SqliteConversationRepository {
    async fn create(&self, conversation: &Conversation) -> Result<(), StorageError> {
        let conversation = prepare_for_write(conversation)?;
        let row = ConversationRow::from_domain(&conversation)?;
        sqlx::query(
            "INSERT INTO conversations \
             (id, title, system_prompt, provider_id, model_id, model_label, params_json, pinned, \
              archived, temporary, created_at, updated_at, last_message_at, message_count, \
              last_error_json, sync_state, storage_key, revision) \
             VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        )
        .bind(&row.id)
        .bind(&row.title)
        .bind(&row.system_prompt)
        .bind(&row.provider_id)
        .bind(&row.model_id)
        .bind(&row.model_label)
        .bind(&row.params_json)
        .bind(row.pinned)
        .bind(row.archived)
        .bind(row.temporary)
        .bind(&row.created_at)
        .bind(&row.updated_at)
        .bind(&row.last_message_at)
        .bind(row.message_count)
        .bind(&row.last_error_json)
        .bind(&row.sync_state)
        .bind(&row.storage_key)
        .bind(row.revision)
        .execute(&self.pool)
        .await
        .map_err(|error| {
            crate::repositories::map_unique_violation(error, "conversation id already exists")
        })?;

        Ok(())
    }

    async fn update(&self, conversation: &Conversation) -> Result<(), StorageError> {
        let conversation = prepare_for_write(conversation)?;
        let row = ConversationRow::from_domain(&conversation)?;
        let result = sqlx::query(
            "UPDATE conversations SET \
             title = ?, system_prompt = ?, provider_id = ?, model_id = ?, model_label = ?, \
             params_json = ?, pinned = ?, archived = ?, temporary = ?, created_at = ?, \
             updated_at = ?, last_message_at = ?, message_count = ?, last_error_json = ?, \
             sync_state = ?, \
             storage_key = CASE WHEN storage_key = '' THEN ? ELSE storage_key END, \
             revision = revision + 1 \
             WHERE id = ?",
        )
        .bind(&row.title)
        .bind(&row.system_prompt)
        .bind(&row.provider_id)
        .bind(&row.model_id)
        .bind(&row.model_label)
        .bind(&row.params_json)
        .bind(row.pinned)
        .bind(row.archived)
        .bind(row.temporary)
        .bind(&row.created_at)
        .bind(&row.updated_at)
        .bind(&row.last_message_at)
        .bind(row.message_count)
        .bind(&row.last_error_json)
        .bind(&row.sync_state)
        .bind(&row.storage_key)
        .bind(&row.id)
        .execute(&self.pool)
        .await?;

        if result.rows_affected() == 0 {
            return Err(StorageError::NotFound(format!(
                "conversation {}",
                conversation.id
            )));
        }

        Ok(())
    }

    async fn upsert(&self, conversation: &Conversation) -> Result<(), StorageError> {
        let conversation = prepare_for_write(conversation)?;
        let row = ConversationRow::from_domain(&conversation)?;
        sqlx::query(
            "INSERT INTO conversations \
             (id, title, system_prompt, provider_id, model_id, model_label, params_json, pinned, \
              archived, temporary, created_at, updated_at, last_message_at, message_count, \
              last_error_json, sync_state, storage_key, revision) \
             VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) \
             ON CONFLICT(id) DO UPDATE SET \
             title = excluded.title, system_prompt = excluded.system_prompt, \
             provider_id = excluded.provider_id, model_id = excluded.model_id, \
             model_label = excluded.model_label, params_json = excluded.params_json, \
             pinned = excluded.pinned, archived = excluded.archived, \
             temporary = excluded.temporary, created_at = excluded.created_at, \
             updated_at = excluded.updated_at, last_message_at = excluded.last_message_at, \
             message_count = excluded.message_count, last_error_json = excluded.last_error_json, \
             sync_state = excluded.sync_state, \
             storage_key = CASE WHEN conversations.storage_key = '' \
                                THEN excluded.storage_key ELSE conversations.storage_key END, \
             revision = conversations.revision + 1",
        )
        .bind(&row.id)
        .bind(&row.title)
        .bind(&row.system_prompt)
        .bind(&row.provider_id)
        .bind(&row.model_id)
        .bind(&row.model_label)
        .bind(&row.params_json)
        .bind(row.pinned)
        .bind(row.archived)
        .bind(row.temporary)
        .bind(&row.created_at)
        .bind(&row.updated_at)
        .bind(&row.last_message_at)
        .bind(row.message_count)
        .bind(&row.last_error_json)
        .bind(&row.sync_state)
        .bind(&row.storage_key)
        .bind(row.revision)
        .execute(&self.pool)
        .await?;

        Ok(())
    }

    async fn upsert_with_revision(&self, conversation: &Conversation) -> Result<(), StorageError> {
        let conversation = prepare_for_write(conversation)?;
        let row = ConversationRow::from_domain(&conversation)?;
        sqlx::query(
            "INSERT INTO conversations \
             (id, title, system_prompt, provider_id, model_id, model_label, params_json, pinned, \
              archived, temporary, created_at, updated_at, last_message_at, message_count, \
              last_error_json, sync_state, storage_key, revision) \
             VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) \
             ON CONFLICT(id) DO UPDATE SET \
             title = excluded.title, system_prompt = excluded.system_prompt, \
             provider_id = excluded.provider_id, model_id = excluded.model_id, \
             model_label = excluded.model_label, params_json = excluded.params_json, \
             pinned = excluded.pinned, archived = excluded.archived, \
             temporary = excluded.temporary, created_at = excluded.created_at, \
             updated_at = excluded.updated_at, last_message_at = excluded.last_message_at, \
             message_count = excluded.message_count, last_error_json = excluded.last_error_json, \
             sync_state = excluded.sync_state, \
             storage_key = CASE WHEN conversations.storage_key = '' \
                                THEN excluded.storage_key ELSE conversations.storage_key END, \
             revision = excluded.revision",
        )
        .bind(&row.id)
        .bind(&row.title)
        .bind(&row.system_prompt)
        .bind(&row.provider_id)
        .bind(&row.model_id)
        .bind(&row.model_label)
        .bind(&row.params_json)
        .bind(row.pinned)
        .bind(row.archived)
        .bind(row.temporary)
        .bind(&row.created_at)
        .bind(&row.updated_at)
        .bind(&row.last_message_at)
        .bind(row.message_count)
        .bind(&row.last_error_json)
        .bind(&row.sync_state)
        .bind(&row.storage_key)
        .bind(row.revision)
        .execute(&self.pool)
        .await?;

        Ok(())
    }

    async fn get(&self, id: &str) -> Result<Option<Conversation>, StorageError> {
        let query = format!("SELECT {SELECT_COLUMNS} FROM conversations WHERE id = ?");
        let row = sqlx::query_as::<_, ConversationRow>(&query)
            .bind(id)
            .fetch_optional(&self.pool)
            .await?;

        row.map(ConversationRow::into_domain).transpose()
    }

    async fn list(&self, filter: &ConversationFilter) -> Result<Vec<Conversation>, StorageError> {
        let pinned = filter.pinned.map(i64::from);
        let archived = filter.archived.map(i64::from);
        let limit = bounded_limit(filter.limit.unwrap_or(100), 500);

        let query = format!(
            "SELECT {SELECT_COLUMNS} FROM conversations \
             WHERE (? IS NULL OR pinned = ?) \
               AND (? IS NULL OR archived = ?) \
             ORDER BY pinned DESC, updated_at DESC, id ASC \
             LIMIT ?"
        );

        let rows = sqlx::query_as::<_, ConversationRow>(&query)
            .bind(pinned)
            .bind(pinned)
            .bind(archived)
            .bind(archived)
            .bind(limit)
            .fetch_all(&self.pool)
            .await?;

        rows.into_iter().map(ConversationRow::into_domain).collect()
    }

    async fn list_all(&self) -> Result<Vec<Conversation>, StorageError> {
        let query =
            format!("SELECT {SELECT_COLUMNS} FROM conversations ORDER BY created_at ASC, id ASC");
        let rows = sqlx::query_as::<_, ConversationRow>(&query)
            .fetch_all(&self.pool)
            .await?;

        rows.into_iter().map(ConversationRow::into_domain).collect()
    }

    async fn delete(&self, id: &str) -> Result<bool, StorageError> {
        let mut transaction = self.pool.begin().await?;

        delete_fts_for_conversation(&mut transaction, id).await?;

        let result = sqlx::query("DELETE FROM conversations WHERE id = ?")
            .bind(id)
            .execute(&mut *transaction)
            .await?;

        transaction.commit().await?;
        Ok(result.rows_affected() > 0)
    }

    async fn set_pinned(&self, id: &str, pinned: bool) -> Result<(), StorageError> {
        let result =
            sqlx::query("UPDATE conversations SET pinned = ?, updated_at = ?, revision = revision + 1 WHERE id = ?")
                .bind(i64::from(pinned))
                .bind(Timestamp::now().to_rfc3339())
                .bind(id)
                .execute(&self.pool)
                .await?;

        if result.rows_affected() == 0 {
            return Err(StorageError::NotFound(format!("conversation {id}")));
        }

        Ok(())
    }

    async fn set_archived(&self, id: &str, archived: bool) -> Result<(), StorageError> {
        let result =
            sqlx::query("UPDATE conversations SET archived = ?, updated_at = ?, revision = revision + 1 WHERE id = ?")
                .bind(i64::from(archived))
                .bind(Timestamp::now().to_rfc3339())
                .bind(id)
                .execute(&self.pool)
                .await?;

        if result.rows_affected() == 0 {
            return Err(StorageError::NotFound(format!("conversation {id}")));
        }

        Ok(())
    }

    async fn rename(&self, id: &str, title: &str) -> Result<(), StorageError> {
        let result = sqlx::query(
            "UPDATE conversations SET title = ?, updated_at = ?, revision = revision + 1 WHERE id = ?",
        )
        .bind(title)
        .bind(Timestamp::now().to_rfc3339())
        .bind(id)
        .execute(&self.pool)
        .await?;

        if result.rows_affected() == 0 {
            return Err(StorageError::NotFound(format!("conversation {id}")));
        }

        Ok(())
    }

    async fn touch(&self, id: &str, updated_at: Timestamp) -> Result<(), StorageError> {
        let result = sqlx::query("UPDATE conversations SET updated_at = ? WHERE id = ?")
            .bind(updated_at.to_rfc3339())
            .bind(id)
            .execute(&self.pool)
            .await?;

        if result.rows_affected() == 0 {
            return Err(StorageError::NotFound(format!("conversation {id}")));
        }

        Ok(())
    }
}
