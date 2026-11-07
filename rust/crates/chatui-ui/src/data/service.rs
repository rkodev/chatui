//! Data-screen orchestration boundaries.
//!
//! [`DataService`] is the synchronous surface used by the Slint controller. The
//! desktop app can implement it with a blocking adapter over
//! [`chatui_storage::ExportService`], [`chatui_storage::ImportService`], the
//! repositories, and the secret store. [`MemoryDataService`] is a complete
//! in-memory implementation used by tests and as a reference for the adapter.
//!
//! The service never returns a secret value. Stored keys are represented by
//! non-secret metadata only.

use std::collections::{BTreeMap, HashSet};
use std::sync::Mutex;

use chatui_contracts::{ChatExport, ExportAttachment};
use chatui_domain::{
    Attachment, AttachmentKind, ChatErrorCode, ContentPart, Conversation, Message, MessageRole,
    Model, Provider, Settings, Timestamp,
};
use chatui_storage::{encode_export, validate_import_archive};

use crate::data::error::DataError;
use crate::data::state::{
    ClearSummary, ExportArtifact, ExportFormat, ExportRequest, ExportSummary, ImportMode,
    ImportSummaryView, ProviderKeySummary, StorageUsage, StorageUsageEntry,
};

macro_rules! usage_entry {
    ($records:expr) => {{
        let records = $records;
        let mut bytes = 0_u64;

        for record in records {
            bytes += serde_json::to_vec(record)
                .map_err(|error| DataError::Storage(error.to_string()))?
                .len() as u64;
        }

        StorageUsageEntry {
            count: records.len() as u64,
            bytes,
        }
    }};
}

/// Storage usage, export, import, clear-data, and per-key deletion.
pub trait DataService: Send + Sync {
    /// Read storage usage.
    fn usage(&self) -> Result<StorageUsage, DataError>;
    /// List conversations newest-first for the export picker.
    fn list_conversations(&self) -> Result<Vec<Conversation>, DataError>;
    /// Build a full export.
    fn export_all(&self, options: &ExportRequest) -> Result<ExportArtifact, DataError>;
    /// Build a per-conversation export.
    fn export_conversation(
        &self,
        conversation_id: &str,
        options: &ExportRequest,
    ) -> Result<ExportArtifact, DataError>;
    /// Validate and apply an archive.
    fn import_text(&self, text: &str, mode: ImportMode) -> Result<ImportSummaryView, DataError>;
    /// Clear conversations, messages, attachments, providers, models, and keys.
    fn clear_all(&self) -> Result<ClearSummary, DataError>;
    /// List non-secret provider key summaries.
    fn list_provider_keys(&self) -> Result<Vec<ProviderKeySummary>, DataError>;
    /// Remove one stored key and its cached metadata.
    fn remove_provider_key(&self, provider_id: &str) -> Result<(), DataError>;
}

/// Attachment metadata plus optional base64 bytes.
#[derive(Debug, Clone, PartialEq)]
pub struct MemoryAttachment {
    /// Attachment metadata.
    pub attachment: Attachment,
    /// Base64-encoded bytes, or `None` when the bytes are not stored.
    pub data_base64: Option<String>,
}

/// In-memory data used by [`MemoryDataService`].
#[derive(Debug, Clone)]
pub struct MemoryData {
    /// Conversations.
    pub conversations: Vec<Conversation>,
    /// Messages.
    pub messages: Vec<Message>,
    /// Attachments.
    pub attachments: Vec<MemoryAttachment>,
    /// Providers.
    pub providers: Vec<Provider>,
    /// Models.
    pub models: Vec<Model>,
    /// Settings.
    pub settings: Settings,
    /// Secret ID to opaque ciphertext bytes.
    pub secrets: BTreeMap<String, Vec<u8>>,
}

impl Default for MemoryData {
    fn default() -> Self {
        Self {
            conversations: Vec::new(),
            messages: Vec::new(),
            attachments: Vec::new(),
            providers: Vec::new(),
            models: Vec::new(),
            settings: chatui_storage::default_settings(),
            secrets: BTreeMap::new(),
        }
    }
}

/// In-memory [`DataService`] used by tests and as an adapter reference.
#[derive(Debug, Default)]
pub struct MemoryDataService {
    data: Mutex<MemoryData>,
}

