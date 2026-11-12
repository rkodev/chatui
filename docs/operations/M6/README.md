# M6 Task Breakdown - Managed roksky hosting

- Source plan: [M6 Managed roksky Hosting Implementation Plan](../M6-managed-hosting-implementation-plan.md)
- Milestone: [M6 Managed roksky hosting](../../milestones/M6-managed-hosting.md)
- Authoritative spec: [IMPLEMENTATION.md](../../IMPLEMENTATION.md) sections 13.8, 14.2-14.3, 17 (M6), 19
- Current baseline: [M4 verification](../M4/M4-verification.md) (phase 1 gate passed 2026-11-10)
- Backup baseline: [M5 verification](../M5/M5-verification.md) (M5 complete 2026-11-12)
- Current parity: [contracts/parity.md](../../../contracts/parity.md)
- Status: Planned - entry gate blocked on product, hosting-operator, and privacy-owner decisions
- Last updated: 2026-11-12
- Ordering: work through the `Order` column. A task may start only when every dependency is `Complete`.
- Scope: optional roksky-managed static hosting for the web client. Repository work is templates, public tenant configuration, documentation, runbooks, validation scripts, and evidence. The roksky control plane, DNS, TLS automation, billing, and support portal are external operator deliverables.
- Platform rule: web hosting only. Do not add a backend, server-side prompt proxy, key custody, plaintext conversation storage, or backup-passphrase access.

## Entry gate

Do not start any task until all of the following are true:

- [x] M4 exit gate is met and phase 1 is released (`docs/operations/M4/M4-verification.md`, 2026-11-10).
- [x] M5 is complete, so BYO encrypted storage onboarding exists (`docs/operations/M5/M5-verification.md`, 2026-11-12).
- [ ] The user explicitly authorizes M6 work and confirms it remains a hosting/documentation milestone, not a backend build.
- [ ] The product name, domain, and support model from spec section 20 question 10 have named owners.
- [ ] A roksky hosting operator is named and can provide a staging tenant, subdomain, custom-domain test path, TLS, and edge configuration access.
- [ ] A privacy/legal reviewer is named for data-processing, logging, retention, and support-access copy.
- [ ] The feature-flag decision is made: expose none in M6, or add a typed, allowlisted public flag shape.
- [ ] The tenant configuration boundary is confirmed: private operator manifest plus public app `config.json`, with no secrets in either served artifact.
- [ ] `docs/operations/M6/evidence/` is agreed as the evidence location, using synthetic data only.

`M6-01.1` owns closing this gate and recording every unresolved item as a blocker. Do
not start `M6-02.1`, `M6-03.1`, or `M6-04.1` until `M6-01.1` is `Complete`.

## How to use these tasks

1. Read the source plan section named in `Read first` and the listed spec sections before writing.
2. Touch only the paths under `Owns`. If another path is required, record the dependency in `Handoff notes` instead of widening scope.
3. Do not start a task until every task under `Depends on` is `Complete`.
4. Keep the repository boundary: no backend, prompt proxy, key custody, plaintext conversation storage, or managed storage offering.
5. Never record real API keys, storage credentials, backup passphrases, prompts, responses, or conversation content in task files, docs, tests, logs, or evidence. Use synthetic data only.
6. Do not publish privacy claims that the external operator cannot enforce. Mark unverified claims as blockers.
7. Do not mark a task complete until every acceptance checkbox is true and every verification command passes or has an approved, recorded blocker.
8. Update this index and the task file status when work starts or completes.
9. Put decisions, deviations, and evidence in `Handoff notes`; do not leave them only in chat or commit messages.
10. Tasks that need a staging tenant or external operator access must record the dependency in `Handoff notes` if access is unavailable.

## Status values

| Status | Meaning |
| --- | --- |
| Not started | No implementation work has begun |
| In progress | Work has begun; keep this index updated |
| Blocked | A dependency, decision, credential, tenant, or reviewer prevents progress; record the blocker |
| Complete | Acceptance criteria and verification pass |
| Cancelled | Work was removed or replaced; record why |

## Ordered task list

