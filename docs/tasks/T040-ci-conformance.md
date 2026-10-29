# T040 - CI/CD and contract conformance integration

- Milestone: M0-M4
- Depends on: T000, T001, T010-T034 (as available)
- Suggested subagent: general
- Parallel-safe: no, wait for platform scaffolds before wiring full matrices
- Read: `docs/IMPLEMENTATION.md` sections 5.3-5.4, 14, 15, 16

## Goal

Create the CI workflows and conformance gates that keep the three clients aligned with `contracts/`. Workflows must be useful from M0 onward and grow into the full release pipeline.

## Owns

- `.github/workflows/**`
- CI-related repository configuration
- Any CI helper scripts under `.github/` or `scripts/` that are not owned by T001
- Release-note templates if needed

Do not modify client source. If a workflow exposes a bug, report it to the owning task.

## Deliverables

1. Contracts workflow:
   - Validate JSON Schemas and fixtures with `scripts/validate-contracts.mjs`.
   - Fail on any OpenRouter fixture regression.
   - Fail if a schema change is not reflected in every enabled client or `contracts/parity.md`.
2. Web workflow:
   - pnpm install with frozen lockfile.
   - Contracts check, lint, typecheck, unit tests with coverage, build.
   - Playwright against the built app with mocked providers.
   - Upload build artifacts; on tags build/push the static container.
3. Android workflow:
   - JDK and Android SDK setup, Gradle cache.
   - `lintDebug`, `testDebugUnitTest`, `assembleDebug`.
   - Optional emulator instrumented tests on main.
   - On tags, build signed AAB/APK from CI secrets and attach artifacts.
4. Rust workflow:
   - Pinned toolchain, Cargo cache.
   - `cargo fmt --all --check`, `cargo clippy --workspace --all-targets -- -D warnings`, `cargo nextest run --workspace`, `cargo build --release --workspace`.
   - Cross-platform matrix: Linux x86_64, macOS Apple Silicon, Windows x86_64.
   - On tags, build signed desktop artifacts and update manifests with `cargo-dist` or equivalent.
   - `cargo-deny`/`cargo-audit` on schedule and dependency changes.
5. Shared conformance job that runs the same fixtures against every enabled client and publishes a clear pass/fail matrix.
6. Caching, artifact retention, concurrency groups, least-privilege permissions, and no secrets echoed in logs.
7. Versioning rules from section 16.5: app semver, contract integer, backup format integer, per-platform DB versions.

## Steps

1. Add contracts and web workflows.
2. Add Android and Rust workflows.
3. Add the fixture conformance matrix.
4. Add release/tag jobs with secret-backed signing.
5. Validate YAML locally and run a dry-run where possible.

## Acceptance criteria

- All workflows are valid and use least-privilege permissions.
- A contract-breaking change fails CI.
- A failed OpenRouter fixture blocks merge/release.
- Web, Android, and Rust gates run on PRs and main.
- Tag builds produce the documented artifacts without exposing secrets.
- The pipeline does not couple `web/`, `android/`, and `rust/` into one package manager workspace.

## Verification

```bash
make contracts-check
make web-lint && make web-test
make android-test
make rust-test
```

Plus a YAML syntax check for every workflow file and a review of secret usage.

## Out of scope

- Implementing client tests or build scripts (platform tasks).
- Choosing a release host or managed-hosting provider (T043).
