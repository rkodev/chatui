# M6 managed hosting decisions

- Milestone: M6
- Recorded by: `M6-01.1` preflight
- Date: 2026-11-12
- Baseline: [M4 verification](../M4/M4-verification.md) (phase 1 gate passed
  2026-11-10); [M5 verification](../M5/M5-verification.md) (M5 complete
  2026-11-12)
- Source: [M6 plan](../M6-managed-hosting-implementation-plan.md) sections 1.1,
  1.3, 4, and 10; [M6 milestone](../../milestones/M6-managed-hosting.md) entry
  criteria, risks, and out of scope; spec sections 13.8, 14.3, and 20 question 10
- Status: Preflight record complete. The entry gate is blocked on `DEC-M6-10`
  only: no external staging operator or staging tenant is assigned. The M6
  folder status stays `Planned` until that is resolved.

This file freezes the decisions that later M6 tasks depend on. A decision marked
`Confirmed` is within the repository's authority, was confirmed by the product
owner, or is already fixed by the M6 plan, milestone, or spec. A decision marked
`Blocked` needs a named external owner; it is not an invitation to guess. Change
a decision only with a new dated entry and a matching update to the affected task
files and the M6 README.

No secret, real key, storage credential, backup passphrase, prompt, response, or
conversation content is recorded here. Names that are not assigned are recorded
as `unassigned`, not invented.

## Decision summary

| ID | Decision | Status | Owner | Date |
| --- | --- | --- | --- | --- |
| `DEC-M6-01` | M6 is authorized as a hosting/documentation milestone, not a backend build | Confirmed for preflight | User/product owner | 2026-11-12 |
| `DEC-M6-02` | Repository owns static artifacts, schemas, templates, docs, and evidence; external operator owns control plane, DNS, TLS, status, and support tooling | Confirmed | Repository maintainers / architecture owner | 2026-11-12 |
| `DEC-M6-03` | Final product and hosting brand is ChatUI; base domain `chatui.dev`; support is GitHub Issues, best effort, no SLA | Confirmed | Product owner (repository maintainer) | 2026-11-12 |
| `DEC-M6-04` | Private operator manifest plus public app `config.json`; no secrets in either served artifact | Confirmed | Contracts/architecture owner | 2026-11-12 |
| `DEC-M6-05` | Tenant manifest at `contracts/schema/hosting-tenant.schema.json` with fixtures under `contracts/fixtures/hosting/` | Confirmed | Contracts owner | 2026-11-12 |
| `DEC-M6-06` | Expose no feature flags in M6; any future flag needs a typed allowlist and a new decision | Confirmed | Product/release owner with repository maintainers | 2026-11-12 |
| `DEC-M6-07` | Subdomain policy is operator-enforced; region means static edge location only, with no data-residency claim | Confirmed for semantics; region list and residency claims remain operator/product/legal | Hosting operator with product/legal owner | 2026-11-12 |
| `DEC-M6-08` | BYO storage only; no ChatUI-managed storage offering in M6 | Confirmed | Product/release owner (milestone scope) | 2026-11-12 |
| `DEC-M6-09` | Support access is least privilege, audited, non-impersonating, and has no key/chat/passphrase access | Confirmed for the boundary; detailed roles and audit retention remain for M6-04.2 and privacy review | Security/privacy owner with hosting operator | 2026-11-12 |
| `DEC-M6-10` | Named external staging operator, staging tenant, custom-domain test path, and TLS access | Blocked | Hosting operator (unassigned) | 2026-11-12 |
| `DEC-M6-11` | Named privacy/legal reviewer and approval process: product owner self-review with dated risk acceptance | Confirmed with recorded risk acceptance; not independent legal review | Product owner (self-reviewer) | 2026-11-12 |
| `DEC-M6-12` | Logging fields: IP, user agent, path, status, timing, tenant/subdomain, request ID; retention: edge/error 30 days, audit/support 90 days; automated deletion | Confirmed | Privacy reviewer (product owner self-review) with hosting operator | 2026-11-12 |
| `DEC-M6-13` | PWA install name/icons remain build-time ChatUI branding and are not tenant-branded in M6 | Confirmed; no branded build required | Product owner with web/UI owner | 2026-11-12 |
| `DEC-M6-14` | External operator owns custom-domain DNS, TLS issuance/renewal, and failure handling | Confirmed for ownership; named operator and operational details Blocked by `DEC-M6-10` | Hosting operator with release/security owner | 2026-11-12 |
| `DEC-M6-15` | Pin artifact digest and config hash together; roll back both; repository owns the runbook, operator executes | Confirmed | Release/security owner with hosting operator | 2026-11-12 |

