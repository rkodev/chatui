# M2 Providers and Content Implementation Plan

- Source milestone: [M2-providers-and-content.md](../milestones/M2-providers-and-content.md)
- Task breakdown: [M2 task list](M2/README.md)
- Authoritative spec: [IMPLEMENTATION.md](../IMPLEMENTATION.md)
- Foundation baseline: [M1 verification evidence](M1/M1-verification.md)
- Status: Proposed
- Prepared: 2026-11-04
- Scope: generalize the OpenRouter transport to the launch providers; add reasoning, math, parameters, conversation settings, attachments, search, pin/archive, export/import, data controls, settings surfaces, Playwright coverage, and parity evidence
- Platforms: web and Android
- Planning assumption: M1 is complete and its OpenRouter behavior, contracts, persistence primitives, secret stores, chat runners, and evidence are the baseline. Rust remains untouched and out of scope.

## 1. Summary

M1 delivered OpenRouter-only chat on web and Android, including fixture conformance, streaming, reasoning, usage/cost, routing, onboarding, model management, persistence, recovery, Markdown, and code copy. M2 starts from that baseline and must generalize the client without regressing OpenRouter.

The repository already has most of the foundation: provider presets for every launch provider, raw stream and model fixtures, web attachment storage primitives, Android attachment metadata tables, typed settings stores, conversation repositories, and a large M1 test suite. The missing work is the provider-specific transport and adapters, the content and parameter surfaces, the attachment product flow, conversation search/pin/archive, export/import, full settings screens, and the web end-to-end suite. No M2 feature is complete today.

| Area | Current completion | Assessment |
| --- | --- | --- |
| Shared provider contracts | Foundation complete | All 14 presets, schemas, raw fixtures, and parity table exist |
| Shared expected conformance vectors | Partial | Expected vectors and request vectors cover OpenRouter only; no expected outputs for OpenAI, Anthropic, Gemini, or Ollama |
| Web provider layer | OpenRouter only | Registry is M1-gated; runtime config enables only OpenRouter; no generic transport, native adapters, Custom provider creation, or multi-provider CORS handling |
| Android provider layer | OpenRouter only | `ProviderAdapter` and `OpenRouterAdapter`; no generic transport, Anthropic, Gemini, Ollama, or Custom provider creation |
| Reasoning and rich content | Partial | OpenRouter reasoning, Markdown, code copy, and web code wrap exist; math is absent and multi-provider reasoning mapping is absent |
| Parameters and conversation settings | Partial | Data model and OpenRouter request builder support parameters; there is no per-conversation settings surface and no general extra-JSON editing |
| Attachments | Primitive only | Web has OPFS/IndexedDB attachment storage and metadata repositories; Android has attachment entities/DAOs. No picker, downscale, EXIF strip, chip UI, provider content parts, or cleanup flow |
| Conversation management | Partial | Date grouping, rename, delete, and web undo exist. Search, pin, archive view, and Android undo are absent |
| Export/import and data controls | Missing | No export, import, storage usage, clear-all, or per-key deletion UI |
| Settings surfaces | Partial | Web has provider/models screens; appearance/data/backup/about are placeholders. Android settings is a placeholder and provider management is not wired into the shell |
| Web Playwright suite | Missing | No Playwright configuration or E2E tests |
| Parity and evidence | Missing for M2 | Parity records M1 only; no M2 verification evidence |
| M2 exit gate | Not met | No M2 feature is complete end-to-end |

### 1.1 Critical path

```text
M2-00 Shared expected vectors, provider fixtures, CORS matrix plan
   |
   +--> M2-01 Web OpenAI-compatible transport + presets ----+
   |                                                        |
   +--> M2-02 Web Anthropic/Gemini/Ollama + CORS ------------+--> M2-03 Web content/attachments/params/settings
   |                                                        |
   +--> M2-05 Android OpenAI-compatible transport + presets +--> M2-06 Android content/attachments/params/settings
   |                                                        |
   +--> M2-07 Android Anthropic/Gemini/Ollama + CORS --------+
   |
   +--> M2-04 Web conversations/data/Playwright
   +--> M2-08 Android conversations/data
   |
   +--> M2-09 Conformance, parity, critical cases, exit gate
```

M2-00 must land first because both platforms need the same normalized expectations for the new provider streams and catalogs. Web and Android then proceed independently. Content/attachment work depends on the provider adapters because request serialization differs per provider. Conversation and data work can run in parallel with content once the storage contracts are settled. M2-09 is the final gate.

### 1.2 Estimate

The milestone target is two weeks.

| Staffing | Estimate | Notes |
| --- | --- | --- |
| One web implementer plus one Android implementer | 10-14 working days | The intended split; shared conformance and CORS verification are the main coordination points |
| One implementer | 20-25 working days | Attachments, export/import, and Playwright on both platforms are too large for a single two-week stream |
| Two implementers plus a shared verification owner | 9-12 working days | Verification owner can build the CORS matrix, Playwright harness, and evidence template early |

The largest schedule risks are unknown provider CORS, native adapter stream-shape differences, attachment serialization per provider, and export/import as a security boundary. They are treated as explicit decisions in section 8.

## 2. Current-State Audit

### 2.1 Reusable M1 assets

| Asset | Current state | M2 impact |
| --- | --- | --- |
| `contracts/providers.json` | All 14 presets including OpenAI-compatible providers, Anthropic, Gemini, Ollama, LM Studio, llama.cpp, and Custom | Provider metadata can be generalized without duplicating presets in client code |
| `contracts/fixtures/streams/` | Raw fixtures for OpenRouter, OpenAI text/reasoning, Anthropic, Gemini, Ollama NDJSON, and a 429 stream | Each adapter can start from fixture-driven tests |
| `contracts/fixtures/models/` | Raw catalogs for OpenRouter, OpenAI, Anthropic, Gemini, and Ollama tags | Model mapping tests can begin per provider |
| `contracts/fixtures/expected/` | Expected normalized vectors for OpenRouter only | Shared M2 expectations must be added before adapter work is meaningful |
| `contracts/fixtures/requests/` | OpenRouter request vectors only | Per-provider request vectors must be added |
| `contracts/schema/chat-export.schema.json` | Complete export shape including attachments and settings | Export/import implementation has a normative schema |
| `contracts/schema/settings.schema.json` | Complete settings shape | Settings UI and wiring can follow the schema |
| `web/src/providers/` | OpenRouter adapter, SSE transport, retry policy, catalog cache, registry, catalog/key mappers | Generalize the existing transport instead of creating a second stack |
| `web/src/features/providers/` | Provider list/detail, key validation, rotation/removal, model picker, defaults, routing | Extend for multiple providers and Custom creation |
| `web/src/features/conversations/` | Date grouping, rename, delete, 5-second undo, last-active restore, recovery | Add search, pin, archive, and export/import integration |
| `web/src/storage/AttachmentStore.ts` | OPFS with IndexedDB blob fallback, content-addressed by SHA-256 | Attachment product flow can store bytes locally |
| `web/src/storage/AttachmentRepository.ts` | Attachment metadata CRUD by message and hash | Attachments can be persisted transactionally |
| `web/src/features/chat/` | Chat runner, streaming store, Markdown, code highlighting, copy, wrap toggle, actions, windowed list | Add math, reasoning normalization, attachment rendering, and parameters |
| `android/core/network/` | OpenRouter adapter, SSE transport, retry policy, registry, catalog/key mapping | Generalize the existing transport and add native adapters |
| `android/core/database/` | Room entities for messages, attachments, providers, models, FTS4 search, recovery | Attachment metadata, FTS search, and pin/archive storage already exist |
| `android/core/datastore/` | Typed settings store and Tink-backed secret store | Settings and secret wiring can be extended without schema work |
| `android/feature/providers/` | Provider management service, list/detail, model picker, defaults, routing | Wire into the shell and extend for multiple providers/Custom |
| `android/feature/chat/` | Chat runner, ViewModel, Markdown, code copy, actions, list paging | Add math, attachments, parameters, and provider-specific content parts |
| M1 tests and evidence | Adapter, persistence, chat, recovery, Markdown, and critical-case evidence | Regression baseline for M2 |

