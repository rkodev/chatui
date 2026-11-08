//! Native Ollama adapter.
//!
//! Ollama is not only OpenAI-compatible: it has a native API at the server root
//! with `POST /api/chat` (NDJSON) and `GET /api/tags` (model discovery). This
//! module implements the native path first and keeps the OpenAI-compatible
//! `/v1/chat/completions` path as a documented fallback for custom local
//! servers.
//!
//! Mapping rules from spec sections 7.5, 7.6, and 7.8:
//!
//! - the contract base URL points at `/v1`; native calls strip that suffix and
//!   use `/api/chat` and `/api/tags`;
//! - the system prompt is the first message;
//! - text and text-file parts become the message `content`, with the shared
//!   `[File: name]` header;
//! - images become the message `images` array of base64 strings;
//! - sampling controls live under `options`; `maxOutputTokens` maps to
//!   `num_predict` and `stop` maps to `stop`;
//! - each NDJSON line is parsed in the adapter, never in the transport;
//! - `message.content` maps to `text-delta`, `message.thinking` to
//!   `reasoning-delta`, `done`/`done_reason` to `finish`, and
//!   `prompt_eval_count`/`eval_count` to usage;
//! - `{ "error": ... }` envelopes normalize to the shared [`ChatError`].
//!
//! Ollama needs no key. Credentials are never placed in a URL or a non-secret
//! header.

#![allow(clippy::result_large_err)]

use std::collections::BTreeMap;
use std::time::Instant;

use base64::Engine as _;
use base64::engine::general_purpose::STANDARD as BASE64_STANDARD;
use chatui_contracts::{ModelDiscoveryFormat, ProviderPreset};
use chatui_domain::{
    ChatError, ChatErrorCode, ContentPart, FinishReason, StreamEvent, Timestamp, Usage,
};
use reqwest::header::{HeaderMap, RETRY_AFTER};
use reqwest::{Method, Url};
use serde_json::{Map, Value, json};
use tokio_util::sync::CancellationToken;

use crate::adapter::ProviderContext;
use crate::error::{NetworkError, NetworkErrorKind, parse_retry_after};
use crate::http::HttpClient;
use crate::ndjson::{DEFAULT_MAX_NDJSON_BUFFER_BYTES, NdjsonError, read_ndjson};
use crate::openai_compatible::{default_message, network_error_to_chat_error};
use crate::openrouter::catalog::CatalogModel;
use crate::openrouter::key::ProviderKeyInfo;
use crate::openrouter::request::{
    NormalizedChatMessage, NormalizedChatRequest, PreparedChatRequest, ResolvedAttachment,
};
use crate::openrouter::stream::StreamSummary;
use crate::retry::PartialOutput;
use crate::url_policy::{UrlPolicyError, join_url};

/// Provider ID used on every normalized Ollama error.
pub const OLLAMA_PROVIDER_ID: &str = "ollama";

/// Namespace that owns Ollama-only advanced JSON.
pub const OLLAMA_EXTRA_NAMESPACE: &str = "ollama";

/// Fields advanced JSON may never override.
const PROTECTED_FIELDS: [&str; 4] = ["model", "messages", "stream", "stream_options"];

/// Ollama-only transport selectors that are never forwarded to the provider.
const OLLAMA_CONTROL_FIELDS: [&str; 2] = ["transport", "mode"];

/// The native Ollama adapter.
///
/// The adapter stores only the preset ID. Every request reads the contract
/// preset from [`ProviderContext`], so no provider metadata is duplicated.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct OllamaAdapter {
    preset_id: String,
}

impl OllamaAdapter {
    /// Build an adapter for a contract preset ID.
    pub fn new(preset_id: impl Into<String>) -> Self {
        Self {
            preset_id: preset_id.into(),
        }
    }

    /// Return the preset ID.
    pub fn preset_id(&self) -> &str {
        &self.preset_id
    }
}

/// Transport selected for one Ollama chat request.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum OllamaTransport {
    /// Native `POST /api/chat` with NDJSON.
    Native,
    /// OpenAI-compatible `POST /v1/chat/completions` fallback.
    OpenAiCompatible,
}

/// Strip the contract's trailing `/v1` so native `/api/*` calls use the server
/// root.
pub fn ollama_native_base_url(base_url: &str) -> String {
    let trimmed = base_url.trim_end_matches('/');
    let without_v1 = trimmed.strip_suffix("/v1").unwrap_or(trimmed);
    without_v1.trim_end_matches('/').to_owned()
}

/// Build the native `POST /api/chat` URL.
pub fn build_chat_url(base_url: &str) -> Result<Url, UrlPolicyError> {
    join_url(&ollama_native_base_url(base_url), "api/chat")
}

/// Build the native `GET /api/tags` URL.
pub fn build_tags_url(base_url: &str) -> Result<Url, UrlPolicyError> {
    join_url(&ollama_native_base_url(base_url), "api/tags")
}