## DEC-M6-01 - M6 authorization and scope

**Decision: M6 is authorized as a hosting/documentation milestone, not a backend
build.**

- The repository delivers static artifacts, tenant schemas, public config
  templates, documentation, runbooks, validation scripts, and evidence.
- The external operator delivers the control plane, DNS, TLS, status, support
  tooling, tenant records, and provisioning.
- M6 must not add a backend, server-side prompt proxy, key custody, plaintext
  conversation storage, storage-credential custody, or a managed storage
  offering.
- Status: Confirmed for preflight.
- Owner: User/product owner.
- Date: 2026-11-12.
- Rationale: the user requested implementation of `M6-01.1` on 2026-11-12, and
  the M6 plan and milestone define M6 as hosting/documentation only.
- Evidence: user request for this task; M6 plan section 1; milestone objective;
  M6 README scope and platform rule.
- Consequence: `M6-01.2` and `M6-01.3` may proceed once `M6-01.1` is
  `Complete`. Implementation tasks remain gated by the unresolved entry items
  below. Any request to build a control plane or backend in this repository is a
  separate approved scope, not M6.

## DEC-M6-02 - Repository versus external-operator boundary

**Decision: the repository owns static artifacts, schemas, templates, docs, and
evidence; the external operator owns the control plane, DNS, TLS, status, and
support tooling.**

| Area | Repository | External operator |
| --- | --- | --- |
| Static app | Build and publish the static artifact; keep the public config contract stable | Serve the artifact on tenant origins and apply edge headers/cache rules |
| Tenant model | Define and validate the private manifest and public `config.json` templates | Persist tenant records and enforce subdomain/domain/region policy |
| One-click flow | Document and template the contract | Provide the actual UI/API, DNS, TLS, and provisioning |
| Custom domains and TLS | Document records, validation states, health checks, and rollback | Issue/renew certificates and manage DNS and edge routing |
| Health and status | Define checks, thresholds, expected responses, and evidence format | Run checks, alert, and display tenant status |
| BYO storage | Keep credentials and passphrases client-side; document S3/CORS behavior | Make no server-side storage request and store no storage credentials |
| Privacy and support | Publish accurate data-processing, logging, and support-access statements | Enforce roles, access controls, retention, and audit |
| Evidence | Supply verification scripts, checklists, and evidence format | Supply a staging tenant, custom domain, TLS, and operator access |

- Status: Confirmed.
- Owner: Repository maintainers / architecture owner.
- Date: 2026-11-12.
- Rationale: plan section 1.1 and the milestone out-of-scope list; the largest
  M6 risk is boundary creep into a backend or a support data-access path.
- Evidence: plan section 1.1; milestone out of scope; M6 README boundary table.
- Consequence: the repository must reject or escalate any task that needs a
  backend, prompt proxy, key custody, plaintext conversation storage,
  storage-credential custody, or managed storage. The boundary is a hard gate,
  not a preference.

## DEC-M6-03 - Product name, domain, branding owner, and support model

**Decision: the final product and managed-hosting brand is "ChatUI". The base
domain is `chatui.dev`; tenant origins use `<tenant>.chatui.dev`. The branding
owner is the product owner (repository maintainer). Support is provided through
GitHub Issues on a best-effort basis, with no SLA, no guaranteed hours, no
escalation commitment, and no paid support included in the M6 offering.**

- Status: Confirmed.
- Owner: Product owner (repository maintainer).
- Date: 2026-11-12.
- Rationale: this answers spec section 20 question 10 and satisfies the
  milestone entry criteria. The product owner chose a single ChatUI brand and
  retired "roksky" as a public hosting brand.
- Evidence: product-owner answers recorded on 2026-11-12; this decision record.
- Scope limits: this decision does not register the domain or transfer DNS, and
  domain availability was not verified from the repository. Domain
  registration/ownership, DNS, and TLS remain operator deliverables under
  `DEC-M6-10` and `DEC-M6-14`. If `chatui.dev` cannot be owned or delegated, this
  decision must be revised before staging.
