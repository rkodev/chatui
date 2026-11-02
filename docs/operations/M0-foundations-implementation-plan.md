# M0 Foundations Implementation Plan

- Source milestone: [M0-foundations.md](../milestones/M0-foundations.md)
- Task breakdown: [M0 task list](M0/README.md)
- Authoritative spec: [IMPLEMENTATION.md](../IMPLEMENTATION.md)
- Status: Proposed
- Prepared: 2026-10-30
- Scope: shared contracts, web foundation, Android foundation, CI skeleton, parity baseline
- Planning assumption: the repository bootstrap prerequisite recorded in [T000-foundations.md](../tasks/T000-foundations.md) is complete and must not be redesigned.

## 1. Summary

M0 is currently at the bootstrap boundary. The root repository, developer entry points, ignore rules, documentation, and empty directory skeleton exist. None of the M0 delivery workstreams have started: there is no provider contract data, no web scaffold, no Android scaffold, no CI workflow, and no parity table.

| Area | Current completion | Assessment |
| --- | --- | --- |
| Repository bootstrap | Complete | Root README, Makefile, `.gitignore`, `.editorconfig`, docs, directory skeleton |
| Shared contracts | 0% | Only `.gitkeep` placeholders |
| Web foundation | 0% | Only `web/.gitkeep` |
| Android foundation | 0% | Only `android/.gitkeep` |
| CI skeleton | 0% | Only `.github/workflows/.gitkeep` |
| Parity baseline | 0% | `contracts/parity.md` does not exist |
| M0 exit gate | Not met | No contracts, client builds, tests, or CI evidence |

### 1.1 Critical path

```text
M0-01 Contracts
   |
   +--> M0-02 Web core ---- M0-03 Web shell ----+
   |                                            |
   +--> M0-04 Android core - M0-05 Android shell +--> M0-06 CI ---> M0 exit gate
```

Contracts are blocking. Web and Android can then proceed in parallel. CI can start early for contracts, but web and Android jobs are only complete after their scaffolds land. Rust remains untouched and out of scope.

### 1.2 Estimate

The milestone target is one week. From the current baseline, that requires at least two parallel implementation streams. A realistic estimate is 7-10 working days for one implementer, or 4-6 working days for two or three implementers working by area. The largest uncertainty is the Android scaffold and secret-storage testability, not the contracts.

## 2. Current-State Audit

### 2.1 Existing artifacts

| Path | State | Evidence |
| --- | --- | --- |
| `README.md` | Present | Documents BYOK, clients, prerequisites, commands, and security rules |
| `Makefile` | Present | All expected targets exist; they fail with actionable messages for missing directories |
| `.gitignore` | Present | Covers Node, pnpm, Vite, Gradle, Android, Rust, IDE, OS, env, secrets, signing material |
| `.editorconfig` | Present | UTF-8, LF, final newline, indentation conventions |
| `contracts/` | Empty skeleton | Only `.gitkeep` files under `contracts/`, `schema/`, `fixtures/{streams,models,errors}/` |
| `scripts/` | Empty | Only `.gitkeep` |
| `web/` | Empty | Only `.gitkeep` |
| `android/` | Empty | Only `.gitkeep` |
| `.github/workflows/` | Empty | Only `.gitkeep` |
| `rust/` | Intentionally empty | Skeleton directories only; M3.5 owns implementation |
| `docs/milestones/` | Present | M0-M6 milestone definitions |
| `docs/tasks/` | Bootstrap record only | T000 retained; no M0 task files yet |
| `docs/operations/` | Created by this plan | Contains this plan only |

### 2.2 Environment audit

Observed locally, not a CI contract:

| Tool | Observed | Implication |
| --- | --- | --- |
| Node.js | 24.16.0 | Compatible with the web toolchain; CI should pin Node LTS |
| npm | 11.13.0 | Used for isolated contract-tooling installs |
| pnpm | 11.7.0 | Compatible; pin via `packageManager` in `web/package.json` |
| JDK | 21.0.2 | Suitable for modern Android Gradle Plugin builds |
| Android SDK | Present at `/home/ronald/Android/Sdk` | Platforms 31-37 and build-tools 34-37 are installed |
| Android environment | `ANDROID_HOME` and `ANDROID_SDK_ROOT` unset; `sdkmanager` and `adb` not on PATH | Local Android builds need an env or `android/local.properties` setup |
| Gradle | Not on PATH | Generate and commit a Gradle wrapper; do not depend on a system Gradle |
| Rust | 1.98.1 | Not used in M0 |
| Docker | 29.9.2 | Available for optional actionlint validation |

