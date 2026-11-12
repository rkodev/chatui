# M6 Managed ChatUI Hosting Implementation Plan

- Source milestone: [M6-managed-hosting.md](../milestones/M6-managed-hosting.md)
- Authoritative spec: [IMPLEMENTATION.md](../IMPLEMENTATION.md) sections 13.8, 14.2-14.3, 17 (M6), 19
- Current baseline: [M4 verification](M4/M4-verification.md) (phase 1 gate passed 2026-11-10)
- Backup baseline: [M5 verification](M5/M5-verification.md) (M5 complete 2026-11-12)
- Current parity: [contracts/parity.md](../../contracts/parity.md)
- Status: Planned - entry gate blocked on the hosting-operator/staging decision (`DEC-M6-10` / `B-M6-02`)
- Prepared: 2026-11-12
- Last updated: 2026-11-12 (ChatUI brand rename; preflight decisions recorded)
- Scope: Optional ChatUI-managed static hosting for the web client, tenant configuration and branding, BYO encrypted storage onboarding, privacy documentation, deployment templates, runbooks, and staging validation
- Platforms: Web hosting only
- Estimate: 1-2 weeks for repository artifacts and documentation; 2-4 weeks to close the gate with an external staging tenant, custom domain/TLS, and legal review
- Hard constraint: No backend application, server-side prompt proxy, key custody, plaintext conversation storage, or backup-passphrase access

## 1. Summary

M6 packages the already static web client for an optional ChatUI-managed hosting
flow. ChatUI serves the same static bundle that the container and static-host
recipes serve, manages tenant subdomains, custom domains, and TLS, and never
proxies provider requests or stores user secrets. The user configures provider
keys and optional BYO encrypted storage in the browser after deployment.

The repository already contains most of the web substrate M6 needs: a strict,
public-only runtime `config.json`, the static container and host recipes, the
section 14.1 header and cache contract, the `/healthz` endpoint, M5 S3-compatible
storage adapters with a connectivity test, and M5 backup/restore evidence. It
does **not** contain a tenant deployment model, a `deploy/chatui/` template
tree, `docs/hosting/` tenant documentation, privacy and data-processing copy, a
tenant rollback runbook, or any staging-tenant validation. The remaining gap is
mostly hosting operations and documentation, not client application code.

The largest risk is boundary creep: "managed hosting" can quietly become a
backend, and support tooling can quietly become a data-access path. This plan
keeps the repository's role to static artifacts, public tenant configuration,
templates, runbooks, documentation, and evidence. Any actual ChatUI control
plane, DNS/TLS automation, billing, or support portal is an external operator
deliverable. If that control plane must be built in this repository, M6 is no
longer a hosting/documentation milestone and requires a separate approved scope.

| Workstream | Current completion | Assessment |
| --- | --- | --- |
| Tenant configuration and branding | Partial | Public app config and strict validation exist; no tenant manifest, templates, feature-flag decision, or hosting docs |
| One-click hosting flow | Missing | Only a manual recipe in `deploy/chatui.md`; no "Deploy to ChatUI" entry point or tenant provisioning contract |
| BYO storage onboarding | Partial | M5 UI, adapters, connectivity test, and CORS operations doc exist; no tenant-facing onboarding, links, or consolidated failure-state guide |
| Privacy and data-processing documentation | Missing | No tenant data-processing summary, logging/retention statement, or support-access statement |
| Deployment templates and runbooks | Partial | Generic static deployment is complete; no `deploy/chatui/`, tenant config review, health definitions, rollback runbook, or `docs/hosting/` |
| Validation and security review | Missing | M4 container smoke evidence exists; no staging tenant, tenant header/CSP evidence, config secret review, portability proof, or security sign-off |

### 1.1 Repository and external-operator boundary

| Deliverable | Repository responsibility | External ChatUI operator responsibility |
| --- | --- | --- |
| Static app artifact | Build `web/dist`, publish the container and web artifact, keep the public config contract stable | Serve the artifact on the tenant origin and apply edge headers/cache rules |
| Tenant model | Define and validate the tenant manifest and public `config.json` templates | Persist tenant records and enforce subdomain/domain/region policy |
| One-click entry point | Document and template the flow; provide a link/action placeholder and validation contract | Provide the actual "Deploy to ChatUI" UI or API, DNS, TLS, and provisioning |
| Custom domains and TLS | Document required records, validation states, and rollback behavior | Issue/renew certificates and manage DNS and edge routing |
| Health and status | Define checks, thresholds, expected responses, and operator/user status surfaces | Run checks, alert, and display tenant status |
| BYO storage | Keep credentials and passphrases client-side; document S3/CORS/connectivity behavior | Make no server-side storage request and store no storage credentials |
| Privacy and support | Publish accurate data-processing, logging, and support-access statements | Enforce support roles, access controls, retention, and audit |
| Validation | Supply the verification script, checklist, and evidence format | Supply a staging tenant, custom domain, TLS, and operator access |

M6 cannot close on repository work alone. The exit gate requires a real staging
tenant and an external owner for brand, domain, support, and privacy review.

### 1.2 Critical path

```text
M6-01 Preflight, decisions, tenant schema, branding, public config templates
   |
   +--> M6-02 Deployment templates, one-click flow, health checks, rollback
   |
   +--> M6-03 BYO storage onboarding, CORS guidance, failure states
   |
   +--> M6-04 Privacy, data-processing, logging, and support-access docs
   |
   +--> M6-05 Staging tenant deployment and validation evidence
           |
           +--> M6-06 Security review, self-host/export portability, exit gate
```

`M6-02`, `M6-03`, and `M6-04` may run in parallel after `M6-01` if they do not
edit the same files. `M6-05` needs the templates, storage docs, and privacy copy
so the staging tenant is tested against the final contract. `M6-06` is the final
gate and cannot start until `M6-05` is complete.

### 1.3 Entry gate

Implementation may not start until all of the following are true:

