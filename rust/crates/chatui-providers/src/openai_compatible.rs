//! Preset-driven OpenAI-compatible transport.
//!
//! OpenRouter is the reference implementation. This module generalizes the
//! same transport for every OpenAI-compatible preset in
//! `contracts/providers.json`: OpenAI, Groq, Mistral, DeepSeek, xAI, Together,
//! Fireworks, LM Studio, llama.cpp, and the user-defined Custom preset.
//!
//! The transport never branches on a provider name. It reads the base URL,
//! authentication style, default and attribution headers, discovery path and
//! format, key-validation path, extra namespace, and passthrough behavior from
//! the contract preset. OpenRouter keeps its curated advanced-JSON behavior and
//! reasoning deduplication through the same preset conversion.
//!
//! Credentials are sent only in headers. A query-parameter auth style is
//! rejected before any request is made, and no error message or URL contains a
//! key.

#![allow(clippy::result_large_err)]

use std::collections::BTreeMap;

use base64::Engine as _;
use base64::engine::general_purpose::STANDARD as BASE64_STANDARD;
use chatui_contracts::{
    KeyValidation, ModelDiscovery, ModelDiscoveryFormat, ProviderAuth, ProviderPreset,
};
use chatui_domain::{
    ChatError, ChatErrorCode, ContentPart, ProviderAuthType, StreamEvent, Timestamp,
};
use reqwest::header::{HeaderName, RETRY_AFTER};
use reqwest::{Method, Url};
use serde_json::{Map, Value, json};
use tokio_util::sync::CancellationToken;

use crate::adapter::ProviderContext;
use crate::error::{NetworkError, NetworkErrorKind, parse_retry_after};
use crate::http::{HttpClient, SecretHeader};
use crate::openai_catalog::{defaults_for_preset, map_models};
use crate::openrouter::catalog::CatalogModel;
use crate::openrouter::errors::map_http_error_for;
use crate::openrouter::key::{ProviderKeyInfo, map_key};
use crate::openrouter::request::{NormalizedChatRequest, PreparedChatRequest, ResolvedAttachment};
use crate::openrouter::stream::{ReasoningStyle, StreamConfig, StreamSummary, stream_chat_with};
use crate::url_policy::{UrlPolicyError, join_url};

/// Fields advanced JSON may never override.
const PROTECTED_FIELDS: [&str; 4] = ["model", "messages", "stream", "stream_options"];

/// How provider-specific advanced JSON is forwarded.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ExtraMode {
    /// Only forward the declared passthrough fields.
    Curated,
    /// Forward every non-protected field.
    All,
}

/// A contract preset converted into the transport's preset shape.
///
/// The conversion carries only data. It never copies provider metadata into
/// Rust source; the [`ProviderPreset`] remains the single source of truth.
#[derive(Debug, Clone, PartialEq)]
pub struct OpenAiCompatiblePreset {
    /// The contract preset.
    pub preset: ProviderPreset,
    /// Namespace under `params.extra` that belongs to this preset.
    pub extra_namespace: String,
    /// Advanced-JSON forwarding mode.
    pub extra_mode: ExtraMode,
    /// Fields forwarded in curated mode.
    pub extra_passthrough_fields: Vec<String>,
    /// Reasoning field style.
    pub reasoning_style: ReasoningStyle,
}

impl OpenAiCompatiblePreset {
    /// Convert a validated contract preset into the transport shape.
    ///
    /// OpenRouter is identified by its discovery format, not by a provider-name
    /// branch. It keeps curated advanced JSON and OpenRouter reasoning
    /// deduplication. Every other OpenAI-compatible preset forwards safe extra
    /// fields and uses the OpenAI `reasoning_effort` field.
    pub fn from_contract(preset: &ProviderPreset) -> Self {
        let (extra_mode, extra_passthrough_fields, reasoning_style) =
            match preset.model_discovery.format {
                ModelDiscoveryFormat::Openrouter => (
                    ExtraMode::Curated,
                    vec![
                        "provider".to_owned(),
                        "models".to_owned(),
                        "route".to_owned(),
                        "reasoning".to_owned(),
                    ],
                    ReasoningStyle::OpenRouter,
                ),
                _ => (ExtraMode::All, Vec::new(), ReasoningStyle::OpenAi),
            };

        Self {
            preset: preset.clone(),
            extra_namespace: preset.id.clone(),
            extra_mode,
            extra_passthrough_fields,
            reasoning_style,
        }
    }

