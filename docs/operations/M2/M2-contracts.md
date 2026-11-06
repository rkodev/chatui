# M2 Shared Contract Decisions

- Status: Active contract guidance for M2 provider work.
- Source plan: [M2 Providers and Content Implementation Plan](../M2-providers-and-content-implementation-plan.md)
- Authoritative spec: [IMPLEMENTATION.md](../../IMPLEMENTATION.md)
- Validator: `scripts/validate-contracts.mjs`

This file records the shared decisions that web and Android must implement
identically. Fixtures are the executable form of these decisions; this document
explains the intent and the defaults.

## 1. Provider extras namespace

Provider-specific JSON is namespaced by preset id under `params.extra`:

```json
{
  "params": {
    "extra": {
      "openai": { "user": "contract-test" },
      "anthropic": { "metadata": { "user_id": "contract-test" } },
      "google": { "safetySettings": [] },
      "ollama": { "keep_alive": "5m" }
    }
  }
}
```

Rules:

- Only the namespace for the active preset is read. Other namespaces are
  ignored, never merged.
- The namespace value must be a JSON object.
- Non-protected keys are merged into the provider request body at the top
  level. This keeps provider-specific fields out of the normalized domain model.
- OpenRouter keeps its existing curated `params.extra.openrouter` handling:
  `provider`, `models`, `route`, and `reasoning`.
- Extras must never contain secrets, keys, or personal data.

## 2. Protected fields

`model`, `messages`, `stream`, and `stream_options` are protected for every
provider. Advanced JSON under `params.extra[<presetId>]` may not override them.

A protected-field vector must produce a normalized `invalid_request` error with
`details.protectedFields` sorted ascending and must not contain an expected
request body. The validator recomputes the offending keys from the vector.

## 3. Request serialization

Request vectors live in `contracts/fixtures/requests/`. Each vector carries the
normalized request, an optional resolved attachment map, the effective
`supportedParameters`, and the expected provider body.

### Resolved attachments

Normalized content parts reference attachments by id. The caller resolves bytes
before serialization and supplies them in the vector's `attachments` map:

```json
{
  "att-image-1": {
    "mimeType": "image/png",
    "name": "pixel.png",
    "dataBase64": "..."
  }
}
```

File parts are appended as text with a deterministic header:

```text
\n\n[File: <name>]\n<utf-8 content>
```

### OpenAI-compatible

- System prompt becomes the first `messages` entry with `role: "system"`.
- Text and file parts are concatenated into `content` when there is no image.
- If any image part is present, `content` is an ordered array of
  `{ "type": "text", "text": ... }` and
  `{ "type": "image_url", "image_url": { "url": "data:<mime>;base64,<data>" } }`.
- `stream` is `params.streaming`; `stream_options` is
  `{ "include_usage": true }`.
- Optional controls are emitted only when set and listed in
  `supportedParameters`.

### Anthropic

- System prompt is the top-level `system` field.
- Messages use content blocks: `{ "type": "text", "text": ... }` and
  `{ "type": "image", "source": { "type": "base64", "media_type": ..., "data": ... } }`.
- `max_tokens` is always emitted. When `params.maxOutputTokens` is null the
  adapter uses the documented default `1024`.
- `stream` is `params.streaming`. `stream_options` is not sent.
- `stop` maps to `stop_sequences`.

### Gemini

- System prompt is `systemInstruction.parts[].text`.
- Messages become `contents[]`; assistant messages use `role: "model"`.
- Text and file parts become `{ "text": ... }`; images become
  `{ "inlineData": { "mimeType": ..., "data": ... } }`.
- Generation controls live under `generationConfig`; `stop` maps to
  `stopSequences`.
- Streaming is selected by the endpoint (`alt=sse`), not by a body field.

### Ollama native

- System prompt becomes the first `messages` entry with `role: "system"`.
- Text and file parts are concatenated into `content`.
- Images become the message `images` array of base64 strings.
- `stream` is `params.streaming`.
- Generation controls live under `options`; `maxOutputTokens` maps to
  `num_predict` and `stop` maps to `stop`.

## 4. Capability inference

Catalog fields are inferred from preset metadata when the provider omits them.
The executable cases are in
`contracts/fixtures/expected/capability-inference.json`.

Defaults:

- Missing `inputModalities` uses the preset defaults.
- Missing `outputModalities` uses the preset defaults.
- Missing `supportedParameters` uses the preset defaults.
- Missing `contextWindow` and `maxOutputTokens` are `null`, not invented.
- Missing `capabilities` are derived in this order:
  `text` when output includes text, `vision` when input includes image,
  `files` when input includes file, `tools` when supported parameters include
  `tools`, `reasoning` when supported parameters include `reasoning`, and
  `streaming` when the preset advertises streaming or supported parameters
  include `stream`.
- Raw provider values always win over preset defaults.
- Embedding models are marked `hidden` and are excluded from text fallback.

## 5. Fallback model rule

The executable lists and cases are in
`contracts/fixtures/expected/fallback-models.json`.

When discovery succeeds:

1. Use `defaultModelId` from `contracts/providers.json` when it exists in the
   loaded catalog. The preset default wins even if the model is hidden.
2. Otherwise select the first non-hidden model whose `outputModalities`
   includes `text`, ordered by `remoteId` ascending.
3. If the preset default is missing, surface a non-blocking notice:
   `Default model <presetDefaultModelId> is unavailable; using <selectedModelId>.`
   When no text model exists:
   `Default model <presetDefaultModelId> is unavailable; no text model is available.`
   and `selectedModelId` is `null`.

When discovery is blocked and the catalog is empty:

1. Use the first entry in the preset's fallback list and show
   `Discovery unavailable; using fallback model <selectedModelId>.`
2. If the fallback list is empty, select no model and show
   `Discovery unavailable; no fallback model is available.`

Fallback lists are contract data, not client constants. Clients must not invent
model ids.

## 6. Math fixtures

`contracts/fixtures/expected/math.json` defines the shared math rendering
contract:

- Inline math uses `$...$` or `\(...\)`.
- Block math uses `$$...$$` or `\[...\]`.
- A valid case lists ordered segments of `{ "type": "text", "text": ... }` and
  `{ "type": "math", "display": boolean, "source": ... }`.
- Invalid or streaming-incomplete input falls back to one raw text segment and
  `"fallback": "raw"`. Clients must never render partial math as broken HTML.
- The validator rejects unbalanced braces and unclosed delimiters.

## 7. Export fixture

`contracts/fixtures/expected/chatui-export.json` is a schema-valid
`chatui.export` document. It covers:

- Stable conversation and message ids.
- Per-conversation message ordering starting at sequence `0`.
- Model snapshots referenced by messages.
- Provider records without secrets.
- Attachment metadata with optional `dataBase64`; one attachment includes bytes
  and one omits them.
- Settings that reference existing provider and model ids.

The validator checks the schema plus id uniqueness, ordering, references, and
base64 shape. Import must merge by default and must not mutate on invalid input.

## 8. CORS evidence

The CORS matrix template is
[M2-cors-matrix.md](M2-cors-matrix.md). It covers every preset and the
endpoints that matter for discovery, key validation, and chat streaming. It
contains no live results and no secrets. M2-09 records the evidence and updates
`browserCors` in `contracts/providers.json`.

## 9. Validation

```bash
make contracts-check
```

The validator fails with a precise file and field for broken catalogs, request
vectors, capability cases, fallback cases, math cases, and export fixtures.
