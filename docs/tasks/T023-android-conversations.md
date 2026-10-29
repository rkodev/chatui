# T023 - Android conversations, search, export/import, settings, share

- Milestone: M1/M2
- Depends on: T022
- Suggested subagent: general
- Parallel-safe: yes, with T013 and T033
- Read: `docs/IMPLEMENTATION.md` sections 3.2, 3.7-3.8, 6.4, 9.3-9.5, 9.8, 9.10, 10.1, 11, 12.3-12.4, 15.2 (cases 24-27), 21.4

## Goal

Complete Android conversation and settings surfaces: drawer history, search, pin/archive/rename/delete, export/import, settings, share target, deep links, and app-level recovery.

## Owns

- `android/feature/conversations/**`
- `android/feature/settings/**`
- `android/feature/data/**` if separate
- Share-target and deep-link wiring in `android/app/src/main/AndroidManifest.xml` and app navigation registration
- `android/app/src/main/res/xml/` share/deep-link resources as needed
- Tests for conversations, settings, export/import, share intent parsing

## Deliverables

1. Conversation drawer:
   - Search, new chat, pinned, history groups (Today, Yesterday, Previous 7 days, Previous 30 days, Older), archive, settings entry.
   - Rename, delete with confirmation, pin, archive.
   - Restore last active conversation.
2. Search using Room FTS over message text and titles, with debounce and empty state.
3. Export/import:
   - Per-conversation JSON and Markdown via Storage Access Framework.
   - Full `chatui.export` archive with optional attachments and plaintext warning.
   - Schema validation, size limits, merge-by-default, optional replace, attachment name sanitization.
   - Secrets excluded by default.
4. Settings screens: appearance, chat defaults, data/storage usage, clear data, backup placeholder, about/privacy.
5. Share target for `ACTION_SEND` with `text/plain` to prefill the composer; handle the intent without losing current drafts.
6. Deep links: `chatui://chat/<id>`; open the corresponding conversation or a safe not-found state.
7. Platform integration: copy message/code, open provider docs in Custom Tab, hardware keyboard shortcuts, predictive back ordering.
8. App-level process-death recovery for drafts, navigation, and conversation selection. Do not duplicate T022's streaming-message recovery.
9. Tests for date grouping, search, delete/undo, export/import round trip, invalid imports, clear data, share intent parsing, and deep links.

## Steps

1. Build drawer and conversation actions.
2. Add FTS search UI.
3. Add export/import and data controls.
4. Add settings, share target, and deep links.
5. Add tests and run the Android gate.

## Acceptance criteria

- Conversations persist, group correctly, and survive restart/process death.
- Search finds title and message text.
- Export/import preserves IDs, ordering, model snapshots, and attachments when included.
- Invalid imports do not mutate local data.
- Clearing data removes conversations, keys, and attachments.
- Share intents prefill the composer without data loss.
- Deep links open the correct conversation or a safe fallback.
- Critical cases 24-27 pass on Android.

## Verification

```bash
cd android && ./gradlew :feature:conversations:testDebugUnitTest :feature:settings:testDebugUnitTest :app:assembleDebug
```

## Out of scope

- Provider/model UI (T021).
- Chat rendering/composer (T022).
- Release signing, baseline profiles, final accessibility/performance audit (T024).
- Additional providers and screenshots (T025).
