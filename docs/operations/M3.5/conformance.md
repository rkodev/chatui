# Rust conformance fixture harness

The `chatui-conformance` package is the shared Rust oracle for every adapter,
parser, catalog, and export task. It reads the checked-in fixtures under
`contracts/fixtures/**` at test time. It never copies fixture content into Rust
source and never rewrites expected vectors.

## Fixture root resolution

`ConformanceFixtures::repository()` resolves the repository root from
`CARGO_MANIFEST_DIR`. The package lives at `rust/tests/conformance`, so the root
is three ancestors above the manifest directory. Fixtures are read from
`<root>/contracts/fixtures`.

Tests that need a temporary tree can use
`ConformanceFixtures::from_repository_root(path)`. Production conformance tests
must use `ConformanceFixtures::repository()`.

## Loader API

`ConformanceFixtures` is the single entry point:

| Method | Returns | Source |
| --- | --- | --- |
| `stream_fixtures()` | `Vec<StreamFixture>` | `contracts/fixtures/streams/` |
| `model_fixtures()` | `Vec<ModelFixture>` | `contracts/fixtures/models/` |
| `error_fixtures()` | `Vec<ErrorFixture>` | `contracts/fixtures/errors/` |
| `request_vectors()` | `Vec<RequestVector>` | `contracts/fixtures/requests/` |
| `expected_files()` | `Vec<String>` | `contracts/fixtures/expected/` |
| `stream_expectations()` | `Vec<StreamExpectation>` | expected files with an `events` array |
| `key_expectation()` | `KeyExpectation` | `expected/openrouter-key.json` |
| `catalogs()` | `CatalogVectors` | `expected/catalogs.json` |
| `capability_inference()` | `CapabilityInference` | `expected/capability-inference.json` |
| `fallback_models()` | `FallbackModels` | `expected/fallback-models.json` |
| `math()` | `MathVectors` | `expected/math.json` |
| `gemini_finish_reasons()` | `GeminiFinishReasons` | `expected/gemini-finish-reasons.json` |
| `anthropic_thinking()` | `AnthropicThinking` | `expected/anthropic-thinking.json` |
| `openrouter_errors()` | `ErrorVectors` | `expected/openrouter-errors.json` |
| `provider_errors()` | `ErrorVectors` | `expected/provider-errors.json` |
| `export()` | `chatui_contracts::ChatExport` | `expected/chatui-export.json` |
| `inventory()` | `FixtureInventory` | counts for every family |

`FixtureRoot` exposes `read`, `read_json`, `list_files`, and `list_json_files`
for cases that need a raw file that does not have a typed loader yet.

Expected stream vectors use `ExpectedStreamEvent` and `ExpectedChatError`
instead of the domain `StreamEvent` and `ChatError`. The shared vectors omit the
runtime `at` timestamp on errors; adapters attach it when they construct a
domain error.

## Inventory

`FixtureInventory` asserts the shared counts:

- 14 provider presets
- 5 JSON Schemas
- 11 raw stream fixtures
- 6 raw model fixtures
- 28 raw error fixtures
- 22 request vectors
- 21 expected normalized vectors

The inventory tests also assert the exact file names in each family, so a
missing or renamed fixture fails the suite.

## Test names

The package currently runs these conformance tests:

- `fixture_inventory_matches_shared_counts`
- `fixture_file_names_match_the_shared_inventory`
- `every_expected_stream_vector_resolves_to_a_raw_fixture`
- `every_request_vector_has_a_known_kind_and_provider`
- `chatui_export_deserializes_through_contracts`
- `stream_expectations_have_consistent_text_and_reasoning`
- `request_vectors_have_the_expected_shape_for_their_kind`
- `model_fixtures_are_classified_and_have_entries`
- `error_fixtures_are_classified_and_parse`
- `expected_error_vectors_cover_the_required_codes`
- `catalog_vectors_load_and_reference_real_model_fixtures`
- `capability_inference_vectors_load`
- `fallback_model_vectors_load`
- `math_vectors_load`
- `gemini_finish_reason_vectors_load`
- `anthropic_thinking_vectors_load`
- `key_expectation_loads`

Run them with:

```bash
cd rust && cargo nextest run -p chatui-conformance
```

## Adapter surface and registry (M3.5-06.4)

`chatui-providers` exposes one stable adapter surface in
`src/adapter.rs` and one contract-backed registry in `src/registry.rs`.

