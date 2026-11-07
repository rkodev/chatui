# M3.5 Task Breakdown

- Source plan: [M3.5 Rust Desktop Implementation Plan](../M3.5-rust-desktop-implementation-plan.md)
- Milestone: [M3.5 Rust desktop client](../../milestones/M3.5-rust-desktop.md)
- Authoritative spec: [IMPLEMENTATION.md](../../IMPLEMENTATION.md)
- Current parity: [contracts/parity.md](../../../contracts/parity.md)
- Status: Not started
- Last updated: 2026-11-07
- Ordering: work through the `Order` column. A task may start only when every dependency is `Complete`.
- Scope: Rust desktop only: Windows, macOS, and Linux. M3.5a is core, contracts, storage, secrets, and OpenRouter. M3.5b is UI, desktop integration, packaging, and release evidence.

## How to use these tasks

1. Read the source plan section named in the task and the spec sections listed in `Read first` before editing code.
2. Touch only the paths listed under `Owns`. If another path is required, stop and record the dependency in `Handoff notes` instead of silently expanding scope.
3. Do not start a task until every task under `Depends on` is `Complete`.
4. Do not modify `web/` or `android/`. Do not add Android, iOS, or WASM Rust targets.
5. Do not start feature UI before M3.5-01.2 records the UI framework decision.
6. Keep provider metadata in `contracts/providers.json`. Do not copy presets into Rust source.
7. Do not record API keys, prompts, conversation content, signing material, or personal data in task files, tests, screenshots, logs, or CI artifacts.
8. Do not mark a task complete until every acceptance checkbox is true and every verification command passes or has an approved, recorded blocker.
9. Update this index and the task file status when work starts or completes.
10. Put decisions, deviations, and evidence in `Handoff notes`; do not leave them only in chat or commit messages.

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
| 1 | [M3.5-00.1 Preflight, baseline, and environment](M3.5-00.1-preflight-baseline.md) | Complete | none | preflight | none; blocks all |
| 2 | [M3.5-00.2 Scope, decisions, and release enablement](M3.5-00.2-decisions-enablement.md) | Complete | M3.5-00.1 | preflight | none; blocks implementation choices |
| 3 | [M3.5-01.1 Cargo workspace, toolchain, and crate skeletons](M3.5-01.1-workspace-toolchain.md) | Complete | M3.5-00.2 | workspace | none; unblocks all implementation |
| 4 | [M3.5-01.2 UI framework spike and decision](M3.5-01.2-ui-framework-spike.md) | Complete | M3.5-00.2, M3.5-01.1 | workspace | yes, with M3.5-02.1 |
| 5 | [M3.5-01.3 Developer tooling, dependency policy, and release profile](M3.5-01.3-tooling-deny-release-profile.md) | Complete | M3.5-01.1 | workspace | yes, with M3.5-01.2 and M3.5-02.1 |
| 6 | [M3.5-02.1 Domain entities, IDs, time, errors, and stream events](M3.5-02.1-domain-types.md) | Complete | M3.5-01.1 | domain | yes, with M3.5-01.2, M3.5-01.3, M3.5-04.1 |
| 7 | [M3.5-02.2 Contract loading, schemas, export types, and app config](M3.5-02.2-contracts-export-types.md) | Complete | M3.5-02.1 | contracts | yes, with M3.5-03.1, M3.5-04.1, M3.5-05.1 |
| 8 | [M3.5-02.3 Fixture harness and conformance scaffold](M3.5-02.3-fixture-harness.md) | Complete | M3.5-02.1, M3.5-02.2 | conformance | yes, with M3.5-03.1, M3.5-04.1, M3.5-05.1 |
| 9 | [M3.5-03.1 SQLite connection, pragmas, and migrations](M3.5-03.1-sqlite-migrations.md) | Complete | M3.5-02.1, M3.5-02.2 | storage | yes, with M3.5-04.1, M3.5-05.1 |
| 10 | [M3.5-03.2 Repositories, schema mapping, FTS5, and pagination](M3.5-03.2-repositories-fts.md) | Complete | M3.5-03.1 | storage | yes, with M3.5-03.3 |
| 11 | [M3.5-03.3 Content-addressed attachments and startup recovery](M3.5-03.3-attachments-recovery.md) | Complete | M3.5-03.1, M3.5-02.1 | storage | yes, with M3.5-03.2 |
| 12 | [M3.5-03.4 Export and import service](M3.5-03.4-export-import.md) | Complete | M3.5-03.2, M3.5-03.3, M3.5-02.2 | storage | no; follows attachment store |
| 13 | [M3.5-04.1 Keyring integration and secret-store abstraction](M3.5-04.1-keyring-store.md) | Complete | M3.5-01.1 | secrets | yes, with M3.5-02.* and M3.5-03.* |
| 14 | [M3.5-04.2 Encrypted fallback, session mode, and key lifecycle](M3.5-04.2-encrypted-fallback-lifecycle.md) | Complete | M3.5-04.1 | secrets | yes, with M3.5-03.* and M3.5-05.* |
| 15 | [M3.5-04.3 Redaction, zeroization, and backup exclusion](M3.5-04.3-redaction-zeroize-backup.md) | Complete | M3.5-04.1, M3.5-04.2, M3.5-00.2 | secrets | yes, with M3.5-05.* |
| 16 | [M3.5-05.1 Shared HTTP client, URL policy, and connection handling](M3.5-05.1-http-client-cleartext.md) | Complete | M3.5-02.1, M3.5-02.2 | networking | yes, with M3.5-03.* and M3.5-04.* |
| 17 | [M3.5-05.2 Bounded SSE parser](M3.5-05.2-sse-parser.md) | Complete | M3.5-02.1, M3.5-02.3 | networking | yes, with M3.5-05.3 |
| 18 | [M3.5-05.3 Bounded NDJSON parser](M3.5-05.3-ndjson-parser.md) | Complete | M3.5-02.1, M3.5-02.3 | networking | yes, with M3.5-05.2 |
| 19 | [M3.5-05.4 Retry policy, watchdog, cancellation, and network test harness](M3.5-05.4-retry-watchdog-harness.md) | Complete | M3.5-05.1, M3.5-05.2 | networking | yes, with M3.5-05.3 |
| 20 | [M3.5-06.1 OpenRouter key validation and catalog cache](M3.5-06.1-openrouter-key-catalog.md) | Complete | M3.5-05.1, M3.5-02.2 | providers | yes, with M3.5-06.2 |
| 21 | [M3.5-06.2 OpenRouter request serialization, routing, and protected fields](M3.5-06.2-openrouter-request-routing.md) | Not started | M3.5-02.1, M3.5-02.2, M3.5-05.1 | providers | yes, with M3.5-06.1 |
| 22 | [M3.5-06.3 OpenRouter streaming, reasoning, usage, and errors](M3.5-06.3-openrouter-streaming-errors.md) | Not started | M3.5-05.1, M3.5-05.2, M3.5-05.4, M3.5-06.2 | providers | none; unblocks M3.5-06.4 |
| 23 | [M3.5-06.4 Provider registry and OpenRouter conformance gate](M3.5-06.4-registry-conformance.md) | Not started | M3.5-06.1, M3.5-06.2, M3.5-06.3, M3.5-02.3 | providers | none; unblocks all other providers |
| 24 | [M3.5-07.1 OpenAI-compatible preset adapters](M3.5-07.1-openai-compatible-presets.md) | Not started | M3.5-06.4 | providers | none; unblocks native adapters |
| 25 | [M3.5-07.2 Anthropic adapter](M3.5-07.2-anthropic-adapter.md) | Not started | M3.5-07.1 | providers | yes, with M3.5-07.3 and M3.5-07.4 |
| 26 | [M3.5-07.3 Gemini adapter](M3.5-07.3-gemini-adapter.md) | Not started | M3.5-07.1 | providers | yes, with M3.5-07.2 and M3.5-07.4 |
| 27 | [M3.5-07.4 Ollama adapter, discovery fallback, and capability inference](M3.5-07.4-ollama-discovery.md) | Not started | M3.5-07.1 | providers | yes, with M3.5-07.2 and M3.5-07.3 |
| 28 | [M3.5-08.1 Design tokens, theme, accessibility primitives, and responsive shell](M3.5-08.1-tokens-shell.md) | Not started | M3.5-01.2, M3.5-02.1 | ui | yes, with M3.5-03.*, M3.5-04.*, M3.5-05.*, M3.5-06.* |
| 29 | [M3.5-08.2 Onboarding, key entry, connection test, and default model](M3.5-08.2-onboarding.md) | Not started | M3.5-08.1, M3.5-04.1, M3.5-06.1, M3.5-06.4 | ui | none; unblocks provider/model UI |
| 30 | [M3.5-08.3 Provider management and key lifecycle UI](M3.5-08.3-provider-management.md) | Not started | M3.5-08.2, M3.5-04.2, M3.5-06.4 | ui | yes, with M3.5-08.4 |
| 31 | [M3.5-08.4 Model management, discovery UI, and model picker](M3.5-08.4-model-management.md) | Not started | M3.5-08.2, M3.5-06.1, M3.5-07.4 | ui | yes, with M3.5-08.3 |
| 32 | [M3.5-09.1 Chat runner, streaming state, send, stop, and regenerate](M3.5-09.1-chat-runner.md) | Not started | M3.5-08.3, M3.5-08.4, M3.5-06.4, M3.5-03.2 | ui | none; unblocks chat surfaces |
| 33 | [M3.5-09.2 Windowed message list, pagination, and scroll anchoring](M3.5-09.2-message-list.md) | Not started | M3.5-09.1, M3.5-03.2 | ui | yes, with M3.5-09.3 and M3.5-09.5 |
| 34 | [M3.5-09.3 Composer, drafts, attachments, paste, and drag-and-drop](M3.5-09.3-composer-attachments.md) | Not started | M3.5-09.1, M3.5-03.3 | ui | yes, with M3.5-09.2 and M3.5-09.5 |
| 35 | [M3.5-09.4 Markdown, code highlighting, math fallback, and safe content](M3.5-09.4-markdown-rendering.md) | Not started | M3.5-09.2 | ui | yes, with M3.5-09.3 and M3.5-09.5 |
| 36 | [M3.5-09.5 Conversation history, search, pin, archive, and rename](M3.5-09.5-conversations-search.md) | Not started | M3.5-09.1, M3.5-03.2 | ui | yes, with M3.5-09.3 and M3.5-09.4 |
| 37 | [M3.5-10.1 Settings screens and behavior wiring](M3.5-10.1-settings.md) | Not started | M3.5-08.1, M3.5-09.1 | ui | yes, with M3.5-10.3 |
| 38 | [M3.5-10.2 Data controls and export/import UI](M3.5-10.2-data-export-import-ui.md) | Not started | M3.5-10.1, M3.5-03.4 | ui | yes, with M3.5-10.3 |
| 39 | [M3.5-10.3 Native menus, shortcuts, window state, dialogs, and deep links](M3.5-10.3-desktop-integration.md) | Not started | M3.5-08.1, M3.5-09.1, M3.5-09.3 | desktop | yes, with M3.5-10.1 and M3.5-10.2 |
| 40 | [M3.5-10.4 Accessibility evidence and screen-reader smoke tests](M3.5-10.4-accessibility-evidence.md) | Not started | M3.5-09.1, M3.5-09.2, M3.5-09.3, M3.5-09.4, M3.5-09.5, M3.5-10.1, M3.5-10.2, M3.5-10.3 | evidence | none; final UI evidence |
| 41 | [M3.5-11.1 Release profile, symbols, and binary-size budget](M3.5-11.1-release-profile-size.md) | Not started | M3.5-01.3, M3.5-08.1 | release | yes, with M3.5-09.* and M3.5-10.* |
| 42 | [M3.5-11.2 Cross-platform packaging and checksums](M3.5-11.2-packaging-checksums.md) | Not started | M3.5-11.1, M3.5-08.1 | release | yes, with M3.5-09.* and M3.5-10.* |
| 43 | [M3.5-11.3 Auto-update manifests, signing, and verification](M3.5-11.3-updater-signing.md) | Not started | M3.5-11.2, M3.5-04.1 | release | yes, with M3.5-10.* |
| 44 | [M3.5-11.4 Rust CI matrix and tag release workflow](M3.5-11.4-ci-release-workflow.md) | Not started | M3.5-11.2, M3.5-11.3 | release | none; needs updater and packaging |
| 45 | [M3.5-11.5 Performance, cold start, and installer-size evidence](M3.5-11.5-performance-evidence.md) | Not started | M3.5-11.2, M3.5-11.4 | release | none; follows CI evidence |
| 46 | [M3.5-12.1 Full conformance and critical-case evidence](M3.5-12.1-conformance-critical-cases.md) | Not started | all prior tasks | evidence | none; full gate |
| 47 | [M3.5-12.2 Parity, release gate, and M3.5 verification report](M3.5-12.2-parity-release-verification.md) | Not started | M3.5-12.1 | evidence | none; final gate |

