# M3 Task Breakdown

- Source plan: [M3 Android Depth Implementation Plan](../M3-android-depth-implementation-plan.md)
- Milestone: [M3 Android depth](../../milestones/M3-android-depth.md)
- Authoritative spec: [IMPLEMENTATION.md](../../IMPLEMENTATION.md)
- Status: Not started
- Last updated: 2026-11-04
- Ordering: work through the `Order` column. A task may start only when every dependency is `Complete`.
- Scope: Android only. M2 provider, attachment, settings, search, and export/import behavior is a prerequisite, not M3 implementation work.

## How to use these tasks

1. Read the source plan section named in the task before editing code.
2. Read the spec sections listed in the task's `Read first` line.
3. Touch only the paths listed under `Owns`. If another path is required, stop and record the dependency in `Handoff notes` instead of silently expanding scope.
4. Do not start a task until its `Depends on` tasks are `Complete`. M3-00.1 is a hard gate: if M2 is incomplete, stop and report it rather than reimplementing M2 work.
5. Keep changes inside one task's area. If two tasks need the same file, follow the dependency order unless the task says they are parallel-safe.
6. Do not mark a task complete until every acceptance checkbox is true and every verification command passes.
7. Update this index and the task file status when work starts or completes.
8. Put design decisions, deviations, and device evidence in the task's `Handoff notes`; do not leave them only in chat or commit messages.
9. Never record API keys, prompts, conversation content, signing material, or personal data in task files, screenshots, logs, or CI artifacts.
10. Do not start M4 work from inside an M3 task.

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
| 1 | [M3-00.1 Preflight and M2 handoff verification](M3-00.1-preflight-m2-handoff.md) | Not started | none | preflight | none; blocks all |
| 2 | [M3-01.1 Real Android Keystore and ciphertext-only proof](M3-01.1-android-keystore-proof.md) | Not started | M3-00.1 | secrets | M3-01.2, M3-01.3, M3-01.4, M3-06.1 |
| 3 | [M3-01.2 Backup exclusion and key-restore evidence](M3-01.2-backup-exclusion-case-27.md) | Not started | M3-00.1, M3-01.1 | secrets | M3-01.3, M3-01.4 |
| 4 | [M3-01.3 Sensitive screens, key lifecycle, and logging audit](M3-01.3-flag-secure-key-lifecycle-logging.md) | Not started | M3-00.1, M3-01.1 | secrets | M3-01.2, M3-01.4 |
| 5 | [M3-01.4 Network security and certificate validation](M3-01.4-network-security.md) | Not started | M3-00.1 | secrets | M3-01.2, M3-01.3 |
| 6 | [M3-02.1 Share target and draft-preserving intent intake](M3-02.1-share-target.md) | Not started | M3-00.1, M3-01.4 | platform | M3-02.3, M3-02.4 |
| 7 | [M3-02.2 Deep link routing](M3-02.2-deep-links.md) | Not started | M3-02.1 | platform | M3-02.3, M3-02.4 |
| 8 | [M3-02.3 Provider documentation in Custom Tabs](M3-02.3-custom-tabs.md) | Not started | M3-00.1 | platform | M3-02.1, M3-02.2, M3-02.4 |
| 9 | [M3-02.4 Verify M2 attachment input and clipboard paths](M3-02.4-verify-attachment-input.md) | Not started | M3-00.1 | platform | M3-02.1, M3-02.3 |
| 10 | [M3-03.1 Predictive back ordering](M3-03.1-predictive-back.md) | Not started | M3-02.2 | navigation | M3-03.2 no; same shell files |
| 11 | [M3-03.2 Hardware keyboard shortcuts](M3-03.2-keyboard-shortcuts.md) | Not started | M3-03.1 | navigation | M3-03.3 no; same shell files |
| 12 | [M3-03.3 Adaptive tablet/foldable layout and insets](M3-03.3-adaptive-layout-insets.md) | Not started | M3-03.2 | navigation | M3-04.1 no; shell stability required |
| 13 | [M3-04.1 Configuration-change stream survival](M3-04.1-config-change-survival.md) | Not started | M3-03.3 | lifecycle | M3-04.2 no; lifecycle harness shared |
| 14 | [M3-04.2 Persistent drafts and navigation restore](M3-04.2-persistent-drafts-navigation.md) | Not started | M3-04.1, M3-02.1 | lifecycle | M3-04.3 no; lifecycle harness shared |
| 15 | [M3-04.3 Process-death streaming recovery and case 26](M3-04.3-process-death-case-26.md) | Not started | M3-04.2 | lifecycle | M3-04.4 no; startup files shared |
| 16 | [M3-04.4 Startup attachment cleanup and background behavior](M3-04.4-startup-cleanup-background.md) | Not started | M3-04.3 | lifecycle | none; startup files shared |
| 17 | [M3-05.1 Android test infrastructure and fake provider harness](M3-05.1-android-test-infrastructure.md) | Not started | M3-04.4 | tests | M3-06.1 |
| 18 | [M3-05.2 Core Compose UI flows](M3-05.2-core-compose-ui-flows.md) | Not started | M3-05.1 | tests | M3-05.3, M3-05.4, M3-05.5 |
| 19 | [M3-05.3 Roborazzi screenshot suite](M3-05.3-roborazzi-screenshots.md) | Not started | M3-05.2 | tests | M3-05.4, M3-05.5 |
| 20 | [M3-05.4 Data and platform edge-case regression tests](M3-05.4-edge-case-regressions.md) | Not started | M3-05.2 | tests | M3-05.3, M3-05.5 |
| 21 | [M3-05.5 Large-conversation bounded composition](M3-05.5-large-conversation.md) | Not started | M3-05.2 | tests | M3-05.3, M3-05.4 |
| 22 | [M3-06.1 Release minification, shrinking, and mapping](M3-06.1-release-minification.md) | Not started | M3-00.1 | release | M3-01.*, M3-02.*, M3-03.*, M3-04.*, M3-05.* |
| 23 | [M3-06.2 Signing and version policy](M3-06.2-signing-versioning.md) | Not started | M3-06.1 | release | none at sign-off; build config shared |
| 24 | [M3-06.3 Baseline profile and startup benchmark](M3-06.3-baseline-profile.md) | Not started | M3-06.1 | release | M3-06.2 |
| 25 | [M3-06.4 Release artifacts, CI, and developer targets](M3-06.4-release-artifacts-ci.md) | Not started | M3-06.1, M3-06.2, M3-06.3 | release | none; final release gate |
| 26 | [M3-07.1 Performance and size evidence](M3-07.1-performance-size-evidence.md) | Not started | M3-06.4, M3-05.5 | evidence | M3-07.2, M3-07.3 |
| 27 | [M3-07.2 Accessibility baseline and TalkBack](M3-07.2-accessibility.md) | Not started | M3-03.3, M3-05.3 | evidence | M3-07.1, M3-07.3 |
| 28 | [M3-07.3 Manual device matrix](M3-07.3-device-matrix.md) | Not started | M3-05.4, M3-06.4 | evidence | M3-07.2 |
| 29 | [M3-07.4 Parity, regressions, and exit evidence](M3-07.4-parity-exit-evidence.md) | Not started | M3-07.1, M3-07.2, M3-07.3 | evidence | none; final gate |

