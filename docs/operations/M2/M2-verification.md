# M2 Verification Evidence

- Status: Final exit-gate evidence recorded 2026-11-06; M2 gate passed.
- Owner: M2-09.1 recorded the command and CORS evidence; M2-09.2 finalized the parity and exit gate.
- Scope: web and Android M2 provider, content, data, and CORS evidence.
- Source plan: [M2 Providers and Content Implementation Plan](../M2-providers-and-content-implementation-plan.md)
- Authoritative spec: [IMPLEMENTATION.md](../../IMPLEMENTATION.md)
- Detailed CORS matrix: [M2-cors-matrix.md](M2-cors-matrix.md)

This file is the shared evidence location for the M2 exit gate. Do not mark a
row passed until the command or manual check has actually run. Record only
status codes, headers, timestamps, test names, and conclusions. Never record an
API key, a real prompt, a real conversation, or personal data.

## 1. Verification environment

- Date: 2026-11-06.
- Host: Linux x86_64.
- Node.js 24.16.0, pnpm 11.7.0.
- JDK 21.0.2+13, Gradle 9.8.1.
- Playwright Chromium 1248 (headless shell), installed for this run.
- Web unit tests run in jsdom; Playwright runs the production bundle through
  `vite preview` on `http://127.0.0.1:4173`.
- Android tests are JVM/Robolectric unit tests. No device or emulator was used.

## 2. Command results

Commands ran from the repository root on 2026-11-06 unless noted.

| Command | Result | Bounded evidence |
| --- | --- | --- |
| `make contracts-check` | Pass | `Contract validation passed.` |
| `make web-lint` | Pass | `eslint .` completed with no findings. |
| `make web-typecheck` | Pass | `tsc -b` completed with no errors. |
| `make web-test` | Pass | 95 test files, 852 tests, 0 failures. |
| `make web-build` | Pass | Vite build completed; only the existing chunk-size warning was emitted. |
| `cd web && pnpm exec playwright test` | Pass | 15 tests passed in 15.8 s. |
| `make android-lint` | Pass | `BUILD SUCCESSFUL in 1m 10s`. |
| `make android-test` | Pass | `BUILD SUCCESSFUL in 44s`; 781 tests, 0 failures, 0 errors, 0 skipped. |
| `make android-assemble` | Pass | `BUILD SUCCESSFUL in 3s`; debug APK produced. |

The first Playwright attempt failed because Chromium 1248 was not installed.
`pnpm exec playwright install chromium` installed the matching browser, and the
suite then passed. This is an environment setup step, not a product failure.

## 3. Fixture conformance

Shared expected vectors live in `contracts/fixtures/expected/`; raw streams,
catalogs, errors, and request vectors live under `contracts/fixtures/`. Both
clients consume the same files.

| Fixture family | Shared files | Web evidence | Android evidence |
| --- | --- | --- | --- |
| OpenAI-compatible streams | `openai-text.sse` / `openai-text.json`, `openai-reasoning.sse` / `openai-reasoning.json` | `web/src/providers/openaiCompatible.test.ts` streaming fixture cases | `OpenAICompatibleTransportTest` text and reasoning cases; `ProviderConformanceTest` case 11 |
| Anthropic streams | `anthropic-text.sse` / `anthropic-text.json`, `anthropic-thinking.json` | `web/src/providers/anthropic.test.ts` text and thinking cases | `AnthropicAdapterTest` text and thinking cases; `ProviderConformanceTest` case 12 |
| Gemini streams and finish reasons | `gemini-text.sse` / `gemini-text.json`, `gemini-finish-reasons.json` | `web/src/providers/gemini.test.ts` stream and finish-reason cases | `GeminiAdapterTest` stream and finish-reason cases; `ProviderConformanceTest` case 13 |
| Ollama NDJSON | `ollama-ndjson.txt` / `ollama-ndjson.json` | `web/src/providers/ollama.test.ts` NDJSON reader and stream cases | `OllamaAdapterTest` NDJSON cases; `ProviderConformanceTest` case 14 |
| OpenRouter streams | `openrouter-text`, `openrouter-keepalive`, `openrouter-reasoning`, `openrouter-usage`, `openrouter-error-200` | `web/src/providers/openrouter.test.ts` and `web/src/providers/sse.test.ts` | `OpenRouterStreamingTest` and `SseTransportTest` |
| Catalogs and capability inference | `catalogs.json`, `capability-inference.json`, `fallback-models.json`, raw model fixtures | `openrouterCatalog.test.ts`, `openaiCatalog.test.ts`, `fallbackModels.test.ts`, adapter catalog cases | `OpenRouterCatalogTest`, `OpenAICatalogTest`, `FallbackModelsTest`, adapter catalog cases |
| Errors | `openrouter-errors.json`, `provider-errors.json`, raw HTTP and in-stream error fixtures | Adapter error-mapping cases in `openrouter.test.ts`, `openaiCompatible.test.ts`, `anthropic.test.ts`, `gemini.test.ts`, `ollama.test.ts` | `OpenRouterStreamingTest`, `OpenAICompatibleTransportTest`, `AnthropicAdapterTest`, `GeminiAdapterTest`, `OllamaAdapterTest` |
| Request vectors | `openai-*`, `anthropic-*`, `gemini-*`, `ollama-*`, `openrouter-*` basic, extras, attachments, and protected vectors | Adapter request-serialization and protected-field cases | Adapter request-serialization and protected-field cases; `ProviderConformanceTest` |

