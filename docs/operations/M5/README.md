# M5 Task Breakdown - Backup and sync

- Source plan: [M5 Backup and Sync Implementation Plan](../M5-backup-and-sync-implementation-plan.md)
- Milestone: [M5 Backup and sync](../../milestones/M5-backup-and-sync.md)
- Authoritative spec: [IMPLEMENTATION.md](../../IMPLEMENTATION.md) sections 6.2, 12, 13, 17 (M5), 21.5
- Current parity: [contracts/parity.md](../../../contracts/parity.md)
- Status: In progress - phase 2 authorized 2026-11-11
- Last updated: 2026-11-11
- Ordering: work through the `Order` column. A task may start only when every dependency is `Complete`.
- Scope: encrypted, user-owned backup and sync. Web, Android, and Rust desktop are required (`M5-decisions.md` `DEC-M5-03`).
- Platform rule: one task owns one client area. Do not span web, Android, and Rust in a single task.

## Entry gate

Do not start any task until all of the following are true:

- [x] M4 exit gate is met and phase 1 is released (`docs/operations/M4/M4-verification.md`, 2026-11-10).
- [x] The user explicitly confirms phase 2 scope and authorizes M5 work (`M5-decisions.md` `DEC-M5-01`, 2026-11-11).
- [x] `M5-00.1` is `Complete` and freezes the envelope, manifest, and test vectors.
- [x] Rust release status is re-confirmed. Current decision: shipping and required (`M5-decisions.md` `DEC-M5-03`).

The phase 2 gate is open. Start `M5-01.1` next.

## How to use these tasks

1. Read the source plan section named in the task and the spec sections listed in `Read first` before editing code.
2. Touch only the paths listed under `Owns`. If another path is required, stop and record the dependency in `Handoff notes` instead of widening scope.
3. Do not start a task until every task under `Depends on` is `Complete`.
4. Do not modify `contracts/` from a client task. Contract changes belong to `M5-01.1`.
5. Do not add a plaintext upload path. Every object written to storage must be encrypted.
6. Do not record API keys, passphrases, prompts, conversation content, or personal data in task files, tests, logs, or fixtures.
7. Do not mark a task complete until every acceptance checkbox is true and every verification command passes or has an approved, recorded blocker.
8. Update this index and the task file status when work starts or completes.
9. Put decisions, deviations, and evidence in `Handoff notes`; do not leave them only in chat or commit messages.
10. Rust tasks are conditional. If Rust is not shipping, mark them `Cancelled` with the reason and keep the web/Android gate independent.

## Status values

| Status | Meaning |
| --- | --- |
| Not started | No implementation work has begun |
| In progress | Work has begun; keep this index updated |
| Blocked | A dependency, decision, credential, device, or runner prevents progress; record the blocker |
| Complete | Acceptance criteria and verification pass |
| Cancelled | Work was removed or replaced; record why |

## Ordered task list