### 2.2 Confirmed missing areas

| Path or capability | Current state | Consequence |
| --- | --- | --- |
| `contracts/fixtures/expected/` | OpenRouter only | No shared normalized target for non-OpenRouter streams |
| `contracts/fixtures/requests/` | OpenRouter only | No request serialization target for Anthropic, Gemini, or Ollama |
| `web/public/config.json` | Only OpenRouter enabled; `allowCustomProviders` false | Additional presets cannot be selected |
| `web/src/providers/registry.ts` | `M1_ENABLED_PRESET_IDS = ['openrouter']` | M1 gating must be replaced with milestone-appropriate enablement |
| `web/src/providers/openrouter.ts` and `openrouterStream.ts` | OpenRouter-specific naming, headers, body, errors | No general OpenAI-compatible transport or native adapters |
| `web/src/app/routes/placeholders.tsx` | Appearance, data, backup, and about are placeholders | Settings surfaces are incomplete |
| `web/src/features/attachments/` | Only `.gitkeep` | No picker, downscale, paste, drag-and-drop, chips, or send integration |
| `web/src/features/chat/MarkdownRenderer.tsx` | No math plugin; remote images always blocked | Math rendering and the remote-image setting are missing |
| `web/src/features/chat/chatRequest.ts` | Text-only message normalization | Attachments are never sent to providers |
| `web/src/storage/MessageRepository.ts` | No text search API | Web search requires an index or bounded scan strategy |
| `web/src/storage/ConversationRepository.ts` | No title/message search | Search UI has no data source |
| `web/e2e/` or Playwright config | Does not exist | No M2 end-to-end suite |
| `android/core/network/` | OpenRouter-specific adapter and registry gate | No generic transport, native adapters, or Custom provider creation |
| `android/feature/providers/` | Real screens exist but shell still renders `ProvidersPlaceholder` | Provider management is unreachable in the app |
| `android/feature/settings/` | `SettingsPlaceholder` only | No appearance, chat defaults, data, or about screens |
| `android/feature/chat/ChatRequestBuilder.kt` | Text-only message normalization | No image/file content parts or provider-specific serialization |
| `android/feature/chat/ChatCodeBlock.kt` | Copy only; no wrap/scroll toggle | Code rendering feature is incomplete |
| `android/core/database/` | FTS query exists, but no search service or UI | Search is not user-accessible |
| `android/feature/conversations/` | Rename and delete only; hard delete with no undo | Pin, archive, search, and undo are missing |
| `android` export/import | No exporter, importer, or data-controls screen | Required data portability is absent |
| `contracts/parity.md` | M1 implementation status only | M2 cannot overclaim or record evidence |

### 2.3 Foundation-to-feature gaps that are easy to miss

- The web runtime config schema hard-codes M1 gating. Removing the gate requires an explicit M2 config contract, not just a changed constant.
- The web provider registry's `getPresetAvailability()` and the Android registry's `getPresetAvailability()` both return "coming later" for non-OpenRouter presets. UI copy and tests must change.
- Both clients currently name OpenRouter errors and catalog types directly. Generalizing the transport must not leave OpenRouter-specific names in shared adapter contracts.
- The web `ProviderAdapter` type only exposes `validateKey` and `listModels`; streaming is a separate exported function. M2 should introduce one adapter surface that covers validation, discovery, and streaming per preset.
- Anthropic requires a `system` top-level field and base64 image blocks; Gemini requires `systemInstruction`, `inlineData`, and `alt=sse`; Ollama requires native NDJSON and `/api/tags`. None of these can be handled by reusing the OpenRouter body builder unchanged.
- The `params.extra` object currently has no provider namespace contract. M2 must define whether extras are nested by provider preset and must keep `model`, `messages`, `stream`, and `stream_options` protected.
- Web attachment bytes are stored correctly but no code maps content parts to provider request bodies. The send path currently drops images and files.
- Android has FTS4 search tables but no repository method that joins search results back to conversations for a sidebar search view.
- Web has no full-text index. Search must either add an IndexedDB index, scan bounded pages, or maintain a search field on message writes.
- Export/import must preserve provider/model snapshots and attachment references. The current repositories do not expose a bulk export/import transaction.
- The Android shell still shows provider and settings placeholders even though provider screens exist. M2 must wire navigation before UI tests or evidence can cover those surfaces.
- M1 browser CORS evidence exists only for OpenRouter. Every other preset remains `unknown`; CORS verification is a release input, not a post-release cleanup.
- Model catalogs for non-OpenRouter providers may not expose modalities or supported parameters. Capability inference from preset metadata must be shared and documented.
- The web Playwright suite is absent, so the M2 verification command in the milestone currently cannot run.

## 3. Gap Analysis

Status values:

- **Missing**: no implementation exists.
- **Partial**: foundation exists but the M2 deliverable is not met.
- **Present**: complete for M2, subject only to verification.
- **Blocked**: cannot be completed until a dependency or decision lands.

### 3.1 Shared contracts, fixtures, and CORS

| ID | Required deliverable | Current | Gap | Resolution / owner | Verification |
| --- | --- | --- | --- | --- | --- |
| S-01 | Expected normalized output for every non-OpenRouter stream fixture | Only OpenRouter expected vectors exist | Adapters have no shared target for text, reasoning, usage, finish, and errors | Add expected JSON for OpenAI text/reasoning, Anthropic, Gemini, Ollama, keep-alive/error/429 cases; extend `scripts/validate-contracts.mjs`; M2-00 | `make contracts-check` plus fixture tests on both clients |
| S-02 | Expected normalized catalogs for OpenAI, Anthropic, Gemini, and Ollama | Raw model fixtures only | Mapping differences are undefined across clients | Add expected model catalogs with IDs, labels, context, modalities, capabilities, and pricing behavior; M2-00 | Fixture mapping tests on web and Android |
| S-03 | Provider-specific error fixtures | Only OpenRouter errors and a generic 429 stream exist | Anthropic/Gemini/Ollama/OpenAI errors may map differently | Add HTTP and in-stream error fixtures; include auth, rate limit, model not found, context length, and provider errors; M2-00 | `make contracts-check` and adapter error tests |
| S-04 | Request serialization vectors per provider | OpenRouter request vectors only | No shared target for system prompts, content parts, or provider extras | Add OpenAI-compatible, Anthropic, Gemini, and Ollama request vectors; M2-00 | Both clients test against the vectors |
| S-05 | Browser CORS matrix for every launch provider | `browserCors` is `unknown` for all non-OpenRouter presets | Web cannot claim providers work without verification | Verify `/models`, key validation, and chat streaming where applicable for each preset; update `contracts/providers.json`; document proxy-free fallback; M2-00/M2-09 | CORS matrix evidence |
| S-06 | Capability inference rules | OpenRouter-specific mapping only | Non-OpenRouter catalogs may omit modalities and supported parameters | Define shared inference from preset metadata with explicit defaults and unavailable states; M2-00 | Unit tests on both clients |
| S-07 | Math rendering contract and fixtures | No math renderer or fixtures | Inline/block math and raw-source fallback are undefined | Add representative valid, invalid, and streaming-incomplete math fixtures; M2-00/M2-03/M2-06 | Rendering tests on both clients |
| S-08 | Export/import round-trip fixtures | Schema exists, no fixture archive | Import behavior and preservation rules are unproven | Add a shared `chatui.export` fixture with IDs, ordering, snapshots, and optional attachment bytes; M2-00 | Round-trip tests on both clients |
| S-09 | Default model and fallback lists per preset | Only `defaultModelId` in presets; no fallback list | Onboarding can fail when discovery is blocked | Define a shared fallback model list or deterministic fallback rule; M2-00 | Provider tests |
| S-10 | CORS/error copy contract | OpenRouter-specific copy only | Users may see provider-inappropriate messages | Add shared error categories and provider-specific actionable copy; M2-00 | UI copy tests |

