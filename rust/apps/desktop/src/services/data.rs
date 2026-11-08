//! Data-screen adapter over the shared SQLite runtime.
//!
//! [`SqliteDataService`] implements the synchronous
//! [`chatui_ui::data::DataService`] surface with the storage
//! [`ExportService`], [`ImportService`], the shared repositories, and the
//! content-addressed [`AttachmentStore`]. It never issues SQL directly and never
//! reads or returns a secret value.
//!
//! Exports reuse the storage sanitizer, so secret headers and secret-bearing
//! metadata keys cannot enter an archive. Attachment bytes are opt-in. Clear-all
//! removes conversations, messages, attachments, providers, models, and stored
//! keys while keeping settings, matching the in-memory reference service.

use std::collections::{BTreeMap, HashSet};
use std::sync::Arc;

use chatui_domain::{
    Attachment, ChatErrorCode, ContentPart, Conversation, Message, MessageRole, Timestamp,
};
use chatui_storage::{
    AttachmentRepository, AttachmentStore, ConversationRepository, ExportOptions, ExportService,
    ImportOptions, ImportService, MessageRepository, ModelRepository, ProviderRepository,
    SecretRepository, SettingsRepository, encode_export,
};
use chatui_ui::data::{
    ClearSummary, DataError, DataService, ExportArtifact, ExportFormat, ExportRequest,
    ExportSummary, ImportMode, ImportSummaryView, ProviderKeySummary, StorageUsage,
    StorageUsageEntry,
};
use sqlx::SqlitePool;

use crate::services::ServiceRuntime;
use crate::services::sync::SyncBridge;

/// Synchronous data service over the shared SQLite runtime.
pub struct SqliteDataService {
    bridge: SyncBridge,
    conversations: Arc<dyn ConversationRepository>,
    messages: Arc<dyn MessageRepository>,
    attachments: Arc<dyn AttachmentRepository>,
    providers: Arc<dyn ProviderRepository>,
    models: Arc<dyn ModelRepository>,
    secrets: Arc<dyn SecretRepository>,
    attachment_store: AttachmentStore,
    export: ExportService,
    import: ImportService,
}

impl SqliteDataService {
    /// Create the adapter over the shared bridge, pool, repositories, and store.
    #[allow(clippy::too_many_arguments)]
    pub fn new(
        bridge: SyncBridge,
        pool: SqlitePool,
        conversations: Arc<dyn ConversationRepository>,
        messages: Arc<dyn MessageRepository>,
        attachments: Arc<dyn AttachmentRepository>,
        providers: Arc<dyn ProviderRepository>,
        models: Arc<dyn ModelRepository>,
        settings: Arc<dyn SettingsRepository>,
        secrets: Arc<dyn SecretRepository>,
        attachment_store: AttachmentStore,
    ) -> Self {
        let export = ExportService::new(
            conversations.clone(),
            messages.clone(),
            attachments.clone(),
            providers.clone(),
            models.clone(),
            settings,
        )
        .with_attachment_store(attachment_store.clone());
        let import = ImportService::new(pool, attachment_store.clone());

        Self {
            bridge,
            conversations,
            messages,
            attachments,
            providers,
            models,
            secrets,
            attachment_store,
            export,
            import,
        }
    }

    /// Build the adapter from the shared desktop runtime.
    pub fn from_runtime(runtime: &ServiceRuntime) -> Self {
        Self::new(
            runtime.bridge(),
            runtime.pool().clone(),
            runtime.conversations(),
            runtime.messages(),
            runtime.attachment_repository(),
            runtime.providers(),
            runtime.models(),
            runtime.settings(),
            runtime.secrets(),
            runtime.attachment_store().clone(),
        )
    }
}

