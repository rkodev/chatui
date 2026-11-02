# M1 OpenRouter Core Implementation Plan

- Source milestone: [M1-openrouter-core.md](../milestones/M1-openrouter-core.md)
- Task breakdown: [M1 task list](M1/README.md)
- Authoritative spec: [IMPLEMENTATION.md](../IMPLEMENTATION.md)
- Foundation baseline: [M0 exit-gate verification](M0/M0-verification.md)
- Status: Proposed
- Prepared: 2026-11-02
- Scope: OpenRouter-only end-to-end chat on web and Android, including adapter, catalog, streaming, onboarding, model management, chat UI, conversation basics, and conformance evidence
- Platforms: web, Android
- Planning assumption: M0 is complete and its contracts, repositories, secret stores, router/shell, and CI workflows are the baseline. Rust remains untouched.

## 1. Summary

M1 starts from a complete M0 foundation and has no M1 feature implementation yet. The contracts and fixtures exist, both clients build, the local persistence layers exist, and the shells launch. However, neither client has a provider registry, an SSE transport, an OpenRouter adapter, a chat runner, onboarding, a model picker, a chat screen, or conversation management UI. Browser-direct OpenRouter CORS is unverified, and the shared expected normalized stream outputs that M0 deliberately deferred do not exist.

| Area | Current completion | Assessment |
| --- | --- | --- |
| Shared contracts and provider presets | Complete for M0 | `contracts/providers.json`, schemas, raw fixtures, and parity baseline exist |
| Expected normalized conformance vectors | 0% | M0 deferred these to M1; no `contracts/fixtures/expected/` tree |
| Web provider layer | 0% | `web/src/providers/` contains only `.gitkeep` |
| Web feature surfaces | 0% | Onboarding, providers, chat, and conversations folders contain only `.gitkeep`; router routes are placeholders |
| Web persistence and secrets | Foundation complete | IndexedDB repositories and three secret modes exist and are tested |
| Android provider layer | Interface only | `core:network` has contracts but no HTTP client, SSE parser, adapter, or registry |
| Android feature surfaces | 0% | Every feature module is a placeholder composable |
| Android persistence and secrets | Foundation complete | Room DAOs, DataStore, and Tink secret storage exist; no domain repository layer |
| Browser CORS | Unverified | `browserCors` is `unknown` for every preset |
| M1 exit gate | Not met | No OpenRouter request, chat stream, onboarding, or evidence exists |

### 1.1 Critical path

```text
M1-01 Shared conformance vectors and CORS plan
   |
   +--> M1-02 Web provider layer ----+--> M1-03 Web onboarding/models --+
   |                                  +--> M1-05 Web conversations -----+--> M1-04 Web chat
   |
   +--> M1-06 Android provider layer -+--> M1-07 Android onboarding/models --+
                                      +--> M1-08 Android conversations -----+--> M1-09 Android chat

M1-02..M1-09 ------------------------------> M1-10 CORS, conformance, parity, exit evidence
```

The shared conformance vectors should land first because both adapters must emit the same normalized events and errors. Web and Android then proceed independently. CORS verification can begin as soon as either adapter can issue `/key` and `/chat/completions`; a blocker stops the web release path rather than being worked around with a proxy.

### 1.2 Estimate

The milestone target is two weeks. With one web implementer and one Android implementer plus a short shared conformance/CORS preparation, a realistic estimate is 8-11 working days. A single implementer should plan for 16-20 working days because the chat UI, Markdown/code rendering, and recovery work are substantial on both platforms. The largest schedule risks are browser CORS, Markdown/code rendering scope, and Android dependency/API work in feature modules that currently only depend on Compose.

## 2. Current-State Audit

### 2.1 Reusable M0 assets

| Asset | Current state | M1 impact |
| --- | --- | --- |
| `contracts/providers.json` | Present with all 14 presets and OpenRouter as default/reference/required | Web and Android can load presets instead of hard-coding provider metadata |
| `contracts/fixtures/streams/openrouter-*.sse` | Present for text, reasoning, usage, in-stream error, and keep-alive | Adapter and SSE tests can start immediately |
| `contracts/fixtures/models/openrouter-models.json` | Present with paid, free, vision, reasoning, tool, long-context, unusual pricing, unknown fields | Catalog mapping tests have realistic input |
| `contracts/fixtures/models/openrouter-key.json` | Present with label, usage, limit, free-tier, and rate-limit fields | `/key` mapping tests have input |
| `contracts/fixtures/errors/openrouter-{402,429,503}.json` | Present | HTTP error mapping tests have input |
| Web domain types | Complete for spec section 6 | No new domain modeling needed except narrow additions |
| Web IndexedDB repositories | Complete for conversations, messages, providers, models, settings, secrets, attachments | M1 wires features on top of them |
| Web secret store | Session, remember, and passphrase modes with AES-GCM | Onboarding can persist keys without new crypto work |
| Web router and shell | All spec 8.3 routes exist as placeholders | M1 replaces placeholders rather than adding routes |
| Web design tokens and primitives | Complete | Chat and provider UI can use existing Button, Dialog, Menu, Tooltip, Toast |
| Android `core:model` | Complete domain types including `StreamEvent`, `FinishReason`, `ChatError` | No new stream event model |
| Android Room schema and DAOs | Complete for spec 9.5 | M1 adds repository mappers and a few recovery queries |
| Android DataStore settings | Typed settings model with change methods | M1 wires defaults and last-active restore |
| Android Tink secret store | Ciphertext-only storage with test coverage | Onboarding can store keys |
| Android Hilt shell | Application, activity, and settings injection exist | M1 extends the graph with database, network, repositories, and runners |
| CI workflows | Contracts, web, and Android gates pass | M1 adds tests to existing gates; no new workflow is required |

### 2.2 Confirmed missing areas