- [x] M4 exit gate is met and phase 1 is released (`M4/M4-verification.md`, 2026-11-10).
- [x] M5 is complete, so BYO encrypted storage onboarding exists (`M5/M5-verification.md`, 2026-11-12).
- [x] The user explicitly authorizes M6 work and confirms it remains a hosting/documentation milestone, not a backend build (`DEC-M6-01`, 2026-11-12).
- [x] The product name, domain, and support model from spec section 20 question 10 are recorded (`DEC-M6-03`: ChatUI, `chatui.dev`, product owner, GitHub Issues best effort).
- [ ] A ChatUI hosting operator is named and can provide a staging tenant, subdomain, custom-domain test path, TLS, and edge configuration access (`DEC-M6-10` / `B-M6-02`).
- [x] A privacy reviewer is named for the data-processing, logging, retention, and support-access copy (`DEC-M6-11`: product owner self-review with risk acceptance; not independent legal review).
- [x] The feature-flag decision is made: expose none in M6 (`DEC-M6-06`).
- [x] The tenant configuration boundary is confirmed: private operator manifest plus public app `config.json`, with no secrets in either served artifact (`DEC-M6-04`).
- [x] The M6 evidence location is agreed: `docs/operations/M6/evidence/` with synthetic data only (cross-cutting evidence decision).

If an entry item is unresolved, record it as a blocker in `M6-01`; do not
silently start a narrower implementation that cannot close the gate.

## 2. Current-State Audit

### 2.1 Reusable assets

| Asset | Current state | Plan impact |
| --- | --- | --- |
| `web/public/config.json` | Public runtime config: app name, theme, default preset, enabled presets, custom-provider allowance, OpenRouter attribution, docs URL, telemetry | Reuse as the public tenant config contract; add templates, not a second config format |
| `web/src/app/config.ts` | Strict Zod schema; rejects unknown fields, malformed JSON, unknown presets, and invalid combinations; fetches with `no-store` | Extend only if M6 accepts feature flags; otherwise keep strict and document no flags |
| `web/src/app/config.test.ts` | Schema and preset validation tests, including checked-in config parse | Extend for tenant template validation if config changes |
| `deploy/Caddyfile` | SPA fallback, exact section 14.1 CSP/security headers, cache rules, static `/healthz` | Source of truth for tenant edge template and header verification |
| `deploy/Dockerfile` | Builds `web/dist` and serves it with Caddy; no application secrets or environment variables | Reuse as the portability and self-host reference |
| `deploy/docker-compose.yml` | One-command static deployment with `/healthz` healthcheck | Reuse for local staging baseline |
| `deploy/cloudflare-pages/_headers` | Header/cache contract for Cloudflare Pages | Reuse or adapt for the ChatUI edge if Cloudflare is the substrate |
| `deploy/netlify.toml`, `deploy/vercel.json` | SPA fallback and header rules for other static hosts | Keep as portability evidence; not the primary ChatUI template |
| `deploy/chatui.md` | Manual tenant recipe: build, upload, subdomain/domain, edge headers, config, smoke commands | Consolidate into `deploy/chatui/`; correct the managed-storage-partner claim |
| `docs/operations/M4/deployment.md` | Deployment, header, cache, config, and troubleshooting contract | Link as the canonical static contract from `docs/hosting/` |
| `docs/operations/M4/evidence/deployment/README.md` | Recorded container headers, `/healthz`, SPA fallback, and image secret-pattern scan | Reuse method for tenant evidence; do not treat container evidence as tenant evidence |
| `docs/operations/M5/web-s3-cors.md` | Required CORS, bucket policy, provider notes, troubleshooting, security notes | Re-export or link from tenant onboarding; add per-tenant origin guidance |
| `web/src/features/backup/storage/` | S3-compatible first, plus Azure Blob, GCS, Google Drive, and WebDAV adapters with `testConnection` | No new backend work required; use as the onboarding implementation |
| `web/src/features/settings/BackupSettingsScreen.tsx` | Backup form, connectivity test, status, manual backup/restore/delete, passphrase warning, client-side statement | Link from hosted onboarding docs; no server-side storage credential path exists |
| `web/src/features/settings/DataExportPanel.tsx` | "Export all data (JSON)", per-conversation JSON/Markdown, optional attachment bytes | Reuse as the application "export everything" path; document it in hosting docs |
| `web/src/features/settings/AboutSettingsScreen.tsx` | Privacy summary: local data unencrypted at rest, device-bound keys, direct provider requests, no analytics | Use as a building block for the tenant-facing privacy statement; it is not a data-processing summary |
| `docs/operations/M5/evidence/` | Cross-client restore, storage inspection, conflict/safe-failure, scheduling evidence | Link for BYO storage validation; do not duplicate or fabricate tenant evidence |
| `.github/workflows/web.yml`, `contracts.yml` | Static build, contract validation, release container publishing | Add tenant template/config validation if new schemas or scripts land |

### 2.2 Confirmed missing or unproven areas

| Capability | Current state | Consequence |
| --- | --- | --- |
| Tenant deployment manifest | Missing | No validated model for subdomain, region, custom domain, release pin, config hash, or status |
| Tenant public config templates | Missing | One checked-in config only; no minimal/full example or placeholder policy for branding |
| Feature-flag contract | Missing | The spec lists optional feature flags, but the strict public config has no such field |
| `deploy/chatui/` templates | Missing | Tenant edge configuration, health checks, and rollback are not reproducible from the repository |
| `docs/hosting/` | Missing | Tenant operators and users have no onboarding, custom-domain, BYO storage, privacy, export, or rollback docs |
| "Deploy to ChatUI" entry point | Missing | Only a manual recipe exists; there is no one-click action or documented external handoff |
| Custom-domain and TLS runbook | Missing | No DNS/domain verification, certificate state, renewal, or failure procedure |
| Tenant status and health definitions | Partial | Static `/healthz` exists; no tenant-level status, alert thresholds, operator view, or user-visible status |
| Rollback runbook | Missing | No previous-release/config rollback, cache invalidation, or post-rollback verification |
| Tenant config review | Missing | No schema lint plus secret scan and approval step before publishing |
| Automated tenant header/CSP check | Missing | The M4 recipe is manual `curl` commands; no repeatable pass/fail script for a tenant URL |
| Privacy/data-processing docs | Missing | ChatUI cannot publish an accurate account of what it can and cannot see |
| Logging/retention statement | Missing | Edge logs and retention are undefined for tenants |
| Support-access statement and controls | Missing | No documented least-privilege/no-impersonation model or audit trail |
| Existing managed-storage-partner claim | Incorrect/out of scope | `deploy/chatui.md` advertises an optional managed storage partner that M6 explicitly excludes |
| Tenant staging evidence | Missing | No tenant URL, header output, config hash, export/restore result, or rollback drill record |
| Self-host/export portability proof | Missing | The config contract is shared, but no M6 evidence proves a tenant can export and re-host the same build/config |
| Security/no-data-access review | Missing | No data-flow diagram, support-access review, edge-log inspection, or no-hidden-network verification for a tenant |

