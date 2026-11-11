# UX-00 - Preflight, decisions, and baseline capture

- Plan: [Android UI/UX Polish Implementation Plan](../android-ux-implementation-plan.md)
- Design review: [android-ux.md](../../android-ux.md)
- Status: Not started
- Order: 1
- Depends on: none
- Parallel-safe: no; blocks all implementation
- Owns: `docs/operations/android-ux/` decision log and baseline notes; no production code
- Read first: [android-ux.md](../../android-ux.md) sections 1-4 and 17; [implementation plan](../android-ux-implementation-plan.md) sections 1-3 and 9

## Goal

Freeze the design direction, record the decisions that affect every later task,
and capture the current visual and performance baseline so later changes can be
compared against it. This task produces no production code.

## Preconditions

- The design review and implementation plan are approved as the source of truth.
- The Android project builds and tests pass on the current branch.

## Deliverables

- A decision log in this file (section "Decision log") covering:
  - compact bottom navigation vs sidebar-only;
  - dynamic color default (recommended: off, opt-in);
  - haptics default (recommended: on, with a setting);
  - custom font vs system font (recommended: system font first);
  - primitives location: `core:designsystem` vs a new `core:ui` module
    (recommended: keep in `core:designsystem`).
- A baseline note in this file recording:
  - current Roborazzi golden file list and location;
  - current cold-start measurement method and value (or "not measured");
  - current debug/release APK sizes (or "not measured");
  - the list of tests that assert on visible text labels or exact token values.
- A short inventory of every text-glyph icon and every screen that bypasses
  design tokens, with file paths and line numbers.

## Implementation steps

1. Read `android-ux.md` and the implementation plan end to end.
2. Run the current test suite and record the result:
   ```bash
   make android-lint && make android-test
   ```
3. List the current goldens:
   ```bash
   find android/app/src/test/screenshots -type f | sort
   ```
4. Record the current APK sizes if a build exists:
   ```bash
   find android/app/build/outputs -name '*.apk' -o -name '*.aab' | sort
   ```
5. Search for text-glyph icons and record each hit:
   ```bash
   grep -rn 'Text("☰"\|Text("⌨"\|Text("⋯"\|Text("…"' android --include=*.kt
   ```
6. Search for hardcoded spacing and typography that bypasses tokens:
   ```bash
   grep -rn 'padding(24.dp)\|padding(8.dp)\|MaterialTheme.typography' android/feature android/app --include=*.kt
   ```
7. Search for tests that assert on visible text:
   ```bash
   grep -rn 'onNodeWithText\|assertTextEquals\|hasText' android --include=*.kt
   ```
8. Fill in the decision log and baseline note below. Do not start UX-01 until
   every decision is recorded.

## Acceptance criteria

- [ ] Every decision in the deliverables list is recorded with a chosen option.
- [ ] The baseline note records test status, goldens, APK sizes, and the
      text-assertion inventory.
- [ ] The text-glyph and token-bypass inventory is complete with file paths.
- [ ] No production code is changed by this task.

## Verification

```bash
make android-lint && make android-test
find android/app/src/test/screenshots -type f | sort
```

## Out of scope

- Any token, component, or screen change.
- Performance optimization or golden regeneration.

## Decision log

| Decision | Chosen option | Rationale | Date |
| --- | --- | --- | --- |
| Compact navigation | _TBD_ | _TBD_ | _TBD_ |
| Dynamic color default | _TBD_ | _TBD_ | _TBD_ |
| Haptics default | _TBD_ | _TBD_ | _TBD_ |
| Font strategy | _TBD_ | _TBD_ | _TBD_ |
| Primitives location | _TBD_ | _TBD_ | _TBD_ |

## Baseline note

| Item | Value | Method |
| --- | --- | --- |
| Test status | _TBD_ | `make android-lint && make android-test` |
| Golden files | _TBD_ | `find android/app/src/test/screenshots -type f` |
| Cold start | _TBD_ | _TBD_ |
| Debug APK size | _TBD_ | _TBD_ |
| Release APK size | _TBD_ | _TBD_ |

## Handoff notes

- Record anything UX-01 must know before changing tokens, especially tests that
  will fail and must be updated in the same change.