### 3.2 Web transport and provider adapters

| ID | Required deliverable | Current | Gap | Resolution / owner | Verification |
| --- | --- | --- | --- | --- | --- |
| W-01 | OpenAI-compatible transport parameterized by preset | OpenRouter-specific implementation | No generic transport for OpenAI, Groq, Mistral, DeepSeek, xAI, Together, Fireworks, LM Studio, llama.cpp, or Custom | Extract a preset-driven transport while preserving OpenRouter vectors; M2-01 | Fixture conformance and OpenRouter regression |
| W-02 | Enable launch presets and Custom providers | Runtime config and registry allow OpenRouter only | Other providers cannot be chosen or created | Replace M1 gating with M2 enablement, configurable allowed presets, and Custom creation; M2-01 | Onboarding/provider tests |
| W-03 | Custom provider input | No base URL, auth style, or non-secret header UI | Custom preset is unusable | Add validated base URL, auth mode, and non-secret headers with no secrets in records/logs; M2-01 | Service and UI tests |
| W-04 | Anthropic adapter | Missing | No `x-api-key`, `anthropic-version`, browser-access header, top-level system, base64 images, or thinking mapping | Implement native adapter from fixtures; M2-02 | Anthropic fixture tests |
| W-05 | Gemini adapter | Missing | No `alt=sse`, `x-goog-api-key`, `usageMetadata`, `systemInstruction`, `inlineData`, or finish mapping | Implement native adapter from fixtures; M2-02 | Gemini fixture tests |
| W-06 | Ollama adapter | Missing | No native `/api/chat` NDJSON, `/api/tags`, or OpenAI-compatible fallback | Implement native-first adapter and document `OLLAMA_ORIGINS`; M2-02 | Ollama fixture tests |
| W-07 | Per-preset model discovery and fallback | OpenRouter discovery only | Other providers cannot populate a usable catalog | Add per-format discovery, cache keys, last-known-good, and fallback model lists; M2-01/M2-02 | Adapter and model service tests |
| W-08 | Capability inference from presets | Missing | Models may appear text-only or unusable | Apply shared inference rules when catalog fields are absent; M2-01/M2-02 | Mapping tests |
| W-09 | CORS classification and actionable errors | OpenRouter network errors map to generic network errors | Browser users cannot tell CORS failure from a real outage | Add `cors` classification, provider-specific copy, and a no-proxy fallback; M2-02 | Error tests and manual browser checks |
| W-10 | Reasoning mapping across providers | OpenRouter reasoning only | DeepSeek `reasoning_content`, Anthropic thinking, and other reasoning are not separated | Normalize research text per provider and never replay it unless required; M2-01/M2-02 | Reasoning tests |
| W-11 | Provider error normalization | OpenRouter error mapping only | Anthropic/Gemini/Ollama error envelopes may become raw provider errors | Add shared error mapping and preserve request IDs/status where available; M2-02 | Fixture error tests |
| W-12 | Preserve OpenRouter-only behavior | OpenRouter works in M1 | Generalization can regress routing, in-stream errors, free badge, cache, and attribution | Keep the OpenRouter fixture suite as the reference gate; M2-01/M2-02 | Full M1 regression suite |
| W-13 | Provider metadata UI for all presets | Provider list only handles OpenRouter creation | Other presets lack add/edit/CORS/key flows | Extend provider management for preset selection and Custom; M2-01 | Provider UI tests |
| W-14 | Provider documentation and CORS status | Partial | New providers need docs and honest CORS/status copy | Use contract `docsUrl` and `browserCors`; M2-01/M2-02 | UI tests |

### 3.3 Web content, parameters, attachments, and settings

| ID | Required deliverable | Current | Gap | Resolution / owner | Verification |
| --- | --- | --- | --- | --- | --- |
| W-15 | Math rendering with raw-source fallback | Missing | LaTeX inline/block math is not rendered | Add a safe math renderer, never execute scripts, and fall back to raw source on failure; M2-03 | Rendering and XSS tests |
| W-16 | Code rendering completion | Copy and wrap toggle exist | Wrapping behavior and incomplete-fence streaming need verification for all providers | Reuse existing CodeBlock with verification tests; M2-03 | Code tests |
| W-17 | Remote-image setting and per-message load | Renderer always blocks remote images | The user-facing setting is not wired | Add the setting, re-render behavior, and privacy copy; M2-03 | Settings and Markdown tests |
| W-18 | Per-conversation system prompt and parameters | Data model and OpenRouter builder support params; no UI | Users cannot tune generation per conversation | Add a conversation settings surface with system prompt, temperature, top P, max tokens, stop, reasoning effort, streaming, and extras; M2-03 | UI and request tests |
| W-19 | Unsupported-parameter note | Request builder omits unsupported params silently | Users cannot tell why a value was ignored | Show an inline note when a value is unsupported; M2-03 | UI tests |
| W-20 | General provider extras and protected fields | OpenRouter-specific `params.extra` handling | Other providers cannot use extras safely | Define the namespace, validate JSON, and keep `model`, `messages`, `stream`, and `stream_options` protected; M2-01/M2-03 | Request vector tests |
| W-21 | Conversation settings surface | Missing | No route or sheet for model, prompt, params, routing, export, delete | Add the web route/sheet from spec 8.3 and 11; M2-03 | Route and component tests |
| W-22 | Attachment picker and drag-and-drop | Missing | No user input path | Add picker, drop zone, file validation, and size limits using existing storage; M2-03 | Component and browser tests |
| W-23 | Clipboard image paste | Missing | No paste path | Handle clipboard images, validate, and add to composer; M2-03 | Component tests |
| W-24 | Image downscale, re-encode, and metadata stripping | Missing | Large images and metadata may leak or fail provider limits | Downscale to 2048 px longest edge, re-encode where appropriate, strip metadata except orientation; M2-03 | Image processing tests |
| W-25 | Composer/message attachment chips and removal | Missing | Attachments cannot be reviewed or removed | Show thumbnails/chips, support removal before send, and render attachments in messages; M2-03 | UI tests |
| W-26 | Provider-specific attachment content parts | Chat request is text-only | Images/files are dropped before send | Serialize OpenAI data URLs, Anthropic base64 blocks, Gemini `inlineData`, and text-file headers; M2-01/M2-03 | Request vector and adapter tests |
| W-27 | Capability warnings and model-switch confirmation | Missing | Users can send unsupported attachments | Warn for text-only models and confirm attachment loss when switching; M2-03 | UI tests |
| W-28 | Attachment persistence, reload, and orphan cleanup | Storage/repository primitives exist only | Attachments may be lost or orphaned after edits/deletes | Wire metadata transactions and cleanup; M2-03 | Persistence/reload tests |
| W-29 | Full settings routes | Appearance/data/backup/about are placeholders | Required settings surfaces are incomplete | Implement appearance, data, backup placeholder, and about/privacy; M2-03 | Route tests |
| W-30 | Behavior wiring for settings | Send key, usage, last active, defaults partly wired | Remote images and full default params/prompt behavior are incomplete | Wire every setting that affects behavior; M2-03 | Settings regression tests |

