# T032 - Rust UI shell, chat, conversations, rendering

- Milestone: M3.5
- Depends on: T031
- Suggested subagent: general
- Parallel-safe: yes, with T012 and T022
- Read: `docs/IMPLEMENTATION.md` sections 3.2, 3.5-3.7, 6.2, 10.5-10.11, 11, 12.6, 15.2 (cases 15-27)

## Goal

Build the Rust client user experience: shared UI shell, chat screen, streaming presentation, Markdown/code/math rendering, composer, attachments, conversation management, settings, and export/import UI. Keep all business logic in the core crates.

## Owns

- `rust/crates/chatui-ui/src/**` except `features/onboarding`, `features/providers`, and `features/models` owned by T031
- `rust/crates/chatui-ui/src/features/chat/**`
- `rust/crates/chatui-ui/src/features/conversations/**`
- `rust/crates/chatui-ui/src/features/settings/**`
- `rust/crates/chatui-ui/src/features/attachments/**`
- `rust/crates/chatui-ui/src/features/rendering/**`
- `rust/apps/desktop/src/**` UI wiring
- UI tests under `rust/crates/chatui-ui/tests/**` and desktop tests

## Deliverables

1. UI shell with desktop sidebar and narrow/mobile drawer/navigation stack, theme switching, density, chat font size, reduced motion, and focus-visible states.
2. Chat screen:
   - Message list with virtualization/windowing, stable keys, pagination, scroll anchoring.
   - Isolated streaming message so token updates do not re-render history.
   - Streaming start/complete/error states, stop button, auto-scroll only near bottom.
3. Composer:
   - Auto-grow, Enter/Mod+Enter behavior, drafts per conversation.
   - Attachment picker, drag-and-drop, clipboard paste, chips, disabled states.
   - Preserve text on failure.
4. Chat runner integration: consume T031 provider events, persist partial text at most once per second and on pause/completion/cancel/error, mark streaming messages cancelled on startup.
5. Markdown rendering:
   - `pulldown-cmark` for GFM, tables, task lists, links, inline/fenced code.
   - Code highlighting with language label and copy button.
   - Math renderer or sanitized raw fallback.
   - No raw HTML, no remote images by default, incomplete fences render as code.
6. Attachments: image/text selection, downscale with `image`, store via `chatui-storage`, capability warnings.
7. Conversation management: sidebar date groups, search, pin/archive/rename/delete, new chat, drafts, last active restore.
8. Settings: appearance, chat defaults, data/storage usage, clear data, export/import, backup placeholder, about/privacy. Wire settings to behavior.
9. Export/import UI for `chatui.export`, per-conversation JSON/Markdown, attachment opt-in, plaintext warning.
10. Platform integration: copy message/code, file dialogs, native menus, keyboard shortcuts, `chatui://chat/<id>` deep links, share where supported.
11. Accessibility: keyboard navigation, focus rings, screen-reader semantics via the UI framework bridge, TalkBack/desktop screen-reader smoke test.
12. Tests for composer, message list, Markdown XSS, incomplete fences, math fallback, attachment flow, cancellation, persistence, conversation grouping/search, and export/import.

## Steps

1. Wire the UI shell/router and design tokens.
2. Build chat and streaming presentation.
3. Add rendering and attachments.
4. Add conversations, settings, and export/import.
5. Add tests and run the Rust gate.

## Acceptance criteria

- Sending streams text in order; stop preserves partial text.
- Restart/process death preserves partial content and marks it cancelled/interrupted.
- Markdown XSS is inert and remote images are blocked by default.
- Attachments are stored locally and survive reload.
- Conversations group, search, pin/archive/rename/delete correctly.
- Export/import round trips preserve IDs, ordering, snapshots, and optional attachments.
- Keyboard navigation and focus rings work on desktop.
- Critical cases 15-27 pass on Rust when enabled.

## Verification

```bash
cd rust && cargo fmt --all --check && cargo clippy --workspace --all-targets -- -D warnings && cargo nextest run --workspace
cd rust && cargo build --workspace
```

## Out of scope

- Provider adapters and onboarding/model picker (T031).
- Packaging, updater, final security/performance hardening (T033).
- Additional providers and parity (T034).
