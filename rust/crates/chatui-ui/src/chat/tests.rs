//! Chat runner, streaming state, and service tests.

use std::collections::BTreeMap;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;

use async_trait::async_trait;
use chatui_contracts::{AppConfig, provider_catalog};
use chatui_domain::{
    BrowserCors, ChatError, ChatErrorCode, ChatParams, ContentPart, Conversation, FinishReason,
    InputModality, Message, MessageRole, MessageStatus, Model, ModelCapability, ModelSource,
    OutputModality, Provider, ProviderAuthType, ProviderKind, StreamEvent, Timestamp, Usage,
};
use chatui_providers::{
    CancellationToken, NormalizedChatMessage, NormalizedChatRequest, PartialOutput,
    ProviderContext, ResolvedAttachment, StreamSummary,
};
use chatui_secrets::{SecretError, SecretId, SecretStore, SecretValue};
use chatui_storage::{
    ConversationRepository, MessagePage, MessageRepository, ModelRepository, ProviderRepository,
    SecretRecord, SecretRepository, SqliteConversationRepository, SqliteMessageRepository,
    SqliteModelRepository, SqliteProviderRepository, SqliteSecretRepository, StorageError,
};
use tokio::sync::Notify;

use super::runner::{
    ChatRunInput, ChatRunResult, ChatRunner, ChatRunnerOptions, ChatStreamAdapter, RegenerateInput,
    RunRequest, can_retry,
};
use super::service::{ChatService, build_chat_request, describe_chat_error};
use super::state::{
    Clock, MemoryStreamingStore, RunnerState, StreamingState, StreamingStore, reduce_stream,
};

fn fixed() -> Timestamp {
    Timestamp::parse("2026-01-01T00:00:00.000Z").expect("valid timestamp")
}

fn chat_error(code: ChatErrorCode, retryable: bool) -> ChatError {
    ChatError {
        code,
        message: "error".to_owned(),
        provider_id: None,
        status: None,
        retryable,
        retry_after_seconds: None,
        request_id: None,
        details: None,
        at: fixed(),
    }
}

fn conversation() -> Conversation {
    Conversation {
        id: "conversation-1".to_owned(),
        title: "New chat".to_owned(),
        system_prompt: "You are concise.".to_owned(),
        provider_id: "provider-1".to_owned(),
        model_id: "model-1".to_owned(),
        model_label: "OpenRouter: Auto".to_owned(),
        params: ChatParams::default(),
        pinned: false,
        archived: false,
        temporary: false,
        created_at: fixed(),
        updated_at: fixed(),
        last_message_at: fixed(),
        message_count: 0,
        last_error: None,
        sync: None,
        storage_key: String::new(),
        revision: 0,
    }
}

fn message(id: &str, sequence: u64, role: MessageRole, text: &str) -> Message {
    Message {
        id: id.to_owned(),
        conversation_id: "conversation-1".to_owned(),
        sequence,
        role,
        parts: vec![ContentPart::Text {
            text: text.to_owned(),
        }],
        status: MessageStatus::Complete,
        provider_id: Some("provider-1".to_owned()),
        model_id: Some("model-1".to_owned()),
        model_label: Some("OpenRouter: Auto".to_owned()),
        usage: None,
        finish_reason: None,
        error: None,
        latency_ms: None,
        first_token_ms: None,
        created_at: fixed(),
        updated_at: fixed(),
        deleted_at: None,
    }
}

fn context() -> ProviderContext {
    let preset = provider_catalog()
        .get("openrouter")
        .expect("openrouter preset");
    let app_config = AppConfig::new("ChatUI", "https://chatui.example").expect("app config");
    ProviderContext::new(preset, &app_config, Some("sk-test".to_owned()))
}

fn request() -> NormalizedChatRequest {
    NormalizedChatRequest {
        model: "openrouter/auto".to_owned(),
        system: Some("You are concise.".to_owned()),
        messages: vec![NormalizedChatMessage {
            role: MessageRole::User,
            parts: vec![ContentPart::Text {
                text: "Hello".to_owned(),
            }],
        }],
        params: ChatParams::default(),
    }
}

fn run_request() -> RunRequest {
    RunRequest {
        conversation: conversation(),
        context: context(),
        request: request(),
        supported_parameters: Vec::new(),
        attachments: BTreeMap::new(),
        cancel: None,
    }
}