    /// Return the preset ID.
    pub fn id(&self) -> &str {
        &self.preset.id
    }

    /// Return the preset display label.
    pub fn label(&self) -> &str {
        &self.preset.label
    }

    /// Return the base URL.
    pub fn base_url(&self) -> &str {
        &self.preset.base_url
    }

    /// Return the authentication configuration.
    pub fn auth(&self) -> &ProviderAuth {
        &self.preset.auth
    }

    /// Return the non-secret default headers.
    pub fn default_headers(&self) -> &BTreeMap<String, String> {
        &self.preset.default_headers
    }

    /// Return the attribution header placeholders.
    pub fn attribution_headers(&self) -> &BTreeMap<String, String> {
        &self.preset.attribution_headers
    }

    /// Return the model discovery configuration.
    pub fn model_discovery(&self) -> &ModelDiscovery {
        &self.preset.model_discovery
    }

    /// Return the optional key-validation endpoint.
    pub fn key_validation(&self) -> Option<&KeyValidation> {
        self.preset.key_validation.as_ref()
    }
}

/// Build the exact OpenAI-compatible `POST /chat/completions` body.
///
/// The function is pure so tests can assert the serialized JSON against the
/// shared request vectors. It rejects protected-field overrides before any
/// network call.
pub fn build_body(
    preset: &OpenAiCompatiblePreset,
    request: &NormalizedChatRequest,
    supported_parameters: &[String],
    attachments: &BTreeMap<String, ResolvedAttachment>,
) -> Result<Value, ChatError> {
    let advanced = advanced_namespace(&request.params.extra, &preset.extra_namespace)?;
    reject_protected_fields(advanced, preset.id())?;

    let mut body = Map::new();
    body.insert("model".to_owned(), Value::String(request.model.clone()));

    let mut messages = Vec::with_capacity(request.messages.len() + 1);
    if let Some(system) = &request.system {
        messages.push(json!({ "role": "system", "content": system }));
    }
    for message in &request.messages {
        messages.push(serialize_message(message, attachments, preset.id())?);
    }
    body.insert("messages".to_owned(), Value::Array(messages));

    body.insert("stream".to_owned(), Value::Bool(request.params.streaming));
    if request.params.streaming {
        body.insert(
            "stream_options".to_owned(),
            json!({ "include_usage": true }),
        );
    }

    if let Some(max_tokens) = request.params.max_output_tokens
        && supports(supported_parameters, "max_tokens")
    {
        body.insert("max_tokens".to_owned(), json!(max_tokens));
    }
    if let Some(temperature) = request.params.temperature
        && supports(supported_parameters, "temperature")
    {
        body.insert("temperature".to_owned(), json!(temperature));
    }
    if let Some(top_p) = request.params.top_p
        && supports(supported_parameters, "top_p")
    {
        body.insert("top_p".to_owned(), json!(top_p));
    }
    if !request.params.stop.is_empty() && supports(supported_parameters, "stop") {
        body.insert("stop".to_owned(), json!(request.params.stop));
    }
    if let Some(effort) = request.params.reasoning_effort
        && supports(supported_parameters, "reasoning")
    {
        match preset.reasoning_style {
            ReasoningStyle::OpenRouter => {
                body.insert("reasoning".to_owned(), json!({ "effort": effort }));
            }
            ReasoningStyle::OpenAi => {
                body.insert("reasoning_effort".to_owned(), json!(effort));
            }
        }
    }

    if let Some(advanced) = advanced {
        apply_advanced(preset, advanced, supported_parameters, &mut body)?;
    }

    Ok(Value::Object(body))
}

/// Build the provider request body and non-secret headers.
pub fn prepare_request(
    ctx: &ProviderContext,
    request: &NormalizedChatRequest,
    supported_parameters: &[String],
    attachments: &BTreeMap<String, ResolvedAttachment>,
) -> Result<PreparedChatRequest, ChatError> {
    let preset = OpenAiCompatiblePreset::from_contract(&ctx.preset);

    Ok(PreparedChatRequest {
        body: build_body(&preset, request, supported_parameters, attachments)?,
        headers: ctx.headers.clone(),
    })
}