impl MemoryDataService {
    /// Create an empty service.
    pub fn new() -> Self {
        Self::default()
    }

    /// Create a service with explicit data.
    pub fn with_data(data: MemoryData) -> Self {
        Self {
            data: Mutex::new(data),
        }
    }

    /// Add a conversation.
    pub fn add_conversation(&self, conversation: Conversation) {
        self.lock().conversations.push(conversation);
    }

    /// Add a message.
    pub fn add_message(&self, message: Message) {
        self.lock().messages.push(message);
    }

    /// Add an attachment.
    pub fn add_attachment(&self, attachment: Attachment, data_base64: Option<String>) {
        self.lock().attachments.push(MemoryAttachment {
            attachment,
            data_base64,
        });
    }

    /// Add a provider.
    pub fn add_provider(&self, provider: Provider) {
        self.lock().providers.push(provider);
    }

    /// Add a model.
    pub fn add_model(&self, model: Model) {
        self.lock().models.push(model);
    }

    /// Add a stored secret.
    pub fn add_secret(&self, id: impl Into<String>, ciphertext: Vec<u8>) {
        self.lock().secrets.insert(id.into(), ciphertext);
    }

    /// Snapshot the in-memory data.
    pub fn snapshot(&self) -> MemoryData {
        self.lock().clone()
    }

    fn lock(&self) -> std::sync::MutexGuard<'_, MemoryData> {
        self.data
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
    }

    fn build_export(
        &self,
        data: &MemoryData,
        conversation_id: Option<&str>,
        options: &ExportRequest,
    ) -> Result<ChatExport, DataError> {
        let conversations: Vec<Conversation> = match conversation_id {
            Some(id) => data
                .conversations
                .iter()
                .filter(|conversation| conversation.id == id)
                .cloned()
                .collect(),
            None => data.conversations.clone(),
        };

        if conversation_id.is_some() && conversations.is_empty() {
            return Err(DataError::NotFound(format!(
                "Conversation {} was not found.",
                conversation_id.unwrap_or_default()
            )));
        }

        let conversation_ids: HashSet<&str> = conversations
            .iter()
            .map(|conversation| conversation.id.as_str())
            .collect();
        let messages: Vec<Message> = data
            .messages
            .iter()
            .filter(|message| conversation_ids.contains(message.conversation_id.as_str()))
            .cloned()
            .collect();
        let referenced = referenced_attachment_ids(&messages);
        let attachments = data
            .attachments
            .iter()
            .filter(|attachment| referenced.contains(attachment.attachment.id.as_str()))
            .map(|attachment| {
                let mut exported = ExportAttachment {
                    id: attachment.attachment.id.clone(),
                    name: attachment.attachment.name.clone(),
                    mime_type: attachment.attachment.mime_type.clone(),
                    sha256: attachment.attachment.sha256.clone(),
                    size: attachment.attachment.size,
                    data_base64: None,
                };

                if options.include_attachment_bytes {
                    exported.data_base64 =
                        Some(attachment.data_base64.clone().ok_or_else(|| {
                            DataError::Export(format!(
                                "Attachment {} has no stored bytes.",
                                attachment.attachment.id
                            ))
                        })?);
                }

                Ok(exported)
            })
            .collect::<Result<Vec<_>, DataError>>()?;

        let export = ChatExport::new(
            Timestamp::now(),
            env!("CARGO_PKG_VERSION"),
            conversations,
            messages,
            attachments,
            data.providers.clone(),
            data.models.clone(),
            data.settings.clone(),
        );

        export
            .validate()
            .map_err(|error| DataError::Export(error.to_string()))?;

        Ok(export)
    }
}

impl DataService for MemoryDataService {
    fn usage(&self) -> Result<StorageUsage, DataError> {
        let data = self.lock();
        let conversations = usage_entry!(&data.conversations);
        let messages = usage_entry!(&data.messages);
        let providers = usage_entry!(&data.providers);
        let models = usage_entry!(&data.models);

        let mut attachment_sizes = BTreeMap::new();
        for attachment in &data.attachments {
            attachment_sizes
                .entry(attachment.attachment.sha256.clone())
                .or_insert(attachment.attachment.size);
        }
        let attachments = StorageUsageEntry {
            count: data.attachments.len() as u64,
            bytes: attachment_sizes.values().sum(),
        };
        let secrets = StorageUsageEntry {
            count: data.secrets.len() as u64,
            bytes: data.secrets.values().map(|bytes| bytes.len() as u64).sum(),
        };

        Ok(StorageUsage::new(
            conversations,
            messages,
            attachments,
            secrets,
            providers,
            models,
        ))
    }