### 2.3 Current failure behavior is expected

The following now fail only because their owned files do not exist:

```bash
make contracts-check   # missing scripts/validate-contracts.mjs
make web-install       # missing web/package.json
make android-test      # missing android/gradlew
```

The Makefile already provides the final command surface. The implementation plan should satisfy those targets rather than create a second command surface.

## 3. Gap Analysis

Status values: **Missing** means no implementation exists; **Partial** means scaffolding exists but the deliverable is not met; **Present** means complete for the current milestone.

### 3.1 Shared Contracts

| ID | Required deliverable | Current | Gap | Implementation note |
| --- | --- | --- | --- | --- |
| C-01 | `contracts/providers.json` with all 14 presets | Missing | Every preset is absent: OpenRouter, OpenAI, Anthropic, Google Gemini, Groq, Mistral, DeepSeek, xAI, Together, Fireworks, Ollama, LM Studio, llama.cpp, Custom | Use IDs `openrouter`, `openai`, `anthropic`, `google`, `groq`, `mistral`, `deepseek`, `xai`, `together`, `fireworks`, `ollama`, `lmstudio`, `llamacpp`, `custom` |
| C-02 | Preset fields and role markers | Missing | No `id`, `label`, `kind`, `baseUrl`, auth, key validation, model discovery, `browserCors`, `docsUrl`, `defaultModelId`, attribution headers | Add an explicit representation for OpenRouter's default/reference/required role; enforce exactly one default and one required preset |
| C-03 | OpenRouter attribution and catalog TTL | Missing | No `HTTP-Referer`/`X-Title` mapping and no 24-hour catalog cache TTL | Keep attribution values as placeholders such as `app.origin` and `app.name`; runtime config supplies actual values |
| C-04 | Five Draft 2020-12 schemas | Missing | `provider-preset`, `model-info`, `chat-export`, `backup-manifest`, `settings` schemas are absent | Every schema needs `$schema`, `$id`, `title`, `contractVersion`, and deliberate `additionalProperties` |
| C-05 | Stream fixtures | Missing | Eleven fixtures absent | Text, reasoning, usage, in-stream error, keep-alive, OpenAI text/reasoning, Anthropic, Gemini, Ollama NDJSON, and `error-429` |
| C-06 | Model catalog fixtures | Missing | OpenRouter plus OpenAI, Anthropic, Gemini, Ollama fixtures absent | OpenRouter fixture must include paid, `:free`, vision, reasoning, tool-capable, long-context, unusual price strings, unknown fields, and canonical slugs |
| C-07 | OpenRouter key fixture | Missing | Not explicitly listed in the M0 milestone, but required by spec section 4 and consumed by M1 | Add `contracts/fixtures/models/openrouter-key.json`; flag the milestone omission rather than skipping it |
| C-08 | Error fixtures | Missing | OpenRouter 402, 429, and 503 envelopes absent | Keep them as provider-shaped JSON, not normalized domain errors |
| C-09 | Parity baseline | Missing | `contracts/parity.md` absent | See section 3.5 |
| C-10 | Contract validator | Missing | `scripts/validate-contracts.mjs` absent | Must parse all JSON, validate schemas and fixtures, check UTF-8 and non-emptiness, unique IDs, required fields, parity columns and rows, and exit non-zero with file-and-field-specific errors |
| C-11 | Isolated validator dependencies | Missing | No `scripts/package.json` or lockfile | Use only local tooling dependencies under `scripts/`; no root Node workspace |
| C-12 | Fixture safety | Missing | No fixtures exist, so no leaked data yet | No real keys, prompts, personal data, or live API captures |
| C-13 | Fixture expected outputs | Missing / ambiguous | Spec 5.1 asks for fixtures with expected normalized output; the M0 milestone lists raw streams only | Add small expected-event snapshots under `contracts/fixtures/expected/` or record an explicit deferral to M1 conformance |
| C-14 | Model-info validation shape | Missing / ambiguous | Provider catalog fixtures are raw envelopes such as `{ "data": [...] }`, while `model-info.schema.json` describes normalized models | Define the mapping in the validator or split raw-provider and normalized-model schemas; do not pretend raw catalogs are normalized models |

### 3.2 Web Foundation

