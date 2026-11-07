//! `chatui.export` document construction.
//!
//! The export service reads through repository traits and never opens the
//! secret store. Provider records are sanitized so secret headers and
//! secret-bearing metadata keys cannot enter an archive. Attachment bytes are
//! opt-in and base64-encoded only when the caller asks for them.
//!
//! Ordering is deterministic and matches the web and Android readers:
//! conversations by creation time then ID, messages by conversation order then
//! sequence then ID, attachments and providers by creation time then ID, and
//! models by provider, remote ID, then ID.

use std::cmp::Ordering;
use std::collections::{BTreeSet, HashMap};
use std::sync::Arc;

use base64::Engine;
use base64::engine::general_purpose::STANDARD as BASE64;
use chatui_contracts::{ChatExport, ExportAttachment};
use chatui_domain::{
    Attachment, ContentPart, Conversation, Message, Model, Provider, Settings, Timestamp,
};
use serde_json::Value;

use crate::StorageError;
use crate::attachments::AttachmentStore;
use crate::repositories::{
    AttachmentRepository, ConversationRepository, MessageRepository, ModelRepository,
    ProviderRepository, SettingsRepository,
};

/// Plaintext warning for the export/import UI to display before writing a file.
pub const EXPORT_PLAINTEXT_WARNING: &str = "Exports contain plaintext conversations, prompts, and attachment metadata. Anyone with the file can read them. Store exports somewhere you trust.";

/// Header names that must never be exported.
const SECRET_HEADER_NAMES: &[&str] = &[
    "authorization",
    "proxy-authorization",
    "x-api-key",
    "x-goog-api-key",
    "api-key",
    "apikey",
    "api_key",
];

/// Metadata keys that must never be exported.
///
/// The bare key `key` is deliberately not listed because OpenRouter metadata
/// uses it for a non-secret `/key` label.
const SECRET_METADATA_KEYS: &[&str] = &[
    "authorization",
    "proxy-authorization",
    "x-api-key",
    "x-goog-api-key",
    "api-key",
    "apikey",
    "api_key",
    "secret",
    "password",
    "access_token",
    "refresh_token",
    "credential",
    "credentials",
];

/// Options for building an export.
#[derive(Debug, Clone, Default)]
pub struct ExportOptions {
    /// Include attachment bytes as base64. Defaults to `false`.
    pub include_attachment_bytes: bool,
    /// Deterministic export timestamp. Defaults to the current time.
    pub exported_at: Option<Timestamp>,
    /// Application version written to the archive. Defaults to the crate
    /// version.
    pub app_version: Option<String>,
}

/// Builds `chatui.export` documents from repository data.
pub struct ExportService {
    conversations: Arc<dyn ConversationRepository>,
    messages: Arc<dyn MessageRepository>,
    attachments: Arc<dyn AttachmentRepository>,
    providers: Arc<dyn ProviderRepository>,
    models: Arc<dyn ModelRepository>,
    settings: Arc<dyn SettingsRepository>,
    attachment_store: Option<AttachmentStore>,
    app_version: String,
}

impl ExportService {
    /// Create an export service over repository traits.
    pub fn new(
        conversations: Arc<dyn ConversationRepository>,
        messages: Arc<dyn MessageRepository>,
        attachments: Arc<dyn AttachmentRepository>,
        providers: Arc<dyn ProviderRepository>,
        models: Arc<dyn ModelRepository>,
        settings: Arc<dyn SettingsRepository>,
    ) -> Self {
        Self {
            conversations,
            messages,
            attachments,
            providers,
            models,
            settings,
            attachment_store: None,
            app_version: env!("CARGO_PKG_VERSION").to_owned(),
        }
    }

    /// Attach the content-addressed byte store used for opt-in attachment data.
    pub fn with_attachment_store(mut self, store: AttachmentStore) -> Self {
        self.attachment_store = Some(store);
        self
    }

    /// Override the application version written to archives.
    pub fn with_app_version(mut self, version: impl Into<String>) -> Self {
        self.app_version = version.into();
        self
    }

    /// List conversations newest-first for the export entry points.
    pub async fn list_conversations(&self) -> Result<Vec<Conversation>, StorageError> {
        let mut conversations = self.conversations.list_all().await?;
        conversations.sort_by(compare_conversations_newest_first);
        Ok(conversations)
    }