impl DataService for SqliteDataService {
    fn usage(&self) -> Result<StorageUsage, DataError> {
        let conversations = self
            .bridge
            .block_on(self.conversations.list_all())
            .map_err(DataError::from)?;

        let mut messages = Vec::new();
        for conversation in &conversations {
            messages.extend(
                self.bridge
                    .block_on(self.messages.list_all_including_deleted(&conversation.id))
                    .map_err(DataError::from)?,
            );
        }

        let attachments = self
            .bridge
            .block_on(self.attachments.list_all_unbounded())
            .map_err(DataError::from)?;
        let providers = self
            .bridge
            .block_on(self.providers.list())
            .map_err(DataError::from)?;
        let models = self
            .bridge
            .block_on(self.models.list_all())
            .map_err(DataError::from)?;
        let secret_ids = self
            .bridge
            .block_on(self.secrets.list_ids())
            .map_err(DataError::from)?;

        let mut secret_bytes = 0_u64;
        for id in &secret_ids {
            if let Some(record) = self
                .bridge
                .block_on(self.secrets.get(id))
                .map_err(DataError::from)?
            {
                secret_bytes += record.ciphertext.len() as u64;
            }
        }

        let mut attachment_sizes = BTreeMap::new();
        for attachment in &attachments {
            attachment_sizes
                .entry(attachment.sha256.clone())
                .or_insert(attachment.size);
        }

        Ok(StorageUsage::new(
            usage_entry(&conversations)?,
            usage_entry(&messages)?,
            StorageUsageEntry {
                count: attachments.len() as u64,
                bytes: attachment_sizes.values().sum(),
            },
            StorageUsageEntry {
                count: secret_ids.len() as u64,
                bytes: secret_bytes,
            },
            usage_entry(&providers)?,
            usage_entry(&models)?,
        ))
    }

    fn list_conversations(&self) -> Result<Vec<Conversation>, DataError> {
        self.bridge
            .block_on(self.export.list_conversations())
            .map_err(DataError::from)
    }

    fn export_all(&self, options: &ExportRequest) -> Result<ExportArtifact, DataError> {
        let export_options = ExportOptions {
            include_attachment_bytes: options.include_attachment_bytes,
            exported_at: None,
            app_version: None,
        };
        let export = self
            .bridge
            .block_on(self.export.export_all(&export_options))
            .map_err(|error| DataError::Export(error.to_string()))?;
        let content =
            encode_export(&export).map_err(|error| DataError::Export(error.to_string()))?;
        let summary =
            ExportSummary::from_export(&export, options.include_attachment_bytes, content.len());

        Ok(ExportArtifact {
            filename: export_filename(export.exported_at),
            mime_type: ExportFormat::Json.mime_type().to_owned(),
            content,
            summary,
        })
    }

    fn export_conversation(
        &self,
        conversation_id: &str,
        options: &ExportRequest,
    ) -> Result<ExportArtifact, DataError> {
        if options.format == ExportFormat::Markdown {
            return self.export_conversation_markdown(conversation_id);
        }

        let export_options = ExportOptions {
            include_attachment_bytes: options.include_attachment_bytes,
            exported_at: None,
            app_version: None,
        };
        let export = self
            .bridge
            .block_on(
                self.export
                    .export_conversation(conversation_id, &export_options),
            )
            .map_err(|error| DataError::Export(error.to_string()))?
            .ok_or_else(|| not_found_conversation(conversation_id))?;
        let conversation = export.conversations.first().ok_or_else(|| {
            DataError::Export("The export did not contain the conversation.".to_owned())
        })?;
        let content =
            encode_export(&export).map_err(|error| DataError::Export(error.to_string()))?;
        let summary =
            ExportSummary::from_export(&export, options.include_attachment_bytes, content.len());

        Ok(ExportArtifact {
            filename: conversation_filename(conversation, ExportFormat::Json),
            mime_type: ExportFormat::Json.mime_type().to_owned(),
            content,
            summary,
        })
    }

    fn import_text(&self, text: &str, mode: ImportMode) -> Result<ImportSummaryView, DataError> {
        let options = ImportOptions {
            mode: storage_import_mode(mode),
        };
        let summary = self
            .bridge
            .block_on(self.import.import_text(text, &options))
            .map_err(DataError::from)?;

        Ok(ImportSummaryView {
            mode,
            conversations: summary.conversations,
            messages: summary.messages,
            attachments: summary.attachments,
            providers: summary.providers,
            models: summary.models,
            attachment_bytes: summary.attachment_bytes,
            skipped_attachment_bytes: summary.skipped_attachment_bytes,
        })
    }

