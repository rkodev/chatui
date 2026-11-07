# M3.5 UI Framework Decision

- Status: Decided 2026-11-07 by M3.5-01.2.
- Owner: UI owner, with Desktop/UI and Verification/OS owners.
- Scope: desktop UI framework for the Rust client on Windows, macOS, and Linux.
- Source plan: [M3.5 Rust Desktop Implementation Plan](../M3.5-rust-desktop-implementation-plan.md)
- Milestone: [M3.5 Rust desktop client](../../milestones/M3.5-rust-desktop.md)
- Authoritative spec: [IMPLEMENTATION.md](../../IMPLEMENTATION.md) sections 10.2, 10.8, 10.9, 11.9, and 11.11.

## 1. Decision

| Item | Decision |
| --- | --- |
| Chosen framework | **Slint 1.18.1** with the winit backend, femtovg/software renderers, and the `accessibility` feature. |
| Fallback | **Iced 0.14.0** if Slint is rejected on licensing or if a blocking Slint defect appears before feature UI starts. |
| Dioxus | Not selected. `dioxus-desktop` is a WebView shell (`tao` + `wry`), which conflicts with the milestone's no-WebView production boundary. |
| Tauri | Comparison only. It is a WebView shell and cannot be selected without an explicit spec/milestone change. |
| Prototype | `rust/spikes/ui-framework/` is a detached Slint package. It is not a member of the main `rust/` workspace. |

The decision is recorded before any feature UI task starts. M3.5-08.1 and later UI
tasks must use Slint unless this record is superseded by an approved change.

## 2. Criteria and weights

Weights reflect the milestone's hard requirements: accessibility and native
desktop behavior carry the most weight; packaging and binary size are important
but can be improved later; async and unsafe surface are secondary because all
four options can integrate with `tokio` and all use some platform bindings.

| Criterion | Weight | Why it matters |
| --- | ---: | --- |
| Native desktop behavior on Windows, macOS, and Linux | 15% | The client must not be a WebView wrapper and must behave like a desktop app. |
| Accessibility bridge and keyboard focus | 20% | Section 11.11 and M3.5-10.4 require keyboard reachability, focus rings, and screen-reader evidence. |
| High-DPI and font scaling | 10% | Tier-1 desktop displays are commonly scaled; text and hit targets must remain correct. |
| Packaging support (MSI/DMG/AppImage/deb/rpm) | 10% | Section 10.10 requires signed installers on all three OSes. |
| Binary size and build time | 10% | Section 10.9 budgets an installer under 40 MB and a cold start under 1.5 s. |
| Test harness and snapshot support | 10% | Section 10.11 requires UI tests and snapshot tests where available. |
| Native menus, dialogs, clipboard, drag-and-drop, window state, tray, multi-window, deep links | 15% | Section 10.8 and M3.5-10.3 require these desktop integrations. |
| Async integration with `tokio` | 5% | Streaming, storage, and provider work are async. |
| Dependency and unsafe surface | 5% | The spec asks to avoid `unsafe` outside vetted platform bindings. |

Scores use a 1-5 scale: 1 = blocking gap, 3 = workable with custom work, 5 =
first-class support. Weighted totals are the sum of `weight × score`.

## 3. Comparison