### 2.3 Test and evidence baseline

- `make contracts-check` validates providers, schemas, fixtures, and parity, but
  there is no tenant manifest schema or hosting fixture.
- `web/src/app/config.test.ts` proves the current public config is strict and
  fail-closed, but does not validate tenant templates or feature flags.
- `docs/operations/M4/evidence/deployment/README.md` proves the container
  headers, cache rules, `/healthz`, SPA fallback, and image secret scan. It is
  not evidence for a tenant subdomain, custom domain, or edge configuration.
- `docs/operations/M5/evidence/` proves encrypted BYO storage restore,
  conflict handling, and storage inspection. M6 still needs a tenant-facing
  onboarding walkthrough and a staging connectivity/CORS check.
- There is no `docs/operations/M6/` directory, no `M6-verification.md`, and no
  M6 evidence artifact.
- No test records that a deployed tenant's `config.json` contains no secrets or
  that the same public config can self-host the exported build.

## 3. Gap Analysis

Status values:

- **Missing**: no implementation or documentation exists.
- **Partial**: a foundation exists, but the deliverable is not met or not proven.
- **Present**: complete, subject only to regression verification.
- **Blocked**: cannot proceed until an entry-gate decision or external dependency is resolved.

### 3.1 Tenant configuration and branding (T)

| ID | Required deliverable | Current | Gap | Resolution | Verification |
| --- | --- | --- | --- | --- | --- |
| T-01 | Tenant deployment manifest schema | Missing | No schema for subdomain, region, custom domain, release pin, config hash, status, or support contact | Add `contracts/schema/hosting-tenant.schema.json` plus fixtures under `contracts/fixtures/hosting/`; validate in `scripts/validate-contracts.mjs` | `make contracts-check`; broken-vector tests |
| T-02 | Public config templates | Partial | Only `web/public/config.json`; no full/minimal tenant templates | Add `deploy/chatui/templates/config.full.json` and `config.minimal.json` using only the existing public fields | Template parses with `configSchema`; web config tests |
| T-03 | Branding fields | Partial | `appName`, `defaultTheme`, presets, custom-provider allowance, OpenRouter attribution, docs URL, telemetry exist; feature flags do not | Keep existing fields; decide and document feature flags; do not add tenant-only fields to the served app config unless the app consumes them | Config schema tests; template review |
| T-04 | Feature flags | Missing | Spec lists optional feature flags; strict schema rejects any unknown field | Prefer no flags in M6 and record that decision; if flags are required, add a typed allowlist object and tests, never an arbitrary map | Decision record; config tests |
| T-05 | Subdomain and region constraints | Missing | No rules for name length, reserved names, region list, or collision behavior | Define constraints in the tenant schema and `docs/hosting/tenant-configuration.md`; operator enforces uniqueness | Schema fixtures; docs review |
| T-06 | Custom domain and TLS metadata | Missing | No domain status, verification state, or certificate state in a tenant model | Add fields and states to the operator manifest; document that ChatUI owns DNS/TLS execution | Schema tests; runbook review |
| T-07 | Public-only enforcement and secret review | Partial | App config is strict and public-only; no tenant-side review or scan | Add `scripts/validate-tenant-config.mjs` that validates schema, exact origin, `telemetry: false`, and secret patterns; require review before publish | Script tests; config review evidence |
| T-08 | OpenRouter attribution origin per tenant | Partial | `openRouter.attributionOrigin` exists but defaults to `chat.example.com` | Template must use the actual tenant origin, including a custom domain; document the pairing with CORS | Config validation; header inspection |
| T-09 | Self-host portability contract | Partial | The same config contract is intended, but no M6 proof exists | Document the export/self-host path and run the static container with the tenant config | Portability evidence in `M6-06` |
| T-10 | Tenant configuration documentation | Missing | No `docs/hosting/` | Add tenant configuration, branding, and review docs under `docs/hosting/` | Docs review |
| T-11 | PWA/install branding | Partial | The PWA manifest name and icons are static; `appName` is runtime config | Decide whether M6 requires install-name/icon branding; if yes, add a build-time or manifest strategy; if no, document the limitation | Decision record; optional manifest test |
| T-12 | Configuration ownership and approval | Missing | No named owner or review step before a tenant config is published | Add an approval checklist with product/branding owner and security reviewer | Review record |

### 3.2 One-click hosting flow (H)

| ID | Required deliverable | Current | Gap | Resolution | Verification |
| --- | --- | --- | --- | --- | --- |
| H-01 | "Deploy to ChatUI" entry point | Missing | No deploy action, link, workflow, or documented external handoff | Document the flow in `docs/hosting/one-click.md`; add an external link/action template owned by the ChatUI operator; do not invent a repo backend | Link check; docs review; staging flow walkthrough |
| H-02 | Static bundle transport | Present | CI uploads `web/dist` and publishes the container; no tenant handoff format | Reuse the artifact; add release-pin metadata to the tenant manifest | Artifact digest matches tenant record |
| H-03 | Tenant provisioning record | Missing | No record of subdomain, region, release, config hash, or status | Use the M6-01 tenant manifest; operator stores it; repository provides examples only | Schema validation; staging record |
| H-04 | TLS management | Missing | No certificate or renewal procedure | Document operator ownership, certificate states, expiry checks, and failure handling | Staging TLS check; certificate expiry inspection |
| H-05 | Tenant health and status | Partial | `/healthz` is a static 200; no tenant-level status or user-visible status | Define operator checks and a user status link; document thresholds and alert ownership | Health check script; status page review |
| H-06 | Rollback | Missing | No rollback runbook | Document previous artifact/config rollback, cache invalidation, health/header re-check, and incident record | Rollback drill in `M6-05` |
| H-07 | "Export everything" path | Partial | App data export and encrypted backup exist; tenant config/export documentation is missing | Document data export, backup, public config download, and self-host steps in one `docs/hosting/export.md` | Manual export/self-host check in `M6-05` |
| H-08 | No prompt proxying or key custody | Present | No server component exists; provider requests are browser-direct | Keep the architecture; re-verify the tenant network path and document it | Network audit in `M6-06` |
| H-09 | Support access boundaries | Missing | No support-access policy or enforcement description | Add privacy/support docs and review operator roles, impersonation, and audit | Support-access review in `M6-06` |
| H-10 | Release pinning and provenance | Missing | No tenant release version, artifact digest, or config hash | Add release pin fields to the tenant manifest and require immutable artifact references | Manifest validation; rollback drill |
| H-11 | Region behavior and data residency | Missing | "Region" is not defined beyond the spec flow | Define what region controls, which logs exist, and the user-facing residency statement | Docs review with privacy owner |
| H-12 | Tenant teardown and export | Missing | No delete/export procedure for a tenant | Document export-before-delete, DNS/TLS cleanup, and config retention | Teardown drill in `M6-05` or `M6-06` |