| ID | Required deliverable | Current | Gap | Implementation note |
| --- | --- | --- | --- | --- |
| W-01 | pnpm scaffold and stack | Missing | No `package.json`, lockfile, Vite config, TypeScript config, HTML entry point, ESLint, or Prettier | Pin `packageManager`; include React 19, TypeScript, Vite, TanStack Router, Zustand, `idb`, `zod`, `uuid`, Vitest, Testing Library, MSW, ESLint, Prettier |
| W-02 | Folder structure and placeholders | Missing | No `src/app`, `domain`, `providers`, `storage`, `features`, `platform`, `ui`, `styles`, or `test` structure | Create empty/placeholder folders only; do not implement provider adapters or feature screens |
| W-03 | Runtime config | Missing | No `public/config.json` or zod loader | Validate app name, default theme, default preset, enabled presets, custom-provider allowance, OpenRouter attribution, docs URL, telemetry flag; fail visibly |
| W-04 | Domain model | Missing | No TypeScript types or UUIDv7/timestamp helpers | Mirror spec section 6 exactly, including `Provider`, `Model`, `Conversation`, `ChatParams`, `Message`, `ContentPart`, `Attachment`, `Usage`, `ChatError`, `Settings`, `BackupConfig` |
| W-05 | Sync metadata type | Missing / ambiguous | Spec references `SyncMeta` and `syncState` without defining them | Add a documented placeholder type with no sync behavior; record as an M5 question |
| W-06 | IndexedDB v1 | Missing | No `chatui` database, migrations, stores, or indexes | Implement all nine stores from spec 8.5 with exact keys and indexes |
| W-07 | Repositories | Missing | No conversation, message, attachment, provider, model, settings, secret, backup-config, or sync-queue repositories | Use transactions for message append plus conversation metadata; limit reads to the last 50 messages with pagination |
| W-08 | OPFS attachment byte store | Missing | No content-addressed byte store, Blob fallback, or orphan cleanup | Use `attachments/<sha256>`; abstract the filesystem handle so tests can fake OPFS |
| W-09 | Secret storage | Missing | No session, remember-on-device, or passphrase modes; no AES-GCM device key; no redaction helpers | Never use `localStorage`, URLs, logs, or Cache API; store IV and ciphertext only; use a non-extractable AES-GCM key for remember mode |
| W-10 | Routing shell | Missing | No TanStack Router setup or route placeholders | Implement every route from spec 8.3 with history routing and SPA fallback; unknown feature routes may be placeholders |
| W-11 | Shell state | Missing | No Zustand stores for sidebar, dialogs, toasts, drafts | Shell state only; no streaming state in M0 |
| W-12 | Design tokens and primitives | Missing | No tokens, themes, density, font-size, reduced-motion support, Button, Dialog, Menu, Tooltip, or Toast | Map section 11.12 token names to CSS custom properties; use accessible focus styles and light/dark/system themes |
| W-13 | Web foundation tests | Missing | No test harness or tests | Cover config validation, IDs/timestamps, empty-v1 migrations, every repository, OPFS fallback, secret encrypt/decrypt, and redaction |
| W-14 | Security baseline | Missing / deferred | Section 8.6 requires a strict CSP; deployment headers belong to M4 | Add a baseline CSP meta policy in `index.html` now if it does not block local providers; record full header enforcement as M4 |
| W-15 | Feature boundaries | Missing | Nothing exists, so no leaked feature code yet | No provider adapters, onboarding, chat screen, sidebar, search, export/import UI, PWA, service worker, or deployment artifacts |

### 3.3 Android Foundation

