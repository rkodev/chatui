# ChatUI Client Implementation Specification

Status: Draft for implementation
Last updated: 2026-10-28
Audience: Web and Android implementers, reviewers, and future maintainers
Scope: Web client (`web/`) and native Android client (`android/`) for a ChatGPT-like, bring-your-own-key (BYOK) chat application

---

## 1. Summary

ChatUI is a self-hostable chat client that talks directly to user-selected AI providers. It provides a ChatGPT-like experience: conversation history, streaming responses, Markdown and code rendering, attachments, model and provider switching, and per-conversation parameters.

The application is deliberately split into two independent clients:

- `web/` - React, Vite, TypeScript, installable PWA, with Capacitor available as an optional packaging shell.
- `android/` - native Kotlin, Jetpack Compose, Room, and Android Keystore-backed secret storage. iOS is a later phase.

There is no application backend in phase 1. Users enter their own provider keys, and requests go from the device directly to the provider. Conversation data remains on-device. Future phases add encrypted backup to user-owned object storage and an optional single-click managed deployment hosted by roksky.

OpenRouter is the first-class provider and the reference implementation. Phase 1 is considered usable only after OpenRouter works end-to-end on both clients: key validation, public model catalog with pricing and capability metadata, streaming, reasoning content, usage accounting, provider routing preferences, and OpenRouter-specific errors. The OpenAI-compatible transport built for OpenRouter becomes the base for other presets, and native adapters for Anthropic and Gemini follow in M2.

The two clients share behavior through `contracts/`: JSON Schemas, provider presets, stream fixtures, normalized event definitions, and a parity checklist. They do not share application code.

---

## 2. Goals and non-goals

### 2.1 Goals

1. Be OpenRouter-first. OpenRouter is the default and reference provider, and every OpenRouter capability we expose must work before any other provider is declared supported.
2. A polished chat experience competitive with the ChatGPT web and mobile interfaces.
3. Add and manage multiple providers and API keys without editing configuration files.
4. Switch provider and model per conversation, and set defaults globally.
5. Stream responses token-by-token with cancellation, retry, and clear error states.
6. Render Markdown, code, tables, math, and common content safely.
7. Persist conversations locally on both platforms, with search, pinning, archiving, and export.
8. Support image and text-file attachments where the selected model supports them.
9. Keep user data local by default and never route keys or chats through roksky infrastructure in phase 1.
10. Be deployable as static files, a container, or a one-click hosted instance without server secrets.
11. Maintain feature parity between web and Android, verified by shared fixtures and a parity checklist.

### 2.2 Non-goals for phase 1

- User accounts, server-side conversation storage, or cross-device sync.
- Tool calling, agents, web browsing, image generation, or voice conversation.
- Team workspaces, sharing links, or collaboration.
- iOS application.
- Provider resale, billing, or usage metering.
- Local model inference inside the app.
- Server-side key custody or prompt proxying.

### 2.3 Explicit future scope

- Encrypted backup and sync to S3-compatible storage, Azure Blob Storage, Google Cloud Storage, or WebDAV.
- Multi-device sync with deterministic conflict resolution.
- One-click roksky-managed hosting with BYO storage.
- Optional self-hosted proxy for providers that block browser CORS.
- iOS client, potentially reusing the contracts and design tokens.

---

## 3. Product requirements

### 3.1 First-run experience

1. User opens the web app or installs the Android app.
2. Empty state explains that the app is BYOK and that data stays on-device.
3. The provider picker opens with OpenRouter preselected. The user can switch to another preset or "Custom OpenAI-compatible".
4. User pastes an OpenRouter API key, optionally names it, and presses "Connect".
5. The client loads the public model catalog from `GET /models` and validates the key with `GET /key`. If `/key` is unavailable, show a diagnostic instead of silently accepting the key. Never validate by making a paid completion request.
6. On success, the client caches the OpenRouter catalog, selects the user's last-used model or a sensible default, and opens a new chat.
7. If validation fails, show a provider-specific, actionable error and keep the entry form populated.

"Simply provide the key and start using" is a hard requirement. Do not require account creation, a config file, or a server. A user with only an OpenRouter key must reach a working chat without configuring any other provider.

### 3.2 Conversation management

- New chat, rename, delete, pin, archive, and search.
- Sidebar grouped by date: Today, Yesterday, Previous 7 days, Previous 30 days, Older.
- Conversation title defaults to the first user message truncated to 60 characters. Optional model-generated titles are a later enhancement.
- Auto-scroll while streaming only when the user is near the bottom.
- Regenerate the last assistant response; edit the last user message and resend.
- Delete a conversation with confirmation; support undo via a toast for 5 seconds where feasible.
- Export a conversation as JSON and Markdown.
- Temporary/incognito chat mode (no persistence) is a stretch goal for phase 1.

### 3.3 Provider and model switching

- Global default provider and model.
- Per-conversation provider and model override.
- Switching mid-conversation is allowed. Each assistant message stores a snapshot of the provider and model used.
- System prompt and generation parameters are per-conversation and preserved when switching models.
- Model picker groups models by provider, supports search, and shows capability chips, context window, input/output modalities, and price where available. OpenRouter model metadata is the reference for this UI.
- Warn before sending images or files to a model without the matching capability.
- Allow users to hide, pin, and manually add models.
- Multiple keys/profiles per provider, with labels, so a user can keep personal and work accounts separate.
- For OpenRouter, expose advanced routing preferences: provider fallbacks, preferred provider order, routing sort (`price`, `throughput`, `latency`), and a "do not use providers that may train on prompts" option mapped to `data_collection: "deny"`. Basic controls ship in M1; the full raw routing object is available as advanced JSON.
- Show the OpenRouter key label, credit usage, and limit status in provider details when `/key` returns them.

### 3.4 Provider setup

Presets at launch:

| Preset | Kind | Notes |
| --- | --- | --- |
| OpenRouter | OpenAI-compatible | **Default and reference provider.** Bearer token, `/key` validation, public `/models` catalog with pricing/modalities/context, provider routing, reasoning content, streamed usage, attribution headers |
| OpenAI | OpenAI-compatible | Bearer token, `/models` discovery |
| Anthropic | Anthropic native | `x-api-key`, `anthropic-version`, browser access header |
| Google Gemini | Gemini native | `x-goog-api-key`, model discovery |
| Groq | OpenAI-compatible | Bearer token |
| Mistral | OpenAI-compatible | Bearer token |
| DeepSeek | OpenAI-compatible | Bearer token; reasoning content support |
| xAI | OpenAI-compatible | Bearer token |
| Together | OpenAI-compatible | Bearer token |
| Fireworks | OpenAI-compatible | Bearer token |
| Ollama | OpenAI-compatible plus native discovery | Local server, no key by default |
| LM Studio | OpenAI-compatible | Local server |
| llama.cpp server | OpenAI-compatible | Local server |
| Custom | OpenAI-compatible | User supplies base URL, auth style, and optional headers |

Every preset must be defined in `contracts/providers.json`; do not hard-code provider metadata in either client.

Release policy:

- OpenRouter is a hard release blocker for both web and Android.
- OpenAI-compatible, Anthropic, Gemini, and Ollama are launch targets, but they do not block an OpenRouter-first preview release.
- Do not allow other provider work to delay OpenRouter compatibility. Implement the OpenRouter path first, then generalize the transport for other presets.

### 3.5 Generation controls

Per-conversation controls:

- System prompt (multi-line, optional).
- Temperature.
- Top P.
- Max output tokens.
- Stop sequences.
- Reasoning effort where the provider/model supports it.
- Streaming on/off (streaming is the default and the primary experience).
- Provider-specific extra JSON (advanced, collapsible).

Only send parameters the selected model/provider supports. Ignore unsupported values rather than sending invalid payloads; show a small inline note when a value is ignored.

### 3.6 Content and attachments

- Markdown: headings, lists, tables, blockquotes, task lists, links, inline code, fenced code.
- Syntax highlighting with a language label and copy button.
- Math: inline and block LaTeX.
- Attachments: images (PNG, JPEG, WebP, GIF) and plain text files (`.txt`, `.md`, `.json`, `.csv`, source files up to a size limit).
- Downscale images before upload: max 2048 px on the longest edge, re-encode to JPEG/WebP when appropriate.
- Display attachment thumbnails in the composer and inside messages.
- Store attachment bytes locally; never rely on provider URLs for redisplay.
- Do not render remote images from Markdown by default; require an explicit user action to load them, to avoid IP leaks.

### 3.7 Data and privacy controls

- View storage usage.
- Clear all data with confirmation.
- Export all data (JSON archive) and import it back.
- Per-conversation export.
- Delete an individual API key and all derived settings.
- Optional passphrase lock for stored secrets on web (stretch).
- No analytics, telemetry, or remote logging by default.
- Debug logging is opt-in, local, and redacts headers, keys, and message content.

### 3.8 Appearance and accessibility

- Light, dark, and system themes.
- Compact and comfortable density.
- Adjustable chat font size.
- Reduced motion support.
- WCAG 2.2 AA: keyboard navigation, visible focus, 4.5:1 text contrast, screen-reader labels, live regions for streaming status.
- Android: Material 3 dynamic color optional, 48 dp touch targets, edge-to-edge, predictive back.

---

## 4. Repository layout

