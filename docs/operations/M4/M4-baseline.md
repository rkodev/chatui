# M4 baseline preflight

- Milestone: M4
- Recorded by: `M4-00.1`
- Date: 2026-11-10
- Baseline commit: `495e2c0397975edf920da9b06cd840fab2fde017`
- Purpose: measured pre-change baseline so later M4 tasks optimize and verify
  against known numbers instead of guesses.

This task changes no product behavior. All numbers below are from the baseline
commit before any M4 polish, PWA, performance, deployment, or CI work.

## Environment

| Item | Value |
| --- | --- |
| Host | `ronald-Lenovo-Legion-S7-15IMH5` |
| OS | Ubuntu 26.04.1 LTS, Linux 7.0.0-42-generic, x86_64 |
| CPU / RAM | 16 logical CPUs / 30 GiB |
| Node | v24.16.0 |
| pnpm | 11.7.0 |
| Chrome | 157.0.8103.38 |
| JDK | Temurin 21.0.2 |
| Rust | cargo 1.98.1 (not shipping; not measured) |
| Android device | `m3_api34_phone` AVD, API 34, `sdk_gphone64_x86_64` (emulator) |
| Network | No outbound npm registry access during preflight |

## Gate results

| Command | Result |
| --- | --- |
| `make contracts-check` | Pass: `Contract validation passed.` |
| `make web-lint` | Pass: `eslint .` with no findings. |
| `make web-typecheck` | Pass: `tsc -b` with no errors. |
| `make web-test` | Pass: 95 files, 852 tests, 0 failures. |
| `make web-build` | Pass: production build in ~0.4 s. |
| `cd web && pnpm exec playwright test` | Pass: 15 tests, 0 failures (Chromium). |
| `make android-lint` | Pass: `BUILD SUCCESSFUL`. |
| `make android-test` | Pass: 997 tests across modules, 0 failures, 0 errors, 0 skipped. |
| `make android-assemble` | Pass: `BUILD SUCCESSFUL`; debug APK produced. |
| `cd android && RELEASE_SIGNING_REQUIRED=false ./gradlew :app:assembleRelease :app:bundleRelease` | Pass: unsigned release APK and AAB produced. |
| `cd android && ANDROID_HOME=$HOME/Android/Sdk ./gradlew :app:connectedDebugAndroidTest` | Pass: 10 tests, 0 failures, 0 errors, 2 skipped (manual restart/backup phases). |
| Rust gates | Not run: `Rust desktop: not shipping` (DEC-M4-01). |

## Web bundle baseline

Commands:

```bash
du -sh web/dist
find web/dist -name '*.js' -printf '%s\n' | awk '{s+=$1} END {print s}'
gzip -c web/dist/assets/*.js | wc -c
find web/dist -name '*.js' -printf '%s %p\n' | sort -nr | head -20
gzip -c web/dist/assets/index-*.js | wc -c
```

| Metric | Value |
| --- | --- |
| `web/dist` total | 5.0 MiB |
| Total JS raw (all chunks) | 3,789,784 bytes (3.61 MiB) |
| Total JS gzip (concatenated) | 684,355 bytes (668 KiB) |
| Initial entry JS raw (`index-*.js`) | 1,129,833 bytes (1.08 MiB) |
| Initial entry JS gzip | 332,240 bytes (324.5 KiB) |
| CSS raw (`index-*.css`) | 76,504 bytes |

Largest chunks at baseline:

| Bytes | Chunk |
| --- | --- |
| 1,129,833 | `index-BkMr8y59.js` (entry) |
| 796,954 | `cpp-Cts1PyIS.js` (Shiki language) |
| 259,118 | `katex-BhzyR5At.js` |
| 181,067 | `typescript-j_1H8WHN.js` |
| 177,779 | `jsx-D3KKoC5L.js` |
| 175,523 | `tsx-BOvsFOI6.js` |
| 174,814 | `javascript-BzbQS41l.js` |

Baseline initial JS gzip is **324.5 KiB**, above the 250 KiB gzip budget. This
is the pre-optimization number; `M4-03.1` owns code-splitting and re-measurement.
The build also warns that the entry chunk exceeds 500 kB minified.

