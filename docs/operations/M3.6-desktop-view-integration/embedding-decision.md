# M3.6 embedding decision

- Task: `M3.6-01.1`
- Slint version: `1.18.1` (workspace pin)
- Spike: `rust/spikes/ui-embedding/`
- Decision: **GO** — embed the existing feature `Window` components into
  `ShellWindow` with `ComponentContainer` + `ComponentFactory`.

## What was proven

The spike builds a host `Window` with two `ComponentContainer`s and embeds two
`EmbeddedWindow` instances whose root is a `Window`. The Rust entry point
creates each embedded component with `::new()`, installs a factory that returns
the same handle the controller owns, and updates a property after embedding.

The headless tests in `rust/spikes/ui-embedding/src/main.rs` prove:

| Requirement | Test | Result |
| --- | --- | --- |
| Embedded content renders inside the host | `embedded_content_renders_inside_the_host` | The embedded `Text` is reachable from the host accessibility tree and has a non-zero layout size. |
| Callback fires from embedded content while embedded | `embedded_callback_reaches_rust_while_embedded` | Invoking the embedded button through the host tree increments the embedded `count` and reaches the Rust handler. |
| Setter on the controller handle updates the embedded UI | `setter_on_controller_handle_updates_embedded_ui` | `left.set_label(...)` changes the `Text` found in the host tree. |
| Two containers toggle with `visible` without losing handles | `visibility_toggle_keeps_embedded_handles_usable` | Both handles still accept setters and callbacks while hidden and after being shown again. |
| Dropping the host does not panic | `dropping_the_host_does_not_panic` | Dropping the host leaves the controller handles usable; dropping them afterwards is clean. |

## Exact Slint APIs used

- `ComponentContainer { component-factory: root.left-factory; }` in
  `ui/embedding.slint`.
- `in property <component-factory> left-factory;` on the host component.
- `slint::ComponentFactory::new(move |_context| Some(handle.clone_strong()))`
  from Rust.
- `slint::ComponentHandle::clone_strong()` to clone the controller handle into
  the factory.
- Generated setters `HostWindow::set_left_factory` / `set_right_factory`.
- `visible: root.show-left;` on each `ComponentContainer` for toggling.
- `i_slint_backend_testing::init_no_event_loop()` plus
  `ElementHandle::find_by_accessible_label` / `query_descendants` for the
  headless assertions.

`ComponentContainer` and `component-factory` are **experimental** Slint
builtins. They are removed from the public builtin register unless
`SLINT_ENABLE_EXPERIMENTAL_FEATURES` is set at compile time. The spike enables
it in `build.rs` and compiles `ui/embedding.slint` with
`slint_build::CompilerConfiguration::new().with_debug_info(true)`.

## Limitations to carry into production

1. **Experimental builtins.** `M3.6-03.1` must enable
   `SLINT_ENABLE_EXPERIMENTAL_FEATURES=1` for `chatui-ui`. That crate uses the
   `slint!` macro, so its `build.rs` should emit
   `cargo:rustc-env=SLINT_ENABLE_EXPERIMENTAL_FEATURES=1` (the same mechanism
   already used for `SLINT_EMIT_DEBUG_INFO`). Experimental APIs may change in a
   future Slint release; the workspace pin to `1.18.1` contains that risk.
2. **Window chrome.** Only the embedded component's item tree is embedded. The
   embedded `Window`'s own title, menu bar, and other window-level chrome are
   not rendered by the host. Feature views must not depend on their own
   `MenuBar`; the shell owns the menu bar.
3. **Sizing.** `ComponentContainer` installs width/height bindings on the
   embedded `WindowItem` that follow the container. The embedded view must be
   layout-driven and must not rely on its own fixed window size.
4. **Focus.** The embedded `WindowItem` keeps the window adapter created by
   `::new()`. Focus traversal across the host/embedded boundary was not
   exercised by this spike. `M3.6-03.1` must verify keyboard focus and
   accessibility order after wiring the real views.
5. **Nested windows and popups.** Dialogs or popups opened from an embedded
   component may attach to the embedded window adapter rather than the host.
   This was not exercised. `M3.6-03.1` must verify any feature view that opens
   a popup or dialog.
6. **Factory identity.** The factory must return the controller's own handle.
   Returning a fresh component would leave the controller updating a different
   instance. The spike's factory clones the handle with `clone_strong()`.
7. **Visibility vs. conditional.** Toggle containers with `visible`, not `if`.
   An `if` removes the container and can destroy the embedded item tree; the
   spike uses `visible` and proves the handles survive.

## Fallback plan

If a later Slint release removes the experimental builtins, or if a feature
view cannot be embedded because of window chrome, focus, or popup behaviour,
fall back to the plan's alternative:

- Convert each `XWindow` into a `Rectangle`-rooted view component.
- Expose that view's properties and callbacks on `ShellWindow` (or on a
  dedicated host component) and instantiate it directly in the shell layout.
- Keep the controller handles and `sync()` methods; only the root element type
  changes from `Window` to `Rectangle`.

This fallback is more invasive because it moves the inline `slint!` blocks into
reusable view components, but it does not depend on experimental APIs.

## Affected tasks

The decision is **go**, so no task needs to switch to the fallback. `M3.6-03.1`
must:

- enable `SLINT_ENABLE_EXPERIMENTAL_FEATURES=1` for `chatui-ui`;
- verify focus, accessibility order, and popups for each embedded view;
- keep the shell's menu bar and window chrome on `ShellWindow`.

## Verification

Run from `rust/spikes/ui-embedding`:

```bash
cargo fmt --all --check
cargo clippy --all-targets -- -D warnings
cargo test
```

Results:

- `cargo fmt --all --check`: clean.
- `cargo clippy --all-targets -- -D warnings`: clean.
- `cargo test`: 5 passed, 0 failed.