| ID | Required deliverable | Current | Gap | Implementation note |
| --- | --- | --- | --- | --- |
| A-01 | Gradle Kotlin DSL project | Missing | No `settings.gradle.kts`, root build script, `gradle.properties`, version catalog, wrapper, or build logic | Commit the wrapper; pin AGP/Kotlin/Compose/library versions in the catalog; use latest stable compile/target SDK |
| A-02 | Module graph | Missing | No `app`, `core:*`, `feature:*`, or `build-logic` modules | Create all modules from spec 9.2; feature modules remain placeholders |
| A-03 | `core:model` | Missing | No pure Kotlin domain types | Mirror spec section 6, including `StreamEvent`, `FinishReason`, and `ChatError`; keep the module free of Android dependencies |
| A-04 | UUIDv7 on Android | Missing / ambiguous | No UUIDv7 generator or dependency decision | Decide between a vetted dependency and a tested local generator; minSdk 26 rules out assuming a platform UUIDv7 API |
| A-05 | Timestamp serialization | Missing | No ISO 8601 `Instant` serializer | Choose `java.time.Instant` with a serializer or `kotlinx-datetime`; keep the representation consistent with contracts |
| A-06 | Room database | Missing | No entities, DAOs, database class, FTS table, indexes, or migrations | Implement spec 9.5; include future backup/sync entities or explicitly defer them with a migration plan |
| A-07 | FTS choice | Missing / ambiguous | Spec says FTS4/FTS5 without choosing one | Prefer FTS4 for minSdk 26 compatibility unless the implemented Room/SQLite combination proves FTS5 reliable |
| A-08 | Migration and schema export | Missing | No versioned migrations, schema export directory, or migration tests | Every schema change needs a `MigrationTestHelper` test; destructive migration forbidden in release builds |
| A-09 | DataStore | Missing | No non-secret settings persistence | Persist settings and defaults; secrets must not enter DataStore |
| A-10 | `core:common` | Missing | No result/error types, redaction helpers, retry primitives, or test utilities | Keep platform-independent; retry primitives must be usable by later provider work |
| A-11 | `core:network` interfaces | Missing | No adapter interfaces | Interfaces only; no provider implementations, HTTP clients, or endpoint logic in M0 |
| A-12 | Secret storage | Missing | No Tink keyset, Android Keystore master key, AES-256-GCM encryption, ciphertext-only persistence, or forget APIs | Abstract the crypto engine so JVM unit tests do not require a real Android Keystore |
| A-13 | Backup exclusion | Missing | No manifest rules or extraction rules | Exclude secret stores and databases via `allowBackup` plus explicit data-extraction/full-backup rules for API 31+ and 26-30; verify rules, do not assume `allowBackup="false"` is enough |
| A-14 | Design system | Missing | No Material 3 theme, tokens, typography, dimens, themes, density, or reduced motion | Keep semantic token names aligned with web and spec 11.12 |
| A-15 | App shell | Missing | No Hilt wiring, edge-to-edge, splash screen, or navigation placeholders | Placeholder destinations only for onboarding, chat, conversations, providers, settings, backup |
| A-16 | Android tests | Missing | No DAO, migration, FTS, secret, settings, or serialization tests | Use in-memory Room, `MigrationTestHelper`, Tink test keysets, and serialization round trips |
| A-17 | Feature boundaries | Missing | Nothing exists, so no leaked feature code yet | No provider adapters, onboarding, chat UI, drawer, share target, pickers, screenshots, release signing, baseline profiles, or packaging |

### 3.4 CI Skeleton

| ID | Required deliverable | Current | Gap | Implementation note |
| --- | --- | --- | --- | --- |
| I-01 | Contracts workflow | Missing | No workflow file | Checkout, Node LTS, install `scripts/` dependencies, then run contracts validation; fail on schema or fixture errors |
| I-02 | Web workflow | Missing | No workflow file | Install pnpm and Node LTS, frozen lockfile install, contracts check, lint, typecheck, tests, build, artifact upload |
| I-03 | Android workflow | Missing | No workflow file | JDK and Android SDK setup, Gradle cache, `lintDebug`, `testDebugUnitTest`, `assembleDebug`, APK artifact |
| I-04 | Workflow guardrails | Missing | No permissions, concurrency, caching, retention, or secret-handling review | Use least-privilege permissions, PR concurrency groups, dependency caches, bounded artifact retention, and no secret echoing |
| I-05 | CI boundaries | Missing | Nothing exists, so no overreach yet | No release signing, container publishing, Play Store, Rust workflow, or full conformance matrix |
| I-06 | Contracts dependency install | Missing / process gap | `make contracts-check` runs Node directly and does not install validator dependencies | CI must run `make contracts-install` or `npm ci --prefix scripts` before validation |
| I-07 | Workflow validation | Missing | No local or PR-level workflow syntax check | Validate YAML locally with actionlint if available and require a successful PR run before marking M0 complete |

### 3.5 Parity Baseline

| ID | Required deliverable | Current | Gap | Implementation note |
| --- | --- | --- | --- | --- |
| P-01 | `contracts/parity.md` | Missing | File and table absent | Use exactly the Feature, Web, Android, and Rust columns |
| P-02 | Initial rows | Missing | No rows from spec 5.4 | Include all 21 rows, including desktop packaging and auto-update |
| P-03 | OpenRouter blocker marking | Missing | No release-blocker signal | Mark OpenRouter onboarding, key introspection, catalog, streaming/reasoning/usage, and routing rows as the first release blockers in notes below the table |
| P-04 | Rust optional rule | Missing | No required-when-enabled note | Rust may be `Optional` until enabled; document that it must meet the same bar once enabled |
| P-05 | Status honesty | Missing | No table to audit | Do not mark any feature complete in M0; foundation-level capabilities may be noted separately without changing the feature status |
| P-06 | Validator coupling | Missing | Validator does not yet check the table | Enforce required columns and rows in `scripts/validate-contracts.mjs` |

