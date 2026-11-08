//! Backup configuration repository placeholder.
//!
//! The table exists so the schema foundation is stable. Backup behavior is
//! owned by M5.

use async_trait::async_trait;
use chatui_domain::{BackupConfig, Timestamp};
use sqlx::SqlitePool;

use crate::StorageError;
use crate::mappers::BackupConfigRow;

/// Backup configuration persistence abstraction.
#[async_trait]
pub trait BackupConfigRepository: Send + Sync {
    /// Load the single backup configuration row.
    async fn load(&self) -> Result<Option<BackupConfig>, StorageError>;
    /// Insert or update the backup configuration.
    async fn save(&self, config: &BackupConfig) -> Result<(), StorageError>;
    /// Delete the backup configuration.
    async fn delete(&self) -> Result<bool, StorageError>;
}

/// SQLite-backed backup configuration repository.
#[derive(Debug, Clone)]
pub struct SqliteBackupConfigRepository {
    pool: SqlitePool,
}

impl SqliteBackupConfigRepository {
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
impl BackupConfigRepository for SqliteBackupConfigRepository {
    async fn load(&self) -> Result<Option<BackupConfig>, StorageError> {
        let row = sqlx::query_as::<_, BackupConfigRow>(
            "SELECT id, enabled, config_json, created_at, updated_at \
             FROM backup_config WHERE id = 'default'",
        )
        .fetch_optional(&self.pool)
        .await?;

        row.map(BackupConfigRow::into_domain).transpose()
    }

    async fn save(&self, config: &BackupConfig) -> Result<(), StorageError> {
        let now = Timestamp::now();
        let row = BackupConfigRow::from_domain(config, now, now)?;

        sqlx::query(
            "INSERT INTO backup_config (id, enabled, config_json, created_at, updated_at) \
             VALUES (?, ?, ?, ?, ?) \
             ON CONFLICT(id) DO UPDATE SET \
             enabled = excluded.enabled, config_json = excluded.config_json, \
             updated_at = excluded.updated_at",
        )
        .bind(&row.id)
        .bind(row.enabled)
        .bind(&row.config_json)
        .bind(&row.created_at)
        .bind(&row.updated_at)
        .execute(&self.pool)
        .await?;

        Ok(())
    }

    async fn delete(&self) -> Result<bool, StorageError> {
        let result = sqlx::query("DELETE FROM backup_config WHERE id = 'default'")
            .execute(&self.pool)
            .await?;

        Ok(result.rows_affected() > 0)
    }
}
