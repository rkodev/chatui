# Release engineering

This guide describes the ChatUI phase 1 preview release pipelines, the secrets
they require, the artifacts they produce, and the manual steps to cut a release.
It is the operator-facing companion to the workflows in `.github/workflows/`.

## Release scope

| Client | Status for `0.1.0` |
| --- | --- |
| Web | Required release-gated client |
| Android | Required release-gated client |
| Rust desktop | **Post-preview / parallel**; not a phase 1 release deliverable |

The Rust decision is recorded in
[`M4-decisions.md`](M4-decisions.md) (DEC-M4-01). The Rust workflows remain in
the repository for the parallel track, but no Rust artifact is required for the
phase 1 preview and no Rust signing secret is required to cut `v0.1.0`.

## Workflow triggers and outputs

| Workflow | Triggers | Required gates | Artifacts / side effects |
| --- | --- | --- | --- |
| `web.yml` | Pull requests, pushes to `dev`, tags `v*.*.*`, manual dispatch | Frozen-lockfile install, contracts, lint, typecheck, unit tests with V8 coverage, production build, Playwright against the built app with mocked providers | `web-dist`, `web-coverage`, `playwright-report` (failure only); on a `v*.*.*` tag, publishes the static container to GHCR with semver and `latest` tags |
| `android.yml` | Pull requests, pushes to `dev`, manual dispatch | Contracts, `lintDebug`, `testDebugUnitTest`, `assembleDebug`; optional emulator connected tests on manual dispatch | `android-debug-apk`; optional connected-test reports |
| `android-release.yml` | Tags `v*.*.*`, manual dispatch | Contracts, signing-secret check, `lintDebug`, `testDebugUnitTest`, Roborazzi screenshot verification, `lintRelease`, signed `assembleRelease` and `bundleRelease` | Signed APK, signed AAB, R8 mapping, SHA-256 checksums, screenshot and connected-test reports; creates a GitHub Release on a tag |
| `contracts.yml` | Pull requests, pushes to `dev`, manual dispatch | JSON Schema compilation, every fixture (including OpenRouter stream/error fixtures), parity file | Fails the run on any schema, fixture, or parity mismatch |
| `rust.yml` | Pull requests, pushes to `main`/`dev`, tags, weekly schedule | Pinned toolchain, fmt, clippy, nextest, conformance, release build, `cargo-deny`, `cargo-audit` | Post-preview / parallel only; not a phase 1 release deliverable |
| `rust-release.yml` | Tags `v*`, `beta-*` | Rust release gates, signed desktop artifacts and update manifests | Post-preview / parallel only; not a phase 1 release deliverable |

All workflows use least-privilege `permissions`, a concurrency group, dependency
caching, and bounded artifact retention. Only the Android release job and the
web container job request write access (`contents: write` and `packages: write`
respectively); every other job is read-only.

## Required secret names

Secret values live only in the GitHub Actions secret store. Never commit, echo,
or log them. The workflows reference them only through `${{ secrets.* }}`.

### Required for the phase 1 release

| Secret | Used by | Purpose |
| --- | --- | --- |
| `ANDROID_KEYSTORE_BASE64` | `android-release.yml` | Base64-encoded release keystore |
| `ANDROID_KEYSTORE_PASSWORD` | `android-release.yml` | Keystore password |
| `ANDROID_KEY_ALIAS` | `android-release.yml` | Release key alias |
| `ANDROID_KEY_PASSWORD` | `android-release.yml` | Release key password |

`android-release.yml` fails closed before building if any of these four secrets
is empty. It never substitutes a debug key. The keystore is decoded to a
temporary file outside the repository and is not uploaded.

The web container job uses the automatic `GITHUB_TOKEN` with `packages: write`;
no additional registry secret is required for GHCR.

### Post-preview / parallel only

These names are documented for the Rust track. They are **not** required for the
phase 1 preview and must not be added just to make a phase 1 tag green.

| Secret | Used by | Purpose |
| --- | --- | --- |
| `UPDATE_SIGNING_PRIVATE_KEY_STABLE` | `rust-release.yml` | Stable-channel update-manifest signing key |
| `UPDATE_SIGNING_PRIVATE_KEY_BETA` | `rust-release.yml` | Beta-channel update-manifest signing key |
| `APPLE_CERTIFICATE` | `rust-release.yml` | macOS signing certificate (base64) |
| `APPLE_CERTIFICATE_PASSWORD` | `rust-release.yml` | macOS certificate password |
| `APPLE_SIGNING_IDENTITY` | `rust-release.yml` | macOS signing identity |
| `APPLE_NOTARY_PROFILE` | `rust-release.yml` | macOS notarization keychain profile |
| `APPLE_NOTARY_APPLE_ID` | `rust-release.yml` | macOS notarization Apple ID |
| `APPLE_NOTARY_TEAM_ID` | `rust-release.yml` | macOS notarization team ID |
| `APPLE_NOTARY_PASSWORD` | `rust-release.yml` | macOS notarization app-specific password |
| `WINDOWS_CERTIFICATE` | `rust-release.yml` | Windows signing certificate (base64) |
| `WINDOWS_CERTIFICATE_PASSWORD` | `rust-release.yml` | Windows certificate password |

## Versioning

The release values are frozen in
[`M4-decisions.md`](M4-decisions.md) (DEC-M4-03 and DEC-M4-04) and follow spec
section 16.5.

