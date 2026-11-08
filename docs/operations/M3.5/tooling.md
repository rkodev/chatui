# M3.5 Rust developer tooling

This page documents the Rust gate exposed from the repository root. Rust is
optional and parallel to the web and Android builds; none of these targets are
part of the web or Android build paths.

## Commands

Run these from the repository root:

| Command | Purpose |
| --- | --- |
| `make rust-check` | Type-check the workspace with `cargo check --workspace --all-targets`. |
| `make rust-lint` | Run Clippy with warnings denied. |
| `make rust-test` | Run the workspace tests with `cargo test`. |
| `make rust-build` | Build the workspace in release mode. |
| `make rust-run` | Run the default desktop application. |
| `make rust-fmt` | Check formatting with `cargo fmt --all --check`. |
| `make rust-nextest` | Run the workspace tests with `cargo nextest run --workspace`. |
| `make rust-deny` | Check advisories, licenses, bans, and sources with `cargo deny check`. |
| `make rust-audit` | Check dependencies for known vulnerabilities with `cargo audit`. |
| `make rust-conformance` | Run the `chatui-conformance` test package with nextest. |
| `make rust-ui-test` | Run the `chatui-ui` test package with nextest. |
| `make rust-package` | Run the desktop packaging command. The command is selected in M3.5-11.2; until then the target fails with an actionable message. |

Every Rust target requires `rust/Cargo.toml` and fails with an actionable
message when the workspace is absent.

## Required tools

The workspace pins its toolchain in `rust/rust-toolchain.toml`:

- Rust `1.98.1` with the `rustfmt` and `clippy` components.
- `cargo-nextest` for `rust-nextest`, `rust-conformance`, and `rust-ui-test`.
- `cargo-deny` for `rust-deny`.
- `cargo-audit` for `rust-audit`.

`cargo`, `rustfmt`, and `clippy` are sufficient for `rust-check`, `rust-lint`,
`rust-test`, `rust-build`, `rust-run`, and `rust-fmt`.

## Local installation

Install the pinned toolchain with rustup:

```bash
rustup toolchain install 1.98.1 --profile minimal --component rustfmt --component clippy
```

Install the optional gate tools with Cargo:

```bash
cargo install --locked cargo-nextest cargo-deny cargo-audit
```

`cargo-binstall` can be used instead when it is already available:

```bash
cargo binstall --locked cargo-nextest cargo-deny cargo-audit
```

Do not commit installed binaries, Cargo's install directory, or tool caches.
Tool installation is a local developer action, not a repository artifact.

## CI installation

CI installation belongs to M3.5-11.4. That task should install the pinned
toolchain from `rust-toolchain.toml`, cache the Cargo registry and target
directories, and install `cargo-nextest`, `cargo-deny`, and `cargo-audit` in the
workflow rather than relying on a developer machine. This task does not add or
modify CI workflows.

## Dependency policy

`rust/deny.toml` defines the policy:

- **Advisories:** deny known vulnerabilities and yanked crates; warn on
  unmaintained, unsound, and notice-level advisories. The RustSec advisory
  database is the source.
- **Licenses:** allow a conservative set of permissive licenses commonly used
  by the selected crates. Private workspace crates are ignored because they are
  `publish = false` and do not carry a license field yet. Add a narrowly scoped
  exception with a comment rather than disabling the check.
- **Bans:** deny wildcard dependencies; warn on multiple versions. No crates are
  banned or skipped yet.
- **Sources:** deny unknown registries and unknown git sources. Only
  crates.io is allowed. A new registry or git dependency requires an explicit
  policy change and review.

Do not disable a check to make the gate pass.

## Release profile

`rust/Cargo.toml` contains a release-profile skeleton:

```toml
[profile.release]
lto = "thin"
codegen-units = 1
strip = "symbols"
panic = "abort"
```

This follows the milestone requirement for thin or fat LTO, one codegen unit,
stripped symbols, and abort-on-panic where compatible with crash reporting.
Final tuning, the retained debug-symbol artifact, packaging, and performance
evidence are owned by M3.5-11.1 and later release tasks.

## Current environment status

At the time this task was implemented, `cargo`, `rustfmt`, and `clippy` were
available, but `cargo-nextest`, `cargo-deny`, and `cargo-audit` were not
installed. The corresponding targets therefore fail with Cargo's
`no such command` error until those tools are installed locally or in CI. This
is recorded as a tooling blocker in the task handoff notes; it is not a pass.
