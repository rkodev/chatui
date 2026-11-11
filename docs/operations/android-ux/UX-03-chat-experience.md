# UX-03 - Chat experience

- Plan: [Android UI/UX Polish Implementation Plan](../android-ux-implementation-plan.md)
- Design review: [android-ux.md](../../android-ux.md)
- Status: Complete
- Order: 4
- Depends on: UX-01, UX-02
- Parallel-safe: yes, with UX-04 and UX-05
- Owns: `android/feature/chat/`, chat tests
- Read first: [android-ux.md](../../android-ux.md) section 6; [implementation plan](../android-ux-implementation-plan.md) section 3.3; `ChatScreen.kt`, `ChatMessageList.kt`, `ChatMessageItem.kt`, `ChatComposer.kt`, `ChatCodeBlock.kt`, `ChatMessageActions.kt`

## Goal

Turn the chat surface into the polished centerpiece: grouped bubbles, avatars,
a real streaming indicator, a scroll affordance, a floating composer, and
friendly empty/error states. Preserve all streaming, persistence, attachment,
and action behavior.

## Preconditions

- UX-01 and UX-02 are `Complete`.
- Do not change `ChatViewModel`, `ChatRunner`, `StreamingState`, persistence, or
  attachment validation logic.
- Preserve every `ChatScreenTestTags`, `ChatMessageTestTags`,
  `ChatMessageListTestTags`, `ChatComposerTestTags`, `ChatCodeBlockTestTags`,
  and `ChatMessageActionsTestTags` value.

## Deliverables

### Message list and bubbles

- Bubble geometry: max width 78%, 20 dp radius with a 4 dp tail corner on the
  sender side, 14×10 dp padding, 2 dp gap within a group, 12 dp between groups.
- Grouping: consecutive messages from the same role share a group; show the
  role/avatar only on the first message; timestamps on tap or at group end.
- Avatars: 28 dp assistant brand mark and a user initial circle.
- Assistant surface: grouped background, no border. User surface: accent-tinted.
- Remove the per-message role label row.
- Reasoning: animated disclosure with a one-line preview.
- Code blocks: rounded 12 dp container, language pill, icon copy/wrap buttons,
  horizontal scroll with edge fade.
- Actions: long-press context menu plus a trailing overflow; keep an inline row
  only for the last assistant message. Animate the row in.
- Meta: move model/usage into a collapsible details section or footnote.

### Streaming

- Replace the `…` glyph with a three-dot bouncing indicator.
- Add a blinking caret at the end of streamed text.
- Add a subtle shimmer on the bubble while waiting for the first token.
- Keep the streaming row isolated so history does not recompose.

### Scroll

- Add a scroll-to-bottom FAB that appears when the user scrolls up during
  streaming.
- Add a "new messages" pill when content arrives off-screen.
- Use `animateScrollToItem` for programmatic scrolls.
- Preserve scroll position across rotation and process death.

### Composer

- Replace the `OutlinedTextField` + text buttons with a floating rounded bar:
  - leading `+` attach button that opens a bottom sheet with Photo / File /
    Paste;
  - auto-growing multi-line field with placeholder "Message…";
  - trailing circular send button (arrow-up icon) that morphs into a stop square
    while streaming;
  - attachment chips as rounded thumbnails with an `×` overlay, horizontally
    scrollable, with a remove animation.
- Remove the always-visible "Attach" and "Paste" text buttons.
- Add a focus border and soft shadow.
- Offline: compact inline banner above the bar; disable send.
- Add a send haptic and a springy press scale.

### Empty and error states

- Empty state: brand mark, warm greeting, 2–3 suggested prompt chips, and a
  model selector. Animate in with fade + rise.
- Availability banner: card with icon, title, body, and one primary action; use
  warning (amber) for setup gaps, not error red.
- Errors: inline card with icon, human copy, Details disclosure, and Retry.
- Transient failures: snackbar with Retry.

## Implementation steps

1. Read all chat files and their tests.
2. Add a `ChatBubble` composable (or refactor `ChatMessageItem`) with the new
   geometry and grouping. Keep `ChatMessageTestTags.message(id)` and
   `ChatMessageTestTags.text(id)`.
3. Add grouping logic in `ChatMessageList` using the previous message role.
   Keep stable keys and content types.
4. Add `TypingIndicator` and `StreamingCaret` composables. Replace the `…` text.
5. Add the scroll FAB and new-message pill using `derivedStateOf` on
   `LazyListState`. Use `animateScrollToItem`.
