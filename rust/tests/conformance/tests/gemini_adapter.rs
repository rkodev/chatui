//! Gemini adapter conformance suite.
//!
//! This is the M3.5-07.3 gate. It drives the native Google Gemini adapter
//! against the shared request, stream, finish-reason, error, and catalog
//! vectors and proves the desktop header policy: the key travels only in
//! `x-goog-api-key`, never in a URL or an error message.

use std::collections::BTreeMap;
use std::time::Duration;

use chatui_conformance::{
    ConformanceFixtures, ExpectedChatError, ExpectedStreamEvent, NormalizedRequest,
    RequestExpectation, RequestKind, RequestVector, ResolvedAttachment as FixtureAttachment,
    StreamExpectation,
};
use chatui_contracts::{AppConfig, provider_catalog};
use chatui_domain::{ChatError, ChatErrorCode, ChatParams, ContentPart, MessageRole, StreamEvent};
use chatui_providers::adapter::{ProviderAdapter, ProviderContext};
use chatui_providers::cancel::CancellationToken;
use chatui_providers::gemini::map_finish_reason;
use chatui_providers::http::HttpClient;
use chatui_providers::openrouter::request::{
    NormalizedChatMessage, NormalizedChatRequest, ResolvedAttachment,
};
use chatui_providers::registry::{ProviderRegistry, create_adapter};
use serde_json::{Value, json};
use wiremock::matchers::{header, method, path};
use wiremock::{Mock, MockServer, ResponseTemplate};

#[path = "../../../crates/chatui-providers/tests/network/harness.rs"]
#[allow(dead_code)]
mod harness;

use harness::{ChunkedSseServer, SseChunk};

fn fixtures() -> ConformanceFixtures {
    ConformanceFixtures::repository()
}

fn app_config() -> AppConfig {
    AppConfig::new("ChatUI", "https://chatui.example").expect("valid app config")
}

fn context_for(preset_id: &str, base_url: &str, api_key: Option<&str>) -> ProviderContext {
    let mut preset = provider_catalog()
        .get(preset_id)
        .unwrap_or_else(|| panic!("missing preset {preset_id}"))
        .clone();
    preset.base_url = base_url.to_owned();
    ProviderContext::new(&preset, &app_config(), api_key.map(str::to_owned))
}

fn adapter_for(preset_id: &str) -> ProviderAdapter {
    create_adapter(preset_id).unwrap_or_else(|error| panic!("{preset_id} adapter: {error}"))
}

