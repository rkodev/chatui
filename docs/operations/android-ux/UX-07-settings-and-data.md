# UX-07 - Settings and data

- Plan: [Android UI/UX Polish Implementation Plan](../android-ux-implementation-plan.md)
- Design review: [android-ux.md](../../android-ux.md)
- Status: Not started
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

- [ ] Settings root is a grouped inset list with icons and chevrons.
- [ ] Appearance uses a segmented theme control and live previews.
- [ ] Haptics and dynamic color settings exist and persist.
- [ ] Chat defaults are grouped into cards with sliders.
- [ ] Data destructive actions use a red-tinted section and typed confirmation.
- [ ] About uses the brand mark and grouped rows.
- [ ] A global snackbar reports save/export/import results.
- [ ] All existing settings/data test tags are preserved and tests pass.
- [ ] No setting, export/import, or clear-data behavior changed.

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

## Handoff notes

- Record the new settings keys and their defaults.
- Note how the snackbar host is provided.
- List any test that had to change and why.
