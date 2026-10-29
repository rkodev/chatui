# M6 - Managed roksky hosting (future)

- Status: Future
- Target: follow-on to M4/M5
- Depends on: M4; M5 for BYO encrypted storage onboarding
- Blocks: none
- Platforms: web hosting
- Spec: sections 13.8, 14.2-14.3, 17 (M6), 19

## Objective

Provide an optional roksky-managed hosting flow for the web client. roksky serves the static app and manages TLS; users bring provider keys and optionally their own object storage. roksky must have no access to keys, plaintext chats, or backup passphrases. This is a hosting and documentation milestone, not a backend application.

## Entry criteria

- M4 exit gate is met: the static web build, container, headers, config contract, and release pipeline are stable.
- M5 is complete if BYO encrypted storage onboarding is in scope.
- The product name, domain, support model, and hosting brand questions from spec section 20 have owners.

## Scope by workstream

### 1. Tenant configuration and branding

Deliver:

- Tenant selection of subdomain and region.
- Custom domain and TLS management.
- Branding configuration: app name, default theme, enabled provider presets, custom-provider allowance, OpenRouter attribution origin/title, docs link, and optional feature flags.
- Public-only `config.json` deployment; no keys, storage credentials, or server-side secrets in tenant configuration.
- The exported app remains self-hostable with the same `config.json` contract.
- Rollback and health-check definitions for a tenant deployment.

### 2. One-click hosting flow

Deliver:

- "Deploy to roksky" flow: choose subdomain and region, roksky serves the static app and manages TLS.
- The user configures providers and keys in the browser after deployment.
- Optional connection of user-owned object storage for encrypted backups via M5.
- A clear "export everything" path.
- Health check and deployment status visible to the operator and user.
- No server-side prompt proxying, key custody, or plaintext conversation storage.
- Support access explicitly excluded from keys, plaintext chats, and backup passphrases.

### 3. BYO storage onboarding

Deliver:

- S3-compatible storage onboarding first, with CORS guidance and a connectivity test.
- Explicit statement that storage credentials and the backup passphrase remain client-side and are never sent to roksky.
- Links to backup/restore documentation from M5.
- Failure states for invalid credentials, CORS, unreachable endpoints, and bucket permissions.

### 4. Privacy and data-processing documentation

Deliver:

- Data-processing summary covering what roksky can and cannot see.
- Logging and retention statement: no keys, prompts, responses, or plaintext chats in roksky logs.
- Support-access statement: support cannot recover keys, read chats, or restore a lost passphrase.
- Clear statement that the user is responsible for provider terms and for their own storage.
- Documentation that the static app has no hidden network destinations.
- Review by the appropriate owner before publishing.

### 5. Deployment templates and runbooks

Deliver:

- Templates under `deploy/roksky/` and tenant configuration docs under `docs/hosting/`.
- Static hosting path with SPA fallback and the security headers from section 14.1.
- Header/CSP verification for deployed tenants.
- Staging deployment and smoke test.
- Rollback runbook and health-check procedure.
- Tenant configuration review step to prevent accidental secret inclusion.

### 6. Validation

- Staging deployment smoke test: load app, configure providers in-browser, chat with a test key, export data, and optionally restore encrypted backup.
- Header and CSP check against section 14.1.
- Review tenant configuration for secrets.
- Verify a tenant can export and self-host with the same public config.
- Verify roksky has no path to read provider keys, plaintext chats, or passphrases.
- Manual export/restore check using M5 if available.

## Out of scope

- Implementing backup encryption or sync (M5).
- Changing the phase 1 static deployment contract (M4).
- Provider resale, billing, usage metering, or prompt proxying.
- Server-side key custody, server-side conversation storage, or cross-device sync.
- A managed storage offering sold by roksky beyond BYO storage; any future partner storage must remain client-side encrypted and unreadable by roksky.
- Native Android or Rust distribution.

## Exit gate

- A tenant can be deployed with a custom domain and branding without server-side secrets.
- roksky has no path to read provider keys, plaintext chats, or backup passphrases.
- The exported app remains self-hostable with the same `config.json` contract.
- BYO storage onboarding works and clearly states that credentials and passphrase stay client-side.
- Privacy and data-processing documentation is accurate and reviewed.
- Rollback and health checks are documented and tested.
- Staging deployment passes SPA fallback, header/CSP, and config review checks.
- No hidden network destinations or server-side prompt proxying exist.

## Verification

- Staging deployment smoke test, including a chat and export path.
- Header/CSP check against section 14.1 for the deployed tenant.
- Tenant configuration review confirming no secrets.
- Manual export/restore check using M5 if available.
- Self-host the exported build with the same config to confirm portability.

## Task-generation guidance

Expect 4-6 tasks:

1. Tenant configuration schema, branding, and public config templates.
2. One-click hosting flow, health checks, and rollback runbooks.
3. BYO storage onboarding and connectivity/CORS guidance, linked to M5.
4. Privacy, data-processing, logging, and support-access documentation.
5. Deployment templates and staging validation.
6. Security review and self-host/export portability check.

Do not generate tasks that add a server-side proxy or any custody of keys or plaintext data.

## Risks and decisions to confirm

- Brand, domain, and support model for the managed offering are unresolved in spec section 20.
- Managed hosting can accidentally become a backend. Keep the deployment static and client-side encrypted.
- Support tooling can become a data-access path. Document and enforce no access to keys, chats, or passphrases.
- Custom domains and TLS introduce operational complexity. Define health checks and rollback before launch.
- Users may expect passphrase recovery. State clearly that there is none.
- Whether a future partner storage offering exists is a product and legal decision, not an implementation shortcut.