- Consequence: M6 plan, milestone, task breakdown, and task files use ChatUI and
  planned paths under `deploy/chatui/`, including `deploy/chatui.md`. Branding
  templates, support copy, and the staging domain may use ChatUI. Historical M4
  and M5 records and the authoritative spec still contain "roksky" references;
  those are historical and require a separate documentation/spec change. The
  existing `deploy/chatui.md` recipe must still be corrected by `M6-02.1` to
  remove the out-of-scope managed-storage claim.

## DEC-M6-04 - Tenant model

**Decision: a private operator manifest plus the public app `config.json`. The
operator manifest holds operational metadata and is not served. The public
`config.json` is the only tenant configuration served to the browser. Neither
artifact may contain provider keys, storage credentials, backup passphrases,
prompts, responses, or conversation content.**

- Status: Confirmed.
- Owner: Contracts/architecture owner.
- Date: 2026-11-12.
- Rationale: plan section 4 recommended default; it separates operational
  metadata from the served public config and keeps secrets out of both.
- Evidence: plan section 4; spec sections 13.8 and 14.3; the existing strict
  public config contract.
- Consequence: `M6-01.2` defines the private manifest schema; `M6-01.3` defines
  the public templates; `M6-01.4` validates both and scans for secrets. A tenant
  record that needs a secret is a design error, not a schema gap.

## DEC-M6-05 - Tenant manifest location

**Decision: the tenant manifest schema lives at
`contracts/schema/hosting-tenant.schema.json`, with fixtures under
`contracts/fixtures/hosting/`.**

- Status: Confirmed.
- Owner: Contracts owner.
- Date: 2026-11-12.
- Rationale: plan section 4 recommended default; contracts remain the source of
  truth for validated formats, and the repository already uses
  `contracts/schema/` and `contracts/fixtures/`.
- Evidence: plan section 4; existing contracts layout.
- Consequence: `M6-01.2` creates the schema and fixtures; `M6-01.4` validates
  them. Do not create a second tenant schema under `deploy/` or `docs/`.

## DEC-M6-06 - Feature flags

**Decision: expose no feature flags in M6. The public `config.json` schema must
not gain an arbitrary flag map. If a real reviewed flag is required later, add a
typed, allowlisted flag shape with tests and a new dated decision.**

- Status: Confirmed.
- Owner: Product/release owner with repository maintainers.
- Date: 2026-11-12.
- Rationale: plan section 4 recommended default; the current strict schema is
  safer than an arbitrary flag map, and no reviewed flag requirement was
  identified for M6.
- Evidence: plan section 4; existing strict public config schema.
- Consequence: `M6-01.3` must not add a flag map or a free-form `features`
  object. Any future flag requires a new decision, a typed allowlist, and tests.

## DEC-M6-07 - Subdomain and region policy

**Decision: subdomains are operator-enforced with lowercase DNS labels,
documented reserved names, length and character-set limits, and collision
handling. "Region" selects the static edge location only. It does not create a
server-side user data store and does not imply data residency. No data-residency
guarantee is made in M6.**

- Status: Confirmed for the semantics. The region list and any residency claim
  remain operator/product/legal deliverables.
- Owner: Hosting operator with product/legal owner.
- Date: 2026-11-12.
- Rationale: plan section 4 recommended default; it avoids implying storage or
  residency that does not exist.
- Evidence: plan section 4; spec section 14.3; milestone risks.
- Consequence: `M6-02.3` documents subdomain and region behavior; `M6-04.1`
  states the data-residency limitation. Any future residency claim requires a
  new decision and legal review. The operator may publish a region list, but the
  repository must not claim that user data is stored in a region.

## DEC-M6-08 - BYO storage only

**Decision: M6 supports BYO S3-compatible storage only. No ChatUI-managed
storage offering is sold or claimed in M6. Storage credentials and backup
passphrases remain client-side and are never sent to ChatUI.**

- Status: Confirmed.
- Owner: Product/release owner (milestone scope).
- Date: 2026-11-12.
- Rationale: the milestone out-of-scope list excludes a managed storage offering
  beyond BYO storage; M5 already delivers the client path; managed storage is a
  separate product and legal decision.
- Evidence: milestone out of scope; spec section 13.8; plan section 4.
- Consequence: `M6-03.1`, `M6-03.2`, and `M6-03.3` document BYO only. No managed
  storage claim, partner storage claim, or server-side storage request may be
  added. Any future partner storage must remain client-side encrypted and
  unreadable by ChatUI.

## DEC-M6-09 - Support-access model

