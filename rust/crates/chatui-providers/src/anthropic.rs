//! Native Anthropic Messages adapter.
//!
//! Anthropic is not OpenAI-compatible: the system prompt is a top-level field,
//! messages carry ordered text and base64 image blocks, `max_tokens` is
//! mandatory, and the SSE stream uses Anthropic event names. This module keeps
//! every Anthropic-specific rule inside the adapter and reuses the shared HTTP
//! client, SSE parser, error mapper, and stream summary.
//!
//! Mapping rules from spec sections 7.5, 7.6, and 7.9:
//!
//! - headers are `x-api-key`, `anthropic-version: 2023-06-01`,
//!   `content-type: application/json`, and `Accept: text/event-stream`;
//! - the web-only `anthropic-dangerous-direct-browser-access` header is never
//!   sent by the desktop adapter, even though the shared contract lists it in
//!   `defaultHeaders` for the web client;
//! - the system prompt is the top-level `system` field, never a message;
//! - text parts become `text` blocks, images become base64 `image` blocks, and
//!   text files are appended as text blocks with a deterministic header;
//! - `max_tokens` is always emitted and defaults to 1024 when unset;
//! - `stop` maps to `stop_sequences`;
//! - `message_start` emits `start` and captures initial usage;
//! - `content_block_start` and `content_block_delta` map text and thinking
//!   deltas to separate `text-delta` and `reasoning-delta` events;
//! - `message_delta` emits merged usage and records the stop reason;
//! - `message_stop` emits `finish`;
//! - `error` normalizes to the shared [`ChatError`].
//!
//! Credentials are sent only in the `x-api-key` header. No error message, URL,
//! or debug output contains the key.

#![allow(clippy::result_large_err)]

use std::collections::BTreeMap;
use std::time::Instant;

use base64::Engine as _;
use base64::engine::general_purpose::STANDARD as BASE64_STANDARD;
use chatui_domain::{
    ChatError, ChatErrorCode, ContentPart, FinishReason, MessageRole, StreamEvent, Timestamp, Usage,
};
use reqwest::header::{HeaderMap, HeaderName, RETRY_AFTER};
use reqwest::{Method, Url};
use serde_json::{Map, Value, json};
use tokio_util::sync::CancellationToken;

use crate::adapter::ProviderContext;
use crate::error::{NetworkError, NetworkErrorKind, parse_retry_after};
use crate::http::{HttpClient, SecretHeader};
use crate::openai_catalog::{
    OpenAiCatalogError, anthropic_catalog_defaults, map_models as map_openai_models,
};
use crate::openai_compatible::{default_message, network_error_to_chat_error};
use crate::openrouter::catalog::CatalogModel;
use crate::openrouter::errors::map_error_object_for;
use crate::openrouter::errors::map_http_error_for;
use crate::openrouter::key::ProviderKeyInfo;
use crate::openrouter::request::{
    NormalizedChatMessage, NormalizedChatRequest, PreparedChatRequest, ResolvedAttachment,
};
use crate::openrouter::stream::StreamSummary;
use crate::retry::PartialOutput;
use crate::sse::{DEFAULT_MAX_SSE_BUFFER_BYTES, SseError, read_sse};
use crate::url_policy::{UrlPolicyError, join_url};

/// Provider ID used on every normalized Anthropic error.
pub const ANTHROPIC_PROVIDER_ID: &str = "anthropic";

/// Anthropic API version sent with every request.
pub const ANTHROPIC_VERSION: &str = "2023-06-01";

/// Default `max_tokens` when the caller did not set one.
pub const DEFAULT_MAX_TOKENS: u32 = 1024;

/// Web-only browser access header that the desktop adapter must not send.
pub const BROWSER_ACCESS_HEADER: &str = "anthropic-dangerous-direct-browser-access";

/// Fields advanced JSON may never override.
const PROTECTED_FIELDS: [&str; 4] = ["model", "messages", "stream", "stream_options"];

/// Namespace that owns Anthropic-only advanced JSON.
const EXTRA_NAMESPACE: &str = "anthropic";