| Order | Task | Status | Depends on | Area | Parallel-safe with |
| --- | --- | --- | --- | --- | --- |
| 1 | [M5-00.1 Preflight, decisions, and cross-client test vectors](M5-00.1-preflight-decisions-vectors.md) | Complete | none | preflight | none; blocks all |
| 2 | [M5-01.1 Contracts: envelope, manifest body, conversation payload, fixtures](M5-01.1-contracts-envelope-manifest.md) | Complete | M5-00.1 | contracts | none; blocks client codecs |
| 3 | [M5-01.2 Web envelope codec and crypto tests](M5-01.2-web-envelope-codec.md) | Complete | M5-01.1 | web crypto | M5-01.3, M5-01.4 |
| 4 | [M5-01.3 Android envelope codec and crypto tests](M5-01.3-android-envelope-codec.md) | Complete | M5-01.1 | android crypto | M5-01.2, M5-01.4 |
| 5 | [M5-01.4 Rust envelope codec and crypto tests](M5-01.4-rust-envelope-codec.md) | Complete | M5-01.1 | rust crypto | M5-01.2, M5-01.3 |
| 6 | [M5-02.1 Web manifest, storage keys, and DB v2 migration](M5-02.1-web-manifest-storage-keys-migration.md) | Complete | M5-01.2 | web data model | M5-02.2, M5-02.3 |
| 7 | [M5-02.2 Android manifest, storage keys, and Room migration](M5-02.2-android-manifest-storage-keys-migration.md) | Complete | M5-01.3 | android data model | M5-02.1, M5-02.3 |
| 8 | [M5-02.3 Rust manifest, storage keys, and SQLite migration](M5-02.3-rust-manifest-storage-keys-migration.md) | Complete | M5-01.4 | rust data model | M5-02.1, M5-02.2 |
| 9 | [M5-03.1 Web S3-compatible adapter, conditional writes, and CORS docs](M5-03.1-web-s3-adapter.md) | Complete | M5-02.1 | web backend | M5-03.2, M5-03.3 |
| 10 | [M5-03.2 Android S3-compatible adapter and conditional writes](M5-03.2-android-s3-adapter.md) | Complete | M5-02.2 | android backend | M5-03.1, M5-03.3 |
| 11 | [M5-03.3 Rust S3-compatible adapter and conditional writes](M5-03.3-rust-s3-adapter.md) | Complete | M5-02.3 | rust backend | M5-03.1, M5-03.2 |
| 12 | [M5-04.1 Web sync engine, merge, and tombstones](M5-04.1-web-sync-engine.md) | Complete | M5-03.1 | web sync | M5-04.2, M5-04.3 |
| 13 | [M5-04.2 Android sync engine, merge, and tombstones](M5-04.2-android-sync-engine.md) | Not started | M5-03.2 | android sync | M5-04.1, M5-04.3 |
| 14 | [M5-04.3 Rust sync engine, merge, and tombstones](M5-04.3-rust-sync-engine.md) | Complete | M5-03.3 | rust sync | M5-04.1, M5-04.2 |
| 15 | [M5-05.1 Web backup/restore UI and settings](M5-05.1-web-backup-ui.md) | Complete | M5-04.1 | web UI | M5-05.2, M5-05.3 |
| 16 | [M5-05.2 Android backup/restore UI and settings](M5-05.2-android-backup-ui.md) | Not started | M5-04.2 | android UI | M5-05.1, M5-05.3 |
| 17 | [M5-05.3 Rust backup/restore UI and settings](M5-05.3-rust-backup-ui.md) | Not started | M5-04.3 | rust UI | M5-05.1, M5-05.2 |
| 18 | [M5-06.1 Android WorkManager scheduling](M5-06.1-android-workmanager.md) | Not started | M5-05.2 | android scheduling | M5-06.2, M5-06.3 |
| 19 | [M5-06.2 Web scheduling and platform constraints](M5-06.2-web-scheduling.md) | Not started | M5-05.1 | web scheduling | M5-06.1, M5-06.3 |
| 20 | [M5-06.3 Rust desktop scheduling and documentation](M5-06.3-rust-scheduling.md) | Not started | M5-05.3 | rust scheduling | M5-06.1, M5-06.2 |
| 21 | [M5-07.1 Web Azure Blob, GCS, Google Drive, and WebDAV adapters](M5-07.1-web-additional-backends.md) | Not started | M5-03.1, M5-04.1 | web backend | M5-07.2, M5-07.3 |
| 22 | [M5-07.2 Android Azure Blob, GCS, Google Drive, and WebDAV adapters](M5-07.2-android-additional-backends.md) | Not started | M5-03.2, M5-04.2 | android backend | M5-07.1, M5-07.3 |
| 23 | [M5-07.3 Rust Azure Blob, GCS, Google Drive, and WebDAV adapters](M5-07.3-rust-additional-backends.md) | Not started | M5-03.3, M5-04.3 | rust backend | M5-07.1, M5-07.2 |
| 24 | [M5-07.4 Parity, cross-client restore, and exit evidence](M5-07.4-parity-cross-client-evidence.md) | Not started | M5-06.1, M5-06.2, M5-06.3, M5-07.1, M5-07.2, M5-07.3 | evidence | none; final gate |

## Parallel execution groups

- Group A: `M5-00.1` first. It freezes decisions and test vectors and blocks all work.
- Group B: `M5-01.1` next. Then `M5-01.2`, `M5-01.3`, and `M5-01.4` can run in parallel.
- Group C: after each client codec, run the matching data-model task: `M5-02.1`, `M5-02.2`, `M5-02.3` in parallel.
- Group D: after each data model, run the matching S3 adapter: `M5-03.1`, `M5-03.2`, `M5-03.3` in parallel.
- Group E: after each S3 adapter, run the matching sync engine: `M5-04.1`, `M5-04.2`, `M5-04.3` in parallel.
- Group F: after each sync engine, run the matching UI task: `M5-05.1`, `M5-05.2`, `M5-05.3` in parallel.
- Group G: after each UI task, run the matching scheduling task: `M5-06.1`, `M5-06.2`, `M5-06.3` in parallel.
- Group H: after S3 and sync, run the additional backend tasks: `M5-07.1`, `M5-07.2`, `M5-07.3` in parallel.
- Group I: `M5-07.4` is the final gate. It cannot start until every required web, Android, and Rust task is `Complete`.

## Rust work

Rust is shipping and required for M5 per `M5-decisions.md` `DEC-M5-03`, which
supersedes `M4-decisions.md` `DEC-M4-01` for M5 onward. Tasks `M5-01.4`,
`M5-02.3`, `M5-03.3`, `M5-04.3`, `M5-05.3`, `M5-06.3`, and `M5-07.3` are
required and must meet the same contracts and security bar as web and Android.
They block `M5-07.4`. Do not mark them `Cancelled` without a new dated decision.

## Completion rule

M5 is complete only when every required web, Android, and Rust task is `Complete`
(or explicitly cancelled with an approved deferral), `docs/operations/M5/M5-verification.md`
records the exit-gate evidence, `contracts/parity.md` backup rows are truthful,
and the milestone exit gate in the source plan section 8 is fully checked.

## Task orchestration

```bash
scripts/next-task docs/operations/M5
scripts/next-task docs/operations/M5 --list
scripts/next-task docs/operations/M5 --include-in-progress
scripts/task-status get docs/operations/M5/M5-00.1-preflight-decisions-vectors.md
```

Keep one task file per numbered row and update the status in both this index and
the task file. Do not add tasks at the `docs/operations` root.
