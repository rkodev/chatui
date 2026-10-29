# T001 - Shared contracts, presets, fixtures, parity

- Milestone: M0
- Depends on: T000
- Suggested subagent: general
- Parallel-safe: no, this blocks all clients
- Read: `docs/IMPLEMENTATION.md` sections 3.4, 5, 6, 7.2-7.10, 15, 21.3-21.5

## Goal

Create the shared contract layer that every client consumes: provider presets, JSON Schemas, normalized stream fixtures, model catalog fixtures, error fixtures, validation tooling, and the parity checklist. OpenRouter must be the richest and most complete contract.

## Owns

- `contracts/providers.json`
- `contracts/schema/*.schema.json`
- `contracts/fixtures/streams/*`
- `contracts/fixtures/models/*`
- `contracts/fixtures/errors/*`
- `contracts/parity.md`
- `scripts/validate-contracts.mjs`
- `scripts/package.json` and `scripts/package-lock.json` if a validator dependency is needed
- Contracts-related targets in `Makefile` only, if T000 did not already create them

Do not hard-code contracts inside client directories. Do not create client-specific types here.

## Deliverables

### Provider presets

Create `contracts/providers.json` with every preset from spec section 3.4/7.5:

- `openrouter` (default, reference, required)
- `openai`
- `anthropic`
- `google`
- `groq`
- `mistral`
- `deepseek`
- `xai`
- `together`
- `fireworks`
- `ollama`
- `lmstudio`
- `llamacpp`
- `custom`

Each preset must include at least: `id`, `label`, `kind`, `baseUrl`, `auth.type`, `keyValidation`, `modelDiscovery`, `browserCors`, `docsUrl`, `defaultModelId`, and any attribution headers. OpenRouter must include `HTTP-Referer`/`X-Title` mappings and a 24-hour catalog cache TTL. Use the example in spec section 21.3 as the shape reference.

### JSON Schemas

Create Draft 2020-12 schemas:

- `provider-preset.schema.json`
- `model-info.schema.json`
- `chat-export.schema.json`
- `backup-manifest.schema.json`
- `settings.schema.json`

Every schema must include `contractVersion`, a title, and `additionalProperties` behavior appropriate to imports. Export/import schemas must reject unknown top-level formats and enforce size-sensitive fields where practical.

### Stream fixtures

Create the fixtures listed in section 4, with realistic and deliberately awkward content:

- `openrouter-text.sse`
- `openrouter-reasoning.sse` (both `reasoning` and `reasoning_details`)
- `openrouter-usage.sse` (final usage and cost)
- `openrouter-error-200.sse` (error object inside HTTP 200)
- `openrouter-keepalive.sse` (`: OPENROUTER PROCESSING`, unknown fields)
- `openai-text.sse`
- `openai-reasoning.sse`
- `anthropic-text.sse`
- `gemini-text.sse`
- `ollama-ndjson.txt`
- `error-429.sse`

Fixtures must be valid according to their provider protocol and must not contain real keys, prompts, or user data.

### Model and error fixtures

- `openrouter-models.json` with paid, free (`:free`), vision, reasoning, tool-capable, long-context, unusual pricing strings, unknown fields, and canonical slugs.
- `openrouter-key.json` with label, usage, limit, free-tier, and rate-limit metadata.
- `openai-models.json`, `anthropic-models.json`, `gemini-models.json`, `ollama-tags.json`.
- `openrouter-402.json`, `openrouter-429.json`, `openrouter-503.json`.

### Parity checklist

Create `contracts/parity.md` with columns `Feature | Web | Android | Rust` using the initial feature list in spec section 5.4, including the new desktop packaging and auto-update rows. Mark Rust as `Optional` until the Rust client is enabled, but keep required-when-enabled semantics in the notes below the table.

### Validator

Create `scripts/validate-contracts.mjs` that:

1. Parses every JSON file under `contracts/`.
2. Validates `providers.json` and fixture models against their schemas.
3. Validates every stream fixture is non-empty and decodes as UTF-8.
4. Checks that every provider preset has a unique `id` and required fields.
5. Checks that `contracts/parity.md` has the expected columns and rows.
6. Exits non-zero with a file-and-field-specific error message.

If a dependency is needed, keep it isolated under `scripts/package.json`; do not add a root workspace.

## Steps

1. Write `providers.json`.
2. Write schemas.
3. Write fixtures and model/error data.
4. Write `parity.md`.
5. Write the validator.
6. Run `make contracts-check` (or `node scripts/validate-contracts.mjs`).
7. Fix all validation errors.

## Acceptance criteria

- `make contracts-check` exits 0.
- All presets from section 3.4 exist; OpenRouter is marked as default/recommended/reference.
- OpenRouter fixtures cover text, reasoning, usage, keep-alive, and in-stream error paths.
- `.sse` fixtures can be replayed as raw bytes without modification.
- No secrets, real prompts, or personal data appear in fixtures.
- Parity table includes Rust and the new desktop/auto-update rows.

## Verification

```bash
make contracts-check
node scripts/validate-contracts.mjs
git status --short
```

## Out of scope

- Client-side contract types and parsing (T010-T034).
- OpenRouter live API calls and CORS verification (platform tasks).
- CI wiring (T040).