### Adapter surface

`ProviderAdapter` is a small enum dispatch (`OpenRouter` or `Unsupported`)
because the async methods would otherwise need boxed futures. It exposes:

| Method | Purpose |
| --- | --- |
| `kind()` | Provider kind from the contract preset. |
| `preset_id()` | Contract preset ID. |
| `is_supported()` | Whether the adapter is implemented. |
| `validate_key(client, ctx, cancel)` | `GET /key`; cancellation drops the request future. |
| `list_models(client, ctx, cancel)` | Public `GET /models`; no key is sent. |
| `prepare_request(ctx, request, supported_parameters, attachments)` | Provider body plus non-secret headers. |
| `stream_chat(client, ctx, request, supported_parameters, attachments, cancel, on_event)` | Streams normalized `StreamEvent` values through a callback and returns a `StreamSummary`. |

`ProviderContext` carries only the contract preset, resolved base URL, auth
scheme, non-secret headers, and the resolved API key. It never carries
settings, storage handles, or a secret store. `ProviderContext::new` combines
the preset's `defaultHeaders` with its resolved `attributionHeaders`.

`OpenRouterAdapter` is the only implemented adapter. Every other preset gets an
`UnsupportedAdapter` whose methods return a clear `unknown` error with the
message `Provider preset <id> is not supported yet.` until M3.5-07.*.

### Registry behavior

`ProviderRegistry::load()` reads `contracts/providers.json` through
`chatui-contracts`. It:

- loads every preset in contract order;
- rejects duplicate IDs;
- rejects presets missing `id`, `label`, `baseUrl`, `defaultModelId`,
  `modelDiscovery.path`, the auth header/query field required by the auth
  scheme, or a non-empty `keyValidation.path`;
- maps `openrouter` to `OpenRouterAdapter` and every other preset to
  `UnsupportedAdapter`;
- exposes `presets()`, `get(id)`, `adapter(id)`, `default_preset()`,
  `reference_preset()`, and `context(id, app_config, api_key)`.

`create_adapter(preset_id)` is the free-function entry point. It returns
`RegistryError::UnknownPreset` for an ID that is not in the contract.

## OpenRouter conformance gate (M3.5-06.4)

The OpenRouter adapter conformance suite lives in
`rust/tests/conformance/tests/openrouter_adapter.rs`. It drives the adapter
surface against the shared fixtures and mocks. It reuses the provider network
harness for chunked-stream cancellation and abrupt-stream retry cases.

### Test names

- `registry_loads_every_contract_preset`
- `registry_rejects_duplicate_ids`
- `registry_rejects_missing_required_fields`
- `create_adapter_rejects_unknown_presets`
- `unsupported_presets_return_a_clear_error`
- `validate_key_maps_the_shared_fixture`
- `list_models_maps_the_shared_catalog`
- `every_openrouter_stream_vector_matches_through_the_adapter`
- `in_stream_error_preserves_partial_text_and_does_not_retry`
- `every_openrouter_http_error_vector_matches_through_the_adapter`
- `http_429_preserves_retry_after_and_is_retryable`
- `http_402_is_not_retryable`
- `http_503_is_retryable`
- `every_openrouter_request_vector_matches_through_the_adapter`
- `cancellation_before_first_token_returns_cancelled`
- `cancellation_after_first_token_preserves_partial_text`
- `retry_allowed_before_partial_output`
- `retry_not_allowed_after_partial_output`

### Section 7.4 checklist