    fn clear_all(&self) -> Result<ClearSummary, DataError> {
        let conversations = self
            .bridge
            .block_on(self.conversations.list_all())
            .map_err(DataError::from)?;
        let mut message_count = 0;
        for conversation in &conversations {
            message_count += self
                .bridge
                .block_on(self.messages.list_all_including_deleted(&conversation.id))
                .map_err(DataError::from)?
                .len();
        }
        let attachments = self
            .bridge
            .block_on(self.attachments.list_all_unbounded())
            .map_err(DataError::from)?;
        let providers = self
            .bridge
            .block_on(self.providers.list())
            .map_err(DataError::from)?;
        let models = self
            .bridge
            .block_on(self.models.list_all())
            .map_err(DataError::from)?;
        let secret_ids = self
            .bridge
            .block_on(self.secrets.list_ids())
            .map_err(DataError::from)?;

        let summary = ClearSummary {
            conversations: conversations.len(),
            messages: message_count,
            attachments: attachments.len(),
            secrets: secret_ids.len(),
            providers: providers.len(),
            models: models.len(),
        };

        for conversation in &conversations {
            self.bridge
                .block_on(self.conversations.delete(&conversation.id))
                .map_err(DataError::from)?;
        }
        for provider in &providers {
            self.bridge
                .block_on(self.providers.delete(&provider.id))
                .map_err(DataError::from)?;
        }
        for model in &models {
            self.bridge
                .block_on(self.models.delete(&model.id))
                .map_err(DataError::from)?;
        }
        for id in &secret_ids {
            self.bridge
                .block_on(self.secrets.delete(id))
                .map_err(DataError::from)?;
        }
        for attachment in &attachments {
            self.bridge
                .block_on(self.attachments.delete(&attachment.id))
                .map_err(DataError::from)?;
        }

        self.bridge
            .block_on(
                self.attachment_store
                    .cleanup_orphans_for_repository(self.attachments.as_ref()),
            )
            .map_err(DataError::from)?;

        Ok(summary)
    }

    fn list_provider_keys(&self) -> Result<Vec<ProviderKeySummary>, DataError> {
        let providers = self
            .bridge
            .block_on(self.providers.list())
            .map_err(DataError::from)?;
        let mut keys: Vec<ProviderKeySummary> = providers
            .iter()
            .map(ProviderKeySummary::from_provider)
            .collect();
        keys.sort_by(|left, right| {
            left.label
                .cmp(&right.label)
                .then_with(|| left.id.cmp(&right.id))
        });

        Ok(keys)
    }

    fn remove_provider_key(&self, provider_id: &str) -> Result<(), DataError> {
        let mut provider = self
            .bridge
            .block_on(self.providers.get(provider_id))
            .map_err(DataError::from)?
            .ok_or_else(|| DataError::NotFound(format!("Provider {provider_id} was not found.")))?;

        let secret_id = provider.secret_id.take();
        provider.metadata = without_key_metadata(provider.metadata.take());
        provider.updated_at = Timestamp::now();

        self.bridge
            .block_on(self.providers.update(&provider))
            .map_err(DataError::from)?;

        if let Some(secret_id) = secret_id {
            self.bridge
                .block_on(self.secrets.delete(&secret_id))
                .map_err(DataError::from)?;
        }

        Ok(())
    }
}

impl SqliteDataService {
    fn export_conversation_markdown(
        &self,
        conversation_id: &str,
    ) -> Result<ExportArtifact, DataError> {
        let conversation = self
            .bridge
            .block_on(self.conversations.get(conversation_id))
            .map_err(DataError::from)?
            .ok_or_else(|| not_found_conversation(conversation_id))?;
        let messages = self
            .bridge
            .block_on(self.messages.list_all_including_deleted(conversation_id))
            .map_err(DataError::from)?;
        let all_attachments = self
            .bridge
            .block_on(self.attachments.list_all_unbounded())
            .map_err(DataError::from)?;
        let referenced = referenced_attachment_ids(&messages);
        let attachments: Vec<Attachment> = all_attachments
            .into_iter()
            .filter(|attachment| referenced.contains(&attachment.id))
            .collect();
        let content = format_conversation_markdown(&conversation, &messages, &attachments);
        let summary = ExportSummary {
            conversations: 1,
            messages: messages.len(),
            attachments: attachments.len(),
            providers: 0,
            models: 0,
            includes_settings: false,
            includes_attachment_bytes: false,
            bytes: content.len(),
        };

        Ok(ExportArtifact {
            filename: conversation_filename(&conversation, ExportFormat::Markdown),
            mime_type: ExportFormat::Markdown.mime_type().to_owned(),
            content,
            summary,
        })
    }
}

fn usage_entry<T: serde::Serialize>(records: &[T]) -> Result<StorageUsageEntry, DataError> {
    let mut bytes = 0_u64;

    for record in records {
        bytes += serde_json::to_vec(record)
            .map_err(|error| DataError::Storage(error.to_string()))?
            .len() as u64;
    }

    Ok(StorageUsageEntry {
        count: records.len() as u64,
        bytes,
    })
}

fn storage_import_mode(mode: ImportMode) -> chatui_storage::ImportMode {
    match mode {
        ImportMode::Merge => chatui_storage::ImportMode::Merge,
        ImportMode::Replace => chatui_storage::ImportMode::Replace,
    }
}

fn not_found_conversation(conversation_id: &str) -> DataError {
    DataError::NotFound(format!("Conversation {conversation_id} was not found."))
}

