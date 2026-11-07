//! Inventory and cross-family conformance checks.

use std::collections::BTreeSet;

use chatui_conformance::{
    ConformanceFixtures, EXPECTED_ERROR_FIXTURE_COUNT, EXPECTED_MODEL_FIXTURE_COUNT,
    EXPECTED_PRESET_COUNT, EXPECTED_REQUEST_VECTOR_COUNT, EXPECTED_SCHEMA_COUNT,
    EXPECTED_STREAM_FIXTURE_COUNT, EXPECTED_VECTOR_COUNT, FixtureInventory, KNOWN_PROVIDER_IDS,
    RequestKind,
};
use chatui_contracts::{EXPORT_FORMAT, EXPORT_FORMAT_VERSION, provider_catalog};

fn fixtures() -> ConformanceFixtures {
    ConformanceFixtures::repository()
}

#[test]
fn fixture_inventory_matches_shared_counts() {
    let inventory = fixtures().inventory().expect("fixture inventory loads");

    assert_eq!(
        inventory,
        FixtureInventory {
            presets: EXPECTED_PRESET_COUNT,
            schemas: EXPECTED_SCHEMA_COUNT,
            streams: EXPECTED_STREAM_FIXTURE_COUNT,
            models: EXPECTED_MODEL_FIXTURE_COUNT,
            errors: EXPECTED_ERROR_FIXTURE_COUNT,
            requests: EXPECTED_REQUEST_VECTOR_COUNT,
            expected: EXPECTED_VECTOR_COUNT,
        }
    );
}

#[test]
fn fixture_file_names_match_the_shared_inventory() {
    let fixtures = fixtures();

    assert_eq!(
        fixtures
            .stream_fixtures()
            .expect("streams load")
            .iter()
            .map(|fixture| fixture.name.as_str())
            .collect::<Vec<_>>(),
        vec![
            "anthropic-text.sse",
            "error-429.sse",
            "gemini-text.sse",
            "ollama-ndjson.txt",
            "openai-reasoning.sse",
            "openai-text.sse",
            "openrouter-error-200.sse",
            "openrouter-keepalive.sse",
            "openrouter-reasoning.sse",
            "openrouter-text.sse",
            "openrouter-usage.sse",
        ]
    );

    assert_eq!(
        fixtures
            .model_fixtures()
            .expect("models load")
            .iter()
            .map(|fixture| fixture.name.as_str())
            .collect::<Vec<_>>(),
        vec![
            "anthropic-models.json",
            "gemini-models.json",
            "ollama-tags.json",
            "openai-models.json",
            "openrouter-key.json",
            "openrouter-models.json",
        ]
    );

    assert_eq!(
        fixtures
            .error_fixtures()
            .expect("errors load")
            .iter()
            .map(|fixture| fixture.name.as_str())
            .collect::<Vec<_>>(),
        vec![
            "anthropic-400-context.json",
            "anthropic-401.json",
            "anthropic-403.json",
            "anthropic-404.json",
            "anthropic-429.json",
            "anthropic-529.json",
            "anthropic-stream-error.sse",
            "gemini-400-api-key.json",
            "gemini-400-context.json",
            "gemini-403-permission.json",
            "gemini-404.json",
            "gemini-429.json",
            "gemini-500.json",
            "gemini-stream-error.sse",
            "ollama-404.json",
            "ollama-500.json",
            "ollama-stream-error.txt",
            "openai-400-content-filter.json",
            "openai-400-context.json",
            "openai-401.json",
            "openai-403.json",
            "openai-404.json",
            "openai-429.json",
            "openai-500.json",
            "openai-stream-error.sse",
            "openrouter-402.json",
            "openrouter-429.json",
            "openrouter-503.json",
        ]
    );

    assert_eq!(
        fixtures
            .request_vectors()
            .expect("requests load")
            .iter()
            .map(|vector| vector.file.as_str())
            .collect::<Vec<_>>(),
        vec![
            "anthropic-attachments.json",
            "anthropic-basic.json",
            "anthropic-extras.json",
            "anthropic-protected.json",
            "gemini-attachments.json",
            "gemini-basic.json",
            "gemini-extras.json",
            "gemini-protected.json",
            "ollama-attachments.json",
            "ollama-basic.json",
            "ollama-extras.json",
            "ollama-protected.json",
            "openai-attachments.json",
            "openai-basic.json",
            "openai-extras.json",
            "openai-protected.json",
            "openrouter-advanced-protected.json",
            "openrouter-attribution.json",
            "openrouter-basic.json",
            "openrouter-default-model.json",
            "openrouter-routing.json",
            "openrouter-unsupported-parameter.json",
        ]
    );

    assert_eq!(
        fixtures.expected_files().expect("expected files load"),
        vec![
            "anthropic-text.json",
            "anthropic-thinking.json",
            "capability-inference.json",
            "catalogs.json",
            "chatui-export.json",
            "error-429.json",
            "fallback-models.json",
            "gemini-finish-reasons.json",
            "gemini-text.json",
            "math.json",
            "ollama-ndjson.json",
            "openai-reasoning.json",
            "openai-text.json",
            "openrouter-error-200.json",
            "openrouter-errors.json",
            "openrouter-keepalive.json",
            "openrouter-key.json",
            "openrouter-reasoning.json",
            "openrouter-text.json",
            "openrouter-usage.json",
            "provider-errors.json",
        ]
    );
}

#[test]
fn every_expected_stream_vector_resolves_to_a_raw_fixture() {
    let fixtures = fixtures();
    let raw_streams: BTreeSet<String> = fixtures
        .stream_fixtures()
        .expect("streams load")
        .into_iter()
        .map(|fixture| fixture.name)
        .collect();

    let expectations = fixtures
        .stream_expectations()
        .expect("stream expectations load");
    assert_eq!(expectations.len(), EXPECTED_STREAM_FIXTURE_COUNT);

    for expectation in expectations {
        assert!(
            raw_streams.contains(&expectation.source),
            "{} references missing raw stream {}",
            expectation.file,
            expectation.source
        );
    }
}

#[test]
fn every_request_vector_has_a_known_kind_and_provider() {
    let catalog = provider_catalog();

    for vector in fixtures().request_vectors().expect("requests load") {
        assert!(
            RequestKind::ALL.contains(&vector.kind),
            "{} has an unknown kind",
            vector.file
        );
        assert!(
            KNOWN_PROVIDER_IDS.contains(&vector.provider_id.as_str()),
            "{} has an unknown provider ID {}",
            vector.file,
            vector.provider_id
        );
        assert!(
            catalog.get(&vector.provider_id).is_some(),
            "{} references provider {} missing from contracts/providers.json",
            vector.file,
            vector.provider_id
        );
        assert_eq!(
            vector.name,
            vector.file.trim_end_matches(".json"),
            "{} name does not match its file stem",
            vector.file
        );
    }
}

#[test]
fn chatui_export_deserializes_through_contracts() {
    let export = fixtures().export().expect("export vector loads");

    assert_eq!(export.format, EXPORT_FORMAT);
    assert_eq!(export.format_version, EXPORT_FORMAT_VERSION);
    export
        .validate()
        .expect("export vector satisfies the schema");
}
