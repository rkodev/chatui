# M1 Verification Evidence

- Status: Final exit-gate evidence recorded 2026-11-04; M1 gate passed.
- Owner: M1-10 finalizes this file.
- Scope: OpenRouter-only chat on web and Android.
- Source plan: [M1 OpenRouter Core Implementation Plan](../M1-openrouter-core-implementation-plan.md)
- Authoritative spec: [IMPLEMENTATION.md](../../IMPLEMENTATION.md)

This file is the shared evidence location for the M1 exit gate. Tasks add
evidence as they land. Do not mark a row passed until the command or manual
check has actually run and the evidence is linked or quoted.

## Evidence rules

- Record commands, exit codes, and bounded output. Do not paste full logs.
- Record only status codes, headers, timestamps, and conclusions for CORS.
- Never record an API key, a real prompt, a real conversation, or personal data.
- Redact keys in screenshots and logs. Use a disposable test key.
- Leave a row `Pending` when the evidence does not exist yet.

## 1. Commands

Environment: Linux x86_64, Node.js 24.16.0, pnpm 11.7.0, JDK 21.0.2+13,
Gradle 9.8.1, Android compile/target SDK 37, minSdk 26. Commands ran from the
repository root on 2026-11-04.

| Command | Purpose | Expected | Result | Evidence |
| --- | --- | --- | --- | --- |
| `make contracts-install` | Install contract validator dependencies | Exit 0 | Pass | `npm --prefix scripts install` reported 0 vulnerabilities. |
| `make contracts-check` | Validate providers, schemas, fixtures, request vectors, and parity | Exit 0 | Pass | `Contract validation passed.` |
| `make web-lint` | Web lint | Exit 0 | Pass | `eslint .` completed with no findings. |
| `make web-typecheck` | Web type checks | Exit 0 | Pass | `tsc -b` completed with no errors. |
| `make web-test` | Web unit and component tests | Exit 0 | Pass | 67 test files, 519 tests, 0 failures. |
| `make web-build` | Web production build | Exit 0 | Pass | Vite build completed; `dist/` produced. |
| `make android-lint` | Android lint | Exit 0 | Pass | `BUILD SUCCESSFUL`. |
| `make android-test` | Android unit and ViewModel tests | Exit 0 | Pass | 61 result files, 335 tests, 0 failures, 0 errors, 0 skipped. |
| `make android-assemble` | Android debug build | Exit 0 | Pass | `BUILD SUCCESSFUL`; debug APK produced. |

## 2. CORS matrix

Verified 2026-11-04 against `https://openrouter.ai/api/v1` from the Vite dev
origin `http://localhost:5173`. Preflight and header inspection used
`curl 8.18.0`; browser enforcement was confirmed with headless Google Chrome
157.0.8103.38 (UA reports `HeadlessChrome/157.0.0.0`). The valid-key checks used
a disposable key that was deleted after the run and is not recorded here.
Browser `fetch()` responses were readable and reported `type: "cors"` for every
request, with no CORS error.

| Endpoint | Method | Origin | Preflight | Status | Response headers observed | Timestamp (UTC) | Conclusion | Evidence |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `/key` | `GET` | `http://localhost:5173` | `204`; ACAO `*`; methods include `GET`; headers include `Authorization`, `HTTP-Referer`, `X-Title` | `200` valid key; `401` invalid key | ACAO `*`; `content-type: application/json`; `cache-control: private, no-store`; `access-control-expose-headers` includes `X-Generation-Id`, `request-id`, `cf-ray` | Preflight `17:02:15Z`; invalid `17:02:16Z`; valid `17:06:32Z` | Supported. Valid key returns key metadata; invalid key is a readable `401`. | OPTIONS/GET probes; headless Chrome `/key` invalid-key fetch was `type: cors`, status `401`. |
| `/models` | `GET` | `http://localhost:5173` | `204`; ACAO `*`; methods include `GET`; headers include `Authorization`, `HTTP-Referer`, `X-Title` | `200` without a key | ACAO `*`; `content-type: application/json`; `cache-control: public, max-age=120`; `cf-cache-status: HIT` | Preflight `17:02:14Z`; GET `17:02:15Z`; browser `17:07:03Z` | Supported. Public catalog is readable without a key. | OPTIONS/GET probes; headless Chrome `/models` fetch was `type: cors`, status `200`. |
| `/chat/completions` | `POST` | `http://localhost:5173` | `204`; ACAO `*`; methods include `POST`; headers include `Authorization`, `Content-Type`, `HTTP-Referer`, `X-Title` | `200` streaming with valid key; `401` invalid key | ACAO `*`; `content-type: text/event-stream`; `cache-control: no-cache`; `x-generation-id` exposed; invalid-key response is `application/json` | Preflight `17:02:16Z`; invalid `17:02:33Z`; stream `17:06:39Z` | Supported. Streaming request returned 5 SSE data lines ending in `[DONE]`, plus a final usage chunk. Invalid key is a readable `401`. | OPTIONS/POST probes; headless Chrome `/chat/completions` invalid-key fetch was `type: cors`, status `401`. |