**Decision: support access is least privilege, audited, and non-impersonating.
Support cannot access provider keys, plaintext chats, storage credentials, or
backup passphrases, and cannot recover a lost passphrase. Support may see tenant
metadata and operational logs only as defined by `DEC-M6-12`.**

- Status: Confirmed for the boundary. Detailed roles, audit retention, and
  escalation remain for `M6-04.2` and privacy review.
- Owner: Security/privacy owner with hosting operator.
- Date: 2026-11-12.
- Rationale: the milestone and plan require that support must not become a
  data-access path or a key/chat/passphrase recovery channel.
- Evidence: milestone scope; plan section 4; spec section 14.3.
- Consequence: `M6-04.2` documents the model; `M6-04.3` reviews it. Any
  impersonation, browser-data access, key access, chat access, or passphrase
  recovery feature is out of scope and must be rejected.

## DEC-M6-10 - External staging operator and tenant

**Decision: not yet assigned. No named external ChatUI operator, staging tenant,
custom-domain test path, or TLS/edge access is recorded.**

- Status: Blocked.
- Owner: Hosting operator (unassigned).
- Date: 2026-11-12.
- Rationale: the repository cannot supply a real staging tenant or operator
  access, and the exit gate requires both.
- Evidence: M6 README entry gate unchecked; no operator is named in the
  repository; plan section 10 risk.
- Blocked by: name the external operator and provide a staging tenant, a
  subdomain, a custom-domain test path, TLS certificate/edge access, and a
  contact for staging incidents.
- Consequence: `M6-05.2` and `M6-05.3` cannot run, and `M6-06.3` cannot close.
  Do not substitute container evidence for tenant evidence.

## DEC-M6-11 - Privacy/legal reviewer and approval process

**Decision: the product owner (repository maintainer) is the named privacy/legal
reviewer, using a documented self-review and dated risk acceptance. The approval
process is: review `data-processing.md`, `privacy.md`,
`logging-and-retention.md`, `support-access.md`, `provider-terms.md`, and
`backup-and-restore.md`; record the sign-off in
`docs/operations/M6/evidence/privacy-review.md` with the review scope, date,
open items, and explicit risk acceptance; re-review before publication and after
any material change to data flows, logging, retention, support access, storage,
or subprocessors.**

- Status: Confirmed with recorded risk acceptance. This is not independent
  legal review and does not satisfy a regulated-industry or jurisdiction-specific
  legal requirement.
- Owner: Product owner acting as privacy/legal reviewer.
- Date: 2026-11-12.
- Rationale: no external reviewer was available or named. The product owner chose
  a documented self-review with explicit risk acceptance so documentation can
  proceed without pretending that an independent review occurred.
- Evidence: product-owner answer recorded on 2026-11-12; the dated sign-off
  artifact is produced by `M6-04.3`.
- Consequence: `M6-04.3` may complete when the sign-off records this limitation.
  If an external reviewer is named later, that review supersedes this decision.
  Claims that require legal advice, including data residency, subprocessors, and
  regulated data, must not be published while unresolved.

## DEC-M6-12 - Logging fields and retention periods

**Decision: edge logs may include IP address, user agent, request path, HTTP
status, timing, tenant/subdomain, and request ID. Logs must never include request
or response bodies, prompts, completions, provider keys, storage credentials,
backup passphrases, or conversation content. Retention is 30 days for
edge/access and error logs, and 90 days for audit and support-access records.
Deletion is automatic at the end of each period by log rotation; the operator
must not keep copies beyond these periods in downstream systems. A deletion
request is handled by operator support and confirmed to the requester without
accessing user content.**

- Status: Confirmed.
- Owner: Privacy reviewer (product owner self-review) with hosting operator.
- Date: 2026-11-12.
- Rationale: plan section 4 requires an accurate logging statement; the product
  owner set the shortest workable periods and an automated deletion path.
- Evidence: product-owner answers recorded on 2026-11-12; plan section 4;
  milestone logging requirement.
- Consequence: `M6-04.2` may publish these field lists and retention periods, and
  `M6-04.3` reviews them. The operator must configure rotation to match; if it
  cannot, that is a new recorded blocker and the copy must not claim the period.

## DEC-M6-13 - PWA install name and icon branding

**Decision: PWA install name and icons remain build-time ChatUI branding and are
not tenant-branded in M6. The limitation must be documented. The product owner
confirmed that tenant-branded install name/icons are not required. A
tenant-branded PWA build is not in M6 scope unless the product owner approves a
separate build path and a new decision.**

