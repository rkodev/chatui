# M3.6 Task Breakdown - Desktop view integration

- Source plan: [PLAN.md](PLAN.md)
- Predecessor: [M3.5 task breakdown](../M3.5/README.md)
- Milestone context: [M3.5 Rust desktop client](../../milestones/M3.5-rust-desktop.md)
- Authoritative spec: [IMPLEMENTATION.md](../../IMPLEMENTATION.md)
- Status: Not started
- Last updated: 2026-11-08
- Ordering: work through the `Order` column. A task may start only when every dependency is `Complete`.
- Scope: Rust desktop only. Replace the shell placeholders with embedded feature views backed by production SQLite, provider, and secret adapters.
- Do not modify `web/`, `android/`, or `contracts/`.

## Why this work exists

M3.5 shipped the shell, feature view models, and feature windows, but the
desktop binary composes only the shell. Every primary destination renders
placeholder copy, and no production adapter connects storage or providers to
the synchronous UI services. This milestone closes that integration gap.

## How to use these tasks

1. Read the source plan section named in the task and the files listed in
   `Read first` before editing code.
2. Touch only the paths listed under `Owns`. If another path is required, stop
   and record the dependency in `Handoff notes` instead of widening scope.
3. Do not start a task until every task under `Depends on` is `Complete`.
4. Do not modify `web/`, `android/`, or `contracts/`.
5. Do not add a second network destination, a new provider protocol, or a new
   secret backend.
6. Never record API keys, prompts, conversation content, signing material, or
   personal data in task files, tests, screenshots, logs, or CI artifacts.
7. Do not mark a task complete until every acceptance checkbox is true and every
   verification command passes or has an approved, recorded blocker.
8. Update this index and the task file status when work starts or completes.
9. Put decisions, deviations, and evidence in `Handoff notes`; do not leave them
   only in chat or commit messages.

## Status values

| Status | Meaning |
| --- | --- |
| Not started | No implementation work has begun |
| In progress | Work has begun; keep this index updated |
| Blocked | A dependency, decision, credential, runner, or OS environment prevents progress; record the blocker |
| Complete | Acceptance criteria and verification pass |
| Cancelled | Work was removed or replaced; record why |

## Ordered task list

| Order | Task | Status | Depends on | Area | Parallel-safe with |
| --- | --- | --- | --- | --- | --- |
| 1 | [M3.6-01.1 Embedding spike and decision](M3.6-01.1-embedding-spike.md) | Complete | none | foundation | yes, with M3.6-02.1 |
| 2 | [M3.6-02.1 SQLite runtime, secrets, and sync bridge](M3.6-02.1-sqlite-runtime-bridge.md) | Complete | none | adapters | yes, with M3.6-01.1 |
| 3 | [M3.6-02.2 Settings, provider, and model adapters](M3.6-02.2-settings-provider-model-adapters.md) | Complete | M3.6-02.1 | adapters | no; needs the runtime |
| 4 | [M3.6-02.3 Data service adapter](M3.6-02.3-data-service-adapter.md) | Not started | M3.6-02.1 | adapters | yes, with M3.6-02.2 and M3.6-02.4 |
| 5 | [M3.6-02.4 Onboarding adapters](M3.6-02.4-onboarding-adapters.md) | Not started | M3.6-02.1, M3.6-02.2 | adapters | yes, with M3.6-02.3 |
| 6 | [M3.6-03.1 Shell feature host](M3.6-03.1-shell-feature-host.md) | Not started | M3.6-01.1 | ui | yes, with M3.6-02.* |
| 7 | [M3.6-04.1 History view and controller](M3.6-04.1-history-view.md) | Not started | M3.6-02.1, M3.6-03.1 | ui | yes, with M3.6-05.1 |
| 8 | [M3.6-05.1 Chat view and controller](M3.6-05.1-chat-view.md) | Not started | M3.6-02.1, M3.6-03.1 | ui | yes, with M3.6-04.1 |
| 9 | [M3.6-06.1 Desktop composition](M3.6-06.1-desktop-composition.md) | Not started | M3.6-02.2, M3.6-02.3, M3.6-02.4, M3.6-03.1, M3.6-04.1, M3.6-05.1 | desktop | no; integration task |
| 10 | [M3.6-07.1 Verification and evidence](M3.6-07.1-verification.md) | Not started | M3.6-06.1 | evidence | none; final gate |

## Parallel execution groups

- Group A: `M3.6-01.1` and `M3.6-02.1` are independent and may run in parallel.
  `M3.6-01.1` gates all UI embedding work; `M3.6-02.1` gates the adapter work.
- Group B: after `M3.6-02.1`, run `M3.6-02.2`, `M3.6-02.3`, and `M3.6-02.4`.
  `M3.6-02.4` also needs `M3.6-02.2`.
- Group C: after `M3.6-01.1`, run `M3.6-03.1`. After `M3.6-03.1` and
  `M3.6-02.1`, run `M3.6-04.1` and `M3.6-05.1` in parallel.
- Group D: `M3.6-06.1` follows every adapter and UI task.
- Group E: `M3.6-07.1` is the final gate.

## Completion rule

M3.6 is complete only when every task is `Complete`, `verification.md` records
the commands and results, no primary destination renders placeholder copy, and
the desktop smoke exercises all five destinations.

## Task orchestration

```bash
scripts/next-task docs/operations/M3.6-desktop-view-integration
scripts/next-task docs/operations/M3.6-desktop-view-integration --list
scripts/next-task docs/operations/M3.6-desktop-view-integration --include-in-progress
scripts/task-status get docs/operations/M3.6-desktop-view-integration/M3.6-01.1-embedding-spike.md
```

Keep one task file per numbered row and update the status in both this index and
the task file. The task runner supports dotted milestone IDs such as `M3.6-01.1`.
