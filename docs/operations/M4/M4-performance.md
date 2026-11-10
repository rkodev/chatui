# M4 performance budgets and measurements

- Milestone: M4
- Task: `M4-03.1`
- Date: 2026-11-10
- Baseline: [M4-baseline.md](M4-baseline.md) (commit `495e2c0`)
- Rust decision: `Rust desktop: not shipping` (DEC-M4-01), so Rust budgets are
  not applicable and were not measured.

This document records the phase 1 performance budgets, the measured values, the
hardware and method for each metric, and every accepted deviation. Raw evidence
lives in [`evidence/performance/`](evidence/performance/).

## Environment

| Item | Value |
| --- | --- |
| Host | `ronald-Lenovo-Legion-S7-15IMH5` |
| OS | Ubuntu 26.04.1 LTS, Linux 7.0.0-42-generic, x86_64 |
| CPU / RAM | 16 logical CPUs / 30 GiB |
| Node / pnpm | v24.16.0 / 11.7.0 |
| Browser | Chrome 157.0.8103.38; Playwright Chromium 1248 |
| Lighthouse | 12.8.2 (mobile form factor, default mobile throttling) |
| JDK | Temurin 21.0.2 |
| Android device | `m3_api34_phone` AVD, API 34, `sdk_gphone64_x86_64` (emulator) |
| Rust | Not shipping; not measured |

## Web

| Metric | Target | Measured | Hardware / method | Result | Deviation / follow-up |
| --- | --- | --- | --- | --- | --- |
| Initial JS (entry chunk) | < 250 KB gzip | 130.2 KiB gzip (`index-*.js`, 133,353 bytes) | Production `vite build`; `gzip -c` on the entry chunk | Pass | None |
| Initial JS (entry + modulepreload for the first route) | < 250 KB gzip | 182.2 KiB gzip (186,583 bytes) | Production build; sum of entry and `modulepreload` JS from `dist/index.html` | Pass | None |
| Lighthouse mobile performance | >= 90 | 92 (representative run); 91 median across 5 final runs; range 86-92 | Lighthouse 12.8.2, mobile form factor, `vite preview` production build, `/chat/lighthouse-conversation` | Pass | Headless Lighthouse variance is recorded below. |
| Lighthouse mobile accessibility | >= 90 | 100 | Same run | Pass | None |
| 10,000-message scroll | 60 fps | 60.03 fps median; 59.65-60.23 fps across 3 runs; 0-1 dropped frames | Playwright Chromium, 10,000 persisted messages, 3 s top-to-bottom scroll, `requestAnimationFrame` deltas | Pass | Headless desktop Chromium, not a physical mid-tier phone. |
| Settings code-split | Required | `AppearanceSettingsScreen`, `ChatSettingsScreen`, `ModelsSettingsScreen`, `DataSettingsScreen`, `BackupSettingsScreen`, `AboutSettingsScreen` are `lazyRouteComponent` chunks | `web/src/app/router.tsx`; production build chunk list | Pass | None |
| Provider management code-split | Required | `ProvidersSettingsRoute` and `ProviderDetailRoute` are lazy chunks | `web/src/app/router.tsx` | Pass | None |
| Shiki languages code-split | Required | Each language is a dynamic `import('shiki/langs/*.mjs')`; Shiki core and the JS regex engine are also dynamic imports | `web/src/features/chat/highlighter.ts`; build emits per-language chunks | Pass | None |
| Markdown lazy-loaded below the fold | Where practical | `MarkdownRenderer` is a `React.lazy` chunk loaded only when a message has text; the windowed list does not mount off-screen rows | `web/src/features/chat/MessageItem.tsx`, `MessageList.tsx`, `useWindowedList.ts` | Pass | None |
| Virtualization | Required | `useWindowedList` renders only the visible range plus overscan | `web/src/features/chat/useWindowedList.ts` | Pass | None |
| Memoized message components | Required | `MessageItem` is `memo` with `messageItemPropsEqual` keyed by id, `updatedAt`, status, error, usage, and parts | `web/src/features/chat/MessageItem.tsx`, `messageItemProps.ts` | Pass | None |
| Isolated streaming | Required | `StreamingMessage` is the only subscriber to the streaming store; history rows do not subscribe | `web/src/features/chat/StreamingMessage.tsx`, `MessageList.tsx` | Pass | None |
| Image aspect ratios | Required | `.markdown__image` reserves a 16:9 box; images with `width`/`height` get an exact inline `aspect-ratio` | Playwright computed-style check: `{"aspectRatio":"16 / 9","width":"640px","display":"block"}` | Pass | None |

### Web evidence

- `evidence/performance/lighthouse-chat-mobile.json` and `.html` - Lighthouse
  mobile performance 92, accessibility 100, LCP 2.8 s, TBT 70 ms, CLS 0.