| Order | Task | Status | Depends on | Area | Parallel-safe with |
| --- | --- | --- | --- | --- | --- |
| 1 | [M6-01.1 Preflight decisions and entry-gate closure](M6-01.1-preflight-decisions.md) | Not started | none | preflight | none; blocks all |
| 2 | [M6-01.2 Tenant manifest schema and fixtures](M6-01.2-tenant-manifest-schema.md) | Not started | M6-01.1 | contracts | M6-01.3 |
| 3 | [M6-01.3 Public config templates and feature-flag handling](M6-01.3-public-config-templates.md) | Not started | M6-01.1 | config | M6-01.2 |
| 4 | [M6-01.4 Tenant configuration validator and docs](M6-01.4-tenant-config-validator-docs.md) | Not started | M6-01.2, M6-01.3 | validation/docs | none; blocks hosting tasks |
| 5 | [M6-02.1 Deployment template tree and edge configuration](M6-02.1-deployment-templates-edge.md) | Not started | M6-01.4 | deployment | M6-03.1, M6-05.1 |
| 6 | [M6-02.2 One-click hosting flow and external handoff contract](M6-02.2-one-click-flow.md) | Not started | M6-02.1 | hosting flow | M6-02.3, M6-02.4 |
| 7 | [M6-02.3 Custom domain, TLS, health, and status runbooks](M6-02.3-domain-tls-health-status.md) | Not started | M6-02.1 | hosting runbooks | M6-02.2, M6-02.4 |
| 8 | [M6-02.4 Release pinning and rollback runbook](M6-02.4-release-pinning-rollback.md) | Not started | M6-02.1 | rollback | M6-02.2, M6-02.3 |
| 9 | [M6-03.1 BYO storage onboarding guide](M6-03.1-byo-storage-onboarding.md) | Not started | M6-01.4 | storage docs | M6-02.1, M6-05.1 |
| 10 | [M6-03.2 CORS, custom-origin, and failure-state documentation](M6-03.2-cors-failure-states.md) | Not started | M6-03.1 | storage docs | M6-03.3 |
| 11 | [M6-03.3 Backup/restore and provider-terms documentation](M6-03.3-backup-restore-provider-terms.md) | Not started | M6-03.1 | storage docs | M6-03.2 |
| 12 | [M6-04.1 Data-processing and privacy documentation](M6-04.1-data-processing-privacy.md) | Not started | M6-01.4, M6-02.1, M6-03.1 | privacy docs | M6-04.2 |
| 13 | [M6-04.2 Logging, retention, and support-access documentation](M6-04.2-logging-support-access.md) | Not started | M6-01.4, M6-02.1 | privacy docs | M6-04.1 |
| 14 | [M6-04.3 Privacy/legal review and publish gate](M6-04.3-privacy-legal-review.md) | Not started | M6-02.3, M6-03.3, M6-04.1, M6-04.2 | privacy review | none |
| 15 | [M6-05.1 Hosted deployment verification script](M6-05.1-hosted-verification-script.md) | Not started | M6-01.4, M6-02.1 | validation tooling | M6-03.1, M6-04.1 |
| 16 | [M6-05.2 Staging tenant deployment and smoke test](M6-05.2-staging-tenant-smoke.md) | Not started | M6-02.2, M6-02.3, M6-02.4, M6-03.2, M6-03.3, M6-04.3, M6-05.1 | staging validation | none; needs external staging |
| 17 | [M6-05.3 Tenant config review, rollback drill, and evidence](M6-05.3-config-review-rollback-drill.md) | Not started | M6-05.2 | staging validation | none |
| 18 | [M6-06.1 Security data-flow and no-hidden-destination review](M6-06.1-security-data-flow-review.md) | Not started | M6-04.3, M6-05.3 | security review | M6-06.2 |
| 19 | [M6-06.2 Self-host and export portability proof](M6-06.2-self-host-portability.md) | Not started | M6-05.3 | portability | M6-06.1 |
| 20 | [M6-06.3 Exit-gate verification and milestone closure](M6-06.3-exit-gate-closure.md) | Not started | M6-06.1, M6-06.2, all prior tasks | exit gate | none; final gate |

## Parallel execution groups

- Group A: `M6-01.1` first. It records decisions and closes the entry gate. It blocks all work.
- Group B: after `M6-01.1`, run `M6-01.2` and `M6-01.3` in parallel. Then run `M6-01.4` after both.
- Group C: after `M6-01.4`, run `M6-02.1`, `M6-03.1`, and `M6-05.1` in parallel. They touch different paths.
- Group D: after `M6-02.1`, run `M6-02.2`, `M6-02.3`, and `M6-02.4` in parallel.
- Group E: after `M6-03.1`, run `M6-03.2` and `M6-03.3` in parallel.
- Group F: after `M6-02.1` and `M6-03.1`, run `M6-04.1` and `M6-04.2` in parallel.
- Group G: `M6-04.3` follows the deployment, storage, and privacy docs. It needs the named privacy/legal reviewer.
- Group H: `M6-05.2` is the staging gate. It needs every deployment, storage, and privacy task plus `M6-05.1`.
- Group I: after `M6-05.2`, run `M6-05.3`.
- Group J: after `M6-05.3`, run `M6-06.1` and `M6-06.2` in parallel.
- Group K: `M6-06.3` is the final gate. It cannot start until every other task is `Complete`.

## Repository and external-operator boundary

| Deliverable | Repository tasks | External operator |
| --- | --- | --- |
| Static app artifact | `M6-02.1`, `M6-05.1`, `M6-06.2` | Serves the artifact on tenant origins |
| Tenant model and public config | `M6-01.2`, `M6-01.3`, `M6-01.4` | Stores tenant records, enforces subdomain/region policy |
| One-click entry point | `M6-02.2` documents the contract | Provides the actual UI/API, DNS, TLS, provisioning |
| Custom domains and TLS | `M6-02.3`, `M6-05.2` validate the flow | Issues certificates, manages DNS and edge routing |
| Health and status | `M6-02.3`, `M6-05.1` define and verify checks | Runs checks, alerts, and displays status |
| BYO storage | `M6-03.1`, `M6-03.2`, `M6-03.3` document and validate client-side behavior | Makes no server-side storage request |
| Privacy and support | `M6-04.1`, `M6-04.2`, `M6-04.3` write and review | Enforces roles, access, retention, audit |
| Evidence | `M6-05.2`, `M6-05.3`, `M6-06.1`, `M6-06.2`, `M6-06.3` | Supplies the staging tenant and operator access |

M6 cannot close on repository work alone. If the external staging tenant or
privacy reviewer is unavailable, mark the affected task `Blocked`; do not
fabricate evidence.

## Completion rule

M6 is complete only when every task is `Complete` or has an approved, recorded
deferral, `docs/operations/M6/M6-verification.md` records the exit-gate evidence,
the privacy review is approved, the staging tenant passes the static and storage
checks, self-host/export portability is proven, and no backend, proxy,
key-custody, or support data-access path was introduced.

## Task orchestration

```bash
scripts/next-task docs/operations/M6
scripts/next-task docs/operations/M6 --list
scripts/next-task docs/operations/M6 --include-in-progress
scripts/task-status get docs/operations/M6/M6-01.1-preflight-decisions.md
```

Keep one task file per numbered row and update the status in both this index and
the task file. Do not add tasks at the `docs/operations` root.