All fixture rows above passed in the full web and Android test runs recorded in
section 2. No fixture was skipped or marked pending.

## 4. CORS matrix

The detailed preflight and browser-readability evidence is in
[M2-cors-matrix.md](M2-cors-matrix.md). The final `browserCors` values in
`contracts/providers.json` are:

| Preset | `browserCors` | Evidence summary |
| --- | --- | --- |
| openrouter | `supported` | `/key`, `/models`, and `/chat/completions` preflight and readable browser fetch from `http://localhost:5173`; placeholder credential only. |
| openai | `supported` | `/models` and `/chat/completions` preflight and readable browser fetch; placeholder credential only. |
| anthropic | `supported` | `/models` and `/messages` preflight and readable browser fetch; `anthropic-dangerous-direct-browser-access` accepted. |
| google | `supported` | `/models` and `streamGenerateContent` preflight and readable browser fetch; `x-goog-api-key` accepted. |
| groq | `supported` | `/models` and `/chat/completions` preflight and readable browser fetch. |
| mistral | `supported` | `/models` and `/chat/completions` preflight and readable browser fetch. |
| deepseek | `supported` | `/models` and `/chat/completions` preflight and readable browser fetch. |
| xai | `supported` | `/models` and `/chat/completions` preflight and readable browser fetch. |
| together | `supported` | `/models` and `/chat/completions` preflight and readable browser fetch. |
| fireworks | `supported` | `/models` and `/chat/completions` preflight and readable browser fetch. |
| ollama | `unknown` | No local Ollama server was running. Users must set `OLLAMA_ORIGINS` or use Android. |
| lmstudio | `unknown` | No local LM Studio server was running. Users must enable CORS or use Android. |
| llamacpp | `unknown` | No local llama.cpp server was running. Users must enable CORS or use Android. |
| custom | `unknown` | The contract base URL is a placeholder. Verify per user configuration with the browser diagnostic. |

No cloud preset is marked `unsupported`. No proxy was added. The local and
custom presets remain `unknown` because no real server was available; they are
not presented as verified.

## 5. Critical cases 11-14, 19, and 22-25