### 3.6 Cross-Cutting Gaps and Ambiguities

| ID | Gap | Impact | Proposed resolution |
| --- | --- | --- | --- |
| X-01 | Expected normalized stream outputs are not in the M0 deliverable list | Fixture drift and divergent client parsing later | Add minimal expected-event snapshots now, or record a formal deferral to M1 and make M1 the conformance gate |
| X-02 | Raw provider catalogs versus normalized model schema | Validator may reject valid provider shapes or validate the wrong thing | Add an explicit mapping step in the validator or separate raw and normalized schemas |
| X-03 | Provider default/reference/required role has no field shape | Validator cannot enforce the requirement consistently | Define explicit role flags or a role array in `provider-preset.schema.json` |
| X-04 | `SyncMeta` and `syncState` are referenced but undefined | Clients may invent incompatible types | Use a documented placeholder in M0 and defer the real shape to M5 |
| X-05 | Passphrase lock is called optional/stretch in the spec but listed as an M0 mode | Scope ambiguity for the web secret store | Implement the mode fully in M0; if time-boxed, implement it behind tests and do not silently omit it |
| X-06 | Android Tink/Keystore is not available in plain JVM unit tests | `testDebugUnitTest` could fail or secret tests could be weak | Inject the crypto engine; use Tink in-memory keysets for unit tests and Robolectric or instrumentation for Keystore behavior |
| X-07 | OPFS is unavailable in jsdom | Attachment-store tests may be unreliable | Abstract the filesystem handle and inject a fake in tests; test the IndexedDB Blob fallback separately |
| X-08 | Android SDK environment is not configured locally | Local Android gate may fail despite correct code | Set `ANDROID_HOME` or `android/local.properties` locally; CI uses its own SDK setup |
| X-09 | Gradle is not installed system-wide | Android scaffold cannot rely on a system Gradle | Generate and commit the wrapper using a locally downloaded Gradle distribution or a temporary container |
| X-10 | `.gitkeep` files and README/Makefile messages assume an incomplete M0 | Stale repository guidance after completion | Remove placeholders and update README/Makefile error messages only where the milestone status changes |
| X-11 | `scripts/generate-icons.sh`, `web/public/icons/`, and Playwright config appear in the spec layout but not in M0 scope | Could expand scope unnecessarily | Defer icons, PWA assets, and Playwright setup to M2/M4; do not add them just to match the layout |
| X-12 | Parity status vocabulary is not defined | Table may become inconsistent | Use `Required`, `In progress`, `Complete`, `N/A`, `Future`, `Optional`, and `Required when enabled`; document the meaning below the table |

## 4. Proposed Work Breakdown

Six tasks match the milestone's suggested slices. They are planning units; formal task records should later be generated under `docs/tasks/M0/` using the milestone task template.

### 4.1 Task map

| ID | Title | Owns | Depends on | Parallel-safe with |
| --- | --- | --- | --- | --- |
| M0-01 | Shared contracts, fixtures, parity, validator | `contracts/`, `scripts/` | none | none; blocks all clients |
| M0-02 | Web scaffold, config, domain, storage, secrets | `web/` scaffold and core source | M0-01 | M0-04 |
| M0-03 | Web routing shell, design tokens, primitives, tests | `web/src/app`, `ui`, `styles`, shell tests | M0-02 | M0-05 |
| M0-04 | Android Gradle scaffold, domain, Room, DataStore, secrets | `android/` Gradle and core modules | M0-01 | M0-02 |
| M0-05 | Android design system, app shell, tests | `android/core/designsystem`, `android/app`, feature placeholders | M0-04 | M0-03 |
| M0-06 | CI skeleton and contracts/parity guard | `.github/workflows/` | M0-01, M0-02 to M0-05 | none at completion |

### 4.2 M0-01: Shared contracts, fixtures, parity, validator

Goal: create the source-of-truth contract layer before any client code.

Deliverables:

- `contracts/providers.json` with all 14 presets and explicit OpenRouter role metadata.
- Five Draft 2020-12 schemas with `contractVersion`, titles, and import-safe `additionalProperties` behavior.
- All listed stream, model, key, and error fixtures, plus expected normalized output snapshots if that ambiguity is resolved in favor of M0.
- `contracts/parity.md` with the exact required columns and all 21 initial rows.
- `scripts/validate-contracts.mjs` and an isolated `scripts/package.json` plus lockfile.
- Validator checks for JSON parsing, schema validation, UTF-8 and non-empty fixtures, unique preset IDs, required fields, parity columns and rows, and file/field-specific failure messages.

