//! Native Google Gemini adapter.
//!
//! Gemini is not OpenAI-compatible: the system prompt is a top-level
//! `systemInstruction`, messages are `contents` with `user`/`model` roles,
//! images are inline base64 `inlineData` parts, sampling controls live under
//! `generationConfig`, and the streaming endpoint is
//! `POST /models/{model}:streamGenerateContent?alt=sse`. This module keeps every
//! Gemini-specific rule inside the adapter and reuses the shared HTTP client,
//! SSE parser, error mapper, and stream summary.
//!
//! Mapping rules from spec sections 7.5, 7.6, and 7.9:
//!
//! - the key travels only in `x-goog-api-key`, never in the query string;
//! - the system prompt is `systemInstruction`, never a `contents` entry;
//! - assistant turns use `role: "model"` and every other role uses `"user"`;
//! - images become `inlineData` with `mimeType` and base64 `data`;
//! - text files are appended as text parts with a deterministic header;
//! - `temperature`, `top_p`, and `stop` map to `generationConfig` fields;
//! - `candidates[0].content.parts[].text` maps to `text-delta`;
//! - `usageMetadata` maps to the shared [`Usage`];
//! - `STOP` -> `stop`, `MAX_TOKENS` -> `length`, `SAFETY`/`RECITATION` ->
//!   `content_filter`, everything else -> `unknown`;
//! - `{ "error": { ... } }` envelopes normalize to the shared [`ChatError`].
//!
//! Credentials are sent only in the `x-goog-api-key` header. No error message,
//! URL, or debug output contains the key.

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
use crate::openai_catalog::{CatalogDefaults, OpenAiCatalogError, google_catalog_defaults};
use crate::openai_compatible::{default_message, network_error_to_chat_error};
use crate::openrouter::catalog::CatalogModel;
use crate::openrouter::errors::{map_http_error_for, map_stream_error_for};
use crate::openrouter::key::ProviderKeyInfo;
use crate::openrouter::request::{
    NormalizedChatMessage, NormalizedChatRequest, PreparedChatRequest, ResolvedAttachment,
};
use crate::openrouter::stream::StreamSummary;
use crate::retry::PartialOutput;
use crate::sse::{DEFAULT_MAX_SSE_BUFFER_BYTES, SseError, read_sse};
use crate::url_policy::{UrlPolicyError, join_url};

/// Provider ID used on every normalized Gemini error.
pub const GEMINI_PROVIDER_ID: &str = "google";

/// Namespace that owns Gemini-only advanced JSON.
pub const GEMINI_EXTRA_NAMESPACE: &str = "google";

/// Fields advanced JSON may never override.
const PROTECTED_FIELDS: [&str; 4] = ["model", "messages", "stream", "stream_options"];

/// The native Google Gemini adapter.
///
/// The adapter stores only the preset ID. Every request reads the contract
/// preset from [`ProviderContext`], so no provider metadata is duplicated.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct GeminiAdapter {
    preset_id: String,
}

