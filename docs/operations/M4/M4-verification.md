# M4 verification and phase 1 release gate

- Task: `M4-06.1` parity audit and phase 1 release gate
- Date: 2026-11-10
- Commit under test: `ac1ae55b7fa8043e7fcbae9380a425388ba6cc87`
- Host: `ronald-Lenovo-Legion-S7-15IMH5`, Ubuntu 26.04.1 LTS, x86_64,
  16 logical CPUs / 30 GiB RAM
- Toolchain: Node 24.16.0, pnpm 11.7.0, JDK Temurin 21.0.2, Rust/cargo 1.98.1,
  Docker 29.9.3, Playwright 1.64.1 (Chromium), Lighthouse 12.8.2
- Android device: `m3_api34_phone` AVD, API 34, `sdk_gphone64_x86_64`
  (emulator; no physical device available)
- Release scope: Web and Android required; Rust desktop `post-preview /
  parallel` per `M4-decisions.md` DEC-M4-01
- Approved exceptions: none (DEC-M4-07)

This document records the audit only. No product behavior was changed to make a
check pass. Every claim below links to a test, a manual check, or a recorded
artifact. No key, prompt, conversation content, personal data, or signing
material is recorded here; all fixtures are synthetic.

## 1. Verification commands and results

| Command | Result |
| --- | --- |
| `make contracts-check` | Pass: `Contract validation passed.` |
| `make web-lint` | Pass: `eslint .` with no findings |
| `make web-typecheck` | Pass: `tsc -b` with no errors |
| `make web-test` | Pass: 101 files, 882 tests, 0 failures |
| `make web-build` | Pass: production build produced `web/dist` |
| `cd web && pnpm exec playwright test` | Pass: 30 tests. The first run had one flaky accessibility overflow assertion; the isolated test and a full rerun both passed 30/30 |
| `make android-lint` | Pass: `BUILD SUCCESSFUL` |
| `make android-test` | Pass: 758 tests across modules, 0 failures, 0 errors, 0 skipped |
| `make android-assemble` | Pass: `BUILD SUCCESSFUL`; debug APK produced |
| `cd android && ./gradlew :app:lintRelease :app:testDebugUnitTest :app:assembleRelease :app:bundleRelease` | Pass: `BUILD SUCCESSFUL`; unsigned release APK and AAB produced |
| `make rust-check` | Pass |
| `make rust-lint` | Pass: clippy with `-D warnings` |
| `make rust-test` | Pass |
| `make rust-build` | Pass |
| `make rust-conformance` | Pass: 96 tests, 0 skipped |
| `cd rust && cargo deny check` | **Fail** on pre-existing advisory `CVE-2026-119375` in `libsqlite3-sys 0.30.1` via `sqlx-sqlite 0.8.6`. Post-preview Rust track only |
| `cd rust && cargo audit` | **Fail**: 1 vulnerability and 10 allowed warnings. Post-preview Rust track only |
| Container build and smoke | Pass: see section 6 |
| Image secret-pattern scan | Pass: `no secret patterns found` |

The Rust supply-chain failures are pre-existing and belong to the post-preview
Rust track. Rust is not shipping (DEC-M4-01), so they are not phase 1 release
blockers. They remain recorded as known issues for that track.

## 2. Parity audit

The commitment table in [`contracts/parity.md`](../../../contracts/parity.md)
is unchanged. The release status below is the audit result for `0.1.0`.

