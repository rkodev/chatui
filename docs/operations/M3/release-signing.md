# Release signing and versioning

This document records the release signing and version policy for the Android
app. It lists secret **names only**. Never commit a keystore, password, base64
blob, or any other signing value.

## Signing inputs

Release signing is configured in `android/app/build.gradle.kts` and reads only
environment/CI values:

| Name | Required | Purpose |
| --- | --- | --- |
| `ANDROID_KEYSTORE_PATH` | one of the two keystore inputs | Path to a keystore file available on the build machine. |
| `ANDROID_KEYSTORE_BASE64` | one of the two keystore inputs | Base64-encoded keystore contents, for CI where the file is supplied as a secret. |
| `ANDROID_KEYSTORE_PASSWORD` | yes | Keystore password. |
| `ANDROID_KEY_ALIAS` | yes | Release key alias. |
| `ANDROID_KEY_PASSWORD` | yes | Release key password. |
| `RELEASE_SIGNING_REQUIRED` | no | When `true`, the build fails if any required signing value is missing. |
| `ANDROID_VERSION_CODE` | no | Explicit positive integer `versionCode`; normally set by CI. |
| `ANDROID_VERSION_NAME` | no | Explicit semantic `versionName`; normally set by CI. |

`ANDROID_KEYSTORE_PATH` takes precedence when both keystore inputs are set.

When `ANDROID_KEYSTORE_BASE64` is used, the build decodes it to a temporary
file outside the repository, restricts the file to the current user, and
deletes it after the release package tasks finish (and on JVM exit as a
fallback). The decoded file is never written into the repository.

## Local build behavior

- **Debug builds** keep the default Android debug signing config. No release
  secret is needed for local debug work.
- **Release builds without secrets** remain unsigned. This is the default local
  behavior and produces `app-release-unsigned.apk`.
- **Release builds with `RELEASE_SIGNING_REQUIRED=true`** fail at configuration
  time with an actionable message naming the missing values. A debug key is
  never substituted.
- **Release builds with all signing values present** use the `release` signing
  config.

Typical local commands:

```bash
cd android
./gradlew :app:assembleDebug
RELEASE_SIGNING_REQUIRED=false ./gradlew :app:assembleRelease
```

CI release jobs must set `RELEASE_SIGNING_REQUIRED=true` and provide the four
signing secrets. The CI release workflow itself is owned by M3-06.4.

## Version policy

- `versionCode` is a positive integer and must increase monotonically.
  - `ANDROID_VERSION_CODE` wins when set. CI should set it from a monotonic
    source (for example a release counter or the full-history commit count).
  - Otherwise the build uses `git rev-list --count HEAD`.
  - If git history is unavailable, it falls back to `1`.
  - An invalid `ANDROID_VERSION_CODE` fails the build rather than silently
    producing a wrong version.
- `versionName` follows semantic versioning (`MAJOR.MINOR.PATCH`, optionally
  with a pre-release suffix).
  - `ANDROID_VERSION_NAME` wins when set.
  - Otherwise an exact semantic git tag (with an optional leading `v`) is used.
  - Otherwise the build uses `0.1.0-dev`.

Shallow CI clones report a commit count of `1`, so release CI must either set
`ANDROID_VERSION_CODE` explicitly or fetch full history. Do not rely on a
shallow clone for a monotonic `versionCode`.

## Secret hygiene check

Run this scan before handing off or releasing:

```bash
rg -n --hidden -g '!*.lock' -g '!**/build/**' "keystore|storePassword|keyPassword" .
```

Expected matches are documentation and build-script references only. No
keystore file, password, or base64 value may be committed. `.gitignore` already
excludes `*.jks`, `*.keystore`, `*.p12`, `*.pfx`, `*.key`, `*.pem`, and
`secrets/`.

## CI release workflow

`.github/workflows/android-release.yml` builds and publishes signed release
artifacts. It runs on a semantic version tag (`v*.*.*`) and on manual
`workflow_dispatch`.

- The job checks out full history so the git commit-count `versionCode` stays
  monotonic, then runs `make contracts-check`, `make android-lint`,
  `make android-test`, `make android-screenshot`, and
  `./gradlew :app:assembleRelease :app:bundleRelease`.
- It sets `RELEASE_SIGNING_REQUIRED=true` and reads the four signing secrets
  listed above. A dedicated step fails the job with an actionable message when
  any secret is missing; a debug key is never substituted.
- It uploads the signed APK, signed AAB, `mapping.txt`, and a SHA-256
  `checksums.txt` as workflow artifacts. Screenshot verification output and
  connected-test reports are uploaded when produced.
- On a tag it creates a GitHub Release with `gh release create` and attaches
  only the signed APK, signed AAB, mapping, and checksums.
- Manual runs may set `version_code` and `version_name` inputs. The optional
  `run_connected_tests` input runs `make android-test-ui` on an emulator and
  uploads the reports.

The workflow never publishes to the Play Store and never attaches an unsigned
APK.

## Developer targets

The M3 release commands are available as stable Make targets:

| Target | Command |
| --- | --- |
| `make android-test-ui` | `./gradlew connectedDebugAndroidTest` |
| `make android-screenshot` | `./gradlew :app:verifyRoborazziDebug` |
| `make android-release` | `./gradlew :app:assembleRelease :app:bundleRelease` |
| `make android-baseline-profile` | `./gradlew :baselineprofile:connectedBenchmarkReleaseAndroidTest` |

`make android-release` uses the same env-driven signing as the CI job. Without
secrets it produces an unsigned APK; set `RELEASE_SIGNING_REQUIRED=true` to
fail instead. The baseline-profile target uses the task name produced by the
installed `androidx.baselineprofile` plugin version.

## Current availability

As recorded in [`M3-decisions.md`](M3-decisions.md), CI signing secrets are not
yet provisioned. The configuration and failure behavior above are implemented
and verified with an unsigned local release build. A signed CI release remains
blocked until the repository administrator provisions the four signing secrets
and verifies the release keystore identity.
