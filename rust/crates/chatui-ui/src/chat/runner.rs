//! Chat runner: send, stream, stop, regenerate, and retry.
//!
//! The runner owns one active stream per conversation. It persists the user
//! message and assistant placeholder immediately, reduces the shared
//! [`chatui_domain::StreamEvent`] model into [`StreamingState`], persists
//! partial content at most once per second and on terminal events, and keeps
//! the live streaming snapshot separate from history rendering.
//!
//! # State transitions
//!
//! ```text
//! Idle -> Sending -> Streaming -> Complete
//!                        |  \-> Error
//!                        \-> Cancelling -> Complete (message status Cancelled)
//! ```
//!
//! # Retry policy
//!
//! A user-initiated retry is allowed only for an assistant message that ended
//! with a retryable error and emitted no text or reasoning. Automatic retries
//! inside one request are owned by the provider retry module; the runner never
//! resubmits a request after partial output.

#![allow(clippy::result_large_err)]

use std::collections::BTreeMap;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;

use async_trait::async_trait;
use chatui_domain::{
    ChatError, ChatErrorCode, ContentPart, Conversation, FinishReason, Message, MessageRole,
    MessageStatus, StreamEvent, Timestamp,
};
use chatui_providers::{
    CancellationToken, HttpClient, NormalizedChatRequest, ProviderContext, ResolvedAttachment,
    StreamSummary, create_adapter,
};
use chatui_storage::{MessageRepository, StorageError};
use tokio::sync::oneshot;
use tokio::time::MissedTickBehavior;

use crate::chat::state::{
    Clock, MemoryStreamingStore, RunnerState, StreamingState, StreamingStore, SystemClock,
    reduce_stream,
};

/// Default persistence cadence from spec section 8.4.
pub const DEFAULT_PERSIST_INTERVAL: Duration = Duration::from_secs(1);

/// Identifier generator injected for deterministic tests.
pub type IdGenerator = Arc<dyn Fn() -> String + Send + Sync>;

/// Adapter surface used by the runner.
///
/// The production implementation is [`ProviderChatAdapter`]. Tests inject a
/// controllable fake instead of opening a network connection.
#[async_trait]
pub trait ChatStreamAdapter: Send + Sync {
    /// Stream a chat completion and deliver normalized events to `on_event`.
    #[allow(clippy::too_many_arguments)]
    async fn stream_chat(
        &self,
        context: &ProviderContext,
        request: &NormalizedChatRequest,
        supported_parameters: &[String],
        attachments: &BTreeMap<String, ResolvedAttachment>,
        cancel: &CancellationToken,
        on_event: &mut (dyn FnMut(StreamEvent) + Send),
    ) -> Result<StreamSummary, ChatError>;
}

/// Production adapter that dispatches to the provider registry.
///
/// One instance works for every provider because the adapter is selected from
/// the [`ProviderContext`] preset on each call.
#[derive(Debug, Clone)]
pub struct ProviderChatAdapter {
    client: HttpClient,
}

impl ProviderChatAdapter {
    /// Build an adapter over the shared HTTP client.
    pub fn new(client: HttpClient) -> Self {
        Self { client }
    }
}

#[async_trait]
impl ChatStreamAdapter for ProviderChatAdapter {
    async fn stream_chat(
        &self,
        context: &ProviderContext,
        request: &NormalizedChatRequest,
        supported_parameters: &[String],
        attachments: &BTreeMap<String, ResolvedAttachment>,
        cancel: &CancellationToken,
        on_event: &mut (dyn FnMut(StreamEvent) + Send),
    ) -> Result<StreamSummary, ChatError> {
        let adapter = create_adapter(&context.preset.id).map_err(|error| ChatError {
            code: ChatErrorCode::Unknown,
            message: format!(
                "Provider preset {} is not supported: {error}",
                context.preset.id
            ),
            provider_id: Some(context.preset.id.clone()),
            status: None,
            retryable: false,
            retry_after_seconds: None,
            request_id: None,
            details: None,
            at: Timestamp::now(),
        })?;

        adapter
            .stream_chat(
                &self.client,
                context,
                request,
                supported_parameters,
                attachments,
                cancel,
                on_event,
            )
            .await
    }
}

