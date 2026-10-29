# T020 - Android foundation: Gradle, Room, DataStore, secrets

- Milestone: M0/M1
- Depends on: T001
- Suggested subagent: general
- Parallel-safe: yes, with T010 and T030
- Read: `docs/IMPLEMENTATION.md` sections 4, 5, 6, 9.1-9.5, 9.7, 9.11, 11.12, 12.3, 15.1

## Goal

Build the native Android foundation: Gradle project, module layout, Compose shell, Room schema and migrations, DataStore settings, Tink-backed secret storage, Hilt, navigation shell, design tokens, and shared domain models. Do not implement provider adapters or chat features.

## Owns

- `android/settings.gradle.kts`, `android/build.gradle.kts`, `android/gradle.properties`, `android/gradle/libs.versions.toml`, `android/build-logic/**`
- `android/app/**` (shell, DI, navigation placeholders, manifest)
- `android/core/model/**`, `android/core/common/**`, `android/core/designsystem/**`, `android/core/database/**`, `android/core/datastore/**`, `android/core/network/**` (interfaces only)
- `android/feature/*/` placeholders only

## Deliverables

1. Gradle Kotlin DSL project with a version catalog, modules from section 9.2, minSdk 26, latest stable compile/target SDK, Kotlin serialization, Hilt, Navigation Compose, Coil, WorkManager, SplashScreen, Activity Compose, Lifecycle, Paging.
2. `core:model` pure Kotlin data classes mirroring section 6: `Provider`, `Model`, `Conversation`, `ChatParams`, `Message`, `ContentPart`, `Attachment`, `Usage`, `ChatError`, `Settings`, `BackupConfig`, `StreamEvent`, `FinishReason`. UUIDv7 IDs and ISO 8601 `Instant` storage.
3. `core:database` Room entities, DAOs, database class, FTS4/FTS5 `messages_fts`, indexes, and versioned migrations as in section 9.5. Destructive migration forbidden in release builds.
4. `core:datastore` settings persistence for non-secret settings and defaults.
5. `core:common` result/error types, redaction helpers, retry policy primitives, and test utilities.
6. Tink `AndroidKeysetManager` with Android Keystore master key for AEAD AES-256-GCM secret storage; `SecretEntity` ciphertext only; "Forget key" and "Forget all keys" APIs. Configure backup exclusion for secret stores and databases.
7. `core:designsystem` Material 3 theme, design tokens from section 11.12, typography, dimens, dark/light/system, density, reduced motion support.
8. `app` shell with Hilt wiring, edge-to-edge, splash screen, and navigation placeholders for onboarding, chat, conversations, providers, settings, and backup.
9. Tests: Room in-memory DAOs, migration tests with `MigrationTestHelper`, FTS search, secret encryption/decryption, settings persistence, and model serialization.

## Steps

1. Create Gradle project and modules.
2. Add model, database, datastore, common, and secret storage.
3. Add design system and app shell.
4. Add migration and storage tests.
5. Run lint, unit tests, and assemble.

## Acceptance criteria

- `./gradlew :app:assembleDebug` succeeds and the app launches to a themed shell.
- Room schema matches section 9.5 with FTS search and required indexes.
- A migration test upgrades an older schema without data loss.
- Secrets are encrypted with Tink and never stored in plaintext.
- Backup exclusion rules cover secret stores and databases.
- No provider calls, chat UI, or attachments UI are implemented yet.
- `:app:lintDebug` and `:app:testDebugUnitTest` pass.

## Verification

```bash
cd android && ./gradlew :app:lintDebug :app:testDebugUnitTest :app:assembleDebug
```

## Out of scope

- OpenRouter adapter, onboarding, provider/model UI (T021).
- Chat screen, composer, Markdown, attachments (T022).
- Conversation drawer, search UI, export/import, share target (T023).
- Release signing, baseline profiles, final security/a11y/perf pass (T024, T025).
