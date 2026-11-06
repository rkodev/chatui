# Expected contract vectors

These files are the shared, deterministic expectations for the raw provider
fixtures. Web and Android adapters must produce the same normalized events,
text, reasoning, usage, and errors.

## Stream vector shape

Every `*.json` stream vector uses the same top-level shape:

```json
{
  "source": "openai-text.sse",
  "events": [
    { "type": "start", "responseId": "chatcmpl-example-text" },
    { "type": "text-delta", "text": "Hello" },
    { "type": "finish", "reason": "stop" },
    {
      "type": "usage",
      "usage": {
        "inputTokens": 8,
        "outputTokens": 5,
        "totalTokens": 13,
        "costUsd": null,
        "estimated": false
      }
    }
  ],
  "text": "Hello",
  "reasoning": "",
  "retryAllowed": false
}
```

- `source` names the raw fixture under `contracts/fixtures/streams/`.
- `events` is the ordered normalized event stream. Supported event types are
  `start`, `text-delta`, `reasoning-delta`, `tool-call-delta`, `usage`,
  `finish`, and `error`.
- `text` is the concatenated assistant text.
- `reasoning` is the concatenated reasoning text.
- `retryAllowed` is `false` once any text or reasoning has been emitted, or
  when the error is not retryable.

`usage` always contains all five fields. `inputTokens`, `outputTokens`, and
`totalTokens` are integers or `null`; `costUsd` is a number or `null`;
`estimated` is `true` only when the adapter had to estimate usage.

Event order follows the provider's terminal sequence. OpenAI, Gemini, and
Ollama report the terminal finish before the final usage, so `finish` precedes
`usage`. Anthropic reports final usage in `message_delta` before `message_stop`,
so `usage` precedes `finish` in `anthropic-text.json`.

## Provider stream vectors

| Expected file | Raw fixture | Notes |
| --- | --- | --- |
| `openai-text.json` | `openai-text.sse` | Text deltas, `stop`, final usage. |
| `openai-reasoning.json` | `openai-reasoning.sse` | `reasoning_content` becomes `reasoning-delta`; answer text stays separate. |
| `anthropic-text.json` | `anthropic-text.sse` | Text deltas, `end_turn` -> `stop`, merged input/output usage. |
| `gemini-text.json` | `gemini-text.sse` | Text deltas, `STOP` -> `stop`, `usageMetadata` mapping. |
| `ollama-ndjson.json` | `ollama-ndjson.txt` | NDJSON text deltas, `done_reason` -> `stop`, final usage. |
| `error-429.json` | `error-429.sse` | In-stream 429 maps to `rate_limit`, retryable, `retryAllowed: true`. |

## Anthropic thinking/text separation

`anthropic-text.sse` contains text blocks only. `anthropic-thinking.json`
defines the synthetic thinking-then-text case that adapters must satisfy:
`thinking_delta` becomes `reasoning-delta`, `text_delta` becomes `text-delta`,
and the two streams are never concatenated.

## Gemini finish reason mapping

`gemini-finish-reasons.json` defines the required mapping:

| Gemini `finishReason` | Normalized `finish.reason` |
| --- | --- |
| `STOP` | `stop` |
| `MAX_TOKENS` | `length` |
| `SAFETY` | `content_filter` |
| `RECITATION` | `content_filter` |

## Key vector shape

`openrouter-key.json` normalizes `contracts/fixtures/models/openrouter-key.json`:

```json
{
  "source": "openrouter-key.json",
  "providerId": "openrouter",
  "key": {
    "label": "Example OpenRouter Key",
    "usageUsd": 12.34,
    "limitUsd": 100,
    "limitRemainingUsd": 87.66,
    "isFreeTier": false,
    "rateLimit": { "requests": 60, "interval": "1m" }
  }
}
```

`limitUsd`, `limitRemainingUsd`, and `rateLimit` may be `null` when the provider
does not report them.

## Error vector shape

`openrouter-errors.json` contains one case per normalized OpenRouter
`ChatError`. `provider-errors.json` contains the non-OpenRouter cases.

