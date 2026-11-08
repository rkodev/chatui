//! Repository integration tests over an in-memory SQLite database.

use chatui_domain::{
    Attachment, AttachmentKind, BackupBackend, BackupConfig, BackupEncryption, BackupKdf,
    BrowserCors, ChatError, ChatErrorCode, ChatFontSize, ChatParams, ContentPart, Conversation,
    Density, FinishReason, InputModality, Message, MessageRole, MessageStatus, Model,
    ModelCapability, ModelPricing, ModelSource, OutputModality, Provider, ProviderAuthType,
    ProviderKind, SendKey, SyncMeta, SyncState, Theme, Timestamp, Usage,
};
use serde_json::json;
use sqlx::SqlitePool;

use crate::StorageError;
use crate::db::open_in_memory;
use crate::mappers::{SecretRecord, SyncQueueEntry};
use crate::repositories::{
    AttachmentRepository, BackupConfigRepository, ConversationFilter, ConversationRepository,
    MessageRepository, ModelRepository, ProviderRepository, SecretRepository, SettingsRepository,
    SqliteAttachmentRepository, SqliteBackupConfigRepository, SqliteConversationRepository,
    SqliteMessageRepository, SqliteModelRepository, SqliteProviderRepository,
    SqliteSecretRepository, SqliteSettingsRepository, SqliteSyncQueueRepository,
    SyncQueueRepository,
};
use crate::search::{SearchOptions, SearchRepository, SqliteSearchRepository};

fn timestamp(seconds: u32) -> Timestamp {
    Timestamp::parse(&format!("2026-11-04T12:00:{seconds:02}.000Z")).expect("valid timestamp")
}

fn conversation(id: &str) -> Conversation {
    Conversation {
        id: id.to_owned(),
        title: format!("Conversation {id}"),
        system_prompt: "You are concise.".to_owned(),
        provider_id: "provider-1".to_owned(),
        model_id: "model-1".to_owned(),
        model_label: "Model One".to_owned(),
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
        model_label: Some("Model One".to_owned()),
        usage: None,
        finish_reason: Some(FinishReason::Stop),
        error: None,
        latency_ms: Some(120),
        first_token_ms: Some(40),
        created_at: timestamp(0),
        updated_at: timestamp(0),
        deleted_at: None,
    }
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
        headers: [("X-Test".to_owned(), "yes".to_owned())]
            .into_iter()
            .collect(),
        enabled: true,
        browser_cors: BrowserCors::Unknown,
        metadata: Some(json!({ "freeTier": true, "unknown": [1, null] })),
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
        pricing: Some(ModelPricing {
            input_per_m_tok: Some(1.5),
            output_per_m_tok: Some(2.5),
            per_request: None,
            currency: Some("USD".to_owned()),
        }),
        canonical_slug: Some(remote_id.to_owned()),
        input_modalities: vec![InputModality::Text, InputModality::Image],
        output_modalities: vec![OutputModality::Text],
        supported_parameters: vec!["temperature".to_owned()],
        metadata: Some(json!({ "top_provider": { "is_moderated": false } })),
        hidden: false,
        pinned: false,
        source: ModelSource::Discovered,
        created_at: timestamp(0),
        updated_at: timestamp(0),
    }
}

fn attachment(id: &str, message_id: &str) -> Attachment {
    Attachment {
        id: id.to_owned(),
        message_id: Some(message_id.to_owned()),
        kind: AttachmentKind::File,
        mime_type: "text/plain".to_owned(),
        name: "notes.txt".to_owned(),
        size: 17,
        sha256: "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789".to_owned(),
        storage_key: "0123456789abcdef0123456789abcdef".to_owned(),
        width: None,
        height: None,
        created_at: timestamp(0),
    }
}

fn secret(id: &str) -> SecretRecord {
    SecretRecord {
        id: id.to_owned(),
        ciphertext: vec![1, 2, 3, 4],
        created_at: timestamp(0),
        updated_at: timestamp(0),
    }
}