/// Select the transport for one request.
///
/// Native is the default for the Ollama preset. A request may explicitly set
/// `params.extra.ollama.transport` (or `mode`) to `openai-compatible` to use the
/// shared OpenAI-compatible transport for a custom local server.
pub fn select_transport(
    preset: &ProviderPreset,
    request: &NormalizedChatRequest,
) -> OllamaTransport {
    let extra = request
        .params
        .extra
        .get(OLLAMA_EXTRA_NAMESPACE)
        .and_then(Value::as_object);
    let requested = extra
        .and_then(|extra| extra.get("transport").or_else(|| extra.get("mode")))
        .and_then(Value::as_str);

    match requested {
        Some("native") => OllamaTransport::Native,
        Some("openai-compatible") => OllamaTransport::OpenAiCompatible,
        _ if preset.model_discovery.format == ModelDiscoveryFormat::OllamaTags
            || preset.id == "ollama" =>
        {
            OllamaTransport::Native
        }
        _ => OllamaTransport::OpenAiCompatible,
    }
}

/// Build the exact native Ollama `POST /api/chat` body.
///
/// The function is pure so tests can assert the serialized JSON against the
/// shared request vectors. It rejects protected-field overrides before any
/// network call.
pub fn build_body(
    request: &NormalizedChatRequest,
    supported_parameters: &[String],
    attachments: &BTreeMap<String, ResolvedAttachment>,
) -> Result<Value, ChatError> {
    let advanced = advanced_namespace(&request.params.extra)?;
    reject_protected_fields(advanced)?;

    let mut messages = Vec::with_capacity(request.messages.len() + 1);
    if let Some(system) = &request.system {
        messages.push(json!({ "role": "system", "content": system }));
    }
    for message in &request.messages {
        messages.push(serialize_message(message, attachments)?);
    }

    let mut body = Map::new();
    body.insert("model".to_owned(), Value::String(request.model.clone()));
    body.insert("messages".to_owned(), Value::Array(messages));
    body.insert("stream".to_owned(), Value::Bool(true));

    let mut options = Map::new();
    if let Some(max_output_tokens) = request.params.max_output_tokens
        && supports(supported_parameters, "max_tokens")
    {
        options.insert("num_predict".to_owned(), json!(max_output_tokens));
    }
    if let Some(temperature) = request.params.temperature
        && supports(supported_parameters, "temperature")
    {
        options.insert("temperature".to_owned(), json!(temperature));
    }
    if let Some(top_p) = request.params.top_p
        && supports(supported_parameters, "top_p")
    {
        options.insert("top_p".to_owned(), json!(top_p));
    }
    if !request.params.stop.is_empty() && supports(supported_parameters, "stop") {
        options.insert("stop".to_owned(), json!(request.params.stop));
    }
    if !options.is_empty() {
        body.insert("options".to_owned(), Value::Object(options));
    }

    if let Some(advanced) = advanced {
        for (field, value) in advanced {
            if PROTECTED_FIELDS.contains(&field.as_str()) {
                continue;
            }
            if OLLAMA_CONTROL_FIELDS.contains(&field.as_str()) {
                continue;
            }
            body.insert(field.clone(), value.clone());
        }
    }

    Ok(Value::Object(body))
}

/// Build the provider request body and non-secret headers.
///
/// An explicit `openai-compatible` transport delegates to the shared
/// OpenAI-compatible transport. The transport selector itself is stripped so it
/// never reaches the provider.
pub fn prepare_request(
    ctx: &ProviderContext,
    request: &NormalizedChatRequest,
    supported_parameters: &[String],
    attachments: &BTreeMap<String, ResolvedAttachment>,
) -> Result<PreparedChatRequest, ChatError> {
    if select_transport(&ctx.preset, request) == OllamaTransport::OpenAiCompatible {
        let request = without_transport_control(request);
        return crate::openai_compatible::prepare_request(
            ctx,
            &request,
            supported_parameters,
            attachments,
        );
    }

    Ok(PreparedChatRequest {
        body: build_body(request, supported_parameters, attachments)?,
        headers: build_headers(ctx),
    })
}

/// Build the non-secret native Ollama request headers.
///
/// Ollama needs no key. `content-type` and `Accept` are always set for
/// streaming requests.
pub fn build_headers(ctx: &ProviderContext) -> BTreeMap<String, String> {
    let mut headers = BTreeMap::new();

    for (name, value) in &ctx.headers {
        headers.insert(name.clone(), value.clone());
    }

    headers.retain(|name, _| {
        !name.eq_ignore_ascii_case("content-type") && !name.eq_ignore_ascii_case("accept")
    });

    headers.insert("content-type".to_owned(), "application/json".to_owned());
    headers.insert("Accept".to_owned(), "application/x-ndjson".to_owned());

    headers
}

/// Validate an Ollama endpoint.
///
/// Ollama has no key-introspection endpoint and needs no key, so this is a
/// no-op that returns empty key metadata.
pub async fn validate_key(
    _client: &HttpClient,
    _ctx: &ProviderContext,
    _cancel: &CancellationToken,
) -> Result<ProviderKeyInfo, ChatError> {
    Ok(empty_key_info())
}

