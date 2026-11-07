//! Model repository. The unique `(provider_id, remote_id)` index is enforced by
//! the database and surfaced as [`StorageError::Conflict`].

use async_trait::async_trait;
use chatui_domain::Model;
use sqlx::SqlitePool;

use crate::StorageError;
use crate::mappers::ModelRow;
use crate::repositories::map_unique_violation;

/// Model persistence abstraction.
#[async_trait]
pub trait ModelRepository: Send + Sync {
    /// Insert a model.
    async fn insert(&self, model: &Model) -> Result<(), StorageError>;
    /// Update a model.
    async fn update(&self, model: &Model) -> Result<(), StorageError>;
    /// Insert or replace a model by ID.
    async fn upsert(&self, model: &Model) -> Result<(), StorageError>;
    /// Fetch one model.
    async fn get(&self, id: &str) -> Result<Option<Model>, StorageError>;
    /// Fetch a model by its provider and remote ID.
    async fn get_by_remote_id(
        &self,
        provider_id: &str,
        remote_id: &str,
    ) -> Result<Option<Model>, StorageError>;
    /// List models for one provider.
    async fn list_by_provider(&self, provider_id: &str) -> Result<Vec<Model>, StorageError>;
    /// List every model.
    async fn list_all(&self) -> Result<Vec<Model>, StorageError>;
    /// Delete one model.
    async fn delete(&self, id: &str) -> Result<bool, StorageError>;
    /// Delete every model for a provider.
    async fn delete_by_provider(&self, provider_id: &str) -> Result<u64, StorageError>;
    /// Set the hidden flag.
    async fn set_hidden(&self, id: &str, hidden: bool) -> Result<(), StorageError>;
    /// Set the pinned flag.
    async fn set_pinned(&self, id: &str, pinned: bool) -> Result<(), StorageError>;
}

/// SQLite-backed model repository.
#[derive(Debug, Clone)]
pub struct SqliteModelRepository {
    pool: SqlitePool,
}

impl SqliteModelRepository {
    /// Create a repository over an existing pool.
    pub fn new(pool: SqlitePool) -> Self {
        Self { pool }
    }

    /// Borrow the underlying pool.
    pub fn pool(&self) -> &SqlitePool {
        &self.pool
    }
}

const SELECT_COLUMNS: &str = "id, provider_id, remote_id, label, context_window, \
     max_output_tokens, capabilities_json, pricing_json, canonical_slug, input_modalities_json, \
     output_modalities_json, supported_parameters_json, metadata_json, hidden, pinned, source, \
     created_at, updated_at";

