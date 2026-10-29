# M1 - OpenRouter-first core chat

- Status: Planned
- Target: 2 weeks
- Depends on: M0
- Blocks: M2, M3.5
- Platforms: web, android
- Spec: sections 3.1-3.5, 5.3-5.4, 6, 7.1-7.10, 8.4-8.5, 9.4-9.6, 10.4, 11.2-11.5, 11.7, 11.9-11.11, 12.2-12.4, 15.2 (cases 1-10, 15-18, 20-21, 28), 17 (M1), 21.1-21.3

## Objective

Deliver an OpenRouter-only end-to-end chat experience on web and Android. A new user must be able to paste an OpenRouter key, validate it, browse the public model catalog, pick a model, stream a response, and keep the conversation locally. OpenRouter is the release blocker, so its adapter, catalog mapping, streaming semantics, routing preferences, usage/cost, and error taxonomy must be correct before any other provider exists.

The Rust client is not part of M1. Its equivalent work is in M3.5.

## Entry criteria

- M0 exit gate is met: contracts validate, web and Android foundations build and launch, parity baseline exists.
- `contracts/providers.json` and the OpenRouter fixtures are available.
- Web IndexedDB/OPFS repositories and Android Room/DataStore repositories are stable enough to persist conversations and messages.

## Scope by workstream

### 1. Provider layer (web and Android)

Deliver on each platform:

- The `ProviderAdapter` contract from section 7.1 with `ProviderContext`, `ProviderKeyInfo`, `NormalizedChatRequest`, `StreamEvent`, `FinishReason`, and normalized `ChatError`.
- An SSE transport with CRLF normalization, multi-line `data:` fields, comments, bounded buffering, final-buffer flush, and abort/cancellation support. The transport must not parse provider JSON.
- A provider registry that loads `contracts/providers.json` or a generated equivalent. No provider metadata may be hard-coded in client source.
- The OpenRouter adapter:
  - `GET /key` validation mapping label, usage, limit, free-tier flag, and rate-limit metadata.
  - Public `GET /models` catalog with a 24-hour cache, manual refresh, and last-known-good snapshot on failure.
  - Catalog mapping: `remoteId`, canonical slug, label, context window, max output tokens, input/output modalities, pricing strings converted from USD-per-token to USD-per-million, supported parameters, free `:free` models, and unknown non-secret fields into metadata.
  - `POST /chat/completions` streaming with `stream_options.include_usage`, attribution headers, reasoning normalization and deduplication, keep-alive tolerance, in-stream error handling, and usage/cost persistence.
  - Curated routing controls in M1: `allow_fallbacks`, `sort` (`price`, `throughput`, `latency`), and `data_collection: "deny"`. Advanced JSON may not override `model`, `messages`, `stream`, or `stream_options`.
  - OpenRouter-specific UX metadata: recommended badge, key label/usage/limit/free tier/rate-limit note, price per million, modality icons, free badge, routing summary, and a provider-docs link.
- Retry policy from section 7.9: at most 3 attempts, jittered exponential backoff starting at 800 ms with an 8 s cap, honor `Retry-After`, retry only before the first token, and never retry after partial output.
- A `ChatRunner`/streaming service per platform that drives the adapter, emits normalized events, enforces one active stream per conversation and a send lock, and supports cancel and regenerate.

Browser CORS verification is a release blocker: verify `/key`, `/models`, and `/chat/completions` from a browser, record the result in `browserCors` and the milestone evidence, and treat a blocker as a release stop rather than silently proxying.

### 2. Onboarding and provider/model management (web and Android)

Deliver on each platform:

- Welcome/empty state explaining BYOK and on-device data, with OpenRouter preselected and marked Recommended.
- Provider picker showing OpenRouter as the only fully enabled provider in M1. Other presets may appear as disabled or "coming later"; do not expose the custom-provider creation flow until M2.
- Key entry with optional label, Connect, inline testing state, and actionable provider-specific errors that preserve the form.
- Load the public OpenRouter catalog before key validation so models are visible, but keep sending disabled until `GET /key` succeeds.
- Provider management: OpenRouter key rotation/removal, enabled toggle, CORS status, and key details (label, usage, limit, free-tier, rate-limit). Adding and editing non-OpenRouter or custom providers belongs to M2.
- Model picker and Manage models: search, provider groups, pinned/hidden models, capability chips, context window, modalities, price per million, free badge, manual model add, unavailable warnings, per-conversation override, and global defaults.
- Model switching mid-conversation persists immediately and applies to the next send. Assistant messages snapshot provider ID, model ID, and model label.

### 3. Chat experience (web and Android)

Deliver on each platform:

- Chat route/screen wired to the M0 shell and the new streaming service.
- Virtualized/windowed message list with stable keys, pagination of older messages, scroll anchoring, and an isolated streaming message so token updates do not re-render history.
- Message item with role layout, metadata, action row on hover/focus, inline error card, and cancelled/interrupted states.
- Streaming presentation: assistant placeholder, typing indicator, stop button, auto-scroll only when near the bottom, and live-region announcements for start, complete, and error, not every token.
- Composer: auto-grow 1-8 lines, Enter vs Mod+Enter based on settings, Shift+Enter newline, per-conversation drafts, send/stop, disabled states for empty/streaming/offline, and text preservation on send failure. Attachments controls are deferred to M2.
- Markdown rendering in M1: GFM, headings, lists, tables, blockquotes, task lists, links, inline code, and fenced code. Shiki or equivalent highlighting with language label and copy. Incomplete fenced code during streaming must render as code. Do not render raw HTML. Remote images remain blocked by default; the user-facing remote-image setting is delivered in M2.
- Math rendering is deferred to M2.
- Message actions: copy message, copy code, regenerate last assistant response, edit last user message with truncation confirmation, and retry a failed request. No branching in phase 1.
- Chat states: no provider configured, empty conversation, sending, streaming, complete, error, and model missing/unavailable. Offline and context-meter states may be minimal in M1 and are completed later.