| Path | Current state | Consequence |
| --- | --- | --- |
| `web/src/providers/.gitkeep` | Empty directory | No registry, adapter, SSE transport, cache, retry, or error normalization |
| `web/src/features/{onboarding,providers,chat,conversations}/.gitkeep` | Empty directories | No user-facing M1 flow exists |
| `web/src/app/router.tsx` | Routes render placeholders | Chat and settings routes are not wired to features |
| `web/src/app/routes/placeholders.tsx` | Placeholder copy only | No welcome, provider, model, chat, or conversation UI |
| `android/core/network` | Interfaces only; depends on coroutines and serialization | No OkHttp, SSE, MockWebServer, adapter, registry, cache, or retry |
| `android/feature/*` | Placeholder composables with Compose-only dependencies | No feature state, persistence access, markdown, or networking |
| `android/core/database` | Entities and DAOs only | No entity-to-domain repositories, transaction service, or streaming recovery query |
| `android/app/.../di/AppModule.kt` | Provides only `SettingsDataStore` | No database, secret store, HTTP client, repositories, or runner injection |
| `android/app/.../MainActivity.kt` | Collects settings and renders the shell | No app-scope services or recovery on start |
| `contracts/fixtures/expected/` | Does not exist | Web and Android lack shared normalized-event and normalized-error expectations |
| `contracts/parity.md` | Requirement table only | No implemented/partial/evidence status for M1 rows |
| `scripts/validate-contracts.mjs` | Validates schemas, fixtures, presets, parity shape | Does not validate expected normalized vectors |
| Browser CORS evidence | None | `browserCors` remains `unknown`; web release cannot be cleared |

### 2.3 Foundation-to-feature gaps that are easy to miss

- Web runtime config currently enables `openai`, `anthropic`, `google`, `groq`, `ollama`, and `custom` in `web/public/config.json`. M1 must keep OpenRouter as the only fully enabled provider and must not expose custom-provider creation, even though the presets remain in contracts.
- Web has no typed settings facade. `SettingsRepository` is key/value only, so defaults, `onboardingCompleted`, `lastActiveConversationId`, and send-key behavior need a typed wrapper or service.
- Android `AndroidSettings.loadRemoteImages` defaults to `true`, while M1 requires remote Markdown images to remain blocked by default. M1 must ensure the renderer does not load remote images regardless of that setting until M2 exposes the user-facing control.
- Android has no repository layer above the DAOs. Feature modules currently cannot depend on `core:database` entities directly without violating the M0 boundary note in `ChatUiDatabase`.
- Android has no DAO query for messages left in `streaming`, so startup recovery cannot be implemented without a small schema-safe DAO addition.
- Web `MessageRepository` has no query for messages by status either, so reload recovery needs a small repository extension.
- Neither client has a build-time mechanism for loading `contracts/providers.json` and shared fixtures. The contracts live outside the web root and outside the Android module resource roots.
- The existing parity validator treats every pipe-delimited table in `contracts/parity.md` as part of the requirement table. Adding a second implementation-status table without updating the validator will produce duplicate-row failures.

## 3. Gap Analysis

Status values: **Missing** means no implementation exists; **Partial** means foundation exists but the M1 deliverable is not met; **Present** means complete for M1.

### 3.1 Shared Contracts and Evidence

| ID | Required deliverable | Current | Gap | Resolution / owner |
| --- | --- | --- | --- | --- |
| S-01 | Expected normalized output for every OpenRouter stream fixture | Missing | M0 handoff explicitly deferred expected snapshots to M1; only raw SSE exists | Add `contracts/fixtures/expected/openrouter-*.json` covering events, text/reasoning accumulation, usage, finish, and errors; validate in `scripts/validate-contracts.mjs`; M1-01 |
| S-02 | Expected normalized errors for status and in-stream envelopes | Missing | Only provider-shaped 402/429/503 envelopes exist; no normalized `ChatError` vectors | Add normalized error expectations for 402, 429, 503, in-stream 502, auth, model-not-found, and context-length cases; M1-01 |
| S-03 | Error fixtures for key and request failures | Partial | 401, 403, 404, and 400 context-length fixtures are absent | Add synthetic envelopes or document inline test construction; M1-01 |
| S-04 | Routing serialization and protected-field vectors | Missing | No fixture or contract statement for curated `provider` options or forbidden `model`, `messages`, `stream`, `stream_options` overrides | Add request-serialization vectors under `contracts/fixtures/requests/` and test both adapters against them; M1-01 |
| S-05 | Browser CORS result recorded | Missing | `browserCors` is `unknown`; no `/key`, `/models`, `/chat/completions` evidence | Run the three-endpoint browser matrix with a redacted test key, update `contracts/providers.json`, and record evidence; M1-10 |
| S-06 | OpenRouter conformance checklist evidence | Missing | Spec 7.4 checklist exists but no M1 evidence file | Add `docs/operations/M1/M1-verification.md` with checklist, fixture results, CORS matrix, and critical-case mapping; M1-10 |
| S-07 | Truthful parity status for M1 | Partial | Requirement baseline exists; no implementation status or evidence | Keep the required table intact and add an M1 implementation-status list below it, or update the validator to support a second table; M1-10 |
| S-08 | Default model selection contract | Missing | `defaultModelId` is `openrouter/auto`, which is not guaranteed to appear in a public catalog snapshot | Define fallback selection and test it; M1-01 with adapter consumers |
| S-09 | Deterministic reasoning deduplication rule | Partial | Fixture exercises both `reasoning` and `reasoning_details`, but no shared expected output defines precedence | Expected vector chooses structured details once and suppresses the duplicate plain reasoning delta; M1-01 |
| S-10 | Contract delivery mechanism to clients | Missing | No web import path, Android asset copy, or test-resource path for contracts and fixtures | Define one generated/copied contract bundle per client without duplicating provider metadata in source; M1-02 and M1-06 |

### 3.2 Web Client

