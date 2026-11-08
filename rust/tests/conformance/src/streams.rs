//! Raw stream fixtures and expected normalized stream vectors.

use chatui_domain::{ChatErrorCode, FinishReason, Usage};
use serde::{Deserialize, Serialize};
use serde_json::Value;

/// A raw provider stream fixture under `contracts/fixtures/streams/`.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct StreamFixture {
    /// File name, such as `openai-text.sse`.
    pub name: String,
    /// Raw fixture contents.
    pub contents: String,
}

/// The shared expected-vector shape for a stream.
///
/// This mirrors `contracts/fixtures/expected/README.md`. It intentionally does
/// not reuse [`chatui_domain::StreamEvent`] because expected error events omit
/// the runtime `at` timestamp that the domain error carries.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct StreamExpectation {
    /// Raw fixture file name under `contracts/fixtures/streams/`.
    pub source: String,
    /// Ordered normalized events.
    pub events: Vec<ExpectedStreamEvent>,
    /// Concatenated assistant text.
    pub text: String,
    /// Concatenated reasoning text.
    pub reasoning: String,
    /// Whether a retry is allowed after this stream.
    pub retry_allowed: bool,
    /// Expected-vector file name. Set by the loader.
    #[serde(skip)]
    pub file: String,
}

impl StreamExpectation {
    /// Concatenate every `text-delta` event.
    pub fn concatenated_text(&self) -> String {
        self.events
            .iter()
            .filter_map(|event| match event {
                ExpectedStreamEvent::TextDelta { text } => Some(text.as_str()),
                _ => None,
            })
            .collect()
    }

    /// Concatenate every `reasoning-delta` event.
    pub fn concatenated_reasoning(&self) -> String {
        self.events
            .iter()
            .filter_map(|event| match event {
                ExpectedStreamEvent::ReasoningDelta { text } => Some(text.as_str()),
                _ => None,
            })
            .collect()
    }
}

/// A normalized event in an expected stream vector.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(tag = "type", rename_all = "kebab-case")]
pub enum ExpectedStreamEvent {
    /// Stream started.
    Start {
        /// Provider response ID, or `null` when unavailable.
        #[serde(rename = "responseId", default)]
        response_id: Option<String>,
    },
    /// Text delta.
    TextDelta {
        /// Appended text.
        text: String,
    },
    /// Reasoning delta.
    ReasoningDelta {
        /// Appended reasoning text.
        text: String,
    },
    /// Tool call delta.
    ToolCallDelta {
        /// Tool call index.
        index: u32,
        /// Tool call ID, or `null` until the provider supplies it.
        #[serde(default)]
        id: Option<String>,
        /// Tool name, or `null` until the provider supplies it.
        #[serde(default)]
        name: Option<String>,
        /// Appended JSON arguments.
        #[serde(rename = "argumentsDelta")]
        arguments_delta: String,
    },
    /// Usage update.
    Usage {
        /// Token and cost usage.
        usage: Usage,
    },
    /// Stream finished.
    Finish {
        /// Finish reason.
        reason: FinishReason,
    },
    /// Stream error.
    Error {
        /// Normalized error without the runtime timestamp.
        error: ExpectedChatError,
    },
}

/// A normalized error in an expected vector.
///
/// The shared vectors omit `at`; adapters attach the runtime timestamp when
/// they construct a [`chatui_domain::ChatError`].
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ExpectedChatError {
    /// Normalized error code.
    pub code: ChatErrorCode,
    /// Actionable user-facing message.
    pub message: String,
    /// Provider ID, or `null` when not provider-specific.
    pub provider_id: Option<String>,
    /// HTTP status, or `null` when not applicable.
    pub status: Option<u16>,
    /// Whether the request may be retried.
    pub retryable: bool,
    /// Retry delay in seconds, or `null` when not provided.
    pub retry_after_seconds: Option<f64>,
    /// Provider request ID, or `null` when not provided.
    pub request_id: Option<String>,
    /// Additional non-secret details, or `null`.
    pub details: Option<Value>,
}
