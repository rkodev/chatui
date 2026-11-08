//! Typed loader and expected-vector shape checks.

use std::collections::HashSet;

use chatui_conformance::{
    ConformanceFixtures, ErrorFixtureBody, ErrorFixtureKind, ModelFixtureKind, RequestExpectation,
    RequestKind,
};
use chatui_domain::ChatErrorCode;

fn fixtures() -> ConformanceFixtures {
    ConformanceFixtures::repository()
}

#[test]
fn stream_expectations_have_consistent_text_and_reasoning() {
    let expectations = fixtures()
        .stream_expectations()
        .expect("stream expectations load");

    for expectation in expectations {
        assert!(
            !expectation.events.is_empty(),
            "{} has no events",
            expectation.file
        );
        assert_eq!(
            expectation.concatenated_text(),
            expectation.text,
            "{} text does not match its text-delta events",
            expectation.file
        );
        assert_eq!(
            expectation.concatenated_reasoning(),
            expectation.reasoning,
            "{} reasoning does not match its reasoning-delta events",
            expectation.file
        );

        if !expectation.text.is_empty() || !expectation.reasoning.is_empty() {
            assert!(
                !expectation.retry_allowed,
                "{} allows a retry after emitting content",
                expectation.file
            );
        }
    }
}

#[test]
fn request_vectors_have_the_expected_shape_for_their_kind() {
    for vector in fixtures().request_vectors().expect("requests load") {
        match vector.kind {
            RequestKind::ChatRequest => {
                assert!(
                    vector.request.is_some(),
                    "{} is missing its normalized request",
                    vector.file
                );
                assert!(
                    matches!(vector.expected, Some(RequestExpectation::Body { .. })),
                    "{} is missing its expected body",
                    vector.file
                );
            }
            RequestKind::ProtectedFields => {
                assert!(
                    vector.request.is_some(),
                    "{} is missing its normalized request",
                    vector.file
                );
                match vector.expected {
                    Some(RequestExpectation::Error { error }) => {
                        assert_eq!(
                            error.code,
                            ChatErrorCode::InvalidRequest,
                            "{} must expect invalid_request",
                            vector.file
                        );
                    }
                    other => panic!("{} has an unexpected expectation: {other:?}", vector.file),
                }
            }
            RequestKind::Attribution => {
                assert!(
                    vector.app_config.is_some(),
                    "{} is missing appConfig",
                    vector.file
                );
                assert!(
                    matches!(vector.expected, Some(RequestExpectation::Headers { .. })),
                    "{} is missing expected headers",
                    vector.file
                );
            }
            RequestKind::DefaultModel => {
                assert!(
                    vector.expected.is_none(),
                    "{} must not carry a top-level expected value",
                    vector.file
                );
                assert!(
                    !vector.cases.is_empty(),
                    "{} has no default-model cases",
                    vector.file
                );
                for case in &vector.cases {
                    assert!(
                        case.expected.selected_model_id.is_some() || case.expected.notice.is_some(),
                        "{} case {} has neither a selection nor a notice",
                        vector.file,
                        case.name
                    );
                }
            }
        }
    }
}

#[test]
fn model_fixtures_are_classified_and_have_entries() {
    for fixture in fixtures().model_fixtures().expect("models load") {
        assert_ne!(
            fixture.kind,
            ModelFixtureKind::Other,
            "{} is not classified",
            fixture.name
        );

        if fixture.kind == ModelFixtureKind::OpenrouterKey {
            assert!(
                fixture.entries().is_none(),
                "{} is a key response, not a catalog",
                fixture.name
            );
        } else {
            let entries = fixture
                .entries()
                .unwrap_or_else(|| panic!("{} has no catalog entries", fixture.name));
            assert!(!entries.is_empty(), "{} has an empty catalog", fixture.name);
        }
    }
}

