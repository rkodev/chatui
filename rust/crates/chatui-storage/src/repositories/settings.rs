//! Key/value settings repository with a typed [`Settings`] facade.
//!
//! Each setting is stored under its shared camelCase key, matching the web
//! client. Reads merge stored values over the defaults, so a fresh database and
//! a partially populated database both return a complete [`Settings`] value.

use async_trait::async_trait;
use chatui_domain::{ChatFontSize, ChatParams, Density, SendKey, Settings, Theme, Timestamp};
use serde_json::{Map, Value};
use sqlx::SqlitePool;

use crate::StorageError;
use crate::mappers::{SettingRecord, SettingsRow};

/// Settings persistence abstraction.
#[async_trait]
pub trait SettingsRepository: Send + Sync {
    /// Read one raw setting value.
    async fn get(&self, key: &str) -> Result<Option<Value>, StorageError>;
    /// Write one raw setting value.
    async fn set(&self, key: &str, value: &Value) -> Result<(), StorageError>;
    /// Delete one setting.
    async fn delete(&self, key: &str) -> Result<bool, StorageError>;
    /// List every setting row.
    async fn list(&self) -> Result<Vec<SettingRecord>, StorageError>;
    /// Load a complete settings object, merging stored values over defaults.
    async fn load(&self) -> Result<Settings, StorageError>;
    /// Persist every field of a settings object.
    async fn save(&self, settings: &Settings) -> Result<(), StorageError>;
}

/// SQLite-backed settings repository.
#[derive(Debug, Clone)]
pub struct SqliteSettingsRepository {
    pool: SqlitePool,
}

impl SqliteSettingsRepository {
    /// Create a repository over an existing pool.
    pub fn new(pool: SqlitePool) -> Self {
        Self { pool }
    }

    /// Borrow the underlying pool.
    pub fn pool(&self) -> &SqlitePool {
        &self.pool
    }
}

/// Spec-aligned default settings.
///
/// `remember_keys_on_web` defaults to true because the web client treats
/// "remember on this device" as the convenience default. `load_remote_images`
/// stays false until the user opts in.
pub fn default_settings() -> Settings {
    Settings {
        theme: Theme::System,
        density: Density::Comfortable,
        chat_font_size: ChatFontSize::Md,
        send_key: SendKey::Enter,
        default_provider_id: None,
        default_model_id: None,
        default_system_prompt: String::new(),
        default_params: ChatParams::default(),
        remember_keys_on_web: true,
        show_token_usage: true,
        load_remote_images: false,
        last_active_conversation_id: None,
        backup: None,
        onboarding_completed: false,
    }
}

#[async_trait]
impl SettingsRepository for SqliteSettingsRepository {
    async fn get(&self, key: &str) -> Result<Option<Value>, StorageError> {
        let value_json: Option<String> =
            sqlx::query_scalar("SELECT value_json FROM settings WHERE key = ?")
                .bind(key)
                .fetch_optional(&self.pool)
                .await?;

        value_json
            .map(|text| serde_json::from_str(&text).map_err(StorageError::from))
            .transpose()
    }

    async fn set(&self, key: &str, value: &Value) -> Result<(), StorageError> {
        let value_json = serde_json::to_string(value)?;
        let updated_at = Timestamp::now().to_rfc3339();

        sqlx::query(
            "INSERT INTO settings (key, value_json, updated_at) VALUES (?, ?, ?) \
             ON CONFLICT(key) DO UPDATE SET \
             value_json = excluded.value_json, updated_at = excluded.updated_at",
        )
        .bind(key)
        .bind(value_json)
        .bind(updated_at)
        .execute(&self.pool)
        .await?;

        Ok(())
    }

    async fn delete(&self, key: &str) -> Result<bool, StorageError> {
        let result = sqlx::query("DELETE FROM settings WHERE key = ?")
            .bind(key)
            .execute(&self.pool)
            .await?;

        Ok(result.rows_affected() > 0)
    }

    async fn list(&self) -> Result<Vec<SettingRecord>, StorageError> {
        let rows = sqlx::query_as::<_, SettingsRow>(
            "SELECT key, value_json, updated_at FROM settings ORDER BY key ASC",
        )
        .fetch_all(&self.pool)
        .await?;

        rows.into_iter().map(SettingsRow::into_record).collect()
    }

    async fn load(&self) -> Result<Settings, StorageError> {
        let mut merged = serde_json::to_value(default_settings())?
            .as_object()
            .cloned()
            .unwrap_or_else(Map::new);

        for record in self.list().await? {
            merged.insert(record.key, record.value);
        }

        serde_json::from_value(Value::Object(merged)).map_err(StorageError::from)
    }

    async fn save(&self, settings: &Settings) -> Result<(), StorageError> {
        let value = serde_json::to_value(settings)?;
        let object = value.as_object().ok_or_else(|| {
            StorageError::Mapping("settings did not serialize to a JSON object".to_owned())
        })?;
        let updated_at = Timestamp::now().to_rfc3339();

        let mut transaction = self.pool.begin().await?;
        for (key, value) in object {
            let value_json = serde_json::to_string(value)?;
            sqlx::query(
                "INSERT INTO settings (key, value_json, updated_at) VALUES (?, ?, ?) \
                 ON CONFLICT(key) DO UPDATE SET \
                 value_json = excluded.value_json, updated_at = excluded.updated_at",
            )
            .bind(key)
            .bind(value_json)
            .bind(&updated_at)
            .execute(&mut *transaction)
            .await?;
        }
        transaction.commit().await?;

        Ok(())
    }
}