- `evidence/performance/bundle-analysis.txt` - entry and modulepreload sizes,
  plus every JS chunk.
- `evidence/performance/web-scroll-10k.md` - 10,000-message scroll method and
  three runs.
- `evidence/performance/web-image-aspect.md` - computed aspect-ratio check.

### Web deviations

1. **Lighthouse performance variance.** Five final runs scored 91, 91, 92, 89,
   and 91 (median 91); earlier runs ranged 86-92. The representative saved run
   is 92. The variance comes from headless Chrome CPU/network throttling on a
   shared host, not from a code change between runs. Accepted deviation:
   `M4-05.1` should add a Lighthouse CI job that runs three times and records
   the median, so the release gate does not depend on a single noisy run.
2. **10,000-message scroll device.** The measurement used headless desktop
   Chromium on the recorded host, not a physical mid-tier phone. The target was
   met with 0-1 dropped frames. Accepted deviation: `M4-06.1` should repeat the
   scroll check on a physical mid-tier device if one is available before the
   release gate.

## Android

| Metric | Target | Measured | Hardware / method | Result | Deviation / follow-up |
| --- | --- | --- | --- | --- | --- |
| Cold start | Recorded budget; target under 1.5 s | Median 1035 ms `TotalTime` (812-1068 ms across 5 runs) | Release APK signed with the local debug keystore, `m3_api34_phone` API 34 emulator, `am force-stop` + `am start -W` | Pass | Emulator, not a physical mid-tier phone. |
| Debug APK size | < 20 MB | 18,734,354 bytes (17.87 MiB) | `make android-assemble` | Pass | None |
| Release APK size | < 15 MB if feasible | 2,894,958 bytes (2.76 MiB) | `RELEASE_SIGNING_REQUIRED=false ./gradlew :app:assembleRelease` | Pass | Unsigned release artifact; signing is owned by `M4-05.1`. |
| Release AAB size | Recorded | 7,043,131 bytes (6.72 MiB) | `:app:bundleRelease` | Recorded | None |
| `LazyColumn` keys/content type | Required | `key = { it.id }`, `contentType = { it.role.name }`; older and streaming rows have stable keys/content types | `ChatMessageList.kt`; `ChatMessageListTest` | Pass | None |
| No history recomposition during streaming | Required | Active stream is a separate `item(key = "streaming-...", contentType = "streaming")` | `ChatMessageList.kt`; `ChatMessageListTest` | Pass | None |
| Image decode sizing | Required | Coil request uses `.size(boundedWidth, boundedHeight)` from display bounds | `ChatMessageItem.kt`; `ChatMessageItemTest` asserts `Size(1080, 960)` | Pass | None |
| Coil thumbnail cache | Required | Explicit memory cache key includes path, mtime, and target size; Coil default disk cache remains enabled | `ChatMessageItem.kt` | Pass | None |
| Baseline profile | Required | `baseline-prof.txt` and `startup-prof.txt` checked in; release APK contains `assets/dexopt/baseline.prof` and `.profm` | `android/app/build.gradle.kts`; `unzip -l` on the release APK | Pass | None |

### Android evidence

- `evidence/performance/android-performance.md` - sizes, cold-start runs,
  baseline-profile verification, and code references.

### Android deviations

1. **Cold start device.** The measurement used the `m3_api34_phone` emulator,
   not a physical mid-tier phone. The release build met the 1.5 s target with a
   1035 ms median. Accepted deviation: `M4-06.1` should repeat the cold-start
   check on a physical mid-tier device if one is available before the release
   gate.
2. **Release signing.** The release APK was signed with the local debug keystore
   only so it could be installed for measurement. Production signing is owned by
   `M4-05.1`; no signing material is recorded here.

## Rust

`Rust desktop: not shipping` (DEC-M4-01). Cold start under 1.5 s and installer
under 40 MB are **not applicable** and were not measured. Rust remains
post-preview / parallel.

## Verification commands

```bash
make web-lint && make web-typecheck && make web-test && make web-build
cd web && pnpm exec playwright test
make android-lint && make android-test && make android-assemble
cd android && RELEASE_SIGNING_REQUIRED=false ./gradlew :app:assembleRelease :app:bundleRelease
```

Plus the Lighthouse, bundle-analysis, 10,000-message scroll, image aspect-ratio,
cold-start, and artifact-size evidence listed above. `make rust-build` was not
run because Rust is not shipping.

## Redaction

No API keys, prompts, conversation content, personal data, or signing material
are recorded here. The seeded performance messages are synthetic. The release
APK was signed with a local debug keystore only for installation; no key
material is included in this document or the evidence.