- Status: Confirmed.
- Owner: Product owner with web/UI owner.
- Date: 2026-11-12.
- Rationale: plan section 4 recommended default; it avoids scope creep, and the
  static build already has a fixed manifest. The product owner confirmed no
  branded build is needed.
- Evidence: plan section 4; existing web build and PWA manifest; product-owner
  answer recorded on 2026-11-12.
- Consequence: `M6-01.3` and `M6-02.1` document the limitation. No
  tenant-specific PWA manifest is generated in M6. A future branded-build
  request is a new scope decision, not a defect in M6.

## DEC-M6-14 - Custom-domain and TLS ownership

**Decision: the external operator owns DNS, certificate issuance and renewal,
edge routing, and TLS failure handling. The repository documents the required
records, validation states, health checks, and rollback behavior. Apex and
`www` policy, certificate expiry monitoring, and renewal ownership must be
documented in the runbook.**

- Status: Confirmed for ownership. The named operator and operational details
  are Blocked by `DEC-M6-10`.
- Owner: Hosting operator with release/security owner.
- Date: 2026-11-12.
- Rationale: plan sections 1.1 and 4 and the boundary table; TLS is
  operationally risky and needs a named owner.
- Evidence: plan sections 1.1 and 4; spec section 14.3.
- Consequence: `M6-02.3` writes the runbook; `M6-05.2` tests the custom-domain
  path. The repository must not add a certificate authority, DNS automation, or
  a server-side TLS control plane.

## DEC-M6-15 - Release pinning and rollback ownership

**Decision: pin an immutable artifact digest and the public config hash
together. Roll back both together, purge the edge cache, and re-run the health
checks. The repository owns the release artifact, pinning metadata, and rollback
runbook; the external operator executes the rollback on the tenant.**

- Status: Confirmed.
- Owner: Release/security owner with hosting operator.
- Date: 2026-11-12.
- Rationale: plan section 4 recommended default; a config/artifact mismatch can
  break a tenant even when the bundle is healthy.
- Evidence: plan section 4; M4 release pipeline.
- Consequence: `M6-02.4` writes the runbook; `M6-05.3` drills it. A mutable
  `latest`-only rollback is not accepted.

## Cross-cutting - Evidence location

**Decision: `docs/operations/M6/evidence/` is the evidence location. All
evidence uses synthetic data only; no real keys, credentials, passphrases,
prompts, responses, or conversation content may be recorded.**

- Status: Confirmed.
- Owner: Repository maintainers with the privacy reviewer.
- Date: 2026-11-12.
- Rationale: plan section 1.3 and the M6 README entry gate.
- Evidence: plan section 1.3; M6 README.
- Consequence: `M6-05.2`, `M6-05.3`, `M6-06.1`, `M6-06.2`, and `M6-06.3` write
  evidence there. Evidence is scanned before commit.

## Blockers

Only `B-M6-02` remains open. The other blockers were resolved by product-owner
decisions on 2026-11-12 and are kept here for traceability.

| ID | Blocker | Decision | Owner needed | Status | Resolution or effect |
| --- | --- | --- | --- | --- | --- |
| `B-M6-01` | Final product name, domain, branding owner, and support model | `DEC-M6-03` | Product owner | Resolved 2026-11-12 | ChatUI, `chatui.dev`, product owner, GitHub Issues best effort |
| `B-M6-02` | Named external operator, staging tenant, custom-domain test path, and TLS/edge access | `DEC-M6-10` | Hosting operator | Open | `M6-05.2`, `M6-05.3`, and `M6-06.3` cannot run or close |
| `B-M6-03` | Named privacy/legal reviewer and approval process | `DEC-M6-11` | Product owner | Resolved 2026-11-12 with risk acceptance | Self-review is recorded; it is not independent legal review |
| `B-M6-04` | Log retention periods and deletion process | `DEC-M6-12` | Product owner with operator | Resolved 2026-11-12 | 30-day edge/error, 90-day audit/support, automated deletion |
| `B-M6-05` | Whether tenant-branded PWA install name/icons are required | `DEC-M6-13` | Product owner | Resolved 2026-11-12 | Not required in M6; build-time ChatUI branding only |

## Entry-gate status

