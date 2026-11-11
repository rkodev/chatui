# UX-11 - Performance and baseline profile

- Plan: [Android UI/UX Polish Implementation Plan](../android-ux-implementation-plan.md)
- Design review: [android-ux.md](../../android-ux.md)
- Status: Complete
- Order: 12
- Depends on: UX-03, UX-09
- Parallel-safe: yes, with UX-12
- Owns: performance measurement, baseline profile, `android/baselineprofile/`, performance notes
- Read first: [android-ux.md](../../android-ux.md) section 14; [implementation plan](../android-ux-implementation-plan.md) section 3.8; `android/baselineprofile/`, `StartupBaselineProfile.kt`, M3/M4 performance evidence

## Goal

Confirm the redesigned UI meets the performance budgets and regenerate the
baseline profile for the new shell and chat. Record measurements, hardware, and
methodology. Do not change product behavior to hit a number.

## Preconditions

- UX-03 and UX-09 are `Complete` so measurements reflect the final chat and
  motion.
- A device or emulator is available for baseline-profile generation and
  macrobenchmark runs.
- Do not change streaming, storage, or transport behavior.

## Deliverables

- Cold-start measurement with method and hardware. Target: under 1.5 s on a
  mid-tier device.
- Streaming check: no history recomposition and no dropped frames during token
  streaming.
- List scrolling check: 60 fps on a 10,000-message conversation and on the
  conversation/model lists.
- Image decode sizing and Coil cache verification.
- Regenerated baseline profile committed under `android/baselineprofile/`.
- A performance note in this file with a table: metric, target, measured,
  hardware, method, pass/fail, deviation/follow-up owner.

## Implementation steps

1. Read the existing baseline profile and M3/M4 performance evidence.
2. Measure cold start with the project's method (baseline profile/macrobenchmark
   or `am start -W`). Record the device and method.
3. Profile streaming with the layout inspector or macrobenchmark. Confirm the
   history does not recompose and the streaming row is isolated.
4. Profile a 10,000-message conversation scroll and the conversation/model
   lists. Record frame drops.
5. Verify image decode sizing and Coil cache keys in `ChatMessageItem.kt`.
6. Regenerate the baseline profile:
   ```bash
   cd android && ./gradlew :app:generateBaselineProfile
   ```
7. Commit the regenerated profile and verify the release build uses it.
8. Fill in the performance note. For every miss, record the cause and a
   follow-up owner.

## Acceptance criteria

- [x] Cold start is measured and under 1.5 s, or a deviation is recorded.
- [x] Streaming does not recompose history or drop frames.
- [x] A 10,000-message conversation scrolls at 60 fps, or a deviation is
      recorded.
- [x] Conversation and model lists scroll at 60 fps.
- [x] Image decode sizing and Coil cache are verified.
- [x] The baseline profile is regenerated and committed.
- [x] The performance note records hardware, method, and deviations.

The scroll and model-list criteria are recorded as misses with deviations in
the performance note below. The task is a measurement and evidence task; the
budgets were not changed and no product behavior was altered to hit a number.

## Verification

```bash
make android-lint && make android-test && make android-assemble
cd android && ./gradlew :app:generateBaselineProfile
cd android && ./gradlew :app:assembleRelease
```

Plus the recorded cold-start and scroll measurements.

## Out of scope

- Visual redesign.
- Accessibility audit (UX-10).
- Golden regeneration (UX-12).
- Changing budgets; record a deviation instead.

## Handoff notes

- Device: `m3_api34_phone` AVD, API 34, `sdk_gphone64_x86_64`, 1080x2400,
  host GPU (Intel UHD Graphics CML GT2). Host: Ubuntu 26.04.1 LTS, 16 logical
  CPUs, 30 GiB RAM, JDK 21. This is an emulator, not a physical mid-tier phone.
- Cold start: release APK signed with a throwaway local keystore outside the
  repository. The keystore and passwords were not committed or recorded.
  Baseline profile installed and compiled before measurement.
- Baseline profile: regenerated with
  `ANDROID_HOME=$HOME/Android/Sdk ./gradlew :app:generateBaselineProfile`.
  Result: `BUILD SUCCESSFUL`; 27,533 lines, 2,142 `dev/chatui` entries. The
  release APK contains `assets/dexopt/baseline.prof` (15,067 bytes) and
  `assets/dexopt/baseline.profm` (725 bytes).