/// Shared inputs for a new send, regenerate, or retry.
#[derive(Debug, Clone)]
pub struct RunRequest {
    /// Conversation being answered.
    pub conversation: Conversation,
    /// Resolved provider context, including the key.
    pub context: ProviderContext,
    /// Normalized request built from history and params.
    pub request: NormalizedChatRequest,
    /// Provider-supported request parameters for the selected model.
    pub supported_parameters: Vec<String>,
    /// Resolved attachment bytes, keyed by attachment ID.
    pub attachments: BTreeMap<String, ResolvedAttachment>,
    /// Optional external cancellation token linked to the runner token.
    pub cancel: Option<CancellationToken>,
}

/// Input for a new assistant response.
#[derive(Debug, Clone)]
pub struct ChatRunInput {
    /// Shared run inputs.
    pub run: RunRequest,
    /// User message content parts.
    pub user_parts: Vec<ContentPart>,
}

/// Input for regenerating an existing assistant response.
#[derive(Debug, Clone)]
pub struct RegenerateInput {
    /// Shared run inputs.
    pub run: RunRequest,
    /// Assistant message to replace in place.
    pub target: Message,
}

/// Input for retrying a failed assistant response.
#[derive(Debug, Clone)]
pub struct RetryInput {
    /// Shared run inputs.
    pub run: RunRequest,
    /// Failed assistant message to retry.
    pub target: Message,
}

/// Result of a runner operation.
#[allow(clippy::large_enum_variant)]
#[derive(Debug, Clone, PartialEq)]
pub enum ChatRunResult {
    /// The run finished. The message is the final persisted snapshot.
    Ok {
        /// Final assistant message.
        message: Message,
        /// Final streaming state.
        state: StreamingState,
    },
    /// A stream is already active for the conversation.
    Busy,
    /// A message could not be persisted.
    Storage {
        /// Normalized storage error.
        error: ChatError,
    },
    /// The regenerate or retry target is not allowed.
    InvalidTarget,
}

/// Runner construction options.
pub struct ChatRunnerOptions {
    /// Provider adapter.
    pub adapter: Arc<dyn ChatStreamAdapter>,
    /// Message persistence.
    pub messages: Arc<dyn MessageRepository>,
    /// Ephemeral streaming snapshots.
    pub store: Arc<dyn StreamingStore>,
    /// Injectable clock.
    pub clock: Arc<dyn Clock>,
    /// Identifier generator.
    pub new_id: IdGenerator,
    /// Persistence cadence. Defaults to [`DEFAULT_PERSIST_INTERVAL`].
    pub persist_interval: Duration,
}

struct ActiveStream {
    cancel: CancellationToken,
    done: Mutex<Option<oneshot::Receiver<Message>>>,
}

#[allow(clippy::large_enum_variant)]
enum StartMode {
    New { user_parts: Vec<ContentPart> },
    Regenerate { target: Message },
}

/// Non-UI service that owns one stream per conversation.
pub struct ChatRunner {
    adapter: Arc<dyn ChatStreamAdapter>,
    messages: Arc<dyn MessageRepository>,
    store: Arc<dyn StreamingStore>,
    clock: Arc<dyn Clock>,
    new_id: IdGenerator,
    persist_interval: Duration,
    active: Mutex<BTreeMap<String, ActiveStream>>,
}

impl ChatRunner {
    /// Build a runner over the injected dependencies.
    pub fn new(options: ChatRunnerOptions) -> Self {
        Self {
            adapter: options.adapter,
            messages: options.messages,
            store: options.store,
            clock: options.clock,
            new_id: options.new_id,
            persist_interval: options.persist_interval.max(Duration::from_millis(1)),
            active: Mutex::new(BTreeMap::new()),
        }
    }

