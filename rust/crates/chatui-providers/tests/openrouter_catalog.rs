//! OpenRouter key, catalog, and cache tests against the shared fixtures.

use chatui_conformance::ConformanceFixtures;
use chatui_domain::{
    InputModality, ModelCapability, ModelPricing, ModelSource, OutputModality, Timestamp,
};
use chatui_providers::http::HttpClient;
use chatui_providers::openrouter::cache::{
    CATALOG_DIR_NAME, CacheError, CatalogCache, CatalogSnapshot, FileCatalogCache,
    MemoryCatalogCache, OPENROUTER_CATALOG_TTL_SECONDS, is_fresh, load_catalog, refresh_catalog,
};
use chatui_providers::openrouter::catalog::{
    CatalogModel, dedupe_by_canonical_slug, list_models, map_model, map_models,
    multiply_decimal_by_million, usd_amount, usd_per_million,
};
use chatui_providers::openrouter::key::{OpenRouterError, ProviderKeyInfo, map_key, validate_key};
use serde_json::{Value, json};
use wiremock::matchers::{header, method, path};
use wiremock::{Mock, MockServer, ResponseTemplate};

fn fixtures() -> ConformanceFixtures {
    ConformanceFixtures::repository()
}

fn models_fixture() -> Value {
    fixtures()
        .root()
        .read_json("models/openrouter-models.json")
        .expect("models fixture loads")
}

fn key_fixture() -> Value {
    fixtures()
        .root()
        .read_json("models/openrouter-key.json")
        .expect("key fixture loads")
}

fn key_expected() -> Value {
    fixtures()
        .root()
        .read_json("expected/openrouter-key.json")
        .expect("key expectation loads")
}

fn find<'a>(models: &'a [CatalogModel], remote_id: &str) -> &'a CatalogModel {
    models
        .iter()
        .find(|model| model.remote_id == remote_id)
        .unwrap_or_else(|| panic!("missing fixture model {remote_id}"))
}

fn fixed(value: &str) -> Timestamp {
    Timestamp::parse(value).expect("valid timestamp")
}

fn model(remote_id: &str) -> CatalogModel {
    map_model(&json!({ "id": remote_id })).expect("model maps")
}

#[test]
fn maps_every_fixture_entry() {
    let models = map_models(&models_fixture()).expect("catalog maps");
    assert_eq!(models.len(), 8);
}

#[test]
fn maps_a_paid_text_and_tool_model() {
    let models = map_models(&models_fixture()).expect("catalog maps");
    let model = find(&models, "openai/gpt-4o-mini-2024-07-18");

    assert_eq!(model.canonical_slug.as_deref(), Some("openai/gpt-4o-mini"));
    assert_eq!(model.label, "OpenAI: GPT-4o mini (2024-07-18)");
    assert_eq!(model.context_window, Some(128_000));
    assert_eq!(model.max_output_tokens, Some(16_384));
    assert_eq!(model.input_modalities, vec![InputModality::Text]);
    assert_eq!(model.output_modalities, vec![OutputModality::Text]);
    assert_eq!(
        model.capabilities,
        vec![
            ModelCapability::Text,
            ModelCapability::Tools,
            ModelCapability::Streaming
        ]
    );
    assert_eq!(
        model.pricing,
        Some(ModelPricing {
            input_per_m_tok: Some(0.15),
            output_per_m_tok: Some(0.6),
            per_request: Some(0.0),
            currency: Some("USD".to_owned()),
        })
    );
    assert!(!model.is_free);

    let metadata = model.metadata.as_ref().expect("metadata");
    assert_eq!(metadata["architecture"]["modality"], "text->text");
    assert_eq!(metadata["architecture"]["tokenizer"], "GPT");
    assert_eq!(metadata["pricing"]["prompt"], "0.00000015");
    assert_eq!(metadata["topProvider"]["isModerated"], false);
}

#[test]
fn marks_a_zero_priced_free_model_and_preserves_the_suffix() {
    let models = map_models(&models_fixture()).expect("catalog maps");
    let model = find(&models, "meta-llama/llama-3.2-3b-instruct:free");

    assert!(model.is_free);
    assert_eq!(model.remote_id, "meta-llama/llama-3.2-3b-instruct:free");
    assert_eq!(
        model.pricing,
        Some(ModelPricing {
            input_per_m_tok: Some(0.0),
            output_per_m_tok: Some(0.0),
            per_request: Some(0.0),
            currency: Some("USD".to_owned()),
        })
    );
    assert_eq!(
        model.capabilities,
        vec![ModelCapability::Text, ModelCapability::Streaming]
    );
    assert_eq!(
        model.metadata.as_ref().expect("metadata")["perRequestLimits"]["requests_per_minute"],
        20
    );
}

