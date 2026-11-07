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