/// Validate a provider key.
///
/// Presets without a key-validation endpoint return empty key metadata. Other
/// presets call their configured endpoint and, for OpenAI-format discovery,
/// validate the response by mapping the model catalog. The key is sent only in
/// a header.
pub async fn validate_key(
    client: &HttpClient,
    ctx: &ProviderContext,
    cancel: &CancellationToken,
) -> Result<ProviderKeyInfo, ChatError> {
    let preset = OpenAiCompatiblePreset::from_contract(&ctx.preset);
    let Some(validation) = preset.key_validation() else {
        return Ok(empty_key_info());
    };

    if requires_key(preset.auth()) {
        ctx.require_key()?;
    }

    let url = join_endpoint(&ctx.base_url, &validation.path).map_err(|error| {
        invalid_request_error(preset.id(), format!("The provider URL is invalid: {error}"))
    })?;
    let auth = auth_header(&preset, ctx.api_key.as_deref())?;
    let defaults = defaults_for_preset(&ctx.preset);

    let request = async {
        let prepared = client.prepare(Method::GET, &url).map_err(|error| {
            invalid_request_error(preset.id(), format!("The provider URL is invalid: {error}"))
        })?;
        let mut builder = prepared.into_builder();
        if let Some(auth) = auth {
            builder = auth.apply(builder);
        }

        let response = client
            .send(builder)
            .await
            .map_err(|error| network_error_to_chat_error(error, preset.id()))?;
        let status = response.status();
        if !status.is_success() {
            let retry_after = response
                .headers()
                .get(RETRY_AFTER)
                .and_then(|value| value.to_str().ok())
                .and_then(parse_retry_after);
            let request_id = extract_request_id(response.headers());
            let body = response
                .text()
                .await
                .ok()
                .and_then(|text| serde_json::from_str::<Value>(&text).ok());
            return Err(map_http_error_for(
                preset.id(),
                status.as_u16(),
                retry_after,
                body.as_ref(),
                request_id,
                default_message,
            ));
        }

        let bytes = response.bytes().await.map_err(|error| {
            network_error_to_chat_error(NetworkError::from_reqwest(error), preset.id())
        })?;
        let payload: Value = serde_json::from_slice(&bytes).map_err(|error| {
            parse_error(
                preset.id(),
                format!("The provider response was not valid JSON: {error}"),
            )
        })?;

        if preset.model_discovery().format == ModelDiscoveryFormat::Openrouter {
            map_key(&payload).map_err(|error| {
                parse_error(
                    preset.id(),
                    format!("The provider key response was not valid: {error}"),
                )
            })
        } else {
            map_models(&payload, &defaults).map_err(|error| {
                parse_error(
                    preset.id(),
                    format!("The provider /models response was not valid: {error}"),
                )
            })?;
            Ok(empty_key_info())
        }
    };

    tokio::select! {
        result = request => result,
        _ = cancel.cancelled() => Err(cancelled_error(preset.id())),
    }
}

/// Load the provider model catalog.
///
/// The discovery path, format, and public flag come from the preset. A public
/// catalog is fetched without a key. The response is mapped into the shared
/// [`CatalogModel`] shape.
pub async fn list_models(
    client: &HttpClient,
    ctx: &ProviderContext,
    cancel: &CancellationToken,
) -> Result<Vec<CatalogModel>, ChatError> {
    let preset = OpenAiCompatiblePreset::from_contract(&ctx.preset);
    let discovery = preset.model_discovery();
    let url = join_endpoint(&ctx.base_url, &discovery.path).map_err(|error| {
        invalid_request_error(preset.id(), format!("The provider URL is invalid: {error}"))
    })?;
    let auth = if discovery.public {
        None
    } else {
        auth_header(&preset, ctx.api_key.as_deref())?
    };
    let defaults = defaults_for_preset(&ctx.preset);

    let request = async {
        let prepared = client.prepare(Method::GET, &url).map_err(|error| {
            invalid_request_error(preset.id(), format!("The provider URL is invalid: {error}"))
        })?;
        let mut builder = prepared.into_builder();
        if let Some(auth) = auth {
            builder = auth.apply(builder);
        }

        let response = client
            .send(builder)
            .await
            .map_err(|error| network_error_to_chat_error(error, preset.id()))?;
        let status = response.status();
        if !status.is_success() {
            let retry_after = response
                .headers()
                .get(RETRY_AFTER)
                .and_then(|value| value.to_str().ok())
                .and_then(parse_retry_after);
            let request_id = extract_request_id(response.headers());
            let body = response
                .text()
                .await
                .ok()
                .and_then(|text| serde_json::from_str::<Value>(&text).ok());
            return Err(map_http_error_for(
                preset.id(),
                status.as_u16(),
                retry_after,
                body.as_ref(),
                request_id,
                default_message,
            ));
        }

        let bytes = response.bytes().await.map_err(|error| {
            network_error_to_chat_error(NetworkError::from_reqwest(error), preset.id())
        })?;
        let payload: Value = serde_json::from_slice(&bytes).map_err(|error| {
            parse_error(
                preset.id(),
                format!("The provider response was not valid JSON: {error}"),
            )
        })?;

        match discovery.format {
            ModelDiscoveryFormat::Openai => map_models(&payload, &defaults).map_err(|error| {
                parse_error(
                    preset.id(),
                    format!("The provider /models response was not valid: {error}"),
                )
            }),
            ModelDiscoveryFormat::Openrouter => crate::openrouter::catalog::map_models(&payload)
                .map_err(|error| {
                    parse_error(
                        preset.id(),
                        format!("The provider /models response was not valid: {error}"),
                    )
                }),
            other => Err(invalid_request_error(
                preset.id(),
                format!("Unsupported model discovery format: {other:?}"),
            )),
        }
    };

    tokio::select! {
        result = request => result,
        _ = cancel.cancelled() => Err(cancelled_error(preset.id())),
    }
}