| Feature | Web | Android | Rust |
| --- | --- | --- | --- |
| BYOK onboarding | Complete: `onboardingService.test.ts`, `OnboardingScreen.test.tsx`, `e2e/onboarding.spec.ts` | Complete: `DefaultOnboardingServiceTest`, `OnboardingViewModelTest`, `OnboardingScreenTest` | Optional (post-preview / parallel) |
| OpenRouter first-run onboarding | Complete: `onboardingService.test.ts`, `e2e/onboarding.spec.ts` | Complete: `DefaultOnboardingServiceTest`, `OnboardingViewModelTest` | Optional (post-preview / parallel) |
| OpenRouter key introspection (`/key`) | Complete: `onboardingService.test.ts` valid/invalid key cases; M1 CORS matrix | Complete: `DefaultOnboardingServiceTest`, `OpenRouterAdapterTest` | Optional (post-preview / parallel) |
| OpenRouter catalog, pricing, modalities | Complete: `openrouterCatalog.test.ts` | Complete: `OpenRouterCatalogTest` | Optional (post-preview / parallel) |
| OpenRouter streaming, reasoning, usage/cost | Complete: `openrouter.test.ts`, `ChatRunner.test.ts`, `MessageItem.test.tsx` | Complete: `OpenRouterStreamingTest`, `ChatRunnerTest`, `StreamingReducerTest` | Optional (post-preview / parallel) |
| OpenRouter provider routing preferences | Complete: `routing.test.ts`, `ConversationRoutingControls.test.tsx` | Complete: `RoutingTest`, `ConversationRoutingControlsTest` | Optional (post-preview / parallel) |
| OpenAI-compatible streaming | Complete: `openaiCompatible.test.ts` | Complete: `OpenAICompatibleTransportTest`, `ProviderConformanceTest` case 11 | Optional (post-preview / parallel) |
| Anthropic streaming | Complete: `anthropic.test.ts` | Complete: `AnthropicAdapterTest`, `ProviderConformanceTest` case 12 | Optional (post-preview / parallel) |
| Gemini streaming | Complete: `gemini.test.ts` | Complete: `GeminiAdapterTest`, `ProviderConformanceTest` case 13 | Optional (post-preview / parallel) |
| Ollama local | Complete: `ollama.test.ts` | Complete: `OllamaAdapterTest`, `ProviderConformanceTest` case 14 | Optional (post-preview / parallel) |
| Model discovery and switching | Complete: `modelService.test.ts`, `ConversationModelControl.test.tsx` | Complete: `ModelPickerViewModelTest`, `ProviderManagementServiceTest` | Optional (post-preview / parallel) |
| Conversation persistence | Complete: `repositories.test.ts`, `migrations.test.ts` | Complete: `PersistenceTest`, `MigrationTest` | Optional (post-preview / parallel) |
| Search | Complete: `useConversationSearch.test.tsx` | Complete: `ConversationSearchTest`, `MessageFtsTest` | Optional (post-preview / parallel) |
| Markdown + code + math | Complete: `MarkdownRenderer.test.tsx`, `CodeBlock.test.tsx` | Complete: `ChatMarkdownTest`, `ChatCodeBlockTest`; math is the documented raw-source fallback | Optional (post-preview / parallel) |
| Attachments | Complete: `attachmentService.test.ts`, `attachmentSupport.test.ts`, `imageProcessing.test.ts` | Complete: `AttachmentServiceTest`, `AttachmentSupportTest`, `AndroidImageProcessorTest` | Optional (post-preview / parallel) |
| Export/import | Complete: `exportService.test.ts`, `importService.test.ts`, `e2e/data.spec.ts` | Complete: `ExportServiceTest`, `ImportServiceTest` | Optional (post-preview / parallel) |
| Backup/sync | Future (not a phase 1 row) | Future (not a phase 1 row) | Future (not a phase 1 row) |
| PWA install | Complete: `register.test.ts`, `e2e/pwa.spec.ts`, `evidence/pwa/README.md` | N/A | N/A |
| Native share target | N/A | Complete: `ExternalIntentIntakeTest`, `ShareTextParserTest`, M3 device evidence | Optional (post-preview / parallel) |
| Desktop packaging (installer/AppImage) | N/A | N/A | Optional (post-preview / parallel); M3.5 packaging evidence exists but is not released |
| Auto-update | N/A | N/A | Optional (post-preview / parallel); M3.5 update-signing evidence exists but is not released |

Rust status is `Optional` for every row, consistent with DEC-M4-01. No Rust row
is marked complete or required for this release.

## 3. Critical test cases 1-28

Cases apply to every enabled client unless the case is platform-specific. Web
and Android are the enabled clients. Rust is not shipping.

