# T034 - Rust additional providers, tests, parity

- Milestone: M3.5
- Depends on: T031, T032, T033
- Suggested subagent: general
- Parallel-safe: yes, with T015 and T025
- Read: `docs/IMPLEMENTATION.md` sections 3.4, 7.5-7.9, 10.2, 10.4, 10.11-10.12, 15.1-15.2, 15.3

## Goal

Add the remaining launch providers to the Rust client and finish fixture conformance, cross-platform tests, and parity. OpenRouter must not regress.

## Owns

- `rust/crates/chatui-providers/src/**` additions for Anthropic, Gemini, Ollama, and OpenAI-compatible presets
- `rust/crates/chatui-ui/src/features/providers/**` and `features/models/**` registration for additional presets (coordinate with T031)
- `rust/crates/chatui-providers/tests/**` additional provider tests
- `rust/tests/conformance/**`
- `contracts/parity.md` updates for Rust

## Deliverables

1. OpenAI-compatible presets: OpenAI, Groq, Mistral, DeepSeek, xAI, Together, Fireworks, LM Studio, llama.cpp, Custom.
2. Anthropic adapter: `x-api-key`, `anthropic-version`, browser access header harmless, system field, base64 images, event mapping.
3. Gemini adapter: `alt=sse`, `x-goog-api-key`, `usageMetadata`, finish mapping, `systemInstruction`, `inlineData`.
4. Ollama: native `/api/chat` NDJSON first, `/api/tags` discovery, OpenAI-compatible fallback.
5. Model discovery fallbacks and capability inference for presets without rich metadata.
6. Fixture conformance tests for every shared stream, model catalog, and error fixture, with expected normalized events.
7. Cross-platform CI matrix tests: Linux x86_64, macOS Apple Silicon, Windows x86_64; Android build/smoke on Linux.
8. Update `contracts/parity.md` Rust column honestly. The Rust column stays `Optional` until the client is enabled for release, then required rows must be complete.

## Steps

1. Generalize the transport and register extra presets.
2. Add Anthropic, Gemini, and Ollama adapters with tests.
3. Run the full Rust gate on the CI matrix.
4. Update parity and record results.

## Acceptance criteria

- All launch providers work on Rust when enabled.
- Shared fixtures produce the same normalized events as web and Android.
- OpenRouter fixtures pass with no regression.
- The Rust release gate in section 10.12 still passes.
- Parity is updated and reviewed.

## Verification

```bash
make contracts-check
cd rust && cargo fmt --all --check && cargo clippy --workspace --all-targets -- -D warnings && cargo nextest run --workspace
```

## Out of scope

- Web and Android adapter work (T015, T025).
- Backup/sync and managed hosting (T042, T043).