    /// Build a runner with the system clock, in-memory store, and default
    /// persistence cadence.
    pub fn with_defaults(
        adapter: Arc<dyn ChatStreamAdapter>,
        messages: Arc<dyn MessageRepository>,
    ) -> Self {
        Self::new(ChatRunnerOptions {
            adapter,
            messages,
            store: Arc::new(MemoryStreamingStore::new()),
            clock: Arc::new(SystemClock),
            new_id: Arc::new(chatui_domain::new_id),
            persist_interval: DEFAULT_PERSIST_INTERVAL,
        })
    }

    /// True while a stream is active for the conversation.
    pub fn is_streaming(&self, conversation_id: &str) -> bool {
        self.active
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
            .contains_key(conversation_id)
    }

    /// Conversation IDs with an active stream.
    pub fn active_conversation_ids(&self) -> Vec<String> {
        self.active
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
            .keys()
            .cloned()
            .collect()
    }

    /// Start a new assistant response.
    ///
    /// The user message and assistant placeholder are persisted before the
    /// adapter is called. A second call for the same conversation returns
    /// [`ChatRunResult::Busy`] without writing anything.
    pub async fn send(&self, input: ChatRunInput) -> ChatRunResult {
        self.start(
            input.run,
            StartMode::New {
                user_parts: input.user_parts,
            },
        )
        .await
    }

    /// Replace the last assistant response in place.
    ///
    /// The target must be an assistant message in the conversation and must be
    /// the last assistant message. Its ID and sequence are reused, so the
    /// persisted history is replaced rather than branched.
    pub async fn regenerate(&self, input: RegenerateInput) -> ChatRunResult {
        if input.target.role != MessageRole::Assistant
            || input.target.conversation_id != input.run.conversation.id
        {
            return ChatRunResult::InvalidTarget;
        }

        let last = match self.last_assistant_id(&input.run.conversation.id).await {
            Ok(last) => last,
            Err(error) => return ChatRunResult::Storage { error },
        };

        if last.as_deref() != Some(input.target.id.as_str()) {
            return ChatRunResult::InvalidTarget;
        }

        self.start(
            input.run,
            StartMode::Regenerate {
                target: input.target,
            },
        )
        .await
    }

    /// Retry a failed assistant response when the shared policy allows it.
    ///
    /// Retry is rejected after any text or reasoning output and for
    /// non-retryable errors. Callers should offer regenerate instead.
    pub async fn retry(&self, input: RetryInput) -> ChatRunResult {
        if !can_retry(&input.target) {
            return ChatRunResult::InvalidTarget;
        }

        self.regenerate(RegenerateInput {
            run: input.run,
            target: input.target,
        })
        .await
    }

    /// Cancel the active stream for a conversation.
    ///
    /// Cancellation aborts the adapter, persists the partial content as
    /// `cancelled`, and waits for the run to finish so the final snapshot is
    /// durable. Returns `None` when no stream is active.
    pub async fn cancel(&self, conversation_id: &str) -> Option<Message> {
        let (cancel, receiver) = {
            let mut active = self
                .active
                .lock()
                .unwrap_or_else(|poisoned| poisoned.into_inner());
            let entry = active.get_mut(conversation_id)?;
            let receiver = entry
                .done
                .lock()
                .unwrap_or_else(|poisoned| poisoned.into_inner())
                .take()?;
            (entry.cancel.clone(), receiver)
        };

        cancel.cancel();
        receiver.await.ok()
    }

    /// Cancel every active stream.
    pub async fn dispose(&self) {
        for conversation_id in self.active_conversation_ids() {
            let _ = self.cancel(&conversation_id).await;
        }
    }

    async fn last_assistant_id(&self, conversation_id: &str) -> Result<Option<String>, ChatError> {
        let messages = self
            .messages
            .list_all(conversation_id)
            .await
            .map_err(|error| storage_error(error, self.clock.now()))?;

        Ok(messages
            .into_iter()
            .rev()
            .find(|message| message.role == MessageRole::Assistant)
            .map(|message| message.id))
    }