impl GeminiAdapter {
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

/// Build the exact Gemini `streamGenerateContent` body.
///
/// The function is pure so tests can assert the serialized JSON against the
/// shared request vectors. It rejects protected-field overrides before any
/// network call. The model ID is carried in the URL, not the body.
pub fn build_body(
    request: &NormalizedChatRequest,
    supported_parameters: &[String],
    attachments: &BTreeMap<String, ResolvedAttachment>,
) -> Result<Value, ChatError> {
    let advanced = advanced_namespace(&request.params.extra)?;
    reject_protected_fields(advanced)?;

    let mut body = Map::new();

    let mut contents = Vec::with_capacity(request.messages.len());
    for message in &request.messages {
        contents.push(serialize_message(message, attachments)?);
    }
    body.insert("contents".to_owned(), Value::Array(contents));

    if let Some(system) = &request.system {
        body.insert(
            "systemInstruction".to_owned(),
            json!({ "parts": [{ "text": system }] }),
        );
    }

    let mut generation_config = Map::new();

    if let Some(max_output_tokens) = request.params.max_output_tokens {
        generation_config.insert("maxOutputTokens".to_owned(), json!(max_output_tokens));
    }
    if let Some(temperature) = request.params.temperature
        && supports(supported_parameters, "temperature")
    {
        generation_config.insert("temperature".to_owned(), json!(temperature));
    }
    if let Some(top_p) = request.params.top_p
        && supports(supported_parameters, "top_p")
    {
        generation_config.insert("topP".to_owned(), json!(top_p));
    }
    if !request.params.stop.is_empty() && supports(supported_parameters, "stop") {
        generation_config.insert("stopSequences".to_owned(), json!(request.params.stop));
    }

    if !generation_config.is_empty() {
        body.insert(
            "generationConfig".to_owned(),
            Value::Object(generation_config),
        );
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

/// Build the non-secret Gemini request headers.
///
/// The key is added separately as a [`SecretHeader`]. `content-type` and
/// `Accept` are always set for streaming requests.
pub fn build_headers(ctx: &ProviderContext) -> BTreeMap<String, String> {
    let mut headers = BTreeMap::new();

    for (name, value) in &ctx.headers {
        headers.insert(name.clone(), value.clone());
    }

    headers.retain(|name, _| {
        !name.eq_ignore_ascii_case("content-type") && !name.eq_ignore_ascii_case("accept")
    });

    headers.insert("content-type".to_owned(), "application/json".to_owned());
    headers.insert("Accept".to_owned(), "text/event-stream".to_owned());

    headers
}

/// Build `POST /models/{model}:streamGenerateContent?alt=sse`.
///
/// A leading `models/` on the model ID is stripped so callers can pass either
/// form. The key is never part of the URL.
pub fn build_chat_url(base_url: &str, model: &str) -> Result<Url, UrlPolicyError> {
    let model = model.trim().trim_start_matches("models/");
    join_url(
        base_url,
        &format!("models/{model}:streamGenerateContent?alt=sse"),
    )
}

/// Validate a Gemini key by loading `GET /models`.
///
/// Gemini has no dedicated key-introspection endpoint. A successful catalog
/// load proves the key works; the returned metadata is empty because Gemini
/// does not expose OpenRouter-style key details.
pub async fn validate_key(
    client: &HttpClient,
    ctx: &ProviderContext,
    cancel: &CancellationToken,
) -> Result<ProviderKeyInfo, ChatError> {
    list_models(client, ctx, cancel).await?;
    Ok(empty_key_info())
}

/// Load the Gemini model catalog with `GET /models`.
///
/// The key is sent only in the `x-goog-api-key` header. The response is mapped
/// with the Gemini preset defaults so missing capability metadata still
/// produces the shared [`CatalogModel`] shape.
pub async fn list_models(
    client: &HttpClient,
    ctx: &ProviderContext,
    cancel: &CancellationToken,
) -> Result<Vec<CatalogModel>, ChatError> {
    let url = join_endpoint(&ctx.base_url, &ctx.preset.model_discovery.path)
        .map_err(|error| invalid_request_error(format!("The Gemini URL is invalid: {error}")))?;
    let auth = auth_header(ctx)?;

    let request = async {
        let prepared = client.prepare(Method::GET, &url).map_err(|error| {
            invalid_request_error(format!("The Gemini URL is invalid: {error}"))
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
            .map_err(|error| network_error_to_chat_error(error, GEMINI_PROVIDER_ID))?;
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
            network_error_to_chat_error(NetworkError::from_reqwest(error), GEMINI_PROVIDER_ID)
        })?;
        let payload: Value = serde_json::from_slice(&bytes).map_err(|error| {
            parse_error(format!(
                "The Gemini /models response was not valid JSON: {error}"
            ))
        })?;

        map_models(&payload).map_err(|error| {
            parse_error(format!(
                "The Gemini /models response was not valid: {error}"
            ))
        })
    };

    tokio::select! {
        result = request => result,
        _ = cancel.cancelled() => Err(cancelled_error()),
    }
}

/// Map a Gemini `/models` payload to normalized catalog entries.
///
/// Gemini reports the model ID in `baseModelId` (or `name` with a `models/`
/// prefix), the label in `displayName`, and limits in `inputTokenLimit` and
/// `outputTokenLimit`. Capability metadata comes from the shared Gemini preset
/// defaults.
pub fn map_models(payload: &Value) -> Result<Vec<CatalogModel>, OpenAiCatalogError> {
    let Some(entries) = payload.get("models").and_then(Value::as_array) else {
        return Err(OpenAiCatalogError::new(
            "Gemini /models response must contain a models array",
        ));
    };

    let defaults = google_catalog_defaults();
    Ok(entries
        .iter()
        .filter_map(|entry| map_model(entry, &defaults))
        .collect())
}

/// Map one Gemini model entry.
pub fn map_model(raw: &Value, defaults: &CatalogDefaults) -> Option<CatalogModel> {
    if !raw.is_object() {
        return None;
    }

    let name = raw.get("name").and_then(Value::as_str).unwrap_or("").trim();
    let base_model_id = raw
        .get("baseModelId")
        .and_then(Value::as_str)
        .unwrap_or("")
        .trim();
    let remote_id = if base_model_id.is_empty() {
        name.strip_prefix("models/").unwrap_or(name)
    } else {
        base_model_id
    };

    if remote_id.is_empty() {
        return None;
    }

    let label = raw
        .get("displayName")
        .and_then(Value::as_str)
        .filter(|label| !label.trim().is_empty())
        .unwrap_or(remote_id);

    Some(CatalogModel {
        remote_id: remote_id.to_owned(),
        canonical_slug: None,
        label: label.to_owned(),
        context_window: to_positive_integer(raw.get("inputTokenLimit")),
        max_output_tokens: to_positive_integer(raw.get("outputTokenLimit")),
        capabilities: defaults.capabilities.clone(),
        pricing: None,
        input_modalities: defaults.input_modalities.clone(),
        output_modalities: defaults.output_modalities.clone(),
        supported_parameters: defaults.supported_parameters.clone(),
        metadata: None,
        is_free: false,
        hidden: false,
    })
}

/// Stream a Gemini `streamGenerateContent` completion.
///
/// Events are emitted in the shared order: `start`, text/reasoning deltas,
/// `finish`, then `usage`. The shared Gemini vector puts `finish` before
/// `usage`, so usage is flushed at finish time. Cancellation stops the stream
/// and preserves any partial text or reasoning in the returned
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
    let url = build_chat_url(&ctx.base_url, &request.model)
        .map_err(|error| invalid_request_error(format!("The Gemini URL is invalid: {error}")))?;
    let auth = auth_header(ctx)?;

    let prepared_request = client.prepare(Method::POST, &url).map_err(|error| {
        invalid_request_error(format!("The Gemini chat URL is invalid: {error}"))
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
        .map_err(|error| network_error_to_chat_error(error, GEMINI_PROVIDER_ID))?;

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
    let mut decoder = GeminiStreamDecoder::new();

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
                    format!("The Gemini stream failed: {message}"),
                );
                decoder.set_error(error.to_chat_error(Some(GEMINI_PROVIDER_ID.to_owned())));
            }
        }
        Err(SseError::BufferOverflow { max_bytes }) => {
            if !decoder.has_error() {
                decoder.set_error(parse_error(format!(
                    "The Gemini stream exceeded the {max_bytes} byte buffer limit."
                )));
            }
        }
        Err(SseError::InvalidUtf8) => {
            if !decoder.has_error() {
                decoder.set_error(parse_error("The Gemini stream contained invalid UTF-8."));
            }
        }
    }

