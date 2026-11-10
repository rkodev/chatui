# CI evidence

- Task: `M4-05.1` CI/CD release pipelines
- Date: 2026-11-10
- Environment: local Linux workstation, Node 24.16.0, pnpm 11.7.0, JDK 21,
  Docker available, no GitHub Actions runner, no repository secrets, and no
  GHCR credentials.

## Environment blocker

The workflows were **not executed on GitHub Actions** in this environment. The
exact blocker is:

- No GitHub Actions runner is available locally, so `workflow_dispatch` and
  tag-triggered runs cannot be started.
- The four Android signing secrets (`ANDROID_KEYSTORE_BASE64`,
  `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`) are
  not present, so a signed APK/AAB cannot be produced locally.
- No GHCR credentials are present, so the web container cannot be pushed.
- The Rust signing secrets are not present. Rust is post-preview / parallel and
  is not a phase 1 release deliverable.

No artifact is claimed as produced by a GitHub Actions run. The local gates and
the local container build are recorded below.

## Workflow static validation

All six workflow files parse as YAML and expose the expected jobs:

| Workflow | Jobs |
| --- | --- |
| `web.yml` | `web`, `container` |
| `android.yml` | `android`, `connected-tests` |
| `android-release.yml` | `release`, `connected-tests` |
| `contracts.yml` | `validate` |
| `rust.yml` | `rust`, `supply-chain` |
| `rust-release.yml` | `verify`, `build`, `publish` |

## Local verification results

| Command | Result |
| --- | --- |
| `make contracts-check` | Passed: `Contract validation passed.` |
| `make web-lint` | Passed |
| `make web-typecheck` | Passed |
| `make web-test-coverage` | Passed; statements 81.95%, branches 71.81%, functions 81.77%, lines 81.92% |
| `make web-build` | Passed |
| `cd web && pnpm exec playwright test` | Passed: 30 tests |
| `make android-lint` | Passed: `BUILD SUCCESSFUL` |
| `make android-test` | Passed: `BUILD SUCCESSFUL` |
| `make android-assemble` | Passed: `BUILD SUCCESSFUL` |
| `cd android && ./gradlew :app:lintRelease :app:testDebugUnitTest :app:assembleRelease :app:bundleRelease` | Passed: `BUILD SUCCESSFUL` (unsigned local release; CI requires the four signing secrets) |
| `make rust-fmt` | Passed |
| `make rust-lint` | Passed |
| `make rust-test` | Passed |
| `make rust-build` | Passed |
| `cd rust && cargo deny check` | **Failed** on a pre-existing advisory: `CVE-2026-119375` in `libsqlite3-sys 0.30.1` via `sqlx-sqlite 0.8.6`. Post-preview / parallel only. |
| `cd rust && cargo audit` | **Failed**: 1 vulnerability and 10 allowed warnings (including unmaintained `zvariant`). Post-preview / parallel only. |
| `make web-container` | Passed: built `chatui-web:local` |
| Container smoke (`/`, `/healthz`, deep route) | Passed: `/` 200 with `Cache-Control: no-cache` and CSP; `/healthz` 200; deep route 200 |

The Rust supply-chain failures are pre-existing and belong to the post-preview
Rust track. They are not phase 1 release blockers because Rust is not shipping
(DEC-M4-01). They are recorded in the release notes as known issues for that
track.

## Per-pipeline status

| Pipeline | Trigger | Last run in this environment | Artifact status |
| --- | --- | --- | --- |
| `web.yml` | PR, push `dev`, tag `v*.*.*`, manual | Local gates and container build passed; GitHub run blocked | `web-dist`, `web-coverage`, `playwright-report` not uploaded; container not pushed |
| `android.yml` | PR, push `dev`, manual | Local gates passed; GitHub run blocked | `android-debug-apk` not uploaded |
| `android-release.yml` | Tag `v*.*.*`, manual | Local unsigned release build passed; signed run blocked on secrets | Signed APK/AAB/mapping/checksums not produced |
| `contracts.yml` | PR, push `dev`, manual | Local validator passed; GitHub run blocked | No artifact |
| `rust.yml` | PR, push `main`/`dev`, tags, schedule | Local gates passed except supply-chain advisories; GitHub run blocked | Post-preview only |
| `rust-release.yml` | Tags `v*`, `beta-*` | Not run; post-preview only | Post-preview only |

## Artifact names

- Web: `web-dist`, `web-coverage`, `playwright-report`; container
  `ghcr.io/<owner>/<repo>:0.1.0`, `:0.1`, `:latest`.
- Android: `android-debug-apk`, `android-release-apk`, `android-release-aab`,
  `android-release-mapping`, `android-release-checksums`,
  `android-screenshot-report`, `android-connected-test-report`.
- Rust: post-preview / parallel only; no phase 1 artifact.

## Secrets

Only secret names are recorded in [`../release.md`](../release.md). No secret
value, keystore, certificate, or token is recorded in this file or in any
artifact.