#[test]
fn infers_vision_and_file_capabilities_from_modalities() {
    let models = map_models(&models_fixture()).expect("catalog maps");
    let model = find(&models, "google/gemini-2.5-flash");

    assert_eq!(
        model.input_modalities,
        vec![
            InputModality::Text,
            InputModality::Image,
            InputModality::File
        ]
    );
    assert_eq!(model.output_modalities, vec![OutputModality::Text]);
    assert_eq!(
        model.capabilities,
        vec![
            ModelCapability::Text,
            ModelCapability::Vision,
            ModelCapability::Files,
            ModelCapability::Tools,
            ModelCapability::Reasoning,
            ModelCapability::Streaming
        ]
    );
    assert_eq!(
        model
            .pricing
            .as_ref()
            .and_then(|pricing| pricing.input_per_m_tok),
        Some(0.3)
    );
    assert_eq!(
        model
            .pricing
            .as_ref()
            .and_then(|pricing| pricing.output_per_m_tok),
        Some(2.5)
    );

    let metadata = model.metadata.as_ref().expect("metadata");
    assert_eq!(metadata["defaultParameters"]["temperature"], 0.7);
    assert_eq!(metadata["topProvider"]["isModerated"], true);
    assert!(metadata.get("default_parameters").is_none());
}

#[test]
fn infers_reasoning_and_keeps_raw_reasoning_pricing() {
    let models = map_models(&models_fixture()).expect("catalog maps");
    let model = find(&models, "deepseek/deepseek-r1");

    assert_eq!(
        model.capabilities,
        vec![
            ModelCapability::Text,
            ModelCapability::Reasoning,
            ModelCapability::Streaming
        ]
    );
    assert_eq!(
        model.metadata.as_ref().expect("metadata")["pricing"]["internal_reasoning"],
        "0.00000219"
    );
}

#[test]
fn maps_a_tool_capable_model() {
    let models = map_models(&models_fixture()).expect("catalog maps");
    let model = find(&models, "anthropic/claude-sonnet-4.5");

    assert_eq!(
        model.capabilities,
        vec![
            ModelCapability::Text,
            ModelCapability::Vision,
            ModelCapability::Files,
            ModelCapability::Tools,
            ModelCapability::Reasoning,
            ModelCapability::Streaming
        ]
    );
}

#[test]
fn maps_a_long_context_model() {
    let models = map_models(&models_fixture()).expect("catalog maps");
    let model = find(&models, "google/gemini-2.5-pro");

    assert_eq!(model.context_window, Some(1_048_576));
    assert_eq!(model.max_output_tokens, Some(65_536));
}

#[test]
fn converts_unusual_decimal_pricing_without_binary_float_artifacts() {
    let models = map_models(&models_fixture()).expect("catalog maps");
    let model = find(&models, "example/unusual-pricing");

    assert_eq!(
        model.pricing,
        Some(ModelPricing {
            input_per_m_tok: Some(0.000_001),
            output_per_m_tok: Some(0.000_002),
            per_request: Some(0.0005),
            currency: Some("USD".to_owned()),
        })
    );
    assert_eq!(
        model.metadata.as_ref().expect("metadata")["pricing"]["prompt"],
        "0.000000000001"
    );
    assert!(!model.is_free);
}

#[test]
fn ignores_unknown_fields_but_keeps_small_non_secret_metadata() {
    let models = map_models(&models_fixture()).expect("catalog maps");
    let model = find(&models, "example/unknown-fields");
    let metadata = model.metadata.as_ref().expect("metadata");

    assert_eq!(metadata["x_provider_note"], "ignore this unknown field");
    assert_eq!(metadata["future_capability_flags"]["synthetic"], true);
}

