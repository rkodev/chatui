//! Sync-queue repository placeholder.
//!
//! The table exists so the schema foundation is stable. Sync behavior is owned
//! by M5.

use async_trait::async_trait;
use chatui_domain::Timestamp;
use sqlx::SqlitePool;

use crate::StorageError;
use crate::mappers::{SyncQueueEntry, SyncQueueRow};
use crate::repositories::bounded_limit;

/// Sync-queue persistence abstraction.
#[async_trait]
pub trait SyncQueueRepository: Send + Sync {
    /// Insert or replace a queue entry.
    async fn enqueue(&self, entry: &SyncQueueEntry) -> Result<(), StorageError>;
    /// List a bounded page of queue entries in creation order.
    async fn list(&self, limit: u32) -> Result<Vec<SyncQueueEntry>, StorageError>;
    /// Delete one queue entry.
    async fn delete(&self, id: &str) -> Result<bool, StorageError>;
    /// Increment the delivery attempt counter.
    async fn increment_attempts(&self, id: &str) -> Result<(), StorageError>;
    /// Delete every queue entry.
    async fn clear(&self) -> Result<u64, StorageError>;
}

/// SQLite-backed sync-queue repository.
#[derive(Debug, Clone)]
pub struct SqliteSyncQueueRepository {
    pool: SqlitePool,
}

impl SqliteSyncQueueRepository {
    /// Create a repository over an existing pool.
    pub fn new(pool: SqlitePool) -> Self {
        Self { pool }
    }

    /// Borrow the underlying pool.
    pub fn pool(&self) -> &SqlitePool {
        &self.pool
    }
}

#[async_trait]
impl SyncQueueRepository for SqliteSyncQueueRepository {
    async fn enqueue(&self, entry: &SyncQueueEntry) -> Result<(), StorageError> {
        let row = SyncQueueRow::from_entry(entry)?;
        sqlx::query(
            "INSERT INTO sync_queue \
             (id, entity_type, entity_id, operation, payload_json, attempts, created_at, \
              updated_at) \
             VALUES (?, ?, ?, ?, ?, ?, ?, ?) \
             ON CONFLICT(id) DO UPDATE SET \
             entity_type = excluded.entity_type, entity_id = excluded.entity_id, \
             operation = excluded.operation, payload_json = excluded.payload_json, \
             attempts = excluded.attempts, updated_at = excluded.updated_at",
        )
        .bind(&row.id)
        .bind(&row.entity_type)
        .bind(&row.entity_id)
        .bind(&row.operation)
        .bind(&row.payload_json)
        .bind(row.attempts)
        .bind(&row.created_at)
        .bind(&row.updated_at)
        .execute(&self.pool)
        .await?;

        Ok(())
    }

    async fn list(&self, limit: u32) -> Result<Vec<SyncQueueEntry>, StorageError> {
        let limit = bounded_limit(limit, 1_000);
        let rows = sqlx::query_as::<_, SyncQueueRow>(
            "SELECT id, entity_type, entity_id, operation, payload_json, attempts, created_at, \
             updated_at FROM sync_queue ORDER BY created_at ASC, id ASC LIMIT ?",
        )
        .bind(limit)
        .fetch_all(&self.pool)
        .await?;

        rows.into_iter().map(SyncQueueRow::into_entry).collect()
    }

    async fn delete(&self, id: &str) -> Result<bool, StorageError> {
        let result = sqlx::query("DELETE FROM sync_queue WHERE id = ?")
            .bind(id)
            .execute(&self.pool)
            .await?;

        Ok(result.rows_affected() > 0)
    }

    async fn increment_attempts(&self, id: &str) -> Result<(), StorageError> {
        let result = sqlx::query(
            "UPDATE sync_queue SET attempts = attempts + 1, updated_at = ? WHERE id = ?",
        )
        .bind(Timestamp::now().to_rfc3339())
        .bind(id)
        .execute(&self.pool)
        .await?;

        if result.rows_affected() == 0 {
            return Err(StorageError::NotFound(format!("sync queue entry {id}")));
        }

        Ok(())
    }

    async fn clear(&self) -> Result<u64, StorageError> {
        let result = sqlx::query("DELETE FROM sync_queue")
            .execute(&self.pool)
            .await?;

        Ok(result.rows_affected())
    }
}
