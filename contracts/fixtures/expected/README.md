# Expected OpenRouter vectors

These files are the shared, deterministic expectations for the raw OpenRouter
fixtures. Web and Android adapters must produce the same normalized events,
text, reasoning, usage, and errors.

## Stream vector shape

Every `openrouter-*.json` stream vector uses the same top-level shape:

```json
{
  "source": "openrouter-text.sse",
  "events": [
    { "type": "start", "responseId": "gen-example-text" },
    { "type": "text-delta", "text": "Hello" },
    { "type": "finish", "reason": "stop" },
    {
      "type": "usage",
      "usage": {
        "inputTokens": 12,
        "outputTokens": 4,
        "totalTokens": 16,
        "costUsd": 0.000012,
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

`error` events and the `openrouter-errors.json` cases use the deterministic
part of `ChatError`: `code`, `message`, `providerId`, `status`, `retryable`,
`retryAfterSeconds`, `requestId`, and `details`. The runtime `at` timestamp is
assigned by the adapter and is intentionally excluded from these vectors.

## Reasoning precedence rule

When a delta contains both `reasoning_details` and `reasoning`, the structured
`reasoning_details` text wins. Across deltas, emit each distinct reasoning text
once per turn: a later delta whose text has already been emitted is suppressed,
regardless of whether it came from `reasoning` or `reasoning_details`.

In `openrouter-reasoning.sse` the plain `reasoning` delta arrives first and the
identical `reasoning_details` delta arrives second. The expected vector emits
the text once and suppresses the duplicate. The emitted text is identical, so
the vector does not depend on which field supplied the first occurrence.

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

`openrouter-errors.json` contains one case per normalized `ChatError`. A case
with a `fixture` field references a raw envelope under
`contracts/fixtures/errors/`. A case with `"synthetic": true` has no raw fixture
because the M0 fixture set does not include that status; the adapter test
constructs the envelope inline.

The required cases are 402 (`quota`), 429 (`rate_limit` with
`retryAfterSeconds: 2`), 503 (`provider`), 401 (`auth`), 404
(`model_not_found`), and 400 context-length (`context_length`).
