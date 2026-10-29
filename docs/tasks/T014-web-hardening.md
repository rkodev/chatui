# T014 - Web PWA, deployment, security, accessibility, performance

- Milestone: M4
- Depends on: T013
- Suggested subagent: general
- Parallel-safe: yes, with T024 and T033
- Read: `docs/IMPLEMENTATION.md` sections 3.7-3.8, 8.6-8.9, 11.11, 12.2, 14.1-14.3, 15.1, 18

## Goal

Turn the completed web client into a deployable, installable, secure, accessible, and performant PWA without changing feature behavior.

## Owns

- `web/vite.config.ts` PWA section
- `web/public/manifest.webmanifest`, `web/public/icons/**`
- `web/src/app/serviceWorker.ts` or equivalent registration module
- `web/deploy/**`, `web/Dockerfile`, `web/Caddyfile`
- `web/lighthouse*` config, accessibility/performance test helpers
- Production values in `web/public/config.json` only; T010 owns the schema/loader and T013 owns the settings UI

## Deliverables

1. Service worker and PWA:
   - Cache the app shell and hashed static assets only.
   - Never cache API requests, responses, secrets, or decrypted content.
   - Offline history remains readable; sending is disabled with an offline banner.
   - Update prompt when a new worker is waiting; never force reload during an active stream.
   - Do not register the service worker in development.
2. Manifest and icons: installable metadata, theme colors, maskable icons, screenshots where useful.
3. Runtime config: `config.json` is public-only; validate at startup; document the fields in section 8.9.
4. Deployment artifacts:
   - Minimal static container using Caddy or nginx with SPA fallback.
   - `index.html` and `config.json` no-cache, hashed assets immutable.
   - `/healthz` static 200 where the chosen server supports it.
   - Security headers: CSP, `X-Content-Type-Options`, `Referrer-Policy`, minimal `Permissions-Policy`, `Cross-Origin-Opener-Policy`.
   - No environment variables required for normal operation.
5. Security hardening:
   - Enforce the CSP from section 8.6/14.1 and confirm no third-party scripts.
   - Redact `Authorization`, `x-api-key`, and `x-goog-api-key` everywhere.
   - Confirm "Forget all keys" behavior and no secret caching.
   - Review dependencies for unnecessary surface; keep lockfiles committed.
6. Accessibility pass:
   - Keyboard navigation and visible focus on all routes.
   - Accessible names for icon buttons; semantic live regions for streaming start/complete/error only.
   - Contrast, reduced motion, scalable text, table/code overflow.
   - Run axe on main routes and fix serious/critical issues.
7. Performance:
   - Code-split settings, provider management, and Shiki languages.
   - Lazy-load Markdown rendering below the fold where practical.
   - Confirm message virtualization and isolated streaming updates.
   - Memoize message components; reserve image aspect ratios.
   - Meet or document: initial JS under 250 KB gzip, Lighthouse mobile performance/accessibility >= 90.
8. Tests/checks for service-worker registration rules, offline banner, update prompt, CSP presence, and bundle budget.

## Steps

1. Add PWA config, manifest, icons, and registration.
2. Add deployment artifacts and security headers.
3. Run accessibility and performance audits.
4. Fix findings and record budgets in the task report.
5. Verify offline and update behavior.

## Acceptance criteria

- The app installs as a PWA and works offline for history.
- No API response, secret, or decrypted content enters the Cache API.
- A new service worker prompts instead of force-reloading during streaming.
- The container serves the SPA with correct cache and security headers.
- axe reports no serious/critical issues on main routes.
- Lighthouse mobile performance and accessibility are at least 90, or deviations are documented with a plan.
- No third-party scripts or hidden network destinations.

## Verification

```bash
make web-build
cd web && pnpm exec playwright test
cd web && pnpm exec lighthouse-ci autorun
```

If Lighthouse CI is not configured yet, run Lighthouse manually and record the report path.

## Out of scope

- New features, providers, or behavior changes.
- Additional provider adapters and cross-provider E2E (T015).
- Managed hosting flow (T043).