| Checklist item | Result | Evidence |
| --- | --- | --- |
| `GET /key` validates the key and exposes label, usage, limit, free-tier, and rate-limit metadata. | Pass | `validate_key_maps_the_shared_fixture`; provider `validate_key_sends_bearer_auth_and_maps_the_fixture` |
| `GET /models` populates the catalog, including paid, free, vision, reasoning, and long-context models. | Pass | `list_models_maps_the_shared_catalog`; provider `maps_every_fixture_entry` |
| Pricing strings are converted correctly and displayed per million tokens, with free models marked. | Pass | `converts_unusual_decimal_pricing_without_binary_float_artifacts`, `marks_a_zero_priced_free_model_and_preserves_the_suffix`, `multiplies_decimal_strings_by_one_million_exactly`; UI `models::state::tests::price_formatting_uses_per_million_and_free`, `models::state::tests::free_badge_uses_zero_price_and_free_suffix`, `models::view::tests::window_renders_grouped_models_with_badges_and_prices` |
| Context window, max output tokens, input/output modalities, and supported parameters are mapped. | Pass | `list_models_maps_the_shared_catalog`; provider `maps_a_paid_text_and_tool_model`, `infers_vision_and_file_capabilities_from_modalities`, `maps_a_long_context_model` |
| Chat streams incrementally from `POST /chat/completions`. | Pass | `every_openrouter_stream_vector_matches_through_the_adapter`; provider `every_openrouter_stream_vector_matches` |
| `: OPENROUTER PROCESSING` keep-alives and unknown SSE fields do not break parsing. | Pass | `every_openrouter_stream_vector_matches_through_the_adapter` (includes `openrouter-keepalive`); provider `keepalives_do_not_emit_blank_text` |
| Reasoning content is normalized and displayed separately from the answer. | Pass | `every_openrouter_stream_vector_matches_through_the_adapter` (includes `openrouter-reasoning`); provider `reasoning_details_win_over_reasoning_without_duplication`, `incremental_reasoning_is_not_duplicated`; UI `chat::tests::reduce_stream_keeps_text_and_reasoning_separate`, `chat::tests::send_persists_user_message_and_streams_text_and_reasoning`. `MessageRow::from_message` populates a separate `reasoning` field through `reasoning_text`. |
| Final usage and cost are persisted and visible. | Pass | `every_openrouter_stream_vector_matches_through_the_adapter` (includes `openrouter-usage`); provider `reported_usage_is_not_estimated`, `usage_cost_round_trips`; UI `chat::tests::send_persists_user_message_and_streams_text_and_reasoning`, `chat::message_list_tests::message_row_renders_role_metadata_status_and_error`, `settings::tests::token_usage_setting_hides_message_metadata` |
| `402`, `429`, `503`, context errors, and errors inside a 200 stream are handled. | Pass | `every_openrouter_http_error_vector_matches_through_the_adapter`, `http_429_preserves_retry_after_and_is_retryable`, `http_402_is_not_retryable`, `http_503_is_retryable`, `in_stream_error_preserves_partial_text_and_does_not_retry` |
| Provider routing preferences serialize correctly and protected fields cannot be overridden. | Pass | `every_openrouter_request_vector_matches_through_the_adapter` (includes `openrouter-routing` and `openrouter-advanced-protected`); provider `routing_vector_serializes_curated_provider_controls`, `advanced_protected_vector_rejects_sorted_fields` |
| Attribution headers are configurable and contain no user data. | Pass | `every_openrouter_request_vector_matches_through_the_adapter` (includes `openrouter-attribution`); provider `attribution_vector_uses_app_config`, `no_key_or_personal_data_in_headers_or_body` |
| Browser-direct CORS behavior is verified, or the web release is explicitly blocked. | N/A for desktop | The Rust desktop client uses native HTTP, not browser CORS. Web evidence is owned by M1. |

### Cancellation and retry rules

| Rule | Result | Evidence |
| --- | --- | --- |
| Cancellation before the first token stops the stream and emits no text. | Pass | `cancellation_before_first_token_returns_cancelled` |
| Cancellation after the first token preserves partial text. | Pass | `cancellation_after_first_token_preserves_partial_text` |
| A retry is allowed only before any text or reasoning is emitted. | Pass | `retry_allowed_before_partial_output`, `retry_not_allowed_after_partial_output` |
| In-stream errors are never retryable. | Pass | `in_stream_error_preserves_partial_text_and_does_not_retry` |
| `429` and `503` are retryable; `402` is not. | Pass | `http_429_preserves_retry_after_and_is_retryable`, `http_503_is_retryable`, `http_402_is_not_retryable` |

### Gate result

The OpenRouter checklist passes for every item that applies to the desktop
client. The adapter-level items were re-run in M3.5-12.1 together with the UI
display and persistence tests that were previously deferred to M3.5-08.4,
M3.5-09.1, M3.5-09.4, and M3.5-09.5. Browser-direct CORS remains `N/A` because
the desktop client uses native HTTP.

## Network test harness

`rust/crates/chatui-providers/tests/network/harness.rs` is the reusable network
harness for provider adapter tests. It has two parts:

- `NetworkHarness` wraps a `wiremock` server. It mounts SSE bodies, keep-alive
  comments, mid-stream error events, `Retry-After` responses, delayed
  responses, and plain status errors. `received_requests()` returns the
  recorded requests so a test can assert retry counts.