fn summary(
    text: &str,
    reasoning: &str,
    usage: Option<Usage>,
    finish_reason: Option<FinishReason>,
    error: Option<ChatError>,
) -> StreamSummary {
    StreamSummary {
        response_id: Some("response-1".to_owned()),
        text: text.to_owned(),
        reasoning: reasoning.to_owned(),
        usage,
        finish_reason,
        error,
        partial_output: PartialOutput::none(),
        retry_allowed: true,
        first_token_ms: Some(10),
        latency_ms: 20,
    }
}

struct FixedClock {
    now: Timestamp,
}

impl FixedClock {
    fn new(now: Timestamp) -> Self {
        Self { now }
    }
}

impl Clock for FixedClock {
    fn now(&self) -> Timestamp {
        self.now
    }
}

fn counter_id() -> impl Fn() -> String + Send + Sync {
    let counter = AtomicUsize::new(0);
    move || {
        let value = counter.fetch_add(1, Ordering::SeqCst);
        format!("message-{value}")
    }
}

#[derive(Default)]
struct FakeMessageRepository {
    messages: Mutex<BTreeMap<String, Message>>,
    inserts: AtomicUsize,
    updates: AtomicUsize,
}

impl FakeMessageRepository {
    fn insert_count(&self) -> usize {
        self.inserts.load(Ordering::SeqCst)
    }

    fn update_count(&self) -> usize {
        self.updates.load(Ordering::SeqCst)
    }

    fn get_message(&self, id: &str) -> Option<Message> {
        self.messages
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
            .get(id)
            .cloned()
    }

    fn all(&self) -> Vec<Message> {
        let mut messages: Vec<Message> = self
            .messages
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
            .values()
            .cloned()
            .collect();
        messages.sort_by_key(|message| message.sequence);
        messages
    }
}

#[async_trait]
impl MessageRepository for FakeMessageRepository {
    async fn insert(&self, message: &Message) -> Result<(), StorageError> {
        self.inserts.fetch_add(1, Ordering::SeqCst);
        self.messages
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
            .insert(message.id.clone(), message.clone());
        Ok(())
    }

    async fn insert_many(&self, messages: &[Message]) -> Result<(), StorageError> {
        for message in messages {
            self.insert(message).await?;
        }
        Ok(())
    }

    async fn update(&self, message: &Message) -> Result<(), StorageError> {
        self.updates.fetch_add(1, Ordering::SeqCst);
        self.messages
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
            .insert(message.id.clone(), message.clone());
        Ok(())
    }

    async fn upsert(&self, message: &Message) -> Result<(), StorageError> {
        self.messages
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
            .insert(message.id.clone(), message.clone());
        Ok(())
    }

    async fn get(&self, id: &str) -> Result<Option<Message>, StorageError> {
        Ok(self.get_message(id))
    }

    async fn delete(&self, id: &str) -> Result<bool, StorageError> {
        Ok(self
            .messages
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
            .remove(id)
            .is_some())
    }

    async fn soft_delete(&self, id: &str, deleted_at: Timestamp) -> Result<(), StorageError> {
        let mut messages = self
            .messages
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        let message = messages
            .get_mut(id)
            .ok_or_else(|| StorageError::NotFound(id.to_owned()))?;
        message.deleted_at = Some(deleted_at);
        Ok(())
    }

    async fn newest_page(
        &self,
        conversation_id: &str,
        limit: u32,
    ) -> Result<MessagePage, StorageError> {
        let mut messages: Vec<Message> = self
            .all()
            .into_iter()
            .filter(|message| {
                message.conversation_id == conversation_id && message.deleted_at.is_none()
            })
            .collect();
        let has_more = messages.len() > limit as usize;
        if has_more {
            messages = messages.split_off(messages.len() - limit as usize);
        }
        Ok(MessagePage { messages, has_more })
    }

    async fn older_page(
        &self,
        conversation_id: &str,
        before_sequence: u64,
        limit: u32,
    ) -> Result<MessagePage, StorageError> {
        let mut messages: Vec<Message> = self
            .all()
            .into_iter()
            .filter(|message| {
                message.conversation_id == conversation_id
                    && message.deleted_at.is_none()
                    && message.sequence < before_sequence
            })
            .collect();
        let has_more = messages.len() > limit as usize;
        if has_more {
            messages = messages.split_off(messages.len() - limit as usize);
        }
        Ok(MessagePage { messages, has_more })
    }

