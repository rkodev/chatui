# Web end-to-end tests

Run the suite from `web/`:

```bash
pnpm exec playwright test
# or
pnpm test:e2e
```

The Playwright config builds the app and serves the production bundle with
`vite preview` on `http://127.0.0.1:4173`.

## Mock provider

`fixtures/mockProvider.ts` intercepts the OpenRouter base URL at the browser
boundary. It serves a deterministic catalog, `/key` metadata, and SSE chat
completions. Tests use fake keys only (`test-key-not-a-secret`); no live
provider is contacted and no real secret is written to artifacts.

The mock supports success, provider-error, HTTP-error, and delayed responses so
the suite can cover streaming, retry, and cancel paths.

## Isolation

Every Playwright test gets a fresh browser context, so IndexedDB, localStorage,
and the catalog cache start empty. Tests do not share provider or conversation
state.

## Provider switching

The conversation settings UI exposes model switching but not a provider
selector. The provider-switch test creates a second provider through the UI,
then updates the conversation record through the same IndexedDB schema the app
uses. This verifies that a provider change is durable and that the next send
uses the new provider's model.

## Artifacts

Failure artifacts are limited to a screenshot and a Playwright trace. Video is
disabled. The CI workflow uploads `web/playwright-report` only when the job
fails.
