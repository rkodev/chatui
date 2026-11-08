# Rust CI matrix and tag release workflow

- Task: M3.5-11.4
- Milestone: M3.5
- Spec: [IMPLEMENTATION.md](../../IMPLEMENTATION.md) sections 10.11, 14.5, and 16.3
- Plan: [M3.5 plan](../M3.5-rust-desktop-implementation-plan.md) sections 3.7 and 6
- Last updated: 2026-11-08

This page documents the Rust CI matrix, the tag-driven release workflow, the
required CI secret names, and the artifact and update-manifest names. It does
not cover packaging internals (M3.5-11.2), updater verification (M3.5-11.3), or
performance and installer-size evidence (M3.5-11.5).

## Workflow files

| File | Purpose |
| --- | --- |
| `.github/workflows/rust.yml` | Pull-request, `main`/`dev`, tag, and scheduled CI gates. |
| `.github/workflows/rust-release.yml` | Tag-driven packaging, signing, checksum, manifest, and GitHub Release publication. |

The existing web, Android, and contracts workflows are unchanged.

## Triggers

`rust.yml` runs on:

- every pull request;
- pushes to `main` and `dev` (the repository currently uses `dev` as its
  default branch; the specification names `main`, so both are listed);
- tags matching `v*` (stable) and `beta-*` (beta);
- a weekly schedule (`0 6 * * 1`) for dependency-policy and advisory checks.

`rust-release.yml` runs only on tags matching `v*` and `beta-*`. It does not run
on pull requests or branch pushes.

## CI matrix

| Name | Runner | Rust target | Required |
| --- | --- | --- | --- |
| Linux x86_64 | `ubuntu-latest` | `x86_64-unknown-linux-gnu` | Yes |
| macOS Apple Silicon | `macos-14` | `aarch64-apple-darwin` | Yes |
| Windows x86_64 | `windows-latest` | `x86_64-pc-windows-msvc` | Yes |

