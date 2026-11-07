//! OpenRouter streaming chat completions.
//!
//! This module turns the OpenRouter SSE stream into normalized
//! [`StreamEvent`] values from spec section 7.2. It uses the shared HTTP client
//! and the shared SSE parser, so keep-alive comments such as
//! `: OPENROUTER PROCESSING` never reach the JSON layer.
//!
//! Mapping rules from spec sections 7.4 and 7.9:
//!
//! - `choices[0].delta.content` is appended as text. String content is used
//!   directly; array content concatenates its text fields defensively.
//! - `reasoning_details` is preferred over `reasoning`, and repeated or
//!   incremental reasoning text is deduplicated so one normalized reasoning
//!   stream is produced.
//! - `finish_reason` maps to the shared [`FinishReason`]; unknown values become
//!   [`FinishReason::Unknown`].
//! - final `usage` maps input, output, total, and cost. Reported usage is never
//!   marked estimated; [`StreamSummary::estimated_usage`] estimates locally only
//!   when the provider omitted usage.
//! - an `error` object inside an HTTP 200 stream is normalized, partial content
//!   is preserved, the stream stops, and no retry is allowed.
//!
//! The decoder tracks first-token and total latency for the message layer.

#![allow(clippy::result_large_err)]

use std::collections::BTreeMap;
use std::time::Instant;

use chatui_domain::{ChatError, ChatErrorCode, FinishReason, StreamEvent, Timestamp, Usage};
use reqwest::header::{AUTHORIZATION, HeaderMap, RETRY_AFTER};
use reqwest::{Method, Url};
use serde_json::Value;
use tokio_util::sync::CancellationToken;

use crate::error::{NetworkError, NetworkErrorKind, parse_retry_after};
use crate::http::{HttpClient, SecretHeader};
use crate::openrouter::catalog::usd_amount;
use crate::openrouter::errors::{
    OPENROUTER_PROVIDER_ID, default_message, map_http_error_for, map_stream_error_for,
};
use crate::openrouter::request::PreparedChatRequest;
use crate::retry::PartialOutput;
use crate::sse::{DEFAULT_MAX_SSE_BUFFER_BYTES, SseError, read_sse};
use crate::url_policy::join_url;

/// How a provider expresses reasoning deltas.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ReasoningStyle {
    /// OpenRouter: prefer `reasoning_details`, then `reasoning`, and deduplicate
    /// repeated or incremental reasoning text.
    OpenRouter,
    /// OpenAI-compatible: accept `reasoning_content` or `reasoning` and emit
    /// each delta as-is.
    OpenAi,
}

/// Per-request streaming configuration.
pub struct StreamConfig<'a> {
    /// Provider ID copied onto normalized errors.
    pub provider_id: &'a str,
    /// Reasoning field style.
    pub reasoning_style: ReasoningStyle,
    /// Optional secret authorization header.
    pub auth: Option<SecretHeader>,
    /// Provider-neutral fallback message for an HTTP status.
    pub default_message: fn(Option<u16>) -> String,
}

/// The normalized result of one OpenRouter stream.
#[derive(Debug, Clone, PartialEq)]
pub struct StreamSummary {
    /// Provider response ID from the first chunk, or `None` when unavailable.
    pub response_id: Option<String>,
    /// Concatenated assistant text.
    pub text: String,
    /// Concatenated reasoning text.
    pub reasoning: String,
    /// Provider-reported usage, or `None` when the stream omitted it.
    pub usage: Option<Usage>,
    /// Finish reason, or `None` when the stream did not report one.
    pub finish_reason: Option<FinishReason>,
    /// Normalized error, or `None` when the stream completed successfully.
    pub error: Option<ChatError>,
    /// Whether text or reasoning was emitted before the stream ended.
    pub partial_output: PartialOutput,
    /// Whether a retry is allowed. False after any text or reasoning.
    pub retry_allowed: bool,
    /// Milliseconds from request start to the first text or reasoning delta.
    pub first_token_ms: Option<u64>,
    /// Total milliseconds from request start to the end of the stream.
    pub latency_ms: u64,
}

impl StreamSummary {
    /// Return reported usage, or a locally estimated usage when it is absent.
    ///
    /// The estimate uses `ceil(characters / 4)` over the emitted text and
    /// reasoning and is marked `estimated: true`. Input tokens and cost are
    /// unknown locally, so they stay `None`.
    pub fn estimated_usage(&self) -> Usage {
        if let Some(usage) = &self.usage {
            return usage.clone();
        }

        let characters = self.text.chars().count() + self.reasoning.chars().count();
        let estimated = u32::try_from(characters.div_ceil(4)).unwrap_or(u32::MAX);

        Usage {
            input_tokens: None,
            output_tokens: Some(estimated),
            total_tokens: Some(estimated),
            cost_usd: None,
            estimated: true,
        }
    }
}