#[test]
fn skips_malformed_entries_instead_of_failing_the_catalog() {
    let mapped = map_models(&json!({
        "data": [
            null,
            {},
            { "id": "" },
            { "id": "example/minimal", "name": "" },
            {
                "id": "example/fallback",
                "context_length": 0,
                "top_provider": { "context_length": 100, "max_completion_tokens": 0 }
            }
        ]
    }))
    .expect("catalog maps");

    assert_eq!(mapped.len(), 2);
    assert_eq!(mapped[0].remote_id, "example/minimal");
    assert_eq!(mapped[0].label, "example/minimal");
    assert_eq!(mapped[0].context_window, None);
    assert_eq!(mapped[0].max_output_tokens, None);
    assert!(mapped[0].capabilities.is_empty());
    assert_eq!(mapped[1].context_window, Some(100));
    assert_eq!(mapped[1].max_output_tokens, Some(100));
}

#[test]
fn accepts_bare_arrays_and_models_envelopes() {
    assert_eq!(
        map_models(&json!([{ "id": "example/array" }]))
            .expect("array maps")
            .len(),
        1
    );
    assert_eq!(
        map_models(&json!({ "models": [{ "id": "example/models" }] }))
            .expect("models envelope maps")
            .len(),
        1
    );
}

#[test]
fn rejects_a_malformed_envelope() {
    assert!(matches!(
        map_models(&json!({})),
        Err(OpenRouterError::Parse(_))
    ));
    assert!(matches!(
        map_models(&json!({ "data": "nope" })),
        Err(OpenRouterError::Parse(_))
    ));
}

#[test]
fn infers_modalities_from_the_architecture_modality_string() {
    let model = map_model(&json!({
        "id": "example/modality",
        "architecture": { "modality": "text+image->text" }
    }))
    .expect("model maps");

    assert_eq!(
        model.input_modalities,
        vec![InputModality::Text, InputModality::Image]
    );
    assert_eq!(model.output_modalities, vec![OutputModality::Text]);
    assert_eq!(
        model.capabilities,
        vec![ModelCapability::Text, ModelCapability::Vision]
    );
}

#[test]
fn deduplicates_supported_parameters() {
    let model = map_model(&json!({
        "id": "example/params",
        "supported_parameters": ["stream", "stream", "tools", "tools"]
    }))
    .expect("model maps");

    assert_eq!(model.supported_parameters, vec!["stream", "tools"]);
    assert_eq!(
        model.capabilities,
        vec![ModelCapability::Tools, ModelCapability::Streaming]
    );
}

#[test]
fn does_not_mark_a_model_free_when_a_non_token_charge_is_non_zero() {
    let model = map_model(&json!({
        "id": "example/paid-image",
        "pricing": {
            "prompt": "0",
            "completion": "0",
            "request": "0",
            "image": "0.0001"
        }
    }))
    .expect("model maps");

    assert!(!model.is_free);
}

#[test]
fn marks_a_free_suffix_model_free_even_when_pricing_is_non_zero() {
    let model = map_model(&json!({
        "id": "example/suffix:free",
        "pricing": { "prompt": "0.1", "completion": "0.2" }
    }))
    .expect("model maps");

    assert!(model.is_free);
}

#[test]
fn does_not_copy_secret_like_unknown_fields_into_metadata() {
    let model = map_model(&json!({
        "id": "example/secret-metadata",
        "api_key": "must-not-be-copied",
        "x_provider_note": "safe"
    }))
    .expect("model maps");
    let metadata = model.metadata.expect("metadata");

    assert_eq!(metadata["x_provider_note"], "safe");
    assert!(metadata.get("api_key").is_none());
}

#[test]
fn multiplies_decimal_strings_by_one_million_exactly() {
    assert_eq!(
        multiply_decimal_by_million("0.00000015").as_deref(),
        Some("0.15")
    );
    assert_eq!(
        multiply_decimal_by_million("0.0000006").as_deref(),
        Some("0.6")
    );
    assert_eq!(
        multiply_decimal_by_million("0.000000000001").as_deref(),
        Some("0.000001")
    );
    assert_eq!(
        multiply_decimal_by_million("0.1").as_deref(),
        Some("100000")
    );
    assert_eq!(
        multiply_decimal_by_million("1.5").as_deref(),
        Some("1500000")
    );
    assert_eq!(multiply_decimal_by_million("0").as_deref(), Some("0"));
    assert_eq!(
        multiply_decimal_by_million("-0.000001").as_deref(),
        Some("-1")
    );
}