```text
chatui/
|-- docs/
|   `-- IMPLEMENTATION.md
|-- contracts/
|   |-- providers.json
|   |-- schema/
|   |   |-- provider-preset.schema.json
|   |   |-- model-info.schema.json
|   |   |-- chat-export.schema.json
|   |   |-- backup-manifest.schema.json
|   |   `-- settings.schema.json
|   |-- fixtures/
|   |   |-- streams/
|   |   |   |-- openrouter-text.sse
|   |   |   |-- openrouter-reasoning.sse
|   |   |   |-- openrouter-usage.sse
|   |   |   |-- openrouter-error-200.sse
|   |   |   |-- openrouter-keepalive.sse
|   |   |   |-- openai-text.sse
|   |   |   |-- openai-reasoning.sse
|   |   |   |-- anthropic-text.sse
|   |   |   |-- gemini-text.sse
|   |   |   |-- ollama-ndjson.txt
|   |   |   `-- error-429.sse
|   |   |-- models/
|   |   |   |-- openrouter-models.json
|   |   |   |-- openrouter-key.json
|   |   |   |-- openai-models.json
|   |   |   |-- anthropic-models.json
|   |   |   |-- gemini-models.json
|   |   |   `-- ollama-tags.json
|   |   `-- errors/
|   |       |-- openrouter-402.json
|   |       |-- openrouter-429.json
|   |       `-- openrouter-503.json
|   `-- parity.md
|-- scripts/
|   |-- validate-contracts.mjs
|   `-- generate-icons.sh
|-- web/
|-- android/
|-- .github/workflows/
|-- Makefile
`-- README.md
```

Notes:

- `web/` and `android/` are independent builds. Do not introduce a root Node workspace that couples them.
- The root `Makefile` provides discoverable commands (`make web-dev`, `make web-test`, `make android-test`, `make contracts-check`).
- `contracts/` is the source of truth for provider presets, fixture streams, export formats, and backup formats.
- Shared design tokens are documented in section 10.12 and implemented per platform. A generated token pipeline is optional.

---

## 5. Shared contracts and parity

### 5.1 What is shared

- Provider preset catalog.
- Normalized provider event semantics.
- Chat export format.
- Backup envelope format and manifest.
- Settings schema where platform-independent.
- Streaming fixtures with expected normalized output.
- Error taxonomy.
- Feature parity checklist.

### 5.2 What is not shared

- UI component code.
- Persistence implementations.
- Networking stacks.
- Secret storage implementations.
- State management.

### 5.3 Contract validation

- Web validates contracts with Ajv during tests and at runtime for imported files.
- Android validates with `kotlinx.serialization` plus a JSON Schema validator during tests, or by strict deserialization plus fixture tests.
- CI runs `scripts/validate-contracts.mjs` on every change.
- Breaking changes require a `contractVersion` bump and migration notes.

### 5.4 Parity checklist

`contracts/parity.md` contains a table with columns: Feature, Web, Android, Notes. It is updated in every PR that changes user-visible behavior. Release is blocked if a feature is marked complete on one platform and absent on the other without an approved exception.

Initial feature list:

| Feature | Web | Android |
| --- | --- | --- |
| BYOK onboarding | Required | Required |
| OpenRouter first-run onboarding | Required (release blocker) | Required (release blocker) |
| OpenRouter key introspection (`/key`) | Required | Required |
| OpenRouter catalog, pricing, modalities | Required | Required |
| OpenRouter streaming, reasoning, usage/cost | Required | Required |
| OpenRouter provider routing preferences | Required | Required |
| OpenAI-compatible streaming | Required | Required |
| Anthropic streaming | Required | Required |
| Gemini streaming | Required | Required |
| Ollama local | Required | Required |
| Model discovery and switching | Required | Required |
| Conversation persistence | IndexedDB + OPFS | Room + files |
| Search | Required | Required |
| Markdown + code + math | Required | Required |
| Attachments | Required | Required |
| Export/import | Required | Required |
| Backup/sync | Future | Future |
| PWA install | Required | N/A |
| Native share target | N/A | Required |

---

## 6. Domain model

### 6.1 Identifiers and time

- All entity IDs are UUIDv7 strings. UUIDv7 gives time-ordered IDs, which improves index locality and deterministic ordering.
- Timestamps are ISO 8601 strings in UTC for web and `Instant` serialized as ISO 8601 in Android storage.
- Every conversation has a monotonic `sequence` per message for stable ordering; do not rely only on timestamps.
- IDs are generated client-side; never trust provider IDs for local identity.
- `storageKey` for backup objects is a random 128-bit hex string, independent of the conversation ID, to avoid leaking conversation counts and creation times.

### 6.2 Entities

#### Provider

```ts
interface Provider {
  id: string;
  presetId: string;
  label: string;
  kind: 'openai-compatible' | 'anthropic' | 'google-gemini';
  baseUrl: string;
  authType: 'bearer' | 'x-api-key' | 'query-key' | 'none';
  secretId: string | null;
  headers: Record<string, string>;
  enabled: boolean;
  browserCors: 'supported' | 'unsupported' | 'unknown';
  metadata: Record<string, unknown> | null;
  createdAt: string;
  updatedAt: string;
}
```

Rules:

- `secretId` references the secret store; the key itself is never stored in the provider record.
- `headers` may contain non-secret headers only. Secrets in custom headers are stored in the secret bundle.
- `presetId` is `custom` for user-defined OpenAI-compatible providers.
- `metadata` caches non-secret provider details. For OpenRouter it stores the `/key` label, usage, limit, free-tier flag, and rate-limit information.

#### Model

```ts
interface Model {
  id: string;
  providerId: string;
  remoteId: string;
  label: string;
  contextWindow: number | null;
  maxOutputTokens: number | null;
  capabilities: Array<'text' | 'vision' | 'files' | 'tools' | 'reasoning' | 'streaming'>;
  pricing: {
    inputPerMTok?: number;
    outputPerMTok?: number;
    perRequest?: number;
    currency?: string;
  } | null;
  canonicalSlug: string | null;
  inputModalities: Array<'text' | 'image' | 'file' | 'audio' | 'video'>;
  outputModalities: Array<'text' | 'image' | 'audio'>;
  supportedParameters: string[];
  metadata: Record<string, unknown> | null;
  hidden: boolean;
  pinned: boolean;
  source: 'discovered' | 'preset' | 'manual';
  createdAt: string;
  updatedAt: string;
}
```

Rules:

- Unique constraint on `(providerId, remoteId)`.
- Model lists are refreshed on demand; cached models remain selectable if discovery fails.
- A deleted provider cascades to its models and clears conversations that reference them, but the conversation keeps a label snapshot.
- OpenRouter model IDs are `author/model` slugs. Preserve `remoteId` exactly; display `label` from the catalog `name`, and keep `canonicalSlug` for deduplication and future aliases.
- OpenRouter prices arrive as strings representing USD per token. Convert to per-million-token numbers for display and keep the raw values in `metadata`.
- OpenRouter free models use the `:free` suffix and zero pricing. Mark them with a "Free" badge and surface upstream rate-limit errors distinctly from paid quota errors.
- `metadata` stores provider-specific normalized fields such as `top_provider`, `default_parameters`, `is_moderated`, and raw pricing. Do not put secrets or conversation content in it.

#### Conversation

```ts
interface Conversation {
  id: string;
  title: string;
  systemPrompt: string;
  providerId: string;
  modelId: string;
  modelLabel: string;
  params: ChatParams;
  pinned: boolean;
  archived: boolean;
  temporary: boolean;
  createdAt: string;
  updatedAt: string;
  lastMessageAt: string;
  messageCount: number;
  lastError: ChatError | null;
  sync: SyncMeta | null;
}
```

#### ChatParams

```ts
interface ChatParams {
  temperature: number | null;
  topP: number | null;
  maxOutputTokens: number | null;
  stop: string[];
  reasoningEffort: 'low' | 'medium' | 'high' | null;
  streaming: boolean;
  extra: Record<string, unknown>;
}
```

Provider-specific request options are stored under `params.extra`, namespaced by preset. For example, `extra.openrouter` may contain `provider`, `models`, and `route` routing objects. The core domain never interprets these values; the adapter validates and maps them. Core fields such as `model`, `messages`, `stream`, and `stream_options` are protected and cannot be overridden through `extra`.

#### Message

```ts
type MessageRole = 'system' | 'user' | 'assistant' | 'tool';
type MessageStatus = 'pending' | 'streaming' | 'complete' | 'cancelled' | 'error';

interface Message {
  id: string;
  conversationId: string;
  sequence: number;
  role: MessageRole;
  parts: ContentPart[];
  status: MessageStatus;
  providerId: string | null;
  modelId: string | null;
  modelLabel: string | null;
  usage: Usage | null;
  finishReason: FinishReason | null;
  error: ChatError | null;
  latencyMs: number | null;
  firstTokenMs: number | null;
  createdAt: string;
  updatedAt: string;
  deletedAt: string | null;
}
```

`providerId`, `modelId`, and `modelLabel` are snapshots. They are authoritative for display and for understanding how a message was produced, even if the provider is later deleted.

#### ContentPart

```ts
type ContentPart =
  | { type: 'text'; text: string }
  | { type: 'image'; attachmentId: string; mimeType: string; alt: string }
  | { type: 'file'; attachmentId: string; mimeType: string; name: string }
  | { type: 'reasoning'; text: string }
  | { type: 'tool-call'; id: string; name: string; argumentsJson: string }
  | { type: 'tool-result'; toolCallId: string; content: string };
```

Phase 1 renders and persists `text`, `image`, `file`, and `reasoning`. Tool parts are reserved and may be ignored by UI code but must round-trip through export.

#### Attachment

```ts
interface Attachment {
  id: string;
  messageId: string | null;
  kind: 'image' | 'file';
  mimeType: string;
  name: string;
  size: number;
  sha256: string;
  storageKey: string;
  width: number | null;
  height: number | null;
  createdAt: string;
}
```

Binary data is stored outside the relational/document store:

- Web: OPFS at `attachments/<sha256>` when available; IndexedDB Blob fallback.
- Android: `filesDir/attachments/<sha256>`.
- Content-addressed storage deduplicates identical files. Reference counting or orphan cleanup runs on app start.

#### Usage

```ts
interface Usage {
  inputTokens: number | null;
  outputTokens: number | null;
  totalTokens: number | null;
  costUsd: number | null;
  estimated: boolean;
}
```

#### ChatError

```ts
interface ChatError {
  code:
    | 'auth'
    | 'permission'
    | 'rate_limit'
    | 'quota'
    | 'model_not_found'
    | 'context_length'
    | 'content_filter'
    | 'invalid_request'
    | 'network'
    | 'cors'
    | 'timeout'
    | 'cancelled'
    | 'provider'
    | 'parse'
    | 'storage'
    | 'unknown';
  message: string;
  providerId: string | null;
  status: number | null;
  retryable: boolean;
  retryAfterSeconds: number | null;
  requestId: string | null;
  details: Record<string, unknown> | null;
  at: string;
}
```

User-facing messages must be actionable and must not include keys or raw headers.

#### Settings

```ts
interface Settings {
  theme: 'system' | 'light' | 'dark';
  density: 'comfortable' | 'compact';
  chatFontSize: 'sm' | 'md' | 'lg';
  sendKey: 'enter' | 'mod-enter';
  defaultProviderId: string | null;
  defaultModelId: string | null;
  defaultSystemPrompt: string;
  defaultParams: ChatParams;
  rememberKeysOnWeb: boolean;
  showTokenUsage: boolean;
  loadRemoteImages: boolean;
  lastActiveConversationId: string | null;
  backup: BackupConfig | null;
  onboardingCompleted: boolean;
}
```

#### BackupConfig (future)

```ts
interface BackupConfig {
  enabled: boolean;
  backend: 's3' | 'azure-blob' | 'gcs' | 'webdav';
  endpoint: string | null;
  region: string | null;
  bucket: string;
  prefix: string;
  credentialSecretId: string | null;
  encryption: 'aes-256-gcm';
  kdf: 'pbkdf2-sha256' | 'argon2id';
  kdfSalt: string | null;
  lastSyncAt: string | null;
  lastManifestEtag: string | null;
  deviceId: string;
}
```

### 6.3 Entity relationships

- Provider 1:N Model.
- Provider 1:N Conversation via current selection; Conversation N:1 Provider.
- Conversation 1:N Message.
- Message 1:N Attachment (via content parts and `messageId`).
- Conversation/Messages keep denormalized model labels for resilience after provider deletion.

### 6.4 Deletion semantics

- Deleting a conversation soft-deletes messages, then hard-deletes locally after 30 days or immediately when the user chooses "Delete permanently".
- Phase 1 may hard-delete immediately; the schema includes `deletedAt` to support future sync tombstones.
- Deleting a provider removes its models and secrets. Conversations that used it remain readable; sending is disabled until the user selects another provider/model.
- Orphan attachments are removed on startup and after message edits.

---

## 7. Provider integration layer

### 7.1 Adapter interface

Both clients implement the same conceptual adapter. The normalized interface is the contract; provider-specific payloads stay inside the adapter.

```ts
interface ProviderAdapter {
  readonly kind: Provider['kind'];

  validateKey(ctx: ProviderContext, signal: AbortSignal): Promise<ProviderKeyInfo>;

  listModels(ctx: ProviderContext, signal: AbortSignal): Promise<ModelInfo[]>;

  streamChat(
    ctx: ProviderContext,
    request: NormalizedChatRequest,
    signal: AbortSignal,
    onEvent: (event: StreamEvent) => void
  ): Promise<void>;
}

interface ProviderKeyInfo {
  label: string | null;
  usageUsd: number | null;
  limitUsd: number | null;
  isFreeTier: boolean | null;
  rateLimit: Record<string, unknown> | null;
}

interface ProviderContext {
  provider: Provider;
  apiKey: string | null;
  fetch: typeof fetch;
}
```

Android equivalents use `suspend` functions and `Flow<StreamEvent>` or a callback. Cancellation is coroutine cancellation; the underlying OkHttp call must be cancelled.

Core rules:

- OpenRouter implements all three methods first. `validateKey` uses `GET /key`; other presets may implement it with `GET /models` or return a generic "not supported" result.
- The OpenRouter adapter is the conformance baseline. Do not start another provider adapter until the OpenRouter fixture suite passes on both clients.
- Adapters never persist data.
- Adapters never read settings directly; everything needed is passed in.
- Adapters never log secrets.
- Adapters normalize usage, finish reasons, and errors.
- A single adapter conformance suite runs against fixtures for both platforms.

### 7.2 Normalized stream events

```ts
type StreamEvent =
  | { type: 'start'; responseId: string | null }
  | { type: 'text-delta'; text: string }
  | { type: 'reasoning-delta'; text: string }
  | { type: 'tool-call-delta'; index: number; id: string | null; name: string | null; argumentsDelta: string }
  | { type: 'usage'; usage: Usage }
  | { type: 'finish'; reason: FinishReason }
  | { type: 'error'; error: ChatError };

type FinishReason =
  | 'stop'
  | 'length'
  | 'content_filter'
  | 'tool_calls'
  | 'cancelled'
  | 'error'
  | 'unknown';
