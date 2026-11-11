# UX-05 - Onboarding

- Plan: [Android UI/UX Polish Implementation Plan](../android-ux-implementation-plan.md)
- Design review: [android-ux.md](../../android-ux.md)
- Status: Complete
- Order: 6
- Depends on: UX-01
- Parallel-safe: yes, with UX-03 and UX-04
- Owns: `android/feature/onboarding/`, onboarding tests
- Read first: [android-ux.md](../../android-ux.md) section 8; [implementation plan](../android-ux-implementation-plan.md) section 3.5; `OnboardingScreen.kt`, `OnboardingViewModel.kt`, `OnboardingUiState.kt`, `OnboardingService.kt`

## Goal

Make first-run onboarding feel intentional and delightful: a hero welcome, a
step indicator, provider cards, a polished secure key entry, a validation
success state, and a completion screen. Preserve validation, catalog, and
provider-selection behavior.

## Preconditions

- UX-01 is `Complete`.
- Do not change `OnboardingViewModel`, `OnboardingService`, validation rules, or
  catalog loading.
- Preserve every `OnboardingTestTags` value.

## Deliverables

- Hero welcome step:
  - brand mark, short value proposition, and a "Get started" primary button;
  - entrance animation (fade + rise).
- Step indicator: two steps with animated progress.
- Provider picker:
  - large selectable cards with provider logos/initials, a "Recommended" badge,
    and a checkmark;
  - animate selection.
- Key entry:
  - focused secure card with a show/hide eye icon, paste button, inline
    validation, and a "Where do I find this?" help link;
  - keep `SecureScreen` around the key-entry step.
- Validation:
  - the Connect button morphs to a success check, then auto-advances with a
    fade;
  - keep the error card with Details.
- Catalog:
  - skeleton list while loading;
  - compact model list with free badges;
  - collapsed by default with a "Show all models" disclosure.
- Completion state:
  - "You're all set" with a tasteful check animation and a "Start chatting"
    button.

## Implementation steps

1. Read `OnboardingScreen.kt`, `OnboardingViewModel.kt`, `OnboardingUiState.kt`,
   `OnboardingService.kt`, and the onboarding tests.
2. Add a hero section for `OnboardingStep.WELCOME` using `BrandMark` and the
   new typography. Keep `OnboardingTestTags.Welcome`.
3. Add a step indicator composable. Keep the step logic in the ViewModel.
4. Replace the radio rows with provider cards. Keep
   `OnboardingTestTags.providerOption(id)` and the `selectable` semantics.
5. Redesign the key-entry form. Add show/hide and paste. Keep
   `OnboardingTestTags.ApiKeyField`, `LabelField`, `ConnectButton`, and
   `BackButton`.
6. Add the validation success morph. Keep `TestingStatus`, `ConnectedStatus`,
   `Notice`, `ErrorCard`, `ErrorDetailsToggle`, and `ErrorDetails`.
7. Add the catalog skeleton and disclosure. Keep `Catalog`, `CatalogLoading`,
   `CatalogError`, `CatalogEmpty`, `CatalogList`, and `RetryCatalog`.
8. Add the completion state. Keep `StartChattingButton`.
9. Update tests for the new structure. Preserve tags and accessible names.

## Acceptance criteria

- [x] Welcome step has a hero, value proposition, and primary CTA.
- [x] A two-step indicator is visible and animated.
- [x] Provider selection uses cards with a recommended badge and checkmark.
- [x] Key entry has show/hide, paste, inline validation, and a help link.
- [x] Validation success morphs and auto-advances.
- [x] Catalog uses a skeleton and a collapsed disclosure.
- [x] Completion state exists with a check animation.
- [x] `SecureScreen` still wraps key entry.
- [x] All existing onboarding test tags are preserved and tests pass.
- [x] No validation, catalog, or provider-selection behavior changed.

## Verification

```bash
make android-lint && make android-test && make android-assemble
cd android && ./gradlew :feature:onboarding:testDebugUnitTest
```

## Out of scope

- Shell/app-bar changes (UX-02).
- Chat surface (UX-03).
- Global state kit adoption (UX-08).
- Motion tuning (UX-09).

## Completion record

- Implemented in `OnboardingScreen.kt`, `OnboardingRoute.kt`, and the onboarding
  test suite. `OnboardingViewModel`, `OnboardingService`, validation rules, and
  catalog loading are unchanged.
- Verification:
  - `make android-lint` — passed.
  - `make android-test` — passed.
  - `make android-assemble` — passed.
  - `cd android && ./gradlew :feature:onboarding:testDebugUnitTest` — passed
    (28 tests).

## Handoff notes

- Step indicator: a two-label progress bar driven by `OnboardingStep` from the
  ViewModel. It animates from 50% on `WELCOME` to 100% on `KEY_ENTRY` using the
  shared `MotionSpecs.emphasized` spec and respects reduced motion.
- Copy changes: the welcome CTA is now "Get started" (was "Continue"); the hero
  adds "Your keys. Your device. Your conversations." The existing BYOK and
  "add other providers from Providers after setup" copy is preserved.
- Catalog: collapsed by default to the first three models. A "Show all N
  models" disclosure expands the list and toggles to "Show fewer models". The
  loading state uses the shared `SkeletonList`.
- Validation success: the Connect button morphs to a check + "Connected" and
  the completion card fades in automatically below the form. `OnboardingRoute`
  now navigates on the "Start chatting" tap instead of the ViewModel's
  `Connected` event, so the completion state is visible. The ViewModel still
  emits the event; no ViewModel or service behavior changed.
- Tests changed: the old "sending is disabled" catalog test now asserts the
  completion action is absent before validation. Status and catalog assertions
  scroll to their nodes because the redesigned key-entry card is taller. Added
  tests for the step indicator, provider selection semantics, key-entry
  affordances, catalog skeleton/disclosure, success morph, and completion CTA.
- New test tags added (existing tags preserved): `StepIndicator`,
  `ShowKeyToggle`, `PasteKey`, `HelpLink`, `CatalogDisclosure`, `Completion`.