Observed preflight allow-list includes `Authorization`, `Content-Type`,
`HTTP-Referer`, `X-Openrouter-Title`, and `X-Title`, so the attribution headers
used by the app are allowed. `access-control-allow-methods` is
`GET,OPTIONS,PATCH,DELETE,POST,PUT`. No `access-control-max-age` header was
returned. No intermittent CORS behavior was observed.

Conclusion: browser-direct OpenRouter access is supported for all three required
endpoints. The web release is not blocked, and `contracts/providers.json`
`browserCors` is set to `supported`. No proxy was added.

## 3. OpenRouter conformance checklist

| Check | Web | Android | Evidence |
| --- | --- | --- | --- |
| `GET /key` validates the key and exposes label, usage, limit, free-tier, and rate-limit metadata | Pass | Pass | Web `openrouterCatalog.test.ts` key validation; Android `OpenRouterAdapterTest` key validation and metadata mapping. CORS `/key` checks in section 2. |
| `GET /models` populates paid, free, vision, reasoning, and long-context models | Pass | Pass | Web `openrouterCatalog.test.ts`; Android `OpenRouterCatalogTest` maps every fixture entry. |
| Pricing strings convert correctly and display per million tokens; free models are marked | Pass | Pass | Web `openrouterCatalog.test.ts` and `modelPicker.test.ts`; Android `OpenRouterCatalogTest` and `ModelPickerTest`. |
| Context window, max output tokens, input/output modalities, and supported parameters are mapped | Pass | Pass | Web catalog/model picker tests; Android catalog and model picker tests. |
| Chat streams incrementally from `POST /chat/completions` | Pass | Pass | Web `openrouter.test.ts` streaming fixtures; Android `OpenRouterStreamingTest`; manual smoke observed incremental completion. |
| `: OPENROUTER PROCESSING` keep-alives and unknown SSE fields do not break parsing | Pass | Pass | Web `sse.test.ts` keep-alive/framing cases; Android `SseTransportTest` keep-alive/framing cases. |
| Reasoning content is normalized and displayed separately from the answer | Pass | Pass | Web `openrouter.test.ts` deduplication test and `MessageItem` reasoning block; Android `OpenRouterStreamingTest` and `StreamingReducerTest`, plus manual smoke showed a separate `Show reasoning` section. |
| Final usage and cost are persisted and visible | Pass | Pass | Web `ChatRunner.test.ts` and `MessageItem.test.tsx` usage cases, plus manual smoke; Android `ChatRunnerTest`/`StreamingReducerTest`, default token-usage visibility, and manual smoke. |
| `402`, `429`, `503`, context errors, and errors inside a 200 stream are handled | Pass | Pass | Web `openrouter.test.ts` HTTP error mapping and retry tests; Android `OpenRouterAdapterTest`, `OpenRouterStreamingTest`, and `RetryPolicyTest`. |
| Provider routing preferences serialize correctly and protected fields cannot be overridden | Pass | Pass | Web `openrouter.test.ts` request serialization/protected-field tests; Android `OpenRouterStreamingTest` and `RoutingTest`. |
| Attribution headers are configurable and contain no user data | Pass | Pass | Web `openrouter.test.ts` attribution case; Android `OpenRouterStreamingTest` attribution header case. |
| Browser-direct CORS behavior is verified, or the web release is explicitly blocked | Pass | N/A | Section 2 CORS matrix; `browserCors` is `supported`. |

## 4. Fixture conformance

Expected vectors live in `contracts/fixtures/expected/`. Request vectors live in
`contracts/fixtures/requests/`. Both clients pass the same vectors.