/// Incremental OpenRouter SSE decoder.
///
/// Feed each `data:` payload from the shared SSE parser to [`StreamDecoder::push`]
/// and forward the returned events to the caller. Call [`StreamDecoder::finish`]
/// once the stream ends to obtain the normalized summary.
#[derive(Debug)]
pub struct StreamDecoder {
    started: bool,
    done: bool,
    response_id: Option<String>,
    text: String,
    reasoning: String,
    usage: Option<Usage>,
    finish_reason: Option<FinishReason>,
    error: Option<ChatError>,
    partial_output: PartialOutput,
    started_at: Instant,
    first_token_at: Option<Instant>,
    reasoning_style: ReasoningStyle,
    provider_id: String,
    default_message: fn(Option<u16>) -> String,
}

impl Default for StreamDecoder {
    fn default() -> Self {
        Self::new()
    }
}

impl StreamDecoder {
    /// Create a decoder with no events seen.
    pub fn new() -> Self {
        Self::with_reasoning_style(ReasoningStyle::OpenRouter)
    }

    /// Create a decoder with an explicit reasoning style.
    pub fn with_reasoning_style(reasoning_style: ReasoningStyle) -> Self {
        Self::with_provider(OPENROUTER_PROVIDER_ID, reasoning_style)
    }

    /// Create a decoder for a specific provider and reasoning style.
    pub fn with_provider(provider_id: &str, reasoning_style: ReasoningStyle) -> Self {
        Self::with_config(provider_id, reasoning_style, default_message)
    }

    /// Create a decoder for a specific provider, reasoning style, and fallback
    /// message function.
    pub fn with_config(
        provider_id: &str,
        reasoning_style: ReasoningStyle,
        default_message: fn(Option<u16>) -> String,
    ) -> Self {
        Self {
            started: false,
            done: false,
            response_id: None,
            text: String::new(),
            reasoning: String::new(),
            usage: None,
            finish_reason: None,
            error: None,
            partial_output: PartialOutput::none(),
            started_at: Instant::now(),
            first_token_at: None,
            reasoning_style,
            provider_id: provider_id.to_owned(),
            default_message,
        }
    }