async fn setup() -> (
    SqlitePool,
    SqliteConversationRepository,
    SqliteMessageRepository,
    SqliteAttachmentRepository,
    SqliteProviderRepository,
    SqliteModelRepository,
    SqliteSecretRepository,
    SqliteSettingsRepository,
    SqliteBackupConfigRepository,
    SqliteSyncQueueRepository,
    SqliteSearchRepository,
) {
    let pool = open_in_memory().await.expect("open in-memory database");
    (
        pool.clone(),
        SqliteConversationRepository::new(pool.clone()),
        SqliteMessageRepository::new(pool.clone()),
        SqliteAttachmentRepository::new(pool.clone()),
        SqliteProviderRepository::new(pool.clone()),
        SqliteModelRepository::new(pool.clone()),
        SqliteSecretRepository::new(pool.clone()),
        SqliteSettingsRepository::new(pool.clone()),
        SqliteBackupConfigRepository::new(pool.clone()),
        SqliteSyncQueueRepository::new(pool.clone()),
        SqliteSearchRepository::new(pool),
    )
}

#[tokio::test]
async fn repository_traits_are_object_safe() {
    let (
        pool,
        conversations,
        messages,
        attachments,
        providers,
        models,
        secrets,
        settings,
        backup,
        sync,
        search,
    ) = setup().await;

    let _: std::sync::Arc<dyn ConversationRepository> = std::sync::Arc::new(conversations);
    let _: std::sync::Arc<dyn MessageRepository> = std::sync::Arc::new(messages);
    let _: std::sync::Arc<dyn AttachmentRepository> = std::sync::Arc::new(attachments);
    let _: std::sync::Arc<dyn ProviderRepository> = std::sync::Arc::new(providers);
    let _: std::sync::Arc<dyn ModelRepository> = std::sync::Arc::new(models);
    let _: std::sync::Arc<dyn SecretRepository> = std::sync::Arc::new(secrets);
    let _: std::sync::Arc<dyn SettingsRepository> = std::sync::Arc::new(settings);
    let _: std::sync::Arc<dyn BackupConfigRepository> = std::sync::Arc::new(backup);
    let _: std::sync::Arc<dyn SyncQueueRepository> = std::sync::Arc::new(sync);
    let _: std::sync::Arc<dyn SearchRepository> = std::sync::Arc::new(search);

    pool.close().await;
}

#[tokio::test]
async fn conversation_crud_round_trips() {
    let (
        _pool,
        conversations,
        _messages,
        _attachments,
        _providers,
        _models,
        _secrets,
        _settings,
        _backup,
        _sync,
        _search,
    ) = setup().await;

    let mut value = conversation("conversation-1");
    value.params = ChatParams {
        temperature: Some(0.5),
        top_p: Some(0.9),
        max_output_tokens: Some(512),
        stop: vec!["END".to_owned()],
        reasoning_effort: Some(chatui_domain::ReasoningEffort::Medium),
        streaming: true,
        extra: json!({ "openrouter": { "provider": { "order": ["a"] } } })
            .as_object()
            .expect("object")
            .clone(),
    };
    value.last_error = Some(ChatError {
        code: ChatErrorCode::RateLimit,
        message: "Too many requests.".to_owned(),
        provider_id: Some("provider-1".to_owned()),
        status: Some(429),
        retryable: true,
        retry_after_seconds: Some(2.5),
        request_id: Some("request-1".to_owned()),
        details: Some(json!({ "unknown": [true, null] })),
        at: timestamp(0),
    });
    value.sync = Some(SyncMeta {
        state: SyncState::Synced,
        last_synced_at: None,
        remote_revision: None,
    });

    conversations
        .create(&value)
        .await
        .expect("create conversation");
    let loaded = conversations
        .get("conversation-1")
        .await
        .expect("get conversation")
        .expect("conversation exists");
    assert_eq!(loaded, value);

    let mut updated = loaded.clone();
    updated.title = "Renamed".to_owned();
    updated.pinned = true;
    conversations
        .update(&updated)
        .await
        .expect("update conversation");
    assert_eq!(
        conversations
            .get("conversation-1")
            .await
            .expect("get updated conversation")
            .expect("conversation exists"),
        updated
    );

    assert!(
        conversations
            .delete("conversation-1")
            .await
            .expect("delete")
    );
    assert!(
        conversations
            .get("conversation-1")
            .await
            .expect("get deleted conversation")
            .is_none()
    );
    assert!(
        !conversations
            .delete("conversation-1")
            .await
            .expect("delete again")
    );
}

