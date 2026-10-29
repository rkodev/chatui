# T013 - Web conversations, search, export/import, settings

- Milestone: M1/M2
- Depends on: T012, T010
- Suggested subagent: general
- Parallel-safe: yes, with T023 and T033
- Read: `docs/IMPLEMENTATION.md` sections 3.2, 3.7-3.8, 6.4, 8.3-8.6, 10.1, 11, 12.4, 15.2 (cases 18-19, 24-25), 21.4

## Goal

Complete the web app's conversation management and settings/data surfaces: sidebar history, search, pin/archive/rename/delete, export/import, data clearing, and settings routes.

## Owns

- `web/src/features/conversations/**`
- `web/src/features/settings/**`
- `web/src/features/data/**`
- `web/src/storage/export.ts`, `web/src/storage/import.ts` (new files; T010 base repositories remain read-only dependencies)
- `web/src/test/conversations/**`, `web/src/test/settings/**`, `web/src/test/data/**`

## Deliverables

1. Conversation sidebar/drawer:
   - New chat, search, pinned, and date groups: Today, Yesterday, Previous 7 days, Previous 30 days, Older.
   - Rename, delete with confirmation and 5-second undo toast where feasible, pin, archive.
   - Last-active conversation restore and new-chat behavior.
2. Search across conversation titles and message text with debounced input and empty/loading states.
3. Draft persistence integration approved by T012's composer contract.
4. Export:
   - Per-conversation JSON and Markdown.
   - Full data archive following the `chatui.export` format in section 21.4.
   - Secrets excluded by default; attachments optional; plaintext warning.
5. Import:
   - Schema validation with `zod` and the shared schema.
   - Size limits, ID validation, merge-by-default, optional replace.
   - Never execute imported content; sanitize attachment names.
6. Data controls: storage usage view, clear all data with confirmation, per-key deletion, export/import entry points.
7. Settings routes from section 8.3:
   - Appearance: theme, density, chat font size.
   - Providers/models entry points (owned by T011).
   - Data: storage, export/import, clear.
   - Backup: placeholder with "future" state.
   - About: version, licenses, privacy, security notes.
8. Settings that affect behavior: send key, show token usage, load remote images, default system prompt/params, remember keys, last active conversation.
9. Tests for date grouping, search, pin/archive, deletion/undo, export/import round trip, attachment inclusion, invalid import rejection, and clear-all behavior.

## Steps

1. Build sidebar and conversation actions.
2. Add search and grouping.
3. Implement export/import services and UI.
4. Add settings routes and wire settings to behavior.
5. Add tests and run the web gate.

## Acceptance criteria

- Conversations persist, group correctly by date, and survive reload.
- Search finds title and message text.
- Deleting a conversation can be undone within the toast window where implemented.
- Export/import preserves IDs, ordering, model snapshots, and attachments when included.
- Invalid imports are rejected without changing local data.
- Clearing data removes conversations, keys, and attachments.
- Critical cases 24-25 pass on web.

## Verification

```bash
make web-lint
make web-typecheck
make web-test
make web-build
```

## Out of scope

- New provider adapters (T011, T015).
- Chat rendering and composer behavior (T012).
- PWA/service worker, deployment, final performance/accessibility audit (T014).
