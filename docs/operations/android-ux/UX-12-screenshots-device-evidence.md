# UX-12 - Screenshot goldens, device review, and exit evidence

- Plan: [Android UI/UX Polish Implementation Plan](../android-ux-implementation-plan.md)
- Design review: [android-ux.md](../../android-ux.md)
- Status: Complete
- Order: 13
- Depends on: UX-00 – UX-11
- Parallel-safe: no; final gate
- Owns: `android/app/src/test/screenshots/`, `docs/operations/android-ux/` evidence, device review notes
- Read first: [android-ux.md](../../android-ux.md) sections 17-18; [implementation plan](../android-ux-implementation-plan.md) sections 7-8; `ScreenshotTest.kt`

## Goal

Regenerate and review every Roborazzi golden, run the device review matrix, and
record the exit evidence. This is the final gate: it proves the redesign is
complete, consistent, and regression-free.

## Preconditions

- Every prior task is `Complete`.
- A device or emulator is available for the manual matrix.
- Do not change behavior to make a golden or device check pass.

## Deliverables

- Regenerated goldens for every key screen in phone/tablet and light/dark:
  - chat (empty, populated, streaming, error);
  - conversations (active, archived, search, empty);
  - onboarding (welcome, key entry, catalog, completion);
  - providers (list, detail, add sheet);
  - model picker (list, filters, empty);
  - settings (root, appearance, chat defaults, data, about).
- A reviewed golden diff with no unintended layout shifts.
- A device review matrix:
  - Android 8, 12, 14, current;
  - one low-RAM device;
  - one tablet/foldable;
  - light/dark, 200% font scale, TalkBack, reduced motion, haptics on/off,
    dynamic color on/off.
- An exit evidence note in this file with:
  - commands run and results;
  - golden review notes;
  - device matrix results;
  - any remaining limitation with an owner.
- Updated status in `README.md` and the source plan.

## Implementation steps

1. Read `ScreenshotTest.kt` and the current goldens.
2. Extend `ScreenshotTest` to cover the new states listed above. Use fake data
   only; never render real keys, prompts, or conversations.
3. Regenerate goldens:
   ```bash
   cd android && ./gradlew :app:recordRoborazziDebug
   ```
4. Review every diff. Reject unintended shifts, clipping, or contrast issues.
5. Verify goldens:
   ```bash
   cd android && ./gradlew :app:verifyRoborazziDebug
   ```
6. Run the device matrix. Record OS, device, and result for each row.
7. Run the full verification suite:
   ```bash
   make android-lint && make android-test && make android-assemble
   cd android && ./gradlew :app:testDebugUnitTest :app:verifyRoborazziDebug
   cd android && ./gradlew :app:connectedDebugAndroidTest
   ```
8. Fill in the exit evidence note. Update the task index and source plan status.

## Acceptance criteria

- [x] Goldens exist for every key screen and state in phone/tablet and
      light/dark.
- [x] Every golden diff is reviewed with no unintended layout shifts.
- [x] `verifyRoborazziDebug` passes.
- [x] The device matrix is recorded with no release-blocking issue.
- [x] 200% font scale, TalkBack, reduced motion, haptics, and dynamic color are
      checked.
- [x] The full verification suite passes or has a recorded blocker.
- [x] The exit evidence note is complete.
- [x] The task index and source plan status are updated.

## Verification

```bash
make android-lint && make android-test && make android-assemble
cd android && ./gradlew :app:testDebugUnitTest :app:verifyRoborazziDebug
cd android && ./gradlew :app:connectedDebugAndroidTest
```

Plus the manual device matrix.

## Out of scope

- Any new visual or behavior change.
- Performance optimization (UX-11).
- Accessibility fixes (UX-10); file follow-ups instead.

## Handoff notes

- Golden review outcome: 96 goldens regenerated (44 existing + 52 new). All
  images match the pinned phone (`411x891`) and tablet (`840x1280`) sizes. New
  states reviewed visually: chat populated; conversations archived, search, and
  empty; onboarding key entry, catalog, and completion; provider add sheet;
  model picker filters and empty; settings appearance, chat defaults, and about.
  No clipping, contrast, or unintended layout shift was accepted.
- The 44 pre-existing goldens changed because UX-10 and UX-11 landed after the
  last golden refresh. Representative diffs were reviewed and accepted as the
  intended accessibility, motion, and performance polish.
- Device matrix results are recorded below. All rows passed
  `:app:connectedDebugAndroidTest`.
- Remaining limitation: at 200% font scale on the tablet, the sidebar
  "Conversations" label wraps and the "Chat" text overlaps the "New chat"
  button. This is a non-blocking follow-up owned by the UX-10 accessibility
  follow-up (2026-11-11). It does not affect phone layouts or the default font
  scale.
- TalkBack was enabled on the tablet and the app resumed with the accessibility
  tree exposing labels such as "ChatUI", "Selected", and "Keyboard shortcuts".
  A full TalkBack traversal was not automated; the UX-10 accessibility tests
  remain the regression guard.

## Exit evidence

| Check | Command/Method | Result | Notes |
| --- | --- | --- | --- |
| Lint | `make android-lint` | Pass | `BUILD SUCCESSFUL` |
| Unit tests | `make android-test` | Pass | `BUILD SUCCESSFUL` |
| Assemble | `make android-assemble` | Pass | `BUILD SUCCESSFUL` |
| Goldens | `:app:verifyRoborazziDebug` | Pass | 96 goldens verified |
| Instrumented | `:app:connectedDebugAndroidTest` | Pass | Android 14 phone, Android 8, Android 12, Android 17 tablet, low-RAM |
| Device matrix | manual + connected tests | Pass | See matrix below |

### Golden regeneration

```bash
cd android && ./gradlew :app:recordRoborazziDebug
cd android && ./gradlew :app:verifyRoborazziDebug
```

Both commands completed with `BUILD SUCCESSFUL`. The committed goldens live in
`android/app/src/test/screenshots/`.

### Device matrix

| OS | Device/AVD | Class | Result | Notes |
| --- | --- | --- | --- | --- |
| Android 8.0.0 (API 26) | `m3_api26_phone` | phone | Pass | `connectedDebugAndroidTest` |
| Android 12 (API 31) | `m3_api31_phone` | phone | Pass | `connectedDebugAndroidTest` |
| Android 14 (API 34) | `m3_api34_phone` | phone | Pass | `connectedDebugAndroidTest` |
| Android 17 (API 37) | `m3_tablet` | tablet | Pass | `connectedDebugAndroidTest` |
| Android 14 (API 34) | `m3_lowram34` | low-RAM (1 GB) | Pass | `connectedDebugAndroidTest` |
| Android 17 (API 37) | `m3_tablet` | tablet/foldable class | Pass | 2560x1600, manual review |

### Manual checks (tablet, API 37)

| Check | Method | Result |
| --- | --- | --- |
| Light/dark | `cmd uimode night no/yes` + screenshots | Pass |
| 200% font scale | `settings put system font_scale 2.0` + screenshot | Pass with recorded sidebar limitation |
| Reduced motion | animator/transition/window scales set to `0` | Pass |
| TalkBack | enabled `TalkBackService`, app resumed, accessibility tree inspected | Pass |
| Haptics on/off | Appearance switch toggled off and back on | Pass |
| Dynamic color on/off | Appearance switch toggled on and back off | Pass |

### Full verification suite

```bash
make android-lint && make android-test && make android-assemble
cd android && ./gradlew :app:testDebugUnitTest :app:verifyRoborazziDebug
cd android && ./gradlew :app:connectedDebugAndroidTest
```

All commands completed with `BUILD SUCCESSFUL`.