#[tokio::test]
async fn conversation_list_filters_and_ordering() {
    let (
        _pool,
        conversations,
        _messages,
        _attachments,
        _providers,
        _models,
        _secrets,
        _settings,
        _backup,
        _sync,
        _search,
    ) = setup().await;

    let mut first = conversation("conversation-a");
    first.updated_at = timestamp(1);
    let mut second = conversation("conversation-b");
    second.pinned = true;
    second.updated_at = timestamp(2);
    let mut third = conversation("conversation-c");
    third.archived = true;
    third.updated_at = timestamp(3);

    conversations.create(&first).await.expect("create first");
    conversations.create(&second).await.expect("create second");
    conversations.create(&third).await.expect("create third");

    let all = conversations
        .list(&ConversationFilter::default())
        .await
        .expect("list all");
    assert_eq!(
        all.iter()
            .map(|value| value.id.as_str())
            .collect::<Vec<_>>(),
        vec!["conversation-b", "conversation-c", "conversation-a"]
    );

    let pinned = conversations
        .list(&ConversationFilter {
            pinned: Some(true),
            ..ConversationFilter::default()
        })
        .await
        .expect("list pinned");
    assert_eq!(pinned.len(), 1);
    assert_eq!(pinned[0].id, "conversation-b");

    let archived = conversations
        .list(&ConversationFilter {
            archived: Some(true),
            ..ConversationFilter::default()
        })
        .await
        .expect("list archived");
    assert_eq!(archived.len(), 1);
    assert_eq!(archived[0].id, "conversation-c");

    let limited = conversations
        .list(&ConversationFilter {
            limit: Some(1),
            ..ConversationFilter::default()
        })
        .await
        .expect("list limited");
    assert_eq!(limited.len(), 1);
    assert_eq!(limited[0].id, "conversation-b");
}

#[tokio::test]
async fn message_crud_and_mapping_round_trips() {
    let (
        _pool,
        conversations,
        messages,
        _attachments,
        _providers,
        _models,
        _secrets,
        _settings,
        _backup,
        _sync,
        _search,
    ) = setup().await;

    conversations
        .create(&conversation("conversation-1"))
        .await
        .expect("create conversation");

    let mut value = message("message-1", "conversation-1", 0, "hello world");
    value.parts = vec![
        ContentPart::Text {
            text: "hello".to_owned(),
        },
        ContentPart::Reasoning {
            text: "thinking".to_owned(),
        },
        ContentPart::Image {
            attachment_id: "attachment-1".to_owned(),
            mime_type: "image/png".to_owned(),
            alt: "pixel".to_owned(),
        },
    ];
    value.usage = Some(Usage {
        input_tokens: Some(10),
        output_tokens: Some(20),
        total_tokens: Some(30),
        cost_usd: Some(0.001),
        estimated: false,
    });
    value.error = Some(ChatError {
        code: ChatErrorCode::Timeout,
        message: "Timed out.".to_owned(),
        provider_id: None,
        status: None,
        retryable: true,
        retry_after_seconds: None,
        request_id: None,
        details: None,
        at: timestamp(0),
    });

    messages.insert(&value).await.expect("insert message");
    let loaded = messages
        .get("message-1")
        .await
        .expect("get message")
        .expect("message exists");
    assert_eq!(loaded, value);

    let mut updated = loaded.clone();
    updated.parts = vec![ContentPart::Text {
        text: "updated text".to_owned(),
    }];
    updated.status = MessageStatus::Cancelled;
    messages.update(&updated).await.expect("update message");
    assert_eq!(
        messages
            .get("message-1")
            .await
            .expect("get updated message")
            .expect("message exists"),
        updated
    );

    messages
        .soft_delete("message-1", timestamp(5))
        .await
        .expect("soft delete");
    let soft_deleted = messages
        .get("message-1")
        .await
        .expect("get soft-deleted message")
        .expect("message exists");
    assert_eq!(soft_deleted.deleted_at, Some(timestamp(5)));

    assert!(messages.delete("message-1").await.expect("hard delete"));
    assert!(
        messages
            .get("message-1")
            .await
            .expect("get deleted message")
            .is_none()
    );
}