/// Load the Ollama catalog from `GET /api/tags`.
///
/// The URL is derived from the resolved base URL, not from the contract's
/// absolute discovery path, so a user-configured remote Ollama server works.
pub async fn list_models(
    client: &HttpClient,
    ctx: &ProviderContext,
    cancel: &CancellationToken,
) -> Result<Vec<CatalogModel>, ChatError> {
    let url = build_tags_url(&ctx.base_url)
        .map_err(|error| invalid_request_error(format!("The Ollama URL is invalid: {error}")))?;

    let request = async {
        let prepared = client.prepare(Method::GET, &url).map_err(|error| {
            invalid_request_error(format!("The Ollama URL is invalid: {error}"))
        })?;
        let mut builder = prepared.into_builder();

        for (name, value) in discovery_headers(ctx) {
            builder = builder.header(name, value);
        }

        let response = client
            .send(builder)
            .await
            .map_err(|error| network_error_to_chat_error(error, OLLAMA_PROVIDER_ID))?;
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
            return Err(map_http_error(
                status.as_u16(),
                retry_after,
                body.as_ref(),
                request_id,
            ));
        }

        let bytes = response.bytes().await.map_err(|error| {
            network_error_to_chat_error(NetworkError::from_reqwest(error), OLLAMA_PROVIDER_ID)
        })?;
        let payload: Value = serde_json::from_slice(&bytes).map_err(|error| {
            parse_error(format!(
                "The Ollama /api/tags response was not valid JSON: {error}"
            ))
        })?;

        crate::discovery::map_ollama_tags(&payload).map_err(|error| {
            parse_error(format!(
                "The Ollama /api/tags response was not valid: {error}"
            ))
        })
    };

    tokio::select! {
        result = request => result,
        _ = cancel.cancelled() => Err(cancelled_error()),
    }
}

/// Stream a native Ollama chat completion.
///
/// Events are emitted in the shared order: `start`, text/reasoning deltas,
/// `finish`, then `usage`. Cancellation stops the stream and preserves any
/// partial text or reasoning in the returned [`StreamSummary`].
pub async fn stream_chat<F>(
    client: &HttpClient,
    ctx: &ProviderContext,
    request: &NormalizedChatRequest,
    supported_parameters: &[String],
    attachments: &BTreeMap<String, ResolvedAttachment>,
    cancel: &CancellationToken,
    mut on_event: F,
) -> Result<StreamSummary, ChatError>
where
    F: FnMut(StreamEvent) + Send,
{
    if select_transport(&ctx.preset, request) == OllamaTransport::OpenAiCompatible {
        let request = without_transport_control(request);
        return crate::openai_compatible::stream_chat(
            client,
            ctx,
            &request,
            supported_parameters,
            attachments,
            cancel,
            on_event,
        )
        .await;
    }

    let prepared = prepare_request(ctx, request, supported_parameters, attachments)?;
    let url = build_chat_url(&ctx.base_url)
        .map_err(|error| invalid_request_error(format!("The Ollama URL is invalid: {error}")))?;

    let prepared_request = client.prepare(Method::POST, &url).map_err(|error| {
        invalid_request_error(format!("The Ollama chat URL is invalid: {error}"))
    })?;
    let mut builder = prepared_request.into_builder().json(&prepared.body);

    for (name, value) in &prepared.headers {
        // `.json` already sets the content type; adding it again would send a
        // duplicate header.
        if name.eq_ignore_ascii_case("content-type") {
            continue;
        }
        builder = builder.header(name, value);
    }

    let response = client
        .send(builder)
        .await
        .map_err(|error| network_error_to_chat_error(error, OLLAMA_PROVIDER_ID))?;

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

        return Err(map_http_error(
            status.as_u16(),
            retry_after,
            body.as_ref(),
            request_id,
        ));
    }

    let read_cancel = cancel.child_token();
    let cancel_for_closure = read_cancel.clone();
    let mut decoder = OllamaStreamDecoder::new();

    let read_result = read_ndjson(
        response.bytes_stream(),
        |line| {
            for event in decoder.push(line) {
                on_event(event);
            }
            if decoder.has_error() {
                cancel_for_closure.cancel();
            }
        },
        &read_cancel,
        DEFAULT_MAX_NDJSON_BUFFER_BYTES,
    )
    .await;

    match read_result {
        Ok(()) => {}
        Err(NdjsonError::Cancelled) => {
            if !decoder.has_error() {
                decoder.set_error(cancelled_error());
            }
        }
        Err(NdjsonError::Stream { message }) => {
            if !decoder.has_error() {
                let error = NetworkError::new(
                    NetworkErrorKind::Connection,
                    format!("The Ollama stream failed: {message}"),
                );
                decoder.set_error(error.to_chat_error(Some(OLLAMA_PROVIDER_ID.to_owned())));
            }
        }
        Err(NdjsonError::BufferOverflow { max_bytes }) => {
            if !decoder.has_error() {
                decoder.set_error(parse_error(format!(
                    "The Ollama stream exceeded the {max_bytes} byte buffer limit."
                )));
            }
        }
        Err(NdjsonError::InvalidUtf8) => {
            if !decoder.has_error() {
                decoder.set_error(parse_error("The Ollama stream contained invalid UTF-8."));
            }
        }
    }

    let (events, summary) = decoder.finish();
    for event in events {
        on_event(event);
    }

    Ok(summary)
}

