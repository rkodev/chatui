//! OpenRouter streaming, reasoning, usage, and error tests.
//!
//! The tests load the shared OpenRouter stream and error vectors from
//! `contracts/fixtures/` and exercise both the pure decoder and the full HTTP
//! streaming call.

use std::collections::BTreeMap;
use std::time::Duration;

use chatui_conformance::{
    ConformanceFixtures, ExpectedChatError, ExpectedStreamEvent, StreamExpectation,
};
use chatui_domain::{ChatError, ChatErrorCode, FinishReason, StreamEvent};
use chatui_providers::cancel::CancellationToken;
use chatui_providers::http::HttpClient;
use chatui_providers::openrouter::errors::{map_http_error, map_stream_error};
use chatui_providers::openrouter::stream::{
    StreamDecoder, StreamSummary, map_finish_reason, map_usage, stream_chat,
};
use chatui_providers::sse::{DEFAULT_MAX_SSE_BUFFER_BYTES, read_sse};
use reqwest::Url;
use serde_json::{Value, json};
use wiremock::matchers::{method, path};
use wiremock::{Mock, MockServer, ResponseTemplate};

#[path = "network/harness.rs"]
#[allow(dead_code)]
mod harness;

use harness::{ChunkedSseServer, SseChunk};

fn fixtures() -> ConformanceFixtures {
    ConformanceFixtures::repository()
}

fn openrouter_stream_expectations() -> Vec<StreamExpectation> {
    fixtures()
        .stream_expectations()
        .expect("stream expectations load")
        .into_iter()
        .filter(|expectation| expectation.file.starts_with("openrouter-"))
        .collect()
}

fn fixture_contents(name: &str) -> String {
    fixtures()
        .root()
        .read(format!("streams/{name}"))
        .expect("stream fixture loads")
}

async fn decode(contents: &str) -> (Vec<StreamEvent>, StreamSummary) {
    let mut decoder = StreamDecoder::new();
    let mut events = Vec::new();
    let cancel = CancellationToken::new();
    let stream = futures_util::stream::iter(vec![Ok::<Vec<u8>, std::io::Error>(
        contents.as_bytes().to_vec(),
    )]);

    read_sse(
        stream,
        |data| {
            for event in decoder.push(data) {
                events.push(event);
            }
        },
        &cancel,
        DEFAULT_MAX_SSE_BUFFER_BYTES,
    )
    .await
    .expect("read SSE");

    let summary = decoder.finish();
    (events, summary)
}

fn assert_error_eq(actual: &ChatError, expected: &ExpectedChatError) {
    assert_eq!(actual.code, expected.code);
    assert_eq!(actual.message, expected.message);
    assert_eq!(actual.provider_id, expected.provider_id);
    assert_eq!(actual.status, expected.status);
    assert_eq!(actual.retryable, expected.retryable);
    assert_eq!(actual.retry_after_seconds, expected.retry_after_seconds);
    assert_eq!(actual.request_id, expected.request_id);
    assert_eq!(actual.details, expected.details);
}

fn assert_event_eq(actual: &StreamEvent, expected: &ExpectedStreamEvent) {
    match (actual, expected) {
        (
            StreamEvent::Start {
                response_id: actual,
            },
            ExpectedStreamEvent::Start {
                response_id: expected,
            },
        ) => assert_eq!(actual, expected),
        (
            StreamEvent::TextDelta { text: actual },
            ExpectedStreamEvent::TextDelta { text: expected },
        ) => assert_eq!(actual, expected),
        (
            StreamEvent::ReasoningDelta { text: actual },
            ExpectedStreamEvent::ReasoningDelta { text: expected },
        ) => assert_eq!(actual, expected),
        (
            StreamEvent::ToolCallDelta {
                index: actual_index,
                id: actual_id,
                name: actual_name,
                arguments_delta: actual_arguments,
            },
            ExpectedStreamEvent::ToolCallDelta {
                index: expected_index,
                id: expected_id,
                name: expected_name,
                arguments_delta: expected_arguments,
            },
        ) => {
            assert_eq!(actual_index, expected_index);
            assert_eq!(actual_id, expected_id);
            assert_eq!(actual_name, expected_name);
            assert_eq!(actual_arguments, expected_arguments);
        }
        (StreamEvent::Usage { usage: actual }, ExpectedStreamEvent::Usage { usage: expected }) => {
            assert_eq!(actual, expected)
        }
        (
            StreamEvent::Finish { reason: actual },
            ExpectedStreamEvent::Finish { reason: expected },
        ) => assert_eq!(actual, expected),
        (StreamEvent::Error { error: actual }, ExpectedStreamEvent::Error { error: expected }) => {
            assert_error_eq(actual, expected)
        }
        _ => panic!("event mismatch: actual {actual:?}, expected {expected:?}"),
    }
}