| Criterion | Dioxus 0.7.10 | Slint 1.18.1 | Iced 0.14.0 | Tauri 2.12.0 |
| --- | --- | --- | --- | --- |
| Architecture | `dioxus-desktop` uses `tao` + `wry` (system WebView) | Native renderer (femtovg/skia/software) on `winit` | Native renderer (wgpu/tiny-skia) on `winit` | `tauri-runtime-wry` + `wry` (system WebView) |
| Native desktop behavior | 2: WebView, not a native renderer | 5: native widgets and rendering | 5: native rendering | 1: WebView shell |
| Accessibility | 3: system WebView accessibility bridge; no native AccessKit tree | 5: AccessKit feature, keyboard focus, accessible roles | 1: no AccessKit integration in 0.14 | 3: system WebView accessibility bridge |
| High-DPI and font scaling | 4: delegated to the system WebView | 4: winit scale factor and logical pixels | 4: winit scale factor and logical pixels | 4: delegated to the system WebView |
| Packaging | 4: Dioxus CLI bundling exists; not exercised here | 3: no built-in bundler; use `cargo-dist`/`cargo-bundle` | 3: no built-in bundler; use `cargo-dist`/`cargo-bundle` | 5: Tauri bundler covers MSI/DMG/AppImage/deb/rpm |
| Binary size and build time | 3: smaller binary but requires a system WebView runtime | 4: measured 30 MB release, 22 MB stripped; 105 s release build | 4: native, no WebView; wgpu adds size | 3: smaller binary but requires a system WebView runtime |
| Test harness and snapshots | 2: SSR/component tests; no native UI harness | 4: `i-slint-backend-testing` element search and simulated events; no image snapshot built in | 5: `iced_test` snapshot harness | 3: WebDriver/`tauri-driver` plus frontend tests |
| Native menus | 4: `muda` integration | 4: `MenuBar`; native menu bar via `muda` on macOS/Windows, in-window on Linux | 3: no built-in menu bar; integrate `muda` | 5: menu APIs and plugins |
| File dialogs | 4: `rfd` integration | 4: `rfd` integration | 4: `rfd` integration | 5: dialog plugin |
| Clipboard | 4: WebView clipboard plus Rust APIs | 4: platform clipboard and text-input copy/paste | 4: `window_clipboard`/`arboard` | 5: clipboard plugin |
| Drag-and-drop | 4: WebView drag events | 4: `DropArea` and native drag start | 4: winit drag events | 5: Tauri drag-drop events |
| Window state | 4: window builder and window API | 3: winit accessor; persistence is manual | 3: window API; persistence is manual | 5: window-state plugin |
| Tray | 4: `tray-icon` integration | 4: `system-tray` feature | 3: integrate `tray-icon` | 5: tray APIs |
| Multi-window | 4: supported | 4: supported | 4: supported | 5: supported |
| Deep links | 3: manual single-instance handling | 3: manual single-instance handling | 3: manual single-instance handling | 5: deep-link plugin |
| Async with `tokio` | 4: `tokio` integration | 4: `tokio` integration and `spawn_local` | 5: `iced_futures` with `tokio` feature | 5: `tokio` runtime |
| Dependency and unsafe surface | 2: `wry`/`tao` and a WebView runtime | 3: `winit`/femtovg unsafe internals; large dependency tree | 3: `wgpu` unsafe internals; large dependency tree | 2: `wry`/`tao` and a WebView runtime |
| **Weighted total** | **3.10** | **4.20** | **3.40** | **3.35** |

Tauri's weighted total is not a selection result: it is disqualified by the
no-WebView boundary regardless of its score. Dioxus is also disqualified by the
same boundary. The meaningful comparison is Slint versus Iced, and Slint wins
because accessibility is a hard requirement and Iced 0.14 has no AccessKit
integration.

### 3.1 Why Slint over Iced

- Slint ships an AccessKit-backed accessibility tree and keyboard focus. Iced
  0.14 does not integrate AccessKit, so M3.5-10.4 would require custom
  accessibility work or a framework fork.
- Slint has a built-in `MenuBar` and uses `muda` for native menu bars on macOS
  and Windows. Iced requires manual `muda` integration.
- Slint's testing backend supports element lookup and simulated events. Iced's
  `iced_test` snapshot harness is stronger for image snapshots, but it does not
  compensate for the accessibility gap.
- Slint's measured release binary is 30 MB (22 MB stripped) with the default
  renderers and a GTK3 file dialog. That is inside the 40 MB installer budget
  before release-profile tuning.

### 3.2 Why not Dioxus

The spec recommends Dioxus with `dioxus-desktop`, but it also permits "another
Rust desktop UI framework." The spike found a blocking conflict with the
recommended option: `dioxus-desktop` 0.7.10 depends on `tao` and `wry`, and
`wry` is a system-WebView binding. That makes Dioxus a WebView-based client,
which the milestone explicitly forbids ("No WebView-based production client").
The same architecture is why Tauri is comparison-only.

