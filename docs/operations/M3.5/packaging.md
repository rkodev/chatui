# Desktop packaging and checksums

- Task: M3.5-11.2
- Milestone: M3.5
- Spec: [IMPLEMENTATION.md](../../IMPLEMENTATION.md) sections 10.10 and 14.5
- Plan: [M3.5 plan](../M3.5-rust-desktop-implementation-plan.md) sections 3.7 and 4.13
- Last updated: 2026-11-08

This page documents the desktop packaging pipeline, the required formats, the
local build evidence, and the cross-OS blockers. It does not cover updater
manifests or signing (M3.5-11.3), CI release automation (M3.5-11.4), or
installer-size and cold-start evidence (M3.5-11.5).

## Tool decision

`cargo-dist` was evaluated as the first choice. It is not installed in this
environment, and its default artifact set is archives plus shell/PowerShell
installers; it does not cover the full required matrix (DMG, AppImage, `.deb`,
`.rpm`) without additional wrappers. The task allows an equivalent pipeline, so
this task uses a small reproducible Python pipeline that calls the native
packaging tools on each OS:

| OS | Tool | Format |
| --- | --- | --- |
| Linux | `dpkg-deb` | `.deb` |
| Linux | Python `tarfile` + `gzip` | portable `.tar.gz` |
| macOS | `codesign`, `hdiutil` | `.app` bundle, `.dmg` |
| Windows | Python `zipfile` | portable `.zip` |

The pipeline is `rust/packaging/package.py`; its configuration is
`rust/packaging/package.toml`. It reads the version from
`rust/apps/desktop/Cargo.toml` so the package version cannot drift from the
crate. It writes artifacts to `rust/dist/`, which is ignored by git.

### Why not cargo-dist alone

- The required formats are OS-native installers. `cargo-dist` would still need
  wrappers for DMG, AppImage, `.deb`, and `.rpm`.
- The pipeline must run locally on the available OS without a network install
  of a packaging tool.
- The equivalent pipeline is short, auditable, and uses tools already present
  on each target OS.

If a later task standardizes on `cargo-dist`, keep this script as the wrapper
for the formats `cargo-dist` does not produce.

## Required formats

| OS | Required by spec | Configured here | Status |
| --- | --- | --- | --- |
| Windows | MSI or portable zip | portable `.zip` | Configured; needs a Windows runner (B-02). |
| macOS | signed/notarized DMG or app bundle | `.app` bundle + `.dmg` | Configured; needs a macOS runner (B-01) and signing/notarization credentials (B-03). |
| Linux | AppImage and/or `.deb`/`.rpm` | `.deb` + portable `.tar.gz` | Built locally. |

AppImage and RPM are not produced because `appimagetool` and `rpmbuild` are not
available. The spec accepts `.deb` as the Linux format, so this is not a
blocker. MSI is not produced because the spec accepts a portable zip.

## Prerequisites

All targets:

- Rust toolchain pinned by `rust/rust-toolchain.toml` (Rust 1.98.1).
- Python 3.11 or newer (for `tomllib`).
- `cargo` on `PATH`.

Linux:

- `dpkg-deb` from the `dpkg` package.
- `dpkg-shlibdeps` from `dpkg-dev` is used when available to compute the
  `Depends` line from the staged binary; the script falls back to a
  conservative manual list when it is absent.
- `tar` and `gzip` are not required by the script; Python writes the archive.

macOS:

- `hdiutil` and `codesign` (part of macOS).
- `xcrun notarytool` for notarization.
- `APPLE_SIGNING_IDENTITY` for code signing.
- `APPLE_NOTARY_PROFILE` for notarization.

Windows:

- Python 3.11 or newer.
- No MSI toolchain is required for the portable zip.

## Commands

From the repository root:

```bash
# Build the release binary, clean rust/dist/, package the host target, and
# write checksums.
make rust-package

# Equivalent direct invocation without cleaning rust/dist/ first.
python3 rust/packaging/package.py

# Reuse an existing release binary (useful for iterating on packaging).
python3 rust/packaging/package.py --skip-build

# Remove rust/dist/ before packaging.
python3 rust/packaging/package.py --clean

# Show configured targets and formats.
python3 rust/packaging/package.py --list-targets
```