| Case | Description | Web evidence | Android evidence |
| --- | --- | --- | --- |
| 11 | OpenAI-compatible streaming appends text in order and finalizes usage | `openaiCompatible.test.ts` "emits the expected event order for openai-text" against `openai-text.sse` / `openai-text.json` | `ProviderConformanceTest.case 11 openai-compatible chunked SSE appends text in order and finalizes usage`; `OpenAICompatibleTransportTest.openai text stream matches the shared expected events` |
| 12 | Anthropic `thinking` and `text` deltas are separated correctly | `anthropic.test.ts` "keeps thinking deltas separate from answer text" and the Anthropic text fixture case | `ProviderConformanceTest.case 12 anthropic chunked SSE separates thinking and text deltas`; `AnthropicAdapterTest` thinking cases |
| 13 | Gemini `MAX_TOKENS` maps to `length` | `gemini.test.ts` "maps MAX_TOKENS to length" from `gemini-finish-reasons.json`; Gemini stream fixture case | `ProviderConformanceTest.case 13 gemini MAX_TOKENS maps to length`; `GeminiAdapterTest` finish-reason cases |
| 14 | Ollama NDJSON completes and usage is recorded | `ollama.test.ts` "emits text, finish, and usage from the native NDJSON fixture"; NDJSON reader cases | `ProviderConformanceTest.case 14 ollama chunked NDJSON completes and records usage`; `OllamaAdapterTest.emits text finish and usage from the native NDJSON fixture` |
| 19 | Switching provider warns about unsupported attachments | `Composer.test.tsx` "warns and confirms before sending unsupported image attachments". The warning is derived from the current model's attachment support, so a provider/model switch that removes support triggers the same dialog. Manual check of `Composer.tsx` confirms the availability-driven path. | `AttachmentSupportTest.text-only models reject images but accept text files`; `ChatViewModelTest.unsupported attachments require confirmation before sending`; `ChatScreenTest.unsupported attachment warning and confirmation are shown`; `ChatComposerTest.attachment warning and error are rendered` |
| 22 | Markdown XSS payloads are rendered inert | `MarkdownRenderer.test.tsx` "renders raw HTML as inert text", "allows only http, https, mailto, and in-app anchors", "does not load data or script image sources", and "does not link unsafe image sources" | `ChatMarkdownTest.renders raw html as inert text`, `renders unsafe links as inert text`, `does not offer a load action for unsafe image sources` |
| 23 | A 10,000-message conversation scrolls without rendering all messages | Manual check: `MessageList.tsx` renders `visibleMessages.slice(start, end)` from `useWindowedList`, which returns only the visible window when the viewport height is measurable. No automated web test covers 10,000 messages; jsdom has no measurable viewport, so the hook intentionally renders all rows there. | `ChatMessageListTest.ten thousand message history composes only visible rows` (asserts fewer than 100 rows compose and the newest message is visible) |
| 24 | Export/import preserves IDs, ordering, model snapshots, and attachments | `exportService.test.ts` "preserves IDs, message order, snapshots, and attachment references"; `importService.test.ts` "imports a valid archive and round-trips IDs, order, snapshots, and attachments"; Playwright `data.spec.ts` "exports and imports an archive while preserving ids, order, and model snapshots" | `ExportServiceTest.IDs order snapshots and attachment references are preserved`; `ImportServiceTest.ids order snapshots and attachment references are preserved`; `ImportServiceTest.attachment metadata and bytes are restored` |
| 25 | Clearing data removes conversations, keys, and attachments | `dataControlsService.test.ts` "clears conversations, messages, attachments, providers, models, keys, and bytes"; Playwright `data.spec.ts` "clears conversations, messages, providers, models, and stored keys" | `DataControlsServiceTest.clear all removes conversations messages attachments keys and bytes` |

Case 23 is the only row without an automated web test. The implementation is
present and the Android test covers the same behavior; the web gap is recorded
as a residual risk rather than claimed as a passing automated test.

## 6. M1 regression evidence

The full M1 suites reran unchanged in the M2 command runs.

| M1 area | Web evidence | Android evidence |
| --- | --- | --- |
| Onboarding, invalid key, valid key, default model | `web/e2e/onboarding.spec.ts` first-run, invalid-key, and valid-key cases; `openrouterCatalog.test.ts` key validation | `OnboardingViewModelTest`, `OnboardingScreenTest`, `DefaultOnboardingServiceTest`, `OpenRouterAdapterTest` |
| OpenRouter catalog, pricing, modalities, capabilities | `openrouterCatalog.test.ts` mapping and pricing cases | `OpenRouterCatalogTest` |
| OpenRouter streaming, keep-alives, reasoning, usage | `openrouter.test.ts` stream fixtures; `sse.test.ts` keep-alive cases | `OpenRouterStreamingTest`; `SseTransportTest` |
| OpenRouter errors, retries, `Retry-After`, cancellation | `openrouter.test.ts` error, retry, and cancellation cases | `OpenRouterStreamingTest`; `RetryPolicyTest`; `SseTransportTest` |
| OpenRouter request vectors and protected fields | `openrouter.test.ts` serialization and protected-field cases | `OpenRouterStreamingTest` request and protected-field cases |
| Conversation persistence, model switching, recovery | Web persistence, model-switch, and recovery tests; Playwright reload and model-switch cases | `ConversationServiceTest`, `ModelPickerViewModelTest`, `StreamingRecoveryServiceTest`, `InterruptedMessageNoticeTest` |

