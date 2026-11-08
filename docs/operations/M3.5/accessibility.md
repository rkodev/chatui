# M3.5 Accessibility Evidence

- Task: M3.5-10.4 - Accessibility evidence and screen-reader smoke tests
- Status: Complete with a Linux screen-reader blocker
- Recorded: 2026-11-08
- Build: debug build of `chatui-desktop` and `chatui-ui` from this working tree
- Host: Ubuntu 26.04.1 LTS, x86_64, GNOME on Wayland, Slint 1.18.1, rustc 1.98.1
- Spec: [IMPLEMENTATION.md](../../IMPLEMENTATION.md) sections 10.8, 11.11, and 12.1
- Plan: [M3.5 Rust Desktop Implementation Plan](../M3.5-rust-desktop-implementation-plan.md) sections 3.6 and 6.2

This record contains only synthetic labels and harness output. No API key,
prompt, real conversation, personal data, or signing material was used.

## 1. Scope and method

The audit covers the shared Slint windows in `chatui-ui` and the desktop shell
integration in `chatui-desktop`:

- shell navigation, drawer, menus, and shortcuts overlay;
- composer;
- message list and streaming row;
- settings;
- data controls;
- provider management;
- model management;
- onboarding.

The desktop binary currently composes the shell only. The feature windows are
instantiated and audited directly in the UI test harness, which is the same
composition boundary recorded by M3.5-10.2 and M3.5-10.3. The actual app run
therefore proves shell-level navigation; the feature windows are proven by the
hosted component tests below.

Automated tree checks use Slint's testing backend with
`SLINT_EMIT_DEBUG_INFO=1`. The crate's `build.rs` sets that variable for debug
profiles only, so release artifacts do not carry the test-only compiler debug
info.

## 2. Keyboard navigation

| Surface | Expected traversal | Result | Evidence |
| --- | --- | --- | --- |
| Shell navigation | Toggle/menu, Chat, History, Providers, Settings, Data, drawer close | Pass | `shell::tests::every_destination_is_reachable_in_both_layouts`, `accessibility::tests::tab_moves_forward_and_wraps`, `accessibility::tests::shift_tab_moves_backward_and_wraps` |
| Shell drawer | `Escape` closes the drawer; close action joins the focus order | Pass | `shell::tests::escape_closes_the_drawer`, `shell::tests::focus_order_includes_the_drawer_close_action` |
| Settings sections | Every section is a focusable button activated by Space/Return | Pass (fixed) | `settings::view` section buttons plus `accessibility_tests::settings_controls_have_accessible_names` |
| Data sections | Every section is a focusable button activated by Space/Return | Pass (fixed) | `data::view` section buttons plus `accessibility_tests::data_controls_have_accessible_names` |
| Composer | Message editor and all actions are native focusable controls | Pass | `accessibility_tests::composer_controls_have_accessible_names` |
| Message list | Rows are list items; actions are native buttons | Pass | `accessibility_tests::message_list_announcement_is_a_bounded_polite_live_region` and message-list component tests |
| Providers, models, onboarding | Native buttons, check boxes, combo boxes, and text fields | Pass | `accessibility_tests::provider_controls_have_accessible_names`, `model_controls_have_accessible_names`, `onboarding_controls_have_accessible_names` |

The Settings and Data sidebars previously used a bare `Rectangle` plus
`TouchArea`, so they were pointer-only and had no accessible role. They are now
`FocusScope`-backed buttons with `accessible-role: button`, an accessible label,
Space/Return activation, and a visible keyboard focus border.

## 3. Focus visibility

- `FocusRing::from_tokens` supplies a 2 px ring with a 2 px offset. Light uses
  `#1a56db`; dark uses `#60a5fa`.
- Shell nav items track `FocusReason.tab-navigation` and render the ring only
  for keyboard focus. Pointer focus intentionally hides it.
- Settings and Data section buttons use the same keyboard-only focus rule.
- Native Slint buttons render their own framework focus indicator.
- Tests: `accessibility::tests::focus_ring_comes_from_tokens`,
  `accessibility::tests::pointer_focus_hides_the_ring`, and
  `shell::tests::slint_window_applies_density_font_size_and_reduced_motion`.

## 4. Accessible names