On macOS, `make` may be unavailable; run `python3 rust/packaging/package.py`
directly. On Windows, run `python rust\packaging\package.py` from PowerShell or
Command Prompt.

The script refuses to build a target that does not match the host. Cross-OS
artifacts are built on their own runners in M3.5-11.4.

## Output paths and artifact names

All artifacts are written to `rust/dist/`:

| Target | Artifact |
| --- | --- |
| Linux x86_64 | `rust/dist/chatui_<version>_amd64.deb` |
| Linux aarch64 | `rust/dist/chatui_<version>_arm64.deb` |
| Linux x86_64 | `rust/dist/ChatUI-<version>-linux-x86_64.tar.gz` |
| Linux aarch64 | `rust/dist/ChatUI-<version>-linux-aarch64.tar.gz` |
| Windows x86_64 | `rust/dist/ChatUI-<version>-windows-x86_64.zip` |
| macOS aarch64 | `rust/dist/ChatUI.app`, `rust/dist/ChatUI-<version>-macos-aarch64.app.zip`, `rust/dist/ChatUI-<version>-macos-aarch64.dmg` |
| macOS x86_64 | `rust/dist/ChatUI.app`, `rust/dist/ChatUI-<version>-macos-x86_64.app.zip`, `rust/dist/ChatUI-<version>-macos-x86_64.dmg` |
| All | `rust/dist/SHA256SUMS` |

The `.deb` installs:

- `/usr/bin/chatui-desktop`
- `/usr/share/applications/chatui.desktop`
- `/usr/share/icons/hicolor/scalable/apps/chatui.svg`
- `/usr/share/doc/chatui/copyright`

The desktop entry registers the `chatui://` scheme and the `chatui-desktop`
startup class. The macOS `Info.plist` registers the same scheme.

The `.deb` `Depends` line is computed from the staged binary with
`dpkg-shlibdeps` when available, so it targets the build host's distribution
(for example, Ubuntu 26.04 uses `libgtk-3-0t64` and `libc6 (>= 2.43)`). Build
on the oldest supported distribution if a wider install range is required.

## Checksums

`rust/dist/SHA256SUMS` contains one line per artifact in `sha256sum` format:

```text
<hash>  <filename>
```

Verify from `rust/dist/`:

```bash
cd rust/dist
sha256sum -c SHA256SUMS
```

The checksum file is regenerated on every run and is not committed.

## Reproducibility

The pipeline normalizes timestamps to `SOURCE_DATE_EPOCH` when set, otherwise
to the latest git commit time, and falls back to 1980-01-01. It sets archive
member ownership to `root:root`, clears user/group names, and uses a fixed
gzip header timestamp. Two consecutive `--skip-build` runs produced identical
`SHA256SUMS` on the local Linux host.

The release binary itself is not bit-for-bit reproducible across machines
because Cargo embeds build paths and the linker output depends on the host
toolchain. The packaging step is reproducible for a fixed binary.

## Local build evidence

Built on 2026-11-08 on the available Linux host:

| Item | Value |
| --- | --- |
| OS | Ubuntu 26.04.1 LTS, Linux 7.0.0-42-generic x86_64 |
| Toolchain | rustc 1.98.1 (48a229cea 2026-09-01), cargo 1.98.1 |
| Command | `make rust-package` |
| Release binary | `rust/target/release/chatui-desktop` (19,415,648 bytes, stripped) |
| `.deb` | `rust/dist/chatui_0.1.0_amd64.deb`, 6.6 MiB |
| `.deb` SHA-256 | `afb28870f3311929a0fe66e27678e02c801349391f6f046a9f55656a301b24f9` |
| `.tar.gz` | `rust/dist/ChatUI-0.1.0-linux-x86_64.tar.gz`, 8.6 MiB |
| `.tar.gz` SHA-256 | `de01e9552db76cb3195d3cbc908a664919caa0eaf89ce5eb98133c8d71fc1770` |
| `SHA256SUMS` | `rust/dist/SHA256SUMS`, 188 bytes |
| `SHA256SUMS` SHA-256 | `2a070ad7ef73976a610e3e7f635bc508b5d5262371e9795e09d0cc7620b85b92` |

