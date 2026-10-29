# M0 - Foundations

- Status: In progress (repository bootstrap complete)
- Target: 1 week
- Depends on: none
- Blocks: M1, M3.5
- Platforms: shared, web, android
- Spec: sections 4, 5, 6, 8.1-8.6, 9.1-9.5, 9.7, 11.12, 12.2-12.3, 15.1, 16.1-16.4, 17 (M0), 21.3-21.5

## Objective

Establish the shared contract layer, the web and Android application foundations, and a CI skeleton so feature work in M1 can start without schema, storage, or build-system redesign. This milestone delivers structure and plumbing only. It must not deliver provider calls or chat features.

## Entry criteria

None. The repository bootstrap prerequisite is complete: root `README.md`, `Makefile`, `.gitignore`, `.editorconfig`, and the `contracts/`, `scripts/`, `web/`, `android/`, and `rust/` skeleton exist. The completed record is [`docs/tasks/T000-foundations.md`](../tasks/T000-foundations.md).

Remaining M0 work must preserve the bootstrap decisions: independent client builds, no root Node workspace, and thin Makefile recipes.

## Scope by workstream

### 1. Shared contracts (blocking)

Owns `contracts/` and `scripts/validate-contracts.mjs`.

Deliver:

- `contracts/providers.json` with every preset from spec sections 3.4 and 7.5: OpenRouter (default, reference, required), OpenAI, Anthropic, Google Gemini, Groq, Mistral, DeepSeek, xAI, Together, Fireworks, Ollama, LM Studio, llama.cpp, and Custom.
- Preset fields: `id`, `label`, `kind`, `baseUrl`, auth type, key validation, model discovery, `browserCors`, `docsUrl`, `defaultModelId`, and attribution headers where applicable. OpenRouter includes `HTTP-Referer`/`X-Title` mappings and a 24-hour catalog cache TTL.
- Draft 2020-12 schemas: `provider-preset`, `model-info`, `chat-export`, `backup-manifest`, and `settings`. Every schema carries `contractVersion`, a title, and deliberate `additionalProperties` behavior for imports.
- Shared fixtures:
  - Streams: `openrouter-text`, `openrouter-reasoning`, `openrouter-usage`, `openrouter-error-200`, `openrouter-keepalive`, `openai-text`, `openai-reasoning`, `anthropic-text`, `gemini-text`, `ollama-ndjson`, and `error-429`.
  - Model catalogs: OpenRouter (paid, free `:free`, vision, reasoning, tool-capable, long-context, unusual price strings, unknown fields, canonical slugs), OpenAI, Anthropic, Gemini, and Ollama tags.
  - Errors: OpenRouter `402`, `429`, and `503` envelopes.
- `contracts/parity.md` with `Feature | Web | Android | Rust` columns and the initial rows from spec section 5.4, including desktop packaging and auto-update. Rust is `Optional` until the Rust client is enabled, with the required-when-enabled rule documented below the table.
- `scripts/validate-contracts.mjs` that parses every JSON contract, validates presets and model fixtures against schemas, checks fixture encoding and non-emptiness, enforces unique preset IDs and required fields, validates parity columns and rows, and exits non-zero with file-and-field-specific errors.
- Keep validator dependencies isolated under `scripts/package.json`; do not create a root workspace.

Boundaries:

- No client-specific types, adapters, or duplicated provider metadata in `contracts/`.
- No secrets, real prompts, personal data, or live API captures in fixtures.
- Do not modify client source from this workstream.

### 2. Web foundation

Owns `web/`.

Deliver:

- pnpm scaffold using the section 8.1 stack: React 19, TypeScript, Vite, TanStack Router, Zustand, `idb`, `zod`, UUIDv7, Vitest, Testing Library, MSW, ESLint, and Prettier. Do not add Tailwind.
- Folder structure from section 8.2, with placeholder feature folders that later milestones fill.
- Runtime `public/config.json` loader validated with `zod`: app name, default theme, default provider preset, enabled presets, custom-provider allowance, OpenRouter attribution origin/title, docs URL, and telemetry flag. Fail visibly on invalid config.
- Domain types matching section 6, including `Provider`, `Model`, `Conversation`, `ChatParams`, `Message`, `ContentPart`, `Attachment`, `Usage`, `ChatError`, `Settings`, and `BackupConfig`; UUIDv7 IDs and ISO 8601 timestamps.
- IndexedDB database `chatui` v1, versioned migrations, and repositories for conversations, messages, attachments, providers, models, settings, secrets, backup config, and sync queue. Use transactions for message append plus conversation metadata updates. Reads are limited and paginated per section 8.5.
- OPFS attachment byte store with IndexedDB Blob fallback, content-addressed `attachments/<sha256>` naming, and orphan cleanup helper.
- `SecretStore` with session-only, remember-on-device (AES-GCM with a non-extractable `CryptoKey`), and optional passphrase-lock modes. Never write keys to `localStorage`, URLs, logs, or the Cache API. Provide central redaction helpers.
- Routing shell for section 8.3 routes using history routing and static-host fallback; unknown feature routes may render placeholders.
- Zustand stores for shell state only: sidebar, dialogs, toasts, and drafts. Do not implement streaming state here.
- Design tokens and theming from section 11.12, plus shared primitives (Button, Dialog, Menu, Tooltip, Toast), focus styles, light/dark/system theme, density, chat font size, and reduced-motion support.
- Tests for config validation, UUID/timestamp helpers, migrations from an empty v1 database, every repository, OPFS fallback, secret encryption/decryption, and redaction.

