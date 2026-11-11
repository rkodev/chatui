# Android UX Task Breakdown

- Source plan: [Android UI/UX Polish Implementation Plan](../android-ux-implementation-plan.md)
- Source design review: [android-ux.md](../../android-ux.md)
- Authoritative spec: [IMPLEMENTATION.md](../../IMPLEMENTATION.md)
- Status: In progress
- Last updated: 2026-11-11
- Ordering: work through the `Order` column. A task may start only when every dependency is `Complete`.
- Scope: Android-only visual, interaction, motion, feedback, and perceived-performance polish. No new product features, provider protocols, backup/sync, or localization.

## How to use these tasks

1. Read the source plan section and the task file before editing code.
2. Read the files listed in the task's `Read first` line.
3. Touch only the paths listed under `Owns`. If another path is required, stop and record the dependency in `Handoff notes` instead of silently expanding scope.
4. Do not start a task until its `Depends on` tasks are `Complete`.
5. Preserve existing test tags and accessible names when replacing widgets. Update only assertions that read visible text.
6. Do not change streaming, storage, security, navigation, or transport behavior. If a behavior test fails, stop and investigate; do not rewrite the test to pass.
7. Do not mark a task complete until every acceptance checkbox is true and every verification command passes or has an approved, recorded blocker.
8. Update this index and the task file status when work starts or completes.
9. Put decisions, deviations, measurements, and evidence in the task's `Handoff notes`; do not leave them only in chat or commit messages.
10. Never record API keys, prompts, conversation content, signing material, or personal data in task files, screenshots, logs, or CI artifacts.

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
| 1 | [UX-00 Preflight, decisions, and baseline capture](UX-00-preflight-decisions-baseline.md) | Complete | none | preflight | none; blocks all |
| 2 | [UX-01 Design-system foundation](UX-01-design-system-foundation.md) | Complete | UX-00 | foundation | none; blocks screens |
| 3 | [UX-02 Shell and navigation](UX-02-shell-and-navigation.md) | Complete | UX-01 | shell | UX-06, UX-07 |
| 4 | [UX-03 Chat experience](UX-03-chat-experience.md) | Complete | UX-01, UX-02 | chat | UX-04, UX-05 |
| 5 | [UX-04 Conversations](UX-04-conversations.md) | Complete | UX-01, UX-02 | conversations | UX-03, UX-05 |
| 6 | [UX-05 Onboarding](UX-05-onboarding.md) | Complete | UX-01 | onboarding | UX-03, UX-04 |
| 7 | [UX-06 Providers and model picker](UX-06-providers-and-model-picker.md) | Complete | UX-01 | providers | UX-02, UX-07 |
| 8 | [UX-07 Settings and data](UX-07-settings-and-data.md) | Complete | UX-01 | settings | UX-02, UX-06 |
| 9 | [UX-08 States and feedback adoption](UX-08-states-and-feedback.md) | Complete | UX-01, UX-03, UX-04, UX-05, UX-06, UX-07 | cross-cutting | none; touches all screens |
| 10 | [UX-09 Motion and haptics tuning](UX-09-motion-and-haptics.md) | Complete | UX-02, UX-03, UX-04, UX-05, UX-06, UX-07 | motion | UX-10 |
| 11 | [UX-10 Accessibility and dynamic type audit](UX-10-accessibility-dynamic-type.md) | Complete | UX-02, UX-03, UX-04, UX-05, UX-06, UX-07 | accessibility | UX-09 |
| 12 | [UX-11 Performance and baseline profile](UX-11-performance-baseline-profile.md) | Complete | UX-03, UX-09 | performance | UX-12 |
| 13 | [UX-12 Screenshot goldens, device review, and exit evidence](UX-12-screenshots-device-evidence.md) | Not started | UX-00 – UX-11 | evidence | none; final gate |

## Parallel execution groups

- Group A: `UX-00` first. It records decisions and the baseline. It blocks all implementation.
- Group B: `UX-01` alone. It changes shared tokens and primitives that every screen depends on.
- Group C: after `UX-01`, run `UX-02`, `UX-06`, and `UX-07` in parallel. `UX-02` owns shell files; `UX-06` and `UX-07` own feature files.
- Group D: after `UX-02`, run `UX-03`, `UX-04`, and `UX-05` in parallel. They touch different feature modules.
- Group E: `UX-08` follows all screen tasks because it adopts shared states across them.
- Group F: `UX-09` and `UX-10` can run in parallel after the screen tasks. Coordinate on shared files.
- Group G: `UX-11` follows `UX-03` and `UX-09` so measurements reflect the final chat and motion.
- Group H: `UX-12` is the final gate and cannot start until every other task is `Complete`.

## Completion rule

The program is complete only when every task is `Complete` or explicitly cancelled with an approved deferral, all acceptance criteria in the source plan are met, Roborazzi goldens are reviewed, the device matrix is recorded, and no behavior regression remains open.

## Task orchestration

```text
Foundation
  UX-00 -> UX-01

Screens (after UX-01)
  UX-02, UX-06, UX-07 in parallel
  UX-03, UX-04, UX-05 in parallel after UX-02

Cross-cutting
  UX-08 -> UX-09 / UX-10 -> UX-11 -> UX-12
```