    async fn start(&self, run: RunRequest, mode: StartMode) -> ChatRunResult {
        let conversation_id = run.conversation.id.clone();
        let now = self.clock.now();
        let cancel = run
            .cancel
            .as_ref()
            .map(CancellationToken::child_token)
            .unwrap_or_default();
        let (sender, receiver) = oneshot::channel();

        {
            let mut active = self
                .active
                .lock()
                .unwrap_or_else(|poisoned| poisoned.into_inner());

            if active.contains_key(&conversation_id) {
                return ChatRunResult::Busy;
            }

            active.insert(
                conversation_id.clone(),
                ActiveStream {
                    cancel: cancel.clone(),
                    done: Mutex::new(Some(receiver)),
                },
            );
        }

        let assistant_message = match mode {
            StartMode::New { user_parts } => {
                let user_sequence = match self.messages.next_sequence(&conversation_id).await {
                    Ok(sequence) => sequence,
                    Err(error) => {
                        self.remove_active(&conversation_id);
                        return ChatRunResult::Storage {
                            error: storage_error(error, now),
                        };
                    }
                };
                let user_message = build_user_message(
                    &run.conversation,
                    user_parts,
                    user_sequence,
                    now,
                    &self.new_id,
                );
                let assistant_message = build_assistant_message(
                    &run.conversation,
                    user_sequence + 1,
                    now,
                    &self.new_id,
                );

                if let Err(error) = self.messages.insert(&user_message).await {
                    self.remove_active(&conversation_id);
                    return ChatRunResult::Storage {
                        error: storage_error(error, now),
                    };
                }

                if let Err(error) = self.messages.insert(&assistant_message).await {
                    self.remove_active(&conversation_id);
                    return ChatRunResult::Storage {
                        error: storage_error(error, now),
                    };
                }

                assistant_message
            }
            StartMode::Regenerate { target } => {
                let assistant_message = reset_assistant_message(&target, &run.conversation, now);

                if let Err(error) = self.messages.update(&assistant_message).await {
                    self.remove_active(&conversation_id);
                    return ChatRunResult::Storage {
                        error: storage_error(error, now),
                    };
                }

                assistant_message
            }
        };

        let state = StreamingState::new(&conversation_id, &assistant_message.id, now);
        let shared_state = Arc::new(Mutex::new(state.clone()));
        let dirty = Arc::new(AtomicBool::new(false));
        self.store.set_stream(state);

        let event_state = Arc::clone(&shared_state);
        let event_store = Arc::clone(&self.store);
        let event_dirty = Arc::clone(&dirty);
        let event_clock = Arc::clone(&self.clock);
        let mut on_event = move |event: StreamEvent| {
            let now = event_clock.now();
            let mut guard = event_state
                .lock()
                .unwrap_or_else(|poisoned| poisoned.into_inner());
            let next = reduce_stream(&guard, &event, now);

            if next != *guard {
                *guard = next.clone();
                event_dirty.store(true, Ordering::SeqCst);
                event_store.set_stream(next);
            }
        };

        let adapter_future = self.adapter.stream_chat(
            &run.context,
            &run.request,
            &run.supported_parameters,
            &run.attachments,
            &cancel,
            &mut on_event,
        );
        tokio::pin!(adapter_future);

        let mut interval = tokio::time::interval(self.persist_interval);
        interval.set_missed_tick_behavior(MissedTickBehavior::Skip);
        // Consume the immediate first tick so the first partial write happens
        // after one full interval, not at request start.
        interval.tick().await;

        let mut cancelled = false;
        let summary = loop {
            tokio::select! {
                result = &mut adapter_future => break result,
                _ = interval.tick() => {
                    let _ = self
                        .persist_if_dirty(&assistant_message, &shared_state, &dirty)
                        .await;
                }
                _ = cancel.cancelled(), if !cancelled => {
                    cancelled = true;
                    let now = self.clock.now();
                    {
                        let mut guard = shared_state
                            .lock()
                            .unwrap_or_else(|poisoned| poisoned.into_inner());
                        guard.runner_state = RunnerState::Cancelling;
                        guard.updated_at = now;
                    }
                    self.persist_now(&assistant_message, &shared_state).await;
                }
            }
        };

        let now = self.clock.now();
        let mut final_state = shared_state
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
            .clone();
        let cancelled = cancelled || cancel.is_cancelled();

        if cancelled {
            final_state.status = MessageStatus::Cancelled;
            final_state.finish_reason = Some(FinishReason::Cancelled);
            final_state.error = None;
            final_state.runner_state = RunnerState::Complete;
            final_state.done = true;
            final_state.completed_at = final_state.completed_at.or(Some(now));
            final_state.updated_at = now;
        } else {
            match summary {
                Ok(summary) => {
                    if final_state.usage.is_none() {
                        final_state.usage = summary.usage;
                    }
                    if final_state.finish_reason.is_none() {
                        final_state.finish_reason = summary.finish_reason;
                    }
                    if final_state.text.is_empty() {
                        final_state.text = summary.text;
                    }
                    if final_state.reasoning.is_empty() {
                        final_state.reasoning = summary.reasoning;
                    }

                    if let Some(error) = summary.error {
                        final_state.error = Some(error);
                        final_state.status = MessageStatus::Error;
                        final_state.finish_reason = Some(FinishReason::Error);
                        final_state.runner_state = RunnerState::Error;
                    } else {
                        final_state.status = MessageStatus::Complete;
                        final_state.finish_reason =
                            final_state.finish_reason.or(Some(FinishReason::Unknown));
                        final_state.runner_state = RunnerState::Complete;
                    }
                }
                Err(error) => {
                    final_state.error = Some(error);
                    final_state.status = MessageStatus::Error;
                    final_state.finish_reason = Some(FinishReason::Error);
                    final_state.runner_state = RunnerState::Error;
                }
            }

            final_state.done = true;
            final_state.completed_at = final_state.completed_at.or(Some(now));
            final_state.updated_at = now;
        }

        let final_message = message_from_state(&assistant_message, &final_state);
        let update_result = self.messages.update(&final_message).await;
        self.store.set_stream(final_state.clone());
        self.remove_active(&conversation_id);
        let _ = sender.send(final_message.clone());

        match update_result {
            Ok(()) => ChatRunResult::Ok {
                message: final_message,
                state: final_state,
            },
            Err(error) => ChatRunResult::Storage {
                error: storage_error(error, now),
            },
        }
    }

