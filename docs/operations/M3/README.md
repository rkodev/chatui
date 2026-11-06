# M3 Task Breakdown

- Source plan: [M3 Android Depth Implementation Plan](../M3-android-depth-implementation-plan.md)
- Milestone: [M3 Android depth](../../milestones/M3-android-depth.md)
- Authoritative spec: [IMPLEMENTATION.md](../../IMPLEMENTATION.md)
- Status: Not started
- Last updated: 2026-11-06
- Ordering: work through the `Order` column. A task may start only when every dependency is `Complete`.
- Scope: Android only. M2 provider, attachment, settings, search, export/import, and data behavior is the baseline, not M3 implementation work.

## How to use these tasks

1. Read the source plan section and the task file before editing code.
2. Read the spec sections listed in the task's `Read first` line.
3. Touch only the paths listed under `Owns`. If another path is required, stop and record the dependency in `Handoff notes` instead of silently expanding scope.
4. Do not start a task until its `Depends on` tasks are `Complete`.
5. Keep changes inside one task's area. If two tasks need the same file, follow the dependency order unless the task says they are parallel-safe.
6. Do not mark a task complete until every acceptance checkbox is true and every verification command passes.
7. Update this index and the task file status when work starts or completes.
8. Put design decisions, deviations, and evidence in the task's `Handoff notes`; do not leave them only in chat or commit messages.
9. Never record API keys, prompts, conversation content, signing material, or personal data in task files, screenshots, logs, or CI artifacts.
10. Do not start M4 work from inside an M3 task.

## Status values

| Status | Meaning |
| --- | --- |
| Not started | No implementation work has begun |
| In progress | Work has begun; keep this index updated |
| Blocked | A dependency, decision, device, or credential prevents progress; record the blocker |
| Complete | Acceptance criteria and verification pass |
| Cancelled | Work was removed or replaced; record why |

## Ordered task list

