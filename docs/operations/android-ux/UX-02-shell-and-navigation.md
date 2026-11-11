# UX-02 - Shell and navigation

- Plan: [Android UI/UX Polish Implementation Plan](../android-ux-implementation-plan.md)
- Design review: [android-ux.md](../../android-ux.md)
- Status: Complete
- Order: 3
- Depends on: UX-01
- Parallel-safe: yes, with UX-06 and UX-07
- Owns: `android/app/src/main/java/dev/chatui/shell/`, `android/app/src/main/java/dev/chatui/MainActivity.kt` (theme wiring only), shell tests
- Read first: [android-ux.md](../../android-ux.md) section 5; [implementation plan](../android-ux-implementation-plan.md) section 3.2; `ChatUiApp.kt`, `AdaptiveShell.kt`, `ShellNavHost.kt`, `ShellDestination.kt`, `BackPolicy.kt`

## Goal

Replace the stock shell with a polished, Apple-like shell: a collapsing
large-title app bar, a redesigned sidebar/drawer, animated navigation
transitions, and correct insets. Keep every existing destination and the
predictive-back ordering.

## Preconditions

- UX-01 is `Complete`; `ChatUiScaffold`, `GroupedList`, `SettingsRow`, icons,
  and motion helpers exist.
- UX-00 recorded the compact-navigation decision. Follow it exactly.
- Do not change navigation routes, deep-link handling, or intent routing.

## Deliverables

- `ChatUiScaffold` used by the shell with:
  - a large title that collapses to an inline title on scroll;
  - a translucent bar with a hairline separator that fades in on scroll;
  - leading menu icon on compact widths, no leading icon on expanded widths;
  - trailing icon actions (share, settings) instead of text buttons.
- Chat title and model moved into the app bar. Remove the duplicate in-content
  header row in `ChatScreen.kt` (the `ChatScreenTestTags.Header` row).
- Redesigned `ShellSidebar`:
  - grouped sections ("Chats", "Library", "Settings");
  - icon + label rows with a selected accent-soft pill and 12 dp corners;
  - a prominent "New chat" button in the header;
  - keep `ShellTestTags.navigationItem(route)` and `ShellTestTags.Sidebar`.
- Redesigned drawer:
  - rounded trailing corners, header with brand mark and New chat;
  - animated scrim;
  - keep `ShellTestTags.Drawer`.
- Navigation transitions in `ShellNavHost`:
  - shared-axis X for lateral navigation;
  - fade-through for top-level destination switches;
  - scale + fade for chat open.
- Predictive-back ordering preserved: drawer -> sheet -> dialog -> keyboard ->
  navigate. Re-run `BackPolicyTest` and `ShellBackNavigationTest`.
- Composer safe-area treatment: the chat composer sits above the IME with a
  bottom gradient; no double padding.
- If UX-00 chose a compact bottom bar, add it with Chats / Providers / Settings
  and keep the sidebar for expanded widths. If not, document the decision.

## Implementation steps

1. Read `ChatUiApp.kt`, `AdaptiveShell.kt`, `ShellNavHost.kt`,
   `ShellDestination.kt`, `BackPolicy.kt`, and the shell tests.
2. Replace the `Scaffold`/`TopAppBar` in `ShellScaffold` with `ChatUiScaffold`.
   Pass the current destination title and subtitle. Wire the scroll state from
   the active destination if available; otherwise use a hoisted `ScrollState`.
3. Replace the `☰` and `⌨` text glyphs with `Icons.Rounded.Menu` and
   `Icons.Rounded.Keyboard`. Keep `ShellTestTags.MenuButton` and
   `ShellTestTags.ShortcutHelpButton` and their content descriptions.
4. Redesign `ShellSidebar` using `GroupedList`/`SettingsRow`. Keep the existing
   `ConversationRoute` as the conversation list; do not duplicate it.
5. Add the New chat action to the sidebar header. Reuse the existing
   `onNewChat` logic from `ShellScaffold`.
6. Redesign the drawer content in `AdaptiveShell` with rounded corners and a
   header. Keep the 840 dp breakpoint and `ShellTestTags.ExpandedLayout` /
   `ShellTestTags.CompactLayout`.
7. Add `enterTransition`/`exitTransition`/`popEnterTransition`/
   `popExitTransition` to the `NavHost` in `ShellNavHost.kt`. Use the motion
   specs from UX-01 and snap when reduced motion is on.
