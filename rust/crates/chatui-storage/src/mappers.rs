//! Mapping between domain types and SQLite rows.
//!
//! Structured values are stored as JSON text, booleans as integers, and
//! timestamps as RFC 3339 UTC text. The migration declares timestamp columns as
//! `INTEGER`, but SQLite's dynamic typing stores the RFC 3339 text unchanged
//! because it has no numeric affinity. This keeps the on-disk representation
//! aligned with the shared contract and with the Android Room entities.
//!
//! The `sync_state` column only stores the [`SyncState`] discriminant. The
//! provisional [`SyncMeta`] fields `last_synced_at` and `remote_revision` are
//! not persisted because the schema has no columns for them; M5 owns the final
//! sync shape.

use chatui_domain::{
    Attachment, BrowserCors, Conversation, Message, Model, ModelPricing, Provider, SyncMeta,
    SyncState, Timestamp, Tombstone,
};
use serde::Serialize;
use serde::de::DeserializeOwned;
use serde_json::{Map, Value};
use sqlx::FromRow;

use crate::StorageError;

/// Reserved metadata key used to preserve the three-state `browser_cors` value.
///
/// The initial migration declares `providers.browser_cors` as an `INTEGER` with
/// a `CHECK (browser_cors IN (0, 1))` constraint, while the shared contract and
/// the Android entity use the strings `supported`, `unsupported`, and
/// `unknown`. The integer column stores `1` for `supported` and `0` otherwise;
/// the exact string is kept in provider metadata under this key so all three
/// states round-trip without a destructive schema change.
const BROWSER_CORS_METADATA_KEY: &str = "$browserCors";

/// Secret metadata row. The ciphertext is opaque to the storage layer.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SecretRecord {
    /// Secret identifier.
    pub id: String,
    /// Encrypted secret bytes.
    pub ciphertext: Vec<u8>,
    /// Creation timestamp.
    pub created_at: Timestamp,
    /// Last update timestamp.
    pub updated_at: Timestamp,
}

/// One key/value settings row.
#[derive(Debug, Clone, PartialEq)]
pub struct SettingRecord {
    /// Setting key, matching the shared camelCase field name.
    pub key: String,
    /// JSON-encoded setting value.
    pub value: Value,
    /// Last update timestamp.
    pub updated_at: Timestamp,
}

/// Placeholder sync-queue entry. Sync behavior is owned by M5.
#[derive(Debug, Clone, PartialEq)]
pub struct SyncQueueEntry {
    /// Queue entry identifier.
    pub id: String,
    /// Entity type, for example `conversation`.
    pub entity_type: String,
    /// Entity identifier.
    pub entity_id: String,
    /// Operation name, for example `upsert` or `delete`.
    pub operation: String,
    /// Opaque JSON payload.
    pub payload: Value,
    /// Number of delivery attempts.
    pub attempts: u32,
    /// Creation timestamp.
    pub created_at: Timestamp,
    /// Last update timestamp.
    pub updated_at: Timestamp,
}

/// Conversation row.
#[derive(Debug, Clone, FromRow)]
pub struct ConversationRow {
    /// Conversation ID.
    pub id: String,
    /// Title.
    pub title: String,
    /// System prompt.
    pub system_prompt: String,
    /// Current provider ID.
    pub provider_id: Option<String>,
    /// Current model ID.
    pub model_id: Option<String>,
    /// Denormalized model label.
    pub model_label: Option<String>,
    /// JSON-encoded chat parameters.
    pub params_json: String,
    /// Pinned flag as an integer.
    pub pinned: i64,
    /// Archived flag as an integer.
    pub archived: i64,
    /// Temporary flag as an integer.
    pub temporary: i64,
    /// RFC 3339 creation timestamp.
    pub created_at: String,
    /// RFC 3339 update timestamp.
    pub updated_at: String,
    /// RFC 3339 last-message timestamp.
    pub last_message_at: Option<String>,
    /// Number of non-deleted messages.
    pub message_count: i64,
    /// JSON-encoded last error.
    pub last_error_json: Option<String>,
    /// Sync state discriminant.
    pub sync_state: String,
    /// Random backup storage key.
    pub storage_key: String,
    /// Monotonic local revision.
    pub revision: i64,
}