#[tokio::test]
async fn every_openrouter_stream_vector_matches() {
    let expectations = openrouter_stream_expectations();
    assert!(
        !expectations.is_empty(),
        "expected OpenRouter stream vectors"
    );

    for expectation in expectations {
        let contents = fixture_contents(&expectation.source);
        let (events, summary) = decode(&contents).await;

        assert_eq!(
            events.len(),
            expectation.events.len(),
            "{} event count",
            expectation.file
        );
        for (actual, expected) in events.iter().zip(&expectation.events) {
            assert_event_eq(actual, expected);
        }

        assert_eq!(summary.text, expectation.text, "{} text", expectation.file);
        assert_eq!(
            summary.reasoning, expectation.reasoning,
            "{} reasoning",
            expectation.file
        );
        assert_eq!(
            summary.retry_allowed, expectation.retry_allowed,
            "{} retryAllowed",
            expectation.file
        );
    }
}

#[tokio::test]
async fn keepalives_do_not_emit_blank_text() {
    let contents = fixture_contents("openrouter-keepalive.sse");
    let (events, summary) = decode(&contents).await;

    assert!(
        events
            .iter()
            .all(|event| !matches!(event, StreamEvent::TextDelta { text } if text.is_empty())),
        "keep-alives must not produce blank text deltas"
    );
    assert_eq!(summary.text, "Keep alive comments");
    assert_eq!(summary.reasoning, "");
}

#[tokio::test]
async fn in_stream_error_preserves_partial_content_and_does_not_retry() {
    let contents = fixture_contents("openrouter-error-200.sse");
    let (events, summary) = decode(&contents).await;

    assert_eq!(summary.text, "Partial answer");
    assert!(!summary.retry_allowed);
    assert!(
        events
            .iter()
            .any(|event| matches!(event, StreamEvent::Error { .. }))
    );

    let error = summary.error.expect("in-stream error");
    assert_eq!(error.code, ChatErrorCode::Provider);
    assert_eq!(error.status, Some(502));
    assert!(!error.retryable);
    assert_eq!(error.request_id.as_deref(), Some("gen-example-error-200"));
    assert_eq!(
        error.details,
        Some(json!({
            "providerName": "Synthetic Provider",
            "raw": "synthetic-error-detail"
        }))
    );
}

#[test]
fn every_openrouter_error_vector_matches() {
    let vectors = fixtures().openrouter_errors().expect("error vectors load");

    for case in vectors.cases {
        let error = if let Some(fixture) = &case.fixture {
            let body = fixtures()
                .root()
                .read_json::<Value>(fixture)
                .expect("error fixture loads");
            map_http_error(case.status.expect("status"), None, Some(&body), None)
        } else {
            let expected = &case.error;
            let body = json!({
                "error": {
                    "code": case.status.expect("status"),
                    "message": expected.message,
                }
            });
            map_http_error(case.status.expect("status"), None, Some(&body), None)
        };

        assert_error_eq(&error, &case.error);
    }
}

