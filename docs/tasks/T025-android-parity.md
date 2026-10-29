# T025 - Android additional providers, UI tests, parity

- Milestone: M2/M4
- Depends on: T021, T022, T023, T024
- Suggested subagent: general
- Parallel-safe: yes, with T015 and T034
- Read: `docs/IMPLEMENTATION.md` sections 3.4, 7.5-7.9, 9.6, 14.1, 15.1-15.2, 15.3

## Goal

Add the remaining launch providers to Android and finish the test/parity layer: fixture conformance, Compose UI tests, Roborazzi screenshots, and manual device coverage. OpenRouter must not regress.

## Owns

- `android/core/network/**` additions for Anthropic, Gemini, Ollama, and OpenAI-compatible presets
- `android/feature/providers/**` and `feature/models/**` additions for extra presets
- `android/core/network/src/test/**` provider fixture tests
- `android/app/src/androidTest/**`, Compose UI tests, Roborazzi configuration
- `contracts/parity.md` updates for Android

## Deliverables

1. OpenAI-compatible presets: OpenAI, Groq, Mistral, DeepSeek, xAI, Together, Fireworks, LM Studio, llama.cpp, Custom, via the shared transport.
2. Anthropic adapter: `x-api-key`, `anthropic-version`, browser access header harmless, system field, image base64, event mapping.
3. Gemini adapter: `alt=sse`, `x-goog-api-key`, `usageMetadata`, finish mapping, `systemInstruction`, `inlineData`.
4. Ollama: native `/api/chat` NDJSON first, `/api/tags` discovery, OpenAI-compatible fallback.
5. Model discovery fallbacks and capability inference for presets that do not expose rich metadata.
6. Fixture conformance tests for all shared streams and errors.
7. Compose UI tests for onboarding, chat, model picker, provider management, settings, and error states.
8. Roborazzi screenshot tests for key screens in light/dark and phone/tablet.
9. Manual device matrix already recorded by T024; add any provider-specific checks.
10. Update `contracts/parity.md` Android column honestly.

## Steps

1. Generalize the transport and register extra presets.
2. Add Anthropic, Gemini, Ollama adapters with fixture tests.
3. Add Compose UI and Roborazzi suites.
4. Run the Android gate and record results.
5. Update parity.

## Acceptance criteria

- All launch providers work on Android (local providers where reachable).
- Anthropic `thinking`/`text`, Gemini `MAX_TOKENS`, and Ollama NDJSON map correctly.
- Shared fixtures pass with no OpenRouter regression.
- Compose UI tests cover onboarding, chat, model picker, and settings.
- Roborazzi screenshots exist for light/dark and phone/tablet.
- Parity is updated and reviewed.

## Verification

```bash
make contracts-check
cd android && ./gradlew :core:network:testDebugUnitTest :app:testDebugUnitTest :app:connectedDebugAndroidTest :app:verifyRoborazziDebug
```

## Out of scope

- Web and Rust adapters (T015, T034).
- Backup/sync and managed hosting (T042, T043).
