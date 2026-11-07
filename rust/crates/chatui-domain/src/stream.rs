//! Normalized stream events from spec section 7.2.

use serde::{Deserialize, Serialize};

use crate::errors::ChatError;
use crate::message::{FinishReason, Usage};

/// Normalized stream event union from spec section 7.2.
///
/// The UI reducer appends deltas to a streaming buffer and only updates the
/// persisted message at bounded intervals.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(tag = "type", rename_all = "kebab-case")]
pub enum StreamEvent {
    /// Stream started.
    Start {
        /// Provider response ID, or `null` when unavailable.
        #[serde(rename = "responseId")]
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
        id: Option<String>,
        /// Tool name, or `null` until the provider supplies it.
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
        /// Normalized error.
        error: ChatError,
    },
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::errors::ChatErrorCode;
    use crate::time::Timestamp;

    fn fixed() -> Timestamp {
        Timestamp::parse("2026-11-04T12:00:00.000Z").expect("valid timestamp")
    }

    #[test]
    fn every_event_round_trips_with_exact_json() {
        let events = vec![
            (
                StreamEvent::Start { response_id: None },
                serde_json::json!({ "type": "start", "responseId": null }),
            ),
            (
                StreamEvent::TextDelta {
                    text: "hello".to_owned(),
                },
                serde_json::json!({ "type": "text-delta", "text": "hello" }),
            ),
            (
                StreamEvent::ReasoningDelta {
                    text: "thinking".to_owned(),
                },
                serde_json::json!({ "type": "reasoning-delta", "text": "thinking" }),
            ),
            (
                StreamEvent::ToolCallDelta {
                    index: 0,
                    id: None,
                    name: Some("lookup".to_owned()),
                    arguments_delta: "{\"q\":".to_owned(),
                },
                serde_json::json!({
                    "type": "tool-call-delta",
                    "index": 0,
                    "id": null,
                    "name": "lookup",
                    "argumentsDelta": "{\"q\":"
                }),
            ),
            (
                StreamEvent::Usage {
                    usage: Usage {
                        input_tokens: Some(8),
                        output_tokens: Some(5),
                        total_tokens: Some(13),
                        cost_usd: None,
                        estimated: false,
                    },
                },
                serde_json::json!({
                    "type": "usage",
                    "usage": {
                        "inputTokens": 8,
                        "outputTokens": 5,
                        "totalTokens": 13,
                        "costUsd": null,
                        "estimated": false
                    }
                }),
            ),
            (
                StreamEvent::Finish {
                    reason: FinishReason::Stop,
                },
                serde_json::json!({ "type": "finish", "reason": "stop" }),
            ),
            (
                StreamEvent::Error {
                    error: ChatError {
                        code: ChatErrorCode::Network,
                        message: "Network unavailable.".to_owned(),
                        provider_id: None,
                        status: None,
                        retryable: true,
                        retry_after_seconds: None,
                        request_id: None,
                        details: None,
                        at: fixed(),
                    },
                },
                serde_json::json!({
                    "type": "error",
                    "error": {
                        "code": "network",
                        "message": "Network unavailable.",
                        "providerId": null,
                        "status": null,
                        "retryable": true,
                        "retryAfterSeconds": null,
                        "requestId": null,
                        "details": null,
                        "at": "2026-11-04T12:00:00.000Z"
                    }
                }),
            ),
        ];

        for (event, expected) in events {
            let json = serde_json::to_value(&event).expect("serialize event");
            assert_eq!(json, expected);
            let parsed: StreamEvent = serde_json::from_value(json).expect("deserialize event");
            assert_eq!(parsed, event);
        }
    }
}