| ID | Required deliverable | Current | Gap | Resolution / owner |
| --- | --- | --- | --- | --- |
| W-01 | Provider registry from contracts | Missing | `web/src/providers/` is empty; no runtime loading or validation of presets | Add registry, contract loader, enabled-preset handling, and tests; M1-02 |
| W-02 | SSE transport | Missing | No CRLF normalization, multi-line `data:`, comment handling, bounded buffer, final flush, or abort support | Implement transport independent of provider JSON with fixture-driven tests; M1-02 |
| W-03 | OpenRouter adapter | Missing | No `/key`, `/models`, `/chat/completions`, attribution headers, or catalog mapping | Implement `validateKey`, `listModels`, `streamChat`, catalog cache, last-known-good, and manual refresh; M1-02 |
| W-04 | Error and retry policy | Missing | No status mapping, in-stream error handling, `Retry-After`, jittered backoff, or no-retry-after-token rule | Implement section 7.9 with fake timers and fixture tests; M1-02 |
| W-05 | Routing request builder | Missing | No curated routing controls, advanced JSON guard, or protected-field rejection | Implement `provider` serialization plus protected-field validation; M1-02 |
| W-06 | Chat runner and streaming state | Missing | No service owns the request, buffer, flush cadence, send lock, cancel, or regenerate | Add `ChatRunner` and Zustand streaming slice; M1-04 |
| W-07 | Onboarding and welcome state | Missing | Root and provider routes are placeholders | Add BYOK/on-device welcome, OpenRouter preselection, key entry, inline testing, and preserved-form errors; M1-03 |
| W-08 | Provider management | Missing | No provider list, details, key rotation/removal, enabled toggle, CORS status, or key introspection display | Add provider management under settings and the required OpenRouter-only gating; M1-03 |
| W-09 | Model picker and model management | Missing | No search, groups, pinned/hidden, capability chips, context, modalities, price, free badge, manual add, unavailable warnings, or defaults | Add picker and global defaults with immediate persistence; M1-03 |
| W-10 | Chat screen and message list | Missing | Only a placeholder route exists | Add virtualized list, pagination, scroll anchoring, isolated streaming message, stable keys, and chat states; M1-04 |
| W-11 | Composer | Missing | No auto-grow, send key modes, drafts, stop/send, disabled states, or text preservation | Add composer with per-conversation drafts and send lock integration; M1-04 |
| W-12 | Markdown and code | Missing | No Markdown, GFM, sanitization, Shiki, code label, or copy | Add safe Markdown, GFM, no raw HTML, incomplete-fence handling, and code copy; M1-04 |
| W-13 | Message actions | Missing | No copy, copy code, regenerate, edit-last-user, or retry | Add actions with truncation confirmation and no branching; M1-04 |
| W-14 | Conversation basics | Missing | No sidebar, history groups, new/rename/delete, undo toast, or title default | Add conversation list and management UI; M1-05 |
| W-15 | Last-active restore and safe new chat | Missing | Root route always renders a placeholder | Add redirect to last active or new chat and root integration; M1-05 |
| W-16 | Streaming recovery | Missing | No startup scan marks `streaming` messages interrupted | Add repository query and recovery service invoked before chat renders; M1-05 |
| W-17 | Typed defaults and settings | Partial | Generic key/value storage exists; no typed facade | Add typed settings service and wire default provider/model, last active, send key, and usage visibility; M1-03/05 |
| W-18 | Config and feature gating | Partial | Runtime config enables other presets and custom providers | Keep OpenRouter enabled, show others disabled/coming later, and block custom creation; M1-03 |
| W-19 | Offline and CORS errors | Missing | No offline detection or `cors` classification | Add minimal offline banner/disabled send and network-failure-to-CORS heuristic; M1-02/04 |
| W-20 | Web dependency additions | Missing | No Markdown, Shiki, or virtualization dependencies | Add only M1 dependencies; defer math, attachments, PWA, and full Playwright; M1-04 |
| W-21 | Web tests for critical cases | Missing | No MSW handlers, SSE mock, adapter tests, or component tests for M1 flows | Add Vitest/MSW coverage for cases 1-10, 15-18, 20-21, 28; M1-02..05 |
| W-22 | Streaming accessibility | Missing | No live-region announcements or focus behavior | Announce start, complete, and error only; respect reduced motion; M1-04 |
| W-23 | Remote image blocking | Missing | No Markdown renderer exists, so no policy is enforced | Render remote images as blocked placeholders/links by default; M1-04 |

### 3.3 Android Client

| ID | Required deliverable | Current | Gap | Resolution / owner |
| --- | --- | --- | --- | --- |
| A-01 | OkHttp/SSE dependency and transport | Missing | `core:network` has no OkHttp, `okhttp-sse`, MockWebServer, or SSE parser | Add dependencies and a fixture-testable SSE transport with cancellation; M1-06 |
| A-02 | Provider registry from contracts | Missing | No asset/resource loading of presets; no registry | Add build-safe contract packaging, registry, and validation; M1-06 |
| A-03 | OpenRouter adapter | Missing | Interface only; no `/key`, `/models`, `/chat/completions`, attribution, or catalog mapping | Implement all adapter methods, cache, last-known-good, and retry; M1-06 |
| A-04 | Error and retry policy | Missing | No mapping, `Retry-After`, jittered backoff, or no-retry-after-output rule | Implement with coroutine tests and MockWebServer; M1-06 |
| A-05 | Routing request builder | Missing | No curated routing, advanced JSON guard, or protected-field rejection | Implement and test identical semantics to web; M1-06 |
| A-06 | Repository layer | Missing | DAOs exist but no domain repositories or entity mappers | Add conversation, message, provider, model, and settings repositories with transactions; M1-08 |
| A-07 | Streaming recovery query | Missing | Message DAO has no status query | Add a `streaming` message query and startup recovery; M1-08 |
| A-08 | Hilt graph extension | Partial | Only `SettingsDataStore` is provided | Provide database, secret store, HTTP client, clock, repositories, adapter registry, and runner; M1-06/08 |
| A-09 | Onboarding and welcome state | Missing | `OnboardingPlaceholder` only | Add BYOK/on-device welcome, OpenRouter preselection, key entry, testing state, and preserved-form errors; M1-07 |
| A-10 | Provider management | Missing | `ProvidersPlaceholder` only | Add provider list, details, key rotation/removal, enabled toggle, CORS status, and key metadata; M1-07 |
| A-11 | Model picker and defaults | Missing | No model UI or defaults | Add searchable picker, pins/hidden, capability chips, context, modalities, price, free badge, manual add, and defaults; M1-07 |
| A-12 | Chat runner and ViewModel | Missing | `ChatPlaceholder` only; no immutable `UiState`, streaming reducer, debounce persistence, cancel, or regenerate | Add ViewModel-scoped runner and send lock; M1-09 |
| A-13 | Chat screen and composer | Missing | No message list, composer, streaming presentation, or states | Add `LazyColumn` with stable keys, composer, stop/send, drafts, error cards, and chat states; M1-09 |
| A-14 | Markdown and code dependencies | Missing | Feature modules have no Markdown, highlighting, or clipboard support | Add `multiplatform-markdown-renderer-m3`, highlighting, and copy; M1-09 |
| A-15 | Message actions | Missing | None | Add copy, copy code, regenerate, edit-last-user, and retry without branching; M1-09 |
| A-16 | Conversation basics | Missing | `ConversationsPlaceholder` only | Add new chat, rename, delete, date groups, drawer/list, and title default; M1-08 |
| A-17 | Last-active restore and navigation | Missing | Shell starts on the conversations placeholder and has no chat detail route | Add onboarding/chat routing, last-active restore, and safe new chat; M1-08/09 |
| A-18 | Process-death recovery | Missing | No startup scan or interrupted-state UI | Mark `streaming` messages cancelled, preserve partial content, and explain on reload; M1-08 |
| A-19 | Settings and remote-image policy | Partial | Typed DataStore exists; `loadRemoteImages` defaults to true | Wire defaults and ensure M1 Markdown blocks remote images regardless of the future setting; M1-09 |
| A-20 | Android tests for critical cases | Missing | No adapter, MockWebServer, chat-runner, or recovery tests | Add JUnit/MockWebServer and persistence/cancel/reload tests for cases 1-10, 15-18, 20-21, 28; M1-06..09 |
| A-21 | Compose semantics and accessibility | Missing | Placeholders have no interactive semantics | Add content descriptions, 48 dp targets, and live-region equivalents for streaming status; M1-09 |

