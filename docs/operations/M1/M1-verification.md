# M1 Verification Evidence

- Status: Skeleton; no results recorded yet.
- Owner: M1-10 finalizes this file.
- Scope: OpenRouter-only chat on web and Android.
- Source plan: [M1 OpenRouter Core Implementation Plan](../M1-openrouter-core-implementation-plan.md)
- Authoritative spec: [IMPLEMENTATION.md](../../IMPLEMENTATION.md)

This file is the shared evidence location for the M1 exit gate. Tasks add
evidence as they land. Do not mark a row passed until the command or manual
check has actually run and the evidence is linked or quoted.

## Evidence rules

- Record commands, exit codes, and bounded output. Do not paste full logs.
- Record only status codes, headers, timestamps, and conclusions for CORS.
- Never record an API key, a real prompt, a real conversation, or personal data.
- Redact keys in screenshots and logs. Use a disposable test key.
- Leave a row `Pending` when the evidence does not exist yet.

## 1. Commands

| Command | Purpose | Expected | Result | Evidence |
| --- | --- | --- | --- | --- |
| `make contracts-install` | Install contract validator dependencies | Exit 0 | Pending | |
| `make contracts-check` | Validate providers, schemas, fixtures, request vectors, and parity | Exit 0 | Pending | |
| `make web-lint` | Web lint and type checks | Exit 0 | Pending | |
| `make web-test` | Web unit and component tests | Exit 0 | Pending | |
| `make android-test` | Android unit and ViewModel tests | Exit 0 | Pending | |
| `make android-build` | Android debug build | Exit 0 | Pending | |

## 2. CORS matrix

Run from a browser against `https://openrouter.ai/api/v1` with a disposable,
redacted test key. Record the preflight result, final status, and conclusion for
each endpoint. Update `contracts/providers.json` `browserCors` only after the
matrix is complete.

| Endpoint | Method | Preflight | Status | Conclusion | Evidence |
| --- | --- | --- | --- | --- | --- |
| `/key` | `GET` | Pending | Pending | Pending | |
| `/models` | `GET` | Pending | Pending | Pending | |
| `/chat/completions` | `POST` | Pending | Pending | Pending | |

If any required endpoint is blocked, record the web release as blocked and keep
Android as the fallback. Do not add a hidden proxy.

## 3. OpenRouter conformance checklist

| Check | Web | Android | Evidence |
| --- | --- | --- | --- |
| `GET /key` validates the key and exposes label, usage, limit, free-tier, and rate-limit metadata | Pending | Pending | |
| `GET /models` populates paid, free, vision, reasoning, and long-context models | Pending | Pending | |
| Pricing strings convert correctly and display per million tokens; free models are marked | Pending | Pending | |
| Context window, max output tokens, input/output modalities, and supported parameters are mapped | Pending | Pending | |
| Chat streams incrementally from `POST /chat/completions` | Pending | Pending | |
| `: OPENROUTER PROCESSING` keep-alives and unknown SSE fields do not break parsing | Pending | Pending | |
| Reasoning content is normalized and displayed separately from the answer | Pending | Pending | |
| Final usage and cost are persisted and visible | Pending | Pending | |
| `402`, `429`, `503`, context errors, and errors inside a 200 stream are handled | Pending | Pending | |
| Provider routing preferences serialize correctly and protected fields cannot be overridden | Pending | Pending | |
| Attribution headers are configurable and contain no user data | Pending | Pending | |
| Browser-direct CORS behavior is verified, or the web release is explicitly blocked | Pending | Pending | |

## 4. Fixture conformance

Expected vectors live in `contracts/fixtures/expected/`. Request vectors live in
`contracts/fixtures/requests/`. Both clients must pass the same vectors.

| Fixture or vector | Web | Android | Evidence |
| --- | --- | --- | --- |
| `openrouter-text.sse` / `openrouter-text.json` | Pending | Pending | |
| `openrouter-keepalive.sse` / `openrouter-keepalive.json` | Pending | Pending | |
| `openrouter-reasoning.sse` / `openrouter-reasoning.json` | Pending | Pending | |
| `openrouter-usage.sse` / `openrouter-usage.json` | Pending | Pending | |
| `openrouter-error-200.sse` / `openrouter-error-200.json` | Pending | Pending | |
| `openrouter-key.json` / `openrouter-key.json` | Pending | Pending | |
| `openrouter-errors.json` | Pending | Pending | |
| `openrouter-models.json` catalog mapping | Pending | Pending | |
| `openrouter-basic.json` request serialization | Pending | Pending | |
| `openrouter-routing.json` curated routing | Pending | Pending | |
| `openrouter-unsupported-parameter.json` omission | Pending | Pending | |
| `openrouter-advanced-protected.json` protected-field rejection | Pending | Pending | |
| `openrouter-attribution.json` attribution headers | Pending | Pending | |
| `openrouter-default-model.json` fallback selection | Pending | Pending | |

## 5. Critical cases

M1 targets critical cases 1-10, 15-18, 20-21, and 28 from spec 15.2.

