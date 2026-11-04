# M2 Task Breakdown

- Source plan: [M2 Providers and Content Implementation Plan](../M2-providers-and-content-implementation-plan.md)
- Milestone: [M2 Providers and content](../../milestones/M2-providers-and-content.md)
- Authoritative spec: [IMPLEMENTATION.md](../../IMPLEMENTATION.md)
- Status: Not started
- Last updated: 2026-11-04
- Ordering: work through the `Order` column. A task may start only when every dependency is `Complete`.
- Scope: web and Android only. Rust, M3 Android depth, M4 release work, backup/sync, and deployment are out of scope.

## How to use these tasks

1. Read the source plan section named in the task before editing code.
2. Read the spec sections listed in the task's `Read first` line.
3. Touch only the paths listed under `Owns`. If another path is required, stop and record the dependency in `Handoff notes` instead of silently expanding scope.
4. Do not start a task until its `Depends on` tasks are `Complete`.
5. Keep changes inside one task's area. If two tasks need the same file, follow the dependency order unless the task says they are parallel-safe.
6. Do not mark a task complete until every acceptance checkbox is true and every verification command passes.
7. Update this index and the task file status when work starts or completes.
8. Put design decisions, deviations, and evidence in the task's `Handoff notes`; do not leave them only in chat or commit messages.
9. Never record API keys, prompts, conversation content, signing material, or personal data in task files, screenshots, logs, or CI artifacts.
10. Do not start M3 platform integration from inside an M2 task.

## Status values

| Status | Meaning |
| --- | --- |
| Not started | No implementation work has begun |
| In progress | Work has begun; keep this index updated |
| Blocked | A dependency or decision prevents progress; record the blocker |
| Complete | Acceptance criteria and verification pass |
| Cancelled | Work was removed or replaced; record why |

## Ordered task list

| Order | Task | Status | Depends on | Area | Parallel-safe with |
| --- | --- | --- | --- | --- | --- |
| 1 | [M2-00.1 Expected streams and error fixtures](M2-00.1-expected-streams-errors.md) | Complete | none | contracts | none; unblocks provider work |
| 2 | [M2-00.2 Catalogs, request vectors, math/export fixtures, and CORS plan](M2-00.2-catalogs-requests-fixtures.md) | Complete | M2-00.1 | contracts | none |
| 3 | [M2-01.1 Web OpenAI-compatible transport](M2-01.1-web-openai-compatible-transport.md) | Complete | M2-00.2 | web | M2-05.1 |
| 4 | [M2-01.2 Web preset enablement and Custom providers](M2-01.2-web-preset-enablement-custom.md) | Not started | M2-01.1 | web | M2-05.2 |
| 5 | [M2-01.3 Web discovery, fallback, capabilities, reasoning, and provider UI](M2-01.3-web-discovery-capability-reasoning-ui.md) | Not started | M2-01.2 | web | M2-05.3 |
| 6 | [M2-02.1 Web Anthropic adapter](M2-02.1-web-anthropic-adapter.md) | Not started | M2-01.3 | web | M2-02.2, M2-02.3, M2-06.1 |
| 7 | [M2-02.2 Web Gemini adapter](M2-02.2-web-gemini-adapter.md) | Not started | M2-01.3 | web | M2-02.1, M2-02.3, M2-06.2 |
| 8 | [M2-02.3 Web Ollama adapter](M2-02.3-web-ollama-adapter.md) | Not started | M2-01.3 | web | M2-02.1, M2-02.2, M2-06.3 |
| 9 | [M2-02.4 Web CORS classification and matrix](M2-02.4-web-cors-matrix.md) | Not started | M2-02.1, M2-02.2, M2-02.3 | web | M2-03.1 |
| 10 | [M2-03.1 Web content and reasoning rendering](M2-03.1-web-content-rendering.md) | Not started | M2-01.3 | web | M2-02.1, M2-02.2, M2-02.3 |
| 11 | [M2-03.2 Web parameters and conversation settings](M2-03.2-web-parameters-conversation-settings.md) | Not started | M2-01.3 | web | M2-03.1 |
| 12 | [M2-03.3 Web attachments end-to-end](M2-03.3-web-attachments.md) | Not started | M2-03.1, M2-03.2 | web | M2-06.6 |
| 13 | [M2-03.4 Web settings routes and behavior wiring](M2-03.4-web-settings-routes.md) | Not started | M2-01.3 | web | M2-04.1 |
| 14 | [M2-04.1 Web conversation search](M2-04.1-web-search.md) | Not started | M2-03.4 | web | M2-04.2 no; sidebar files shared |
| 15 | [M2-04.2 Web pin, archive, and delete undo](M2-04.2-web-pin-archive-undo.md) | Not started | M2-04.1 | web | M2-07.2 |
| 16 | [M2-04.3 Web export](M2-04.3-web-export.md) | Not started | M2-03.4 | web | M2-07.3 |
| 17 | [M2-04.4 Web import](M2-04.4-web-import.md) | Not started | M2-04.3 | web | M2-07.4 |
| 18 | [M2-04.5 Web data controls](M2-04.5-web-data-controls.md) | Not started | M2-04.3, M2-04.4 | web | M2-07.5 |
| 19 | [M2-04.6 Web Playwright suite](M2-04.6-web-playwright.md) | Not started | M2-04.1 through M2-04.5, M2-03.3 | web | M2-08.2 |
| 20 | [M2-05.1 Android OpenAI-compatible transport](M2-05.1-android-openai-compatible-transport.md) | Not started | M2-00.2 | android | M2-01.1 |
| 21 | [M2-05.2 Android preset enablement and Custom providers](M2-05.2-android-preset-enablement-custom.md) | Not started | M2-05.1 | android | M2-01.2 |
| 22 | [M2-05.3 Android discovery, fallback, capabilities, and reasoning](M2-05.3-android-discovery-capability-reasoning.md) | Not started | M2-05.2 | android | M2-01.3 |
| 23 | [M2-05.4 Android provider UI and shell wiring](M2-05.4-android-provider-ui-shell.md) | Not started | M2-05.3 | android | none; blocks Android adapter tasks |
| 24 | [M2-06.1 Android Anthropic adapter](M2-06.1-android-anthropic-adapter.md) | Not started | M2-05.4 | android | M2-06.2, M2-06.3, M2-02.1 |
| 25 | [M2-06.2 Android Gemini adapter](M2-06.2-android-gemini-adapter.md) | Not started | M2-05.4 | android | M2-06.1, M2-06.3, M2-02.2 |
| 26 | [M2-06.3 Android Ollama adapter](M2-06.3-android-ollama-adapter.md) | Not started | M2-05.4 | android | M2-06.1, M2-06.2, M2-02.3 |
| 27 | [M2-06.4 Android content and reasoning rendering](M2-06.4-android-content-rendering.md) | Not started | M2-05.4 | android | M2-06.5 |
| 28 | [M2-06.5 Android parameters, conversation settings, and settings screens](M2-06.5-android-parameters-settings.md) | Not started | M2-05.4 | android | M2-06.4 |
| 29 | [M2-06.6 Android attachments end-to-end](M2-06.6-android-attachments.md) | Not started | M2-06.4, M2-06.5 | android | M2-03.3 |
| 30 | [M2-07.1 Android conversation search](M2-07.1-android-search.md) | Not started | M2-06.5 | android | M2-07.2 no; conversation files shared |
| 31 | [M2-07.2 Android pin, archive, and delete undo](M2-07.2-android-pin-archive-undo.md) | Not started | M2-07.1 | android | M2-04.2 |
| 32 | [M2-07.3 Android export](M2-07.3-android-export.md) | Not started | M2-06.5 | android | M2-04.3 |
| 33 | [M2-07.4 Android import](M2-07.4-android-import.md) | Not started | M2-07.3 | android | M2-04.4 |
| 34 | [M2-07.5 Android data controls and shell integration](M2-07.5-android-data-controls-shell.md) | Not started | M2-07.3, M2-07.4 | android | M2-04.5 |
| 35 | [M2-08.1 Android provider adapter tests](M2-08.1-android-provider-tests.md) | Not started | M2-06.1, M2-06.2, M2-06.3 | android | none; M2-08.2 follows |
| 36 | [M2-08.2 Android persistence, data, and critical-case tests](M2-08.2-android-persistence-data-tests.md) | Not started | M2-06.6, M2-07.3, M2-07.4, M2-07.5, M2-08.1 | android | M2-04.6 |
| 37 | [M2-09.1 CORS matrix and conformance evidence](M2-09.1-cors-conformance-evidence.md) | Not started | M2-02.4, M2-04.6, M2-08.1, M2-08.2 | verification | none |
| 38 | [M2-09.2 Parity and exit gate](M2-09.2-parity-exit-gate.md) | Not started | all prior tasks | verification | none |