```

The UI reducer appends deltas to a streaming buffer and only updates the persisted message at bounded intervals.

### 7.3 Request normalization

```ts
interface NormalizedChatRequest {
  model: string;
  system: string | null;
  messages: Array<{
    role: 'user' | 'assistant' | 'tool';
    parts: ContentPart[];
  }>;
  params: ChatParams;
}
```

- The system prompt is separate from messages in the normalized model.
- Only completed or partial assistant text is included in context. Reasoning content is not sent back to providers unless a provider explicitly requires it within the same turn.
- Attachments are resolved to bytes by the caller and passed as part of a resolved request where the adapter needs base64 or multipart.
- Tools are not sent in phase 1.

### 7.4 OpenRouter reference integration

OpenRouter is the first adapter implemented and the reference for every other provider. It is OpenAI-compatible at the transport layer but adds a rich model catalog, key introspection, provider routing, unified reasoning, and streamed usage. Implement this section before generalizing the OpenAI-compatible adapter.

#### Endpoints and authentication

Base URL: `https://openrouter.ai/api/v1`

| Operation | Endpoint | Auth | Purpose |
| --- | --- | --- | --- |
| List models | `GET /models` | Public (no key) | Full catalog with pricing, modalities, context, supported parameters |
| Validate key | `GET /key` | Bearer | Key label, usage, limit, free-tier flag, rate limit |
| Chat | `POST /chat/completions` | Bearer | Streaming and non-streaming chat |

Request headers:

```text
Authorization: Bearer <key>
Content-Type: application/json
HTTP-Referer: <public app origin>
X-Title: <app display name>
```

- `HTTP-Referer` and `X-Title` are optional attribution headers. Send them by default using the deployment origin and configured app name. They must be configurable in `config.json` and must never contain personal data.
- Do not send keys in query parameters.
- Custom headers cause a CORS preflight in browsers. Verify OpenRouter browser-direct behavior in M1 and record the result in `browserCors`.
- The model catalog is public. Fetch it during onboarding even before a key is entered so users can see available models. Chat remains disabled until `/key` succeeds.

Key introspection mapping:

- `data.label` -> provider display label.
- `data.usage` -> cumulative credits used in USD.
- `data.limit` -> credit limit, or `null` for none.
- `data.is_free_tier` -> free-tier badge and rate-limit expectations.
- `data.rate_limit` -> display only; do not attempt client-side rate limiting beyond basic send locking.

Store this non-secret metadata on the provider record and refresh it on provider open and after authentication errors.

#### Model catalog mapping

`GET /models` returns `{ "data": [ ... ] }`. Normalize as follows:

| OpenRouter field | Local mapping |
| --- | --- |
| `id` | `remoteId` |
| `canonical_slug` | `canonicalSlug` |
| `name` | `label` |
| `context_length` | `contextWindow` |
| `top_provider.max_completion_tokens` | `maxOutputTokens`; fall back to context length |
| `top_provider.context_length` | Secondary context value; prefer the top-level value |
| `architecture.input_modalities` | `inputModalities`; `image` enables vision, `file` enables file input |
| `architecture.output_modalities` | `outputModalities` |
| `architecture.modality` | Keep in `metadata` for diagnostics |
| `pricing.prompt` | USD per input token; multiply by 1,000,000 for `inputPerMTok` |
| `pricing.completion` | USD per output token; multiply by 1,000,000 for `outputPerMTok` |
| `pricing.request` | `perRequest` |
| `pricing.internal_reasoning` | Keep in `metadata` |
| `supported_parameters` | `supportedParameters`; infer tools and reasoning capabilities |
| `default_parameters` | Keep in `metadata`; do not silently override user settings |
| `top_provider.is_moderated` | Keep in `metadata` and surface a moderation note |
| `per_request_limits` | Keep in `metadata` and surface when present |

Rules:

- Pricing values are strings in USD per token. Parse them as decimals, not binary floating point, and round only for display. Show "Free" when the effective price is zero, and preserve the `:free` suffix.
- OpenRouter model IDs are `author/model` slugs. Never rewrite them.
- The catalog changes frequently. Cache it for 24 hours, allow manual refresh, and keep the last successful snapshot when refresh fails.
- Ignore unknown fields rather than failing the whole catalog. Put useful unknown fields in `metadata` only when they are non-secret and small.
- Deduplicate by `canonical_slug` only when displaying; keep distinct IDs selectable.

#### Chat request

```json
{
  "model": "openrouter/auto",
  "messages": [
    { "role": "system", "content": "..." },
    { "role": "user", "content": "Hello" }
  ],
  "stream": true,
  "stream_options": { "include_usage": true },
  "reasoning": { "effort": "medium" },
  "provider": {
    "allow_fallbacks": true,
    "sort": "price",
    "data_collection": "deny"
  }
}
```

Rules:

- Send a parameter only when it is present in `supported_parameters` or explicitly supported by the preset. Ignore unsupported controls in the UI.
- Map `reasoningEffort` to `reasoning.effort`. Omit `reasoning` entirely when the model does not support it. Advanced users may supply `reasoning.max_tokens` or `reasoning.exclude` through the advanced JSON field.
- `provider` routing is optional. If the user has not configured preferences, omit the object and let OpenRouter use its defaults.
- Support these curated routing controls in M1: `allow_fallbacks`, `sort` (`price`, `throughput`, `latency`), and a privacy option that sets `data_collection: "deny"`. Expose `order`, `only`, `ignore`, `models` fallback arrays, and `route: "fallback"` through advanced JSON in M1 and curated UI in M2.
- Warn that `data_collection: "deny"` can make some models unavailable and may exclude free endpoints.
- Protect core fields: advanced JSON may not override `model`, `messages`, `stream`, or `stream_options`. Reject dangerous or malformed keys with a validation error.
- OpenRouter supports image inputs through OpenAI-style content parts. Send images as data URLs. Text files are appended as text parts in phase 1.
- Do not use the `models` fallback array automatically; that changes which model actually answered and must be an explicit advanced choice.

#### Streaming behavior

- Ignore SSE comment/keep-alive lines, including `: OPENROUTER PROCESSING`. They must not break parsing or add blank messages.
- Parse `data:` JSON until `data: [DONE]`.
- `choices[0].delta.content` is normally a string. If a provider returns an array of content objects, concatenate text fields defensively.
- Normalize `choices[0].delta.reasoning` and `choices[0].delta.reasoning_details` into `reasoning-delta` events. Do not duplicate text when both are present; prefer the structured details and fall back to `reasoning`.
- Map `finish_reason` values directly when they match OpenAI (`stop`, `length`, `tool_calls`, `content_filter`) and otherwise to `unknown`.
- The final chunk may contain `usage` with `prompt_tokens`, `completion_tokens`, `total_tokens`, and `cost`. Persist reported usage and cost. Mark estimated values when usage is absent.
- OpenRouter can return an `error` object inside an HTTP 200 stream. Normalize it, persist partial content, stop, and do not auto-retry after text has been emitted.

#### Error mapping

| Condition | Code | Retry |
| --- | --- | --- |
| 401 invalid key | `auth` | No |
| 402 insufficient credits | `quota` | No |
| 403 moderation or policy block | `content_filter` / `permission` | No |
| 404 unknown model | `model_not_found` | No |
| 429 rate limit or upstream rate limit | `rate_limit` | Yes, honor `Retry-After` |
| 503 no available upstream provider | `provider` | Yes, retry with jitter |
| 400 context length | `context_length` | No |
| Network failure before first token | `network` | Yes |
| Network failure after first token | `network` | No |
| User abort | `cancelled` | No |

Extract the provider error message and any generation or request identifier from the response when present. Show the normalized message and keep raw details behind "Details". Never log the key.

#### OpenRouter-specific UX

- Mark OpenRouter with "Recommended" in the provider picker.
- Show key label, credits used, limit, free-tier state, and rate-limit note in provider details.
- Show model price per million input/output tokens and context window in the model picker.
- Show input modality icons (text, image, file) and a "Free" badge.
- Show a routing summary in conversation settings when preferences are active: "Fallbacks on, cheapest provider, data collection denied".
- Explain that OpenRouter may route the same model to different upstream providers and that some providers have different data policies.
- Add a link to the OpenRouter model page or provider documentation from the model details sheet. Do not embed keys or conversation content in the URL.

#### OpenRouter compatibility checklist

A release is OpenRouter-compatible only when all of the following work on both web and Android:

- `GET /key` validates the key and exposes label, usage, limit, free-tier, and rate-limit metadata.
- `GET /models` populates the catalog, including paid, free, vision, reasoning, and long-context models.
- Pricing strings are converted correctly and displayed per million tokens, with free models marked.
- Context window, max output tokens, input/output modalities, and supported parameters are mapped.
- Chat streams incrementally from `POST /chat/completions`.
- `: OPENROUTER PROCESSING` keep-alives and unknown SSE fields do not break parsing.
- Reasoning content is normalized and displayed separately from the answer.
- Final usage and cost are persisted and visible.
- `402`, `429`, `503`, context errors, and errors inside a 200 stream are handled.
- Provider routing preferences serialize correctly and protected fields cannot be overridden.
- Attribution headers are configurable and contain no user data.
- Browser-direct CORS behavior is verified, or the web release is explicitly blocked.

#### Test fixtures

Add shared fixtures:

- `openrouter-models.json` with paid, free, vision, reasoning, and tool-capable models, unusual pricing strings, and unknown fields.
- `openrouter-text.sse` with a normal text stream.
- `openrouter-keepalive.sse` containing `: OPENROUTER PROCESSING` comments and unknown fields.
- `openrouter-reasoning.sse` with `reasoning` and `reasoning_details`.
- `openrouter-usage.sse` with final usage and cost.
- `openrouter-error-200.sse` where an error arrives inside a 200 stream.
- `openrouter-402.json`, `openrouter-429.json`, and `openrouter-503.json`.

Both clients must produce identical normalized events and persisted messages for these fixtures.

### 7.5 Provider matrix

| Provider | Protocol | Chat endpoint | Auth | Model discovery |
| --- | --- | --- | --- | --- |
| OpenRouter | OpenAI-compatible with routing | `POST /chat/completions` | `Authorization: Bearer <key>` | `GET /models` (public); `GET /key` validates |
| OpenAI | OpenAI Chat Completions | `POST /chat/completions` | `Authorization: Bearer <key>` | `GET /models` |
| Anthropic | Anthropic Messages | `POST /messages` | `x-api-key`, `anthropic-version` | `GET /models` |
| Google Gemini | Gemini generateContent | `POST /models/{model}:streamGenerateContent?alt=sse` | `x-goog-api-key` | `GET /models` |
| Groq | OpenAI-compatible | `POST /chat/completions` | `Authorization: Bearer <key>` | `GET /models` |
| Mistral | OpenAI-compatible | `POST /chat/completions` | `Authorization: Bearer <key>` | `GET /models` |
| DeepSeek | OpenAI-compatible | `POST /chat/completions` | `Authorization: Bearer <key>` | `GET /models` |
| xAI | OpenAI-compatible | `POST /chat/completions` | `Authorization: Bearer <key>` | `GET /models` |
| Together | OpenAI-compatible | `POST /chat/completions` | `Authorization: Bearer <key>` | `GET /models` |
| Fireworks | OpenAI-compatible | `POST /chat/completions` | `Authorization: Bearer <key>` | `GET /models` |
| Ollama | OpenAI-compatible and native | `POST /chat/completions` or `POST /api/chat` | none | `GET /api/tags` |
| LM Studio | OpenAI-compatible | `POST /chat/completions` | optional bearer | `GET /models` |
| llama.cpp | OpenAI-compatible | `POST /chat/completions` | optional bearer | `GET /models` |

Base URLs and versions live in `contracts/providers.json`, not in this document's code. OpenRouter is the reference row; all OpenAI-compatible providers reuse its transport with preset-specific metadata.

### 7.6 Protocol details

#### OpenAI-compatible

This transport is implemented and validated against OpenRouter first, then reused by other OpenAI-compatible presets. OpenRouter-specific behavior is defined in section 7.4.

Request body:

```json
{
  "model": "model-id",
  "messages": [
    { "role": "system", "content": "..." },
    { "role": "user", "content": [{ "type": "text", "text": "Hello" }] }
  ],
  "stream": true,
  "stream_options": { "include_usage": true },
  "temperature": 0.7
}
```

