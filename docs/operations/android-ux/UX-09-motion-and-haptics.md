# UX-09 - Motion and haptics tuning

- Plan: [Android UI/UX Polish Implementation Plan](../android-ux-implementation-plan.md)
- Design review: [android-ux.md](../../android-ux.md)
- Status: Not started
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

- [ ] The motion table is applied consistently.
- [ ] Lists animate insert/remove.
- [ ] The send button morphs and presses with a spring.
- [ ] Haptics fire on send/copy/save/delete/selection and respect the setting.
- [ ] Reduced motion snaps every animation to the end state.
- [ ] No motion causes visible jank during streaming or scrolling.
- [ ] Motion and haptics tests pass.

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

## Handoff notes

- Record any motion that was simplified for performance.
- Note the haptic points and the setting behavior.
- List any test that had to change and why.