impl ConversationRow {
    /// Build a row from a domain conversation.
    pub fn from_domain(conversation: &Conversation) -> Result<Self, StorageError> {
        let sync_state = match &conversation.sync {
            Some(sync) => enum_to_text(&sync.state)?,
            None => enum_to_text(&SyncState::Local)?,
        };

        Ok(Self {
            id: conversation.id.clone(),
            title: conversation.title.clone(),
            system_prompt: conversation.system_prompt.clone(),
            provider_id: non_empty(&conversation.provider_id),
            model_id: non_empty(&conversation.model_id),
            model_label: non_empty(&conversation.model_label),
            params_json: to_json(&conversation.params)?,
            pinned: bool_to_int(conversation.pinned),
            archived: bool_to_int(conversation.archived),
            temporary: bool_to_int(conversation.temporary),
            created_at: conversation.created_at.to_rfc3339(),
            updated_at: conversation.updated_at.to_rfc3339(),
            last_message_at: Some(conversation.last_message_at.to_rfc3339()),
            message_count: i64::try_from(conversation.message_count).map_err(|_| {
                StorageError::Mapping("conversation message_count does not fit in i64".to_owned())
            })?,
            last_error_json: conversation.last_error.as_ref().map(to_json).transpose()?,
            sync_state,
            storage_key: conversation.storage_key.clone(),
            revision: i64::try_from(conversation.revision).map_err(|_| {
                StorageError::Mapping("conversation revision does not fit in i64".to_owned())
            })?,
        })
    }

    /// Convert the row into a domain conversation.
    pub fn into_domain(self) -> Result<Conversation, StorageError> {
        let sync = match self.sync_state.as_str() {
            "local" => None,
            other => Some(SyncMeta {
                state: enum_from_text(other)?,
                last_synced_at: None,
                remote_revision: None,
            }),
        };

        let last_message_at = match self.last_message_at {
            Some(value) => parse_timestamp(&value, "last_message_at")?,
            None => parse_timestamp(&self.created_at, "created_at")?,
        };

        Ok(Conversation {
            id: self.id,
            title: self.title,
            system_prompt: self.system_prompt,
            provider_id: self.provider_id.unwrap_or_default(),
            model_id: self.model_id.unwrap_or_default(),
            model_label: self.model_label.unwrap_or_default(),
            params: from_json(&self.params_json)?,
            pinned: int_to_bool(self.pinned),
            archived: int_to_bool(self.archived),
            temporary: int_to_bool(self.temporary),
            created_at: parse_timestamp(&self.created_at, "created_at")?,
            updated_at: parse_timestamp(&self.updated_at, "updated_at")?,
            last_message_at,
            message_count: u64::try_from(self.message_count).map_err(|_| {
                StorageError::Mapping("conversation message_count is negative".to_owned())
            })?,
            last_error: self.last_error_json.as_deref().map(from_json).transpose()?,
            sync,
            storage_key: self.storage_key,
            revision: u64::try_from(self.revision).map_err(|_| {
                StorageError::Mapping("conversation revision is negative".to_owned())
            })?,
        })
    }
}

/// Deletion tombstone row.
#[derive(Debug, Clone, FromRow)]
pub struct TombstoneRow {
    /// Tombstone ID.
    pub id: String,
    /// Conversation the tombstone applies to.
    pub conversation_id: String,
    /// Message the tombstone applies to, or `null` for a conversation deletion.
    pub message_id: Option<String>,
    /// RFC 3339 deletion timestamp.
    pub deleted_at: String,
    /// Device that observed the deletion.
    pub device_id: String,
}

impl TombstoneRow {
    /// Build a row from a domain tombstone.
    pub fn from_domain(tombstone: &Tombstone) -> Self {
        Self {
            id: tombstone.id.clone(),
            conversation_id: tombstone.conversation_id.clone(),
            message_id: tombstone.message_id.clone(),
            deleted_at: tombstone.deleted_at.to_rfc3339(),
            device_id: tombstone.device_id.clone(),
        }
    }

    /// Convert the row into a domain tombstone.
    pub fn into_domain(self) -> Result<Tombstone, StorageError> {
        Ok(Tombstone {
            id: self.id,
            conversation_id: self.conversation_id,
            message_id: self.message_id,
            deleted_at: parse_timestamp(&self.deleted_at, "deleted_at")?,
            device_id: self.device_id,
        })
    }
}

