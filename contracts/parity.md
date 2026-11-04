# ChatUI Feature Parity

- Source: [IMPLEMENTATION.md section 5.4](../docs/IMPLEMENTATION.md#54-parity-checklist)
- Updated: 2026-11-04
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

## Implementation status (M1, 2026-11-04)

M1 delivers OpenRouter-only BYOK chat on web and Android. This section records
what is implemented; the commitment table above is unchanged.

Implemented on both clients:

- OpenRouter first-run onboarding with public catalog preload, `/key`
  validation, invalid-key rollback, and default model selection.
- OpenRouter key introspection (`/key`) and provider metadata: label, usage,
  limit, free tier, rate limit, and CORS status.
- Public `/models` catalog with paid, free, vision, reasoning, tool, and
  long-context mapping; per-million pricing; 24-hour cache; manual refresh; and
  last-known-good snapshot.
- Streaming `/chat/completions` with keep-alive tolerance, reasoning
  normalization and deduplication, usage/cost persistence, in-stream error
  handling, retries with `Retry-After`, and cancellation.
- Curated routing (`allow_fallbacks`, `sort`, `data_collection`) and protected
  request-field rejection.
- Conversation persistence, last-active restore, startup recovery for
  interrupted streams, and model switching with provider/model snapshots.
- GFM Markdown, fenced code with language label and copy, raw-HTML inertness,
  blocked remote images, and copy/regenerate/edit-last-user/retry actions.
- Browser-direct CORS verified for `/key`, `/models`, and `/chat/completions`;
  OpenRouter `browserCors` is `supported`.

Not delivered in M1 (M2 or later):

- Additional providers, custom-provider creation, attachments, math rendering,
  search, pin/archive, export/import, backup/sync, PWA, Rust client, and release
  packaging.
- The remote-image loading setting and per-conversation system-prompt/sampling
  parameter UI remain M2.
