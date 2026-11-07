//! OpenRouter adapter conformance suite.
//!
//! This is the M3.5-06.4 gate. It drives the stable adapter surface against the
//! shared fixtures and mocks and covers the section 7.4 OpenRouter checklist:
//! `/key`, `/models`, request bodies and headers, text/keep-alive/reasoning/
//! usage streams, HTTP and in-stream errors, routing and protected fields,
//! cancellation, and retry rules.

use std::collections::BTreeMap;
use std::time::Duration;

use chatui_conformance::{
    ConformanceFixtures, ExpectedChatError, ExpectedStreamEvent, NormalizedRequest,
    RequestExpectation, RequestKind, RequestVector, ResolvedAttachment as FixtureAttachment,
    StreamExpectation,
};
use chatui_contracts::{AppConfig, provider_catalog};
use chatui_domain::{
    ChatError, ChatErrorCode, ChatParams, ContentPart, MessageRole, ModelCapability, StreamEvent,
};
use chatui_providers::adapter::{ProviderAdapter, ProviderContext};
use chatui_providers::cancel::CancellationToken;
use chatui_providers::http::HttpClient;
use chatui_providers::openrouter::request::{
    DefaultModelCandidate, NormalizedChatMessage, NormalizedChatRequest, ResolvedAttachment,
    select_default_model,
};
use chatui_providers::registry::{ProviderRegistry, RegistryError, create_adapter};
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

fn openrouter_context(base_url: &str, api_key: Option<&str>) -> ProviderContext {
    let mut preset = provider_catalog()
        .get("openrouter")
        .expect("openrouter preset")
        .clone();
    preset.base_url = base_url.to_owned();
    ProviderContext::new(&preset, &app_config(), api_key.map(str::to_owned))
}

fn openrouter_adapter() -> ProviderAdapter {
    create_adapter("openrouter").expect("openrouter adapter")
}

