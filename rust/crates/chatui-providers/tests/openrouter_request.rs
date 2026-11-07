//! OpenRouter request serialization tests against the shared request vectors.

use std::collections::BTreeMap;

use chatui_conformance::{
    ConformanceFixtures, ExpectedChatError, NormalizedRequest, RequestExpectation, RequestKind,
    RequestVector, ResolvedAttachment as FixtureAttachment,
};
use chatui_contracts::AppConfig;
use chatui_domain::{
    ChatError, ChatErrorCode, ChatParams, ContentPart, MessageRole, ReasoningEffort,
};
use chatui_providers::openrouter::request::{
    DefaultModelCandidate, NormalizedChatMessage, NormalizedChatRequest, ResolvedAttachment,
    attribution_headers, build_body, build_request, select_default_model,
};
use serde_json::{Value, json};

fn fixtures() -> ConformanceFixtures {
    ConformanceFixtures::repository()
}

fn openrouter_vectors() -> Vec<RequestVector> {
    fixtures()
        .request_vectors()
        .expect("request vectors load")
        .into_iter()
        .filter(|vector| vector.provider_id == "openrouter")
        .collect()
}

fn vector(name: &str) -> RequestVector {
    openrouter_vectors()
        .into_iter()
        .find(|vector| vector.name == name)
        .unwrap_or_else(|| panic!("missing request vector {name}"))
}