#[async_trait]
impl ModelRepository for SqliteModelRepository {
    async fn insert(&self, model: &Model) -> Result<(), StorageError> {
        let row = ModelRow::from_domain(model)?;
        sqlx::query(
            "INSERT INTO models \
             (id, provider_id, remote_id, label, context_window, max_output_tokens, \
              capabilities_json, pricing_json, canonical_slug, input_modalities_json, \
              output_modalities_json, supported_parameters_json, metadata_json, hidden, pinned, \
              source, created_at, updated_at) \
             VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        )
        .bind(&row.id)
        .bind(&row.provider_id)
        .bind(&row.remote_id)
        .bind(&row.label)
        .bind(row.context_window)
        .bind(row.max_output_tokens)
        .bind(&row.capabilities_json)
        .bind(&row.pricing_json)
        .bind(&row.canonical_slug)
        .bind(&row.input_modalities_json)
        .bind(&row.output_modalities_json)
        .bind(&row.supported_parameters_json)
        .bind(&row.metadata_json)
        .bind(row.hidden)
        .bind(row.pinned)
        .bind(&row.source)
        .bind(&row.created_at)
        .bind(&row.updated_at)
        .execute(&self.pool)
        .await
        .map_err(|error| {
            map_unique_violation(error, "model (provider_id, remote_id) already exists")
        })?;

        Ok(())
    }

    async fn update(&self, model: &Model) -> Result<(), StorageError> {
        let row = ModelRow::from_domain(model)?;
        let result = sqlx::query(
            "UPDATE models SET \
             provider_id = ?, remote_id = ?, label = ?, context_window = ?, \
             max_output_tokens = ?, capabilities_json = ?, pricing_json = ?, canonical_slug = ?, \
             input_modalities_json = ?, output_modalities_json = ?, \
             supported_parameters_json = ?, metadata_json = ?, hidden = ?, pinned = ?, source = ?, \
             created_at = ?, updated_at = ? \
             WHERE id = ?",
        )
        .bind(&row.provider_id)
        .bind(&row.remote_id)
        .bind(&row.label)
        .bind(row.context_window)
        .bind(row.max_output_tokens)
        .bind(&row.capabilities_json)
        .bind(&row.pricing_json)
        .bind(&row.canonical_slug)
        .bind(&row.input_modalities_json)
        .bind(&row.output_modalities_json)
        .bind(&row.supported_parameters_json)
        .bind(&row.metadata_json)
        .bind(row.hidden)
        .bind(row.pinned)
        .bind(&row.source)
        .bind(&row.created_at)
        .bind(&row.updated_at)
        .bind(&row.id)
        .execute(&self.pool)
        .await
        .map_err(|error| {
            map_unique_violation(error, "model (provider_id, remote_id) already exists")
        })?;

        if result.rows_affected() == 0 {
            return Err(StorageError::NotFound(format!("model {}", model.id)));
        }

        Ok(())
    }

    async fn upsert(&self, model: &Model) -> Result<(), StorageError> {
        let row = ModelRow::from_domain(model)?;
        sqlx::query(
            "INSERT INTO models \
             (id, provider_id, remote_id, label, context_window, max_output_tokens, \
              capabilities_json, pricing_json, canonical_slug, input_modalities_json, \
              output_modalities_json, supported_parameters_json, metadata_json, hidden, pinned, \
              source, created_at, updated_at) \
             VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) \
             ON CONFLICT(id) DO UPDATE SET \
             provider_id = excluded.provider_id, remote_id = excluded.remote_id, \
             label = excluded.label, context_window = excluded.context_window, \
             max_output_tokens = excluded.max_output_tokens, \
             capabilities_json = excluded.capabilities_json, pricing_json = excluded.pricing_json, \
             canonical_slug = excluded.canonical_slug, \
             input_modalities_json = excluded.input_modalities_json, \
             output_modalities_json = excluded.output_modalities_json, \
             supported_parameters_json = excluded.supported_parameters_json, \
             metadata_json = excluded.metadata_json, hidden = excluded.hidden, \
             pinned = excluded.pinned, source = excluded.source, \
             created_at = excluded.created_at, updated_at = excluded.updated_at",
        )
        .bind(&row.id)
        .bind(&row.provider_id)
        .bind(&row.remote_id)
        .bind(&row.label)
        .bind(row.context_window)
        .bind(row.max_output_tokens)
        .bind(&row.capabilities_json)
        .bind(&row.pricing_json)
        .bind(&row.canonical_slug)
        .bind(&row.input_modalities_json)
        .bind(&row.output_modalities_json)
        .bind(&row.supported_parameters_json)
        .bind(&row.metadata_json)
        .bind(row.hidden)
        .bind(row.pinned)
        .bind(&row.source)
        .bind(&row.created_at)
        .bind(&row.updated_at)
        .execute(&self.pool)
        .await
        .map_err(|error| {
            map_unique_violation(error, "model (provider_id, remote_id) already exists")
        })?;

        Ok(())
    }

    async fn get(&self, id: &str) -> Result<Option<Model>, StorageError> {
        let query = format!("SELECT {SELECT_COLUMNS} FROM models WHERE id = ?");
        let row = sqlx::query_as::<_, ModelRow>(&query)
            .bind(id)
            .fetch_optional(&self.pool)
            .await?;

        row.map(ModelRow::into_domain).transpose()
    }

    async fn get_by_remote_id(
        &self,
        provider_id: &str,
        remote_id: &str,
    ) -> Result<Option<Model>, StorageError> {
        let query =
            format!("SELECT {SELECT_COLUMNS} FROM models WHERE provider_id = ? AND remote_id = ?");
        let row = sqlx::query_as::<_, ModelRow>(&query)
            .bind(provider_id)
            .bind(remote_id)
            .fetch_optional(&self.pool)
            .await?;

        row.map(ModelRow::into_domain).transpose()
    }

    async fn list_by_provider(&self, provider_id: &str) -> Result<Vec<Model>, StorageError> {
        let query = format!(
            "SELECT {SELECT_COLUMNS} FROM models WHERE provider_id = ? \
             ORDER BY pinned DESC, label COLLATE NOCASE ASC, id ASC"
        );
        let rows = sqlx::query_as::<_, ModelRow>(&query)
            .bind(provider_id)
            .fetch_all(&self.pool)
            .await?;

        rows.into_iter().map(ModelRow::into_domain).collect()
    }

    async fn list_all(&self) -> Result<Vec<Model>, StorageError> {
        let query = format!(
            "SELECT {SELECT_COLUMNS} FROM models \
             ORDER BY provider_id ASC, pinned DESC, label COLLATE NOCASE ASC, id ASC"
        );
        let rows = sqlx::query_as::<_, ModelRow>(&query)
            .fetch_all(&self.pool)
            .await?;

        rows.into_iter().map(ModelRow::into_domain).collect()
    }

    async fn delete(&self, id: &str) -> Result<bool, StorageError> {
        let result = sqlx::query("DELETE FROM models WHERE id = ?")
            .bind(id)
            .execute(&self.pool)
            .await?;

        Ok(result.rows_affected() > 0)
    }

    async fn delete_by_provider(&self, provider_id: &str) -> Result<u64, StorageError> {
        let result = sqlx::query("DELETE FROM models WHERE provider_id = ?")
            .bind(provider_id)
            .execute(&self.pool)
            .await?;

        Ok(result.rows_affected())
    }

    async fn set_hidden(&self, id: &str, hidden: bool) -> Result<(), StorageError> {
        let result = sqlx::query("UPDATE models SET hidden = ?, updated_at = ? WHERE id = ?")
            .bind(i64::from(hidden))
            .bind(chatui_domain::Timestamp::now().to_rfc3339())
            .bind(id)
            .execute(&self.pool)
            .await?;

        if result.rows_affected() == 0 {
            return Err(StorageError::NotFound(format!("model {id}")));
        }

        Ok(())
    }

    async fn set_pinned(&self, id: &str, pinned: bool) -> Result<(), StorageError> {
        let result = sqlx::query("UPDATE models SET pinned = ?, updated_at = ? WHERE id = ?")
            .bind(i64::from(pinned))
            .bind(chatui_domain::Timestamp::now().to_rfc3339())
            .bind(id)
            .execute(&self.pool)
            .await?;

        if result.rows_affected() == 0 {
            return Err(StorageError::NotFound(format!("model {id}")));
        }

        Ok(())
    }
}