/// Message row.
#[derive(Debug, Clone, FromRow)]
pub struct MessageRow {
    /// Message ID.
    pub id: String,
    /// Owning conversation ID.
    pub conversation_id: String,
    /// Monotonic sequence.
    pub sequence: i64,
    /// Role discriminant.
    pub role: String,
    /// JSON-encoded content parts.
    pub parts_json: String,
    /// Status discriminant.
    pub status: String,
    /// Provider ID snapshot.
    pub provider_id: Option<String>,
    /// Model ID snapshot.
    pub model_id: Option<String>,
    /// Model label snapshot.
    pub model_label: Option<String>,
    /// JSON-encoded usage.
    pub usage_json: Option<String>,
    /// Finish reason discriminant.
    pub finish_reason: Option<String>,
    /// JSON-encoded error.
    pub error_json: Option<String>,
    /// Total latency in milliseconds.
    pub latency_ms: Option<i64>,
    /// Time to first token in milliseconds.
    pub first_token_ms: Option<i64>,
    /// RFC 3339 creation timestamp.
    pub created_at: String,
    /// RFC 3339 update timestamp.
    pub updated_at: String,
    /// RFC 3339 soft-deletion timestamp.
    pub deleted_at: Option<String>,
}

impl MessageRow {
    /// Build a row from a domain message.
    pub fn from_domain(message: &Message) -> Result<Self, StorageError> {
        Ok(Self {
            id: message.id.clone(),
            conversation_id: message.conversation_id.clone(),
            sequence: i64::try_from(message.sequence).map_err(|_| {
                StorageError::Mapping("message sequence does not fit in i64".to_owned())
            })?,
            role: enum_to_text(&message.role)?,
            parts_json: to_json(&message.parts)?,
            status: enum_to_text(&message.status)?,
            provider_id: message.provider_id.clone(),
            model_id: message.model_id.clone(),
            model_label: message.model_label.clone(),
            usage_json: message.usage.as_ref().map(to_json).transpose()?,
            finish_reason: message
                .finish_reason
                .as_ref()
                .map(enum_to_text)
                .transpose()?,
            error_json: message.error.as_ref().map(to_json).transpose()?,
            latency_ms: message
                .latency_ms
                .map(|value| {
                    i64::try_from(value).map_err(|_| {
                        StorageError::Mapping("message latency_ms does not fit in i64".to_owned())
                    })
                })
                .transpose()?,
            first_token_ms: message
                .first_token_ms
                .map(|value| {
                    i64::try_from(value).map_err(|_| {
                        StorageError::Mapping(
                            "message first_token_ms does not fit in i64".to_owned(),
                        )
                    })
                })
                .transpose()?,
            created_at: message.created_at.to_rfc3339(),
            updated_at: message.updated_at.to_rfc3339(),
            deleted_at: message.deleted_at.map(|value| value.to_rfc3339()),
        })
    }

    /// Convert the row into a domain message.
    pub fn into_domain(self) -> Result<Message, StorageError> {
        Ok(Message {
            id: self.id,
            conversation_id: self.conversation_id,
            sequence: u64::try_from(self.sequence)
                .map_err(|_| StorageError::Mapping("message sequence is negative".to_owned()))?,
            role: enum_from_text(&self.role)?,
            parts: from_json(&self.parts_json)?,
            status: enum_from_text(&self.status)?,
            provider_id: self.provider_id,
            model_id: self.model_id,
            model_label: self.model_label,
            usage: self.usage_json.as_deref().map(from_json).transpose()?,
            finish_reason: self
                .finish_reason
                .as_deref()
                .map(enum_from_text)
                .transpose()?,
            error: self.error_json.as_deref().map(from_json).transpose()?,
            latency_ms: self
                .latency_ms
                .map(|value| {
                    u64::try_from(value).map_err(|_| {
                        StorageError::Mapping("message latency_ms is negative".to_owned())
                    })
                })
                .transpose()?,
            first_token_ms: self
                .first_token_ms
                .map(|value| {
                    u64::try_from(value).map_err(|_| {
                        StorageError::Mapping("message first_token_ms is negative".to_owned())
                    })
                })
                .transpose()?,
            created_at: parse_timestamp(&self.created_at, "created_at")?,
            updated_at: parse_timestamp(&self.updated_at, "updated_at")?,
            deleted_at: self
                .deleted_at
                .as_deref()
                .map(|value| parse_timestamp(value, "deleted_at"))
                .transpose()?,
        })
    }
}

