# M2 CORS Matrix

- Status: Verified 2026-11-06 for cloud presets; local and custom presets remain `unknown`.
- Owner: M2-02.4 records web adapter results; M2-09.1 records the final matrix.
- Scope: every preset in `contracts/providers.json`.
- Source plan: [M2 Providers and Content Implementation Plan](../M2-providers-and-content-implementation-plan.md)
- Authoritative spec: [IMPLEMENTATION.md](../../IMPLEMENTATION.md) section 7.7

This file is the shared evidence location for browser-direct provider access.
Do not mark a row supported until the preflight and request have actually run.
Never record an API key, a real prompt, a real conversation, or personal data.
Record only status codes, headers, timestamps, and conclusions.

## 1. Verification environment

- Date: 2026-11-06.
- Origin: `http://localhost:5173` (Vite dev origin).
- HTTP preflight/header probe: Node 24 `fetch` with `Origin`,
  `Access-Control-Request-Method`, and `Access-Control-Request-Headers`.
- Browser readability probe: headless Chromium 1243 from the same origin. Each
  request used a fixed, non-secret placeholder credential and a fixed probe
  payload. No real key, prompt, conversation, or response body is recorded.
- Cloud responses were `401`, `400`, or `404` because the credential was a
  placeholder. A readable response with `type: "cors"` proves CORS readability;
  it does not claim a valid-key success.
- Local presets had no running server and custom is a placeholder host, so they
  remain `unknown` rather than being marked supported from an assumption.

## 2. Matrix

`Pending` means no evidence exists. `N/A` means the endpoint does not apply to
that preset. `Blocked` means the provider cannot be used browser-direct and the
web release must say so. `/models` is also the key-validation endpoint for
presets whose contract sets `keyValidation.path` to `/models`.