| Case | Web | Android | Evidence |
| --- | --- | --- | --- |
| 1 First run shows onboarding, preselects OpenRouter, blocks sending | Pass | Pass | Web `OnboardingScreen.test.tsx`, `e2e/onboarding.spec.ts`; Android `OnboardingViewModelTest`, `OnboardingScreenTest` |
| 2 Invalid key shows auth error, preserves form, rejects key | Pass | Pass | Web `onboardingService.test.ts`, `OnboardingScreen.test.tsx`; Android `DefaultOnboardingServiceTest`, `OnboardingViewModelTest` |
| 3 Valid key passes `/key`, loads `/models`, selects default | Pass | Pass | Web `onboardingService.test.ts`; Android `DefaultOnboardingServiceTest` |
| 4 Catalog mapping handles paid/free/vision/reasoning/tool, per-million prices, unknown fields | Pass | Pass | Web `openrouterCatalog.test.ts`; Android `OpenRouterCatalogTest` |
| 5 Streaming ignores keep-alives and appends text in order | Pass | Pass | Web `openrouter.test.ts`, `sse.test.ts`; Android `OpenRouterStreamingTest`, `SseTransportTest` |
| 6 Reasoning and reasoning_details produce one normalized stream | Pass | Pass | Web `openrouter.test.ts`; Android `OpenRouterStreamingTest`, `OpenAICompatibleTransportTest` |
| 7 Usage/cost from the final chunk is persisted and displayed | Pass | Pass | Web `ChatRunner.test.ts`, `MessageItem.test.tsx`; Android `ChatRunnerTest`, `StreamingReducerTest` |
| 8 In-stream error stops the message, preserves partial text, no auto-retry | Pass | Pass | Web `openrouter.test.ts`; Android `OpenRouterStreamingTest` |
| 9 402 maps to quota with billing message and no retry | Pass | Pass | Web `openrouter.test.ts`; Android `OpenRouterAdapterTest`, `OpenRouterStreamingTest` |
| 10 503 retries with backoff | Pass | Pass | Web `openrouter.test.ts`, retry-policy tests; Android `OpenRouterAdapterTest`, `RetryPolicyTest` |
| 11 OpenAI-compatible streaming appends text and finalizes usage | Pass | Pass | Web `openaiCompatible.test.ts`; Android `ProviderConformanceTest` case 11, `OpenAICompatibleTransportTest` |
| 12 Anthropic thinking and text deltas are separated | Pass | Pass | Web `anthropic.test.ts`; Android `ProviderConformanceTest` case 12, `AnthropicAdapterTest` |
| 13 Gemini MAX_TOKENS maps to length | Pass | Pass | Web `gemini.test.ts`; Android `ProviderConformanceTest` case 13, `GeminiAdapterTest` |
| 14 Ollama NDJSON completes and records usage | Pass | Pass | Web `ollama.test.ts`; Android `ProviderConformanceTest` case 14, `OllamaAdapterTest` |
| 15 Cancel stops the request, persists partial text, marks cancelled | Pass | Pass | Web `ChatRunner.test.ts`, `openrouter.test.ts`; Android `ChatRunnerTest`, `SseTransportTest` |
| 16 Network failure before first token retries up to 3 times | Pass | Pass | Web `openrouter.test.ts`; Android `OpenRouterStreamingTest` |
| 17 Network failure after first token does not auto-retry | Pass | Pass | Web `openrouter.test.ts`; Android `OpenRouterStreamingTest` |
| 18 Model switch mid-conversation persists and is used next send | Pass | Pass | Web `ConversationModelControl.test.tsx`, `managementService.test.ts`; Android `ModelPickerViewModelTest`, `ProviderManagementServiceTest` |
| 19 Provider switch warns about unsupported attachments | Pass | Pass | Web `Composer.test.tsx`; Android `AttachmentSupportTest`, `ChatViewModelTest`, `ChatScreenTest` |
| 20 Routing serializes correctly; protected fields cannot be overridden | Pass | Pass | Web `routing.test.ts`, protected-field tests; Android `RoutingTest`, `OpenRouterStreamingTest` |
| 21 Reload during streaming marks interrupted and preserves partial text | Pass | Pass | Web `recoveryService.test.ts`, `MessageItem.test.tsx`, `ChatRunner.test.ts`; Android `StreamingRecoveryServiceTest`, `InterruptedMessageNoticeTest` |
| 22 Markdown XSS payloads render inert | Pass | Pass | Web `MarkdownRenderer.test.tsx`; Android `ChatMarkdownTest` |
| 23 10,000-message conversation scrolls without rendering all messages | Pass (manual check) | Pass | Web manual check of `MessageList.tsx` and `useWindowedList`; Android `ChatMessageListTest` asserts fewer than 100 rows compose. Web has no automated 10,000-row test; this is a recorded limitation, not a blocker |
| 24 Export/import preserves IDs, ordering, snapshots, attachments | Pass | Pass | Web `exportService.test.ts`, `importService.test.ts`, `e2e/data.spec.ts`; Android `ExportServiceTest`, `ImportServiceTest` |
| 25 Clearing data removes conversations, keys, attachments | Pass | Pass | Web `dataControlsService.test.ts`, `e2e/data.spec.ts`; Android `DataControlsServiceTest` |
| 26 Android process death during streaming preserves partial content | N/A (web uses case 21) | Pass | Android `StreamingRecoveryServiceTest`, `InterruptedMessageNoticeTest`, M3-verification section 16 |
| 27 Android backup exclusion prevents key restore on another device | N/A | Pass | Android `BackupRulesTest`, `BackupSeedTest`, `data_extraction_rules.xml`, `backup_rules.xml`, M3-verification section 10 |
| 28 429 with Retry-After schedules a retry and shows the delay | Pass | Pass | Web `openrouter.test.ts`, `chatErrorCopy.test.ts`; Android `OpenRouterAdapterTest`, `OpenRouterStreamingTest`, `RetryPolicyTest`. The delay is shown as static copy (`Retry after Ns`); there is no ticking countdown, matching the M1 residual-risk record |