- `ChunkedSseServer` is a small raw TCP server that writes a real
  `Transfer-Encoding: chunked` SSE response. `wiremock` buffers a response
  body, so this server is the only way to exercise chunk boundaries,
  mid-stream connection failures, and cancellation while a stream is open.
  `ChunkedSseServer::start_abrupt` closes the connection without the final
  zero-length chunk to simulate a transport failure.

| API | Purpose |
| --- | --- |
| `NetworkHarness::start()` | Start a mock server. |
| `NetworkHarness::uri()` | Base URI for requests. |
| `mount_sse(route, body)` | Raw SSE body. |
| `mount_sse_events(route, events)` | One `data:` event per payload. |
| `mount_keepalive_sse(route, events)` | Keep-alive comments between events. |
| `mount_mid_stream_error(route, partial)` | Partial text followed by an error event. |
| `mount_retry_after(route, status, seconds)` | Status response with `Retry-After`. |
| `mount_delayed(route, delay)` | Delayed response for timeout tests. |
| `mount_status(route, status)` | Plain status response. |
| `received_requests()` | Recorded requests for retry-count assertions. |
| `closed_port_uri()` | URI with no listener for connection-failure tests. |
| `ChunkedSseServer::start(chunks)` | Chunked SSE response with a clean terminator. |
| `ChunkedSseServer::start_abrupt(chunks)` | Chunked SSE response that drops mid-stream. |
| `SseChunk::new(data)` / `.with_delay(delay)` | One chunk and its pre-write delay. |

A provider test outside `tests/network/` includes the harness with:

```rust
#[path = "network/harness.rs"]
mod harness;
```

The harness tests currently run as the `network` test target:

- `harness_serves_chunked_sse`
- `harness_serves_sse_events`
- `harness_serves_keepalives`
- `harness_serves_mid_stream_error_event`
- `harness_reports_mid_stream_error`
- `harness_honors_retry_after`
- `harness_simulates_connection_failure`
- `harness_simulates_timeout`
- `cancellation_interrupts_stream_reads`
- `watchdog_cancels_stalled_stream`
- `retry_attempts_three_times_before_giving_up`
- `retry_does_not_repeat_after_partial_output`
- `retry_loop_uses_retry_after_from_failure`

## Adding a conformance case

Shared vectors are the contract. Do not edit them to make an adapter pass.

1. If the case is already covered by a shared vector, load the input through
   `ConformanceFixtures` and assert the adapter output in the provider crate.
   For example, an OpenRouter adapter test loads
   `fixtures.stream_expectations()` and compares its normalized events to the
   matching `StreamExpectation`.
2. If the case is adapter-specific and has no shared vector, add the test in the
   provider crate. Keep the shared input fixture unchanged and construct only
   the adapter-specific input inline.
3. If the shared contract genuinely needs a new vector, change
   `contracts/fixtures/**` and `scripts/validate-contracts.mjs` in the same
   change, then update the inventory counts and typed loader here. Contract
   changes are not part of an adapter task.
4. Add a typed loader only when a new fixture family appears. Prefer extending
   an existing typed struct over adding a `serde_json::Value` escape hatch.
5. Run `make contracts-check`, `cargo fmt --all --check`, and
   `cargo nextest run -p chatui-conformance` before marking the task complete.

## Missing fixtures

A missing fixture fails the inventory test with the expected count and the
exact file-name list. A missing raw stream referenced by an expected vector
fails `every_expected_stream_vector_resolves_to_a_raw_fixture`. A missing
provider ID fails `every_request_vector_has_a_known_kind_and_provider`.

Do not add a fallback that silently skips a missing fixture. The conformance
suite is a release gate, not a best-effort smoke test.

## Full conformance run (M3.5-12.1)

Recorded on 2026-11-08 on the Linux x86_64 host described in
[M3.5-verification.md](M3.5-verification.md) section 1. The run used the debug
test profile (`cargo nextest` default) and the release workspace build. No
shared fixture was edited. `git status --short` showed only the task files and
the new conformance test support files.

### Commands and results

