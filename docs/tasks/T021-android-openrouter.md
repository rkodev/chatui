# T021 - Android OpenRouter adapter, onboarding, providers, models

- Milestone: M1
- Depends on: T020
- Suggested subagent: general
- Parallel-safe: yes, with T011 and T031
- Read: `docs/IMPLEMENTATION.md` sections 3.1, 3.3-3.4, 5.3, 7.1-7.10, 9.6, 10.4, 11.5, 12.3, 15.2 (cases 1-10, 20, 28)

## Goal

Implement the Android OpenRouter-first provider layer and onboarding/provider/model management. OpenRouter must work end-to-end before other adapters are added.

## Owns

- `android/core/network/**` (adapters, SSE/NDJSON parsers, retry, normalized events)
- `android/feature/onboarding/**`
- `android/feature/providers/**`
- `android/feature/models/**`
- `android/core/network/src/test/**`, `android/feature/onboarding/src/test/**`, `android/feature/providers/src/test/**`

## Deliverables

1. Provider adapter interface matching section 7.1 with `suspend` functions and `Flow<StreamEvent>`/callback. Include `ProviderContext`, `ProviderKeyInfo`, `NormalizedChatRequest`, `ChatError`.
2. OkHttp client with connection pooling, timeouts (connect 30 s, read 0, call 0), 120 s inactivity watchdog, cancellation via coroutine + `Call.cancel()`, redacted debug logging interceptor only when enabled.
3. SSE parser with `okhttp-sse` and `kotlinx.serialization`; ignore `: OPENROUTER PROCESSING`; handle multi-line data and final buffer.
4. Registry that loads `contracts/providers.json` or generated equivalent; no duplicated metadata.
5. OpenRouter adapter:
   - `/key` validation with label, usage, limit, free-tier, rate-limit.
   - Public `/models` catalog with 24-hour cache and last-known-good snapshot.
   - Catalog mapping: pricing strings per token to per million, context, max output, modalities, supported parameters, free badge, canonical slug, unknown fields.
   - `/chat/completions` streaming with `stream_options.include_usage`, reasoning and `reasoning_details` dedup, in-stream error handling, usage/cost, attribution headers.
   - Curated routing controls and protected fields as in section 7.4.
6. Retry policy from section 7.9 with `Retry-After`; no retry after partial output.
7. `ChatStreamer` service that drives adapters and emits normalized events; T022 will own the chat ViewModel/persistence loop.
8. Onboarding screens: welcome, provider picker with OpenRouter preselected/recommended, key entry, test connection, default model.
9. Provider management: list, add/edit custom, key rotation/removal, model refresh, OpenRouter key details.
10. Model picker: bottom sheet with provider groups, search, pinned/hidden, capability chips, context, modalities, price, free badge, manual add.
11. Tests with MockWebServer and shared fixtures for catalog mapping, key validation, keep-alives, reasoning, usage/cost, 402/429/503, in-stream errors, cancellation, protected fields.

## Steps

1. Implement network interfaces, SSE parser, and OpenRouter adapter.
2. Add registry and model caching.
3. Build onboarding and provider/model UI.
4. Add MockWebServer fixture tests.
5. Run the Android gate.

## Acceptance criteria

- Valid OpenRouter key passes `/key`, loads `/models`, and selects a default model.
- Invalid key shows an actionable auth error without accepting the key.
- Paid/free/vision/reasoning models map correctly and prices display per million tokens.
- Keep-alives and unknown fields do not break streaming.
- Reasoning is not duplicated; usage/cost is available to the chat layer.
- In-stream errors and 402/429/503 map correctly; retries honor `Retry-After`.
- Protected request fields cannot be overridden.
- Critical cases 1-10, 20, and 28 pass on Android.

## Verification

```bash
cd android && ./gradlew :core:network:testDebugUnitTest :feature:onboarding:testDebugUnitTest :feature:providers:testDebugUnitTest :app:assembleDebug
```

## Out of scope

- Chat screen, composer, Markdown, attachments (T022).
- Conversation drawer, search, export/import, settings, share target (T023).
- Additional providers (T025).
- Release hardening (T024).
