# Request vectors

These files are the shared, deterministic request-side expectations for the
OpenRouter adapter. Web and Android must serialize the same normalized request
into the same OpenRouter JSON body, omit unsupported controls, reject protected
field overrides, and send the same attribution headers.

The validator in `scripts/validate-contracts.mjs` reads every `*.json` file in
this directory. A file without an expected result is a validation error.

## Common shape

Every file has:

```json
{
  "name": "openrouter-basic",
  "kind": "chat-request",
  "providerId": "openrouter",
  "expected": {}
}
```

- `name` must match the file name without `.json`.
- `kind` is one of `chat-request`, `protected-fields`, `attribution`, or
  `default-model`.
- `providerId` is `openrouter` for M1.
- `expected` is required. Its shape depends on `kind`.

## Normalized request

`chat-request` and `protected-fields` files carry a normalized request using the
spec 7.3 shape:

```json
{
  "model": "openai/gpt-4o-mini-2024-07-18",
  "system": "You are a concise assistant.",
  "messages": [
    { "role": "user", "parts": [{ "type": "text", "text": "Hello" }] }
  ],
  "params": {
    "temperature": null,
    "topP": null,
    "maxOutputTokens": 256,
    "stop": [],
    "reasoningEffort": null,
    "streaming": true,
    "extra": {}
  }
}
```

`supportedParameters` is the effective OpenRouter `supported_parameters` list
for the selected model. It controls which optional controls may be serialized.

### Serialization rules

- The system prompt is emitted as the first `messages` entry with
  `role: "system"` when `system` is not `null`.
- Each normalized message becomes one OpenRouter message. For M1 text-only
  vectors, the `content` is the concatenation of the message's `text` parts.
- `stream` is always `params.streaming`.
- `stream_options` is always `{ "include_usage": true }` for streaming requests.
- `max_tokens` is emitted only when `params.maxOutputTokens` is not `null` and
  `supportedParameters` contains `max_tokens`.
- `temperature`, `top_p`, and `stop` follow the same supported-parameter rule.
- `reasoning` is emitted only when `params.reasoningEffort` is not `null` and
  `supportedParameters` contains `reasoning`. It is `{ "effort": "<value>" }`.
- `provider` is emitted only when `params.extra.openrouter.provider` is present.
  The curated M1 controls are `allow_fallbacks`, `sort` (`price`, `throughput`,
  `latency`), and `data_collection` (`allow`, `deny`).
- Unsupported controls are omitted, never sent. `openrouter-unsupported-parameter.json`
  is the regression vector for this rule.

### Protected fields

`model`, `messages`, `stream`, and `stream_options` are protected. Advanced JSON
under `params.extra.openrouter` may not override them. A `protected-fields`
vector must produce a normalized `invalid_request` error and must not contain an
expected request body. The error `details.protectedFields` lists the offending
keys in ascending order.

## Attribution

`attribution` vectors map runtime app config to the OpenRouter attribution
headers:

```json
{
  "appConfig": { "name": "ChatUI", "origin": "https://chatui.example" },
  "expected": {
    "headers": {
      "HTTP-Referer": "https://chatui.example",
      "X-Title": "ChatUI"
    }
  }
}
```

`contracts/providers.json` keeps the placeholder mapping
`HTTP-Referer -> app.origin` and `X-Title -> app.name`. The runtime substitutes
the configured public origin and app name. Attribution headers must never
contain personal data or secrets.

## Default-model fallback rule

The rule is deterministic and testable:

1. Use `defaultModelId` from `contracts/providers.json` when it exists in the
   loaded catalog. The preset default wins even if the model is hidden.
2. Otherwise select the first non-hidden model whose `outputModalities`
   includes `text`, ordered by `remoteId` ascending.
3. If the preset default is missing from the catalog, surface a non-blocking
   unavailable notice and do not invent a model. When a fallback is selected the
   notice is
   `Default model <presetDefaultModelId> is unavailable; using <selectedModelId>.`
   When no text model exists the notice is
   `Default model <presetDefaultModelId> is unavailable; no text model is available.`
   and `selectedModelId` is `null`.

`openrouter-default-model.json` encodes the present, missing, and no-text-model
cases. The validator recomputes the selection and rejects a vector whose
expected result does not match the rule.

## Non-OpenRouter provider vectors

M2 adds vectors for `openai` (OpenAI-compatible), `anthropic`, `google`
(Gemini), and `ollama`. They use the same top-level shape as the OpenRouter
vectors. `providerId` must be one of `openrouter`, `openai`, `anthropic`,
`google`, or `ollama`.

Each provider has four vectors:

- `<provider>-basic.json`: system prompt and one text message.
- `<provider>-attachments.json`: text, image, and file content parts.
- `<provider>-extras.json`: provider-specific JSON under
  `params.extra[<providerId>]`.
- `<provider>-protected.json`: protected-field rejection.

### Resolved attachments

Normalized parts reference attachments by id. The vector supplies the resolved
bytes in an optional `attachments` map:

```json
{
  "att-image-1": {
    "mimeType": "image/png",
    "name": "pixel.png",
    "dataBase64": "..."
  }
}
```

File parts are appended as text with the deterministic header
`\n\n[File: <name>]\n<utf-8 content>`.

### Provider serialization

- OpenAI-compatible: system becomes the first `messages` entry. Text and file
  parts concatenate into `content`; if an image is present, `content` is an
  ordered array of text and `image_url` parts. `stream_options` is always
  `{ "include_usage": true }`.
- Anthropic: system is the top-level `system` field. Messages use text and
  base64 image blocks. `max_tokens` is always emitted; when unset the adapter
  uses `1024`. `stop` maps to `stop_sequences`.
- Gemini: system is `systemInstruction`. Messages become `contents[]` with
  `role: "model"` for assistant turns. Images use `inlineData`. Generation
  controls live under `generationConfig`; `stop` maps to `stopSequences`.
  Streaming is selected by the endpoint, not the body.
- Ollama native: system becomes the first `messages` entry. Text and file parts
  concatenate into `content`; images become the message `images` array.
  Generation controls live under `options`; `maxOutputTokens` maps to
  `num_predict` and `stop` maps to `stop`.

### Extras namespace

Provider-specific JSON is namespaced by preset id under `params.extra`. Only the
active namespace is read. Non-protected keys are merged into the provider body
at the top level. OpenRouter keeps its existing curated `openrouter` namespace.

### Protected fields

`model`, `messages`, `stream`, and `stream_options` are protected for every
provider. A protected-field vector must expect an `invalid_request` error with
`details.protectedFields` sorted ascending and must not contain an expected
body.

## Secrets

These vectors contain no keys, real prompts, or personal data. Do not add a real
API key, a real user prompt, or a real deployment origin to this directory.