| Fixture or vector | Web | Android | Evidence |
| --- | --- | --- | --- |
| `openrouter-text.sse` / `openrouter-text.json` | Pass | Pass | Web `openrouter.test.ts` streaming fixtures; Android `OpenRouterStreamingTest` text case. |
| `openrouter-keepalive.sse` / `openrouter-keepalive.json` | Pass | Pass | Web `sse.test.ts` and `openrouter.test.ts`; Android `SseTransportTest` and `OpenRouterStreamingTest`. |
| `openrouter-reasoning.sse` / `openrouter-reasoning.json` | Pass | Pass | Web `openrouter.test.ts` reasoning cases; Android `OpenRouterStreamingTest` reasoning case. |
| `openrouter-usage.sse` / `openrouter-usage.json` | Pass | Pass | Web `openrouter.test.ts` usage case; Android `OpenRouterStreamingTest` usage case. |
| `openrouter-error-200.sse` / `openrouter-error-200.json` | Pass | Pass | Web `openrouter.test.ts` in-stream error case; Android `OpenRouterStreamingTest` in-stream error case. |
| `openrouter-key.json` / `openrouter-key.json` | Pass | Pass | Web `openrouterCatalog.test.ts` key mapping; Android `OpenRouterCatalogTest` key mapping. |
| `openrouter-errors.json` | Pass | Pass | Web `openrouter.test.ts` HTTP error mapping; Android `OpenRouterStreamingTest` HTTP error fixtures. |
| `openrouter-models.json` catalog mapping | Pass | Pass | Web `openrouterCatalog.test.ts`; Android `OpenRouterCatalogTest`. |
| `openrouter-basic.json` request serialization | Pass | Pass | Web `openrouter.test.ts`; Android `OpenRouterStreamingTest`. |
| `openrouter-routing.json` curated routing | Pass | Pass | Web `openrouter.test.ts`; Android `OpenRouterStreamingTest` and `RoutingTest`. |
| `openrouter-unsupported-parameter.json` omission | Pass | Pass | Web `openrouter.test.ts`; Android `OpenRouterStreamingTest`. |
| `openrouter-advanced-protected.json` protected-field rejection | Pass | Pass | Web `openrouter.test.ts`; Android `OpenRouterStreamingTest`. |
| `openrouter-attribution.json` attribution headers | Pass | Pass | Web `openrouter.test.ts`; Android `OpenRouterStreamingTest`. |
| `openrouter-default-model.json` fallback selection | Pass | Pass | Web `modelService.test.ts`; Android `ModelServiceTest`. |

## 5. Critical cases

M1 targets critical cases 1-10, 15-18, 20-21, and 28 from spec 15.2.

| Case | Description | Web | Android | Evidence |
| --- | --- | --- | --- | --- |
| 1 | First run with no provider shows onboarding, preselects OpenRouter, and blocks sending | Pass | Pass | Web `OnboardingScreen.test.tsx` first-run case and manual smoke; Android `OnboardingViewModelTest`/`OnboardingScreenTest` and manual smoke. |
| 2 | Invalid OpenRouter key shows an auth error, preserves the form, and does not accept the key | Pass | Pass | Web `OnboardingScreen.test.tsx` and `onboardingService.test.ts`; Android `OnboardingViewModelTest` and `DefaultOnboardingServiceTest`; both manual smokes. |
| 3 | Valid OpenRouter key passes `GET /key`, loads `/models`, and selects a default model | Pass | Pass | Web `onboardingService.test.ts`; Android `DefaultOnboardingServiceTest`; both manual smokes. |
| 4 | Catalog mapping handles paid, free, vision, reasoning, and tool models; converts prices; ignores unknown fields | Pass | Pass | Web `openrouterCatalog.test.ts`; Android `OpenRouterCatalogTest`. |
| 5 | Streaming ignores keep-alives and appends text in order | Pass | Pass | Web `openrouter.test.ts`/`sse.test.ts`; Android `OpenRouterStreamingTest`/`SseTransportTest`. |
| 6 | `reasoning` and `reasoning_details` produce one normalized reasoning stream | Pass | Pass | Web `openrouter.test.ts` deduplication case; Android `OpenRouterStreamingTest` reasoning cases. |
| 7 | Usage and cost from the final chunk are persisted and displayed | Pass | Pass | Web `ChatRunner.test.ts` and `MessageItem.test.tsx`; Android `ChatRunnerTest` and `StreamingReducerTest`; manual smokes. |
| 8 | An error object inside an HTTP 200 stream stops the message, preserves partial text, and does not auto-retry | Pass | Pass | Web `openrouter.test.ts` in-stream error case; Android `OpenRouterStreamingTest` in-stream error case. |
| 9 | 402 insufficient credits maps to `quota` with a billing message and no retry | Pass | Pass | Web `openrouter.test.ts` 402 fixture; Android `OpenRouterStreamingTest` error fixtures and `OpenRouterAdapterTest`. |
| 10 | 503 no available upstream provider retries with backoff | Pass | Pass | Web 503 mapping plus retry-policy tests; Android `OpenRouterAdapterTest` 503 mapping plus `RetryPolicyTest`. |
| 15 | Cancel stops the request, persists partial text, and marks `cancelled` | Pass | Pass | Web `ChatRunner.test.ts` cancel case and `openrouter.test.ts` cancellation; Android `ChatRunnerTest` cancel and `SseTransportTest` cancellation. |
| 16 | Network failure before first token retries up to 3 times | Pass | Pass | Web `openrouter.test.ts` network retry/three-attempt cases; Android `OpenRouterStreamingTest` retry cases. |
| 17 | Network failure after first token does not auto-retry | Pass | Pass | Web `openrouter.test.ts` post-token failure case; Android `OpenRouterStreamingTest` post-token timeout case. |
| 18 | Switching model mid-conversation persists and uses the new model on the next send | Pass | Pass | Web `ConversationModelControl.test.tsx` and `managementService.test.ts`; Android `ModelPickerViewModelTest` and `ProviderManagementServiceTest`. |
| 20 | Routing preferences serialize correctly and protected fields cannot be overridden | Pass | Pass | Web request serialization/protected tests; Android `OpenRouterStreamingTest` and `RoutingTest`. |
| 21 | Reload during streaming marks the message interrupted and preserves partial text | Pass | Pass | Web `recoveryService.test.ts`, `MessageItem.test.tsx`, and `ChatRunner.test.ts` pagehide case; Android `StreamingRecoveryServiceTest` and `InterruptedMessageNoticeTest`. |
| 28 | 429 with `Retry-After` schedules a retry and shows a countdown | Pass | Pass | Web `openrouter.test.ts` Retry-After cases and retry helpers; Android `OpenRouterAdapterTest`/`OpenRouterStreamingTest` Retry-After cases and `RetryPolicyTest`. Error copy reports the delay; there is no ticking countdown (see residual risks). |

