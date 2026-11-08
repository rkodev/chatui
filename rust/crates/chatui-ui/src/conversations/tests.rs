//! Conversation history, grouping, search, undo, drafts, and route tests.

use std::sync::Arc;
use std::time::{Duration, Instant};

use async_trait::async_trait;
use chatui_domain::{
    Attachment, AttachmentKind, BrowserCors, ChatParams, Conversation, InputModality, Message,
    MessageRole, MessageStatus, Model, ModelCapability, ModelSource, OutputModality, Provider,
    ProviderAuthType, ProviderKind, Timestamp,
};
use chatui_storage::{
    AttachmentRepository, ConversationRepository, MessageRepository, ModelRepository,
    ProviderRepository, SearchHit, SearchOptions, SearchRepository, SettingsRepository,
    SqliteAttachmentRepository, SqliteConversationRepository, SqliteMessageRepository,
    SqliteModelRepository, SqliteProviderRepository, SqliteSearchRepository,
    SqliteSettingsRepository, StorageError, open_in_memory,
};

use crate::chat::drafts::{DraftStore, MemoryDraftStore};
use crate::shell::Destination;

use super::factory::{ConversationFactory, DefaultConversationFactory};
use super::grouping::{
    HistoryGroupKey, format_conversation_timestamp, group_conversations, history_group_for_date,
};
use super::history::{
    ConversationHistory, DEFAULT_CONVERSATION_TITLE, DEFAULT_UNDO_WINDOW, HistoryConfig,
    HistoryError, HistoryStatus,
};
use super::route::ConversationNavigation;
use super::search::{
    ConversationSearchError, ConversationSearchResult, ConversationSearchService,
    ConversationSearchState, MAX_SNIPPET_CHARS,
};

fn ts(value: &str) -> Timestamp {
    Timestamp::parse(value).expect("valid timestamp")
}

fn conversation(
    id: &str,
    title: &str,
    pinned: bool,
    archived: bool,
    last_message_at: Timestamp,
) -> Conversation {
    Conversation {
        id: id.to_owned(),
        title: title.to_owned(),
        system_prompt: String::new(),
        provider_id: "provider-1".to_owned(),
        model_id: "model-1".to_owned(),
        model_label: "Model One".to_owned(),
        params: ChatParams::default(),
        pinned,
        archived,
        temporary: false,
        created_at: last_message_at,
        updated_at: last_message_at,
        last_message_at,
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
        parts: vec![chatui_domain::ContentPart::Text {
            text: text.to_owned(),
        }],
        status: MessageStatus::Complete,
        provider_id: Some("provider-1".to_owned()),
        model_id: Some("model-1".to_owned()),
        model_label: Some("Model One".to_owned()),
        usage: None,
        finish_reason: None,
        error: None,
        latency_ms: None,
        first_token_ms: None,
        created_at: ts("2026-11-07T09:00:00.000Z"),
        updated_at: ts("2026-11-07T09:00:00.000Z"),
        deleted_at: None,
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
        created_at: ts("2026-11-07T09:00:00.000Z"),
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
        headers: std::collections::BTreeMap::new(),
        enabled: true,
        browser_cors: BrowserCors::Unknown,
        metadata: None,
        created_at: ts("2026-11-07T09:00:00.000Z"),
        updated_at: ts("2026-11-07T09:00:00.000Z"),
    }
}

fn model(id: &str, provider_id: &str) -> Model {
    Model {
        id: id.to_owned(),
        provider_id: provider_id.to_owned(),
        remote_id: "remote-model".to_owned(),
        label: "Model One".to_owned(),
        context_window: Some(128_000),
        max_output_tokens: Some(4_096),
        capabilities: vec![ModelCapability::Text],
        pricing: None,
        canonical_slug: None,
        input_modalities: vec![InputModality::Text],
        output_modalities: vec![OutputModality::Text],
        supported_parameters: Vec::new(),
        metadata: None,
        hidden: false,
        pinned: false,
        source: ModelSource::Manual,
        created_at: ts("2026-11-07T09:00:00.000Z"),
        updated_at: ts("2026-11-07T09:00:00.000Z"),
    }
}

