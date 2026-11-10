# PWA, service worker, and offline evidence (M4-02.1)

Date: 2026-11-10
Task: [M4-02.1](../M4-02.1-web-pwa-offline.md)
Client checked: web (Chromium via Playwright 1.64.1, Desktop Chrome profile).
Android Capacitor packaging was not exercised; it remains optional per
`M4-decisions.md`.

## Implementation choice

- No Workbox or PWA plugin dependency was added. The service worker is
  hand-written and generated at build time by a small Vite plugin in
  `web/vite.config.ts`.
- Shared policy: `web/src/platform/pwa/cachePolicy.ts`.
- Worker source template: `web/src/platform/pwa/serviceWorkerSource.ts`.
- Production registration: `web/src/main.tsx` calls
  `web/src/platform/pwa/register.ts`, which is a no-op outside
  `import.meta.env.PROD`.
- Update prompt: `web/src/platform/pwa/PwaUpdatePrompt.tsx`, mounted from
  `web/src/app/providers.tsx`; stream check in
  `web/src/platform/pwa/activeStream.ts`.
- Cache name: `chatui-shell-<sha256-prefix>`; the current build is
  `chatui-shell-b5b2fa83a937`.
- Precache list source: the Vite plugin walks `web/dist` after build and filters
  entries through the same allowlist the tests use. Only `/index.html`,
  `/manifest.webmanifest`, `/assets/*` (hashed build output), and `/icons/*`
  are included. This build precaches 111 entries.
- Update strategy: network-first navigation with cached `index.html` fallback,
  cache-first hashed assets, and pass-through for every other request. A
  waiting worker is prompted; activation posts `SKIP_WAITING` only after the
  user chooses Update and no stream is active.

## Cache API inspection

The Playwright test `e2e/pwa.spec.ts` runs the production build with
`vite preview`, completes onboarding, sends a probe message, then reads all
Cache API keys, request URLs, and response bodies.

| Point | Cache keys | Entries |
| --- | --- | --- |
| After install, before chat | `chatui-shell-b5b2fa83a937` | 111 |
| After sending a chat message | `chatui-shell-b5b2fa83a937` | 111 (unchanged) |

Observed cache contents:

- `/index.html`, `/manifest.webmanifest`, `/icons/*`, and hashed `/assets/*`
  only.
- No `/config.json`, no `/api/*`, no cross-origin provider URL, and no response
  body containing the chat probe text.
- IndexedDB conversation history is not copied into the Cache API.

Exported listing: [cache-keys.json](cache-keys.json).

## Offline behaviour

- Conversation history remains readable from IndexedDB when the connection is
  lost after the app has loaded.
- The sidebar shows "You are offline. History is available; sending is
  disabled." and the composer shows "You are offline. Sending is disabled."
  Sending is disabled without a reload; the online event restores it.
- Offline navigation is served from the precached shell. The test asserts the
  navigation response reports `fromServiceWorker() === true`.
- Accepted limitation: `config.json` is fetched with `cache: 'no-store'` and is
  intentionally never cached, so a **cold** offline start cannot load runtime
  configuration and stops before rendering the app. Offline history support
  applies to a running/loaded app and to shell navigation, not a first-ever
  offline launch. Attachments are not cached for offline use.

## Update flow

- A waiting or newly installed replacement worker triggers a non-blocking
  prompt with Update now and Dismiss.
- Update now checks the streaming store at click time. If any stream is
  unfinished, the prompt stays visible and says the update is paused until the
  current response finishes; `SKIP_WAITING` is not sent.
- When no stream is active, the page posts `SKIP_WAITING`; the reload happens
  only after the resulting `controllerchange`, and only when the user requested
  the update. There is no automatic reload.
- Covered by `register.test.ts`, `activeStream.test.ts`, and
  `PwaUpdatePrompt.test.tsx`.

## Development registration

`registerServiceWorker` returns without touching `navigator.serviceWorker` when
`isProduction` is false (default `import.meta.env.PROD`). The Vite plugin also
has `apply: 'build'`, so `web/dist/sw.js` is produced only by the production
build. Covered by `register.test.ts`.

## Installability

The manifest has name, short name, start URL, standalone display, theme and
background colors, 192 px and 512 px icons, and a dedicated 512 px maskable
icon. The worker is registered at root scope and controls the page. These are
the Chromium install criteria; the headless test environment does not show the
browser install prompt, so a manual OS/browser install click was not performed.

## Commands and results

```bash
make web-lint && make web-typecheck && make web-test && make web-build
cd web && pnpm exec playwright test
```

- Web lint, typecheck, and build: passed.
- Unit tests: 882 passed across 101 files.
- Playwright: 30 passed, including three PWA tests (manifest, cache policy,
  offline shell navigation) and the existing offline history/send test.

## Notes

- The precache list includes every hashed build asset, including lazily loaded
  syntax-highlighting chunks. This keeps offline code rendering working but
  makes the first install larger; a later performance task can trim unused
  chunks if needed.
- Deployment headers for `config.json` and `sw.js` are owned by M4-04.1.