| Command | Result | Bounded evidence |
| --- | --- | --- |
| `make contracts-check` | Pass (exit 0) | `Contract validation passed.` |
| `make rust-fmt` | Pass (exit 0) | no output |
| `make rust-lint` | Pass (exit 0) | `Finished dev profile` |
| `make rust-nextest` | Pass (exit 0) | 1001 tests run: 1001 passed, 0 skipped |
| `make rust-conformance` | Pass (exit 0) | 96 tests run: 96 passed, 0 skipped |
| `make rust-build` | Pass (exit 0) | `Finished release profile` |
| `make rust-deny` | Blocked (exit 2) | Pre-existing `rust/deny.toml` schema mismatch: cargo-deny 0.20.2 rejects `unmaintained = "warn"` and `unsound = "warn"`. `rust/deny.toml` is outside this task's owned paths. |
| `make rust-audit` | Blocked (exit 2) | Pre-existing advisory `rsa 0.9.10` RUSTSEC-2023-0071 (Marvin Attack, no fixed upgrade). `rust/Cargo.lock` is outside this task's owned paths. |

The two supply-chain blockers are the same pre-existing issues recorded by
M3.5-11.4 in [ci.md](ci.md). They are not conformance failures and no fixture,
adapter, or test was changed to hide them.

### Fixture coverage

Every shared fixture family is loaded through `ConformanceFixtures` and checked
by the conformance package. The inventory test asserts the exact counts and file
names, so a missing or renamed fixture fails the suite.

| Family | Count | Tests |
| --- | --- | --- |
| Provider presets | 14 | `fixture_inventory_matches_shared_counts`, `fixture_file_names_match_the_shared_inventory`, `registry_loads_every_contract_preset` |
| JSON Schemas | 5 | `fixture_inventory_matches_shared_counts` |
| Raw stream fixtures | 11 | `every_expected_stream_vector_resolves_to_a_raw_fixture`, `every_*_stream_vector_matches_through_the_adapter` |
| Raw model fixtures | 6 | `model_fixtures_are_classified_and_have_entries`, `*_catalog_maps_the_shared_vector` |
| Raw error fixtures | 28 | `error_fixtures_are_classified_and_parse`, `every_*_http_error_vector_matches_through_the_adapter` |
| Request vectors | 22 | `request_vectors_have_the_expected_shape_for_their_kind`, `every_*_request_vector_matches_through_the_adapter` |
| Expected normalized vectors | 21 | `stream_expectations_have_consistent_text_and_reasoning`, `expected_error_vectors_cover_the_required_codes` |
| Catalog vectors | shared | `catalog_vectors_load_and_reference_real_model_fixtures` |
| Fallback vectors | shared | `fallback_model_vectors_load`, `fallback_model_ids_match_the_shared_vector` |
| Capability vectors | shared | `capability_inference_vectors_load`, `capability_inference_uses_preset_defaults` |
| Math vectors | shared | `math_vectors_load` |
| Export fixture | shared | `chatui_export_deserializes_through_contracts` |

### Provider conformance suites

`make rust-conformance` ran 96 tests across every enabled adapter family:

| Suite | Tests | Result |
| --- | --- | --- |
| `openrouter_adapter` | 18 | Pass |
| `openai_compatible` (OpenAI, Groq, Mistral, DeepSeek, xAI, Together, Fireworks, LM Studio, llama.cpp, Custom) | 15 | Pass |
| `anthropic_adapter` | 15 | Pass |
| `gemini_adapter` | 14 | Pass |
| `ollama_adapter` | 16 | Pass |
| `inventory` | 5 | Pass |
| `loaders` | 12 | Pass |
| `process_death` | 1 | Pass |

The provider crate's own suite also passed: `cargo nextest run -p chatui-providers`
ran 268 tests with 268 passed.

### Critical cases (spec section 15.2)

Cases that are explicitly web- or Android-specific are marked `N/A` for the
desktop client. Every other case has a test name or a recorded gap.

