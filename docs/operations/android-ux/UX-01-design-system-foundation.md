# UX-01 - Design-system foundation

- Plan: [Android UI/UX Polish Implementation Plan](../android-ux-implementation-plan.md)
- Design review: [android-ux.md](../../android-ux.md)
- Status: Not started
- Order: 2
- Depends on: UX-00
- Parallel-safe: no; blocks every screen task
- Owns: `android/core/designsystem/`, `android/core/model/Settings.kt` (Android settings only), `android/core/datastore/settings/`, `android/gradle/libs.versions.toml`, `android/app/src/main/res/`, design-system tests
- Read first: [android-ux.md](../../android-ux.md) sections 4, 11, 12, 15, 16; [implementation plan](../android-ux-implementation-plan.md) sections 3.1 and 4.1; `android/core/designsystem/*.kt`; `android/core/datastore/settings/*.kt`

## Goal

Upgrade the shared design system so every screen can compose polished
primitives instead of raw Material widgets. This task adds tokens, icons,
motion, haptics, and reusable components. It does not redesign screens.

## Preconditions

- UX-00 is `Complete` and records the decisions for dynamic color, haptics,
  font strategy, and primitives location.
- Do not change streaming, storage, security, or navigation behavior.
- Do not add a new Gradle module unless UX-00 explicitly chose one.

## Deliverables

### Dependencies

- Add `androidx.compose.material:material-icons-extended` to
  `android/gradle/libs.versions.toml` and to `core:designsystem` and the app
  module. Use the Compose BOM; do not hardcode a version in a module build file.
- Add `androidx.compose.animation:animation` (not only `animation-core`) to
  `core:designsystem` for `AnimatedVisibility`, `Crossfade`, and
  `animateContentSize`.

### Tokens

- `Color.kt`: add defaulted fields to `ChatUiColors`:
  `bgGrouped`, `bgInput`, `separator`, `accentSoft`, `onAccentSoft`, `scrim`,
  `skeletonBase`, `skeletonHighlight`. Retune `LightColors` and `DarkColors` to
  the palette in the design review section 4.1. Keep every existing field.
- `Dimens.kt`: add defaulted fields:
  `screenPadding` (20.dp), `sectionGap` (28.dp), `rowMinHeight` (52.dp),
  `cardPadding` (16.dp), `bubblePaddingH` (14.dp), `bubblePaddingV` (10.dp),
  `radiusXs` (8.dp), `radiusXl` (28.dp), `bubbleRadius` (20.dp),
  `bubbleTailRadius` (4.dp), `elevation1` (1.dp), `elevation2` (3.dp),
  `elevation3` (8.dp). Keep every existing field.
- `Typography.kt`: add styles `largeTitle`, `title1`, `title2`, `title3`,
  `headline`, `body`, `callout`, `subhead`, `footnote`, `caption` using the ramp
  in the design review section 4.2. Keep `bodyStyle`, `codeStyle`,
  `headingStyle1..3`, and `chatStyle()` working.
- `Motion.kt`: add `springSnappy`, `springGentle`, `fadeThroughMillis` (180),
  `sharedAxisMillis` (220), `emphasizedMillis` (300). Keep the existing
  `fastMillis`, `normalMillis`, `standardEasing`, and `reducedMotion` contract.

### New design-system files

Create these under `android/core/designsystem/src/main/kotlin/dev/chatui/core/designsystem/`:

- `Haptics.kt`: `@Composable fun rememberHaptics(): Haptics` with
  `tick()`, `confirm()`, `reject()`, `longPress()`. Use
  `LocalHapticFeedback` and `HapticFeedbackType`. No-op when the setting is off.
- `SoftShadow.kt`: `fun Modifier.softShadow(elevation: Dp, shape: Shape): Modifier`
  using `graphicsLayer` with low-alpha ambient shadow.
- `MotionSpecs.kt`: named `spring`/`tween` specs and a
  `@Composable fun <T> animateContent(...)` helper that snaps when
  `LocalReducedMotion.current` is true.
- `ChatUiScaffold.kt`: `@Composable fun ChatUiScaffold(title, subtitle, actions,
  snackbarHost, content)` with a large-title bar, translucent surface, and
  scroll-linked collapse. Expose a `ScrollState`/`LazyListState` parameter.
