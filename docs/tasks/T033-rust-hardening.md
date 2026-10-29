# T033 - Rust packaging, updater, security, accessibility, performance

- Milestone: M3.5
- Depends on: T032
- Suggested subagent: general
- Parallel-safe: yes, with T014 and T024
- Read: `docs/IMPLEMENTATION.md` sections 3.8, 10.1-10.12, 12.6, 14.5, 15.1, 18

## Goal

Make the Rust client release-ready: desktop installers, signed update manifests, Android packaging when enabled, security hardening, accessibility verification, and performance/binary-size budgets. No new product features.

## Owns

- `rust/Cargo.toml` release profile and packaging metadata only
- `rust/dist/**` or `cargo-dist` configuration
- `rust/apps/desktop/**` updater and platform packaging wiring
- `rust/apps/android/**` packaging config
- `rust/release/**` signing/update documentation
- Platform security configs under `rust/apps/*`
- Accessibility/performance test helpers under `rust/tests/**`

## Deliverables

1. Release profile: `lto`, `codegen-units`, `strip`, and `panic` settings as in section 10.9. Keep a debug-symbol artifact for symbolication.
2. Desktop packaging: Windows MSI or portable zip, macOS signed/notarized DMG or app bundle, Linux AppImage and/or `.deb`/`.rpm`. Use `cargo-dist` or an equivalent reproducible pipeline.
3. Auto-update: signed manifests over HTTPS, signature verification before apply, pinned update public keys, stable/beta channels, no update during an active stream without asking.
4. Android packaging when in scope: signed APK/AAB with the project signing policy; no WebView-only build.
5. Security hardening:
   - Cleartext only for localhost/private-range hosts with a warning; HTTPS default.
   - Secret redaction in logs and panic hooks.
   - `cargo-deny` and `cargo-audit` clean of new advisories.
   - Unsafe code isolated in platform bindings with documented invariants.
6. Accessibility: keyboard navigation, focus rings, high-DPI/scaling, desktop screen-reader smoke tests, TalkBack on Android.
7. Performance: cold start under 1.5 s on a mid-tier desktop and under 2 s on a mid-tier Android device; installer under 40 MB, Android APK under 30 MB if feasible. Record actuals.
8. Verify the release gate in section 10.12: contracts, OpenRouter conformance, persistence, secrets, parity, signing.

## Steps

1. Configure release profile and packaging.
2. Add signing/update pipeline and documentation.
3. Run security, accessibility, and performance checks.
4. Produce signed artifacts and record budgets.
5. Confirm the release gate.

## Acceptance criteria

- Signed desktop artifacts are produced for all tier-1 OSes.
- Update manifests are signed and verified before apply.
- Android artifact uses the project signing policy when built.
- No new `cargo-deny`/`cargo-audit` advisories.
- Accessibility and performance budgets are met or documented.
- The Rust release gate in section 10.12 is satisfied.
- Parity is updated where behavior changed.

## Verification

```bash
cd rust && cargo build --release --workspace
cd rust && cargo deny check && cargo audit
```

Plus the platform packaging commands chosen by the implementation, recorded in the task report.

## Out of scope

- New features or providers.
- Additional provider tests and final parity (T034).
- Web deployment (T014) and Android Kotlin release (T024).