### 3.4 Web conversations, data, and Playwright

| ID | Required deliverable | Current | Gap | Resolution / owner | Verification |
| --- | --- | --- | --- | --- | --- |
| W-31 | Conversation search | Missing | No search UI or data source | Add debounced title/message search with empty/loading states; M2-04 | Unit/component/E2E tests |
| W-32 | Pin and archive | Domain/repository flags exist; no UI | Users cannot organize history | Add pin/unpin, archive/unarchive, pinned-first ordering, and archive view; M2-04 | UI tests |
| W-33 | Delete undo on web | Present for web | Needs regression coverage with attachments and search | Keep the 5-second undo and verify attachment cleanup semantics; M2-04 | E2E test |
| W-34 | Per-conversation export | Missing | No JSON or Markdown export | Export conversation, messages, snapshots, and attachment references; M2-04 | Round-trip tests |
| W-35 | Full data archive export | Missing | No `chatui.export` archive | Export conversations, messages, attachments (optional bytes), providers, models, and settings; exclude secrets by default; M2-04 | Schema validation and round-trip |
| W-36 | Import validation and merge | Missing | No untrusted import boundary | Validate schema, size, IDs, paths, and attachment names; merge by default, optional replace, and never mutate on invalid input; M2-04 | Invalid-import and round-trip tests |
| W-37 | Data controls | Missing | No storage usage, clear-all, per-key deletion, or export/import entry points | Add the data settings screen with confirmation and warnings; M2-04 | UI and persistence tests |
| W-38 | Preserve IDs, order, snapshots, and attachments | Missing | Import/export preservation is unproven | Add round-trip invariants and attachment reference checks; M2-04 | E2E tests |
| W-39 | Playwright setup and suite | Missing | Milestone verification command cannot run | Add Playwright config, mocked provider streams, and the required onboarding/chat/model/provider/reload/export/delete/clear/offline/error flows; M2-04 | `pnpm exec playwright test` |
| W-40 | Settings/data routes reachable from shell | Placeholders only | Navigation may hide completed screens | Wire all settings routes in the shell and lock navigation in tests; M2-03/M2-04 | Route/E2E tests |

### 3.5 Android transport and provider adapters

| ID | Required deliverable | Current | Gap | Resolution / owner | Verification |
| --- | --- | --- | --- | --- | --- |
| A-01 | OpenAI-compatible transport parameterized by preset | OpenRouter-specific implementation | No generic transport for additional presets | Extract preset-driven transport and keep OpenRouter vectors; M2-05 | Fixture and M1 regression tests |
| A-02 | Enable launch presets and Custom providers | Registry enables OpenRouter only | Other providers cannot be selected or created | Replace M1 gating with M2 enablement and Custom creation; M2-05 | Provider tests |
| A-03 | Custom provider input | Missing | No base URL, auth style, or non-secret headers | Add validated Custom provider flow without secret leakage; M2-05 | Service/UI tests |
| A-04 | Anthropic adapter | Missing | Native headers, top-level system, base64 images, and thinking mapping are absent | Implement from fixtures; M2-06 | Anthropic fixture tests |
| A-05 | Gemini adapter | Missing | `alt=sse`, `x-goog-api-key`, usage metadata, system instruction, inline data, and finish mapping are absent | Implement from fixtures; M2-06 | Gemini fixture tests |
| A-06 | Ollama adapter | Missing | No NDJSON streaming, `/api/tags`, or OpenAI-compatible fallback | Implement native-first adapter; M2-06 | Ollama fixture tests |
| A-07 | Per-preset discovery and fallback | OpenRouter only | Non-OpenRouter catalogs cannot populate | Add per-format discovery, cache, last-known-good, and fallback; M2-05/M2-06 | Model service tests |
| A-08 | Capability inference | Missing | Non-OpenRouter models may lack modalities/params | Apply shared inference rules; M2-05/M2-06 | Mapping tests |
| A-09 | Reasoning and error mapping | OpenRouter only | Other provider streams may lose reasoning or error semantics | Normalize reasoning and errors per provider; M2-05/M2-06 | Fixture tests |
| A-10 | OpenRouter regression | M1 suite exists | Generalization may regress OpenRouter | Keep M1 test suite as gate; M2-05/M2-06 | Full M1 Android suite |
| A-11 | Provider UI for all presets | Provider screens support OpenRouter creation only | Preset selection and Custom flow are incomplete | Extend provider management UI; M2-05 | UI/ViewModel tests |
| A-12 | Wire provider management into the shell | `ProvidersPlaceholder` is still routed | Implemented screens are unreachable | Replace placeholder with provider routes; M2-05 | Navigation test |

### 3.6 Android content, parameters, attachments, and settings

| ID | Required deliverable | Current | Gap | Resolution / owner | Verification |
| --- | --- | --- | --- | --- | --- |
| A-13 | Math rendering | Missing | No LaTeX inline/block rendering or fallback | Add a safe math renderer with raw fallback; M2-06 | Rendering tests |
| A-14 | Code wrap/scroll toggle | Copy only | Long code lines cannot be toggled | Add wrap/scroll behavior while preserving exact copy; M2-06 | UI tests |
| A-15 | Remote-image setting and rendering | Renderer blocks remote images | No user-facing load action | Wire the setting and safe per-message behavior; M2-06 | UI/Markdown tests |
| A-16 | Per-conversation system prompt and parameters | Data model supports params; no UI | Users cannot tune generation per conversation | Add a full-height conversation settings sheet; M2-06 | ViewModel/UI tests |
| A-17 | Unsupported-parameter note | Missing | Users cannot see ignored values | Show an inline note; M2-06 | UI tests |
| A-18 | General provider extras and protected fields | OpenRouter-specific extra handling | Other providers cannot use extras safely | Define namespace, validate JSON, protect core fields; M2-05/M2-06 | Request tests |
| A-19 | Conversation settings sheet | Missing | No model, prompt, params, routing, export, or delete surface | Add the full-height sheet from spec 9.3; M2-06 | UI tests |
| A-20 | Attachment picker, photo picker, SAF documents | Missing | No attachment input path | Add photo picker, document picker, and file validation; M2-06 | Device/UI tests |
| A-21 | Clipboard image paste and drag-and-drop | Missing | ChromeOS/tablet input is unsupported | Add clipboard paste and drag-and-drop; M2-06 | Device tests |
| A-22 | Image downscale, EXIF strip, content-addressed storage | Missing | Bytes are neither stored nor normalized | Downscale to 2048 px, strip EXIF except orientation, store under `filesDir/attachments/<sha256>`; M2-06 | Unit/device tests |
| A-23 | Composer/message chips and removal | Missing | Attachments cannot be reviewed/removed | Add chips, thumbnails, and removal; M2-06 | UI tests |
| A-24 | Provider-specific attachment content parts | Chat request is text-only | Images/files are dropped | Serialize OpenAI data URLs, Anthropic base64 blocks, Gemini inline data, and text-file headers; M2-05/M2-06 | Request tests |
| A-25 | Capability warnings and switch confirmation | Missing | Unsupported attachments can be sent | Add warnings and confirmation; M2-06 | UI tests |
| A-26 | Attachment persistence/reload and orphan cleanup | DAO/entity only | Attachment bytes and metadata can diverge | Wire transaction service and startup cleanup; M2-06 | Persistence/reload tests |
| A-27 | Settings screens | `SettingsPlaceholder` only | Appearance, chat defaults, data, about are absent | Implement settings screens and wiring; M2-06 | UI tests |
| A-28 | Behavior wiring for settings | Partial | Defaults, remote images, send key, usage, last active are incomplete | Wire every setting that affects behavior; M2-06 | Settings tests |

