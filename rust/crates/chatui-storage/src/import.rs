//! Safe `chatui.export` import.
//!
//! Validation happens before any write: the archive is size-bounded, parsed as
//! JSON, validated against `chat-export.schema.json`, and checked for duplicate
//! IDs, dangling references, unsafe attachment names, and invalid attachment
//! bytes. Merge is the default and never deletes local records. Replace is
//! explicit and clears only the data stores represented by the archive.
//!
//! The write itself runs in one SQLite transaction. Attachment bytes are
//! content-addressed and written before the transaction; if the transaction
//! fails, newly created byte files are removed so a failed import cannot leave
//! partial state or orphan files behind.

use std::collections::{BTreeMap, BTreeSet, HashMap, HashSet};

use base64::Engine;
use base64::engine::general_purpose::STANDARD as BASE64;
use chatui_contracts::{ChatExport, ExportAttachment, SchemaName, schemas};
use chatui_domain::{
    Attachment, AttachmentKind, ContentPart, Conversation, Message, Model, ModelSource, Provider,
    Settings, Timestamp,
};
use serde_json::Value;
use sqlx::SqlitePool;
use sqlx::sqlite::SqliteConnection;
use thiserror::Error;

use crate::StorageError;
use crate::attachments::{AttachmentStore, sha256_hex};
use crate::export::sanitize_provider;
use crate::mappers::{AttachmentRow, ConversationRow, MessageRow, ModelRow, ProviderRow};
use crate::repositories::messages::refresh_conversation_stats;
use crate::search::replace_fts_row;

/// Maximum accepted archive size before parsing.
pub const MAX_IMPORT_BYTES: usize = 50 * 1024 * 1024;

/// Maximum decoded size of one attachment restored from an archive.
pub const MAX_IMPORT_ATTACHMENT_BYTES: usize = 20 * 1024 * 1024;

/// Maximum attachment file name length, matching the picker rules.
pub const MAX_IMPORT_ATTACHMENT_NAME_LENGTH: usize = 255;

/// Maximum accepted entity ID length.
pub const MAX_IMPORT_ID_LENGTH: usize = 256;

/// How an import applies an archive to local data.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum ImportMode {
    /// Keep local records and upsert archive records by ID. This is the default.
    #[default]
    Merge,
    /// Clear the imported data stores first, then write the archive.
    Replace,
}

/// Options for an import.
#[derive(Debug, Clone, Default)]
pub struct ImportOptions {
    /// Merge is the default; replace must be explicit.
    pub mode: ImportMode,
}

/// Counts returned after a successful import.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ImportSummary {
    /// Applied mode.
    pub mode: ImportMode,
    /// Number of conversations written.
    pub conversations: usize,
    /// Number of messages written.
    pub messages: usize,
    /// Number of attachment metadata rows written.
    pub attachments: usize,
    /// Number of providers written.
    pub providers: usize,
    /// Number of models written.
    pub models: usize,
    /// Number of attachments whose bytes were restored.
    pub attachment_bytes: usize,
    /// Number of attachments whose bytes were absent from the archive.
    pub skipped_attachment_bytes: usize,
}

