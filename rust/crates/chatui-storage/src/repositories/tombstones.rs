//! Tombstone repository.

use async_trait::async_trait;
use chatui_domain::Tombstone;
use sqlx::SqlitePool;

use crate::StorageError;
use crate::mappers::TombstoneRow;

/// Tombstone persistence abstraction.
#[async_trait]
pub trait TombstoneRepository: Send + Sync {
    /// Insert a tombstone.
    async fn insert(&self, tombstone: &Tombstone) -> Result<(), StorageError>;
    /// List tombstones for one conversation ordered by deletion time then ID.
    async fn list_for_conversation(
        &self,
        conversation_id: &str,
    ) -> Result<Vec<Tombstone>, StorageError>;
    /// List every tombstone ordered by deletion time then ID.
    async fn list_all(&self) -> Result<Vec<Tombstone>, StorageError>;
    /// Delete a tombstone by ID. Returns `false` when it did not exist.
    async fn delete(&self, id: &str) -> Result<bool, StorageError>;
}

/// SQLite-backed tombstone repository.
#[derive(Debug, Clone)]
pub struct SqliteTombstoneRepository {
    pool: SqlitePool,
}

impl SqliteTombstoneRepository {
    /// Create a repository over an existing pool.
    pub fn new(pool: SqlitePool) -> Self {
        Self { pool }
    }

    /// Borrow the underlying pool.
    pub fn pool(&self) -> &SqlitePool {
        &self.pool
    }
}

const SELECT_COLUMNS: &str = "id, conversation_id, message_id, deleted_at, device_id";

#[async_trait]
impl TombstoneRepository for SqliteTombstoneRepository {
    async fn insert(&self, tombstone: &Tombstone) -> Result<(), StorageError> {
        let row = TombstoneRow::from_domain(tombstone);
        sqlx::query(
            "INSERT INTO tombstones \
             (id, conversation_id, message_id, deleted_at, device_id) \
             VALUES (?, ?, ?, ?, ?)",
        )
        .bind(&row.id)
        .bind(&row.conversation_id)
        .bind(&row.message_id)
        .bind(&row.deleted_at)
        .bind(&row.device_id)
        .execute(&self.pool)
        .await
        .map_err(|error| {
            crate::repositories::map_unique_violation(error, "tombstone id already exists")
        })?;

        Ok(())
    }

    async fn list_for_conversation(
        &self,
        conversation_id: &str,
    ) -> Result<Vec<Tombstone>, StorageError> {
        let query = format!(
            "SELECT {SELECT_COLUMNS} FROM tombstones \
             WHERE conversation_id = ? ORDER BY deleted_at ASC, id ASC"
        );
        let rows = sqlx::query_as::<_, TombstoneRow>(&query)
            .bind(conversation_id)
            .fetch_all(&self.pool)
            .await?;

        rows.into_iter().map(TombstoneRow::into_domain).collect()
    }

    async fn list_all(&self) -> Result<Vec<Tombstone>, StorageError> {
        let query =
            format!("SELECT {SELECT_COLUMNS} FROM tombstones ORDER BY deleted_at ASC, id ASC");
        let rows = sqlx::query_as::<_, TombstoneRow>(&query)
            .fetch_all(&self.pool)
            .await?;

        rows.into_iter().map(TombstoneRow::into_domain).collect()
    }

    async fn delete(&self, id: &str) -> Result<bool, StorageError> {
        let result = sqlx::query("DELETE FROM tombstones WHERE id = ?")
            .bind(id)
            .execute(&self.pool)
            .await?;

        Ok(result.rows_affected() > 0)
    }
}