Streaming response is SSE. Lines beginning with `data:` contain JSON; `data: [DONE]` terminates. Parse multi-line `data:` fields and ignore comments. Content is normally `choices[0].delta.content`; reasoning may appear as `delta.reasoning_content` (DeepSeek) or `delta.reasoning` (some providers). Usage may arrive in the final chunk when `stream_options.include_usage` is supported. If a provider rejects `stream_options`, retry once without it.

Images are sent as `image_url` parts with data URLs. Text files are appended as text parts with a filename header; do not invent provider-specific file APIs in phase 1.

#### Anthropic

Headers:

```text
x-api-key: <key>
anthropic-version: 2023-06-01
content-type: application/json
anthropic-dangerous-direct-browser-access: true
```

The browser access header is required for direct browser calls. Only send it from the web app. The Android app does not need it, but sending it is harmless.

Streaming event types to handle:

- `message_start` -> `start`, initial usage.
- `content_block_start` with `text` or `thinking` blocks.
- `content_block_delta` with `text_delta` or `thinking_delta`.
- `content_block_stop`.
- `message_delta` -> output usage and stop reason.
- `message_stop` -> finish.
- `error` -> normalized error.

Images use `{"type":"image","source":{"type":"base64","media_type":"image/jpeg","data":"..."}}`.
System prompt is the top-level `system` field.

#### Google Gemini

Streaming endpoint uses `alt=sse`. Header `x-goog-api-key` is preferred over the query parameter to avoid key leakage in logs. Response data lines contain `candidates[0].content.parts[].text`. Usage metadata appears in `usageMetadata`. Finish reasons map `STOP` -> `stop`, `MAX_TOKENS` -> `length`, `SAFETY`/`RECITATION` -> `content_filter`.

Images use `inlineData` with `mimeType` and base64 `data`. System instructions use `systemInstruction`.

#### Ollama

Two supported modes:

1. OpenAI-compatible at `/v1/chat/completions` for uniformity.
2. Native `/api/chat` with NDJSON for richer local behavior and reliable model listing.

Use native discovery at `/api/tags`. For chat, implement native NDJSON first because it is stable locally, and keep the OpenAI-compatible path as a fallback for custom local servers. NDJSON lines contain `{ message: { content }, done, done_reason, prompt_eval_count, eval_count }`.

Local endpoints are `http://localhost:11434` by default. CORS must be enabled with `OLLAMA_ORIGINS` for the web app; Android is unaffected. Browsers may block HTTPS-to-HTTP requests or apply private-network restrictions, so the UI must detect and explain failures clearly.

### 7.7 CORS and browser limitations

Direct browser calls are a product requirement, but some providers may block them or require special headers.

- Maintain `browserCors` on every preset: `supported`, `unsupported`, or `unknown`.
- OpenRouter: browser-direct support is required for the web release. Verify `/key`, `/models`, and `/chat/completions` from a browser during M1 and record the result. Send `HTTP-Referer` and `X-Title` attribution headers.
- Surface a dedicated `cors` error when a request fails with a network error and the host matches a known provider. Suggest the Android app or a future self-hosted proxy.
- Anthropic: send `anthropic-dangerous-direct-browser-access: true`.
- Google: use `x-goog-api-key`, not a query parameter.
- Ollama: instruct users to set `OLLAMA_ORIGINS`.
- Custom providers: provide a "Test from browser" diagnostic that distinguishes DNS/TLS, CORS, auth, and model errors.
- Never silently route requests through a third party to work around CORS.

### 7.8 Model discovery

- Discovery is best-effort and cached.
- `GET /models` responses are normalized to `ModelInfo`.
- OpenRouter is the first discovery implementation and the richest source: cache the catalog for 24 hours, map pricing/modalities/context/supported parameters, and keep the last good snapshot on failure.
- OpenRouter `/models` is public. It can populate the onboarding model preview before the key is validated, but sending remains disabled until `/key` succeeds.
- Presets include fallback model lists so onboarding works if discovery is blocked.
- Users can add a model manually with `remoteId`, label, context window, and capabilities.
- Model capabilities are inferred from preset metadata when the API does not expose them.
- Refresh errors do not break chat; show a non-blocking banner.

### 7.9 Errors, retries, and cancellation

Mapping:

| HTTP/state | Code | Retry |
| --- | --- | --- |
| 401 | `auth` | No |
| 402 (OpenRouter insufficient credits) | `quota` | No |
| 403 | `permission` or `content_filter` | No |
| 404 model | `model_not_found` | No |
| 413 / context message | `context_length` | No |
| 429 | `rate_limit` or `quota` | Yes, honor `Retry-After` |
| 503 (OpenRouter no available upstream) | `provider` | Yes |
| Error object inside HTTP 200 stream | mapped from the envelope | No after text is emitted |
| 5xx | `provider` | Yes |
| Network failure before first token | `network` | Yes |
| Network failure after first token | `network` | No automatic retry |
| User abort | `cancelled` | No |

OpenRouter errors normally use `{ "error": { "code": ..., "message": ... } }` with an HTTP status. The same envelope can appear as a `data:` event inside an HTTP 200 stream. Normalize both paths identically and preserve any generation or request identifier in `ChatError.details`.

Retry policy: at most 3 attempts, exponential backoff with jitter, base 800 ms, cap 8 s. Retry only when no text has been received. Never submit the same request twice after partial output; offer "Regenerate" instead.

Cancellation: `AbortController` on web; coroutine cancellation plus OkHttp `Call.cancel()` on Android. Persist partial content and mark the message `cancelled`.

### 7.10 Token accounting

- Prefer provider-reported usage. OpenRouter reports input, output, total, and cost in the final stream chunk when usage is requested; persist all reported values.
- If absent, estimate with a local heuristic (`ceil(characters / 4)` for Latin text) and mark `estimated: true`.
- Context usage meter is approximate and labeled as such.
- Do not bundle a full tokenizer in phase 1; evaluate `gpt-tokenizer` or `jtokkit` later if accuracy becomes a priority.

---

## 8. Web client

### 8.1 Stack

- React 19, TypeScript, Vite.
- `@tanstack/react-router` for routing.
- Zustand for ephemeral UI and streaming state.
- Repositories and adapters are plain TypeScript classes, injected through React context for testability.
- `idb` for IndexedDB access; hand-written versioned migrations.
- OPFS for attachment bytes; IndexedDB Blob fallback.
- `react-markdown`, `remark-gfm`, `remark-math`, `rehype-katex`, `rehype-sanitize`.
- Shiki for syntax highlighting, with a limited bundled language set and lazy loading.
- Radix UI primitives for dialogs, menus, tooltips, switches, and tabs. No full component framework.
- `lucide-react` for icons.
- `zod` for runtime validation of external config and imports.
- `uuid` for UUIDv7.
- `@tanstack/react-virtual` for message list virtualization.
- `vite-plugin-pwa` for the service worker and manifest.
- Vitest, Testing Library, MSW, Playwright, axe-core.
- ESLint and Prettier, matching the conventions in the existing TS repositories.
- Styling: CSS custom properties and plain CSS modules or scoped feature CSS. Do not add Tailwind.

Capacitor:

- Optional `web/capacitor.config.ts` and `npx cap` scripts may be present for dogfooding and PWA-to-shell experiments.
- The production Android client is the native Kotlin app. Do not ship a Capacitor build as the primary Android product.
- If a Capacitor dev build is produced, it must not persist keys unless the WebView storage is protected equivalently to the native app.

### 8.2 Folder structure

```text
web/
|-- public/
|   |-- config.json
|   `-- icons/
|-- src/
|   |-- app/
|   |   |-- App.tsx
|   |   |-- router.tsx
|   |   |-- providers.tsx
|   |   `-- bootstrap.ts
|   |-- domain/
|   |   |-- conversation.ts
|   |   |-- message.ts
|   |   |-- provider.ts
|   |   |-- model.ts
|   |   |-- settings.ts
|   |   `-- errors.ts
|   |-- providers/
|   |   |-- registry.ts
|   |   |-- openrouter.ts
|   |   |-- openai-compatible.ts
|   |   |-- anthropic.ts
|   |   |-- gemini.ts
|   |   |-- ollama.ts
|   |   |-- sse.ts
|   |   `-- ndjson.ts
|   |-- storage/
|   |   |-- db.ts
|   |   |-- migrations.ts
|   |   |-- ConversationRepository.ts
|   |   |-- MessageRepository.ts
|   |   |-- AttachmentStore.ts
|   |   |-- ProviderRepository.ts
|   |   |-- SettingsRepository.ts
|   |   |-- SecretStore.ts
|   |   `-- ExportService.ts
|   |-- features/
|   |   |-- chat/
|   |   |-- conversations/
|   |   |-- providers/
|   |   |-- settings/
|   |   |-- onboarding/
|   |   `-- attachments/
|   |-- platform/
|   |   |-- capabilities.ts
|   |   |-- crypto.ts
|   |   |-- files.ts
|   |   `-- share.ts
|   |-- ui/
|   |   |-- Button.tsx
|   |   |-- Dialog.tsx
|   |   |-- Menu.tsx
|   |   |-- Tooltip.tsx
|   |   |-- Toast.tsx
|   |   `-- ...
|   |-- styles/
|   |   |-- tokens.css
|   |   |-- themes.css
|   |   |-- layout.css
|   |   `-- typography.css
|   |-- test/
|   `-- main.tsx
|-- e2e/
|-- index.html
|-- vite.config.ts
|-- tsconfig.json
|-- package.json
`-- playwright.config.ts
```

### 8.3 Routing

| Route | Purpose |
| --- | --- |
| `/` | Redirect to last active conversation or new chat |
| `/chat/$conversationId` | Chat view |
| `/settings` | Settings root, redirects to appearance |
| `/settings/appearance` | Theme, density, font size |
| `/settings/providers` | Provider list |
| `/settings/providers/new` | Provider onboarding |
| `/settings/providers/$providerId` | Provider details, key rotation, models |
| `/settings/models` | Global model defaults and hidden/pinned models |
| `/settings/data` | Storage usage, export/import, clear data |
| `/settings/backup` | Future backup configuration |
| `/settings/about` | Version, licenses, privacy |

Use history routing with a static-host fallback to `index.html`. Do not use hash routing.

### 8.4 State management

Two layers:

1. Persistent repositories: IndexedDB and OPFS. All writes go through repositories; UI never touches `idb` directly.
2. Ephemeral stores: Zustand stores for sidebar state, composer drafts, active stream buffers, dialogs, and toasts.

Streaming design:

- The provider call runs in a `ChatRunner` service, not in a React component.
- `ChatRunner` appends normalized events to a streaming buffer in a Zustand store.
- React subscribes to the active streaming message via a selector.
- Buffer updates are flushed to the UI at most every 33-50 ms using `requestAnimationFrame` or a timer, not per token.
- Persist partial text every 1 second and on `visibilitychange`, `pagehide`, completion, cancellation, and error.
- One active stream per conversation. Prevent duplicate sends with a send lock.
- On app start, any message with status `streaming` is marked `cancelled` with a note that the app was closed. Partial content is preserved.

### 8.5 IndexedDB schema

Database name: `chatui`, version 1.

| Store | Key | Indexes |
| --- | --- | --- |
| `conversations` | `id` | `updatedAt`, `lastMessageAt`, `archived`, `pinned` |
| `messages` | `id` | `[conversationId+sequence]`, `conversationId`, `createdAt` |
| `attachments` | `id` | `sha256`, `messageId`, `createdAt` |
| `providers` | `id` | `presetId`, `enabled` |
| `models` | `id` | `[providerId+remoteId]` unique, `providerId` |
| `settings` | `key` | none |
| `secrets` | `id` | none |
| `backupConfig` | `id` | none |
| `syncQueue` | `id` | `conversationId`, `attempts` |

Rules:

- Migrations are versioned functions with tests that create a v1 database and upgrade it.
- Store structured data only; store binary attachments in OPFS or Blobs.
- Use transactions for message append plus conversation metadata updates.
- Limit message reads to the last 50 messages, then paginate older messages on scroll.
- Include a `schemaVersion` in export files independent of the IndexedDB version.

### 8.6 Secrets on web

Threat model: the web app cannot fully protect a key from XSS or malicious dependencies. Be explicit in the UI.

Storage modes:

1. Session only (default for high-security users): keep keys in memory; lost on reload.
2. Remember on this device (default for convenience): encrypt key value with AES-GCM using a non-extractable `CryptoKey` stored in IndexedDB. This protects against casual database inspection but not against XSS while the app is running.
3. Passphrase lock (stretch): derive an AES-GCM key with PBKDF2-HMAC-SHA256 (600,000 iterations, random salt) and require the passphrase after reload. Never store the passphrase.

Implementation rules:

- Generate one non-extractable device key per browser profile via `crypto.subtle.generateKey({ name: 'AES-GCM', length: 256 }, false, ['encrypt', 'decrypt'])`.
- Store IV and ciphertext in the `secrets` store.
- Never put keys in URLs, `localStorage`, logs, error reports, or service worker caches.
- Redact `Authorization`, `x-api-key`, and `x-goog-api-key` from all diagnostics.
- Enforce a strict CSP: `default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; connect-src 'self' https: http://localhost:* http://127.0.0.1:*; font-src 'self' data:; object-src 'none'; base-uri 'self'; frame-ancestors 'none'`.
- Do not inject third-party scripts. Prefer self-hosted fonts or system fonts.
- Settings page must offer "Forget all keys".

