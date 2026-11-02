# ChatUI Feature Parity

- Source: [IMPLEMENTATION.md section 5.4](../docs/IMPLEMENTATION.md#54-parity-checklist)
- Updated: 2026-10-31
- Purpose: authoritative cross-client requirement and commitment table.

| Feature | Web | Android | Rust |
| --- | --- | --- | --- |
| BYOK onboarding | Required | Required | Required when enabled |
| OpenRouter first-run onboarding | Required (release blocker) | Required (release blocker) | Required when enabled |
| OpenRouter key introspection (`/key`) | Required | Required | Required when enabled |
| OpenRouter catalog, pricing, modalities | Required | Required | Required when enabled |
| OpenRouter streaming, reasoning, usage/cost | Required | Required | Required when enabled |
| OpenRouter provider routing preferences | Required | Required | Required when enabled |
| OpenAI-compatible streaming | Required | Required | Required when enabled |
| Anthropic streaming | Required | Required | Required when enabled |
| Gemini streaming | Required | Required | Required when enabled |
| Ollama local | Required | Required | Required when enabled |
| Model discovery and switching | Required | Required | Required when enabled |
| Conversation persistence | IndexedDB + OPFS | Room + files | SQLite + files |
| Search | Required | Required | Required when enabled |
| Markdown + code + math | Required | Required | Required when enabled |
| Attachments | Required | Required | Required when enabled |
| Export/import | Required | Required | Required when enabled |
| Backup/sync | Future | Future | Future |
| PWA install | Required | N/A | N/A |
| Native share target | N/A | Required | Required on desktop and mobile |
| Desktop packaging (installer/AppImage) | N/A | N/A | Required when enabled |
| Auto-update | N/A | N/A | Required when enabled |

## Notes

- OpenRouter is the first release blocker. These rows are release-blocking on every published client: OpenRouter first-run onboarding, key introspection, catalog/pricing/modalities, streaming/reasoning/usage/cost, and provider routing preferences.
- The Rust column may be `Optional` until the Rust client is enabled for release. Once enabled and published, Rust must meet the same contracts, conformance, parity, and security bar as web and Android.
- No feature is complete at M0. Foundation-level persistence, routing placeholders, or design tokens do not satisfy a feature row.
- This table records requirement and platform commitment, not current implementation status. Update it in the same change as any user-visible behavior change.
- Allowed platform values are `Required`, `Required (release blocker)`, `Required when enabled`, `Required on desktop and mobile`, `Future`, `N/A`, and the platform-specific persistence descriptions shown above.
- Release is blocked if a feature is marked complete on one platform and absent on another without an approved exception recorded here.