#[tokio::test]
async fn message_pagination_is_ordered_and_bounded() {
    let (
        _pool,
        conversations,
        messages,
        _attachments,
        _providers,
        _models,
        _secrets,
        _settings,
        _backup,
        _sync,
        _search,
    ) = setup().await;

    conversations
        .create(&conversation("conversation-1"))
        .await
        .expect("create conversation");

    for sequence in 0..10 {
        messages
            .insert(&message(
                &format!("message-{sequence}"),
                "conversation-1",
                sequence,
                &format!("text {sequence}"),
            ))
            .await
            .expect("insert message");
    }

    let newest = messages
        .newest_page("conversation-1", 3)
        .await
        .expect("newest page");
    assert_eq!(
        newest
            .messages
            .iter()
            .map(|value| value.sequence)
            .collect::<Vec<_>>(),
        vec![7, 8, 9]
    );
    assert!(newest.has_more);

    let older = messages
        .older_page("conversation-1", 7, 3)
        .await
        .expect("older page");
    assert_eq!(
        older
            .messages
            .iter()
            .map(|value| value.sequence)
            .collect::<Vec<_>>(),
        vec![4, 5, 6]
    );
    assert!(older.has_more);

    let oldest = messages
        .older_page("conversation-1", 1, 3)
        .await
        .expect("oldest page");
    assert_eq!(
        oldest
            .messages
            .iter()
            .map(|value| value.sequence)
            .collect::<Vec<_>>(),
        vec![0]
    );
    assert!(!oldest.has_more);

    let newer = messages
        .newer_page("conversation-1", 6, 3)
        .await
        .expect("newer page");
    assert_eq!(
        newer
            .messages
            .iter()
            .map(|value| value.sequence)
            .collect::<Vec<_>>(),
        vec![7, 8, 9]
    );
    assert!(!newer.has_more);

    assert_eq!(
        messages
            .next_sequence("conversation-1")
            .await
            .expect("next sequence"),
        10
    );
}

#[tokio::test]
async fn large_conversation_pagination_stays_bounded() {
    let (
        _pool,
        conversations,
        messages,
        _attachments,
        _providers,
        _models,
        _secrets,
        _settings,
        _backup,
        _sync,
        search,
    ) = setup().await;

    conversations
        .create(&conversation("conversation-large"))
        .await
        .expect("create conversation");

    let batch: Vec<Message> = (0..10_000)
        .map(|sequence| {
            message(
                &format!("message-{sequence}"),
                "conversation-large",
                sequence,
                &format!("message {sequence}"),
            )
        })
        .collect();

    messages
        .insert_many(&batch)
        .await
        .expect("insert 10k messages");

    let stored = conversations
        .get("conversation-large")
        .await
        .expect("get conversation")
        .expect("conversation exists");
    assert_eq!(stored.message_count, 10_000);

    let newest = messages
        .newest_page("conversation-large", 50)
        .await
        .expect("newest page");
    assert_eq!(newest.messages.len(), 50);
    assert_eq!(newest.messages.first().expect("first").sequence, 9_950);
    assert_eq!(newest.messages.last().expect("last").sequence, 9_999);
    assert!(newest.has_more);

    let older = messages
        .older_page("conversation-large", 9_950, 50)
        .await
        .expect("older page");
    assert_eq!(older.messages.len(), 50);
    assert_eq!(older.messages.first().expect("first").sequence, 9_900);
    assert_eq!(older.messages.last().expect("last").sequence, 9_949);

    let newer = messages
        .newer_page("conversation-large", 0, 50)
        .await
        .expect("newer page");
    assert_eq!(newer.messages.len(), 50);
    assert_eq!(newer.messages.first().expect("first").sequence, 1);
    assert_eq!(newer.messages.last().expect("last").sequence, 50);

    // A caller asking for more than the cap still receives a bounded page.
    let capped = messages
        .newest_page("conversation-large", 10_000)
        .await
        .expect("capped page");
    assert_eq!(capped.messages.len(), 500);

    let hits = search
        .search(
            "9999",
            &SearchOptions {
                conversation_id: Some("conversation-large".to_owned()),
                limit: Some(10),
            },
        )
        .await
        .expect("search large conversation");
    assert_eq!(hits.len(), 1);
    assert_eq!(hits[0].message_id, "message-9999");
}

#[tokio::test]
async fn search_synchronizes_on_insert_update_delete_and_cascade() {
    let (
        _pool,
        conversations,
        messages,
        _attachments,
        _providers,
        _models,
        _secrets,
        _settings,
        _backup,
        _sync,
        search,
    ) = setup().await;

    conversations
        .create(&conversation("conversation-1"))
        .await
        .expect("create conversation");

    let mut value = message("message-1", "conversation-1", 0, "alpha beta");
    messages.insert(&value).await.expect("insert message");

    let hits = search
        .search("alpha", &SearchOptions::default())
        .await
        .expect("search alpha");
    assert_eq!(hits.len(), 1);
    assert_eq!(hits[0].conversation_id, "conversation-1");
    assert_eq!(hits[0].message_id, "message-1");
    assert!(hits[0].snippet.contains("alpha"));

    value.parts = vec![ContentPart::Text {
        text: "gamma delta".to_owned(),
    }];
    messages.update(&value).await.expect("update message");

    assert!(
        search
            .search("alpha", &SearchOptions::default())
            .await
            .expect("search old text")
            .is_empty()
    );
    assert_eq!(
        search
            .search("gamma", &SearchOptions::default())
            .await
            .expect("search new text")
            .len(),
        1
    );

    messages.delete("message-1").await.expect("delete message");
    assert!(
        search
            .search("gamma", &SearchOptions::default())
            .await
            .expect("search deleted text")
            .is_empty()
    );

    messages
        .insert(&message("message-2", "conversation-1", 1, "epsilon"))
        .await
        .expect("insert second message");
    assert_eq!(
        search
            .search("epsilon", &SearchOptions::default())
            .await
            .expect("search before cascade")
            .len(),
        1
    );

    conversations
        .delete("conversation-1")
        .await
        .expect("delete conversation");
    assert!(
        search
            .search("epsilon", &SearchOptions::default())
            .await
            .expect("search after cascade")
            .is_empty()
    );
}