    fn list_conversations(&self) -> Result<Vec<Conversation>, DataError> {
        let mut conversations = self.lock().conversations.clone();
        conversations.sort_by(|left, right| {
            right
                .updated_at
                .cmp(&left.updated_at)
                .then_with(|| right.id.cmp(&left.id))
        });
        Ok(conversations)
    }

    fn export_all(&self, options: &ExportRequest) -> Result<ExportArtifact, DataError> {
        let data = self.lock();
        let export = self.build_export(&data, None, options)?;
        let content = encode_export(&export)?;
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
        let data = self.lock();
        let conversation = data
            .conversations
            .iter()
            .find(|conversation| conversation.id == conversation_id)
            .ok_or_else(|| {
                DataError::NotFound(format!("Conversation {conversation_id} was not found."))
            })?;

        if options.format == ExportFormat::Markdown {
            let content = format_conversation_markdown(&data, conversation);
            let messages = data
                .messages
                .iter()
                .filter(|message| message.conversation_id == conversation_id)
                .count();
            let attachments = data
                .attachments
                .iter()
                .filter(|attachment| {
                    attachment
                        .attachment
                        .message_id
                        .as_deref()
                        .is_some_and(|id| {
                            data.messages.iter().any(|message| {
                                message.id == id && message.conversation_id == conversation_id
                            })
                        })
                })
                .count();
            let summary = ExportSummary {
                conversations: 1,
                messages,
                attachments,
                providers: 0,
                models: 0,
                includes_settings: false,
                includes_attachment_bytes: false,
                bytes: content.len(),
            };

            return Ok(ExportArtifact {
                filename: conversation_filename(conversation, ExportFormat::Markdown),
                mime_type: ExportFormat::Markdown.mime_type().to_owned(),
                content,
                summary,
            });
        }

        let export = self.build_export(&data, Some(conversation_id), options)?;
        let content = encode_export(&export)?;
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
        let value: serde_json::Value = serde_json::from_str(text).map_err(|error| {
            DataError::Import(format!("The archive is not valid JSON: {error}"))
        })?;
        let archive = validate_import_archive(&value)?;

        let mut data = self.lock();

        if mode == ImportMode::Replace {
            data.conversations.clear();
            data.messages.clear();
            data.attachments.clear();
            data.providers.clear();
            data.models.clear();
            data.settings = archive.settings.clone();
        }

        for provider in &archive.providers {
            upsert_by_id(&mut data.providers, provider.clone(), |provider| {
                provider.id.clone()
            });
        }
        for model in &archive.models {
            upsert_by_id(&mut data.models, model.clone(), |model| model.id.clone());
        }
        for conversation in &archive.conversations {
            upsert_by_id(
                &mut data.conversations,
                conversation.clone(),
                |conversation| conversation.id.clone(),
            );
        }
        for message in &archive.messages {
            upsert_by_id(&mut data.messages, message.clone(), |message| {
                message.id.clone()
            });
        }

        for attachment in &archive.attachments {
            let (message_id, kind) = attachment_reference(&archive, &attachment.id);
            let sha256 = attachment.sha256.to_ascii_lowercase();
            let domain = Attachment {
                id: attachment.id.clone(),
                message_id,
                kind,
                mime_type: attachment.mime_type.clone(),
                name: attachment.name.clone(),
                size: attachment.size,
                sha256: sha256.clone(),
                storage_key: sha256,
                width: None,
                height: None,
                created_at: Timestamp::now(),
            };
            upsert_by_id(
                &mut data.attachments,
                MemoryAttachment {
                    attachment: domain,
                    data_base64: attachment.data_base64.clone(),
                },
                |attachment| attachment.attachment.id.clone(),
            );
        }

        Ok(ImportSummaryView {
            mode,
            conversations: archive.conversations.len(),
            messages: archive.messages.len(),
            attachments: archive.attachments.len(),
            providers: archive.providers.len(),
            models: archive.models.len(),
            attachment_bytes: archive
                .attachments
                .iter()
                .filter(|attachment| attachment.data_base64.is_some())
                .count(),
            skipped_attachment_bytes: archive
                .attachments
                .iter()
                .filter(|attachment| attachment.data_base64.is_none())
                .count(),
        })
    }

