# M0 Task Breakdown

- Source plan: [M0 Foundations Implementation Plan](../M0-foundations-implementation-plan.md)
- Milestone: [M0 Foundations](../../milestones/M0-foundations.md)
- Status: Not started
- Last updated: 2026-11-02
- Ordering: complete tasks in the numbered order unless the dependency column says otherwise.

## How to use these tasks

1. Read the source plan section for the parent task before starting.
2. Read the spec sections listed in the task.
3. Touch only the paths listed under `Owns`.
4. Do not mark a task complete until every acceptance criterion and verification command passes.
5. Update both this index and the task file status when work starts or completes.
6. If a task exposes a design ambiguity, write the decision and its consequences in the task's handoff notes instead of silently expanding scope.

## Status values

| Status | Meaning |
| --- | --- |
| Not started | No implementation work has begun |
| In progress | Work has begun; keep the index updated |
| Blocked | A dependency or decision prevents progress; record the blocker |
| Complete | Acceptance criteria and verification pass |
| Cancelled | Work was removed or replaced; record why |

## Ordered task list

| Order | Task | Status | Depends on | Area | Parallel-safe with |
| --- | --- | --- | --- | --- | --- |
| 1 | [M0-01.1 Provider presets and preset schema](M0-01.1-provider-presets-and-schema.md) | Complete | none | contracts | M0-01.4 |
| 2 | [M0-01.2 Model, settings, export, and backup schemas](M0-01.2-remaining-schemas.md) | Complete | M0-01.1 | contracts | M0-01.4 |
| 3 | [M0-01.3 Stream, catalog, and error fixtures](M0-01.3-fixtures.md) | Complete | M0-01.1, M0-01.2 | contracts | M0-01.4 |
| 4 | [M0-01.4 Parity checklist](M0-01.4-parity.md) | Complete | none | contracts | M0-01.1 to M0-01.3 |
| 5 | [M0-01.5 Contract validator and tooling](M0-01.5-contract-validator.md) | Complete | M0-01.1 to M0-01.4 | contracts | none |
| 6 | [M0-02.1 Web scaffold and toolchain](M0-02.1-web-scaffold.md) | Complete | M0-01.5 | web | M0-04.1 |
| 7 | [M0-02.2 Runtime config and domain model](M0-02.2-web-config-domain.md) | Complete | M0-02.1 | web | M0-04.2 |
| 8 | [M0-02.3 IndexedDB schema, migrations, repositories](M0-02.3-web-indexeddb.md) | Complete | M0-02.2 | web | M0-04.3 |
| 9 | [M0-02.4 OPFS attachment store and fallback](M0-02.4-web-opfs.md) | Complete | M0-02.3 | web | M0-04.4 |
| 10 | [M0-02.5 Secret store, crypto, and redaction](M0-02.5-web-secrets.md) | Complete | M0-02.3 | web | M0-04.4 |
| 11 | [M0-03.1 Router and shell state](M0-03.1-web-router-shell.md) | Complete | M0-02.5 | web | M0-03.2 |
| 12 | [M0-03.2 Design tokens, themes, and accessibility modes](M0-03.2-web-design-tokens.md) | Complete | M0-02.5 | web | M0-03.1 |
| 13 | [M0-03.3 Shared primitives, shell tests, web verification](M0-03.3-web-primitives-verification.md) | Complete | M0-03.1, M0-03.2 | web | M0-05.1 |
| 14 | [M0-04.1 Gradle project, wrapper, catalog, module graph](M0-04.1-android-gradle.md) | Not started | M0-01.5 | android | M0-02.1 |
| 15 | [M0-04.2 Core model, common utilities, network interfaces](M0-04.2-android-core.md) | Not started | M0-04.1 | android | M0-02.2 |
| 16 | [M0-04.3 Room schema, DAOs, FTS, migrations](M0-04.3-android-room.md) | Not started | M0-04.2 | android | M0-02.3 |
| 17 | [M0-04.4 DataStore, Tink secret storage, backup exclusion](M0-04.4-android-datastore-secrets.md) | Not started | M0-04.3 | android | M0-02.4, M0-02.5 |
| 18 | [M0-05.1 Android design system and theme](M0-05.1-android-designsystem.md) | Not started | M0-04.4 | android | M0-03.3 |
| 19 | [M0-05.2 App shell, navigation, Hilt, Android verification](M0-05.2-android-app-shell.md) | Not started | M0-05.1 | android | M0-03.3 |
| 20 | [M0-06.1 Contracts and web workflows](M0-06.1-contracts-web-ci.md) | Not started | M0-01.5, M0-03.3 | ci | M0-06.2 |
| 21 | [M0-06.2 Android workflow](M0-06.2-android-ci.md) | Not started | M0-05.2 | ci | M0-06.1 |
| 22 | [M0-06.3 CI guardrails and final M0 verification](M0-06.3-final-verification.md) | Not started | M0-06.1, M0-06.2, and all prior tasks | ci | none |

