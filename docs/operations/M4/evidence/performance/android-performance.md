# Android performance evidence

- Date: 2026-11-10
- Host: `ronald-Lenovo-Legion-S7-15IMH5`, Ubuntu 26.04.1 LTS, x86_64,
  16 logical CPUs / 30 GiB RAM
- JDK: Temurin 21.0.2
- Device: `m3_api34_phone` AVD, API 34, `sdk_gphone64_x86_64` (emulator)
- Build variants: debug APK and unsigned release APK/AAB built with
  `RELEASE_SIGNING_REQUIRED=false`. The release APK was signed with the local
  debug keystore only for installation and cold-start measurement; no signing
  material is recorded.

## Artifact sizes

| Artifact | Bytes | Size | Target | Result |
| --- | --- | --- | --- | --- |
| Debug APK (`app-debug.apk`) | 18,734,354 | 17.87 MiB | < 20 MB | Pass |
| Release APK unsigned (`app-release-unsigned.apk`) | 2,894,958 | 2.76 MiB | < 15 MB | Pass |
| Release AAB (`app-release.aab`) | 7,043,131 | 6.72 MiB | n/a | Recorded |

Commands:

```bash
make android-assemble
cd android && RELEASE_SIGNING_REQUIRED=false ./gradlew :app:assembleRelease :app:bundleRelease
```

## Cold start

Method: install the release APK, `adb shell am force-stop dev.chatui`, then
`adb shell am start -W -n dev.chatui/.MainActivity` five times. The release APK
was signed with the local debug keystore only so it could be installed; the
build itself is the release variant with the baseline profile.

| Run | `TotalTime` (ms) | `WaitTime` (ms) |
| --- | --- | --- |
| 1 | 904 | 910 |
| 2 | 812 | 821 |
| 3 | 1065 | 1112 |
| 4 | 1035 | 1064 |
| 5 | 1068 | 1082 |

Median `TotalTime`: **1035 ms**. Target: under 1.5 s. Result: **Pass** on the
recorded emulator. This is an emulator, not a physical mid-tier phone; the
number is a relative budget check, not a physical-device guarantee.

Debug-build comparison on the same emulator: 2233-3830 ms `TotalTime` across
five runs. Debug builds are not representative of release startup.

## Baseline profile

- `android/app/src/release/generated/baselineProfiles/baseline-prof.txt` and
  `startup-prof.txt` are checked in.
- `android/app/build.gradle.kts` applies `androidx.baselineprofile` and adds
  `baselineProfile(project(":baselineprofile"))` plus
  `androidx.profileinstaller`.
- The signed release APK contains `assets/dexopt/baseline.prof` and
  `assets/dexopt/baseline.profm` (verified with `unzip -l`).

## Message list and streaming

- `android/feature/chat/src/main/kotlin/dev/chatui/feature/chat/ChatMessageList.kt`
  uses `LazyColumn` with `key = { message -> message.id }` and
  `contentType = { message -> message.role.name }`; the older-page control and
  streaming row also have stable keys and content types.
- The active stream is a separate `item(key = "streaming-${stream.messageId}",
  contentType = "streaming")`, so token updates do not recompose persisted
  history rows.
- `ChatMessageListTest` covers the stable-key and streaming-row behavior; the
  full `make android-test` suite passes.

## Image decode sizing and Coil cache

- `ChatMessageItem.kt` builds the Coil request with
  `.size(boundedWidth, boundedHeight)` from the measured display bounds and a
  cache key that includes the file path, modification time, and target size.
- `ChatMessageItemTest` asserts the request size is `Size(1080, 960)` for the
  test bounds.
- Coil's default `ImageLoader` disk cache remains enabled; the request also sets
  an explicit memory cache key so resized requests do not reuse a differently
  sized bitmap.
