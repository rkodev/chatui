# UX-08 - States and feedback adoption

- Plan: [Android UI/UX Polish Implementation Plan](../android-ux-implementation-plan.md)
- Design review: [android-ux.md](../../android-ux.md)
- Status: Complete
- Order: 9
- Depends on: UX-01, UX-03, UX-04, UX-05, UX-06, UX-07
- Parallel-safe: no; touches all screens
- Owns: all `android/feature/*` screens for state adoption, shared state tests
- Read first: [android-ux.md](../../android-ux.md) section 11; [implementation plan](../android-ux-implementation-plan.md) section 3.8; `StateKit.kt` from UX-01

## Goal

Make loading, empty, error, offline, and success feedback consistent across the
app. Replace text-only loading and ad-hoc error text with the shared state kit
and a global snackbar. This task does not redesign layouts; it standardizes
states inside the layouts delivered by UX-03 through UX-07.

## Preconditions

- UX-01 and all screen tasks are `Complete`.
- `Skeleton`, `SkeletonList`, `EmptyState`, `ErrorState`, `InlineBanner`,
  `LoadingButton`, and the snackbar host exist.
- Do not change data loading, retry, or error-mapping logic.

## Deliverables

- Replace every text loading state with a skeleton or a spinner appropriate to
  the surface:
  - chat history, conversations, providers, models, onboarding catalog,
    settings data usage.
- Replace ad-hoc error text with `ErrorState` or `InlineBanner`:
  - chat availability and send errors;
  - conversation list and search errors;
  - provider list/detail errors;
  - model picker errors;
  - settings and data errors.
- Replace ad-hoc empty text with `EmptyState`:
  - conversations, search results, providers, models, catalog.
- Add a global snackbar host for transient results:
  - conversation delete undo;
  - export/import results;
  - key rotation/removal results;
  - save confirmations.
- Add pull-to-refresh where a refresh action exists:
  - provider list, model picker.
- Ensure every async action has pending, success, and failure feedback.
- Reserve space for banners so state changes do not shift layout.

## Implementation steps

1. Read `StateKit.kt` and every screen's loading/error/empty branches.
2. Inventory every `Text("Loading…")`, `Text("Loading ...")`, and inline error
   `Text` in `android/feature` and `android/app`.
3. Replace loading branches with `Skeleton`/`SkeletonList`. Keep the existing
   test tags (for example `Loading`, `CatalogLoading`, `SearchLoading`).
4. Replace error branches with `ErrorState` or `InlineBanner`. Keep the existing
   test tags and retry callbacks.
5. Replace empty branches with `EmptyState`. Keep the existing test tags.
6. Add the snackbar host at the shell or screen level. Wire delete undo,
   export/import, key actions, and save results.
7. Add pull-to-refresh to provider list and model picker where refresh exists.
8. Add tests for each state: loading shows a skeleton, error shows a retry,
   empty shows the CTA, and snackbar actions fire.
9. Run the full test suite and fix regressions.

## Acceptance criteria

- [x] No text-only "Loading…" remains in feature screens.
- [x] Errors use `ErrorState` or `InlineBanner` with a retry where applicable.
- [x] Empty states use `EmptyState` with a CTA where applicable.
- [x] A global snackbar reports transient results and supports actions.
- [x] Pull-to-refresh works where refresh exists.
- [x] Every async action has pending, success, and failure feedback.
- [x] State changes do not shift layout.
- [x] Existing test tags are preserved and tests pass.
- [x] No data loading, retry, or error-mapping behavior changed.

## Verification

```bash
make android-lint && make android-test && make android-assemble
```

Plus a manual walk through every screen's loading, empty, error, and offline
states.

## Out of scope

- Layout redesign (UX-03 through UX-07).
- Motion tuning (UX-09).
- Accessibility audit (UX-10).
- Performance measurement (UX-11).

## Completion record