    async fn newer_page(
        &self,
        conversation_id: &str,
        after_sequence: u64,
        limit: u32,
    ) -> Result<MessagePage, StorageError> {
        let mut messages: Vec<Message> = self
            .all()
            .into_iter()
            .filter(|message| {
                message.conversation_id == conversation_id
                    && message.deleted_at.is_none()
                    && message.sequence > after_sequence
            })
            .collect();
        let has_more = messages.len() > limit as usize;
        messages.truncate(limit as usize);
        Ok(MessagePage { messages, has_more })
    }

    async fn next_sequence(&self, conversation_id: &str) -> Result<u64, StorageError> {
        Ok(self
            .all()
            .into_iter()
            .filter(|message| message.conversation_id == conversation_id)
            .map(|message| message.sequence + 1)
            .max()
            .unwrap_or(0))
    }

    async fn list_all(&self, conversation_id: &str) -> Result<Vec<Message>, StorageError> {
        Ok(self
            .all()
            .into_iter()
            .filter(|message| {
                message.conversation_id == conversation_id && message.deleted_at.is_none()
            })
            .collect())
    }

    async fn list_all_including_deleted(
        &self,
        conversation_id: &str,
    ) -> Result<Vec<Message>, StorageError> {
        Ok(self
            .all()
            .into_iter()
            .filter(|message| message.conversation_id == conversation_id)
            .collect())
    }

    async fn list_by_status(&self, status: MessageStatus) -> Result<Vec<Message>, StorageError> {
        Ok(self
            .all()
            .into_iter()
            .filter(|message| message.status == status && message.deleted_at.is_none())
            .collect())
    }
}

struct FakeAdapter {
    events: Vec<StreamEvent>,
    summary: StreamSummary,
    started: Arc<Notify>,
    gate: Arc<Notify>,
    wait: bool,
    wait_for_cancel: bool,
    captured_request: Mutex<Option<NormalizedChatRequest>>,
    captured_context: Mutex<Option<ProviderContext>>,
}

impl FakeAdapter {
    fn new(events: Vec<StreamEvent>, summary: StreamSummary) -> Self {
        Self {
            events,
            summary,
            started: Arc::new(Notify::new()),
            gate: Arc::new(Notify::new()),
            wait: false,
            wait_for_cancel: false,
            captured_request: Mutex::new(None),
            captured_context: Mutex::new(None),
        }
    }

    fn waiting(events: Vec<StreamEvent>, summary: StreamSummary) -> Self {
        Self {
            wait: true,
            ..Self::new(events, summary)
        }
    }

    fn cancel_waiting(events: Vec<StreamEvent>, summary: StreamSummary) -> Self {
        Self {
            wait: true,
            wait_for_cancel: true,
            ..Self::new(events, summary)
        }
    }
}

#[async_trait]
impl ChatStreamAdapter for FakeAdapter {
    async fn stream_chat(
        &self,
        context: &ProviderContext,
        request: &NormalizedChatRequest,
        _supported_parameters: &[String],
        _attachments: &BTreeMap<String, ResolvedAttachment>,
        cancel: &CancellationToken,
        on_event: &mut (dyn FnMut(StreamEvent) + Send),
    ) -> Result<StreamSummary, ChatError> {
        *self
            .captured_request
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner()) = Some(request.clone());
        *self
            .captured_context
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner()) = Some(context.clone());

        for event in &self.events {
            on_event(event.clone());
        }

        self.started.notify_one();

        if self.wait {
            if self.wait_for_cancel {
                cancel.cancelled().await;
            } else {
                self.gate.notified().await;
            }
        }

        Ok(self.summary.clone())
    }
}

fn runner(
    adapter: Arc<dyn ChatStreamAdapter>,
    messages: Arc<FakeMessageRepository>,
    store: Arc<MemoryStreamingStore>,
    clock: Arc<dyn Clock>,
) -> ChatRunner {
    ChatRunner::new(ChatRunnerOptions {
        adapter,
        messages,
        store,
        clock,
        new_id: Arc::new(counter_id()),
        persist_interval: Duration::from_secs(1),
    })
}