struct Harness {
    conversations: Arc<dyn ConversationRepository>,
    messages: Arc<dyn MessageRepository>,
    attachments: Arc<dyn AttachmentRepository>,
    settings: Arc<dyn SettingsRepository>,
    providers: Arc<dyn ProviderRepository>,
    models: Arc<dyn ModelRepository>,
    drafts: Arc<MemoryDraftStore>,
    factory: Arc<dyn ConversationFactory>,
    history: ConversationHistory,
}

impl Harness {
    fn history_with_config(&self, config: HistoryConfig) -> ConversationHistory {
        ConversationHistory::new(
            Arc::clone(&self.conversations),
            Arc::clone(&self.settings),
            self.drafts.clone(),
            Arc::clone(&self.factory),
            config,
        )
    }
}

async fn harness() -> Harness {
    let pool = open_in_memory().await.expect("open in-memory database");
    let conversations: Arc<dyn ConversationRepository> =
        Arc::new(SqliteConversationRepository::new(pool.clone()));
    let messages: Arc<dyn MessageRepository> = Arc::new(SqliteMessageRepository::new(pool.clone()));
    let attachments: Arc<dyn AttachmentRepository> =
        Arc::new(SqliteAttachmentRepository::new(pool.clone()));
    let settings: Arc<dyn SettingsRepository> =
        Arc::new(SqliteSettingsRepository::new(pool.clone()));
    let providers: Arc<dyn ProviderRepository> =
        Arc::new(SqliteProviderRepository::new(pool.clone()));
    let models: Arc<dyn ModelRepository> = Arc::new(SqliteModelRepository::new(pool.clone()));
    let drafts = Arc::new(MemoryDraftStore::new());
    let factory: Arc<dyn ConversationFactory> = Arc::new(DefaultConversationFactory::new(
        Arc::clone(&conversations),
        Arc::clone(&providers),
        Arc::clone(&models),
        Arc::clone(&settings),
    ));
    let history = ConversationHistory::new(
        Arc::clone(&conversations),
        Arc::clone(&settings),
        drafts.clone(),
        Arc::clone(&factory),
        HistoryConfig::default(),
    );

    Harness {
        conversations,
        messages,
        attachments,
        settings,
        providers,
        models,
        drafts,
        factory,
        history,
    }
}

#[test]
fn groups_pinned_then_dates_in_order() {
    let now = ts("2026-11-07T12:00:00.000Z");
    let conversations = vec![
        conversation(
            "pinned",
            "Pinned",
            true,
            false,
            ts("2026-10-01T12:00:00.000Z"),
        ),
        conversation(
            "today",
            "Today",
            false,
            false,
            ts("2026-11-07T09:00:00.000Z"),
        ),
        conversation(
            "yesterday",
            "Yesterday",
            false,
            false,
            ts("2026-11-06T09:00:00.000Z"),
        ),
        conversation("week", "Week", false, false, ts("2026-11-03T09:00:00.000Z")),
        conversation(
            "month",
            "Month",
            false,
            false,
            ts("2026-10-20T09:00:00.000Z"),
        ),
        conversation(
            "older",
            "Older",
            false,
            false,
            ts("2026-09-01T09:00:00.000Z"),
        ),
    ];

    let groups = group_conversations(&conversations, now);
    let keys: Vec<HistoryGroupKey> = groups.iter().map(|group| group.key).collect();

    assert_eq!(
        keys,
        vec![
            HistoryGroupKey::Pinned,
            HistoryGroupKey::Today,
            HistoryGroupKey::Yesterday,
            HistoryGroupKey::Previous7Days,
            HistoryGroupKey::Previous30Days,
            HistoryGroupKey::Older,
        ]
    );
    assert_eq!(groups[0].conversations[0].id, "pinned");
    assert_eq!(groups[0].label, "Pinned");
}

#[test]
fn groups_sort_newest_first_within_a_bucket() {
    let now = ts("2026-11-07T12:00:00.000Z");
    let conversations = vec![
        conversation(
            "early",
            "Early",
            false,
            false,
            ts("2026-11-07T08:00:00.000Z"),
        ),
        conversation("late", "Late", false, false, ts("2026-11-07T10:00:00.000Z")),
    ];

    let groups = group_conversations(&conversations, now);
    let ids: Vec<&str> = groups[0]
        .conversations
        .iter()
        .map(|conversation| conversation.id.as_str())
        .collect();

    assert_eq!(ids, vec!["late", "early"]);
}