#[test]
fn stream_error_envelope_maps_to_shared_error() {
    let envelope = json!({
        "id": "gen-example-error-200",
        "error": {
            "code": 502,
            "message": "Upstream provider returned an error.",
            "metadata": {
                "provider_name": "Synthetic Provider",
                "raw": "synthetic-error-detail"
            }
        }
    });

    let error = map_stream_error(&envelope, Some("gen-example-error-200".to_owned()));

    assert_eq!(error.code, ChatErrorCode::Provider);
    assert_eq!(error.status, Some(502));
    assert!(!error.retryable);
    assert_eq!(error.request_id.as_deref(), Some("gen-example-error-200"));
    assert_eq!(
        error.details,
        Some(json!({
            "providerName": "Synthetic Provider",
            "raw": "synthetic-error-detail"
        }))
    );
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
fn usage_cost_round_trips() {
    let usage = map_usage(&json!({
        "prompt_tokens": 42,
        "completion_tokens": 12,
        "total_tokens": 54,
        "cost": 0.000081
    }));

    assert_eq!(usage.input_tokens, Some(42));
    assert_eq!(usage.output_tokens, Some(12));
    assert_eq!(usage.total_tokens, Some(54));
    assert_eq!(usage.cost_usd, Some(0.000081));
    assert!(!usage.estimated);
}

async fn mount_chat_error(
    server: &MockServer,
    status: u16,
    body: Value,
    retry_after: Option<&str>,
) {
    let mut response = ResponseTemplate::new(status).set_body_json(body);
    if let Some(retry_after) = retry_after {
        response = response.insert_header("retry-after", retry_after);
    }

    Mock::given(method("POST"))
        .and(path("/chat/completions"))
        .respond_with(response)
        .mount(server)
        .await;
}

async fn chat_url(server: &MockServer) -> Url {
    Url::parse(&format!("{}/chat/completions", server.uri())).expect("valid chat URL")
}

#[tokio::test]
async fn http_402_maps_to_quota_without_retry() {
    let server = MockServer::start().await;
    mount_chat_error(
        &server,
        402,
        json!({
            "error": {
                "code": 402,
                "message": "Insufficient credits. Add more credits to continue.",
                "metadata": { "provider_name": "OpenRouter" }
            }
        }),
        None,
    )
    .await;

    let client = HttpClient::new().expect("client");
    let url = chat_url(&server).await;
    let cancel = CancellationToken::new();
    let error = stream_chat(
        &client,
        &url,
        "sk-test",
        &json!({}),
        &BTreeMap::new(),
        &cancel,
        |_| {},
    )
    .await
    .expect_err("402 should fail");

    assert_eq!(error.code, ChatErrorCode::Quota);
    assert_eq!(error.status, Some(402));
    assert!(!error.retryable);
    assert_eq!(error.details, Some(json!({ "providerName": "OpenRouter" })));
}

#[tokio::test]
async fn http_429_preserves_retry_after() {
    let server = MockServer::start().await;
    mount_chat_error(
        &server,
        429,
        json!({
            "error": {
                "code": 429,
                "message": "Rate limit exceeded. Please retry after 2 seconds."
            }
        }),
        Some("2"),
    )
    .await;

    let client = HttpClient::new().expect("client");
    let url = chat_url(&server).await;
    let cancel = CancellationToken::new();
    let error = stream_chat(
        &client,
        &url,
        "sk-test",
        &json!({}),
        &BTreeMap::new(),
        &cancel,
        |_| {},
    )
    .await
    .expect_err("429 should fail");

    assert_eq!(error.code, ChatErrorCode::RateLimit);
    assert_eq!(error.status, Some(429));
    assert!(error.retryable);
    assert_eq!(error.retry_after_seconds, Some(2.0));
}

#[tokio::test]
async fn http_503_maps_to_provider_and_is_retryable() {
    let server = MockServer::start().await;
    mount_chat_error(
        &server,
        503,
        json!({
            "error": {
                "code": 503,
                "message": "No available upstream provider could serve the request.",
                "metadata": { "provider_name": "Synthetic Provider" }
            }
        }),
        None,
    )
    .await;

    let client = HttpClient::new().expect("client");
    let url = chat_url(&server).await;
    let cancel = CancellationToken::new();
    let error = stream_chat(
        &client,
        &url,
        "sk-test",
        &json!({}),
        &BTreeMap::new(),
        &cancel,
        |_| {},
    )
    .await
    .expect_err("503 should fail");

    assert_eq!(error.code, ChatErrorCode::Provider);
    assert_eq!(error.status, Some(503));
    assert!(error.retryable);
    assert_eq!(
        error.details,
        Some(json!({ "providerName": "Synthetic Provider" }))
    );
}

#[tokio::test]
async fn http_401_does_not_leak_the_key() {
    let server = MockServer::start().await;
    mount_chat_error(
        &server,
        401,
        json!({
            "error": {
                "code": 401,
                "message": "Authentication failed. Check your OpenRouter API key."
            }
        }),
        None,
    )
    .await;

    let client = HttpClient::new().expect("client");
    let url = chat_url(&server).await;
    let cancel = CancellationToken::new();
    let error = stream_chat(
        &client,
        &url,
        "sk-secret-value",
        &json!({}),
        &BTreeMap::new(),
        &cancel,
        |_| {},
    )
    .await
    .expect_err("401 should fail");

    assert_eq!(error.code, ChatErrorCode::Auth);
    assert!(!error.retryable);
    assert!(!error.message.contains("sk-secret-value"));
    assert!(!error.message.contains("Bearer"));
}

#[tokio::test]
async fn cancellation_before_first_token_preserves_no_text() {
    let server = ChunkedSseServer::start(vec![
        SseChunk::new(
            "data: {\"id\":\"gen-cancel\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"late\"},\"finish_reason\":null}]}\n\n",
        )
        .with_delay(Duration::from_secs(30)),
    ])
    .await;

    let client = HttpClient::new().expect("client");
    let url = Url::parse(&format!("{}/chat/completions", server.uri())).expect("valid chat URL");
    let cancel = CancellationToken::new();
    let cancel_for_task = cancel.clone();

    tokio::spawn(async move {
        tokio::time::sleep(Duration::from_millis(50)).await;
        cancel_for_task.cancel();
    });

    let mut events = Vec::new();
    let summary = tokio::time::timeout(
        Duration::from_secs(2),
        stream_chat(
            &client,
            &url,
            "sk-test",
            &json!({}),
            &BTreeMap::new(),
            &cancel,
            |event| events.push(event),
        ),
    )
    .await
    .expect("cancellation should stop the stream")
    .expect("stream summary");

    assert!(summary.text.is_empty());
    assert!(summary.reasoning.is_empty());
    assert_eq!(
        summary.error.as_ref().map(|error| error.code),
        Some(ChatErrorCode::Cancelled)
    );
    assert!(!summary.retry_allowed);
    assert!(events.is_empty());
    server.shutdown();
}

#[tokio::test]
async fn cancellation_after_first_token_preserves_partial_text() {
    let server = ChunkedSseServer::start(vec![
        SseChunk::new(
            "data: {\"id\":\"gen-cancel\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"partial\"},\"finish_reason\":null}]}\n\n",
        ),
        SseChunk::new(
            "data: {\"id\":\"gen-cancel\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\" more\"},\"finish_reason\":null}]}\n\n",
        )
        .with_delay(Duration::from_secs(30)),
    ])
    .await;

    let client = HttpClient::new().expect("client");
    let url = Url::parse(&format!("{}/chat/completions", server.uri())).expect("valid chat URL");
    let cancel = CancellationToken::new();
    let cancel_for_event = cancel.clone();
    let mut events = Vec::new();

    let summary = tokio::time::timeout(
        Duration::from_secs(2),
        stream_chat(
            &client,
            &url,
            "sk-test",
            &json!({}),
            &BTreeMap::new(),
            &cancel,
            |event| {
                if matches!(event, StreamEvent::TextDelta { .. }) {
                    cancel_for_event.cancel();
                }
                events.push(event);
            },
        ),
    )
    .await
    .expect("cancellation should stop the stream")
    .expect("stream summary");

    assert_eq!(summary.text, "partial");
    assert_eq!(
        summary.error.as_ref().map(|error| error.code),
        Some(ChatErrorCode::Cancelled)
    );
    assert!(!summary.retry_allowed);
    assert!(
        events
            .iter()
            .any(|event| matches!(event, StreamEvent::TextDelta { text } if text == "partial"))
    );
    server.shutdown();
}

#[tokio::test]
async fn retry_allowed_before_partial_output() {
    let server = ChunkedSseServer::start_abrupt(vec![]).await;

    let client = HttpClient::new().expect("client");
    let url = Url::parse(&format!("{}/chat/completions", server.uri())).expect("valid chat URL");
    let cancel = CancellationToken::new();
    let summary = stream_chat(
        &client,
        &url,
        "sk-test",
        &json!({}),
        &BTreeMap::new(),
        &cancel,
        |_| {},
    )
    .await
    .expect("stream summary");

    assert!(summary.text.is_empty());
    assert!(summary.error.is_some());
    assert!(summary.retry_allowed);
    server.shutdown();
}

#[tokio::test]
async fn retry_not_allowed_after_partial_output() {
    let server = ChunkedSseServer::start_abrupt(vec![SseChunk::new(
        "data: {\"id\":\"gen-partial\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"partial\"},\"finish_reason\":null}]}\n\n",
    )])
    .await;

    let client = HttpClient::new().expect("client");
    let url = Url::parse(&format!("{}/chat/completions", server.uri())).expect("valid chat URL");
    let cancel = CancellationToken::new();
    let summary = stream_chat(
        &client,
        &url,
        "sk-test",
        &json!({}),
        &BTreeMap::new(),
        &cancel,
        |_| {},
    )
    .await
    .expect("stream summary");

    assert_eq!(summary.text, "partial");
    assert!(summary.error.is_some());
    assert!(!summary.retry_allowed);
    server.shutdown();
}