| Order | Task | Status | Depends on | Area | Parallel-safe with |
| --- | --- | --- | --- | --- | --- |
| 1 | [M3-00.1 Preflight and baseline verification](M3-00.1-preflight-baseline.md) | Complete | none | preflight | none; blocks all |
| 2 | [M3-00.2 Confirm M3 scope decisions](M3-00.2-confirm-decisions.md) | Complete | M3-00.1 | preflight | none; blocks implementation choices |
| 3 | [M3-05.1 Android test infrastructure and fake provider harness](M3-05.1-test-infrastructure.md) | Complete | M3-00.2 | tests | no; shares app build file with M3-06.1 |
| 4 | [M3-06.1 Release minification, shrinking, mapping, and keep rules](M3-06.1-release-minification.md) | Complete | M3-00.1 | release | M3-01.*, M3-03.* |
| 5 | [M3-01.1 Real Android Keystore and ciphertext-only proof](M3-01.1-keystore-proof.md) | Complete | M3-00.2, M3-05.1 | secrets | M3-01.4, M3-02.3 |
| 6 | [M3-01.2 Backup exclusion and case 27 evidence](M3-01.2-backup-exclusion.md) | Complete | M3-01.1 | secrets | M3-01.3, M3-01.4 |
| 7 | [M3-01.3 FLAG_SECURE, key lifecycle, and logging audit](M3-01.3-flag-secure-key-lifecycle.md) | Complete | M3-01.1 | secrets | M3-01.2, M3-01.4 |
| 8 | [M3-01.4 Network security config and local cleartext policy](M3-01.4-network-security.md) | Not started | M3-00.2 | secrets | M3-01.1, M3-01.2, M3-01.3 |
| 9 | [M3-01.5 Cleartext warning and certificate-validation guard](M3-01.5-cleartext-warning.md) | Not started | M3-01.4 | secrets | M3-01.2, M3-01.3 |
| 10 | [M3-03.1 Adaptive shell with permanent sidebar and drawer](M3-03.1-adaptive-shell.md) | Not started | M3-00.2 | navigation | M3-01.*, M3-06.1 |
| 11 | [M3-03.2 Window insets, IME, and edge-to-edge](M3-03.2-insets-ime.md) | Not started | M3-03.1 | navigation | M3-01.* |
| 12 | [M3-03.3 Predictive back ordering](M3-03.3-predictive-back.md) | Not started | M3-03.1, M3-03.2 | navigation | none; shell files shared |
| 13 | [M3-03.4 Hardware keyboard shortcuts](M3-03.4-keyboard-shortcuts.md) | Not started | M3-03.3 | navigation | none; shell files shared |
| 14 | [M3-02.1 Share target and draft-preserving intent intake](M3-02.1-share-target.md) | Not started | M3-03.4 | platform | M3-02.3 |
| 15 | [M3-02.2 Deep link routing](M3-02.2-deep-links.md) | Not started | M3-02.1 | platform | M3-02.3 |
| 16 | [M3-02.3 Provider documentation in Custom Tabs](M3-02.3-custom-tabs.md) | Not started | M3-00.2 | platform | M3-02.1, M3-02.2, M3-04.* |
| 17 | [M3-02.4 Outbound conversation share](M3-02.4-outbound-share.md) | Not started | M3-00.2, M3-02.1 | platform | M3-02.2, M3-02.3 |
| 18 | [M3-04.1 Configuration-change stream survival](M3-04.1-config-change.md) | Not started | M3-03.4, M3-05.1 | lifecycle | M3-02.* |
| 19 | [M3-04.2 Persistent drafts](M3-04.2-persistent-drafts.md) | Not started | M3-04.1 | lifecycle | M3-02.3 |
| 20 | [M3-04.3 Navigation and last-active restore](M3-04.3-navigation-restore.md) | Not started | M3-04.2, M3-02.2 | lifecycle | none; startup/navigation files shared |
| 21 | [M3-04.4 Process-death streaming recovery and case 26](M3-04.4-process-death-case-26.md) | Not started | M3-04.3 | lifecycle | none; startup files shared |
| 22 | [M3-04.5 Startup orphan-attachment cleanup](M3-04.5-startup-attachment-cleanup.md) | Not started | M3-04.4 | lifecycle | none; startup files shared |
| 23 | [M3-04.6 Background behavior documentation](M3-04.6-background-behavior.md) | Not started | M3-04.5 | lifecycle | M3-02.3, M3-05.1 |
| 24 | [M3-05.2 Core Compose UI flows](M3-05.2-core-compose-flows.md) | Not started | M3-04.6, M3-03.4 | tests | M3-06.2, M3-06.3 |
| 25 | [M3-05.3 Roborazzi screenshot suite](M3-05.3-roborazzi-screenshots.md) | Not started | M3-05.2 | tests | M3-05.4, M3-05.5 |
| 26 | [M3-05.4 Platform edge-case regression tests](M3-05.4-edge-case-regressions.md) | Not started | M3-05.2, M3-02.2, M3-03.3 | tests | M3-05.3, M3-05.5 |
| 27 | [M3-05.5 Large-conversation bounded composition](M3-05.5-large-conversation.md) | Not started | M3-05.2 | tests | M3-05.3, M3-05.4 |
| 28 | [M3-06.2 Signing and version policy](M3-06.2-signing-versioning.md) | Not started | M3-06.1, M3-00.2 | release | M3-05.* |
| 29 | [M3-06.3 Baseline profile and startup benchmark](M3-06.3-baseline-profile.md) | Not started | M3-06.1, M3-03.4, M3-04.5 | release | no; shares app build file with M3-06.2 |
| 30 | [M3-06.4 Release CI, artifacts, and developer targets](M3-06.4-release-ci-artifacts.md) | Not started | M3-06.1, M3-06.2, M3-06.3 | release | M3-05.* |
| 31 | [M3-06.5 Image decode, Coil sizing, and release size measurement](M3-06.5-image-decode-size.md) | Not started | M3-06.1 | release | M3-05.* |
| 32 | [M3-07.1 Performance and size evidence](M3-07.1-performance-size-evidence.md) | Not started | M3-06.4, M3-06.5, M3-05.5 | evidence | M3-07.2, M3-07.3, M3-07.4 |
| 33 | [M3-07.2 Accessibility baseline and TalkBack](M3-07.2-accessibility.md) | Not started | M3-05.3, M3-03.4 | evidence | M3-07.1, M3-07.3, M3-07.4 |
| 34 | [M3-07.3 Manual device matrix](M3-07.3-device-matrix.md) | Not started | M3-05.4, M3-06.4, M3-02.1, M3-02.2 | evidence | M3-07.1, M3-07.2, M3-07.4 |
| 35 | [M3-07.4 M2 carryover closeout and platform input checks](M3-07.4-m2-carryover-closeout.md) | Not started | M3-01.5, M3-03.4, M3-05.4 | evidence | M3-07.1, M3-07.2, M3-07.3 |
| 36 | [M3-07.5 Parity, regressions, and exit evidence](M3-07.5-parity-exit-evidence.md) | Not started | M3-01.1, M3-01.2, M3-01.3, M3-01.4, M3-01.5, M3-02.3, M3-07.1, M3-07.2, M3-07.3, M3-07.4 | evidence | none; final gate |

## Parallel execution groups

- Group A: M3-00.1, then M3-00.2. Both block implementation choices.
- Group B: after M3-00.2, run M3-05.1 and M3-06.1 in parallel. M3-01.1 and M3-01.4 can then start; M3-01.2 and M3-01.3 follow M3-01.1.
- Group C: M3-03.1, M3-03.2, M3-03.3, and M3-03.4 are sequential because they edit the same shell and layout files.
- Group D: after M3-03.4, run M3-02.1. M3-02.3 is independent. M3-02.2 follows M3-02.1; M3-02.4 follows both M3-02.1 and the scope decision.
- Group E: M3-04.1 through M3-04.6 are sequential because they share lifecycle, startup, and navigation files.
- Group F: M3-05.2 follows all feature work. M3-05.3, M3-05.4, and M3-05.5 can then run in parallel.
- Group G: M3-06.2 and M3-06.3 can run in parallel after M3-06.1; M3-06.4 follows both. M3-06.5 follows M3-06.1.
- Group H: after all implementation, test, and release tasks, run M3-07.1, M3-07.2, M3-07.3, and M3-07.4 in parallel. M3-07.5 is the final gate.

## Completion rule

The M3 milestone is complete only when every task is `Complete` or explicitly cancelled with an approved deferral, `docs/operations/M3/M3-verification.md` contains the exit-gate evidence, `contracts/parity.md` is truthful, the manual device matrix has no release-blocking issue, and the milestone status table is updated.

## Task orchestration

```bash
scripts/next-task docs/operations/M3
scripts/next-task docs/operations/M3 --list
scripts/next-task docs/operations/M3 --include-in-progress
scripts/task-status get docs/operations/M3/M3-01.1-keystore-proof.md
```

Keep one task file per numbered row and update the status in both this index and the task file. Do not add tasks at the `docs/operations` root.