fn referenced_attachment_ids(messages: &[Message]) -> HashSet<String> {
    let mut ids = HashSet::new();

    for message in messages {
        for part in &message.parts {
            match part {
                ContentPart::Image { attachment_id, .. }
                | ContentPart::File { attachment_id, .. } => {
                    ids.insert(attachment_id.clone());
                }
                _ => {}
            }
        }
    }

    ids
}

fn without_key_metadata(metadata: Option<serde_json::Value>) -> Option<serde_json::Value> {
    let mut object = metadata?.as_object().cloned()?;
    object.remove("key");

    if object.is_empty() {
        None
    } else {
        Some(serde_json::Value::Object(object))
    }
}

fn export_filename(exported_at: Timestamp) -> String {
    let stamp = exported_at.to_rfc3339().replace([':', '.'], "-");
    format!("chatui-export-{stamp}.json")
}

fn conversation_filename(conversation: &Conversation, format: ExportFormat) -> String {
    format!(
        "chatui-{}-{}.{}",
        slugify(&conversation.title),
        conversation.id,
        format.extension()
    )
}

fn slugify(value: &str) -> String {
    let mut slug = String::new();
    let mut last_dash = false;

    for character in value.chars() {
        if character.is_ascii_alphanumeric() {
            slug.push(character.to_ascii_lowercase());
            last_dash = false;
        } else if !last_dash && !slug.is_empty() {
            slug.push('-');
            last_dash = true;
        }
    }

    let slug = slug.trim_matches('-');

    if slug.is_empty() {
        "conversation".to_owned()
    } else {
        slug.chars().take(48).collect()
    }
}

fn format_conversation_markdown(
    conversation: &Conversation,
    messages: &[Message],
    attachments: &[Attachment],
) -> String {
    let mut lines = vec![
        format!("# {}", conversation.title),
        String::new(),
        format!("- Conversation ID: `{}`", conversation.id),
        format!("- Provider: {}", conversation.provider_id),
        format!(
            "- Model: {} (`{}`)",
            conversation.model_label, conversation.model_id
        ),
        format!("- Created: {}", conversation.created_at),
        format!("- Updated: {}", conversation.updated_at),
        format!("- Last message: {}", conversation.last_message_at),
        format!("- Messages: {}", conversation.message_count),
        format!(
            "- Pinned: {}",
            if conversation.pinned { "yes" } else { "no" }
        ),
        format!(
            "- Archived: {}",
            if conversation.archived { "yes" } else { "no" }
        ),
        format!(
            "- Temporary: {}",
            if conversation.temporary { "yes" } else { "no" }
        ),
        String::new(),
    ];

    if !conversation.system_prompt.trim().is_empty() {
        lines.push("## System prompt".to_owned());
        lines.push(String::new());
        lines.push(conversation.system_prompt.clone());
        lines.push(String::new());
    }

    lines.push("## Messages".to_owned());
    lines.push(String::new());

    let mut messages: Vec<&Message> = messages.iter().collect();
    messages.sort_by_key(|message| message.sequence);

    for message in messages {
        let snapshot = match (&message.model_label, &message.model_id) {
            (Some(label), Some(id)) => format!(" — {label} (`{id}`)"),
            (Some(label), None) => format!(" — {label}"),
            _ => String::new(),
        };
        lines.push(format!(
            "### {}. {} — {}{snapshot}",
            message.sequence + 1,
            role_label(message.role),
            message.created_at
        ));
        lines.push(String::new());

        for part in &message.parts {
            lines.push(render_part(attachments, part));
            lines.push(String::new());
        }

        if let Some(usage) = &message.usage {
            lines.push(format!(
                "_Usage: {} in / {} out / {} total{}_",
                optional_count(usage.input_tokens),
                optional_count(usage.output_tokens),
                optional_count(usage.total_tokens),
                if usage.estimated { " (estimated)" } else { "" }
            ));
            lines.push(String::new());
        }

        if let Some(error) = &message.error {
            lines.push(format!(
                "_Error ({}): {}_",
                error_code_label(error.code),
                error.message
            ));
            lines.push(String::new());
        }
    }

    format!("{}\n", lines.join("\n").trim_end())
}