#[test]
fn future_timestamps_are_today_and_empty_buckets_are_omitted() {
    let now = ts("2026-11-07T12:00:00.000Z");

    assert_eq!(
        history_group_for_date(ts("2026-11-08T12:00:00.000Z"), now),
        HistoryGroupKey::Today
    );
    assert!(group_conversations(&[], now).is_empty());
}

#[test]
fn formats_conversation_timestamps() {
    let now = ts("2026-11-07T12:00:00.000Z");

    assert_eq!(
        format_conversation_timestamp(ts("2026-11-07T09:05:00.000Z"), now),
        "09:05"
    );
    assert_eq!(
        format_conversation_timestamp(ts("2026-11-03T09:05:00.000Z"), now),
        "Nov 3"
    );
    assert_eq!(
        format_conversation_timestamp(ts("2025-11-03T09:05:00.000Z"), now),
        "Nov 3, 2025"
    );
}

#[tokio::test]
async fn search_is_bounded_and_returns_snippets() {
    let pool = open_in_memory().await.expect("open in-memory database");
    let conversations: Arc<dyn ConversationRepository> =
        Arc::new(SqliteConversationRepository::new(pool.clone()));
    let messages = SqliteMessageRepository::new(pool.clone());
    let search: Arc<dyn SearchRepository> = Arc::new(SqliteSearchRepository::new(pool));
    let conversation = conversation(
        "c1",
        "Alpha chat",
        false,
        false,
        ts("2026-11-07T09:00:00.000Z"),
    );
    conversations.create(&conversation).await.expect("create");

    for index in 0..25 {
        messages
            .insert(&message(
                &format!("m{index}"),
                "c1",
                index,
                "alpha beta gamma",
            ))
            .await
            .expect("insert message");
    }

    let service = ConversationSearchService::with_limit(search, conversations, 5);
    let result = service.search("alpha").await.expect("search");

    assert_eq!(result.hits.len(), 5);
    assert!(result.truncated);
    assert_eq!(result.hits[0].conversation_title, "Alpha chat");
    assert!(result.hits[0].snippet.contains("alpha"));
    assert!(result.hits[0].snippet.chars().count() <= MAX_SNIPPET_CHARS + 3);
}

#[tokio::test]
async fn search_empty_query_and_no_match_are_empty() {
    let pool = open_in_memory().await.expect("open in-memory database");
    let conversations: Arc<dyn ConversationRepository> =
        Arc::new(SqliteConversationRepository::new(pool.clone()));
    let search: Arc<dyn SearchRepository> = Arc::new(SqliteSearchRepository::new(pool));
    let service = ConversationSearchService::new(search, conversations);

    let empty = service.search("   ").await.expect("empty query");
    assert!(empty.hits.is_empty());
    assert!(!empty.truncated);

    let no_match = service.search("zzzz").await.expect("no match");
    assert!(no_match.hits.is_empty());
    assert!(!no_match.truncated);
}

struct FailingSearch;

#[async_trait]
impl SearchRepository for FailingSearch {
    async fn search(
        &self,
        _query: &str,
        _options: &SearchOptions,
    ) -> Result<Vec<SearchHit>, StorageError> {
        Err(StorageError::InvalidInput("boom".to_owned()))
    }
}

#[tokio::test]
async fn search_error_is_content_free() {
    let pool = open_in_memory().await.expect("open in-memory database");
    let conversations: Arc<dyn ConversationRepository> =
        Arc::new(SqliteConversationRepository::new(pool));
    let service = ConversationSearchService::new(Arc::new(FailingSearch), conversations);
    let error = service
        .search("secret message content")
        .await
        .expect_err("search should fail");

    assert_eq!(error.message, "Search is unavailable right now. Try again.");
    assert!(!error.message.contains("secret"));
}

#[test]
fn search_state_covers_empty_loading_and_error() {
    assert_eq!(
        ConversationSearchState::loading("  alpha  "),
        ConversationSearchState::Loading {
            query: "alpha".to_owned()
        }
    );

    let empty = ConversationSearchState::from_result(
        Ok(ConversationSearchResult {
            query: "alpha".to_owned(),
            hits: Vec::new(),
            truncated: false,
        }),
        "alpha",
    );
    assert_eq!(
        empty,
        ConversationSearchState::Empty {
            query: "alpha".to_owned()
        }
    );

    let error = ConversationSearchState::from_result(Err(ConversationSearchError::new()), "alpha");
    assert!(matches!(error, ConversationSearchState::Error { .. }));
}