## 6. Manual smoke

The smoke runs were scripted against the built web app and a real Android
emulator so they are reproducible. They still exercised the real onboarding,
key validation, catalog, streaming, persistence, and UI. No key, prompt, or
conversation content is recorded here.

| Scenario | Web | Android | Evidence |
| --- | --- | --- | --- |
| New user onboards and sends a first message in under two minutes | Pass | Pass | Web: headless Chrome 157 against `vite preview`, first-run onboarding through completed assistant response in 10 s. Android: Pixel_4 API 29 emulator, first-run onboarding through completed assistant response in 81.6 s including an invalid-key retry and scripted input overhead. |
| Invalid key is actionable and the form is preserved | Pass | Pass | Web: auth alert shown, typed key preserved, still on onboarding. Android: `API key rejected` shown, masked key value preserved, still on key entry. |
| Model picker shows price, context, modalities, and free badge | Pass | Pass | Web `ModelPickerDialog.test.tsx`/`modelPicker.test.ts`; Android `ModelPickerContentTest`/`ModelPickerTest`. |
| Streaming text appears incrementally and stop works | Pass | Pass | Streaming and cancel tests above; manual smokes observed incremental completion. |
| Reasoning is visually distinct from the answer | Pass | Pass | Web `MessageItem` renders a separate reasoning block and reasoning tests pass; Android manual smoke showed a separate `Show reasoning` section. |
| Usage and cost are visible after a completed turn | Pass | Pass | Web manual smoke and `MessageItem.test.tsx`; Android manual smoke displayed token totals and cost after the default token-usage visibility fix. |
| Reload during streaming preserves partial text and marks it interrupted | Pass | Pass | Web `recoveryService.test.ts`, `ChatRunner.test.ts` pagehide, `MessageItem.test.tsx`; Android `StreamingRecoveryServiceTest` and `InterruptedMessageNoticeTest`. |
| Conversation survives restart and model switching persists | Pass | Pass | Web persistence/model-switch tests; Android manual restart restored the completed conversation with history and actions, plus `ConversationServiceTest` and `ModelPickerViewModelTest`. |
| Routing summary reflects active preferences | Pass | Pass | Web `ConversationRoutingControls.test.tsx`/`routing.test.ts`; Android `ConversationRoutingControlsTest`/`RoutingTest`. |
| Browser CORS result is recorded or the web release is blocked | Pass | N/A | Section 2, `browserCors: supported`. |

## 7. Exit-gate evidence matrix

