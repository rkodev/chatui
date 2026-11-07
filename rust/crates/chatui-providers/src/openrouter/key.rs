//! OpenRouter key introspection (`GET /key`).
//!
//! The key is sent only in the `Authorization: Bearer` header. It is never
//! placed in a URL, and the mapped metadata contains no secret material.

use reqwest::header::{AUTHORIZATION, RETRY_AFTER};
use reqwest::{Method, Url};
use serde::{Deserialize, Serialize};
use serde_json::Value;
use thiserror::Error;

use crate::error::{NetworkError, parse_retry_after};
use crate::http::{HttpClient, SecretHeader};
use crate::openrouter::catalog::usd_amount;
use crate::url_policy::{UrlPolicyError, join_url};

/// An OpenRouter request or response error.
#[derive(Debug, Clone, Error)]
pub enum OpenRouterError {
    /// A transport or HTTP status failure.
    #[error(transparent)]
    Network(#[from] NetworkError),
    /// The endpoint URL failed the shared URL policy.
    #[error(transparent)]
    Url(#[from] UrlPolicyError),
    /// The response body was not valid JSON or did not match the expected
    /// envelope.
    #[error("OpenRouter response could not be parsed: {0}")]
    Parse(String),
    /// A key was required but not supplied.
    #[error("An OpenRouter API key is required.")]
    MissingKey,
}

/// Non-secret OpenRouter key metadata from `GET /key`.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ProviderKeyInfo {
    /// Key label.
    pub label: Option<String>,
    /// Cumulative credits used in USD.
    pub usage_usd: Option<f64>,
    /// Credit limit in USD, or `None` when the key has no limit.
    pub limit_usd: Option<f64>,
    /// Remaining credit in USD, or `None` when not reported.
    pub limit_remaining_usd: Option<f64>,
    /// Whether the key is on the free tier.
    pub is_free_tier: Option<bool>,
    /// Rate-limit metadata for display only.
    pub rate_limit: Option<Value>,
}

/// Map an OpenRouter `/key` envelope to normalized key metadata.
pub fn map_key(payload: &Value) -> Result<ProviderKeyInfo, OpenRouterError> {
    let data = payload
        .get("data")
        .and_then(Value::as_object)
        .ok_or_else(|| {
            OpenRouterError::Parse("OpenRouter /key response must contain a data object".to_owned())
        })?;

    Ok(ProviderKeyInfo {
        label: data.get("label").and_then(Value::as_str).map(str::to_owned),
        usage_usd: data.get("usage").and_then(usd_amount),
        limit_usd: data.get("limit").and_then(usd_amount),
        limit_remaining_usd: data.get("limit_remaining").and_then(usd_amount),
        is_free_tier: data.get("is_free_tier").and_then(Value::as_bool),
        rate_limit: data
            .get("rate_limit")
            .filter(|value| value.is_object())
            .cloned(),
    })
}

/// Validate an OpenRouter key with `GET /key`.
///
/// The key is sent only in the `Authorization` header. An empty key is rejected
/// before any request is made.
pub async fn validate_key(
    client: &HttpClient,
    base_url: &str,
    api_key: &str,
) -> Result<ProviderKeyInfo, OpenRouterError> {
    if api_key.trim().is_empty() {
        return Err(OpenRouterError::MissingKey);
    }

    let url = join_url(base_url, "key")?;
    let payload = get_json(client, &url, Some(api_key)).await?;
    map_key(&payload)
}

/// Send a GET request and parse a JSON response.
///
/// `api_key` is optional so the public `/models` endpoint can reuse this
/// helper without sending an authorization header.
pub(crate) async fn get_json(
    client: &HttpClient,
    url: &Url,
    api_key: Option<&str>,
) -> Result<Value, OpenRouterError> {
    let prepared = client.prepare(Method::GET, url)?;
    let mut builder = prepared.into_builder();

    if let Some(api_key) = api_key {
        let header = SecretHeader::new(AUTHORIZATION, &format!("Bearer {api_key}"))?;
        builder = header.apply(builder);
    }

    let response = client.send(builder).await?;
    let status = response.status();
    if !status.is_success() {
        let retry_after = response
            .headers()
            .get(RETRY_AFTER)
            .and_then(|value| value.to_str().ok())
            .and_then(parse_retry_after);
        return Err(NetworkError::from_status(status.as_u16(), retry_after).into());
    }

    let bytes = response.bytes().await.map_err(NetworkError::from_reqwest)?;
    serde_json::from_slice(&bytes)
        .map_err(|error| OpenRouterError::Parse(format!("invalid JSON: {error}")))
}