fn minimal_request() -> NormalizedChatRequest {
    NormalizedChatRequest {
        model: "openrouter/auto".to_owned(),
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

fn openrouter_request_vectors() -> Vec<RequestVector> {
    fixtures()
        .request_vectors()
        .expect("request vectors load")
        .into_iter()
        .filter(|vector| vector.provider_id == "openrouter")
        .collect()
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
        .and(path("/api/v1/chat/completions"))
        .respond_with(response)
        .mount(server)
        .await;
}

async fn stream_error(
    server: &MockServer,
    status: u16,
    body: Value,
    retry_after: Option<&str>,
) -> ChatError {
    mount_chat_error(server, status, body, retry_after).await;

    let adapter = openrouter_adapter();
    let ctx = openrouter_context(&format!("{}/api/v1", server.uri()), Some("sk-test-key"));
    let client = HttpClient::new().expect("client");
    let cancel = CancellationToken::new();

    adapter
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
        .expect_err("HTTP error")
}

#[test]
fn registry_loads_every_contract_preset() {
    let registry = ProviderRegistry::load().expect("registry loads");

    assert_eq!(registry.presets().len(), 14);
    assert_eq!(registry.default_preset().id, "openrouter");
    assert_eq!(registry.reference_preset().id, "openrouter");

    for preset in registry.presets() {
        let adapter = registry.adapter(&preset.id).expect("adapter exists");
        assert_eq!(adapter.preset_id(), preset.id);
        assert_eq!(adapter.kind(), preset.kind);
    }

    assert!(
        registry
            .adapter("openrouter")
            .expect("openrouter adapter")
            .is_supported()
    );
    for preset in registry.presets() {
        let adapter = registry.adapter(&preset.id).expect("adapter exists");
        let expected = matches!(
            preset.model_discovery.format,
            chatui_contracts::ModelDiscoveryFormat::Openrouter
                | chatui_contracts::ModelDiscoveryFormat::Openai
                | chatui_contracts::ModelDiscoveryFormat::Anthropic
                | chatui_contracts::ModelDiscoveryFormat::Gemini
        );
        assert_eq!(
            adapter.is_supported(),
            expected,
            "{} support does not match its discovery format",
            preset.id
        );
    }
    assert!(
        !registry
            .adapter("ollama")
            .expect("adapter exists")
            .is_supported(),
        "ollama must remain unsupported"
    );
}

#[test]
fn registry_rejects_duplicate_ids() {
    let preset = provider_catalog()
        .get("openrouter")
        .expect("openrouter preset")
        .clone();

    let error = ProviderRegistry::from_presets(vec![preset.clone(), preset])
        .expect_err("duplicate IDs are rejected");
    assert_eq!(error, RegistryError::DuplicateId("openrouter".to_owned()));
}

#[test]
fn registry_rejects_missing_required_fields() {
    let mut preset = provider_catalog()
        .get("openrouter")
        .expect("openrouter preset")
        .clone();
    preset.base_url = String::new();

    let error =
        ProviderRegistry::from_presets(vec![preset]).expect_err("missing base URL is rejected");
    assert!(matches!(
        error,
        RegistryError::MissingRequiredField {
            field: "baseUrl",
            ..
        }
    ));
}

#[test]
fn create_adapter_rejects_unknown_presets() {
    assert_eq!(
        create_adapter("nope").expect_err("unknown preset"),
        RegistryError::UnknownPreset("nope".to_owned())
    );
}

#[tokio::test]
async fn unsupported_presets_return_a_clear_error() {
    let registry = ProviderRegistry::load().expect("registry loads");
    let adapter = create_adapter("ollama").expect("ollama adapter");
    let ctx = registry
        .context("ollama", &app_config(), Some("sk-test".to_owned()))
        .expect("context");
    let client = HttpClient::new().expect("client");
    let cancel = CancellationToken::new();

    let error = adapter
        .validate_key(&client, &ctx, &cancel)
        .await
        .expect_err("unsupported preset");
    assert_eq!(error.code, ChatErrorCode::Unknown);
    assert!(error.message.contains("not supported"));
    assert_eq!(error.provider_id.as_deref(), Some("ollama"));
}

#[tokio::test]
async fn validate_key_maps_the_shared_fixture() {
    let server = MockServer::start().await;
    let key_fixture: Value = fixtures()
        .root()
        .read_json("models/openrouter-key.json")
        .expect("key fixture loads");
    Mock::given(method("GET"))
        .and(path("/api/v1/key"))
        .and(header("authorization", "Bearer sk-test-key"))
        .respond_with(ResponseTemplate::new(200).set_body_json(key_fixture))
        .mount(&server)
        .await;

    let adapter = openrouter_adapter();
    let ctx = openrouter_context(&format!("{}/api/v1", server.uri()), Some("sk-test-key"));
    let client = HttpClient::new().expect("client");
    let cancel = CancellationToken::new();
    let info = adapter
        .validate_key(&client, &ctx, &cancel)
        .await
        .expect("key validates");

    let expected = fixtures().key_expectation().expect("key expectation loads");
    assert_eq!(info.label.as_deref(), Some(expected.key.label.as_str()));
    assert_eq!(info.usage_usd, Some(expected.key.usage_usd));
    assert_eq!(info.limit_usd, expected.key.limit_usd);
    assert_eq!(info.limit_remaining_usd, expected.key.limit_remaining_usd);
    assert_eq!(info.is_free_tier, Some(expected.key.is_free_tier));
    assert!(info.rate_limit.is_some());

    let requests = server.received_requests().await.expect("requests");
    assert_eq!(requests.len(), 1);
    assert!(!requests[0].url.as_str().contains("sk-test-key"));
}

#[tokio::test]
async fn list_models_maps_the_shared_catalog() {
    let server = MockServer::start().await;
    let models_fixture: Value = fixtures()
        .root()
        .read_json("models/openrouter-models.json")
        .expect("models fixture loads");
    Mock::given(method("GET"))
        .and(path("/api/v1/models"))
        .respond_with(ResponseTemplate::new(200).set_body_json(models_fixture))
        .mount(&server)
        .await;

    let adapter = openrouter_adapter();
    let ctx = openrouter_context(&format!("{}/api/v1", server.uri()), None);
    let client = HttpClient::new().expect("client");
    let cancel = CancellationToken::new();
    let models = adapter
        .list_models(&client, &ctx, &cancel)
        .await
        .expect("catalog loads");

    assert_eq!(models.len(), 8);

    let paid = models
        .iter()
        .find(|model| model.remote_id == "openai/gpt-4o-mini-2024-07-18")
        .expect("paid model");
    assert_eq!(paid.context_window, Some(128_000));
    assert_eq!(paid.max_output_tokens, Some(16_384));
    assert!(paid.capabilities.contains(&ModelCapability::Tools));

    let free = models
        .iter()
        .find(|model| model.remote_id == "meta-llama/llama-3.2-3b-instruct:free")
        .expect("free model");
    assert!(free.is_free);

    let vision = models
        .iter()
        .find(|model| model.remote_id == "google/gemini-2.5-flash")
        .expect("vision model");
    assert!(vision.capabilities.contains(&ModelCapability::Vision));

    let reasoning = models
        .iter()
        .find(|model| model.remote_id == "deepseek/deepseek-r1")
        .expect("reasoning model");
    assert!(reasoning.capabilities.contains(&ModelCapability::Reasoning));

    let long_context = models
        .iter()
        .find(|model| model.remote_id == "google/gemini-2.5-pro")
        .expect("long-context model");
    assert_eq!(long_context.context_window, Some(1_048_576));

    let requests = server.received_requests().await.expect("requests");
    assert_eq!(requests.len(), 1);
    assert!(requests[0].headers.get("authorization").is_none());
}

#[tokio::test]
async fn every_openrouter_stream_vector_matches_through_the_adapter() {
    let expectations = openrouter_stream_expectations();
    assert!(
        !expectations.is_empty(),
        "expected OpenRouter stream vectors"
    );

    for expectation in expectations {
        let server = MockServer::start().await;
        let body = fixture_contents(&expectation.source);
        Mock::given(method("POST"))
            .and(path("/api/v1/chat/completions"))
            .and(header("authorization", "Bearer sk-test-key"))
            .respond_with(
                ResponseTemplate::new(200)
                    .insert_header("content-type", "text/event-stream")
                    .set_body_string(body),
            )
            .mount(&server)
            .await;

        let adapter = openrouter_adapter();
        let ctx = openrouter_context(&format!("{}/api/v1", server.uri()), Some("sk-test-key"));
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
async fn in_stream_error_preserves_partial_text_and_does_not_retry() {
    let server = MockServer::start().await;
    let body = fixture_contents("openrouter-error-200.sse");
    Mock::given(method("POST"))
        .and(path("/api/v1/chat/completions"))
        .respond_with(
            ResponseTemplate::new(200)
                .insert_header("content-type", "text/event-stream")
                .set_body_string(body),
        )
        .mount(&server)
        .await;

    let adapter = openrouter_adapter();
    let ctx = openrouter_context(&format!("{}/api/v1", server.uri()), Some("sk-test-key"));
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
        .expect("stream summary");

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
}

#[tokio::test]
async fn every_openrouter_http_error_vector_matches_through_the_adapter() {
    let vectors = fixtures().openrouter_errors().expect("error vectors load");

    for case in vectors.cases {
        let server = MockServer::start().await;
        let status = case.status.expect("status");
        let body = if let Some(fixture) = &case.fixture {
            fixtures()
                .root()
                .read_json::<Value>(fixture)
                .expect("error fixture loads")
        } else {
            json!({
                "error": {
                    "code": status,
                    "message": case.error.message,
                }
            })
        };

        let error = stream_error(&server, status, body, None).await;
        assert_error_eq(&error, &case.error);
    }
}

#[tokio::test]
async fn http_429_preserves_retry_after_and_is_retryable() {
    let server = MockServer::start().await;
    let body = json!({
        "error": {
            "code": 429,
            "message": "Rate limit exceeded. Please retry after 2 seconds."
        }
    });

    let error = stream_error(&server, 429, body, Some("2")).await;
    assert_eq!(error.code, ChatErrorCode::RateLimit);
    assert!(error.retryable);
    assert_eq!(error.retry_after_seconds, Some(2.0));
}

#[tokio::test]
async fn http_402_is_not_retryable() {
    let server = MockServer::start().await;
    let body = json!({
        "error": {
            "code": 402,
            "message": "Insufficient credits. Add more credits to continue."
        }
    });

    let error = stream_error(&server, 402, body, None).await;
    assert_eq!(error.code, ChatErrorCode::Quota);
    assert!(!error.retryable);
}

#[tokio::test]
async fn http_503_is_retryable() {
    let server = MockServer::start().await;
    let body = json!({
        "error": {
            "code": 503,
            "message": "No available upstream provider could serve the request."
        }
    });

    let error = stream_error(&server, 503, body, None).await;
    assert_eq!(error.code, ChatErrorCode::Provider);
    assert!(error.retryable);
}

#[test]
fn every_openrouter_request_vector_matches_through_the_adapter() {
    let adapter = openrouter_adapter();
    let ctx = openrouter_context("https://openrouter.ai/api/v1", Some("sk-test-key"));

    for vector in openrouter_request_vectors() {
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
            RequestKind::Attribution => {
                let config = vector.app_config.as_ref().expect("app config");
                let mut preset = provider_catalog()
                    .get("openrouter")
                    .expect("openrouter preset")
                    .clone();
                preset.base_url = "https://openrouter.ai/api/v1".to_owned();
                let ctx = ProviderContext::new(&preset, config, Some("sk-test-key".to_owned()));

                match vector.expected.as_ref().expect("expected result") {
                    RequestExpectation::Headers { headers } => {
                        assert_eq!(&ctx.headers, headers, "{}", vector.name);
                    }
                    other => panic!("{} expected headers, got {other:?}", vector.name),
                }
            }
            RequestKind::DefaultModel => {
                for case in &vector.cases {
                    let candidates: Vec<DefaultModelCandidate> = case
                        .catalog
                        .iter()
                        .map(|entry| DefaultModelCandidate {
                            remote_id: entry.remote_id.clone(),
                            hidden: entry.hidden,
                            output_modalities: entry.output_modalities.clone(),
                        })
                        .collect();
                    let selection =
                        select_default_model(&case.preset_default_model_id, &candidates);
                    assert_eq!(
                        selection.selected_model_id, case.expected.selected_model_id,
                        "{}",
                        case.name
                    );
                    assert_eq!(selection.notice, case.expected.notice, "{}", case.name);
                }
            }
        }
    }
}

#[tokio::test]
async fn cancellation_before_first_token_returns_cancelled() {
    let server = ChunkedSseServer::start(vec![
        SseChunk::new(
            "data: {\"id\":\"gen-cancel\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"late\"},\"finish_reason\":null}]}\n\n",
        )
        .with_delay(Duration::from_secs(30)),
    ])
    .await;

    let adapter = openrouter_adapter();
    let ctx = openrouter_context(&format!("{}/api/v1", server.uri()), Some("sk-test-key"));
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

    let adapter = openrouter_adapter();
    let ctx = openrouter_context(&format!("{}/api/v1", server.uri()), Some("sk-test-key"));
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
async fn retry_allowed_before_partial_output() {
    let server = ChunkedSseServer::start_abrupt(vec![]).await;

    let adapter = openrouter_adapter();
    let ctx = openrouter_context(&format!("{}/api/v1", server.uri()), Some("sk-test-key"));
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
async fn retry_not_allowed_after_partial_output() {
    let server = ChunkedSseServer::start_abrupt(vec![SseChunk::new(
        "data: {\"id\":\"gen-partial\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"partial\"},\"finish_reason\":null}]}\n\n",
    )])
    .await;

    let adapter = openrouter_adapter();
    let ctx = openrouter_context(&format!("{}/api/v1", server.uri()), Some("sk-test-key"));
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