## Parallel execution groups

- Group A: M2-00.1, then M2-00.2. Both block all adapter work.
- Group B: M2-01.1 and M2-05.1 in parallel. Then M2-01.2/M2-05.2, then M2-01.3/M2-05.3.
- Group C: web native adapters M2-02.1, M2-02.2, and M2-02.3 in parallel; Android native adapters M2-06.1, M2-06.2, and M2-06.3 in parallel after M2-05.4.
- Group D: M2-02.4 CORS follows the three web adapters. M2-03.1 and M2-03.2 can run in parallel after M2-01.3. M2-03.3 follows both. M2-03.4 is independent of content work.
- Group E: M2-04.1 and M2-04.2 are sequential because they share sidebar files. M2-04.3, M2-04.4, and M2-04.5 are sequential because they share export/import/data services. M2-04.6 is last on web.
- Group F: M2-05.4 follows M2-05.3. M2-06.4 and M2-06.5 can run in parallel after M2-05.4. M2-06.6 follows both.
- Group G: M2-07.1 and M2-07.2 are sequential. M2-07.3, M2-07.4, and M2-07.5 are sequential. M2-07.* can run in parallel with M2-06.* after M2-06.5.
- Group H: M2-08.1 after the Android adapters. M2-08.2 after the Android data and attachment tasks. M2-09.1 after web/Android test evidence exists. M2-09.2 is the final gate.

## Completion rule

The M2 milestone is complete only when every task is `Complete` or explicitly cancelled with an approved deferral, `docs/operations/M2/M2-verification.md` contains the exit-gate evidence, `contracts/parity.md` is truthful, and the milestone status table is updated.

## Task orchestration

The existing runner scripts accept a task folder:

```bash
scripts/next-task docs/operations/M2
scripts/next-task docs/operations/M2 --list
scripts/next-task docs/operations/M2 --include-in-progress
scripts/task-status get docs/operations/M2/M2-01.1-web-openai-compatible-transport.md
```

Keep one task file per numbered row and update the status in both this index and the task file.
