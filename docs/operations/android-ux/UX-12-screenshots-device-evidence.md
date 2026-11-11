# UX-12 - Screenshot goldens, device review, and exit evidence

- Plan: [Android UI/UX Polish Implementation Plan](../android-ux-implementation-plan.md)
- Design review: [android-ux.md](../../android-ux.md)
- Status: Not started
- Order: 13
- Depends on: UX-00 through UX-11
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

- [ ] Goldens exist for every key screen and state in phone/tablet and
      light/dark.
- [ ] Every golden diff is reviewed with no unintended layout shifts.
- [ ] `verifyRoborazziDebug` passes.
- [ ] The device matrix is recorded with no release-blocking issue.
- [ ] 200% font scale, TalkBack, reduced motion, haptics, and dynamic color are
      checked.
- [ ] The full verification suite passes or has a recorded blocker.
- [ ] The exit evidence note is complete.
- [ ] The task index and source plan status are updated.

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

- Record the golden review outcome and any accepted visual deviation.
- Record the device matrix results.
- List any remaining limitation with an owner and date.

## Exit evidence

| Check | Command/Method | Result | Notes |
| --- | --- | --- | --- |
| Lint | `make android-lint` | _TBD_ | _TBD_ |
| Unit tests | `make android-test` | _TBD_ | _TBD_ |
| Assemble | `make android-assemble` | _TBD_ | _TBD_ |
| Goldens | `:app:verifyRoborazziDebug` | _TBD_ | _TBD_ |
| Instrumented | `:app:connectedDebugAndroidTest` | _TBD_ | _TBD_ |
| Device matrix | manual | _TBD_ | _TBD_ |
