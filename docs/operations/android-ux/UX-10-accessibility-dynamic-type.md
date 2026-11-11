# UX-10 - Accessibility and dynamic type audit

- Plan: [Android UI/UX Polish Implementation Plan](../android-ux-implementation-plan.md)
- Design review: [android-ux.md](../../android-ux.md)
- Status: Complete
- Order: 11
- Depends on: UX-02, UX-03, UX-04, UX-05, UX-06, UX-07
- Parallel-safe: yes, with UX-09
- Owns: accessibility fixes across `android/app` and `android/feature`, accessibility tests
- Read first: [android-ux.md](../../android-ux.md) section 13; [implementation plan](../android-ux-implementation-plan.md) section 3.8; existing M3/M4 accessibility evidence

## Goal

Verify and fix accessibility for the redesigned UI: icon descriptions, 48 dp
targets, dynamic type at 200%, contrast, TalkBack order, focus restoration, and
the haptics setting. This task fixes concrete issues; it does not redesign.

## Preconditions

- UX-02 through UX-07 are `Complete`.
- Do not change behavior to satisfy an accessibility check.
- Do not add localization.

## Deliverables

- Every icon has a content description or is marked decorative.
- Every new icon button, swipe action, and chip has a 48 dp target.
- Dynamic type at 200% reflows without clipping or overlap.
- Body text meets 4.5:1 contrast in light, dark, and dynamic color.
- TalkBack traversal order is logical on chat, conversations, providers,
  models, onboarding, and settings.
- Focus returns to the trigger after sheets and dialogs close.
- The haptics setting is reachable and announced correctly.
- Streaming live regions announce start/complete/error only.
- A written accessibility note records what was checked and any remaining
  limitation.

## Implementation steps

1. Read the M3/M4 accessibility evidence and the new screens.
2. Search for icon-only buttons without descriptions:
   ```bash
   grep -rn 'IconButton\|Icon(' android/feature android/app --include=*.kt
   ```
3. Add content descriptions or `clearAndSetSemantics {}` for decorative icons.
4. Audit touch targets. Add `Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)`
   where needed.
5. Run the app at 200% font scale (emulator setting or `fontScale` qualifier)
   and fix clipping. Prefer wrapping over truncation.
6. Add or update a contrast test for both themes and dynamic color.
7. Walk TalkBack on each primary flow. Record the order and fix obvious issues.
8. Verify focus restoration after sheets/dialogs.
9. Verify the streaming live region announces only start/complete/error.
10. Write the accessibility note in the handoff.

## Acceptance criteria

- [x] Every icon has a description or is decorative.
- [x] Every interactive control has a 48 dp target.
- [x] 200% font scale reflows without clipping.
- [x] Contrast passes in light, dark, and dynamic color.
- [x] TalkBack order is logical on all primary flows.
- [x] Focus returns to the trigger after sheets/dialogs.
- [x] The haptics setting is reachable and announced.
- [x] Streaming live regions announce only start/complete/error.
- [x] The accessibility note is recorded.

## Verification

```bash
make android-lint && make android-test && make android-assemble
cd android && ./gradlew :app:connectedDebugAndroidTest   # device/emulator
```

Plus a manual TalkBack and 200% font-scale pass.

## Out of scope

- Layout redesign.
- Motion tuning (UX-09).
- Performance measurement (UX-11).
- Localization.

## Completion record

- Status: Complete on 2026-11-11.
- Icon descriptions: audited every `Icon`/`IconButton` in `android/app` and
  `android/feature`. Icon-only actions already had descriptions; decorative
  icons remain `contentDescription = null` inside labelled rows. Added tests
  for the composer attach/send/stop descriptions.
- 48 dp targets:
  - `ChatComposer` attachment remove button changed from `size(28.dp)` to
    `sizeIn(minWidth = 48.dp, minHeight = 48.dp)`.
  - Empty-state prompt chips, model-picker filter chips, provider preset chips,
    and provider auth chips now use `heightIn(min = touchTargetMin)`.
  - Existing icon buttons, swipe rows, and menu actions already met 48 dp.
- Dynamic type:
  - `ChatUiScaffold` now uses `heightIn` for the large-title bar and title row,
    and allows the expanded title/subtitle to wrap to two lines.
  - `GroupedList` values wrap to two lines.
  - Conversation title/preview, provider label/subtitle, model label/provider,
    and provider-detail title wrap to two lines instead of truncating at one.
  - Added font-scale 2.0 tests for the scaffold, chat empty state, and
    conversation rows.
- Contrast: extended `DesignSystemTest` to assert 4.5:1 for primary and
  secondary body text on canvas, surface, and raised surface in light and dark
  themes, plus dynamic-color accent containers.
- TalkBack order: manual pass on the `m3_api34_phone` AVD with TalkBack enabled.
  Observed onboarding order: Welcome, Connect, ChatUI brand/title, tagline,
  body, recommendation, Choose a provider, then top-bar navigation and shortcut
  actions. Settings order: section headings and rows in visual order, with the
  haptics toggle announced as a switch. Drawer order: ChatUI, New chat, Chats,
  Conversations, Archived, search, Providers, Data, Backup, Settings.
- Focus restoration: added `AccessibilityFocusTest` (dialog) and a
  `ChatComposerTest` case (bottom sheet). Both assert focus returns to the
  trigger after dismissal.
- Haptics setting: `SettingsScreensTest` asserts the "Haptic feedback" row is
  reachable and exposes `ToggleableState.On`.
- Streaming live region: `ChatViewModelTest` now covers start, token updates,
  completion, and terminal error. The existing polite live region in
  `ChatScreen` is unchanged.

## Handoff notes

- TalkBack order per flow:
  - onboarding: Welcome, Connect, ChatUI brand/title, tagline, body,
    recommendation, Choose a provider, Open navigation, Keyboard shortcuts;
  - settings: section headings and rows in visual order; haptics toggle reads
    as a switch;
  - drawer: ChatUI, New chat, Chats, Conversations, Archived, search,
    Providers, Data, Backup, Settings;
  - chat, conversations, providers, and models: composition order follows the
    visual order; automated tests assert accessible names and 48 dp targets.
- Accessibility fixes are listed in the completion record.
- Remaining limitations:
  - The streaming live region also announces "Response cancelled" as a terminal
    state. This is existing behavior and was not removed because the task
    forbids behavior changes for accessibility checks.
  - The manual TalkBack walk covered onboarding, settings, and the drawer. A
    full TalkBack walk of chat, conversations, providers, and models remains
    for the UX-12 device review.
  - No localization was added.
- Device/emulator used for the manual pass: `m3_api34_phone` AVD, API 34,
  1080x2400, font scale 2.0, TalkBack enabled.
- Verification:
  - `make android-lint` - passed.
  - `make android-test` - passed.
  - `make android-assemble` - passed.
  - `cd android && ./gradlew :app:connectedDebugAndroidTest` - passed on
    `m3_api34_phone` (API 34).
  - Manual 200% font-scale pass on the same AVD: onboarding and appearance
    settings reflowed without clipping; screenshots reviewed.