8. Move the chat title/model into the app bar. Remove the header row from
   `ChatContent` in `ChatScreen.kt`; keep the model chip as a tappable subtitle
   that opens the model picker.
9. Add the composer bottom gradient and verify IME insets in `ChatScreen.kt`.
10. Update shell tests for the new structure. Preserve test tags. Add tests for
    the large-title collapse, sidebar grouping, and navigation transitions
    (assert the destination is reached; do not assert animation frames).

## Acceptance criteria

- [x] The shell uses `ChatUiScaffold` with a collapsing large title.
- [x] No text glyphs remain in the shell.
- [x] Sidebar and drawer are grouped, icon-led, and include New chat.
- [x] Every existing destination remains reachable.
- [x] Navigation transitions are present and snap under reduced motion.
- [x] Predictive-back ordering still passes.
- [x] The chat title is not duplicated in the content area.
- [x] The composer is not obscured by the IME or gesture bar.
- [x] Shell tests pass with preserved test tags.

## Verification

```bash
make android-lint && make android-test && make android-assemble
cd android && ./gradlew :app:testDebugUnitTest --tests '*Shell*' --tests '*AdaptiveShell*' --tests '*BackPolicy*'
```

Plus a phone and tablet check when a device/emulator is available.

## Out of scope

- Chat bubble/composer redesign (UX-03).
- Conversation row redesign (UX-04).
- Motion tuning across all screens (UX-09).
- Accessibility audit (UX-10).

## Completion record

- Status: Complete on 2026-11-11.
- `ShellScaffold` now uses `ChatUiScaffold` with a collapsing large title, a
  translucent bar, a hairline separator that fades in on scroll, a compact-only
  leading menu icon, and icon actions.
- `ChatUiScaffold` gained `navigationIcon`, `contentWindowInsets`,
  `onSubtitleClick`, and `subtitleTestTag` parameters. `IconAction` now puts the
  content description on the button node so the accessible name is preserved.
- `ShellSidebar` is grouped into Chats / Library / Settings with icon-led
  `SettingsRow`s, a 12 dp accent-soft selected pill, a brand header, and a
  prominent New chat button. The existing `ConversationRoute` remains the
  conversation list.
- The compact drawer uses rounded trailing corners, a brand header, and an
  animated scrim.
- `ShellNavHost` adds shared-axis X, fade-through, and scale + fade transitions
  and snaps under reduced motion.
- Chat title/model/share/settings moved into the app bar through a new
  `ChatTopBarState` composition local. `ChatScreen` keeps a standalone header
  fallback for direct composition tests and previews.
- The composer has a bottom gradient and keeps the existing IME padding; the
  shell consumes the navigation-bar inset once.

## Handoff notes

- Compact-navigation decision: UX-00 chose sidebar-only (no bottom bar). The
  drawer remains the compact entry point and the sidebar header now has a
  prominent New chat button. No bottom bar was added.
- Scroll state source: destinations do not expose their scroll state yet, so
  `ShellScaffold` passes a hoisted `rememberScrollState()`. UX-03 should wire
  the chat list state into `ChatUiScaffold` so the title collapses on chat
  scroll.
- Shell tests changed: `ShellSidebarTest` now passes `onNewChat` and adds tests
  for the header New chat action and the Chats/Library/Settings grouping. New
  `ShellTopBarTest` covers the large-title collapse and `ShellTransitionsTest`
  covers reaching a destination through the animated host.
- Scope note: the task's `Owns` line lists only the shell, but steps 8-9 require
  `ChatScreen.kt` changes. The implementation also touched
  `core/designsystem/ChatUiScaffold.kt` and `Buttons.kt` to add the leading
  slot, separator, and accessible icon action required by the deliverables, and
  added `feature/chat/ChatTopBarState.kt` to publish chat bar content to the
  shell. `ChatScreenTest` was not changed because the standalone header
  fallback keeps its tags and behavior.
- Verification: `make android-lint`, `make android-test`, and
  `make android-assemble` all `BUILD SUCCESSFUL`; the targeted
  `:app:testDebugUnitTest --tests '*Shell*' --tests '*AdaptiveShell*'
  --tests '*BackPolicy*'` run also passed. Device/emulator checks were not run
  because none is attached.