### 3.3 BYO storage onboarding (S)

| ID | Required deliverable | Current | Gap | Resolution | Verification |
| --- | --- | --- | --- | --- | --- |
| S-01 | S3-compatible onboarding | Present | Backup settings and S3 adapter exist and are M5-complete | Link the in-app flow from tenant docs; keep S3-compatible first in the walkthrough | Manual MinIO onboarding |
| S-02 | Connectivity test | Present | `testConnection` and UI success/failure states exist | Document where the test is and what each result means | Adapter tests; staging connectivity run |
| S-03 | CORS guidance | Partial | Guidance exists at `docs/operations/M5/web-s3-cors.md`, not in tenant onboarding | Re-export or link it from `docs/hosting/byo-storage.md`; keep one canonical source | Docs link and review |
| S-04 | Custom-domain CORS origin | Missing | Examples use a generic `https://chatui.example.com`; custom domains change the origin | Require the exact tenant origin in CORS; template it from the tenant manifest | CORS preflight test from the tenant origin |
| S-05 | Client-side credentials/passphrase statement | Partial | Backup UI states it; tenant docs do not | Add the statement to onboarding and backup docs; never describe a server-side credential path | Copy review |
| S-06 | Failure-state matrix | Partial | Adapters map auth/permission/not_found/conflict/network/server errors; no consolidated user guide | Document invalid credentials, CORS, unreachable endpoint, bucket permission, and conflict behavior | Docs review; adapter tests |
| S-07 | Backup/restore documentation links | Missing | No hosted onboarding links to M5 backup/restore behavior | Link `M5/README.md`, the backup settings screen, and M5 restore evidence | Docs link check |
| S-08 | Provider terms and user responsibility | Missing | No tenant-facing statement that the user owns provider and storage terms | Add to BYO storage and privacy docs | Legal/privacy review |
| S-09 | Managed storage partner claim | Wrong/out of scope | `deploy/chatui.md` says an optional managed storage partner can be enabled; M6 excludes that offering | Remove or mark as future/not offered in M6; keep BYO storage only | Docs review; milestone scope check |
| S-10 | No server-side storage credentials | Present | Adapters and UI keep credentials in browser storage; no server endpoint exists | Preserve; include in data-flow review | Architecture review; network audit |

### 3.4 Privacy and data-processing documentation (P)

| ID | Required deliverable | Current | Gap | Resolution | Verification |
| --- | --- | --- | --- | --- | --- |
| P-01 | Data-processing summary | Missing | No tenant-facing account of what ChatUI can and cannot see | Write `docs/hosting/data-processing.md` with a technical data-flow diagram and explicit non-access claims | Technical review; owner/legal sign-off |
| P-02 | Logging and retention statement | Missing | No statement for edge logs, IP/user-agent handling, or retention | Define log fields, exclusions, retention periods, access, and deletion; state no prompts/responses/keys/passphrases in logs | Review of actual edge log config; docs review |
| P-03 | Support-access statement | Missing | No statement or control description | State support cannot recover keys, read chats, restore a passphrase, or access encrypted backups; document roles and audit | Support-access review in `M6-06` |
| P-04 | Provider terms responsibility | Missing | No hosted statement that the user is responsible for provider terms and storage | Add to privacy and BYO storage docs | Docs review |
| P-05 | No hidden network destinations | Partial | M4 verified the static app; no tenant-facing statement | Re-verify the tenant build and publish the statement with a link to the provider catalog | Network audit in `M6-06` |
| P-06 | Technical attestation for privacy claims | Missing | Copy would otherwise be aspirational | Add a data-flow description: static edge, browser-direct provider calls, browser-direct storage calls, no request-body logging | Architecture review |
| P-07 | Region and data residency statement | Missing | No residency statement tied to tenant region | Draft with the privacy owner; explain what region affects and what data ChatUI does not hold | Legal review |
| P-08 | Review and approval owner | Missing | No named reviewer | Record the privacy/legal reviewer and dated approval in `M6-04` and `M6-verification.md` | Signed review record |
| P-09 | Passphrase recovery expectation | Partial | Backup UI warns that a lost passphrase is unrecoverable; hosted docs do not | Repeat prominently in tenant onboarding and support docs | Copy review |
| P-10 | Subprocessors/third parties | Missing | May be required by legal review if ChatUI uses DNS, CDN, or logging vendors | List only actual providers after legal review; do not invent a list | Legal review |

### 3.5 Deployment templates and runbooks (D)

| ID | Required deliverable | Current | Gap | Resolution | Verification |
| --- | --- | --- | --- | --- | --- |
| D-01 | `deploy/chatui/` template tree | Missing | No tenant edge template, manifest examples, or template README | Add a template tree that reproduces SPA fallback, headers, cache rules, `/healthz`, and config serving | Template diff against `deploy/Caddyfile`; staging verification |
| D-02 | `docs/hosting/` | Missing | No tenant-facing or operator-facing hosting docs | Add overview, tenant config, branding, one-click, custom domains, BYO storage, privacy, export, health, and rollback docs | Docs link check and review |
| D-03 | Consolidate `deploy/chatui.md` | Partial | A standalone recipe exists with an out-of-scope managed-storage claim and M4 evidence path | Move the recipe into `deploy/chatui/README.md`; leave a pointer or update all links; correct scope claims | Link check; docs review |
| D-04 | Header/CSP verification procedure | Partial | Manual `curl` checks exist; no repeatable pass/fail script | Add `scripts/verify-hosted-deployment.mjs <url>` with exact assertions and nonzero exit on failure | Script run against staging |
| D-05 | Staging deployment | Missing | No tenant staging environment or evidence | Deploy the static build and templates to staging with a subdomain and test custom domain | Staging smoke in `M6-05` |
| D-06 | Rollback runbook | Missing | No previous-release/config rollback procedure | Document artifact/config rollback, cache purge, health/header verification, and incident record | Rollback drill |
| D-07 | Health-check definitions | Partial | `/healthz` exists; no tenant-level definition or ownership | Define `/`, `/config.json`, `/healthz`, asset, deep-route, TLS, header, and config-hash checks with thresholds | Script and runbook review |
| D-08 | Release pinning and provenance | Missing | No tenant artifact digest or config hash record | Require immutable release references in the tenant manifest; record the digest and hash | Manifest validation; staging record |
| D-09 | Tenant config review checklist | Missing | No schema, secret, origin, or telemetry review step | Add a checklist and run it before every publish | Review evidence |
| D-10 | CI validation for hosting artifacts | Missing | No workflow validates tenant templates or configs | Add contract/schema validation and a template lint step to CI | CI result |
| D-11 | M6 evidence directory | Missing | No `docs/operations/M6/evidence/` | Add evidence README and artifacts for staging, headers, config, export, restore, rollback, and review | Evidence review |
| D-12 | Operator and user status communication | Missing | No status surface or communication procedure | Document operator status and a user-facing status link or message; define incident updates | Status review |