| Surface | Control | Accessible name | Result |
| --- | --- | --- | --- |
| Shell | Destination buttons | `Chat`, `History`, `Providers`, `Settings`, `Data` | Pass |
| Shell | Narrow-layout menu control | `Toggle navigation` | Pass (fixed) |
| Shell | Drawer close | `Close navigation` | Pass |
| Shell | Landmarks | `Navigation`, page title such as `Chat` | Pass |
| Settings | Section buttons | Visible section labels | Pass (fixed) |
| Settings | Theme, density, font size, send key, reasoning, provider, model | Explicit labels matching the visible field label | Pass (fixed) |
| Settings | Prompt and text fields | `Default system prompt`, `Temperature`, `Top P`, `Max output tokens`, `Stop sequences` | Pass (fixed) |
| Data | Section buttons | Visible section labels | Pass (fixed) |
| Data | Conversation, format, import mode | Explicit labels | Pass (fixed) |
| Data | Archive JSON and clear confirmation | `Archive JSON`, `Type DELETE ALL DATA to confirm` | Pass (fixed) |
| Data | Backup link | Falls back to `Open backup` when the label is empty | Pass (fixed) |
| Composer | Message editor | `Message` | Pass (fixed) |
| Providers | New key, label, base URL, authentication, API key, headers | Explicit labels | Pass (fixed) |
| Models | Search and manual-model fields | `Search models`, `Model id`, `Label`, `Context window`, `Capabilities` | Pass (fixed) |
| Onboarding | Key and label fields | `OpenRouter API key`, `Key label` | Pass (fixed) |
| Message list | Message rows | `list-item` with the visible role label (`You`, `Assistant`, ...) | Pass |

Text-only buttons inherit their accessible name from the visible text through the
Slint standard button. The audit test walks every `button`, `checkbox`,
`combobox`, and `text-input` node in each window and fails on an empty name.

## 5. Streaming live regions

- `streaming_announcement` emits only on state transitions:
  - `Response started` (polite);
  - `Response complete` (polite);
  - `Response cancelled` (polite);
  - `Response failed: <plain-language title>` (assertive).
- Token deltas produce no announcement. The latest announcement is retained in
  `MessageListState`, so a screen reader can read it without hearing each token.
- Announcements are capped at 160 characters. Errors reuse the visible
  plain-language title and never expose raw provider payloads.
- `MessageListWindow` exposes the text through an `accessible-live-region`
  property that switches between `polite` and `assertive`.
- Tests:
  - `chat::announcement::tests::start_is_announced_once`
  - `chat::announcement::tests::token_deltas_are_not_announced`
  - `chat::announcement::tests::completion_is_announced_once`
  - `chat::announcement::tests::cancellation_is_announced_as_cancelled`
  - `chat::announcement::tests::errors_are_announced_assertively_with_plain_language`
  - `chat::announcement::tests::announcements_are_bounded`
  - `chat::announcement::tests::initial_snapshot_announces_once`
  - `chat::message_list_tests::streaming_announcements_are_bounded_and_exposed_as_a_live_region`
  - `accessibility_tests::message_list_announcement_is_a_bounded_polite_live_region`

## 6. Reduced motion

| Check | Result | Evidence |
| --- | --- | --- |
| Reduced preference resolves to zero durations | Pass | `tokens::tests::reduced_motion_disables_transitions` |
| System preference follows the OS | Pass | `shell::tests::system_theme_and_reduced_motion_follow_the_os`, `settings::state::tests::system_theme_and_reduced_motion_follow_the_os` |
| Shell transition duration becomes 0 ms | Pass | `shell::tests::slint_window_applies_density_font_size_and_reduced_motion` |
| Streaming indicator has no animation | Pass by source audit | The message list renders a static typing marker and only restyles visible rows. |
| Linux probe reads `enable-animations` | Pass | `window_state` parser and probe tests |

Native Slint button hover/press transitions are short framework-internal color
transitions, not content motion. The app-owned navigation transition and streaming
indicator honor the reduced-motion preference.

## 7. Contrast

`tokens::tests::body_text_meets_wcag_contrast_in_both_themes` checks every text
foreground against canvas, surface, raised surface, user bubble, and code
background. `tokens::tests::focus_and_control_boundaries_meet_non_text_contrast`
checks focus rings and strong control boundaries at the 3:1 non-text threshold.

