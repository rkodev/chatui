//! Normalized networking errors.
//!
//! Every provider shares the same transport, so every transport failure is
//! normalized here before it reaches a provider adapter or the UI. The error
//! carries explicit retryability metadata and never includes API keys, raw
//! headers, or full request URLs.

use std::error::Error as StdError;

use chatui_domain::{ChatError, ChatErrorCode, Timestamp};
use thiserror::Error;

/// The transport-level category of a networking failure.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum NetworkErrorKind {
    /// The host name could not be resolved.
    Dns,
    /// The TLS handshake or certificate validation failed.
    Tls,
    /// The request or connection timed out.
    Timeout,
    /// The connection failed or was reset.
    Connection,
    /// The provider returned a non-success HTTP status.
    HttpStatus,
    /// The caller cancelled the request.
    Cancelled,
    /// A redirect was refused, for example because it crossed origins.
    Redirect,
    /// The URL was malformed or used an unsupported scheme.
    InvalidUrl,
    /// A header value could not be constructed.
    InvalidHeader,
    /// Any other transport failure.
    Other,
}

impl NetworkErrorKind {
    /// Return the stable machine-readable name for this kind.
    pub fn as_str(self) -> &'static str {
        match self {
            Self::Dns => "dns",
            Self::Tls => "tls",
            Self::Timeout => "timeout",
            Self::Connection => "connection",
            Self::HttpStatus => "http_status",
            Self::Cancelled => "cancelled",
            Self::Redirect => "redirect",
            Self::InvalidUrl => "invalid_url",
            Self::InvalidHeader => "invalid_header",
            Self::Other => "other",
        }
    }

    /// Return whether a failure of this kind is retryable by default.
    ///
    /// HTTP status retryability depends on the status code and is set by
    /// [`NetworkError::from_status`] instead.
    pub fn is_retryable(self) -> bool {
        matches!(self, Self::Dns | Self::Timeout | Self::Connection)
    }
}

/// A normalized networking error with retryability metadata.
///
/// The message is safe to show to a user and safe to log: it never contains a
/// key, an authorization header, or a full request URL.
#[derive(Debug, Clone, PartialEq, Error)]
#[error("{message}")]
pub struct NetworkError {
    /// Transport-level category.
    pub kind: NetworkErrorKind,
    /// Actionable, secret-free user-facing message.
    pub message: String,
    /// HTTP status when the failure came from a response.
    pub status: Option<u16>,
    /// Whether the request may be retried.
    pub retryable: bool,
    /// Server-provided retry delay in seconds, when present.
    pub retry_after_seconds: Option<f64>,
    /// Provider request identifier, when present.
    pub request_id: Option<String>,
}

impl NetworkError {
    /// Build an error from a kind and a secret-free message.
    pub fn new(kind: NetworkErrorKind, message: impl Into<String>) -> Self {
        Self {
            kind,
            message: message.into(),
            status: None,
            retryable: kind.is_retryable(),
            retry_after_seconds: None,
            request_id: None,
        }
    }

    /// Build an error for a non-success HTTP status.
    pub fn from_status(status: u16, retry_after_seconds: Option<f64>) -> Self {
        Self {
            kind: NetworkErrorKind::HttpStatus,
            message: format!("Provider returned HTTP {status}."),
            status: Some(status),
            retryable: is_retryable_status(status),
            retry_after_seconds,
            request_id: None,
        }
    }

    /// Build a timeout error.
    pub fn timeout() -> Self {
        Self::new(
            NetworkErrorKind::Timeout,
            "The request timed out. Check the network connection and try again.",
        )
    }

    /// Build a cancellation error.
    pub fn cancelled() -> Self {
        Self::new(NetworkErrorKind::Cancelled, "The request was cancelled.")
    }

    /// Build an invalid-header error without echoing the header value.
    pub fn invalid_header(name: &str) -> Self {
        Self::new(
            NetworkErrorKind::InvalidHeader,
            format!("The {name} header value is not valid."),
        )
    }

    /// Attach a server-provided retry delay.
    pub fn with_retry_after(mut self, seconds: Option<f64>) -> Self {
        self.retry_after_seconds = seconds;
        self
    }

    /// Attach a provider request identifier.
    pub fn with_request_id(mut self, request_id: Option<String>) -> Self {
        self.request_id = request_id;
        self
    }