### 3.4 Cross-Cutting Gaps and Ambiguities

| ID | Gap | Impact | Proposed resolution |
| --- | --- | --- | --- |
| X-01 | Contracts live outside client source/resource roots | Both clients could re-implement or drift from presets | Web imports/copies non-secret contracts at build time; Android copies presets and fixtures into generated assets/test resources; CI freshness check if generated files are committed |
| X-02 | Expected normalized outputs were deferred from M0 | Web and Android can diverge while both pass their own tests | Make S-01/M1-01 the shared conformance gate before adapter implementation starts |
| X-03 | `defaultModelId` may be absent from the catalog | Onboarding cannot always select `openrouter/auto` | Select `defaultModelId` when present; otherwise choose the first non-hidden, text-output model in stable remote-ID order, and show a notice if the preset default is unavailable |
| X-04 | Reasoning deduplication has no written algorithm | One client may duplicate reasoning text | Per chunk, prefer `reasoning_details` text when present; only emit `reasoning` when the same text was not already emitted for that turn; assert against S-01 |
| X-05 | Pricing uses decimal strings but domain pricing uses `number` | Display and cost rounding can differ | Parse with decimal-safe conversion, keep raw strings in metadata, store reported `costUsd` as the provider value, and format only at the UI boundary |
| X-06 | Routing controls have no explicit M1 surface | Adapter work may land without UI, or UI may drift into M2 conversation settings | Put curated routing defaults in OpenRouter provider details and a read-only routing summary plus minimal per-conversation override in chat settings; full parameter sheet remains M2 |
| X-07 | Advanced JSON is mentioned in M1 but parameter UI is M2 | Unclear whether an advanced JSON editor is M1 | Enforce protected-field rejection in the request builder now and test it programmatically; defer the advanced JSON editor to M2 |
| X-08 | Parity document cannot hold a second table without validator changes | Adding implementation status may fail `make contracts-check` | Keep the required table unchanged and add a bullet/status subsection, or update the validator to parse only the first table and validate the second deliberately |
| X-09 | Streaming persistence cadence is not enforced anywhere | UI jank or lost partial text | Flush UI at 33-50 ms, persist about every 1 s and on visibility/pagehide/complete/cancel/error; isolate the streaming message |
| X-10 | Browser CORS is an all-or-nothing web release risk | Web milestone could be undeliverable | Verify early with a redacted key; if blocked, stop the web release path and record Android plus future self-hosted proxy as the fallback; never add a hidden proxy |
| X-11 | Playwright is optional in M1 | Schedule risk or missing end-to-end evidence | Defer Playwright and the full E2E suite to M2; use Vitest/MSW and manual smoke evidence in M1 |
| X-12 | Multi-tab stream locking is unspecified | Two tabs could stream into one conversation | Enforce one active stream per conversation per running client instance; multi-tab coordination is explicitly out of scope for M1 |
| X-13 | Title truncation semantics are unspecified | Different client titles for the same text | Trim, collapse whitespace/newlines, and take the first 60 Unicode code points from the first user message; use the same helper logic on both platforms |
| X-14 | Remote-image default conflicts on Android | M1 could leak IP addresses through Markdown | Block remote images in the M1 renderer on both platforms; leave the user-facing setting to M2 |
| X-15 | CORS evidence needs a key but fixtures must stay secret-free | Evidence could leak a real key | Use a disposable test key, redact it in screenshots/logs, and record only status codes, headers, timestamps, and conclusions |
| X-16 | No shared evidence location for M1 | Exit gate cannot be reviewed | Create `docs/operations/M1/M1-verification.md` with the CORS matrix, conformance checklist, command results, critical-case mapping, and manual smoke records |
| X-17 | Task-record location differs between M0 and the milestone contract | Runner may not find M1 tasks | Generate formal tasks under `docs/tasks/M1/` per `docs/milestones/README.md`, or point the runner at `docs/operations/M1/` if reusing the M0 layout; record the choice before task generation |
| X-18 | Android default settings differ from web/spec intent | Behavior parity issues | Review defaults during M1-08/09, especially `loadRemoteImages`, and document the deliberate default |

## 4. Proposed Work Breakdown

Ten planning units are proposed. Nine mirror the milestone's suggested platform slices; M1-01 is a shared prerequisite needed to make fixture conformance meaningful, and M1-10 is the shared verification/evidence task explicitly allowed by the milestone. Formal task records should be generated later using the milestone task template.

### 4.1 Task map