macOS Intel and Linux aarch64 remain manual/future per
[DEC-02](M3.5-decisions.md#3-platform-and-architecture-matrix-dec-02).

## CI jobs and commands

### `rust` matrix job

Each matrix entry runs:

| Step | Command |
| --- | --- |
| Formatting | `cargo fmt --all --check` |
| Lint | `cargo clippy --workspace --all-targets -- -D warnings` |
| Tests | `cargo nextest run --workspace` |
| Conformance | `cargo nextest run -p chatui-conformance` |
| Release build | `cargo build --release --workspace --locked` |

The toolchain channel is read from `rust/rust-toolchain.toml` at run time and
installed with `dtolnay/rust-toolchain`. `rustfmt` and `clippy` are installed as
components. `cargo-nextest` is installed in CI only with
`taiki-e/install-action`; it is not a repository dependency.

`Swatinem/rust-cache` caches the Cargo registry, git checkouts, and
`rust/target` per OS. No secrets are cached.

### `supply-chain` job

Runs on Ubuntu and executes:

| Step | Command |
| --- | --- |
| Dependency policy | `cargo deny check` |
| Security advisories | `cargo audit` |

`cargo-deny` and `cargo-audit` are installed in CI only. The job runs on pull
requests, branch pushes, tags, and the weekly schedule.

## Release workflow

`rust-release.yml` has three jobs:

1. `verify` runs the same formatting, lint, test, conformance, deny, and audit
   gates on Ubuntu. The release cannot start if a gate fails.
2. `build` runs once per matrix entry. Each entry:
   - verifies that its required signing credentials are present;
   - installs the pinned toolchain and caches Cargo;
   - builds `cargo build --release --workspace --locked`;
   - signs the Windows binary with `signtool` when running on Windows;
   - imports the Apple certificate and configures the notarization profile when
     running on macOS;
   - runs `python rust/packaging/package.py --skip-build --target <target>`;
   - renames the per-target checksum file to `SHA256SUMS-<target>`;
   - signs a per-target update manifest with the channel key;
   - uploads the artifacts and manifest.
3. `publish` downloads all matrix artifacts, combines the per-target checksum
   files into `SHA256SUMS`, and publishes a GitHub Release with
   `softprops/action-gh-release`.

The release job fails closed. If a required signing or notarization secret is
absent, the `Verify required signing credentials` step exits non-zero before any
artifact is built. There is no unsigned production fallback. The repository's
current enablement decision is `defer` ([DEC-01](M3.5-decisions.md#2-enablement-decision-dec-01-dec-15)),
so a preview build is not a release and this workflow is expected to fail until
the credentials and runners exist.

## Required CI secrets

Secret values are never committed, printed, or written to artifacts. The names
below are the complete set used by `rust-release.yml`.

| Secret | Used by | Purpose |
| --- | --- | --- |
| `UPDATE_SIGNING_PRIVATE_KEY_STABLE` | `build` (all OSes, stable tags) | Ed25519 private key that signs stable update manifests. |
| `UPDATE_SIGNING_PRIVATE_KEY_BETA` | `build` (all OSes, beta tags) | Ed25519 private key that signs beta update manifests. |
| `APPLE_CERTIFICATE` | `build` (macOS) | Base64-encoded Apple Developer ID `.p12` certificate. |
| `APPLE_CERTIFICATE_PASSWORD` | `build` (macOS) | Password for the `.p12` certificate. |
| `APPLE_SIGNING_IDENTITY` | `build` (macOS) | `codesign` identity string for the app bundle. |
| `APPLE_NOTARY_PROFILE` | `build` (macOS) | Keychain profile name used by `notarytool`. |
| `APPLE_NOTARY_APPLE_ID` | `build` (macOS) | Apple ID used to create the notarization profile. |
| `APPLE_NOTARY_TEAM_ID` | `build` (macOS) | Apple Developer team ID. |
| `APPLE_NOTARY_PASSWORD` | `build` (macOS) | App-specific password for notarization. |
| `WINDOWS_CERTIFICATE` | `build` (Windows) | Base64-encoded Authenticode `.pfx` certificate. |
| `WINDOWS_CERTIFICATE_PASSWORD` | `build` (Windows) | Password for the `.pfx` certificate. |

`GITHUB_TOKEN` is provided automatically by GitHub Actions and is used by the
`publish` job with `contents: write`. It is not a repository secret.

The update-signing key pairs must be generated outside the repository. The
public keys are pinned in `rust/apps/desktop/src/updater.rs`; see
[update-signing.md](update-signing.md). Production key ownership is still
`unknown` (B-05).

## Artifact names

The release workflow publishes the host-target artifacts produced by
`rust/packaging/package.py`:

| Target | Artifacts |
| --- | --- |
| Linux x86_64 | `chatui_<version>_amd64.deb`, `ChatUI-<version>-linux-x86_64.tar.gz` |
| macOS Apple Silicon | `ChatUI-<version>-macos-aarch64.dmg`, `ChatUI-<version>-macos-aarch64.app.zip` |
| Windows x86_64 | `ChatUI-<version>-windows-x86_64.zip` |
| All | `SHA256SUMS`, `SHA256SUMS-<target>` |

The combined `SHA256SUMS` is generated in the `publish` job from the per-target
files. Each line uses `sha256sum` format:

```text
<hash>  <filename>
```

## Update manifests

Each matrix entry signs one manifest for its target:

```text
update-<channel>-<target>.json
```

Examples:

```text
update-stable-linux-x86_64.json
update-stable-macos-aarch64.json
update-stable-windows-x86_64.json
update-beta-linux-x86_64.json
```

The manifest is the envelope documented in
[update-signing.md](update-signing.md). The payload names the GitHub Release
asset URL, the artifact SHA-256 from the per-target checksum file, and the
artifact size. The signature is verified with the signing key's public key
before the file is uploaded.

The updater's default `ManifestUrls` point at a single channel URL
(`update-stable.json` / `update-beta.json`). Because a manifest describes one
target, the release publishes per-target manifests. A release owner must either
point the updater at the target-specific URL or add a hosting redirect that maps
the channel URL to the running target's manifest. This is a hosting decision,
not a workflow gap.

## Local verification

The workflow commands are the same as the Makefile targets:

```bash
make rust-fmt
make rust-check
make rust-lint
make rust-nextest
make rust-conformance
make rust-deny
make rust-audit
make rust-build
```

Results for this task are recorded in the task file's completion record.

## CI-only blocker

GitHub Actions cannot be executed in this environment:

- `act` is not installed, so the workflows cannot be dry-run locally.
- macOS Apple Silicon and Windows x86_64 runners are not available locally
  (B-01, B-02).
- Apple signing/notarization and Windows Authenticode credentials are absent
  (B-03, B-04).
- Update-signing production key ownership is `unknown` (B-05).

The workflow files are validated by YAML parsing and by running the same
commands locally on Linux. A real matrix run and a real tag release require the
external runners and credentials above.

## Known gate blockers

The workflow is configured to run every gate, but two pre-existing issues
currently fail the supply-chain job:

- `rust/deny.toml` uses the pre-0.16 advisories schema. cargo-deny 0.20.2
  rejects `unmaintained = "warn"` and `unsound = "warn"`. A follow-up task that
  owns `rust/deny.toml` must migrate it to the current schema.
- `cargo audit` reports `rsa 0.9.10` RUSTSEC-2023-0071 (Marvin Attack) with no
  fixed upgrade. A follow-up task that owns the dependency graph must replace or
  isolate the crate, or record an approved, narrowly scoped exception.

These are not workflow defects. The gates are present and fail closed.

## Related documents

- [Packaging and checksums](packaging.md)
- [Update manifests and signing](update-signing.md)
- [M3.5 decisions](M3.5-decisions.md)
- [M3.5 verification](M3.5-verification.md)