/// Incremental native Ollama NDJSON decoder.
///
/// Feed each complete line from the shared NDJSON parser to [`Self::push`] and
/// forward the returned events to the caller. Call [`Self::finish`] once the
/// stream ends to obtain any trailing `finish`/`usage` events and the
/// normalized summary.
#[derive(Debug)]
pub struct OllamaStreamDecoder {
    started: bool,
    finished: bool,
    text: String,
    reasoning: String,
    input_tokens: Option<u32>,
    output_tokens: Option<u32>,
    usage: Option<Usage>,
    usage_emitted: bool,
    finish_reason: Option<FinishReason>,
    error: Option<ChatError>,
    partial_output: PartialOutput,
    started_at: Instant,
    first_token_at: Option<Instant>,
}

impl Default for OllamaStreamDecoder {
    fn default() -> Self {
        Self::new()
    }
}

impl OllamaStreamDecoder {
    /// Create a decoder with no events seen.
    pub fn new() -> Self {
        Self {
            started: false,
            finished: false,
            text: String::new(),
            reasoning: String::new(),
            input_tokens: None,
            output_tokens: None,
            usage: None,
            usage_emitted: false,
            finish_reason: None,
            error: None,
            partial_output: PartialOutput::none(),
            started_at: Instant::now(),
            first_token_at: None,
        }
    }

    /// Decode one NDJSON line and return the events to emit.
    ///
    /// Once an error has been recorded, later lines are ignored so partial
    /// content is preserved and no further events are emitted.
    pub fn push(&mut self, line: &str) -> Vec<StreamEvent> {
        let mut events = Vec::new();

        if self.finished || self.error.is_some() {
            return events;
        }

        let line = line.trim();
        if line.is_empty() {
            return events;
        }

        let value: Value = match serde_json::from_str(line) {
            Ok(value) => value,
            Err(error) => {
                self.set_error(parse_error(format!(
                    "The Ollama stream contained invalid JSON: {error}"
                )));
                events.push(StreamEvent::Error {
                    error: self.error.clone().expect("error was just set"),
                });
                return events;
            }
        };

        if value.get("error").is_some_and(|error| !error.is_null()) {
            let error = map_stream_error(&value, None);
            self.error = Some(error.clone());
            events.push(StreamEvent::Error { error });
            return events;
        }

        if !self.started {
            self.started = true;
            events.push(StreamEvent::Start { response_id: None });
        }

        if let Some(message) = value.get("message").and_then(Value::as_object) {
            if let Some(content) = message
                .get("content")
                .and_then(Value::as_str)
                .filter(|text| !text.is_empty())
            {
                self.push_text(content, &mut events);
            }
            if let Some(thinking) = message
                .get("thinking")
                .and_then(Value::as_str)
                .filter(|text| !text.is_empty())
            {
                self.push_reasoning(thinking, &mut events);
            }
        }

        if let Some(input) = token_count(value.get("prompt_eval_count")) {
            self.input_tokens = Some(input);
        }
        if let Some(output) = token_count(value.get("eval_count")) {
            self.output_tokens = Some(output);
        }

        if value.get("done").and_then(Value::as_bool) == Some(true) {
            let reason = value
                .get("done_reason")
                .and_then(Value::as_str)
                .map(map_finish_reason)
                .unwrap_or(FinishReason::Stop);
            self.finish_reason = Some(reason);
            events.push(StreamEvent::Finish { reason });
            self.finished = true;
            self.emit_usage(&mut events);
        }

        events
    }

    /// Return whether an error has been recorded.
    pub fn has_error(&self) -> bool {
        self.error.is_some()
    }

    /// Record an error unless one is already present.
    pub fn set_error(&mut self, error: ChatError) {
        if self.error.is_none() {
            self.error = Some(error);
        }
    }

    /// Finish decoding and return trailing events plus the normalized summary.
    ///
    /// A stream that started but never reported `done` emits `finish` with
    /// `unknown`, matching the web adapter. A stream that never started is
    /// treated as a parse failure so an empty 200 response is not mistaken for
    /// a successful completion.
    pub fn finish(mut self) -> (Vec<StreamEvent>, StreamSummary) {
        let mut events = Vec::new();

        if self.error.is_none() && !self.finished {
            if self.started {
                self.finish_reason = Some(FinishReason::Unknown);
                events.push(StreamEvent::Finish {
                    reason: FinishReason::Unknown,
                });
                self.finished = true;
                self.emit_usage(&mut events);
            } else {
                self.error = Some(parse_error("The Ollama stream ended before any content."));
            }
        }

        let usage = self.usage.clone().or_else(|| self.build_usage());
        let Self {
            text,
            reasoning,
            finish_reason,
            error,
            partial_output,
            started_at,
            first_token_at,
            ..
        } = self;

        let retry_allowed =
            error.as_ref().is_some_and(|error| error.retryable) && !partial_output.has_any();
        let latency_ms = u64::try_from(started_at.elapsed().as_millis()).unwrap_or(u64::MAX);
        let first_token_ms = first_token_at
            .map(|at| u64::try_from(at.duration_since(started_at).as_millis()).unwrap_or(u64::MAX));

        (
            events,
            StreamSummary {
                response_id: None,
                text,
                reasoning,
                usage,
                finish_reason,
                error,
                partial_output,
                retry_allowed,
                first_token_ms,
                latency_ms,
            },
        )
    }