/// Stream an OpenAI-compatible chat completion.
///
/// Events are emitted in the shared order: `start`, content/reasoning deltas,
/// `finish`, then `usage`. Terminal failures are recorded in the returned
/// [`StreamSummary`] so partial content is preserved. Cancellation stops the
/// stream and marks the summary cancelled.
pub async fn stream_chat<F>(
    client: &HttpClient,
    ctx: &ProviderContext,
    request: &NormalizedChatRequest,
    supported_parameters: &[String],
    attachments: &BTreeMap<String, ResolvedAttachment>,
    cancel: &CancellationToken,
    on_event: F,
) -> Result<StreamSummary, ChatError>
where
    F: FnMut(StreamEvent) + Send,
{
    let preset = OpenAiCompatiblePreset::from_contract(&ctx.preset);
    let prepared = prepare_request(ctx, request, supported_parameters, attachments)?;
    let url = join_endpoint(&ctx.base_url, "chat/completions").map_err(|error| {
        invalid_request_error(preset.id(), format!("The provider URL is invalid: {error}"))
    })?;
    let auth = auth_header(&preset, ctx.api_key.as_deref())?;
    let config = StreamConfig {
        provider_id: preset.id(),
        reasoning_style: preset.reasoning_style,
        auth,
        default_message,
    };

    stream_chat_with(
        client,
        &url,
        &prepared.body,
        &prepared.headers,
        config,
        cancel,
        on_event,
    )
    .await
}

/// Provider-neutral default error message for an HTTP status.
pub fn default_message(status: Option<u16>) -> String {
    match status {
        Some(401) => "Authentication failed. Check your API key.".to_owned(),
        Some(402) => "The provider reports insufficient credits or an exhausted quota.".to_owned(),
        Some(403) => "The provider blocked the request.".to_owned(),
        Some(404) => "The requested model was not found.".to_owned(),
        Some(408) => "The provider took too long to respond. Try again.".to_owned(),
        Some(413) => "The request exceeds the model's context length.".to_owned(),
        Some(429) => "Rate limit exceeded. Please retry later.".to_owned(),
        Some(503) => "The provider is temporarily unavailable.".to_owned(),
        Some(500..=599) => "The provider returned an error.".to_owned(),
        Some(400) => "The request was invalid.".to_owned(),
        Some(status) => format!("The provider request failed with HTTP {status}"),
        None => "The provider request failed.".to_owned(),
    }
}

/// Map a transport failure to a provider-neutral [`ChatError`].
pub fn network_error_to_chat_error(error: NetworkError, provider_id: &str) -> ChatError {
    let (code, message) = match error.kind {
        NetworkErrorKind::Dns | NetworkErrorKind::Tls | NetworkErrorKind::Connection => (
            ChatErrorCode::Network,
            "Could not reach the provider. Check your connection and base URL.".to_owned(),
        ),
        NetworkErrorKind::Timeout => (
            ChatErrorCode::Timeout,
            "The provider took too long to respond. Try again.".to_owned(),
        ),
        NetworkErrorKind::Cancelled => (
            ChatErrorCode::Cancelled,
            "The provider request was cancelled.".to_owned(),
        ),
        NetworkErrorKind::HttpStatus => (error.chat_error_code(), error.message.clone()),
        NetworkErrorKind::Redirect => (
            ChatErrorCode::Network,
            "The provider redirected to a different origin, which is not allowed.".to_owned(),
        ),
        NetworkErrorKind::InvalidUrl | NetworkErrorKind::InvalidHeader => {
            (ChatErrorCode::InvalidRequest, error.message.clone())
        }
        NetworkErrorKind::Other => (
            ChatErrorCode::Unknown,
            "The provider request failed.".to_owned(),
        ),
    };

    ChatError {
        code,
        message,
        provider_id: Some(provider_id.to_owned()),
        status: error.status,
        retryable: error.retryable,
        retry_after_seconds: error.retry_after_seconds,
        request_id: error.request_id,
        details: None,
        at: Timestamp::now(),
    }
}