#[test]
fn error_fixtures_are_classified_and_parse() {
    for fixture in fixtures().error_fixtures().expect("errors load") {
        assert_ne!(
            fixture.kind,
            ErrorFixtureKind::Other,
            "{} is not classified",
            fixture.name
        );

        match (&fixture.kind, &fixture.body) {
            (ErrorFixtureKind::HttpJson, ErrorFixtureBody::Json(value)) => {
                assert!(
                    value.is_object(),
                    "{} is not a JSON error envelope",
                    fixture.name
                );
            }
            (
                ErrorFixtureKind::StreamSse | ErrorFixtureKind::StreamNdjson,
                ErrorFixtureBody::Stream(text),
            ) => {
                assert!(!text.is_empty(), "{} is empty", fixture.name);
            }
            (kind, body) => panic!(
                "{} has mismatched kind {kind:?} and body {body:?}",
                fixture.name
            ),
        }
    }
}

#[test]
fn expected_error_vectors_cover_the_required_codes() {
    let fixtures = fixtures();
    let mut codes = HashSet::new();

    for vectors in [
        fixtures
            .openrouter_errors()
            .expect("openrouter errors load"),
        fixtures.provider_errors().expect("provider errors load"),
    ] {
        for case in vectors.cases {
            codes.insert(case.error.code);
        }
    }

    for code in [
        ChatErrorCode::Auth,
        ChatErrorCode::Permission,
        ChatErrorCode::RateLimit,
        ChatErrorCode::Quota,
        ChatErrorCode::ModelNotFound,
        ChatErrorCode::ContextLength,
        ChatErrorCode::ContentFilter,
        ChatErrorCode::Network,
        ChatErrorCode::Timeout,
        ChatErrorCode::Provider,
    ] {
        assert!(
            codes.contains(&code),
            "missing expected error code {code:?}"
        );
    }
}

#[test]
fn catalog_vectors_load_and_reference_real_model_fixtures() {
    let fixtures = fixtures();
    let catalogs = fixtures.catalogs().expect("catalogs load");

    assert_eq!(catalogs.cases.len(), 4);
    for case in catalogs.cases {
        assert!(
            fixtures.root().path(&case.fixture).is_file(),
            "{} references missing model fixture {}",
            case.name,
            case.fixture
        );
        assert!(
            !case.expected.is_empty(),
            "{} has no expected entries",
            case.name
        );
    }
}

#[test]
fn capability_inference_vectors_load() {
    let vectors = fixtures()
        .capability_inference()
        .expect("capability inference loads");

    assert_eq!(vectors.cases.len(), 5);
    for case in vectors.cases {
        assert!(!case.name.is_empty());
        assert!(!case.expected.capabilities.is_empty());
    }
}

#[test]
fn fallback_model_vectors_load() {
    let vectors = fixtures().fallback_models().expect("fallback models load");

    assert_eq!(vectors.presets.len(), 4);
    assert_eq!(vectors.cases.len(), 5);
    for case in vectors.cases {
        assert!(
            case.expected.selected_model_id.is_some() || case.expected.notice.is_some(),
            "{} has neither a selection nor a notice",
            case.name
        );
    }
}

#[test]
fn math_vectors_load() {
    let vectors = fixtures().math().expect("math vectors load");

    assert_eq!(vectors.cases.len(), 5);
    for case in vectors.cases {
        assert!(
            !case.expected.segments.is_empty(),
            "{} has no segments",
            case.name
        );
    }
}

#[test]
fn gemini_finish_reason_vectors_load() {
    let vectors = fixtures()
        .gemini_finish_reasons()
        .expect("gemini finish reasons load");

    assert_eq!(vectors.cases.len(), 4);
}

#[test]
fn anthropic_thinking_vectors_load() {
    let vectors = fixtures()
        .anthropic_thinking()
        .expect("anthropic thinking loads");

    assert_eq!(vectors.cases.len(), 1);
    for case in vectors.cases {
        assert!(
            !case.raw_events.is_empty(),
            "{} has no raw events",
            case.name
        );
        assert!(
            !case.events.is_empty(),
            "{} has no expected events",
            case.name
        );
    }
}

#[test]
fn key_expectation_loads() {
    let expectation = fixtures().key_expectation().expect("key expectation loads");

    assert_eq!(expectation.provider_id, "openrouter");
    assert_eq!(expectation.key.label, "Example OpenRouter Key");
    assert!(expectation.key.rate_limit.is_some());
}