#[test]
fn rejects_invalid_decimal_strings() {
    assert_eq!(multiply_decimal_by_million("abc"), None);
    assert_eq!(multiply_decimal_by_million("1e-6"), None);
    assert_eq!(multiply_decimal_by_million(""), None);
    assert_eq!(multiply_decimal_by_million("1."), None);
    assert_eq!(multiply_decimal_by_million(".5"), None);
}

#[test]
fn converts_usd_per_token_to_usd_per_million() {
    assert_eq!(usd_per_million(&json!("0.00000015")), Some(0.15));
    assert_eq!(usd_per_million(&json!("0.1")), Some(100_000.0));
    assert_eq!(usd_per_million(&json!(0.1)), None);
    assert_eq!(usd_per_million(&json!("abc")), None);
}

#[test]
fn parses_usd_amounts_without_multiplying() {
    assert_eq!(usd_amount(&json!("0.0005")), Some(0.0005));
    assert_eq!(usd_amount(&json!("0")), Some(0.0));
    assert_eq!(usd_amount(&json!("abc")), None);
    assert_eq!(usd_amount(&json!(1)), Some(1.0));
}

#[test]
fn converts_a_catalog_entry_to_the_shared_model_shape() {
    let models = map_models(&models_fixture()).expect("catalog maps");
    let catalog = find(&models, "openai/gpt-4o-mini-2024-07-18");
    let now = fixed("2026-11-07T12:00:00.000Z");
    let model = catalog.to_model("model-1", "openrouter", now, now);

    assert_eq!(model.id, "model-1");
    assert_eq!(model.provider_id, "openrouter");
    assert_eq!(model.remote_id, catalog.remote_id);
    assert_eq!(model.label, catalog.label);
    assert_eq!(model.pricing, catalog.pricing);
    assert_eq!(model.source, ModelSource::Discovered);
    assert!(!model.pinned);
}

#[test]
fn deduplicates_by_canonical_slug_for_display() {
    let models = vec![
        map_model(&json!({ "id": "a/one", "canonical_slug": "a/model" })).expect("model"),
        map_model(&json!({ "id": "a/two", "canonical_slug": "a/model" })).expect("model"),
        map_model(&json!({ "id": "b/one" })).expect("model"),
    ];

    let deduped = dedupe_by_canonical_slug(&models);
    assert_eq!(deduped.len(), 2);
    assert_eq!(deduped[0].remote_id, "a/one");
    assert_eq!(deduped[1].remote_id, "b/one");
}

#[test]
fn maps_the_shared_key_fixture() {
    let info = map_key(&key_fixture()).expect("key maps");
    let expected = key_expected();

    assert_eq!(info.label.as_deref(), expected["key"]["label"].as_str());
    assert_eq!(info.usage_usd, expected["key"]["usageUsd"].as_f64());
    assert_eq!(info.limit_usd, expected["key"]["limitUsd"].as_f64());
    assert_eq!(
        info.limit_remaining_usd,
        expected["key"]["limitRemainingUsd"].as_f64()
    );
    assert_eq!(info.is_free_tier, expected["key"]["isFreeTier"].as_bool());
    assert_eq!(
        info.rate_limit.as_ref(),
        Some(&expected["key"]["rateLimit"])
    );
}

#[test]
fn maps_null_and_numeric_string_key_fields() {
    let info = map_key(&json!({
        "data": {
            "label": null,
            "usage": null,
            "limit": null,
            "limit_remaining": null,
            "is_free_tier": null,
            "rate_limit": null
        }
    }))
    .expect("key maps");

    assert_eq!(
        info,
        ProviderKeyInfo {
            label: None,
            usage_usd: None,
            limit_usd: None,
            limit_remaining_usd: None,
            is_free_tier: None,
            rate_limit: None,
        }
    );

    let info = map_key(&json!({ "data": { "usage": "12.5", "limit": "100" } })).expect("key maps");
    assert_eq!(info.usage_usd, Some(12.5));
    assert_eq!(info.limit_usd, Some(100.0));
}

#[test]
fn rejects_a_malformed_key_envelope() {
    assert!(matches!(
        map_key(&json!({})),
        Err(OpenRouterError::Parse(_))
    ));
    assert!(matches!(
        map_key(&json!({ "data": null })),
        Err(OpenRouterError::Parse(_))
    ));
}