    fn remove_active(&self, conversation_id: &str) {
        self.active
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
            .remove(conversation_id);
    }

    async fn persist_if_dirty(
        &self,
        assistant: &Message,
        state: &Arc<Mutex<StreamingState>>,
        dirty: &Arc<AtomicBool>,
    ) -> Result<(), ChatError> {
        if !dirty.swap(false, Ordering::SeqCst) {
            return Ok(());
        }

        let snapshot = state
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
            .clone();
        let message = message_from_state(assistant, &snapshot);

        self.messages
            .update(&message)
            .await
            .map_err(|error| storage_error(error, self.clock.now()))
    }

    async fn persist_now(&self, assistant: &Message, state: &Arc<Mutex<StreamingState>>) {
        let snapshot = state
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
            .clone();
        let message = message_from_state(assistant, &snapshot);
        let _ = self.messages.update(&message).await;
    }
}

/// Return whether a message may be retried under the shared policy.
///
/// Retry requires an assistant message that ended with a retryable error and
/// emitted no text or reasoning. A cancelled message is never retried.
pub fn can_retry(message: &Message) -> bool {
    if message.role != MessageRole::Assistant || message.status != MessageStatus::Error {
        return false;
    }

    let Some(error) = &message.error else {
        return false;
    };

    error.retryable && !has_partial_output(message)
}

/// Return whether a message contains text or reasoning output.
pub fn has_partial_output(message: &Message) -> bool {
    message.parts.iter().any(|part| match part {
        ContentPart::Text { text } => !text.is_empty(),
        ContentPart::Reasoning { text } => !text.is_empty(),
        _ => false,
    })
}