fn join_endpoint(base_url: &str, path: &str) -> Result<Url, UrlPolicyError> {
    if path.starts_with("http://") || path.starts_with("https://") {
        join_url(base_url, path)
    } else {
        join_url(base_url, path.trim_start_matches('/'))
    }
}

fn supports(supported_parameters: &[String], name: &str) -> bool {
    supported_parameters
        .iter()
        .any(|parameter| parameter == name)
}

fn requires_key(auth: &ProviderAuth) -> bool {
    matches!(
        auth.auth_type,
        ProviderAuthType::Bearer | ProviderAuthType::XApiKey | ProviderAuthType::QueryKey
    )
}

fn auth_header(
    preset: &OpenAiCompatiblePreset,
    api_key: Option<&str>,
) -> Result<Option<SecretHeader>, ChatError> {
    let auth = preset.auth();

    match auth.auth_type {
        ProviderAuthType::None => Ok(None),
        ProviderAuthType::Bearer => {
            let key = require_key(preset, api_key)?;
            let name = auth.header_name.as_deref().unwrap_or("Authorization");
            let scheme = auth.scheme.as_deref().unwrap_or("Bearer");
            let header =
                SecretHeader::new(header_name(name, preset.id())?, &format!("{scheme} {key}"))
                    .map_err(|error| network_error_to_chat_error(error, preset.id()))?;
            Ok(Some(header))
        }
        ProviderAuthType::XApiKey => {
            let key = require_key(preset, api_key)?;
            let name = auth.header_name.as_deref().unwrap_or("x-api-key");
            let header = SecretHeader::new(header_name(name, preset.id())?, key)
                .map_err(|error| network_error_to_chat_error(error, preset.id()))?;
            Ok(Some(header))
        }
        ProviderAuthType::QueryKey => Err(invalid_request_error(
            preset.id(),
            "API keys are never placed in URLs. Use a header-based auth style.",
        )),
    }
}

fn require_key<'a>(
    preset: &OpenAiCompatiblePreset,
    api_key: Option<&'a str>,
) -> Result<&'a str, ChatError> {
    match api_key {
        Some(key) if !key.trim().is_empty() => Ok(key),
        _ => Err(ChatError {
            code: ChatErrorCode::Auth,
            message: format!("An {} API key is required.", preset.label()),
            provider_id: Some(preset.id().to_owned()),
            status: None,
            retryable: false,
            retry_after_seconds: None,
            request_id: None,
            details: None,
            at: Timestamp::now(),
        }),
    }
}

fn header_name(name: &str, provider_id: &str) -> Result<HeaderName, ChatError> {
    HeaderName::from_bytes(name.as_bytes()).map_err(|_| {
        invalid_request_error(provider_id, format!("The {name} header name is not valid."))
    })
}

fn empty_key_info() -> ProviderKeyInfo {
    ProviderKeyInfo {
        label: None,
        usage_usd: None,
        limit_usd: None,
        limit_remaining_usd: None,
        is_free_tier: None,
        rate_limit: None,
    }
}

fn extract_request_id(headers: &reqwest::header::HeaderMap) -> Option<String> {
    const NAMES: [&str; 3] = ["x-request-id", "request-id", "x-generation-id"];

    for name in NAMES {
        if let Some(value) = headers.get(name).and_then(|value| value.to_str().ok()) {
            return Some(value.to_owned());
        }
    }

    None
}

fn advanced_namespace<'a>(
    extra: &'a Map<String, Value>,
    namespace: &str,
) -> Result<Option<&'a Map<String, Value>>, ChatError> {
    match extra.get(namespace) {
        None | Some(Value::Null) => Ok(None),
        Some(Value::Object(map)) => Ok(Some(map)),
        Some(_) => Err(invalid_request_error(
            namespace,
            "Advanced JSON must be a JSON object.",
        )),
    }
}

