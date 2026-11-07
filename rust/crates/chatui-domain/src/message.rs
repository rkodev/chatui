//! Message, content, and usage entities from spec section 6.2.

use serde::{Deserialize, Serialize};

use crate::errors::ChatError;
use crate::time::Timestamp;

/// Message author role from spec section 6.2.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum MessageRole {
    /// System instruction.
    System,
    /// User message.
    User,
    /// Assistant message.
    Assistant,
    /// Tool result message.
    Tool,
}

/// Message lifecycle status from spec section 6.2.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum MessageStatus {
    /// Waiting to be sent.
    Pending,
    /// Currently streaming.
    Streaming,
    /// Finished successfully.
    Complete,
    /// Cancelled by the user.
    Cancelled,
    /// Finished with an error.
    Error,
}

/// Normalized finish reason from spec section 7.2.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum FinishReason {
    /// Natural stop.
    Stop,
    /// Output token limit reached.
    Length,
    /// Content filter stopped generation.
    ContentFilter,
    /// The model requested tool calls.
    ToolCalls,
    /// The user cancelled generation.
    Cancelled,
    /// Generation failed.
    Error,
    /// Unknown finish reason.
    Unknown,
}

/// Content part union from spec section 6.2.
///
/// Phase 1 renders and persists text, image, file, and reasoning. Tool parts are
/// reserved and may be ignored by UI code but must round-trip through export.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(tag = "type", rename_all = "kebab-case")]
pub enum ContentPart {
    /// Plain text.
    Text {
        /// Text content.
        text: String,
    },
    /// Image attachment reference.
    Image {
        /// Attachment ID.
        #[serde(rename = "attachmentId")]
        attachment_id: String,
        /// MIME type.
        #[serde(rename = "mimeType")]
        mime_type: String,
        /// Alternative text.
        alt: String,
    },
    /// File attachment reference.
    File {
        /// Attachment ID.
        #[serde(rename = "attachmentId")]
        attachment_id: String,
        /// MIME type.
        #[serde(rename = "mimeType")]
        mime_type: String,
        /// File name.
        name: String,
    },
    /// Reasoning text.
    Reasoning {
        /// Reasoning content.
        text: String,
    },
    /// Tool call requested by the model.
    ToolCall {
        /// Tool call ID.
        id: String,
        /// Tool name.
        name: String,
        /// JSON-encoded arguments.
        #[serde(rename = "argumentsJson")]
        arguments_json: String,
    },
    /// Tool result supplied to the model.
    ToolResult {
        /// ID of the tool call this result belongs to.
        #[serde(rename = "toolCallId")]
        tool_call_id: String,
        /// Tool result content.
        content: String,
    },
}

/// Token and cost usage from spec section 6.2.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Usage {
    /// Input tokens, or `null` when unknown.
    pub input_tokens: Option<u32>,
    /// Output tokens, or `null` when unknown.
    pub output_tokens: Option<u32>,
    /// Total tokens, or `null` when unknown.
    pub total_tokens: Option<u32>,
    /// Cost in USD, or `null` when unknown.
    pub cost_usd: Option<f64>,
    /// Whether the usage values are estimated.
    pub estimated: bool,
}

/// Message entity from spec section 6.2.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Message {
    /// Local message ID.
    pub id: String,
    /// Owning conversation ID.
    pub conversation_id: String,
    /// Monotonic sequence within the conversation.
    pub sequence: u64,
    /// Message author role.
    pub role: MessageRole,
    /// Message content parts.
    pub parts: Vec<ContentPart>,
    /// Message lifecycle status.
    pub status: MessageStatus,
    /// Provider ID snapshot.
    pub provider_id: Option<String>,
    /// Model ID snapshot.
    pub model_id: Option<String>,
    /// Model label snapshot.
    pub model_label: Option<String>,
    /// Token and cost usage, or `null` when unavailable.
    pub usage: Option<Usage>,
    /// Finish reason, or `null` while pending or streaming.
    pub finish_reason: Option<FinishReason>,
    /// Error, or `null` when the message is healthy.
    pub error: Option<ChatError>,
    /// Total latency in milliseconds, or `null` when unavailable.
    pub latency_ms: Option<u64>,
    /// Time to first token in milliseconds, or `null` when unavailable.
    pub first_token_ms: Option<u64>,
    /// Creation timestamp.
    pub created_at: Timestamp,
    /// Last update timestamp.
    pub updated_at: Timestamp,
    /// Soft-deletion timestamp, or `null` when not deleted.
    pub deleted_at: Option<Timestamp>,
}

