# ChatUI Milestones

This directory is the milestone layer between [`docs/IMPLEMENTATION.md`](../IMPLEMENTATION.md) and generated implementation tasks. A milestone defines a bounded, independently reviewable slice of the product: its scope, owned areas, dependencies, acceptance gate, and verification commands.

Milestones are intentionally not task lists. Concrete tasks are generated later, one milestone at a time, by subagents using the contract in [Task generation](#task-generation). That keeps task boundaries aligned with the specification instead of drifting into a second specification.

## Status

| ID | Milestone | Status | Depends on | Platforms |
| --- | --- | --- | --- | --- |
| M0 | Foundations | In progress | none | shared, web, android |
| M1 | OpenRouter-first core chat | Planned | M0 | web, android |
| M2 | Providers and content | Planned | M1 | web, android |
| M3 | Android depth | Planned | M2 | android |
| M3.5 | Rust multiplatform client | Optional / parallel | M0, M1 | rust desktop, Android, future iOS |
| M4 | Polish and release | Planned | M1, M2, M3, M3.5 if shipping | all enabled clients |
| M5 | Backup and sync | Future | M4 | all shipping clients |
| M6 | Managed roksky hosting | Future | M4 (M5 for BYO storage) | web hosting |

Repository bootstrap (root README, Makefile, ignore rules, directory skeleton) is complete. The completed record is [`docs/tasks/T000-foundations.md`](../tasks/T000-foundations.md). M0 remains in progress until shared contracts, the web and Android foundations, and the CI skeleton are complete.

## Dependency graph

```text
M0 ──> M1 ──> M2 ──> M3 ──┐
        │                   ├──> M4 ──> M5 ──> M6
        └──> M3.5 ──────────┘
```

- M1 is the first OpenRouter-gated feature milestone.
- M3.5 may start after M1 and must not delay M2, M3, or M4.
- M3 depends on M2 for the Android chat and conversation surfaces.
- M4 is the phase 1 release gate for every client that will ship.
- M5 and M6 are explicitly phase 2 and must not start before M4.

## Milestone index

| File | Scope |
| --- | --- |
| [M0-foundations.md](M0-foundations.md) | Contracts, web foundation, Android foundation, CI skeleton |
| [M1-openrouter-core.md](M1-openrouter-core.md) | OpenRouter end-to-end chat on web and Android |
| [M2-providers-and-content.md](M2-providers-and-content.md) | Additional providers, attachments, parameters, search, export/import |
| [M3-android-depth.md](M3-android-depth.md) | Android platform integration, recovery, tests, release configuration |
| [M3.5-rust-multiplatform.md](M3.5-rust-multiplatform.md) | Optional Rust workspace, desktop/mobile shell, conformance, packaging |
| [M4-polish-and-release.md](M4-polish-and-release.md) | Accessibility, PWA, performance, deployment, parity audit, release |
| [M5-backup-and-sync.md](M5-backup-and-sync.md) | Encrypted user-owned backup and sync (future) |
| [M6-managed-hosting.md](M6-managed-hosting.md) | Optional roksky-managed hosting (future) |

## Cross-cutting invariants

These apply to every milestone and every generated task:

- `contracts/` is the only source of truth for provider presets, JSON Schemas, fixtures, export/backup formats, and the parity checklist. No client may duplicate provider metadata.
- OpenRouter is the default, reference, and release-blocking provider for every published client.
- `contracts/parity.md` is updated in the same change as any user-visible behavior change.
- Web, Android, and Rust are independent builds. No root Node workspace couples them, and Rust is not a build-time dependency of web or Kotlin.
- IDs are UUIDv7. Timestamps are ISO 8601 / RFC 3339 UTC. Messages carry a monotonic per-conversation `sequence`.
- Secrets are never committed, logged, placed in URLs, cached by service workers, or included in exports and backup metadata.
- No analytics, telemetry, hidden network destinations, or server-side prompt proxying in phase 1.
- The Rust client is optional for the phase 1 web/Android gate. Once published it is first-class and must meet the same contracts, conformance, parity, and security bars.

## Ownership map

| Area | Paths | Primary milestone |
| --- | --- | --- |
| Shared contracts | `contracts/`, `scripts/validate-contracts.mjs` | M0 |
| Web client | `web/` | M0-M2, M4 |
| Web deployment | `web/deploy/`, `web/Dockerfile`, `web/Caddyfile` | M4 |
| Android client | `android/` | M0-M4 |
| Rust workspace | `rust/` | M3.5 |
| CI workflows | `.github/workflows/` | M0 skeleton, M4 release pipeline |
| Milestone docs | `docs/milestones/` | all |
| Release evidence | `docs/release/` | M4 |
| Managed hosting | `deploy/roksky/`, `docs/hosting/` | M6 |

Generated tasks may only own paths inside one area and one milestone. Cross-area changes must be split into separate tasks with explicit dependencies.

## Task generation

Subagents generate tasks from a milestone, never from the specification alone. Follow this protocol:

1. Confirm the milestone's entry criteria are met. Do not generate tasks for a blocked milestone except research or spike work explicitly allowed by the milestone.
2. Read the milestone file, then the spec sections it lists. The milestone narrows scope; the spec remains authoritative for behavior.
3. Produce roughly 3-8 tasks per milestone (more for M3.5 if split by workstream). Each task must be independently completable and reviewable.
4. Give every task an ID, title, milestone, dependencies, spec sections, owned paths, goal, deliverables, acceptance criteria, verification commands, and explicit out-of-scope items.
5. Keep one task to one primary area. Do not let a task span web and Android, or Rust and Kotlin, unless the milestone explicitly requires a shared artifact.
6. Put generated task files under `docs/tasks/<milestone-id>/`, for example `docs/tasks/M1/T110-web-openrouter.md`. Do not add new task files at the `docs/tasks/` root.
7. Include the tests and verification commands required by the milestone gate. A task is not complete because code compiles; the relevant gate must pass or the gap must be documented precisely.
8. Stop and review the generated task list with the user before implementation when the milestone is new or its boundaries changed.

### Task file template

```markdown
# <ID> - <Title>

- Milestone: <M#>
- Depends on: <task IDs or "none">
- Spec: docs/IMPLEMENTATION.md sections <list>
- Parallel-safe: <yes/no and with what>

## Goal

## Owns

## Deliverables

## Acceptance criteria

## Verification

## Out of scope
```

## Global verification gates

```bash
make contracts-check
make web-lint && make web-typecheck && make web-test && make web-build
make android-lint && make android-test && make android-assemble
make rust-check && make rust-lint && make rust-test && make rust-build
```

Plus the milestone-specific gates in each file. A milestone is complete only when its exit gate has evidence, `contracts/parity.md` reflects the delivered behavior, and the status table above is updated.