#[tokio::test]
async fn validate_key_sends_bearer_auth_and_maps_the_fixture() {
    let server = MockServer::start().await;
    Mock::given(method("GET"))
        .and(path("/api/v1/key"))
        .and(header("authorization", "Bearer sk-test-key"))
        .respond_with(ResponseTemplate::new(200).set_body_json(key_fixture()))
        .mount(&server)
        .await;

    let client = HttpClient::new().expect("client");
    let info = validate_key(&client, &format!("{}/api/v1", server.uri()), "sk-test-key")
        .await
        .expect("key validates");

    assert_eq!(info.label.as_deref(), Some("Example OpenRouter Key"));
    let requests = server.received_requests().await.expect("requests");
    assert_eq!(requests.len(), 1);
    assert!(!requests[0].url.as_str().contains("sk-test-key"));
}

#[tokio::test]
async fn validate_key_rejects_a_missing_key_without_a_request() {
    let server = MockServer::start().await;
    let client = HttpClient::new().expect("client");

    let error = validate_key(&client, &format!("{}/api/v1", server.uri()), "  ")
        .await
        .expect_err("missing key");

    assert!(matches!(error, OpenRouterError::MissingKey));
    assert!(
        server
            .received_requests()
            .await
            .expect("requests")
            .is_empty()
    );
}

#[tokio::test]
async fn validate_key_maps_http_status_errors() {
    let server = MockServer::start().await;
    Mock::given(method("GET"))
        .and(path("/api/v1/key"))
        .respond_with(ResponseTemplate::new(401))
        .mount(&server)
        .await;

    let client = HttpClient::new().expect("client");
    let error = validate_key(&client, &format!("{}/api/v1", server.uri()), "sk-test-key")
        .await
        .expect_err("401");

    match error {
        OpenRouterError::Network(error) => {
            assert_eq!(error.status, Some(401));
            assert!(!error.retryable);
        }
        other => panic!("unexpected error: {other:?}"),
    }
}

#[tokio::test]
async fn list_models_is_public_and_does_not_send_authorization() {
    let server = MockServer::start().await;
    Mock::given(method("GET"))
        .and(path("/api/v1/models"))
        .respond_with(ResponseTemplate::new(200).set_body_json(models_fixture()))
        .mount(&server)
        .await;

    let client = HttpClient::new().expect("client");
    let models = list_models(&client, &format!("{}/api/v1", server.uri()))
        .await
        .expect("catalog loads");

    assert_eq!(models.len(), 8);
    let requests = server.received_requests().await.expect("requests");
    assert_eq!(requests.len(), 1);
    assert!(requests[0].headers.get("authorization").is_none());
}

#[tokio::test]
async fn malformed_catalog_response_is_a_parse_error() {
    let server = MockServer::start().await;
    Mock::given(method("GET"))
        .and(path("/api/v1/models"))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!({ "nope": true })))
        .mount(&server)
        .await;

    let client = HttpClient::new().expect("client");
    let error = list_models(&client, &format!("{}/api/v1", server.uri()))
        .await
        .expect_err("parse error");

    assert!(matches!(error, OpenRouterError::Parse(_)));
}

#[tokio::test]
async fn invalid_json_is_a_parse_error() {
    let server = MockServer::start().await;
    Mock::given(method("GET"))
        .and(path("/api/v1/models"))
        .respond_with(ResponseTemplate::new(200).set_body_string("not json"))
        .mount(&server)
        .await;

    let client = HttpClient::new().expect("client");
    let error = list_models(&client, &format!("{}/api/v1", server.uri()))
        .await
        .expect_err("parse error");

    assert!(matches!(error, OpenRouterError::Parse(_)));
}

#[test]
fn memory_cache_round_trips_and_clones_snapshots() {
    let cache = MemoryCatalogCache::new();
    let now = fixed("2026-11-07T12:00:00.000Z");
    let snapshot = CatalogSnapshot {
        fetched_at: now,
        models: vec![model("example/model")],
    };

    cache.put("openrouter", &snapshot, now).expect("put");
    let mut first = cache.get("openrouter").expect("get").expect("snapshot");
    first.models.clear();
    let second = cache.get("openrouter").expect("get").expect("snapshot");

    assert_eq!(second.models.len(), 1);
}