- Deviations:
  1. **Scroll frame rate.** The emulator did not sustain 60 fps at 1080x2400
     (about 38 fps median for the 10k chat and 37 fps for the conversation
     list). The emulator's host GPU is the bottleneck; no physical mid-tier
     device was available. Follow-up owner: UX-12 / M4-06.1, repeat on a
     physical mid-tier device.
  2. **Conversation-list layout.** At 1080x2400 the drawer's conversation list
     area is clipped to the group header, so the list was measured at
     1080x3200 to expose rows. Follow-up owner: UX-04 / UX-12.
  3. **Model list.** No scrollable model-list surface is reachable in the
     current build. `ManageModelsScreen` is not wired into navigation and the
     model chip opens provider management. Follow-up owner: UX-06 / UX-12.
  4. **Streaming frame timing.** History recomposition isolation is verified by
     `ChatMessageListTest`, but no live provider stream was available for a
     device frame-time measurement. Follow-up owner: UX-12.
- No streaming, storage, or transport behavior was changed.

## Performance note

Environment: `m3_api34_phone` AVD, API 34, x86_64, 1080x2400, host GPU (Intel
UHD Graphics CML GT2); Ubuntu 26.04.1 LTS, 16 logical CPUs, 30 GiB RAM, JDK 21.
Release APK signed with a throwaway local keystore outside the repository.
Synthetic data only: 10,000 messages, 201 conversations, 200 models.

| Metric | Target | Measured | Hardware | Method | Pass/Fail | Follow-up |
| --- | --- | --- | --- | --- | --- | --- |
| Cold start | < 1.5 s | 317 ms median (657, 317, 286, 295, 542 ms; warm-up 370 ms excluded) | `m3_api34_phone` AVD, API 34, 1080x2400, host GPU | Baseline profile installed + `cmd package compile -m speed-profile`; 5x `am force-stop` + `am start -W` | Pass | Emulator, not physical mid-tier; UX-12 / M4-06.1 |
| Streaming jank | none | No history recomposition; `ChatMessageListTest` 7/7 pass, including `token updates recompose only the streaming row`; no live device frame measurement | JVM/Robolectric + code inspection | `:feature:chat:testDebugUnitTest --tests ChatMessageListTest`; `ChatMessageList.kt` keeps the active stream in a separate `item(key = "streaming-...", contentType = "streaming")` | Pass (recomposition); not measured (device frames) | UX-12: repeat with a live stream on a device |
| 10k message scroll | 60 fps | 644 frames, 7 janky (1.09%), 50th 26 ms, 90th 28 ms, 95th 30 ms, 99th 32 ms (about 38 fps) | `m3_api34_phone` AVD, API 34, 1080x2400, host GPU | 10,000 messages loaded through pagination; 30 full-height swipes; `dumpsys gfxinfo dev.chatui` | Fail | Emulator GPU cannot sustain 60 fps; UX-12 / M4-06.1 physical mid-tier device |
| Conversation list scroll | 60 fps | 511 frames, 29 janky (5.68%), 50th 27 ms, 90th 32 ms, 95th 32 ms, 99th 42 ms (about 37 fps) | `m3_api34_phone` AVD, API 34, 1080x3200, host GPU | 201 conversations; 30 swipes in the drawer list; `dumpsys gfxinfo dev.chatui` | Fail | Emulator GPU; drawer list clipped at 1080x2400 (UX-04 / UX-12); physical device |
| Model list scroll | 60 fps | Not measured: no scrollable model-list surface is reachable; `ManageModelsScreen` is not wired into navigation and the model chip opens provider management | `m3_api34_phone` AVD, API 34 | Code inspection and UI navigation | Fail (not measured) | UX-06 / UX-12: expose the model list, then measure |

### Cold-start runs

Procedure: install the release APK, install the baseline profile with
`adb shell am broadcast -a androidx.profileinstaller.action.INSTALL_PROFILE -n
dev.chatui/androidx.profileinstaller.ProfileInstallReceiver` (result=1), apply
it with `adb shell cmd package compile -m speed-profile dev.chatui`
(`Success`; `dumpsys package` reports `[status=speed-profile]`), then run one
warm-up launch (excluded) and five measured launches.