    /// Decode one SSE `data:` payload and return the events to emit.
    ///
    /// `[DONE]` ends the stream without producing an event. Once an error has
    /// been recorded, later payloads are ignored so partial content is preserved
    /// and no further events are emitted.
    pub fn push(&mut self, data: &str) -> Vec<StreamEvent> {
        let mut events = Vec::new();

        if self.done || self.error.is_some() {
            return events;
        }

        let data = data.trim();
        if data.is_empty() {
            return events;
        }
        if data == "[DONE]" {
            self.done = true;
            return events;
        }

        let value: Value = match serde_json::from_str(data) {
            Ok(value) => value,
            Err(error) => {
                self.set_error(parse_error(
                    &self.provider_id,
                    format!("The provider stream contained invalid JSON: {error}"),
                ));
                events.push(StreamEvent::Error {
                    error: self.error.clone().expect("error was just set"),
                });
                return events;
            }
        };

        if !self.started {
            self.started = true;
            let response_id = value.get("id").and_then(Value::as_str).map(str::to_owned);
            self.response_id = response_id.clone();
            events.push(StreamEvent::Start { response_id });
        }

        if value.get("error").is_some() {
            let error = map_stream_error_for(
                &self.provider_id,
                &value,
                self.response_id.clone(),
                self.default_message,
            );
            self.error = Some(error.clone());
            events.push(StreamEvent::Error { error });
            return events;
        }

        if let Some(choice) = value
            .get("choices")
            .and_then(Value::as_array)
            .and_then(|choices| choices.first())
        {
            if let Some(delta) = choice.get("delta") {
                if let Some(content) = delta.get("content") {
                    let text = content_text(content);
                    if !text.is_empty() {
                        self.text.push_str(&text);
                        self.partial_output.mark_text();
                        self.mark_first_token();
                        events.push(StreamEvent::TextDelta { text });
                    }
                }

                if let Some(candidate) = reasoning_text(delta) {
                    let text = match self.reasoning_style {
                        ReasoningStyle::OpenRouter => self.dedup_reasoning(&candidate),
                        ReasoningStyle::OpenAi => (!candidate.is_empty()).then_some(candidate),
                    };

                    if let Some(text) = text {
                        self.reasoning.push_str(&text);
                        self.partial_output.mark_reasoning();
                        self.mark_first_token();
                        events.push(StreamEvent::ReasoningDelta { text });
                    }
                }
            }

            if let Some(reason) = choice.get("finish_reason").and_then(Value::as_str) {
                let reason = map_finish_reason(reason);
                self.finish_reason = Some(reason);
                events.push(StreamEvent::Finish { reason });
            }
        }

        if let Some(usage) = value.get("usage").filter(|usage| usage.is_object()) {
            let usage = map_usage(usage);
            self.usage = Some(usage.clone());
            events.push(StreamEvent::Usage { usage });
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
        let Self {
            done,
            response_id,
            text,
            reasoning,
            usage,
            finish_reason,
            error,
            partial_output,
            started_at,
            first_token_at,
            provider_id,
            ..
        } = self;

        let mut error = error;
        if error.is_none() && finish_reason.is_none() && !done {
            error = Some(parse_error(
                &provider_id,
                "The provider stream ended before a finish event.",
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

    fn mark_first_token(&mut self) {
        if self.first_token_at.is_none() {
            self.first_token_at = Some(Instant::now());
        }
    }

    fn dedup_reasoning(&self, candidate: &str) -> Option<String> {
        if candidate.is_empty() || candidate == self.reasoning {
            return None;
        }

        if candidate.starts_with(&self.reasoning) {
            let suffix = &candidate[self.reasoning.len()..];
            if suffix.is_empty() {
                return None;
            }
            return Some(suffix.to_owned());
        }

        if self.reasoning.starts_with(candidate) {
            return None;
        }

        Some(candidate.to_owned())
    }
}

/// Send an OpenRouter chat request and decode its SSE stream.
///
/// HTTP and transport failures before the stream starts return `Err`. Once the
/// stream has started, failures are recorded in [`StreamSummary::error`] so the
/// caller keeps any partial text or reasoning. In-stream errors are never
/// retryable.
pub async fn stream_chat<F>(
    client: &HttpClient,
    url: &Url,
    api_key: &str,
    body: &Value,
    headers: &BTreeMap<String, String>,
    cancel: &CancellationToken,
    on_event: F,
) -> Result<StreamSummary, ChatError>
where
    F: FnMut(StreamEvent) + Send,
{
    let authorization = SecretHeader::new(AUTHORIZATION, &format!("Bearer {api_key}"))
        .map_err(|error| error.to_chat_error(Some(OPENROUTER_PROVIDER_ID.to_owned())))?;
    let config = StreamConfig {
        provider_id: OPENROUTER_PROVIDER_ID,
        reasoning_style: ReasoningStyle::OpenRouter,
        auth: Some(authorization),
        default_message,
    };

    stream_chat_with(client, url, body, headers, config, cancel, on_event).await
}

/// Send a chat request with an explicit provider configuration.
///
/// This is the shared transport used by every OpenAI-compatible preset. The
/// caller supplies the provider ID, reasoning style, optional secret header,
/// and provider-neutral fallback message.
pub async fn stream_chat_with<F>(
    client: &HttpClient,
    url: &Url,
    body: &Value,
    headers: &BTreeMap<String, String>,
    config: StreamConfig<'_>,
    cancel: &CancellationToken,
    mut on_event: F,
) -> Result<StreamSummary, ChatError>
where
    F: FnMut(StreamEvent) + Send,
{
    let prepared = client.prepare(Method::POST, url).map_err(|error| {
        invalid_request_error(
            config.provider_id,
            format!("The provider chat URL is invalid: {error}"),
        )
    })?;
    let mut builder = prepared.into_builder().json(body);

    if let Some(authorization) = config.auth {
        builder = authorization.apply(builder);
    }

    for (name, value) in headers {
        builder = builder.header(name, value);
    }

    let response = client
        .send(builder)
        .await
        .map_err(|error| error.to_chat_error(Some(config.provider_id.to_owned())))?;

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
            config.provider_id,
            status.as_u16(),
            retry_after,
            body.as_ref(),
            request_id,
            config.default_message,
        ));
    }

    let read_cancel = cancel.child_token();
    let cancel_for_closure = read_cancel.clone();
    let mut decoder = StreamDecoder::with_config(
        config.provider_id,
        config.reasoning_style,
        config.default_message,
    );

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
                decoder.set_error(cancelled_error(config.provider_id));
            }
        }
        Err(SseError::Stream { message }) => {
            if !decoder.has_error() {
                let error = NetworkError::new(
                    NetworkErrorKind::Connection,
                    format!("The provider stream failed: {message}"),
                );
                decoder.set_error(error.to_chat_error(Some(config.provider_id.to_owned())));
            }
        }
        Err(SseError::BufferOverflow { max_bytes }) => {
            if !decoder.has_error() {
                decoder.set_error(parse_error(
                    config.provider_id,
                    format!("The provider stream exceeded the {max_bytes} byte buffer limit."),
                ));
            }
        }
        Err(SseError::InvalidUtf8) => {
            if !decoder.has_error() {
                decoder.set_error(parse_error(
                    config.provider_id,
                    "The provider stream contained invalid UTF-8.",
                ));
            }
        }
    }

    Ok(decoder.finish())
}