#[test]
fn ttl_is_24_hours_and_the_boundary_is_stale() {
    assert_eq!(OPENROUTER_CATALOG_TTL_SECONDS, 86_400);

    let snapshot = CatalogSnapshot {
        fetched_at: fixed("2026-11-07T00:00:00.000Z"),
        models: Vec::new(),
    };

    assert!(is_fresh(
        &snapshot,
        fixed("2026-11-07T23:59:59.000Z"),
        OPENROUTER_CATALOG_TTL_SECONDS
    ));
    assert!(!is_fresh(
        &snapshot,
        fixed("2026-11-08T00:00:00.000Z"),
        OPENROUTER_CATALOG_TTL_SECONDS
    ));
}

#[test]
fn file_cache_round_trips_and_stores_no_secrets() {
    let directory = tempfile::tempdir().expect("tempdir");
    let cache = FileCatalogCache::new(directory.path());
    let now = fixed("2026-11-07T12:00:00.000Z");
    let snapshot = CatalogSnapshot {
        fetched_at: now,
        models: vec![model("example/model")],
    };

    cache.put("openrouter", &snapshot, now).expect("put");
    let loaded = cache.get("openrouter").expect("get").expect("snapshot");
    assert_eq!(loaded, snapshot);

    let contents = std::fs::read_to_string(directory.path().join("openrouter.json"))
        .expect("cache file reads");
    assert!(contents.contains("\"version\": 1"));
    assert!(!contents.contains("sk-"));
    assert!(!contents.contains("Bearer"));
    assert!(!contents.contains("Authorization"));
}

#[test]
fn file_cache_rejects_an_unknown_version() {
    let directory = tempfile::tempdir().expect("tempdir");
    std::fs::write(
        directory.path().join("openrouter.json"),
        r#"{"version":99,"providerId":"openrouter","fetchedAt":"2026-11-07T12:00:00.000Z","models":[]}"#,
    )
    .expect("cache file writes");

    let cache = FileCatalogCache::new(directory.path());
    assert!(matches!(
        cache.get("openrouter"),
        Err(CacheError::UnsupportedVersion(99))
    ));
}

#[test]
fn file_cache_rejects_path_traversal_provider_ids() {
    let directory = tempfile::tempdir().expect("tempdir");
    let cache = FileCatalogCache::new(directory.path());

    assert!(matches!(
        cache.get("../secret"),
        Err(CacheError::InvalidProviderId(_))
    ));
    assert!(matches!(
        cache.get(".."),
        Err(CacheError::InvalidProviderId(_))
    ));
}

#[test]
fn default_cache_path_is_under_app_data() {
    let path = FileCatalogCache::default_path().expect("app-data path");
    assert!(path.ends_with(CATALOG_DIR_NAME));
}

#[tokio::test]
async fn refresh_failure_returns_the_last_known_good_snapshot() {
    let server = MockServer::start().await;
    Mock::given(method("GET"))
        .and(path("/api/v1/models"))
        .respond_with(ResponseTemplate::new(500))
        .mount(&server)
        .await;

    let client = HttpClient::new().expect("client");
    let cache = MemoryCatalogCache::new();
    let fetched = fixed("2026-11-06T00:00:00.000Z");
    let snapshot = CatalogSnapshot {
        fetched_at: fetched,
        models: vec![model("example/cached")],
    };
    cache.put("openrouter", &snapshot, fetched).expect("put");

    let result = load_catalog(
        &client,
        &format!("{}/api/v1", server.uri()),
        &cache,
        "openrouter",
        fixed("2026-11-07T12:00:00.000Z"),
        false,
    )
    .await
    .expect("stale snapshot");

    assert!(result.from_cache);
    assert!(result.stale);
    assert_eq!(result.models.len(), 1);
    assert_eq!(result.models[0].remote_id, "example/cached");
    assert!(matches!(
        result.refresh_error,
        Some(OpenRouterError::Network(_))
    ));
}

#[tokio::test]
async fn refresh_failure_without_a_snapshot_returns_the_error() {
    let server = MockServer::start().await;
    Mock::given(method("GET"))
        .and(path("/api/v1/models"))
        .respond_with(ResponseTemplate::new(500))
        .mount(&server)
        .await;

    let client = HttpClient::new().expect("client");
    let cache = MemoryCatalogCache::new();
    let error = load_catalog(
        &client,
        &format!("{}/api/v1", server.uri()),
        &cache,
        "openrouter",
        fixed("2026-11-07T12:00:00.000Z"),
        false,
    )
    .await
    .expect_err("refresh error");

    assert!(matches!(error, OpenRouterError::Network(_)));
}