### 3.6 Validation and security review (V)

| ID | Required deliverable | Current | Gap | Resolution | Verification |
| --- | --- | --- | --- | --- | --- |
| V-01 | Staging tenant smoke test | Missing | No tenant load, provider configuration, chat, export, or restore run | Run the full staging scenario with synthetic data and no recorded secrets | `M6-05` evidence |
| V-02 | Tenant header/CSP evidence | Missing | Only container evidence exists | Run the new verification script and record raw headers for shell, config, health, deep route, and an asset | `M6-05` evidence |
| V-03 | Tenant config secret review | Missing | No tenant config review exists | Validate the public config and scan templates/deployed config for secret patterns; record result | Review evidence |
| V-04 | Self-host/export portability | Missing | No M6 proof | Export the tenant public config, serve the same build with the container, and run the same checks | Portability evidence |
| V-05 | No key/chat/passphrase access path | Missing | No tenant data-flow or support-access review | Diagram and review the static edge, browser-direct provider/storage calls, and support roles; record no access path | `M6-06` review |
| V-06 | No hidden destinations or prompt proxy | Partial | M4 verified the app; not re-verified for the tenant | Record a browser network trace and edge configuration review; allow only same-origin, provider, and user-storage origins | Network audit evidence |
| V-07 | Manual export/restore using M5 | Missing | No tenant-specific export/restore run | Export all data, restore encrypted backup from MinIO or the user bucket, and record results with synthetic data | `M6-05` evidence |
| V-08 | Rollback drill | Missing | No rollback has been exercised | Deploy a second release, roll back to the first, and verify app/config/headers/health | `M6-05` evidence |
| V-09 | Privacy/legal review | Missing | No review exists | Review data-processing, logging, retention, support, region, and provider-terms copy | Dated review record |
| V-10 | Security sign-off | Missing | No final security review | Review boundary, support access, secret handling, network destinations, and rollback | `M6-06` sign-off |
| V-11 | Regression gates | Present | Existing contracts/web/release gates exist | Re-run if M6 touches config, schemas, or scripts; otherwise record why not applicable | Commands in section 9 |
| V-12 | Teardown/export test | Missing | No procedure or evidence | Export tenant config/data, remove DNS/TLS, and verify the exported build remains usable | Teardown evidence |

## 4. Design Decisions to Freeze in M6-01

These decisions must be recorded before implementation. The recommended defaults
are shown; the product owner, hosting operator, or privacy owner must confirm
the blocked items.

| Decision | Recommended default | Rationale |
| --- | --- | --- |
| Hosting boundary | Repository owns static artifacts, tenant schema, templates, docs, and evidence; external operator owns control plane, DNS, TLS, status, and support tooling | Keeps M6 a hosting/documentation milestone and prevents an accidental backend |
| Tenant model | Private operator manifest plus public app `config.json` | Separates operational metadata from the served public config and avoids secrets in the wrong place |
| Tenant manifest location | `contracts/schema/hosting-tenant.schema.json` plus fixtures | Contracts remain the source of truth for validated formats |
| Public config templates | Full and minimal examples under `deploy/chatui/templates/` | Operators can brand a tenant without inventing fields |
| Feature flags | Expose none in M6 unless a real reviewed flag is required; if required, add a typed allowlist | The current strict schema is safer than an arbitrary flag map |
| Subdomain policy | Document reserved names, length, character set, and collision handling; operator enforces uniqueness | Clients and operators need one rule |
| Region semantics | Region selects the static edge location only; no server-side user data store | Avoids implying data residency or storage that does not exist |
| Custom domains | Supported through operator DNS/TLS; apex and `www` policy documented; certificate expiry monitored | TLS is operationally risky and must have an owner |
| Health check | `/`, `/config.json`, `/healthz`, one hashed asset, one deep route, TLS, exact headers, and config hash | Covers the actual static contract, not just process liveness |
| Rollback | Pin immutable artifact digest and config hash; roll back both together; purge cache; re-run checks | A config/artifact mismatch can break a tenant even when the bundle is healthy |
| Logging/retention | Edge logs may include IP, user-agent, path, status, and timing; never request bodies, prompts, keys, or passphrases; retention set by privacy owner | Needed for an accurate data-processing statement |
| Support access | Least privilege, no impersonation, no browser data access, audited operator actions; no key/chat/passphrase recovery | Support must not become a data-access path |
| BYO storage scope | S3-compatible first, then the M5-supported backends; no ChatUI-managed storage in M6 | M5 already delivers the client path; managed storage is explicitly out of scope |
| Export path | Browser data export plus encrypted backup plus public config download and self-host recipe | Meets "export everything" without a server-side export service |
| PWA branding | Document that install name/icons remain build-time; decide whether M6 needs a branded build | Avoid scope creep unless install branding is a product requirement |
| Privacy reviewer | Name one owner and record a dated approval before publishing | The exit gate requires accurate, reviewed copy |

## 5. Task Breakdown

M6 is a hosting/documentation milestone. The six workstreams below match the
milestone guidance. The detailed task breakdown is in
[M6/README.md](M6/README.md); do not start `M6-02` or later from an earlier
milestone task, and do not start any M6 task until the entry gate is open.

### 5.1 Ordered task list

| Order | Task | Depends on | Area | Parallel-safe with |
| --- | --- | --- | --- | --- |
| 1 | M6-01 Preflight, decisions, tenant schema, branding, and public config templates | none | preflight, contracts, config | none; blocks all |
| 2 | M6-02 Deployment templates, one-click flow, health checks, and rollback runbooks | M6-01 | hosting templates, runbooks | M6-03, M6-04 |
| 3 | M6-03 BYO storage onboarding, CORS guidance, and failure states | M6-01 | storage onboarding | M6-02, M6-04 |
| 4 | M6-04 Privacy, data-processing, logging, and support-access documentation | M6-01 | privacy/legal docs | M6-02, M6-03 |
| 5 | M6-05 Staging tenant deployment, header/CSP validation, config review, and evidence | M6-02, M6-03, M6-04 | validation | none; needs staging |
| 6 | M6-06 Security review, self-host/export portability, and exit gate | M6-05 | security/evidence | none; final gate |