/// Attachment metadata row.
#[derive(Debug, Clone, FromRow)]
pub struct AttachmentRow {
    /// Attachment ID.
    pub id: String,
    /// Owning message ID.
    pub message_id: String,
    /// Kind discriminant.
    pub kind: String,
    /// MIME type.
    pub mime_type: String,
    /// Original file name.
    pub name: String,
    /// Size in bytes.
    pub size: i64,
    /// SHA-256 content hash.
    pub sha256: String,
    /// Content-addressed storage key.
    pub storage_key: String,
    /// Image width.
    pub width: Option<i64>,
    /// Image height.
    pub height: Option<i64>,
    /// RFC 3339 creation timestamp.
    pub created_at: String,
}

impl AttachmentRow {
    /// Build a row from domain attachment metadata.
    pub fn from_domain(attachment: &Attachment) -> Result<Self, StorageError> {
        let message_id = attachment.message_id.clone().ok_or_else(|| {
            StorageError::InvalidInput(
                "attachment metadata requires a linked message_id".to_owned(),
            )
        })?;

        Ok(Self {
            id: attachment.id.clone(),
            message_id,
            kind: enum_to_text(&attachment.kind)?,
            mime_type: attachment.mime_type.clone(),
            name: attachment.name.clone(),
            size: i64::try_from(attachment.size).map_err(|_| {
                StorageError::Mapping("attachment size does not fit in i64".to_owned())
            })?,
            sha256: attachment.sha256.clone(),
            storage_key: attachment.storage_key.clone(),
            width: attachment.width.map(i64::from),
            height: attachment.height.map(i64::from),
            created_at: attachment.created_at.to_rfc3339(),
        })
    }

    /// Convert the row into domain attachment metadata.
    pub fn into_domain(self) -> Result<Attachment, StorageError> {
        Ok(Attachment {
            id: self.id,
            message_id: Some(self.message_id),
            kind: enum_from_text(&self.kind)?,
            mime_type: self.mime_type,
            name: self.name,
            size: u64::try_from(self.size)
                .map_err(|_| StorageError::Mapping("attachment size is negative".to_owned()))?,
            sha256: self.sha256,
            storage_key: self.storage_key,
            width: self
                .width
                .map(|value| {
                    u32::try_from(value).map_err(|_| {
                        StorageError::Mapping("attachment width does not fit in u32".to_owned())
                    })
                })
                .transpose()?,
            height: self
                .height
                .map(|value| {
                    u32::try_from(value).map_err(|_| {
                        StorageError::Mapping("attachment height does not fit in u32".to_owned())
                    })
                })
                .transpose()?,
            created_at: parse_timestamp(&self.created_at, "created_at")?,
        })
    }
}

/// Provider row.
#[derive(Debug, Clone, FromRow)]
pub struct ProviderRow {
    /// Provider ID.
    pub id: String,
    /// Preset ID.
    pub preset_id: Option<String>,
    /// Display label.
    pub label: String,
    /// Kind discriminant.
    pub kind: String,
    /// Base URL.
    pub base_url: Option<String>,
    /// Authentication discriminant.
    pub auth_type: String,
    /// Secret-store reference.
    pub secret_id: Option<String>,
    /// JSON-encoded non-secret headers.
    pub headers_json: String,
    /// Enabled flag as an integer.
    pub enabled: i64,
    /// Browser CORS discriminant stored as an integer.
    pub browser_cors: i64,
    /// JSON-encoded metadata.
    pub metadata_json: String,
    /// RFC 3339 creation timestamp.
    pub created_at: String,
    /// RFC 3339 update timestamp.
    pub updated_at: String,
}

impl ProviderRow {
    /// Build a row from a domain provider.
    pub fn from_domain(provider: &Provider) -> Result<Self, StorageError> {
        let (metadata_json, browser_cors) =
            encode_provider_metadata(&provider.metadata, provider.browser_cors)?;

        Ok(Self {
            id: provider.id.clone(),
            preset_id: non_empty(&provider.preset_id),
            label: provider.label.clone(),
            kind: enum_to_text(&provider.kind)?,
            base_url: non_empty(&provider.base_url),
            auth_type: enum_to_text(&provider.auth_type)?,
            secret_id: provider.secret_id.clone(),
            headers_json: to_json(&provider.headers)?,
            enabled: bool_to_int(provider.enabled),
            browser_cors,
            metadata_json,
            created_at: provider.created_at.to_rfc3339(),
            updated_at: provider.updated_at.to_rfc3339(),
        })
    }