| Case | Description | Web | Android | Evidence |
| --- | --- | --- | --- | --- |
| 1 | First run with no provider shows onboarding, preselects OpenRouter, and blocks sending | Pending | Pending | |
| 2 | Invalid OpenRouter key shows an auth error, preserves the form, and does not accept the key | Pending | Pending | |
| 3 | Valid OpenRouter key passes `GET /key`, loads `/models`, and selects a default model | Pending | Pending | |
| 4 | Catalog mapping handles paid, free, vision, reasoning, and tool models; converts prices; ignores unknown fields | Pending | Pending | |
| 5 | Streaming ignores keep-alives and appends text in order | Pending | Pending | |
| 6 | `reasoning` and `reasoning_details` produce one normalized reasoning stream | Pending | Pending | |
| 7 | Usage and cost from the final chunk are persisted and displayed | Pending | Pending | |
| 8 | An error object inside an HTTP 200 stream stops the message, preserves partial text, and does not auto-retry | Pending | Pending | |
| 9 | 402 insufficient credits maps to `quota` with a billing message and no retry | Pending | Pending | |
| 10 | 503 no available upstream provider retries with backoff | Pending | Pending | |
| 15 | Cancel stops the request, persists partial text, and marks `cancelled` | Pending | Pending | |
| 16 | Network failure before first token retries up to 3 times | Pending | Pending | |
| 17 | Network failure after first token does not auto-retry | Pending | Pending | |
| 18 | Switching model mid-conversation persists and uses the new model on the next send | Pending | Pending | |
| 20 | Routing preferences serialize correctly and protected fields cannot be overridden | Pending | Pending | |
| 21 | Reload during streaming marks the message interrupted and preserves partial text | Pending | Pending | |
| 28 | 429 with `Retry-After` schedules a retry and shows a countdown | Pending | Pending | |

## 6. Manual smoke

| Scenario | Web | Android | Evidence |
| --- | --- | --- | --- |
| New user onboards and sends a first message in under two minutes | Pending | Pending | |
| Invalid key is actionable and the form is preserved | Pending | Pending | |
| Model picker shows price, context, modalities, and free badge | Pending | Pending | |
| Streaming text appears incrementally and stop works | Pending | Pending | |
| Reasoning is visually distinct from the answer | Pending | Pending | |
| Usage and cost are visible after a completed turn | Pending | Pending | |
| Reload during streaming preserves partial text and marks it interrupted | Pending | Pending | |
| Conversation survives restart and model switching persists | Pending | Pending | |
| Routing summary reflects active preferences | Pending | Pending | |
| Browser CORS result is recorded or the web release is blocked | Pending | Pending | |

## 7. Exit-gate evidence matrix

| Exit-gate requirement | Evidence to capture | Responsible tasks | Status |
| --- | --- | --- | --- |
| New user onboards and chats in under two minutes | Timed manual web and Android smoke record with screenshots | M1-03, M1-04, M1-07, M1-09, M1-10 | Pending |
| Valid key passes `/key`; invalid key is actionable and not accepted | Adapter tests, component/ViewModel tests, manual invalid-key check | M1-02, M1-03, M1-06, M1-07 | Pending |
| Catalog maps paid, free, vision, reasoning, long-context, and tool models | Fixture test output from both clients against `openrouter-models.json` | M1-02, M1-06, M1-10 | Pending |
| Streaming appends text; keep-alives and unknown fields do not break parsing | Fixture tests plus manual stream | M1-02, M1-04, M1-06, M1-09 | Pending |
| Reasoning appears once and is visually distinct | Expected-vector test and UI evidence | M1-01, M1-02, M1-04, M1-06, M1-09 | Pending |
| Final usage and cost persisted and visible | Usage fixture test, persisted-message assertion, UI screenshot | M1-02, M1-04, M1-06, M1-09 | Pending |
| 402, 429, 503, context-length, in-stream errors, and cancellation map correctly | Error fixture tests, fake-timer retry tests, cancel tests, manual error evidence | M1-02, M1-06, M1-10 | Pending |
| Routing serializes correctly and protected fields cannot be overridden | Shared request vectors tested on both adapters plus UI routing evidence | M1-01, M1-02, M1-06, M1-10 | Pending |
| Conversations survive restart; model switching persists; messages keep snapshots | Repository/persistence tests and manual reload check | M1-03, M1-05, M1-07, M1-08 | Pending |
| Browser-direct OpenRouter behavior is verified for all three endpoints or web release is blocked | CORS matrix with preflight/status/conclusion and updated `browserCors` | M1-10 | Pending |
| No OpenRouter regression; critical cases 1-10, 15-18, 20-21, 28 pass on both platforms | Test output mapped to critical-case IDs | M1-02..M1-09, M1-10 | Pending |
| `contracts/parity.md` reflects what actually works | Parity review with implementation-status evidence and no M2 overclaim | M1-10 | Pending |

## 8. Default-model fallback rule

The rule is recorded in `contracts/fixtures/requests/README.md` and encoded in
`contracts/fixtures/requests/openrouter-default-model.json`:

1. Use `defaultModelId` from `contracts/providers.json` when it exists in the
   loaded catalog.
2. Otherwise select the first non-hidden model with text output, ordered by
   `remoteId` ascending.
3. If the preset default is missing, surface a non-blocking unavailable notice
   and do not invent a model.

## 9. Sign-off

| Area | Owner | Date | Result | Evidence |
| --- | --- | --- | --- | --- |
| Contracts and fixtures | | | Pending | |
| Web adapter and chat | | | Pending | |
| Android adapter and chat | | | Pending | |
| CORS and browser release decision | | | Pending | |
| Parity and final exit gate | | | Pending | |
