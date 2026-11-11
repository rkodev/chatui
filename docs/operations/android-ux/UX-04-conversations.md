# UX-04 - Conversations

- Plan: [Android UI/UX Polish Implementation Plan](../android-ux-implementation-plan.md)
- Design review: [android-ux.md](../../android-ux.md)
- Status: Not started
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

- [ ] Conversation rows are grouped, compact, and show a preview and timestamp.
- [ ] Swipe actions work and have menu equivalents.
- [ ] Delete undo uses a snackbar.
- [ ] Search is in the app bar with a clear button and match highlighting.
- [ ] Group headers are sticky.
- [ ] Empty state and segmented Chats/Archived are implemented.
- [ ] List changes animate.
- [ ] All existing conversation test tags are preserved or tests are updated
      deliberately.
- [ ] No grouping, search, pin, archive, delete, or undo behavior changed.

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

## Handoff notes

- Record the swipe thresholds and haptic behavior.
- Note whether search lives in the shell app bar or the list header.
- List any test that had to change and why.