| Version | Value for `0.1.0` | Source of truth |
| --- | --- | --- |
| App semver | `0.1.0` | Annotated git tag `v0.1.0` |
| Contract version | `1` | `contracts/schema/*.json` |
| Backup/export format version | `1` | Web and Android export code |
| Web IndexedDB schema version | `1` | `DB_VERSION` |
| Android Room schema version | `1` | `CHATUI_DATABASE_VERSION` |
| Android export format version | `1` | `CHATUI_EXPORT_FORMAT_VERSION` |
| Rust SQLite migration head | `0001_initial.sql` | Not shipping; post-preview |

CI derives the Android `versionName` from an exact semantic tag (or an explicit
`ANDROID_VERSION_NAME` input) and the `versionCode` from an explicit positive
integer input or the full-history git commit count. The commit count is
monotonic because the release checkout uses `fetch-depth: 0`. The web container
tags come from the git tag through `docker/metadata-action` (`0.1.0`,
`0.1`, and `latest`).

These integers are independent. A change to one does not imply a change to
another. Any change must be recorded in the release notes with upgrade and
migration notes.

## Artifacts

| Artifact | Workflow | Retention | Where to find it |
| --- | --- | --- | --- |
| `web-dist` | `web.yml` | 7 days | Actions run artifacts |
| `web-coverage` | `web.yml` | 14 days | Actions run artifacts |
| `playwright-report` | `web.yml` | 7 days | Actions run artifacts (failure only) |
| Web container image | `web.yml` | Registry policy | `ghcr.io/<owner>/<repo>:0.1.0`, `:0.1`, `:latest` |
| `android-debug-apk` | `android.yml` | 7 days | Actions run artifacts |
| `android-release-apk` | `android-release.yml` | 30 days | Actions run artifacts and GitHub Release |
| `android-release-aab` | `android-release.yml` | 30 days | Actions run artifacts and GitHub Release |
| `android-release-mapping` | `android-release.yml` | 30 days | Actions run artifacts and GitHub Release |
| `android-release-checksums` | `android-release.yml` | 30 days | Actions run artifacts and GitHub Release |
| `android-screenshot-report` | `android-release.yml` | 14 days | Actions run artifacts |
| `android-connected-test-report` | `android.yml`, `android-release.yml` | 14 days | Actions run artifacts |
| Rust desktop artifacts | `rust-release.yml` | 7 days | Post-preview / parallel only |

The Android checksum file contains SHA-256 digests for the signed APK, the
signed AAB, and the R8 mapping file. The GitHub Release attaches the APK, AAB,
mapping, and checksum file. The web container digest is available from the
`docker/build-push-action` step summary and the GHCR package page.

## Cutting a release

1. Confirm the release scope and version in
   [`M4-decisions.md`](M4-decisions.md). Do not change the version in a workflow
   file.
2. Confirm `dev` is green: `contracts.yml`, `web.yml`, and `android.yml` must
   pass. Run `make release-check` locally if you need the same gates outside CI.
3. Update [`release-notes.md`](release-notes.md) with the changes, upgrade and
   migration notes, known issues, and the artifact list. The release blocker
   list must be empty.
4. Confirm the four Android signing secrets exist in the repository secret
   store. Do not print or copy their values.
5. Create and push the annotated tag:

   ```bash
   git tag -a v0.1.0 -m "ChatUI 0.1.0 phase 1 preview"
   git push origin v0.1.0
   ```

6. Watch the tag-triggered runs:
   - `web.yml` runs the gates and publishes the container to GHCR.
   - `android-release.yml` builds the signed APK/AAB, uploads the mapping and
     checksums, and creates the GitHub Release.
   - `rust-release.yml` may run for the parallel Rust track. It is not a phase 1
     release deliverable; a missing Rust signing secret is not a phase 1
     blocker.
7. Verify the GitHub Release assets and the GHCR tags. Download the checksum
   file and compare it with the attached APK, AAB, and mapping.
8. Record the run URLs, artifact names, and any deviation in
   [`evidence/ci/README.md`](evidence/ci/README.md).

Do not publish the Play Store listing, data-safety forms, or store metadata from
this task. Those remain out of scope.

## Local verification

The same gates can be run locally:

```bash
make contracts-check
make web-lint && make web-typecheck && make web-test-coverage && make web-build
cd web && pnpm exec playwright test
make android-lint && make android-test && make android-assemble
cd android && ./gradlew :app:lintRelease :app:testDebugUnitTest :app:assembleRelease :app:bundleRelease
make rust-fmt && make rust-lint && make rust-test && make rust-build
cd rust && cargo deny check && cargo audit
```

The Rust commands are post-preview / parallel and are not required for the phase
1 release. The Android release build requires the four signing environment
values or `RELEASE_SIGNING_REQUIRED=false` for an unsigned local build.

## Troubleshooting

- **Android release fails before building:** one or more of the four signing
  secrets is empty. Add the secret in the repository settings; never commit a
  keystore.
- **Web container job does not run:** the ref is not a `v*.*.*` tag, or the
  `web` gate job failed. The container job depends on the gates.
- **Container tag is missing `latest`:** confirm the tag matches
  `vMAJOR.MINOR.PATCH`; `docker/metadata-action` derives semver tags from it.
- **Contracts workflow fails:** run `make contracts-check` locally. A failure is
  a release blocker; do not bypass it.
- **Rust release fails on a phase 1 tag:** expected for the post-preview track.
  It is not a phase 1 release blocker.