    fn clear_all(&self) -> Result<ClearSummary, DataError> {
        let mut data = self.lock();
        let summary = ClearSummary {
            conversations: data.conversations.len(),
            messages: data.messages.len(),
            attachments: data.attachments.len(),
            secrets: data.secrets.len(),
            providers: data.providers.len(),
            models: data.models.len(),
        };

        data.conversations.clear();
        data.messages.clear();
        data.attachments.clear();
        data.providers.clear();
        data.models.clear();
        data.secrets.clear();

        Ok(summary)
    }

    fn list_provider_keys(&self) -> Result<Vec<ProviderKeySummary>, DataError> {
        let data = self.lock();
        let mut keys: Vec<ProviderKeySummary> = data
            .providers
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
        let mut data = self.lock();
        let index = data
            .providers
            .iter()
            .position(|provider| provider.id == provider_id)
            .ok_or_else(|| DataError::NotFound(format!("Provider {provider_id} was not found.")))?;

        let secret_id = data.providers[index].secret_id.take();
        if let Some(secret_id) = secret_id {
            data.secrets.remove(&secret_id);
        }

        data.providers[index].metadata =
            without_key_metadata(data.providers[index].metadata.take());
        data.providers[index].updated_at = Timestamp::now();

        Ok(())
    }
}

fn upsert_by_id<T>(items: &mut Vec<T>, item: T, key: impl Fn(&T) -> String) {
    let item_key = key(&item);

    if let Some(existing) = items.iter_mut().find(|existing| key(existing) == item_key) {
        *existing = item;
    } else {
        items.push(item);
    }
}

fn referenced_attachment_ids(messages: &[Message]) -> HashSet<&str> {
    let mut ids = HashSet::new();

    for message in messages {
        for part in &message.parts {
            match part {
                ContentPart::Image { attachment_id, .. }
                | ContentPart::File { attachment_id, .. } => {
                    ids.insert(attachment_id.as_str());
                }
                _ => {}
            }
        }
    }

    ids
}