fn reject_protected_fields(
    advanced: Option<&Map<String, Value>>,
    provider_id: &str,
) -> Result<(), ChatError> {
    let Some(advanced) = advanced else {
        return Ok(());
    };

    let mut protected: Vec<&str> = PROTECTED_FIELDS
        .iter()
        .copied()
        .filter(|field| advanced.contains_key(*field))
        .collect();

    if protected.is_empty() {
        return Ok(());
    }

    protected.sort_unstable();
    let message = format!(
        "Advanced JSON cannot override protected fields: {}.",
        protected.join(", ")
    );

    let mut error = invalid_request_error(provider_id, message);
    error.details = Some(json!({ "protectedFields": protected }));
    Err(error)
}

fn apply_advanced(
    preset: &OpenAiCompatiblePreset,
    advanced: &Map<String, Value>,
    supported_parameters: &[String],
    body: &mut Map<String, Value>,
) -> Result<(), ChatError> {
    match preset.extra_mode {
        ExtraMode::All => {
            for (key, value) in advanced {
                if PROTECTED_FIELDS.contains(&key.as_str()) {
                    continue;
                }
                body.insert(key.clone(), value.clone());
            }
            Ok(())
        }
        ExtraMode::Curated => {
            for (field, value) in advanced {
                match field.as_str() {
                    "provider" => {
                        let provider = value.as_object().ok_or_else(|| {
                            invalid_request_error(
                                preset.id(),
                                "OpenRouter provider routing must be a JSON object.",
                            )
                        })?;
                        let mut provider_body = Map::new();

                        for (provider_key, provider_value) in provider {
                            match provider_key.as_str() {
                                "allow_fallbacks" => {
                                    if !provider_value.is_boolean() {
                                        return Err(invalid_request_error(
                                            preset.id(),
                                            "OpenRouter provider.allow_fallbacks must be a boolean.",
                                        ));
                                    }
                                    provider_body
                                        .insert(provider_key.clone(), provider_value.clone());
                                }
                                "sort" => {
                                    let sort = provider_value.as_str().ok_or_else(|| {
                                        invalid_request_error(
                                            preset.id(),
                                            "OpenRouter provider.sort must be a string.",
                                        )
                                    })?;
                                    if !matches!(sort, "price" | "throughput" | "latency") {
                                        return Err(invalid_request_error(
                                            preset.id(),
                                            "OpenRouter provider.sort must be one of price, throughput, or latency.",
                                        ));
                                    }
                                    provider_body
                                        .insert(provider_key.clone(), provider_value.clone());
                                }
                                "data_collection" => {
                                    let data_collection =
                                        provider_value.as_str().ok_or_else(|| {
                                            invalid_request_error(
                                                preset.id(),
                                                "OpenRouter provider.data_collection must be a string.",
                                            )
                                        })?;
                                    if !matches!(data_collection, "allow" | "deny") {
                                        return Err(invalid_request_error(
                                            preset.id(),
                                            "OpenRouter provider.data_collection must be allow or deny.",
                                        ));
                                    }
                                    provider_body
                                        .insert(provider_key.clone(), provider_value.clone());
                                }
                                "order" | "only" | "ignore" => {
                                    validate_string_array(
                                        preset.id(),
                                        provider_key,
                                        provider_value,
                                    )?;
                                    provider_body
                                        .insert(provider_key.clone(), provider_value.clone());
                                }
                                _ => {
                                    return Err(invalid_request_error(
                                        preset.id(),
                                        format!(
                                            "Unsupported OpenRouter provider field: {provider_key}."
                                        ),
                                    ));
                                }
                            }
                        }

                        body.insert("provider".to_owned(), Value::Object(provider_body));
                    }
                    "models" => {
                        validate_string_array(preset.id(), "models", value)?;
                        body.insert("models".to_owned(), value.clone());
                    }
                    "route" => {
                        let route = value.as_str().ok_or_else(|| {
                            invalid_request_error(preset.id(), "OpenRouter route must be a string.")
                        })?;
                        if route != "fallback" {
                            return Err(invalid_request_error(
                                preset.id(),
                                "OpenRouter route must be fallback.",
                            ));
                        }
                        body.insert("route".to_owned(), value.clone());
                    }
                    "reasoning" => {
                        if !supports(supported_parameters, "reasoning") || !value.is_object() {
                            continue;
                        }
                        let existing = body
                            .get("reasoning")
                            .and_then(Value::as_object)
                            .cloned()
                            .unwrap_or_default();
                        let mut merged = existing;
                        if let Some(object) = value.as_object() {
                            for (key, value) in object {
                                merged.insert(key.clone(), value.clone());
                            }
                        }
                        body.insert("reasoning".to_owned(), Value::Object(merged));
                    }
                    _ => {
                        return Err(invalid_request_error(
                            preset.id(),
                            format!("Unsupported OpenRouter advanced field: {field}."),
                        ));
                    }
                }
            }
            Ok(())
        }
    }
}