| Entry-gate item | State | Decision or blocker |
| --- | --- | --- |
| M4 exit gate met and phase 1 released | Met | M4 verification, 2026-11-10 |
| M5 complete, BYO encrypted storage onboarding exists | Met | M5 verification, 2026-11-12 |
| User authorizes M6 work and confirms hosting/documentation scope | Met for preflight | `DEC-M6-01`; implementation tasks remain gated by the items below |
| Product name, domain, and support model have named owners | Met | `DEC-M6-03`: ChatUI, `chatui.dev`, product owner, GitHub Issues best effort |
| A ChatUI hosting operator is named with staging tenant, subdomain, custom-domain test path, TLS, and edge access | Blocked | `DEC-M6-10`, `B-M6-02`; operator unassigned |
| A privacy/legal reviewer is named | Met with recorded risk acceptance | `DEC-M6-11`: product owner self-review; not independent legal review |
| Feature-flag decision made | Met | `DEC-M6-06`: expose none in M6 |
| Tenant configuration boundary confirmed | Met | `DEC-M6-04`: private manifest plus public `config.json`, no secrets |
| Evidence location agreed with synthetic data only | Met | Cross-cutting evidence decision |

The M6 folder status stays `Planned` until `B-M6-02` is resolved. Do not start any
M6 implementation task while the gate is blocked. `DEC-M6-01` authorizes the
M6-01 documentation/config scope, but the task ordering rule still blocks later
tasks until `M6-01.1` is `Complete`.

## Dependency consistency check

The later task dependencies still match these decisions. No task file needs a
dependency change.

| Task | Depends on | Decisions used | Consistent |
| --- | --- | --- | --- |
| `M6-01.2` | `M6-01.1` | `DEC-M6-04`, `DEC-M6-05` | Yes |
| `M6-01.3` | `M6-01.1` | `DEC-M6-04`, `DEC-M6-06`, `DEC-M6-13` | Yes |
| `M6-01.4` | `M6-01.2`, `M6-01.3` | `DEC-M6-04`, `DEC-M6-05`, `DEC-M6-06` | Yes |
| `M6-02.1` | `M6-01.4` | `DEC-M6-02`, `DEC-M6-07`, `DEC-M6-14` | Yes |
| `M6-02.2` | `M6-02.1` | `DEC-M6-02`, `DEC-M6-03`, `DEC-M6-10` | Yes; product/support copy is confirmed, staging link still waits on `DEC-M6-10` |
| `M6-02.3` | `M6-01.2`, `M6-01.4`, `M6-02.1` | `DEC-M6-07`, `DEC-M6-14` | Yes |
| `M6-02.4` | `M6-01.2`, `M6-02.1` | `DEC-M6-15` | Yes |
| `M6-03.1` | `M6-01.4` | `DEC-M6-08` | Yes |
| `M6-03.2` | `M6-03.1` | `DEC-M6-08` | Yes |
| `M6-03.3` | `M6-03.1` | `DEC-M6-08` | Yes |
| `M6-04.1` | `M6-01.4`, `M6-02.1`, `M6-03.1` | `DEC-M6-07`, `DEC-M6-08`, `DEC-M6-09`, `DEC-M6-12` | Yes; privacy review uses the recorded self-review risk acceptance |
| `M6-04.2` | `M6-01.4`, `M6-02.1` | `DEC-M6-09`, `DEC-M6-12` | Yes; retention is confirmed |
| `M6-04.3` | `M6-02.3`, `M6-03.3`, `M6-04.1`, `M6-04.2` | `DEC-M6-11` | Yes; reviewer is named by `DEC-M6-11` (self-review, risk accepted) |
| `M6-05.1` | `M6-01.4`, `M6-02.1` | `DEC-M6-14`, `DEC-M6-15` | Yes |
| `M6-05.2` | `M6-02.2`, `M6-02.3`, `M6-02.4`, `M6-03.2`, `M6-03.3`, `M6-04.3`, `M6-05.1` | `DEC-M6-10`, `DEC-M6-11` | Yes; staging is blocked |
| `M6-05.3` | `M6-05.2` | `DEC-M6-15` | Yes |
| `M6-06.1` | `M6-04.3`, `M6-05.3` | `DEC-M6-02`, `DEC-M6-09` | Yes |
| `M6-06.2` | `M6-05.3` | `DEC-M6-02`, `DEC-M6-08` | Yes |
| `M6-06.3` | `M6-06.1`, `M6-06.2`, all prior tasks | All decisions | Yes |
