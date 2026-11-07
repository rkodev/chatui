//! Attachment metadata repository.
//!
//! Attachment bytes live in the content-addressed attachment store owned by
//! M3.5-03.3. This repository only persists metadata rows.

use async_trait::async_trait;
use chatui_domain::Attachment;
use sqlx::SqlitePool;

use crate::StorageError;
use crate::mappers::AttachmentRow;
use crate::repositories::bounded_limit;

/// Attachment metadata persistence abstraction.
#[async_trait]
pub trait AttachmentRepository: Send + Sync {
    /// Insert attachment metadata. The attachment must reference a message.
    async fn insert(&self, attachment: &Attachment) -> Result<(), StorageError>;
    /// Update attachment metadata.
    async fn update(&self, attachment: &Attachment) -> Result<(), StorageError>;
    /// Fetch one attachment.
    async fn get(&self, id: &str) -> Result<Option<Attachment>, StorageError>;
    /// List attachments for a message.
    async fn list_for_message(&self, message_id: &str) -> Result<Vec<Attachment>, StorageError>;
    /// List attachments that share a SHA-256 hash.
    async fn list_by_sha256(&self, sha256: &str) -> Result<Vec<Attachment>, StorageError>;
    /// List a bounded page of attachments.
    async fn list_all(&self, limit: u32) -> Result<Vec<Attachment>, StorageError>;
    /// List every distinct storage key referenced by metadata.
    async fn list_storage_keys(&self) -> Result<Vec<String>, StorageError>;
    /// Delete one attachment metadata row.
    async fn delete(&self, id: &str) -> Result<bool, StorageError>;
    /// Delete every attachment metadata row for a message.
    async fn delete_for_message(&self, message_id: &str) -> Result<u64, StorageError>;
}

/// SQLite-backed attachment metadata repository.
#[derive(Debug, Clone)]
pub struct SqliteAttachmentRepository {
    pool: SqlitePool,
}

impl SqliteAttachmentRepository {
    /// Create a repository over an existing pool.
    pub fn new(pool: SqlitePool) -> Self {
        Self { pool }
    }

    /// Borrow the underlying pool.
    pub fn pool(&self) -> &SqlitePool {
        &self.pool
    }
}

const SELECT_COLUMNS: &str = "id, message_id, kind, mime_type, name, size, sha256, storage_key, \
     width, height, created_at";

#[async_trait]
impl AttachmentRepository for SqliteAttachmentRepository {
    async fn insert(&self, attachment: &Attachment) -> Result<(), StorageError> {
        let row = AttachmentRow::from_domain(attachment)?;
        sqlx::query(
            "INSERT INTO attachments \
             (id, message_id, kind, mime_type, name, size, sha256, storage_key, width, height, \
              created_at) \
             VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        )
        .bind(&row.id)
        .bind(&row.message_id)
        .bind(&row.kind)
        .bind(&row.mime_type)
        .bind(&row.name)
        .bind(row.size)
        .bind(&row.sha256)
        .bind(&row.storage_key)
        .bind(row.width)
        .bind(row.height)
        .bind(&row.created_at)
        .execute(&self.pool)
        .await
        .map_err(|error| {
            crate::repositories::map_unique_violation(error, "attachment id already exists")
        })?;

        Ok(())
    }

    async fn update(&self, attachment: &Attachment) -> Result<(), StorageError> {
        let row = AttachmentRow::from_domain(attachment)?;
        let result = sqlx::query(
            "UPDATE attachments SET \
             message_id = ?, kind = ?, mime_type = ?, name = ?, size = ?, sha256 = ?, \
             storage_key = ?, width = ?, height = ?, created_at = ? \
             WHERE id = ?",
        )
        .bind(&row.message_id)
        .bind(&row.kind)
        .bind(&row.mime_type)
        .bind(&row.name)
        .bind(row.size)
        .bind(&row.sha256)
        .bind(&row.storage_key)
        .bind(row.width)
        .bind(row.height)
        .bind(&row.created_at)
        .bind(&row.id)
        .execute(&self.pool)
        .await?;

        if result.rows_affected() == 0 {
            return Err(StorageError::NotFound(format!(
                "attachment {}",
                attachment.id
            )));
        }

        Ok(())
    }

    async fn get(&self, id: &str) -> Result<Option<Attachment>, StorageError> {
        let query = format!("SELECT {SELECT_COLUMNS} FROM attachments WHERE id = ?");
        let row = sqlx::query_as::<_, AttachmentRow>(&query)
            .bind(id)
            .fetch_optional(&self.pool)
            .await?;

        row.map(AttachmentRow::into_domain).transpose()
    }

    async fn list_for_message(&self, message_id: &str) -> Result<Vec<Attachment>, StorageError> {
        let query = format!(
            "SELECT {SELECT_COLUMNS} FROM attachments WHERE message_id = ? \
             ORDER BY created_at ASC, id ASC"
        );
        let rows = sqlx::query_as::<_, AttachmentRow>(&query)
            .bind(message_id)
            .fetch_all(&self.pool)
            .await?;

        rows.into_iter().map(AttachmentRow::into_domain).collect()
    }

    async fn list_by_sha256(&self, sha256: &str) -> Result<Vec<Attachment>, StorageError> {
        let query = format!(
            "SELECT {SELECT_COLUMNS} FROM attachments WHERE sha256 = ? \
             ORDER BY created_at ASC, id ASC"
        );
        let rows = sqlx::query_as::<_, AttachmentRow>(&query)
            .bind(sha256)
            .fetch_all(&self.pool)
            .await?;

        rows.into_iter().map(AttachmentRow::into_domain).collect()
    }

    async fn list_all(&self, limit: u32) -> Result<Vec<Attachment>, StorageError> {
        let limit = bounded_limit(limit, 1_000);
        let query = format!(
            "SELECT {SELECT_COLUMNS} FROM attachments ORDER BY created_at ASC, id ASC LIMIT ?"
        );
        let rows = sqlx::query_as::<_, AttachmentRow>(&query)
            .bind(limit)
            .fetch_all(&self.pool)
            .await?;

        rows.into_iter().map(AttachmentRow::into_domain).collect()
    }

    async fn list_storage_keys(&self) -> Result<Vec<String>, StorageError> {
        let keys = sqlx::query_scalar(
            "SELECT DISTINCT storage_key FROM attachments ORDER BY storage_key ASC",
        )
        .fetch_all(&self.pool)
        .await?;

        Ok(keys)
    }

    async fn delete(&self, id: &str) -> Result<bool, StorageError> {
        let result = sqlx::query("DELETE FROM attachments WHERE id = ?")
            .bind(id)
            .execute(&self.pool)
            .await?;

        Ok(result.rows_affected() > 0)
    }

    async fn delete_for_message(&self, message_id: &str) -> Result<u64, StorageError> {
        let result = sqlx::query("DELETE FROM attachments WHERE message_id = ?")
            .bind(message_id)
            .execute(&self.pool)
            .await?;

        Ok(result.rows_affected())
    }
}
