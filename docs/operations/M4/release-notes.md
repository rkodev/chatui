# ChatUI 0.1.0 release notes

- Release: `0.1.0` (phase 1 preview)
- Tag: `v0.1.0`
- Date: 2026-11-10
- Clients: Web (required), Android (required)
- Rust desktop: **post-preview / parallel**; not part of this release

## What changed

This is the first phase 1 preview release. There is no earlier public release to
upgrade from.

- OpenRouter-first chat with onboarding, `/key` validation, the public `/models`
  catalog, model metadata, pricing, context, modalities, and capabilities.
- Streaming responses with keep-alive handling, reasoning, usage/cost, and
  cancellation.
- Provider support for OpenRouter, OpenAI-compatible endpoints, Anthropic,
  Gemini, and Ollama, with shared contracts and fixtures.
- Model switching, routing preferences, fallback models, and provider error
  handling.
- Local conversation persistence, search, Markdown, code highlighting, math
  rendering, attachments, export/import, and the Android native share target.
- Web PWA install, offline shell, and a safe update flow.
- Accessibility and UX polish across the enabled clients.
- Performance work: code splitting, message virtualization, stable list keys,
  image sizing, and the Android baseline profile.
- Static web deployment artifacts: a minimal Caddy container, SPA fallback,
  cache and security headers, `/healthz`, and recipes for Docker Compose,
  Cloudflare Pages, Netlify, Vercel, GitHub Pages, and roksky.
- Release engineering: least-privilege CI workflows, signed Android artifacts,
  tag-based web container publishing, contract conformance, and versioning.

## Upgrade and migration notes

- **Fresh install:** this is the first preview. No migration from an earlier
  release is required.
- **Web IndexedDB:** schema version `1`. Existing preview data is not migrated
  because there is no earlier release.
- **Android Room:** schema version `1`. The release `versionCode` is derived
  from the full-history git commit count (or an explicit CI input) and is
  monotonic. `versionName` is `0.1.0`.
- **Export/import format:** version `1` on web and Android. Imports from a
  different format version fail closed with a visible error.
- **Contracts:** contract version `1` in every `contracts/schema/*.json`.
- **Backup format:** version `1`.
- **Rust desktop:** not shipping. The Rust SQLite migration head is
  `0001_initial.sql` for the parallel track; no Rust upgrade or update-manifest
  action is required for this release.

The version integers are independent. A future change to one does not imply a
change to another.

## Known issues

- **Android math rendering:** complex math falls back to raw source in some
  cases. This is the documented fallback, not a new regression.
- **GitHub Pages:** GitHub Pages cannot set response headers, so it does not
  meet the CSP/cache contract. Use the container, Cloudflare Pages, Netlify,
  Vercel, or roksky when the headers are required.
- **Rust desktop:** post-preview / parallel. Desktop packaging, auto-update, and
  signed update manifests are not part of this release.
- **Store distribution:** the Play Store listing, data-safety forms, and store
  publishing are out of scope for this release. The signed AAB is produced for
  the internal testing track.
- **Local model servers:** Ollama and other localhost providers require the
  user's own local server; the app does not bundle a model runtime.
- **Web 10,000-message scroll:** the windowing path has a manual code check, not
  an automated 10,000-message web test. Android has an automated equivalent.
- **Retry delay display:** a `429` with `Retry-After` schedules the retry and
  shows the delay as static copy (`Retry after Ns`); there is no ticking
  countdown.
- **Signed Android artifacts:** the signed APK/AAB are produced by the tag
  pipeline and require the four signing secrets. A local environment without
  those secrets can only produce unsigned release builds.

## Release blockers

No release blockers are recorded for `0.1.0`. The `M4-06.1` parity and release
gate passed on 2026-11-10 with no approved exceptions; the full audit is in
[`M4-verification.md`](M4-verification.md). The signed Android artifacts and the
GHCR image are produced by the tag pipeline documented in
[`release.md`](release.md); no artifact is claimed as produced by the audit
environment.

## Artifacts

| Artifact | Name | Where to find it |
| --- | --- | --- |
| Web build | `web-dist` | GitHub Actions artifact from `web.yml` |
| Web coverage | `web-coverage` | GitHub Actions artifact from `web.yml` |
| Web container | `ghcr.io/<owner>/<repo>:0.1.0`, `:0.1`, `:latest` | GitHub Container Registry |
| Android debug APK | `android-debug-apk` | GitHub Actions artifact from `android.yml` |
| Android signed APK | `android-release-apk` | GitHub Actions artifact and GitHub Release |
| Android signed AAB | `android-release-aab` | GitHub Actions artifact and GitHub Release |
| Android R8 mapping | `android-release-mapping` | GitHub Actions artifact and GitHub Release |
| Android checksums | `android-release-checksums` | GitHub Actions artifact and GitHub Release |
| Rust desktop artifacts | Post-preview / parallel | Not produced for this release |

The Android checksum file contains SHA-256 digests for the signed APK, the
signed AAB, and the R8 mapping. Verify the downloaded files against
`checksums.txt` before distributing them. The web container digest is shown in
the `web.yml` build step summary and on the GHCR package page.

## Verification

The release gates are documented in [`release.md`](release.md). The CI evidence
or the recorded environment blocker is in
[`evidence/ci/README.md`](evidence/ci/README.md).