    fn push_text(&mut self, text: &str, events: &mut Vec<StreamEvent>) {
        self.text.push_str(text);
        self.partial_output.mark_text();
        self.mark_first_token();
        events.push(StreamEvent::TextDelta {
            text: text.to_owned(),
        });
    }

    fn push_reasoning(&mut self, text: &str, events: &mut Vec<StreamEvent>) {
        self.reasoning.push_str(text);
        self.partial_output.mark_reasoning();
        self.mark_first_token();
        events.push(StreamEvent::ReasoningDelta {
            text: text.to_owned(),
        });
    }

    fn emit_usage(&mut self, events: &mut Vec<StreamEvent>) {
        if self.usage_emitted {
            return;
        }

        let Some(usage) = self.build_usage() else {
            return;
        };

        self.usage_emitted = true;
        self.usage = Some(usage.clone());
        events.push(StreamEvent::Usage { usage });
    }

    fn build_usage(&self) -> Option<Usage> {
        if self.input_tokens.is_none() && self.output_tokens.is_none() {
            return None;
        }

        let total_tokens = match (self.input_tokens, self.output_tokens) {
            (Some(input), Some(output)) => input.checked_add(output),
            _ => None,
        };

        Some(Usage {
            input_tokens: self.input_tokens,
            output_tokens: self.output_tokens,
            total_tokens,
            cost_usd: None,
            estimated: false,
        })
    }

    fn mark_first_token(&mut self) {
        if self.first_token_at.is_none() {
            self.first_token_at = Some(Instant::now());
        }
    }
}

/// Map an Ollama `done_reason` to the shared [`FinishReason`].
pub fn map_finish_reason(reason: &str) -> FinishReason {
    match reason {
        "stop" => FinishReason::Stop,
        "length" => FinishReason::Length,
        "content_filter" => FinishReason::ContentFilter,
        "tool_calls" => FinishReason::ToolCalls,
        _ => FinishReason::Unknown,
    }
}

/// Map an Ollama HTTP error response to a normalized [`ChatError`].
///
/// Ollama uses `{ "error": "message" }` for most failures and
/// `{ "error": { "message": ... } }` for some. Both are normalized here.
pub fn map_http_error(
    status: u16,
    retry_after_seconds: Option<f64>,
    body: Option<&Value>,
    request_id: Option<String>,
) -> ChatError {
    let message = body
        .and_then(error_message)
        .unwrap_or_else(|| default_message(Some(status)));
    let retryable = status == 429 || status >= 500;
    let details = retry_after_seconds.map(|seconds| json!({ "retryAfterSeconds": seconds }));

    ChatError {
        code: ollama_code(Some(status), &message),
        message,
        provider_id: Some(OLLAMA_PROVIDER_ID.to_owned()),
        status: Some(status),
        retryable,
        retry_after_seconds,
        request_id,
        details,
        at: Timestamp::now(),
    }
}

/// Map an Ollama in-stream `error` line to a normalized [`ChatError`].
///
/// In-stream errors are never retryable: the stream has already started and may
/// have emitted partial text or reasoning.
pub fn map_stream_error(payload: &Value, response_id: Option<String>) -> ChatError {
    let status = payload
        .get("status")
        .and_then(Value::as_u64)
        .and_then(|value| u16::try_from(value).ok())
        .unwrap_or(500);
    let message = error_message(payload).unwrap_or_else(|| default_message(Some(status)));

    ChatError {
        code: ollama_code(Some(status), &message),
        message,
        provider_id: Some(OLLAMA_PROVIDER_ID.to_owned()),
        status: Some(status),
        retryable: false,
        retry_after_seconds: None,
        request_id: response_id,
        details: None,
        at: Timestamp::now(),
    }
}

fn error_message(payload: &Value) -> Option<String> {
    if let Some(error) = payload.get("error") {
        if let Some(text) = error.as_str().filter(|text| !text.trim().is_empty()) {
            return Some(text.to_owned());
        }
        if let Some(message) = error
            .get("message")
            .and_then(Value::as_str)
            .filter(|text| !text.trim().is_empty())
        {
            return Some(message.to_owned());
        }
    }

    payload
        .get("message")
        .and_then(Value::as_str)
        .filter(|text| !text.trim().is_empty())
        .map(str::to_owned)
}