## Parallel execution groups

- Group A: M3-00.1 only. It blocks all implementation.
- Group B: after M3-00.1, run M3-01.1 and M3-06.1 in parallel. Then M3-01.2, M3-01.3, and M3-01.4 can run in parallel; serialize edits to `AndroidManifest.xml` between M3-01.3 and M3-01.4.
- Group C: after M3-01.4, run M3-02.1 and M3-02.3 in parallel. M3-02.4 can run once M2 attachment behavior is confirmed. M3-02.2 follows M3-02.1 because both edit the activity intent path.
- Group D: M3-03.1, M3-03.2, and M3-03.3 are sequential because they edit the same shell and layout files.
- Group E: M3-04.1, M3-04.2, M3-04.3, and M3-04.4 are sequential because they share the lifecycle harness and startup path.
- Group F: M3-05.1 first. Then M3-05.2. After M3-05.2, M3-05.3, M3-05.4, and M3-05.5 can run in parallel.
- Group G: M3-06.2 and M3-06.3 can run in parallel after M3-06.1. M3-06.4 follows both.
- Group H: after all implementation and test tasks, run M3-07.1, M3-07.2, and M3-07.3. M3-07.4 is the final gate.

## Completion rule

The M3 milestone is complete only when every task is `Complete` or explicitly cancelled with an approved deferral, `docs/operations/M3/M3-verification.md` contains the exit-gate evidence, `contracts/parity.md` is truthful, the manual device matrix has no release-blocking issue, and the milestone status table is updated.

## Task orchestration

The existing runner scripts accept a task folder:

```bash
scripts/next-task docs/operations/M3
scripts/next-task docs/operations/M3 --list
scripts/next-task docs/operations/M3 --include-in-progress
scripts/task-status get docs/operations/M3/M3-01.1-android-keystore-proof.md
```

Keep one task file per numbered row and update the status in both this index and the task file. If the team switches to `docs/tasks/M3/`, mirror these files rather than maintaining two task lists.