| # | Case | Result | Evidence |
| --- | --- | --- | --- |
| 1 | First run with no provider shows onboarding, preselects OpenRouter, and blocks sending. | Pass | `onboarding::state::tests::openrouter_is_preselected_and_recommended`, `onboarding::state::tests::sending_is_disabled_until_complete`, `onboarding::view::tests::window_starts_on_welcome_with_openrouter_recommended`, `onboarding::view::tests::skip_is_unavailable_on_first_run`, `chatui-desktop::bin/chatui-desktop deep_link::tests::routes_to_onboarding_when_incomplete` |
| 2 | Invalid OpenRouter key shows an auth error, preserves the form, and does not accept the key. | Pass | `onboarding::service::tests::invalid_key_is_rejected_and_nothing_is_stored`, `onboarding::state::tests::validation_failure_preserves_the_form`, `onboarding::view::tests::failed_connect_preserves_the_form_and_shows_actionable_copy`, `onboarding::error::tests::auth_error_copy_is_actionable` |
| 3 | Valid OpenRouter key passes `GET /key`, loads `/models`, and selects a default model. | Pass | `onboarding::service::tests::valid_key_stores_secret_and_persists_defaults`, `onboarding::service::tests::missing_preset_default_uses_first_text_model`, `onboarding::view::tests::successful_connect_completes_and_routes_to_the_shell` |
| 4 | OpenRouter catalog mapping handles paid, free, vision, reasoning, and tool-capable models, converts per-token prices to per-million, and ignores unknown fields. | Pass | `openrouter_adapter::list_models_maps_the_shared_catalog`; provider `maps_every_fixture_entry`, `maps_a_paid_text_and_tool_model`, `infers_vision_and_file_capabilities_from_modalities`, `maps_a_long_context_model`, `converts_unusual_decimal_pricing_without_binary_float_artifacts`, `marks_a_zero_priced_free_model_and_preserves_the_suffix`, `multiplies_decimal_strings_by_one_million_exactly`, `ignores_unknown_fields_but_keeps_small_non_secret_metadata` |
| 5 | OpenRouter streaming ignores `: OPENROUTER PROCESSING` keep-alives and appends text in order. | Pass | `openrouter_adapter::every_openrouter_stream_vector_matches_through_the_adapter` (includes `openrouter-keepalive`); provider `keepalives_do_not_emit_blank_text`, `sse::openrouter_keepalive_fixture_emits_only_data_payloads`, `sse::openrouter_keepalive_fixture_handles_arbitrary_chunk_boundaries`, `sse::openrouter_keepalive_fixture_handles_crlf` |
| 6 | OpenRouter `reasoning` and `reasoning_details` produce one normalized reasoning stream without duplication. | Pass | `openrouter_adapter::every_openrouter_stream_vector_matches_through_the_adapter` (includes `openrouter-reasoning`); provider `reasoning_details_win_over_reasoning_without_duplication`, `incremental_reasoning_is_not_duplicated`; UI `chat::tests::reduce_stream_keeps_text_and_reasoning_separate` |
| 7 | OpenRouter usage/cost from the final chunk is persisted and displayed. | Pass | `openrouter_adapter::every_openrouter_stream_vector_matches_through_the_adapter` (includes `openrouter-usage`); provider `reported_usage_is_not_estimated`, `usage_cost_round_trips`; UI `chat::tests::send_persists_user_message_and_streams_text_and_reasoning`, `chat::message_list_tests::message_row_renders_role_metadata_status_and_error` |
| 8 | An OpenRouter error object inside an HTTP 200 stream stops the message, preserves partial text, and does not auto-retry. | Pass | `openrouter_adapter::in_stream_error_preserves_partial_text_and_does_not_retry`; provider `in_stream_error_preserves_partial_content_and_does_not_retry`, `in_stream_errors_are_never_retryable`; UI `chat::tests::error_preserves_partial_output` |
| 9 | OpenRouter 402 insufficient credits maps to `quota` with a billing message and no retry. | Pass | `openrouter_adapter::http_402_is_not_retryable`; provider `http_402_maps_to_quota_without_retry`; UI `chat::tests::describe_chat_error_maps_codes_to_actionable_copy` |
| 10 | OpenRouter 503 no available upstream provider retries with backoff. | Pass | `openrouter_adapter::http_503_is_retryable`; provider `http_503_maps_to_provider_and_is_retryable`; network `retry_attempts_three_times_before_giving_up` |
| 11 | OpenAI-compatible streaming appends text in order and finalizes usage. | Pass | `openai_compatible::every_openai_stream_vector_matches_through_the_adapter`; provider `openai_compatible::tests::openai_preset_uses_all_mode` (request mode) and the shared stream expectations cover text order and usage. |
| 12 | Anthropic `thinking` and `text` deltas are separated correctly. | Pass | `anthropic_adapter::anthropic_thinking_and_text_stay_separate`; provider `anthropic::tests::thinking_and_text_deltas_stay_separate` |
| 13 | Gemini `MAX_TOKENS` maps to `length`. | Pass | `gemini_adapter::gemini_finish_reasons_match_the_shared_vector`; provider `gemini::tests::finish_reasons_match_the_shared_enum` (`map_finish_reason("MAX_TOKENS") == FinishReason::Length`) |
| 14 | Ollama NDJSON completes and usage is recorded. | Pass | `ollama_adapter::every_ollama_stream_vector_matches_through_the_adapter`; provider `ollama::tests::stream_maps_text_finish_and_usage` |
| 15 | Cancel stops the request, persists partial text, and marks `cancelled`. | Pass | `chat::tests::stop_cancels_and_marks_cancelled`; `openrouter_adapter::cancellation_after_first_token_preserves_partial_text`; provider `openrouter_stream::cancellation_after_first_token_preserves_partial_text` |
| 16 | Network failure before first token retries up to 3 times. | Pass | `chatui-providers::network retry_attempts_three_times_before_giving_up`; `openrouter_adapter::retry_allowed_before_partial_output` |
| 17 | Network failure after first token does not auto-retry. | Pass | `chatui-providers::network retry_does_not_repeat_after_partial_output`; `openrouter_adapter::retry_not_allowed_after_partial_output`; provider `retry::tests::no_retry_after_partial_output` |
| 18 | Switching model mid-conversation persists and uses the new model on the next send. | Blocked | The desktop client stores the provider/model snapshot on each assistant message (`chat::tests::send_persists_user_message_and_streams_text_and_reasoning`) and the model picker sets the global default (`models::service::tests::set_default_model_persists_provider_and_model`), but there is no per-conversation model override or mid-conversation switch path. No test claims it. |
| 19 | Switching provider warns about unsupported attachments. | Pass | `chat::composer_tests::capability_warning_for_unsupported_attachments`, `chat::composer_tests::model_capabilities_fall_back_to_capability_flags`. The warning is driven by the selected model's capabilities, which is the desktop equivalent of the provider/model switch warning. |
| 20 | OpenRouter provider routing preferences are serialized correctly and protected fields cannot be overridden by advanced JSON. | Pass | `openrouter_adapter::every_openrouter_request_vector_matches_through_the_adapter` (includes `openrouter-routing` and `openrouter-advanced-protected`); provider `routing_vector_serializes_curated_provider_controls`, `advanced_protected_vector_rejects_sorted_fields`, `advanced_routing_keys_pass_through`, `invalid_routing_values_are_rejected` |
| 21 | Reload during streaming marks the message interrupted and preserves partial text. | Pass | `recovery::tests::recovery_marks_streaming_cancelled_and_preserves_partial_content`; `process_death::process_death_preserves_partial_content_and_marks_cancelled`; UI `chat::message_list_tests::persisted_streaming_message_is_not_rendered_twice` |
| 22 | Markdown XSS payloads are rendered inert. | Pass | `markdown_tests::xss_payloads_are_inert`, `markdown_tests::image_allowlist_rejects_non_http_sources`, `markdown_tests::link_allowlist_rejects_credentials_and_missing_hosts` |
| 23 | A 10,000-message conversation scrolls without rendering all messages. | Pass | `chat::message_list_tests::ten_thousand_messages_render_a_bounded_window_in_order`; storage `repositories::tests::large_conversation_pagination_stays_bounded` |
| 24 | Export/import preserves IDs, ordering, model snapshots, and attachments. | Pass | `export::tests::export_round_trips_ids_ordering_and_snapshots`, `import::tests::import_round_trips_export`, `export::tests::export_attachment_bytes_are_opt_in`, `import::tests::import_skips_missing_attachment_bytes`, `import::tests::import_remaps_model_ids_by_identity` |
| 25 | Clearing data removes conversations, keys, and attachments. | Pass | `data::service::tests::clear_all_removes_data_and_keys_but_keeps_settings`, `data::tests::clear_all_requires_typed_confirmation`, `data::state::tests::clear_requires_the_exact_typed_phrase` |
| 26 | Android process death during streaming preserves partial content. | N/A for desktop | Android-specific. The desktop equivalent is covered by `process_death::process_death_preserves_partial_content_and_marks_cancelled` and `recovery::tests::recovery_marks_streaming_cancelled_and_preserves_partial_content`. |
| 27 | Android backup exclusion prevents keys from being restored on another device. | N/A for desktop | Android-specific. Desktop backup exclusion is documented, not implemented, in [security.md](security.md) section 5; backup/sync is M5 scope. |
| 28 | 429 with `Retry-After` schedules a retry and shows a countdown. | Blocked | Retry scheduling passes: `openrouter_adapter::http_429_preserves_retry_after_and_is_retryable`, provider `openrouter_stream::http_429_preserves_retry_after`, `retry::tests::retry_after_overrides_local_backoff`, `network::retry_loop_uses_retry_after_from_failure`. The UI shows a static `Retry after Ns` detail (`chat::service::describe_chat_error`) but has no live countdown. No test claims a countdown. |