### 8.7 Service worker and offline behavior

- Cache the app shell and hashed static assets.
- Never cache API requests or responses.
- Never cache secrets or decrypted content in the Cache API.
- Offline: conversation history is readable from IndexedDB; sending is disabled with an offline banner.
- Show an update prompt when a new service worker is waiting; do not force-reload during an active stream.
- Do not register a service worker in development.

### 8.8 Performance

- Code-split settings, provider management, and Shiki languages.
- Lazy-load the Markdown renderer for messages below the fold where practical.
- Virtualize the message list.
- Memoize message components by message ID and a content revision.
- Keep the streaming message isolated from the rest of the list so token updates do not re-render history.
- Budget: initial JS under 250 KB gzip, Lighthouse mobile performance and accessibility at least 90, 10,000-message conversation scrolling at 60 fps on a mid-tier device.
- Avoid layout shift when images load; reserve aspect-ratio boxes.

### 8.9 Deployment

Static deployment options:

- Any static host with SPA fallback.
- Docker image with Caddy or nginx.
- Cloudflare Pages, Netlify, Vercel, GitHub Pages, or roksky hosting.
- Capacitor shell for optional internal builds only.

Requirements:

- `index.html` served with `Cache-Control: no-cache`.
- Hashed assets served with long-lived immutable caching.
- `/config.json` served with `no-cache` and validated at startup.
- SPA fallback to `index.html` for unknown routes.
- Security headers: CSP, `X-Content-Type-Options: nosniff`, `Referrer-Policy: no-referrer`, `Permissions-Policy` minimal, `Cross-Origin-Opener-Policy: same-origin`.
- `config.json` contains only public configuration: app name, default theme, enabled provider presets, links, and feature flags. It never contains keys.

Example runtime config:

```json
{
  "appName": "ChatUI",
  "defaultTheme": "system",
  "defaultProviderPreset": "openrouter",
  "enabledPresets": ["openrouter", "openai", "anthropic", "google", "groq", "ollama", "custom"],
  "allowCustomProviders": true,
  "openRouter": {
    "attributionOrigin": "https://chat.example.com",
    "appTitle": "ChatUI"
  },
  "docsUrl": "https://github.com/rkodev/chatui",
  "telemetry": false
}
```

---

## 9. Android client

### 9.1 Stack

- Kotlin, Jetpack Compose, Material 3.
- Gradle Kotlin DSL with a version catalog.
- Minimum SDK 26; compile and target the latest stable SDK at implementation time.
- Kotlin serialization for JSON.
- OkHttp for HTTP; `okhttp-sse` for SSE; manual line parser for Ollama NDJSON.
- Room for conversations, messages, providers, models, attachments metadata, and FTS search.
- DataStore for settings and non-secret configuration.
- Tink Android with an Android Keystore master key for envelope encryption of secrets.
- Hilt for dependency injection.
- Navigation Compose.
- Coil for image loading and thumbnails.
- `com.mikepenz:multiplatform-markdown-renderer-m3` for Markdown in Compose.
- `dev.snipme:highlights` or equivalent for code highlighting.
- WorkManager for future backup/sync.
- AndroidX SplashScreen, Activity Compose, Lifecycle, Paging where needed.

Avoid WebView for the chat experience. The native app must feel native and work offline.

### 9.2 Modules

```text
android/
|-- app/
|-- core/
|   |-- model/
|   |-- common/
|   |-- designsystem/
|   |-- database/
|   |-- datastore/
|   `-- network/
|-- feature/
|   |-- onboarding/
|   |-- chat/
|   |-- conversations/
|   |-- providers/
|   |-- settings/
|   `-- backup/
`-- build-logic/
```

Rules:

- `core:model` contains pure Kotlin data classes and no Android dependencies.
- `core:network` contains adapters and normalized events.
- `feature:*` depends on core modules, never on other features.
- `app` wires navigation and dependency injection.
- Start with these modules; do not split further until a clear need appears.

### 9.3 Screens and navigation

- Onboarding: welcome, provider picker, key entry, test connection, default model.
- Chat: conversation screen with drawer, app bar, message list, composer.
- Conversation drawer: search, new chat, pinned, history groups, archive, settings.
- Provider management: list, add/edit, key rotation, model refresh.
- Model picker: bottom sheet with provider groups, search, capability chips.
- Settings: appearance, chat defaults, data, backup (future), about.
- Conversation settings sheet: system prompt, parameters, model, export, delete.

Compose considerations:

- Use `Scaffold`, `TopAppBar`, `ModalNavigationDrawer`, `LazyColumn`, `BottomSheet`, `AlertDialog`.
- Use `WindowInsets` and edge-to-edge handling for the keyboard and system bars.
- Predictive back: close the drawer or sheet before leaving the screen.
- Support tablets and foldables with a permanent sidebar at width >= 840 dp.

### 9.4 MVVM and data flow

- Each screen has a ViewModel exposing one immutable `UiState` via `StateFlow`.
- User intents are methods on the ViewModel; no UI logic in composables.
- Repositories expose `Flow` for queries and `suspend` functions for writes.
- The chat runner runs in a ViewModel-scoped coroutine or an application-scoped service.
- Streaming events are reduced into a `StreamingState` and persisted on a debounce.
- Do not write to Room on every token. Persist every 1 second, on pause, completion, cancellation, and error.
- On process death, mark `streaming` messages as `cancelled` and keep partial content.

### 9.5 Room schema

Entities:

- `ConversationEntity`: id, title, systemPrompt, providerId, modelId, modelLabel, paramsJson, pinned, archived, temporary, createdAt, updatedAt, lastMessageAt, messageCount, lastErrorJson, syncState.
- `MessageEntity`: id, conversationId, sequence, role, partsJson, status, providerId, modelId, modelLabel, usageJson, finishReason, errorJson, latencyMs, firstTokenMs, createdAt, updatedAt, deletedAt.
- `AttachmentEntity`: id, messageId, kind, mimeType, name, size, sha256, storageKey, width, height, createdAt.
- `ProviderEntity`: id, presetId, label, kind, baseUrl, authType, secretId, headersJson, enabled, browserCors, metadataJson, createdAt, updatedAt.
- `ModelEntity`: id, providerId, remoteId, label, contextWindow, maxOutputTokens, capabilitiesJson, pricingJson, canonicalSlug, inputModalitiesJson, outputModalitiesJson, supportedParametersJson, metadataJson, hidden, pinned, source, createdAt, updatedAt.
- `SecretEntity`: id, ciphertext, createdAt, updatedAt.
- `BackupConfigEntity` (future).
- `SyncQueueEntity` (future).

Indexes:

- `messages(conversationId, sequence)`.
- `models(providerId, remoteId)` unique.
- `attachments(sha256)`.
- FTS4/FTS5 virtual table `messages_fts` over message text for search, kept in sync by repository writes or Room triggers.

Migrations:

- Every schema change adds a Room migration and a migration test using `MigrationTestHelper`.
- Destructive migration is forbidden in release builds.
- Export/import includes a separate `formatVersion`.

### 9.6 Networking and streaming

- Implement the OpenRouter client first: `/key` validation, `/models` catalog mapping, `/chat/completions` streaming, reasoning, usage/cost, and OpenRouter error envelopes.
- Build the OpenAI-compatible transport as a shared layer parameterized by preset, then add Anthropic, Gemini, and Ollama adapters.
- Use OkHttp `Call` for model discovery and chat.
- Use `okhttp-sse` for SSE; parse event data with `kotlinx.serialization`. Ignore keep-alive comments such as `: OPENROUTER PROCESSING`.
- Send OpenRouter attribution headers from public app configuration. Never include user content or secrets.
- Use a buffered reader for Ollama NDJSON.
- Implement timeouts: connect 30 s, read 0 (no timeout during stream), call 0. Enforce an inactivity watchdog of 120 s that cancels the call with a retryable timeout error.
- Cancellation cancels the OkHttp call and the coroutine.
- Add an interceptor for redacted debug logging only when a debug setting is enabled.
- Respect `Retry-After` on 429.
- Use a single HTTP client with a connection pool shared across providers.
- Certificate validation stays enabled. Do not add a global trust-all manager. For local HTTP providers, use a network security config that permits cleartext only for `localhost` and private-range hosts, and require an explicit user warning.

### 9.7 Secure secret storage

- Use Tink `AndroidKeysetManager` backed by Android Keystore.
- Encrypt each API key with AEAD AES-256-GCM.
- Store ciphertext in Room or DataStore, never plaintext.
- Keep the keyset in Android Keystore; never export it.
- Disable Android auto-backup for secret stores and databases: set `android:allowBackup="false"` or provide explicit data extraction rules that exclude them.
- Do not include keys in logs, crash reports, or screenshots of debug overlays.
- Provide "Forget key" and "Forget all keys".
- Optional biometric or device-credential gate before revealing/copying a key.

### 9.8 Local files and attachments

- Store attachments under `filesDir/attachments/<sha256>`.
- Insert metadata in Room inside a transaction after the file is written and hashed.
- Downscale images with `BitmapFactory` and `ImageDecoder`; strip EXIF metadata except orientation.
- Use the Storage Access Framework for import/export; use `PhotoPicker` for images.
- Register a share target for `ACTION_SEND` with `text/plain` to prefill the composer.
- Support drag-and-drop on ChromeOS and tablets.
- Clean orphan files on startup.

### 9.9 Background behavior

- Phase 1: a stream continues while the app is foregrounded. If the app is backgrounded, keep streaming for a short grace period where the OS permits.
- If a long response must survive backgrounding, use a foreground service with a persistent notification. Treat this as an enhancement, not an initial requirement.
- On process death, preserve partial content and mark the message as interrupted.
- Do not schedule background sync until backup is implemented.
- Future backup uses WorkManager with network and battery constraints.

### 9.10 Platform integration

- Share conversation as text/Markdown via `ACTION_SEND`.
- Copy message and code blocks to clipboard.
- Open provider documentation in a Custom Tab.
- Handle back: close drawer, sheet, dialog, keyboard, then navigate.
- Support hardware keyboard shortcuts for common actions.
- Support Android 13+ per-app language if localization is added.
- Deep links: `chatui://chat/<id>` and HTTPS app links later.

### 9.11 Performance

- Cold start under 1.5 s on a mid-tier device.
- Message list uses `LazyColumn` with stable keys and `contentType`.
- Avoid recomposition of history during streaming by hoisting the streaming message.
- Decode images at the target size; cache thumbnails with Coil.
- Keep the debug APK under 20 MB and the release APK under 15 MB if feasible.
- Use baseline profiles before release.

---

## 10. UI and UX specification

### 10.1 Information architecture

Primary areas:

- Chat.
- Conversation history.
- Provider and model management.
- Settings.
- Data and backup.

Desktop web layout:

```text
+----------------------+---------------------------------------------+
| Sidebar (280px)      | Top bar: title, model picker, actions       |
| - New chat           +---------------------------------------------+
| - Search             |                                             |
| - Pinned             | Message list                                |
| - Today              |                                             |
| - Yesterday          |                                             |
| - Older              |                                             |
| - Settings           +---------------------------------------------+
|                      | Composer: attachments, input, send/stop     |
+----------------------+---------------------------------------------+
```

Mobile layout:

- Full-screen chat; sidebar becomes a modal navigation drawer.
- Top bar: menu, conversation title, model chip.
- Composer pinned above the keyboard and navigation bar.
- Model picker opens a bottom sheet.
- Conversation settings open a full-height sheet.

### 10.2 Core components

| Component | Responsibility |
| --- | --- |
| `AppShell` | Layout, theme, global dialogs and toasts |
| `Sidebar` / `ConversationDrawer` | New chat, search, grouped history, settings |
| `ConversationItem` | Title, timestamp, pin/archive actions |
| `ChatHeader` | Title, model chip, conversation settings, export |
| `MessageList` | Virtualized list, scroll anchoring, load older |
| `MessageItem` | Role layout, Markdown, actions, metadata |
| `MarkdownRenderer` | Safe Markdown, code, math, tables, links |
| `CodeBlock` | Language label, copy, wrap toggle |
| `Composer` | Auto-growing input, attachments, send/stop |
| `ModelPicker` | Provider groups, search, capabilities, defaults |
| `ProviderForm` | Preset, key, base URL, test, save |
| `ParamsSheet` | System prompt and sampling parameters |
| `EmptyState` | Welcome, suggested prompts, onboarding CTA |
| `ErrorBanner` | Actionable provider errors with retry |
| `SettingsDialog` or route | Appearance, behavior, data |

### 10.3 Chat screen states

- No provider configured: onboarding CTA, composer disabled.
- Provider configured, empty conversation: welcome message, suggested prompts, model visible.
- Sending: user message appears immediately, assistant placeholder with typing indicator.
- Streaming: stop button replaces send; auto-scroll if near bottom; live region announces start and completion, not every token.
- Complete: action row appears on hover/focus; copy, regenerate, export.
- Error: inline assistant error card with retry and details.
- Offline: banner; composer disabled; history remains readable.
- Model missing: chip shows unavailable, sending disabled, prompt to pick another model.
- Context near limit: meter and warning; block send when clearly over the limit.

### 10.4 Composer behavior

- Auto-grow from 1 to 8 lines, then scroll.
- Enter sends when `sendKey = enter`; Shift+Enter inserts a newline. Ctrl/Cmd+Enter sends in the alternate mode.
- On mobile, the enter key inserts a newline if the user has chosen that behavior; a send button is always visible.
- Drafts persist per conversation.
- Attachment button and drag-and-drop on web; photo/file picker on Android.
- Paste images from the clipboard on both platforms.
- Show attachment chips with size and remove action.
- Disable send when the input is empty and no attachments are present, while streaming, or offline.
- Preserve typed text if sending fails.
- Character counter is not shown by default; context meter lives in the header or params sheet.

### 10.5 Model and provider UX

- Model chip in the header shows the current model label and provider icon/initial. OpenRouter is labeled "Recommended" in the provider picker.
- Clicking opens the picker with a search box, pinned models at the top, then groups by provider.
- Each row shows label, provider, context window, input/output modalities, and price per million tokens. Show a "Free" badge for zero-priced OpenRouter models.
- The model details sheet shows the canonical slug, max output, supported parameters, upstream context, moderation flag, and a link to provider documentation.
- A warning icon appears if the selected model is unavailable or lacks required capabilities for current attachments.
- "Manage providers" and "Add provider" links at the bottom.
- Switching model updates the conversation immediately and persists. It does not rewrite past messages.
- If the current conversation has images and the user selects a text-only model, show a confirmation.
- OpenRouter conversation settings show a routing summary and a privacy toggle that maps to `data_collection: "deny"`.
- Provider details show the OpenRouter key label, usage, limit, free-tier status, and rate-limit note.

### 10.6 Markdown rendering rules

- Render raw HTML as text; do not enable `rehype-raw` by default.
- Sanitize links; allow `http`, `https`, `mailto`, and in-app anchors only.
- Open external links with `rel="noopener noreferrer"` and target `_blank` on web.
- Remote images require `settings.loadRemoteImages` or a per-message "Load image" action.
- Code blocks: do not execute, do not render HTML, copy exact text.
- Incomplete fenced code during streaming must render as a code block rather than flashing raw fences.
- Math rendering failures fall back to the raw source without breaking the message.
- Tables scroll horizontally on narrow screens.
- Very long code lines wrap or scroll with an explicit toggle.

### 10.7 Message actions

- Copy message text.
- Copy code block.
- Regenerate assistant response (last assistant only in phase 1).
- Edit last user message; confirm truncation of later messages.
- Retry failed request.
- Delete message (phase 1.1).
- Show metadata: model, provider, time, token usage, latency (debug details in a popover).
- No branching in phase 1; schema keeps room for `parentId` later.

### 10.8 Empty, loading, and error states

- Skeleton or subtle spinner while loading history.
- Provider validation shows inline status and a disabled connect button while testing.
- Model discovery failure shows cached models and a retry action.
- Errors use plain language:
  - "Your API key was rejected. Check the key and try again."
  - "This provider is rate limiting requests. Retrying in 30 seconds."
  - "This free OpenRouter model is rate limited. Try again later or choose a paid model."
  - "OpenRouter reports insufficient credits. Add credits or choose a free model."
  - "The selected model is unavailable. Choose another model."
  - "OpenRouter could not find an available upstream provider for this model. Try again or allow fallbacks."
  - "The conversation is too long for this model. Start a new chat or reduce the context."
  - "The request could not reach the provider. This may be a browser CORS restriction. Try the Android app or a self-hosted proxy."
- Never show raw JSON as the primary error; expose it under "Details".

### 10.9 Responsive breakpoints

| Width | Behavior |
| --- | --- |
| < 600 px | Mobile web, drawer navigation, single column |
| 600-839 px | Compact desktop, collapsible sidebar |
| 840-1279 px | Persistent sidebar, comfortable message width |
| >= 1280 px | Persistent sidebar, optional right params panel |

Message column max width: 48 rem. Composer max width matches. Do not let text lines exceed 80 characters.

### 10.10 Keyboard shortcuts (web)

| Shortcut | Action |
| --- | --- |
| `Ctrl/Cmd + K` | Command palette / search |
| `Ctrl/Cmd + Shift + O` | New chat |
| `Ctrl/Cmd + /` | Toggle shortcuts help |
| `Esc` | Stop streaming, close dialog/sheet |
| `Enter` | Send (mode-dependent) |
| `Shift + Enter` | Newline |
| `Ctrl/Cmd + Shift + C` | Copy last response |
| `Alt + Up/Down` | Navigate conversations |

### 10.11 Accessibility

- All interactive elements reachable by keyboard and have visible focus rings.
- Icon-only buttons have accessible names.
- Streaming announcements use `aria-live="polite"` sparingly: "Response started", "Response complete", and errors.
- Do not announce every token.
- Respect `prefers-reduced-motion`.
- Minimum contrast 4.5:1 for body text and 3:1 for large text and UI boundaries.
- Android uses semantics, content descriptions, and minimum 48 dp targets.
- Test with TalkBack and VoiceOver/TalkBack equivalents, plus one browser screen reader.

### 10.12 Design tokens

Shared token names, implemented separately per platform:

```text
color.bg.canvas
color.bg.surface
color.bg.surface-raised
color.bg.user-bubble
color.bg.code
color.fg.primary
color.fg.secondary
color.fg.muted
color.fg.on-accent
color.accent
color.accent-hover
color.border.subtle
color.border.strong
color.danger
color.warning
color.success

space.1  = 4px
space.2  = 8px
space.3  = 12px
space.4  = 16px
space.5  = 24px
space.6  = 32px
space.7  = 48px

radius.sm = 6px
radius.md = 10px
radius.lg = 16px
radius.full = 9999px

font.body.family
font.body.size
font.body.line-height
font.code.family
font.code.size
font.heading.*
```

Web implements tokens as CSS custom properties in `styles/tokens.css` and theme overrides in `styles/themes.css`. Android maps them to a Material 3 color scheme and `Dimens`/`Typography` objects. Keep the semantic names identical to ease review.

---

## 11. Security and privacy

### 11.1 Principles

- BYOK and local-first.
- No hidden network destinations.
- No analytics or telemetry in phase 1.
- Keys are secrets; chat content is sensitive.
- Every network destination is visible in the provider settings.

### 11.2 Web threats and mitigations

| Threat | Mitigation |
| --- | --- |
| XSS exfiltrating keys | Strict CSP, no third-party scripts, sanitize Markdown, no raw HTML, dependency review |
| Malicious dependency | Lockfiles, pinned versions, automated dependency review, minimal dependency surface |
| Key in logs | Central redaction, no request/response body logging by default |
| Key in URL | Never use query-param auth; prohibit keys in share links |
| Backup/sync leaks | Encrypt before upload; user-owned keys; no provider keys in backup metadata |
| Clickjacking | `frame-ancestors 'none'` |
| Service worker caching secrets | Never cache API responses; cache shell assets only |

### 11.3 Android threats and mitigations

| Threat | Mitigation |
| --- | --- |
| Key extraction from app data | Tink AEAD with Android Keystore; exclude from backups |
| Screenshot leakage | Optional `FLAG_SECURE` on secret entry and key detail screens |
| Cleartext interception | HTTPS by default; cleartext limited to localhost/private ranges with warning |
| Malicious import file | Validate schema, size limits, never execute content, sanitize paths |
| Overlay/tapjacking | Standard Android protections; no sensitive actions without confirmation |
| Log leakage | Redacted logging; no keys or content in release logs |

### 11.4 Data handling

- Chat content is sent only to the selected provider, except when that provider is OpenRouter. OpenRouter forwards the request to an upstream model provider selected by its routing. Disclose this clearly during onboarding and in the routing summary.
- When OpenRouter routing is active, show which preferences are applied and offer `data_collection: "deny"` to exclude providers that may train on prompts. State that this can reduce model availability.
- Local storage is not encrypted at rest in phase 1 for conversations. Document this. Future backup encryption protects remote copies, not the local device.
- Offer "clear all data" with an explicit confirmation phrase if the user wants a high-friction guard.
- Export files contain plaintext conversations; warn the user in the export dialog.
- Attachment EXIF metadata is stripped on Android; web image re-encoding strips metadata where applicable.

### 11.5 Provider policy and compliance

- Providers may restrict browser-direct usage. Document that users are responsible for complying with provider terms.
- OpenRouter may route a request to an upstream provider with its own terms and data policy. Surface this in the UI and link to provider information when available.
- Do not hide provider identity in requests.
- Do not proxy or resell provider access in phase 1.
- If a provider requires a server-side integration, mark it as unsupported for direct browser use.

---

## 12. Backup and sync (future)

This section is design guidance for phase 2. Do not build it until phase 1 is stable, but keep the schema compatible.

### 12.1 Requirements

- Offline-first.
- User-owned storage: S3-compatible, Azure Blob, GCS, or WebDAV.
- End-to-end encryption with a user passphrase. The storage provider and roksky cannot read content.
- Selective backup: all conversations or pinned only.
- Restore onto a new device.
- Deterministic conflict resolution.
- No server component required. Direct client-to-storage uploads.

### 12.2 Threat model

- Storage credentials are secrets stored like API keys.
- The backup passphrase is never stored; only a salt and a verification token.
- File names, sizes, and timestamps may leak metadata unless object names and manifests are opaque. Use random `storageKey` values and encrypt manifests.
- A malicious storage provider can delete or roll back data; detect via manifest snapshots and keep local copies. Do not claim Byzantine protection.

### 12.3 Object layout

```text
<prefix>/
|-- manifest.enc
|-- conversations/
|   `-- <random-storage-key>.enc
`-- attachments/
    `-- <sha256>.enc
