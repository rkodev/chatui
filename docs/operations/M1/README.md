# M1 Task Breakdown

- Source plan: [M1 OpenRouter Core Implementation Plan](../M1-openrouter-core-implementation-plan.md)
- Milestone: [M1 OpenRouter-first core chat](../../milestones/M1-openrouter-core.md)
- Authoritative spec: [IMPLEMENTATION.md](../../IMPLEMENTATION.md)
- Status: Not started
- Last updated: 2026-11-03
- Ordering: work through the `Order` column unless a task's dependency column says otherwise.
- Scope: OpenRouter-only chat on web and Android. Rust, attachments, math, search, export/import, and additional providers are out of scope.

## How to use these tasks

1. Read the source plan section named in the task before editing code.
2. Read the spec sections listed in the task's `Read first` line.
3. Touch only the paths listed under `Owns`. If another path is required, stop and record the dependency in `Handoff notes` instead of silently expanding scope.
4. Do not mark a task complete until every acceptance checkbox is true and every verification command passes.
5. Update this index and the task file status when work starts or completes.
6. Put design decisions and deviations in the task's `Handoff notes`; do not leave them only in chat or commit messages.
7. Do not start M2 work from inside an M1 task.

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
| 1 | [M1-01.1 Expected OpenRouter normalized vectors](M1-01.1-openrouter-expected-vectors.md) | Complete | none | contracts | none; unblocks adapters |
| 2 | [M1-01.2 Request vectors and CORS evidence template](M1-01.2-request-vectors-and-cors-template.md) | Complete | M1-01.1 | contracts | none |
| 3 | [M1-02.1 Web contract registry and config gating](M1-02.1-web-contract-registry.md) | Complete | M1-01.2 | web | M1-06.1 |
| 4 | [M1-02.2 Web SSE transport](M1-02.2-web-sse-transport.md) | Complete | M1-02.1 | web | M1-02.3, M1-06.2 |
| 5 | [M1-02.3 Web OpenRouter key validation and catalog](M1-02.3-web-openrouter-key-catalog.md) | Complete | M1-02.1 | web | M1-02.2, M1-06.3 |
| 6 | [M1-02.4 Web OpenRouter streaming, retry, errors, routing](M1-02.4-web-openrouter-streaming.md) | Complete | M1-01.2, M1-02.2, M1-02.3 | web | M1-06.4 |
| 7 | [M1-03.1 Web settings and provider/model services](M1-03.1-web-settings-provider-services.md) | Complete | M1-02.4 | web | M1-05.1, M1-07.1 |
| 8 | [M1-05.1 Web conversation service](M1-05.1-web-conversation-service.md) | Complete | M1-03.1 | web | M1-03.2, M1-08.1 |
| 9 | [M1-03.2 Web onboarding and key entry](M1-03.2-web-onboarding-key-entry.md) | Complete | M1-03.1 | web | M1-07.2 |
| 10 | [M1-03.3 Web provider, model, defaults, and routing UI](M1-03.3-web-provider-model-management.md) | Complete | M1-03.1, M1-03.2 | web | M1-07.3 |
| 11 | [M1-04.1 Web ChatRunner and streaming store](M1-04.1-web-chat-runner.md) | Complete | M1-02.4, M1-05.1 | web | M1-09.1 |
| 12 | [M1-04.2 Web chat screen and message states](M1-04.2-web-chat-screen.md) | Complete | M1-04.1, M1-03.1, M1-05.1 | web | M1-09.2 |
| 13 | [M1-04.3 Web composer](M1-04.3-web-composer.md) | Complete | M1-04.1 | web | M1-09.2 |
| 14 | [M1-04.4 Web Markdown, code, actions, accessibility](M1-04.4-web-markdown-actions.md) | Complete | M1-04.2 | web | M1-09.3 |
| 15 | [M1-05.2 Web history, restore, and recovery](M1-05.2-web-history-recovery.md) | Complete | M1-05.1, M1-03.1, M1-04.2 | web | M1-08.2 |
| 16 | [M1-06.1 Android contract registry and dependencies](M1-06.1-android-contract-registry.md) | Complete | M1-01.2 | android | M1-02.1 |
| 17 | [M1-06.2 Android SSE transport](M1-06.2-android-sse-transport.md) | Complete | M1-06.1 | android | M1-02.2, M1-06.3 |
| 18 | [M1-06.3 Android key validation and catalog](M1-06.3-android-key-catalog.md) | Complete | M1-06.1 | android | M1-06.2, M1-02.3 |
| 19 | [M1-06.4 Android OpenRouter streaming, retry, errors, routing](M1-06.4-android-openrouter-streaming.md) | Not started | M1-01.2, M1-06.2, M1-06.3 | android | M1-02.4 |
| 20 | [M1-08.1 Android repositories and transactions](M1-08.1-android-repositories.md) | Not started | M1-06.4 | android | M1-05.1 |
| 21 | [M1-07.1 Android provider/model services and Hilt wiring](M1-07.1-android-provider-services.md) | Not started | M1-06.4, M1-08.1 | android | M1-03.1 |
| 22 | [M1-07.2 Android onboarding and key entry](M1-07.2-android-onboarding-key-entry.md) | Not started | M1-07.1 | android | M1-03.2 |
| 23 | [M1-07.3 Android provider, model, defaults, and routing UI](M1-07.3-android-provider-model-management.md) | Not started | M1-07.1, M1-07.2 | android | M1-03.3 |
| 24 | [M1-08.2 Android conversations and navigation](M1-08.2-android-conversations-recovery.md) | Not started | M1-08.1, M1-07.3 | android | M1-05.2 |
| 25 | [M1-08.3 Android recovery and conversation tests](M1-08.3-android-recovery-tests.md) | Not started | M1-08.2, M1-07.3 | android | M1-05.2 |
| 26 | [M1-09.1 Android chat runner and ViewModel](M1-09.1-android-chat-runner.md) | Not started | M1-06.4, M1-08.1, M1-08.2 | android | M1-04.1 |
| 27 | [M1-09.2 Android chat screen and composer](M1-09.2-android-chat-screen.md) | Not started | M1-09.1 | android | M1-04.2, M1-04.3 |
| 28 | [M1-09.3 Android Markdown, code, and actions](M1-09.3-android-markdown-actions.md) | Not started | M1-09.2 | android | M1-04.4 |
| 29 | [M1-10.1 Browser CORS verification and browserCors update](M1-10.1-cors-verification.md) | Not started | M1-02.4, M1-06.4, M1-03.2, M1-07.2 | verification | none |
| 30 | [M1-10.2 Conformance, parity, verification, and exit gate](M1-10.2-conformance-exit-gate.md) | Not started | all prior tasks | verification | none |