#[tokio::test]
async fn search_filters_by_conversation_and_limits() {
    let (
        _pool,
        conversations,
        messages,
        _attachments,
        _providers,
        _models,
        _secrets,
        _settings,
        _backup,
        _sync,
        search,
    ) = setup().await;

    conversations
        .create(&conversation("conversation-1"))
        .await
        .expect("create first conversation");
    conversations
        .create(&conversation("conversation-2"))
        .await
        .expect("create second conversation");

    messages
        .insert(&message("message-1", "conversation-1", 0, "shared word"))
        .await
        .expect("insert first message");
    messages
        .insert(&message("message-2", "conversation-2", 0, "shared word"))
        .await
        .expect("insert second message");

    assert_eq!(
        search
            .search("shared", &SearchOptions::default())
            .await
            .expect("search all")
            .len(),
        2
    );

    let filtered = search
        .search(
            "shared",
            &SearchOptions {
                conversation_id: Some("conversation-1".to_owned()),
                limit: None,
            },
        )
        .await
        .expect("search filtered");
    assert_eq!(filtered.len(), 1);
    assert_eq!(filtered[0].conversation_id, "conversation-1");

    assert_eq!(
        search
            .search(
                "shared",
                &SearchOptions {
                    conversation_id: None,
                    limit: Some(1),
                },
            )
            .await
            .expect("search limited")
            .len(),
        1
    );

    // Punctuation and quotes must not produce an FTS syntax error.
    assert!(
        search
            .search("\"unterminated", &SearchOptions::default())
            .await
            .expect("search punctuation")
            .is_empty()
    );
}

#[tokio::test]
async fn cascade_deletion_removes_messages_attachments_and_fts() {
    let (
        _pool,
        conversations,
        messages,
        attachments,
        _providers,
        _models,
        _secrets,
        _settings,
        _backup,
        _sync,
        search,
    ) = setup().await;

    conversations
        .create(&conversation("conversation-1"))
        .await
        .expect("create conversation");
    messages
        .insert(&message("message-1", "conversation-1", 0, "cascade text"))
        .await
        .expect("insert message");
    attachments
        .insert(&attachment("attachment-1", "message-1"))
        .await
        .expect("insert attachment");

    assert_eq!(
        search
            .search("cascade", &SearchOptions::default())
            .await
            .expect("search before delete")
            .len(),
        1
    );

    conversations
        .delete("conversation-1")
        .await
        .expect("delete conversation");

    assert!(
        messages
            .get("message-1")
            .await
            .expect("get message")
            .is_none()
    );
    assert!(
        attachments
            .get("attachment-1")
            .await
            .expect("get attachment")
            .is_none()
    );
    assert!(
        search
            .search("cascade", &SearchOptions::default())
            .await
            .expect("search after delete")
            .is_empty()
    );

    // Deleting a message directly also removes its attachments and FTS row.
    conversations
        .create(&conversation("conversation-2"))
        .await
        .expect("create second conversation");
    messages
        .insert(&message("message-2", "conversation-2", 0, "direct delete"))
        .await
        .expect("insert second message");
    attachments
        .insert(&attachment("attachment-2", "message-2"))
        .await
        .expect("insert second attachment");

    messages.delete("message-2").await.expect("delete message");
    assert!(
        attachments
            .get("attachment-2")
            .await
            .expect("get second attachment")
            .is_none()
    );
    assert!(
        search
            .search("direct", &SearchOptions::default())
            .await
            .expect("search after message delete")
            .is_empty()
    );
}

