# M4 - Polish and release

- Status: Planned
- Target: 1-2 weeks
- Depends on: M1, M2, M3; M3.5 if the Rust client ships
- Blocks: M5, M6
- Platforms: all enabled clients
- Spec: sections 3.7-3.8, 5.4, 8.6-8.9, 10.9-10.12, 11.6-11.12, 12, 14, 15, 16, 17 (M4), 18, 21.7

## Objective

Harden, optimize, deploy, and audit the phase 1 release. M4 is primarily verification, polish, packaging, and evidence, not new product features. It turns the working clients into installable/distributable artifacts with demonstrated parity, accessibility, security, and performance, and it surfaces blockers instead of hiding them.

## Entry criteria

- M1 and M2 exit gates are met on web and Android.
- M3 exit gate is met for Android release configuration and device evidence.
- If Rust is shipping, its M3.5 exit gate is met and the section 10.12 release gate passes.
- `contracts/parity.md` is current and every critical case has an owner.

## Scope by workstream

### 1. Accessibility and UX polish

Deliver across enabled clients:

- Web: WCAG 2.2 AA keyboard navigation, visible focus, 4.5:1 body-text contrast, accessible names for icon buttons, semantic live regions for stream start/complete/error only, dialog/sheet focus management, reduced-motion respect, scalable text, and table/code overflow. Run axe on main routes; no serious or critical issues.
- Android: Material 3 semantics, content descriptions, 48 dp targets, TalkBack pass, predictive back ordering, reduced motion, and optional dynamic color.
- Rust: keyboard-first navigation, focus rings, high-DPI/scaling, and screen-reader bridge smoke tests on tier-1 desktop OSes plus TalkBack on Android.
- Responsive breakpoints from section 11.9: mobile drawer, compact desktop, persistent sidebar, optional params panel. Message column max 48 rem.
- Empty, loading, error, and offline states use the copy and behavior in section 11.8. No raw JSON as the primary error.
- Keyboard shortcuts from section 11.10 on web and Rust desktop.

No new feature behavior should be introduced to satisfy polish except small, test-covered defects.

### 2. Web PWA and offline behavior

Deliver:

- Service worker that caches the app shell and hashed static assets only.
- Never cache API requests/responses, secrets, decrypted content, or user data in the Cache API.
- Offline: conversation history remains readable from IndexedDB; sending is disabled with an offline banner.
- Update prompt when a new service worker is waiting; never force reload during an active stream.
- Do not register a service worker in development.
- Manifest and icons: installable metadata, theme colors, maskable icons, and screenshots where useful.
- Runtime `config.json` remains public-only, no-cache, and validated at startup.
- Capacitor remains an optional internal shell, never the primary Android product, and must not persist keys unless the WebView storage is equivalently protected.

### 3. Performance

Deliver and document:

- Web: code-split settings, provider management, and Shiki languages; lazy-load Markdown below the fold where practical; confirm message virtualization; memoize message components; keep the streaming message isolated; reserve image aspect ratios. Budget: initial JS under 250 KB gzip, Lighthouse mobile performance and accessibility at least 90, and a 10,000-message conversation scrolling at 60 fps on a mid-tier device.
- Android: cold-start budget, stable `LazyColumn` keys/content type, no history recomposition during streaming, image decode sizing, Coil thumbnail cache, baseline profile, debug APK under 20 MB and release APK under 15 MB if feasible.
- Rust: cold start under 1.5 s on mid-tier desktop and under 2 s on mid-tier Android, installer under 40 MB, Android APK under 30 MB if feasible.
- Record actual measurements, hardware, and methodology. Document deviations with a follow-up plan.

### 4. Deployment and release pipeline

Web deployment:

- Minimal static container using Caddy or nginx with SPA fallback.
- `index.html` and `config.json` served no-cache; hashed assets immutable.
- `/healthz` static 200 where the server supports it.
- Security headers: CSP from section 8.6/14.1, `X-Content-Type-Options`, `Referrer-Policy`, minimal `Permissions-Policy`, `Cross-Origin-Opener-Policy`.
- No environment variables required for normal operation; `config.json` contains only public configuration.
- Publish to the container registry with semver and latest tags.
- Deployment recipes and documentation: Docker Compose, Cloudflare Pages, Netlify, Vercel, GitHub Pages, and roksky hosting entry point.

CI/CD:

