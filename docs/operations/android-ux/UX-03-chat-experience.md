# UX-03 - Chat experience

- Plan: [Android UI/UX Polish Implementation Plan](../android-ux-implementation-plan.md)
- Design review: [android-ux.md](../../android-ux.md)
- Status: Not started
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

- [ ] Bubbles use the new geometry, grouping, and avatars.
- [ ] Streaming shows a three-dot indicator, caret, and first-token shimmer.
- [ ] Reasoning and code blocks are polished and animated.
- [ ] Message actions are available via long-press and overflow.
- [ ] Scroll-to-bottom FAB and new-message pill work.
- [ ] Composer is a floating bar with an attach sheet and morphing send/stop.
- [ ] Attachment chips show thumbnails and remove with animation.
- [ ] Empty, availability, and error states use the new cards.
- [ ] All existing chat test tags are preserved and tests pass.
- [ ] No streaming, persistence, or attachment behavior changed.

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

## Handoff notes

- Record any test that had to change and why.
- Note the grouping rule and how timestamps are shown.
- Note the scroll FAB visibility threshold.