M1 critical cases 1-10, 15-18, 20-21, and 28 remain covered by the suites listed
in [M1 verification](../M1/M1-verification.md). The M2 runs added no failures in
those suites.

## 7. Manual smoke notes

- No live provider smoke was run for M2-09.1 because no disposable provider keys
  were available. The CORS matrix used fixed placeholder credentials and a
  fixed probe payload; it proves browser readability, not valid-key success.
- The web Playwright suite uses the deterministic mock provider in
  `web/e2e/fixtures/mockProvider.ts`. It does not contact a live provider and
  uses a fake key only.
- Android evidence is JVM/Robolectric unit and ViewModel coverage. Device-level
  photo picker, SAF document picker, clipboard image paste, drag-and-drop, and
  visual attachment/data screens remain M3 device-matrix work.
- Local presets (Ollama, LM Studio, llama.cpp) were not running, so their CORS
  rows remain `unknown`. Custom uses a placeholder base URL and must be tested
  per user configuration.
- Case 19 web evidence is a unit test plus a manual check of the
  availability-driven warning path; there is no dedicated provider-switch
  Playwright test.
- Case 23 web evidence is a manual code check; there is no automated 10,000-row
  web test.

## 8. Redaction review

- No API key, real prompt, conversation content, personal data, or response body
  is recorded in this file or in `M2-cors-matrix.md`.
- CORS evidence records only status codes, headers, timestamps, and conclusions.
- Playwright artifacts are failure-only screenshots and traces; video is
  disabled and the suite uses a fake key.
- Test names and fixture names contain no user data.

## 9. Residual risks and blockers

Residual risks:

- Valid-key success was not re-run for every cloud preset. CORS readability is
  proven with placeholder credentials; a later release check should repeat the
  matrix with disposable keys if one is available.
- Ollama, LM Studio, llama.cpp, and Custom remain `unknown` until a real
  configured server passes the same preflight and browser-readability checks.
- Web case 23 has no automated 10,000-message test. The windowing implementation
  and Android equivalent are present, but the web gap is not hidden.
- No live web or Android manual smoke was performed for M2-09.1. The evidence is
  command, fixture, unit, component, and Playwright coverage.

Blockers: none for M2-09.1 or the final gate. The residual risks above are
carried into section 11 with owners; they do not change any `browserCors` value.

## 10. Exit-gate evidence matrix

Reviewed against the M2 milestone exit gate and plan section 5 on 2026-11-06.

| Exit-gate requirement | Evidence | Status |
| --- | --- | --- |
| Every launch provider works end-to-end where CORS permits | Sections 3-4; per-provider adapter suites; CORS matrix. Cloud presets are `supported`; local and Custom remain `unknown`. | Pass with the recorded deferrals in section 11. |
| Streaming fixture conformance passes for all shared stream fixtures; OpenRouter shows no regression | Section 3 fixture table; section 6 M1 regression rows. | Pass. |
| Anthropic thinking/text separation, Gemini `MAX_TOKENS`, and Ollama NDJSON behave correctly | Section 5 cases 12-14. | Pass. |
| Reasoning is displayed separately across providers | Section 5 case 12; web and Android rendering tests. | Pass. |
| Markdown, code, and math render safely; XSS payloads are inert; incomplete fences render as code | Section 5 case 22; web KaTeX and Android raw-source math tests; code-fence tests. | Pass with the Android math limitation in section 11. |
| Per-conversation system prompt and parameters work; unsupported values are noted; protected fields cannot be overridden | Section 3 request vectors; web and Android parameter/settings tests. | Pass. |
| Attachments are stored locally, survive reload, are downscaled, and warn when unsupported | Section 5 case 19; attachment persistence, image-processing, and capability tests. | Pass. |
| Search, pin, archive, rename, and delete work | Web `useConversationHistory`/sidebar tests and Playwright delete/undo; Android `ConversationSearchTest`, `ConversationListViewModelTest`, and `ConversationListScreenTest`. | Pass. |
| Export/import round trips preserve IDs, order, snapshots, and attachments; invalid imports do not mutate data; clearing data removes conversations, keys, and attachments | Section 5 cases 24-25; export/import/data-control suites. | Pass. |
| CORS failures produce actionable errors and never silently proxy | Section 4; `networkError.test.ts` and adapter CORS cases. | Pass. |
| Web Playwright suite passes; Android provider and persistence tests pass | Section 2. | Pass. |
| Critical cases 11-14, 19, and 22-25 have evidence on both platforms; M1 cases still pass | Sections 5-6. | Pass with the web case 23 limitation in section 11. |
| `contracts/parity.md` reflects what actually works | `contracts/parity.md` M2 implementation-status section; `make contracts-check`. | Pass. |