Acceptance criteria:

- `make contracts-install && make contracts-check` exits 0.
- Every preset from spec 3.4/7.5 exists exactly once.
- OpenRouter is the only default/reference/required preset.
- Fixtures contain no secrets, real prompts, or personal data.
- A deliberately broken preset or parity file produces a non-zero exit with a precise message.
- No client source or provider adapter is introduced.

Verification:

```bash
make contracts-install
make contracts-check
node scripts/validate-contracts.mjs
git status --short
```

Out of scope: client types, live API calls, CORS verification, CI workflow wiring.

### 4.3 M0-02: Web scaffold, config, domain, storage, secrets

Goal: establish a buildable, testable web foundation without feature UI or provider behavior.

Deliverables:

- pnpm scaffold, lockfile, TypeScript/Vite configuration, ESLint, Prettier, test harness, and a minimal buildable entry point.
- Folder structure from spec 8.2 with placeholders, except provider implementations which remain empty.
- Runtime config schema, `public/config.json`, loader, and visible invalid-config state.
- Domain types and UUIDv7/ISO 8601 helpers.
- IndexedDB `chatui` v1 with all nine stores, migrations, repositories, and transaction helpers.
- OPFS attachment byte store with IndexedDB Blob fallback and orphan cleanup.
- Session, remember-on-device, and passphrase-lock secret modes with redaction helpers.
- Tests for config, identifiers/timestamps, migrations, repositories, OPFS fallback, secret encryption/decryption, and redaction.

Acceptance criteria:

- `pnpm lint`, `pnpm typecheck`, `pnpm test`, and `pnpm build` pass.
- The minimal app builds and loads without a provider or chat dependency.
- Repositories persist across a simulated reload.
- A v1 migration test upgrades an empty database.
- Remember mode never stores plaintext keys; session mode loses keys on reload.
- No provider adapter, onboarding, chat, or feature UI exists.

Verification:

```bash
make web-install
make web-lint
make web-typecheck
make web-test
make web-build
```

Out of scope: router shell, design tokens, shared primitives, feature screens.

### 4.4 M0-03: Web routing shell, design tokens, primitives, tests

Goal: make the web foundation launch to a themed, navigable shell.

Deliverables:

- TanStack Router route tree for all spec 8.3 routes with history routing and SPA fallback.
- Placeholder route components and an unknown-route fallback.
- Zustand shell stores for sidebar, dialogs, toasts, and drafts only.
- CSS token files, light/dark/system themes, density, chat font size, reduced motion, and focus styles.
- Accessible Button, Dialog, Menu, Tooltip, and Toast primitives.
- Shell tests for route resolution, theme selection, and primitive rendering.

Acceptance criteria:

- The app launches to a themed shell with working theme switching.
- All required routes resolve without feature screens.
- No streaming state, provider call, or chat feature exists.
- Web lint, typecheck, tests, and build pass.

Verification:

```bash
make web-lint && make web-typecheck && make web-test && make web-build
```

Out of scope: any feature screen, onboarding, model picker, chat UI, PWA, service worker, or deployment artifact.

### 4.5 M0-04: Android Gradle scaffold, domain, Room, DataStore, secrets

Goal: establish a buildable Android foundation with local persistence and encrypted secrets.

Deliverables:

- Gradle Kotlin DSL project, committed wrapper, version catalog, build logic, and all modules from spec 9.2.
- `core:model` domain types with UUIDv7 and ISO 8601 serialization.
- `core:database` entities, DAOs, database class, FTS table, indexes, schema export, and versioned migrations.
- `core:datastore` non-secret settings persistence.
- `core:common` result/error types, redaction, retry primitives, and test utilities.
- `core:network` interfaces only.
- Tink-backed secret storage with ciphertext-only persistence, forget-key/forget-all APIs, and backup exclusion rules.
- Tests for DAOs, migrations, FTS search, secret encryption/decryption, settings persistence, and model serialization.

Acceptance criteria:

- The full module graph configures and a debug build assembles.
- Room schema matches spec 9.5 with the required indexes and FTS table.
- A migration test upgrades a prior schema without data loss.
- Destructive migration is not enabled for release.
- Secrets are encrypted and excluded from backup.
- No provider implementation or feature screen exists.

Verification:

```bash
cd android && ./gradlew :app:assembleDebug
cd android && ./gradlew :app:testDebugUnitTest
```

Out of scope: design system, app shell, navigation, feature work.

### 4.6 M0-05: Android design system, app shell, tests

Goal: make the Android foundation launch to a themed shell with placeholder navigation.

