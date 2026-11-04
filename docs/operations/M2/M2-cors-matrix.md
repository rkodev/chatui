# M2 CORS Matrix Template

- Status: Template. No live results recorded yet.
- Owner: M2-02.4 records web adapter results; M2-09.1 records the final matrix.
- Scope: every preset in `contracts/providers.json`.
- Source plan: [M2 Providers and Content Implementation Plan](../M2-providers-and-content-implementation-plan.md)
- Authoritative spec: [IMPLEMENTATION.md](../../IMPLEMENTATION.md) section 7.7

This file is the shared evidence location for browser-direct provider access.
Do not mark a row supported until the preflight and request have actually run.
Never record an API key, a real prompt, a real conversation, or personal data.
Record only status codes, headers, timestamps, and conclusions.

## 1. Matrix

`Pending` means no evidence exists. `N/A` means the endpoint does not apply to
that preset. `Blocked` means the provider cannot be used browser-direct and the
web release must say so.

| Provider | Endpoint | Method | Preflight | Status | Conclusion | Evidence |
| --- | --- | --- | --- | --- | --- | --- |
| openrouter | `/key` | `GET` | Pending | Pending | Pending | Pending |
| openrouter | `/models` | `GET` | Pending | Pending | Pending | Pending |
| openrouter | `/chat/completions` | `POST` | Pending | Pending | Pending | Pending |
| openai | `/models` | `GET` | Pending | Pending | Pending | Pending |
| openai | `/chat/completions` | `POST` | Pending | Pending | Pending | Pending |
| anthropic | `/models` | `GET` | Pending | Pending | Pending | Pending |
| anthropic | `/messages` | `POST` | Pending | Pending | Pending | Pending |
| google | `/models` | `GET` | Pending | Pending | Pending | Pending |
| google | `/models/{model}:streamGenerateContent?alt=sse` | `POST` | Pending | Pending | Pending | Pending |
| groq | `/models` | `GET` | Pending | Pending | Pending | Pending |
| groq | `/chat/completions` | `POST` | Pending | Pending | Pending | Pending |
| mistral | `/models` | `GET` | Pending | Pending | Pending | Pending |
| mistral | `/chat/completions` | `POST` | Pending | Pending | Pending | Pending |
| deepseek | `/models` | `GET` | Pending | Pending | Pending | Pending |
| deepseek | `/chat/completions` | `POST` | Pending | Pending | Pending | Pending |
| xai | `/models` | `GET` | Pending | Pending | Pending | Pending |
| xai | `/chat/completions` | `POST` | Pending | Pending | Pending | Pending |
| together | `/models` | `GET` | Pending | Pending | Pending | Pending |
| together | `/chat/completions` | `POST` | Pending | Pending | Pending | Pending |
| fireworks | `/models` | `GET` | Pending | Pending | Pending | Pending |
| fireworks | `/chat/completions` | `POST` | Pending | Pending | Pending | Pending |
| ollama | `/api/tags` | `GET` | Pending | Pending | Pending | Pending |
| ollama | `/api/chat` | `POST` | Pending | Pending | Pending | Pending |
| ollama | `/v1/chat/completions` | `POST` | Pending | Pending | Pending | Pending |
| lmstudio | `/models` | `GET` | Pending | Pending | Pending | Pending |
| lmstudio | `/chat/completions` | `POST` | Pending | Pending | Pending | Pending |
| llamacpp | `/models` | `GET` | Pending | Pending | Pending | Pending |
| llamacpp | `/chat/completions` | `POST` | Pending | Pending | Pending | Pending |
| custom | `/models` | `GET` | Pending | Pending | Pending | Pending |
| custom | `/chat/completions` | `POST` | Pending | Pending | Pending | Pending |

## 2. Verification plan

For each row:

1. Send an `OPTIONS` preflight from the web dev origin with the headers the
   adapter will send. Record the status, `access-control-allow-origin`,
   `access-control-allow-methods`, `access-control-allow-headers`, and
   `access-control-max-age`.
2. Send the real request with a disposable key or no key where the endpoint is
   public. Record the status and the response headers that affect browser
   readability.
3. Confirm the browser `fetch()` response is readable and reports
   `type: "cors"`. A network error with no readable response is a CORS failure,
   not a provider outage.
4. Record the UTC timestamp and a bounded evidence note. Do not paste full
   response bodies or logs.
5. Set the preset `browserCors` to `supported`, `unsupported`, or `unknown` in
   `contracts/providers.json` only after the evidence exists.

Provider-specific notes:

- Anthropic requires `anthropic-dangerous-direct-browser-access: true` from the
  web app.
- Google must use `x-goog-api-key`, never a query parameter.
- Ollama requires `OLLAMA_ORIGINS` for the web origin. Android is unaffected.
- Local presets may be blocked by HTTPS-to-HTTP or private-network rules even
  when the server allows CORS.
- Custom providers must expose a "Test from browser" diagnostic that separates
  DNS/TLS, CORS, auth, and model errors.
- Never add a hidden proxy to work around CORS. Surface a `cors` error and
  suggest the Android app or a future self-hosted proxy.

## 3. Conclusion

No conclusion is recorded yet. M2-09.1 fills this section after the matrix is
complete and updates `contracts/providers.json` to match the evidence.
