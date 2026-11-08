# M3.6 Verification Evidence

- Task: M3.6-07.1 - Verification and evidence
- Status: Complete
- Recorded: 2026-11-08
- Composition commit under test: `750ffb5983ac76357494b2efc337d3f3145fb73f`
  (`M3.6-06.1 compose the desktop shell from real services`)
- Verification changes: the commit that adds this record and the
  `every_destination_hosts_a_real_feature_view` UI test
- Host: Ubuntu 26.04.1 LTS, x86_64, GNOME on Wayland, Slint 1.18.1 winit
  backend, rustc 1.98.1
- Backend: production `ServiceRuntime` over SQLite; session-only secret store
  for `--smoke`; default OS secret store for normal startup
- Scope: Rust desktop only. No `web/`, `android/`, or `contracts/` file was
  changed.

This record contains only synthetic labels and harness output. No API key,
prompt, real conversation, personal data, or signing material is included.

## 1. Environment

| Fact | Value |
| --- | --- |
| OS | Ubuntu 26.04.1 LTS (Resolute Raccoon) |
| Kernel | Linux 7.0.0-42-generic |
| Architecture | x86_64 |
| Desktop session | GNOME on Wayland (`XDG_SESSION_TYPE=wayland`, `DISPLAY=:0`, `WAYLAND_DISPLAY=wayland-0`) |
| Rust | `cargo 1.98.1`, `rustc 1.98.1` |
| UI toolkit | Slint 1.18.1, winit backend |
| Database | SQLite through `ServiceRuntime` |
| Smoke profile | Temporary directory created by `TempProfile`; removed on exit |
| Smoke secrets | `session_only_secret_store()`; the real OS keyring is not touched |

## 2. Per-destination evidence

The desktop composition installs each controller's own window handle into the
shell factory properties. The smoke navigates every destination and the
component tests exercise the actions below.

| Destination | View shown | Real data source | Actions exercised | Result |
| --- | --- | --- | --- | --- |
| Chat | `MessageListWindow` + `ComposerWindow` from `ChatController` | SQLite conversations, messages, providers, and models through `ChatService`; `ProviderChatAdapter`; `MemoryStreamingStore`; `PersistentDraftStore` | Send, stop/cancel, copy, attach, paste image, remove attachment, draft restore | Pass |
| History | `HistoryWindow` from `HistoryController` | `ConversationHistory` and `ConversationSearchService` over SQLite repositories; `DefaultConversationFactory` | New chat, search, rename, archive, delete/undo, open conversation, load more | Pass |
| Providers | `ProviderManagementWindow` from `ProviderManagementController` | `ProviderStoreAdapter`, `ProviderModelStore`, `ProviderConversationStore`, `ProviderSettingsStore`, `ProviderKeyValidator`, secret store | Add/edit/delete provider, rotate key, test connection, enable/disable, forget key | Pass |
| Settings | `SettingsWindow` from `SettingsController` | `SettingsStoreAdapter` and `SettingsCatalog` over SQLite | Theme, density, font size, send key, token usage, remote images, prompt/parameters, provider/model defaults, About/backup navigation | Pass |
| Data | `DataWindow` from `DataController` | `SqliteDataService` | Usage refresh, export all/conversation, import merge/replace, clear all, per-key removal | Pass |
| Models overlay | `ModelManagementWindow` from `ModelManagementController` | `ModelRepositoryAdapter`, `ModelProviderRepository`, `ModelSettingsStore`, `ProviderModelDiscovery` | Search, pin/hide, set default, manual add, refresh | Pass |

The smoke output was:

```text
smoke: chat
smoke: history
smoke: providers
smoke: settings
smoke: data
smoke: models overlay
smoke: close models
smoke: done
```

## 3. Commands and results

All commands ran from `rust/` on 2026-11-08.

| Command | Result | Bounded evidence |
| --- | --- | --- |
| `cargo fmt --all --check` | Pass | no output |
| `cargo clippy --workspace --all-targets -- -D warnings` | Pass | `Finished dev profile` |
| `cargo nextest run --workspace` | Pass | 1076 tests run: 1076 passed, 0 skipped |
| `cargo run -p chatui-desktop -- --smoke --no-single-instance` | Pass (exit 0) | printed every destination and `smoke: done` |
| `cargo nextest run -p chatui-ui accessibility_tests` | Pass | 9 tests run: 9 passed |
| `cargo nextest run -p chatui-ui chat::view::tests` | Pass | 14 tests run: 14 passed |
| `cargo nextest run -p chatui-ui conversations::view::tests` | Pass | 15 tests run: 15 passed |
| `cargo nextest run -p chatui-ui providers::view::tests` | Pass | 8 tests run: 8 passed |
| `cargo nextest run -p chatui-ui settings::view::tests` | Pass | 10 tests run: 10 passed |
| `cargo nextest run -p chatui-ui data::view::tests` | Pass | 7 tests run: 7 passed |
| `cargo nextest run -p chatui-ui models::view::tests` | Pass | 7 tests run: 7 passed |

The new integration test is
`chatui-ui accessibility_tests::every_destination_hosts_a_real_feature_view`.
It builds `ShellWindow` with the real `MessageListWindow`, `ComposerWindow`,
`HistoryWindow`, `ProviderManagementWindow`, `SettingsWindow`, `DataWindow`, and
`ModelManagementWindow` as factories, navigates all five destinations, and
asserts a control that only exists in the real view is present. It also rejects
the retired placeholder strings in the accessibility tree and checks the Models
overlay's search and close controls.

## 4. Accessibility checks