fn minimal_request() -> NormalizedChatRequest {
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

fn gemini_request_vectors() -> Vec<RequestVector> {
    fixtures()
        .request_vectors()
        .expect("request vectors load")
        .into_iter()
        .filter(|vector| vector.provider_id == "google")
        .collect()
}

fn gemini_stream_expectations() -> Vec<StreamExpectation> {
    fixtures()
        .stream_expectations()
        .expect("stream expectations load")
        .into_iter()
        .filter(|expectation| expectation.file.starts_with("gemini-"))
        .collect()
}

fn fixture_contents(name: &str) -> String {
    fixtures()
        .root()
        .read(format!("streams/{name}"))
        .expect("stream fixture loads")
}

fn to_request(request: &NormalizedRequest) -> NormalizedChatRequest {
    NormalizedChatRequest {
        model: request.model.clone(),
        system: request.system.clone(),
        messages: request
            .messages
            .iter()
            .map(|message| NormalizedChatMessage {
                role: message.role,
                parts: message.parts.clone(),
            })
            .collect(),
        params: request.params.clone(),
    }
}

fn to_attachments(
    attachments: &BTreeMap<String, FixtureAttachment>,
) -> BTreeMap<String, ResolvedAttachment> {
    attachments
        .iter()
        .map(|(id, attachment)| {
            (
                id.clone(),
                ResolvedAttachment {
                    mime_type: attachment.mime_type.clone(),
                    name: attachment.name.clone(),
                    data_base64: attachment.data_base64.clone(),
                },
            )
        })
        .collect()
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

#[test]
fn registry_enables_gemini() {
    let registry = ProviderRegistry::load().expect("registry loads");
    let adapter = registry.adapter("google").expect("google adapter");

    assert!(adapter.is_supported());
    assert_eq!(adapter.preset_id(), "google");
    assert_eq!(adapter.kind(), chatui_domain::ProviderKind::GoogleGemini);

    assert!(
        registry
            .adapter("ollama")
            .expect("ollama adapter")
            .is_supported(),
        "ollama must be supported"
    );
}

#[test]
fn every_gemini_request_vector_matches_through_the_adapter() {
    let adapter = adapter_for("google");
    let ctx = context_for(
        "google",
        "https://generativelanguage.googleapis.com/v1beta",
        Some("sk-test-key"),
    );

    for vector in gemini_request_vectors() {
        match vector.kind {
            RequestKind::ChatRequest => {
                let request = to_request(vector.request.as_ref().expect("request"));
                let attachments = to_attachments(&vector.attachments);
                let prepared = adapter
                    .prepare_request(&ctx, &request, &vector.supported_parameters, &attachments)
                    .unwrap_or_else(|error| panic!("{} failed: {error:?}", vector.name));

                match vector.expected.as_ref().expect("expected result") {
                    RequestExpectation::Body { body } => {
                        assert_eq!(&prepared.body, body, "{}", vector.name);
                    }
                    other => panic!("{} expected body, got {other:?}", vector.name),
                }
            }
            RequestKind::ProtectedFields => {
                let request = to_request(vector.request.as_ref().expect("request"));
                let attachments = to_attachments(&vector.attachments);
                let error = adapter
                    .prepare_request(&ctx, &request, &vector.supported_parameters, &attachments)
                    .expect_err("protected fields must be rejected");

                match vector.expected.as_ref().expect("expected result") {
                    RequestExpectation::Error { error: expected } => {
                        assert_error_eq(&error, expected);
                    }
                    other => panic!("{} expected error, got {other:?}", vector.name),
                }
            }
            other => panic!("{} has unexpected kind {other:?}", vector.name),
        }
    }
}

#[test]
fn gemini_headers_use_x_goog_api_key() {
    let adapter = adapter_for("google");
    let ctx = context_for(
        "google",
        "https://generativelanguage.googleapis.com/v1beta",
        Some("sk-test-key"),
    );
    let prepared = adapter
        .prepare_request(&ctx, &minimal_request(), &[], &BTreeMap::new())
        .expect("request prepares");

    assert_eq!(
        prepared.headers.get("content-type").map(String::as_str),
        Some("application/json")
    );
    assert_eq!(
        prepared.headers.get("Accept").map(String::as_str),
        Some("text/event-stream")
    );
    assert!(
        !prepared
            .headers
            .values()
            .any(|value| value.contains("sk-test-key")),
        "non-secret headers must not contain the API key"
    );
}

#[tokio::test]
async fn every_gemini_stream_vector_matches_through_the_adapter() {
    let expectations = gemini_stream_expectations();
    assert!(!expectations.is_empty(), "expected Gemini stream vectors");

    for expectation in expectations {
        let server = MockServer::start().await;
        let body = fixture_contents(&expectation.source);
        Mock::given(method("POST"))
            .and(path(
                "/v1beta/models/gemini-2.5-flash:streamGenerateContent",
            ))
            .and(header("x-goog-api-key", "sk-test-key"))
            .respond_with(
                ResponseTemplate::new(200)
                    .insert_header("content-type", "text/event-stream")
                    .set_body_string(body),
            )
            .mount(&server)
            .await;

        let adapter = adapter_for("google");
        let ctx = context_for(
            "google",
            &format!("{}/v1beta", server.uri()),
            Some("sk-test-key"),
        );
        let client = HttpClient::new().expect("client");
        let cancel = CancellationToken::new();
        let mut events = Vec::new();
        let summary = adapter
            .stream_chat(
                &client,
                &ctx,
                &minimal_request(),
                &[],
                &BTreeMap::new(),
                &cancel,
                &mut |event| events.push(event),
            )
            .await
            .unwrap_or_else(|error| panic!("{} failed: {error:?}", expectation.file));

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

#[test]
fn gemini_finish_reasons_match_the_shared_vector() {
    let vectors = fixtures()
        .gemini_finish_reasons()
        .expect("finish-reason vectors load");

    for case in vectors.cases {
        assert_eq!(
            map_finish_reason(&case.finish_reason),
            case.expected,
            "{}",
            case.name
        );
    }
}

#[tokio::test]
async fn every_gemini_http_error_vector_matches_through_the_adapter() {
    let vectors = fixtures().provider_errors().expect("error vectors load");

    for case in vectors
        .cases
        .into_iter()
        .filter(|case| case.provider_id.as_deref() == Some("google"))
        .filter(|case| case.kind.as_deref() == Some("http"))
    {
        let server = MockServer::start().await;
        let status = case.status.expect("status");
        let fixture: Value = fixtures()
            .root()
            .read_json(case.fixture.as_ref().expect("fixture"))
            .expect("error fixture loads");
        let body = fixture.get("body").cloned().unwrap_or(fixture.clone());
        let mut response = ResponseTemplate::new(status).set_body_json(body);

        if let Some(headers) = fixture.get("headers").and_then(Value::as_object) {
            for (name, value) in headers {
                if let Some(value) = value.as_str() {
                    response = response.insert_header(name.as_str(), value);
                }
            }
        }

        Mock::given(method("POST"))
            .and(path(
                "/v1beta/models/gemini-2.5-flash:streamGenerateContent",
            ))
            .respond_with(response)
            .mount(&server)
            .await;

        let adapter = adapter_for("google");
        let ctx = context_for(
            "google",
            &format!("{}/v1beta", server.uri()),
            Some("sk-test-key"),
        );
        let client = HttpClient::new().expect("client");
        let cancel = CancellationToken::new();
        let error = adapter
            .stream_chat(
                &client,
                &ctx,
                &minimal_request(),
                &[],
                &BTreeMap::new(),
                &cancel,
                &mut |_| {},
            )
            .await
            .expect_err("HTTP error");

        assert_error_eq(&error, &case.error);
        assert!(!error.message.contains("sk-test-key"));
    }
}

#[tokio::test]
async fn gemini_in_stream_error_matches_the_shared_vector() {
    let vectors = fixtures().provider_errors().expect("error vectors load");
    let case = vectors
        .cases
        .into_iter()
        .find(|case| case.name == "gemini-stream-internal")
        .expect("gemini stream error case");
    let body = fixtures()
        .root()
        .read(case.fixture.as_ref().expect("fixture"))
        .expect("stream error fixture loads");

    let server = MockServer::start().await;
    Mock::given(method("POST"))
        .and(path(
            "/v1beta/models/gemini-2.5-flash:streamGenerateContent",
        ))
        .respond_with(
            ResponseTemplate::new(200)
                .insert_header("content-type", "text/event-stream")
                .set_body_string(body),
        )
        .mount(&server)
        .await;

    let adapter = adapter_for("google");
    let ctx = context_for(
        "google",
        &format!("{}/v1beta", server.uri()),
        Some("sk-test-key"),
    );
    let client = HttpClient::new().expect("client");
    let cancel = CancellationToken::new();
    let summary = adapter
        .stream_chat(
            &client,
            &ctx,
            &minimal_request(),
            &[],
            &BTreeMap::new(),
            &cancel,
            &mut |_| {},
        )
        .await
        .expect("stream summary");

    let error = summary.error.expect("in-stream error");
    assert_error_eq(&error, &case.error);
    assert!(!summary.retry_allowed);
}

#[tokio::test]
async fn gemini_catalog_maps_the_shared_vector() {
    let server = MockServer::start().await;
    let models_fixture: Value = fixtures()
        .root()
        .read_json("models/gemini-models.json")
        .expect("models fixture loads");
    Mock::given(method("GET"))
        .and(path("/v1beta/models"))
        .and(header("x-goog-api-key", "sk-test-key"))
        .respond_with(ResponseTemplate::new(200).set_body_json(models_fixture))
        .mount(&server)
        .await;

    let adapter = adapter_for("google");
    let ctx = context_for(
        "google",
        &format!("{}/v1beta", server.uri()),
        Some("sk-test-key"),
    );
    let client = HttpClient::new().expect("client");
    let cancel = CancellationToken::new();
    let models = adapter
        .list_models(&client, &ctx, &cancel)
        .await
        .expect("catalog loads");

    let case = fixtures()
        .catalogs()
        .expect("catalogs load")
        .cases
        .into_iter()
        .find(|case| case.provider_id == "google")
        .expect("google catalog case");

    assert_eq!(models.len(), case.expected.len());
    for (actual, expected) in models.iter().zip(&case.expected) {
        assert_eq!(actual.remote_id, expected.remote_id);
        assert_eq!(actual.label, expected.label);
        assert_eq!(actual.context_window, expected.context_window);
        assert_eq!(actual.max_output_tokens, expected.max_output_tokens);
        assert_eq!(
            serde_json::to_value(&actual.input_modalities).expect("modalities serialize"),
            json!(expected.input_modalities)
        );
        assert_eq!(
            serde_json::to_value(&actual.output_modalities).expect("modalities serialize"),
            json!(expected.output_modalities)
        );
        assert_eq!(
            serde_json::to_value(&actual.capabilities).expect("capabilities serialize"),
            json!(expected.capabilities)
        );
        assert_eq!(actual.supported_parameters, expected.supported_parameters);
        assert_eq!(
            serde_json::to_value(&actual.pricing).expect("pricing serializes"),
            expected.pricing.clone().unwrap_or(Value::Null)
        );
        assert_eq!(actual.hidden, expected.hidden);
    }
}

#[tokio::test]
async fn gemini_validate_key_uses_models_endpoint() {
    let server = MockServer::start().await;
    let models_fixture: Value = fixtures()
        .root()
        .read_json("models/gemini-models.json")
        .expect("models fixture loads");
    Mock::given(method("GET"))
        .and(path("/v1beta/models"))
        .and(header("x-goog-api-key", "sk-test-key"))
        .respond_with(ResponseTemplate::new(200).set_body_json(models_fixture))
        .mount(&server)
        .await;

    let adapter = adapter_for("google");
    let ctx = context_for(
        "google",
        &format!("{}/v1beta", server.uri()),
        Some("sk-test-key"),
    );
    let client = HttpClient::new().expect("client");
    let cancel = CancellationToken::new();
    let info = adapter
        .validate_key(&client, &ctx, &cancel)
        .await
        .expect("key validates");

    assert_eq!(info.label, None);
    assert_eq!(info.usage_usd, None);

    let requests = server.received_requests().await.expect("requests");
    assert_eq!(requests.len(), 1);
    assert!(!requests[0].url.as_str().contains("sk-test-key"));
    assert_eq!(
        requests[0]
            .headers
            .get("x-goog-api-key")
            .and_then(|value| value.to_str().ok()),
        Some("sk-test-key")
    );
}

#[tokio::test]
async fn gemini_credentials_stay_in_headers_and_never_in_urls() {
    let server = MockServer::start().await;
    let body = fixture_contents("gemini-text.sse");
    Mock::given(method("POST"))
        .and(path(
            "/v1beta/models/gemini-2.5-flash:streamGenerateContent",
        ))
        .and(header("x-goog-api-key", "sk-secret"))
        .respond_with(
            ResponseTemplate::new(200)
                .insert_header("content-type", "text/event-stream")
                .set_body_string(body),
        )
        .mount(&server)
        .await;

    let adapter = adapter_for("google");
    let ctx = context_for(
        "google",
        &format!("{}/v1beta", server.uri()),
        Some("sk-secret"),
    );
    let client = HttpClient::new().expect("client");
    let cancel = CancellationToken::new();
    let summary = adapter
        .stream_chat(
            &client,
            &ctx,
            &minimal_request(),
            &[],
            &BTreeMap::new(),
            &cancel,
            &mut |_| {},
        )
        .await
        .expect("stream summary");
    assert_eq!(summary.text, "Hello from Gemini.");

    let requests = server.received_requests().await.expect("requests");
    assert_eq!(requests.len(), 1);
    assert!(!requests[0].url.as_str().contains("sk-secret"));
    assert!(
        requests[0].url.as_str().contains("alt=sse"),
        "the streaming URL must request SSE"
    );
    assert_eq!(
        requests[0]
            .headers
            .get("x-goog-api-key")
            .and_then(|value| value.to_str().ok()),
        Some("sk-secret")
    );
}

#[tokio::test]
async fn gemini_cancellation_before_first_token_returns_cancelled() {
    let server = ChunkedSseServer::start(vec![
        SseChunk::new(
            "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"late\"}]}}]}\n\n",
        )
        .with_delay(Duration::from_secs(30)),
    ])
    .await;

    let adapter = adapter_for("google");
    let ctx = context_for(
        "google",
        &format!("{}/v1beta", server.uri()),
        Some("sk-test-key"),
    );
    let client = HttpClient::new().expect("client");
    let cancel = CancellationToken::new();
    let cancel_for_task = cancel.clone();

    tokio::spawn(async move {
        tokio::time::sleep(Duration::from_millis(50)).await;
        cancel_for_task.cancel();
    });

    let mut events = Vec::new();
    let summary = tokio::time::timeout(
        Duration::from_secs(2),
        adapter.stream_chat(
            &client,
            &ctx,
            &minimal_request(),
            &[],
            &BTreeMap::new(),
            &cancel,
            &mut |event| events.push(event),
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
async fn gemini_cancellation_after_first_token_preserves_partial_text() {
    let server = ChunkedSseServer::start(vec![
        SseChunk::new(
            "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"partial\"}]}}]}\n\n",
        ),
        SseChunk::new(
            "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\" more\"}]}}]}\n\n",
        )
        .with_delay(Duration::from_secs(30)),
    ])
    .await;

    let adapter = adapter_for("google");
    let ctx = context_for(
        "google",
        &format!("{}/v1beta", server.uri()),
        Some("sk-test-key"),
    );
    let client = HttpClient::new().expect("client");
    let cancel = CancellationToken::new();
    let cancel_for_event = cancel.clone();
    let mut events = Vec::new();

    let summary = tokio::time::timeout(
        Duration::from_secs(2),
        adapter.stream_chat(
            &client,
            &ctx,
            &minimal_request(),
            &[],
            &BTreeMap::new(),
            &cancel,
            &mut |event| {
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
async fn gemini_retry_allowed_before_partial_output() {
    let server = ChunkedSseServer::start_abrupt(vec![]).await;

    let adapter = adapter_for("google");
    let ctx = context_for(
        "google",
        &format!("{}/v1beta", server.uri()),
        Some("sk-test-key"),
    );
    let client = HttpClient::new().expect("client");
    let cancel = CancellationToken::new();
    let summary = adapter
        .stream_chat(
            &client,
            &ctx,
            &minimal_request(),
            &[],
            &BTreeMap::new(),
            &cancel,
            &mut |_| {},
        )
        .await
        .expect("stream summary");

    assert!(summary.text.is_empty());
    assert!(summary.error.is_some());
    assert!(summary.retry_allowed);
    server.shutdown();
}

#[tokio::test]
async fn gemini_retry_not_allowed_after_partial_output() {
    let server = ChunkedSseServer::start_abrupt(vec![SseChunk::new(
        "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"partial\"}]}}]}\n\n",
    )])
    .await;

    let adapter = adapter_for("google");
    let ctx = context_for(
        "google",
        &format!("{}/v1beta", server.uri()),
        Some("sk-test-key"),
    );
    let client = HttpClient::new().expect("client");
    let cancel = CancellationToken::new();
    let summary = adapter
        .stream_chat(
            &client,
            &ctx,
            &minimal_request(),
            &[],
            &BTreeMap::new(),
            &cancel,
            &mut |_| {},
        )
        .await
        .expect("stream summary");

    assert_eq!(summary.text, "partial");
    assert!(summary.error.is_some());
    assert!(!summary.retry_allowed);
    server.shutdown();
}
