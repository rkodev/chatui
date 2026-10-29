# T022 - Android chat UI, composer, rendering, attachments

- Milestone: M1/M2
- Depends on: T021
- Suggested subagent: general
- Parallel-safe: yes, with T012 and T032
- Read: `docs/IMPLEMENTATION.md` sections 3.2, 3.5-3.6, 6.2, 9.3-9.4, 9.8, 10.3-10.8, 11.3-11.7, 11.11, 15.2 (cases 15-19, 21-26)

## Goal

Build the native Android chat experience: Compose chat screen, streaming presentation, Markdown/code/math rendering, composer, attachments, message actions, and streaming state persistence including process-death behavior.

## Owns

- `android/feature/chat/**`
- `android/feature/attachments/**`
- Chat-specific Compose components added under `feature/chat`
- `android/feature/chat/src/test/**`, `android/feature/chat/src/androidTest/**`, attachment tests

## Deliverables

1. Chat screen using `Scaffold`, `TopAppBar`, `LazyColumn`, and the T020 navigation shell.
2. `MessageList` with stable keys and `contentType`; hoist the streaming message so history does not recompose per token; load older messages on scroll.
3. `MessageItem`: role layout, metadata, action row, inline error card, cancelled/interrupted states.
4. Streaming ViewModel/service using the T021 `ChatStreamer`:
   - Emits to a `StreamingState`; persists at most once per second.
   - Persists on pause, completion, cancellation, error, and process death where possible.
   - One active stream per conversation; send lock.
5. Composer:
   - Auto-grow behavior, send/stop, disabled states.
   - Enter behavior consistent with settings; send button always visible on mobile.
   - Draft persistence per conversation.
   - Photo picker, document picker, drag-and-drop on ChromeOS/tablets, clipboard image paste.
   - Attachment chips with size/remove; preserve text on failure.
6. Attachments:
   - Store under `filesDir/attachments/<sha256>`.
   - Insert Room metadata in a transaction after write and hash.
   - Downscale with `BitmapFactory`/`ImageDecoder`; strip EXIF except orientation.
   - Capability warnings for text-only models.
7. Markdown rendering with `multiplatform-markdown-renderer-m3`:
   - GFM, tables, task lists, links, inline/fenced code.
   - Code highlighting via `dev.snipme:highlights` or equivalent; copy button.
   - Math rendering with raw-source fallback.
   - Never render raw HTML; remote images blocked unless enabled.
   - Incomplete fenced code during streaming renders as code.
8. Message actions: copy, copy code, regenerate last assistant, edit last user with truncation confirmation, retry failed. No branching.
9. Chat states from section 10.3, including offline, model missing, context warning, and streaming live-region semantics.
10. Tests: Composer, message list, Markdown XSS, code copy, attachment downscale/EXIF, cancellation, process-death recovery, and a large conversation list.

## Steps

1. Wire chat route and streaming ViewModel to T021.
2. Build message list and chat states.
3. Add Markdown/code/math rendering.
4. Add composer and attachments.
5. Add message actions and tests.

## Acceptance criteria

- Sending shows the user message immediately and appends streamed text in order.
- Stop cancels the OkHttp call, persists partial text, and marks the message cancelled.
- Process death during streaming preserves partial content and marks it interrupted.
- Markdown XSS is inert; remote images stay blocked by default.
- Images are downscaled, EXIF-stripped, and survive reload.
- A 10,000-message conversation scrolls without rendering all messages.
- Critical cases 15-19 and 21-26 pass on Android.

## Verification

```bash
cd android && ./gradlew :feature:chat:testDebugUnitTest :feature:chat:connectedDebugAndroidTest :app:assembleDebug
```

## Out of scope

- Provider adapters/onboarding/model picker (T021).
- Conversation drawer, search, export/import, settings, share target (T023).
- Release hardening and final accessibility/performance pass (T024).
- Additional providers and screenshot suite (T025).
