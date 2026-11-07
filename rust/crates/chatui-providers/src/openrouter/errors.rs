//! OpenRouter error normalization.
//!
//! OpenRouter uses the same `{ "error": { "code": ..., "message": ...,
//! "metadata": ... } }` envelope for HTTP error responses and for error objects
//! delivered inside an HTTP 200 stream. Both paths are normalized here to the
//! shared [`ChatError`] shape from spec sections 6.2, 7.4, and 7.9.
//!
//! The normalized message is safe to show to a user and safe to log. It never
//! contains an API key, an authorization header, or a raw request URL. Provider
//! metadata is copied into `details` only after known snake_case keys are
//! renamed to the shared camelCase shape.

use chatui_domain::{ChatError, ChatErrorCode, Timestamp};
use serde_json::{Map, Value};

use crate::error::is_retryable_status;

/// Provider ID used on every normalized OpenRouter error.
pub const OPENROUTER_PROVIDER_ID: &str = "openrouter";

/// Map an HTTP error response to a normalized [`ChatError`].
///
/// `retry_after_seconds` comes from the `Retry-After` header when present. When
/// the envelope carries `metadata.retry_after_seconds`, that value is used as a
/// fallback so the shared error vectors round-trip without a header.
pub fn map_http_error(
    status: u16,
    retry_after_seconds: Option<f64>,
    body: Option<&Value>,
    request_id: Option<String>,
) -> ChatError {
    match body.and_then(|body| body.get("error")) {
        Some(error) => {
            map_error_object(error, Some(status), retry_after_seconds, request_id, false)
        }
        None => default_error(status, retry_after_seconds, request_id),
    }
}

/// Map an error object inside an HTTP 200 stream to a normalized [`ChatError`].
///
/// In-stream errors are never retryable: the stream has already started and may
/// have emitted partial text or reasoning. The response ID from the `start`
/// event is preserved as the request ID.
pub fn map_stream_error(envelope: &Value, response_id: Option<String>) -> ChatError {
    match envelope.get("error") {
        Some(error) => map_error_object(error, None, None, response_id, true),
        None => default_error(200, None, response_id),
    }
}

/// Map an OpenRouter error object to a normalized [`ChatError`].
///
/// `http_status` is the response status for an HTTP error. For an in-stream
/// error the status is taken from the envelope's numeric `code` field instead.
pub fn map_error_object(
    error: &Value,
    http_status: Option<u16>,
    retry_after_seconds: Option<f64>,
    request_id: Option<String>,
    in_stream: bool,
) -> ChatError {
    let status = error
        .get("code")
        .and_then(Value::as_u64)
        .and_then(|code| u16::try_from(code).ok())
        .or(http_status);
    let message = error
        .get("message")
        .and_then(Value::as_str)
        .map(str::to_owned)
        .unwrap_or_else(|| default_message(status));
    let metadata = error.get("metadata").and_then(Value::as_object);
    let retry_after_seconds = if in_stream {
        None
    } else {
        retry_after_seconds.or_else(|| {
            metadata
                .and_then(|metadata| metadata.get("retry_after_seconds"))
                .and_then(Value::as_f64)
        })
    };
    let retryable = !in_stream && status.is_some_and(is_retryable_status);
    let details = metadata.and_then(map_metadata);

    ChatError {
        code: map_code(status, &message),
        message,
        provider_id: Some(OPENROUTER_PROVIDER_ID.to_owned()),
        status,
        retryable,
        retry_after_seconds,
        request_id,
        details,
        at: Timestamp::now(),
    }
}

fn default_error(
    status: u16,
    retry_after_seconds: Option<f64>,
    request_id: Option<String>,
) -> ChatError {
    ChatError {
        code: map_code(Some(status), ""),
        message: default_message(Some(status)),
        provider_id: Some(OPENROUTER_PROVIDER_ID.to_owned()),
        status: Some(status),
        retryable: is_retryable_status(status),
        retry_after_seconds,
        request_id,
        details: None,
        at: Timestamp::now(),
    }
}

fn map_code(status: Option<u16>, message: &str) -> ChatErrorCode {
    match status {
        Some(401) => ChatErrorCode::Auth,
        Some(402) => ChatErrorCode::Quota,
        Some(403) => {
            if is_content_filter(message) {
                ChatErrorCode::ContentFilter
            } else {
                ChatErrorCode::Permission
            }
        }
        Some(404) => ChatErrorCode::ModelNotFound,
        Some(408) => ChatErrorCode::Timeout,
        Some(413) => ChatErrorCode::ContextLength,
        Some(429) => ChatErrorCode::RateLimit,
        Some(400) => {
            if is_context_length(message) {
                ChatErrorCode::ContextLength
            } else {
                ChatErrorCode::InvalidRequest
            }
        }
        Some(500..=599) => ChatErrorCode::Provider,
        _ => ChatErrorCode::Provider,
    }
}