    /// Convert the row into a domain provider.
    pub fn into_domain(self) -> Result<Provider, StorageError> {
        let (metadata, browser_cors) =
            decode_provider_metadata(&self.metadata_json, self.browser_cors)?;

        Ok(Provider {
            id: self.id,
            preset_id: self.preset_id.unwrap_or_default(),
            label: self.label,
            kind: enum_from_text(&self.kind)?,
            base_url: self.base_url.unwrap_or_default(),
            auth_type: enum_from_text(&self.auth_type)?,
            secret_id: self.secret_id,
            headers: from_json(&self.headers_json)?,
            enabled: int_to_bool(self.enabled),
            browser_cors,
            metadata,
            created_at: parse_timestamp(&self.created_at, "created_at")?,
            updated_at: parse_timestamp(&self.updated_at, "updated_at")?,
        })
    }
}

/// Model row.
#[derive(Debug, Clone, FromRow)]
pub struct ModelRow {
    /// Model ID.
    pub id: String,
    /// Owning provider ID.
    pub provider_id: String,
    /// Provider-specific model identifier.
    pub remote_id: String,
    /// Display label.
    pub label: String,
    /// Context window.
    pub context_window: Option<i64>,
    /// Maximum output tokens.
    pub max_output_tokens: Option<i64>,
    /// JSON-encoded capabilities.
    pub capabilities_json: String,
    /// JSON-encoded pricing.
    pub pricing_json: String,
    /// Canonical slug.
    pub canonical_slug: Option<String>,
    /// JSON-encoded input modalities.
    pub input_modalities_json: String,
    /// JSON-encoded output modalities.
    pub output_modalities_json: String,
    /// JSON-encoded supported parameters.
    pub supported_parameters_json: String,
    /// JSON-encoded metadata.
    pub metadata_json: String,
    /// Hidden flag as an integer.
    pub hidden: i64,
    /// Pinned flag as an integer.
    pub pinned: i64,
    /// Source discriminant.
    pub source: String,
    /// RFC 3339 creation timestamp.
    pub created_at: String,
    /// RFC 3339 update timestamp.
    pub updated_at: String,
}

impl ModelRow {
    /// Build a row from a domain model.
    pub fn from_domain(model: &Model) -> Result<Self, StorageError> {
        Ok(Self {
            id: model.id.clone(),
            provider_id: model.provider_id.clone(),
            remote_id: model.remote_id.clone(),
            label: model.label.clone(),
            context_window: model.context_window.map(i64::from),
            max_output_tokens: model.max_output_tokens.map(i64::from),
            capabilities_json: to_json(&model.capabilities)?,
            pricing_json: to_json(&model.pricing)?,
            canonical_slug: model.canonical_slug.clone(),
            input_modalities_json: to_json(&model.input_modalities)?,
            output_modalities_json: to_json(&model.output_modalities)?,
            supported_parameters_json: to_json(&model.supported_parameters)?,
            metadata_json: to_json(&model.metadata)?,
            hidden: bool_to_int(model.hidden),
            pinned: bool_to_int(model.pinned),
            source: enum_to_text(&model.source)?,
            created_at: model.created_at.to_rfc3339(),
            updated_at: model.updated_at.to_rfc3339(),
        })
    }

    /// Convert the row into a domain model.
    pub fn into_domain(self) -> Result<Model, StorageError> {
        Ok(Model {
            id: self.id,
            provider_id: self.provider_id,
            remote_id: self.remote_id,
            label: self.label,
            context_window: self
                .context_window
                .map(|value| {
                    u32::try_from(value).map_err(|_| {
                        StorageError::Mapping("model context_window does not fit in u32".to_owned())
                    })
                })
                .transpose()?,
            max_output_tokens: self
                .max_output_tokens
                .map(|value| {
                    u32::try_from(value).map_err(|_| {
                        StorageError::Mapping(
                            "model max_output_tokens does not fit in u32".to_owned(),
                        )
                    })
                })
                .transpose()?,
            capabilities: from_json(&self.capabilities_json)?,
            pricing: from_json::<Option<ModelPricing>>(&self.pricing_json)?,
            canonical_slug: self.canonical_slug,
            input_modalities: from_json(&self.input_modalities_json)?,
            output_modalities: from_json(&self.output_modalities_json)?,
            supported_parameters: from_json(&self.supported_parameters_json)?,
            metadata: from_json::<Option<Value>>(&self.metadata_json)?,
            hidden: int_to_bool(self.hidden),
            pinned: int_to_bool(self.pinned),
            source: enum_from_text(&self.source)?,
            created_at: parse_timestamp(&self.created_at, "created_at")?,
            updated_at: parse_timestamp(&self.updated_at, "updated_at")?,
        })
    }
}

