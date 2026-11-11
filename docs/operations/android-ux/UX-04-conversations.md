# UX-04 - Conversations

- Plan: [Android UI/UX Polish Implementation Plan](../android-ux-implementation-plan.md)
- Design review: [android-ux.md](../../android-ux.md)
- Status: Complete
- Order: 5
- Depends on: UX-01, UX-02
- Parallel-safe: yes, with UX-03 and UX-05
- Owns: `android/feature/conversations/ConversationListScreen.kt`, conversation list tests
- Read first: [android-ux.md](../../android-ux.md) section 7; [implementation plan](../android-ux-implementation-plan.md) section 3.4; `ConversationListScreen.kt`, `ConversationListViewModel.kt`, `ConversationGrouping.kt`, `ConversationSearch.kt`

## Goal

Turn the conversation history into a polished, grouped list with swipe actions,
snackbar undo, app-bar search, and a friendly empty state. Preserve grouping,
search, pin, archive, delete, and undo behavior.

## Preconditions

- UX-01 and UX-02 are `Complete`.
- Do not change `ConversationListViewModel`, `ConversationService`, grouping
  rules, or search semantics.
- Preserve every `ConversationListTestTags` value.

## Deliverables

- Grouped list rows:
  - 52 dp min height, title (semibold), one-line preview snippet, relative
    timestamp, pin indicator, and chevron;
  - selected row uses an accent-soft background;
  - remove the card container and the "Manage" text button.
- Swipe actions:
  - swipe right to pin, swipe left to archive/delete;
  - colored action background and haptic threshold;
  - every swipe action also remains available in the overflow menu.
- Snackbar undo:
  - replace the inline undo row with an app snackbar ("Conversation deleted" +
    Undo) anchored above the list;
  - auto-dismiss with a progress ring.
- Search:
  - move into the app bar as an expanding search field;
  - show a clear button and highlight matches in results;
  - keep `ConversationListTestTags.SearchField`.
- Sticky group headers with uppercase caption style.
- Empty state: illustration + "No conversations yet" + "Start a chat" button.
- Segmented control for Chats / Archived instead of the text toggle.
- Animated item placement on insert/remove.

## Implementation steps

1. Read `ConversationListScreen.kt`, `ConversationListViewModel.kt`,
   `ConversationGrouping.kt`, `ConversationSearch.kt`, and the list tests.
2. Replace `ConversationRow`'s `Card` with a grouped row using `SettingsRow` or
   a dedicated `ConversationRow` primitive. Keep
   `ConversationListTestTags.item(id)` and `actions(id)`.
3. Add swipe actions with `SwipeToDismissBox` or a custom gesture. Keep the
   overflow menu entries and tags (`pin`, `archive`, `rename`, `delete`).
4. Replace the inline undo row with a snackbar. The screen needs a
   `SnackbarHostState` parameter or a host from `ChatUiScaffold`. Keep
   `ConversationListTestTags.UndoBar` and `UndoButton` if tests depend on them;
   otherwise update the tests to assert the snackbar.
5. Move search into the app bar. If the app bar is owned by the shell, expose a
   search slot or keep the field at the top of the list with the new styling.
   Do not break `SearchField`, `SearchLoading`, `SearchEmpty`, `SearchError`,
   and `SearchRetry`.
6. Add sticky headers with `stickyHeader` in `LazyColumn`.
7. Replace the archived toggle with a `SegmentedControl`. Keep
   `ConversationListTestTags.ArchivedToggle` or update the test.
8. Add the empty state and animated item placement.
9. Update tests for the new structure. Preserve tags and accessible names.

## Acceptance criteria

- [x] Conversation rows are grouped, compact, and show a preview and timestamp.
- [x] Swipe actions work and have menu equivalents.
- [x] Delete undo uses a snackbar.
- [x] Search is in the app bar with a clear button and match highlighting.
- [x] Group headers are sticky.
- [x] Empty state and segmented Chats/Archived are implemented.
- [x] List changes animate.
- [x] All existing conversation test tags are preserved or tests are updated
      deliberately.
- [x] No grouping, search, pin, archive, delete, or undo behavior changed.

## Verification

```bash
make android-lint && make android-test && make android-assemble
cd android && ./gradlew :feature:conversations:testDebugUnitTest
```

## Out of scope

- Shell/sidebar redesign (UX-02).
- Chat surface (UX-03).
- Global state kit adoption (UX-08).
- Motion tuning (UX-09).

## Completion record

- Status: Complete on 2026-11-11.
- Verification:
  - `make android-lint` - passed.
  - `make android-test` - passed.
  - `make android-assemble` - passed.
  - `cd android && ./gradlew :feature:conversations:testDebugUnitTest` - passed.
- Changed files:
  - `android/feature/conversations/src/main/kotlin/dev/chatui/feature/conversations/ConversationListScreen.kt`
  - `android/feature/conversations/src/test/kotlin/dev/chatui/feature/conversations/ConversationListScreenTest.kt`
  - `docs/operations/android-ux/UX-04-conversations.md`
  - `docs/operations/android-ux/README.md`

## Handoff notes

- Swipe thresholds: a swipe must cross 40% of the row width. Swipe right pins,
  swipe left archives. The haptic `confirm()` fires when the threshold is
  crossed. Both actions snap the row back and remain available in the overflow
  menu. Delete stays in the overflow menu so a destructive action is never a
  single accidental swipe.
- Search lives at the top of the list, not in a shell app bar. The shell owns
  the sidebar and has no search slot, so the list header keeps the field with
  the new styling, a clear button, and match highlighting. `SearchField`,
  `SearchLoading`, `SearchEmpty`, `SearchError`, and `SearchRetry` are
  preserved.
- The snackbar auto-dismisses after the ViewModel's 5 s undo window and shows a
  progress ring. The dismiss delay runs off the composition test dispatcher so
  Robolectric does not advance it before the snackbar can be asserted.
- Preview text uses the model label (or message count when the label is blank)
  because `Conversation` has no last-message text and the ViewModel/service are
  out of scope.
- Tests changed deliberately:
  - `archive toggle switches the view` now clicks the "Archived" segment text
    because `SegmentedControl` tags the whole control with `ArchivedToggle`.
  - Added `segmented control switches back to chats`, `swipe right pins and
    swipe left archives`, `search clear button clears the query`, and `empty
    state start chat button invokes the intent`.
  - `undo bar restores a buffered delete` waits for idle before asserting the
    snackbar.
- New tags: `EmptyStartChat` and `SearchClear`. All existing tags are preserved.
