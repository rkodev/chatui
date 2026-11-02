# M0 Exit-Gate Verification

- Date: 2026-11-02
- Milestone: [M0 Foundations](../../milestones/M0-foundations.md)
- Task: [M0-06.3](M0-06.3-final-verification.md)
- Result: Pass. M0 exit-gate evidence is recorded below.

## Environment

| Item | Value |
| --- | --- |
| OS | Linux (Ubuntu 26.04.1 LTS), x86_64 |
| Node.js | 24.16.0 |
| pnpm | 11.7.0 |
| JDK | Temurin 21.0.2+13 |
| Gradle wrapper | 9.8.1 |
| Android compile/target SDK | 37 |
| Android minSdk | 26 |
| Android emulator | Pixel_4 AVD, API 29, x86 |
| Browser | Chromium headless (snap) |
| CI simulation | `act` 0.2.x with `ghcr.io/catthehacker/ubuntu:act-latest` |

`android/local.properties` points at the local SDK and is gitignored. It is not
committed and is not part of the evidence.

## Final exit gate

All commands were run from the repository root on 2026-11-02.

| Command | Result |
| --- | --- |
| `make help` | Pass. Printed the contracts, web, Android, Rust, and meta target list. |
| `make contracts-install` | Pass. `npm --prefix scripts install` reported 7 audited packages, 0 vulnerabilities. |
| `make contracts-check` | Pass. `Contract validation passed.` |
| `make web-lint` | Pass. `eslint .` completed with no findings. |
| `make web-typecheck` | Pass. `tsc -b` completed with no errors. |
| `make web-test` | Pass. 25 test files, 144 tests, 0 failures. |
| `make web-build` | Pass. Vite 8.3.5 built 299 modules; `dist/index.html`, `dist/assets/index-CsHhLRTX.css`, `dist/assets/index-BjnrV8cu.js`. |
| `make android-lint` | Pass. `BUILD SUCCESSFUL`; 384 actionable tasks. |
| `make android-test` | Pass. `BUILD SUCCESSFUL`. A forced rerun (`./gradlew testDebugUnitTest --rerun`) executed 87 tests across 19 result files with 0 failures, 0 errors, 0 skipped. |
| `make android-assemble` | Pass. `BUILD SUCCESSFUL`; 322 actionable tasks; debug APK produced. |
| `git status --short` | Clean apart from the intended M0-06.3 documentation and placeholder changes. |

A first forced Android test rerun failed because `/tmp` was full: Robolectric
could not create its native temp files and the JVM aborted with `SIGBUS`. After
removing stale temporary Gradle caches under `/tmp/opencode`, the same rerun
passed. This was an environment issue, not a source or test failure.

## CI workflows

All three workflow files parse and pass `actionlint`:

```bash
/tmp/opencode/bin/actionlint .github/workflows/contracts.yml \
  .github/workflows/web.yml .github/workflows/android.yml
# exit 0
```

Each workflow was executed end-to-end with `act` on the `pull_request` event:

| Workflow | Command | Result |
| --- | --- | --- |
| Contracts | `act pull_request -W .github/workflows/contracts.yml -P ubuntu-latest=ghcr.io/catthehacker/ubuntu:act-latest` | Job succeeded. `Contract validation passed.` |
| Web | `act pull_request -W .github/workflows/web.yml -P ubuntu-latest=ghcr.io/catthehacker/ubuntu:act-latest --artifact-server-path /tmp/opencode/artifacts-final` | Job succeeded. Lint, typecheck, 144 tests, and build passed. `web-dist` artifact uploaded (155,031 bytes). |
| Android | `act pull_request -W .github/workflows/android.yml -P ubuntu-latest=ghcr.io/catthehacker/ubuntu:act-latest --artifact-server-path /tmp/opencode/artifacts-final` | Job succeeded. `lintDebug`, `testDebugUnitTest`, and `assembleDebug` passed. `android-debug-apk` artifact uploaded (15,981,150 bytes). |

No GitHub-hosted run exists yet. The repository API reports
`total_count: 0` for workflow runs because no branch has been pushed or PR
opened from this task runner. The `act` runs above are the pull-request
evidence; record the hosted run links on the first push/PR.

### Workflow review

| Check | Contracts | Web | Android |
| --- | --- | --- | --- |
| Least-privilege permissions | `contents: read` | `contents: read` | `contents: read` |
| Concurrency group | `contracts-${workflow}-${ref}`, cancel in progress | `web-${workflow}-${ref}`, cancel in progress | `android-${workflow}-${ref}`, cancel in progress |
| Dependency caching | npm cache keyed by `scripts/package-lock.json` | pnpm cache keyed by `web/pnpm-lock.yaml` | Gradle dependency and wrapper cache via `gradle/actions/setup-gradle` |
| Artifact retention | None | `web-dist`, 7 days, fails if missing | `android-debug-apk`, 7 days, fails if missing |
| Secret echoing | None | None | None |
| Untrusted input interpolation | None; no event fields in `run` steps | None | None |
| Triggers | `pull_request`, push to `dev` | `pull_request`, push to `dev` | `pull_request`, push to `dev` |

