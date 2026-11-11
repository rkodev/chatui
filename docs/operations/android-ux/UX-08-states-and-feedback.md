# UX-08 - States and feedback adoption

- Plan: [Android UI/UX Polish Implementation Plan](../android-ux-implementation-plan.md)
- Design review: [android-ux.md](../../android-ux.md)
- Status: Not started
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

- [ ] No text-only "Loading…" remains in feature screens.
- [ ] Errors use `ErrorState` or `InlineBanner` with a retry where applicable.
- [ ] Empty states use `EmptyState` with a CTA where applicable.
- [ ] A global snackbar reports transient results and supports actions.
- [ ] Pull-to-refresh works where refresh exists.
- [ ] Every async action has pending, success, and failure feedback.
- [ ] State changes do not shift layout.
- [ ] Existing test tags are preserved and tests pass.
- [ ] No data loading, retry, or error-mapping behavior changed.

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

## Handoff notes

- List every screen whose state presentation changed.
- Record the snackbar host location and how actions are wired.
- Note any state that could not be standardized and why.