#[tokio::test]
async fn send_persists_user_message_and_streams_text_and_reasoning() {
    let usage = Usage {
        input_tokens: Some(10),
        output_tokens: Some(20),
        total_tokens: Some(30),
        cost_usd: Some(0.001),
        estimated: false,
    };
    let events = vec![
        StreamEvent::Start {
            response_id: Some("response-1".to_owned()),
        },
        StreamEvent::ReasoningDelta {
            text: "think".to_owned(),
        },
        StreamEvent::TextDelta {
            text: "Hello".to_owned(),
        },
        StreamEvent::TextDelta {
            text: " world".to_owned(),
        },
        StreamEvent::Usage {
            usage: usage.clone(),
        },
        StreamEvent::Finish {
            reason: FinishReason::Stop,
        },
    ];
    let adapter = Arc::new(FakeAdapter::new(
        events,
        summary(
            "Hello world",
            "think",
            Some(usage.clone()),
            Some(FinishReason::Stop),
            None,
        ),
    ));
    let repo = Arc::new(FakeMessageRepository::default());
    let store = Arc::new(MemoryStreamingStore::new());
    let clock = Arc::new(FixedClock::new(fixed()));
    let runner = runner(adapter, repo.clone(), store.clone(), clock);

    let result = runner
        .send(ChatRunInput {
            run: run_request(),
            user_parts: vec![ContentPart::Text {
                text: "Hello".to_owned(),
            }],
        })
        .await;

    let ChatRunResult::Ok { message, state } = result else {
        panic!("expected ok: {result:?}");
    };

    assert_eq!(message.status, MessageStatus::Complete);
    assert_eq!(message.finish_reason, Some(FinishReason::Stop));
    assert_eq!(message.usage, Some(usage));
    assert_eq!(message.provider_id.as_deref(), Some("provider-1"));
    assert_eq!(message.model_id.as_deref(), Some("model-1"));
    assert_eq!(message.model_label.as_deref(), Some("OpenRouter: Auto"));
    assert_eq!(
        message.parts,
        vec![
            ContentPart::Reasoning {
                text: "think".to_owned(),
            },
            ContentPart::Text {
                text: "Hello world".to_owned(),
            },
        ]
    );
    assert_eq!(message.latency_ms, Some(0));
    assert_eq!(message.first_token_ms, Some(0));
    assert_eq!(state.text, "Hello world");
    assert_eq!(state.reasoning, "think");
    assert_eq!(state.runner_state, RunnerState::Complete);

    let all = repo.all();
    assert_eq!(all.len(), 2);
    assert_eq!(repo.insert_count(), 2);
    assert_eq!(all[0].role, MessageRole::User);
    assert_eq!(all[0].sequence, 0);
    assert_eq!(
        all[0].parts,
        vec![ContentPart::Text {
            text: "Hello".to_owned(),
        }]
    );
    assert_eq!(all[1].id, message.id);
    assert_eq!(all[1].sequence, 1);
    assert_eq!(
        store.stream("conversation-1").expect("stream").status,
        MessageStatus::Complete
    );
}