/// Secret metadata row.
#[derive(Debug, Clone, FromRow)]
pub struct SecretRow {
    /// Secret ID.
    pub id: String,
    /// Encrypted secret bytes.
    pub ciphertext: Vec<u8>,
    /// RFC 3339 creation timestamp.
    pub created_at: String,
    /// RFC 3339 update timestamp.
    pub updated_at: String,
}

impl SecretRow {
    /// Build a row from a secret record.
    pub fn from_record(record: &SecretRecord) -> Self {
        Self {
            id: record.id.clone(),
            ciphertext: record.ciphertext.clone(),
            created_at: record.created_at.to_rfc3339(),
            updated_at: record.updated_at.to_rfc3339(),
        }
    }

    /// Convert the row into a secret record.
    pub fn into_record(self) -> Result<SecretRecord, StorageError> {
        Ok(SecretRecord {
            id: self.id,
            ciphertext: self.ciphertext,
            created_at: parse_timestamp(&self.created_at, "created_at")?,
            updated_at: parse_timestamp(&self.updated_at, "updated_at")?,
        })
    }
}

/// Settings row.
#[derive(Debug, Clone, FromRow)]
pub struct SettingsRow {
    /// Setting key.
    pub key: String,
    /// JSON-encoded value.
    pub value_json: String,
    /// RFC 3339 update timestamp.
    pub updated_at: String,
}

impl SettingsRow {
    /// Convert the row into a setting record.
    pub fn into_record(self) -> Result<SettingRecord, StorageError> {
        Ok(SettingRecord {
            key: self.key,
            value: from_json(&self.value_json)?,
            updated_at: parse_timestamp(&self.updated_at, "updated_at")?,
        })
    }
}

/// Backup configuration row.
#[derive(Debug, Clone, FromRow)]
pub struct BackupConfigRow {
    /// Fixed row ID.
    pub id: String,
    /// Enabled flag as an integer.
    pub enabled: i64,
    /// JSON-encoded backup configuration.
    pub config_json: String,
    /// RFC 3339 creation timestamp.
    pub created_at: String,
    /// RFC 3339 update timestamp.
    pub updated_at: String,
}

impl BackupConfigRow {
    /// Build a row from a domain backup configuration.
    pub fn from_domain(
        config: &chatui_domain::BackupConfig,
        created_at: Timestamp,
        updated_at: Timestamp,
    ) -> Result<Self, StorageError> {
        Ok(Self {
            id: "default".to_owned(),
            enabled: bool_to_int(config.enabled),
            config_json: to_json(config)?,
            created_at: created_at.to_rfc3339(),
            updated_at: updated_at.to_rfc3339(),
        })
    }

    /// Convert the row into a domain backup configuration.
    pub fn into_domain(self) -> Result<chatui_domain::BackupConfig, StorageError> {
        from_json(&self.config_json)
    }
}

/// Sync-queue row.
#[derive(Debug, Clone, FromRow)]
pub struct SyncQueueRow {
    /// Queue entry ID.
    pub id: String,
    /// Entity type.
    pub entity_type: String,
    /// Entity ID.
    pub entity_id: String,
    /// Operation name.
    pub operation: String,
    /// JSON-encoded payload.
    pub payload_json: String,
    /// Delivery attempts.
    pub attempts: i64,
    /// RFC 3339 creation timestamp.
    pub created_at: String,
    /// RFC 3339 update timestamp.
    pub updated_at: String,
}