No path filters are used, matching the M0 decision to run all three gates on
every pull request. No release signing, publishing, deployment, or secret
material is referenced.

## Manual checks

### Web shell

Started the Vite dev server on `http://127.0.0.1:5173/` and loaded `/` with
headless Chromium:

```bash
chromium --headless --no-sandbox --disable-gpu --virtual-time-budget=5000 \
  --dump-dom http://127.0.0.1:5173/
```

The rendered DOM contained `data-theme`, `data-density="comfortable"`,
`data-chat-font-size="md"`, the `app-shell` layout, the `ChatUI` heading, and
the `No conversation selected` route placeholder. The server was stopped after
the check.

### Web persistence

`web/src/storage/repositories.test.ts` exercises create, read, update, list,
delete, pagination, transactional message append, cascade deletion, and
reopening the database. `web/src/storage/migrations.test.ts` covers the v1
schema. All 144 web tests pass. The app has no conversation UI in M0, so a
separate manual browser refresh was not possible; the repository tests are the
persistence evidence.

### Web secrets

`web/src/storage/SecretStore.test.ts` asserts:

- session-mode secrets stay in memory only and are lost on reload;
- remember-mode secrets persist only IV and ciphertext, never plaintext;
- the device key is a non-extractable `CryptoKey` stored in IndexedDB;
- passphrase mode never stores the passphrase or plaintext;
- switching modes removes or persists ciphertext as appropriate.

The app has no secret-entry UI in M0, so a manual IndexedDB inspection was not
possible. The tests are the evidence for the ciphertext-only and session-loss
invariants.

### Android shell

Installed `android/app/build/outputs/apk/debug/app-debug.apk` on the Pixel_4
API 29 emulator and launched `dev.chatui/.MainActivity`. The resumed activity
was `dev.chatui/.MainActivity`. A `uiautomator` hierarchy dump showed the
themed shell and all six destinations: `Onboarding`, `Chat`, `Conversations`,
`Providers`, `Settings`, and `Backup`, plus the placeholder text
`Conversation history and search arrive in M1.` `ShellThemeTest` covers the
theme and density wiring.

### Room schema and migrations

- Schema export: `android/core/database/schemas/dev.chatui.core.database.ChatUiDatabase/1.json`.
- `MigrationTest` validates the version 1 schema and records that no migrations
  exist yet.
- The forced Android test rerun passed all 87 tests, including the DAO, FTS,
  migration, and destructive-migration guard tests.

### Android secret storage

`SecretStore` writes only `SecretEntity.ciphertext`; plaintext is passed to the
injected `SecretCipher` and never persisted. `SecretStoreTest` and
`TinkSecretCipherTest` pass. The production `AndroidKeysetManager` path still
needs a device or Robolectric check; see the deferral below.

### Android backup exclusion

`android/app/src/main/AndroidManifest.xml` keeps `android:allowBackup="false"`
and declares `android:dataExtractionRules` and `android:fullBackupContent`.
Both rule files exclude `chatui.db`, `chatui.db-shm`, `chatui.db-wal`,
`chatui.db-journal`, `datastore/`, and `chatui_secret_keyset_prefs.xml`.
`BackupRulesTest` passes.

## No provider calls or chat features

- No `fetch`, `axios`, `XMLHttpRequest`, `WebSocket`, or `EventSource` calls
  exist in `web/src` or Android main sources.
- `android/core/network` contains interfaces and models only; there are no
  provider implementations.
- Web routes render placeholders only. Android feature modules render
  placeholder composables only.
- No onboarding flow, chat screen, conversation sidebar, search, export/import
  UI, PWA manifest, or service worker exists.
- `contracts/parity.md` has the required `Feature | Web | Android | Rust`
  columns and the initial rows. It explicitly states that no feature is
  complete at M0.

## Accepted deferrals

| Gap | Owner | Target | Notes |
| --- | --- | --- | --- |
| Hosted GitHub Actions run links | Repository maintainer | First push/PR after M0 | Local `act` pull-request runs passed for all three workflows. The GitHub API currently reports zero workflow runs. |
| Android production Keystore path not exercised by JVM tests | Android implementer | M1 or M3 device/instrumentation work | `TinkSecretCipher.androidKeystore` requires a real Android Keystore. JVM tests use an in-memory Tink keyset. |
| Manual web IndexedDB inspection | Web implementer | M1, when secret-entry UI exists | M0 has no secret-entry UI. `SecretStoreTest` covers ciphertext-only and session-loss behavior. |
| Rust workspace remains empty placeholders | Rust implementer | M3.5 | Rust is optional and out of scope for M0. |

## Placeholder cleanup

Removed `.gitkeep` files only where a real file now exists:

- `android/.gitkeep`
- `web/src/domain/.gitkeep`
- `web/src/platform/.gitkeep`
- `web/src/storage/.gitkeep`
- `web/src/styles/.gitkeep`

Kept `.gitkeep` files in intentionally empty directories reserved for later
milestones: `web/src/features/*`, `web/src/providers`, and the `rust/` tree.