### Process-death and restart procedure

The automated procedure is
`chatui-conformance::process_death process_death_preserves_partial_content_and_marks_cancelled`.
It runs in the debug test profile on Linux x86_64:

1. The test spawns `process_death_child` with a temporary SQLite database path.
2. The child opens the database, creates a conversation, commits an assistant
   message with `status = streaming` and partial text plus reasoning, prints
   `ready`, and waits.
3. The parent reads `ready` and calls `Child::kill()`. On Unix this is
   `SIGKILL`; on Windows it is `TerminateProcess`. No shutdown or cleanup code
   runs.
4. The parent reopens the same database and runs `RecoveryService::recover`.
5. The test asserts the message is `cancelled`, the finish reason is
   `cancelled`, the interruption note is present, and the partial text and
   reasoning are byte-for-byte preserved.

The storage-level test
`recovery::tests::recovery_marks_streaming_cancelled_and_preserves_partial_content`
also covers the same recovery contract without a child process. The one-second
streaming persistence trade-off means a kill can lose up to about one second of
the newest tokens; recovery preserves exactly what reached the database and
never invents or truncates text.

### Secret-handling evidence

No real key, prompt, conversation, or personal data is used in this evidence.
The tests use sentinels such as `sk-test-not-a-real-key` and `sk-test-key`.