#[tokio::test]
async fn provider_crud_and_delete_cascade() {
    let (
        _pool,
        conversations,
        _messages,
        _attachments,
        providers,
        models,
        secrets,
        _settings,
        _backup,
        _sync,
        _search,
    ) = setup().await;

    secrets.put(&secret("secret-1")).await.expect("put secret");

    let mut value = provider("provider-1");
    value.secret_id = Some("secret-1".to_owned());
    providers.insert(&value).await.expect("insert provider");
    assert_eq!(
        providers
            .get("provider-1")
            .await
            .expect("get provider")
            .expect("provider exists"),
        value
    );
    assert_eq!(providers.list().await.expect("list providers").len(), 1);

    models
        .insert(&model("model-1", "provider-1", "remote-1"))
        .await
        .expect("insert model");

    let mut conversation = conversation("conversation-1");
    conversation.provider_id = "provider-1".to_owned();
    conversation.model_id = "model-1".to_owned();
    conversation.model_label = "Model One".to_owned();
    conversations
        .create(&conversation)
        .await
        .expect("create conversation");

    assert!(
        providers
            .delete("provider-1")
            .await
            .expect("delete provider")
    );
    assert!(models.get("model-1").await.expect("get model").is_none());
    assert!(secrets.get("secret-1").await.expect("get secret").is_none());

    let stored = conversations
        .get("conversation-1")
        .await
        .expect("get conversation")
        .expect("conversation exists");
    assert_eq!(stored.provider_id, "");
    assert_eq!(stored.model_id, "");
    assert_eq!(stored.model_label, "Model One");

    assert!(!providers.delete("provider-1").await.expect("delete again"));
}

#[tokio::test]
async fn model_uniqueness_is_enforced() {
    let (
        _pool,
        _conversations,
        _messages,
        _attachments,
        providers,
        models,
        _secrets,
        _settings,
        _backup,
        _sync,
        _search,
    ) = setup().await;

    providers
        .insert(&provider("provider-1"))
        .await
        .expect("insert provider");

    models
        .insert(&model("model-a", "provider-1", "remote-x"))
        .await
        .expect("insert first model");

    let duplicate = models
        .insert(&model("model-b", "provider-1", "remote-x"))
        .await;
    assert!(matches!(duplicate, Err(StorageError::Conflict(_))));

    models
        .insert(&model("model-c", "provider-1", "remote-y"))
        .await
        .expect("insert third model");

    let mut conflicting = model("model-c", "provider-1", "remote-x");
    conflicting.label = "Conflicting".to_owned();
    let update = models.update(&conflicting).await;
    assert!(matches!(update, Err(StorageError::Conflict(_))));

    assert_eq!(
        models
            .get_by_remote_id("provider-1", "remote-x")
            .await
            .expect("get by remote id")
            .expect("model exists")
            .id,
        "model-a"
    );
    assert_eq!(
        models
            .get_by_remote_id("provider-1", "remote-y")
            .await
            .expect("get by remote id")
            .expect("model exists")
            .id,
        "model-c"
    );
}

#[tokio::test]
async fn attachment_crud_and_mapping() {
    let (
        _pool,
        conversations,
        messages,
        attachments,
        _providers,
        _models,
        _secrets,
        _settings,
        _backup,
        _sync,
        _search,
    ) = setup().await;

    conversations
        .create(&conversation("conversation-1"))
        .await
        .expect("create conversation");
    messages
        .insert(&message("message-1", "conversation-1", 0, "text"))
        .await
        .expect("insert message");

    let value = attachment("attachment-1", "message-1");
    attachments.insert(&value).await.expect("insert attachment");
    assert_eq!(
        attachments
            .get("attachment-1")
            .await
            .expect("get attachment")
            .expect("attachment exists"),
        value
    );
    assert_eq!(
        attachments
            .list_for_message("message-1")
            .await
            .expect("list for message")
            .len(),
        1
    );
    assert_eq!(
        attachments
            .list_by_sha256(&value.sha256)
            .await
            .expect("list by sha256")
            .len(),
        1
    );
    assert_eq!(attachments.list_all(10).await.expect("list all").len(), 1);
    assert_eq!(
        attachments
            .list_storage_keys()
            .await
            .expect("list storage keys"),
        vec![value.storage_key.clone()]
    );

    let mut updated = value.clone();
    updated.name = "renamed.txt".to_owned();
    attachments
        .update(&updated)
        .await
        .expect("update attachment");
    assert_eq!(
        attachments
            .get("attachment-1")
            .await
            .expect("get updated attachment")
            .expect("attachment exists")
            .name,
        "renamed.txt"
    );

    assert!(attachments.delete("attachment-1").await.expect("delete"));
    assert!(
        attachments
            .get("attachment-1")
            .await
            .expect("get deleted attachment")
            .is_none()
    );

    let mut unlinked = attachment("attachment-2", "message-1");
    unlinked.message_id = None;
    assert!(matches!(
        attachments.insert(&unlinked).await,
        Err(StorageError::InvalidInput(_))
    ));
}