#[tokio::test(start_paused = true)]
async fn persists_partial_content_at_most_once_per_second() {
    let adapter = Arc::new(FakeAdapter::waiting(
        vec![
            StreamEvent::TextDelta {
                text: "a".to_owned(),
            },
            StreamEvent::TextDelta {
                text: "b".to_owned(),
            },
        ],
        summary("ab", "", None, Some(FinishReason::Stop), None),
    ));
    let repo = Arc::new(FakeMessageRepository::default());
    let store = Arc::new(MemoryStreamingStore::new());
    let clock = Arc::new(FixedClock::new(fixed()));
    let runner = Arc::new(runner(adapter.clone(), repo.clone(), store.clone(), clock));

    let handle = tokio::spawn({
        let runner = Arc::clone(&runner);
        async move {
            runner
                .send(ChatRunInput {
                    run: run_request(),
                    user_parts: vec![ContentPart::Text {
                        text: "Hello".to_owned(),
                    }],
                })
                .await
        }
    });

    adapter.started.notified().await;

    // The live row is in the streaming store, but the persisted assistant
    // message is still the empty placeholder.
    assert_eq!(store.stream("conversation-1").expect("stream").text, "ab");
    let assistant_id = repo
        .all()
        .into_iter()
        .find(|message| message.role == MessageRole::Assistant)
        .expect("assistant placeholder")
        .id;
    assert!(
        repo.get_message(&assistant_id)
            .expect("assistant")
            .parts
            .is_empty()
    );
    assert_eq!(repo.update_count(), 0);

    tokio::time::advance(Duration::from_millis(500)).await;
    tokio::task::yield_now().await;
    assert_eq!(repo.update_count(), 0);

    tokio::time::advance(Duration::from_millis(600)).await;
    tokio::task::yield_now().await;
    assert_eq!(repo.update_count(), 1);
    assert_eq!(
        repo.get_message(&assistant_id).expect("assistant").parts,
        vec![ContentPart::Text {
            text: "ab".to_owned(),
        }]
    );

    // No new events arrived, so the next tick must not write again.
    tokio::time::advance(Duration::from_millis(1000)).await;
    tokio::task::yield_now().await;
    assert_eq!(repo.update_count(), 1);

    adapter.gate.notify_one();
    let result = handle.await.expect("join");
    assert!(matches!(result, ChatRunResult::Ok { .. }));
    assert_eq!(repo.update_count(), 2);
}

#[tokio::test]
async fn stop_cancels_and_marks_cancelled() {
    let adapter = Arc::new(FakeAdapter::cancel_waiting(
        vec![StreamEvent::TextDelta {
            text: "partial".to_owned(),
        }],
        summary("partial", "", None, Some(FinishReason::Stop), None),
    ));
    let repo = Arc::new(FakeMessageRepository::default());
    let store = Arc::new(MemoryStreamingStore::new());
    let clock = Arc::new(FixedClock::new(fixed()));
    let runner = Arc::new(runner(adapter.clone(), repo.clone(), store, clock));

    let handle = tokio::spawn({
        let runner = Arc::clone(&runner);
        async move {
            runner
                .send(ChatRunInput {
                    run: run_request(),
                    user_parts: vec![ContentPart::Text {
                        text: "Hello".to_owned(),
                    }],
                })
                .await
        }
    });

    adapter.started.notified().await;
    let cancelled = runner
        .cancel("conversation-1")
        .await
        .expect("cancelled message");

    assert_eq!(cancelled.status, MessageStatus::Cancelled);
    assert_eq!(cancelled.finish_reason, Some(FinishReason::Cancelled));
    assert_eq!(
        cancelled.parts,
        vec![ContentPart::Text {
            text: "partial".to_owned(),
        }]
    );

    let result = handle.await.expect("join");
    let ChatRunResult::Ok { message, state } = result else {
        panic!("expected ok: {result:?}");
    };
    assert_eq!(message.status, MessageStatus::Cancelled);
    assert_eq!(state.status, MessageStatus::Cancelled);
    assert_eq!(state.runner_state, RunnerState::Complete);
    assert_eq!(
        repo.get_message(&message.id).expect("message").status,
        MessageStatus::Cancelled
    );
}

#[tokio::test]
async fn regenerate_only_last_assistant() {
    let repo = Arc::new(FakeMessageRepository::default());
    let first_assistant = message("assistant-1", 1, MessageRole::Assistant, "old");
    let last_assistant = message("assistant-2", 3, MessageRole::Assistant, "old");
    repo.insert(&message("user-1", 0, MessageRole::User, "hi"))
        .await
        .expect("insert");
    repo.insert(&first_assistant).await.expect("insert");
    repo.insert(&message("user-2", 2, MessageRole::User, "again"))
        .await
        .expect("insert");
    repo.insert(&last_assistant).await.expect("insert");

    let adapter = Arc::new(FakeAdapter::new(
        vec![
            StreamEvent::TextDelta {
                text: "new".to_owned(),
            },
            StreamEvent::Finish {
                reason: FinishReason::Stop,
            },
        ],
        summary("new", "", None, Some(FinishReason::Stop), None),
    ));
    let store = Arc::new(MemoryStreamingStore::new());
    let clock = Arc::new(FixedClock::new(fixed()));
    let runner = runner(adapter, repo.clone(), store, clock);

    let invalid = runner
        .regenerate(RegenerateInput {
            run: run_request(),
            target: first_assistant,
        })
        .await;
    assert_eq!(invalid, ChatRunResult::InvalidTarget);

    let result = runner
        .regenerate(RegenerateInput {
            run: run_request(),
            target: last_assistant.clone(),
        })
        .await;
    let ChatRunResult::Ok { message, .. } = result else {
        panic!("expected ok: {result:?}");
    };

    assert_eq!(message.id, last_assistant.id);
    assert_eq!(message.sequence, last_assistant.sequence);
    assert_eq!(
        message.parts,
        vec![ContentPart::Text {
            text: "new".to_owned(),
        }]
    );
    assert_eq!(repo.all().len(), 4);
}