| Check | Result | Evidence |
| --- | --- | --- |
| Errors never contain secret material. | Pass | `chatui-providers error::tests::chat_error_never_contains_secret_material`, `openrouter::errors::tests::error_messages_never_contain_secret_material` |
| Debug output redacts keys. | Pass | `onboarding::service::tests::connect_input_debug_redacts_the_key`, `providers::state::tests::form_debug_redacts_the_key`, `secrets::lifecycle::tests::debug_output_is_redacted` |
| Panic output is redacted. | Pass | `secrets::panic::tests::panic_report_writes_redacted_output`, `panic_message_redacts_headers_and_tokens`, `panic_message_redacts_registered_values` |
| Exports exclude secrets. | Pass | `export::tests::export_excludes_secret_headers_and_metadata`, `data::service::tests::export_all_includes_entities_and_excludes_secrets` |
| Settings views do not expose secrets. | Pass | `settings::tests::settings_view_model_does_not_expose_secrets` |
| Catalog cache stores no secrets. | Pass | `openrouter_catalog::file_cache_round_trips_and_stores_no_secrets` |
| Evidence scan for key-shaped strings. | Pass | `grep -rEn "sk-[A-Za-z0-9]{8,}|Bearer [A-Za-z0-9]{8,}|api[_-]?key..." docs/operations/M3.5 rust/tests/conformance` returned no matches. |

### Blockers and gaps

| ID | Blocker or gap | Impact | Owner | Next action |
| --- | --- | --- | --- | --- |
| C-01 | `make rust-deny` fails on the pre-0.16 `rust/deny.toml` advisories schema. | Supply-chain gate cannot pass. | Task that owns `rust/deny.toml` | Migrate `unmaintained` and `unsound` to the cargo-deny 0.20 schema. |
| C-02 | `make rust-audit` reports `rsa 0.9.10` RUSTSEC-2023-0071 with no fixed upgrade. | Supply-chain gate cannot pass. | Task that owns the dependency graph | Replace or isolate `rsa`, or record an approved, narrowly scoped exception. |
| C-03 | No per-conversation model override or mid-conversation model switch. | Section 15.2 case 18 is not met. | Chat/model UI owner | Add a conversation model update path and a test that sends with the new model. |
| C-04 | No live `Retry-After` countdown; only a static detail string. | Section 15.2 case 28 is partially met. | Chat UI owner | Add a countdown driven by `retry_after_seconds` and a test. |
| C-05 | macOS and Windows process-death runs are unavailable. | Process-death evidence is Linux-only. | Verification/OS owner | Repeat the procedure on macOS and Windows when runners exist (B-01, B-02). |
