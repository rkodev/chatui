# M3.6 Desktop View Integration Plan

- Milestone: M3.6
- Status: Not started
- Source: findings from the M3.5 shell review plus `docs/operations/M3.5/accessibility.md`
- Scope: Rust desktop (`chatui-desktop`, `chatui-ui`, desktop service adapters)
- Platforms: Linux, macOS, Windows (Linux is the first verification target)
- Inputs: [M3.5 desktop plan](../M3.5-rust-desktop-implementation-plan.md), [M3.5 desktop integration](../M3.5/M3.5-10.3-desktop-integration.md), [M3.5 accessibility](../M3.5/accessibility.md)

## 1. Problem statement

The desktop app renders placeholder copy for every primary destination and never
constructs the feature UIs:

- `Destination::placeholder()` returns strings such as
  `"Chat placeholder. The chat surface arrives in M3.5-09."`
  (`rust/crates/chatui-ui/src/shell.rs`).
- `ShellWindow`/`MainContent` only render `page-title`, `page-body`, and
  `notice`; `sync_window` pushes the placeholder into `page-body`.
- `chatui-desktop` creates only `ShellController` and wires menus, shortcuts,
  deep links, and window state. It never instantiates
  `ProviderManagementController`, `SettingsController`, `DataController`,
  `ModelManagementController`, `OnboardingController`, `ComposerController`, or
  `MessageListController`.
- Those controllers exist and are tested, but only inside `chatui-ui` tests.
- Chat and History have no Slint view. Chat has separate `ComposerWindow` and
  `MessageListWindow` components. History has only the async view model.
- No production adapters connect `chatui-storage`, `chatui-providers`, or
  `chatui-secrets` to the synchronous UI service traits. Every UI trait
  implementation is an in-test fake or an in-memory service.
- UI service traits are synchronous while storage repositories are async. Chat
  and History already use the async storage traits directly.

This is a known, documented boundary: `docs/operations/M3.5/accessibility.md`
records that "The desktop binary currently composes the shell only."

## 2. Target architecture

Embed the existing feature windows into `ShellWindow` with Slint's
`ComponentContainer` and `ComponentFactory`. This reuses the ~240 existing
feature properties and callbacks and avoids moving the inline `slint!` blocks
into `.slint` files.

```text
ShellWindow (Window: MenuBar, sidebar, drawer, shortcuts, notices)
└─ MainContent
   ├─ Chat              -> ComponentContainer(messages) + ComponentContainer(composer)
   ├─ History           -> ComponentContainer(history)
   ├─ Providers         -> ComponentContainer(providers)
   ├─ Settings          -> ComponentContainer(settings)
   └─ Data              -> ComponentContainer(data)
   overlays             -> ComponentContainer(models), onboarding view
```

Controllers keep owning their own window handle. A factory embeds that same
handle into the container, so `controller.sync()` continues to update the
embedded item tree.

**Required spike:** `ComponentContainer` explicitly handles `WindowItem` roots,
but embedding a component created with `XWindow::new()` must be proven before
production work. If it fails, the fallback is to convert each `XWindow` into a
`Rectangle` view and expose its properties on `ShellWindow`.

## 3. Production adapter matrix

| UI trait | Backing implementation | Notes |
| --- | --- | --- |
| `providers::ProviderStore` | `SqliteProviderRepository` | synchronous wrapper |
| `providers::ModelStore` | `SqliteModelRepository` | |
| `providers::ConversationStore` | `SqliteConversationRepository` | |
| `providers::SettingsStore` | `SqliteSettingsRepository` | |
| `providers::ProviderKeyValidator` | `chatui-providers` per-kind `validate_key` | network |
| `models::ModelRepository` | `SqliteModelRepository` | |
| `models::ProviderRepository` | `SqliteProviderRepository` | |
| `models::ModelDiscovery` | `chatui-providers` catalog/discovery | network |
| `models::SettingsStore` | `SqliteSettingsRepository` | |
| `settings::SettingsStore` | `SqliteSettingsRepository` | synchronous wrapper |
| `settings::ProviderModelCatalog` | provider and model repositories | |
| `data::DataService` | new `SqliteDataService` over `ExportService`, `ImportService`, repositories, `SecretRepository` | usage, export, import, clear, keys |
| `onboarding::CatalogSource` | `chatui-providers` OpenRouter catalog | network |
| `onboarding::KeyValidator` | provider `validate_key` | network |
| `onboarding::ProviderStore` / `ModelStore` / `SettingsStore` | SQLite repositories | |
| Secrets | `chatui-secrets::select_store` | explicit fallback choice required |

## 4. Threading strategy

- One `tokio` multi-thread runtime owned by the desktop app.
- Open the database with `open_default_database`, run migrations and startup
  recovery, then build repositories.
- Synchronous UI services use `runtime.block_on(...)` for local SQLite work.
- Network work (catalog load, key validation, connection test) runs on the
  runtime and posts results back through `upgrade_in_event_loop`; blocking
  network calls on the Slint thread are forbidden.
- Chat streaming runs on the runtime. The live row is read from
  `MemoryStreamingStore` on a short Slint timer (50-100 ms).
- History load, refresh, and search run on worker tasks and marshal bounded
  snapshots back to the Slint thread.

## 5. Delivery phases

1. Prove `ComponentContainer` embedding and record the decision.
2. Build the SQLite runtime, secret selection, and synchronous adapter bridge.
3. Implement settings, provider, and model adapters.
4. Implement the data service adapter.
5. Implement onboarding adapters.
6. Add the destination host containers and factories to the shell.
7. Implement the History view and controller.
8. Implement the Chat view and controller.
9. Compose all services, controllers, navigation, menus, and onboarding in the
   desktop binary.
10. Verify, test, and record evidence.

## 6. Risks and decisions

1. **Embedding support**: `ComponentContainer` is the main architectural risk.
   Task `M3.6-01.1` must resolve it before any UI wiring.
2. **Secret backend**: `select_store` requires an explicit fallback choice. Pick
   keyring-with-notice, session-only, or encrypted-file with a passphrase
   prompt. Persistent keys are blocked until this is recorded.
3. **Blocking network traits**: `ProviderKeyValidator`, `CatalogSource`, and
   `KeyValidator` are synchronous. Keep network calls off the Slint thread; a
   later task may make them async.
4. **Models and onboarding routes**: the shell has five destinations. Models
   opens as an overlay; onboarding is a startup gate. Confirm this during
   `M3.6-03.1`.
5. **Streaming cadence**: polling `MemoryStreamingStore` must not jank the UI.
   Start at 50 ms and tune in `M3.6-05.1`.

## 7. Definition of done

- Every primary destination renders a real, interactive view.
- Provider, model, settings, data, onboarding, chat, and history actions hit
  SQLite and the provider layer, not fakes.
- No placeholder copy is reachable in the normal flow.
- Menus, shortcuts, deep links, and drag-and-drop target the real controllers.
- Formatting, lint, unit tests, component tests, and the desktop smoke pass.
- `verification.md` records commands, results, and known blockers.