### 5.2 Task details

#### M6-01 - Preflight, decisions, tenant schema, branding, and public config templates

- Confirm the entry gate, phase authorization, brand/domain/support owners,
  privacy reviewer, staging tenancy, and the repository/external-operator
  boundary.
- Record the decisions in section 4 in `docs/operations/M6/M6-decisions.md`.
- Define the private tenant manifest and its states: subdomain, region, custom
  domain, TLS status, release tag/digest, config hash, health status, support
  contact, and rollback target.
- Add `contracts/schema/hosting-tenant.schema.json`, fixtures, and validator
  coverage. Do not put secrets or private credentials in the manifest.
- Add full and minimal public config templates under
  `deploy/chatui/templates/`. Use only fields the app consumes.
- Decide feature flags. If M6 exposes none, record that; if it exposes any, add a
  typed allowlist to `web/src/app/config.ts`, tests, and an example.
- Add `scripts/validate-tenant-config.mjs` for schema validation, exact
  OpenRouter origin, `telemetry: false`, and secret-pattern rejection.
- Add `docs/hosting/tenant-configuration.md` and `docs/hosting/branding.md`.
- Verify: `make contracts-check`, config tests, template validation, and a
  negative test for a secret-shaped value or unknown field.

#### M6-02 - Deployment templates, one-click flow, health checks, and rollback runbooks

- Add `deploy/chatui/` with a README, edge configuration template that
  reproduces `deploy/Caddyfile` behavior, tenant manifest examples, and a
  release-pin example.
- Define the one-click flow contract in `docs/hosting/one-click.md`: external
  operator UI/API link, inputs, outputs, failure states, and what the user does
  after deployment. Do not implement a control-plane backend.
- Document custom domains and TLS in `docs/hosting/custom-domains.md`: DNS
  records, ownership verification, apex/`www` behavior, certificate states,
  renewal, expiry monitoring, and failure handling.
- Define health checks and status in `docs/hosting/health-and-status.md`:
  checks, expected responses, thresholds, alert ownership, operator view, and
  user-facing status.
- Write the rollback runbook in `docs/hosting/rollback.md`: artifact and config
  rollback, cache invalidation, health/header verification, communication, and
  incident record.
- Move the existing recipe into `deploy/chatui/README.md` and remove or qualify
  the managed-storage-partner claim. Update references from
  `docs/operations/M4/deployment.md` and elsewhere.
- Verify: template diff against `deploy/Caddyfile`, manifest validation, link
  checks, and a dry-run rollback tabletop before staging.

#### M6-03 - BYO storage onboarding, CORS guidance, and failure states

- Add `docs/hosting/byo-storage.md` as the tenant-facing S3-compatible
  onboarding guide. Link the canonical CORS and bucket-policy details from
  `docs/operations/M5/web-s3-cors.md`.
- Require the exact tenant origin, including a custom domain, in CORS. Explain
  that CORS is not authorization and that the access key still needs
  least-privilege bucket permissions.
- Document connectivity testing, invalid credentials, CORS/preflight failure,
  unreachable endpoints, missing `ListBucket`, bucket permission errors,
  conflicts, and eventual-consistency behavior.
- Add `docs/hosting/backup-and-restore.md` linking the in-app backup flow, M5
  restore behavior, passphrase-loss warning, and M5 evidence. Keep the
  statement that storage credentials and backup passphrases remain client-side
  and are never sent to ChatUI.
- State that the user is responsible for provider terms and their own storage.
- Remove the managed-storage-partner implication from all M6 docs.
- Verify: docs review, adapter tests, a local MinIO connectivity run, and a
  manual invalid-credentials/CORS failure check using synthetic data.

#### M6-04 - Privacy, data-processing, logging, and support-access documentation

- Add `docs/hosting/privacy.md` and `docs/hosting/data-processing.md` covering
  what ChatUI can see, what it cannot see, provider-direct traffic, BYO storage
  traffic, and the user's control over local data and backups.
- Add `docs/hosting/logging-and-retention.md`: exact log fields, exclusions,
  retention periods, access controls, deletion, and incident handling. State
  that no keys, prompts, responses, plaintext chats, storage credentials, or
  backup passphrases are logged.
- Add `docs/hosting/support-access.md`: support cannot recover keys, read chats,
  restore a lost passphrase, or decrypt backups; document roles, least
  privilege, no impersonation, audit logging, and escalation.
- Document provider terms responsibility and the region/data-residency meaning.
- Re-use the M4 no-hidden-network verification and re-state it for tenants, with
  a link to the provider catalog and storage origins.
- Record the privacy/legal reviewer and dated approval. Do not publish claims
  the operator cannot enforce.
- Verify: technical review against edge logs and support roles, legal/privacy
  review record, and a copy check for passphrase-loss and no-recovery wording.

#### M6-05 - Staging tenant deployment, header/CSP validation, config review, and evidence

- Deploy the static build to a staging tenant with a subdomain and a test custom
  domain using the M6-02 templates. Pin the release digest and config hash.
- Add `scripts/verify-hosted-deployment.mjs <url>` if M6-02 did not already add
  it; assert shell/config/asset/deep-route/`/healthz` status, exact cache and
  security headers, CSP, and config-fetch behavior.
- Run the smoke flow: load the app, configure a provider in the browser with a
  synthetic/test key, chat, export all data, and restore an encrypted backup
  from MinIO or another test bucket. Never record a real key, prompt, or
  passphrase.
- Run a config review: schema validation, secret scan, exact OpenRouter origin,
  `telemetry: false`, and no unknown fields.
- Run the rollback drill and verify the previous release/config still loads and
  passes headers/health checks.
- Record evidence under `docs/operations/M6/evidence/`: raw headers, config
  hash, config review result, smoke result, export/restore result, rollback
  result, and a note that data is synthetic.
- Verify: `make contracts-check`, web gates if config changed, the hosted
  verification script, and the manual smoke/rollback checks.

#### M6-06 - Security review, self-host/export portability, and exit gate

