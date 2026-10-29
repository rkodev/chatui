# T012 - Web chat UI, composer, rendering, attachments

- Milestone: M1/M2
- Depends on: T011
- Suggested subagent: general
- Parallel-safe: yes, with T022 and T032
- Read: `docs/IMPLEMENTATION.md` sections 3.2, 3.5-3.6, 6.2 (Message/ContentPart/Attachment), 8.4, 10.3-10.8, 11.3-11.7, 11.11, 12.2, 15.2 (cases 15-19, 21-25)

## Goal

Build the web chat experience on top of the provider layer: chat screen, virtualized message list, streaming presentation, Markdown/code/math rendering, composer, attachments, message actions, and all chat states.

## Owns

- `web/src/features/chat/**` except `ChatRunner.ts` and the streaming store contract owned by T011
- `web/src/features/attachments/**`
- `web/src/features/rendering/**` or equivalent Markdown/code/math components
- `web/src/test/chat/**`, `web/src/test/rendering/**`, `web/src/test/attachments/**`

## Deliverables

1. `/chat/$conversationId` view using the T010 shell and the T011 `ChatRunner`/streaming store contract.
2. `MessageList` with virtualization, stable keys, pagination of older messages, scroll anchoring, and isolated streaming message updates.
3. `MessageItem`: role layout, metadata, action row on hover/focus, inline error card, cancelled/interrupted states.
4. `ChatHeader`: title, model chip, conversation-settings entry point, export action hooks.
5. `Composer`:
   - Auto-grow 1-8 lines, then scroll.
   - Enter vs Mod+Enter based on settings; Shift+Enter newline.
   - Draft persistence per conversation.
   - Send/stop, disabled states offline/streaming/empty.
   - Attachment picker, drag-and-drop, clipboard image paste, chips with size/remove.
   - Preserve text on send failure.
6. Streaming presentation: placeholder, typing indicator, stop button, auto-scroll only near bottom, live-region announcements for start/complete/error, not every token.
7. Markdown rendering:
   - GFM, tables, task lists, blockquotes, links, inline and fenced code.
   - Shiki highlighting with language label and copy button.
   - KaTeX inline/block math with raw-source fallback.
   - Sanitize links and never render raw HTML or remote images by default.
   - Render incomplete fenced code during streaming as code.
8. Attachment handling:
   - Image (PNG/JPEG/WebP/GIF) and text files (`.txt`, `.md`, `.json`, `.csv`, source files) with size limits.
   - Downscale images to max 2048 px on the longest edge and re-encode where appropriate.
   - Store bytes through the T010 attachment store; never rely on provider URLs.
   - Capability warnings when the model lacks vision/files support.
9. Message actions: copy message, copy code, regenerate last assistant, edit last user with truncation confirmation, retry failed request. No branching.
10. Chat states from section 10.3: no provider, empty conversation, sending, streaming, complete, error, offline, model missing, context near limit.
11. Tests for composer behavior, virtualization, Markdown XSS, incomplete fences, math fallback, tables, code copy, attachment flow, cancellation, model-switch warning, and failed-send preservation.

## Steps

1. Wire the route and streaming state to the T011 runner.
2. Build the message list and chat states.
3. Add Markdown/code/math rendering.
4. Add composer and attachments.
5. Add message actions and tests.

## Acceptance criteria

- Sending appends the user message immediately and streams assistant text in order.
- Stop cancels and preserves partial text; regenerate and edit behave as specified.
- Markdown XSS payloads render inert and remote images stay blocked by default.
- Images are downscaled and stored locally; switching to a text-only model with attachments warns first.
- A 10,000-message conversation scrolls without rendering all messages.
- Streaming does not re-render the full history and auto-scroll only follows when near the bottom.
- Critical cases 15-19 and 21-25 pass on web.

## Verification

```bash
make web-lint
make web-typecheck
make web-test
make web-build
```

## Out of scope

- Provider adapters and onboarding (T011).
- Conversation sidebar, search, export/import, settings pages (T013).
- PWA, deployment, final performance/accessibility audit (T014, T015).
- Additional providers (T015).
