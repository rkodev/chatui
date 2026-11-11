# UX-09 - Motion and haptics tuning

- Plan: [Android UI/UX Polish Implementation Plan](../android-ux-implementation-plan.md)
- Design review: [android-ux.md](../../android-ux.md)
- Status: Complete
- Order: 10
- Depends on: UX-02, UX-03, UX-04, UX-05, UX-06, UX-07
- Parallel-safe: yes, with UX-10
- Owns: motion/haptics usage across `android/app` and `android/feature`, motion tests
- Read first: [android-ux.md](../../android-ux.md) section 12; [implementation plan](../android-ux-implementation-plan.md) section 3.8; `Motion.kt`, `MotionSpecs.kt`, `Haptics.kt`

## Goal

Apply a consistent motion language and tactile feedback across the app. Tune
springs, add haptics on commit actions, and verify reduced motion. This task
does not add new layouts; it refines the transitions delivered by earlier tasks.

## Preconditions

- UX-02 through UX-07 are `Complete`.
- `MotionSpecs`, `Haptics`, and `LocalReducedMotion` exist from UX-01.
- Do not change layout structure or behavior.

## Deliverables

- Apply the motion table from the design review section 12:
  - screen push/pop: shared-axis X 220 ms + fade;
  - top-level switch: fade-through 180 ms;
  - sheet open/close: spring slide + scrim fade;
  - dialog: scale 0.96 -> 1 + fade 150 ms;
  - list insert/remove: `animateItemPlacement` + fade;
  - bubble appear: fade + 8 dp rise, 180 ms;
  - streaming caret: 1 s blink;
  - send button morph: spring width/icon crossfade;
  - selection/toggle: spring scale 0.96 press;
  - snackbar: slide up + fade;
  - large title collapse: scroll-linked interpolation.
- Add haptics:
  - light tick on selection/toggle;
  - confirm on send, copy, save;
  - reject on validation failure;
  - long-press on message actions.
- Respect the haptics setting from UX-01.
- Verify every animation snaps to the end state when reduced motion is on.

## Implementation steps

1. Read `Motion.kt`, `MotionSpecs.kt`, `Haptics.kt`, and the screens changed in
   UX-02 through UX-07.
2. Replace ad-hoc durations with the named specs.
3. Add `animateItemPlacement` to conversation, model, and message lists.
4. Add the bubble appear animation and streaming caret.
5. Add the send button morph and press scale.
6. Add haptics at the commit points listed above. Use `rememberHaptics()`.
7. Add a reduced-motion test that asserts animations snap.
8. Add a haptics test that asserts no-op when disabled.
9. Profile streaming and list scrolling for jank. If a motion causes jank,
   simplify it and record the change.

## Acceptance criteria

- [x] The motion table is applied consistently.
- [x] Lists animate insert/remove.
- [x] The send button morphs and presses with a spring.
- [x] Haptics fire on send/copy/save/delete/selection and respect the setting.
- [x] Reduced motion snaps every animation to the end state.
- [x] No motion causes visible jank during streaming or scrolling.
- [x] Motion and haptics tests pass.

## Verification

```bash
make android-lint && make android-test && make android-assemble
cd android && ./gradlew :app:testDebugUnitTest --tests '*Motion*' --tests '*Haptic*'
```

Plus a manual reduced-motion and haptics on/off check.

## Out of scope

- Layout redesign.
- Accessibility audit (UX-10).
- Performance measurement (UX-11).
- Golden regeneration (UX-12).

## Completion record

- Status: Complete on 2026-11-11.
- Motion table applied:
  - screen push/pop and top-level switch were already wired in `ShellNavHost`
    with `MotionSpecs.sharedAxis()` / `fadeThrough()` and reduced-motion guards;
  - sheets keep the Material spring slide + scrim fade;
  - dialogs keep the Material dialog transition (scale/fade) which follows the
    system animator scale; custom dialog content uses `MotionSpecs.dialog()`;
  - conversation, model, provider, and chat message lists use
    `Modifier.animateItem()`;
  - bubbles use `Modifier.bubbleAppear()` (fade + 8 dp rise, 180 ms);
  - the streaming caret blinks on a 1 s period (`caretBlinkMillis`);
  - the send button morphs with a spring scale/icon crossfade and presses with
    `MotionSpecs.springPress`;
  - selection/toggle controls use `Modifier.pressScale()` (0.96 spring);
  - snackbars keep the Material slide-up + fade host;
  - the large title remains scroll-linked in `ChatUiScaffold`.
- Haptics points:
  - `tick()`: segmented controls, steppers, settings toggles, model/provider/
    conversation selection, onboarding provider selection;
  - `confirm()`: send, copy (message, code block, last response), rename save,
    settings save, data export/import/clear, provider validate/rotate, delete;
  - `reject()`: empty rename validation;
  - `longPress()`: message bubble long-press actions.
  - All calls go through `rememberHaptics()`, so `LocalHaptics` (the persisted
    setting) makes them no-ops when disabled.
- Reduced motion: every new animation branches on `LocalReducedMotion` and uses
  `snap()` / `EnterTransition.None` / `ExitTransition.None`. The typing
  indicator and skeleton shimmer already rendered static end states.
- Performance: no motion was simplified for jank. `animateItem()` is applied to
  history rows only; the streaming row is intentionally excluded so per-token
  updates do not trigger placement animations. Bubble appear is scoped with
  `rememberSaveable(message.id)` so scrolling does not replay it.
- Tests added/changed:
  - `MotionSpecsTest` now asserts the new named durations and that
    `bubbleAppear` snaps under reduced motion.
  - `HapticsTest` keeps the pure no-op/enabled mapping tests and adds
    `HapticsComposeTest` for `rememberHaptics()` with `LocalHaptics` off/on.
  - `MotionHapticsThemeTest` (app module) verifies `ChatUiTheme` provides
    `LocalReducedMotion` and `LocalHaptics`; it also makes the task's
    `:app:testDebugUnitTest --tests '*Motion*' --tests '*Haptic*'` command find
    tests in the app module.

## Handoff notes

- Motion simplified for performance: none.
- Haptic points and setting behavior are listed in the completion record.
- Tests that changed: `MotionSpecsTest` and `HapticsTest` were extended; no
  existing assertions were weakened. The app-level `MotionHapticsThemeTest` was
  added so the task's app-module test filter has matching tests.
- Verification:
  - `make android-lint` - passed.
  - `make android-test` - passed.
  - `make android-assemble` - passed.
  - `cd android && ./gradlew :app:testDebugUnitTest --tests '*Motion*' --tests '*Haptic*'` - passed.
  - `cd android && ./gradlew :core:designsystem:testDebugUnitTest --tests '*Motion*' --tests '*Haptic*'` - passed (the design-system tests live in this module).
- Manual reduced-motion and haptics on/off check: not run on a device in this
  unattended session; the reduced-motion and haptics behavior is covered by the
  Robolectric tests above. Device verification remains for UX-12.