No case is missing evidence on an enabled client. Case 23 web is a manual code
check; case 28 shows the delay without a live countdown. Both are recorded as
known limitations and are not release blockers.

## 4. OpenRouter conformance

| Surface | Result | Evidence |
| --- | --- | --- |
| `/key` validation and metadata | Pass | Web `onboardingService.test.ts`, `openrouterCatalog.test.ts`; Android `DefaultOnboardingServiceTest`, `OpenRouterAdapterTest`; shared `validate_key` fixture |
| `/models` catalog, pricing, modalities | Pass | Web `openrouterCatalog.test.ts`; Android `OpenRouterCatalogTest`; shared catalog fixture |
| Streaming and keep-alives | Pass | Web `openrouter.test.ts`, `sse.test.ts`; Android `OpenRouterStreamingTest`, `SseTransportTest`; shared stream fixtures |
| Reasoning normalization | Pass | Web `openrouter.test.ts`; Android `OpenRouterStreamingTest`, `OpenAICompatibleTransportTest`; shared reasoning fixture |
| Usage/cost | Pass | Web `ChatRunner.test.ts`, `MessageItem.test.tsx`; Android `ChatRunnerTest`, `StreamingReducerTest`; shared usage fixture |
| Provider routing preferences | Pass | Web `routing.test.ts`; Android `RoutingTest`; shared request vectors |
| Error mapping (402, 429, 503, in-stream) | Pass | Web `openrouter.test.ts`; Android `OpenRouterAdapterTest`, `OpenRouterStreamingTest`; shared error fixtures |
| Browser CORS on web | Pass | M1 CORS matrix: `/key`, `/models`, and `/chat/completions` returned readable `type: cors` responses in headless Chrome; `browserCors: supported` in `contracts/providers.json` |
| Rust conformance (not shipping) | Pass for the parallel track | `make rust-conformance`: 96 tests passed; not a phase 1 release claim |

`make contracts-check` validates every schema, fixture, request vector, and the
parity file. It passed.

## 5. Security and privacy audit