impl SyncQueueRow {
    /// Build a row from a sync-queue entry.
    pub fn from_entry(entry: &SyncQueueEntry) -> Result<Self, StorageError> {
        Ok(Self {
            id: entry.id.clone(),
            entity_type: entry.entity_type.clone(),
            entity_id: entry.entity_id.clone(),
            operation: entry.operation.clone(),
            payload_json: to_json(&entry.payload)?,
            attempts: i64::from(entry.attempts),
            created_at: entry.created_at.to_rfc3339(),
            updated_at: entry.updated_at.to_rfc3339(),
        })
    }

    /// Convert the row into a sync-queue entry.
    pub fn into_entry(self) -> Result<SyncQueueEntry, StorageError> {
        Ok(SyncQueueEntry {
            id: self.id,
            entity_type: self.entity_type,
            entity_id: self.entity_id,
            operation: self.operation,
            payload: from_json(&self.payload_json)?,
            attempts: u32::try_from(self.attempts)
                .map_err(|_| StorageError::Mapping("sync queue attempts is negative".to_owned()))?,
            created_at: parse_timestamp(&self.created_at, "created_at")?,
            updated_at: parse_timestamp(&self.updated_at, "updated_at")?,
        })
    }
}

fn enum_to_text<T: Serialize>(value: &T) -> Result<String, StorageError> {
    match serde_json::to_value(value)? {
        Value::String(text) => Ok(text),
        other => Err(StorageError::Mapping(format!(
            "expected a string enum value, got {other}"
        ))),
    }
}

fn enum_from_text<T: DeserializeOwned>(text: &str) -> Result<T, StorageError> {
    serde_json::from_value(Value::String(text.to_owned())).map_err(StorageError::from)
}

fn to_json<T: Serialize>(value: &T) -> Result<String, StorageError> {
    serde_json::to_string(value).map_err(StorageError::from)
}

fn from_json<T: DeserializeOwned>(text: &str) -> Result<T, StorageError> {
    serde_json::from_str(text).map_err(StorageError::from)
}

fn bool_to_int(value: bool) -> i64 {
    i64::from(value)
}

fn int_to_bool(value: i64) -> bool {
    value != 0
}

fn non_empty(value: &str) -> Option<String> {
    if value.is_empty() {
        None
    } else {
        Some(value.to_owned())
    }
}

fn parse_timestamp(value: &str, field: &str) -> Result<Timestamp, StorageError> {
    Timestamp::parse(value).map_err(|error| {
        StorageError::Mapping(format!("invalid {field} timestamp {value:?}: {error}"))
    })
}

fn encode_provider_metadata(
    metadata: &Option<Value>,
    browser_cors: BrowserCors,
) -> Result<(String, i64), StorageError> {
    let mut object = match metadata {
        None => Map::new(),
        Some(Value::Object(map)) => map.clone(),
        Some(other) => {
            return Err(StorageError::Mapping(format!(
                "provider metadata must be an object or null, got {other}"
            )));
        }
    };

    object.insert(
        BROWSER_CORS_METADATA_KEY.to_owned(),
        Value::String(enum_to_text(&browser_cors)?),
    );

    let browser_cors = match browser_cors {
        BrowserCors::Supported => 1,
        BrowserCors::Unsupported | BrowserCors::Unknown => 0,
    };

    Ok((serde_json::to_string(&Value::Object(object))?, browser_cors))
}

fn decode_provider_metadata(
    metadata_json: &str,
    browser_cors: i64,
) -> Result<(Option<Value>, BrowserCors), StorageError> {
    let mut metadata = from_json::<Option<Value>>(metadata_json)?;

    let browser_cors = match metadata.as_mut() {
        Some(Value::Object(map)) => match map.remove(BROWSER_CORS_METADATA_KEY) {
            Some(Value::String(text)) => enum_from_text(&text)?,
            Some(other) => {
                return Err(StorageError::Mapping(format!(
                    "provider metadata {BROWSER_CORS_METADATA_KEY} must be a string, got {other}"
                )));
            }
            None => browser_cors_from_int(browser_cors),
        },
        _ => browser_cors_from_int(browser_cors),
    };

    if let Some(Value::Object(map)) = &metadata
        && map.is_empty()
    {
        metadata = None;
    }

    Ok((metadata, browser_cors))
}

fn browser_cors_from_int(value: i64) -> BrowserCors {
    if value != 0 {
        BrowserCors::Supported
    } else {
        BrowserCors::Unknown
    }
}