    let (events, summary) = decoder.finish();
    for event in events {
        on_event(event);
    }

    Ok(summary)
}

/// Incremental Gemini SSE decoder.
///
/// Feed each `data:` payload from the shared SSE parser to [`Self::push`] and
/// forward the returned events to the caller. Call [`Self::finish`] once the
/// stream ends to obtain any trailing `finish`/`usage` events and the
/// normalized summary.
#[derive(Debug)]
pub struct GeminiStreamDecoder {
    started: bool,
    finished: bool,
    response_id: Option<String>,
    text: String,
    reasoning: String,
    input_tokens: Option<u32>,
    output_tokens: Option<u32>,
    total_tokens: Option<u32>,
    usage: Option<Usage>,
    usage_emitted: bool,
    finish_reason: Option<FinishReason>,
    error: Option<ChatError>,
    partial_output: PartialOutput,
    started_at: Instant,
    first_token_at: Option<Instant>,
}

impl Default for GeminiStreamDecoder {
    fn default() -> Self {
        Self::new()
    }
}

impl GeminiStreamDecoder {
    /// Create a decoder with no events seen.
    pub fn new() -> Self {
        Self {
            started: false,
            finished: false,
            response_id: None,
            text: String::new(),
            reasoning: String::new(),
            input_tokens: None,
            output_tokens: None,
            total_tokens: None,
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

        if self.finished || self.error.is_some() {
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
                    "The Gemini stream contained invalid JSON: {error}"
                )));
                events.push(StreamEvent::Error {
                    error: self.error.clone().expect("error was just set"),
                });
                return events;
            }
        };

        if value.get("error").is_some() {
            let error = map_stream_error(&value, self.response_id.clone());
            self.error = Some(error.clone());
            events.push(StreamEvent::Error { error });
            return events;
        }

        if let Some(id) = value.get("responseId").and_then(Value::as_str) {
            self.response_id = Some(id.to_owned());
        }

        if !self.started {
            self.started = true;
            events.push(StreamEvent::Start {
                response_id: self.response_id.clone(),
            });
        }

        self.handle_usage_metadata(value.get("usageMetadata"));

        if let Some(candidates) = value.get("candidates").and_then(Value::as_array) {
            for candidate in candidates {
                self.handle_candidate(candidate, &mut events);
            }
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
    /// A stream that started but never reported a finish reason emits
    /// `finish` with `unknown`, matching the web adapter. A stream that never
    /// started is treated as a parse failure so an empty 200 response is not
    /// mistaken for a successful completion.
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
                self.error = Some(parse_error("The Gemini stream ended before any content."));
            }
        }

        let usage = self.usage.clone().or_else(|| self.build_usage());
        let Self {
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

        let retry_allowed =
            error.as_ref().is_some_and(|error| error.retryable) && !partial_output.has_any();
        let latency_ms = u64::try_from(started_at.elapsed().as_millis()).unwrap_or(u64::MAX);
        let first_token_ms = first_token_at
            .map(|at| u64::try_from(at.duration_since(started_at).as_millis()).unwrap_or(u64::MAX));

        (
            events,
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
            },
        )
    }

    fn handle_candidate(&mut self, candidate: &Value, events: &mut Vec<StreamEvent>) {
        if let Some(parts) = candidate
            .get("content")
            .and_then(|content| content.get("parts"))
            .and_then(Value::as_array)
        {
            for part in parts {
                let Some(text) = part
                    .get("text")
                    .and_then(Value::as_str)
                    .filter(|text| !text.is_empty())
                else {
                    continue;
                };

                if part.get("thought").and_then(Value::as_bool) == Some(true) {
                    self.push_reasoning(text, events);
                } else {
                    self.push_text(text, events);
                }
            }
        }

        if !self.finished
            && let Some(reason) = candidate
                .get("finishReason")
                .and_then(Value::as_str)
                .filter(|reason| !reason.is_empty())
        {
            let reason = map_finish_reason(reason);
            self.finish_reason = Some(reason);
            events.push(StreamEvent::Finish { reason });
            self.finished = true;
            self.emit_usage(events);
        }
    }

    fn handle_usage_metadata(&mut self, value: Option<&Value>) {
        let Some(value) = value else {
            return;
        };

        if let Some(input) = token_count(value.get("promptTokenCount")) {
            self.input_tokens = Some(input);
        }
        if let Some(output) = token_count(value.get("candidatesTokenCount")) {
            self.output_tokens = Some(output);
        }
        if let Some(total) = token_count(value.get("totalTokenCount")) {
            self.total_tokens = Some(total);
        }
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

        let total_tokens =
            self.total_tokens
                .or_else(|| match (self.input_tokens, self.output_tokens) {
                    (Some(input), Some(output)) => input.checked_add(output),
                    _ => None,
                });

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

/// Map a Gemini `finishReason` to the shared [`FinishReason`].
pub fn map_finish_reason(reason: &str) -> FinishReason {
    match reason {
        "STOP" => FinishReason::Stop,
        "MAX_TOKENS" => FinishReason::Length,
        "SAFETY" | "RECITATION" => FinishReason::ContentFilter,
        _ => FinishReason::Unknown,
    }
}

/// Map a Gemini HTTP error response to a normalized [`ChatError`].
pub fn map_http_error(
    status: u16,
    retry_after_seconds: Option<f64>,
    body: Option<&Value>,
    request_id: Option<String>,
) -> ChatError {
    let mut error = map_http_error_for(
        GEMINI_PROVIDER_ID,
        status,
        retry_after_seconds,
        body,
        request_id,
        default_message,
    );
    adjust_code(&mut error);
    error
}

/// Map a Gemini in-stream `error` event to a normalized [`ChatError`].
///
/// In-stream errors are never retryable: the stream has already started and may
/// have emitted partial text or reasoning. The response ID from the stream is
/// preserved as the request ID.
pub fn map_stream_error(envelope: &Value, response_id: Option<String>) -> ChatError {
    let mut error =
        map_stream_error_for(GEMINI_PROVIDER_ID, envelope, response_id, default_message);
    adjust_code(&mut error);
    error
}

fn adjust_code(error: &mut ChatError) {
    error.code = gemini_code(error.status, &error.message);
}

fn gemini_code(status: Option<u16>, message: &str) -> ChatErrorCode {
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
            if is_api_key(message) {
                ChatErrorCode::Auth
            } else if is_content_filter(message) {
                ChatErrorCode::ContentFilter
            } else if is_context_length(message) {
                ChatErrorCode::ContextLength
            } else {
                ChatErrorCode::InvalidRequest
            }
        }
        Some(500..=599) => ChatErrorCode::Provider,
        _ => ChatErrorCode::Provider,
    }
}