Deliverables:

- Material 3 theme, section 11.12 tokens, typography, dimens, light/dark/system themes, density, and reduced motion.
- Hilt application wiring, edge-to-edge handling, splash screen, and navigation placeholders for onboarding, chat, conversations, providers, settings, and backup.
- Placeholder feature modules with no feature behavior.
- Tests as needed for shell composition and theme selection.

Acceptance criteria:

- The app launches to a themed shell.
- Navigation placeholders resolve without feature screens.
- `lintDebug`, `testDebugUnitTest`, and `assembleDebug` pass.
- No provider, chat, drawer, share target, picker, screenshot, signing, or packaging work exists.

Verification:

```bash
make android-lint && make android-test && make android-assemble
```

Out of scope: all feature UI and release configuration.

### 4.7 M0-06: CI skeleton and contracts/parity guard

Goal: run the contracts, web, and Android gates on pull requests.

Deliverables:

- Contracts workflow that installs validator dependencies and runs validation.
- Web workflow that installs pnpm and Node LTS, runs frozen-lockfile install, contracts check, lint, typecheck, tests, build, and artifact upload.
- Android workflow that sets up JDK and Android SDK, caches Gradle, runs `lintDebug`, `testDebugUnitTest`, `assembleDebug`, and uploads the debug APK.
- Least-privilege permissions, PR concurrency groups, dependency caches, bounded artifact retention, and no secret echoing.
- Removal of `.github/workflows/.gitkeep` and any placeholder files superseded by workflows.

Acceptance criteria:

- All workflow YAML parses and a pull request run completes the three gates.
- Contracts changes fail CI when validation fails.
- Web and Android jobs do not couple into a single package-manager workspace.
- No release signing, container publishing, Play Store, Rust, or full conformance matrix is added.

Verification:

```bash
make contracts-install && make contracts-check
make web-lint && make web-typecheck && make web-test && make web-build
make android-lint && make android-test && make android-assemble
# plus a successful PR workflow run or local actionlint validation
```

## 5. Execution Sequence

| Phase | Days (parallel capacity assumed) | Work | Gate |
| --- | --- | --- | --- |
| 0. Preflight | 0.5 | Confirm toolchains, set Android SDK env, decide contract ambiguities, generate Gradle wrapper strategy | Local commands can run |
| 1. Contracts | 0.5-1.5 | M0-01 | `make contracts-check` passes |
| 2. Client cores | 2-3 | M0-02 and M0-04 in parallel | Web core tests and Android core build/tests pass |
| 3. Client shells | 1.5-2 | M0-03 and M0-05 in parallel | Both apps launch to themed shells |
| 4. CI and gate | 0.5-1 | M0-06, full verification, parity update, README/status update | M0 exit gate evidence complete |

Single-implementer order: M0-01, M0-02, M0-03, M0-04, M0-05, M0-06. This is slower but avoids merge conflicts across client areas.

Parallel order: M0-01 first; then one implementer on M0-02 to M0-03 and one on M0-04 to M0-05; CI owner can draft contracts workflow after M0-01 and finish after both scaffolds.

## 6. Exit-Gate Evidence Matrix

| Exit-gate requirement | Evidence to capture | Responsible task |
| --- | --- | --- |
| `make contracts-check` exits 0 | Command output showing providers, schemas, fixtures, and parity validation | M0-01 |
| Web lint/typecheck/test/build pass | Command output for all four targets | M0-02, M0-03 |
| Web launches to a themed shell | Screenshot or short recording of the running app | M0-03 |
| Web repositories survive refresh | Manual check plus repository tests | M0-02 |
| Web remember mode is not plaintext | IndexedDB inspection and secret tests | M0-02 |
| Web session mode loses keys on reload | Manual check plus secret tests | M0-02 |
| Android lint/test/assemble pass | Gradle output for all three tasks | M0-04, M0-05 |
| Android launches to a themed shell | Screenshot or short recording from an emulator/device | M0-05 |
| Room schema and migrations match spec 9.5 | Schema export, migration test output, and DAO tests | M0-04 |
| Android secrets are encrypted and backup-excluded | Secret tests and manifest/extraction-rule review | M0-04 |
| CI workflows are valid and run on PRs | Workflow file review and successful PR run | M0-06 |
| `contracts/parity.md` exists with required columns/rows | File review and validator output | M0-01 |
| No provider calls or chat features | Source review, dependency review, and route/screen inventory | M0-02 to M0-05 |

Recommended evidence location: a short `docs/operations/M0-verification.md` written after the final gate, with command results and links to screenshots or CI runs. Do not mark milestone status complete until this evidence exists.