Launch checks:

| Check | Result |
| --- | --- |
| Extract `.deb` with `dpkg-deb -x` and run `usr/bin/chatui-desktop --print-menu` | Pass; exit 0, menu printed. |
| Extract `.tar.gz` and run `ChatUI-0.1.0-linux-x86_64/chatui-desktop --print-menu` | Pass; exit 0, menu printed. |
| `make rust-package` | Pass; release build finished and both artifacts were written. |
| `sha256sum rust/dist/*` | Pass; hashes match `SHA256SUMS`. |

The `--print-menu` path is the headless launch smoke used by M3.5-11.1. It
exercises the packaged binary without opening a window. A full windowed launch
on each tier-1 OS remains part of the M3.5-12.2 OS smoke matrix.

## Signing and notarization

The pipeline wires the credential hooks but does not contain credentials:

- macOS code signing: set `APPLE_SIGNING_IDENTITY`; the script runs
  `codesign --force --deep --options runtime --sign <identity> ChatUI.app`.
- macOS notarization: set `APPLE_NOTARY_PROFILE`; the script runs
  `xcrun notarytool submit <dmg> --keychain-profile <profile> --wait`.
- Windows Authenticode signing is not implemented. The portable zip is
  unsigned until a certificate and signing step are added (B-04).

Signing keys and certificates are never committed. They belong in CI secrets
for M3.5-11.4.

## Metadata

`rust/packaging/package.toml` defines the app metadata:

| Field | Value |
| --- | --- |
| Name | `chatui` |
| Display name | `ChatUI` |
| Binary | `chatui-desktop` |
| Version | Read from `rust/apps/desktop/Cargo.toml` (`0.1.0`) |
| Description | Local-first, bring-your-own-key chat client for multiple model providers. |
| License | `Proprietary` (placeholder; the repository has no `LICENSE` file yet) |
| Authors | ChatUI contributors |
| Homepage | `https://github.com/rkodev/chatui` |
| Identifier | `dev.chatui.ChatUI` |

The license value is a placeholder, not a product decision. Replace it when the
project license is confirmed. The Linux icon is a placeholder SVG; macOS
`.icns` and Windows `.ico` generation is a follow-up if the release needs
platform-native icons.

## No browser engine

The desktop client uses Slint's native renderer. The packaging pipeline copies
only the release binary, the desktop entry, the SVG icon, and a README. It does
not bundle a browser engine, WebView runtime, or web assets.

## Blockers

| ID | Blocker | Impact | Action needed |
| --- | --- | --- | --- |
| B-01 | No macOS Apple Silicon runner or machine is available. | Cannot build or launch the macOS `.app`/`.dmg` locally. | Provision a macOS runner or machine before M3.5-11.4/12.2. |
| B-02 | No Windows x86_64 runner or machine is available. | Cannot build or launch the Windows portable zip locally. | Provision a Windows runner or machine before M3.5-11.4/12.2. |
| B-03 | Apple signing and notarization credentials are absent. | macOS artifacts are unsigned and unnotarized. | Provision `APPLE_SIGNING_IDENTITY` and `APPLE_NOTARY_PROFILE` in CI secrets. |
| B-04 | Windows Authenticode certificate is absent. | Windows binary is unsigned. | Provision a code-signing certificate and signing step in CI. |

These are external environment and credential blockers, not pipeline defects.
The formats are configured and the Linux artifact is built and launched
locally.

## Out of scope

- Signed update manifests and channels (M3.5-11.3).
- CI release automation and the OS matrix (M3.5-11.4).
- Installer-size and cold-start evidence (M3.5-11.5).
- AppImage and RPM artifacts (not required because `.deb` is produced).
- MSI artifacts (not required because the portable zip is produced).