## Parallel execution groups

- Group A: M1-01.1, then M1-01.2. Both block all client adapter work.
- Group B: M1-02.1 and M1-06.1 in parallel after M1-01.2.
- Group C: M1-02.2 and M1-02.3 in parallel; M1-06.2 and M1-06.3 in parallel; then M1-02.4 and M1-06.4 in parallel.
- Group D: Web M1-03.1 first, then M1-05.1, M1-03.2, and M1-03.3; Android M1-08.1 first, then M1-07.1, M1-07.2, and M1-07.3.
- Group E: Web chat M1-04.1 through M1-04.4 and M1-05.2; Android chat M1-08.2, M1-08.3, and M1-09.1 through M1-09.3. Keep web and Android streams separate.
- Group F: M1-10.1 can run once both adapters and onboarding paths exist. M1-10.2 is the final gate.

## Completion rule

The M1 milestone is complete only when every task is `Complete` or explicitly cancelled with an approved deferral, `docs/operations/M1/M1-verification.md` contains the exit-gate evidence, `contracts/parity.md` is truthful, and the milestone status table is updated.

## Task orchestration

The existing runner scripts accept a task folder:

```bash
scripts/next-task docs/operations/M1
scripts/next-task docs/operations/M1 --list
scripts/next-task docs/operations/M1 --include-in-progress
scripts/task-status get docs/operations/M1/M1-01.1-openrouter-expected-vectors.md
```

If the runner is pointed at this folder, keep the M0 layout used here. If the team switches to the milestone contract, mirror these files under `docs/tasks/M1/` rather than maintaining two task lists.