| Requirement | Result | Evidence |
| --- | --- | --- |
| No keys in logs | Pass | Web `redaction.ts` and `redaction.test.ts`; no `console.log` in `web/src`; Android `RedactionTest`; the only release log is a generic startup-recovery warning |
| No keys in URLs | Pass | No query-param auth in `web/src` or Android; keys travel only in `Authorization`, `x-api-key`, or `x-goog-api-key` headers |
| No keys in the Cache API | Pass | `cachePolicy.ts` allowlist; `e2e/pwa.spec.ts` inspects cache keys and bodies; `evidence/pwa/README.md` records no `/config.json`, `/api/*`, provider URL, or chat text in the cache |
| No keys in exports | Pass | `exportService.test.ts` and Android `ExportServiceTest` assert secrets are excluded; export schema has no secret field |
| No keys in backup metadata | Pass | Android `allowBackup="false"`, `backup_rules.xml`, `data_extraction_rules.xml`; `BackupRulesTest`, `BackupSeedTest` |
| Web CSP and security headers | Pass | Container smoke returned CSP, `X-Content-Type-Options`, `Referrer-Policy`, `Permissions-Policy`, and `Cross-Origin-Opener-Policy` on every path |
| Android secret storage | Pass | Tink AEAD with Android Keystore in `TinkSecretCipher`/`SecretStore`; `KeystoreProofTest`, `KeystoreRestartProofTest`, `TinkSecretCipherTest` |
| Rust secret storage (not shipping) | Not applicable | Post-preview; M3.5 `security.md` records the OS credential store and encrypted fallback |
| No analytics or telemetry | Pass | `web/public/config.json` has `telemetry: false`; no analytics/telemetry scripts in `web/src` or `index.html`; About screen states no analytics are loaded |
| No hidden network destinations | Pass | Provider origins come from `contracts/providers.json`; no server-side proxy; `deployment.md` states the app is client-only |
| No server-side prompt proxying | Pass | Static container serves only `web/dist`; no API routes; `deploy/Caddyfile` has no proxy directive |
| Plaintext-export warnings | Pass | Web `EXPORT_PLAINTEXT_WARNING` in `exportService.ts` and `DataExportPanel.tsx`; Android `EXPORT_PLAINTEXT_WARNING` and `ExportPlaintextWarningDialog` |

The container image secret-pattern scan returned `no secret patterns found`.

## 6. Deployment verification

Built with `docker build -f deploy/Dockerfile -t chatui-web:gate .` and run with
`docker run -d --name chatui-web-gate -p 8080:80 chatui-web:gate`. Checks used
Node `fetch` because the environment denies `curl`.

| Path | Status | `Cache-Control` | Security headers |
| --- | --- | --- | --- |
| `/` | 200 | `no-cache` | CSP, nosniff, no-referrer, permissions, COOP present |
| `/config.json` | 200 | `no-cache` | Present |
| `/healthz` | 200 | `no-cache` | Present; empty body |
| `/some/deep/route` | 200 | `no-cache` | Present; SPA shell returned |
| `/assets/index-C6EeZYKY.js` | 200 | `public, max-age=31536000, immutable` | Present |

`/config.json` contains only public fields: `appName`, `defaultTheme`,
`defaultProviderPreset`, `enabledPresets`, `allowCustomProviders`,
`openRouter.attributionOrigin`, `openRouter.appTitle`, `docsUrl`, and
`telemetry: false`. No key, token, or private endpoint is present.

Android signing policy is documented in `release.md` and enforced by
`android/app/build.gradle.kts`: the four signing secrets are required when
`RELEASE_SIGNING_REQUIRED=true`, the build fails closed when they are missing,
and the keystore is never committed. Local unsigned release APK/AAB builds pass.
The signed artifacts are produced by the tag pipeline; this environment has no
runner or signing secrets, so it cannot produce them. That is a pre-publish
execution step, not an open release blocker.

Rust update-manifest signing is documented in the M3.5 `update-signing.md` and
`release.md` as post-preview / parallel. No Rust signing secret is required for
`v0.1.0`.

## 7. Definition of done (spec 21.7)