| ID | Title | Owns | Depends on | Parallel-safe with |
| --- | --- | --- | --- | --- |
| M1-01 | Shared OpenRouter conformance vectors and CORS plan | `contracts/fixtures/expected/`, `contracts/fixtures/requests/`, `scripts/validate-contracts.mjs`, `docs/operations/M1/` skeleton | none | none at start; unblocks adapters |
| M1-02 | Web OpenRouter provider layer | `web/src/providers/`, contract loading, web platform CORS/offline helpers, provider-layer tests | M1-01 | M1-06 |
| M1-03 | Web onboarding, provider management, and model management | `web/src/features/onboarding/`, `web/src/features/providers/`, provider/model settings routes, typed settings facade | M1-02 | M1-07, M1-05 |
| M1-04 | Web chat runner, chat screen, composer, Markdown/code, actions | `web/src/features/chat/`, streaming stores, chat route, Markdown/code dependencies | M1-02, M1-05 | M1-09 |
| M1-05 | Web conversations, persistence integration, and recovery | `web/src/features/conversations/`, conversation route/sidebar, storage extensions, startup recovery | M1-02 | M1-03, M1-08 |
| M1-06 | Android OpenRouter provider layer | `android/core/network/`, contract asset copy, OkHttp/SSE dependencies, provider-layer tests | M1-01 | M1-02 |
| M1-07 | Android onboarding, provider management, and model management | `android/feature/onboarding/`, `android/feature/providers/`, provider/model UI, defaults | M1-06 | M1-03, M1-08 |
| M1-08 | Android repositories, conversations, and recovery | `android/core/database` repository additions, `android/feature/conversations/`, navigation/restore, Hilt graph | M1-06, M1-07 | M1-05, M1-09 |
| M1-09 | Android chat runner, chat screen, composer, Markdown/code, actions | `android/feature/chat/`, chat ViewModel/runner, Markdown dependencies | M1-06, M1-08 | M1-04 |
| M1-10 | Shared CORS verification, conformance evidence, parity, exit gate | `contracts/providers.json` `browserCors`, `contracts/parity.md`, `docs/operations/M1/M1-verification.md`; no client feature source | all | none at completion |

### 4.2 M1-01: Shared OpenRouter conformance vectors and CORS plan

Goal: make the raw fixtures executable as shared conformance expectations before either adapter is written.

Deliverables:

- Expected normalized event sequences for text, keep-alive, reasoning, usage, in-stream error, and cancellation behavior under `contracts/fixtures/expected/`.
- Expected normalized `ChatError` objects for 402, 429, 503, in-stream error, invalid key, model not found, and context length.
- Request vectors for curated routing, advanced JSON protection, attribution headers, and system/message normalization under `contracts/fixtures/requests/`.
- A documented default-model fallback rule and reasoning deduplication rule.
- Validator support for the expected and request vectors, with file-and-field-specific errors.
- `docs/operations/M1/` skeleton and a CORS matrix template that records endpoint, preflight result, status, and conclusion without secrets.

Acceptance criteria:

- `make contracts-check` validates the new vectors.
- The expected vectors are raw JSON/JSONL, UTF-8, non-empty, and contain no secrets or real prompts.
- A deliberately broken expected vector or request vector fails validation with a precise message.
- No client source is introduced.

Verification:

```bash
make contracts-install
make contracts-check
git status --short
```

Out of scope: client adapters, live CORS execution, UI, and parity status changes.

### 4.3 M1-02: Web OpenRouter provider layer

Goal: make OpenRouter callable through a normalized web adapter with no feature UI.

Deliverables:

- Contract loading and a provider registry that reads `contracts/providers.json` or a generated equivalent; no provider metadata hard-coded in components.
- SSE transport with CRLF normalization, multi-line `data:`, comments, bounded buffering, final flush, and `AbortSignal` cancellation.
- OpenRouter `validateKey`, `listModels`, and `streamChat`.
- Catalog mapping for IDs, canonical slug, label, context, max output, modalities, pricing per million, supported parameters, free models, and unknown metadata.
- 24-hour cache with manual refresh and last-known-good snapshot.
- Attribution headers from runtime config; no secrets in query parameters.
- Retry policy, status mapping, in-stream error handling, reasoning deduplication, usage/cost normalization, and protected-field validation.
- CORS/network error classification and tests for request serialization.

Acceptance criteria:

- Fixture tests cover text, keep-alive, reasoning, usage, in-stream error, 402, 429, 503, cancellation, retry, and `Retry-After`.
- The adapter emits exactly the S-01 normalized events.
- Catalog tests cover paid, free, vision, reasoning, tool, long-context, unusual pricing, and unknown fields.
- Routing tests show curated options serialize and protected fields cannot be overridden.
- No onboarding, chat, or conversation UI is added.

Verification:

```bash
make web-lint && make web-typecheck && make web-test && make web-build
```

Out of scope: all feature screens, persistence wiring, Markdown, and chat state.

### 4.4 M1-03: Web onboarding, provider management, and model management

Goal: deliver the OpenRouter-only setup and model-selection surfaces.

Deliverables:

- Welcome/empty state explaining BYOK and on-device data, OpenRouter preselected and marked Recommended.
- Provider picker with OpenRouter enabled and other presets disabled or "coming later"; no custom-provider creation.
- Key entry with optional label, Connect, inline testing, actionable errors, and preserved form.
- Public catalog load before key validation; sending remains disabled until `/key` succeeds.
- Provider management with key rotation/removal, enabled toggle, CORS status, and key label/usage/limit/free-tier/rate-limit details.
- Model picker and Manage models with search, provider groups, pinned/hidden, capability chips, context, modalities, price per million, free badge, manual add, unavailable warnings, per-conversation override, and global defaults.
- Immediate model-switch persistence and provider/model label snapshots for assistant messages.
- Typed settings facade for defaults, onboarding completion, send key, usage visibility, and last active conversation.
- Minimal curated routing controls and a routing summary in the OpenRouter provider/conversation surface.

Acceptance criteria:

- Critical cases 1-4, 18, and the routing/model parts of 20 have tests.
- An invalid key preserves the form and is not accepted.
- Sending is disabled until key validation succeeds.
- Other providers and custom creation are not exposed as usable flows.
- Model pricing is displayed per million tokens and free models are marked.

Verification:

```bash
make web-lint && make web-typecheck && make web-test && make web-build
```

Out of scope: attachments, math, full parameter sheet, search, export/import, and additional providers.

### 4.5 M1-04: Web chat runner, chat screen, composer, Markdown/code, actions

Goal: deliver the end-to-end web chat experience on top of the M1-02 adapter and M1-05 conversation persistence.

Deliverables:

- `ChatRunner` service with one active stream per conversation, send lock, cancel, regenerate, and normalized-event reduction.
- Streaming buffer flushed at 33-50 ms, persistence about every 1 s and on lifecycle boundaries, isolated from history rendering.
- Chat route with virtualized/windowed message list, stable keys, older-message pagination, scroll anchoring, and isolated streaming message.
- Message item with role layout, metadata, hover/focus actions, inline error card, and cancelled/interrupted states.
- Streaming presentation with placeholder, typing indicator, stop button, conditional auto-scroll, and live-region announcements for start/complete/error only.
- Composer with 1-8 line auto-grow, send-key modes, Shift+Enter newline, per-conversation drafts, send/stop, disabled states, and text preservation on failure.
- Safe Markdown rendering with GFM, headings, lists, tables, blockquotes, task lists, links, inline code, fenced code, Shiki highlighting, language label, and copy; no raw HTML; remote images blocked; incomplete fences render as code.
- Message actions: copy, copy code, regenerate last assistant, edit and resend last user with truncation confirmation, and retry failed request.