/// Send a prepared OpenRouter chat request to `/chat/completions`.
///
/// This is the convenience wrapper used by the message layer. It joins the
/// endpoint with the shared URL policy and delegates to [`stream_chat`].
pub async fn stream_chat_completions<F>(
    client: &HttpClient,
    base_url: &str,
    api_key: &str,
    request: &PreparedChatRequest,
    cancel: &CancellationToken,
    on_event: F,
) -> Result<StreamSummary, ChatError>
where
    F: FnMut(StreamEvent) + Send,
{
    let url = join_url(base_url, "chat/completions").map_err(|error| {
        invalid_request_error(
            OPENROUTER_PROVIDER_ID,
            format!("The OpenRouter chat URL is invalid: {error}"),
        )
    })?;

    stream_chat(
        client,
        &url,
        api_key,
        &request.body,
        &request.headers,
        cancel,
        on_event,
    )
    .await
}

/// Map an OpenRouter `finish_reason` to the shared [`FinishReason`].
pub fn map_finish_reason(reason: &str) -> FinishReason {
    match reason {
        "stop" => FinishReason::Stop,
        "length" => FinishReason::Length,
        "tool_calls" => FinishReason::ToolCalls,
        "content_filter" => FinishReason::ContentFilter,
        "cancelled" => FinishReason::Cancelled,
        "error" => FinishReason::Error,
        _ => FinishReason::Unknown,
    }
}

/// Map an OpenRouter `usage` object to the shared [`Usage`] shape.
pub fn map_usage(value: &Value) -> Usage {
    Usage {
        input_tokens: token_count(value.get("prompt_tokens")),
        output_tokens: token_count(value.get("completion_tokens")),
        total_tokens: token_count(value.get("total_tokens")),
        cost_usd: value.get("cost").and_then(usd_amount),
        estimated: false,
    }
}

fn token_count(value: Option<&Value>) -> Option<u32> {
    value
        .and_then(Value::as_u64)
        .and_then(|value| u32::try_from(value).ok())
}

fn content_text(content: &Value) -> String {
    match content {
        Value::String(text) => text.clone(),
        Value::Array(parts) => {
            let mut text = String::new();
            for part in parts {
                match part {
                    Value::String(part) => text.push_str(part),
                    Value::Object(object) => {
                        if let Some(part) = object.get("text").and_then(Value::as_str) {
                            text.push_str(part);
                        }
                    }
                    _ => {}
                }
            }
            text
        }
        _ => String::new(),
    }
}