### 3.7 Android conversation management, data, and tests

| ID | Required deliverable | Current | Gap | Resolution / owner | Verification |
| --- | --- | --- | --- | --- | --- |
| A-29 | Conversation search service and UI | FTS DAO and repository query exist; no service/UI | Users cannot search history | Add debounced search service, result grouping, empty/loading states; M2-07 | Repository/UI tests |
| A-30 | Pin and archive | Repository methods exist; no UI | History cannot be organized | Add pin/unpin, archive/unarchive, archive view, and pinned-first ordering; M2-07 | ViewModel/UI tests |
| A-31 | Delete undo | Hard delete with confirmation, no undo | Accidental deletion is irreversible | Add a buffered 5-second undo or documented equivalent; M2-07 | ViewModel/UI tests |
| A-32 | Per-conversation and full export | Missing | No JSON/Markdown/archive export | Add exporter preserving IDs, order, snapshots, and attachments; M2-07 | Export tests |
| A-33 | Import validation and merge | Missing | No untrusted import boundary | Validate schema, size, IDs, paths, and attachment names; merge by default; M2-07 | Invalid-import tests |
| A-34 | Data controls | Missing | No storage usage, clear-all, per-key deletion, or export/import entry points | Add data settings and confirmations; M2-07 | UI/persistence tests |
| A-35 | Attachment cleanup after delete/edit | Missing | Orphan bytes/metadata can remain | Integrate cleanup with conversation and message edits; M2-07 | Persistence tests |
| A-36 | M2 provider and persistence tests | M1 tests only | No non-OpenRouter provider or export/import tests | Add unit/MockWebServer and Room tests for all M2 flows; M2-05..M2-07 | `make android-test` |
| A-37 | Shell wiring for settings/data/provider routes | Placeholders routed | Real screens may be unreachable | Wire navigation; M2-06/M2-07 | Navigation tests |
| A-38 | Critical cases 11-14, 19, 22-25 evidence | Missing | Exit gate has no Android evidence | Map tests and manual evidence to each case; M2-09 | Evidence table |

### 3.8 Cross-cutting gaps and ambiguities

| ID | Gap | Impact | Proposed resolution |
| --- | --- | --- | --- |
| X-01 | Provider CORS varies and is unverified | Web cannot truthfully enable providers | Build the matrix in M2-00 and treat unsupported providers as documented limitations, never proxy |
| X-02 | Normalized behavior for new providers is undefined | Web and Android can diverge while both pass local tests | S-01/S-02/S-04 expected vectors are the shared gate |
| X-03 | Some catalogs omit capabilities and parameters | Wrong warnings or invalid requests | Shared inference rules with explicit defaults and unavailable states |
| X-04 | Custom providers can direct requests to arbitrary hosts | SSRF-like risk from imported config and cleartext exposure | Validate http(s), warn on cleartext/local hosts, never include secrets in headers or records |
| X-05 | Attachment bytes can be large or malicious | Memory pressure, provider rejection, or path abuse | Enforce type/size limits, downscale, sanitize names, and never execute content |
| X-06 | Export/import is a security boundary | Invalid or hostile archive can corrupt local data | Validate with schema, size, IDs, paths, and transactional merge-before-commit |
| X-07 | Web has no full-text index | Search may scan all messages | Add an index or a bounded cursor scan; never scan the full store on every keystroke |
| X-08 | Delete undo semantics differ by platform | Parity ambiguity | Web keeps buffered undo; Android adds an equivalent or records an approved deferral |
| X-09 | Playwright needs deterministic provider behavior | Flaky E2E | Mock providers and SSE streams through MSW or a local fixture server |
| X-10 | Parity validator treats all pipe tables as one table | Adding an M2 table can fail `make contracts-check` | Add M2 status as a list or deliberately extend the validator before adding a second table |
| X-11 | M3 scope can leak into M2 | Milestone slip | Keep share target, deep links, tablet sidebar, Compose UI tests, screenshots, signing, and baseline profiles out of M2 |
| X-12 | Extra-JSON namespace is unspecified | Provider-specific options may leak across providers | Namespace extras by preset and reject protected fields |
| X-13 | Math renderer choice affects bundle size and XSS surface | Web performance or security regression | Pick one sanitized math renderer, lazy-load it, and add fallback tests |
| X-14 | Provider discovery may be blocked or unsupported | Onboarding fails | Use fallback model lists and actionable error copy |

## 4. Proposed Work Breakdown

Ten planning units are proposed: one shared prerequisite, eight implementation slices mirroring the milestone guidance, and one final evidence gate. The executable task records are in [M2/README.md](M2/README.md).

### 4.1 Task map

| ID | Title | Owns | Depends on | Parallel-safe with |
| --- | --- | --- | --- | --- |
| M2-00 | Shared M2 expectations, provider fixtures, and CORS plan | `contracts/fixtures/expected/`, `contracts/fixtures/requests/`, `contracts/fixtures/errors/`, `scripts/validate-contracts.mjs`, `docs/operations/M2/` skeleton | none | none at start; unblocks all adapter work |
| M2-01 | Web OpenAI-compatible transport, presets, and Custom providers | `web/src/providers/`, `web/src/app/config.ts`, provider registry/config, web provider-layer tests | M2-00 | M2-05 |
| M2-02 | Web Anthropic, Gemini, and Ollama adapters with CORS handling | `web/src/providers/`, provider runtime/CORS classification, adapter tests | M2-00, M2-01 | M2-06 |
| M2-03 | Web reasoning, content, math, parameters, attachments, and settings | `web/src/features/chat/`, `web/src/features/attachments/`, `web/src/features/settings/`, conversation settings route | M2-01, M2-02 | M2-06 |
| M2-04 | Web conversations search/pin/archive, export/import, data controls, Playwright | `web/src/features/conversations/`, data/settings routes, export/import services, `web/e2e/`, Playwright config | M2-01, M2-03 | M2-07 |
| M2-05 | Android OpenAI-compatible transport, presets, Custom providers, and shell wiring | `android/core/network/`, registry/config, `android/feature/providers/`, app shell provider route | M2-00 | M2-01 |
| M2-06 | Android Anthropic, Gemini, Ollama, reasoning, content, attachments, and settings | `android/core/network/`, `android/feature/chat/`, `android/feature/settings/`, attachment storage service | M2-00, M2-05 | M2-02, M2-03 |
| M2-07 | Android conversations search/pin/archive, export/import, data controls, tests | `android/core/database` repository additions, `android/feature/conversations/`, `android/feature/settings/`, export/import services | M2-05, M2-06 | M2-04 |
| M2-08 | Android provider, persistence, and M2-critical-case tests | Android test sources for adapters, attachments, export/import, search, and recovery | M2-05, M2-06, M2-07 | M2-04 |
| M2-09 | Conformance, parity, CORS recording, and exit-gate evidence | `contracts/providers.json`, `contracts/parity.md`, `docs/operations/M2/M2-verification.md` | all prior tasks | none at completion |

