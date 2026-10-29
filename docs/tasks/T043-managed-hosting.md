# T043 - Managed roksky deployment (future)

- Milestone: M6
- Depends on: T014, T041
- Suggested subagent: general
- Parallel-safe: no, future phase
- Read: `docs/IMPLEMENTATION.md` sections 13.8, 14.2-14.3, 19

## Goal

Design the optional roksky-managed hosting flow for the web client. roksky serves the static app and TLS; users bring provider keys and optionally their own object storage. roksky must have no access to keys, plaintext chats, or backup passphrases.

## Owns

- Deployment templates and documentation for the managed flow, for example `deploy/roksky/**`
- Branding/config examples and tenant configuration docs
- Privacy/data-processing summary drafts
- `docs/` hosting runbooks

Do not add a backend that proxies provider requests or stores plaintext conversations.

## Deliverables

1. One-click hosting flow:
   - User chooses "Deploy to roksky", selects subdomain and region.
   - roksky serves the static app and manages TLS.
   - User configures providers and keys in the browser.
   - Optional BYO object storage for encrypted backups.
2. Tenant configuration:
   - Custom domain, branding, enabled presets, attribution origin/title, docs link.
   - Public-only `config.json`; no secrets in deployment config.
3. BYO storage onboarding:
   - S3-compatible first, with CORS guidance and connectivity test.
   - Explicit statement that storage credentials and passphrase stay client-side.
4. Privacy and data-processing documentation:
   - roksky support has no access to keys, plaintext chats, or backup passphrases.
   - Clear "export everything" path.
   - Data retention, logging, and support-access statements.
5. One-click deployment from the repository and from roksky, with rollback and health checks.
6. Documentation and tests for the static hosting path, including SPA fallback and security headers.

## Steps

1. Define tenant config and branding schema.
2. Produce deployment templates and runbooks.
3. Wire the BYO storage onboarding docs to T042.
4. Draft privacy/data-processing documents.
5. Validate a staging deployment.

## Acceptance criteria

- A tenant can be deployed with a custom domain and branding without server-side secrets.
- roksky has no path to read provider keys, plaintext chats, or backup passphrases.
- The exported app remains self-hostable with the same `config.json` contract.
- Privacy documentation is accurate and reviewed.
- Rollback and health checks are documented and tested.

## Verification

- Staging deployment smoke test.
- Header/CSP check against section 14.1.
- Review of tenant config for secrets.
- Manual export/restore check using T042 if available.

## Out of scope

- Implementing backup encryption (T042).
- Changing the phase 1 static deployment (T014).
- Provider resale, billing, or prompt proxying.
