//! Ollama adapter, discovery fallback, and capability inference conformance.
//!
//! This is the M3.5-07.4 gate. It drives the native Ollama adapter against the
//! shared request, stream, error, and `/api/tags` vectors, then proves the
//! shared fallback selection and capability inference rules. It also proves the
//! documented OpenAI-compatible fallback for custom local servers.

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
use chatui_providers::capabilities::infer_model_metadata;
use chatui_providers::discovery::{
    FallbackCatalogEntry, fallback_model_ids_for, select_fallback_model,
};
use chatui_providers::http::HttpClient;
use chatui_providers::ollama::{build_chat_url, build_tags_url, ollama_native_base_url};
use chatui_providers::openai_catalog::defaults_for_preset;
use chatui_providers::openrouter::request::{
    NormalizedChatMessage, NormalizedChatRequest, ResolvedAttachment,
};
use chatui_providers::registry::{ProviderRegistry, create_adapter};
use serde_json::{Value, json};
use wiremock::matchers::{method, path};
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

fn ollama_request_vectors() -> Vec<RequestVector> {
    fixtures()
        .request_vectors()
        .expect("request vectors load")
        .into_iter()
        .filter(|vector| vector.provider_id == "ollama")
        .collect()
}

fn ollama_stream_expectations() -> Vec<StreamExpectation> {
    fixtures()
        .stream_expectations()
        .expect("stream expectations load")
        .into_iter()
        .filter(|expectation| expectation.file.starts_with("ollama-"))
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
fn registry_enables_ollama() {
    let registry = ProviderRegistry::load().expect("registry loads");
    let adapter = registry.adapter("ollama").expect("ollama adapter");

    assert!(adapter.is_supported());
    assert_eq!(adapter.preset_id(), "ollama");
    assert_eq!(
        adapter.kind(),
        chatui_domain::ProviderKind::OpenaiCompatible
    );
}

#[test]
fn every_ollama_request_vector_matches_through_the_adapter() {
    let adapter = adapter_for("ollama");
    let ctx = context_for("ollama", "http://localhost:11434/v1", None);

    for vector in ollama_request_vectors() {
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
fn ollama_native_urls_strip_the_v1_suffix() {
    assert_eq!(
        ollama_native_base_url("http://localhost:11434/v1"),
        "http://localhost:11434"
    );
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

#[tokio::test]
async fn every_ollama_stream_vector_matches_through_the_adapter() {
    let expectations = ollama_stream_expectations();
    assert!(!expectations.is_empty(), "expected Ollama stream vectors");

    for expectation in expectations {
        let server = MockServer::start().await;
        let body = fixture_contents(&expectation.source);
        Mock::given(method("POST"))
            .and(path("/api/chat"))
            .respond_with(
                ResponseTemplate::new(200)
                    .insert_header("content-type", "application/x-ndjson")
                    .set_body_string(body),
            )
            .mount(&server)
            .await;

        let adapter = adapter_for("ollama");
        let ctx = context_for("ollama", &format!("{}/v1", server.uri()), None);
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

#[tokio::test]
async fn every_ollama_http_error_vector_matches_through_the_adapter() {
    let vectors = fixtures().provider_errors().expect("error vectors load");

    for case in vectors
        .cases
        .into_iter()
        .filter(|case| case.provider_id.as_deref() == Some("ollama"))
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
            .and(path("/api/chat"))
            .respond_with(response)
            .mount(&server)
            .await;

        let adapter = adapter_for("ollama");
        let ctx = context_for("ollama", &format!("{}/v1", server.uri()), None);
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
    }
}

#[tokio::test]
async fn ollama_in_stream_error_matches_the_shared_vector() {
    let vectors = fixtures().provider_errors().expect("error vectors load");
    let case = vectors
        .cases
        .into_iter()
        .find(|case| case.name == "ollama-stream-error")
        .expect("ollama stream error case");
    let body = fixtures()
        .root()
        .read(case.fixture.as_ref().expect("fixture"))
        .expect("stream error fixture loads");

    let server = MockServer::start().await;
    Mock::given(method("POST"))
        .and(path("/api/chat"))
        .respond_with(
            ResponseTemplate::new(200)
                .insert_header("content-type", "application/x-ndjson")
                .set_body_string(body),
        )
        .mount(&server)
        .await;

    let adapter = adapter_for("ollama");
    let ctx = context_for("ollama", &format!("{}/v1", server.uri()), None);
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
async fn ollama_catalog_maps_the_shared_vector() {
    let server = MockServer::start().await;
    let models_fixture: Value = fixtures()
        .root()
        .read_json("models/ollama-tags.json")
        .expect("models fixture loads");
    Mock::given(method("GET"))
        .and(path("/api/tags"))
        .respond_with(ResponseTemplate::new(200).set_body_json(models_fixture))
        .mount(&server)
        .await;

    let adapter = adapter_for("ollama");
    let ctx = context_for("ollama", &format!("{}/v1", server.uri()), None);
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
        .find(|case| case.provider_id == "ollama")
        .expect("ollama catalog case");

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

#[test]
fn ollama_capability_inference_matches_the_shared_vector() {
    let vectors = fixtures()
        .capability_inference()
        .expect("capability inference loads");
    let defaults = defaults_for_preset(provider_catalog().get("ollama").expect("ollama preset"));

    for case in vectors
        .cases
        .into_iter()
        .filter(|case| case.preset.id == "ollama")
    {
        let inferred = infer_model_metadata(&case.raw, &defaults);

        assert_eq!(
            serde_json::to_value(&inferred.input_modalities).expect("modalities serialize"),
            json!(case.expected.input_modalities),
            "{} input modalities",
            case.name
        );
        assert_eq!(
            serde_json::to_value(&inferred.output_modalities).expect("modalities serialize"),
            json!(case.expected.output_modalities),
            "{} output modalities",
            case.name
        );
        assert_eq!(
            serde_json::to_value(&inferred.capabilities).expect("capabilities serialize"),
            json!(case.expected.capabilities),
            "{} capabilities",
            case.name
        );
        assert_eq!(
            inferred.supported_parameters, case.expected.supported_parameters,
            "{} supported parameters",
            case.name
        );
        assert_eq!(
            inferred.context_window, case.expected.context_window,
            "{} context window",
            case.name
        );
        assert_eq!(
            inferred.max_output_tokens, case.expected.max_output_tokens,
            "{} max output tokens",
            case.name
        );
    }
}

#[test]
fn fallback_model_ids_match_the_shared_vector() {
    let vectors = fixtures().fallback_models().expect("fallback models load");

    for preset in vectors.presets {
        let contract = provider_catalog().get(&preset.provider_id);
        assert_eq!(
            fallback_model_ids_for(&preset.provider_id, contract),
            preset.fallback_model_ids,
            "{}",
            preset.provider_id
        );
    }
}

#[test]
fn fallback_selection_matches_the_shared_vector() {
    let vectors = fixtures().fallback_models().expect("fallback models load");

    for case in vectors.cases {
        let catalog: Vec<FallbackCatalogEntry> = case
            .catalog
            .iter()
            .map(|entry| FallbackCatalogEntry {
                remote_id: entry.remote_id.clone(),
                hidden: entry.hidden,
                output_modalities: entry.output_modalities.clone(),
            })
            .collect();
        let selection = select_fallback_model(
            &catalog,
            &case.preset_default_model_id,
            &case.fallback_model_ids,
        );

        assert_eq!(
            selection.selected_model_id, case.expected.selected_model_id,
            "{} selected model",
            case.name
        );
        assert_eq!(
            selection.notice, case.expected.notice,
            "{} notice",
            case.name
        );
    }
}

#[tokio::test]
async fn ollama_validate_key_is_a_no_op() {
    let adapter = adapter_for("ollama");
    let ctx = context_for("ollama", "http://localhost:11434/v1", None);
    let client = HttpClient::new().expect("client");
    let cancel = CancellationToken::new();

    let info = adapter
        .validate_key(&client, &ctx, &cancel)
        .await
        .expect("key validates");

    assert_eq!(info.label, None);
    assert_eq!(info.usage_usd, None);
}

#[tokio::test]
async fn ollama_openai_compatible_fallback_uses_the_shared_transport() {
    let server = MockServer::start().await;
    let body = fixture_contents("openai-text.sse");
    Mock::given(method("POST"))
        .and(path("/v1/chat/completions"))
        .respond_with(
            ResponseTemplate::new(200)
                .insert_header("content-type", "text/event-stream")
                .set_body_string(body),
        )
        .mount(&server)
        .await;

    let adapter = adapter_for("ollama");
    let ctx = context_for("ollama", &format!("{}/v1", server.uri()), None);
    let client = HttpClient::new().expect("client");
    let cancel = CancellationToken::new();
    let mut request = minimal_request();
    request.params.extra.insert(
        "ollama".to_owned(),
        json!({ "transport": "openai-compatible" }),
    );

    let summary = adapter
        .stream_chat(
            &client,
            &ctx,
            &request,
            &[],
            &BTreeMap::new(),
            &cancel,
            &mut |_| {},
        )
        .await
        .expect("fallback stream");

    assert_eq!(summary.text, "Hello from OpenAI.");

    let requests = server.received_requests().await.expect("requests");
    assert_eq!(requests.len(), 1);
    let sent: Value = serde_json::from_slice(&requests[0].body).expect("request body");
    assert!(
        sent.get("transport").is_none(),
        "the transport selector must not reach the provider"
    );
    assert!(sent.get("messages").is_some());
}

#[tokio::test]
async fn ollama_cancellation_before_first_token_returns_cancelled() {
    let server = ChunkedSseServer::start(vec![
        SseChunk::new("{\"message\":{\"content\":\"late\"},\"done\":false}\n")
            .with_delay(Duration::from_secs(30)),
    ])
    .await;

    let adapter = adapter_for("ollama");
    let ctx = context_for("ollama", &format!("{}/v1", server.uri()), None);
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
async fn ollama_cancellation_after_first_token_preserves_partial_text() {
    let server = ChunkedSseServer::start(vec![
        SseChunk::new("{\"message\":{\"content\":\"partial\"},\"done\":false}\n"),
        SseChunk::new("{\"message\":{\"content\":\" more\"},\"done\":false}\n")
            .with_delay(Duration::from_secs(30)),
    ])
    .await;

    let adapter = adapter_for("ollama");
    let ctx = context_for("ollama", &format!("{}/v1", server.uri()), None);
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
async fn ollama_retry_allowed_before_partial_output() {
    let server = ChunkedSseServer::start_abrupt(vec![]).await;

    let adapter = adapter_for("ollama");
    let ctx = context_for("ollama", &format!("{}/v1", server.uri()), None);
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
async fn ollama_retry_not_allowed_after_partial_output() {
    let server = ChunkedSseServer::start_abrupt(vec![SseChunk::new(
        "{\"message\":{\"content\":\"partial\"},\"done\":false}\n",
    )])
    .await;

    let adapter = adapter_for("ollama");
    let ctx = context_for("ollama", &format!("{}/v1", server.uri()), None);
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