    /// Build a full export containing every conversation and attachment.
    pub async fn export_all(&self, options: &ExportOptions) -> Result<ChatExport, StorageError> {
        let conversations = self.conversations.list_all().await?;
        let mut messages = Vec::new();

        for conversation in &conversations {
            messages.extend(
                self.messages
                    .list_all_including_deleted(&conversation.id)
                    .await?,
            );
        }

        let attachments = self.attachments.list_all_unbounded().await?;
        let providers = self.providers.list().await?;
        let models = self.models.list_all().await?;
        let settings = self.settings.load().await?;

        self.build_export(
            conversations,
            messages,
            attachments,
            providers,
            models,
            settings,
            options,
        )
        .await
    }

    /// Build an export for one conversation.
    ///
    /// Returns `None` when the conversation does not exist. The archive keeps
    /// every provider, model, and the current settings so references resolve.
    pub async fn export_conversation(
        &self,
        conversation_id: &str,
        options: &ExportOptions,
    ) -> Result<Option<ChatExport>, StorageError> {
        let Some(conversation) = self.conversations.get(conversation_id).await? else {
            return Ok(None);
        };

        let messages = self
            .messages
            .list_all_including_deleted(conversation_id)
            .await?;
        let referenced = referenced_attachment_ids(&messages);
        let attachments = self
            .attachments
            .list_all_unbounded()
            .await?
            .into_iter()
            .filter(|attachment| referenced.contains(&attachment.id))
            .collect();
        let providers = self.providers.list().await?;
        let models = self.models.list_all().await?;
        let settings = self.settings.load().await?;

        self.build_export(
            vec![conversation],
            messages,
            attachments,
            providers,
            models,
            settings,
            options,
        )
        .await
        .map(Some)
    }

    /// Build and serialize a full export as pretty JSON with a trailing newline.
    pub async fn export_all_json(&self, options: &ExportOptions) -> Result<String, StorageError> {
        let export = self.export_all(options).await?;
        encode_export(&export)
    }

    /// Build and serialize a per-conversation export.
    pub async fn export_conversation_json(
        &self,
        conversation_id: &str,
        options: &ExportOptions,
    ) -> Result<Option<String>, StorageError> {
        match self.export_conversation(conversation_id, options).await? {
            Some(export) => Ok(Some(encode_export(&export)?)),
            None => Ok(None),
        }
    }

    #[allow(clippy::too_many_arguments)]
    async fn build_export(
        &self,
        mut conversations: Vec<Conversation>,
        mut messages: Vec<Message>,
        mut attachments: Vec<Attachment>,
        mut providers: Vec<Provider>,
        mut models: Vec<Model>,
        settings: Settings,
        options: &ExportOptions,
    ) -> Result<ChatExport, StorageError> {
        conversations.sort_by(compare_conversations);

        let conversation_order: HashMap<&str, usize> = conversations
            .iter()
            .enumerate()
            .map(|(index, conversation)| (conversation.id.as_str(), index))
            .collect();

        messages.sort_by(|left, right| {
            conversation_order
                .get(left.conversation_id.as_str())
                .copied()
                .unwrap_or(usize::MAX)
                .cmp(
                    &conversation_order
                        .get(right.conversation_id.as_str())
                        .copied()
                        .unwrap_or(usize::MAX),
                )
                .then_with(|| left.sequence.cmp(&right.sequence))
                .then_with(|| left.id.cmp(&right.id))
        });

        attachments.sort_by(compare_attachments);
        providers.sort_by(compare_providers);
        models.sort_by(compare_models);

        let exported_at = options.exported_at.unwrap_or_else(Timestamp::now);
        let app_version = options
            .app_version
            .clone()
            .unwrap_or_else(|| self.app_version.clone());

        let mut export_attachments = Vec::with_capacity(attachments.len());
        for attachment in attachments {
            let mut exported = ExportAttachment {
                id: attachment.id.clone(),
                name: attachment.name.clone(),
                mime_type: attachment.mime_type.clone(),
                sha256: attachment.sha256.clone(),
                size: attachment.size,
                data_base64: None,
            };

            if options.include_attachment_bytes {
                let store = self.attachment_store.as_ref().ok_or_else(|| {
                    StorageError::InvalidInput(
                        "attachment bytes were requested but no attachment store is configured"
                            .to_owned(),
                    )
                })?;
                let bytes = store.get(&attachment.storage_key)?;
                exported.data_base64 = Some(BASE64.encode(bytes));
            }

            export_attachments.push(exported);
        }

        let providers = providers.into_iter().map(sanitize_provider).collect();

        let export = ChatExport::new(
            exported_at,
            app_version,
            conversations,
            messages,
            export_attachments,
            providers,
            models,
            settings,
        );

        export.validate().map_err(|error| {
            StorageError::InvalidInput(format!(
                "export does not satisfy the chatui.export schema: {error}"
            ))
        })?;

        Ok(export)
    }
}