fn is_api_key(message: &str) -> bool {
    let message = message.to_ascii_lowercase();
    message.contains("api key")
        || message.contains("api_key")
        || message.contains("api-key")
        || message.contains("apikey")
        || message.contains("credential")
}

fn is_context_length(message: &str) -> bool {
    let message = message.to_ascii_lowercase();
    message.contains("context length")
        || message.contains("context_length")
        || message.contains("token count")
        || message.contains("maximum number of tokens")
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
    let role = match message.role {
        MessageRole::Assistant => "model",
        MessageRole::System | MessageRole::User | MessageRole::Tool => "user",
    };

    let mut parts = Vec::new();

    for part in &message.parts {
        match part {
            ContentPart::Text { text } => {
                if !text.is_empty() {
                    parts.push(json!({ "text": text }));
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

                parts.push(json!({
                    "inlineData": {
                        "mimeType": media_type,
                        "data": attachment.data_base64,
                    }
                }));
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

                parts.push(json!({ "text": format!("\n\n[File: {file_name}]\n{body}") }));
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
        "parts": parts,
    }))
}

fn advanced_namespace(
    extra: &Map<String, Value>,
) -> Result<Option<&Map<String, Value>>, ChatError> {
    match extra.get(GEMINI_EXTRA_NAMESPACE) {
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
        .unwrap_or("x-goog-api-key");
    let name = HeaderName::from_bytes(name.as_bytes())
        .map_err(|_| invalid_request_error(format!("The {name} header name is not valid.")))?;

    SecretHeader::new(name, key)
        .map_err(|error| network_error_to_chat_error(error, GEMINI_PROVIDER_ID))
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

fn to_positive_integer(value: Option<&Value>) -> Option<u32> {
    let number = value?.as_u64()?;
    if number == 0 || number > u64::from(u32::MAX) {
        return None;
    }
    u32::try_from(number).ok()
}

fn extract_request_id(headers: &HeaderMap) -> Option<String> {
    const NAMES: [&str; 3] = ["x-goog-request-id", "x-request-id", "request-id"];

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
        provider_id: Some(GEMINI_PROVIDER_ID.to_owned()),
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
        provider_id: Some(GEMINI_PROVIDER_ID.to_owned()),
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
        provider_id: Some(GEMINI_PROVIDER_ID.to_owned()),
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
            model: "gemini-2.5-flash".to_owned(),
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
    fn system_prompt_is_system_instruction() {
        let mut request = request();
        request.system = Some("You are concise.".to_owned());
        let body = build_body(&request, &[], &BTreeMap::new()).expect("body builds");

        assert_eq!(
            body.get("systemInstruction"),
            Some(&json!({ "parts": [{ "text": "You are concise." }] }))
        );
        assert_eq!(
            body.get("contents").and_then(Value::as_array).map(Vec::len),
            Some(1)
        );
        assert!(body.get("model").is_none());
    }

    #[test]
    fn assistant_turns_use_the_model_role() {
        let mut request = request();
        request.messages.push(NormalizedChatMessage {
            role: MessageRole::Assistant,
            parts: vec![ContentPart::Text {
                text: "Hi".to_owned(),
            }],
        });
        let body = build_body(&request, &[], &BTreeMap::new()).expect("body builds");
        let contents = body
            .get("contents")
            .and_then(Value::as_array)
            .expect("contents");

        assert_eq!(contents[0].get("role"), Some(&json!("user")));
        assert_eq!(contents[1].get("role"), Some(&json!("model")));
    }

    #[test]
    fn stop_maps_to_stop_sequences() {
        let mut request = request();
        request.params.stop = vec!["STOP".to_owned()];
        let body =
            build_body(&request, &["stop".to_owned()], &BTreeMap::new()).expect("body builds");

        assert_eq!(
            body.get("generationConfig")
                .and_then(|config| config.get("stopSequences")),
            Some(&json!(["STOP"]))
        );
        assert!(body.get("stop").is_none());
    }

    #[test]
    fn protected_fields_are_rejected_with_sorted_details() {
        let mut request = request();
        request.params.extra.insert(
            "google".to_owned(),
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
        assert_eq!(map_finish_reason("STOP"), FinishReason::Stop);
        assert_eq!(map_finish_reason("MAX_TOKENS"), FinishReason::Length);
        assert_eq!(map_finish_reason("SAFETY"), FinishReason::ContentFilter);
        assert_eq!(map_finish_reason("RECITATION"), FinishReason::ContentFilter);
        assert_eq!(map_finish_reason("OTHER"), FinishReason::Unknown);
    }

    #[test]
    fn stream_maps_text_finish_and_usage() {
        let mut decoder = GeminiStreamDecoder::new();
        let mut events = Vec::new();

        for data in [
            r#"{"candidates":[{"content":{"parts":[{"text":"Hello"}],"role":"model"},"finishReason":null,"index":0}],"usageMetadata":{"promptTokenCount":8,"candidatesTokenCount":1,"totalTokenCount":9}}"#,
            r#"{"candidates":[{"content":{"parts":[{"text":" from Gemini."}],"role":"model"},"finishReason":"STOP","index":0}],"usageMetadata":{"promptTokenCount":8,"candidatesTokenCount":5,"totalTokenCount":13}}"#,
        ] {
            events.extend(decoder.push(data));
        }

        let (trailing, summary) = decoder.finish();
        events.extend(trailing);

        assert_eq!(summary.text, "Hello from Gemini.");
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
        let error = map_stream_error(
            &json!({
                "error": {
                    "code": 500,
                    "message": "Internal error.",
                    "status": "INTERNAL"
                }
            }),
            None,
        );

        assert_eq!(error.code, ChatErrorCode::Provider);
        assert_eq!(error.status, Some(500));
        assert!(!error.retryable);
    }

    #[test]
    fn api_key_400_maps_to_auth() {
        let error = map_http_error(
            400,
            None,
            Some(&json!({
                "error": {
                    "code": 400,
                    "message": "API key not valid. Please pass a valid API key.",
                    "status": "INVALID_ARGUMENT"
                }
            })),
            None,
        );

        assert_eq!(error.code, ChatErrorCode::Auth);
        assert!(!error.retryable);
    }

    #[test]
    fn context_400_maps_to_context_length() {
        let error = map_http_error(
            400,
            None,
            Some(&json!({
                "error": {
                    "code": 400,
                    "message": "The input token count exceeds the maximum number of tokens allowed.",
                    "status": "INVALID_ARGUMENT"
                }
            })),
            None,
        );

        assert_eq!(error.code, ChatErrorCode::ContextLength);
    }

    #[test]
    fn chat_url_uses_alt_sse_and_strips_the_models_prefix() {
        let url = build_chat_url(
            "https://generativelanguage.googleapis.com/v1beta",
            "models/gemini-2.5-flash",
        )
        .expect("url builds");

        assert_eq!(
            url.as_str(),
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:streamGenerateContent?alt=sse"
        );
    }
}