- Produce a data-flow diagram and review the tenant deployment: static edge,
  browser-direct provider calls, browser-direct user-storage calls, no
  server-side prompt proxy, no key custody, no plaintext conversation storage,
  and no backup-passphrase access.
- Review support access, edge logs, retention, tenant teardown, and DNS/TLS
  ownership. Record the absence of a support data-access path.
- Run a browser network trace and edge configuration review to confirm there are
  no hidden network destinations or third-party scripts.
- Export the tenant public config; deploy the same build with the static
  container using that config; run the same header/health/SPA checks. Record the
  self-host/export portability result.
- Run the final regression gates, fill `docs/operations/M6/M6-verification.md`,
  update the milestone status, and record any unapproved gaps as blockers.
- Verify: `M6-verification.md` exit-gate checklist, evidence links, and a final
  review that no backend, proxy, or key-custody change was introduced.

### 5.3 Parallel execution groups

- Group A: `M6-01` first. It freezes the boundary, decisions, schema, and
  templates and blocks all work.
- Group B: after `M6-01`, run `M6-02`, `M6-03`, and `M6-04` in parallel. They
  touch different docs and templates; coordinate before editing shared index or
  link files.
- Group C: `M6-05` follows all three workstreams and needs the external staging
  tenant.
- Group D: `M6-06` is the final gate and cannot start until `M6-05` is complete.

## 6. Phased Roadmap

| Phase | Tasks | Outcome |
| --- | --- | --- |
| 0 - Preflight | M6-01 | Decisions frozen, tenant schema, config templates, feature-flag decision |
| 1 - Hosting artifacts | M6-02 | Deploy templates, one-click contract, custom domain/TLS, health, rollback |
| 2 - Storage and privacy | M6-03, M6-04 | BYO storage onboarding and reviewed privacy/support copy |
| 3 - Staging proof | M6-05 | Tenant deployed and validated against the full static contract |
| 4 - Exit gate | M6-06 | Security/portability review and M6 verification record |

## 7. Test Strategy

- **Schema first.** The tenant manifest and public config templates are validated
  by contracts and dedicated scripts. A tenant config that fails schema, origin,
  or telemetry validation must not be published.
- **Secret review is mandatory.** Scan the public config and templates for key,
  token, private-key, and passphrase patterns. The evidence must show synthetic
  values only.
- **Header/CSP verification is exact.** Compare tenant responses to the section
  14.1 contract, including cache rules for shell, config, assets, and deep
  routes, plus `/healthz`.
- **Static portability is proven, not assumed.** Serve the tenant config with the
  M4 container and re-run the same smoke checks.
- **BYO storage is tested from the browser origin.** Use MinIO or a disposable
  bucket, apply the documented CORS policy, test success and the documented
  failure states, and restore encrypted data produced by M5.
- **Privacy claims are checked against reality.** Review edge logs, support
  roles, retention configuration, and the browser network trace before
  publishing the data-processing statement.
- **Rollback is exercised.** A rollback drill checks the previous artifact and
  config together, cache invalidation, headers, health, and user-data safety.
- **Teardown is tested after export.** Export the tenant config and app data,
  remove DNS/TLS, and confirm the exported build remains self-hostable.
- **No live secrets in evidence.** Use synthetic conversations, disposable
  credentials, and test buckets. Do not store real provider keys or
  passphrases in task files, screenshots, logs, or CI artifacts.

## 8. Acceptance Criteria

Mapped to the milestone exit gate. All boxes remain unchecked until `M6-05` and
`M6-06` record evidence.

- [ ] A tenant can be deployed with a custom domain and branding without
      server-side secrets.
- [ ] ChatUI has no path to read provider keys, plaintext chats, or backup
      passphrases.
- [ ] The exported app remains self-hostable with the same `config.json`
      contract.
- [ ] BYO storage onboarding works and clearly states that credentials and
      passphrase stay client-side.
- [ ] Privacy and data-processing documentation is accurate and reviewed.
- [ ] Rollback and health checks are documented and tested.
- [ ] Staging deployment passes SPA fallback, header/CSP, and config review
      checks.
- [ ] No hidden network destinations or server-side prompt proxying exist.
- [ ] The "export everything" path is documented and exercised.
- [ ] Tenant configuration contains no keys, storage credentials, or
      server-side secrets.
- [ ] The one-click flow has a named external owner and a documented failure and
      recovery path.
- [ ] No managed storage offering or backend control plane was added to this
      repository under M6.

## 9. Verification

Repository gates:

```bash
make contracts-check
make web-lint && make web-typecheck && make web-test && make web-build
```

If M6 changes config, schemas, or scripts, also run:

```bash
make release-check
```

Tenant and artifact checks:

```bash
node scripts/validate-tenant-config.mjs deploy/chatui/templates/config.full.json
node scripts/verify-hosted-deployment.mjs https://chat.staging.<chatui-domain>

make web-build
docker build -f deploy/Dockerfile -t chatui-web:m6-portability .
docker run --rm -d --name chatui-m6-portability -p 8080:80 chatui-web:m6-portability
curl -sSI http://localhost:8080/
curl -sSI http://localhost:8080/config.json
curl -sSI http://localhost:8080/healthz
curl -sS http://localhost:8080/deep/route | head
docker stop chatui-m6-portability
```

Manual checks:

- Staging tenant load, provider configuration, chat, export, and encrypted
  restore using synthetic data.
- Tenant config schema, exact OpenRouter origin, `telemetry: false`, and secret
  scan.
- Raw header/CSP capture for shell, config, asset, deep route, and `/healthz`.
- Self-host the tenant build with the tenant `config.json` and repeat the checks.
- Rollback drill and teardown/export drill.
- Privacy/legal review against edge logs, retention, support roles, and the
  browser network trace.

## 10. Risks and Open Decisions

