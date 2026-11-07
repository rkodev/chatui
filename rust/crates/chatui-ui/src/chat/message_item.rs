//! Message row view model.
//!
//! The row model is framework-independent so it can be tested without a window.
//! The Slint component in [`crate::chat::message_list`] renders these rows. The
//! Markdown slot is plain text in this task; M3.5-09.4 replaces the renderer
//! without changing the row contract.
//!
//! Rows carry the role layout, the Markdown slot, the action set, metadata, the
//! lifecycle status, and the normalized error copy. The streaming row is built
//! from [`StreamingState`] and is never mixed into completed history.

use chatui_domain::{
    ChatError, ContentPart, Conversation, Message, MessageRole, MessageStatus, Timestamp, Usage,
};

use crate::chat::service::describe_chat_error;
use crate::chat::state::StreamingState;

/// A message action offered by a row.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum MessageAction {
    /// Copy the message text.
    Copy,
    /// Regenerate the last assistant response.
    Regenerate,
    /// Retry a failed assistant response.
    Retry,
}

impl MessageAction {
    /// Stable action id used by the Slint callbacks.
    pub const fn id(self) -> &'static str {
        match self {
            Self::Copy => "copy",
            Self::Regenerate => "regenerate",
            Self::Retry => "retry",
        }
    }

    /// Visible action label.
    pub const fn label(self) -> &'static str {
        match self {
            Self::Copy => "Copy",
            Self::Regenerate => "Regenerate",
            Self::Retry => "Retry",
        }
    }
}

/// Metadata shown under a message.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct MessageMetadata {
    /// Model label snapshot.
    pub model: Option<String>,
    /// Provider ID snapshot.
    pub provider: Option<String>,
    /// Local time label.
    pub time: String,
    /// Token and cost summary.
    pub usage: Option<String>,
    /// Total latency label.
    pub latency: Option<String>,
    /// Time-to-first-token label.
    pub first_token: Option<String>,
}

impl MessageMetadata {
    /// One-line metadata summary for the row footer.
    pub fn summary(&self) -> String {
        let mut parts = Vec::new();

        if let Some(model) = &self.model {
            let mut label = model.clone();

            if let Some(provider) = &self.provider {
                label.push_str(" · ");
                label.push_str(provider);
            }

            parts.push(label);
        }

        if let Some(usage) = &self.usage {
            parts.push(usage.clone());
        }

        if let Some(latency) = &self.latency {
            parts.push(latency.clone());
        }

        if let Some(first_token) = &self.first_token {
            parts.push(first_token.clone());
        }

        parts.join(" · ")
    }
}

/// Error copy attached to a message row.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct MessageErrorView {
    /// Short heading.
    pub title: String,
    /// Actionable message.
    pub message: String,
    /// Optional non-secret details.
    pub details: Option<String>,
}

/// Options that depend on the list position and streaming state.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct MessageRowOptions {
    /// Show token usage in the metadata line.
    pub show_token_usage: bool,
    /// The row is the last assistant message and no stream is active.
    pub can_regenerate: bool,
    /// The row is a failed assistant message that the retry policy allows.
    pub can_retry: bool,
    /// The row is the active streaming row.
    pub is_active_stream: bool,
}

impl Default for MessageRowOptions {
    fn default() -> Self {
        Self {
            show_token_usage: true,
            can_regenerate: false,
            can_retry: false,
            is_active_stream: false,
        }
    }
}

/// A single message row.
#[derive(Debug, Clone, PartialEq)]
pub struct MessageRow {
    /// Stable message ID.
    pub id: String,
    /// Author role.
    pub role: MessageRole,
    /// Visible role label.
    pub role_label: String,
    /// Markdown slot. Plain text until M3.5-09.4.
    pub markdown: String,
    /// Reasoning text, kept separate from the answer.
    pub reasoning: String,
    /// Lifecycle status.
    pub status: MessageStatus,
    /// Status copy, when the row needs one.
    pub status_label: Option<String>,
    /// Metadata line.
    pub metadata: MessageMetadata,
    /// Error copy, when the message failed.
    pub error: Option<MessageErrorView>,
    /// Actions offered by the row.
    pub actions: Vec<MessageAction>,
    /// Whether this is the active streaming row.
    pub is_streaming: bool,
    /// Whether a persisted streaming row was interrupted.
    pub is_interrupted: bool,
    /// Whether the row has text or attachments.
    pub has_content: bool,
    /// Whether to show the typing indicator.
    pub show_typing: bool,
}

impl MessageRow {
    /// Build a row from a persisted message.
    pub fn from_message(message: &Message, options: MessageRowOptions) -> Self {
        let markdown = message_text(message);
        let reasoning = reasoning_text(message);
        let has_attachments = message
            .parts
            .iter()
            .any(|part| matches!(part, ContentPart::Image { .. } | ContentPart::File { .. }));
        let has_content = !markdown.trim().is_empty() || has_attachments;
        let is_interrupted =
            message.status == MessageStatus::Streaming && !options.is_active_stream;
        let error = message.error.as_ref().map(error_view);
        let status_label = status_label(message.status, is_interrupted, has_content);
        let mut actions = Vec::new();

        if !markdown.is_empty() {
            actions.push(MessageAction::Copy);
        }

        if options.can_regenerate {
            actions.push(MessageAction::Regenerate);
        }

        if options.can_retry {
            actions.push(MessageAction::Retry);
        }

        Self {
            id: message.id.clone(),
            role: message.role,
            role_label: role_label(message.role).to_owned(),
            markdown,
            reasoning,
            status: message.status,
            status_label,
            metadata: metadata_for(message, options.show_token_usage),
            error,
            actions,
            is_streaming: options.is_active_stream,
            is_interrupted,
            has_content,
            show_typing: options.is_active_stream && !has_content,
        }
    }