- Status: Complete on 2026-11-11.
- Verification:
  - `make android-lint` - passed.
  - `make android-test` - passed.
  - `make android-assemble` - passed.
  - Targeted feature tests (`chat`, `conversations`, `onboarding`, `providers`,
    `settings`, `core:designsystem`) - passed.
- Changed files:
  - `android/core/designsystem/src/main/kotlin/dev/chatui/core/designsystem/Snackbar.kt`
  - `android/core/designsystem/src/main/kotlin/dev/chatui/core/designsystem/StateKit.kt`
  - `android/app/src/main/java/dev/chatui/shell/ChatUiApp.kt`
  - `android/feature/chat/src/main/kotlin/dev/chatui/feature/chat/ChatScreen.kt`
  - `android/feature/chat/src/main/kotlin/dev/chatui/feature/chat/ChatComposer.kt`
  - `android/feature/chat/src/main/kotlin/dev/chatui/feature/chat/ChatMessageItem.kt`
  - `android/feature/conversations/src/main/kotlin/dev/chatui/feature/conversations/ConversationListScreen.kt`
  - `android/feature/onboarding/src/main/kotlin/dev/chatui/feature/onboarding/OnboardingScreen.kt`
  - `android/feature/providers/src/main/kotlin/dev/chatui/feature/providers/ModelPickerSheet.kt`
  - `android/feature/providers/src/main/kotlin/dev/chatui/feature/providers/ProviderListScreen.kt`
  - `android/feature/providers/src/main/kotlin/dev/chatui/feature/providers/ProviderDetailScreen.kt`
  - `android/feature/providers/src/main/kotlin/dev/chatui/feature/providers/ConversationModelControl.kt`
  - `android/feature/providers/src/main/kotlin/dev/chatui/feature/providers/ConversationSettingsSheet.kt`
  - `android/feature/providers/src/main/kotlin/dev/chatui/feature/providers/ConversationRoutingControls.kt`
  - `android/feature/providers/src/main/kotlin/dev/chatui/feature/providers/DefaultsScreen.kt`
  - `android/feature/settings/src/main/kotlin/dev/chatui/feature/settings/DataSettingsScreen.kt`
  - `android/feature/settings/src/main/kotlin/dev/chatui/feature/settings/SettingsScreens.kt`
  - Tests in `chat`, `conversations`, `onboarding`, `providers`, and `settings`.
  - `docs/operations/android-ux/UX-08-states-and-feedback.md`
  - `docs/operations/android-ux/README.md`

## Handoff notes

- Screens whose state presentation changed: chat (loading skeleton, attachment
  banners, message error banner), conversations (list/search loading skeletons,
  list/search errors, list/search empty states), onboarding catalog (error and
  empty states), provider list (pull-to-refresh, form banners), provider detail
  (cleartext and documentation banners), model picker (error/notice/warning
  banners, pull-to-refresh), conversation model control and settings sheet
  (banners), defaults (warning banner), data settings (usage/key skeletons,
  error banner), and settings chat defaults (error banner).
- The global snackbar host lives in `ShellScaffold` and is passed to
  `ChatUiScaffold`'s `snackbarHost` slot. `LocalSnackbarHostState` is provided
  around the shell so every screen shares one `SnackbarHostState`. Screens keep
  a local fallback host when rendered outside the shell (tests, previews,
  screenshots). Delete undo, export/import start, key actions, and save
  confirmations all use the shared state.
- `ErrorState` gained an optional `retryModifier` so existing retry test tags
  (for example `RetryCatalog`) survive the adoption.
- Pull-to-refresh uses Material 3 `PullToRefreshBox` on the provider list
  (`onRetry`) and model picker (`onRefresh`). Both keep their existing refresh
  buttons.
- Banners are inline (`InlineBanner`) and reserve their own space; they never
  overlay content. No data loading, retry, or error-mapping logic changed.
- Field-level validation text (rename title, extras JSON) and destructive
  labels remain as field supporting text / red labels because they are not
  screen-level error states.