Acceptance criteria:

- Critical cases 5-10, 15-17, 21, and 28 have tests.
- Keep-alives do not break parsing; reasoning appears once; usage/cost is visible; partial text survives errors and cancellation.
- Streaming token updates do not re-render history.
- Markdown XSS cannot execute; remote images do not load; incomplete code fences render as code.

Verification:

```bash
make web-lint && make web-typecheck && make web-test && make web-build
# plus a manual send/stream/cancel/reload smoke against a controlled key
```

Out of scope: math, attachments, search, export/import, branching, and full Playwright E2E.

### 4.6 M1-05: Web conversations, persistence integration, and recovery

Goal: make chats durable, navigable, restart-safe, and recoverable.

Deliverables:

- Conversation creation, rename, delete with confirmation and undo toast where feasible.
- Date-grouped history: Today, Yesterday, Previous 7 days, Previous 30 days, Older.
- Title defaults from the first user message, truncated to 60 characters with shared normalization.
- Last-active conversation restore; new chat uses configured defaults; root redirect works.
- Persistence wiring through existing repositories, with model/provider snapshots on assistant messages.
- Startup recovery: any message left in `streaming` is marked `cancelled` with preserved partial content and an explanation.
- Minimal offline state: history readable, sending disabled.
- Tests for persistence across simulated reload, model switching, and recovery.

Acceptance criteria:

- Critical cases 18 and 21 have tests.
- Conversations and messages survive reload.
- A message left streaming is marked interrupted and keeps partial text.
- Deleting a conversation removes its messages and does not corrupt the list.
- History grouping matches the required buckets.

Verification:

```bash
make web-lint && make web-typecheck && make web-test && make web-build
```

Out of scope: search, pin, archive, export/import, and data controls.

### 4.7 M1-06: Android OpenRouter provider layer

Goal: make OpenRouter callable through a normalized Android adapter with no feature UI.

Deliverables:

- `core:network` OkHttp and `okhttp-sse` dependencies plus MockWebServer test support.
- Contract loading and registry from presets copied into a build-safe Android resource/asset location.
- SSE transport with CRLF normalization, comments, bounded buffering, final flush, and coroutine/OkHttp cancellation.
- OpenRouter `validateKey`, `listModels`, and `streamChat`.
- Catalog mapping, 24-hour cache, manual refresh, last-known-good snapshot, and the same mapping semantics as web.
- Attribution headers from app config; no secrets in URLs or logs.
- Timeouts, 120 s inactivity watchdog, retry policy, status mapping, in-stream error handling, reasoning deduplication, and usage/cost normalization.
- Curated routing and protected-field validation with the same request vectors as web.

Acceptance criteria:

- MockWebServer/fixture tests cover text, keep-alive, reasoning, usage, in-stream error, 402, 429, 503, cancellation, retry, and `Retry-After`.
- The adapter emits exactly the S-01 normalized events.
- Catalog tests cover the same OpenRouter fixture cases as web.
- Routing tests match the web request vectors.
- No feature UI is added.

Verification:

```bash
make android-lint && make android-test
```

Out of scope: feature screens, repositories, ViewModels, and Markdown.

### 4.8 M1-07: Android onboarding, provider management, and model management

Goal: deliver the OpenRouter-only Android setup and model-selection surfaces.

Deliverables:

- Welcome/onboarding flow with BYOK/on-device explanation, OpenRouter preselected and Recommended.
- Provider picker with OpenRouter enabled and other presets disabled or "coming later"; no custom creation.
- Key entry with optional label, Connect, inline testing, actionable errors, and preserved input.
- Public catalog load before key validation; sending remains disabled until `/key` succeeds.
- Provider management with key rotation/removal, enabled toggle, CORS status, and key details from `/key`.
- Model picker with search, grouping, pinned/hidden, capability chips, context, modalities, price, free badge, manual add, unavailable warnings, per-conversation override, and global defaults.
- Immediate model-switch persistence and assistant-message snapshots.
- Defaults and last-active settings wiring through the typed settings store.
- Minimal curated routing controls and a routing summary.

Acceptance criteria:

- Critical cases 1-4, 18, and routing/model parts of 20 have tests.
- Invalid-key errors preserve the input and do not accept the key.
- Other providers/custom creation are not usable flows.
- Model metadata and free badge render correctly.

Verification:

```bash
make android-lint && make android-test && make android-assemble
```

Out of scope: attachments, math, full parameter sheet, search, export/import, share target, and additional providers.

### 4.9 M1-08: Android repositories, conversations, and recovery

Goal: make Android conversations durable, navigable, and process-death safe.

Deliverables:

- Entity/domain repository mappers for conversations, messages, providers, models, and settings.
- Transactional message append plus conversation metadata update, with FTS text synchronization.
- Message status query for `streaming` recovery and startup recovery service.
- Hilt providers for the database, secret store, clock, HTTP client, repositories, registry, and app-scope services.
- Conversation list/drawer with new chat, rename, delete with confirmation, and required date groups.
- Title defaults matching web.
- Last-active restore, safe new chat, and navigation changes from the placeholder shell.
- Process-death recovery: mark streaming messages cancelled, preserve partial content, and show an explanation.
- Tests for repository mapping, cancel, reload, and recovery.

Acceptance criteria:

- Critical cases 18, 21, and Android process-death behavior have tests.
- Conversations and messages survive an activity restart.
- A streaming message is recovered as cancelled with partial content.
- Deleting a conversation removes its messages and FTS rows.
- The placeholder bottom-navigation start behavior no longer blocks the real flow.

Verification:

```bash
make android-lint && make android-test && make android-assemble
```

Out of scope: search, pin/archive, export/import, tablets, share target, and Compose screenshot tests.

### 4.10 M1-09: Android chat runner, chat screen, composer, Markdown/code, actions

Goal: deliver the end-to-end Android chat experience.

Deliverables:

- Chat ViewModel with immutable `UiState`, `StateFlow`, streaming reducer, debounced persistence, send lock, cancel, and regenerate.
- Chat screen with `LazyColumn`, stable keys and content types, older-message paging, isolated streaming message, and chat states.
- Composer with auto-grow, send-key behavior, drafts, send/stop, disabled states, and text preservation.
- Streaming presentation with placeholder, stop control, conditional auto-scroll, error card, cancelled state, and accessibility announcements for start/complete/error.
- Safe Markdown and code rendering with GFM, language label, copy, and remote images blocked; incomplete fences render as code.
- Message actions: copy, copy code, regenerate, edit last user with truncation confirmation, and retry.
- Model-switch integration and provider/model snapshot display.

Acceptance criteria:

- Critical cases 5-10, 15-17, 18, 21, and 28 have Android tests.
- Keep-alives do not break parsing; reasoning appears once; usage/cost is visible.
- Cancellation persists partial text and marks the message cancelled.
- Streaming does not recompose unrelated history items.
- Markdown XSS is inert and remote images do not load.

Verification:

```bash
make android-lint && make android-test && make android-assemble
# plus a manual send/stream/cancel/reload smoke on an emulator
```

Out of scope: math, attachments, search, export/import, branching, tablet layouts, and screenshot suites.

### 4.11 M1-10: Shared CORS verification, conformance evidence, parity, exit gate

Goal: prove the M1 exit gate and record what actually works.

Deliverables:

- Browser-direct CORS matrix for `/key`, `/models`, and `/chat/completions`, with preflight, status, and blocked/allowed conclusion.
- OpenRouter conformance checklist evidence from spec 7.4 on web and Android.
- Fixture conformance results for both clients and critical-case mapping for cases 1-10, 15-18, 20-21, and 28.
- Updated `contracts/providers.json` `browserCors` result and a truthful M1 parity status.
- Updated milestone status and `docs/operations/M1/M1-verification.md`.
- Manual two-minute onboarding-and-chat smoke record on web and Android, with keys redacted.

Acceptance criteria:

- `make contracts-check`, the web gate, and the Android gate pass.
- If browser CORS fails, the web release is explicitly blocked and the evidence says so; no proxy is added.
- The parity document reflects what works without claiming M2 features.
- Every exit-gate row in section 5 has evidence.
- No secrets, real prompts, or personal data appear in evidence.

Verification:

```bash
make contracts-check
make web-lint && make web-typecheck && make web-test && make web-build
make android-lint && make android-test && make android-assemble
# plus the CORS matrix and manual smoke records
```

Out of scope: M2 provider work, attachments, math, search, export/import, PWA, deployment, and Rust.

## 5. Exit-Gate Evidence Matrix

| Exit-gate requirement | Evidence to capture | Responsible tasks |
| --- | --- | --- |
| New user onboards and chats in under two minutes | Timed manual web and Android smoke record with screenshots of welcome, key entry, model selection, and first stream | M1-03, M1-04, M1-07, M1-09, M1-10 |
| Valid key passes `/key`; invalid key is actionable and not accepted | Adapter tests, component/ViewModel tests, and manual invalid-key check | M1-02, M1-03, M1-06, M1-07 |
| Catalog maps paid, free, vision, reasoning, long-context, and tool models; prices per million; free marked; unknown fields ignored | Fixture test output from both clients against `openrouter-models.json` | M1-02, M1-06, M1-10 |
| Streaming appends text; keep-alives and unknown fields do not break parsing | Fixture tests for `openrouter-text.sse` and `openrouter-keepalive.sse` plus manual stream | M1-02, M1-04, M1-06, M1-09 |
| Reasoning appears once and is visually distinct | Expected-vector test for `openrouter-reasoning.sse` and UI evidence | M1-01, M1-02, M1-04, M1-06, M1-09 |
| Final usage and cost persisted and visible | `openrouter-usage.sse` test, persisted-message assertion, UI screenshot | M1-02, M1-04, M1-06, M1-09 |
| 402, 429, 503, context-length, in-stream errors, and cancellation map correctly; retries honor `Retry-After` and stop after partial output | Error fixture tests, fake-timer retry tests, cancel tests, manual error evidence | M1-02, M1-06, M1-10 |
| Routing serializes correctly and protected fields cannot be overridden | Shared request vectors tested on both adapters plus UI routing evidence | M1-01, M1-02, M1-06, M1-10 |
| Conversations survive restart; model switching persists; messages keep snapshots | Repository/persistence tests and a manual reload check on both clients | M1-03, M1-05, M1-07, M1-08 |
| Browser-direct OpenRouter behavior is verified for all three endpoints or web release is blocked | CORS matrix with preflight/status/conclusion and updated `browserCors` | M1-10 |
| No OpenRouter regression in fixture tests; critical cases 1-10, 15-18, 20-21, 28 pass on both platforms | Test output mapped to critical-case IDs | M1-02..M1-09, M1-10 |
| `contracts/parity.md` reflects what actually works | Parity review with implementation-status evidence and no M2 overclaim | M1-10 |

Recommended evidence location: `docs/operations/M1/M1-verification.md`, written as tasks land and finalized by M1-10. Do not mark the milestone complete until this evidence exists and the status table is updated.

## 6. Execution Sequence

| Phase | Estimate | Work | Gate |
| --- | --- | --- | --- |
| 0. Preflight | 0.5 day | Confirm toolchains, decide contract packaging, default-model fallback, routing surface, parity-status format, and task-record location | Decisions recorded; no open blocker |
| 1. Shared conformance | 0.5-1 day | M1-01 expected vectors, request vectors, validator support, evidence skeleton | `make contracts-check` passes with new vectors |
| 2. Platform adapters | 2-3 days | M1-02 and M1-06 in parallel | Fixture conformance green on both adapters |
| 3. Setup and model surfaces | 2-3 days | M1-03 and M1-07 in parallel | Onboarding and model selection work; invalid-key path tested |
| 4. Chat and conversations | 4-5 days | M1-05, M1-04, M1-08, and M1-09 with web/Android parallel streams | End-to-end chat, persistence, cancel, reload recovery |
| 5. Evidence and exit | 1 day | M1-10 CORS matrix, conformance checklist, parity, manual smoke, final gates | M1 exit gate met or web explicitly blocked |

Single-implementer order: M1-01, M1-02, M1-06, M1-03, M1-07, M1-05, M1-08, M1-04, M1-09, M1-10. This avoids cross-platform context switching but extends the schedule.

Parallel order: M1-01 first; then one owner on web (M1-02 to M1-05) and one on Android (M1-06 to M1-09); the verification owner can draft the CORS matrix and evidence template early and finish M1-10 after both streams. M1-03/M1-05 and M1-07/M1-08 can overlap with coordination on navigation and settings ownership.