## 11. Deferrals, residual risks, and owners

No exit-gate blocker remains. The following items are recorded deferrals or
residual risks; none is presented as a passing automated check.

| Item | Impact | Owner / target | Evidence |
| --- | --- | --- | --- |
| Android local Ollama over cleartext HTTP | A real device cannot reach `http://localhost:11434` until a network security config permits cleartext for local/private hosts. HTTPS endpoints work; the adapter and guidance are present. | M3 Android depth | M2-06.3 handoff; `android/app/src/main/AndroidManifest.xml` has no `networkSecurityConfig`. |
| Android Anthropic and Gemini discovery | `ModelService.refreshModels` passes no API key, so discovery falls back to the shared fallback model lists. Chat and key validation work. | Follow-up owner; target M3 | `ModelService.kt` passes `apiKey = null`; M2-06.1/M2-06.2 handoffs. |
| Android math typesetting | Standalone math blocks render as bounded raw source; inline math stays raw text. This is the documented safe fallback, not typeset math. | M3 Android depth | M2-06.4 handoff; `ChatMarkdown.kt`. |
| Android device-level input and visual checks | Photo picker, SAF, clipboard paste, drag-and-drop, and visual attachment/data screens are covered by unit/Compose tests, not a device matrix. | M3 Android depth | M2-08.2 handoff; section 7. |
| Web case 23 automated coverage | The 10,000-message windowing path has a manual code check on web; Android has an automated equivalent. | M4 polish or follow-up | Section 5 case 23. |
| Live provider smoke and valid-key success | CORS evidence proves browser readability with placeholder credentials; valid-key success was not re-run for every cloud preset. | M4 release check | Sections 4 and 7. |
| Local and Custom CORS | Ollama, LM Studio, llama.cpp, and Custom remain `unknown` until a real configured server passes the same checks. | M4 release check | Section 4. |

## 12. Final redaction and overclaim review

- No API key, real prompt, conversation content, personal data, or response body
  appears in this file, `M2-cors-matrix.md`, or the M2 task records.
- CORS evidence records only status codes, headers, timestamps, and conclusions.
- Playwright uses a fake key and failure-only screenshots/traces; video is
  disabled.
- `contracts/parity.md` keeps the commitment table unchanged and records M2
  behavior in a status list. It does not claim PWA, share target, backup/sync,
  Rust, deployment, Android math typesetting, or device-matrix work.
- The M2 milestone is marked Complete only after this review; the deferrals in
  section 11 are visible and owned.

## 13. Sign-off

| Area | Owner | Date | Result | Evidence |
| --- | --- | --- | --- | --- |
| Contracts and fixtures | M2-09.2 | 2026-11-06 | Pass | Sections 2-3; `make contracts-check`. |
| Web providers, content, and data | M2-09.2 | 2026-11-06 | Pass | Sections 2-6. |
| Android providers, content, and data | M2-09.2 | 2026-11-06 | Pass | Sections 2-6. |
| CORS and browser release decision | M2-09.1 | 2026-11-06 | Pass | Section 4; `M2-cors-matrix.md`. |
| Parity and final exit gate | M2-09.2 | 2026-11-06 | Pass | Sections 10-12; `contracts/parity.md`. |
