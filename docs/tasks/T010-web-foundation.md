# T010 - Web foundation: scaffold, domain, storage, secrets

- Milestone: M0/M1
- Depends on: T001
- Suggested subagent: general
- Parallel-safe: yes, with T020 and T030
- Read: `docs/IMPLEMENTATION.md` sections 4, 5, 6, 8.1-8.6, 11.12, 12.2, 15.1

## Goal

Build the web application foundation: Vite/React/TypeScript scaffold, routing shell, domain types, IndexedDB repositories and migrations, OPFS attachment storage, encrypted secret storage, runtime config, design tokens, and shared UI primitives. This task must not implement provider adapters or chat features.

## Owns

- `web/package.json`, `web/pnpm-lock.yaml`, `web/tsconfig*.json`, `web/vite.config.ts`, `web/index.html`, `web/playwright.config.ts` (skeleton only)
- `web/public/config.json`, `web/public/icons/*`
- `web/src/main.tsx`, `web/src/app/**`, `web/src/domain/**`, `web/src/storage/**` (except export/import owned by T013), `web/src/platform/**`, `web/src/ui/**`, `web/src/styles/**`, `web/src/test/**`
- `web/eslint.config.*`, `web/.prettierrc*`

## Deliverables

1. Scaffold with the stack in section 8.1: React 19, TypeScript, Vite, TanStack Router, Zustand, `idb`, `zod`, `uuid` v7, Vitest, Testing Library, MSW, ESLint, Prettier. Use pnpm. Do not add Tailwind.
2. Folder structure from section 8.2, with placeholder feature folders that other tasks fill in.
3. Runtime config loader for `public/config.json`, validated with `zod`, including app name, default provider preset, enabled presets, OpenRouter attribution origin/title, docs URL, and telemetry flag. Fail safely with a visible diagnostic, never silently.
4. Domain types from section 6 (`Provider`, `Model`, `Conversation`, `ChatParams`, `Message`, `ContentPart`, `Attachment`, `Usage`, `ChatError`, `Settings`, `BackupConfig`) with UUIDv7 creation and ISO 8601 timestamps.
5. IndexedDB schema and versioned migrations from section 8.5. Implement repositories for conversations, messages, attachments metadata, providers, models, settings, secrets, backup config, and sync queue. Use transactions for message append plus conversation metadata updates.
6. OPFS attachment byte store with IndexedDB Blob fallback and content-addressed `attachments/<sha256>` names. Include orphan cleanup helper.
7. `SecretStore` implementing the three storage modes in section 8.6: session-only, remember-on-device with AES-GCM and a non-extractable key, and optional passphrase lock. Never write keys to `localStorage`, URLs, logs, or the Cache API. Implement redaction helpers.
8. Routing shell for the routes in section 8.3, using history routing and a static-host fallback. Unknown feature routes may render a placeholder.
9. Zustand stores for UI shell state only (sidebar, dialogs, toasts, drafts). Do not implement streaming state here.
10. Shared UI primitives and design tokens from section 11.12: Button, Dialog, Menu, Tooltip, Toast, focus styles, light/dark/system themes, density, chat font size, reduced motion.
11. Tests for config validation, UUID/timestamp helpers, migrations from an empty v1 database, each repository, OPFS fallback, secret encryption/decryption, and redaction.

## Steps

1. Scaffold and install dependencies. Commit `pnpm-lock.yaml`.
2. Add config, domain, storage, crypto, and platform modules.
3. Add routing shell and UI primitives.
4. Write migration and repository tests.
5. Run lint, typecheck, tests, and build.

## Acceptance criteria

- App builds and launches to an empty shell with working theme switching.
- Repositories persist and reload data across a page refresh.
- A migration test creates a v1 database and upgrades it.
- Secrets are encrypted at rest in remember mode and never appear in plaintext in IndexedDB.
- Session-only mode loses keys on reload.
- No provider calls, chat UI, or attachments UI are implemented yet.
- `pnpm lint`, `pnpm typecheck`, `pnpm test`, and `pnpm build` pass.

## Verification

```bash
make web-install
make web-lint
make web-typecheck
make web-test
make web-build
```

## Out of scope

- Provider adapters, onboarding, model catalogs (T011).
- Chat, composer, Markdown rendering, attachments UI (T012).
- Conversation sidebar, search, export/import, settings pages (T013).
- PWA/service worker and deployment hardening (T014).