fn attachment_reference(
    archive: &ChatExport,
    attachment_id: &str,
) -> (Option<String>, AttachmentKind) {
    for message in &archive.messages {
        for part in &message.parts {
            match part {
                ContentPart::Image {
                    attachment_id: id, ..
                } if id == attachment_id => {
                    return (Some(message.id.clone()), AttachmentKind::Image);
                }
                ContentPart::File {
                    attachment_id: id, ..
                } if id == attachment_id => {
                    return (Some(message.id.clone()), AttachmentKind::File);
                }
                _ => {}
            }
        }
    }

    (None, AttachmentKind::File)
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

fn format_conversation_markdown(data: &MemoryData, conversation: &Conversation) -> String {
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

    let mut messages: Vec<&Message> = data
        .messages
        .iter()
        .filter(|message| message.conversation_id == conversation.id)
        .collect();
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
            lines.push(render_part(data, part));
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

fn render_part(data: &MemoryData, part: &ContentPart) -> String {
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
            let name =
                attachment_name(data, attachment_id).unwrap_or_else(|| attachment_id.clone());
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

fn attachment_name(data: &MemoryData, attachment_id: &str) -> Option<String> {
    data.attachments
        .iter()
        .find(|attachment| attachment.attachment.id == attachment_id)
        .map(|attachment| attachment.attachment.name.clone())
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
    use chatui_domain::{
        BrowserCors, ChatParams, FinishReason, MessageStatus, ModelCapability, ModelSource,
        ProviderAuthType, ProviderKind,
    };

    fn timestamp(seconds: u32) -> Timestamp {
        Timestamp::parse(&format!("2026-11-07T12:00:{seconds:02}.000Z")).expect("valid timestamp")
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
            headers: std::collections::BTreeMap::new(),
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

    fn attachment(id: &str, message_id: &str, name: &str) -> Attachment {
        Attachment {
            id: id.to_owned(),
            message_id: Some(message_id.to_owned()),
            kind: AttachmentKind::File,
            mime_type: "text/plain".to_owned(),
            name: name.to_owned(),
            size: 5,
            sha256: "a".repeat(64),
            storage_key: "a".repeat(64),
            width: None,
            height: None,
            created_at: timestamp(0),
        }
    }

    fn seeded() -> MemoryDataService {
        let service = MemoryDataService::new();
        service.add_provider(provider("provider-1"));
        service.add_model(model("model-1", "provider-1"));
        service.add_conversation(conversation("conversation-1", "provider-1", "model-1", 0));
        let mut first = message("message-1", "conversation-1", 0, "hello");
        first.parts.push(ContentPart::File {
            attachment_id: "attachment-1".to_owned(),
            mime_type: "text/plain".to_owned(),
            name: "notes.txt".to_owned(),
        });
        service.add_message(first);
        service.add_message(message("message-2", "conversation-1", 1, "world"));
        service.add_attachment(
            attachment("attachment-1", "message-1", "notes.txt"),
            Some("aGVsbG8=".to_owned()),
        );
        service.add_secret("provider-provider-1", b"super-secret".to_vec());
        service
    }

    #[test]
    fn usage_counts_records_and_deduplicates_attachment_bytes() {
        let service = seeded();
        let usage = service.usage().expect("usage");

        assert_eq!(usage.conversations.count, 1);
        assert_eq!(usage.messages.count, 2);
        assert_eq!(usage.attachments.count, 1);
        assert_eq!(usage.attachments.bytes, 5);
        assert_eq!(usage.secrets.count, 1);
        assert_eq!(usage.providers.count, 1);
        assert_eq!(usage.models.count, 1);
        assert!(usage.total_bytes > 0);
    }

    #[test]
    fn export_all_includes_entities_and_excludes_secrets() {
        let service = seeded();
        let artifact = service
            .export_all(&ExportRequest::all())
            .expect("export all");

        let export: ChatExport = serde_json::from_str(&artifact.content).expect("parse export");
        assert_eq!(export.conversations.len(), 1);
        assert_eq!(export.messages.len(), 2);
        assert_eq!(export.attachments.len(), 1);
        assert_eq!(export.providers.len(), 1);
        assert_eq!(export.models.len(), 1);
        assert_eq!(export.settings, chatui_storage::default_settings());
        assert!(!artifact.content.contains("super-secret"));
        assert!(export.attachments[0].data_base64.is_none());
        assert_eq!(artifact.summary.conversations, 1);
        assert!(artifact.summary.includes_settings);
    }

    #[test]
    fn attachment_bytes_are_opt_in() {
        let service = seeded();

        let without = service
            .export_all(&ExportRequest::all())
            .expect("export without bytes");
        let parsed: ChatExport = serde_json::from_str(&without.content).expect("parse");
        assert!(parsed.attachments[0].data_base64.is_none());

        let with = service
            .export_all(&ExportRequest {
                include_attachment_bytes: true,
                ..ExportRequest::all()
            })
            .expect("export with bytes");
        let parsed: ChatExport = serde_json::from_str(&with.content).expect("parse");
        assert_eq!(
            parsed.attachments[0].data_base64.as_deref(),
            Some("aGVsbG8=")
        );
        assert!(with.summary.includes_attachment_bytes);
    }

    #[test]
    fn per_conversation_json_and_markdown_export_work() {
        let service = seeded();
        let json = service
            .export_conversation(
                "conversation-1",
                &ExportRequest::conversation("conversation-1", ExportFormat::Json),
            )
            .expect("json export");
        let parsed: ChatExport = serde_json::from_str(&json.content).expect("parse");
        assert_eq!(parsed.conversations.len(), 1);
        assert_eq!(parsed.messages.len(), 2);

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
    }

    #[test]
    fn import_merges_by_default_and_replace_is_explicit() {
        let source = seeded();
        let archive = source
            .export_all(&ExportRequest::all())
            .expect("source export")
            .content;

        let target = MemoryDataService::new();
        target.add_provider(provider("provider-2"));
        target.add_model(model("model-2", "provider-2"));
        target.add_conversation(conversation("conversation-2", "provider-2", "model-2", 5));

        let merged = target
            .import_text(&archive, ImportMode::Merge)
            .expect("merge import");
        assert_eq!(merged.mode, ImportMode::Merge);
        let snapshot = target.snapshot();
        assert!(
            snapshot
                .conversations
                .iter()
                .any(|conversation| conversation.id == "conversation-1")
        );
        assert!(
            snapshot
                .conversations
                .iter()
                .any(|conversation| conversation.id == "conversation-2")
        );

        let replaced = target
            .import_text(&archive, ImportMode::Replace)
            .expect("replace import");
        assert_eq!(replaced.mode, ImportMode::Replace);
        let snapshot = target.snapshot();
        assert!(
            snapshot
                .conversations
                .iter()
                .any(|conversation| conversation.id == "conversation-1")
        );
        assert!(
            !snapshot
                .conversations
                .iter()
                .any(|conversation| conversation.id == "conversation-2")
        );
    }

    #[test]
    fn invalid_import_is_rejected_without_data_loss() {
        let target = MemoryDataService::new();
        target.add_provider(provider("provider-2"));
        target.add_model(model("model-2", "provider-2"));
        target.add_conversation(conversation("conversation-2", "provider-2", "model-2", 5));

        let error = target
            .import_text("not json", ImportMode::Merge)
            .expect_err("invalid JSON");
        assert!(matches!(error, DataError::Import(_)));
        assert_eq!(target.snapshot().conversations.len(), 1);

        let error = target
            .import_text("{\"format\":\"wrong\"}", ImportMode::Merge)
            .expect_err("invalid schema");
        assert!(matches!(error, DataError::Import(_)));
        assert_eq!(target.snapshot().conversations.len(), 1);
    }

    #[test]
    fn clear_all_removes_data_and_keys_but_keeps_settings() {
        let service = seeded();
        let summary = service.clear_all().expect("clear");

        assert_eq!(summary.conversations, 1);
        assert_eq!(summary.messages, 2);
        assert_eq!(summary.attachments, 1);
        assert_eq!(summary.secrets, 1);
        assert_eq!(summary.providers, 1);
        assert_eq!(summary.models, 1);

        let snapshot = service.snapshot();
        assert!(snapshot.conversations.is_empty());
        assert!(snapshot.messages.is_empty());
        assert!(snapshot.attachments.is_empty());
        assert!(snapshot.providers.is_empty());
        assert!(snapshot.models.is_empty());
        assert!(snapshot.secrets.is_empty());
        assert_eq!(snapshot.settings, chatui_storage::default_settings());
    }

    #[test]
    fn per_key_deletion_removes_secret_and_metadata() {
        let service = MemoryDataService::new();
        let mut provider = provider("provider-1");
        provider.secret_id = Some("provider-provider-1".to_owned());
        provider.metadata = Some(serde_json::json!({
            "key": { "label": "Personal key" },
            "freeTier": true
        }));
        service.add_provider(provider);
        service.add_secret("provider-provider-1", b"secret".to_vec());

        let keys = service.list_provider_keys().expect("keys");
        assert!(keys[0].has_key);
        assert_eq!(keys[0].key_label.as_deref(), Some("Personal key"));

        service
            .remove_provider_key("provider-1")
            .expect("remove key");

        let snapshot = service.snapshot();
        assert!(snapshot.secrets.is_empty());
        assert!(snapshot.providers[0].secret_id.is_none());
        let metadata = snapshot.providers[0].metadata.as_ref().expect("metadata");
        assert!(metadata.get("key").is_none());
        assert_eq!(metadata["freeTier"], serde_json::json!(true));

        let keys = service.list_provider_keys().expect("keys");
        assert!(!keys[0].has_key);
    }

    #[test]
    fn missing_conversation_export_is_not_found() {
        let service = seeded();
        let error = service
            .export_conversation(
                "missing",
                &ExportRequest::conversation("missing", ExportFormat::Json),
            )
            .expect_err("missing conversation");
        assert!(matches!(error, DataError::NotFound(_)));
    }
}
