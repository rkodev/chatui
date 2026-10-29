# T031 - Rust OpenRouter provider, streaming, onboarding

- Milestone: M3.5
- Depends on: T030
- Suggested subagent: general
- Parallel-safe: yes, with T011 and T021
- Read: `docs/IMPLEMENTATION.md` sections 3.1, 3.3-3.4, 5.3, 7.1-7.10, 10.2, 10.4, 11.5, 12.6, 15.2 (cases 1-10, 20, 28)

## Goal

Implement the Rust provider layer and Rust onboarding/provider/model UI for OpenRouter first. This is the reference adapter for Rust and must pass the shared conformance fixtures.

## Owns

- `rust/crates/chatui-providers/**`
- `rust/crates/chatui-ui/src/features/onboarding/**`
- `rust/crates/chatui-ui/src/features/providers/**`
- `rust/crates/chatui-ui/src/features/models/**`
- Provider-related tests under `rust/crates/chatui-providers/tests/**` and `rust/tests/conformance/**`

## Deliverables

1. `ProviderAdapter` trait with async methods, `ProviderContext`, `ProviderKeyInfo`, `NormalizedChatRequest`, `StreamEvent`, cancellation via `CancellationToken`, and event delivery via `mpsc` or `futures::Stream`.
2. `reqwest` client with rustls, shared pooling, user agent, timeouts (connect 30 s, no total during stream, 120 s inactivity watchdog), and cancellation that aborts the request.
3. SSE parser matching section 7.6 and appendix 21.1:
   - CRLF normalization, multi-line data, comments, bounded buffer, final flush.
   - Ignore `: OPENROUTER PROCESSING` and unknown fields.
   - No JSON parsing in the transport.
4. OpenRouter adapter:
   - `/key` validation and metadata mapping.
   - Public `/models` catalog with 24-hour cache and last-known-good snapshot.
   - Catalog mapping: pricing strings to per-million, context, max output, modalities, supported parameters, free badge, canonical slug, unknown fields.
   - `/chat/completions` streaming with usage, reasoning dedup, in-stream errors, and attribution headers.
   - Curated routing controls and protected fields.
5. Registry loading presets from `contracts/providers.json`; no duplicated metadata.
6. Retry policy from section 7.9 with jitter, `Retry-After`, and no retry after partial output.
7. Onboarding UI: welcome, provider picker with OpenRouter preselected/recommended, key entry, test connection, default model.
8. Provider/model UI: provider list, add/edit custom, key rotation/removal, model refresh, model picker with search, pinned/hidden, capabilities, context, modalities, price, free badge, manual add.
9. Tests with `wiremock`/`httpmock` and shared fixtures for `/key`, catalog mapping, keep-alives, reasoning, usage/cost, 402/429/503, in-stream errors, cancellation, and protected fields.

## Steps

1. Define the provider trait and SSE parser.
2. Implement the OpenRouter adapter and registry.
3. Build onboarding and provider/model UI.
4. Add fixture and mock-server tests.
5. Run the Rust gate.

## Acceptance criteria

- Valid OpenRouter key passes `/key`, loads `/models`, and selects a default model.
- Invalid key shows an actionable error without accepting the key.
- Paid/free/vision/reasoning models map correctly; prices display per million tokens.
- Keep-alives and unknown fields do not break streaming.
- Reasoning is not duplicated; usage/cost is available.
- In-stream errors and 402/429/503 map correctly; retries honor `Retry-After`.
- Protected request fields cannot be overridden.
- Critical cases 1-10, 20, and 28 pass on Rust when enabled.

## Verification

```bash
cd rust && cargo fmt --all --check && cargo clippy --workspace --all-targets -- -D warnings && cargo nextest run --workspace
```

## Out of scope

- Chat, conversations, settings UI (T032).
- Packaging, updater, final security/accessibility (T033).
- Additional providers and parity (T034).
