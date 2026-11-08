# Release profile, symbols, and binary-size baseline

- Task: M3.5-11.1
- Milestone: M3.5
- Spec: [IMPLEMENTATION.md](../../IMPLEMENTATION.md) sections 10.9 and 14.5
- Plan: [M3.5 plan](../M3.5-rust-desktop-implementation-plan.md) sections 3.7 and 4.13
- Last updated: 2026-11-08

This document records the finalized Cargo release profile, the retained
debug-symbol artifact, the panic strategy, and the first desktop binary-size
and build-time baseline. Installer size and cold-start measurements are out of
scope here and belong to M3.5-11.2 and M3.5-11.5.

## Profile settings

`rust/Cargo.toml` defines two profiles:

```toml
[profile.release]
lto = "thin"
codegen-units = 1
strip = "symbols"
panic = "abort"

[profile.release-debug]
inherits = "release"
strip = "none"
debug = "line-tables-only"
```

| Setting | Value | Reason |
| --- | --- | --- |
| `lto` | `"thin"` | Cross-crate inlining and dead-code removal with much lower build cost than `"fat"`. The spec allows thin or fat; thin keeps local and CI builds practical. |
| `codegen-units` | `1` | Maximum optimization and smallest code. Costs build parallelism; acceptable for a single desktop binary. |
| `strip` | `"symbols"` | Removes symbol tables from the shipped binary. Debug info is retained separately (see below). |
| `panic` | `"abort"` | Removes unwinding tables and landing pads, shrinking the binary and simplifying codegen. Compatible with this application; see the compatibility note. |
| `debug` (release-debug) | `"line-tables-only"` | Enough DWARF to resolve function names and source lines in backtraces without the full variable-level debug payload. |
| `strip` (release-debug) | `"none"` | Keeps the symbol table and debug info for symbolication. |

### Why thin LTO instead of fat

`lto = "fat"` can produce a slightly smaller and faster binary, but it
serializes the whole-program optimization pass and roughly doubles clean build
time. The spec permits either. Thin LTO was chosen because the size difference
is small relative to the 40 MB installer budget and the build-time cost of fat
LTO is paid on every release and CI run. If a later measurement shows the
installer budget is at risk, switching to `"fat"` is a one-line change and does
not affect any other setting.

### Panic strategy compatibility

`panic = "abort"` is used. Evidence that it is compatible:

- The application installs no unwinding-dependent crash reporter. The only
  panic hook is `chatui_secrets::panic::install_panic_hook*`, which formats a
  redacted message and writes it to stderr; hooks still run before an abort.
- No crate uses `std::panic::catch_unwind` to recover from panics.
- No test uses `#[should_panic]`, so the release profile's abort behavior does
  not invalidate the test suite. Tests run under the dev/test profile, which
  keeps unwinding.
- The Slint shell does not require unwinding for normal operation; the release
  binary starts and runs its headless smoke path (see verification below).

If a future crash reporter needs to unwind, switch `panic` back to `"unwind"`
and record the size cost here.

## Debug-symbol artifact

The shipped release binary is stripped and cannot be symbolicated on its own.
A separate artifact is built from the `release-debug` profile, which inherits
the release settings but keeps debug info and symbols:

```bash
cd rust
cargo build --profile release-debug -p chatui-desktop
```

- Artifact path: `rust/target/release-debug/chatui-desktop`
- It is **not** shipped and is **not** committed. `rust/target/` is ignored by
  `.gitignore`.
- Keep the artifact (or its build ID) alongside the matching stripped release
  binary so crash reports can be resolved with `addr2line`, `gdb`, or the
  platform symbolication tool.

The two binaries have different build IDs because debug info changes the link
output. Record the release binary's build ID with the release so the correct
debug artifact can be matched.

## Baseline measurements

Measured on 2026-11-08 on the development machine:

| Item | Value |
| --- | --- |
| OS | Linux 7.0.0-42-generic x86_64 (Ubuntu, glibc 2.43) |
| CPU | Intel Core i9-10980HK @ 2.40 GHz, 16 logical cores |
| Memory | 30 GiB |
| Toolchain | rustc 1.98.1 (48a229cea 2026-09-01), cargo 1.98.1 |
| Target | `x86_64-unknown-linux-gnu` |
| Release binary | `rust/target/release/chatui-desktop` |
| Release binary size | 19,415,648 bytes (18.5 MiB / 19 MB), stripped |
| Release build time | 5m 47s (347.5 s) clean-ish build, 3.0 GiB peak RSS |
| Debug-symbol artifact | `rust/target/release-debug/chatui-desktop` |
| Debug artifact size | 81,416,368 bytes (77.6 MiB / 78 MB), with debug info, not stripped |
| Debug artifact build time | 5m 34s (334.7 s), 3.5 GiB peak RSS |

Notes:

- The release build reused some cached dependency artifacts from earlier work,
  so a fully cold build will be slower. The recorded time is the observed wall
  time for the verification command on this machine.
- The 19 MB executable is well under the 40 MB installer budget, but installer
  compression, bundled resources, and platform packaging are measured in
  M3.5-11.2/11.5. Do not treat the executable size as the installer size.
- Windows and macOS sizes must be recorded on their own runners when available;
  this baseline is Linux x86_64 only.

## Verification

```bash
cd rust && cargo build --release -p chatui-desktop
ls -lh rust/target/release/chatui-desktop
cd rust && cargo build --profile release-debug -p chatui-desktop
ls -lh rust/target/release-debug/chatui-desktop
```

Observed results:

| Command | Result |
| --- | --- |
| `cargo build --release -p chatui-desktop` | Pass; finished in 5m 47s. |
| `ls -lh rust/target/release/chatui-desktop` | 19 MB; `file` reports `stripped`. |
| `cargo build --profile release-debug -p chatui-desktop` | Pass; finished in 5m 34s. |
| `ls -lh rust/target/release-debug/chatui-desktop` | 78 MB; `file` reports `with debug_info, not stripped`. |
| `nm rust/target/release-debug/chatui-desktop \| wc -l` | 19,274 symbols. |
| `nm rust/target/release/chatui-desktop` | `no symbols` (expected). |
| `cargo test -p chatui-desktop` | Pass; 49 tests. |
| `./rust/target/release/chatui-desktop --print-menu` | Pass; exit 0. |

## Out of scope

- Installer packaging, compression, and checksums (M3.5-11.2).
- Cold-start measurement (M3.5-11.5).
- Updater signing (M3.5-11.3).
- Windows and macOS binary sizes (record on their runners).
