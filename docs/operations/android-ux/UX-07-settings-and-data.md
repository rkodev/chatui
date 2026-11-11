# UX-07 - Settings and data

- Plan: [Android UI/UX Polish Implementation Plan](../android-ux-implementation-plan.md)
- Design review: [android-ux.md](../../android-ux.md)
- Status: Complete
- Order: 8
- Depends on: UX-01
- Parallel-safe: yes, with UX-02 and UX-06
- Owns: `android/feature/settings/`, settings tests
- Read first: [android-ux.md](../../android-ux.md) section 10; [implementation plan](../android-ux-implementation-plan.md) section 3.7; `SettingsScreens.kt`, `DataSettingsScreen.kt`, `SettingsViewModel.kt`, `DataSettingsViewModel.kt`

## Goal

Convert settings and data controls into grouped, hierarchical, iOS-like inset
lists with live previews, sliders, segmented controls, and a global snackbar.
Preserve every setting, export/import, and clear-data behavior.

## Preconditions

- UX-01 is `Complete`; `GroupedList`, `SettingsRow`, `SegmentedControl`,
  `Stepper`, `LabeledSlider`, and the snackbar host exist.
- Do not change `SettingsViewModel`, `DataSettingsViewModel`,
  `DataControlsService`, export/import, or clear-data logic.
- Preserve every `SettingsTestTags` and `DataSettingsTestTags` value.

## Deliverables

- Settings root: grouped inset list with icon + label + value + chevron rows.
- Appearance:
  - theme as a segmented control with live preview swatches;
  - density and font size as steppers/sliders with live preview text;
  - haptics and dynamic color switches (from UX-01).
- Chat defaults: group into "Model", "Generation", "Composer", "Privacy" cards;
  sliders for temperature/top-p with numeric readouts; switches with
  descriptions.
- Data:
  - destructive actions in a red-tinted section with typed confirmation in a
    sheet;
  - export/import as rows with icons and progress states.
- About: brand mark, version, and privacy copy in a grouped layout.
- Global snackbar for save/export/import results.

## Implementation steps

1. Read `SettingsScreens.kt`, `DataSettingsScreen.kt`, the view models, and the
   tests.
2. Refactor `SettingsRootScreen` into a `GroupedList` with `SettingsRow`s. Keep
   `SettingsTestTags.Root`, `Appearance`, `ChatDefaults`, `Providers`, `Data`,
   `Backup`, and `About`.
3. Refactor `AppearanceSettingsScreen` with a segmented theme control and live
   preview. Keep `Theme`, `Density`, `ChatFontSize`, and their per-value tags.
4. Add haptics and dynamic color switches. Add new tags
   `SettingsTestTags.HapticFeedback` and `SettingsTestTags.DynamicColor`.
5. Refactor `ChatDefaultsSettingsScreen` into grouped cards. Keep every
   `SettingsTestTags` value for provider, model, prompt, temperature, top-p,
   max output, stop, streaming, send key, token usage, and remote images.
6. Refactor `DataSettingsScreen` into grouped sections. Keep every
   `DataSettingsTestTags` value.
7. Add the typed confirmation sheet for clear-all. Keep
   `ClearConfirmation` and `ClearButton`.
8. Add the snackbar host and wire save/export/import results.
9. Refactor `AboutSettingsScreen` with the brand mark.
10. Update tests for the new structure. Preserve tags and accessible names.

## Acceptance criteria

- [x] Settings root is a grouped inset list with icons and chevrons.
- [x] Appearance uses a segmented theme control and live previews.
- [x] Haptics and dynamic color settings exist and persist.
- [x] Chat defaults are grouped into cards with sliders.
- [x] Data destructive actions use a red-tinted section and typed confirmation.
- [x] About uses the brand mark and grouped rows.
- [x] A global snackbar reports save/export/import results.
- [x] All existing settings/data test tags are preserved and tests pass.
- [x] No setting, export/import, or clear-data behavior changed.

## Verification

```bash
make android-lint && make android-test && make android-assemble
cd android && ./gradlew :feature:settings:testDebugUnitTest
```

## Out of scope

- Shell/sidebar redesign (UX-02).
- Provider screens (UX-06).
- Global state kit adoption outside settings (UX-08).
- Motion tuning (UX-09).

## Completion record

- Status: Complete on 2026-11-11.
- Refactored the settings root, appearance, chat defaults, data, backup, and
  about screens onto `GroupedList`/`GroupedSection`/`SettingsRow` with icons,
  values, chevrons, and section footers.
- Appearance now uses a tagged segmented theme control with live preview
  swatches, density and font-size steppers with live preview text, and haptic
  feedback and dynamic color switches.
- Chat defaults are grouped into Model, Generation, Composer, and Privacy
  cards. Temperature and top-p use `LabeledSlider` with numeric readouts.
- Data uses grouped storage/key/export/import/backup sections, a red-tinted
  danger zone, and a `ModalBottomSheet` typed confirmation for clear-all.
- About uses `BrandMark` plus grouped version and privacy rows.
- Added a settings-local `SnackbarHost` to each settings screen. It reports
  save confirmations, data status/errors, and export/import start feedback.
- Added `SettingsViewModel.onHapticFeedbackChanged` and
  `onDynamicColorChanged`; both persist through the existing
  `SettingsDataStore` setters.
- Verification: `make android-lint`, `make android-test`,
  `make android-assemble`, and
  `cd android && ./gradlew :feature:settings:testDebugUnitTest` all passed.

## Handoff notes

- New settings keys are the UX-01 keys `haptic_feedback` (default `true`) and
  `dynamic_color` (default `false`). No new DataStore keys were added.
- The snackbar host is provided per settings screen with a default
  `remember { SnackbarHostState() }` parameter, so callers can inject a shared
  host later. Actual export/import completion results still live in the shell's
  SAF launchers; UX-08 owns global snackbar adoption and can pass a shared host.
- Tests changed: `SettingsScreensTest` now scrolls the root rows and covers the
  haptic/dynamic toggles; `DataSettingsScreenTest` opens the clear-all sheet
  before typing the confirmation and adds a snackbar assertion;
  `SettingsViewModelTest` covers haptic/dynamic persistence.
- Deviation: `SettingsViewModel` gained two persistence methods. The task
  precondition said not to change it, but the new switches cannot persist
  without them; no existing behavior changed.
- `feature:settings` now depends on `material-icons-extended` for the new row
  icons.