fn ollama_code(status: Option<u16>, message: &str) -> ChatErrorCode {
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
            } else if is_content_filter(message) {
                ChatErrorCode::ContentFilter
            } else {
                ChatErrorCode::InvalidRequest
            }
        }
        Some(500..=599) => ChatErrorCode::Provider,
        _ => ChatErrorCode::Provider,
    }
}

fn is_context_length(message: &str) -> bool {
    let message = message.to_ascii_lowercase();
    message.contains("context length")
        || message.contains("context_length")
        || message.contains("too many tokens")
        || message.contains("too long")
}

fn is_content_filter(message: &str) -> bool {
    let message = message.to_ascii_lowercase();
    message.contains("content filter")
        || message.contains("content_filter")
        || message.contains("safety")
        || message.contains("policy")
        || message.contains("blocked")
        || message.contains("moderation")
}

fn serialize_message(
    message: &NormalizedChatMessage,
    attachments: &BTreeMap<String, ResolvedAttachment>,
) -> Result<Value, ChatError> {
    let mut text = String::new();
    let mut images = Vec::new();

    for part in &message.parts {
        match part {
            ContentPart::Text { text: part_text } => {
                if !part_text.is_empty() {
                    text.push_str(part_text);
                }
            }
            ContentPart::Image { attachment_id, .. } => {
                let attachment = attachments.get(attachment_id).ok_or_else(|| {
                    invalid_request_error(format!(
                        "Attachment {attachment_id} is missing from the resolved request."
                    ))
                })?;
                images.push(Value::String(attachment.data_base64.clone()));
            }
            ContentPart::File {
                attachment_id,
                mime_type,
                name,
            } => {
                let attachment = attachments.get(attachment_id).ok_or_else(|| {
                    invalid_request_error(format!(
                        "Attachment {attachment_id} is missing from the resolved request."
                    ))
                })?;
                let file_name = if attachment.name.trim().is_empty() {
                    name.as_str()
                } else {
                    attachment.name.as_str()
                };
                let media_type = if attachment.mime_type.trim().is_empty() {
                    mime_type.as_str()
                } else {
                    attachment.mime_type.as_str()
                };
                let body = if media_type.to_ascii_lowercase().starts_with("text/") {
                    let bytes = BASE64_STANDARD
                        .decode(&attachment.data_base64)
                        .map_err(|_| {
                            invalid_request_error(format!(
                                "Attachment {attachment_id} is not valid base64."
                            ))
                        })?;
                    String::from_utf8(bytes).map_err(|_| {
                        invalid_request_error(format!(
                            "Attachment {attachment_id} is not valid UTF-8 text."
                        ))
                    })?
                } else {
                    String::new()
                };

                text.push_str(&format!("\n\n[File: {file_name}]\n{body}"));
            }
            ContentPart::Reasoning { .. }
            | ContentPart::ToolCall { .. }
            | ContentPart::ToolResult { .. } => {
                // Reasoning and tools are not replayed in phase 1.
            }
        }
    }

    let mut entry = Map::new();
    entry.insert("role".to_owned(), json!(message.role));
    entry.insert("content".to_owned(), Value::String(text));
    if !images.is_empty() {
        entry.insert("images".to_owned(), Value::Array(images));
    }

    Ok(Value::Object(entry))
}

fn advanced_namespace(
    extra: &Map<String, Value>,
) -> Result<Option<&Map<String, Value>>, ChatError> {
    match extra.get(OLLAMA_EXTRA_NAMESPACE) {
        None | Some(Value::Null) => Ok(None),
        Some(Value::Object(map)) => Ok(Some(map)),
        Some(_) => Err(invalid_request_error(
            "Advanced JSON must be a JSON object.",
        )),
    }
}

fn reject_protected_fields(advanced: Option<&Map<String, Value>>) -> Result<(), ChatError> {
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

    let mut error = invalid_request_error(message);
    error.details = Some(json!({ "protectedFields": protected }));
    Err(error)
}

fn without_transport_control(request: &NormalizedChatRequest) -> NormalizedChatRequest {
    let Some(extra) = request
        .params
        .extra
        .get(OLLAMA_EXTRA_NAMESPACE)
        .and_then(Value::as_object)
    else {
        return request.clone();
    };

    if !extra.contains_key("transport") && !extra.contains_key("mode") {
        return request.clone();
    }

    let mut next_extra = request.params.extra.clone();
    let mut next_ollama = extra.clone();
    next_ollama.remove("transport");
    next_ollama.remove("mode");
    next_extra.insert(
        OLLAMA_EXTRA_NAMESPACE.to_owned(),
        Value::Object(next_ollama),
    );

    let mut next = request.clone();
    next.params.extra = next_extra;
    next
}

fn supports(supported_parameters: &[String], name: &str) -> bool {
    supported_parameters
        .iter()
        .any(|parameter| parameter == name)
}

