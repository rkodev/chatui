//! Framework-independent chat runner state.
//!
//! The runner keeps one [`StreamingState`] per active assistant response. The
//! state is reduced from the shared [`StreamEvent`] model in
//! [`chatui_domain`]; this module never defines a second stream event type.
//!
//! Streaming state is deliberately separate from persisted history. The runner
//! writes snapshots to a [`StreamingStore`] for the live row and persists the
//! assistant message through the message repository at a bounded cadence.

use std::collections::BTreeMap;
use std::sync::Mutex;

use chatui_domain::{ChatError, FinishReason, MessageStatus, StreamEvent, Timestamp, Usage};

/// The runner lifecycle state machine.
///
/// A cancelled run ends in [`RunnerState::Complete`] with
/// [`MessageStatus::Cancelled`] on the message. `Cancelling` is the transient
/// state between the stop request and the adapter returning.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum RunnerState {
    /// No run is active.
    Idle,
    /// The user message and assistant placeholder are being persisted.
    Sending,
    /// The adapter is streaming events.
    Streaming,
    /// The user requested a stop and the adapter is winding down.
    Cancelling,
    /// The run finished, including a cancelled run.
    Complete,
    /// The run finished with an error.
    Error,
}

/// Ephemeral state for one active assistant response.
///
/// Text and reasoning are kept in separate buffers so the UI can render
/// reasoning without mixing it into the answer. The persisted message is built
/// from this state only at the persistence cadence and on terminal events.
#[derive(Debug, Clone, PartialEq)]
pub struct StreamingState {
    /// Owning conversation ID.
    pub conversation_id: String,
    /// Assistant message ID.
    pub message_id: String,
    /// Provider response ID, or `None` when unavailable.
    pub response_id: Option<String>,
    /// Whether the provider sent a start event.
    pub started: bool,
    /// Assistant answer text.
    pub text: String,
    /// Reasoning text, kept separate from the answer.
    pub reasoning: String,
    /// Provider-reported usage, or `None` when unavailable.
    pub usage: Option<Usage>,
    /// Finish reason, or `None` while streaming.
    pub finish_reason: Option<FinishReason>,
    /// Normalized error, or `None` when the run is healthy.
    pub error: Option<ChatError>,
    /// Persisted message status.
    pub status: MessageStatus,
    /// Runner lifecycle state.
    pub runner_state: RunnerState,
    /// Whether the run reached a terminal state.
    pub done: bool,
    /// Request start time.
    pub started_at: Timestamp,
    /// First text or reasoning delta time, or `None` before the first token.
    pub first_token_at: Option<Timestamp>,
    /// Terminal time, or `None` while streaming.
    pub completed_at: Option<Timestamp>,
    /// Last update time.
    pub updated_at: Timestamp,
}

impl StreamingState {
    /// Create the initial state for a new assistant response.
    pub fn new(
        conversation_id: impl Into<String>,
        message_id: impl Into<String>,
        now: Timestamp,
    ) -> Self {
        Self {
            conversation_id: conversation_id.into(),
            message_id: message_id.into(),
            response_id: None,
            started: false,
            text: String::new(),
            reasoning: String::new(),
            usage: None,
            finish_reason: None,
            error: None,
            status: MessageStatus::Streaming,
            runner_state: RunnerState::Sending,
            done: false,
            started_at: now,
            first_token_at: None,
            completed_at: None,
            updated_at: now,
        }
    }
}

/// Reduce one normalized stream event into the next streaming state.
///
/// The function is pure: it clones the previous state and never touches
/// persistence. Text and reasoning deltas append in arrival order. The first
/// text or reasoning delta records the first-token timestamp. Tool-call deltas
/// are accepted but not rendered in phase 1.
pub fn reduce_stream(
    state: &StreamingState,
    event: &StreamEvent,
    now: Timestamp,
) -> StreamingState {
    let mut next = state.clone();

    match event {
        StreamEvent::Start { response_id } => {
            next.response_id = response_id.clone();
            next.started = true;
            next.runner_state = RunnerState::Streaming;
            next.updated_at = now;
        }
        StreamEvent::TextDelta { text } => {
            next.text.push_str(text);
            next.first_token_at = next.first_token_at.or(Some(now));
            next.runner_state = RunnerState::Streaming;
            next.updated_at = now;
        }
        StreamEvent::ReasoningDelta { text } => {
            next.reasoning.push_str(text);
            next.first_token_at = next.first_token_at.or(Some(now));
            next.runner_state = RunnerState::Streaming;
            next.updated_at = now;
        }
        StreamEvent::ToolCallDelta { .. } => {}
        StreamEvent::Usage { usage } => {
            next.usage = Some(usage.clone());
            next.updated_at = now;
        }
        StreamEvent::Finish { reason } => {
            next.finish_reason = Some(*reason);
            next.status = if next.error.is_some() {
                MessageStatus::Error
            } else {
                MessageStatus::Complete
            };
            next.runner_state = if next.error.is_some() {
                RunnerState::Error
            } else {
                RunnerState::Complete
            };
            next.done = true;
            next.completed_at = next.completed_at.or(Some(now));
            next.updated_at = now;
        }
        StreamEvent::Error { error } => {
            next.error = Some(error.clone());
            next.status = MessageStatus::Error;
            next.runner_state = RunnerState::Error;
            next.done = true;
            next.completed_at = next.completed_at.or(Some(now));
            next.updated_at = now;
        }
    }

    next
}

/// Ephemeral streaming snapshots keyed by conversation ID.
///
/// The store is intentionally separate from the message repository. History
/// rendering reads persisted messages; the live row reads this store.
pub trait StreamingStore: Send + Sync {
    /// Store or replace the snapshot for a conversation.
    fn set_stream(&self, state: StreamingState);
    /// Remove the snapshot for a conversation.
    fn clear_stream(&self, conversation_id: &str);
    /// Return the snapshot for a conversation, or `None` when idle.
    fn stream(&self, conversation_id: &str) -> Option<StreamingState>;
}

/// In-memory streaming store used by the app and tests.
#[derive(Debug, Default)]
pub struct MemoryStreamingStore {
    streams: Mutex<BTreeMap<String, StreamingState>>,
}

impl MemoryStreamingStore {
    /// Create an empty store.
    pub fn new() -> Self {
        Self::default()
    }
}

impl StreamingStore for MemoryStreamingStore {
    fn set_stream(&self, state: StreamingState) {
        self.streams
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
            .insert(state.conversation_id.clone(), state);
    }

    fn clear_stream(&self, conversation_id: &str) {
        self.streams
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
            .remove(conversation_id);
    }

    fn stream(&self, conversation_id: &str) -> Option<StreamingState> {
        self.streams
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
            .get(conversation_id)
            .cloned()
    }
}

/// Injectable clock for deterministic timing tests.
pub trait Clock: Send + Sync {
    /// Return the current UTC time.
    fn now(&self) -> Timestamp;
}

/// Wall-clock implementation of [`Clock`].
#[derive(Debug, Clone, Copy, Default)]
pub struct SystemClock;

impl Clock for SystemClock {
    fn now(&self) -> Timestamp {
        Timestamp::now()
    }
}