### 4.2 M2-00: Shared M2 expectations, provider fixtures, and CORS plan

Goal: define executable shared behavior for the new providers before either client generalizes its transport.

Deliverables:

- Expected normalized streams for OpenAI text/reasoning, Anthropic, Gemini, Ollama, keep-alive, in-stream error, and 429 behavior.
- Expected model-catalog mappings for OpenAI, Anthropic, Gemini, and Ollama.
- Request vectors for OpenAI-compatible, Anthropic, Gemini, and Ollama requests, including system prompts, image/file content parts, and protected-field cases.
- Provider-specific HTTP and in-stream error fixtures.
- Capability inference and fallback-model rules.
- Math and export/import fixture inputs.
- CORS matrix template and verification plan for every preset.
- Validator support for the new vectors.

Acceptance criteria:

- `make contracts-check` validates every new fixture and vector.
- Every fixture is secret-free and uses synthetic data.
- A deliberately broken vector fails with a precise file and field message.
- OpenRouter expectations remain unchanged and continue to pass.
- No client source is introduced except validator support.

Verification:

```bash
make contracts-install
make contracts-check
```

Out of scope: client adapters, UI, E2E, CORS execution, and parity status.

### 4.3 M2-01: Web OpenAI-compatible transport, presets, and Custom providers

Goal: let the web client talk to every OpenAI-compatible preset through one transport while preserving OpenRouter behavior.

Deliverables:

- Preset-driven OpenAI-compatible transport for model discovery and chat streaming.
- Enablement for OpenAI, Groq, Mistral, DeepSeek, xAI, Together, Fireworks, LM Studio, llama.cpp, and Custom.
- Custom provider creation with base URL, auth style, and non-secret headers.
- Per-preset discovery, cache, last-known-good, fallback models, and capability inference.
- Reasoning normalization for OpenAI-compatible streams.
- Provider UI updates for preset selection and editing.
- OpenRouter regression coverage.

Acceptance criteria:

- Rootless OpenRouter behavior and tests still pass.
- OpenAI-compatible fixtures pass on the web transport.
- Custom provider validation rejects invalid URLs, missing auth fields, and secrets in headers.
- Discovery failures produce an actionable state and do not hide cached models.
- No native-adapter or attachment work is added.

Verification:

```bash
make web-lint && make web-typecheck && make web-test && make web-build
```

Out of scope: Anthropic, Gemini, Ollama, attachments, math, search, export/import, and settings routes beyond provider/model management.

### 4.4 M2-02: Web Anthropic, Gemini, and Ollama adapters with CORS handling

Goal: add the native protocol adapters and truthful browser behavior.

Deliverables:

- Anthropic adapter: `x-api-key`, `anthropic-version`, web-only browser-access header, top-level system prompt, base64 image blocks, thinking/text separation, and error mapping.
- Gemini adapter: `alt=sse`, `x-goog-api-key`, `systemInstruction`, `inlineData`, usage metadata, and finish reason mapping.
- Ollama adapter: native NDJSON chat, `/api/tags` discovery, OpenAI-compatible fallback, and `OLLAMA_ORIGINS` documentation.
- CORS classification and actionable provider copy.
- Reasoning and error mapping for each native protocol.
- Browser CORS checks for every preset and updates to `contracts/providers.json`.

Acceptance criteria:

- Fixture tests cover text, reasoning, usage, finish, errors, cancellation, and retry per adapter.
- Anthropic thinking/text remain separate.
- Gemini `MAX_TOKENS`, `SAFETY`, and `RECITATION` map correctly.
- Ollama NDJSON completes and records usage.
- CORS failures produce the `cors` code and never silently proxy.
- OpenRouter regression remains green.

Verification:

```bash
make web-lint && make web-typecheck && make web-test && make web-build
```

Plus the browser CORS matrix evidence.

Out of scope: attachment UI/serialization, math, parameters, search, export/import, and Playwright.

### 4.5 M2-03: Web reasoning, content, math, parameters, attachments, and settings

Goal: complete the web content and generation controls on top of the provider layer.

Deliverables:

- Math rendering with raw-source fallback and XSS-safe behavior.
- Remote-image setting and safe rendering.
- Per-conversation system prompt, parameters, reasoning effort, streaming toggle, provider extras, and protected-field validation.
- Conversation settings route/sheet with model, prompt, params, routing, export, and delete.
- Attachment picker, drag-and-drop, clipboard image paste, downscale/re-encode, metadata stripping, chips, removal, and message rendering.
- Provider-appropriate image/file content parts in requests.
- Capability warnings and model-switch confirmation.
- Appearance, data, backup placeholder, and about/privacy settings routes.
- Settings behavior wiring.

Acceptance criteria:

- Critical cases 19 and 22 are covered, plus the content/parameter parts of the M2 gate.
- Math renders inline and block; invalid math falls back to raw source.
- Remote images load only when explicitly allowed.
- Parameters are omitted when unsupported and a note explains omissions.
- Protected fields cannot be overridden.
- Attachments survive reload, are downscaled, and are never remote dependencies.
- Unsupported attachments warn before send or model switch.
- Settings surfaces are reachable and persist immediately.

Verification:

```bash
make web-lint && make web-typecheck && make web-test && make web-build
```

Plus manual attachment and math smoke checks.

Out of scope: search, pin/archive, export/import, Playwright, and Android work.

### 4.6 M2-04: Web conversations, data, and Playwright

Goal: make web history searchable, organizable, portable, and end-to-end tested.

Deliverables:

- Debounced title/message search with empty/loading states.
- Pin/unpin, archive/unarchive, archive view, and pinned-first ordering.
- Per-conversation JSON/Markdown export and full `chatui.export` archive export.
- Schema-validated import with size limits, ID/path validation, merge-by-default, optional replace, and no mutation on failure.
- Data controls: storage usage, clear all, per-key deletion, export/import entry points, and backup placeholder.
- Playwright configuration and required E2E flows with mocked providers.
- Attachment cleanup and undo regression coverage.

Acceptance criteria:

- Critical cases 22-25 are covered on web.
- Search returns title and message matches with correct states.
- Pin/archive/delete/undo work and persist across reload.
- Export/import round trips preserve IDs, order, model snapshots, and attachment references.
- Invalid imports leave local data unchanged.
- Clearing data removes conversations, keys, and attachments.
- Playwright suite passes.

Verification:

```bash
make web-lint && make web-typecheck && make web-test && make web-build
cd web && pnpm exec playwright test
```

Out of scope: Android conversation/data work and M3/mobile integration.

### 4.7 M2-05: Android OpenAI-compatible transport, presets, Custom providers, and shell wiring

Goal: let Android talk to every OpenAI-compatible preset and reach provider management from the app shell.

Deliverables:

- Preset-driven transport for model discovery and chat streaming.
- Enablement for all OpenAI-compatible presets and Custom creation.
- Per-preset discovery, cache, last-known-good, fallback models, and capability inference.
- Reasoning normalization for OpenAI-compatible streams.
- Provider UI updates and shell navigation replacing `ProvidersPlaceholder`.
- OpenRouter regression coverage.

Acceptance criteria:

- OpenRouter fixtures and M1 tests remain green.
- OpenAI-compatible fixtures pass on Android.
- Custom provider validation rejects invalid input and keeps secrets out of records/logs.
- Provider management is reachable from the shell.
- No native adapters or attachment work is added.

Verification:

```bash
make android-lint && make android-test && make android-assemble
```

Out of scope: Anthropic, Gemini, Ollama, attachments, parameters UI, search, export/import, and settings screens.

### 4.8 M2-06: Android Anthropic, Gemini, Ollama, reasoning, content, attachments, and settings

Goal: complete Android provider-native behavior and the content/settings surfaces.

Deliverables:

- Anthropic, Gemini, and Ollama adapters with fixture conformance.
- Reasoning and error mapping across providers.
- Math rendering and code wrap/scroll behavior.
- Remote-image setting.
- Per-conversation system prompt, parameters, extras, and protected fields.
- Full-height conversation settings sheet.
- Attachment picker, photo picker, SAF documents, clipboard image paste, drag-and-drop, downscale, EXIF strip, content-addressed storage, chips, removal, and message rendering.
- Provider-appropriate image/file content parts.
- Capability warnings and switch confirmation.
- Attachment persistence/reload and orphan cleanup.
- Settings screens and behavior wiring; shell settings route replacement.

Acceptance criteria:

- Critical cases 11-14, 19, and 22 are covered on Android.
- Native adapter fixtures pass, including Anthropic thinking separation, Gemini finish mapping, and Ollama NDJSON.
- Reasoning is displayed separately across providers.
- Math and code render safely.
- Parameters are persisted per conversation and unsupported values are noted.
- Attachments are local, downscaled, EXIF-stripped, persist across reload, and produce capability warnings.
- Settings screens are reachable and settings affect behavior.

Verification:

```bash
make android-lint && make android-test && make android-assemble
```

Plus attachment, math, and settings smoke evidence.

Out of scope: search, pin/archive, export/import, Android Compose UI suite, screenshots, share target, deep links, and tablet layout.

### 4.9 M2-07: Android conversations, data, and tests

Goal: make Android history searchable, organizable, portable, and covered by M2 tests.

Deliverables:

- Search service and UI using FTS, with debounce and result states.
- Pin/unpin, archive/unarchive, archive view, and Android delete undo.
- Per-conversation and full export using `chatui.export`.
- Import validation, merge-by-default, optional replace, and no mutation on failure.
- Data controls: storage usage, clear all, per-key deletion, export/import entry points, and backup placeholder.
- Attachment cleanup after delete/edit.
- Provider, persistence, search, and export/import tests.
- Critical-case evidence placeholders.

Acceptance criteria:

- Critical cases 22-25 are covered on Android.
- Search queries return relevant conversations and messages without scanning in the UI layer.
- Pin/archive/delete/undo persist correctly.
- Export/import round trips preserve IDs, order, snapshots, and attachment references.
- Invalid imports leave data unchanged.
- Clearing data removes conversations, keys, and attachments.

Verification:

```bash
make android-lint && make android-test && make android-assemble
```

Out of scope: Compose UI test suite, Roborazzi screenshots, release configuration, baseline profiles, share target, deep links, and tablet layout.

### 4.10 M2-08: Android provider, persistence, and M2-critical-case tests

Goal: prove Android M2 behavior with unit, MockWebServer, Room, and persistence tests.

Deliverables:

- MockWebServer tests for every provider adapter.
- Repository/service tests for attachments, search, pin/archive, export/import, and data controls.
- Persistence/reload tests for attachments and provider/model snapshots.
- Invalid-import and clear-data tests.
- Mapping from M2 critical cases 11-14, 19, and 22-25 to Android tests and manual evidence.
- M1 regression suite rerun.

Acceptance criteria:

- All Android M2 tests pass.
- Critical cases have explicit test or manual evidence.
- OpenRouter regression tests pass.
- No M3 instrumented UI/screenshot suite is introduced.

Verification:

```bash
make android-lint && make android-test && make android-assemble
```

Out of scope: Compose UI instrumentation, screenshots, signing, and device matrix.

### 4.11 M2-09: Conformance, parity, CORS, and exit gate

Goal: produce the M2 evidence and release decision.

Deliverables:

- Updated `contracts/providers.json` with verified `browserCors` results.
- `docs/operations/M2/M2-verification.md` with command results, fixture conformance, CORS matrix, critical cases 11-14, 19, and 22-25, and manual smoke records.
- Updated `contracts/parity.md` with truthful M2 status.
- Regression evidence for M1 critical cases.
- Exit-gate review and recorded blockers/deferrals.

Acceptance criteria:

- `make contracts-check` passes.
- Web and Android M2 tests pass; Playwright passes.
- Every launch provider works where CORS permits, or is documented as unsupported with actionable copy.
- OpenRouter shows no regression.
- Evidence contains no secrets, prompts, or personal data.
- Parity does not overclaim.

Verification:

```bash
make contracts-check
make web-lint && make web-typecheck && make web-test && make web-build
cd web && pnpm exec playwright test
make android-lint && make android-test && make android-assemble
```

Out of scope: M3 Android depth, M3.5 Rust, M4 release polish, backup/sync, and deployment.

## 5. Exit-Gate Evidence Matrix

| Exit-gate requirement | Evidence to capture | Responsible tasks |
| --- | --- | --- |
| Every launch provider works end-to-end where CORS permits | Per-provider adapter tests plus manual web/Android smoke records | M2-01, M2-02, M2-05, M2-06, M2-09 |
| Streaming fixture conformance passes for all shared stream fixtures; OpenRouter shows no regression | Expected-vector test output for every provider plus M1 regression suite | M2-00 through M2-08 |
| Anthropic thinking/text separation, Gemini `MAX_TOKENS`, and Ollama NDJSON behave correctly | Adapter fixture tests and UI evidence | M2-02, M2-06 |
| Reasoning is displayed separately across providers | Reasoning rendering tests and screenshots/manual notes | M2-02, M2-03, M2-06 |
| Markdown, code, and math render safely; XSS payloads are inert; incomplete fences render as code | Rendering tests, XSS cases, and manual math checks | M2-00, M2-03, M2-06 |
| Per-conversation system prompt and parameters work; unsupported values are noted; protected fields cannot be overridden | Request tests, settings UI tests, and protected-field vectors | M2-00, M2-03, M2-06 |
| Attachments are stored locally, survive reload, are downscaled, and warn when unsupported | Persistence/reload tests, image processing tests, capability warnings, and manual evidence | M2-03, M2-06, M2-08 |
| Search, pin, archive, rename, and delete work | Web and Android UI/ViewModel tests plus E2E/manual evidence | M2-04, M2-07 |
| Export/import round trips preserve IDs, order, snapshots, and attachments; invalid imports do not mutate data; clearing data removes conversations, keys, and attachments | Schema validation, round-trip tests, invalid-import tests, and clear-data tests | M2-04, M2-07, M2-08 |
| CORS failures produce actionable errors and never silently proxy | CORS matrix, error code tests, and manual browser checks | M2-00, M2-02, M2-09 |
| Web Playwright suite passes; Android provider and persistence tests pass | Command output and uploaded artifacts | M2-04, M2-08, M2-09 |
| Critical cases 11-14, 19, and 22-25 have evidence on both platforms; M1 cases still pass | Critical-case table with links/commands | M2-09 |
| `contracts/parity.md` reflects what actually works | Parity diff plus `make contracts-check` | M2-09 |

Recommended evidence location: `docs/operations/M2/M2-verification.md`, written as tasks land and finalized by M2-09.

## 6. Execution Sequence