## 7. Risks and Mitigations

| Risk | Impact | Mitigation |
| --- | --- | --- |
| OpenRouter browser CORS blocks a required endpoint | Web release cannot ship | Verify `/key`, `/models`, and `/chat/completions` early; treat any blocker as a release stop; document Android plus future self-hosted proxy as the fallback; never add a hidden proxy |
| Catalog or pricing strings change | Misleading prices or broken mapping | Tolerant parsing, decimal-safe conversion, raw values in metadata, 24-hour cache, manual refresh, last-known-good, and fixture tests |
| Reasoning arrives through both fields | Duplicated reasoning UI | Shared expected vector plus explicit precedence and dedupe tests on both platforms |
| In-stream error after tokens | Retry duplicates output | Persist partial text, stop immediately, and forbid automatic retry after the first token |
| Streaming persistence causes jank or data loss | Poor UX or lost partial content | 33-50 ms UI flush, about 1 s persistence, lifecycle flushes, isolated streaming message |
| Contracts are copied or loaded differently per platform | Adapter drift | M1-01 shared vectors and one documented contract-delivery mechanism per client |
| Web scope expands into M2 | Missed two-week target | Attachments, math, search, export/import, full parameter sheet, and Playwright stay out of M1 |
| Android feature modules need many new dependencies | Build/config churn | Add dependencies during M1-06/07/09 in the version catalog and keep module dependency direction one-way |
| Markdown/code rendering is larger than estimated | Chat UI slips | Limit to GFM, tables, task lists, links, inline/fenced code, Shiki with a limited language set, and incomplete-fence handling; defer math |
| `defaultModelId` missing from catalog | Onboarding cannot select a model | Implement and test the documented fallback; show an unavailable notice for the preset default |
| Parity status is misrepresented | False exit-gate claim | Keep the requirement table intact; add evidence-backed implementation status; review every M1 row in M1-10 |
| Test evidence leaks a key | Security incident | Use disposable keys, redact screenshots and logs, never commit keys, and record only status/conclusion data |
| Android recovery is added too late | Process-death data loss | Add the streaming-status query and startup recovery in M1-08 before chat integration is finalized |
| Multi-tab streaming is assumed to work | Duplicate sends in web | Enforce one active stream per conversation per client instance; declare multi-tab coordination out of scope for M1 |

## 8. Decisions, Assumptions, and Open Questions

### 8.1 Decisions already made by the milestone

- OpenRouter is the only fully enabled provider in M1.
- The Rust client is out of scope; M3.5 owns the equivalent work.
- Attachments, math, search, pin, archive, export/import, and full parameter UI are M2.
- Browser CORS is a release blocker for web, not a problem to hide with a proxy.
- The adapter must normalize events, usage, finish reasons, and errors; UI and storage must not parse provider JSON.
- One active stream per conversation and a send lock are required.
- Critical cases 1-10, 15-18, 20-21, and 28 are the M1 conformance target.

### 8.2 Assumptions for this plan

- M1 task records will be generated before implementation begins.
- Expected normalized vectors belong in M1 even though M0 deferred them, because both clients need a shared conformance target.
- The public catalog can be loaded before key validation, but sending stays disabled until `/key` succeeds.
- `defaultModelId` may not exist in the live catalog; a deterministic fallback is required.
- The M1 Markdown renderer blocks remote images regardless of the future `loadRemoteImages` setting.
- Playwright and the full E2E suite are M2; M1 uses Vitest/MSW plus manual smoke evidence.
- Android Compose UI test expansion and screenshots remain M3; M1 uses unit, ViewModel, MockWebServer, and persistence tests.
- The existing Makefile command surface is sufficient; M1 should not add a second command surface.

### 8.3 Open questions to resolve before implementation

| Question | Proposed default | Needed by |
| --- | --- | --- |
| Where do M1 task records live? | Follow `docs/milestones/README.md`: `docs/tasks/M1/`; if reusing the M0 runner layout, use `docs/operations/M1/` and update the runner invocation | Task generation |
| How do web and Android consume contracts? | Web imports/copies non-secret contracts at build/test time; Android copies presets/fixtures into generated assets and test resources; do not duplicate provider metadata in source | M1-01 and adapters |
| Are expected normalized vectors part of M1? | Yes; required for cross-client conformance | M1-01 |
| What is the default-model fallback? | Use `defaultModelId` when present; otherwise first non-hidden text-output model in stable remote-ID order, with an unavailable notice for the preset default | M1-01/02/06 |
| Where do curated routing controls live? | OpenRouter provider details plus a routing summary and minimal per-conversation override; full parameter sheet remains M2 | M1-03/M1-07 |
| Is the advanced JSON editor in M1? | No; enforce protected-field rejection in the request builder and test it programmatically | M1-02/M1-06 |
| How is parity implementation status represented? | Keep the required table intact; add an evidence-backed status list or update the validator to support a second table deliberately | M1-10 |
| Is Playwright required in M1? | No; defer to M2 and use unit/component/manual evidence | M1-04/M1-09 |
| What happens if browser CORS fails? | Block the web release, record the matrix, and keep Android as the fallback; do not proxy | M1-10 |
| Should Android `loadRemoteImages` stay `true`? | No; M1 must block remote images regardless, and the default should be revisited when M2 exposes the setting | M1-08/M1-09 |
| How is "60 characters" measured? | First 60 Unicode code points after trimming and collapsing whitespace/newlines; implement identically on both platforms | M1-05/M1-08 |
| Is multi-tab streaming coordination required? | No; one active stream per conversation per client instance | M1-04 |

## 9. Definition of Done for This Plan

This plan is complete when:

1. M1-01 through M1-10 are implemented or explicitly re-scoped with written approval.
2. Every gap row in section 3 is resolved, or recorded as an accepted deferral with an owner and target milestone.
3. The M1 exit-gate evidence in section 5 exists in `docs/operations/M1/M1-verification.md`.
4. `contracts/providers.json` has the verified `browserCors` result, and a blocker is recorded as a release stop rather than worked around.
5. `contracts/parity.md` reflects the delivered M1 behavior truthfully and does not claim M2 features.
6. Critical cases 1-10, 15-18, 20-21, and 28 have passing evidence on both web and Android.
7. No additional provider adapter, attachment flow, math rendering, search, export/import, PWA, deployment, or Rust work was introduced.
8. The milestone status table and task records are updated.