## Parallel execution groups

- Group A: M3.5-00.1, then M3.5-00.2. Both block implementation choices and the enablement decision.
- Group B: M3.5-01.1 first. M3.5-01.2 and M3.5-01.3 can then run in parallel; M3.5-02.1 can also start.
- Group C: after M3.5-02.1, run M3.5-02.2 and M3.5-04.1. Then M3.5-02.3, M3.5-03.1, and M3.5-05.1.
- Group D: M3.5-03.2 and M3.5-03.3 in parallel after M3.5-03.1. M3.5-03.4 follows M3.5-03.2 and M3.5-03.3.
- Group E: M3.5-04.2 follows M3.5-04.1. M3.5-04.3 follows M3.5-04.2.
- Group F: M3.5-05.2 and M3.5-05.3 in parallel after M3.5-02.3. M3.5-05.4 follows M3.5-05.1 and M3.5-05.2.
- Group G: M3.5-06.1 and M3.5-06.2 in parallel. M3.5-06.3 follows both. M3.5-06.4 is the OpenRouter gate.
- Group H: M3.5-07.1 follows the OpenRouter gate. M3.5-07.2, M3.5-07.3, and M3.5-07.4 can then run in parallel.
- Group I: M3.5-08.1 can start after the framework decision and the domain types. M3.5-08.2 follows the onboarding dependencies. M3.5-08.3 and M3.5-08.4 can run in parallel.
- Group J: M3.5-09.1 follows provider/model UI. M3.5-09.2, M3.5-09.3, and M3.5-09.5 can run in parallel. M3.5-09.4 follows M3.5-09.2.
- Group K: M3.5-10.1 and M3.5-10.3 can run in parallel after chat. M3.5-10.2 follows M3.5-10.1. M3.5-10.4 is the final UI evidence task.
- Group L: M3.5-11.1 and M3.5-11.2 can start once the shell exists. M3.5-11.3 follows M3.5-11.2. M3.5-11.4 follows M3.5-11.3. M3.5-11.5 follows M3.5-11.4.
- Group M: M3.5-12.1 after all implementation, UI, release, and OS evidence. M3.5-12.2 is the final gate.

## Completion rule

M3.5 is complete only when every task is `Complete` or explicitly cancelled with an approved deferral, `docs/operations/M3.5/M3.5-verification.md` contains the exit-gate evidence, `contracts/parity.md` is truthful, the OS smoke matrix has no release-blocking issue, and the enablement decision is recorded.

If Rust is deferred or declined, do not mark M3.5 complete. Record the decision, leave parity honest, and keep the tasks open or cancelled with a reason.

## Task orchestration

```bash
scripts/next-task docs/operations/M3.5
scripts/next-task docs/operations/M3.5 --list
scripts/next-task docs/operations/M3.5 --include-in-progress
scripts/task-status get docs/operations/M3.5/M3.5-02.1-domain-types.md
```

Keep one task file per numbered row and update the status in both this index and the task file. The task runner supports dotted milestone IDs such as `M3.5-00.1`.