Dioxus Native/Blitz is not a production substitute at this time: the available
`dioxus-native` release is `0.8.0-alpha.3` and `blitz` is `0.3.0-beta.4`.
Choosing Dioxus would therefore require either accepting a WebView or adopting
pre-release native rendering. Neither is acceptable for the M3.5 release gate
without an explicit spec/milestone change.

If the product owner wants Dioxus, the correct next step is a spec change that
permits a WebView-based desktop client, followed by a new framework decision.
This task does not make that change.

## 4. Prototype evidence

The prototype is a detached Slint package at `rust/spikes/ui-framework/`. It is
not a member of the main `rust/` workspace. It contains one window, one text
field, one button, one list, one `MenuBar`, and one `rfd` file-picker call.

Host: Ubuntu 26.04.1 LTS, x86_64, GNOME on Wayland (`DISPLAY=:0`,
`WAYLAND_DISPLAY=wayland-0`). Rust 1.98.1.

| Command | Result |
| --- | --- |
| `cargo metadata --manifest-path rust/spikes/ui-framework/Cargo.toml --no-deps` | Pass; one package, `chatui-ui-framework-spike`. |
| `cargo build --manifest-path rust/spikes/ui-framework/Cargo.toml` | Pass; cold build 72.29 s including dependencies; incremental rebuild 4.56 s. |
| `cargo run --manifest-path rust/spikes/ui-framework/Cargo.toml -- --smoke` | Pass (exit 0); window opened, event loop ran, and the smoke timer exited after 1.83 s. |
| `timeout 6s cargo run --manifest-path rust/spikes/ui-framework/Cargo.toml` | Exit 124; the window opened and the event loop ran until the timeout. |
| `cargo build --release --manifest-path rust/spikes/ui-framework/Cargo.toml` | Pass; 105.17 s. |
| `cargo fmt --manifest-path rust/spikes/ui-framework/Cargo.toml -- --check` | Pass (exit 0). |
| `cargo clippy --manifest-path rust/spikes/ui-framework/Cargo.toml -- -D warnings` | Pass (exit 0). |
| `cd rust && cargo metadata --no-deps` | Pass; the eight main workspace members are listed and the spike is absent. |
| `make rust-check` | Pass (exit 0). |

Binary sizes:

| Artifact | Size |
| --- | ---: |
| Debug binary | 344 MB |
| Release binary | 30 MB |
| Release binary after `strip` | 22 MB |

The release binary links GTK3 because the prototype uses `rfd` with the `gtk3`
feature for the file dialog. That is a packaging consideration: the Linux
package must either depend on GTK3 or switch `rfd` to the XDG portal backend.
Slint itself does not require GTK3.

## 5. Platform integration notes

| Area | Slint finding | Follow-up |
| --- | --- | --- |
| Accessibility | `accessibility` feature is on by default; the winit backend depends on `accesskit`, `accesskit_winit`, and `accesskit_unix`. Keyboard focus and accessible roles are available. | M3.5-10.4 must still run a real screen-reader smoke test on each tier-1 OS. |
| High-DPI and font scaling | winit reports scale factors; Slint uses logical pixels and scales fonts and hit targets. | Verify at 125%, 150%, and 200% on Windows and macOS; record evidence in M3.5-10.4. |
| Native menu | `MenuBar` is built in. The winit backend uses `muda` for the native menu bar on macOS and Windows; on Linux the menu bar is rendered in-window. | M3.5-10.3 must confirm the Linux in-window menu is acceptable and add shortcuts. |
| File dialog | `rfd` provides native dialogs. The prototype uses the GTK3 backend on Linux. | Decide GTK3 versus XDG portal for packaging; record the runtime dependency. |
| Clipboard | Slint exposes platform clipboard support and text-input copy/paste. | M3.5-09.2/09.4 must add explicit copy actions for messages and code blocks. |
| Drag-and-drop | `DropArea` and native drag start are available. | M3.5-09.3 must test file drops and attachment handling. |
| Window state | The winit accessor exposes the underlying window; persistence is manual. | M3.5-10.3 must persist size, position, and maximized state. |
| Tray | `system-tray` feature is available. | Optional; M3.5-10.3 decides whether to ship a tray icon. |
| Multi-window | Supported. | The first release stays single-window per DEC-11 unless a concrete need appears. |
| Deep links | No built-in deep-link handler; use a single-instance plugin or OS registration. | M3.5-10.3 must implement `chatui://chat/<id>` with cold/warm-start forwarding. |
| Test harness | `i-slint-backend-testing` provides a headless backend, element search, and simulated events. Image snapshots are not built in. | M3.5-08.1/09.* must add UI tests; use `insta` for non-image snapshots and a small image-diff helper if needed. |
| Async | Slint integrates with `tokio` through `spawn_local` and timers. | Keep streaming and storage in `chatui-providers`/`chatui-storage`; the UI only observes state. |