| Surface | Check | Result | Evidence |
| --- | --- | --- | --- |
| Navigation | Every destination button has an accessible name in both layouts | Pass | `accessibility_tests::shell_controls_have_accessible_names_in_both_layouts`, `shell::tests::slint_window_exposes_accessible_names_for_navigation`, `shell::tests::every_slint_button_has_an_accessible_name` |
| Navigation | Every destination is reachable by keyboard | Pass | `accessibility::tests::every_destination_is_reachable_by_keyboard`, `shell::tests::every_destination_is_reachable_in_both_layouts` |
| Feature controls | Settings, Data, Providers, Models, and Onboarding controls have names | Pass | `accessibility_tests::settings_controls_have_accessible_names`, `data_controls_have_accessible_names`, `provider_controls_have_accessible_names`, `model_controls_have_accessible_names`, `onboarding_controls_have_accessible_names` |
| Composer | Message editor and actions have names | Pass | `accessibility_tests::composer_controls_have_accessible_names` |
| Message list | Streaming announcement is a bounded polite live region | Pass | `accessibility_tests::message_list_announcement_is_a_bounded_polite_live_region`, `chat::announcement::tests::*` |
| Overlays | Models overlay renders real search and close controls | Pass | `accessibility_tests::every_destination_hosts_a_real_feature_view`, `shell::tests::models_overlay_opens_and_closes` |

No control was found without an accessible name, so no extended assertion was
needed beyond the new destination-hosting test.

## 5. Placeholder-copy search

Command:

```bash
grep -rn --exclude-dir=target --exclude-dir=.git \
  "arrives in M3.5\|placeholder\. The\|page-body\|Chat placeholder\|History placeholder\|Providers placeholder\|Settings placeholder\|Data placeholder" \
  rust/
```

Result: two matches, neither is rendered copy.

- `rust/crates/chatui-ui/src/accessibility_tests.rs:235` is the new regression
  assertion that rejects the retired strings.
- `rust/crates/chatui-providers/src/lib.rs:4` is a module doc comment about
  provider request logic, not UI copy.

`grep -rn "placeholder" rust/crates/chatui-ui/src/shell.rs
rust/apps/desktop/src/main.rs` returned no matches. The shell no longer has a
`page-body` property or a placeholder body path.

The Data and Settings backup sections still show the existing M5
"backup is not available in this release" notice. That is a feature-availability
notice recorded by `M3.6-06.1`, not a destination placeholder.

## 6. Secret-handling check

| Check | Result | Evidence |
| --- | --- | --- |
| No key in logs or debug output | Pass | `chatui-secrets` redaction and panic tests in the workspace run; `providers::error::tests::key_is_redacted_from_details`; `providers::state::tests::form_debug_redacts_the_key` |
| No key in exports | Pass | `services::data::tests::export_all_includes_entities_and_excludes_secrets`; `data::service::tests::export_all_includes_entities_and_excludes_secrets`; `data::state::tests::export_defaults_exclude_attachment_bytes`; `data::state::tests::markdown_conversation_export_never_includes_attachment_bytes` |
| No key in UI metadata | Pass | `settings::tests::settings_view_model_does_not_expose_secrets`; `providers::state::tests::edit_form_never_loads_the_stored_key`; `providers::service::tests::list_reports_missing_key_without_reading_it`; `services::providers::tests::key_presence_is_reported_without_disclosing_the_key` |
| Smoke does not touch real secrets | Pass | `--smoke` uses `session_only_secret_store()` and a temporary profile |

Targeted secret tests: 3 desktop tests and 5 UI tests passed. The full workspace
run also passed the `chatui-secrets` redaction, panic, and zeroization suites.

## 7. Performance observations

The smoke was timed three times with `/usr/bin/time`:

| Run | Wall time |
| --- | --- |
| 1 | 3.39 s |
| 2 | 3.00 s |
| 3 | 3.14 s |

The smoke has eight timer steps at 300 ms each (2.4 s), so startup, window
creation, and teardown account for roughly 0.6-1.0 s. Destination switching is
driven by the same 300 ms timer; no per-switch instrumentation exists, and no
slow switch was observed. These are observations only; this task does not
optimize performance.

## 8. Blockers and limitations

No product blocker was found. The following limitations are recorded honestly:

| ID | Limitation | Owner | Next action |
| --- | --- | --- | --- |
| L-01 | Manual pointer/keyboard exercise of every action was not performed in this unattended session. The actions are covered by the named automated tests and the smoke navigation. | Verification/QA | Repeat the manual pass on an interactive desktop session before release. |
| L-02 | Screen-reader passes remain blocked by the M3.5 Orca/schema issue (G-04) and the missing Windows/macOS hosts (B-01, B-02). This task claims app-owned accessibility tree checks only. | Accessibility/OS owner | Re-run on compatible hosts before claiming a screen-reader pass. |
| L-03 | The Data/Settings backup sections still show the M5 "backup is not available in this release" notice. | M5 backup owner | Replace the notice when backup ships. |
| L-04 | Performance evidence is limited to smoke wall time; there is no per-destination timing instrumentation. | Verification/QA | Add instrumentation if destination-switch budgets become a release gate. |

## 9. Handoff notes

- The integration test is
  `chatui-ui accessibility_tests::every_destination_hosts_a_real_feature_view`
  in `rust/crates/chatui-ui/src/accessibility_tests.rs`.
- The desktop smoke remains the end-to-end composition check:
  `cargo run -p chatui-desktop -- --smoke --no-single-instance`.
- No destination renders placeholder copy. The only remaining "backup is not
  available" copy is the M5 feature notice recorded by `M3.6-06.1`.
- No product behavior was changed by this task. The only code change is the
  regression test.