6. Refactor `ChatCodeBlock` to use icon buttons and the new container. Keep
   `ChatCodeBlockTestTags`.
7. Refactor `ChatMessageActions` into a long-press menu plus overflow. Keep the
   existing tags and the edit dialog behavior.
8. Refactor `ChatComposer` into the floating bar. Move attach/paste into a
   `ModalBottomSheet`. Keep `ChatComposerTestTags` and the send-key behavior.
9. Add attachment thumbnail chips with remove animation.
10. Replace the empty state and availability/error presentation.
11. Add tests for grouping, streaming indicator, scroll FAB, composer send/stop
    morph, attachment removal, and empty/error states.
12. Run the chat test suite and fix regressions. Do not change behavior to make
    a test pass.

## Acceptance criteria

- [x] Bubbles use the new geometry, grouping, and avatars.
- [x] Streaming shows a three-dot indicator, caret, and first-token shimmer.
- [x] Reasoning and code blocks are polished and animated.
- [x] Message actions are available via long-press and overflow.
- [x] Scroll-to-bottom FAB and new-message pill work.
- [x] Composer is a floating bar with an attach sheet and morphing send/stop.
- [x] Attachment chips show thumbnails and remove with animation.
- [x] Empty, availability, and error states use the new cards.
- [x] All existing chat test tags are preserved and tests pass.
- [x] No streaming, persistence, or attachment behavior changed.

## Verification

```bash
make android-lint && make android-test && make android-assemble
cd android && ./gradlew :feature:chat:testDebugUnitTest
```

Plus a manual streaming check on a device/emulator when available.

## Out of scope

- Shell/app-bar changes (UX-02).
- Conversation list changes (UX-04).
- Global state kit adoption outside chat (UX-08).
- Motion tuning across all screens (UX-09).

## Completion record

- Status: Complete on 2026-11-11.
- Refactored `ChatMessageItem` into grouped bubbles with 78% max width, 20 dp
  radius, a 4 dp sender-side tail, 14×10 dp padding, 28 dp avatars, animated
  reasoning disclosure, streaming typing/caret/shimmer, and an inline error
  card.
- Added grouping in `ChatMessageList` (avatar on group start, timestamp on
  group end or tap), a scroll-to-bottom FAB, a new-message pill, and animated
  programmatic scrolls.
- Refactored `ChatCodeBlock` to a 12 dp container with a language pill, icon
  copy/wrap buttons, and an edge fade.
- Refactored `ChatMessageActions` into an inline row for the last assistant
  message plus an overflow menu and long-press context menu for every message.
- Rebuilt `ChatComposer` as a floating rounded bar with a `+` attach sheet,
  auto-growing field, morphing send/stop button, thumbnail attachment chips,
  focus border, soft shadow, offline banner, send haptic, and press scale.
- Replaced the empty state with a branded greeting, prompt chips, and model
  selector; converted availability and screen errors to warning/error cards.
- Verification: `make android-lint`, `make android-test`,
  `make android-assemble`, and
  `cd android && ./gradlew :feature:chat:testDebugUnitTest` all passed.

## Handoff notes

- Tests updated for visible-text changes only: `ChatScreenTest` now expects the
  new empty-state greeting and opens the overflow menu before editing the last
  user message; `ChatCodeBlockTest` asserts the wrap/copy icon content
  descriptions instead of text labels. No behavior assertion was weakened.
- New tests: grouping/avatar count, scroll-to-bottom FAB, new-message pill,
  typing indicator vs. streaming caret, attach sheet, and overflow actions.
- Grouping rule: consecutive messages with the same role form a group. The
  avatar renders only on the first row; the timestamp renders on the last row
  of the group and on tap. The list uses 2 dp spacing plus a 10 dp top pad on
  group starts for the 12 dp inter-group gap.
- Scroll FAB visibility threshold: shown when the user is not within two items
  of the end and either a stream is active or new content arrived off-screen.
  The new-message pill uses the same off-screen condition.
- Deviation: the last user message exposes Edit through the overflow menu
  rather than an inline row, so the inline row stays reserved for the last
  assistant message as specified.
- Deviation: `ChatUiState.error` has no retry callback, so the screen-level
  transient error renders as an inline error card with Dismiss. Message-level
  failures keep the inline Retry action.
- Manual streaming check on a device/emulator was not available in this
  environment; the streaming behavior is covered by the existing
  `ChatRouteFlowTest` and the new indicator tests.