#[tokio::test]
async fn secret_crud() {
    let (
        _pool,
        _conversations,
        _messages,
        _attachments,
        _providers,
        _models,
        secrets,
        _settings,
        _backup,
        _sync,
        _search,
    ) = setup().await;

    let value = secret("secret-1");
    secrets.put(&value).await.expect("put secret");
    assert_eq!(
        secrets
            .get("secret-1")
            .await
            .expect("get secret")
            .expect("secret exists"),
        value
    );
    assert!(secrets.exists("secret-1").await.expect("exists"));
    assert_eq!(
        secrets.list_ids().await.expect("list ids"),
        vec!["secret-1".to_owned()]
    );

    assert!(secrets.delete("secret-1").await.expect("delete"));
    assert!(
        !secrets
            .exists("secret-1")
            .await
            .expect("exists after delete")
    );
}

#[tokio::test]
async fn settings_round_trip_and_defaults() {
    let (
        _pool,
        _conversations,
        _messages,
        _attachments,
        _providers,
        _models,
        _secrets,
        settings,
        _backup,
        _sync,
        _search,
    ) = setup().await;

    let defaults = settings.load().await.expect("load defaults");
    assert_eq!(defaults.theme, Theme::System);
    assert_eq!(defaults.density, Density::Comfortable);
    assert_eq!(defaults.chat_font_size, ChatFontSize::Md);
    assert_eq!(defaults.send_key, SendKey::Enter);
    assert!(defaults.remember_keys_on_web);
    assert!(defaults.show_token_usage);
    assert!(!defaults.load_remote_images);
    assert!(!defaults.onboarding_completed);

    let mut value = defaults.clone();
    value.theme = Theme::Dark;
    value.density = Density::Compact;
    value.chat_font_size = ChatFontSize::Lg;
    value.send_key = SendKey::ModEnter;
    value.default_provider_id = Some("provider-1".to_owned());
    value.default_model_id = Some("model-1".to_owned());
    value.default_system_prompt = "Be brief.".to_owned();
    value.default_params.temperature = Some(0.2);
    value.remember_keys_on_web = false;
    value.show_token_usage = false;
    value.load_remote_images = true;
    value.last_active_conversation_id = Some("conversation-1".to_owned());
    value.backup = Some(BackupConfig {
        enabled: true,
        backend: BackupBackend::S3,
        endpoint: None,
        region: Some("us-east-1".to_owned()),
        bucket: "chatui".to_owned(),
        prefix: "backups/".to_owned(),
        credential_secret_id: None,
        encryption: BackupEncryption::Aes256Gcm,
        kdf: BackupKdf::Argon2id,
        kdf_salt: None,
        last_sync_at: Some(timestamp(0)),
        last_manifest_etag: None,
        device_id: "device-1".to_owned(),
    });
    value.onboarding_completed = true;

    settings.save(&value).await.expect("save settings");
    assert_eq!(settings.load().await.expect("load saved settings"), value);

    assert_eq!(
        settings.get("theme").await.expect("get theme"),
        Some(json!("dark"))
    );
    settings
        .set("theme", &json!("light"))
        .await
        .expect("set theme");
    assert_eq!(
        settings.get("theme").await.expect("get theme"),
        Some(json!("light"))
    );
    assert!(settings.delete("theme").await.expect("delete theme"));
    assert_eq!(settings.get("theme").await.expect("get theme"), None);
    assert!(
        settings
            .list()
            .await
            .expect("list settings")
            .iter()
            .any(|record| record.key == "density")
    );
}