## Web Lighthouse baseline

**Blocked.** Lighthouse is not installed on the host and the npm registry is
unreachable, so `npx lighthouse` cannot be installed. No Lighthouse score is
recorded rather than a guessed number.

- Owner: `M4-03.1` (performance budgets).
- Action: install Lighthouse or run it in CI, then record mobile
  performance/accessibility for the main chat route against the production
  build. Save JSON/HTML under `docs/operations/M4/evidence/performance/`.

## Web axe baseline

The existing Playwright axe smoke suite passes:

- `web/e2e/accessibility.spec.ts` runs `@axe-core/playwright` with
  `wcag2a`/`wcag2aa` tags on the onboarding route and the chat route.
- Result: 2 tests pass; no `critical` impact violations on either route.
- Limitation: the suite filters to `critical` only and covers two routes.
  `M4-01.1` owns expanding to serious/critical across all main routes.

## Android artifact baseline

Built with `RELEASE_SIGNING_REQUIRED=false` (no signing material available).
Sizes exclude signing and are unsigned release artifacts.

| Artifact | Bytes | Size |
| --- | --- | --- |
| Debug APK (`app-debug.apk`) | 18,734,358 | 17.87 MiB |
| Release APK unsigned (`app-release-unsigned.apk`) | 2,894,962 | 2.76 MiB |
| Release AAB (`app-release.aab`) | 7,042,996 | 6.72 MiB |

Debug APK is under the 20 MB target. Release APK is under the 15 MB target.
`M4-03.1` re-measures after performance work.

## Android cold start baseline

Method: install debug APK, `adb shell am force-stop dev.chatui`, then
`adb shell am start -W -n dev.chatui/.MainActivity` three times.

| Run | `TotalTime` (ms) | `WaitTime` (ms) |
| --- | --- | --- |
| 1 | 1533 | 1537 |
| 2 | 2102 | 2141 |
| 3 | 2452 | 2463 |

This is a **debug build on an emulator**, not a mid-tier physical device. It is
a relative baseline only. `M4-03.1` owns the release-build, physical-device
cold-start budget.

## Compose UI suite baseline

`cd android && ANDROID_HOME=$HOME/Android/Sdk ./gradlew :app:connectedDebugAndroidTest`
on `m3_api34_phone` (API 34): **10 tests, 0 failures, 0 errors, 2 skipped**
(manual restart/backup phases). Roborazzi screenshot goldens were not re-run in
this preflight; `M3-05.3` recorded them as unchanged.

## Rust baseline

`Rust desktop: not shipping` (DEC-M4-01). No Rust binary, installer, cold-start,
or UI-suite measurement is recorded. Rust UI suite status: not run, not
applicable for this release.

## Entry-criteria checklist

| Criterion | Status | Evidence / owner |
| --- | --- | --- |
| M1 exit gate met on web and Android | Pass | `docs/operations/M1/M1-verification.md` (2026-11-04). |
| M2 exit gate met on web and Android | Pass | `docs/operations/M2/M2-verification.md` (2026-11-06). |
| M3 exit gate met for Android release configuration and device evidence | **Fail** | `M3-07.3` (device matrix), `M3-07.4` (carryover closeout), and `M3-07.5` (parity/exit evidence) are `Not started`. Owner: M3 milestone owner. |
| Rust M3.5 exit gate met, if shipping | Pass (not applicable) | Rust is not shipping (DEC-M4-01); M3.5 DEC-01 is `defer`. |
| `contracts/parity.md` current and every critical case has an owner | **Partial / Fail** | The table is current but has no owner/evidence column and critical cases 1-28 are not individually linked. Owner: `M4-06.1`. |

The M3 exit-gate failure is a hard blocker for the M4 release gate. M4
implementation tasks must not start until the M3 milestone owner closes
`M3-07.3`, `M3-07.4`, and `M3-07.5`. The M4 milestone index status is updated
to `Blocked` for this reason.

## Redaction

No API keys, prompts, conversation content, personal data, or signing material
are recorded here. Artifact sizes and test counts only.
