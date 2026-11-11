//! Shared helpers for the additional storage adapters.
//!
//! These helpers only move opaque bytes and metadata. They never inspect or
//! transform object bodies, so adapters cannot accidentally treat a body as
//! plaintext.

use base64::Engine;
use base64::engine::general_purpose::{STANDARD, URL_SAFE_NO_PAD};
use reqwest::Response;

use crate::storage::StorageError;

/// Convert a non-success response into a typed error, preserving the status.
pub(crate) async fn ensure_ok(response: Response, action: &str) -> Result<Response, StorageError> {
    if response.status().is_success() {
        return Ok(response);
    }
    let status = response.status().as_u16();
    let detail = response.text().await.unwrap_or_default();
    let detail: String = detail.trim().chars().take(500).collect();
    let suffix = if detail.is_empty() {
        String::new()
    } else {
        format!(": {detail}")
    };
    Err(StorageError::from_status(
        status,
        format!("{action} failed with HTTP {status}{suffix}"),
    ))
}

/// Read a response header as an owned string.
pub(crate) fn header(response: &Response, name: &str) -> Option<String> {
    response
        .headers()
        .get(name)
        .and_then(|value| value.to_str().ok())
        .filter(|value| !value.is_empty())
        .map(str::to_string)
}

/// Trim leading and trailing slashes from a configured prefix.
pub(crate) fn normalize_prefix(prefix: Option<&str>) -> String {
    prefix.unwrap_or_default().trim_matches('/').to_string()
}

/// Join the configured prefix and a logical key.
pub(crate) fn full_key(prefix: &str, key: &str) -> Result<String, StorageError> {
    let normalized = key.trim_start_matches('/');
    if normalized.is_empty() {
        return Err(StorageError::server("Object key must not be empty"));
    }
    if prefix.is_empty() {
        Ok(normalized.to_string())
    } else {
        Ok(format!("{prefix}/{normalized}"))
    }
}

/// Join the configured prefix and an optional listing prefix.
pub(crate) fn full_prefix(prefix: &str, extra: Option<&str>) -> String {
    let extra = extra.unwrap_or_default().trim_start_matches('/');
    if prefix.is_empty() {
        extra.to_string()
    } else if extra.is_empty() {
        format!("{prefix}/")
    } else {
        format!("{prefix}/{extra}")
    }
}

/// Strip the configured prefix from a provider key.
pub(crate) fn logical_key(prefix: &str, actual: &str) -> String {
    if prefix.is_empty() {
        return actual.to_string();
    }
    let prefix_with_slash = format!("{prefix}/");
    actual
        .strip_prefix(&prefix_with_slash)
        .unwrap_or(actual)
        .to_string()
}

/// Standard base64 encoding.
pub(crate) fn base64_encode(bytes: &[u8]) -> String {
    STANDARD.encode(bytes)
}

/// Standard base64 decoding with a typed error.
pub(crate) fn base64_decode(value: &str) -> Result<Vec<u8>, StorageError> {
    STANDARD
        .decode(value.trim())
        .map_err(|error| StorageError::server(format!("invalid base64 value: {error}")))
}

/// URL-safe base64 without padding, used by JWT segments.
pub(crate) fn base64_url_no_pad(bytes: &[u8]) -> String {
    URL_SAFE_NO_PAD.encode(bytes)
}

/// Percent-decode a URL path for provider canonicalization.
pub(crate) fn percent_decode(value: &str) -> String {
    percent_encoding::percent_decode_str(value)
        .decode_utf8_lossy()
        .into_owned()
}
