//! Provider repository.

use async_trait::async_trait;
use chatui_domain::Provider;
use sqlx::SqlitePool;

use crate::StorageError;
use crate::mappers::ProviderRow;

/// Provider persistence abstraction.
#[async_trait]
pub trait ProviderRepository: Send + Sync {
    /// Insert a provider.
    async fn insert(&self, provider: &Provider) -> Result<(), StorageError>;
    /// Update a provider.
    async fn update(&self, provider: &Provider) -> Result<(), StorageError>;
    /// Insert or replace a provider by ID.
    async fn upsert(&self, provider: &Provider) -> Result<(), StorageError>;
    /// Fetch one provider.
    async fn get(&self, id: &str) -> Result<Option<Provider>, StorageError>;
    /// List providers ordered by label.
    async fn list(&self) -> Result<Vec<Provider>, StorageError>;
    /// Delete a provider, its models, and its secret.
    async fn delete(&self, id: &str) -> Result<bool, StorageError>;
    /// Set the enabled flag.
    async fn set_enabled(&self, id: &str, enabled: bool) -> Result<(), StorageError>;
}

/// SQLite-backed provider repository.
#[derive(Debug, Clone)]
pub struct SqliteProviderRepository {
    pool: SqlitePool,
}

impl SqliteProviderRepository {
    /// Create a repository over an existing pool.
    pub fn new(pool: SqlitePool) -> Self {
        Self { pool }
    }

    /// Borrow the underlying pool.
    pub fn pool(&self) -> &SqlitePool {
        &self.pool
    }
}

const SELECT_COLUMNS: &str = "id, preset_id, label, kind, base_url, auth_type, secret_id, \
     headers_json, enabled, browser_cors, metadata_json, created_at, updated_at";