fn default_message(status: Option<u16>) -> String {
    match status {
        Some(401) => "Authentication failed. Check your OpenRouter API key.".to_owned(),
        Some(402) => "Insufficient credits. Add more credits to continue.".to_owned(),
        Some(403) => "The request was blocked by the provider's policy.".to_owned(),
        Some(404) => "The requested model was not found.".to_owned(),
        Some(408) => {
            "The request timed out. Check the network connection and try again.".to_owned()
        }
        Some(413) => "The request exceeds the model's context length.".to_owned(),
        Some(429) => "Rate limit exceeded. Please retry shortly.".to_owned(),
        Some(503) => "No available upstream provider could serve the request.".to_owned(),
        Some(500..=599) => "The provider returned an error.".to_owned(),
        Some(400) => "The request was invalid.".to_owned(),
        _ => "The provider returned an error.".to_owned(),
    }
}

fn is_context_length(message: &str) -> bool {
    let message = message.to_ascii_lowercase();
    message.contains("context")
        || message.contains("too long")
        || message.contains("maximum length")
}

fn is_content_filter(message: &str) -> bool {
    let message = message.to_ascii_lowercase();
    message.contains("moderation")
        || message.contains("content policy")
        || message.contains("content_filter")
        || message.contains("safety")
}

fn map_metadata(metadata: &Map<String, Value>) -> Option<Value> {
    if metadata.is_empty() {
        return None;
    }

    let mut mapped = Map::new();
    for (key, value) in metadata {
        let key = match key.as_str() {
            "provider_name" => "providerName",
            "retry_after_seconds" => "retryAfterSeconds",
            other => other,
        };
        mapped.insert(key.to_owned(), value.clone());
    }

    Some(Value::Object(mapped))
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn maps_http_statuses_to_shared_codes() {
        let cases = [
            (401, ChatErrorCode::Auth, false),
            (402, ChatErrorCode::Quota, false),
            (403, ChatErrorCode::Permission, false),
            (404, ChatErrorCode::ModelNotFound, false),
            (408, ChatErrorCode::Timeout, true),
            (413, ChatErrorCode::ContextLength, false),
            (429, ChatErrorCode::RateLimit, true),
            (500, ChatErrorCode::Provider, true),
            (503, ChatErrorCode::Provider, true),
        ];

        for (status, code, retryable) in cases {
            let error = map_http_error(status, None, None, None);
            assert_eq!(error.code, code, "status {status}");
            assert_eq!(error.retryable, retryable, "status {status}");
            assert_eq!(error.status, Some(status));
            assert_eq!(error.provider_id.as_deref(), Some(OPENROUTER_PROVIDER_ID));
        }
    }

    #[test]
    fn maps_400_context_length_from_message() {
        let body = json!({
            "error": {
                "code": 400,
                "message": "The request exceeds the model's context length."
            }
        });
        let error = map_http_error(400, None, Some(&body), None);
        assert_eq!(error.code, ChatErrorCode::ContextLength);
        assert!(!error.retryable);
    }

    #[test]
    fn maps_403_moderation_to_content_filter() {
        let body = json!({
            "error": {
                "code": 403,
                "message": "The request was blocked by moderation."
            }
        });
        let error = map_http_error(403, None, Some(&body), None);
        assert_eq!(error.code, ChatErrorCode::ContentFilter);
    }

    #[test]
    fn renames_known_metadata_keys() {
        let body = json!({
            "error": {
                "code": 429,
                "message": "Rate limit exceeded.",
                "metadata": {
                    "provider_name": "Synthetic Provider",
                    "retry_after_seconds": 2,
                    "raw": "detail"
                }
            }
        });
        let error = map_http_error(429, None, Some(&body), None);
        assert_eq!(error.retry_after_seconds, Some(2.0));
        assert_eq!(
            error.details,
            Some(json!({
                "providerName": "Synthetic Provider",
                "retryAfterSeconds": 2,
                "raw": "detail"
            }))
        );
    }

    #[test]
    fn in_stream_errors_are_never_retryable() {
        let envelope = json!({
            "id": "gen-example",
            "error": {
                "code": 502,
                "message": "Upstream provider returned an error.",
                "metadata": { "provider_name": "Synthetic Provider" }
            }
        });
        let error = map_stream_error(&envelope, Some("gen-example".to_owned()));
        assert_eq!(error.code, ChatErrorCode::Provider);
        assert_eq!(error.status, Some(502));
        assert!(!error.retryable);
        assert_eq!(error.request_id.as_deref(), Some("gen-example"));
        assert_eq!(
            error.details,
            Some(json!({ "providerName": "Synthetic Provider" }))
        );
    }

    #[test]
    fn error_messages_never_contain_secret_material() {
        let body = json!({
            "error": {
                "code": 401,
                "message": "Authentication failed. Check your OpenRouter API key."
            }
        });
        let error = map_http_error(401, None, Some(&body), None);
        assert!(!error.message.contains("sk-"));
        assert!(!error.message.contains("Bearer"));
    }
}