## 6. Risks and mitigations

| Risk | Impact | Mitigation |
| --- | --- | --- |
| Slint license is GPL-3.0-only OR Slint Royalty-free 2.0 OR Slint Software 3.0. | The project must accept the royalty-free terms or use GPL-3.0. | Confirm the project license before feature UI. The royalty-free license requires an `AboutSlint` widget or a public "Made with Slint" badge. If that is unacceptable, use the Iced fallback. |
| Slint's Linux menu bar is in-window, not an OS-global menu. | Linux users do not get a global menu bar. | Accept for the first release; document it in M3.5-10.3. |
| `rfd` GTK3 backend adds a GTK3 runtime dependency. | Linux packaging must ship or depend on GTK3. | Prefer the XDG portal backend if the target distros support it; otherwise declare GTK3 in the package metadata. |
| Slint has no built-in image snapshot harness. | UI regression coverage is weaker than Iced's `iced_test`. | Use the testing backend for interaction tests and `insta` for state snapshots; add a small image-diff helper only if needed. |
| Slint's dependency tree is large and includes `unsafe` in winit/femtovg. | Audit and binary-size work is required. | M3.5-01.3 adds `cargo-deny`/`cargo-audit`; M3.5-11.1 tunes the release profile. |
| macOS and Windows were not available on this host. | Native menu, high-DPI, and screen-reader behavior are unverified on those OSes. | Record as an environment blocker; verify in M3.5-10.3/10.4 and the CI matrix. |

## 7. Tauri disqualification

Tauri 2.12.0 is a WebView shell. Its dependency graph includes `wry`,
`tauri-runtime-wry`, `webkit2gtk` on Linux, and `webview2-com` on Windows. The
milestone says "No WebView-based production client" and the plan says Tauri
"can be a comparison/reference point only." Tauri is therefore not a candidate
for production under the current milestone. Selecting it requires an explicit
spec/milestone change.

Tauri's strengths are real: its bundler covers MSI/DMG/AppImage/deb/rpm, and its
plugin ecosystem covers dialogs, clipboard, window state, tray, and deep links.
Those strengths do not override the WebView boundary.

## 8. Conditions and next steps

1. Confirm the project's license compatibility with Slint's royalty-free terms
   before M3.5-08.1. If the terms are unacceptable, switch to the Iced fallback
   and record the accessibility mitigation plan.
2. Keep `chatui-domain`, `chatui-contracts`, and `chatui-providers` free of any
   Slint dependency. Only `chatui-ui` and `apps/desktop` may depend on Slint.
3. Add the Slint testing backend and UI test targets in M3.5-08.1.
4. Verify native menus, high-DPI, file dialogs, clipboard, drag-and-drop, window
   state, and deep links on macOS and Windows when those environments are
   available.
5. Update the M3.5 decision record (DEC-18) from "deferred" to the chosen
   framework in the task that owns that record.

## 9. Evidence sources

- `crates.io` index metadata for `dioxus-desktop` 0.7.10, `slint` 1.18.1,
  `iced` 0.14.0, and `tauri` 2.12.0.
- Slint 1.18.1 crate sources: `Cargo.toml` features, `i-slint-backend-winit`
  dependencies (`accesskit`, `accesskit_winit`, `muda`, `arboard`), and
  `i-slint-backend-testing` API.
- Iced 0.14.0 crate sources: `Cargo.toml` features and `iced_winit` dependency
  list (no AccessKit).
- Prototype commands and results in section 4.
- Spec sections 10.2, 10.8, 10.9, 11.9, and 11.11; plan sections 3.1 and 4.3.
