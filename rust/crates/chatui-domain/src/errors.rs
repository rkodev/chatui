//! Normalized chat errors from spec section 6.2.

use serde::{Deserialize, Serialize};
use serde_json::Value;

use crate::time::Timestamp;

/// Normalized error codes from spec section 6.2.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum ChatErrorCode {
    /// Authentication failed.
    Auth,
    /// Permission denied.
    Permission,
    /// Rate limited.
    RateLimit,
    /// Quota or billing limit reached.
    Quota,
    /// Model not found.
    ModelNotFound,
    /// Context length exceeded.
    ContextLength,
    /// Content filter blocked the request.
    ContentFilter,
    /// Invalid request.
    InvalidRequest,
    /// Network failure.
    Network,
    /// Browser CORS failure.
    Cors,
    /// Request timed out.
    Timeout,
    /// Request cancelled.
    Cancelled,
    /// Provider error.
    Provider,
    /// Response parse failure.
    Parse,
    /// Storage failure.
    Storage,
    /// Unknown error.
    Unknown,
}

/// Normalized chat error from spec section 6.2.
///
/// User-facing messages must be actionable and must not include keys or raw
/// headers.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ChatError {
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
    /// Error timestamp.
    pub at: Timestamp,
}

#[cfg(test)]
mod tests {
    use super::*;

    fn fixed() -> Timestamp {
        Timestamp::parse("2026-11-04T12:00:00.000Z").expect("valid timestamp")
    }

    #[test]
    fn every_error_code_matches_shared_json() {
        let cases = [
            (ChatErrorCode::Auth, "auth"),
            (ChatErrorCode::Permission, "permission"),
            (ChatErrorCode::RateLimit, "rate_limit"),
            (ChatErrorCode::Quota, "quota"),
            (ChatErrorCode::ModelNotFound, "model_not_found"),
            (ChatErrorCode::ContextLength, "context_length"),
            (ChatErrorCode::ContentFilter, "content_filter"),
            (ChatErrorCode::InvalidRequest, "invalid_request"),
            (ChatErrorCode::Network, "network"),
            (ChatErrorCode::Cors, "cors"),
            (ChatErrorCode::Timeout, "timeout"),
            (ChatErrorCode::Cancelled, "cancelled"),
            (ChatErrorCode::Provider, "provider"),
            (ChatErrorCode::Parse, "parse"),
            (ChatErrorCode::Storage, "storage"),
            (ChatErrorCode::Unknown, "unknown"),
        ];

        for (code, expected) in cases {
            assert_eq!(
                serde_json::to_string(&code).expect("serialize code"),
                format!("\"{expected}\"")
            );
        }
    }

    #[test]
    fn round_trips_with_nulls_and_details() {
        let error = ChatError {
            code: ChatErrorCode::RateLimit,
            message: "Too many requests. Try again shortly.".to_owned(),
            provider_id: None,
            status: Some(429),
            retryable: true,
            retry_after_seconds: Some(2.5),
            request_id: None,
            details: Some(serde_json::json!({
                "unknown": { "nested": [true, null] }
            })),
            at: fixed(),
        };

        let json = serde_json::to_value(&error).expect("serialize error");
        assert_eq!(json["providerId"], Value::Null);
        assert_eq!(json["requestId"], Value::Null);
        assert_eq!(json["retryAfterSeconds"], 2.5);
        assert_eq!(json["details"]["unknown"]["nested"][1], Value::Null);

        let parsed: ChatError = serde_json::from_value(json).expect("deserialize error");
        assert_eq!(parsed, error);
    }
}