| Theme | Body text minimum | On-accent text | Focus ring | Strong boundary |
| --- | --- | --- | --- | --- |
| Light | 5.28:1 | 6.18:1 / 8.72:1 hover | 5.77–6.18:1 | 4.52–4.83:1 |
| Dark | 4.53:1 | 7.45:1 / 10.50:1 hover | 6.19–7.43:1 | 3.26–3.91:1 |

Status colors against the canvas measure 6.47–7.13:1 in light and 6.83–11.32:1
in dark. All measured values meet the spec thresholds; no contrast deviation is
recorded.

## 8. Screen-reader matrix

| OS | Screen reader | Status | Evidence |
| --- | --- | --- | --- |
| Linux x86_64 | Orca 50.3-0ubuntu0.1 | Blocked | `orca --replace --debug` exits before the UI loads: `GLib-GIO-ERROR ... Settings schema 'org.gnome.settings-daemon.plugins.xsettings' does not contain a key named 'antialiasing'`. `GDK_BACKEND=wayland` and `GSETTINGS_BACKEND=memory` do not avoid the error. The installed schema has only `disabled-gtk-modules`, `enabled-gtk-modules`, and `overrides`. |
| Windows x86_64 | Narrator | Blocked | No Windows host or runner is available (verification blocker B-02). |
| macOS Apple Silicon | VoiceOver | Blocked | No macOS host or runner is available (verification blocker B-01). |

No screen-reader pass is claimed for any OS. The Linux blocker is a precise
environment failure, not an app failure.

### 8.1 AT-SPI supporting inspection

Because Orca could not start, the Linux accessibility tree was inspected with the
AT-SPI 2.0 Python bindings. This is supporting evidence, not a screen-reader pass.

The running debug build exposed:

```text
application: chatui-desktop
  frame: ChatUI
    label: File, Edit, View, Conversation, Help
    landmark: Navigation
      label: ChatUI
      button: Chat, History, Providers, Settings, Data
    landmark: Chat
      label: Chat
      label: Chat placeholder. The chat surface arrives in M3.5-09.
```

Calling `grab_focus()` on each navigation button returned `True`, and every
button then reported the AT-SPI `FOCUSED` state. This confirms the exposed names,
roles, and programmatic focus targets, but it does not confirm speech output.

## 9. Automated checks and commands

Commands ran from `rust/` on 2026-11-08:

| Command | Result |
| --- | --- |
| `cargo fmt --all --check` | Pass |
| `cargo clippy -p chatui-ui --all-targets -- -D warnings` | Pass |
| `cargo clippy -p chatui-desktop --all-targets -- -D warnings` | Pass |
| `cargo nextest run -p chatui-ui` | 365 passed, 0 skipped |
| `cargo nextest run -p chatui-desktop` | 49 passed, 0 skipped |
| `cargo run -q -p chatui-desktop -- --smoke --no-single-instance` | Exit 0 |
| `cargo run -q -p chatui-desktop -- --smoke --no-single-instance --deep-link chatui://chat/accessibility-smoke` | Exit 0 |

Primary new test groups:

- `accessibility_tests::*` - cross-window role/name audit and live-region check.
- `chat::announcement::tests::*` and
  `chat::message_list_tests::streaming_announcements_*` - bounded announcements.
- `shell::tests::slint_window_exposes_*` and
  `shell::tests::every_slint_button_has_an_accessible_name` - shell semantics.
- `tokens::tests::focus_and_control_boundaries_meet_non_text_contrast` - 3:1
  non-text contrast.

## 10. Blockers, limitations, and deviations

- **Linux screen-reader smoke test is blocked** by the local Orca/schema
  incompatibility described in section 8. Re-run on a host with a compatible
  Orca and GNOME schema before claiming a Linux screen-reader pass.
- **Windows Narrator and macOS VoiceOver are blocked** by the missing hosts
  recorded as B-01 and B-02.
- The desktop app currently hosts only the shared shell. Feature windows are
  audited through their Slint components in the UI test harness; mount them in
  the desktop composition before claiming a full end-to-end screen-reader pass.
- Native Slint widget animations are framework-controlled. App-owned motion
  honors reduced motion; framework hover color transitions remain short and do
  not move content.
- No contrast deviation was found.

## 11. Redaction

No API key, prompt, conversation text, personal data, or signing material is
included in this evidence. The only conversation-like strings are synthetic
labels such as `accessibility-smoke`.