#[test]
fn retry_policy_rejects_partial_output_and_non_retryable_errors() {
    let mut retryable = message("assistant-1", 1, MessageRole::Assistant, "");
    retryable.parts.clear();
    retryable.status = MessageStatus::Error;
    retryable.error = Some(chat_error(ChatErrorCode::Network, true));
    assert!(can_retry(&retryable));

    let mut partial = retryable.clone();
    partial.parts = vec![ContentPart::Text {
        text: "partial".to_owned(),
    }];
    assert!(!can_retry(&partial));

    let mut non_retryable = retryable.clone();
    non_retryable.error = Some(chat_error(ChatErrorCode::Auth, false));
    assert!(!can_retry(&non_retryable));

    let mut cancelled = retryable.clone();
    cancelled.status = MessageStatus::Cancelled;
    assert!(!can_retry(&cancelled));
}

#[tokio::test]
async fn error_preserves_partial_output() {
    let error = chat_error(ChatErrorCode::Network, true);
    let adapter = Arc::new(FakeAdapter::new(
        vec![
            StreamEvent::TextDelta {
                text: "partial".to_owned(),
            },
            StreamEvent::Error {
                error: error.clone(),
            },
        ],
        summary("partial", "", None, None, Some(error.clone())),
    ));
    let repo = Arc::new(FakeMessageRepository::default());
    let store = Arc::new(MemoryStreamingStore::new());
    let clock = Arc::new(FixedClock::new(fixed()));
    let runner = runner(adapter, repo, store, clock);

    let result = runner
        .send(ChatRunInput {
            run: run_request(),
            user_parts: vec![ContentPart::Text {
                text: "Hello".to_owned(),
            }],
        })
        .await;
    let ChatRunResult::Ok { message, state } = result else {
        panic!("expected ok: {result:?}");
    };

    assert_eq!(message.status, MessageStatus::Error);
    assert_eq!(message.finish_reason, Some(FinishReason::Error));
    assert_eq!(message.error, Some(error));
    assert_eq!(
        message.parts,
        vec![ContentPart::Text {
            text: "partial".to_owned(),
        }]
    );
    assert_eq!(state.runner_state, RunnerState::Error);
}

#[tokio::test]
async fn busy_prevents_a_second_stream() {
    let adapter = Arc::new(FakeAdapter::waiting(
        vec![StreamEvent::TextDelta {
            text: "a".to_owned(),
        }],
        summary("a", "", None, Some(FinishReason::Stop), None),
    ));
    let repo = Arc::new(FakeMessageRepository::default());
    let store = Arc::new(MemoryStreamingStore::new());
    let clock = Arc::new(FixedClock::new(fixed()));
    let runner = Arc::new(runner(adapter.clone(), repo.clone(), store, clock));

    let handle = tokio::spawn({
        let runner = Arc::clone(&runner);
        async move {
            runner
                .send(ChatRunInput {
                    run: run_request(),
                    user_parts: vec![ContentPart::Text {
                        text: "Hello".to_owned(),
                    }],
                })
                .await
        }
    });

    adapter.started.notified().await;
    let second = runner
        .send(ChatRunInput {
            run: run_request(),
            user_parts: vec![ContentPart::Text {
                text: "Again".to_owned(),
            }],
        })
        .await;
    assert_eq!(second, ChatRunResult::Busy);

    adapter.gate.notify_one();
    let _ = handle.await.expect("join");
    assert_eq!(repo.all().len(), 2);
}