## 7. Risks and Mitigations

| Risk | Impact | Mitigation |
| --- | --- | --- |
| Contract field names churn after clients start | Rework across web and Android | Lock field names in M0-01; bump `contractVersion` for breaking changes; treat fixtures as contracts |
| Fixture drift | Clients normalize the same stream differently | Validator plus expected normalized outputs, or a formal M1 conformance gate |
| IndexedDB migration defects | Data loss or failed upgrades | Every migration has an upgrade test; no ad hoc schema edits |
| OPFS unavailable in tests | Untested attachment fallback | Abstract the filesystem handle; test fallback with fake-indexeddb |
| Android Keystore unavailable in JVM tests | Weak secret tests or failing unit tests | Injectable crypto engine; Tink in-memory keysets for unit tests; Robolectric/instrumentation for Keystore |
| Android backup rules incomplete | Keys restored to another device | Explicit data-extraction and full-backup rules for all supported API levels; verify with tests or manifest review |
| Gradle wrapper bootstrap | Android gate cannot run locally or in CI | Generate and commit the wrapper early; use the version catalog to pin versions |
| Android SDK not configured locally | Misleading build failures | Set `ANDROID_HOME` or `local.properties`; document CI vs local setup |
| One-week target with from-zero scaffolds | Milestone slips | Parallelize web and Android, timebox ambiguities, and do not add feature UI |
| Over-scaffolding | M0 accidentally ships feature UI or provider metadata | Enforce ownership boundaries and review each task against the M0 out-of-scope list |
| Dependency drift | Lint/test/build failures months later | Commit lockfiles and version catalog; pin CI tool versions |
| Root Node workspace introduced | Coupled clients and slower builds | Keep `scripts/package.json` and `web/package.json` isolated; no root `package.json` workspace |

## 8. Decisions, Assumptions, and Open Questions

### 8.1 Decisions already made by the milestone

- OpenRouter is the only required, default, and reference provider.
- Rust remains optional and untouched in M0.
- The clients build independently; there is no root Node workspace.
- M0 delivers structure and plumbing only, with no provider calls or chat features.
- Contracts are the single source of truth for provider metadata, schemas, fixtures, and parity.

### 8.2 Assumptions for this plan

- `contractVersion` starts at `1`.
- Fixtures are hand-authored synthetic data, not live captures.
- Feature folders contain only placeholders in M0.
- The latest stable Android compile/target SDK is selected at implementation time and recorded in the version catalog.
- FTS4 is acceptable if FTS5 is not reliable at minSdk 26.
- A baseline web CSP can be added in M0 without blocking provider connections; full deployment headers remain M4.
- Task files will be generated under `docs/tasks/M0/` before implementation starts, following the milestone task template.

### 8.3 Open questions to resolve before or during M0

| Question | Proposed default | Needed by |
| --- | --- | --- |
| Are expected normalized fixture outputs part of M0? | Add them in M0-01 if low cost; otherwise defer to M1 with a written note | M0-01 |
| How are raw provider catalogs validated against `model-info.schema.json`? | Add an explicit normalization step in the validator or separate raw/normalized schemas | M0-01 |
| How are default/reference/required provider roles represented? | Explicit boolean flags or a role array in `provider-preset.schema.json` | M0-01 |
| What is the `SyncMeta`/`syncState` placeholder shape? | A documented placeholder with no behavior, revisited in M5 | M0-02, M0-04 |
| Is passphrase lock fully required in M0? | Implement all three modes, with tests | M0-02 |
| Which UUIDv7 implementation does Android use? | Vetted dependency or tested local generator; record the choice | M0-04 |
| Which FTS version is used? | FTS4 for compatibility unless FTS5 is proven | M0-04 |
| Does M0 include `openrouter-key.json` even though the milestone list omits it? | Yes, because spec section 4 includes it and M1 consumes it | M0-01 |
| How is parity status encoded? | Requirement-level values plus notes; no false completion | M0-01 |
| Should CI use path filters? | No path filters in M0; run the three gates on every PR | M0-06 |

## 9. Definition of Done for This Plan

This plan is complete when:

1. M0-01 through M0-06 are implemented or explicitly re-scoped with written approval.
2. Every gap row in section 3 is either resolved or recorded as an accepted deferral with an owner and milestone.
3. The M0 exit-gate evidence in section 6 exists.
4. `contracts/parity.md` is updated and truthful.
5. No provider calls, chat features, feature screens, PWA, release signing, or deployment artifacts were introduced.
6. The milestone status and task records are updated.