/// The native Anthropic adapter.
///
/// The adapter stores only the preset ID. Every request reads the contract
/// preset from [`ProviderContext`], so no provider metadata is duplicated.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct AnthropicAdapter {
    preset_id: String,
}

impl AnthropicAdapter {
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

/// Build the exact Anthropic `POST /messages` body.
///
/// The function is pure so tests can assert the serialized JSON against the
/// shared request vectors. It rejects protected-field overrides before any
/// network call. `max_tokens` is always present and defaults to
/// [`DEFAULT_MAX_TOKENS`].
pub fn build_body(
    request: &NormalizedChatRequest,
    supported_parameters: &[String],
    attachments: &BTreeMap<String, ResolvedAttachment>,
) -> Result<Value, ChatError> {
    let advanced = advanced_namespace(&request.params.extra)?;
    reject_protected_fields(advanced)?;

    let mut body = Map::new();
    body.insert("model".to_owned(), Value::String(request.model.clone()));

    if let Some(system) = &request.system {
        body.insert("system".to_owned(), Value::String(system.clone()));
    }

    let mut messages = Vec::with_capacity(request.messages.len());
    for message in &request.messages {
        messages.push(serialize_message(message, attachments)?);
    }
    body.insert("messages".to_owned(), Value::Array(messages));

    body.insert(
        "max_tokens".to_owned(),
        json!(
            request
                .params
                .max_output_tokens
                .unwrap_or(DEFAULT_MAX_TOKENS)
        ),
    );
    body.insert("stream".to_owned(), Value::Bool(true));

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
        body.insert("stop_sequences".to_owned(), json!(request.params.stop));
    }

    if let Some(advanced) = advanced {
        for (field, value) in advanced {
            if PROTECTED_FIELDS.contains(&field.as_str()) {
                continue;
            }
            body.insert(field.clone(), value.clone());
        }
    }

    Ok(Value::Object(body))
}

/// Build the provider request body and non-secret headers.
///
/// The API key is not part of the returned headers. [`stream_chat`] and
/// [`validate_key`] add it as a [`SecretHeader`] so it never enters a
/// non-secret structure.
pub fn prepare_request(
    ctx: &ProviderContext,
    request: &NormalizedChatRequest,
    supported_parameters: &[String],
    attachments: &BTreeMap<String, ResolvedAttachment>,
) -> Result<PreparedChatRequest, ChatError> {
    Ok(PreparedChatRequest {
        body: build_body(request, supported_parameters, attachments)?,
        headers: build_headers(ctx),
    })
}

/// Build the non-secret Anthropic request headers.
///
/// The contract's web-only browser access header is removed. `anthropic-version`
/// falls back to [`ANTHROPIC_VERSION`] when the preset omits it.
pub fn build_headers(ctx: &ProviderContext) -> BTreeMap<String, String> {
    let mut headers = BTreeMap::new();

    for (name, value) in &ctx.headers {
        if name.eq_ignore_ascii_case(BROWSER_ACCESS_HEADER) {
            continue;
        }
        headers.insert(name.clone(), value.clone());
    }

    headers.retain(|name, _| {
        !name.eq_ignore_ascii_case("content-type") && !name.eq_ignore_ascii_case("accept")
    });

    if !headers
        .keys()
        .any(|name| name.eq_ignore_ascii_case("anthropic-version"))
    {
        headers.insert("anthropic-version".to_owned(), ANTHROPIC_VERSION.to_owned());
    }

    headers.insert("content-type".to_owned(), "application/json".to_owned());
    headers.insert("Accept".to_owned(), "text/event-stream".to_owned());

    headers
}

/// Validate an Anthropic key by loading `GET /models`.
///
/// Anthropic has no dedicated key-introspection endpoint. A successful catalog
/// load proves the key works; the returned metadata is empty because Anthropic
/// does not expose OpenRouter-style key details.
pub async fn validate_key(
    client: &HttpClient,
    ctx: &ProviderContext,
    cancel: &CancellationToken,
) -> Result<ProviderKeyInfo, ChatError> {
    list_models(client, ctx, cancel).await?;
    Ok(empty_key_info())
}