/// Serialize an export as pretty JSON with a trailing newline.
pub fn encode_export(export: &ChatExport) -> Result<String, StorageError> {
    let mut json = serde_json::to_string_pretty(export)?;
    json.push('\n');
    Ok(json)
}

/// Remove secret headers and secret-bearing metadata keys from a provider.
///
/// Provider `secretId` values are opaque references, not secret material, and
/// are preserved so the archive can round-trip the reference.
pub(crate) fn sanitize_provider(mut provider: Provider) -> Provider {
    provider.headers.retain(|name, _| !is_secret_header(name));
    provider.metadata = provider.metadata.map(sanitize_metadata);
    provider
}

fn is_secret_header(name: &str) -> bool {
    let lower = name.to_ascii_lowercase();
    SECRET_HEADER_NAMES.contains(&lower.as_str())
}

fn is_secret_metadata_key(key: &str) -> bool {
    let lower = key.to_ascii_lowercase();
    SECRET_METADATA_KEYS.contains(&lower.as_str())
}

fn sanitize_metadata(value: Value) -> Value {
    match value {
        Value::Object(map) => Value::Object(
            map.into_iter()
                .filter_map(|(key, value)| {
                    if is_secret_metadata_key(&key) {
                        None
                    } else {
                        Some((key, sanitize_metadata(value)))
                    }
                })
                .collect(),
        ),
        Value::Array(values) => Value::Array(values.into_iter().map(sanitize_metadata).collect()),
        other => other,
    }
}

