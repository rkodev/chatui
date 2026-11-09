# ChatUI Feature Parity

- Source: [IMPLEMENTATION.md section 5.4](../docs/IMPLEMENTATION.md#54-parity-checklist)
- Updated: 2026-11-10
- Purpose: authoritative cross-client requirement and commitment table.

| Feature | Web | Android | Rust |
| --- | --- | --- | --- |
| BYOK onboarding | Required | Required | Required when enabled |
| OpenRouter first-run onboarding | Required (release blocker) | Required (release blocker) | Required when enabled |
| OpenRouter key introspection (`/key`) | Required | Required | Required when enabled |
| OpenRouter catalog, pricing, modalities | Required | Required | Required when enabled |
| OpenRouter streaming, reasoning, usage/cost | Required | Required | Required when enabled |
| OpenRouter provider routing preferences | Required | Required | Required when enabled |
| OpenAI-compatible streaming | Required | Required | Required when enabled |
| Anthropic streaming | Required | Required | Required when enabled |
| Gemini streaming | Required | Required | Required when enabled |
| Ollama local | Required | Required | Required when enabled |
| Model discovery and switching | Required | Required | Required when enabled |
| Conversation persistence | IndexedDB + OPFS | Room + files | SQLite + files |
| Search | Required | Required | Required when enabled |
| Markdown + code + math | Required | Required | Required when enabled |
| Attachments | Required | Required | Required when enabled |
| Export/import | Required | Required | Required when enabled |
| Backup/sync | Future | Future | Future |
| PWA install | Required | N/A | N/A |
| Native share target | N/A | Required | Required on desktop |
| Desktop packaging (installer/AppImage) | N/A | N/A | Required when enabled |
| Auto-update | N/A | N/A | Required when enabled |

## Notes

- OpenRouter is the first release blocker. These rows are release-blocking on every published client: OpenRouter first-run onboarding, key introspection, catalog/pricing/modalities, streaming/reasoning/usage/cost, and provider routing preferences.
- The Rust column may be `Optional` until the Rust desktop client is enabled for release. Once enabled and published, Rust must meet the same contracts, conformance, parity, and security bar as web and Android. The Rust client is desktop-only for Windows, macOS, and Linux; it is not an Android, iOS, or WASM client.
- Current Rust status (2026-11-08): `Optional` and deferred. The M3.5 preview implementation is not enabled for release; no Rust row is marked complete or required. See [Implementation status (M3.5)](#implementation-status-m35-2026-11-08).
- No feature is complete at M0. Foundation-level persistence, routing placeholders, or design tokens do not satisfy a feature row.
- This table records requirement and platform commitment, not current implementation status. Update it in the same change as any user-visible behavior change.
- Allowed platform values are `Required`, `Required (release blocker)`, `Required when enabled`, `Required on desktop`, `Future`, `N/A`, and the platform-specific persistence descriptions shown above.
- Release is blocked if a feature is marked complete on one platform and absent on another without an approved exception recorded here.

## Implementation status (M1, 2026-11-04)

M1 delivers OpenRouter-only BYOK chat on web and Android. This section records
what is implemented; the commitment table above is unchanged.

Implemented on both clients:

- OpenRouter first-run onboarding with public catalog preload, `/key`
  validation, invalid-key rollback, and default model selection.
- OpenRouter key introspection (`/key`) and provider metadata: label, usage,
  limit, free tier, rate limit, and CORS status.
- Public `/models` catalog with paid, free, vision, reasoning, tool, and
  long-context mapping; per-million pricing; 24-hour cache; manual refresh; and
  last-known-good snapshot.
- Streaming `/chat/completions` with keep-alive tolerance, reasoning
  normalization and deduplication, usage/cost persistence, in-stream error
  handling, retries with `Retry-After`, and cancellation.
- Curated routing (`allow_fallbacks`, `sort`, `data_collection`) and protected
  request-field rejection.
- Conversation persistence, last-active restore, startup recovery for
  interrupted streams, and model switching with provider/model snapshots.
- GFM Markdown, fenced code with language label and copy, raw-HTML inertness,
  blocked remote images, and copy/regenerate/edit-last-user/retry actions.
- Browser-direct CORS verified for `/key`, `/models`, and `/chat/completions`;
  OpenRouter `browserCors` is `supported`.

Not delivered in M1 (M2 or later):

- Additional providers, custom-provider creation, attachments, math rendering,
  search, pin/archive, export/import, backup/sync, PWA, Rust client, and release
  packaging.
- The remote-image loading setting and per-conversation system-prompt/sampling
  parameter UI remain M2.

## Implementation status (M2, 2026-11-06)

M2 generalizes the M1 OpenRouter path to the launch provider set and adds the
content, parameter, attachment, conversation-management, and data surfaces. The
commitment table above is unchanged; this section records what is implemented.

Implemented on both clients:

- OpenAI-compatible transport parameterized by preset: OpenRouter, OpenAI,
  Groq, Mistral, DeepSeek, xAI, Together, Fireworks, LM Studio, llama.cpp, and
  Custom. Custom providers store base URL, auth style, and non-secret headers;
  keys stay in the secret store.
- Native Anthropic, Google Gemini, and Ollama adapters. Anthropic sends
  `x-api-key` and `anthropic-version` (the browser-only
  `anthropic-dangerous-direct-browser-access` header is web-only); Gemini uses
  `x-goog-api-key` and `alt=sse`; Ollama uses native `/api/chat` NDJSON and
  `/api/tags` discovery with an OpenAI-compatible fallback.
- Per-preset discovery, fallback model lists, capability inference, and
  non-blocking degraded-discovery notices. OpenRouter keeps its M1 catalog,
  routing, reasoning, usage/cost, and error behavior.
- Reasoning is displayed separately from answer text across providers.
- Markdown, fenced code with language label, copy, and wrap/scroll toggle, and
  math. Web renders math with a lazy-loaded KaTeX renderer and raw-source
  fallback; Android renders standalone math blocks as bounded raw source (no
  typesetting library) and keeps inline math as raw text. Raw HTML is inert,
  links are sanitized, and remote images are blocked until the setting or a
  per-message action allows them.
- Per-conversation system prompt, temperature, top P, max output tokens, stop
  sequences, reasoning effort, streaming toggle, and provider extras under
  `params.extra[<presetId>]`. Unsupported values stay stored and show an inline
  note; `model`, `messages`, `stream`, and `stream_options` are protected.
- Attachments: PNG/JPEG/WebP/GIF images and text files, local content-addressed
  storage, 2048 px longest-edge downscale with metadata stripping, composer
  chips, capability warnings and confirmation, and provider-specific content
  parts. Web supports picker, drag-and-drop, and clipboard paste; Android
  supports photo picker, SAF document picker, clipboard image paste, and
  drag-and-drop.
- Search, pin, archive, rename, and delete with a 5-second undo on both
  clients. Web uses a bounded cursor scan; Android uses SQLite FTS4.
- Export/import: `chatui.export` JSON, per-conversation JSON, and Markdown;
  schema validation; merge-by-default with optional replace; IDs, ordering,
  model snapshots, and attachment references preserved; secrets excluded;
  attachment bytes opt-in; invalid imports do not mutate local data.
- Data controls: storage usage, clear-all with typed confirmation, per-key
  deletion, export/import entry points, and a backup placeholder.
- Settings: web routes for appearance, chat defaults, providers/models, data,
  backup, and about/privacy; Android screens for appearance, chat defaults,
  providers, data, backup, and about/privacy. Send key, token usage, remote
  images, default system prompt/parameters, remember-keys, and last-active
  conversation are wired to behavior.

Not delivered in M2 (later milestones or recorded deferrals):

- PWA install, service worker, deployment, and release packaging (M4).
- Android share target, deep links, tablet/foldable permanent sidebar, Compose
  UI test suite, Roborazzi screenshots, baseline profiles, release signing, and
  the final device matrix (M3).
- Rust desktop client (M3.5), backup/sync (M5), and managed hosting (M6).
- Android math typesetting: standalone math blocks render as raw source, the
  documented safe fallback; inline math remains raw text.
- Android local Ollama over cleartext HTTP needed a network security config that
  was not yet in the app manifest. Closed in M3; see the M3 status below.
- Android Anthropic and Gemini model discovery ran without a key and fell back
  to the shared fallback model lists. Closed in M3; see the M3 status below.
- Web case 23 (10,000-message windowing) has a manual code check, not an
  automated web test; Android has an automated equivalent.
- Live provider smoke and valid-key success for every cloud preset were not
  re-run; CORS evidence proves browser readability with placeholder
  credentials. Local and Custom CORS remain `unknown`.

## Implementation status (M3, 2026-11-10)

M3 adds Android platform depth on top of the M2 baseline. The commitment table
above is unchanged. The M3 exit gate is met as of 2026-11-10; this section
records implemented behavior, evidence, and known limitations.

Implemented on Android:

- Share target intake for `ACTION_SEND` text, creating a new conversation with
  the shared text as a draft while preserving any existing draft.
- `chatui://chat/<uuidv7>` deep-link parsing and validation, cold-start and warm
  delivery to a running app, and a not-found state for a missing conversation.
  The cold-start graph-timing defect (B1) is fixed and re-verified on all six
  device-matrix targets.
- Provider documentation opens in Chrome Custom Tabs.
- Adaptive shell: permanent sidebar at >= 840 dp, modal drawer below, IME and
  safe-drawing insets, predictive-back ordering, and hardware keyboard
  shortcuts.
- Lifecycle and drafts: configuration-change stream survival, persistent
  drafts, last-active conversation restore, process-death recovery (case 26),
  startup orphan-attachment cleanup, and documented background behavior.
- Platform security: Android Keystore ciphertext-only proof, backup exclusion
  (case 27), `FLAG_SECURE` on key entry and key detail, network security config
  with a central cleartext allowlist, and the local-cleartext warning/refusal.
- Tests and release configuration: Compose flow tests, Roborazzi goldens,
  large-conversation bounded composition, edge-case regressions, R8
  minification/keep rules, signing/version policy, baseline profile, CI
  artifacts, and bounded image decode.

M2 deferrals closed in M3:

- Android local Ollama cleartext: closed by the network security config,
  cleartext policy, and device check.
- Android Anthropic and Gemini discovery: closed; non-public catalogs now use
  the stored provider key, while OpenRouter and local presets stay keyless.
- Android math typesetting: accepted as the documented raw-source fallback and
  covered by tests.
- Android device input: photo picker and SAF pass on the device matrix;
  clipboard image paste (B2) and drag-and-drop (G1) remain open verification
  items.

Known limitations and open items:

- **B2 (high):** clipboard image paste is URI-only; a bitmap clip from Chrome
  is not attached.
- **G1 (verification gap):** cross-app drag-and-drop was not exercised because
  the headless emulator cannot inject a `ClipData` drag.
- All device evidence is emulator evidence; no physical phone, tablet, or
  ChromeOS hardware was available.
- The signed release artifact is not produced in CI because signing secrets are
  unavailable; local unsigned release APK/AAB builds pass.
- Math typesetting remains the raw-source fallback, and predictive-back
  animation was not visually confirmed on the headless emulator.

Evidence: [M3-verification.md](../docs/operations/M3/M3-verification.md)
sections 9-24, including cases 26 and 27, the device matrix, accessibility,
performance, screenshots, and the final gate record.

## Implementation status (M3.5, 2026-11-08)

The Rust desktop client is **`Optional` and deferred** for the current release.
The Product/release owner recorded `defer` in
[M3.5-decisions.md](../docs/operations/M3.5/M3.5-decisions.md) (DEC-01). The
commitment table above is unchanged: Rust rows remain `Required when enabled`,
and no Rust row is marked complete or required. This section records the
preview implementation and its evidence; it is not a release claim.

Preview implementation delivered (not released):

- Cargo workspace with domain, contracts, providers, storage, secrets, UI, and
  desktop app crates; Slint UI shell; no WebView.
- Contracts consumed from `contracts/` with `include_str!`; no duplicated
  provider presets.
- SQLite storage with migrations, FTS5 search, attachments, startup recovery,
  and export/import.
- OS credential-store integration plus explicit encrypted-file fallback,
  session-only mode, redaction, and zeroization.
- OpenRouter and OpenAI-compatible, Anthropic, Gemini, and Ollama adapters;
  bounded SSE/NDJSON parsers; retry, cancellation, and watchdog behavior.
- Onboarding, provider/model management, chat, composer, Markdown, search,
  settings, data controls, desktop integration, and accessibility primitives.
- Packaging for Linux `.deb`/`.tar.gz`, configured macOS `.app`/`.dmg` and
  Windows `.zip`; signed update-manifest verification with pinned keys.
- Rust CI matrix and tag release workflow; conformance suite and evidence.

Evidence: [M3.5-verification.md](../docs/operations/M3.5/M3.5-verification.md),
[conformance.md](../docs/operations/M3.5/conformance.md),
[packaging.md](../docs/operations/M3.5/packaging.md),
[update-signing.md](../docs/operations/M3.5/update-signing.md),
[ci.md](../docs/operations/M3.5/ci.md),
[performance.md](../docs/operations/M3.5/performance.md),
[security.md](../docs/operations/M3.5/security.md), and
[accessibility.md](../docs/operations/M3.5/accessibility.md).

Not claimed or blocked:

- No signed macOS or Windows artifact; no notarization (B-01 through B-04).
- No production update-signing key; the pinned keys are development keys (B-05).
- No macOS or Windows OS smoke or process-death run (B-01, B-02).
- No Linux screen-reader pass; Orca is blocked in this environment.
- No live provider smoke; conformance uses shared fixtures and mocks.
- Section 15.2 cases 18 and 28 remain blocked (C-03, C-04).
- `make rust-deny` and `make rust-audit` remain blocked by pre-existing
  supply-chain issues (C-01, C-02).

M4 does not depend on Rust while this decision stands. If the owner later
records `proceed`, every required Rust row must meet the web/Android bar and the
full section 10.12 release gate applies.