#[test]
fn reduce_stream_keeps_text_and_reasoning_separate() {
    let mut state = StreamingState::new("conversation-1", "message-1", fixed());
    state = reduce_stream(
        &state,
        &StreamEvent::Start {
            response_id: Some("response-1".to_owned()),
        },
        fixed(),
    );
    state = reduce_stream(
        &state,
        &StreamEvent::ReasoningDelta {
            text: "think".to_owned(),
        },
        fixed(),
    );
    state = reduce_stream(
        &state,
        &StreamEvent::TextDelta {
            text: "answer".to_owned(),
        },
        fixed(),
    );
    state = reduce_stream(
        &state,
        &StreamEvent::Finish {
            reason: FinishReason::Stop,
        },
        fixed(),
    );

    assert_eq!(state.reasoning, "think");
    assert_eq!(state.text, "answer");
    assert_eq!(state.status, MessageStatus::Complete);
    assert_eq!(state.runner_state, RunnerState::Complete);
    assert!(state.done);
}

#[test]
fn build_chat_request_filters_reasoning_and_system_messages() {
    let conversation = conversation();
    let mut user = message("user-1", 0, MessageRole::User, "hi");
    user.parts.push(ContentPart::Reasoning {
        text: "hidden".to_owned(),
    });
    let mut assistant = message("assistant-1", 1, MessageRole::Assistant, "hello");
    assistant.parts.push(ContentPart::Reasoning {
        text: "hidden".to_owned(),
    });
    let mut system = message("system-1", 2, MessageRole::System, "system");
    system.parts = vec![ContentPart::Text {
        text: "system".to_owned(),
    }];

    let request = build_chat_request(
        &conversation,
        "openrouter/auto",
        &[user, assistant, system],
        Some(&[ContentPart::Text {
            text: "next".to_owned(),
        }]),
    );

    assert_eq!(request.model, "openrouter/auto");
    assert_eq!(request.system.as_deref(), Some("You are concise."));
    assert_eq!(request.messages.len(), 3);
    assert_eq!(request.messages[0].role, MessageRole::User);
    assert_eq!(
        request.messages[0].parts,
        vec![ContentPart::Text {
            text: "hi".to_owned(),
        }]
    );
    assert_eq!(request.messages[1].role, MessageRole::Assistant);
    assert_eq!(request.messages[2].role, MessageRole::User);
    assert_eq!(
        request.messages[2].parts,
        vec![ContentPart::Text {
            text: "next".to_owned(),
        }]
    );
}

#[test]
fn describe_chat_error_maps_codes_to_actionable_copy() {
    let rate_limit = describe_chat_error(&chat_error(ChatErrorCode::RateLimit, true));
    assert_eq!(rate_limit.title, "Rate limited");
    assert!(rate_limit.message.contains("rate limiting"));

    let auth = describe_chat_error(&chat_error(ChatErrorCode::Auth, false));
    assert_eq!(auth.title, "API key rejected");

    let cancelled = describe_chat_error(&chat_error(ChatErrorCode::Cancelled, false));
    assert_eq!(cancelled.title, "Response cancelled");
}

struct FakeSecretStore {
    values: Mutex<BTreeMap<String, String>>,
}

impl FakeSecretStore {
    fn with_key(id: &str, value: &str) -> Self {
        let mut values = BTreeMap::new();
        values.insert(id.to_owned(), value.to_owned());
        Self {
            values: Mutex::new(values),
        }
    }
}

impl SecretStore for FakeSecretStore {
    fn set(&self, id: &SecretId, value: &SecretValue) -> Result<(), SecretError> {
        self.values
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
            .insert(id.as_str().to_owned(), value.expose().to_owned());
        Ok(())
    }

    fn get(&self, id: &SecretId) -> Result<Option<SecretValue>, SecretError> {
        Ok(self
            .values
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
            .get(id.as_str())
            .map(|value| SecretValue::new(value.clone())))
    }

    fn delete(&self, id: &SecretId) -> Result<(), SecretError> {
        self.values
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
            .remove(id.as_str());
        Ok(())
    }

    fn forget_key(&self, id: &SecretId) -> Result<(), SecretError> {
        self.delete(id)
    }

    fn forget_all_keys(&self) -> Result<(), SecretError> {
        self.values
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
            .clear();
        Ok(())
    }

    fn is_available(&self) -> bool {
        true
    }
}