| Provider | Endpoint | Method | Origin | Preflight | Status | Conclusion | Timestamp (UTC) | Evidence |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| openrouter | `/key` | `GET` | `http://localhost:5173` | `204`; ACAO `*`; allows `Authorization`, `HTTP-Referer`, `X-Title` | `401` placeholder key | Supported | `2026-11-06T09:41:50Z` | Browser fetch `type: cors`; readable JSON error; no CORS error |
| openrouter | `/models` | `GET` | `http://localhost:5173` | `204`; ACAO `*`; allows `Authorization`, `HTTP-Referer`, `X-Title` | `200` public | Supported | `2026-11-06T09:41:49Z` | Browser fetch `type: cors`; public catalog readable |
| openrouter | `/chat/completions` | `POST` | `http://localhost:5173` | `204`; ACAO `*`; allows `Authorization`, `Content-Type`, `HTTP-Referer`, `X-Title` | `401` placeholder key | Supported | `2026-11-06T09:41:50Z` | Browser fetch `type: cors`; readable JSON error |
| openai | `/models` | `GET` | `http://localhost:5173` | `200`; ACAO `*`; allows `Authorization`; max-age `600` | `401` placeholder key | Supported | `2026-11-06T09:41:50Z` | Browser fetch `type: cors`; readable JSON error |
| openai | `/chat/completions` | `POST` | `http://localhost:5173` | `200`; ACAO origin; allows `Content-Type`, `Authorization`; max-age `86400` | `401` placeholder key | Supported | `2026-11-06T09:41:51Z` | Browser fetch `type: cors`; readable JSON error |
| anthropic | `/models` | `GET` | `http://localhost:5173` | `200`; ACAO `*`; allows `anthropic-version`, `anthropic-dangerous-direct-browser-access`, `x-api-key`; max-age `600` | `401` placeholder key | Supported | `2026-11-06T09:41:53Z` | Browser fetch `type: cors`; browser-access header accepted |
| anthropic | `/messages` | `POST` | `http://localhost:5173` | `200`; ACAO `*`; allows `Content-Type`, `anthropic-version`, `anthropic-dangerous-direct-browser-access`, `x-api-key`; max-age `600` | `401` placeholder key | Supported | `2026-11-06T09:41:54Z` | Browser fetch `type: cors`; readable JSON error |
| google | `/models` | `GET` | `http://localhost:5173` | `200`; ACAO origin; allows `x-goog-api-key`; max-age `3600` | `400` placeholder key | Supported | `2026-11-06T09:41:54Z` | Browser fetch `type: cors`; header auth accepted |
| google | `/models/{model}:streamGenerateContent?alt=sse` | `POST` | `http://localhost:5173` | `200`; ACAO origin; allows `Content-Type`, `x-goog-api-key`; max-age `3600` | `400` placeholder key | Supported | `2026-11-06T09:41:55Z` | Browser fetch `type: cors`; readable error stream |
| groq | `/models` | `GET` | `http://localhost:5173` | `204`; ACAO `*`; allows `Authorization` | `401` placeholder key | Supported | `2026-11-06T09:41:56Z` | Browser fetch `type: cors`; readable JSON error |
| groq | `/chat/completions` | `POST` | `http://localhost:5173` | `204`; ACAO `*`; allows `Content-Type`, `Authorization` | `401` placeholder key | Supported | `2026-11-06T09:41:57Z` | Browser fetch `type: cors`; readable JSON error |
| mistral | `/models` | `GET` | `http://localhost:5173` | `200`; ACAO `*`; allows `Authorization`, `Content-Type`, and client headers; max-age `3600` | `401` placeholder key | Supported | `2026-11-06T09:41:57Z` | Browser fetch `type: cors`; readable JSON error |
| mistral | `/chat/completions` | `POST` | `http://localhost:5173` | `200`; ACAO `*`; allows `Authorization`, `Content-Type`, and client headers; max-age `3600` | `401` placeholder key | Supported | `2026-11-06T09:41:58Z` | Browser fetch `type: cors`; readable JSON error |
| deepseek | `/models` | `GET` | `http://localhost:5173` | `200`; ACAO origin; allows `Authorization` | `401` placeholder key | Supported | `2026-11-06T09:41:58Z` | Browser fetch `type: cors`; readable JSON error |
| deepseek | `/chat/completions` | `POST` | `http://localhost:5173` | `200`; ACAO origin; allows `Content-Type`, `Authorization` | `401` placeholder key | Supported | `2026-11-06T09:41:59Z` | Browser fetch `type: cors`; readable JSON error |
| xai | `/models` | `GET` | `http://localhost:5173` | `200`; ACAO `*`; allows methods `*` and headers `*` | `400` placeholder key | Supported | `2026-11-06T09:41:59Z` | Browser fetch `type: cors`; readable JSON error |
| xai | `/chat/completions` | `POST` | `http://localhost:5173` | `200`; ACAO `*`; allows methods `*` and headers `*` | `400` placeholder key | Supported | `2026-11-06T09:42:01Z` | Browser fetch `type: cors`; readable JSON error |
| together | `/models` | `GET` | `http://localhost:5173` | `200`; ACAO `*`; allows `Authorization` | `401` placeholder key | Supported | `2026-11-06T09:42:02Z` | Browser fetch `type: cors`; readable JSON error |
| together | `/chat/completions` | `POST` | `http://localhost:5173` | `200`; ACAO `*`; allows `Content-Type`, `Authorization` | `401` placeholder key | Supported | `2026-11-06T09:42:04Z` | Browser fetch `type: cors`; readable JSON error |
| fireworks | `/models` | `GET` | `http://localhost:5173` | `200`; ACAO `*`; allows `Authorization` | `401` placeholder key | Supported | `2026-11-06T09:42:06Z` | Browser fetch `type: cors`; readable JSON error |
| fireworks | `/chat/completions` | `POST` | `http://localhost:5173` | `200`; ACAO `*`; allows `Content-Type`, `Authorization` | `404` placeholder model | Supported | `2026-11-06T09:42:07Z` | Browser fetch `type: cors`; readable JSON error |
| ollama | `/api/tags` | `GET` | `http://localhost:5173` | Not run | Pending | Unknown | — | No local Ollama server running; see `OLLAMA_ORIGINS` note |
| ollama | `/api/chat` | `POST` | `http://localhost:5173` | Not run | Pending | Unknown | — | No local Ollama server running; see `OLLAMA_ORIGINS` note |
| ollama | `/v1/chat/completions` | `POST` | `http://localhost:5173` | Not run | Pending | Unknown | — | No local Ollama server running; see `OLLAMA_ORIGINS` note |
| lmstudio | `/models` | `GET` | `http://localhost:5173` | Not run | Pending | Unknown | — | No local LM Studio server running |
| lmstudio | `/chat/completions` | `POST` | `http://localhost:5173` | Not run | Pending | Unknown | — | No local LM Studio server running |
| llamacpp | `/models` | `GET` | `http://localhost:5173` | Not run | Pending | Unknown | — | No local llama.cpp server running |
| llamacpp | `/chat/completions` | `POST` | `http://localhost:5173` | Not run | Pending | Unknown | — | No local llama.cpp server running |
| custom | `/models` | `GET` | `http://localhost:5173` | Not run | Pending | Unknown | — | Base URL is a placeholder; verify per user configuration |
| custom | `/chat/completions` | `POST` | `http://localhost:5173` | Not run | Pending | Unknown | — | Base URL is a placeholder; verify per user configuration |

## 3. Verification plan

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
  web app. The preflight allow-list includes it.
- Google must use `x-goog-api-key`, never a query parameter. The preflight
  allow-list includes it.
- Ollama requires `OLLAMA_ORIGINS` for the web origin. Android is unaffected.
- Local presets may be blocked by HTTPS-to-HTTP or private-network rules even
  when the server allows CORS.
- Custom providers must expose a "Test from browser" diagnostic that separates
  DNS/TLS, CORS, auth, and model errors.
- Never add a hidden proxy to work around CORS. Surface a `cors` error and
  suggest the Android app or a future self-hosted proxy.

## 4. Conclusion

All ten cloud presets (OpenRouter, OpenAI, Anthropic, Google Gemini, Groq,
Mistral, DeepSeek, xAI, Together, and Fireworks) completed a preflight and a
readable browser request from the Vite dev origin. Their `browserCors` values
are `supported` in `contracts/providers.json`.

Ollama, LM Studio, llama.cpp, and Custom remain `unknown`: no local server was
available and the Custom base URL is only a placeholder. They must not be
presented as verified until a real configured server passes the same checks.
No proxy was added.

## 5. Unsupported and local fallback paths

- Cloud preset with a CORS failure: show the `cors` error, suggest the Android
  app or a future self-hosted proxy, and never retry through a hidden proxy.
- Ollama: set `OLLAMA_ORIGINS` to the app origin, or use the Android app.
- LM Studio: enable CORS in the local server settings, or use the Android app.
- llama.cpp: start the server with CORS enabled for the app origin, or use the
  Android app.
- Custom provider: use the "Test from browser" diagnostic to separate DNS/TLS,
  CORS, auth, and model errors before claiming support.
