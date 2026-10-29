# M2 - Providers and content

- Status: Planned
- Target: 2 weeks
- Depends on: M1
- Blocks: M3, M4
- Platforms: web, android
- Spec: sections 3.2-3.7, 5.3-5.4, 6.2, 7.5-7.9, 8.3-8.5, 9.3-9.8, 11.3-11.8, 12.2-12.4, 15.2 (cases 11-14, 19, 22-25, plus regressions), 17 (M2), 21.4

## Objective

Generalize the OpenRouter-proven transport into the remaining launch providers and complete the content, parameter, and conversation-management surfaces. OpenRouter must not regress. M2 is where the client becomes broadly usable beyond a single provider and where users can attach files, tune generation, search history, and move data in and out.

The Rust client is not part of M2. Its equivalent work is in M3.5.

## Entry criteria

- M1 exit gate is met on web and Android: OpenRouter chat, catalog mapping, streaming, reasoning, usage/cost, routing, errors, and basic conversation persistence work.
- OpenRouter fixture conformance is green and the browser CORS result is recorded.

## Scope by workstream

### 1. Transport generalization and additional adapters (web and Android)

Deliver on each platform:

- Generalize the OpenAI-compatible transport parameterized by preset. OpenRouter remains the reference behavior.
- OpenAI-compatible presets: OpenAI, Groq, Mistral, DeepSeek, xAI, Together, Fireworks, LM Studio, llama.cpp, and Custom. Custom providers require base URL, auth style, and optional non-secret headers.
- Anthropic native adapter: `x-api-key`, `anthropic-version`, `anthropic-dangerous-direct-browser-access` from web only, top-level system prompt, base64 image blocks, and stream event mapping for `message_start`, `content_block_start/delta/stop`, `message_delta`, `message_stop`, and errors.
- Google Gemini native adapter: `alt=sse`, `x-goog-api-key` header rather than query parameter, `usageMetadata`, `systemInstruction`, `inlineData`, and finish-reason mapping (`STOP` to `stop`, `MAX_TOKENS` to `length`, `SAFETY`/`RECITATION` to `content_filter`).
- Ollama adapter: native `/api/chat` NDJSON first, native `/api/tags` discovery, and the OpenAI-compatible path as a fallback for custom local servers. Document `OLLAMA_ORIGINS` for web.
- Per-preset model discovery and fallback model lists so onboarding still works when discovery is blocked or unsupported.
- Capability inference from preset metadata when the API does not expose modalities or supported parameters.
- CORS handling: maintain the `browserCors` matrix, emit an actionable `cors` error when a network failure matches a known provider, and never route through a third party. Suggest Android or a future self-hosted proxy only.
- Preserve the OpenRouter-only features: routing preferences, in-stream errors, reasoning deduplication, free badge, and catalog cache.

### 2. Reasoning and rich content (web and Android)

Deliver on each platform:

- Reasoning display across providers: OpenRouter reasoning fields, DeepSeek `reasoning_content`, Anthropic `thinking` blocks, and other supported provider reasoning. Reasoning is visually separate from the answer and is not sent back to providers unless required within the same turn.
- Math rendering: inline and block LaTeX with raw-source fallback on failure. Do not execute scripts.
- Code rendering completion: language label, copy, wrap/scroll toggle for long lines, exact-text copying, and safe handling of incomplete fences during streaming.
- Markdown rules from section 11.6: no raw HTML, sanitized links, `rel="noopener noreferrer"` on web, tables scroll horizontally, remote images require an explicit setting or per-message load action, and XSS payloads render inert.
- Per-conversation parameters and system prompt:
  - System prompt, temperature, top P, max output tokens, stop sequences, reasoning effort, streaming toggle, and provider-specific extra JSON.
  - Send only parameters the model/provider supports and show a small inline note when a value is ignored.
  - Namespace provider extras under `params.extra` and protect `model`, `messages`, `stream`, and `stream_options`.
  - Preserve parameters and system prompt when switching models.
- Conversation settings surface (web route/sheet, Android full-height sheet) for model, system prompt, parameters, routing summary, export, and delete.
- OpenRouter routing summary and privacy toggle in conversation settings.

### 3. Attachments (web and Android)

Deliver on each platform:

- Image attachments: PNG, JPEG, WebP, and GIF; text files: `.txt`, `.md`, `.json`, `.csv`, and source files up to a size limit.
- Downscale images to a maximum of 2048 px on the longest edge and re-encode to JPEG/WebP where appropriate.
- Store attachment bytes through the M0 content-addressed store; never rely on provider URLs or remote URLs for redisplay.
- Display thumbnails/chips in the composer and inside messages; allow removal before send.
- Capability warnings when the selected model lacks vision or file support, and a confirmation when switching to a text-only model with attachments present.
- Web input: attachment picker, drag-and-drop, and clipboard image paste.
- Android input: photo picker, document picker, clipboard image paste, and drag-and-drop on ChromeOS/tablets. EXIF metadata is stripped except orientation. The share target, deep links, and tablet layout polish remain M3.
- Send images as provider-appropriate content parts: OpenAI-style data URLs, Anthropic base64 image blocks, Gemini `inlineData`. Text files are appended as text parts with a filename header in phase 1.
- Attachment persistence and reload: bytes stay local, metadata is transactional, and orphan cleanup runs on startup/after edits.

### 4. Conversation management and data (web and Android)