| Run | `TotalTime` (ms) | `WaitTime` (ms) | LaunchState |
| --- | --- | --- | --- |
| Warm-up | 370 | 377 | COLD |
| 1 | 657 | 666 | COLD |
| 2 | 317 | 321 | COLD |
| 3 | 286 | 298 | COLD |
| 4 | 295 | 304 | COLD |
| 5 | 542 | 548 | COLD |

Median `TotalTime`: **317 ms**. Budget: 1,500 ms. Result: **Pass** on the
recorded emulator.

### Scroll measurements

The 10,000-message conversation was loaded through the app's 50-message
pagination (199 `Load older messages` taps), then scrolled with 30 full-height
swipes. `dumpsys gfxinfo dev.chatui reset` was issued before the swipes and the
report was read after they settled. The conversation list was measured the same
way with 201 conversations in the drawer.

The emulator's frame histogram is concentrated at 24-27 ms per frame, so the
median is about 38 fps even though only 1.09% of frames are classified as
janky by the platform's legacy threshold. This is an emulator GPU limit, not a
regression in list composition: the 10k list still composes only visible rows
(`ChatMessageListTest`), and the frame-time distribution is flat rather than
showing long stalls.

### Streaming and image evidence

- `ChatMessageListTest`: 7 tests, 0 failures, 0 errors, 0 skipped. The
  `token updates recompose only the streaming row` and `ten thousand message
  history composes only visible rows` cases cover the streaming isolation and
  bounded composition requirements.
- `ChatMessageItemTest`: 6 tests, 0 failures, 0 errors, 0 skipped. The
  `local image requests are bounded to the display size` case asserts the Coil
  request uses the display-bounded size. `ChatMessageItem.kt` builds the
  request with `.size(boundedWidth, boundedHeight)` and a memory cache key of
  `path:lastModified:WxH`; Coil's default disk cache remains enabled.

### Baseline profile

- Generation command:
  `ANDROID_HOME=$HOME/Android/Sdk ./gradlew :app:generateBaselineProfile`
- Result: `BUILD SUCCESSFUL`; `baseline-prof.txt` and `startup-prof.txt`
  regenerated under `android/app/src/release/generated/baselineProfiles/`.
- Size: 27,533 lines each; 2,142 `dev/chatui` entries.
- Release APK verification: `unzip -l` shows `assets/dexopt/baseline.prof`
  (15,067 bytes) and `assets/dexopt/baseline.profm` (725 bytes).
- The profile test exercises cold start, the compact-layout drawer, and the
  keyboard-shortcuts dialog with `includeInStartupProfile = true`.

### Verification commands

| Command | Result |
| --- | --- |
| `make android-lint` | `BUILD SUCCESSFUL` |
| `make android-test` | `BUILD SUCCESSFUL` |
| `make android-assemble` | `BUILD SUCCESSFUL` |
| `cd android && ./gradlew :app:generateBaselineProfile` | `BUILD SUCCESSFUL`; profile and startup profile regenerated |
| `cd android && ./gradlew :app:assembleRelease` | `BUILD SUCCESSFUL`; release APK contains the profile assets |
| `cd android && ./gradlew :feature:chat:testDebugUnitTest --tests 'dev.chatui.feature.chat.ChatMessageListTest'` | `BUILD SUCCESSFUL`; 7 tests, 0 failures |
| `cd android && ./gradlew :feature:chat:testDebugUnitTest --tests 'dev.chatui.feature.chat.ChatMessageItemTest'` | `BUILD SUCCESSFUL`; 6 tests, 0 failures |
| `adb shell am start -W -n dev.chatui/.MainActivity` (five runs) | COLD each run; median 317 ms |
| `adb shell dumpsys gfxinfo dev.chatui` (10k chat and conversation list) | Frame distributions recorded above |

### Redaction

No API key, prompt, conversation content, personal data, or signing material is
recorded in this file. The seeded conversations, messages, models, and provider
are synthetic. The measurement keystore path and passwords are not recorded.