Boundaries:

- No provider adapters, onboarding, chat screen, conversation sidebar, search, export/import UI, PWA, service worker, or deployment artifacts.
- UI components must be generic primitives, not feature screens.

### 3. Android foundation

Owns `android/`.

Deliver:

- Gradle Kotlin DSL project with a version catalog, modules from section 9.2, minSdk 26, latest stable compile/target SDK, Kotlin serialization, Hilt, Navigation Compose, Coil, WorkManager, SplashScreen, Activity Compose, Lifecycle, and Paging.
- `core:model` pure Kotlin models matching section 6, including `StreamEvent`, `FinishReason`, and `ChatError`; UUIDv7 IDs and ISO 8601 `Instant` storage.
- `core:database` Room entities, DAOs, database class, FTS4/FTS5 `messages_fts`, indexes, and versioned migrations per section 9.5. Destructive migration is forbidden in release builds.
- `core:datastore` persistence for non-secret settings and defaults.
- `core:common` result/error types, redaction helpers, retry primitives, and test utilities.
- `core:network` interfaces only; no provider implementations.
- Secret storage abstraction plus Tink `AndroidKeysetManager` backed by Android Keystore, AEAD AES-256-GCM, ciphertext-only persistence, forget-key/forget-all APIs, and backup exclusion rules for secret stores and databases.
- `core:designsystem` Material 3 theme, section 11.12 tokens, typography, dimens, light/dark/system theme, density, and reduced-motion support.
- `app` shell with Hilt wiring, edge-to-edge, splash screen, and navigation placeholders for onboarding, chat, conversations, providers, settings, and backup.
- Tests for Room DAOs using in-memory databases, migrations with `MigrationTestHelper`, FTS search, secret encryption/decryption, settings persistence, and model serialization.

Boundaries:

- No provider adapters, onboarding, chat screen, conversation drawer, share target, pickers, screenshots, release signing, baseline profiles, or deployment packaging.
- Navigation destinations may exist only as placeholders.

### 4. CI skeleton

Owns `.github/workflows/`.

Deliver:

- Contracts workflow that runs `scripts/validate-contracts.mjs` and fails on fixture or schema errors.
- Web workflow that installs pnpm and Node LTS, runs `pnpm install --frozen-lockfile`, contracts check, lint, typecheck, tests, and build once `web/` exists.
- Android workflow that sets up JDK and the Android SDK, caches Gradle, and runs `lintDebug`, `testDebugUnitTest`, and `assembleDebug` once `android/` exists.
- Least-privilege permissions, concurrency groups, dependency caching, artifact retention, and no secret echoing.

Boundaries:

- No release signing, container publishing, Play Store, Rust workflow, or full conformance matrix; those belong to M3.5 and M4.
- Workflows must not couple the three clients into one package-manager workspace.

### 5. Parity baseline

- Establish `contracts/parity.md` as the authoritative cross-client status table.
- Mark OpenRouter rows as the first release blockers.
- Do not mark any feature complete in M0 except foundation-level capabilities actually delivered.

## Out of scope

- Any provider network call, onboarding flow, chat UI, streaming, reasoning, usage, or model picker.
- Conversation sidebar, search, pin/archive, export/import, settings screens, attachments UI.
- PWA, service worker, deployment containers, release pipelines.
- Rust workspace and client (M3.5).
- Backup/sync and managed hosting.

## Exit gate

- `make contracts-check` exits 0 and validates providers, schemas, fixtures, and parity.
- Web: lint, typecheck, unit tests, and production build pass; the app launches to a themed shell; repositories persist across refresh; secrets are not plaintext in remember mode and are lost on reload in session mode.
- Android: `lintDebug`, `testDebugUnitTest`, and `assembleDebug` pass; the app launches to a themed shell; Room schema and migrations match section 9.5; secrets are encrypted and excluded from backup.
- CI workflows are valid and run contracts, web, and Android gates on pull requests.
- `contracts/parity.md` exists with the required columns and initial rows.
- No provider calls, chat features, or feature screens are implemented.

## Verification

```bash
make help
make contracts-check
make web-lint && make web-typecheck && make web-test && make web-build
make android-lint && make android-test && make android-assemble
git status --short
```

## Task-generation guidance

Expect 4-6 tasks. The contracts task is blocking and must be generated first. After it lands, web foundation and Android foundation can run in parallel. The CI skeleton can start early but is only complete after both scaffolds exist. Suggested slices:

1. Shared contracts, schemas, fixtures, parity, and validator.
2. Web scaffold, config, domain, storage, and secrets.
3. Web routing shell, design tokens, primitives, and foundation tests.
4. Android Gradle scaffold, domain, Room, DataStore, and secrets.
5. Android design system, app shell, and foundation tests.
6. CI skeleton and contracts/parity guard.

Split any task whose ownership would span `contracts/`, `web/`, and `android/`.

## Risks and open questions

- Schema churn after clients start: lock field names early and bump `contractVersion` for breaking changes.
- Fixture drift: fixtures are contracts; generated tasks must not edit them casually.
- IndexedDB migration mistakes: every migration needs an upgrade test.
- Android backup exclusion: verify extraction rules instead of assuming `allowBackup="false"` is sufficient.
- Over-scaffolding: do not build feature UI in M0 just to satisfy navigation placeholders.