| Exit-gate requirement | Evidence to capture | Responsible tasks | Status |
| --- | --- | --- | --- |
| New user onboards and chats in under two minutes | Timed web and Android smoke records | M1-03, M1-04, M1-07, M1-09, M1-10 | Pass. Web 10 s; Android 81.6 s. |
| Valid key passes `/key`; invalid key is actionable and not accepted | Adapter tests, component/ViewModel tests, smoke invalid-key check | M1-02, M1-03, M1-06, M1-07 | Pass. Sections 3, 5 cases 2-3, 6. |
| Catalog maps paid, free, vision, reasoning, long-context, and tool models | Fixture test output from both clients against `openrouter-models.json` | M1-02, M1-06, M1-10 | Pass. Section 4 catalog rows. |
| Streaming appends text; keep-alives and unknown fields do not break parsing | Fixture tests plus smoke stream | M1-02, M1-04, M1-06, M1-09 | Pass. Sections 4 and 5 case 5. |
| Reasoning appears once and is visually distinct | Expected-vector test and UI evidence | M1-01, M1-02, M1-04, M1-06, M1-09 | Pass. Sections 3, 5 case 6, 6. |
| Final usage and cost persisted and visible | Usage fixture test, persisted-message assertion, UI evidence | M1-02, M1-04, M1-06, M1-09 | Pass. Sections 3, 5 case 7, 6. |
| 402, 429, 503, context-length, in-stream errors, and cancellation map correctly | Error fixture tests, retry tests, cancel tests | M1-02, M1-06, M1-10 | Pass. Sections 4 and 5 cases 8-10, 15, 28. |
| Routing serializes correctly and protected fields cannot be overridden | Shared request vectors on both adapters plus UI routing evidence | M1-01, M1-02, M1-06, M1-10 | Pass. Sections 4 and 5 case 20. |
| Conversations survive restart; model switching persists; messages keep snapshots | Repository/persistence tests and restart check | M1-03, M1-05, M1-07, M1-08 | Pass. Section 6 restart/model-switch rows. |
| Browser-direct OpenRouter behavior is verified for all three endpoints or web release is blocked | CORS matrix with preflight/status/conclusion and updated `browserCors` | M1-10 | Pass. Section 2. |
| No OpenRouter regression; critical cases 1-10, 15-18, 20-21, 28 pass on both platforms | Test output mapped to critical-case IDs | M1-02..M1-09, M1-10 | Pass. Section 5. |
| `contracts/parity.md` reflects what actually works | Parity review with implementation-status evidence and no M2 overclaim | M1-10 | Pass. `contracts/parity.md` M1 implementation-status section. |

## 8. Default-model fallback rule

The rule is recorded in `contracts/fixtures/requests/README.md` and encoded in
`contracts/fixtures/requests/openrouter-default-model.json`:

1. Use `defaultModelId` from `contracts/providers.json` when it exists in the
   loaded catalog.
2. Otherwise select the first non-hidden model with text output, ordered by
   `remoteId` ascending.
3. If the preset default is missing, surface a non-blocking unavailable notice
   and do not invent a model.

## 9. Gate fixes and residual risks

Gate fixes found while running the manual smoke:

- Web called `fetch` as `context.fetch(...)`, which Chrome rejects with
  `Illegal invocation`. The adapter and streaming transport now detach `fetch`
  before calling it. Regression tests cover both paths.
- Android onboarding navigated to the placeholder `chat` destination after key
  validation. It now opens the conversation list, where the new-chat flow
  creates the first conversation and opens the real chat route.
- Android token usage was hidden by default, contrary to spec 9 metadata and the
  M1 gate. It is now shown by default, matching web.

Residual risks and limits:

- The smoke runs were scripted against real Chrome and a real Android emulator,
  not recorded as human screenshots. Scripts and redacted records are enough to
  reproduce the result.
- Case 28 surfaces the `Retry-After` delay in error copy and schedules the
  retry automatically; there is no live ticking countdown UI in M1.
- Release signing, deployment, additional providers, attachments, math, search,
  export/import, PWA, and the Rust client remain out of scope for M1.

## 10. Sign-off

| Area | Owner | Date | Result | Evidence |
| --- | --- | --- | --- | --- |
| Contracts and fixtures | M1-10 | 2026-11-04 | Pass | Sections 1 and 4. |
| Web adapter and chat | M1-10 | 2026-11-04 | Pass | Sections 1, 3-6. |
| Android adapter and chat | M1-10 | 2026-11-04 | Pass | Sections 1, 3-6. |
| CORS and browser release decision | M1-10 | 2026-11-04 | Pass | Section 2. |
| Parity and final exit gate | M1-10 | 2026-11-04 | Pass | Sections 5-7 and `contracts/parity.md`. |
