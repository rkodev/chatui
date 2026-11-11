# UX-10 - Accessibility and dynamic type audit

- Plan: [Android UI/UX Polish Implementation Plan](../android-ux-implementation-plan.md)
- Design review: [android-ux.md](../../android-ux.md)
- Status: Not started
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

- [ ] Every icon has a description or is decorative.
- [ ] Every interactive control has a 48 dp target.
- [ ] 200% font scale reflows without clipping.
- [ ] Contrast passes in light, dark, and dynamic color.
- [ ] TalkBack order is logical on all primary flows.
- [ ] Focus returns to the trigger after sheets/dialogs.
- [ ] The haptics setting is reachable and announced.
- [ ] Streaming live regions announce only start/complete/error.
- [ ] The accessibility note is recorded.

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

## Handoff notes

- Record the TalkBack order per flow.
- List every accessibility fix and any remaining limitation.
- Note the device/emulator used for the manual pass.
