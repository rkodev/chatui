# UX-06 - Providers and model picker

- Plan: [Android UI/UX Polish Implementation Plan](../android-ux-implementation-plan.md)
- Design review: [android-ux.md](../../android-ux.md)
- Status: Not started
- Order: 7
- Depends on: UX-01
- Parallel-safe: yes, with UX-02 and UX-07
- Owns: `android/feature/providers/`, provider tests
- Read first: [android-ux.md](../../android-ux.md) section 9; [implementation plan](../android-ux-implementation-plan.md) section 3.6; `ProviderListScreen.kt`, `ProviderDetailScreen.kt`, `ModelPickerSheet.kt`, `ConversationSettingsSheet.kt`

## Goal

Turn provider management and model selection into grouped, hierarchical, and
scannable surfaces. Preserve provider creation, key rotation/removal, model
refresh, pin/hide, and selection behavior.

## Preconditions

- UX-01 is `Complete`.
- Do not change `ProviderManagementService`, `ModelService`, validation, or
  secret handling.
- Preserve every `ProviderListTestTags`, `ProviderDetailTestTags`,
  `ModelPickerTestTags`, and `ConversationSettingsTestTags` value.

## Deliverables

### Provider list

- Group into "Connected" and "Available" sections.
- Provider rows: avatar/initial, name, status dot, key/usage subtitle, chevron.
- Remove the "Manage" text button; the whole row opens the detail.
- Move "Add provider" into a FAB or top-right `+` that opens a bottom sheet with
  preset cards and a "Custom" option.
- Move preset/custom forms into dedicated sheets with grouped fields, section
  headers, and a sticky primary action.

### Provider detail

- Header card: avatar, name, base URL, enabled switch.
- Grouped sections: "Key & usage", "Models", "Advanced".
- Usage/limit as a progress bar with a label.
- Key rotation/removal in a destructive section with a confirmation sheet.
- Docs link as a row with an external-link icon.

### Model picker

- Full-height sheet with a pinned search field, segmented filters
  (All / Free / Pinned), and grouped rows.
- Model row: name, provider, context/price chips, free badge, and a checkmark
  for the selected model.
- Pin/hide via swipe or a trailing menu, not three text buttons per row.
- Details move into an expandable section or a secondary sheet.
- Skeleton list while loading and a friendly empty state.

## Implementation steps

1. Read the provider screens, view models, services, and tests.
2. Refactor `ProviderListScreen` into grouped sections. Keep
   `ProviderListTestTags.provider(id)` and `providerOption(id)`.
3. Add the add-provider sheet. Keep `AddProvider`, `PresetForm`, `CustomForm`,
   and all field tags. The sheet may host the existing form composables.
4. Refactor `ProviderDetailScreen` into a header card and grouped sections. Keep
   every `ProviderDetailTestTags` value.
5. Add the usage progress bar and the destructive key section. Keep
   `RotateButton`, `RemoveButton`, `ConfirmRemove`, and `NewKeyField`.
6. Refactor `ModelPickerSheet`/`ModelPickerContent` with filters and compact
   rows. Keep `ModelPickerTestTags` values.
7. Add the model details disclosure. Keep `details(id)`.
8. Add skeletons and empty states.
9. Update tests for the new structure. Preserve tags and accessible names.

## Acceptance criteria

- [ ] Provider list is grouped with avatar rows and no "Manage" button.
- [ ] Add-provider uses a sheet with preset/custom options.
- [ ] Provider detail uses a header card and grouped sections.
- [ ] Usage/limit is shown as a progress bar.
- [ ] Key rotation/removal is in a destructive section with confirmation.
- [ ] Model picker has filters, compact rows, and a details disclosure.
- [ ] Pin/hide no longer uses three text buttons per row.
- [ ] Skeletons and empty states are present.
- [ ] All existing provider/model test tags are preserved and tests pass.
- [ ] No provider, key, model, or selection behavior changed.

## Verification

```bash
make android-lint && make android-test && make android-assemble
cd android && ./gradlew :feature:providers:testDebugUnitTest
```

## Out of scope

- Shell/sidebar redesign (UX-02).
- Settings screens (UX-07).
- Global state kit adoption (UX-08).
- Motion tuning (UX-09).

## Handoff notes

- Record how the add-provider sheet hosts the existing forms.
- Note the model filter behavior and the details disclosure.
- List any test that had to change and why.