    /// Normalize a `reqwest` transport error.
    ///
    /// The original error text is inspected only to classify DNS and TLS
    /// failures. It is never copied into the user-facing message because it can
    /// contain the request URL.
    pub fn from_reqwest(error: reqwest::Error) -> Self {
        let kind = classify_reqwest_error(&error);
        let status = error.status().map(|status| status.as_u16());
        let message = match kind {
            NetworkErrorKind::Dns => {
                "Could not resolve the provider host. Check the network connection and the base URL."
            }
            NetworkErrorKind::Tls => {
                "The TLS connection failed. Check the provider certificate and the system clock."
            }
            NetworkErrorKind::Timeout => {
                "The request timed out. Check the network connection and try again."
            }
            NetworkErrorKind::Connection => {
                "Could not connect to the provider. Check the network connection and the base URL."
            }
            NetworkErrorKind::HttpStatus => "The provider returned an error response.",
            NetworkErrorKind::Cancelled => "The request was cancelled.",
            NetworkErrorKind::Redirect => {
                "The provider redirected to a different origin, which is not allowed."
            }
            NetworkErrorKind::InvalidUrl => "The provider URL is invalid.",
            NetworkErrorKind::InvalidHeader => "A request header value is invalid.",
            NetworkErrorKind::Other => "The network request failed.",
        };

        Self {
            kind,
            message: message.to_owned(),
            status,
            retryable: kind.is_retryable(),
            retry_after_seconds: None,
            request_id: None,
        }
    }

    /// Map this transport error to the shared [`ChatErrorCode`].
    pub fn chat_error_code(&self) -> ChatErrorCode {
        match self.kind {
            NetworkErrorKind::Dns | NetworkErrorKind::Tls | NetworkErrorKind::Connection => {
                ChatErrorCode::Network
            }
            NetworkErrorKind::Timeout => ChatErrorCode::Timeout,
            NetworkErrorKind::Cancelled => ChatErrorCode::Cancelled,
            NetworkErrorKind::HttpStatus => match self.status {
                Some(401) => ChatErrorCode::Auth,
                Some(402) => ChatErrorCode::Quota,
                Some(403) => ChatErrorCode::Permission,
                Some(404) => ChatErrorCode::ModelNotFound,
                Some(408) => ChatErrorCode::Timeout,
                Some(413) => ChatErrorCode::ContextLength,
                Some(429) => ChatErrorCode::RateLimit,
                Some(500..=599) => ChatErrorCode::Provider,
                _ => ChatErrorCode::Provider,
            },
            NetworkErrorKind::Redirect => ChatErrorCode::Network,
            NetworkErrorKind::InvalidUrl | NetworkErrorKind::InvalidHeader => {
                ChatErrorCode::InvalidRequest
            }
            NetworkErrorKind::Other => ChatErrorCode::Unknown,
        }
    }

    /// Convert this transport error into the shared [`ChatError`] shape.
    pub fn to_chat_error(&self, provider_id: Option<String>) -> ChatError {
        ChatError {
            code: self.chat_error_code(),
            message: self.message.clone(),
            provider_id,
            status: self.status,
            retryable: self.retryable,
            retry_after_seconds: self.retry_after_seconds,
            request_id: self.request_id.clone(),
            details: None,
            at: Timestamp::now(),
        }
    }
}

/// Return whether an HTTP status is retryable under the shared policy.
///
/// The retry loop itself lives in M3.5-05.4; this helper only records the
/// metadata on the normalized error.
pub fn is_retryable_status(status: u16) -> bool {
    matches!(status, 408 | 425 | 429 | 500..=599)
}

/// Parse a `Retry-After` header value expressed as whole seconds.
///
/// HTTP-date values are intentionally not parsed here; the retry task owns the
/// full `Retry-After` behavior. Returning `None` means "no usable delay".
pub fn parse_retry_after(value: &str) -> Option<f64> {
    value
        .trim()
        .parse::<u64>()
        .ok()
        .map(|seconds| seconds as f64)
}

fn classify_reqwest_error(error: &reqwest::Error) -> NetworkErrorKind {
    if error.is_timeout() {
        return NetworkErrorKind::Timeout;
    }
    if error.is_redirect() {
        return NetworkErrorKind::Redirect;
    }
    if error.is_builder() {
        return NetworkErrorKind::InvalidUrl;
    }
    if error.is_connect() {
        return classify_connect_failure(&error_chain(error));
    }
    if error.is_body() || error.is_decode() {
        return NetworkErrorKind::Other;
    }
    if error.is_request() {
        return NetworkErrorKind::Connection;
    }
    NetworkErrorKind::Other
}