## Parallel execution groups

- Group A: M0-01.1, M0-01.4.
- Group B: M0-01.2, M0-01.3 after M0-01.1.
- Group C: M0-02.1 through M0-02.5 and M0-04.1 through M0-04.4 may proceed as two independent chains after M0-01.5.
- Group D: M0-03.1 and M0-03.2 may overlap; M0-03.3 follows both.
- Group E: M0-05.1 then M0-05.2; M0-06.1 and M0-06.2 may be drafted in parallel once their client gates pass.
- Group F: M0-06.3 is the final gate and must not start until every other task is complete.

## Completion rule

The M0 milestone is complete only when M0-06.3 records exit-gate evidence, `contracts/parity.md` is truthful, no provider calls or chat features exist, and the milestone status tables are updated.

## Task orchestration

The runner executes one task per fresh opencode session. It never reuses a session across tasks.

Select the next task:

```bash
scripts/next-task docs/operations/M0
scripts/next-task docs/operations/M0 --list
scripts/next-task docs/operations/M0 --include-in-progress
```

`next-task` prints only the selected task path. It exits `0` on success, `1` when no task is eligible, and `2` for bad input. `--list` shows every parsed task with order, status, eligibility, task ID, and expanded dependencies.

Run a batch:

```bash
scripts/run-tasks docs/operations/M0
MAX_TASKS=3 scripts/run-tasks docs/operations/M0
OPENCODE_AUTO=0 scripts/run-tasks docs/operations/M0
INCLUDE_IN_PROGRESS=1 scripts/run-tasks docs/operations/M0
```

- `MAX_TASKS=0` means unlimited attempts.
- `OPENCODE_AUTO=0` disables `--auto`. Without auto-approval, a batch can stall on permission prompts and is not suitable for unattended runs.
- `INCLUDE_IN_PROGRESS=1` lets the selector resume `In progress` tasks.
- `run-tasks` passes `--auto` before the message argument by default and stops when the selector has no eligible task, when the same task is selected twice in a row, or when `MAX_TASKS` is reached.
- After each session, `run-tasks` reads the task status with `scripts/task-status`. It fails with a non-zero exit if the task is still `Not started` or `In progress`, and mirrors a `Complete` status into the task folder's `README.md` index row.
- A selector exit code of `2` is propagated as `2`.

Inspect or sync a task status directly:

```bash
scripts/task-status get docs/operations/M0/M0-01.5-contract-validator.md
scripts/task-status sync-index docs/operations/M0 docs/operations/M0/M0-01.5-contract-validator.md
```

Run exactly one task through the command:

```text
/run-task docs/operations/M0
/run-task docs/operations/M0/M0-01.5-contract-validator.md
```

The command delegates to the `task-runner` agent, which reads one task file, follows its `Implementation steps`, obeys its `Out of scope` section, runs its verification commands, updates the task status and this index, and makes one commit. A folder argument uses `scripts/next-task`; a file argument is used directly.

### Resuming and blockers

- To resume an `In progress` task in a batch, use `INCLUDE_IN_PROGRESS=1 scripts/run-tasks docs/operations/M0` or select it directly with `scripts/next-task docs/operations/M0 --include-in-progress`.
- To resume through the command, pass the task file directly, for example `/run-task docs/operations/M0/M0-01.5-contract-validator.md`.
- `Blocked` tasks are never selected automatically. Resolve the decision, update the task's `## Blockers` notes and status, then rerun the selector.
- If a verification command is denied, the agent must report the denial rather than retrying through a shell wrapper.

Batch runs are unattended: the agent does not ask questions. Explicit deny rules in `.opencode/agent/task-runner.md` still apply, even with `--auto`. The agent prompt and model are kept stable across tasks so provider prefix caching can work; task-specific text is passed only in the user message.

Runner tests:

```bash
python3 -m unittest discover -s scripts/tests -v
```