fn validate_string_array(provider_id: &str, field: &str, value: &Value) -> Result<(), ChatError> {
    let array = value.as_array().ok_or_else(|| {
        invalid_request_error(
            provider_id,
            format!("OpenRouter {field} must be an array of strings."),
        )
    })?;

    if !array.iter().all(Value::is_string) {
        return Err(invalid_request_error(
            provider_id,
            format!("OpenRouter {field} must be an array of strings."),
        ));
    }

    Ok(())
}

fn serialize_message(
    message: &crate::openrouter::request::NormalizedChatMessage,
    attachments: &BTreeMap<String, ResolvedAttachment>,
    provider_id: &str,
) -> Result<Value, ChatError> {
    let mut text = String::new();
    let mut parts = Vec::new();
    let mut has_image = false;

    for part in &message.parts {
        match part {
            ContentPart::Text { text: part_text } => {
                text.push_str(part_text);
                parts.push(json!({ "type": "text", "text": part_text }));
            }
            ContentPart::File {
                attachment_id,
                name,
                ..
            } => {
                let attachment = attachments.get(attachment_id).ok_or_else(|| {
                    invalid_request_error(
                        provider_id,
                        format!("Attachment {attachment_id} is missing from the resolved request."),
                    )
                })?;
                let bytes = BASE64_STANDARD
                    .decode(&attachment.data_base64)
                    .map_err(|_| {
                        invalid_request_error(
                            provider_id,
                            format!("Attachment {attachment_id} is not valid base64."),
                        )
                    })?;
                let content = String::from_utf8(bytes).map_err(|_| {
                    invalid_request_error(
                        provider_id,
                        format!("Attachment {attachment_id} is not valid UTF-8 text."),
                    )
                })?;
                let file_text = format!("\n\n[File: {name}]\n{content}");
                text.push_str(&file_text);
                parts.push(json!({ "type": "text", "text": file_text }));
            }
            ContentPart::Image {
                attachment_id,
                mime_type,
                ..
            } => {
                let attachment = attachments.get(attachment_id).ok_or_else(|| {
                    invalid_request_error(
                        provider_id,
                        format!("Attachment {attachment_id} is missing from the resolved request."),
                    )
                })?;
                has_image = true;
                let url = format!("data:{mime_type};base64,{}", attachment.data_base64);
                parts.push(json!({
                    "type": "image_url",
                    "image_url": { "url": url }
                }));
            }
            ContentPart::Reasoning { .. } => {
                // Reasoning content is not sent back to providers.
            }
            ContentPart::ToolCall { .. } | ContentPart::ToolResult { .. } => {
                // Tools are not sent in phase 1.
            }
        }
    }

    let content = if has_image {
        Value::Array(parts)
    } else {
        Value::String(text)
    };

    Ok(json!({
        "role": message.role,
        "content": content,
    }))
}

fn invalid_request_error(provider_id: &str, message: impl Into<String>) -> ChatError {
    ChatError {
        code: ChatErrorCode::InvalidRequest,
        message: message.into(),
        provider_id: Some(provider_id.to_owned()),
        status: None,
        retryable: false,
        retry_after_seconds: None,
        request_id: None,
        details: None,
        at: Timestamp::now(),
    }
}

fn parse_error(provider_id: &str, message: impl Into<String>) -> ChatError {
    ChatError {
        code: ChatErrorCode::Parse,
        message: message.into(),
        provider_id: Some(provider_id.to_owned()),
        status: None,
        retryable: false,
        retry_after_seconds: None,
        request_id: None,
        details: None,
        at: Timestamp::now(),
    }
}