fn provider() -> Provider {
    Provider {
        id: "provider-1".to_owned(),
        preset_id: "openrouter".to_owned(),
        label: "OpenRouter".to_owned(),
        kind: ProviderKind::OpenaiCompatible,
        base_url: "https://openrouter.ai/api/v1".to_owned(),
        auth_type: ProviderAuthType::Bearer,
        secret_id: Some("secret-1".to_owned()),
        headers: BTreeMap::new(),
        enabled: true,
        browser_cors: BrowserCors::Unsupported,
        metadata: None,
        created_at: fixed(),
        updated_at: fixed(),
    }
}

fn model() -> Model {
    Model {
        id: "model-1".to_owned(),
        provider_id: "provider-1".to_owned(),
        remote_id: "openrouter/auto".to_owned(),
        label: "OpenRouter: Auto".to_owned(),
        context_window: Some(128_000),
        max_output_tokens: Some(4096),
        capabilities: vec![ModelCapability::Text, ModelCapability::Streaming],
        pricing: None,
        canonical_slug: None,
        input_modalities: vec![InputModality::Text],
        output_modalities: vec![OutputModality::Text],
        supported_parameters: Vec::new(),
        metadata: None,
        hidden: false,
        pinned: false,
        source: ModelSource::Preset,
        created_at: fixed(),
        updated_at: fixed(),
    }
}

#[tokio::test]
async fn service_sends_with_provider_model_and_key() {
    let pool = chatui_storage::open_in_memory()
        .await
        .expect("in-memory db");
    let conversations = Arc::new(SqliteConversationRepository::new(pool.clone()));
    let messages = Arc::new(SqliteMessageRepository::new(pool.clone()));
    let providers = Arc::new(SqliteProviderRepository::new(pool.clone()));
    let models = Arc::new(SqliteModelRepository::new(pool.clone()));
    let secret_records = SqliteSecretRepository::new(pool.clone());

    conversations
        .create(&conversation())
        .await
        .expect("conversation");
    secret_records
        .put(&SecretRecord {
            id: "secret-1".to_owned(),
            ciphertext: Vec::new(),
            created_at: fixed(),
            updated_at: fixed(),
        })
        .await
        .expect("secret record");
    providers.insert(&provider()).await.expect("provider");
    models.insert(&model()).await.expect("model");

    let secrets = Arc::new(FakeSecretStore::with_key("secret-1", "sk-test"));
    let adapter = Arc::new(FakeAdapter::new(
        vec![
            StreamEvent::TextDelta {
                text: "Hello".to_owned(),
            },
            StreamEvent::Finish {
                reason: FinishReason::Stop,
            },
        ],
        summary("Hello", "", None, Some(FinishReason::Stop), None),
    ));
    let store = Arc::new(MemoryStreamingStore::new());
    let clock = Arc::new(FixedClock::new(fixed()));
    let runner = Arc::new(ChatRunner::new(ChatRunnerOptions {
        adapter: adapter.clone(),
        messages: messages.clone(),
        store,
        clock,
        new_id: Arc::new(counter_id()),
        persist_interval: Duration::from_secs(1),
    }));
    let service = ChatService::new(
        conversations,
        messages.clone(),
        providers,
        models,
        secrets,
        runner,
        AppConfig::new("ChatUI", "https://chatui.example").expect("app config"),
    );

    let message = service
        .send(
            "conversation-1",
            vec![ContentPart::Text {
                text: "Hello".to_owned(),
            }],
        )
        .await
        .expect("send succeeds");

    assert_eq!(message.status, MessageStatus::Complete);
    assert_eq!(
        message.parts,
        vec![ContentPart::Text {
            text: "Hello".to_owned(),
        }]
    );

    let captured = adapter
        .captured_request
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner())
        .clone()
        .expect("captured request");
    assert_eq!(captured.model, "openrouter/auto");
    assert_eq!(captured.messages.len(), 1);
    assert_eq!(
        captured.messages[0].parts,
        vec![ContentPart::Text {
            text: "Hello".to_owned(),
        }]
    );

    let captured_context = adapter
        .captured_context
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner())
        .clone()
        .expect("captured context");
    assert_eq!(captured_context.api_key.as_deref(), Some("sk-test"));

    let all = messages.list_all("conversation-1").await.expect("messages");
    assert_eq!(all.len(), 2);
}