/// Errors returned by import validation or application.
#[derive(Debug, Error)]
pub enum ImportError {
    /// The archive exceeds the configured byte limit.
    #[error("the archive is larger than the {limit} byte import limit")]
    TooLarge {
        /// Configured limit.
        limit: usize,
    },
    /// The archive is not valid JSON.
    #[error("the archive is not valid JSON: {0}")]
    InvalidJson(String),
    /// The archive does not satisfy the export schema.
    #[error("the archive does not satisfy the chatui.export schema: {0}")]
    Schema(String),
    /// An entity ID is empty, too long, or contains control characters.
    #[error("invalid {label} id: {id:?}")]
    InvalidId {
        /// Entity kind.
        label: &'static str,
        /// Rejected ID.
        id: String,
    },
    /// An entity ID appears more than once.
    #[error("duplicate {label} id: {id}")]
    DuplicateId {
        /// Entity kind.
        label: &'static str,
        /// Duplicate ID.
        id: String,
    },
    /// A cross-record reference is missing or inconsistent.
    #[error("invalid reference: {0}")]
    InvalidReference(String),
    /// An attachment name is unsafe.
    #[error("unsafe attachment name: {0:?}")]
    UnsafeAttachmentName(String),
    /// Attachment metadata or bytes are invalid.
    #[error("invalid attachment {id}: {message}")]
    InvalidAttachment {
        /// Attachment ID.
        id: String,
        /// Failure detail.
        message: String,
    },
    /// One attachment exceeds the configured decoded byte limit.
    #[error("attachment {id} is larger than the {limit} byte import limit")]
    AttachmentTooLarge {
        /// Attachment ID.
        id: String,
        /// Configured limit.
        limit: usize,
    },
    /// The archive conflicts with local data in a way merge cannot resolve.
    #[error("import conflict: {0}")]
    Conflict(String),
    /// A storage operation failed.
    #[error("storage error: {0}")]
    Storage(#[from] StorageError),
    /// A SQLite operation failed.
    #[error("SQLite error: {0}")]
    Sqlx(#[from] sqlx::Error),
    /// A JSON operation failed.
    #[error("JSON error: {0}")]
    Json(#[from] serde_json::Error),
}

/// Validates and applies `chatui.export` archives.
pub struct ImportService {
    pool: SqlitePool,
    attachment_store: AttachmentStore,
    max_bytes: usize,
    max_attachment_bytes: usize,
}

impl ImportService {
    /// Create an import service over a database pool and attachment store.
    pub fn new(pool: SqlitePool, attachment_store: AttachmentStore) -> Self {
        Self {
            pool,
            attachment_store,
            max_bytes: MAX_IMPORT_BYTES,
            max_attachment_bytes: MAX_IMPORT_ATTACHMENT_BYTES,
        }
    }

    /// Override the archive and per-attachment byte limits.
    pub fn with_limits(mut self, max_bytes: usize, max_attachment_bytes: usize) -> Self {
        self.max_bytes = max_bytes;
        self.max_attachment_bytes = max_attachment_bytes;
        self
    }

    /// Import an archive from UTF-8 text.
    pub async fn import_text(
        &self,
        text: &str,
        options: &ImportOptions,
    ) -> Result<ImportSummary, ImportError> {
        self.import_bytes(text.as_bytes(), options).await
    }

    /// Import an archive from bytes.
    pub async fn import_bytes(
        &self,
        bytes: &[u8],
        options: &ImportOptions,
    ) -> Result<ImportSummary, ImportError> {
        if bytes.len() > self.max_bytes {
            return Err(ImportError::TooLarge {
                limit: self.max_bytes,
            });
        }

        let value: Value = serde_json::from_slice(bytes)
            .map_err(|error| ImportError::InvalidJson(error.to_string()))?;
        let archive = validate_import_archive(&value)?;
        self.import_archive(archive, options).await
    }

    /// Import an already-parsed archive.
    ///
    /// The archive is re-validated so callers cannot bypass the untrusted-input
    /// checks by constructing a [`ChatExport`] directly.
    pub async fn import_archive(
        &self,
        archive: ChatExport,
        options: &ImportOptions,
    ) -> Result<ImportSummary, ImportError> {
        let value = serde_json::to_value(&archive)?;
        let archive = validate_import_archive(&value)?;

        let mut decoded = BTreeMap::new();
        let mut skipped = 0;

        for attachment in &archive.attachments {
            match decode_attachment_bytes(attachment, self.max_attachment_bytes)? {
                Some(bytes) => {
                    decoded.insert(attachment.id.clone(), bytes);
                }
                None => skipped += 1,
            }
        }

        self.apply(archive, decoded, skipped, options.mode).await
    }

    async fn apply(
        &self,
        archive: ChatExport,
        decoded: BTreeMap<String, Vec<u8>>,
        skipped: usize,
        mode: ImportMode,
    ) -> Result<ImportSummary, ImportError> {
        let now = Timestamp::now();
        let existing_models = if mode == ImportMode::Merge {
            self.load_existing_models().await?
        } else {
            Vec::new()
        };
        let model_plan = plan_models(&archive.models, &existing_models)?;

        let conversations: Vec<Conversation> = archive
            .conversations
            .iter()
            .map(|conversation| remap_conversation(conversation, &model_plan))
            .collect();
        let mut messages: Vec<Message> = archive
            .messages
            .iter()
            .map(|message| remap_message(message, &model_plan))
            .collect();
        messages.sort_by(|left, right| {
            left.conversation_id
                .cmp(&right.conversation_id)
                .then_with(|| left.sequence.cmp(&right.sequence))
                .then_with(|| left.id.cmp(&right.id))
        });
        let settings = remap_settings(&archive.settings, &model_plan);
        let attachments = build_attachments(&archive, &messages)?;

        let mut written = Vec::new();
        for (attachment_id, bytes) in &decoded {
            let attachment = archive
                .attachments
                .iter()
                .find(|attachment| &attachment.id == attachment_id)
                .expect("decoded attachment exists in the archive");
            let sha256 = attachment.sha256.to_ascii_lowercase();
            let existed = self.attachment_store.exists(&sha256)?;

            if let Err(error) = self.attachment_store.put(bytes) {
                if !existed {
                    let _ = self.attachment_store.delete(&sha256);
                }
                return Err(error.into());
            }

            if !existed {
                written.push(sha256);
            }
        }

        let result = self
            .write_transaction(
                &archive,
                &model_plan,
                &conversations,
                &messages,
                &attachments,
                &settings,
                mode,
                now,
            )
            .await;

        if result.is_err() {
            for sha256 in written {
                let _ = self.attachment_store.delete(&sha256);
            }
        }

        let (providers, models) = result?;

        if mode == ImportMode::Replace {
            let referenced = self.load_referenced_storage_keys().await?;
            let _ = self.attachment_store.cleanup_orphans(&referenced);
        }

        Ok(ImportSummary {
            mode,
            conversations: conversations.len(),
            messages: messages.len(),
            attachments: attachments.len(),
            providers,
            models,
            attachment_bytes: decoded.len(),
            skipped_attachment_bytes: skipped,
        })
    }

    async fn load_existing_models(&self) -> Result<Vec<Model>, ImportError> {
        let rows = sqlx::query_as::<_, ModelRow>(
            "SELECT * FROM models ORDER BY provider_id ASC, remote_id ASC, id ASC",
        )
        .fetch_all(&self.pool)
        .await?;

        rows.into_iter()
            .map(ModelRow::into_domain)
            .collect::<Result<Vec<_>, _>>()
            .map_err(ImportError::from)
    }

    async fn load_referenced_storage_keys(&self) -> Result<HashSet<String>, ImportError> {
        let keys: Vec<String> = sqlx::query_scalar("SELECT DISTINCT storage_key FROM attachments")
            .fetch_all(&self.pool)
            .await?;

        Ok(keys.into_iter().collect())
    }

    #[allow(clippy::too_many_arguments)]
    async fn write_transaction(
        &self,
        archive: &ChatExport,
        model_plan: &ModelPlan,
        conversations: &[Conversation],
        messages: &[Message],
        attachments: &[Attachment],
        settings: &Settings,
        mode: ImportMode,
        now: Timestamp,
    ) -> Result<(usize, usize), ImportError> {
        let mut transaction = self.pool.begin().await?;

        if mode == ImportMode::Replace {
            sqlx::query("DELETE FROM messages_fts")
                .execute(&mut *transaction)
                .await?;
            sqlx::query("DELETE FROM attachments")
                .execute(&mut *transaction)
                .await?;
            sqlx::query("DELETE FROM messages")
                .execute(&mut *transaction)
                .await?;
            sqlx::query("DELETE FROM conversations")
                .execute(&mut *transaction)
                .await?;
            sqlx::query("DELETE FROM models")
                .execute(&mut *transaction)
                .await?;
            sqlx::query("DELETE FROM providers")
                .execute(&mut *transaction)
                .await?;
            sqlx::query("DELETE FROM settings")
                .execute(&mut *transaction)
                .await?;
        }

        for provider in &archive.providers {
            let provider = sanitize_provider(provider.clone());
            let existing = if mode == ImportMode::Merge {
                sqlx::query_as::<_, ProviderRow>("SELECT * FROM providers WHERE id = ?")
                    .bind(&provider.id)
                    .fetch_optional(&mut *transaction)
                    .await?
                    .map(ProviderRow::into_domain)
                    .transpose()?
            } else {
                None
            };
            let merged = merge_provider(provider, existing);
            let row = ProviderRow::from_domain(&merged)?;
            upsert_provider(&mut transaction, &row).await?;
        }

        for model in &model_plan.models {
            let existing = if mode == ImportMode::Merge {
                sqlx::query_as::<_, ModelRow>("SELECT * FROM models WHERE id = ?")
                    .bind(&model.id)
                    .fetch_optional(&mut *transaction)
                    .await?
                    .map(ModelRow::into_domain)
                    .transpose()?
            } else {
                None
            };
            let merged = merge_model(model.clone(), existing);
            let row = ModelRow::from_domain(&merged)?;
            upsert_model(&mut transaction, &row).await?;
        }

        for conversation in conversations {
            let row = ConversationRow::from_domain(conversation)?;
            upsert_conversation(&mut transaction, &row).await?;
        }

        let mut affected_conversations = BTreeSet::new();

        for message in messages {
            let previous_conversation: Option<String> =
                sqlx::query_scalar("SELECT conversation_id FROM messages WHERE id = ?")
                    .bind(&message.id)
                    .fetch_optional(&mut *transaction)
                    .await?;

            if let Some(previous) = previous_conversation {
                affected_conversations.insert(previous);
            }

            let sequence = i64::try_from(message.sequence).map_err(|_| ImportError::InvalidId {
                label: "message sequence",
                id: message.sequence.to_string(),
            })?;
            let conflict: Option<String> = sqlx::query_scalar(
                "SELECT id FROM messages \
                 WHERE conversation_id = ? AND sequence = ? AND id != ?",
            )
            .bind(&message.conversation_id)
            .bind(sequence)
            .bind(&message.id)
            .fetch_optional(&mut *transaction)
            .await?;

            if let Some(existing_id) = conflict {
                return Err(ImportError::Conflict(format!(
                    "message {} conflicts with local message {} at sequence {}",
                    message.id, existing_id, message.sequence
                )));
            }

            let row = MessageRow::from_domain(message)?;
            upsert_message(&mut transaction, &row).await?;
            replace_fts_row(&mut transaction, message).await?;
            affected_conversations.insert(message.conversation_id.clone());
        }

        for conversation_id in affected_conversations {
            refresh_conversation_stats(&mut transaction, &conversation_id).await?;
        }

        for attachment in attachments {
            let row = AttachmentRow::from_domain(attachment)?;
            upsert_attachment(&mut transaction, &row).await?;
        }

        write_settings(&mut transaction, settings, mode, now).await?;

        transaction.commit().await?;

        Ok((archive.providers.len(), model_plan.models.len()))
    }
}

/// Validate an untrusted archive value and return the typed export.
///
/// This is the untrusted boundary: it applies the full JSON Schema, then checks
/// unique IDs, cross-record references, attachment names, and SHA-256 values.
pub fn validate_import_archive(value: &Value) -> Result<ChatExport, ImportError> {
    schemas()
        .validate(SchemaName::ChatExport, value)
        .map_err(|error| ImportError::Schema(error.to_string()))?;

    let archive: ChatExport = serde_json::from_value(value.clone())
        .map_err(|error| ImportError::Schema(error.to_string()))?;

    validate_archive(&archive)?;
    Ok(archive)
}

fn validate_archive(archive: &ChatExport) -> Result<(), ImportError> {
    let conversation_ids = unique_ids(
        archive
            .conversations
            .iter()
            .map(|conversation| conversation.id.as_str()),
        "conversation",
    )?;
    unique_ids(
        archive.messages.iter().map(|message| message.id.as_str()),
        "message",
    )?;
    let attachment_ids = unique_ids(
        archive
            .attachments
            .iter()
            .map(|attachment| attachment.id.as_str()),
        "attachment",
    )?;
    let provider_ids = unique_ids(
        archive
            .providers
            .iter()
            .map(|provider| provider.id.as_str()),
        "provider",
    )?;
    unique_ids(
        archive.models.iter().map(|model| model.id.as_str()),
        "model",
    )?;

    let mut model_identities = HashSet::new();
    let mut models_by_id = HashMap::new();

    for model in &archive.models {
        if !provider_ids.contains(&model.provider_id) {
            return Err(ImportError::InvalidReference(format!(
                "model {} references missing provider {}",
                model.id, model.provider_id
            )));
        }

        let identity = (model.provider_id.clone(), model.remote_id.clone());
        if !model_identities.insert(identity) {
            return Err(ImportError::DuplicateId {
                label: "model identity",
                id: format!("{}/{}", model.provider_id, model.remote_id),
            });
        }

        models_by_id.insert(model.id.as_str(), model);
    }

    let mut message_sequences = HashSet::new();
    let mut referenced_attachments = HashSet::new();

    for message in &archive.messages {
        if !conversation_ids.contains(&message.conversation_id) {
            return Err(ImportError::InvalidReference(format!(
                "message {} references missing conversation {}",
                message.id, message.conversation_id
            )));
        }

        let sequence_key = (message.conversation_id.clone(), message.sequence);
        if !message_sequences.insert(sequence_key) {
            return Err(ImportError::DuplicateId {
                label: "message sequence",
                id: format!("{}/{}", message.conversation_id, message.sequence),
            });
        }

        if let Some(provider_id) = &message.provider_id
            && !provider_ids.contains(provider_id)
        {
            return Err(ImportError::InvalidReference(format!(
                "message {} references missing provider {}",
                message.id, provider_id
            )));
        }

        if let Some(model_id) = &message.model_id {
            let Some(model) = models_by_id.get(model_id.as_str()) else {
                return Err(ImportError::InvalidReference(format!(
                    "message {} references missing model {}",
                    message.id, model_id
                )));
            };

            if let Some(provider_id) = &message.provider_id
                && &model.provider_id != provider_id
            {
                return Err(ImportError::InvalidReference(format!(
                    "message {} model {} does not belong to provider {}",
                    message.id, model_id, provider_id
                )));
            }
        }

        for part in &message.parts {
            match part {
                ContentPart::Image { attachment_id, .. }
                | ContentPart::File { attachment_id, .. } => {
                    if !attachment_ids.contains(attachment_id) {
                        return Err(ImportError::InvalidReference(format!(
                            "message {} references missing attachment {}",
                            message.id, attachment_id
                        )));
                    }
                    referenced_attachments.insert(attachment_id.clone());
                }
                _ => {}
            }
        }
    }

    for conversation in &archive.conversations {
        if !provider_ids.contains(&conversation.provider_id) {
            return Err(ImportError::InvalidReference(format!(
                "conversation {} references missing provider {}",
                conversation.id, conversation.provider_id
            )));
        }

        let Some(model) = models_by_id.get(conversation.model_id.as_str()) else {
            return Err(ImportError::InvalidReference(format!(
                "conversation {} references missing model {}",
                conversation.id, conversation.model_id
            )));
        };

        if model.provider_id != conversation.provider_id {
            return Err(ImportError::InvalidReference(format!(
                "conversation {} model {} does not belong to provider {}",
                conversation.id, conversation.model_id, conversation.provider_id
            )));
        }
    }

    for attachment in &archive.attachments {
        validate_attachment_metadata(attachment)?;

        if !referenced_attachments.contains(&attachment.id) {
            return Err(ImportError::InvalidReference(format!(
                "attachment {} is not referenced by any message",
                attachment.id
            )));
        }
    }

    let settings = &archive.settings;

    if let Some(provider_id) = &settings.default_provider_id
        && !provider_ids.contains(provider_id)
    {
        return Err(ImportError::InvalidReference(format!(
            "settings reference missing provider {provider_id}"
        )));
    }

    if let Some(model_id) = &settings.default_model_id {
        let Some(model) = models_by_id.get(model_id.as_str()) else {
            return Err(ImportError::InvalidReference(format!(
                "settings reference missing model {model_id}"
            )));
        };

        if let Some(provider_id) = &settings.default_provider_id
            && &model.provider_id != provider_id
        {
            return Err(ImportError::InvalidReference(format!(
                "settings model {model_id} does not belong to provider {provider_id}"
            )));
        }
    }

    if let Some(conversation_id) = &settings.last_active_conversation_id
        && !conversation_ids.contains(conversation_id)
    {
        return Err(ImportError::InvalidReference(format!(
            "settings reference missing conversation {conversation_id}"
        )));
    }

    Ok(())
}

fn unique_ids<'a>(
    ids: impl Iterator<Item = &'a str>,
    label: &'static str,
) -> Result<HashSet<String>, ImportError> {
    let mut seen = HashSet::new();

    for id in ids {
        validate_id(id, label)?;

        if !seen.insert(id.to_owned()) {
            return Err(ImportError::DuplicateId {
                label,
                id: id.to_owned(),
            });
        }
    }

    Ok(seen)
}

fn validate_id(id: &str, label: &'static str) -> Result<(), ImportError> {
    if id.is_empty() || id.len() > MAX_IMPORT_ID_LENGTH || has_control_character(id) {
        return Err(ImportError::InvalidId {
            label,
            id: id.to_owned(),
        });
    }

    Ok(())
}

fn has_control_character(value: &str) -> bool {
    value.chars().any(char::is_control)
}

fn validate_attachment_metadata(attachment: &ExportAttachment) -> Result<(), ImportError> {
    validate_attachment_name(&attachment.name)?;

    if attachment.sha256.len() != 64
        || !attachment
            .sha256
            .bytes()
            .all(|byte| byte.is_ascii_hexdigit())
    {
        return Err(ImportError::InvalidAttachment {
            id: attachment.id.clone(),
            message: "invalid SHA-256 value".to_owned(),
        });
    }

    Ok(())
}

fn validate_attachment_name(name: &str) -> Result<(), ImportError> {
    if name.is_empty()
        || name.len() > MAX_IMPORT_ATTACHMENT_NAME_LENGTH
        || name.trim() != name
        || name == "."
        || name == ".."
        || has_control_character(name)
        || name.chars().any(|character| {
            matches!(
                character,
                '\\' | '/' | ':' | '*' | '?' | '"' | '<' | '>' | '|'
            )
        })
    {
        return Err(ImportError::UnsafeAttachmentName(name.to_owned()));
    }

    Ok(())
}

fn decode_attachment_bytes(
    attachment: &ExportAttachment,
    max_bytes: usize,
) -> Result<Option<Vec<u8>>, ImportError> {
    if attachment.size > max_bytes as u64 {
        return Err(ImportError::AttachmentTooLarge {
            id: attachment.id.clone(),
            limit: max_bytes,
        });
    }

    let Some(data) = &attachment.data_base64 else {
        return Ok(None);
    };

    let bytes = BASE64
        .decode(data)
        .map_err(|error| ImportError::InvalidAttachment {
            id: attachment.id.clone(),
            message: format!("invalid base64 data: {error}"),
        })?;

    if bytes.len() > max_bytes {
        return Err(ImportError::AttachmentTooLarge {
            id: attachment.id.clone(),
            limit: max_bytes,
        });
    }

    if bytes.len() as u64 != attachment.size {
        return Err(ImportError::InvalidAttachment {
            id: attachment.id.clone(),
            message: "byte length does not match the declared size".to_owned(),
        });
    }

    let digest = sha256_hex(&bytes);
    if digest != attachment.sha256.to_ascii_lowercase() {
        return Err(ImportError::InvalidAttachment {
            id: attachment.id.clone(),
            message: "bytes do not match the declared SHA-256 value".to_owned(),
        });
    }

    Ok(Some(bytes))
}

struct ModelPlan {
    models: Vec<Model>,
    remap: HashMap<String, String>,
}

fn plan_models(imported: &[Model], existing: &[Model]) -> Result<ModelPlan, ImportError> {
    let by_identity: HashMap<(&str, &str), &Model> = existing
        .iter()
        .map(|model| {
            (
                (model.provider_id.as_str(), model.remote_id.as_str()),
                model,
            )
        })
        .collect();
    let by_id: HashMap<&str, &Model> = existing
        .iter()
        .map(|model| (model.id.as_str(), model))
        .collect();
    let mut remap = HashMap::new();
    let mut models = Vec::new();

    for model in imported {
        if let Some(local) = by_id.get(model.id.as_str())
            && (local.provider_id != model.provider_id || local.remote_id != model.remote_id)
        {
            return Err(ImportError::Conflict(format!(
                "local model {} has a different provider/remote identity",
                model.id
            )));
        }

        if let Some(local) =
            by_identity.get(&(model.provider_id.as_str(), model.remote_id.as_str()))
            && local.id != model.id
        {
            remap.insert(model.id.clone(), local.id.clone());
            continue;
        }

        models.push(model.clone());
    }

    Ok(ModelPlan { models, remap })
}

fn remap_conversation(conversation: &Conversation, plan: &ModelPlan) -> Conversation {
    let mut conversation = conversation.clone();
    conversation.model_id = plan
        .remap
        .get(&conversation.model_id)
        .cloned()
        .unwrap_or_else(|| conversation.model_id.clone());

    if conversation.storage_key.is_empty() {
        conversation.storage_key = crate::paths::new_conversation_storage_key();
    }
    if conversation.revision == 0 {
        conversation.revision = 1;
    }

    conversation
}

fn remap_message(message: &Message, plan: &ModelPlan) -> Message {
    let mut message = message.clone();

    if let Some(model_id) = &message.model_id {
        message.model_id = Some(
            plan.remap
                .get(model_id)
                .cloned()
                .unwrap_or_else(|| model_id.clone()),
        );
    }

    message
}

fn remap_settings(settings: &Settings, plan: &ModelPlan) -> Settings {
    let mut settings = settings.clone();

    if let Some(model_id) = &settings.default_model_id {
        settings.default_model_id = Some(
            plan.remap
                .get(model_id)
                .cloned()
                .unwrap_or_else(|| model_id.clone()),
        );
    }

    settings
}

fn build_attachments(
    archive: &ChatExport,
    messages: &[Message],
) -> Result<Vec<Attachment>, ImportError> {
    let mut references: HashMap<&str, (&str, AttachmentKind, Timestamp)> = HashMap::new();

    for message in messages {
        for part in &message.parts {
            match part {
                ContentPart::Image { attachment_id, .. } => {
                    references.entry(attachment_id.as_str()).or_insert((
                        message.id.as_str(),
                        AttachmentKind::Image,
                        message.created_at,
                    ));
                }
                ContentPart::File { attachment_id, .. } => {
                    references.entry(attachment_id.as_str()).or_insert((
                        message.id.as_str(),
                        AttachmentKind::File,
                        message.created_at,
                    ));
                }
                _ => {}
            }
        }
    }

    let mut attachments = Vec::with_capacity(archive.attachments.len());

    for attachment in &archive.attachments {
        let Some((message_id, kind, created_at)) = references.get(attachment.id.as_str()) else {
            return Err(ImportError::InvalidReference(format!(
                "attachment {} is not referenced by any message",
                attachment.id
            )));
        };

        let sha256 = attachment.sha256.to_ascii_lowercase();
        attachments.push(Attachment {
            id: attachment.id.clone(),
            message_id: Some((*message_id).to_owned()),
            kind: *kind,
            mime_type: attachment.mime_type.clone(),
            name: attachment.name.clone(),
            size: attachment.size,
            sha256: sha256.clone(),
            storage_key: sha256,
            width: None,
            height: None,
            created_at: *created_at,
        });
    }

    Ok(attachments)
}

fn merge_provider(mut imported: Provider, existing: Option<Provider>) -> Provider {
    if let Some(existing) = existing {
        imported.enabled = existing.enabled;
        imported.secret_id = existing.secret_id.or(imported.secret_id);
        imported.created_at = existing.created_at;
    }

    imported
}

fn merge_model(mut imported: Model, existing: Option<Model>) -> Model {
    if let Some(existing) = existing {
        imported.hidden = existing.hidden;
        imported.pinned = existing.pinned;

        if existing.source == ModelSource::Manual {
            imported.source = ModelSource::Manual;
        }

        imported.created_at = existing.created_at;
    }

    imported
}

async fn upsert_provider(
    connection: &mut SqliteConnection,
    row: &ProviderRow,
) -> Result<(), StorageError> {
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
    .execute(&mut *connection)
    .await?;

    Ok(())
}

async fn upsert_model(
    connection: &mut SqliteConnection,
    row: &ModelRow,
) -> Result<(), StorageError> {
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
    .execute(&mut *connection)
    .await?;

    Ok(())
}

async fn upsert_conversation(
    connection: &mut SqliteConnection,
    row: &ConversationRow,
) -> Result<(), StorageError> {
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
    .execute(&mut *connection)
    .await?;

    Ok(())
}

async fn upsert_message(
    connection: &mut SqliteConnection,
    row: &MessageRow,
) -> Result<(), StorageError> {
    sqlx::query(
        "INSERT INTO messages \
         (id, conversation_id, sequence, role, parts_json, status, provider_id, model_id, \
          model_label, usage_json, finish_reason, error_json, latency_ms, first_token_ms, \
          created_at, updated_at, deleted_at) \
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) \
         ON CONFLICT(id) DO UPDATE SET \
         conversation_id = excluded.conversation_id, sequence = excluded.sequence, \
         role = excluded.role, parts_json = excluded.parts_json, status = excluded.status, \
         provider_id = excluded.provider_id, model_id = excluded.model_id, \
         model_label = excluded.model_label, usage_json = excluded.usage_json, \
         finish_reason = excluded.finish_reason, error_json = excluded.error_json, \
         latency_ms = excluded.latency_ms, first_token_ms = excluded.first_token_ms, \
         created_at = excluded.created_at, updated_at = excluded.updated_at, \
         deleted_at = excluded.deleted_at",
    )
    .bind(&row.id)
    .bind(&row.conversation_id)
    .bind(row.sequence)
    .bind(&row.role)
    .bind(&row.parts_json)
    .bind(&row.status)
    .bind(&row.provider_id)
    .bind(&row.model_id)
    .bind(&row.model_label)
    .bind(&row.usage_json)
    .bind(&row.finish_reason)
    .bind(&row.error_json)
    .bind(row.latency_ms)
    .bind(row.first_token_ms)
    .bind(&row.created_at)
    .bind(&row.updated_at)
    .bind(&row.deleted_at)
    .execute(&mut *connection)
    .await?;

    Ok(())
}

async fn upsert_attachment(
    connection: &mut SqliteConnection,
    row: &AttachmentRow,
) -> Result<(), StorageError> {
    sqlx::query(
        "INSERT INTO attachments \
         (id, message_id, kind, mime_type, name, size, sha256, storage_key, width, height, \
          created_at) \
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) \
         ON CONFLICT(id) DO UPDATE SET \
         message_id = excluded.message_id, kind = excluded.kind, \
         mime_type = excluded.mime_type, name = excluded.name, size = excluded.size, \
         sha256 = excluded.sha256, storage_key = excluded.storage_key, \
         width = excluded.width, height = excluded.height, created_at = excluded.created_at",
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
    .execute(&mut *connection)
    .await?;

    Ok(())
}

async fn write_settings(
    connection: &mut SqliteConnection,
    settings: &Settings,
    mode: ImportMode,
    now: Timestamp,
) -> Result<(), StorageError> {
    let value = serde_json::to_value(settings)?;
    let object = value.as_object().ok_or_else(|| {
        StorageError::Mapping("settings did not serialize to a JSON object".to_owned())
    })?;
    let updated_at = now.to_rfc3339();

    if mode == ImportMode::Replace {
        sqlx::query("DELETE FROM settings")
            .execute(&mut *connection)
            .await?;
    }

    let existing_keys: HashSet<String> = if mode == ImportMode::Merge {
        sqlx::query_scalar("SELECT key FROM settings")
            .fetch_all(&mut *connection)
            .await?
            .into_iter()
            .collect()
    } else {
        HashSet::new()
    };

    for (key, value) in object {
        if mode == ImportMode::Merge && existing_keys.contains(key) {
            continue;
        }

        let value_json = serde_json::to_string(value)?;
        sqlx::query(
            "INSERT INTO settings (key, value_json, updated_at) VALUES (?, ?, ?) \
             ON CONFLICT(key) DO UPDATE SET \
             value_json = excluded.value_json, updated_at = excluded.updated_at",
        )
        .bind(key)
        .bind(value_json)
        .bind(&updated_at)
        .execute(&mut *connection)
        .await?;
    }

    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::BTreeMap;
    use std::sync::Arc;

    use chatui_domain::{
        BrowserCors, ChatParams, FinishReason, MessageRole, MessageStatus, ModelCapability,
        ProviderAuthType, ProviderKind,
    };
    use sqlx::SqlitePool;

    use crate::attachments::AttachmentService;
    use crate::db::open_in_memory;
    use crate::export::{ExportOptions, ExportService};
    use crate::mappers::SecretRecord;
    use crate::repositories::{
        AttachmentRepository, ConversationRepository, MessageRepository, ModelRepository,
        ProviderRepository, SecretRepository, SettingsRepository, SqliteAttachmentRepository,
        SqliteConversationRepository, SqliteMessageRepository, SqliteModelRepository,
        SqliteProviderRepository, SqliteSecretRepository, SqliteSettingsRepository,
        default_settings,
    };

    fn timestamp(seconds: u32) -> Timestamp {
        Timestamp::parse(&format!("2026-11-04T12:00:{seconds:02}.000Z")).expect("valid timestamp")
    }

    fn provider(id: &str) -> Provider {
        Provider {
            id: id.to_owned(),
            preset_id: "custom".to_owned(),
            label: format!("Provider {id}"),
            kind: ProviderKind::OpenaiCompatible,
            base_url: "https://example.com/v1".to_owned(),
            auth_type: ProviderAuthType::Bearer,
            secret_id: None,
            headers: BTreeMap::new(),
            enabled: true,
            browser_cors: BrowserCors::Unknown,
            metadata: None,
            created_at: timestamp(0),
            updated_at: timestamp(0),
        }
    }

    fn model(id: &str, provider_id: &str, remote_id: &str) -> Model {
        Model {
            id: id.to_owned(),
            provider_id: provider_id.to_owned(),
            remote_id: remote_id.to_owned(),
            label: format!("Model {id}"),
            context_window: Some(128_000),
            max_output_tokens: Some(4_096),
            capabilities: vec![ModelCapability::Text, ModelCapability::Streaming],
            pricing: None,
            canonical_slug: None,
            input_modalities: Vec::new(),
            output_modalities: Vec::new(),
            supported_parameters: Vec::new(),
            metadata: None,
            hidden: false,
            pinned: false,
            source: chatui_domain::ModelSource::Discovered,
            created_at: timestamp(0),
            updated_at: timestamp(0),
        }
    }

    fn conversation(id: &str, provider_id: &str, model_id: &str) -> Conversation {
        Conversation {
            id: id.to_owned(),
            title: format!("Conversation {id}"),
            system_prompt: String::new(),
            provider_id: provider_id.to_owned(),
            model_id: model_id.to_owned(),
            model_label: format!("Model {model_id}"),
            params: ChatParams::default(),
            pinned: false,
            archived: false,
            temporary: false,
            created_at: timestamp(0),
            updated_at: timestamp(0),
            last_message_at: timestamp(0),
            message_count: 0,
            last_error: None,
            sync: None,
            storage_key: String::new(),
            revision: 1,
        }
    }

    fn message(id: &str, conversation_id: &str, sequence: u64, text: &str) -> Message {
        Message {
            id: id.to_owned(),
            conversation_id: conversation_id.to_owned(),
            sequence,
            role: MessageRole::User,
            parts: vec![ContentPart::Text {
                text: text.to_owned(),
            }],
            status: MessageStatus::Complete,
            provider_id: Some("provider-1".to_owned()),
            model_id: Some("model-1".to_owned()),
            model_label: Some("Model model-1".to_owned()),
            usage: None,
            finish_reason: Some(FinishReason::Stop),
            error: None,
            latency_ms: None,
            first_token_ms: None,
            created_at: timestamp(0),
            updated_at: timestamp(0),
            deleted_at: None,
        }
    }

    fn attachment(id: &str, message_id: &str, name: &str, bytes: &[u8]) -> Attachment {
        let sha256 = sha256_hex(bytes);
        Attachment {
            id: id.to_owned(),
            message_id: Some(message_id.to_owned()),
            kind: AttachmentKind::File,
            mime_type: "text/plain".to_owned(),
            name: name.to_owned(),
            size: bytes.len() as u64,
            sha256: sha256.clone(),
            storage_key: sha256,
            width: None,
            height: None,
            created_at: timestamp(0),
        }
    }

    struct Fixture {
        _directory: tempfile::TempDir,
        pool: SqlitePool,
        store: AttachmentStore,
        service: ImportService,
    }

    async fn fixture() -> Fixture {
        let directory = tempfile::tempdir().expect("create temporary directory");
        let store = AttachmentStore::new(directory.path());
        let pool = open_in_memory().await.expect("open in-memory database");
        let service = ImportService::new(pool.clone(), store.clone());

        Fixture {
            _directory: directory,
            pool,
            store,
            service,
        }
    }

    async fn seed_source(pool: &SqlitePool, store: &AttachmentStore) {
        let providers = SqliteProviderRepository::new(pool.clone());
        let models = SqliteModelRepository::new(pool.clone());
        let conversations = SqliteConversationRepository::new(pool.clone());
        let messages = SqliteMessageRepository::new(pool.clone());
        let attachments = Arc::new(SqliteAttachmentRepository::new(pool.clone()));
        let settings = SqliteSettingsRepository::new(pool.clone());

        providers
            .insert(&provider("provider-1"))
            .await
            .expect("insert provider");
        models
            .insert(&model("model-1", "provider-1", "remote-1"))
            .await
            .expect("insert model");
        conversations
            .create(&conversation("conversation-1", "provider-1", "model-1"))
            .await
            .expect("insert conversation");
        let mut first = message("message-1", "conversation-1", 0, "hello");
        first.parts.push(ContentPart::File {
            attachment_id: "attachment-1".to_owned(),
            mime_type: "text/plain".to_owned(),
            name: "notes.txt".to_owned(),
        });
        messages.insert(&first).await.expect("insert message");

        let service = AttachmentService::new(store.clone(), attachments);
        service
            .add(
                attachment("attachment-1", "message-1", "notes.txt", b"hello"),
                b"hello",
            )
            .await
            .expect("insert attachment");

        settings
            .save(&default_settings())
            .await
            .expect("save settings");
    }

    async fn source_export() -> (tempfile::TempDir, ChatExport) {
        let directory = tempfile::tempdir().expect("create temporary directory");
        let store = AttachmentStore::new(directory.path());
        let pool = open_in_memory().await.expect("open in-memory database");
        seed_source(&pool, &store).await;

        let service = ExportService::new(
            Arc::new(SqliteConversationRepository::new(pool.clone())),
            Arc::new(SqliteMessageRepository::new(pool.clone())),
            Arc::new(SqliteAttachmentRepository::new(pool.clone())),
            Arc::new(SqliteProviderRepository::new(pool.clone())),
            Arc::new(SqliteModelRepository::new(pool.clone())),
            Arc::new(SqliteSettingsRepository::new(pool.clone())),
        )
        .with_attachment_store(store);

        let export = service
            .export_all(&ExportOptions {
                include_attachment_bytes: true,
                exported_at: Some(timestamp(30)),
                ..ExportOptions::default()
            })
            .await
            .expect("export");

        (directory, export)
    }

    fn archive_json(export: &ChatExport) -> String {
        serde_json::to_string(export).expect("serialize archive")
    }

    #[tokio::test]
    async fn import_round_trips_export() {
        let (_source_directory, export) = source_export().await;
        let fixture = fixture().await;

        let summary = fixture
            .service
            .import_text(&archive_json(&export), &ImportOptions::default())
            .await
            .expect("import");

        assert_eq!(summary.mode, ImportMode::Merge);
        assert_eq!(summary.conversations, 1);
        assert_eq!(summary.messages, 1);
        assert_eq!(summary.attachments, 1);
        assert_eq!(summary.providers, 1);
        assert_eq!(summary.models, 1);
        assert_eq!(summary.attachment_bytes, 1);
        assert_eq!(summary.skipped_attachment_bytes, 0);

        let conversations = SqliteConversationRepository::new(fixture.pool.clone());
        let messages = SqliteMessageRepository::new(fixture.pool.clone());
        let attachments = SqliteAttachmentRepository::new(fixture.pool.clone());
        let providers = SqliteProviderRepository::new(fixture.pool.clone());
        let models = SqliteModelRepository::new(fixture.pool.clone());

        let conversation = conversations
            .get("conversation-1")
            .await
            .expect("get conversation")
            .expect("conversation exists");
        assert_eq!(conversation.model_id, "model-1");
        assert_eq!(conversation.model_label, "Model model-1");

        let message = messages
            .get("message-1")
            .await
            .expect("get message")
            .expect("message exists");
        assert_eq!(message.sequence, 0);
        assert_eq!(message.model_id.as_deref(), Some("model-1"));

        let attachment = attachments
            .get("attachment-1")
            .await
            .expect("get attachment")
            .expect("attachment exists");
        assert_eq!(attachment.sha256, sha256_hex(b"hello"));
        assert_eq!(
            fixture
                .store
                .get(&attachment.storage_key)
                .expect("read bytes"),
            b"hello"
        );

        assert!(
            providers
                .get("provider-1")
                .await
                .expect("provider")
                .is_some()
        );
        assert!(models.get("model-1").await.expect("model").is_some());
    }

    #[tokio::test]
    async fn import_merge_preserves_local_data() {
        let (_source_directory, export) = source_export().await;
        let fixture = fixture().await;

        let providers = SqliteProviderRepository::new(fixture.pool.clone());
        let conversations = SqliteConversationRepository::new(fixture.pool.clone());
        let settings = SqliteSettingsRepository::new(fixture.pool.clone());
        let secrets = SqliteSecretRepository::new(fixture.pool.clone());

        secrets
            .put(&SecretRecord {
                id: "local-secret".to_owned(),
                ciphertext: vec![1, 2, 3],
                created_at: timestamp(0),
                updated_at: timestamp(0),
            })
            .await
            .expect("insert local secret");

        let mut local_provider = provider("provider-1");
        local_provider.enabled = false;
        local_provider.secret_id = Some("local-secret".to_owned());
        providers
            .insert(&local_provider)
            .await
            .expect("insert local provider");
        conversations
            .create(&conversation("local-conversation", "provider-1", "model-1"))
            .await
            .expect("insert local conversation");

        let mut local_settings = default_settings();
        local_settings.theme = chatui_domain::Theme::Dark;
        settings.save(&local_settings).await.expect("save settings");

        fixture
            .service
            .import_text(&archive_json(&export), &ImportOptions::default())
            .await
            .expect("import");

        let merged_provider = providers
            .get("provider-1")
            .await
            .expect("get provider")
            .expect("provider exists");
        assert!(!merged_provider.enabled);
        assert_eq!(merged_provider.secret_id.as_deref(), Some("local-secret"));

        assert!(
            conversations
                .get("local-conversation")
                .await
                .expect("get local conversation")
                .is_some()
        );
        assert!(
            conversations
                .get("conversation-1")
                .await
                .expect("get imported conversation")
                .is_some()
        );

        let loaded = settings.load().await.expect("load settings");
        assert_eq!(loaded.theme, chatui_domain::Theme::Dark);
    }

    #[tokio::test]
    async fn import_remaps_model_ids_by_identity() {
        let (_source_directory, export) = source_export().await;
        let fixture = fixture().await;

        let providers = SqliteProviderRepository::new(fixture.pool.clone());
        let models = SqliteModelRepository::new(fixture.pool.clone());
        providers
            .insert(&provider("provider-1"))
            .await
            .expect("insert provider");
        models
            .insert(&model("local-model", "provider-1", "remote-1"))
            .await
            .expect("insert local model");

        fixture
            .service
            .import_text(&archive_json(&export), &ImportOptions::default())
            .await
            .expect("import");

        let conversations = SqliteConversationRepository::new(fixture.pool.clone());
        let conversation = conversations
            .get("conversation-1")
            .await
            .expect("get conversation")
            .expect("conversation exists");
        assert_eq!(conversation.model_id, "local-model");

        let messages = SqliteMessageRepository::new(fixture.pool.clone());
        let message = messages
            .get("message-1")
            .await
            .expect("get message")
            .expect("message exists");
        assert_eq!(message.model_id.as_deref(), Some("local-model"));
    }

    #[tokio::test]
    async fn import_skips_missing_attachment_bytes() {
        let (_source_directory, mut export) = source_export().await;
        export.attachments[0].data_base64 = None;
        let fixture = fixture().await;

        let summary = fixture
            .service
            .import_text(&archive_json(&export), &ImportOptions::default())
            .await
            .expect("import");

        assert_eq!(summary.attachment_bytes, 0);
        assert_eq!(summary.skipped_attachment_bytes, 1);

        let attachments = SqliteAttachmentRepository::new(fixture.pool.clone());
        let attachment = attachments
            .get("attachment-1")
            .await
            .expect("get attachment")
            .expect("attachment exists");
        assert!(
            !fixture
                .store
                .exists(&attachment.storage_key)
                .expect("exists")
        );
    }

    #[tokio::test]
    async fn import_rejects_invalid_json() {
        let fixture = fixture().await;
        let error = fixture
            .service
            .import_text("{not json", &ImportOptions::default())
            .await
            .expect_err("invalid JSON is rejected");
        assert!(matches!(error, ImportError::InvalidJson(_)));
    }

    #[tokio::test]
    async fn import_rejects_schema_violations() {
        let (_source_directory, mut export) = source_export().await;
        export.format = "not-chatui".to_owned();
        let fixture = fixture().await;

        let error = fixture
            .service
            .import_text(&archive_json(&export), &ImportOptions::default())
            .await
            .expect_err("schema violation is rejected");
        assert!(matches!(error, ImportError::Schema(_)));
    }

    #[tokio::test]
    async fn import_rejects_duplicate_ids() {
        let (_source_directory, mut export) = source_export().await;
        export.messages.push(export.messages[0].clone());
        let fixture = fixture().await;

        let error = fixture
            .service
            .import_text(&archive_json(&export), &ImportOptions::default())
            .await
            .expect_err("duplicate message id is rejected");
        assert!(matches!(
            error,
            ImportError::DuplicateId {
                label: "message",
                ..
            }
        ));
    }

    #[tokio::test]
    async fn import_rejects_dangling_references() {
        let (_source_directory, mut export) = source_export().await;
        export.messages[0].conversation_id = "missing-conversation".to_owned();
        let fixture = fixture().await;

        let error = fixture
            .service
            .import_text(&archive_json(&export), &ImportOptions::default())
            .await
            .expect_err("dangling conversation reference is rejected");
        assert!(matches!(error, ImportError::InvalidReference(_)));
    }

    #[tokio::test]
    async fn import_rejects_unsafe_attachment_names() {
        let (_source_directory, mut export) = source_export().await;
        export.attachments[0].name = "../escape.txt".to_owned();
        let fixture = fixture().await;

        let error = fixture
            .service
            .import_text(&archive_json(&export), &ImportOptions::default())
            .await
            .expect_err("unsafe attachment name is rejected");
        assert!(matches!(error, ImportError::UnsafeAttachmentName(_)));
    }

    #[tokio::test]
    async fn import_rejects_oversized_archive() {
        let fixture = fixture().await;
        let service = ImportService::new(fixture.pool.clone(), fixture.store.clone())
            .with_limits(8, MAX_IMPORT_ATTACHMENT_BYTES);

        let error = service
            .import_text("0123456789", &ImportOptions::default())
            .await
            .expect_err("oversized archive is rejected");
        assert!(matches!(error, ImportError::TooLarge { limit: 8 }));
    }

    #[tokio::test]
    async fn import_rejects_oversized_attachment() {
        let (_source_directory, mut export) = source_export().await;
        export.attachments[0].size = 100;
        let fixture = fixture().await;
        let service = ImportService::new(fixture.pool.clone(), fixture.store.clone())
            .with_limits(MAX_IMPORT_BYTES, 10);

        let error = service
            .import_text(&archive_json(&export), &ImportOptions::default())
            .await
            .expect_err("oversized attachment is rejected");
        assert!(matches!(error, ImportError::AttachmentTooLarge { .. }));
    }

    #[tokio::test]
    async fn import_rejects_invalid_attachment_bytes() {
        let (_source_directory, mut export) = source_export().await;
        export.attachments[0].data_base64 = Some("not base64".to_owned());
        let fixture = fixture().await;

        let error = fixture
            .service
            .import_text(&archive_json(&export), &ImportOptions::default())
            .await
            .expect_err("invalid base64 is rejected");
        assert!(matches!(error, ImportError::InvalidAttachment { .. }));
    }

    #[tokio::test]
    async fn import_rejects_attachment_size_mismatch() {
        let (_source_directory, mut export) = source_export().await;
        export.attachments[0].size = 99;
        let fixture = fixture().await;

        let error = fixture
            .service
            .import_text(&archive_json(&export), &ImportOptions::default())
            .await
            .expect_err("size mismatch is rejected");
        assert!(matches!(error, ImportError::InvalidAttachment { .. }));
    }

    #[tokio::test]
    async fn import_rejects_attachment_hash_mismatch() {
        let (_source_directory, mut export) = source_export().await;
        export.attachments[0].sha256 = "a".repeat(64);
        let fixture = fixture().await;

        let error = fixture
            .service
            .import_text(&archive_json(&export), &ImportOptions::default())
            .await
            .expect_err("hash mismatch is rejected");
        assert!(matches!(error, ImportError::InvalidAttachment { .. }));
    }

    #[tokio::test]
    async fn import_is_transactional_on_conflict() {
        let (_source_directory, export) = source_export().await;
        let fixture = fixture().await;

        let providers = SqliteProviderRepository::new(fixture.pool.clone());
        let models = SqliteModelRepository::new(fixture.pool.clone());
        let conversations = SqliteConversationRepository::new(fixture.pool.clone());
        let messages = SqliteMessageRepository::new(fixture.pool.clone());

        providers
            .insert(&provider("provider-1"))
            .await
            .expect("insert provider");
        models
            .insert(&model("model-1", "provider-1", "remote-1"))
            .await
            .expect("insert model");
        conversations
            .create(&conversation("conversation-1", "provider-1", "model-1"))
            .await
            .expect("insert conversation");
        messages
            .insert(&message("local-message", "conversation-1", 0, "local"))
            .await
            .expect("insert local message");

        let error = fixture
            .service
            .import_text(&archive_json(&export), &ImportOptions::default())
            .await
            .expect_err("sequence conflict is rejected");
        assert!(matches!(error, ImportError::Conflict(_)));

        assert!(
            messages
                .get("message-1")
                .await
                .expect("get imported message")
                .is_none()
        );
        assert!(!fixture.store.exists(&sha256_hex(b"hello")).expect("exists"));
    }

    #[tokio::test]
    async fn import_replace_clears_local_data() {
        let (_source_directory, export) = source_export().await;
        let fixture = fixture().await;

        let conversations = SqliteConversationRepository::new(fixture.pool.clone());
        conversations
            .create(&conversation("local-conversation", "provider-1", "model-1"))
            .await
            .expect("insert local conversation");

        let summary = fixture
            .service
            .import_text(
                &archive_json(&export),
                &ImportOptions {
                    mode: ImportMode::Replace,
                },
            )
            .await
            .expect("replace import");

        assert_eq!(summary.mode, ImportMode::Replace);
        assert!(
            conversations
                .get("local-conversation")
                .await
                .expect("get local conversation")
                .is_none()
        );
        assert!(
            conversations
                .get("conversation-1")
                .await
                .expect("get imported conversation")
                .is_some()
        );
    }

    #[tokio::test]
    async fn import_merge_fills_missing_settings_only() {
        let (_source_directory, mut export) = source_export().await;
        export.settings.theme = chatui_domain::Theme::Dark;
        export.settings.show_token_usage = false;
        let fixture = fixture().await;

        let settings = SqliteSettingsRepository::new(fixture.pool.clone());
        settings
            .set("theme", &serde_json::json!("light"))
            .await
            .expect("set local theme");

        fixture
            .service
            .import_text(&archive_json(&export), &ImportOptions::default())
            .await
            .expect("import");

        let loaded = settings.load().await.expect("load settings");
        assert_eq!(loaded.theme, chatui_domain::Theme::Light);
        assert!(!loaded.show_token_usage);
    }
}