    /// Build the isolated streaming row from the live snapshot.
    pub fn from_streaming(
        state: &StreamingState,
        conversation: Option<&Conversation>,
        options: MessageRowOptions,
    ) -> Self {
        let has_content = !state.text.trim().is_empty() || !state.reasoning.trim().is_empty();
        let error = state.error.as_ref().map(error_view);
        let status_label = if state.status == MessageStatus::Cancelled {
            Some("Response cancelled. Partial content is preserved.".to_owned())
        } else {
            None
        };
        let mut actions = Vec::new();

        if !state.text.is_empty() {
            actions.push(MessageAction::Copy);
        }

        if options.can_retry && state.done && state.error.is_some() {
            actions.push(MessageAction::Retry);
        }

        Self {
            id: state.message_id.clone(),
            role: MessageRole::Assistant,
            role_label: role_label(MessageRole::Assistant).to_owned(),
            markdown: state.text.clone(),
            reasoning: state.reasoning.clone(),
            status: state.status,
            status_label,
            metadata: streaming_metadata(state, conversation, options.show_token_usage),
            error,
            actions,
            is_streaming: !state.done,
            is_interrupted: false,
            has_content,
            show_typing: !state.done && !has_content,
        }
    }

    /// Text copied by the copy action.
    pub fn text_for_copy(&self) -> String {
        self.markdown.clone()
    }

    /// Whether the row offers an action.
    pub fn has_action(&self, action: MessageAction) -> bool {
        self.actions.contains(&action)
    }
}

/// Visible role label.
pub const fn role_label(role: MessageRole) -> &'static str {
    match role {
        MessageRole::System => "System",
        MessageRole::User => "You",
        MessageRole::Assistant => "Assistant",
        MessageRole::Tool => "Tool",
    }
}

/// Join the text parts of a message with blank lines.
pub fn message_text(message: &Message) -> String {
    message
        .parts
        .iter()
        .filter_map(|part| match part {
            ContentPart::Text { text } => Some(text.as_str()),
            _ => None,
        })
        .filter(|text| !text.is_empty())
        .collect::<Vec<_>>()
        .join("\n\n")
}

/// Join the reasoning parts of a message with blank lines.
pub fn reasoning_text(message: &Message) -> String {
    message
        .parts
        .iter()
        .filter_map(|part| match part {
            ContentPart::Reasoning { text } => Some(text.as_str()),
            _ => None,
        })
        .filter(|text| !text.is_empty())
        .collect::<Vec<_>>()
        .join("\n\n")
}

fn metadata_for(message: &Message, show_token_usage: bool) -> MessageMetadata {
    MessageMetadata {
        model: message
            .model_label
            .clone()
            .or_else(|| message.model_id.clone()),
        provider: message.provider_id.clone(),
        time: format_time(message.created_at),
        usage: if show_token_usage {
            message.usage.as_ref().map(format_usage)
        } else {
            None
        },
        latency: message.latency_ms.map(format_latency),
        first_token: message.first_token_ms.map(format_first_token),
    }
}

fn streaming_metadata(
    state: &StreamingState,
    conversation: Option<&Conversation>,
    show_token_usage: bool,
) -> MessageMetadata {
    let latency = state
        .completed_at
        .map(|completed| elapsed_ms(state.started_at, completed));
    let first_token = state
        .first_token_at
        .map(|first| elapsed_ms(state.started_at, first));

    MessageMetadata {
        model: conversation.map(|conversation| conversation.model_label.clone()),
        provider: conversation.map(|conversation| conversation.provider_id.clone()),
        time: format_time(state.started_at),
        usage: if show_token_usage {
            state.usage.as_ref().map(format_usage)
        } else {
            None
        },
        latency: latency.map(format_latency),
        first_token: first_token.map(format_first_token),
    }
}

fn elapsed_ms(start: Timestamp, end: Timestamp) -> u64 {
    let duration = end.as_offset_date_time() - start.as_offset_date_time();
    duration.whole_milliseconds().max(0) as u64
}

fn format_time(timestamp: Timestamp) -> String {
    let value = timestamp.as_offset_date_time();
    format!("{:02}:{:02}", value.hour(), value.minute())
}

fn format_usage(usage: &Usage) -> String {
    let mut parts = Vec::new();

    if let Some(input) = usage.input_tokens {
        parts.push(format!("{input} in"));
    }

    if let Some(output) = usage.output_tokens {
        parts.push(format!("{output} out"));
    }

    if let Some(total) = usage.total_tokens {
        parts.push(format!("{total} total"));
    }

    if let Some(cost) = usage.cost_usd {
        parts.push(crate::providers::format_usd(Some(cost)));
    }

    parts.join(" · ")
}

fn format_latency(ms: u64) -> String {
    if ms >= 1_000 {
        format!("{:.1} s", ms as f64 / 1_000.0)
    } else {
        format!("{ms} ms")
    }
}

fn format_first_token(ms: u64) -> String {
    format!("first token {}", format_latency(ms))
}

fn status_label(status: MessageStatus, is_interrupted: bool, has_content: bool) -> Option<String> {
    if is_interrupted {
        return Some("Interrupted. The app closed before this response finished.".to_owned());
    }

    match status {
        MessageStatus::Cancelled => {
            Some("Response cancelled. Partial content is preserved.".to_owned())
        }
        MessageStatus::Complete if !has_content => Some("No content.".to_owned()),
        _ => None,
    }
}

fn error_view(error: &ChatError) -> MessageErrorView {
    let copy = describe_chat_error(error);

    MessageErrorView {
        title: copy.title,
        message: copy.message,
        details: copy.details,
    }
}