| Phase | Estimate | Work | Gate |
| --- | --- | --- | --- |
| 0. Shared expectations | 1 day | M2-00 expected streams, catalogs, requests, errors, CORS plan, math/export fixtures | `make contracts-check` passes |
| 1. OpenAI-compatible transport | 2-3 days | M2-01 and M2-05 in parallel | Preset transport green on both platforms; OpenRouter unchanged |
| 2. Native adapters and CORS | 2-3 days | M2-02 and M2-06 adapters in parallel | Anthropic/Gemini/Ollama fixtures and CORS evidence |
| 3. Content and attachments | 3-4 days | M2-03 and M2-06 content/attachments/settings | Math, params, attachments, warnings, settings |
| 4. Conversations and data | 3-4 days | M2-04 and M2-07 | Search, pin/archive, export/import, data controls |
| 5. Tests and Playwright | 2-3 days | M2-08 and web Playwright portion of M2-04 | M2 test suites and E2E pass |
| 6. Exit evidence | 1 day | M2-09 | CORS recorded, parity updated, critical cases evidenced |

Single-implementer order: M2-00, M2-01, M2-05, M2-02, M2-06, M2-03, M2-07, M2-04, M2-08, M2-09. This avoids cross-platform switching but is unlikely to fit two weeks.

Parallel order: M2-00 first; then one owner on web (M2-01, M2-02, M2-03, M2-04) and one on Android (M2-05, M2-06, M2-07, M2-08); verification owner drafts CORS/evidence and finishes M2-09.

## 7. Risks and Mitigations

| Risk | Impact | Mitigation |
| --- | --- | --- |
| Provider CORS blocks a preset | Web cannot enable it | Verify early, record `unsupported`, show actionable copy, and never proxy |
| Generalizing transport regresses OpenRouter | Release blocker | Keep the OpenRouter fixture suite as the reference gate on every change |
| Native stream shapes differ materially | Broken reasoning/usage/finish states | Expected vectors before adapters; fixture-driven development |
| Attachment content parts differ per provider | Images/files dropped or invalid | Shared request vectors and per-provider serialization tests |
| Image processing leaks metadata or creates huge requests | Privacy or provider rejection | Strip metadata, downscale, enforce size/type limits |
| Custom provider input becomes an SSRF or cleartext risk | Security incident | Validate URLs, warn on cleartext/local hosts, never put secrets in headers/records |
| Export/import corrupts local data | Data loss | Validate before writing, transactionally merge, reject invalid input without mutation |
| Search scans the entire store | Jank on large histories | Index/cursor strategy and debounced queries |
| Playwright tests are flaky | False failures | Mock providers and streams deterministically |
| Parity validator breaks with a second table | CI failure | Use a status list or update the validator deliberately |
| Attachment bytes become remote dependencies | Privacy regression | Store locally and verify reload with network blocked |
| M3 scope leaks into M2 | Missed milestone | Keep share target, deep links, tablet layout, Compose UI suite, screenshots, signing, and baseline profiles out of M2 |
| Math renderer increases bundle size or XSS surface | Performance/security regression | Lazy-load a sanitized renderer and test invalid input |
| Delete undo differs by platform | Parity ambiguity | Implement Android undo or record an approved deferral |

## 8. Decisions, Assumptions, and Open Questions

### 8.1 Decisions already made by the milestone

- OpenRouter remains the reference provider and must not regress.
- The OpenAI-compatible transport is generalized from the OpenRouter implementation.
- Anthropic, Gemini, and Ollama are native adapters.
- Custom providers require base URL, auth style, and optional non-secret headers.
- Attachments are local, content-addressed, and never remote dependencies.
- Import is merge-by-default and must not mutate on invalid input.
- CORS failures are actionable and never solved by a hidden proxy.
- Rust, PWA, deployment, backup/sync, Android share/deep links/tablet layout, Compose UI tests, screenshots, signing, and baseline profiles are out of scope.

### 8.2 Assumptions for this plan

- M1 exit-gate evidence remains valid.
- All provider presets and raw fixtures remain the source of truth; only expected outputs and request vectors are missing.
- A single web adapter abstraction can cover OpenAI-compatible transport plus native adapters without leaking provider-specific JSON into UI/storage.
- Web Playwright can use mocked providers and need not hit live provider endpoints.
- Android can add M2 tests without introducing the M3 Compose UI/screenshot suite.
- A maximum attachment size and downscale target can be implemented consistently on both platforms.
- Fallback model lists can be added to contracts or derived deterministically without breaking the provider schema.

### 8.3 Open questions to resolve before implementation

| Question | Proposed default | Needed by |
| --- | --- | --- |
| How should provider extras be namespaced? | `params.extra[<presetId>]`, with protected fields rejected | M2-00/M2-01 |
| How are fallback model lists represented? | Add an optional `fallbackModelIds` array to presets or use a documented deterministic fallback | M2-00 |
| What is the attachment size limit? | Reject files over a documented cap (for example 20 MB) and downscale images to 2048 px longest edge | M2-00/M2-03/M2-06 |
| Which math renderer family should each client use? | Web: lazy-loaded KaTeX-compatible renderer; Android: existing Markdown stack or a safe raw fallback if no compatible renderer exists | M2-00/M2-03/M2-06 |
| Is Android delete undo required? | Implement an equivalent buffered undo; otherwise record an approved deferral | M2-07 |
| How should web search be indexed? | Add a message-search index or cursor scan; do not scan all messages on each keystroke | M2-04 |
| How is Custom provider auth represented? | Bearer, x-api-key, query key, or none, with non-secret headers stored separately | M2-01/M2-05 |
| Should unsupported CORS presets be visible or hidden? | Visible with `unsupported` status and actionable copy; never hidden silently | M2-02 |
| How should export include attachment bytes? | Optional opt-in `dataBase64`; metadata always present | M2-04/M2-07 |
| How are provider failures surfaced when discovery is blocked? | Use fallback models and a non-blocking warning | M2-01/M2-02/M2-05/M2-06 |
| Is `system` sent as a message or a top-level field per provider? | OpenAI-compatible: system message; Anthropic: top-level system; Gemini: `systemInstruction` | M2-00/M2-01/M2-02 |
| Does Android need Compose UI tests in M2? | No; M3 owns them. M2 uses unit/ViewModel/Room/MockWebServer tests | Task generation |
| Where does M2 evidence live? | `docs/operations/M2/M2-verification.md`, matching M1 and M3 | M2-09 |

## 9. Definition of Done for This Plan

This plan is complete when:

1. M2-00 through M2-09 are implemented or explicitly re-scoped with written approval.
2. Every gap row in section 3 is resolved, or recorded as an accepted deferral with an owner and target milestone.
3. All launch providers work end-to-end where CORS permits, and OpenRouter has no regression.
4. Fixture conformance covers every shared stream, catalog, and error fixture.
5. Reasoning, Markdown, code, and math render safely on both platforms.
6. Per-conversation system prompt and parameters work, unsupported values are noted, and protected fields cannot be overridden.
7. Attachments are local, downscaled, persist across reload, and warn when unsupported.
8. Search, pin, archive, rename, and delete work on both platforms.
9. Export/import round trips preserve IDs, ordering, snapshots, and attachment references; invalid imports are rejected without data change; clear data removes conversations, keys, and attachments.
10. CORS failures are actionable and never silently proxied.
11. Web Playwright and Android M2 tests pass.
12. Critical cases 11-14, 19, and 22-25 have evidence on both platforms, and M1 cases still pass.
13. `contracts/parity.md` reflects delivered M2 behavior truthfully.
14. No M3, M3.5, M4, backup/sync, deployment, Playwright-on-Android, screenshot, signing, or baseline-profile work was introduced.