#[tokio::test]
async fn load_groups_and_marks_drafts() {
    let mut harness = harness().await;
    let now = Timestamp::now();
    harness
        .conversations
        .create(&conversation("today", "Today", false, false, now))
        .await
        .expect("create today");
    harness
        .conversations
        .create(&conversation(
            "older",
            "Older",
            false,
            false,
            ts("2020-01-01T00:00:00.000Z"),
        ))
        .await
        .expect("create older");
    harness.drafts.set_draft("today", "draft text");

    harness.history.load(false).await.expect("load");

    let state = harness.history.state();
    assert_eq!(state.status, HistoryStatus::Ready);
    assert!(state.draft_ids.contains("today"));
    assert_eq!(state.groups.len(), 2);
    assert_eq!(state.groups[0].key, HistoryGroupKey::Today);
    assert_eq!(state.groups[1].key, HistoryGroupKey::Older);
}

#[tokio::test]
async fn rename_persists_and_rejects_blank() {
    let mut harness = harness().await;
    let now = Timestamp::now();
    harness
        .conversations
        .create(&conversation("c1", "Old", false, false, now))
        .await
        .expect("create");
    harness.history.load(false).await.expect("load");

    harness
        .history
        .rename("c1", "  New title  ")
        .await
        .expect("rename");

    assert_eq!(
        harness
            .conversations
            .get("c1")
            .await
            .expect("get")
            .expect("conversation")
            .title,
        "New title"
    );
    assert_eq!(harness.history.state().conversations[0].title, "New title");

    let error = harness
        .history
        .rename("c1", "   ")
        .await
        .expect_err("blank title");
    assert!(matches!(error, HistoryError::InvalidInput(_)));
}

#[tokio::test]
async fn pin_and_archive_persist() {
    let mut harness = harness().await;
    let now = Timestamp::now();
    harness
        .conversations
        .create(&conversation("c1", "Chat", false, false, now))
        .await
        .expect("create");
    harness.history.load(false).await.expect("load");

    harness.history.set_pinned("c1", true).await.expect("pin");
    assert!(
        harness
            .conversations
            .get("c1")
            .await
            .expect("get")
            .expect("conversation")
            .pinned
    );
    assert_eq!(
        harness.history.state().groups[0].key,
        HistoryGroupKey::Pinned
    );

    harness
        .history
        .set_archived("c1", true)
        .await
        .expect("archive");
    assert!(
        harness
            .conversations
            .get("c1")
            .await
            .expect("get")
            .expect("conversation")
            .archived
    );
    assert!(harness.history.state().conversations.is_empty());
}

#[tokio::test]
async fn delete_undo_keeps_messages_and_attachments() {
    let mut harness = harness().await;
    let now = Timestamp::now();
    harness
        .conversations
        .create(&conversation("c1", "Chat", false, false, now))
        .await
        .expect("create");
    harness
        .messages
        .insert(&message("m1", "c1", 0, "hello"))
        .await
        .expect("insert message");
    harness
        .attachments
        .insert(&attachment("a1", "m1"))
        .await
        .expect("insert attachment");
    harness.history.load(false).await.expect("load");

    let start = Instant::now();
    harness
        .history
        .request_delete("c1", start)
        .await
        .expect("request delete");

    assert!(harness.history.state().conversations.is_empty());
    assert!(harness.history.pending_delete().is_some());
    assert!(
        harness
            .conversations
            .get("c1")
            .await
            .expect("get")
            .is_some()
    );
    assert_eq!(
        harness
            .messages
            .list_all("c1")
            .await
            .expect("messages")
            .len(),
        1
    );
    assert_eq!(
        harness
            .attachments
            .list_for_message("m1")
            .await
            .expect("attachments")
            .len(),
        1
    );

    harness.history.undo_delete().await.expect("undo");

    assert_eq!(harness.history.state().conversations.len(), 1);
    assert!(harness.history.pending_delete().is_none());
    assert!(
        harness
            .conversations
            .get("c1")
            .await
            .expect("get")
            .is_some()
    );
    assert_eq!(
        harness
            .messages
            .list_all("c1")
            .await
            .expect("messages")
            .len(),
        1
    );
    assert_eq!(
        harness
            .attachments
            .list_for_message("m1")
            .await
            .expect("attachments")
            .len(),
        1
    );
}