#[async_trait]
impl ProviderRepository for SqliteProviderRepository {
    async fn insert(&self, provider: &Provider) -> Result<(), StorageError> {
        let row = ProviderRow::from_domain(provider)?;
        sqlx::query(
            "INSERT INTO providers \
             (id, preset_id, label, kind, base_url, auth_type, secret_id, headers_json, enabled, \
              browser_cors, metadata_json, created_at, updated_at) \
             VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        )
        .bind(&row.id)
        .bind(&row.preset_id)
        .bind(&row.label)
        .bind(&row.kind)
        .bind(&row.base_url)
        .bind(&row.auth_type)
        .bind(&row.secret_id)
        .bind(&row.headers_json)
        .bind(row.enabled)
        .bind(row.browser_cors)
        .bind(&row.metadata_json)
        .bind(&row.created_at)
        .bind(&row.updated_at)
        .execute(&self.pool)
        .await
        .map_err(|error| {
            crate::repositories::map_unique_violation(error, "provider id already exists")
        })?;

        Ok(())
    }

    async fn update(&self, provider: &Provider) -> Result<(), StorageError> {
        let row = ProviderRow::from_domain(provider)?;
        let result = sqlx::query(
            "UPDATE providers SET \
             preset_id = ?, label = ?, kind = ?, base_url = ?, auth_type = ?, secret_id = ?, \
             headers_json = ?, enabled = ?, browser_cors = ?, metadata_json = ?, created_at = ?, \
             updated_at = ? \
             WHERE id = ?",
        )
        .bind(&row.preset_id)
        .bind(&row.label)
        .bind(&row.kind)
        .bind(&row.base_url)
        .bind(&row.auth_type)
        .bind(&row.secret_id)
        .bind(&row.headers_json)
        .bind(row.enabled)
        .bind(row.browser_cors)
        .bind(&row.metadata_json)
        .bind(&row.created_at)
        .bind(&row.updated_at)
        .bind(&row.id)
        .execute(&self.pool)
        .await?;

        if result.rows_affected() == 0 {
            return Err(StorageError::NotFound(format!("provider {}", provider.id)));
        }

        Ok(())
    }

    async fn upsert(&self, provider: &Provider) -> Result<(), StorageError> {
        let row = ProviderRow::from_domain(provider)?;
        sqlx::query(
            "INSERT INTO providers \
             (id, preset_id, label, kind, base_url, auth_type, secret_id, headers_json, enabled, \
              browser_cors, metadata_json, created_at, updated_at) \
             VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) \
             ON CONFLICT(id) DO UPDATE SET \
             preset_id = excluded.preset_id, label = excluded.label, kind = excluded.kind, \
             base_url = excluded.base_url, auth_type = excluded.auth_type, \
             secret_id = excluded.secret_id, headers_json = excluded.headers_json, \
             enabled = excluded.enabled, browser_cors = excluded.browser_cors, \
             metadata_json = excluded.metadata_json, created_at = excluded.created_at, \
             updated_at = excluded.updated_at",
        )
        .bind(&row.id)
        .bind(&row.preset_id)
        .bind(&row.label)
        .bind(&row.kind)
        .bind(&row.base_url)
        .bind(&row.auth_type)
        .bind(&row.secret_id)
        .bind(&row.headers_json)
        .bind(row.enabled)
        .bind(row.browser_cors)
        .bind(&row.metadata_json)
        .bind(&row.created_at)
        .bind(&row.updated_at)
        .execute(&self.pool)
        .await?;

        Ok(())
    }

    async fn get(&self, id: &str) -> Result<Option<Provider>, StorageError> {
        let query = format!("SELECT {SELECT_COLUMNS} FROM providers WHERE id = ?");
        let row = sqlx::query_as::<_, ProviderRow>(&query)
            .bind(id)
            .fetch_optional(&self.pool)
            .await?;

        row.map(ProviderRow::into_domain).transpose()
    }

    async fn list(&self) -> Result<Vec<Provider>, StorageError> {
        let query = format!(
            "SELECT {SELECT_COLUMNS} FROM providers ORDER BY label COLLATE NOCASE ASC, id ASC"
        );
        let rows = sqlx::query_as::<_, ProviderRow>(&query)
            .fetch_all(&self.pool)
            .await?;

        rows.into_iter().map(ProviderRow::into_domain).collect()
    }

    async fn delete(&self, id: &str) -> Result<bool, StorageError> {
        let mut transaction = self.pool.begin().await?;

        let secret_id: Option<String> =
            sqlx::query_scalar("SELECT secret_id FROM providers WHERE id = ?")
                .bind(id)
                .fetch_optional(&mut *transaction)
                .await?
                .flatten();

        let result = sqlx::query("DELETE FROM providers WHERE id = ?")
            .bind(id)
            .execute(&mut *transaction)
            .await?;

        if result.rows_affected() == 0 {
            return Ok(false);
        }

        // Conversations keep their model label snapshot but lose the active
        // provider/model selection so sending is disabled until the user picks
        // another provider.
        sqlx::query(
            "UPDATE conversations SET provider_id = NULL, model_id = NULL WHERE provider_id = ?",
        )
        .bind(id)
        .execute(&mut *transaction)
        .await?;

        if let Some(secret_id) = secret_id {
            sqlx::query("DELETE FROM secrets WHERE id = ?")
                .bind(secret_id)
                .execute(&mut *transaction)
                .await?;
        }

        transaction.commit().await?;
        Ok(true)
    }

    async fn set_enabled(&self, id: &str, enabled: bool) -> Result<(), StorageError> {
        let result = sqlx::query("UPDATE providers SET enabled = ?, updated_at = ? WHERE id = ?")
            .bind(i64::from(enabled))
            .bind(chatui_domain::Timestamp::now().to_rfc3339())
            .bind(id)
            .execute(&self.pool)
            .await?;

        if result.rows_affected() == 0 {
            return Err(StorageError::NotFound(format!("provider {id}")));
        }

        Ok(())
    }
}