fn referenced_attachment_ids(messages: &[Message]) -> BTreeSet<String> {
    let mut ids = BTreeSet::new();

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

fn compare_conversations(left: &Conversation, right: &Conversation) -> Ordering {
    left.created_at
        .cmp(&right.created_at)
        .then_with(|| left.id.cmp(&right.id))
}

fn compare_conversations_newest_first(left: &Conversation, right: &Conversation) -> Ordering {
    right
        .updated_at
        .cmp(&left.updated_at)
        .then_with(|| right.id.cmp(&left.id))
        .then_with(|| left.created_at.cmp(&right.created_at))
}

fn compare_attachments(left: &Attachment, right: &Attachment) -> Ordering {
    left.created_at
        .cmp(&right.created_at)
        .then_with(|| left.id.cmp(&right.id))
}

fn compare_providers(left: &Provider, right: &Provider) -> Ordering {
    left.created_at
        .cmp(&right.created_at)
        .then_with(|| left.id.cmp(&right.id))
}

fn compare_models(left: &Model, right: &Model) -> Ordering {
    left.provider_id
        .cmp(&right.provider_id)
        .then_with(|| left.remote_id.cmp(&right.remote_id))
        .then_with(|| left.id.cmp(&right.id))
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::BTreeMap;

    use chatui_domain::{
        AttachmentKind, BrowserCors, ChatParams, FinishReason, MessageRole, MessageStatus,
        ModelCapability, ModelSource, ProviderAuthType, ProviderKind,
    };
    use sqlx::SqlitePool;

    use crate::attachments::AttachmentService;
    use crate::db::open_in_memory;
    use crate::repositories::{
        SqliteAttachmentRepository, SqliteConversationRepository, SqliteMessageRepository,
        SqliteModelRepository, SqliteProviderRepository, SqliteSettingsRepository,
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
            source: ModelSource::Discovered,
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

    fn message(
        id: &str,
        conversation_id: &str,
        sequence: u64,
        text: &str,
        created: u32,
    ) -> Message {
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
            created_at: timestamp(created),
            updated_at: timestamp(created),
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
            size: 0,
            sha256: String::new(),
            storage_key: String::new(),
            width: None,
            height: None,
            created_at: timestamp(0),
        }
    }

    struct Fixture {
        _directory: tempfile::TempDir,
        pool: SqlitePool,
        store: AttachmentStore,
        service: ExportService,
    }

    async fn fixture() -> Fixture {
        let directory = tempfile::tempdir().expect("create temporary directory");
        let store = AttachmentStore::new(directory.path());
        let pool = open_in_memory().await.expect("open in-memory database");

        let conversations: Arc<dyn ConversationRepository> =
            Arc::new(SqliteConversationRepository::new(pool.clone()));
        let messages: Arc<dyn MessageRepository> =
            Arc::new(SqliteMessageRepository::new(pool.clone()));
        let attachments: Arc<dyn AttachmentRepository> =
            Arc::new(SqliteAttachmentRepository::new(pool.clone()));
        let providers: Arc<dyn ProviderRepository> =
            Arc::new(SqliteProviderRepository::new(pool.clone()));
        let models: Arc<dyn ModelRepository> = Arc::new(SqliteModelRepository::new(pool.clone()));
        let settings: Arc<dyn SettingsRepository> =
            Arc::new(SqliteSettingsRepository::new(pool.clone()));

        let service = ExportService::new(
            conversations,
            messages,
            attachments,
            providers,
            models,
            settings,
        )
        .with_attachment_store(store.clone());

        Fixture {
            _directory: directory,
            pool,
            store,
            service,
        }
    }

    async fn seed(fixture: &Fixture) {
        let providers = SqliteProviderRepository::new(fixture.pool.clone());
        let models = SqliteModelRepository::new(fixture.pool.clone());
        let conversations = SqliteConversationRepository::new(fixture.pool.clone());
        let messages = SqliteMessageRepository::new(fixture.pool.clone());
        let attachments = Arc::new(SqliteAttachmentRepository::new(fixture.pool.clone()));
        let settings = SqliteSettingsRepository::new(fixture.pool.clone());

        providers
            .insert(&provider("provider-1"))
            .await
            .expect("insert provider");
        models
            .insert(&model("model-1", "provider-1", "remote-1"))
            .await
            .expect("insert model");
        conversations
            .create(&conversation("conversation-1", "provider-1", "model-1", 0))
            .await
            .expect("insert conversation");
        let mut first = message("message-1", "conversation-1", 0, "hello", 0);
        first.parts.push(ContentPart::File {
            attachment_id: "attachment-1".to_owned(),
            mime_type: "text/plain".to_owned(),
            name: "notes.txt".to_owned(),
        });
        messages.insert(&first).await.expect("insert message");
        messages
            .insert(&message("message-2", "conversation-1", 1, "world", 1))
            .await
            .expect("insert message");

        let service = AttachmentService::new(fixture.store.clone(), attachments);
        service
            .add(
                attachment("attachment-1", "message-1", "notes.txt"),
                b"hello",
            )
            .await
            .expect("insert attachment");

        settings
            .save(&crate::repositories::default_settings())
            .await
            .expect("save settings");
    }

    #[tokio::test]
    async fn export_round_trips_ids_ordering_and_snapshots() {
        let fixture = fixture().await;
        seed(&fixture).await;

        let export = fixture
            .service
            .export_all(&ExportOptions {
                exported_at: Some(timestamp(30)),
                ..ExportOptions::default()
            })
            .await
            .expect("export");

        assert_eq!(export.format, "chatui.export");
        assert_eq!(export.format_version, 1);
        assert_eq!(export.app_version, env!("CARGO_PKG_VERSION"));
        assert_eq!(export.exported_at, timestamp(30));
        assert_eq!(export.conversations.len(), 1);
        assert_eq!(export.messages.len(), 2);
        assert_eq!(export.attachments.len(), 1);
        assert_eq!(export.providers.len(), 1);
        assert_eq!(export.models.len(), 1);
        assert_eq!(export.messages[0].id, "message-1");
        assert_eq!(export.messages[1].id, "message-2");
        assert_eq!(export.conversations[0].model_label, "Model model-1");
        assert_eq!(export.attachments[0].id, "attachment-1");
        assert!(export.attachments[0].data_base64.is_none());
        export.validate().expect("export satisfies the schema");

        let json = encode_export(&export).expect("encode export");
        assert!(json.ends_with('\n'));
        let parsed: ChatExport = serde_json::from_str(&json).expect("decode export");
        assert_eq!(parsed, export);
    }

    #[tokio::test]
    async fn export_attachment_bytes_are_opt_in() {
        let fixture = fixture().await;
        seed(&fixture).await;

        let without = fixture
            .service
            .export_all(&ExportOptions::default())
            .await
            .expect("export without bytes");
        assert!(without.attachments[0].data_base64.is_none());

        let with = fixture
            .service
            .export_all(&ExportOptions {
                include_attachment_bytes: true,
                ..ExportOptions::default()
            })
            .await
            .expect("export with bytes");
        let data = with.attachments[0]
            .data_base64
            .as_deref()
            .expect("attachment bytes are present");
        assert_eq!(BASE64.decode(data).expect("decode base64"), b"hello");
    }

    #[tokio::test]
    async fn export_without_a_store_rejects_attachment_bytes() {
        let fixture = fixture().await;
        seed(&fixture).await;

        let service = ExportService::new(
            Arc::new(SqliteConversationRepository::new(fixture.pool.clone())),
            Arc::new(SqliteMessageRepository::new(fixture.pool.clone())),
            Arc::new(SqliteAttachmentRepository::new(fixture.pool.clone())),
            Arc::new(SqliteProviderRepository::new(fixture.pool.clone())),
            Arc::new(SqliteModelRepository::new(fixture.pool.clone())),
            Arc::new(SqliteSettingsRepository::new(fixture.pool.clone())),
        );

        let error = service
            .export_all(&ExportOptions {
                include_attachment_bytes: true,
                ..ExportOptions::default()
            })
            .await
            .expect_err("missing store is rejected");
        assert!(matches!(error, StorageError::InvalidInput(_)));
    }

    #[tokio::test]
    async fn export_excludes_secret_headers_and_metadata() {
        let fixture = fixture().await;
        let providers = SqliteProviderRepository::new(fixture.pool.clone());
        let mut provider = provider("provider-1");
        provider
            .headers
            .insert("Authorization".to_owned(), "Bearer super-secret".to_owned());
        provider
            .headers
            .insert("X-Test".to_owned(), "yes".to_owned());
        provider.metadata = Some(serde_json::json!({
            "apiKey": "super-secret",
            "freeTier": true,
            "nested": { "access_token": "secret", "label": "kept" }
        }));
        providers.insert(&provider).await.expect("insert provider");

        let export = fixture
            .service
            .export_all(&ExportOptions::default())
            .await
            .expect("export");

        let exported = &export.providers[0];
        assert!(!exported.headers.contains_key("Authorization"));
        assert_eq!(
            exported.headers.get("X-Test").map(String::as_str),
            Some("yes")
        );
        let metadata = exported.metadata.as_ref().expect("metadata");
        assert!(metadata.get("apiKey").is_none());
        assert_eq!(metadata["freeTier"], serde_json::json!(true));
        assert!(metadata["nested"].get("access_token").is_none());
        assert_eq!(metadata["nested"]["label"], serde_json::json!("kept"));
    }

    #[tokio::test]
    async fn export_conversation_filters_messages_and_attachments() {
        let fixture = fixture().await;
        seed(&fixture).await;

        let conversations = SqliteConversationRepository::new(fixture.pool.clone());
        let messages = SqliteMessageRepository::new(fixture.pool.clone());
        conversations
            .create(&conversation("conversation-2", "provider-1", "model-1", 2))
            .await
            .expect("insert second conversation");
        messages
            .insert(&message("message-3", "conversation-2", 0, "other", 2))
            .await
            .expect("insert second message");

        let export = fixture
            .service
            .export_conversation("conversation-1", &ExportOptions::default())
            .await
            .expect("export conversation")
            .expect("conversation exists");

        assert_eq!(export.conversations.len(), 1);
        assert_eq!(export.conversations[0].id, "conversation-1");
        assert_eq!(export.messages.len(), 2);
        assert!(
            export
                .messages
                .iter()
                .all(|m| m.conversation_id == "conversation-1")
        );
        assert_eq!(export.attachments.len(), 1);
        assert_eq!(export.providers.len(), 1);
        assert_eq!(export.models.len(), 1);

        assert!(
            fixture
                .service
                .export_conversation("missing", &ExportOptions::default())
                .await
                .expect("export missing")
                .is_none()
        );
    }

    #[tokio::test]
    async fn export_orders_deterministically() {
        let fixture = fixture().await;
        seed(&fixture).await;

        let options = ExportOptions {
            exported_at: Some(timestamp(30)),
            ..ExportOptions::default()
        };
        let first = fixture
            .service
            .export_all_json(&options)
            .await
            .expect("first export");
        let second = fixture
            .service
            .export_all_json(&options)
            .await
            .expect("second export");

        assert_eq!(first, second);
    }

    #[tokio::test]
    async fn list_conversations_is_newest_first() {
        let fixture = fixture().await;
        seed(&fixture).await;

        let conversations = SqliteConversationRepository::new(fixture.pool.clone());
        conversations
            .create(&conversation("conversation-2", "provider-1", "model-1", 2))
            .await
            .expect("insert second conversation");

        let listed = fixture
            .service
            .list_conversations()
            .await
            .expect("list conversations");
        assert_eq!(listed[0].id, "conversation-2");
        assert_eq!(listed[1].id, "conversation-1");
    }
}