fn app_config() -> AppConfig {
    AppConfig::new("ChatUI", "https://chatui.example").expect("valid app config")
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

fn expected_body(vector: &RequestVector) -> Value {
    match vector.expected.as_ref().expect("expected result") {
        RequestExpectation::Body { body } => body.clone(),
        other => panic!("expected body, got {other:?}"),
    }
}

fn expected_error(vector: &RequestVector) -> &ExpectedChatError {
    match vector.expected.as_ref().expect("expected result") {
        RequestExpectation::Error { error } => error,
        other => panic!("expected error, got {other:?}"),
    }
}

fn expected_headers(vector: &RequestVector) -> &BTreeMap<String, String> {
    match vector.expected.as_ref().expect("expected result") {
        RequestExpectation::Headers { headers } => headers,
        other => panic!("expected headers, got {other:?}"),
    }
}

fn assert_error_matches(actual: &ChatError, expected: &ExpectedChatError) {
    assert_eq!(actual.code, expected.code);
    assert_eq!(actual.message, expected.message);
    assert_eq!(actual.provider_id, expected.provider_id);
    assert_eq!(actual.status, expected.status);
    assert_eq!(actual.retryable, expected.retryable);
    assert_eq!(actual.retry_after_seconds, expected.retry_after_seconds);
    assert_eq!(actual.request_id, expected.request_id);
    assert_eq!(actual.details, expected.details);
}

fn text_part(text: &str) -> ContentPart {
    ContentPart::Text {
        text: text.to_owned(),
    }
}

fn image_part(id: &str, mime_type: &str) -> ContentPart {
    ContentPart::Image {
        attachment_id: id.to_owned(),
        mime_type: mime_type.to_owned(),
        alt: "alt".to_owned(),
    }
}

fn file_part(id: &str, mime_type: &str, name: &str) -> ContentPart {
    ContentPart::File {
        attachment_id: id.to_owned(),
        mime_type: mime_type.to_owned(),
        name: name.to_owned(),
    }
}

fn attachment(mime_type: &str, name: &str, data_base64: &str) -> ResolvedAttachment {
    ResolvedAttachment {
        mime_type: mime_type.to_owned(),
        name: name.to_owned(),
        data_base64: data_base64.to_owned(),
    }
}

fn simple_request(parts: Vec<ContentPart>, params: ChatParams) -> NormalizedChatRequest {
    NormalizedChatRequest {
        model: "openai/gpt-4o-mini-2024-07-18".to_owned(),
        system: None,
        messages: vec![NormalizedChatMessage {
            role: MessageRole::User,
            parts,
        }],
        params,
    }
}

#[test]
fn every_openrouter_request_vector_matches() {
    let vectors = openrouter_vectors();
    assert!(!vectors.is_empty(), "no OpenRouter request vectors found");

    for vector in vectors {
        match vector.kind {
            RequestKind::ChatRequest => {
                let request = to_request(vector.request.as_ref().expect("request"));
                let attachments = to_attachments(&vector.attachments);
                let body = build_body(&request, &vector.supported_parameters, &attachments)
                    .unwrap_or_else(|error| panic!("{} failed: {error:?}", vector.name));
                assert_eq!(body, expected_body(&vector), "{}", vector.name);
            }
            RequestKind::ProtectedFields => {
                let request = to_request(vector.request.as_ref().expect("request"));
                let attachments = to_attachments(&vector.attachments);
                let error = build_body(&request, &vector.supported_parameters, &attachments)
                    .expect_err("protected fields must be rejected");
                assert_error_matches(&error, expected_error(&vector));
            }
            RequestKind::Attribution => {
                let config = vector.app_config.as_ref().expect("app config");
                let headers = attribution_headers(config);
                assert_eq!(&headers, expected_headers(&vector), "{}", vector.name);
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

#[test]
fn basic_vector_serializes_system_first_and_supported_max_tokens() {
    let vector = vector("openrouter-basic");
    let request = to_request(vector.request.as_ref().expect("request"));
    let body = build_body(&request, &vector.supported_parameters, &BTreeMap::new())
        .expect("basic body builds");

    assert_eq!(body, expected_body(&vector));
    assert_eq!(body["messages"][0]["role"], "system");
    assert_eq!(body["messages"][1]["role"], "user");
    assert_eq!(body["max_tokens"], 256);
    assert!(body.get("temperature").is_none());
    assert!(body.get("top_p").is_none());
    assert!(body.get("stop").is_none());
    assert!(body.get("reasoning").is_none());
}

#[test]
fn routing_vector_serializes_curated_provider_controls() {
    let vector = vector("openrouter-routing");
    let request = to_request(vector.request.as_ref().expect("request"));
    let body = build_body(&request, &vector.supported_parameters, &BTreeMap::new())
        .expect("routing body builds");

    assert_eq!(body, expected_body(&vector));
    assert_eq!(body["provider"]["allow_fallbacks"], true);
    assert_eq!(body["provider"]["sort"], "price");
    assert_eq!(body["provider"]["data_collection"], "deny");
}

#[test]
fn advanced_protected_vector_rejects_sorted_fields() {
    let vector = vector("openrouter-advanced-protected");
    let request = to_request(vector.request.as_ref().expect("request"));
    let error = build_body(&request, &vector.supported_parameters, &BTreeMap::new())
        .expect_err("protected fields must be rejected");

    assert_error_matches(&error, expected_error(&vector));
    assert_eq!(error.code, ChatErrorCode::InvalidRequest);
    assert_eq!(
        error.details,
        Some(json!({
            "protectedFields": ["messages", "model", "stream", "stream_options"]
        }))
    );
}

#[test]
fn attribution_vector_uses_app_config() {
    let vector = vector("openrouter-attribution");
    let config = vector.app_config.as_ref().expect("app config");
    let headers = attribution_headers(config);

    assert_eq!(&headers, expected_headers(&vector));
    assert_eq!(
        headers.get("HTTP-Referer").map(String::as_str),
        Some("https://chatui.example")
    );
    assert_eq!(headers.get("X-Title").map(String::as_str), Some("ChatUI"));
}

#[test]
fn unsupported_parameter_vector_omits_reasoning() {
    let vector = vector("openrouter-unsupported-parameter");
    let request = to_request(vector.request.as_ref().expect("request"));
    let body = build_body(&request, &vector.supported_parameters, &BTreeMap::new())
        .expect("unsupported-parameter body builds");

    assert_eq!(body, expected_body(&vector));
    assert!(body.get("reasoning").is_none());
}

#[test]
fn default_model_vector_matches_selection_rule() {
    let vector = vector("openrouter-default-model");
    assert_eq!(vector.kind, RequestKind::DefaultModel);

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
        let selection = select_default_model(&case.preset_default_model_id, &candidates);
        assert_eq!(
            selection.selected_model_id, case.expected.selected_model_id,
            "{}",
            case.name
        );
        assert_eq!(selection.notice, case.expected.notice, "{}", case.name);
    }
}

#[test]
fn hidden_preset_default_still_wins() {
    let catalog = vec![
        DefaultModelCandidate {
            remote_id: "openrouter/auto".to_owned(),
            hidden: true,
            output_modalities: vec!["text".to_owned()],
        },
        DefaultModelCandidate {
            remote_id: "aaa/text".to_owned(),
            hidden: false,
            output_modalities: vec!["text".to_owned()],
        },
    ];

    let selection = select_default_model("openrouter/auto", &catalog);
    assert_eq!(
        selection.selected_model_id.as_deref(),
        Some("openrouter/auto")
    );
    assert_eq!(selection.notice, None);
}

#[test]
fn text_and_file_parts_concatenate_into_content() {
    let request = simple_request(
        vec![
            text_part("Hello"),
            file_part("file-1", "text/plain", "notes.txt"),
            text_part(" world"),
        ],
        ChatParams::default(),
    );
    let mut attachments = BTreeMap::new();
    attachments.insert(
        "file-1".to_owned(),
        attachment("text/plain", "notes.txt", "aGVsbG8="),
    );

    let body = build_body(&request, &["stream".to_owned()], &attachments).expect("body builds");
    assert_eq!(
        body["messages"][0]["content"],
        "Hello\n\n[File: notes.txt]\nhello world"
    );
}

#[test]
fn image_parts_use_openai_content_parts_and_data_urls() {
    let request = simple_request(
        vec![
            text_part("Describe this"),
            image_part("image-1", "image/png"),
        ],
        ChatParams::default(),
    );
    let mut attachments = BTreeMap::new();
    attachments.insert(
        "image-1".to_owned(),
        attachment("image/png", "pixel.png", "iVBORw0KGgo="),
    );

    let body = build_body(&request, &["stream".to_owned()], &attachments).expect("body builds");
    assert_eq!(
        body["messages"][0]["content"],
        json!([
            { "type": "text", "text": "Describe this" },
            {
                "type": "image_url",
                "image_url": { "url": "data:image/png;base64,iVBORw0KGgo=" }
            }
        ])
    );
}

#[test]
fn missing_attachment_is_invalid_request() {
    let request = simple_request(
        vec![image_part("missing", "image/png")],
        ChatParams::default(),
    );

    let error = build_body(&request, &["stream".to_owned()], &BTreeMap::new())
        .expect_err("missing attachment must fail");
    assert_eq!(error.code, ChatErrorCode::InvalidRequest);
    assert!(error.message.contains("missing"));
}

#[test]
fn invalid_file_base64_is_invalid_request() {
    let request = simple_request(
        vec![file_part("file-1", "text/plain", "notes.txt")],
        ChatParams::default(),
    );
    let mut attachments = BTreeMap::new();
    attachments.insert(
        "file-1".to_owned(),
        attachment("text/plain", "notes.txt", "not base64!"),
    );

    let error = build_body(&request, &["stream".to_owned()], &attachments)
        .expect_err("invalid base64 must fail");
    assert_eq!(error.code, ChatErrorCode::InvalidRequest);
}

#[test]
fn non_streaming_omits_stream_options() {
    let request = simple_request(
        vec![text_part("Hello")],
        ChatParams {
            streaming: false,
            ..ChatParams::default()
        },
    );

    let body = build_body(&request, &[], &BTreeMap::new()).expect("body builds");
    assert_eq!(body["stream"], false);
    assert!(body.get("stream_options").is_none());
}

#[test]
fn reasoning_effort_is_emitted_only_when_supported() {
    let request = simple_request(
        vec![text_part("Think")],
        ChatParams {
            reasoning_effort: Some(ReasoningEffort::Medium),
            ..ChatParams::default()
        },
    );

    let supported = vec!["stream".to_owned(), "reasoning".to_owned()];
    let body = build_body(&request, &supported, &BTreeMap::new()).expect("body builds");
    assert_eq!(body["reasoning"], json!({ "effort": "medium" }));

    let unsupported = vec!["stream".to_owned()];
    let body = build_body(&request, &unsupported, &BTreeMap::new()).expect("body builds");
    assert!(body.get("reasoning").is_none());
}

#[test]
fn advanced_routing_keys_pass_through() {
    let mut extra = serde_json::Map::new();
    extra.insert(
        "openrouter".to_owned(),
        json!({
            "provider": {
                "order": ["a", "b"],
                "only": ["a"],
                "ignore": ["c"],
                "allow_fallbacks": false,
                "sort": "throughput",
                "data_collection": "allow"
            },
            "models": ["a", "b"],
            "route": "fallback"
        }),
    );
    let request = simple_request(
        vec![text_part("Hello")],
        ChatParams {
            extra,
            ..ChatParams::default()
        },
    );

    let body = build_body(&request, &["stream".to_owned()], &BTreeMap::new()).expect("body builds");
    assert_eq!(body["provider"]["order"], json!(["a", "b"]));
    assert_eq!(body["provider"]["only"], json!(["a"]));
    assert_eq!(body["provider"]["ignore"], json!(["c"]));
    assert_eq!(body["provider"]["allow_fallbacks"], false);
    assert_eq!(body["provider"]["sort"], "throughput");
    assert_eq!(body["provider"]["data_collection"], "allow");
    assert_eq!(body["models"], json!(["a", "b"]));
    assert_eq!(body["route"], "fallback");
}

#[test]
fn unknown_advanced_keys_are_rejected() {
    let mut extra = serde_json::Map::new();
    extra.insert(
        "openrouter".to_owned(),
        json!({ "transforms": ["middle-out"] }),
    );
    let request = simple_request(
        vec![text_part("Hello")],
        ChatParams {
            extra,
            ..ChatParams::default()
        },
    );

    let error = build_body(&request, &["stream".to_owned()], &BTreeMap::new())
        .expect_err("unknown advanced key must fail");
    assert_eq!(error.code, ChatErrorCode::InvalidRequest);
}

#[test]
fn invalid_routing_values_are_rejected() {
    for provider in [
        json!({ "sort": "random" }),
        json!({ "data_collection": "maybe" }),
        json!({ "allow_fallbacks": "yes" }),
        json!({ "order": "a" }),
    ] {
        let mut extra = serde_json::Map::new();
        extra.insert("openrouter".to_owned(), json!({ "provider": provider }));
        let request = simple_request(
            vec![text_part("Hello")],
            ChatParams {
                extra,
                ..ChatParams::default()
            },
        );

        let error = build_body(&request, &["stream".to_owned()], &BTreeMap::new())
            .expect_err("invalid routing value must fail");
        assert_eq!(error.code, ChatErrorCode::InvalidRequest);
    }
}

#[test]
fn build_request_returns_body_and_attribution_headers() {
    let vector = vector("openrouter-basic");
    let request = to_request(vector.request.as_ref().expect("request"));
    let prepared = build_request(
        &request,
        &vector.supported_parameters,
        &BTreeMap::new(),
        &app_config(),
    )
    .expect("prepared request");

    assert_eq!(prepared.body, expected_body(&vector));
    assert_eq!(
        prepared.headers.get("HTTP-Referer").map(String::as_str),
        Some("https://chatui.example")
    );
    assert_eq!(
        prepared.headers.get("X-Title").map(String::as_str),
        Some("ChatUI")
    );
}

#[test]
fn no_key_or_personal_data_in_headers_or_body() {
    let vector = vector("openrouter-basic");
    let request = to_request(vector.request.as_ref().expect("request"));
    let prepared = build_request(
        &request,
        &vector.supported_parameters,
        &BTreeMap::new(),
        &app_config(),
    )
    .expect("prepared request");

    let serialized = serde_json::to_string(&prepared.body).expect("body serializes");
    assert!(!serialized.contains("sk-"));
    assert!(!serialized.contains("Bearer"));
    assert!(!serialized.contains('@'));

    for (name, value) in &prepared.headers {
        assert!(!name.eq_ignore_ascii_case("authorization"));
        assert!(!value.contains("sk-"));
        assert!(!value.contains("Bearer"));
        assert!(!value.contains('@'));
    }
}