#[tokio::test]
async fn fresh_cache_avoids_a_network_request() {
    let server = MockServer::start().await;
    let client = HttpClient::new().expect("client");
    let cache = MemoryCatalogCache::new();
    let fetched = fixed("2026-11-07T00:00:00.000Z");
    let snapshot = CatalogSnapshot {
        fetched_at: fetched,
        models: vec![model("example/cached")],
    };
    cache.put("openrouter", &snapshot, fetched).expect("put");

    let result = load_catalog(
        &client,
        &format!("{}/api/v1", server.uri()),
        &cache,
        "openrouter",
        fixed("2026-11-07T23:59:59.000Z"),
        false,
    )
    .await
    .expect("fresh cache");

    assert!(result.from_cache);
    assert!(!result.stale);
    assert!(
        server
            .received_requests()
            .await
            .expect("requests")
            .is_empty()
    );
}

#[tokio::test]
async fn refresh_writes_a_new_snapshot() {
    let server = MockServer::start().await;
    Mock::given(method("GET"))
        .and(path("/api/v1/models"))
        .respond_with(ResponseTemplate::new(200).set_body_json(models_fixture()))
        .mount(&server)
        .await;

    let client = HttpClient::new().expect("client");
    let cache = MemoryCatalogCache::new();
    let now = fixed("2026-11-07T12:00:00.000Z");
    let result = load_catalog(
        &client,
        &format!("{}/api/v1", server.uri()),
        &cache,
        "openrouter",
        now,
        false,
    )
    .await
    .expect("catalog loads");

    assert!(!result.from_cache);
    assert!(!result.stale);
    assert_eq!(result.models.len(), 8);

    let snapshot = cache.get("openrouter").expect("get").expect("snapshot");
    assert_eq!(snapshot.fetched_at, now);
    assert_eq!(snapshot.models.len(), 8);
}

#[tokio::test]
async fn force_refresh_bypasses_a_fresh_cache() {
    let server = MockServer::start().await;
    Mock::given(method("GET"))
        .and(path("/api/v1/models"))
        .respond_with(ResponseTemplate::new(200).set_body_json(models_fixture()))
        .mount(&server)
        .await;

    let client = HttpClient::new().expect("client");
    let cache = MemoryCatalogCache::new();
    let fetched = fixed("2026-11-07T00:00:00.000Z");
    let snapshot = CatalogSnapshot {
        fetched_at: fetched,
        models: vec![model("example/cached")],
    };
    cache.put("openrouter", &snapshot, fetched).expect("put");

    let result = refresh_catalog(
        &client,
        &format!("{}/api/v1", server.uri()),
        &cache,
        "openrouter",
        fixed("2026-11-07T01:00:00.000Z"),
    )
    .await
    .expect("refresh");

    assert!(!result.from_cache);
    assert_eq!(result.models.len(), 8);
    assert_eq!(server.received_requests().await.expect("requests").len(), 1);
}

struct FailingCache;

impl CatalogCache for FailingCache {
    fn get(&self, _provider_id: &str) -> Result<Option<CatalogSnapshot>, CacheError> {
        Ok(None)
    }

    fn put(
        &self,
        _provider_id: &str,
        _snapshot: &CatalogSnapshot,
        _fetched_at: Timestamp,
    ) -> Result<(), CacheError> {
        Err(CacheError::Lock)
    }
}

#[tokio::test]
async fn a_cache_write_failure_does_not_discard_a_successful_refresh() {
    let server = MockServer::start().await;
    Mock::given(method("GET"))
        .and(path("/api/v1/models"))
        .respond_with(ResponseTemplate::new(200).set_body_json(models_fixture()))
        .mount(&server)
        .await;

    let client = HttpClient::new().expect("client");
    let result = load_catalog(
        &client,
        &format!("{}/api/v1", server.uri()),
        &FailingCache,
        "openrouter",
        fixed("2026-11-07T12:00:00.000Z"),
        false,
    )
    .await
    .expect("catalog loads");

    assert_eq!(result.models.len(), 8);
    assert!(!result.from_cache);
    assert!(result.refresh_error.is_none());
}