| Item | Result | Evidence |
| --- | --- | --- |
| New user can install/launch, add an OpenRouter key, pick a model, and chat in under two minutes | Met | Onboarding flow and `e2e/onboarding.spec.ts`; M1 manual smoke |
| OpenRouter works end-to-end on web and Android | Met | Section 4; Rust is post-preview |
| Streaming, stop, regenerate, edit, and error recovery work on every enabled client | Met | Cases 5, 8, 15, 17, 21; `MessageActions.test.tsx`, `ChatMessageActionsTest` |
| OpenRouter, OpenAI-compatible, Anthropic, Gemini, and Ollama work on web and Android where CORS permits | Met | Cases 11-14; provider adapter tests on both clients |
| Conversations persist, search, export, and import correctly | Met | Cases 24-25; persistence and search tests |
| Markdown, code, and math render safely | Met | Case 22; Android math is the documented raw-source fallback |
| Attachments work with capable models and are blocked or warned otherwise | Met | Case 19; attachment tests |
| Accessibility and performance budgets are met | Met | Lighthouse mobile performance 92, accessibility 100; axe 0 serious/critical; `M4-performance.md`, `evidence/accessibility/` |
| No keys or chat content leave the device except to the chosen provider | Met | Section 5; no analytics, telemetry, or proxy |
| Static deployment and container deployment are documented and tested | Met | Section 6; `deployment.md` |
| Parity checklist is complete and reviewed | Met | Section 2; `contracts/parity.md` |
| Rust is explicitly post-preview or meets the same bar | Met (exempted) | Rust is `post-preview / parallel` per DEC-M4-01; no Rust release claim |

## 8. Release notes, migration notes, known issues, and blockers

Release notes and migration notes are in [`release-notes.md`](release-notes.md).
The version integers are frozen in `M4-decisions.md` DEC-M4-03/DEC-M4-04:
app `0.1.0`, contract `1`, backup/export `1`, web IndexedDB `1`, Android Room
`1`, Android export `1`, Rust migration head `0001_initial.sql` (not shipping).

Known issues carried into the release:

- Android math typesetting uses the documented raw-source fallback.
- Web case 23 has a manual code check, not an automated 10,000-message test.
- Case 28 shows the `Retry-After` delay as static copy, not a ticking countdown.
- GitHub Pages cannot set response headers; use the container, Cloudflare Pages,
  Netlify, Vercel, or roksky when the CSP/cache contract is required.
- Rust desktop is post-preview / parallel; its `cargo deny`/`cargo audit`
  advisories are not phase 1 blockers.
- Signed Android APK/AAB production requires the tag pipeline and the four
  signing secrets; this environment cannot run it.

Release blockers: **none** for `0.1.0`.

## 9. Final gate decision

**Pass.**

Every parity row has Web/Android evidence and an honest Rust status. Every
critical case 1-28 has evidence on every enabled client, with the two recorded
limitations above. OpenRouter conformance passes for `/key`, `/models`,
streaming, reasoning, usage/cost, routing, errors, and browser CORS on web. The
security/privacy and deployment checks pass. The spec 21.7 definition of done is
met or explicitly exempted. Release notes, migration notes, known issues, and an
empty blocker list exist.

No release blocker remains. The signed Android artifacts and the GHCR image are
produced by the tag pipeline documented in `release.md`; this environment cannot
execute that pipeline, and no artifact is claimed as produced here.

## 10. Evidence index

- [`contracts/parity.md`](../../../contracts/parity.md) - commitment table and
  release status
- [`M4-decisions.md`](M4-decisions.md) - scope, Rust decision, versions,
  exceptions
- [`M4-performance.md`](M4-performance.md) and
  [`evidence/performance/`](evidence/performance/) - Lighthouse, bundle,
  10,000-message scroll, Android sizes and cold start
- [`evidence/accessibility/`](evidence/accessibility/) - axe reports, keyboard,
  reduced motion, TalkBack
- [`evidence/pwa/`](evidence/pwa/) - service worker, cache policy, offline
- [`evidence/deployment/`](evidence/deployment/) - container smoke and headers
- [`evidence/ci/`](evidence/ci/) - workflow validation and environment blocker
- [`release-notes.md`](release-notes.md) and [`release.md`](release.md) -
  release notes, migration, artifacts, signing policy
- [`deployment.md`](deployment.md) - static and container deployment