#[tokio::test]
async fn delete_commits_after_undo_window() {
    let mut harness = harness().await;
    let now = Timestamp::now();
    harness
        .conversations
        .create(&conversation("c1", "Chat", false, false, now))
        .await
        .expect("create");
    harness
        .messages
        .insert(&message("m1", "c1", 0, "hello"))
        .await
        .expect("insert message");
    harness
        .attachments
        .insert(&attachment("a1", "m1"))
        .await
        .expect("insert attachment");
    harness.history.load(false).await.expect("load");

    let start = Instant::now();
    harness
        .history
        .request_delete("c1", start)
        .await
        .expect("request delete");

    assert!(
        !harness
            .history
            .commit_delete(start + Duration::from_secs(4))
            .await
            .expect("early commit")
    );
    assert!(
        harness
            .conversations
            .get("c1")
            .await
            .expect("get")
            .is_some()
    );

    assert!(
        harness
            .history
            .commit_delete(start + Duration::from_secs(6))
            .await
            .expect("late commit")
    );
    assert!(
        harness
            .conversations
            .get("c1")
            .await
            .expect("get")
            .is_none()
    );
    assert!(
        harness
            .messages
            .list_all("c1")
            .await
            .expect("messages")
            .is_empty()
    );
    assert!(
        harness
            .attachments
            .list_for_message("m1")
            .await
            .expect("attachments")
            .is_empty()
    );
}

#[tokio::test]
async fn refresh_during_undo_window_keeps_pending_hidden() {
    let mut harness = harness().await;
    let now = Timestamp::now();
    harness
        .conversations
        .create(&conversation("c1", "Chat", false, false, now))
        .await
        .expect("create");
    harness.history.load(false).await.expect("load");

    let start = Instant::now();
    harness
        .history
        .request_delete("c1", start)
        .await
        .expect("request delete");
    harness.history.refresh().await.expect("refresh");

    assert!(harness.history.state().conversations.is_empty());
    assert!(harness.history.pending_delete().is_some());

    harness.history.undo_delete().await.expect("undo");
    assert_eq!(harness.history.state().conversations.len(), 1);
}

#[tokio::test]
async fn drafts_indicator_refreshes() {
    let mut harness = harness().await;
    let now = Timestamp::now();
    harness
        .conversations
        .create(&conversation("c1", "Chat", false, false, now))
        .await
        .expect("create");
    harness.drafts.set_draft("c1", "draft text");
    harness.history.load(false).await.expect("load");

    assert!(harness.history.has_draft("c1"));
    assert!(harness.history.draft_ids().contains("c1"));

    harness.drafts.clear_draft("c1");
    harness.history.refresh().await.expect("refresh");

    assert!(!harness.history.has_draft("c1"));
}

#[tokio::test]
async fn last_active_restore_and_stale_clear() {
    let mut harness = harness().await;
    let now = Timestamp::now();
    harness
        .conversations
        .create(&conversation("c1", "Chat", false, false, now))
        .await
        .expect("create");

    let mut settings = harness.settings.load().await.expect("load settings");
    settings.last_active_conversation_id = Some("c1".to_owned());
    harness
        .settings
        .save(&settings)
        .await
        .expect("save settings");

    let restored = harness
        .history
        .restore_last_active()
        .await
        .expect("restore")
        .expect("conversation");
    assert_eq!(restored.id, "c1");
    assert_eq!(
        harness.history.state().active_conversation_id.as_deref(),
        Some("c1")
    );

    settings.last_active_conversation_id = Some("missing".to_owned());
    harness
        .settings
        .save(&settings)
        .await
        .expect("save settings");

    assert!(
        harness
            .history
            .restore_last_active()
            .await
            .expect("restore stale")
            .is_none()
    );
    assert!(
        harness
            .settings
            .load()
            .await
            .expect("load settings")
            .last_active_conversation_id
            .is_none()
    );
}