- `GroupedList.kt`: `GroupedList`, `GroupedSection`, `SettingsRow` with icon,
  label, value, chevron, and optional trailing slot.
- `Buttons.kt`: `PrimaryButton`, `SecondaryButton`, `IconAction`,
  `CircleIconButton`, `LoadingButton`.
- `StateKit.kt`: `Skeleton`, `SkeletonList`, `EmptyState`, `ErrorState`,
  `InlineBanner`.
- `SegmentedControl.kt`, `Stepper.kt`, `LabeledSlider.kt`.
- `BrandMark.kt`: a vector/Compose brand mark used by splash and About.

### Settings

- Add `hapticFeedback: Boolean = true` and `dynamicColor: Boolean = false` to
  `AndroidSettings` in `core/datastore/settings/AndroidSettings.kt`. Do **not**
  add them to the shared `core/model/Settings.kt`; that model is used by
  export/import and must not change.
- Add keys, read/write, and setters in `SettingsDataStore.kt`:
  `setHapticFeedback`, `setDynamicColor`.
- Add tests in `SettingsDataStoreTest.kt` for defaults, persistence, and
  round-trip.

### Theme

- `Theme.kt`: add `dynamicColor: Boolean = false` and `hapticsEnabled: Boolean =
  true` parameters to `ChatUiTheme`. When `dynamicColor` is true and API >= 31,
  derive the accent roles from `dynamicLightColorScheme`/`dynamicDarkColorScheme`
  while keeping status colors fixed. Provide `LocalHaptics`.
- Add a `LocalHaptics` composition local.

### Resources

- Add `android/app/src/main/res/values-night/themes.xml` with a dark parent and
  splash background.
- Replace `ic_splash.xml` with the brand mark. Keep the file name so the
  manifest does not change.

### Tests

- Update `DesignSystemTest.kt` for the retuned values and new tokens.
- Add tests for `Haptics` (no-op when disabled), `MotionSpecs` (reduced motion
  snaps), `GroupedList`/`SettingsRow`, `StateKit`, and `SegmentedControl`.
- Add a contrast test that asserts body text meets 4.5:1 in light and dark.

## Implementation steps

1. Read every file under `android/core/designsystem/` and
   `android/core/datastore/settings/`.
2. Add the dependencies to the version catalog and module build files.
3. Extend the token data classes with defaulted fields. Do not remove or rename
   existing fields.
4. Retune `LightColors`/`DarkColors` and add the new roles.
5. Add the typography ramp and motion specs.
6. Create the new design-system files listed above. Keep each component small
   and stateless; hoist state to the caller.
7. Add the Android settings fields, DataStore keys, setters, and tests.
8. Wire `ChatUiTheme` to dynamic color and haptics.
9. Add the dark theme resources and brand mark.
10. Update `DesignSystemTest` and add the new tests.
11. Run the full Android test suite and fix only design-system failures. If a
    feature test fails because a token value changed, update the assertion in
    the same change and note it in the handoff.

## Acceptance criteria

- [ ] Icons dependency is available and builds.
- [ ] `ChatUiColors`, `Dimens`, `Typography`, and `Motion` expose the new tokens
      with defaults; no existing field is removed.
- [ ] `Haptics`, `SoftShadow`, `MotionSpecs`, `ChatUiScaffold`, `GroupedList`,
      `Buttons`, `StateKit`, `SegmentedControl`, `Stepper`, `LabeledSlider`, and
      `BrandMark` exist and have tests.
- [ ] `AndroidSettings` has `hapticFeedback` and `dynamicColor`; DataStore
      persists them; tests pass.
- [ ] `ChatUiTheme` supports dynamic color and haptics.
- [ ] Dark theme resources and the brand mark exist.
- [ ] `DesignSystemTest` is updated and passes.
- [ ] A contrast test passes for both themes.
- [ ] No screen is redesigned in this task.

## Verification

```bash
make android-lint && make android-test && make android-assemble
cd android && ./gradlew :core:designsystem:testDebugUnitTest
```

## Out of scope

- Screen redesigns (UX-02 through UX-07).
- Motion/haptics tuning on screens (UX-09).
- Accessibility audit (UX-10).
- Performance measurement (UX-11).
- Golden regeneration (UX-12).

## Handoff notes

- List every test that had to be updated for the token retune.
- Record the final token values and any deviation from the design review.
- Note whether dynamic color is opt-in and how it is toggled.