/// Load the Anthropic model catalog with `GET /models`.
///
/// The key is sent only in the `x-api-key` header. The response is mapped with
/// the Anthropic preset defaults so missing capability metadata still produces
/// the shared [`CatalogModel`] shape.
pub async fn list_models(
    client: &HttpClient,
    ctx: &ProviderContext,
    cancel: &CancellationToken,
) -> Result<Vec<CatalogModel>, ChatError> {
    let url = join_endpoint(&ctx.base_url, &ctx.preset.model_discovery.path)
        .map_err(|error| invalid_request_error(format!("The Anthropic URL is invalid: {error}")))?;
    let auth = auth_header(ctx)?;

    let request = async {
        let prepared = client.prepare(Method::GET, &url).map_err(|error| {
            invalid_request_error(format!("The Anthropic URL is invalid: {error}"))
        })?;
        let mut builder = auth.apply(prepared.into_builder());

        for (name, value) in build_headers(ctx) {
            // A GET has no body, so the content type is unnecessary.
            if name.eq_ignore_ascii_case("content-type") {
                continue;
            }
            builder = builder.header(name, value);
        }

        let response = client
            .send(builder)
            .await
            .map_err(|error| network_error_to_chat_error(error, ANTHROPIC_PROVIDER_ID))?;
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
                ANTHROPIC_PROVIDER_ID,
                status.as_u16(),
                retry_after,
                body.as_ref(),
                request_id,
                default_message,
            ));
        }

        let bytes = response.bytes().await.map_err(|error| {
            network_error_to_chat_error(NetworkError::from_reqwest(error), ANTHROPIC_PROVIDER_ID)
        })?;
        let payload: Value = serde_json::from_slice(&bytes).map_err(|error| {
            parse_error(format!(
                "The Anthropic /models response was not valid JSON: {error}"
            ))
        })?;

        map_models(&payload).map_err(|error| {
            parse_error(format!(
                "The Anthropic /models response was not valid: {error}"
            ))
        })
    };

    tokio::select! {
        result = request => result,
        _ = cancel.cancelled() => Err(cancelled_error()),
    }
}

/// Map an Anthropic `/models` payload to normalized catalog entries.
pub fn map_models(payload: &Value) -> Result<Vec<CatalogModel>, OpenAiCatalogError> {
    map_openai_models(payload, &anthropic_catalog_defaults())
}

/// Stream an Anthropic Messages completion.
///
/// Events are emitted in the shared order: `start`, text/reasoning deltas,
/// `usage`, `finish`, or a single terminal `error`. Thinking deltas stay in
/// `reasoning-delta` and are never mixed into answer text. Cancellation stops
/// the stream and preserves any partial text or reasoning in the returned
/// [`StreamSummary`].
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
    let prepared = prepare_request(ctx, request, supported_parameters, attachments)?;
    let url = join_endpoint(&ctx.base_url, "messages")
        .map_err(|error| invalid_request_error(format!("The Anthropic URL is invalid: {error}")))?;
    let auth = auth_header(ctx)?;

    let prepared_request = client.prepare(Method::POST, &url).map_err(|error| {
        invalid_request_error(format!("The Anthropic chat URL is invalid: {error}"))
    })?;
    let mut builder = prepared_request.into_builder().json(&prepared.body);
    builder = auth.apply(builder);

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
        .map_err(|error| network_error_to_chat_error(error, ANTHROPIC_PROVIDER_ID))?;

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
            ANTHROPIC_PROVIDER_ID,
            status.as_u16(),
            retry_after,
            body.as_ref(),
            request_id,
            default_message,
        ));
    }

    let read_cancel = cancel.child_token();
    let cancel_for_closure = read_cancel.clone();
    let mut decoder = AnthropicStreamDecoder::new();

    let read_result = read_sse(
        response.bytes_stream(),
        |data| {
            for event in decoder.push(data) {
                on_event(event);
            }
            if decoder.has_error() {
                cancel_for_closure.cancel();
            }
        },
        &read_cancel,
        DEFAULT_MAX_SSE_BUFFER_BYTES,
    )
    .await;

    match read_result {
        Ok(()) => {}
        Err(SseError::Cancelled) => {
            if !decoder.has_error() {
                decoder.set_error(cancelled_error());
            }
        }
        Err(SseError::Stream { message }) => {
            if !decoder.has_error() {
                let error = NetworkError::new(
                    NetworkErrorKind::Connection,
                    format!("The Anthropic stream failed: {message}"),
                );
                decoder.set_error(error.to_chat_error(Some(ANTHROPIC_PROVIDER_ID.to_owned())));
            }
        }
        Err(SseError::BufferOverflow { max_bytes }) => {
            if !decoder.has_error() {
                decoder.set_error(parse_error(format!(
                    "The Anthropic stream exceeded the {max_bytes} byte buffer limit."
                )));
            }
        }
        Err(SseError::InvalidUtf8) => {
            if !decoder.has_error() {
                decoder.set_error(parse_error("The Anthropic stream contained invalid UTF-8."));
            }
        }
    }

    Ok(decoder.finish())
}