#[tokio::test]
async fn new_chat_creates_and_selects() {
    let mut harness = harness().await;
    harness
        .providers
        .insert(&provider("p1"))
        .await
        .expect("insert provider");
    harness
        .models
        .insert(&model("m1", "p1"))
        .await
        .expect("insert model");

    let mut settings = harness.settings.load().await.expect("load settings");
    settings.default_provider_id = Some("p1".to_owned());
    settings.default_model_id = Some("m1".to_owned());
    harness
        .settings
        .save(&settings)
        .await
        .expect("save settings");

    let conversation = harness.history.new_chat().await.expect("new chat");

    assert_eq!(conversation.title, DEFAULT_CONVERSATION_TITLE);
    assert_eq!(conversation.provider_id, "p1");
    assert_eq!(conversation.model_id, "m1");
    assert_eq!(
        harness.history.state().active_conversation_id.as_deref(),
        Some(conversation.id.as_str())
    );
    assert_eq!(
        harness
            .settings
            .load()
            .await
            .expect("load settings")
            .last_active_conversation_id
            .as_deref(),
        Some(conversation.id.as_str())
    );
    assert!(
        harness
            .conversations
            .get(&conversation.id)
            .await
            .expect("get")
            .is_some()
    );
}

#[tokio::test]
async fn new_chat_requires_configuration() {
    let mut harness = harness().await;
    let error = harness
        .history
        .new_chat()
        .await
        .expect_err("not configured");
    assert!(matches!(error, HistoryError::NotConfigured(_)));
}

#[tokio::test]
async fn empty_loading_and_error_states() {
    let mut harness = harness().await;
    assert_eq!(harness.history.state().status, HistoryStatus::Loading);

    harness.history.load(false).await.expect("load");
    assert_eq!(harness.history.state().status, HistoryStatus::Ready);
    assert!(harness.history.is_empty());
}

#[tokio::test]
async fn load_error_sets_error_state() {
    let pool = open_in_memory().await.expect("open in-memory database");
    let conversations: Arc<dyn ConversationRepository> =
        Arc::new(SqliteConversationRepository::new(pool.clone()));
    let settings: Arc<dyn SettingsRepository> =
        Arc::new(SqliteSettingsRepository::new(pool.clone()));
    let drafts = Arc::new(MemoryDraftStore::new());
    let factory: Arc<dyn ConversationFactory> = Arc::new(DefaultConversationFactory::new(
        Arc::clone(&conversations),
        Arc::new(SqliteProviderRepository::new(pool.clone())),
        Arc::new(SqliteModelRepository::new(pool.clone())),
        Arc::clone(&settings),
    ));
    let mut history = ConversationHistory::new(
        conversations,
        settings,
        drafts,
        factory,
        HistoryConfig::default(),
    );

    pool.close().await;

    let error = history.load(false).await.expect_err("load should fail");
    assert!(matches!(error, HistoryError::Storage(_)));
    assert_eq!(history.state().status, HistoryStatus::Error);
    assert!(history.state().error.is_some());
}

#[tokio::test]
async fn load_more_is_bounded() {
    let harness = harness().await;
    let now = Timestamp::now();

    for index in 0..150 {
        harness
            .conversations
            .create(&conversation(
                &format!("c{index}"),
                "Chat",
                false,
                false,
                now,
            ))
            .await
            .expect("create");
    }

    let mut history = harness.history_with_config(HistoryConfig {
        page_size: 50,
        max_rows: 100,
        undo_window: DEFAULT_UNDO_WINDOW,
    });
    history.load(false).await.expect("load");
    assert_eq!(history.state().conversations.len(), 50);

    assert!(history.load_more().await.expect("load more"));
    assert_eq!(history.state().conversations.len(), 100);

    assert!(!history.load_more().await.expect("load more at cap"));
    assert_eq!(history.state().conversations.len(), 100);
}

#[test]
fn navigation_new_chat_and_selection() {
    let mut navigation = ConversationNavigation::new();

    assert_eq!(navigation.destination(), Destination::History);
    assert!(!navigation.is_chat());

    navigation.new_chat("c1");
    assert_eq!(navigation.destination(), Destination::Chat);
    assert_eq!(navigation.conversation_id(), Some("c1"));
    assert_eq!(navigation.last_active(), Some("c1"));

    navigation.open_history();
    assert_eq!(navigation.destination(), Destination::History);
    assert_eq!(navigation.last_active(), Some("c1"));

    navigation.select("c2");
    assert_eq!(navigation.conversation_id(), Some("c2"));
    assert_eq!(navigation.last_active(), Some("c2"));

    navigation.restore(Some("c3"));
    assert_eq!(navigation.conversation_id(), Some("c3"));

    navigation.restore(None);
    assert_eq!(navigation.destination(), Destination::History);
    assert_eq!(navigation.last_active(), None);
}