fn build_user_message(
    conversation: &Conversation,
    parts: Vec<ContentPart>,
    sequence: u64,
    now: Timestamp,
    new_id: &IdGenerator,
) -> Message {
    Message {
        id: new_id(),
        conversation_id: conversation.id.clone(),
        sequence,
        role: MessageRole::User,
        parts,
        status: MessageStatus::Complete,
        provider_id: Some(conversation.provider_id.clone()),
        model_id: Some(conversation.model_id.clone()),
        model_label: Some(conversation.model_label.clone()),
        usage: None,
        finish_reason: None,
        error: None,
        latency_ms: None,
        first_token_ms: None,
        created_at: now,
        updated_at: now,
        deleted_at: None,
    }
}

fn build_assistant_message(
    conversation: &Conversation,
    sequence: u64,
    now: Timestamp,
    new_id: &IdGenerator,
) -> Message {
    Message {
        id: new_id(),
        conversation_id: conversation.id.clone(),
        sequence,
        role: MessageRole::Assistant,
        parts: Vec::new(),
        status: MessageStatus::Streaming,
        provider_id: Some(conversation.provider_id.clone()),
        model_id: Some(conversation.model_id.clone()),
        model_label: Some(conversation.model_label.clone()),
        usage: None,
        finish_reason: None,
        error: None,
        latency_ms: None,
        first_token_ms: None,
        created_at: now,
        updated_at: now,
        deleted_at: None,
    }
}

fn reset_assistant_message(
    target: &Message,
    conversation: &Conversation,
    now: Timestamp,
) -> Message {
    Message {
        id: target.id.clone(),
        conversation_id: target.conversation_id.clone(),
        sequence: target.sequence,
        role: MessageRole::Assistant,
        parts: Vec::new(),
        status: MessageStatus::Streaming,
        provider_id: Some(conversation.provider_id.clone()),
        model_id: Some(conversation.model_id.clone()),
        model_label: Some(conversation.model_label.clone()),
        usage: None,
        finish_reason: None,
        error: None,
        latency_ms: None,
        first_token_ms: None,
        created_at: target.created_at,
        updated_at: now,
        deleted_at: None,
    }
}

fn message_from_state(assistant: &Message, state: &StreamingState) -> Message {
    let mut parts = Vec::new();

    if !state.reasoning.is_empty() {
        parts.push(ContentPart::Reasoning {
            text: state.reasoning.clone(),
        });
    }

    if !state.text.is_empty() {
        parts.push(ContentPart::Text {
            text: state.text.clone(),
        });
    }

    Message {
        id: assistant.id.clone(),
        conversation_id: assistant.conversation_id.clone(),
        sequence: assistant.sequence,
        role: MessageRole::Assistant,
        parts,
        status: state.status,
        provider_id: assistant.provider_id.clone(),
        model_id: assistant.model_id.clone(),
        model_label: assistant.model_label.clone(),
        usage: state.usage.clone(),
        finish_reason: state.finish_reason,
        error: state.error.clone(),
        latency_ms: state
            .completed_at
            .map(|completed| duration_ms(state.started_at, completed)),
        first_token_ms: state
            .first_token_at
            .map(|first| duration_ms(state.started_at, first)),
        created_at: assistant.created_at,
        updated_at: state.updated_at,
        deleted_at: None,
    }
}

fn duration_ms(start: Timestamp, end: Timestamp) -> u64 {
    let duration = end.as_offset_date_time() - start.as_offset_date_time();
    u64::try_from(duration.whole_milliseconds()).unwrap_or(0)
}

fn storage_error(error: StorageError, now: Timestamp) -> ChatError {
    ChatError {
        code: ChatErrorCode::Storage,
        message: error.to_string(),
        provider_id: None,
        status: None,
        retryable: false,
        retry_after_seconds: None,
        request_id: None,
        details: None,
        at: now,
    }
}