fn reasoning_text(delta: &Value) -> Option<String> {
    if let Some(details) = delta.get("reasoning_details").and_then(Value::as_array) {
        let mut text = String::new();
        for detail in details {
            if let Some(part) = detail.get("text").and_then(Value::as_str) {
                text.push_str(part);
            }
        }
        if !text.is_empty() {
            return Some(text);
        }
    }

    delta
        .get("reasoning")
        .and_then(Value::as_str)
        .filter(|text| !text.is_empty())
        .or_else(|| {
            delta
                .get("reasoning_content")
                .and_then(Value::as_str)
                .filter(|text| !text.is_empty())
        })
        .map(str::to_owned)
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

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    fn reasoning_texts(events: &[StreamEvent]) -> Vec<&str> {
        events
            .iter()
            .filter_map(|event| match event {
                StreamEvent::ReasoningDelta { text } => Some(text.as_str()),
                _ => None,
            })
            .collect()
    }

    fn text_deltas(events: &[StreamEvent]) -> Vec<&str> {
        events
            .iter()
            .filter_map(|event| match event {
                StreamEvent::TextDelta { text } => Some(text.as_str()),
                _ => None,
            })
            .collect()
    }

    #[test]
    fn finish_reasons_match_the_shared_enum() {
        assert_eq!(map_finish_reason("stop"), FinishReason::Stop);
        assert_eq!(map_finish_reason("length"), FinishReason::Length);
        assert_eq!(map_finish_reason("tool_calls"), FinishReason::ToolCalls);
        assert_eq!(
            map_finish_reason("content_filter"),
            FinishReason::ContentFilter
        );
        assert_eq!(map_finish_reason("cancelled"), FinishReason::Cancelled);
        assert_eq!(map_finish_reason("error"), FinishReason::Error);
        assert_eq!(map_finish_reason("something_else"), FinishReason::Unknown);
    }

    #[test]
    fn array_content_concatenates_text_defensively() {
        let mut decoder = StreamDecoder::new();
        let events = decoder.push(
            r#"{"id":"gen","choices":[{"index":0,"delta":{"content":[{"type":"text","text":"a"},{"type":"text","text":"b"}]},"finish_reason":null}]}"#,
        );

        assert_eq!(text_deltas(&events), vec!["ab"]);
    }

    #[test]
    fn reasoning_details_win_over_reasoning_without_duplication() {
        let mut decoder = StreamDecoder::new();
        let events = decoder.push(
            r#"{"id":"gen","choices":[{"index":0,"delta":{"reasoning":"same","reasoning_details":[{"type":"reasoning.text","text":"same"}]},"finish_reason":null}]}"#,
        );

        assert_eq!(reasoning_texts(&events), vec!["same"]);
    }

    #[test]
    fn incremental_reasoning_is_not_duplicated() {
        let mut decoder = StreamDecoder::new();
        let first = decoder.push(
            r#"{"id":"gen","choices":[{"index":0,"delta":{"reasoning":"Let me"},"finish_reason":null}]}"#,
        );
        let second = decoder.push(
            r#"{"id":"gen","choices":[{"index":0,"delta":{"reasoning":"Let me compare"},"finish_reason":null}]}"#,
        );
        let third = decoder.push(
            r#"{"id":"gen","choices":[{"index":0,"delta":{"reasoning_details":[{"type":"reasoning.text","text":"Let me compare"}]},"finish_reason":null}]}"#,
        );

        assert_eq!(reasoning_texts(&first), vec!["Let me"]);
        assert_eq!(reasoning_texts(&second), vec![" compare"]);
        assert!(reasoning_texts(&third).is_empty());
    }

    #[test]
    fn reported_usage_is_not_estimated() {
        let mut decoder = StreamDecoder::new();
        decoder.push(
            r#"{"id":"gen","choices":[],"usage":{"prompt_tokens":1,"completion_tokens":2,"total_tokens":3,"cost":0.5}}"#,
        );
        decoder.push("[DONE]");
        let summary = decoder.finish();
        let usage = summary.estimated_usage();

        assert!(!usage.estimated);
        assert_eq!(usage.input_tokens, Some(1));
        assert_eq!(usage.output_tokens, Some(2));
        assert_eq!(usage.total_tokens, Some(3));
        assert_eq!(usage.cost_usd, Some(0.5));
    }

    #[test]
    fn estimated_usage_is_marked_only_when_reported_usage_is_absent() {
        let mut decoder = StreamDecoder::new();
        decoder.push(
            r#"{"id":"gen","choices":[{"index":0,"delta":{"content":"12345678"},"finish_reason":"stop"}]}"#,
        );
        decoder.push("[DONE]");
        let summary = decoder.finish();
        let usage = summary.estimated_usage();

        assert!(usage.estimated);
        assert_eq!(usage.output_tokens, Some(2));
        assert_eq!(usage.total_tokens, Some(2));
        assert_eq!(usage.cost_usd, None);
    }

    #[test]
    fn first_token_and_latency_are_tracked() {
        let mut decoder = StreamDecoder::new();
        decoder.push(
            r#"{"id":"gen","choices":[{"index":0,"delta":{"content":"hello"},"finish_reason":"stop"}]}"#,
        );
        decoder.push("[DONE]");
        let summary = decoder.finish();

        assert!(summary.first_token_ms.is_some());
        assert!(summary.latency_ms >= summary.first_token_ms.unwrap_or(0));
    }

    #[test]
    fn empty_stream_has_no_first_token() {
        let mut decoder = StreamDecoder::new();
        decoder.push("[DONE]");
        let summary = decoder.finish();

        assert!(summary.first_token_ms.is_none());
        assert!(summary.error.is_none());
    }

    #[test]
    fn usage_cost_accepts_string_and_number() {
        let number = map_usage(&json!({ "cost": 0.25 }));
        assert_eq!(number.cost_usd, Some(0.25));

        let string = map_usage(&json!({ "cost": "0.25" }));
        assert_eq!(string.cost_usd, Some(0.25));
    }
}
