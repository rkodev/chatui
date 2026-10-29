# T011 - Web OpenRouter adapter, onboarding, providers, models

- Milestone: M1
- Depends on: T010
- Suggested subagent: general
- Parallel-safe: yes, with T021 and T031
- Read: `docs/IMPLEMENTATION.md` sections 3.1, 3.3-3.4, 5.3, 7.1-7.10, 10.4, 11.5, 12.2, 15.2 (cases 1-10, 20, 28), 21.1-21.3

## Goal

Implement the OpenRouter-first provider layer and the onboarding/provider/model management UI for web. This is the release-blocking path: key validation, public model catalog, streaming, reasoning, usage/cost, routing preferences, and OpenRouter errors must work before any other provider is added.

## Owns

- `web/src/providers/**`
- `web/src/features/onboarding/**`
- `web/src/features/providers/**`
- `web/src/features/models/**`
- `web/src/features/chat/ChatRunner.ts` and streaming store contract (T012 consumes)
- `web/src/test/providers/**`, `web/src/test/fixtures/**`

## Deliverables

1. `ProviderAdapter` interface from section 7.1, `ProviderContext`, `ProviderKeyInfo`, `NormalizedChatRequest`, `StreamEvent`, `FinishReason`, and `ChatError` mapping.
2. SSE transport helper for `fetch` streams with:
   - CRLF normalization, multi-line `data:` fields, comments, final-buffer flush.
   - Bounded buffering and no JSON parsing in the transport.
   - AbortSignal cancellation.
   - Tests derived from spec section 21.1.
3. Provider registry that loads `contracts/providers.json` (or generated equivalent) and instantiates adapters by preset. No duplicated provider metadata.
4. OpenRouter adapter:
   - `GET /models` public catalog, 24-hour cache, last-known-good snapshot on failure.
   - `GET /key` validation and mapping of label, usage, limit, free-tier, rate-limit.
   - `POST /chat/completions` streaming with `stream_options.include_usage`, reasoning mapping, keep-alive tolerance, in-stream error handling, usage/cost persistence, and attribution headers.
   - Catalog mapping for pricing strings (per token to per million), context, max output, modalities, supported parameters, free models, canonical slug, and unknown fields.
   - Curated routing controls: fallbacks, sort, `data_collection: "deny"`. Advanced JSON may not override `model`, `messages`, `stream`, or `stream_options`.
5. OpenAI-compatible base transport parameterized by preset so T015 can extend it. OpenRouter behavior stays the reference implementation.
6. Retry policy from section 7.9: max 3 attempts, jittered exponential backoff, only before first text, honor `Retry-After`. No retry after partial output.
7. `ChatRunner` service that drives an adapter, emits normalized events to a streaming store, persists partial text at bounded intervals, supports cancel/regenerate, and enforces one active stream per conversation.
8. Onboarding UI:
   - Welcome/empty state, OpenRouter preselected, key entry, optional label, Connect, inline testing state.
   - Load public catalog before key validation; keep chat disabled until `/key` succeeds.
   - Actionable failures that preserve the form.
9. Provider management UI: list, add/edit custom provider, key rotation, key removal, enabled toggle, CORS status, OpenRouter key details.
10. Model picker and `Manage models` UI: search, provider groups, pinned/hidden, capability chips, context, modalities, price per million, free badge, manual model add, availability warnings.
11. Tests with MSW and shared fixtures for catalog mapping, key validation, keep-alives, reasoning dedup, usage/cost, 402/429/503, in-stream errors, cancellation, and protected request fields.

## Steps

1. Define adapter/event/error types and the SSE helper.
2. Implement the OpenRouter adapter and registry.
3. Implement `ChatRunner` and its streaming store contract.
4. Build onboarding, provider, and model UI.
5. Add fixture/MSW tests and run the full web gate.

## Acceptance criteria

- A valid OpenRouter key passes `/key`, loads `/models`, and selects a default model.
- An invalid key shows an auth error, preserves the form, and does not accept the key.
- Paid, free, vision, reasoning, and long-context models map correctly; prices display per million tokens.
- `: OPENROUTER PROCESSING` and unknown SSE fields do not break parsing.
- Reasoning appears once, usage/cost is persisted, and in-stream errors stop without auto-retry.
- Protected fields cannot be overridden by advanced JSON.
- Tests cover spec critical cases 1-10, 20, and 28 on web.

## Verification

```bash
make contracts-check
make web-lint
make web-typecheck
make web-test
```

## Out of scope

- Streaming chat presentation and Markdown rendering (T012).
- Conversation sidebar, search, export/import (T013).
- Additional providers (T015).
- PWA/deployment hardening (T014).