Deliver on each platform:

- Search across conversation titles and message text with debounce, empty/loading states, and FTS on Android.
- Pin, archive, and archive view. Date grouping remains grouped by Today, Yesterday, Previous 7 days, Previous 30 days, Older.
- Delete with confirmation and 5-second undo toast where feasible.
- Export:
  - Per-conversation JSON and Markdown.
  - Full data archive following `chatui.export` from section 21.4.
  - Secrets excluded by default; attachments optional; plaintext warning.
- Import:
  - Schema validation against shared contracts, size limits, ID validation, merge-by-default, optional replace.
  - Never execute imported content; sanitize attachment names and paths.
  - Invalid imports must not mutate local data.
- Data controls: storage usage, clear all data with confirmation, per-key deletion, export/import entry points, and a backup placeholder.
- Settings surfaces:
  - Web routes from section 8.3: appearance, providers/models entry points, models defaults, data, backup placeholder, about/privacy.
  - Android screens from section 9.3: appearance, chat defaults, providers, data, backup placeholder, about/privacy.
  - Wire settings that affect behavior: send key, show token usage, load remote images, default system prompt/params, remember keys, last active conversation.
- Conversation export/import must preserve IDs, ordering, model snapshots, and attachment references.

### 5. Conformance and testing

- Shared stream, model catalog, and error fixture conformance for every provider on every enabled client. OpenRouter fixtures remain the release blocker.
- Web Playwright suite: first-run onboarding with OpenRouter, send/stream with a mocked provider, switch model/provider mid-conversation, reload persistence, export/import round trip, delete/undo, clear data, offline read, and error/retry/cancel paths.
- Android unit/MockWebServer provider tests and persistence/export/import tests. Compose UI tests and screenshots are M3.
- Critical cases covered in M2: 11-14, 19, 22-25 from section 15.2, plus regression coverage for M1 cases.
- Update `contracts/parity.md` honestly for provider, attachment, parameter, search, and export/import rows.

## Out of scope

- Android share target, deep links, tablet/foldable permanent sidebar, Compose UI test suite, Roborazzi screenshots, baseline profiles, release signing, and final device matrix (M3).
- PWA, service worker, offline app shell, deployment containers, and final accessibility/performance audits (M4).
- Rust client (M3.5).
- Backup/sync (M5) and managed hosting (M6).
- New provider protocols beyond the launch set.
- Model-generated titles, temporary/incognito chat, and message deletion beyond the phase 1 basics.

## Exit gate

- Every launch provider works end-to-end where CORS permits: OpenAI-compatible presets, Anthropic, Gemini, and Ollama.
- Streaming fixture conformance passes for all shared stream fixtures; OpenRouter shows no regression.
- Anthropic thinking/text separation, Gemini `MAX_TOKENS`, and Ollama NDJSON behave correctly.
- Reasoning is displayed separately across providers.
- Markdown, code, and math render safely; XSS payloads are inert; incomplete code fences render as code.
- Per-conversation system prompt and parameters work, unsupported values are ignored with a note, and protected fields cannot be overridden.
- Attachments are stored locally, survive reload, are downscaled, and produce capability warnings when unsupported.
- Search, pin, archive, rename, and delete work.
- Export/import round trips preserve IDs, order, snapshots, and optional attachments; invalid imports are rejected without data change; clearing data removes conversations, keys, and attachments.
- CORS failures produce actionable errors and never silently proxy.
- Web Playwright suite passes; Android provider and persistence tests pass.
- Critical cases 11-14, 19, and 22-25 have evidence on both platforms, and M1 cases still pass.
- `contracts/parity.md` reflects what actually works.

## Verification

```bash
make contracts-check
make web-lint && make web-typecheck && make web-test && make web-build
cd web && pnpm exec playwright test
make android-lint && make android-test && make android-assemble
```

Plus fixture conformance evidence per provider and platform.

## Task-generation guidance

Expect 6-8 tasks, split by platform and concern. Suggested slices:

1. Web OpenAI-compatible transport plus OpenAI/Groq/Mistral/DeepSeek/xAI/Together/Fireworks/LM Studio/llama.cpp/Custom presets.
2. Web Anthropic, Gemini, and Ollama adapters with fixture tests and CORS handling.
3. Web attachments, downscaling, rendering, math, parameters, and conversation settings.
4. Web conversations search/pin/archive, export/import, settings, and Playwright E2E.
5. Android OpenAI-compatible transport plus additional presets.
6. Android Anthropic, Gemini, and Ollama adapters with fixture tests and CORS handling.
7. Android attachments, EXIF handling, rendering, math, parameters, and conversation settings.
8. Android search/pin/archive, export/import, settings, and data controls.

Keep adapter work separate from UI work where possible. Do not fold M3 platform integration into M2 tasks.

## Risks and decisions to confirm

- Provider CORS varies. Maintain the matrix and make failures actionable; do not add a proxy.
- Attachments can silently become remote dependencies. Verify bytes are local and rebuild after reload.
- Unsupported parameter handling is easy to get wrong. Prefer omitting unsupported fields over sending invalid payloads.
- Importing untrusted archives is a security boundary. Validate schema, size, IDs, and paths before touching local data.
- Search on large histories may need indexes; do not scan all messages in the UI layer.
- OpenRouter features can regress when generalizing the transport. Keep its fixture suite as the reference gate.