fn classify_connect_failure(chain: &str) -> NetworkErrorKind {
    let chain = chain.to_ascii_lowercase();
    const DNS_MARKERS: &[&str] = &[
        "dns",
        "lookup",
        "name or service not known",
        "nodename nor servname",
        "failed to resolve",
        "no such host",
        "temporary failure in name resolution",
    ];
    const TLS_MARKERS: &[&str] = &[
        "tls",
        "certificate",
        "handshake",
        "ssl",
        "invalid peer certificate",
        "unknown issuer",
        "self-signed",
    ];

    if DNS_MARKERS.iter().any(|marker| chain.contains(marker)) {
        NetworkErrorKind::Dns
    } else if TLS_MARKERS.iter().any(|marker| chain.contains(marker)) {
        NetworkErrorKind::Tls
    } else {
        NetworkErrorKind::Connection
    }
}

fn error_chain(error: &dyn StdError) -> String {
    let mut chain = String::new();
    let mut current = Some(error);
    while let Some(error) = current {
        chain.push_str(&error.to_string());
        chain.push('\n');
        current = error.source();
    }
    chain
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn maps_http_statuses_to_codes_and_retryability() {
        let cases = [
            (401, ChatErrorCode::Auth, false),
            (402, ChatErrorCode::Quota, false),
            (403, ChatErrorCode::Permission, false),
            (404, ChatErrorCode::ModelNotFound, false),
            (408, ChatErrorCode::Timeout, true),
            (413, ChatErrorCode::ContextLength, false),
            (425, ChatErrorCode::Provider, true),
            (429, ChatErrorCode::RateLimit, true),
            (500, ChatErrorCode::Provider, true),
            (503, ChatErrorCode::Provider, true),
        ];

        for (status, code, retryable) in cases {
            let error = NetworkError::from_status(status, None);
            assert_eq!(error.chat_error_code(), code, "status {status}");
            assert_eq!(error.retryable, retryable, "status {status}");
            assert_eq!(error.status, Some(status));
        }
    }

    #[test]
    fn parses_retry_after_seconds() {
        assert_eq!(parse_retry_after("3"), Some(3.0));
        assert_eq!(parse_retry_after(" 12 "), Some(12.0));
        assert_eq!(parse_retry_after("0"), Some(0.0));
        assert_eq!(parse_retry_after("Wed, 21 Oct 2015 07:28:00 GMT"), None);
        assert_eq!(parse_retry_after(""), None);
    }

    #[test]
    fn cancelled_is_not_retryable() {
        let error = NetworkError::cancelled();
        assert_eq!(error.kind, NetworkErrorKind::Cancelled);
        assert!(!error.retryable);
        assert_eq!(error.chat_error_code(), ChatErrorCode::Cancelled);
    }

    #[test]
    fn timeout_is_retryable() {
        let error = NetworkError::timeout();
        assert_eq!(error.kind, NetworkErrorKind::Timeout);
        assert!(error.retryable);
        assert_eq!(error.chat_error_code(), ChatErrorCode::Timeout);
    }

    #[test]
    fn classifies_dns_and_tls_failure_text() {
        assert_eq!(
            classify_connect_failure("error sending request: dns error: failed to lookup address"),
            NetworkErrorKind::Dns
        );
        assert_eq!(
            classify_connect_failure(
                "error sending request: invalid peer certificate: UnknownIssuer"
            ),
            NetworkErrorKind::Tls
        );
        assert_eq!(
            classify_connect_failure("error sending request: connection refused"),
            NetworkErrorKind::Connection
        );
    }

    #[test]
    fn chat_error_never_contains_secret_material() {
        let error =
            NetworkError::from_status(429, Some(2.0)).with_request_id(Some("req_123".to_owned()));
        let chat = error.to_chat_error(Some("openrouter".to_owned()));
        assert_eq!(chat.code, ChatErrorCode::RateLimit);
        assert_eq!(chat.provider_id.as_deref(), Some("openrouter"));
        assert_eq!(chat.retry_after_seconds, Some(2.0));
        assert_eq!(chat.request_id.as_deref(), Some("req_123"));
        assert!(!chat.message.contains("sk-"));
    }

    #[test]
    fn invalid_header_error_does_not_echo_value() {
        let error = NetworkError::invalid_header("authorization");
        assert_eq!(error.kind, NetworkErrorKind::InvalidHeader);
        assert!(!error.retryable);
        assert!(error.message.contains("authorization"));
        assert!(!error.message.contains("Bearer"));
    }
}