/// Incremental Anthropic SSE decoder.
///
/// Feed each `data:` payload from the shared SSE parser to [`Self::push`] and
/// forward the returned events to the caller. Call [`Self::finish`] once the
/// stream ends to obtain the normalized summary.
#[derive(Debug)]
pub struct AnthropicStreamDecoder {
    started: bool,
    done: bool,
    response_id: Option<String>,
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

impl Default for AnthropicStreamDecoder {
    fn default() -> Self {
        Self::new()
    }
}

impl AnthropicStreamDecoder {
    /// Create a decoder with no events seen.
    pub fn new() -> Self {
        Self {
            started: false,
            done: false,
            response_id: None,
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

    /// Decode one SSE `data:` payload and return the events to emit.
    ///
    /// Once an error has been recorded, later payloads are ignored so partial
    /// content is preserved and no further events are emitted.
    pub fn push(&mut self, data: &str) -> Vec<StreamEvent> {
        let mut events = Vec::new();

        if self.done || self.error.is_some() {
            return events;
        }

        let data = data.trim();
        if data.is_empty() {
            return events;
        }

        let value: Value = match serde_json::from_str(data) {
            Ok(value) => value,
            Err(error) => {
                self.set_error(parse_error(format!(
                    "The Anthropic stream contained invalid JSON: {error}"
                )));
                events.push(StreamEvent::Error {
                    error: self.error.clone().expect("error was just set"),
                });
                return events;
            }
        };

        let Some(event_type) = value.get("type").and_then(Value::as_str) else {
            return events;
        };

        match event_type {
            "message_start" => self.handle_message_start(&value, &mut events),
            "content_block_start" => self.handle_content_block_start(&value, &mut events),
            "content_block_delta" => self.handle_content_block_delta(&value, &mut events),
            "content_block_stop" => {}
            "message_delta" => self.handle_message_delta(&value, &mut events),
            "message_stop" => self.handle_message_stop(&mut events),
            "error" => {
                let error = map_stream_error(&value, self.response_id.clone());
                self.error = Some(error.clone());
                events.push(StreamEvent::Error { error });
            }
            _ => {}
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

    /// Finish decoding and return the normalized summary.
    pub fn finish(self) -> StreamSummary {
        let usage = self.usage.clone().or_else(|| self.build_usage());
        let Self {
            done,
            response_id,
            text,
            reasoning,
            finish_reason,
            error,
            partial_output,
            started_at,
            first_token_at,
            ..
        } = self;

        let mut error = error;
        if error.is_none() && finish_reason.is_none() && !done {
            error = Some(parse_error(
                "The Anthropic stream ended before a finish event.",
            ));
        }

        let retry_allowed =
            error.as_ref().is_some_and(|error| error.retryable) && !partial_output.has_any();
        let latency_ms = u64::try_from(started_at.elapsed().as_millis()).unwrap_or(u64::MAX);
        let first_token_ms = first_token_at
            .map(|at| u64::try_from(at.duration_since(started_at).as_millis()).unwrap_or(u64::MAX));

        StreamSummary {
            response_id,
            text,
            reasoning,
            usage,
            finish_reason,
            error,
            partial_output,
            retry_allowed,
            first_token_ms,
            latency_ms,
        }
    }

    fn handle_message_start(&mut self, value: &Value, events: &mut Vec<StreamEvent>) {
        if let Some(message) = value.get("message") {
            if let Some(id) = message.get("id").and_then(Value::as_str) {
                self.response_id = Some(id.to_owned());
            }
            if let Some(usage) = message.get("usage") {
                self.input_tokens = token_count(usage.get("input_tokens"));
                self.output_tokens = token_count(usage.get("output_tokens"));
            }
        }

        if !self.started {
            self.started = true;
            events.push(StreamEvent::Start {
                response_id: self.response_id.clone(),
            });
        }
    }

    fn handle_content_block_start(&mut self, value: &Value, events: &mut Vec<StreamEvent>) {
        let Some(block) = value.get("content_block") else {
            return;
        };

        match block.get("type").and_then(Value::as_str) {
            Some("text") => {
                if let Some(text) = block
                    .get("text")
                    .and_then(Value::as_str)
                    .filter(|text| !text.is_empty())
                {
                    self.push_text(text, events);
                }
            }
            Some("thinking") => {
                if let Some(text) = block
                    .get("thinking")
                    .and_then(Value::as_str)
                    .filter(|text| !text.is_empty())
                {
                    self.push_reasoning(text, events);
                }
            }
            _ => {}
        }
    }

    fn handle_content_block_delta(&mut self, value: &Value, events: &mut Vec<StreamEvent>) {
        let Some(delta) = value.get("delta") else {
            return;
        };

        match delta.get("type").and_then(Value::as_str) {
            Some("text_delta") => {
                if let Some(text) = delta
                    .get("text")
                    .and_then(Value::as_str)
                    .filter(|text| !text.is_empty())
                {
                    self.push_text(text, events);
                }
            }
            Some("thinking_delta") => {
                if let Some(text) = delta
                    .get("thinking")
                    .and_then(Value::as_str)
                    .filter(|text| !text.is_empty())
                {
                    self.push_reasoning(text, events);
                }
            }
            _ => {}
        }
    }

    fn handle_message_delta(&mut self, value: &Value, events: &mut Vec<StreamEvent>) {
        if let Some(delta) = value.get("delta")
            && let Some(reason) = delta.get("stop_reason").and_then(Value::as_str)
        {
            self.finish_reason = Some(map_finish_reason(reason));
        }

        if let Some(usage) = value.get("usage") {
            if let Some(output) = token_count(usage.get("output_tokens")) {
                self.output_tokens = Some(output);
            }
            if let Some(input) = token_count(usage.get("input_tokens")) {
                self.input_tokens = Some(input);
            }
        }

        self.emit_usage(events);
    }

    fn handle_message_stop(&mut self, events: &mut Vec<StreamEvent>) {
        self.emit_usage(events);
        let reason = self.finish_reason.unwrap_or(FinishReason::Stop);
        self.finish_reason = Some(reason);
        events.push(StreamEvent::Finish { reason });
        self.done = true;
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

/// Map an Anthropic `stop_reason` to the shared [`FinishReason`].
pub fn map_finish_reason(reason: &str) -> FinishReason {
    match reason {
        "end_turn" | "stop_sequence" => FinishReason::Stop,
        "max_tokens" => FinishReason::Length,
        "tool_use" => FinishReason::ToolCalls,
        "refusal" => FinishReason::ContentFilter,
        "cancelled" => FinishReason::Cancelled,
        "error" => FinishReason::Error,
        _ => FinishReason::Unknown,
    }
}

/// Map an Anthropic in-stream `error` event to a normalized [`ChatError`].
///
/// In-stream errors are never retryable: the stream has already started and may
/// have emitted partial text or reasoning. The response ID from `message_start`
/// is preserved as the request ID.
pub fn map_stream_error(envelope: &Value, response_id: Option<String>) -> ChatError {
    let error = envelope.get("error").unwrap_or(envelope);
    let status = anthropic_stream_status(error);

    map_error_object_for(
        ANTHROPIC_PROVIDER_ID,
        error,
        status,
        None,
        response_id,
        true,
        default_message,
    )
}

fn anthropic_stream_status(error: &Value) -> Option<u16> {
    match error.get("type").and_then(Value::as_str) {
        Some("overloaded_error") => Some(529),
        Some("server_error") => Some(500),
        Some("rate_limit_error") => Some(429),
        Some("invalid_request_error") => Some(400),
        Some("authentication_error") => Some(401),
        Some("permission_error") => Some(403),
        Some("not_found_error") => Some(404),
        _ => None,
    }
}

fn serialize_message(
    message: &NormalizedChatMessage,
    attachments: &BTreeMap<String, ResolvedAttachment>,
) -> Result<Value, ChatError> {
    let role = match message.role {
        MessageRole::Assistant => "assistant",
        MessageRole::System | MessageRole::User | MessageRole::Tool => "user",
    };

    let mut blocks = Vec::new();

    for part in &message.parts {
        match part {
            ContentPart::Text { text } => {
                if !text.is_empty() {
                    blocks.push(json!({ "type": "text", "text": text }));
                }
            }
            ContentPart::Image {
                attachment_id,
                mime_type,
                ..
            } => {
                let attachment = attachments.get(attachment_id).ok_or_else(|| {
                    invalid_request_error(format!(
                        "Attachment {attachment_id} is missing from the resolved request."
                    ))
                })?;
                let media_type = if attachment.mime_type.trim().is_empty() {
                    mime_type.as_str()
                } else {
                    attachment.mime_type.as_str()
                };

                blocks.push(json!({
                    "type": "image",
                    "source": {
                        "type": "base64",
                        "media_type": media_type,
                        "data": attachment.data_base64,
                    }
                }));
            }
            ContentPart::File {
                attachment_id,
                name,
                ..
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
                let bytes = BASE64_STANDARD
                    .decode(&attachment.data_base64)
                    .map_err(|_| {
                        invalid_request_error(format!(
                            "Attachment {attachment_id} is not valid base64."
                        ))
                    })?;
                let content = String::from_utf8(bytes).map_err(|_| {
                    invalid_request_error(format!(
                        "Attachment {attachment_id} is not valid UTF-8 text."
                    ))
                })?;
                let file_text = format!("\n\n[File: {file_name}]\n{content}");
                blocks.push(json!({ "type": "text", "text": file_text }));
            }
            ContentPart::Reasoning { .. }
            | ContentPart::ToolCall { .. }
            | ContentPart::ToolResult { .. } => {
                // Reasoning and tools are not replayed in phase 1.
            }
        }
    }

    Ok(json!({
        "role": role,
        "content": blocks,
    }))
}

fn advanced_namespace(
    extra: &Map<String, Value>,
) -> Result<Option<&Map<String, Value>>, ChatError> {
    match extra.get(EXTRA_NAMESPACE) {
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

fn supports(supported_parameters: &[String], name: &str) -> bool {
    supported_parameters
        .iter()
        .any(|parameter| parameter == name)
}

fn auth_header(ctx: &ProviderContext) -> Result<SecretHeader, ChatError> {
    let key = ctx.require_key()?;
    let name = ctx
        .preset
        .auth
        .header_name
        .as_deref()
        .unwrap_or("x-api-key");
    let name = HeaderName::from_bytes(name.as_bytes())
        .map_err(|_| invalid_request_error(format!("The {name} header name is not valid.")))?;

    SecretHeader::new(name, key)
        .map_err(|error| network_error_to_chat_error(error, ANTHROPIC_PROVIDER_ID))
}

fn join_endpoint(base_url: &str, path: &str) -> Result<Url, UrlPolicyError> {
    if path.starts_with("http://") || path.starts_with("https://") {
        join_url(base_url, path)
    } else {
        join_url(base_url, path.trim_start_matches('/'))
    }
}

fn token_count(value: Option<&Value>) -> Option<u32> {
    value
        .and_then(Value::as_u64)
        .and_then(|value| u32::try_from(value).ok())
}

fn extract_request_id(headers: &HeaderMap) -> Option<String> {
    const NAMES: [&str; 3] = ["request-id", "x-request-id", "x-generation-id"];

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
        provider_id: Some(ANTHROPIC_PROVIDER_ID.to_owned()),
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
        provider_id: Some(ANTHROPIC_PROVIDER_ID.to_owned()),
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
        provider_id: Some(ANTHROPIC_PROVIDER_ID.to_owned()),
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
    use chatui_domain::ChatParams;

    fn request() -> NormalizedChatRequest {
        NormalizedChatRequest {
            model: "claude-sonnet-4-5".to_owned(),
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
    fn max_tokens_defaults_to_1024() {
        let body = build_body(&request(), &[], &BTreeMap::new()).expect("body builds");
        assert_eq!(body.get("max_tokens"), Some(&json!(1024)));
        assert_eq!(body.get("stream"), Some(&json!(true)));
        assert!(body.get("system").is_none());
    }

    #[test]
    fn system_prompt_is_top_level() {
        let mut request = request();
        request.system = Some("You are concise.".to_owned());
        let body = build_body(&request, &[], &BTreeMap::new()).expect("body builds");

        assert_eq!(body.get("system"), Some(&json!("You are concise.")));
        assert_eq!(
            body.get("messages").and_then(Value::as_array).map(Vec::len),
            Some(1)
        );
    }

    #[test]
    fn stop_maps_to_stop_sequences() {
        let mut request = request();
        request.params.stop = vec!["STOP".to_owned()];
        let body =
            build_body(&request, &["stop".to_owned()], &BTreeMap::new()).expect("body builds");

        assert_eq!(body.get("stop_sequences"), Some(&json!(["STOP"])));
        assert!(body.get("stop").is_none());
    }

    #[test]
    fn protected_fields_are_rejected_with_sorted_details() {
        let mut request = request();
        request.params.extra.insert(
            "anthropic".to_owned(),
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
    fn thinking_and_text_deltas_stay_separate() {
        let mut decoder = AnthropicStreamDecoder::new();
        let mut events = Vec::new();

        for data in [
            r#"{"type":"message_start","message":{"id":"msg_1","usage":{"input_tokens":3,"output_tokens":1}}}"#,
            r#"{"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":""}}"#,
            r#"{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"think"}}"#,
            r#"{"type":"content_block_start","index":1,"content_block":{"type":"text","text":""}}"#,
            r#"{"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"answer"}}"#,
            r#"{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":2}}"#,
            r#"{"type":"message_stop"}"#,
        ] {
            events.extend(decoder.push(data));
        }

        let summary = decoder.finish();
        assert_eq!(summary.text, "answer");
        assert_eq!(summary.reasoning, "think");
        assert_eq!(summary.finish_reason, Some(FinishReason::Stop));
        assert_eq!(
            summary.usage,
            Some(Usage {
                input_tokens: Some(3),
                output_tokens: Some(2),
                total_tokens: Some(5),
                cost_usd: None,
                estimated: false,
            })
        );
        assert!(events.iter().any(|event| matches!(
            event,
            StreamEvent::ReasoningDelta { text } if text == "think"
        )));
        assert!(events.iter().any(|event| matches!(
            event,
            StreamEvent::TextDelta { text } if text == "answer"
        )));
    }

    #[test]
    fn overloaded_stream_error_maps_to_529() {
        let error = map_stream_error(
            &json!({
                "type": "error",
                "error": { "type": "overloaded_error", "message": "Overloaded" }
            }),
            None,
        );

        assert_eq!(error.code, ChatErrorCode::Provider);
        assert_eq!(error.status, Some(529));
        assert!(!error.retryable);
    }

    #[test]
    fn finish_reasons_match_the_shared_enum() {
        assert_eq!(map_finish_reason("end_turn"), FinishReason::Stop);
        assert_eq!(map_finish_reason("stop_sequence"), FinishReason::Stop);
        assert_eq!(map_finish_reason("max_tokens"), FinishReason::Length);
        assert_eq!(map_finish_reason("tool_use"), FinishReason::ToolCalls);
        assert_eq!(map_finish_reason("refusal"), FinishReason::ContentFilter);
        assert_eq!(map_finish_reason("something_else"), FinishReason::Unknown);
    }
}
