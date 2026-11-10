# M4 Task Breakdown - Polish and release

- Source plan: [M4 Polish and release](../../milestones/M4-polish-and-release.md)
- Authoritative spec: [IMPLEMENTATION.md](../../IMPLEMENTATION.md)
- Status: In progress - `M4-01.1`, `M4-02.1`, `M4-03.1`, `M4-04.1`, and `M4-05.1` are complete; `M4-06.1` is ready to start
- Last updated: 2026-11-10
- Ordering: work through the `Order` column. A task may start only when every dependency is `Complete` and the milestone entry criteria are met.
- Scope: verification, polish, packaging, deployment, and evidence for the phase 1 release. M4 does **not** add new product features.
- Platform rule: web and Android are required. Rust desktop work is conditional on the release decision recorded in `M4-00.1`.

## Entry criteria (milestone-level)

These are external to the M4 task graph and must be true before `M4-00.1` starts:

- M1 and M2 exit gates are met on web and Android.
- M3 exit gate is met for Android release configuration and device evidence.
- If the Rust desktop client is shipping, its M3.5 exit gate is met and spec section 10.12 passes.
- `contracts/parity.md` is current and every critical case has an owner.

If an entry criterion is not met, record it as a blocker in `M4-00.1`; do not silently start implementation.

## How to use these tasks

1. Read the milestone section and the task file before editing code.
2. Read the spec sections listed in the task's `Read first` line.
3. Touch only the paths listed under `Owns`. If another path is required, stop and record the dependency in `Handoff notes` instead of widening scope.
4. Do not start a task until its `Depends on` tasks are `Complete`.
5. Do not add product features to make a release check pass. Any substantive defect found during audit becomes a follow-up task, not hidden scope.
6. Do not mark a task complete until every acceptance checkbox is true and every verification command passes or has an approved, recorded blocker.
7. Update this index and the task file status when work starts or completes.
8. Put decisions, deviations, measurements, and evidence in the task's `Handoff notes`; do not leave them only in chat or commit messages.
9. Never record API keys, prompts, conversation content, signing material, or personal data in task files, screenshots, logs, or CI artifacts.
10. Do not start M5 (backup/sync) or M6 (managed hosting) work from inside an M4 task.

## Status values

| Status | Meaning |
| --- | --- |
| Not started | No work has begun |
| In progress | Work has begun; keep this index updated |
| Blocked | A dependency, decision, credential, device, or runner prevents progress; record the blocker |
| Complete | Acceptance criteria and verification pass |
| Cancelled | Work was removed or replaced; record why |

## Ordered task list

| Order | Task | Status | Depends on | Area | Parallel-safe with |
| --- | --- | --- | --- | --- | --- |
| 1 | [M4-00.1 Release scope, decisions, and baseline preflight](M4-00.1-preflight-baseline.md) | Complete | none | preflight | none; blocks all |
| 2 | [M4-01.1 Accessibility and UX polish across enabled clients](M4-01.1-accessibility-ux-polish.md) | Complete | M4-00.1 | accessibility | M4-02.1 |
| 3 | [M4-02.1 Web PWA, service worker, offline, and update flow](M4-02.1-web-pwa-offline.md) | Complete | M4-00.1 | pwa | M4-01.1 |
| 4 | [M4-03.1 Performance optimization and budget measurement](M4-03.1-performance-budgets.md) | Complete | M4-01.1, M4-02.1 | performance | M4-04.1 |
| 5 | [M4-04.1 Web deployment artifacts, container, headers, and recipes](M4-04.1-web-deployment.md) | Complete | M4-02.1 | deployment | M4-03.1 |
| 6 | [M4-05.1 CI/CD release pipelines, signing, artifacts, versioning, and notes](M4-05.1-cicd-release.md) | Complete | M4-03.1, M4-04.1 | release-engineering | none; needs measurement and deploy targets |
| 7 | [M4-06.1 Parity audit and phase 1 release gate](M4-06.1-parity-release-gate.md) | Not started | M4-01.1, M4-02.1, M4-03.1, M4-04.1, M4-05.1 | evidence | none; final gate |

## Conditional Rust work

Tasks `M4-01.1`, `M4-03.1`, `M4-05.1`, and `M4-06.1` include Rust desktop items marked **only if shipping**. `M4-00.1` records one of:

- `Rust desktop: shipping` - all conditional items are required.
- `Rust desktop: not shipping` - Rust items are skipped, and every surface that mentions Rust must say `post-preview / parallel`, including `contracts/parity.md` and the release notes.

Do not leave Rust status implicit or partially covered.

## Parallel execution groups

- Group A: `M4-00.1` first. It records the baseline, Rust decision, and release blockers.
- Group B: after `M4-00.1`, run `M4-01.1` and `M4-02.1` in parallel. They touch different areas, but both may edit `web/src/app` routing; coordinate before editing the same file.
- Group C: after `M4-01.1` and `M4-02.1`, run `M4-03.1` and `M4-04.1` in parallel.
- Group D: `M4-05.1` follows `M4-03.1` and `M4-04.1` so workflows package measured, deployable artifacts.
- Group E: `M4-06.1` is the final gate and cannot start until every other task is `Complete`.

## Completion rule

M4 is complete only when every task is `Complete` or explicitly cancelled with an approved deferral, `M4-verification.md` records the exit-gate evidence, `contracts/parity.md` is truthful, release artifacts exist and are documented, the definition of done in spec section 21.7 is met or explicitly exempted, and the release blocker list is empty.

## Task orchestration

```bash
scripts/next-task docs/operations/M4
scripts/next-task docs/operations/M4 --list
scripts/next-task docs/operations/M4 --include-in-progress
scripts/task-status get docs/operations/M4/M4-00.1-preflight-baseline.md
```

Keep one task file per numbered row and update the status in both this index and the task file. Do not add tasks at the `docs/operations` root.