#[tokio::test]
async fn backup_config_round_trip() {
    let (
        _pool,
        _conversations,
        _messages,
        _attachments,
        _providers,
        _models,
        _secrets,
        _settings,
        backup,
        _sync,
        _search,
    ) = setup().await;

    assert!(backup.load().await.expect("load empty backup").is_none());

    let value = BackupConfig {
        enabled: true,
        backend: BackupBackend::Webdav,
        endpoint: Some("https://dav.example.com".to_owned()),
        region: None,
        bucket: "chatui".to_owned(),
        prefix: "backups/".to_owned(),
        credential_secret_id: Some("secret-1".to_owned()),
        encryption: BackupEncryption::Aes256Gcm,
        kdf: BackupKdf::Pbkdf2Sha256,
        kdf_salt: Some("salt".to_owned()),
        last_sync_at: Some(timestamp(0)),
        last_manifest_etag: Some("etag-1".to_owned()),
        device_id: "device-1".to_owned(),
    };

    backup.save(&value).await.expect("save backup");
    assert_eq!(
        backup
            .load()
            .await
            .expect("load backup")
            .expect("backup exists"),
        value
    );
    assert!(backup.delete().await.expect("delete backup"));
    assert!(backup.load().await.expect("load deleted backup").is_none());
}

#[tokio::test]
async fn sync_queue_round_trip() {
    let (
        _pool,
        _conversations,
        _messages,
        _attachments,
        _providers,
        _models,
        _secrets,
        _settings,
        _backup,
        sync,
        _search,
    ) = setup().await;

    let entry = SyncQueueEntry {
        id: "entry-1".to_owned(),
        entity_type: "conversation".to_owned(),
        entity_id: "conversation-1".to_owned(),
        operation: "upsert".to_owned(),
        payload: json!({ "title": "Hello" }),
        attempts: 0,
        created_at: timestamp(0),
        updated_at: timestamp(0),
    };

    sync.enqueue(&entry).await.expect("enqueue");
    assert_eq!(sync.list(10).await.expect("list"), vec![entry.clone()]);

    sync.increment_attempts("entry-1")
        .await
        .expect("increment attempts");
    assert_eq!(sync.list(10).await.expect("list")[0].attempts, 1);

    assert!(sync.delete("entry-1").await.expect("delete"));
    assert!(sync.list(10).await.expect("list").is_empty());

    sync.enqueue(&entry).await.expect("enqueue first");
    sync.enqueue(&SyncQueueEntry {
        id: "entry-2".to_owned(),
        ..entry
    })
    .await
    .expect("enqueue second");
    assert_eq!(sync.clear().await.expect("clear"), 2);
    assert!(sync.list(10).await.expect("list").is_empty());
}

#[tokio::test]
async fn message_insert_updates_conversation_stats() {
    let (
        _pool,
        conversations,
        messages,
        _attachments,
        _providers,
        _models,
        _secrets,
        _settings,
        _backup,
        _sync,
        _search,
    ) = setup().await;

    conversations
        .create(&conversation("conversation-1"))
        .await
        .expect("create conversation");

    let mut first = message("message-1", "conversation-1", 0, "first");
    first.created_at = timestamp(1);
    first.updated_at = timestamp(1);
    let mut second = message("message-2", "conversation-1", 1, "second");
    second.created_at = timestamp(2);
    second.updated_at = timestamp(2);

    messages.insert(&first).await.expect("insert first");
    messages.insert(&second).await.expect("insert second");

    let stored = conversations
        .get("conversation-1")
        .await
        .expect("get conversation")
        .expect("conversation exists");
    assert_eq!(stored.message_count, 2);
    assert_eq!(stored.last_message_at, timestamp(2));

    messages.delete("message-2").await.expect("delete second");
    let stored = conversations
        .get("conversation-1")
        .await
        .expect("get conversation")
        .expect("conversation exists");
    assert_eq!(stored.message_count, 1);
    assert_eq!(stored.last_message_at, timestamp(1));

    messages
        .soft_delete("message-1", timestamp(3))
        .await
        .expect("soft delete first");
    let stored = conversations
        .get("conversation-1")
        .await
        .expect("get conversation")
        .expect("conversation exists");
    assert_eq!(stored.message_count, 0);
}

#[tokio::test]
async fn message_sequence_uniqueness_is_enforced() {
    let (
        _pool,
        conversations,
        messages,
        _attachments,
        _providers,
        _models,
        _secrets,
        _settings,
        _backup,
        _sync,
        _search,
    ) = setup().await;

    conversations
        .create(&conversation("conversation-1"))
        .await
        .expect("create conversation");

    messages
        .insert(&message("message-1", "conversation-1", 0, "first"))
        .await
        .expect("insert first");

    let duplicate = messages
        .insert(&message("message-2", "conversation-1", 0, "second"))
        .await;
    assert!(matches!(duplicate, Err(StorageError::Conflict(_))));
}