fn render_part(attachments: &[Attachment], part: &ContentPart) -> String {
    match part {
        ContentPart::Text { text } => text.clone(),
        ContentPart::Reasoning { text } => format!("_Reasoning:_\n\n{text}"),
        ContentPart::ToolCall {
            id,
            name,
            arguments_json,
        } => format!("_Tool call_ `{name}` ({id}):\n\n```json\n{arguments_json}\n```"),
        ContentPart::ToolResult {
            tool_call_id,
            content,
        } => format!("_Tool result_ ({tool_call_id}):\n\n{content}"),
        ContentPart::Image {
            attachment_id,
            mime_type,
            alt,
        } => {
            let name = attachment_name(attachments, attachment_id)
                .unwrap_or_else(|| attachment_id.clone());
            let alt = if alt.is_empty() {
                String::new()
            } else {
                format!(" — {alt}")
            };
            format!("_[Image: {name} ({mime_type}), attachment {attachment_id}]_{alt}")
        }
        ContentPart::File {
            attachment_id,
            mime_type,
            name,
        } => format!("_[File: {name} ({mime_type}), attachment {attachment_id}]_"),
    }
}

fn attachment_name(attachments: &[Attachment], attachment_id: &str) -> Option<String> {
    attachments
        .iter()
        .find(|attachment| attachment.id == attachment_id)
        .map(|attachment| attachment.name.clone())
}

fn optional_count(value: Option<u32>) -> String {
    value
        .map(|value| value.to_string())
        .unwrap_or_else(|| "?".to_owned())
}

fn role_label(role: MessageRole) -> &'static str {
    match role {
        MessageRole::System => "system",
        MessageRole::User => "user",
        MessageRole::Assistant => "assistant",
        MessageRole::Tool => "tool",
    }
}