```

- `manifest.enc` contains the conversation index, attachment index, revision numbers, tombstones, and device IDs.
- Conversation files are self-contained: conversation metadata plus all messages plus attachment references.
- Attachments are content-addressed and immutable.
- Do not use conversation UUIDs directly as object names in phase 1 of sync; use random storage keys from the conversation record.

### 12.4 Encryption envelope

```text
magic: "CHATUI1\n"
header: JSON { v, alg: "AES-256-GCM", kdf: "PBKDF2-SHA256", iterations: 600000, salt: base64 }
iv: 12 random bytes
ciphertext: AES-256-GCM ciphertext
tag: included in ciphertext per WebCrypto/Tink conventions; wrap explicitly for consistency
```

Rules:

- Derive the master key from the passphrase with PBKDF2-HMAC-SHA256, 600,000 iterations, 16-byte random salt, 32-byte key. Plan an Argon2id v2 upgrade with a version field.
- Use a fresh random IV for every file write. Never reuse an IV with the same key.
- Include the object type and storage key as associated data to bind ciphertext to its location.
- Gzip compress before encryption for conversations; never compress attachment bytes that are already compressed.
- Keep the format versioned and test round-trips in both clients.

### 12.5 Sync protocol

1. On first setup, create a device ID and a random backup salt. Store them locally.
2. Read `manifest.enc`; if absent, create an empty manifest.
3. Merge local and remote manifests by conversation revision.
4. Upload dirty conversations and attachments.
5. Rewrite the manifest with new revisions and ETag/version preconditions.
6. On conflict, retry with backoff and re-read.

Conflict rules:

- Messages are immutable and identified by UUID. Merge as a set, ordered by `sequence`, then `createdAt`, then `id`.
- Deletions use tombstones with `deletedAt` and `deviceId`; a deletion wins over an older update.
- Conversation metadata (title, pinned, archived, params, current model) uses last-write-wins by `updatedAt`, with `deviceId` as tiebreaker.
- If both devices added messages concurrently, keep both and renumber sequences deterministically.
- Never silently drop local messages.

### 12.6 Storage backend adapters

| Backend | Auth | Notes |
| --- | --- | --- |
| S3-compatible | Access key + secret, SigV4 | AWS, Contabo, Cloudflare R2, MinIO, Backblaze B2 |
| Azure Blob | Account name + key or SAS | Use block blobs |
| Google Cloud Storage | Service account JSON or OAuth | Prefer user OAuth later; service account JSON is acceptable initially |
| WebDAV | URL + username + password | Lowest common denominator; weak consistency |

Client requirements:

- Document the required bucket CORS configuration for web.
- Use conditional writes (`If-Match`/ETag) where supported.
- Handle eventual consistency conservatively.
- Never upload unencrypted content when encryption is enabled.
- Test connectivity before saving.

### 12.7 UX

- Backup settings: backend, bucket, prefix, credentials, passphrase, test, enable.
- Show last backup time, pending changes, errors, and storage usage.
- "Back up now" and "Restore from backup".
- Restore is non-destructive by default: merge, do not wipe local data.
- A "delete remote backup" action requires typing a confirmation.

### 12.8 Managed roksky option

- roksky hosts the web UI; the user supplies storage credentials and passphrase.
- The hosted UI has no server-side access to keys, chats, or storage credentials.
- A future managed offering may provide storage under a partner agreement, still with client-side encryption and no ability to read content.
- One-click deployment must never require the user to hand over provider keys to roksky.

---

## 13. One-click deployment

### 13.1 Static container

Deliver a minimal image:

```text
FROM caddy:2-alpine
COPY web/dist /srv
COPY deploy/Caddyfile /etc/caddy/Caddyfile
EXPOSE 80
```

`Caddyfile`:

```text
:80 {
  root * /srv
  encode zstd gzip
  try_files {path} /index.html
  file_server
  header {
    X-Content-Type-Options nosniff
    Referrer-Policy no-referrer
    Permissions-Policy "camera=(), microphone=(), geolocation=()"
    Content-Security-Policy "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; connect-src 'self' https: http://localhost:* http://127.0.0.1:*; font-src 'self' data:; object-src 'none'; base-uri 'self'; frame-ancestors 'none'"
  }
  @immutable path /assets/*
  header @immutable Cache-Control "public, max-age=31536000, immutable"
  @noCache path / /index.html /config.json
  header @noCache Cache-Control "no-cache"
}
```

- Publish to `ghcr.io/rkodev/chatui-web:<semver>` and `:latest`.
- `/healthz` returns a static 200.
- No environment variables are required; configuration is a public `config.json` baked or mounted at deploy time.

### 13.2 Platform recipes

- Docker Compose with a single web service.
- Cloudflare Pages, Netlify, or Vercel with SPA fallback.
- GitHub Pages with a 404 fallback.
- roksky hosting: tenant subdomain, custom domain, branding, optional managed storage partner.

### 13.3 Managed hosting flow

1. User chooses "Deploy to roksky".
2. User selects a subdomain and region.
3. roksky serves the static app and manages TLS.
4. User configures providers and keys in the browser.
5. Optionally, user connects their own object storage for encrypted backups.
6. roksky support has no access to keys, plaintext chats, or backup passphrases.
7. Provide a data-processing summary and a clear "export everything" path.

### 13.4 Android distribution

- Phase 1: signed APK on GitHub Releases plus an internal testing track.
- Later: Google Play with a privacy policy, data safety declarations, and a BYOK explanation.
- Provide reproducible builds where feasible.
- Signing keys are stored in CI secrets and never committed.
- App versioning follows semantic versioning; `versionCode` increments monotonically.

---

## 14. Testing and quality

### 14.1 Test pyramid

Web:

- Unit tests with Vitest for domain logic, reducers, parsers, storage migrations, repositories, export/import, and token estimation.
- Component tests with Testing Library for composer, model picker, message rendering, onboarding, and error states.
- Network tests with MSW, including a custom SSE `ReadableStream` mock.
- End-to-end tests with Playwright: first-run onboarding, send and stream, switch model, reload persistence, export, delete, offline read.
- Accessibility checks with axe on main routes.
- Visual regression optional.

Android:

- Unit tests with JUnit4, Truth, MockK, and Turbine for ViewModels and repositories.
- Stream parser tests against shared fixtures.
- Room tests with in-memory databases and migration tests.
- Network tests with MockWebServer, including chunked SSE and cancellation.
- Compose UI tests for chat, composer, model picker, onboarding, and settings.
- Roborazzi screenshot tests for key screens in light/dark and phone/tablet.
- Manual device matrix: Android 8, 12, 14, current, one low-RAM device, one tablet.

Shared:

- Contract validation.
- OpenRouter adapter conformance across catalog, key introspection, streaming, reasoning, usage, and errors. This suite is the release gate for M1.
- Stream fixture conformance on both platforms.
- Export/import round-trip using shared fixtures.
- Backup envelope round-trip once implemented.

### 14.2 Critical test cases

1. First run with no provider shows onboarding, preselects OpenRouter, and blocks sending.
2. Invalid OpenRouter key shows an auth error, preserves the form, and does not accept the key.
3. Valid OpenRouter key passes `GET /key`, loads `/models`, and selects a default model.
4. OpenRouter catalog mapping handles paid, free, vision, reasoning, and tool-capable models, converts per-token prices to per-million, and ignores unknown fields.
5. OpenRouter streaming ignores `: OPENROUTER PROCESSING` keep-alives and appends text in order.
6. OpenRouter `reasoning` and `reasoning_details` produce one normalized reasoning stream without duplication.
7. OpenRouter usage/cost from the final chunk is persisted and displayed.
8. An OpenRouter error object inside an HTTP 200 stream stops the message, preserves partial text, and does not auto-retry.
9. OpenRouter 402 insufficient credits maps to `quota` with a billing message and no retry.
10. OpenRouter 503 no available upstream provider retries with backoff.
11. OpenAI-compatible streaming appends text in order and finalizes usage.
12. Anthropic `thinking` and `text` deltas are separated correctly.
13. Gemini `MAX_TOKENS` maps to `length`.
14. Ollama NDJSON completes and usage is recorded.
15. Cancel stops the request, persists partial text, and marks `cancelled`.
16. Network failure before first token retries up to 3 times.
17. Network failure after first token does not auto-retry.
18. Switching model mid-conversation persists and uses the new model on the next send.
19. Switching provider warns about unsupported attachments.
20. OpenRouter provider routing preferences are serialized correctly and protected fields cannot be overridden by advanced JSON.
21. Reload during streaming marks the message interrupted and preserves partial text.
22. Markdown XSS payloads are rendered inert.
23. A 10,000-message conversation scrolls without rendering all messages.
24. Export/import preserves IDs, ordering, model snapshots, and attachments.
25. Clearing data removes conversations, keys, and attachments.
26. Android process death during streaming preserves partial content.
27. Android backup exclusion prevents keys from being restored on another device.
28. 429 with `Retry-After` schedules a retry and shows a countdown.

### 14.3 Commands

```bash
make contracts-check

make web-install
make web-lint
make web-typecheck
make web-test
make web-build
make web-e2e

make android-test
make android-lint
make android-assemble
```

Direct commands:

```bash
cd web && pnpm install && pnpm lint && pnpm typecheck && pnpm test && pnpm build
cd web && pnpm exec playwright test
cd android && ./gradlew :app:lintDebug :app:testDebugUnitTest :app:assembleDebug
```

Quality gates for merge:

- Contracts validate.
- Lint and type checks pass.
- Unit tests pass.
- New behavior has tests.
- Parity checklist updated.
- No secrets in the diff.
- Web bundle budget respected.
- Android lint has no new errors.

---

## 15. CI/CD

### 15.1 Web workflow

Triggers: pull requests and pushes to `main`, tags.

Steps:

1. Install pnpm and Node LTS.
2. `pnpm install --frozen-lockfile`.
3. Validate contracts.
4. Lint, typecheck, unit tests with coverage.
5. Build.
6. Playwright tests against the built app with mocked providers.
7. Upload the build artifact.
8. On tags, build and push the container image.
9. Deploy the static site to the configured host or publish an artifact.

### 15.2 Android workflow

Steps:

1. Set up JDK and Android SDK.
2. Cache Gradle.
3. `./gradlew lintDebug testDebugUnitTest assembleDebug`.
4. Optionally run instrumented tests on an emulator for main.
5. On tags, build a signed release AAB/APK using CI secrets.
6. Attach artifacts to the GitHub release.
7. Generate and upload a mapping file for crash deobfuscation if a crash reporter is added later.

### 15.3 Contracts workflow

- Validate JSON Schemas.
- Run fixture conformance tests on both platforms.
- Treat any OpenRouter fixture failure as a release blocker.
- Fail if a schema change is not reflected in both clients or in the parity file.

### 15.4 Versioning

- App versions: `MAJOR.MINOR.PATCH`.
- Contract version: integer in each schema file.
- Backup format version: integer.
- Database schema versions per platform.
- Write release notes with upgrade and migration notes.

---

## 16. Milestones

### M0 - Foundations (1 week)

Deliverables:

- Root layout, Makefile, contracts folder, CI skeletons.
- Web scaffold with theme tokens, routing, empty shell.
- Android scaffold with Compose, Hilt, Room, navigation shell.
- Contract schemas, provider presets, and the OpenRouter fixture set.
- Parity checklist with OpenRouter as the first release blocker.

Acceptance:

- Both apps build and launch.
- CI runs lint, unit tests, and builds.
- Contracts validate.

### M1 - OpenRouter-first core chat (2 weeks)

Deliverables:

- OpenRouter onboarding with `GET /key` validation and the public `GET /models` catalog.
- OpenRouter model metadata: pricing, context, modalities, capabilities, free badge, canonical slug.
- Chat screen with streaming, keep-alive handling, reasoning, usage/cost, cancellation, Markdown, code, and copy.
- OpenRouter provider routing preferences and protected request fields.
- Local conversation persistence, sidebar, rename, delete, new chat.
- Model switching and defaults.
- OpenRouter error taxonomy and retry policy.
- Attribution headers from public configuration.

Acceptance:

- End-to-end chat works on web and Android with OpenRouter alone.
- Browser-direct OpenRouter behavior is verified for `/key`, `/models`, and `/chat/completions`; any CORS blocker is treated as a release blocker.
- Key introspection, catalog mapping, free/paid badges, reasoning, and usage/cost are correct.
- Conversations survive restart.
- Cancel and error paths persist partial content.
- Tests cover the critical cases in section 14.2 items 1-10 and 15-21.
- No other provider is required to ship this preview.

### M2 - Additional providers and content (2 weeks)

Deliverables:

- Generalize the OpenRouter transport into the OpenAI-compatible adapter.
- OpenAI, Anthropic, Gemini, and Ollama adapters.
- Reasoning content display across providers.
- Image attachments and capability checks.
- Per-conversation parameters and system prompt.
- Search, pin, archive.
- Export/import.

Acceptance:

- Shared stream fixtures pass on both platforms, including all OpenRouter fixtures.
- Switching between all supported protocols works.
- Attachments are stored locally and rendered after reload.
- CORS failures produce actionable errors.
- OpenRouter behavior does not regress when other adapters are added.

### M3 - Android depth (2 weeks)

Deliverables:

- Secure key storage with Tink.
- Share target, photo picker, document picker.
- Tablet/foldable layouts.
- Process-death recovery.
- Compose screenshot tests.

Acceptance:

- Keys are encrypted and excluded from backup.
- Streaming survives configuration changes.
- UI tests cover onboarding, chat, model picker, and settings.

### M4 - Polish and release (1-2 weeks)

Deliverables:

- Accessibility pass and keyboard navigation.
- PWA install and update flow.
- Performance optimization and bundle budgets.
- Static container, deployment docs, release pipeline.
- Full parity audit.

Acceptance:

- Lighthouse mobile performance and accessibility at least 90.
- Playwright and Compose UI suites pass.
- Release artifacts are produced and documented.
- No open blockers in the parity checklist.

### M5 - Backup and sync (future, 3-4 weeks)

Deliverables:

- Encryption envelope and manifest.
- S3-compatible backend first.
- Manual backup and restore.
- Conflict resolution and tests.
- Android WorkManager integration.

Acceptance:

- Cross-device restore works with encrypted data.
- Storage provider cannot read content.
- Conflict tests pass for concurrent edits and deletions.

### M6 - Managed roksky deployment (future)

Deliverables:

- Tenant hosting flow, custom domains, branding config.
- BYO storage onboarding.
- Privacy and data-processing documentation.
- One-click deployment from the repository and from roksky.

---

## 17. Risks and mitigations

| Risk | Impact | Mitigation |
| --- | --- | --- |
| OpenRouter browser CORS blocks direct requests | OpenRouter-first web release is impossible | Treat as a release blocker; verify `/key`, `/models`, and `/chat/completions` in M1; document Android as the fallback and plan an optional self-hosted proxy only if unavoidable |
| OpenRouter catalog or pricing changes | Stale models or misleading costs | 24-hour cache with manual refresh, tolerant parsing, last-known-good snapshot, display timestamp and preserve raw metadata |
| OpenRouter upstream provider variability | Inconsistent output, latency, or moderation behavior | Show routing summary, expose fallbacks and provider preferences, surface upstream errors, link to provider details, avoid promising a specific upstream |
| OpenRouter free-tier rate limits | Failed sends for free models | Mark free models, distinguish rate limits from paid quota, show retry guidance, do not retry aggressively |
| Provider CORS blocks browser requests | Web chat fails for some providers | Maintain a CORS matrix, test during M1/M2, show actionable errors, offer Android fallback and future proxy |
| Browser key storage is not truly secure | Key theft through XSS | Strict CSP, no third-party scripts, sanitized Markdown, optional passphrase/session-only mode, clear warnings |
| Provider APIs change | Broken chats | Adapters isolated, fixture tests, remote config for presets, fast release process |
| Stream parsing differences | Corrupt or missing text | Shared fixtures, conformance suite, defensive parsers, ignore unknown events |
| Markdown XSS | Key/data theft | No raw HTML, sanitize, allowlist links, CSP |
| Large conversations degrade UI | Poor UX | Virtualization, pagination, isolated streaming updates |
| Android background limits kill streams | Interrupted responses | Persist partial content, foreground service enhancement, clear recovery UX |
| Local data unencrypted | Device compromise exposes chats | Document clearly, plan at-rest encryption as a future option |
| Sync conflicts | Data loss | Immutable messages, tombstones, deterministic merge, no destructive restore |
| Object storage CORS/signing complexity | Backup unusable | Start with S3-compatible, document CORS, test SigV4 in both clients |
| Dependency supply-chain attack | Key theft | Minimal dependencies, lockfiles, review, pinned versions, no postinstall scripts where possible |
| Store policy review | Android release delay | Privacy policy, BYOK explanation, data safety form, no hidden telemetry |
| Context/token inaccuracies | Confusing limits | Provider usage when available, label estimates, conservative preflight checks |

---

## 18. Decisions

1. **OpenRouter is the reference provider.** It is the default preset, the first end-to-end implementation, and a release blocker on both platforms. Other providers are built by generalizing the OpenRouter transport.
2. **Two independent clients, shared contracts.** React/Vite for web and native Kotlin for Android. Reason: best platform fit; shared code through contracts, not through a cross-platform runtime.
3. **No backend in phase 1.** Direct provider calls with user keys. Reason: self-deployment, privacy, and the "paste a key and go" requirement.
4. **Capacitor is optional.** It may be used for internal shells, but the native Kotlin app is the production Android target.
5. **Static-first web deployment.** No server secrets, easy one-click hosting, container optional.
6. **IndexedDB plus OPFS on web, Room plus private files on Android.** Local-first with explicit repositories.
7. **UUIDv7 identifiers and message sequences.** Stable ordering and future sync friendliness.
8. **Provider adapters with normalized events.** UI and storage do not understand provider JSON.
9. **BYOK keys are encrypted at rest on Android and optionally on web.** Session-only mode is available; risks are documented.
10. **Encrypted, user-owned backup later.** No sync in phase 1, but schemas and IDs are designed for it.
11. **Markdown without raw HTML.** Security over flexibility; sanitize everything.
12. **No analytics or telemetry.** Privacy default; local debug tooling only.
13. **English-only UI initially.** Structure strings for localization, but do not block phase 1 on translations.

---

## 19. Open questions

1. Product name and branding beyond the working title "ChatUI".
2. Which providers follow OpenRouter in the first public release? OpenRouter is mandatory; the rest of the list in section 3.4 is the proposed launch set. Confirm which additional providers are mandatory versus best-effort.
3. Should web default to remember keys or session-only? Recommendation: remember with encryption, with a prominent option to forget.
4. Are image and text-file attachments required in the first release, or can they slip to M2? Current plan: M2.
5. Minimum Android version: 26 is proposed; confirm if 28 or 29 is acceptable to simplify testing.
6. Is Play Store distribution required, or is a signed APK sufficient for the first release?
7. Should local conversations be encrypted at rest in phase 1? This materially affects UX and performance.
8. Is a model-generated conversation title worth an extra provider call, or is local truncation sufficient?
9. Which backup backend is first after S3-compatible: Azure Blob, GCS, or WebDAV?
10. What is the roksky managed-hosting brand, domain, and support model?
11. License for the repository.
12. Localization timeline and initial languages.
13. Should OpenRouter provider routing default to `data_collection: "deny"`, or should that remain an explicit user choice? Recommendation: explicit choice with a visible summary.
14. Is OpenRouter OAuth/PKCE key provisioning desirable later, or is manual API-key entry sufficient?

---

## 20. Appendix

### 20.1 Example SSE handling (web)

```ts
async function readSse(
  response: Response,
  onData: (data: string) => void,
  signal: AbortSignal
): Promise<void> {
  if (!response.body) throw new Error('Missing response body');
  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';

  while (true) {
    if (signal.aborted) break;
    const { value, done } = await reader.read();
    if (done) break;
    buffer += decoder.decode(value, { stream: true });

    let boundary = buffer.indexOf('\n\n');
    while (boundary !== -1) {
      const rawEvent = buffer.slice(0, boundary);
      buffer = buffer.slice(boundary + 2);
      const data = rawEvent
        .split('\n')
        .filter((line) => line.startsWith('data:'))
        .map((line) => line.slice(5).replace(/^ /, ''))
        .join('\n');
      if (data && data !== '[DONE]') onData(data);
      boundary = buffer.indexOf('\n\n');
    }
  }
}
```

Implementation notes:

- Normalize `\r\n` to `\n` before splitting.
- Handle the final buffer on stream end.
- Handle comments and event names for Anthropic, and ignore OpenRouter keep-alive comments such as `: OPENROUTER PROCESSING`.
- Bound buffer size to protect against a malicious or broken provider.
- Do not parse JSON in the transport layer; let adapters do it.

### 20.2 Example normalized reducer (web)

```ts
function reduceStream(state: StreamingState, event: StreamEvent): StreamingState {
  switch (event.type) {
    case 'start':
      return { ...state, responseId: event.responseId, started: true };
    case 'text-delta':
      return { ...state, text: state.text + event.text };
    case 'reasoning-delta':
      return { ...state, reasoning: state.reasoning + event.text };
    case 'usage':
      return { ...state, usage: event.usage };
    case 'finish':
      return { ...state, finishReason: event.reason, done: true };
    case 'error':
      return { ...state, error: event.error, done: true };
    default:
      return state;
  }
}
```

Flush this state to the UI at a bounded interval; persist at a coarser interval.

### 20.3 Example provider preset

```json
{
  "id": "openrouter",
  "label": "OpenRouter",
  "kind": "openai-compatible",
  "baseUrl": "https://openrouter.ai/api/v1",
  "auth": { "type": "bearer" },
  "keyValidation": { "method": "GET", "path": "/key" },
  "modelDiscovery": {
    "path": "/models",
    "format": "openrouter",
    "public": true,
    "cacheTtlSeconds": 86400
  },
  "attributionHeaders": {
    "HTTP-Referer": "app.origin",
    "X-Title": "app.name"
  },
  "browserCors": "unknown",
  "docsUrl": "https://openrouter.ai/docs",
  "defaultModelId": "openrouter/auto"
}
```

Numbers and model metadata in presets are best-effort and must be refreshable from discovery. OpenRouter is the only preset marked as required; the rest are launch targets.

### 20.4 Export format

```json
{
  "format": "chatui.export",
  "formatVersion": 1,
  "exportedAt": "2026-10-28T12:00:00.000Z",
  "appVersion": "0.1.0",
  "conversations": [],
  "messages": [],
  "attachments": [
    {
      "id": "uuid",
      "name": "photo.jpg",
      "mimeType": "image/jpeg",
      "sha256": "...",
      "size": 12345,
      "dataBase64": "..."
    }
  ],
  "providers": [],
  "models": [],
  "settings": {}
}
```

Rules:

- Exclude secrets by default.
- Include attachment bytes only when the user chooses "include attachments".
- Import validates schema, size limits, and IDs; never execute code.
- Import is non-destructive by default: merge, with an option to replace.
- Warn that exports are plaintext.

### 20.5 Backup envelope (future)

```text
offset  content
0       "CHATUI1\n"
8       uint32 headerLength (big endian)
12      header JSON (UTF-8)
...     12-byte IV
...     AES-256-GCM ciphertext + 16-byte tag
```

Header fields: `v`, `alg`, `kdf`, `iterations`, `salt`, `objectType`, `objectKey`.

### 20.6 Error message copy

| Code | User-facing copy |
| --- | --- |
| `auth` | Your API key was rejected. Check it and try again. |
| `permission` | This key does not have permission to use this model or endpoint. |
| `rate_limit` | The provider is rate limiting requests. Try again shortly. |
| `quota` | The provider reports insufficient credits or an exhausted quota. Check your OpenRouter credits or provider billing settings. |
| `content_filter` | The provider blocked this content. Edit the message or choose another model. |
| `model_not_found` | This model is unavailable. Pick another model. |
| `context_length` | This conversation is too long for the selected model. Start a new chat or use a larger model. |
| `network` | Could not reach the provider. Check your connection and base URL. |
| `cors` | The provider blocked this browser request. Try the Android app or a self-hosted proxy. |
| `timeout` | The provider took too long to respond. Try again. |
| `cancelled` | Response stopped. |
| `provider` | The provider returned an error. Try again. |
| `parse` | The provider returned an unexpected response. Update the app or report this. |
| `storage` | The app could not save data. Check available storage. |

### 20.7 Definition of done for phase 1

- A new user can install/launch the app, add an OpenRouter key, pick a model, and chat in under two minutes.
- OpenRouter works end-to-end on both platforms: `/key` validation, rich catalog, streaming, reasoning, usage/cost, routing preferences, and error handling.
- Streaming, stop, regenerate, edit, and error recovery work on both platforms.
- At least OpenRouter, OpenAI-compatible, Anthropic, Gemini, and Ollama providers work on both platforms where CORS permits.
- Conversations persist, search, export, and import correctly.
- Markdown, code, and math render safely.
- Attachments work with capable models and are blocked or warned otherwise.
- Accessibility and performance budgets are met.
- No keys or chat content leave the device except to the chosen provider.
- Static deployment and container deployment are documented and tested.
- The parity checklist is complete and reviewed.