- Web workflow: install with frozen lockfile, contracts check, lint, typecheck, tests with coverage, build, Playwright against the built app with mocked providers, artifact upload, and tag-based container publishing.
- Android workflow: JDK/SDK setup, Gradle cache, lint, unit tests, debug assemble, optional emulator instrumented tests, and tag-based signed AAB/APK using CI secrets.
- Rust workflow when shipping: pinned toolchain, Cargo cache, fmt, clippy, tests, release build, cross-platform matrix, tag-based signed desktop artifacts and update manifests, and scheduled `cargo-deny`/`cargo-audit`.
- Contracts workflow: schema validation, fixture conformance across enabled clients, and failure on any OpenRouter fixture regression or schema/parity mismatch.
- Least-privilege permissions, concurrency groups, caching, artifact retention, and no secrets echoed in logs.
- Versioning rules from section 16.5: app semver, contract integer, backup format integer, and per-platform DB versions. Release notes include upgrade and migration notes.

### 5. Parity audit and release gate

Deliver:

- Walk every row in `contracts/parity.md`, verify with a test, manual check, or linked artifact, and mark Web/Android complete. Mark Rust optional/complete/blocked honestly.
- Record approved exceptions with an owner and date. Release is blocked if a feature is complete on one required client and absent on another without an approved exception.
- Evidence for every critical test case 1-28 from section 15.2 on every enabled client.
- OpenRouter conformance across `/key`, `/models`, streaming, reasoning, usage/cost, routing, errors, and browser CORS on web.
- Security and privacy verification:
  - No keys in logs, URLs, Cache API, exports, or backup metadata.
  - Web CSP and security headers present.
  - Android/Rust secret storage and backup exclusion verified.
  - No analytics, telemetry, hidden network destinations, or server-side prompt proxying.
  - Export warnings for plaintext data are present.
- Deployment verification: static build and container run with SPA fallback, correct headers, and public-only config. Document Android signing policy and Rust update-manifest signing when applicable.
- Definition-of-done review against section 21.7.
- Release notes, known issues, and a blocker list with owners. Do not declare success while a blocker is unresolved.

### 6. Rust release dependencies

If the Rust client ships in phase 1, M4 additionally verifies its section 10.12 release gate, signed desktop artifacts, signed update manifests, Android artifact signing, and parity. If Rust is not shipping, mark it explicitly as post-preview/parallel in release notes and parity.

## Out of scope

- New features or provider protocols.
- Large feature fixes discovered during audit; create follow-up tasks instead of expanding M4.
- Backup/sync (M5) and managed hosting (M6).
- Play Store listing, privacy-policy copy, and store data-safety forms beyond what the app needs.
- Localization and translations.

## Exit gate

- Lighthouse mobile performance and accessibility are at least 90, or deviations are documented with a plan and owner.
- axe reports no serious/critical issues on main routes.
- Playwright, Compose UI, and Rust UI suites pass as applicable.
- The PWA installs, works offline for history, and prompts for updates without interrupting streams.
- The static container serves the SPA with correct cache and security headers; `config.json` is public-only.
- Release artifacts exist and are documented: web container and static bundle, signed Android APK/AAB, and signed Rust desktop artifacts/update manifests if shipping.
- `contracts/parity.md` is complete and reviewed; every critical case has evidence; approved exceptions have owners and dates.
- The section 21.7 definition of done is met or explicitly exempted.
- Release notes, migration notes, known issues, and blocker list exist.
- No open release blockers.

## Verification

```bash
make contracts-check
make web-lint && make web-typecheck && make web-test && make web-build
cd web && pnpm exec playwright test
make android-lint && make android-test && make android-assemble
cd android && ./gradlew :app:lintRelease :app:testDebugUnitTest :app:assembleRelease :app:bundleRelease
make rust-check && make rust-lint && make rust-test && make rust-build
cd rust && cargo deny check && cargo audit
```

Plus Lighthouse/axe reports, container smoke tests, artifact checksums, and the parity audit record.

## Task-generation guidance

Expect 4-6 tasks. Suggested slices:

1. Accessibility and UX polish across enabled clients, with axe/TalkBack/screen-reader evidence.
2. Web PWA, service worker, manifest, offline, and update flow.
3. Performance optimization and budget measurement across enabled clients.
4. Web deployment artifacts, container, security headers, and deployment recipes.
5. CI/CD release pipelines, signing, artifacts, versioning, and release notes.
6. Parity audit and phase 1 release gate, including security/privacy and definition-of-done verification.

Task 6 is verification-heavy and must not quietly implement features to make checks pass. Any substantive fix becomes a separate follow-up task.

## Risks and decisions to confirm

- Release audits often discover scope gaps. Surface them with owners; do not silently mark incomplete rows complete.
- Service workers can cache content accidentally. Verify the Cache API contains no API responses, secrets, or decrypted data.
- Lighthouse and cold-start numbers vary by hardware. Record methodology and environment.
- Signing secrets are a supply-chain boundary. Keep them in CI secret stores and never log or commit them.
- Rust may slip. It must not block the web/Android preview unless it is explicitly included in the release.
- Parity drift can hide regressions. Require evidence per row, not self-reported completion.