#[cfg(test)]
mod tests {
    use super::*;

    fn fixed() -> Timestamp {
        Timestamp::parse("2026-11-04T12:00:00.000Z").expect("valid timestamp")
    }

    #[test]
    fn enum_values_match_shared_json() {
        assert_eq!(
            serde_json::to_string(&MessageRole::Assistant).unwrap(),
            "\"assistant\""
        );
        assert_eq!(
            serde_json::to_string(&MessageStatus::Streaming).unwrap(),
            "\"streaming\""
        );
        assert_eq!(
            serde_json::to_string(&FinishReason::ContentFilter).unwrap(),
            "\"content_filter\""
        );
        assert_eq!(
            serde_json::to_string(&FinishReason::ToolCalls).unwrap(),
            "\"tool_calls\""
        );
    }

    #[test]
    fn content_parts_round_trip_with_exact_field_names() {
        let parts = vec![
            ContentPart::Text {
                text: "hello".to_owned(),
            },
            ContentPart::Image {
                attachment_id: "attachment-1".to_owned(),
                mime_type: "image/png".to_owned(),
                alt: "A pixel".to_owned(),
            },
            ContentPart::File {
                attachment_id: "attachment-2".to_owned(),
                mime_type: "text/plain".to_owned(),
                name: "notes.txt".to_owned(),
            },
            ContentPart::Reasoning {
                text: "thinking".to_owned(),
            },
            ContentPart::ToolCall {
                id: "call-1".to_owned(),
                name: "lookup".to_owned(),
                arguments_json: "{\"q\":\"rust\"}".to_owned(),
            },
            ContentPart::ToolResult {
                tool_call_id: "call-1".to_owned(),
                content: "result".to_owned(),
            },
        ];

        let json = serde_json::to_value(&parts).expect("serialize parts");
        assert_eq!(
            json[0],
            serde_json::json!({ "type": "text", "text": "hello" })
        );
        assert_eq!(
            json[1],
            serde_json::json!({
                "type": "image",
                "attachmentId": "attachment-1",
                "mimeType": "image/png",
                "alt": "A pixel"
            })
        );
        assert_eq!(
            json[2],
            serde_json::json!({
                "type": "file",
                "attachmentId": "attachment-2",
                "mimeType": "text/plain",
                "name": "notes.txt"
            })
        );
        assert_eq!(
            json[3],
            serde_json::json!({ "type": "reasoning", "text": "thinking" })
        );
        assert_eq!(
            json[4],
            serde_json::json!({
                "type": "tool-call",
                "id": "call-1",
                "name": "lookup",
                "argumentsJson": "{\"q\":\"rust\"}"
            })
        );
        assert_eq!(
            json[5],
            serde_json::json!({
                "type": "tool-result",
                "toolCallId": "call-1",
                "content": "result"
            })
        );

        let parsed: Vec<ContentPart> = serde_json::from_value(json).expect("deserialize parts");
        assert_eq!(parsed, parts);
    }

    #[test]
    fn usage_round_trips_with_nulls() {
        let usage = Usage {
            input_tokens: None,
            output_tokens: None,
            total_tokens: None,
            cost_usd: None,
            estimated: false,
        };

        let json = serde_json::to_value(&usage).expect("serialize usage");
        assert_eq!(json["inputTokens"], serde_json::Value::Null);
        assert_eq!(json["costUsd"], serde_json::Value::Null);

        let parsed: Usage = serde_json::from_value(json).expect("deserialize usage");
        assert_eq!(parsed, usage);
    }

    #[test]
    fn message_round_trips_with_nulls_and_empty_parts() {
        let message = Message {
            id: "message-1".to_owned(),
            conversation_id: "conversation-1".to_owned(),
            sequence: 0,
            role: MessageRole::User,
            parts: Vec::new(),
            status: MessageStatus::Complete,
            provider_id: None,
            model_id: None,
            model_label: None,
            usage: None,
            finish_reason: None,
            error: None,
            latency_ms: None,
            first_token_ms: None,
            created_at: fixed(),
            updated_at: fixed(),
            deleted_at: None,
        };

        let json = serde_json::to_value(&message).expect("serialize message");
        assert_eq!(json["parts"], serde_json::json!([]));
        assert_eq!(json["providerId"], serde_json::Value::Null);
        assert_eq!(json["deletedAt"], serde_json::Value::Null);

        let parsed: Message = serde_json::from_value(json).expect("deserialize message");
        assert_eq!(parsed, message);
    }
}
