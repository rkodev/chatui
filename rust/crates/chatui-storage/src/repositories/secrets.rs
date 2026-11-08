//! Secret metadata repository.
//!
//! The storage layer treats ciphertext as opaque bytes. Key management,
//! encryption, and zeroization are owned by M3.5-04.

use async_trait::async_trait;
use sqlx::SqlitePool;

use crate::StorageError;
use crate::mappers::{SecretRecord, SecretRow};

/// Secret metadata persistence abstraction.
#[async_trait]
pub trait SecretRepository: Send + Sync {
    /// Insert or replace a secret record.
    async fn put(&self, secret: &SecretRecord) -> Result<(), StorageError>;
    /// Fetch one secret record.
    async fn get(&self, id: &str) -> Result<Option<SecretRecord>, StorageError>;
    /// Return whether a secret record exists.
    async fn exists(&self, id: &str) -> Result<bool, StorageError>;
    /// List every secret ID.
    async fn list_ids(&self) -> Result<Vec<String>, StorageError>;
    /// Delete one secret record.
    async fn delete(&self, id: &str) -> Result<bool, StorageError>;
}

/// SQLite-backed secret metadata repository.
#[derive(Debug, Clone)]
pub struct SqliteSecretRepository {
    pool: SqlitePool,
}

impl SqliteSecretRepository {
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
impl SecretRepository for SqliteSecretRepository {
    async fn put(&self, secret: &SecretRecord) -> Result<(), StorageError> {
        let row = SecretRow::from_record(secret);
        sqlx::query(
            "INSERT INTO secrets (id, ciphertext, created_at, updated_at) VALUES (?, ?, ?, ?) \
             ON CONFLICT(id) DO UPDATE SET \
             ciphertext = excluded.ciphertext, updated_at = excluded.updated_at",
        )
        .bind(&row.id)
        .bind(&row.ciphertext)
        .bind(&row.created_at)
        .bind(&row.updated_at)
        .execute(&self.pool)
        .await?;

        Ok(())
    }

    async fn get(&self, id: &str) -> Result<Option<SecretRecord>, StorageError> {
        let row = sqlx::query_as::<_, SecretRow>(
            "SELECT id, ciphertext, created_at, updated_at FROM secrets WHERE id = ?",
        )
        .bind(id)
        .fetch_optional(&self.pool)
        .await?;

        row.map(SecretRow::into_record).transpose()
    }

    async fn exists(&self, id: &str) -> Result<bool, StorageError> {
        let exists: i64 = sqlx::query_scalar("SELECT EXISTS(SELECT 1 FROM secrets WHERE id = ?)")
            .bind(id)
            .fetch_one(&self.pool)
            .await?;

        Ok(exists != 0)
    }

    async fn list_ids(&self) -> Result<Vec<String>, StorageError> {
        let ids = sqlx::query_scalar("SELECT id FROM secrets ORDER BY id ASC")
            .fetch_all(&self.pool)
            .await?;

        Ok(ids)
    }

    async fn delete(&self, id: &str) -> Result<bool, StorageError> {
        let result = sqlx::query("DELETE FROM secrets WHERE id = ?")
            .bind(id)
            .execute(&self.pool)
            .await?;

        Ok(result.rows_affected() > 0)
    }
}