A case with a `fixture` field references a raw envelope under
`contracts/fixtures/errors/`. A case with `"synthetic": true` has no raw
fixture; the adapter test constructs the envelope inline.

Provider HTTP error fixtures use this envelope:

```json
{
  "status": 429,
  "headers": { "retry-after": "2" },
  "body": { "error": { "message": "Rate limit reached." } }
}
```

In-stream error fixtures are raw SSE or NDJSON files under
`contracts/fixtures/errors/`. Their cases set `"kind": "stream"` and
`"httpStatus": 200`.

`requestId` is the provider request ID when the fixture supplies one
(`x-request-id`, `request-id`, or `x-goog-request-id`), otherwise `null`.
In-stream errors preserve the response ID from the `start` event when one is
available.

The required normalized error codes are `auth`, `permission`, `rate_limit`,
`quota`, `model_not_found`, `context_length`, `content_filter`, `network`,
`timeout`, and `provider`. The validator fails if any code is missing from
`provider-errors.json`.

## Catalog vectors

`catalogs.json` maps the raw model fixtures to normalized catalog entries for
OpenAI, Anthropic, Gemini, and Ollama. Each case carries the preset defaults
used for inference and the expected entries:

```json
{
  "name": "openai",
  "providerId": "openai",
  "fixture": "models/openai-models.json",
  "preset": {
    "inputModalities": ["text"],
    "outputModalities": ["text"],
    "capabilities": ["text", "streaming"],
    "supportedParameters": ["temperature", "top_p", "max_tokens", "stop", "stream"]
  },
  "expected": [
    {
      "remoteId": "gpt-4o-mini",
      "label": "gpt-4o-mini",
      "contextWindow": null,
      "maxOutputTokens": null,
      "inputModalities": ["text"],
      "outputModalities": ["text"],
      "capabilities": ["text", "streaming"],
      "supportedParameters": ["temperature", "top_p", "max_tokens", "stop", "stream"],
      "pricing": null,
      "hidden": false
    }
  ]
}
```

The validator recomputes the mapping and rejects a mismatched field. Embedding
models are marked `hidden` and excluded from text fallback.

## Capability inference vectors

`capability-inference.json` defines the defaults used when a provider omits
modalities, capabilities, context, output limits, or supported parameters. Raw
provider values win over preset defaults. Missing capabilities are derived in
the order `text`, `vision`, `files`, `tools`, `reasoning`, `streaming`.

## Fallback model vectors

`fallback-models.json` lists the fallback model ids for each non-OpenRouter
preset and encodes the default-model rule. When discovery succeeds, the preset
default wins, then the first non-hidden text model by `remoteId`. When discovery
is blocked and the catalog is empty, the first fallback id is selected. The
validator recomputes the selection and notice.

## Math vectors

`math.json` defines inline, block, mixed, invalid, and streaming-incomplete math
input. Valid cases list ordered text and math segments. Invalid or incomplete
input falls back to one raw text segment with `"fallback": "raw"`.

## Export vector

`chatui-export.json` is a schema-valid `chatui.export` document with stable ids,
per-conversation message ordering, model snapshots, provider records, and
attachments with and without `dataBase64`. The validator checks the schema plus
id uniqueness, ordering, references, and base64 shape.

## Naming conventions

- Stream expectations use the raw fixture basename: `<fixture>.json`.
- Synthetic stream cases use `<provider>-<topic>.json`.
- HTTP error fixtures use `<provider>-<status>-<case>.json`.
- In-stream error fixtures use `<provider>-stream-<case>.sse` or `.txt`.
- Error expectation files use `<scope>-errors.json`.

## Broken-vector self-test

`scripts/validate-contracts.mjs` runs a built-in broken-vector self-test on
every invocation. It feeds a deliberately mismatched stream vector and an
invalid `ChatError` code through the validators and fails the contract gate if
the expected file and field are not reported. This keeps the validator honest
without committing a broken vector to the tree.