fn cancelled_error(provider_id: &str) -> ChatError {
    ChatError {
        code: ChatErrorCode::Cancelled,
        message: "The request was cancelled.".to_owned(),
        provider_id: Some(provider_id.to_owned()),
        status: None,
        retryable: false,
        retry_after_seconds: None,
        request_id: None,
        details: None,
        at: Timestamp::now(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use chatui_contracts::provider_catalog;
    use chatui_domain::{ChatParams, MessageRole};

    fn preset(id: &str) -> OpenAiCompatiblePreset {
        OpenAiCompatiblePreset::from_contract(provider_catalog().get(id).expect("contract preset"))
    }

    fn request() -> NormalizedChatRequest {
        NormalizedChatRequest {
            model: "gpt-4o-mini".to_owned(),
            system: None,
            messages: vec![crate::openrouter::request::NormalizedChatMessage {
                role: MessageRole::User,
                parts: vec![ContentPart::Text {
                    text: "Hello".to_owned(),
                }],
            }],
            params: ChatParams::default(),
        }
    }

    #[test]
    fn openrouter_preset_keeps_curated_mode() {
        let preset = preset("openrouter");
        assert_eq!(preset.extra_mode, ExtraMode::Curated);
        assert_eq!(preset.reasoning_style, ReasoningStyle::OpenRouter);
        assert_eq!(preset.extra_namespace, "openrouter");
    }

    #[test]
    fn openai_preset_uses_all_mode() {
        let preset = preset("openai");
        assert_eq!(preset.extra_mode, ExtraMode::All);
        assert_eq!(preset.reasoning_style, ReasoningStyle::OpenAi);
        assert_eq!(preset.extra_namespace, "openai");
    }

    #[test]
    fn protected_fields_are_rejected_with_sorted_details() {
        let mut request = request();
        request.params.extra.insert(
            "openai".to_owned(),
            json!({
                "model": "attacker/model",
                "messages": [],
                "stream": false,
                "stream_options": { "include_usage": false }
            }),
        );

        let error = build_body(&preset("openai"), &request, &[], &BTreeMap::new())
            .expect_err("protected fields");
        assert_eq!(error.code, ChatErrorCode::InvalidRequest);
        assert_eq!(
            error.details,
            Some(json!({
                "protectedFields": ["messages", "model", "stream", "stream_options"]
            }))
        );
    }

    #[test]
    fn unsupported_controls_are_omitted() {
        let mut request = request();
        request.params.max_output_tokens = Some(256);
        request.params.temperature = Some(0.7);
        request.params.top_p = Some(0.9);
        request.params.stop = vec!["STOP".to_owned()];
        request.params.reasoning_effort = Some(chatui_domain::ReasoningEffort::Medium);

        let body = build_body(
            &preset("openai"),
            &request,
            &["stream".to_owned()],
            &BTreeMap::new(),
        )
        .expect("body builds");

        assert!(body.get("max_tokens").is_none());
        assert!(body.get("temperature").is_none());
        assert!(body.get("top_p").is_none());
        assert!(body.get("stop").is_none());
        assert!(body.get("reasoning_effort").is_none());
    }

    #[test]
    fn openai_reasoning_uses_reasoning_effort() {
        let mut request = request();
        request.params.reasoning_effort = Some(chatui_domain::ReasoningEffort::High);

        let body = build_body(
            &preset("openai"),
            &request,
            &["stream".to_owned(), "reasoning".to_owned()],
            &BTreeMap::new(),
        )
        .expect("body builds");

        assert_eq!(body.get("reasoning_effort"), Some(&json!("high")));
        assert!(body.get("reasoning").is_none());
    }

    #[test]
    fn query_key_auth_is_rejected() {
        let mut preset = preset("custom");
        preset.preset.auth.auth_type = ProviderAuthType::QueryKey;
        preset.preset.auth.query_param = Some("api_key".to_owned());

        let error = auth_header(&preset, Some("sk-test")).expect_err("query key rejected");
        assert_eq!(error.code, ChatErrorCode::InvalidRequest);
        assert!(!error.message.contains("sk-test"));
    }

    #[test]
    fn network_errors_use_provider_neutral_messages() {
        let network = network_error_to_chat_error(
            NetworkError::new(NetworkErrorKind::Connection, "connection refused"),
            "openai",
        );
        assert_eq!(network.code, ChatErrorCode::Network);
        assert_eq!(
            network.message,
            "Could not reach the provider. Check your connection and base URL."
        );
        assert!(network.retryable);

        let timeout = network_error_to_chat_error(NetworkError::timeout(), "openai");
        assert_eq!(timeout.code, ChatErrorCode::Timeout);
        assert_eq!(
            timeout.message,
            "The provider took too long to respond. Try again."
        );
        assert!(timeout.retryable);
    }
}