fn discovery_headers(ctx: &ProviderContext) -> BTreeMap<String, String> {
    let mut headers = BTreeMap::new();

    for (name, value) in &ctx.headers {
        headers.insert(name.clone(), value.clone());
    }

    headers.retain(|name, _| {
        !name.eq_ignore_ascii_case("content-type") && !name.eq_ignore_ascii_case("accept")
    });
    headers.insert("Accept".to_owned(), "application/json".to_owned());

    headers
}

fn token_count(value: Option<&Value>) -> Option<u32> {
    value
        .and_then(Value::as_u64)
        .and_then(|value| u32::try_from(value).ok())
}

fn extract_request_id(headers: &HeaderMap) -> Option<String> {
    const NAMES: [&str; 3] = ["x-request-id", "request-id", "x-generation-id"];

    for name in NAMES {
        if let Some(value) = headers.get(name).and_then(|value| value.to_str().ok()) {
            return Some(value.to_owned());
        }
    }

    None
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

fn invalid_request_error(message: impl Into<String>) -> ChatError {
    ChatError {
        code: ChatErrorCode::InvalidRequest,
        message: message.into(),
        provider_id: Some(OLLAMA_PROVIDER_ID.to_owned()),
        status: None,
        retryable: false,
        retry_after_seconds: None,
        request_id: None,
        details: None,
        at: Timestamp::now(),
    }
}

fn parse_error(message: impl Into<String>) -> ChatError {
    ChatError {
        code: ChatErrorCode::Parse,
        message: message.into(),
        provider_id: Some(OLLAMA_PROVIDER_ID.to_owned()),
        status: None,
        retryable: false,
        retry_after_seconds: None,
        request_id: None,
        details: None,
        at: Timestamp::now(),
    }
}

fn cancelled_error() -> ChatError {
    ChatError {
        code: ChatErrorCode::Cancelled,
        message: "The request was cancelled.".to_owned(),
        provider_id: Some(OLLAMA_PROVIDER_ID.to_owned()),
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
    use chatui_domain::{ChatParams, MessageRole};
    use serde_json::json;

    fn request() -> NormalizedChatRequest {
        NormalizedChatRequest {
            model: "llama3.2:latest".to_owned(),
            system: None,
            messages: vec![NormalizedChatMessage {
                role: MessageRole::User,
                parts: vec![ContentPart::Text {
                    text: "Hello".to_owned(),
                }],
            }],
            params: ChatParams::default(),
        }
    }

    #[test]
    fn native_base_url_strips_the_v1_suffix() {
        assert_eq!(
            ollama_native_base_url("http://localhost:11434/v1"),
            "http://localhost:11434"
        );
        assert_eq!(
            ollama_native_base_url("http://localhost:11434/v1/"),
            "http://localhost:11434"
        );
        assert_eq!(
            ollama_native_base_url("http://localhost:11434"),
            "http://localhost:11434"
        );
    }

    #[test]
    fn native_urls_use_the_server_root() {
        assert_eq!(
            build_chat_url("http://localhost:11434/v1")
                .expect("chat url")
                .as_str(),
            "http://localhost:11434/api/chat"
        );
        assert_eq!(
            build_tags_url("http://localhost:11434/v1")
                .expect("tags url")
                .as_str(),
            "http://localhost:11434/api/tags"
        );
    }

    #[test]
    fn system_prompt_is_the_first_message() {
        let mut request = request();
        request.system = Some("You are concise.".to_owned());
        let body = build_body(&request, &[], &BTreeMap::new()).expect("body builds");
        let messages = body
            .get("messages")
            .and_then(Value::as_array)
            .expect("messages");

        assert_eq!(
            messages[0],
            json!({ "role": "system", "content": "You are concise." })
        );
        assert_eq!(messages[1], json!({ "role": "user", "content": "Hello" }));
    }

    #[test]
    fn images_and_files_serialize_into_the_native_shape() {
        let mut request = request();
        request.messages[0].parts = vec![
            ContentPart::Text {
                text: "Describe these.".to_owned(),
            },
            ContentPart::Image {
                attachment_id: "att-image-1".to_owned(),
                mime_type: "image/png".to_owned(),
                alt: "A single pixel.".to_owned(),
            },
            ContentPart::File {
                attachment_id: "att-file-1".to_owned(),
                mime_type: "text/plain".to_owned(),
                name: "notes.txt".to_owned(),
            },
        ];
        let mut attachments = BTreeMap::new();
        attachments.insert(
            "att-image-1".to_owned(),
            ResolvedAttachment {
                mime_type: "image/png".to_owned(),
                name: "pixel.png".to_owned(),
                data_base64: "aW1hZ2U=".to_owned(),
            },
        );
        attachments.insert(
            "att-file-1".to_owned(),
            ResolvedAttachment {
                mime_type: "text/plain".to_owned(),
                name: "notes.txt".to_owned(),
                data_base64: "SGVsbG8gYXR0YWNobWVudC4=".to_owned(),
            },
        );

        let body = build_body(&request, &[], &attachments).expect("body builds");
        let message = &body
            .get("messages")
            .and_then(Value::as_array)
            .expect("messages")[0];

        assert_eq!(
            message.get("content"),
            Some(&json!(
                "Describe these.\n\n[File: notes.txt]\nHello attachment."
            ))
        );
        assert_eq!(message.get("images"), Some(&json!(["aW1hZ2U="])));
    }

    #[test]
    fn sampling_controls_map_to_options() {
        let mut request = request();
        request.params.max_output_tokens = Some(256);
        request.params.temperature = Some(0.7);
        request.params.top_p = Some(0.9);
        request.params.stop = vec!["STOP".to_owned()];

        let body = build_body(
            &request,
            &[
                "temperature".to_owned(),
                "top_p".to_owned(),
                "max_tokens".to_owned(),
                "stop".to_owned(),
            ],
            &BTreeMap::new(),
        )
        .expect("body builds");

        assert_eq!(
            body.get("options"),
            Some(&json!({
                "num_predict": 256,
                "temperature": 0.7,
                "top_p": 0.9,
                "stop": ["STOP"]
            }))
        );
    }

    #[test]
    fn protected_fields_are_rejected_with_sorted_details() {
        let mut request = request();
        request.params.extra.insert(
            "ollama".to_owned(),
            json!({
                "model": "attacker/model",
                "messages": [],
                "stream": false,
                "stream_options": { "include_usage": false }
            }),
        );

        let error = build_body(&request, &[], &BTreeMap::new()).expect_err("protected fields");
        assert_eq!(error.code, ChatErrorCode::InvalidRequest);
        assert_eq!(
            error.details,
            Some(json!({
                "protectedFields": ["messages", "model", "stream", "stream_options"]
            }))
        );
    }

    #[test]
    fn finish_reasons_match_the_shared_enum() {
        assert_eq!(map_finish_reason("stop"), FinishReason::Stop);
        assert_eq!(map_finish_reason("length"), FinishReason::Length);
        assert_eq!(
            map_finish_reason("content_filter"),
            FinishReason::ContentFilter
        );
        assert_eq!(map_finish_reason("tool_calls"), FinishReason::ToolCalls);
        assert_eq!(map_finish_reason("other"), FinishReason::Unknown);
    }

    #[test]
    fn stream_maps_text_finish_and_usage() {
        let mut decoder = OllamaStreamDecoder::new();
        let mut events = Vec::new();

        for line in [
            r#"{"message":{"role":"assistant","content":"Hello"},"done":false}"#,
            r#"{"message":{"role":"assistant","content":" from Ollama."},"done":false}"#,
            r#"{"message":{"role":"assistant","content":""},"done":true,"done_reason":"stop","prompt_eval_count":8,"eval_count":5}"#,
        ] {
            events.extend(decoder.push(line));
        }

        let (trailing, summary) = decoder.finish();
        events.extend(trailing);

        assert_eq!(summary.text, "Hello from Ollama.");
        assert_eq!(summary.finish_reason, Some(FinishReason::Stop));
        assert_eq!(
            summary.usage,
            Some(Usage {
                input_tokens: Some(8),
                output_tokens: Some(5),
                total_tokens: Some(13),
                cost_usd: None,
                estimated: false,
            })
        );
        assert!(events.iter().any(|event| matches!(
            event,
            StreamEvent::Finish {
                reason: FinishReason::Stop
            }
        )));
        assert!(events.iter().any(|event| matches!(
            event,
            StreamEvent::Usage { usage } if usage.total_tokens == Some(13)
        )));
    }

    #[test]
    fn stream_error_maps_to_a_non_retryable_provider_error() {
        let error = map_stream_error(&json!({ "error": "internal server error" }), None);

        assert_eq!(error.code, ChatErrorCode::Provider);
        assert_eq!(error.status, Some(500));
        assert_eq!(error.message, "internal server error");
        assert!(!error.retryable);
    }

    #[test]
    fn http_error_reads_string_and_object_envelopes() {
        let string_error = map_http_error(
            404,
            None,
            Some(&json!({ "error": "model 'missing-model' not found" })),
            None,
        );
        assert_eq!(string_error.code, ChatErrorCode::ModelNotFound);
        assert_eq!(string_error.message, "model 'missing-model' not found");
        assert!(!string_error.retryable);

        let object_error = map_http_error(
            500,
            None,
            Some(&json!({ "error": { "message": "internal server error" } })),
            None,
        );
        assert_eq!(object_error.code, ChatErrorCode::Provider);
        assert_eq!(object_error.message, "internal server error");
        assert!(object_error.retryable);
    }

    #[test]
    fn transport_override_selects_the_openai_compatible_fallback() {
        let preset = chatui_contracts::provider_catalog()
            .get("ollama")
            .expect("ollama preset")
            .clone();
        let mut override_request = request();
        override_request.params.extra.insert(
            "ollama".to_owned(),
            json!({ "transport": "openai-compatible" }),
        );

        assert_eq!(
            select_transport(&preset, &override_request),
            OllamaTransport::OpenAiCompatible
        );
        assert_eq!(
            select_transport(&preset, &request()),
            OllamaTransport::Native
        );
    }
}
