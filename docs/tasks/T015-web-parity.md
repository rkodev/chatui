# T015 - Web additional providers, E2E, parity

- Milestone: M2/M4
- Depends on: T011, T012, T013, T014
- Suggested subagent: general
- Parallel-safe: yes, with T025 and T034
- Read: `docs/IMPLEMENTATION.md` sections 3.4, 7.5-7.9, 14.1, 15.1-15.2 (all cases), 15.3, 21.1-21.2

## Goal

Generalize the OpenRouter transport into the remaining launch providers and finish the web confidence layer: shared-fixture conformance, Playwright end-to-end tests, accessibility checks, and parity updates. OpenRouter must not regress.

## Owns

- `web/src/providers/anthropic.ts`, `web/src/providers/gemini.ts`, `web/src/providers/ollama.ts`, `web/src/providers/openai-compatible.ts` extensions
- `web/src/providers/registry.ts` registration for additional presets
- `web/e2e/**`, `web/playwright.config.ts`
- `web/src/test/providers/**` additions for additional providers
- `contracts/parity.md` updates for web

## Deliverables

1. Additional provider adapters:
   - OpenAI-compatible presets: OpenAI, Groq, Mistral, DeepSeek, xAI, Together, Fireworks, LM Studio, llama.cpp, Custom.
   - Anthropic native: headers (`x-api-key`, `anthropic-version`, browser access), system field, image base64 blocks, event mapping.
   - Google Gemini native: `alt=sse`, `x-goog-api-key`, `usageMetadata`, finish reason mapping, `systemInstruction`, `inlineData`.
   - Ollama: native `/api/chat` NDJSON first, `/api/tags` discovery, OpenAI-compatible fallback for custom local servers.
2. Preset-specific model discovery and fallback model lists so onboarding works when discovery is blocked.
3. CORS handling: `browserCors` status, actionable `cors` errors, Anthropic browser header, Gemini key header, Ollama origin guidance. Never proxy through a third party.
4. Shared fixture conformance for every provider stream and error fixture, with expected normalized events.
5. Playwright E2E:
   - First-run onboarding with OpenRouter.
   - Send and stream with mocked provider.
   - Switch model/provider mid-conversation.
   - Reload persistence.
   - Export/import round trip.
   - Delete/undo and clear data.
   - Offline read with composer disabled.
   - Error, retry, and cancellation paths.
   - Accessibility checks with axe on main routes.
6. Update `contracts/parity.md` web column honestly: complete, partial, or absent. Do not mark complete without tests.

## Steps

1. Generalize the OpenAI-compatible base and register presets.
2. Add Anthropic, Gemini, and Ollama adapters with fixture tests.
3. Add E2E and axe coverage.
4. Run the full web gate and record any deviations.
5. Update parity.

## Acceptance criteria

- Every provider in the launch set works where CORS permits.
- Anthropic `thinking`/`text`, Gemini `MAX_TOKENS`, and Ollama NDJSON map correctly.
- Shared fixtures produce the expected normalized events for the same inputs, matching the shared contract expectations used by the other clients.
- OpenRouter fixtures still pass with no regression.
- Playwright covers onboarding, streaming, switching, persistence, export, deletion, offline, and errors.
- axe reports no serious/critical issues on main routes.
- Parity is updated and reviewed.

## Verification

```bash
make contracts-check
make web-lint
make web-typecheck
make web-test
make web-build
cd web && pnpm exec playwright test
```

## Out of scope

- Android and Rust adapters (T025, T034).
- Backup/sync (T042) and managed hosting (T043).