| Risk | Impact | Mitigation |
| --- | --- | --- |
| Brand, domain, or support model unresolved | M6 cannot close | Make the section 20 question 10 owners and decisions an entry-gate blocker |
| No external ChatUI operator or staging tenant | Exit gate cannot be tested | Name the operator in `M6-01`; do not accept container evidence as tenant evidence |
| Hosting creeps into a backend | Violates the milestone and privacy model | Keep the boundary table; reject prompt proxying, key custody, and server-side data stores |
| Support tooling becomes a data-access path | High privacy harm | Least privilege, no impersonation, audit, and no key/chat/passphrase recovery |
| Custom domain or TLS failure | Tenant outage or impersonation risk | DNS/TLS runbook, expiry monitoring, rollback, and staging custom-domain test |
| Secret accidentally published in tenant config | Key compromise | Strict schema, tenant config validator, secret scan, and mandatory review |
| Custom-domain origin mismatch with CORS or OpenRouter attribution | BYO storage or provider calls fail | Template the exact tenant origin into config and CORS guidance; test from the tenant origin |
| Privacy copy overstates what ChatUI cannot see | Legal and trust risk | Technical data-flow review plus privacy/legal sign-off before publishing |
| Region implies data residency that does not exist | Misleading users | Define region as static edge location only and state it explicitly |
| Rollback restores an incompatible config | Broken tenant | Pin artifact and config hash together and test the rollback drill |
| Managed storage partner scope creep | Unapproved product/legal surface | Remove the claim now; keep BYO storage as the only M6 storage path |
| Feature flags become arbitrary config | Validation and security drift | Expose none or use a typed allowlist with tests |
| Evidence contains real keys or chat content | Secret exposure | Synthetic data only; scan evidence before committing |
| PWA branding expectations | Perceived incomplete branding | Decide and document the install-name/icon limitation or add a branded build path |

Open decisions from this plan are tracked in
[M6-decisions.md](M6/M6-decisions.md). Resolved by `M6-01.1` on 2026-11-12:

- Final product name, domain, branding owner, and support model: `DEC-M6-03`.
- Repository versus operator ownership of the one-click entry point: `DEC-M6-02`.
- Feature flags: `DEC-M6-06` (expose none).
- Install/PWA name and icons: `DEC-M6-13` (build-time ChatUI branding only).
- Logging fields and retention periods: `DEC-M6-12`.
- Privacy reviewer and approval process: `DEC-M6-11` (product-owner self-review
  with recorded risk acceptance; not independent legal review).
- Region semantics and data-residency statement: `DEC-M6-07` (static edge
  location only; no residency claim).
- Support roles boundary and no-impersonation policy: `DEC-M6-09`; detailed
  roles and audit retention remain for `M6-04.2`.
- Tenant model and manifest location: `DEC-M6-04`, `DEC-M6-05`.
- BYO storage scope: `DEC-M6-08`.
- Release pinning and rollback ownership: `DEC-M6-15`.

Still open:

- External ChatUI operator, staging environment, and control-plane ownership:
  `DEC-M6-10` / `B-M6-02` (blocked).
- Custom-domain availability policy (all tenants versus a subset): operator
  deliverable under `DEC-M6-10` and `DEC-M6-14`.
- Region list, if the operator publishes one: operator/product deliverable; the
  repository must not claim data residency (`DEC-M6-07`).
- Public config template set and tenant config export format: task deliverables
  in `M6-01.3`, `M6-01.4`, and `M6-02.1`, not a product decision.

## 11. File-Level Work Map

| Area | Primary files |
| --- | --- |
| Tenant manifest contract | `contracts/schema/hosting-tenant.schema.json`, `contracts/fixtures/hosting/**`, `scripts/validate-contracts.mjs` |
| Tenant config templates | `deploy/chatui/templates/config.full.json`, `deploy/chatui/templates/config.minimal.json`, `deploy/chatui/templates/tenant.example.json` |
| Hosting templates | `deploy/chatui/README.md`, `deploy/chatui/edge/**`, `deploy/chatui/health/**`, `deploy/chatui/rollback/**` |
| Existing recipe migration | `deploy/chatui.md` (pointer or removal), `docs/operations/M4/deployment.md` links |
| Tenant docs | `docs/hosting/overview.md`, `tenant-configuration.md`, `branding.md`, `one-click.md`, `custom-domains.md`, `byo-storage.md`, `backup-and-restore.md`, `privacy.md`, `data-processing.md`, `logging-and-retention.md`, `support-access.md`, `export.md`, `health-and-status.md`, `rollback.md` |
| Web config | `web/public/config.json`, `web/src/app/config.ts`, `web/src/app/config.test.ts` (only if feature flags are accepted) |
| Validation scripts | `scripts/validate-tenant-config.mjs`, `scripts/verify-hosted-deployment.mjs` |
| CI | `.github/workflows/contracts.yml`, `.github/workflows/web.yml` (tenant template/config validation) |
| Evidence and verification | `docs/operations/M6/README.md`, `docs/operations/M6/M6-decisions.md`, `docs/operations/M6/evidence/**`, `docs/operations/M6/M6-verification.md` |
| Parity and milestone records | `contracts/parity.md` if a hosting row is added; `docs/milestones/M6-managed-hosting.md` status on completion |

## 12. Out of Scope

- Implementing a ChatUI backend, control plane, billing, usage metering, or
  support portal in this repository.
- Server-side prompt proxying, server-side key custody, server-side
  conversation storage, or cross-device sync through ChatUI.
- A ChatUI-managed storage offering beyond BYO storage. Any future partner
  storage must remain client-side encrypted and unreadable by ChatUI.
- Changes to backup encryption, sync, storage adapters, or the M5 format.
- Changes to the M4 static deployment contract, header policy, or SPA fallback
  contract, except link and template reuse.
- Provider resale, billing, rate limiting, or provider policy changes.
- Native Android or Rust desktop distribution changes.
- A full public privacy policy for the Android app or Play Store data-safety
  declarations; M6 covers the hosted web service documentation.
- Localization, localization of tenant docs, or a marketing site.

## 13. Definition of Done

M6 is complete only when:

1. Every task in section 5 is `Complete` or has an approved, recorded deferral.
2. The entry-gate decisions for brand, domain, support, staging, privacy review,
   and the repository/external-operator boundary are resolved.
3. `deploy/chatui/` templates and `docs/hosting/` documentation exist and link
   to the canonical M4 deployment and M5 storage contracts.
4. Tenant manifest and public config templates validate and contain no secrets.
5. A staging tenant passes SPA fallback, header/CSP, cache, `/healthz`, config,
   and secret-review checks with recorded evidence.
6. Privacy, data-processing, logging/retention, support-access, provider-terms,
   and no-hidden-network documentation is reviewed and approved by the named
   owner.
7. BYO storage onboarding works and states that credentials, passphrase, and
   chat data remain client-side.
8. A tenant can export its data and public config and self-host the same build
   with the same `config.json` contract.
9. Rollback, health checks, and tenant teardown/export are documented and
   exercised.
10. `docs/operations/M6/M6-verification.md` records the exit-gate evidence, and
    the milestone status is updated.
11. No server-side proxy, key custody, plaintext conversation store, managed
    storage offering, or support data-access path was introduced.
