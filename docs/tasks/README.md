# ChatUI Implementation Tasks

This folder decomposes [`docs/IMPLEMENTATION.md`](../IMPLEMENTATION.md) into subagent-sized work packages. Each task is self-contained: it lists the spec sections to read, owned files, dependencies, implementation steps, acceptance criteria, and verification commands.

These tasks assume a greenfield repository. The only existing tracked file is the specification. Do not treat the task text as a replacement for the spec; the spec is authoritative.

## How to use these tasks

1. Pick one task whose dependencies are complete.
2. Read the spec sections listed at the top of the task before editing.
3. Claim the task by creating a branch or worktree named after the task ID, for example `task/T011-web-openrouter`.
4. Only edit the paths listed under **Owns** (or paths you create inside that task's area). If a change is required outside your ownership, report it as a follow-up instead of editing another task's files.
5. Run the verification commands. If a command is not yet available because a dependency is missing, record that in the task report.
6. Update `contracts/parity.md` in the same change whenever user-visible behavior changes.
7. Do not commit secrets, generated signing keys, or provider credentials. Do not commit at all unless the user asked.

## Task report format

When a subagent finishes, it must return:

- Task ID and title.
- Files created or changed.
- Verification commands run and their results.
- Known gaps, follow-ups, or blocked dependencies.
- Whether `contracts/parity.md` was updated.

## Parallelization waves

Tasks in the same wave can run concurrently if their file ownership does not overlap.

| Wave | Tasks | Notes |
| --- | --- | --- |
| 0 | T000, T001 | Foundations and contracts block everything else. T001 depends on T000. |
| 1 | T010, T020, T030 | Platform scaffolds can run in parallel after T001. |
| 2 | T011, T021, T031 | OpenRouter adapter + onboarding per platform. |
| 3 | T012, T022, T032 | Chat UI, streaming display, rendering, attachments per platform. |
| 4 | T013, T023, T033 | Conversations, search, export/import, settings, packaging per platform. |
| 5 | T014, T024, T034 | Hardening, additional providers, parity, release checks per platform. |
| 6 | T015, T025 | Final platform tests and parity for web and Android. |
| 7 | T040, T041 | CI/conformance integration and release audit. |
| Future | T042, T043 | Backup/sync and managed hosting; do not start during phase 1. |

The Rust track is optional for the phase 1 web/Android release gate. It may start in parallel after T001 and must not delay T010-T025.

## Task index

| ID | Task | Milestone | Depends on | Platform | File |
| --- | --- | --- | --- | --- | --- |
| T000 | Repository foundations and developer commands | M0 | none | all | [T000-foundations.md](T000-foundations.md) |
| T001 | Shared contracts, presets, fixtures, parity | M0 | T000 | all | [T001-contracts.md](T001-contracts.md) |
| T010 | Web foundation: scaffold, domain, storage, secrets | M0/M1 | T001 | web | [T010-web-foundation.md](T010-web-foundation.md) |
| T011 | Web OpenRouter adapter, onboarding, providers, models | M1 | T010 | web | [T011-web-openrouter.md](T011-web-openrouter.md) |
| T012 | Web chat UI, composer, rendering, attachments | M1/M2 | T011 | web | [T012-web-chat.md](T012-web-chat.md) |
| T013 | Web conversations, search, export/import, settings | M1/M2 | T012, T010 | web | [T013-web-conversations.md](T013-web-conversations.md) |
| T014 | Web PWA, deployment, security, accessibility, performance | M4 | T013 | web | [T014-web-hardening.md](T014-web-hardening.md) |
| T015 | Web additional providers, E2E, parity | M2/M4 | T011-T014 | web | [T015-web-parity.md](T015-web-parity.md) |
| T020 | Android foundation: Gradle, Room, DataStore, secrets | M0/M1 | T001 | android | [T020-android-foundation.md](T020-android-foundation.md) |
| T021 | Android OpenRouter adapter, onboarding, providers, models | M1 | T020 | android | [T021-android-openrouter.md](T021-android-openrouter.md) |
| T022 | Android chat UI, composer, rendering, attachments | M1/M2 | T021 | android | [T022-android-chat.md](T022-android-chat.md) |
| T023 | Android conversations, search, export/import, settings, share | M1/M2 | T022 | android | [T023-android-conversations.md](T023-android-conversations.md) |
| T024 | Android release, security, accessibility, performance | M3/M4 | T023 | android | [T024-android-hardening.md](T024-android-hardening.md) |
| T025 | Android additional providers, UI tests, parity | M2/M4 | T021-T024 | android | [T025-android-parity.md](T025-android-parity.md) |
| T030 | Rust workspace, domain, contracts, storage, secrets | M3.5 | T001 | rust | [T030-rust-foundation.md](T030-rust-foundation.md) |
| T031 | Rust OpenRouter provider, streaming, onboarding | M3.5 | T030 | rust | [T031-rust-openrouter.md](T031-rust-openrouter.md) |
| T032 | Rust UI shell, chat, conversations, rendering | M3.5 | T031 | rust | [T032-rust-ui.md](T032-rust-ui.md) |
| T033 | Rust packaging, updater, security, accessibility | M3.5 | T032 | rust | [T033-rust-hardening.md](T033-rust-hardening.md) |
| T034 | Rust additional providers, tests, parity | M3.5 | T031-T033 | rust | [T034-rust-parity.md](T034-rust-parity.md) |
| T040 | CI/CD and contract conformance integration | M0-M4 | T000, T001, T010-T034 | all | [T040-ci-conformance.md](T040-ci-conformance.md) |
| T041 | Parity audit and phase 1 release gate | M4 | T015, T025, T040 | all | [T041-parity-release.md](T041-parity-release.md) |
| T042 | Encrypted backup and sync (future) | M5 | T001, platform storage tasks | all | [T042-backup-sync.md](T042-backup-sync.md) |
| T043 | Managed roksky deployment (future) | M6 | T014, T041 | web | [T043-managed-hosting.md](T043-managed-hosting.md) |

## Cross-task contracts

- `contracts/providers.json` is the only provider registry. No client may hard-code presets.
- `contracts/schema/*.schema.json` are the normative JSON shapes. Rust, TypeScript, and Kotlin types must map to them.
- `contracts/fixtures/` are shared conformance inputs. The same fixture must produce the same normalized events on every enabled client.
- `contracts/parity.md` is updated in every PR that changes user-visible behavior.
- UUIDv7 IDs, ISO 8601/RFC 3339 timestamps, and message `sequence` ordering are mandatory.
- OpenRouter is the reference provider and the release blocker for every published client.

## Global verification gates

These are expanded per task where relevant:

```bash
make contracts-check
make web-lint && make web-typecheck && make web-test && make web-build
make android-lint && make android-test && make android-assemble
make rust-lint && make rust-test && make rust-build
```

Do not mark a task complete if the relevant gate fails because of your changes. If it fails because of a missing dependency or a pre-existing issue, document it precisely.