fn error_code_label(code: ChatErrorCode) -> &'static str {
    match code {
        ChatErrorCode::Auth => "auth",
        ChatErrorCode::Permission => "permission",
        ChatErrorCode::RateLimit => "rate_limit",
        ChatErrorCode::Quota => "quota",
        ChatErrorCode::ModelNotFound => "model_not_found",
        ChatErrorCode::ContextLength => "context_length",
        ChatErrorCode::ContentFilter => "content_filter",
        ChatErrorCode::InvalidRequest => "invalid_request",
        ChatErrorCode::Network => "network",
        ChatErrorCode::Cors => "cors",
        ChatErrorCode::Timeout => "timeout",
        ChatErrorCode::Cancelled => "cancelled",
        ChatErrorCode::Provider => "provider",
        ChatErrorCode::Parse => "parse",
        ChatErrorCode::Storage => "storage",
        ChatErrorCode::Unknown => "unknown",
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::BTreeMap;

    use chatui_domain::{
        AttachmentKind, BrowserCors, ChatParams, FinishReason, MessageStatus, Model,
        ModelCapability, ModelSource, Provider, ProviderAuthType, ProviderKind,
    };
    use chatui_storage::{AttachmentService, SecretRecord, default_settings, sha256_hex};

    fn timestamp(seconds: u32) -> Timestamp {
        Timestamp::parse(&format!("2026-11-04T12:00:{seconds:02}.000Z")).expect("valid timestamp")
    }

    fn runtime() -> (tempfile::TempDir, ServiceRuntime) {
        let directory = tempfile::tempdir().expect("create temporary directory");
        let runtime =
            ServiceRuntime::open_at(directory.path().join("chatui.sqlite3"), directory.path())
                .expect("open runtime");
        (directory, runtime)
    }

    fn provider(id: &str, secret_id: Option<&str>) -> Provider {
        Provider {
            id: id.to_owned(),
            preset_id: "custom".to_owned(),
            label: format!("Provider {id}"),
            kind: ProviderKind::OpenaiCompatible,
            base_url: "https://example.com/v1".to_owned(),
            auth_type: ProviderAuthType::Bearer,
            secret_id: secret_id.map(str::to_owned),
            headers: BTreeMap::new(),
            enabled: true,
            browser_cors: BrowserCors::Unknown,
            metadata: None,
            created_at: timestamp(0),
            updated_at: timestamp(0),
        }
    }

    fn model(id: &str, provider_id: &str) -> Model {
        Model {
            id: id.to_owned(),
            provider_id: provider_id.to_owned(),
            remote_id: format!("remote-{id}"),
            label: format!("Model {id}"),
            context_window: Some(128_000),
            max_output_tokens: Some(4_096),
            capabilities: vec![ModelCapability::Text],
            pricing: None,
            canonical_slug: None,
            input_modalities: Vec::new(),
            output_modalities: Vec::new(),
            supported_parameters: Vec::new(),
            metadata: None,
            hidden: false,
            pinned: false,
            source: ModelSource::Manual,
            created_at: timestamp(0),
            updated_at: timestamp(0),
        }
    }

    fn conversation(id: &str, provider_id: &str, model_id: &str, created: u32) -> Conversation {
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
            created_at: timestamp(created),
            updated_at: timestamp(created),
            last_message_at: timestamp(created),
            message_count: 0,
            last_error: None,
            sync: None,
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

    fn seed(runtime: &ServiceRuntime) {
        let secrets = runtime.secrets();
        runtime
            .block_on(secrets.put(&SecretRecord {
                id: "provider-provider-1".to_owned(),
                ciphertext: b"super-secret".to_vec(),
                created_at: timestamp(0),
                updated_at: timestamp(0),
            }))
            .expect("insert secret");

        let providers = runtime.providers();
        runtime
            .block_on(providers.insert(&provider("provider-1", Some("provider-provider-1"))))
            .expect("insert provider");

        let models = runtime.models();
        runtime
            .block_on(models.insert(&model("model-1", "provider-1")))
            .expect("insert model");

        let conversations = runtime.conversations();
        runtime
            .block_on(conversations.create(&conversation(
                "conversation-1",
                "provider-1",
                "model-1",
                0,
            )))
            .expect("insert conversation");

        let messages = runtime.messages();
        let mut first = message("message-1", "conversation-1", 0, "hello");
        first.parts.push(ContentPart::File {
            attachment_id: "attachment-1".to_owned(),
            mime_type: "text/plain".to_owned(),
            name: "notes.txt".to_owned(),
        });
        runtime
            .block_on(messages.insert(&first))
            .expect("insert message");
        runtime
            .block_on(messages.insert(&message("message-2", "conversation-1", 1, "world")))
            .expect("insert message");

        let attachments = runtime.attachment_repository();
        let service = AttachmentService::new(runtime.attachment_store().clone(), attachments);
        runtime
            .block_on(service.add(
                attachment("attachment-1", "message-1", "notes.txt", b"hello"),
                b"hello",
            ))
            .expect("insert attachment");
    }

    #[test]
    fn usage_counts_records_and_attachment_bytes() {
        let (_directory, runtime) = runtime();
        seed(&runtime);
        let service = SqliteDataService::from_runtime(&runtime);

        let usage = service.usage().expect("usage");

        assert_eq!(usage.conversations.count, 1);
        assert_eq!(usage.messages.count, 2);
        assert_eq!(usage.attachments.count, 1);
        assert_eq!(usage.attachments.bytes, 5);
        assert_eq!(usage.secrets.count, 1);
        assert_eq!(usage.secrets.bytes, 12);
        assert_eq!(usage.providers.count, 1);
        assert_eq!(usage.models.count, 1);
        assert!(usage.total_bytes > 0);
    }

    #[test]
    fn export_all_includes_entities_and_excludes_secrets() {
        let (_directory, runtime) = runtime();
        seed(&runtime);

        let providers = runtime.providers();
        let mut provider = provider("provider-1", Some("provider-provider-1"));
        provider
            .headers
            .insert("Authorization".to_owned(), "Bearer super-secret".to_owned());
        provider.metadata = Some(serde_json::json!({
            "apiKey": "super-secret",
            "key": { "label": "Personal key" },
            "freeTier": true
        }));
        runtime
            .block_on(providers.update(&provider))
            .expect("update provider");

        let service = SqliteDataService::from_runtime(&runtime);
        let artifact = service
            .export_all(&ExportRequest::all())
            .expect("export all");

        assert!(!artifact.content.contains("super-secret"));
        let value: serde_json::Value =
            serde_json::from_str(&artifact.content).expect("parse export");
        assert_eq!(
            value["conversations"]
                .as_array()
                .expect("conversations")
                .len(),
            1
        );
        assert_eq!(value["messages"].as_array().expect("messages").len(), 2);
        assert_eq!(
            value["attachments"].as_array().expect("attachments").len(),
            1
        );
        assert_eq!(value["providers"].as_array().expect("providers").len(), 1);
        assert_eq!(value["models"].as_array().expect("models").len(), 1);
        assert!(value["attachments"][0]["dataBase64"].is_null());
        assert_eq!(artifact.summary.conversations, 1);
        assert!(artifact.summary.includes_settings);
    }

    #[test]
    fn attachment_bytes_are_opt_in() {
        let (_directory, runtime) = runtime();
        seed(&runtime);
        let service = SqliteDataService::from_runtime(&runtime);

        let without = service
            .export_all(&ExportRequest::all())
            .expect("export without bytes");
        let parsed: serde_json::Value =
            serde_json::from_str(&without.content).expect("parse without bytes");
        assert!(parsed["attachments"][0]["dataBase64"].is_null());

        let with = service
            .export_all(&ExportRequest {
                include_attachment_bytes: true,
                ..ExportRequest::all()
            })
            .expect("export with bytes");
        let parsed: serde_json::Value =
            serde_json::from_str(&with.content).expect("parse with bytes");
        assert_eq!(
            parsed["attachments"][0]["dataBase64"].as_str(),
            Some("aGVsbG8=")
        );
        assert!(with.summary.includes_attachment_bytes);
    }

    #[test]
    fn per_conversation_json_and_markdown_export_work() {
        let (_directory, runtime) = runtime();
        seed(&runtime);
        let service = SqliteDataService::from_runtime(&runtime);

        let json = service
            .export_conversation(
                "conversation-1",
                &ExportRequest::conversation("conversation-1", ExportFormat::Json),
            )
            .expect("json export");
        let parsed: serde_json::Value = serde_json::from_str(&json.content).expect("parse json");
        assert_eq!(
            parsed["conversations"]
                .as_array()
                .expect("conversations")
                .len(),
            1
        );
        assert_eq!(parsed["messages"].as_array().expect("messages").len(), 2);

        let markdown = service
            .export_conversation(
                "conversation-1",
                &ExportRequest::conversation("conversation-1", ExportFormat::Markdown),
            )
            .expect("markdown export");
        assert_eq!(markdown.mime_type, "text/markdown");
        assert!(markdown.content.contains("# Conversation conversation-1"));
        assert!(markdown.content.contains("hello"));
        assert!(markdown.content.contains("world"));
        assert!(markdown.content.contains("notes.txt"));
        assert!(!markdown.summary.includes_settings);
        assert!(!markdown.summary.includes_attachment_bytes);
    }

    #[test]
    fn missing_conversation_export_is_not_found() {
        let (_directory, runtime) = runtime();
        let service = SqliteDataService::from_runtime(&runtime);

        let error = service
            .export_conversation(
                "missing",
                &ExportRequest::conversation("missing", ExportFormat::Json),
            )
            .expect_err("missing json conversation");
        assert!(matches!(error, DataError::NotFound(_)));

        let error = service
            .export_conversation(
                "missing",
                &ExportRequest::conversation("missing", ExportFormat::Markdown),
            )
            .expect_err("missing markdown conversation");
        assert!(matches!(error, DataError::NotFound(_)));
    }

    #[test]
    fn import_merges_by_default_and_replace_is_explicit() {
        let (_source_directory, source_runtime) = runtime();
        seed(&source_runtime);
        // The archive carries a provider secret reference, not the secret
        // itself. Clear the reference so the import does not depend on a local
        // secret row that the archive cannot contain.
        let source_providers = source_runtime.providers();
        source_runtime
            .block_on(source_providers.update(&provider("provider-1", None)))
            .expect("clear source secret reference");
        let source_service = SqliteDataService::from_runtime(&source_runtime);
        let archive = source_service
            .export_all(&ExportRequest::all())
            .expect("source export")
            .content;

        let (_target_directory, target_runtime) = runtime();
        let target_service = SqliteDataService::from_runtime(&target_runtime);

        let providers = target_runtime.providers();
        target_runtime
            .block_on(providers.insert(&provider("provider-2", None)))
            .expect("insert target provider");
        let models = target_runtime.models();
        target_runtime
            .block_on(models.insert(&model("model-2", "provider-2")))
            .expect("insert target model");
        let conversations = target_runtime.conversations();
        target_runtime
            .block_on(conversations.create(&conversation(
                "conversation-2",
                "provider-2",
                "model-2",
                5,
            )))
            .expect("insert target conversation");

        let merged = target_service
            .import_text(&archive, ImportMode::Merge)
            .expect("merge import");
        assert_eq!(merged.mode, ImportMode::Merge);
        assert_eq!(merged.conversations, 1);
        let listed = target_service
            .list_conversations()
            .expect("list after merge");
        assert!(
            listed
                .iter()
                .any(|conversation| conversation.id == "conversation-1")
        );
        assert!(
            listed
                .iter()
                .any(|conversation| conversation.id == "conversation-2")
        );

        let replaced = target_service
            .import_text(&archive, ImportMode::Replace)
            .expect("replace import");
        assert_eq!(replaced.mode, ImportMode::Replace);
        let listed = target_service
            .list_conversations()
            .expect("list after replace");
        assert!(
            listed
                .iter()
                .any(|conversation| conversation.id == "conversation-1")
        );
        assert!(
            !listed
                .iter()
                .any(|conversation| conversation.id == "conversation-2")
        );
    }

    #[test]
    fn invalid_import_is_rejected_without_data_loss() {
        let (_directory, runtime) = runtime();
        seed(&runtime);
        let service = SqliteDataService::from_runtime(&runtime);

        let error = service
            .import_text("not json", ImportMode::Merge)
            .expect_err("invalid JSON");
        assert!(matches!(error, DataError::Import(_)));
        assert_eq!(service.list_conversations().expect("list").len(), 1);

        let error = service
            .import_text("{\"format\":\"wrong\"}", ImportMode::Merge)
            .expect_err("invalid schema");
        assert!(matches!(error, DataError::Import(_)));
        assert_eq!(service.list_conversations().expect("list").len(), 1);
    }

    #[test]
    fn clear_all_removes_data_and_keys_but_keeps_settings() {
        let (_directory, runtime) = runtime();
        seed(&runtime);
        let service = SqliteDataService::from_runtime(&runtime);

        let summary = service.clear_all().expect("clear all");
        assert_eq!(summary.conversations, 1);
        assert_eq!(summary.messages, 2);
        assert_eq!(summary.attachments, 1);
        assert_eq!(summary.secrets, 1);
        assert_eq!(summary.providers, 1);
        assert_eq!(summary.models, 1);

        let usage = service.usage().expect("usage after clear");
        assert_eq!(usage.conversations.count, 0);
        assert_eq!(usage.messages.count, 0);
        assert_eq!(usage.attachments.count, 0);
        assert_eq!(usage.secrets.count, 0);
        assert_eq!(usage.providers.count, 0);
        assert_eq!(usage.models.count, 0);

        let settings = runtime
            .block_on(runtime.settings().load())
            .expect("load settings");
        assert_eq!(settings, default_settings());

        let key = sha256_hex(b"hello");
        assert!(!runtime.attachment_store().exists(&key).expect("exists"));
    }

    #[test]
    fn per_key_deletion_removes_secret_and_metadata() {
        let (_directory, runtime) = runtime();
        let secrets = runtime.secrets();
        runtime
            .block_on(secrets.put(&SecretRecord {
                id: "provider-provider-1".to_owned(),
                ciphertext: b"secret".to_vec(),
                created_at: timestamp(0),
                updated_at: timestamp(0),
            }))
            .expect("insert secret");

        let providers = runtime.providers();
        let mut provider = provider("provider-1", Some("provider-provider-1"));
        provider.metadata = Some(serde_json::json!({
            "key": { "label": "Personal key" },
            "freeTier": true
        }));
        runtime
            .block_on(providers.insert(&provider))
            .expect("insert provider");

        let service = SqliteDataService::from_runtime(&runtime);
        let keys = service.list_provider_keys().expect("keys");
        assert!(keys[0].has_key);
        assert_eq!(keys[0].key_label.as_deref(), Some("Personal key"));
        assert!(!format!("{keys:?}").contains("secret"));

        service
            .remove_provider_key("provider-1")
            .expect("remove key");

        let loaded = runtime
            .block_on(providers.get("provider-1"))
            .expect("get provider")
            .expect("provider exists");
        assert!(loaded.secret_id.is_none());
        let metadata = loaded.metadata.as_ref().expect("metadata");
        assert!(metadata.get("key").is_none());
        assert_eq!(metadata["freeTier"], serde_json::json!(true));
        assert!(
            !runtime
                .block_on(secrets.exists("provider-provider-1"))
                .expect("secret exists")
        );

        let keys = service.list_provider_keys().expect("keys after removal");
        assert!(!keys[0].has_key);

        let error = service
            .remove_provider_key("missing")
            .expect_err("missing provider");
        assert!(matches!(error, DataError::NotFound(_)));
    }

    #[test]
    fn list_conversations_is_newest_first() {
        let (_directory, runtime) = runtime();
        seed(&runtime);

        let conversations = runtime.conversations();
        runtime
            .block_on(conversations.create(&conversation(
                "conversation-2",
                "provider-1",
                "model-1",
                2,
            )))
            .expect("insert second conversation");

        let service = SqliteDataService::from_runtime(&runtime);
        let listed = service.list_conversations().expect("list conversations");
        assert_eq!(listed[0].id, "conversation-2");
        assert_eq!(listed[1].id, "conversation-1");
    }

    #[test]
    fn storage_errors_are_mapped_without_conversation_content() {
        let (_directory, runtime) = runtime();
        seed(&runtime);
        let service = SqliteDataService::from_runtime(&runtime);

        let error = service
            .remove_provider_key("missing")
            .expect_err("missing provider");
        assert!(matches!(error, DataError::NotFound(_)));
        assert!(!error.message().contains("hello"));
        assert!(!error.message().contains("world"));
    }
}
