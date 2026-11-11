# UX-00 - Preflight, decisions, and baseline capture

- Plan: [Android UI/UX Polish Implementation Plan](../android-ux-implementation-plan.md)
- Design review: [android-ux.md](../../android-ux.md)
- Status: Complete
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

- [x] Every decision in the deliverables list is recorded with a chosen option.
- [x] The baseline note records test status, goldens, APK sizes, and the
      text-assertion inventory.
- [x] The text-glyph and token-bypass inventory is complete with file paths.
- [x] No production code is changed by this task.

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
| Compact navigation | Sidebar-only; no bottom bar | Preserves the M3-00.2 decision and avoids re-litigating navigation. The drawer remains the compact entry point, and the sidebar header gains a prominent New chat action. | 2026-11-11 |
| Dynamic color default | Off; opt-in via a setting | Keeps the brand palette consistent by default while offering Material You to users who want it. Status colors stay fixed. | 2026-11-11 |
| Haptics default | On, with a setting to disable | Adds tactile confirmation on send/copy/save/delete without forcing it on users who dislike haptics. | 2026-11-11 |
| Font strategy | System font first | Avoids bundling a font and APK growth; the new type ramp uses weights, sizes, and tracking to achieve the Apple-like hierarchy. A custom font can be revisited later. | 2026-11-11 |
| Primitives location | Keep in `core:designsystem` | Avoids a new Gradle module and keeps the design system as the single shared UI dependency. | 2026-11-11 |

## Baseline note

| Item | Value | Method |
| --- | --- | --- |
| Test status | Pass. `make android-lint` and `make android-test` both `BUILD SUCCESSFUL`. | `make android-lint && make android-test` |
| Golden files | 44 files: 11 screens × phone/tablet × light/dark. Screens: chat_empty, chat_error, chat_streaming, conversation_list, data_settings, expanded_shell, model_picker, onboarding, provider_detail, provider_list, settings_root. | `find android/app/src/test/screenshots -type f` |
| Cold start | Not measured; no device/emulator attached. | Deferred to UX-11. |
| Debug APK size | 18,734,358 bytes (~17.9 MiB) | `ls -l android/app/build/outputs/apk/debug/app-debug.apk` |
| Release APK size | 2,894,958 bytes (~2.8 MiB), unsigned | `ls -l android/app/build/outputs/apk/release/app-release-unsigned.apk` |
| Release AAB size | 7,043,129 bytes (~6.7 MiB) | `ls -l android/app/build/outputs/bundle/release/app-release.aab` |

### Text-glyph icon inventory

| File | Line | Glyph | Replacement |
| --- | --- | --- | --- |
| `android/app/src/main/java/dev/chatui/shell/ChatUiApp.kt` | 308 | `☰` | `Icons.Rounded.Menu` |
| `android/app/src/main/java/dev/chatui/shell/ChatUiApp.kt` | 322 | `⌨` | `Icons.Rounded.Keyboard` |
| `android/feature/conversations/.../ConversationListScreen.kt` | 488 | `⋯` | `Icons.Rounded.MoreHoriz` |

### Token-bypass inventory

178 matches of `padding(24.dp)`, `padding(8.dp)`, or
`MaterialTheme.typography` in `android/feature` and `android/app`. Highest
concentrations:

| File | Matches |
| --- | --- |
| `feature/settings/SettingsScreens.kt` | 32 |
| `feature/settings/DataSettingsScreen.kt` | 27 |
| `feature/providers/ProviderListScreen.kt` | 17 |
| `feature/providers/ModelPickerSheet.kt` | 15 |
| `feature/onboarding/OnboardingScreen.kt` | 15 |
| `feature/chat/ChatMessageItem.kt` | 11 |
| `feature/providers/ConversationSettingsSheet.kt` | 10 |
| `feature/chat/ChatScreen.kt` | 8 |
| `feature/conversations/ConversationListScreen.kt` | 7 |
| `feature/providers/ProviderDetailScreen.kt` | 6 |
| `feature/chat/ChatComposer.kt` | 5 |

### Text-assertion inventory

103 matches of `onNodeWithText`, `assertTextEquals`, or `hasText` across 17
files. The highest concentrations are `ChatMarkdownTest.kt` (26),
`ProviderListScreenTest.kt` (9), `ChatMessageItemTest.kt` (9),
`OnboardingScreenTest.kt` (8), `ConversationListScreenTest.kt` (8), and
`ChatCodeBlockTest.kt` (8). These tests must be updated deliberately when
visible copy or text buttons change; test tags and accessible names must be
preserved.

## Handoff notes

- UX-01 must update `DesignSystemTest` in the same change as the token retune;
  it asserts exact ARGB, dp, and sp values.
- UX-01 must add the icons dependency before any screen task replaces glyphs.
- UX-02 through UX-07 must preserve test tags and accessible names; update only
  text assertions that read visible copy.
- UX-12 must regenerate all 44 goldens and review the diffs.
- Cold start is unmeasured; UX-11 owns the first real measurement.

## Completion record

- Status: Complete on 2026-11-11.
- Decisions recorded: sidebar-only compact navigation, dynamic color opt-in,
  haptics on by default, system font, primitives in `core:designsystem`.
- Baseline captured: lint and unit tests pass; 44 goldens; debug APK
  18,734,358 bytes; release APK 2,894,958 bytes; AAB 7,043,129 bytes.
- No production code was changed.