### 4. Conversation basics (web and Android)

Deliver on each platform:

- Local persistence of conversations and messages through M0 repositories.
- New chat, rename, delete with confirmation (undo toast where feasible), and date-grouped history: Today, Yesterday, Previous 7 days, Previous 30 days, Older.
- Title defaults to the first user message truncated to 60 characters.
- Last-active conversation restore and safe new-chat behavior.
- Startup/process-death recovery for messages left in `streaming`: mark them interrupted/cancelled, preserve partial content, and explain what happened.
- Basic global defaults: default provider, default model, and last active conversation. Appearance settings already exist from M0.

Search, pin, archive, export/import, and per-conversation parameters are M2.

### 5. Testing and evidence

- Web: Vitest/MSW tests for catalog mapping, key validation, keep-alives, reasoning deduplication, usage/cost, `402`/`429`/`503`, in-stream errors, cancellation, retry, routing serialization, protected fields, and model switching. A small Playwright smoke suite may be added here, but the full E2E suite is M2.
- Android: JUnit/MockWebServer tests for the same OpenRouter fixture paths, plus chat/persistence tests for cancel and reload recovery.
- Critical cases covered in M1: 1-10, 15-18, 20-21, and 28 from spec section 15.2. Case 19 (provider switch warns about unsupported attachments) is completed in M2 when attachments exist.
- Record the browser-direct CORS result and the OpenRouter conformance checklist (section 7.4) as milestone evidence.

## Out of scope

- Additional providers and the generalized OpenAI-compatible transport (M2).
- Attachments, image downscaling, capability warnings, and math rendering (M2).
- Per-conversation system prompt and sampling parameters UI (M2); the domain fields already exist.
- Search, pin, archive, export/import, data controls, and full settings routes (M2).
- Android share target, photo/document pickers, tablet/foldable layouts, screenshot tests, release signing, and deep device hardening (M3).
- PWA, service worker, deployment, and final accessibility/performance audits (M4).
- Rust client (M3.5).
- Backup/sync and managed hosting (M5, M6).

## Exit gate

- On both web and Android, a new user can launch the app, add an OpenRouter key, load models, select a model, and chat end-to-end in under two minutes.
- A valid key passes `/key`; an invalid key shows an actionable auth error, preserves the form, and is not accepted.
- The catalog maps paid, free, vision, reasoning, long-context, and tool-capable models; prices display per million tokens; free models are marked; unknown fields do not break mapping.
- Streaming appends text in order; `: OPENROUTER PROCESSING` keep-alives and unknown fields do not break parsing.
- Reasoning appears once, not duplicated, and is visually distinct from answer text.
- Final usage and cost are persisted and visible.
- `402`, `429`, `503`, context-length, in-stream errors, and cancellation map correctly; retries honor `Retry-After` and stop after partial output.
- Routing preferences serialize correctly and protected request fields cannot be overridden.
- Conversations survive restart; model switching persists; messages retain provider/model snapshots.
- Browser-direct OpenRouter behavior is verified for all three endpoints, or the web release is explicitly blocked.
- No OpenRouter regression in fixture tests; critical cases 1-10, 15-18, 20-21, and 28 have passing evidence on both platforms.
- `contracts/parity.md` reflects what actually works.

## Verification

```bash
make contracts-check
make web-lint && make web-typecheck && make web-test && make web-build
make android-lint && make android-test && make android-assemble
```

Plus the browser CORS matrix and fixture conformance evidence recorded in the milestone report.

## Task-generation guidance

Expect 6-10 tasks, split strictly by platform. Suggested slices:

1. Web OpenRouter adapter, SSE transport, registry, catalog cache, and fixture tests.
2. Web onboarding, provider management, and model picker/defaults.
3. Web chat runner, peer streaming state, chat screen, composer, markdown/code, and message actions.
4. Web conversation basics, persistence wiring, and reload recovery.
5. Android OpenRouter adapter, SSE parser, OkHttp/cancellation, registry, and fixture tests.
6. Android onboarding, provider management, and model picker/defaults.
7. Android chat runner/ViewModel, chat screen, composer, markdown/code, and message actions.
8. Android conversation basics, persistence wiring, and process/reload recovery.
9. Shared CORS verification and OpenRouter conformance evidence (research/verification task; may not own client source).

Do not combine web and Android adapter work into one task. Do not start M2 adapters or attachments inside M1 tasks.

## Risks and decisions to confirm

- OpenRouter browser CORS can block the entire web release. Verify early; the fallback is Android plus a documented future self-hosted proxy, not a hidden proxy.
- OpenRouter catalog/pricing strings change frequently. Tolerant parsing, 24-hour cache, manual refresh, and last-known-good snapshots are mandatory.
- Reasoning may arrive through both `reasoning` and `reasoning_details`; deduplication rules need fixture-backed tests.
- In-stream errors inside HTTP 200 must preserve partial text and never auto-retry after output.
- Streaming persistence frequency is a performance/correctness tradeoff. Follow the 33-50 ms UI flush and roughly 1 s persistence guidance.
- IndexedDB and Room write patterns must not regress with large conversations; keep the streaming message isolated.
