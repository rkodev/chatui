//! Desktop entry point and integration wiring.
//!
//! The shell and feature view models live in `chatui-ui`. This binary owns the
//! desktop-only integration layer: native menus, shortcuts, window state,
//! dialogs, clipboard, drag-and-drop, system theme, deep links, and
//! single-instance forwarding.
//!
//! The binary also composes the production services and controllers:
//!
//! * one [`ServiceRuntime`] owns the database, repositories, and secret store;
//! * onboarding gates the shell when `Settings::onboarding_completed` is false;
//! * feature controllers are created lazily on first navigation and reused;
//! * menu, shortcut, and deep-link commands call the real controllers.
//!
//! `--smoke` opens the real window against a temporary profile, visits every
//! destination, and exits so unattended verification can exercise the
//! composition. `--print-menu`, `--print-shortcuts`, and `--copy-test` are
//! headless smoke helpers.

// The integration modules expose a small tested API. Some helpers are used only
// by tests or by platform-specific paths, so dead-code analysis is not useful
// for this binary crate.
#![allow(dead_code)]

mod clipboard;
mod deep_link;
mod dialogs;
mod menu;
mod services;
mod shortcuts;
mod single_instance;
mod updater;
mod window_state;

use std::cell::{Cell, RefCell};
use std::error::Error;
use std::future::Future;
use std::path::{Path, PathBuf};
use std::pin::Pin;
use std::rc::Rc;
use std::sync::Arc;
use std::sync::mpsc::{self, Receiver, Sender};
use std::time::Duration;

use chatui_contracts::{AppConfig, AppConfigError, provider_catalog};
use chatui_domain::{ChatError, Conversation, Model, ProviderAuthType, Settings};
use chatui_providers::{HttpClient, NetworkError};
use chatui_secrets::SecretStore;
use chatui_storage::{ConversationFilter, SearchRepository, SqliteSearchRepository, StorageError};
use chatui_ui::accessibility::Modifiers;
use chatui_ui::chat::runner::DEFAULT_PERSIST_INTERVAL;
use chatui_ui::chat::{
    AttachmentFileInfo, ChatController, ChatControllerDeps, ChatRunner, ChatRunnerOptions,
    ChatRuntime, ChatService, ComposerSend, DraftStore, MemoryStreamingStore, MessageListConfig,
    PersistentDraftStore, ProviderChatAdapter, SettingsDraftPersistence, StreamingStore,
    SystemClock, store_pending_attachment,
};
use chatui_ui::conversations::{
    ConversationFactory, ConversationHistory, ConversationSearchService,
    DefaultConversationFactory, HistoryConfig, HistoryController, HistoryError, HistoryRuntime,
};
use chatui_ui::data::{
    DataChange, DataController, DataOperation, DataSection, DataService, ExportArtifact,
};
use chatui_ui::models::{ModelManagementController, ModelManagementService};
use chatui_ui::onboarding::{OnboardingController, OnboardingRoute};
use chatui_ui::providers::{ProviderManagementController, ProviderManagementService};
use chatui_ui::settings::{
    APP_NAME, APP_VERSION, DOCS_URL, SettingsController, SettingsError, SettingsRoute,
    SettingsService, SettingsStore as SettingsStoreTrait,
};
use chatui_ui::shell::{Destination, ShellController, ShellState, ShellWindow, sync_window};
use chatui_ui::theme::{ReducedMotion, ThemeSettings};
use slint::{ComponentFactory, ComponentHandle, ModelRc, VecModel};

use clipboard::{ClipboardKind, ClipboardPayload, ClipboardService, NativeClipboard, copy_payload};
use deep_link::{DeepLinkRoute, DeepLinkState};
use dialogs::{
    AttachmentDropSummary, DialogService, NativeDialogService, attachment_info, open_external_url,
};
use menu::{Command, CommandOutcome, MenuBar};
use services::backup::BackupServiceAdapter;
use services::data::SqliteDataService;
use services::models::{
    ModelRepository as ModelRepositoryAdapter, ProviderModelDiscovery,
    ProviderRepository as ModelProviderRepository, SettingsStore as ModelSettingsStore,
};
use services::onboarding::build_onboarding_service;
use services::providers::{
    ConversationStore as ProviderConversationStore, ModelStore as ProviderModelStore,
    ProviderKeyValidator, ProviderStore as ProviderStoreAdapter,
    SettingsStore as ProviderSettingsStore,
};
use services::settings::{
    ProviderModelCatalog as SettingsCatalog, SettingsStore as SettingsStoreAdapter,
};
use services::{
    SecretStoreError, ServiceRuntime, ServiceRuntimeError, SyncBridge, default_secret_store,
    session_only_secret_store,
};
use shortcuts::{ShortcutAction, ShortcutContext, ShortcutKey, ShortcutMap};
use single_instance::{InstanceGuard, SingleInstance, SingleInstanceError};
use window_state::{
    DEFAULT_WIDTH, FileWindowStateStore, MemoryWindowStateStore, NativeSystemThemeProbe,
    SystemThemeProbe, WindowStateController,
};

#[cfg(unix)]
type PlatformSingleInstance = single_instance::UnixSingleInstance;
#[cfg(not(unix))]
type PlatformSingleInstance = single_instance::NoopSingleInstance;

fn main() -> Result<(), Box<dyn Error>> {
    let args: Vec<String> = std::env::args().skip(1).collect();

    if args.iter().any(|arg| arg == "--print-menu") {
        print_menu();
        return Ok(());
    }
    if args.iter().any(|arg| arg == "--print-shortcuts") {
        print_shortcuts();
        return Ok(());
    }
    if args.iter().any(|arg| arg == "--copy-test") {
        NativeClipboard.copy_text("ChatUI clipboard smoke test")?;
        println!("Copied clipboard smoke text.");
        return Ok(());
    }

    let smoke = args.iter().any(|arg| arg == "--smoke");
    let deep_link = deep_link_arg(&args);
    let single_instance = if args.iter().any(|arg| arg == "--no-single-instance") {
        None
    } else {
        Some(platform_single_instance())
    };

    let guard = match &single_instance {
        Some(single) => match single.acquire() {
            Ok(guard) => Some(guard),
            Err(SingleInstanceError::AlreadyRunning) => {
                let message = deep_link.as_deref().unwrap_or("focus");
                let _ = single.forward(message);
                return Ok(());
            }
            Err(SingleInstanceError::Unsupported) => None,
            Err(error) => return Err(Box::new(error)),
        },
        None => None,
    };

    let window_state = WindowStateController::new(if smoke {
        Box::new(MemoryWindowStateStore::new())
    } else {
        Box::new(FileWindowStateStore::new(
            FileWindowStateStore::default_path(),
        ))
    });
    let saved = window_state.load();
    let mut state = ShellState::new(saved.map(|state| state.width).unwrap_or(DEFAULT_WIDTH));
    let theme = NativeSystemThemeProbe.probe();
    state.system_is_dark = theme.dark;
    state.system_reduced_motion = theme.reduced_motion;

    let mut services = match DesktopServices::open(smoke) {
        Ok(services) => services,
        Err(error) => return show_startup_error(&error, smoke),
    };

    state.theme = ThemeSettings::new(
        services.settings.theme,
        services.settings.density,
        services.settings.chat_font_size,
        ReducedMotion::System,
    );

    let onboarding_dark = state.resolved_theme().is_dark();
    let mut onboarding_complete = services.settings.onboarding_completed;
    if !onboarding_complete && !smoke {
        match run_onboarding(&services, onboarding_dark) {
            Ok(true) => {
                onboarding_complete = true;
                services.settings = match services.runtime.settings_store().load() {
                    Ok(settings) => settings,
                    Err(error) => return show_startup_error(&StartupError::Settings(error), smoke),
                };
            }
            Ok(false) => return Ok(()),
            Err(error) => return show_startup_error(&error, smoke),
        }
    }

    state.theme = ThemeSettings::new(
        services.settings.theme,
        services.settings.density,
        services.settings.chat_font_size,
        ReducedMotion::System,
    );

    let controller = ShellController::new(state)?;
    if let Some(saved) = saved {
        saved.apply_to_window(controller.window().window());
    }

    let shortcut_map = ShortcutMap::default();
    let shortcut_lines: Vec<slint::SharedString> = shortcut_map
        .help_lines()
        .into_iter()
        .map(Into::into)
        .collect();
    controller
        .window()
        .set_shortcut_lines(ModelRc::new(VecModel::from(shortcut_lines)));

    let composition = match DesktopComposition::new(
        services,
        controller.state_handle(),
        controller.window().as_weak(),
        window_state,
        onboarding_complete || smoke,
    ) {
        Ok(composition) => Rc::new(composition),
        Err(error) => return show_startup_error(&error, smoke),
    };

    // The shell starts on Chat, so create and sync that controller immediately.
    composition.ensure_and_sync(controller.window(), Destination::Chat);

    wire_menu(&controller, &composition);
    wire_drop(&controller, &composition);
    wire_close(&controller, &composition);
    wire_navigation(&controller, &composition);

    if let Some(link) = deep_link.as_deref() {
        composition.handle_deep_link(controller.window(), link);
    }

    let mut timers = Vec::new();
    if let Some(guard) = guard {
        timers.push(wire_single_instance(&controller, &composition, guard));
    }
    timers.push(wire_system_theme(&controller, &composition));
    timers.push(wire_poll(&controller, &composition));
    timers.push(wire_draft_flush(&controller, &composition));

    if smoke {
        timers.push(wire_smoke(&controller, &composition));
    }

    let result = controller.run();
    composition.save_window_state(controller.window());
    drop(timers);
    result?;
    Ok(())
}

fn platform_single_instance() -> PlatformSingleInstance {
    #[cfg(unix)]
    {
        PlatformSingleInstance::new(PlatformSingleInstance::default_path())
    }
    #[cfg(not(unix))]
    {
        single_instance::NoopSingleInstance
    }
}

fn deep_link_arg(args: &[String]) -> Option<String> {
    for (index, arg) in args.iter().enumerate() {
        if arg == "--deep-link" {
            return args.get(index + 1).cloned();
        }
        if let Some(value) = arg.strip_prefix("--deep-link=") {
            return Some(value.to_owned());
        }
        if arg.starts_with("chatui://") {
            return Some(arg.clone());
        }
    }
    None
}

fn print_menu() {
    let bar = MenuBar::default_menu_bar();
    for menu in bar.menus() {
        println!("{}", menu.title);
        for entry in &menu.entries {
            match entry.shortcut {
                Some(shortcut) => println!(
                    "  {} ({}) -> {}",
                    entry.label,
                    shortcut.label(),
                    entry.command.id()
                ),
                None => println!("  {} -> {}", entry.label, entry.command.id()),
            }
        }
    }
}

fn print_shortcuts() {
    for line in ShortcutMap::default().help_lines() {
        println!("{line}");
    }
}

// ---------------------------------------------------------------------------
// Startup services and onboarding gate
// ---------------------------------------------------------------------------

/// Which top-level window the app should show first.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum StartupRoute {
    /// First-run onboarding.
    Onboarding,
    /// The composed shell.
    Shell,
}

fn startup_route(onboarding_completed: bool) -> StartupRoute {
    if onboarding_completed {
        StartupRoute::Shell
    } else {
        StartupRoute::Onboarding
    }
}

/// A user-safe startup failure.
#[derive(Debug)]
enum StartupError {
    Runtime(ServiceRuntimeError),
    Settings(SettingsError),
    Secret(SecretStoreError),
    Chat(Box<ChatError>),
    Storage(StorageError),
    Platform(slint::PlatformError),
    Config(AppConfigError),
    Network(NetworkError),
    Onboarding(Box<chatui_ui::onboarding::OnboardingError>),
    Message(String),
}

impl StartupError {
    /// Plain-language copy safe to show in the startup window.
    fn user_message(&self) -> String {
        match self {
            Self::Runtime(_) => {
                "ChatUI could not open its local database. Check that the app-data directory is writable and try again."
                    .to_owned()
            }
            Self::Settings(_) => {
                "ChatUI could not load its settings. Check that the local database is readable and try again."
                    .to_owned()
            }
            Self::Secret(error) => error.message().to_owned(),
            Self::Chat(_) => {
                "ChatUI could not prepare the chat service. Check your provider configuration and try again."
                    .to_owned()
            }
            Self::Storage(_) => {
                "ChatUI could not read local data. Check that the local database is readable and try again."
                    .to_owned()
            }
            Self::Platform(error) => format!("ChatUI could not create a window: {error}"),
            Self::Config(_) => "ChatUI has an invalid application configuration.".to_owned(),
            Self::Network(_) => "ChatUI could not prepare the network client.".to_owned(),
            Self::Onboarding(_) => {
                "ChatUI could not start onboarding. Check your connection and try again.".to_owned()
            }
            Self::Message(message) => message.clone(),
        }
    }
}

impl std::fmt::Display for StartupError {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        formatter.write_str(&self.user_message())
    }
}

impl Error for StartupError {}

impl From<ServiceRuntimeError> for StartupError {
    fn from(error: ServiceRuntimeError) -> Self {
        Self::Runtime(error)
    }
}

impl From<SettingsError> for StartupError {
    fn from(error: SettingsError) -> Self {
        Self::Settings(error)
    }
}

impl From<SecretStoreError> for StartupError {
    fn from(error: SecretStoreError) -> Self {
        Self::Secret(error)
    }
}

impl From<ChatError> for StartupError {
    fn from(error: ChatError) -> Self {
        Self::Chat(Box::new(error))
    }
}

impl From<StorageError> for StartupError {
    fn from(error: StorageError) -> Self {
        Self::Storage(error)
    }
}

impl From<slint::PlatformError> for StartupError {
    fn from(error: slint::PlatformError) -> Self {
        Self::Platform(error)
    }
}

impl From<AppConfigError> for StartupError {
    fn from(error: AppConfigError) -> Self {
        Self::Config(error)
    }
}

impl From<NetworkError> for StartupError {
    fn from(error: NetworkError) -> Self {
        Self::Network(error)
    }
}

impl From<chatui_ui::onboarding::OnboardingError> for StartupError {
    fn from(error: chatui_ui::onboarding::OnboardingError) -> Self {
        Self::Onboarding(Box::new(error))
    }
}

/// A temporary profile used by `--smoke` so the real user database is untouched.
struct TempProfile {
    directory: PathBuf,
}

impl TempProfile {
    fn new() -> Result<Self, StartupError> {
        let unique = format!(
            "chatui-smoke-{}-{}",
            std::process::id(),
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .map(|duration| duration.as_nanos())
                .unwrap_or_default()
        );
        let directory = std::env::temp_dir().join(unique);
        std::fs::create_dir_all(&directory).map_err(|error| {
            StartupError::Message(format!("Could not create the smoke profile: {error}"))
        })?;
        Ok(Self { directory })
    }

    fn database_path(&self) -> PathBuf {
        self.directory.join("chatui.sqlite3")
    }

    fn app_data_dir(&self) -> PathBuf {
        self.directory.clone()
    }
}

impl Drop for TempProfile {
    fn drop(&mut self) {
        let _ = std::fs::remove_dir_all(&self.directory);
    }
}

/// The shared runtime, selected secret store, and persisted settings.
struct DesktopServices {
    runtime: ServiceRuntime,
    secrets: Arc<dyn SecretStore>,
    settings: Settings,
    _profile: Option<TempProfile>,
}

impl DesktopServices {
    fn open(smoke: bool) -> Result<Self, StartupError> {
        let (runtime, profile) = if smoke {
            let profile = TempProfile::new()?;
            let runtime = ServiceRuntime::open_at(profile.database_path(), profile.app_data_dir())?;
            (runtime, Some(profile))
        } else {
            (ServiceRuntime::open()?, None)
        };

        let secrets: Arc<dyn SecretStore> = if smoke {
            session_only_secret_store()
        } else {
            default_secret_store()?
        };

        let mut settings = runtime.settings_store().load()?;
        if smoke && !settings.onboarding_completed {
            settings.onboarding_completed = true;
            runtime.settings_store().save(&settings)?;
        }

        Ok(Self {
            runtime,
            secrets,
            settings,
            _profile: profile,
        })
    }
}

fn run_onboarding(services: &DesktopServices, dark: bool) -> Result<bool, StartupError> {
    let preset = provider_catalog()
        .get("openrouter")
        .cloned()
        .ok_or_else(|| StartupError::Message("The OpenRouter preset is missing.".to_owned()))?;
    let service = build_onboarding_service(&services.runtime, &preset, services.secrets.clone())?;
    let controller = OnboardingController::new_background(Arc::new(service), &preset)?;
    controller.window().set_dark(dark);
    let portal_window = controller.window().as_weak();
    controller.window().on_open_key_portal(move || {
        if let Some(window) = portal_window.upgrade() {
            window.set_link_error(
                open_external_url("https://openrouter.ai/keys")
                    .err()
                    .map(|error| error.to_string())
                    .unwrap_or_default()
                    .into(),
            );
        }
    });

    if controller.route() == OnboardingRoute::Shell {
        return Ok(true);
    }

    let weak = controller.window().as_weak();
    let timer = slint::Timer::default();
    timer.start(
        slint::TimerMode::Repeated,
        Duration::from_millis(100),
        move || {
            if let Some(window) = weak.upgrade()
                && window.get_continue_requested()
            {
                let _ = slint::quit_event_loop();
            }
        },
    );

    controller.window().run()?;
    drop(timer);

    let completed = controller.route() == OnboardingRoute::Shell
        && controller.window().get_continue_requested();
    if completed {
        let store = services.runtime.settings_store();
        let mut settings = store.load()?;
        if !settings.onboarding_completed {
            settings.onboarding_completed = true;
            store.save(&settings)?;
        }
    }
    Ok(completed)
}

fn show_startup_error(error: &StartupError, smoke: bool) -> Result<(), Box<dyn Error>> {
    eprintln!("ChatUI could not start: {error:?}");
    let controller = ShellController::new(ShellState::new(DEFAULT_WIDTH))?;
    controller
        .window()
        .set_notice(format!("ChatUI could not start: {error}").into());

    if smoke {
        let weak = controller.window().as_weak();
        slint::Timer::single_shot(Duration::from_millis(500), move || {
            let _ = weak.upgrade();
            let _ = slint::quit_event_loop();
        });
    }

    controller.run()?;
    Ok(())
}

// ---------------------------------------------------------------------------
// Runtime adapters
// ---------------------------------------------------------------------------

/// Spawns controller work on the shared desktop runtime.
///
/// The controllers call this from the Slint thread. The bridge runs a tiny
/// future that hands the real work to `tokio::spawn`, so the network and
/// storage work never runs on the Slint thread.
#[derive(Clone)]
struct RuntimeSpawner {
    bridge: SyncBridge,
}

impl RuntimeSpawner {
    fn new(runtime: &ServiceRuntime) -> Self {
        Self {
            bridge: runtime.bridge(),
        }
    }

    fn spawn_future(&self, future: Pin<Box<dyn Future<Output = ()> + Send + 'static>>) {
        self.bridge.block_on(async move {
            tokio::spawn(future);
        });
    }
}

impl ChatRuntime for RuntimeSpawner {
    fn spawn(&self, future: Pin<Box<dyn Future<Output = ()> + Send + 'static>>) {
        self.spawn_future(future);
    }
}

impl HistoryRuntime for RuntimeSpawner {
    fn spawn(&self, future: Pin<Box<dyn Future<Output = ()> + Send + 'static>>) {
        self.spawn_future(future);
    }
}

// ---------------------------------------------------------------------------
// Composition
// ---------------------------------------------------------------------------

enum DesktopEvent {
    ChatCreated {
        request: u64,
        result: Result<Box<Conversation>, HistoryError>,
    },
    CancelledChatRemoved {
        result: Result<bool, StorageError>,
    },
    ModelChanged {
        conversation_id: String,
        result: Result<(), StorageError>,
    },
}

/// Owns the production services and the lazily created feature controllers.
struct DesktopComposition {
    services: DesktopServices,
    shell_state: Rc<RefCell<ShellState>>,
    shell: slint::Weak<ShellWindow>,
    window_state: WindowStateController,
    clipboard: NativeClipboard,
    dialogs: Rc<dyn DialogService>,
    shortcut_map: ShortcutMap,
    menu_bar: MenuBar,
    deep_links: RefCell<DeepLinkState>,
    onboarding_complete: bool,
    drafts: Arc<PersistentDraftStore>,
    data_service: Arc<dyn DataService>,
    chat: RefCell<Option<Rc<ChatController>>>,
    history: RefCell<Option<Rc<HistoryController>>>,
    providers: RefCell<Option<Rc<ProviderManagementController>>>,
    settings: RefCell<Option<Rc<SettingsController>>>,
    data: RefCell<Option<Rc<DataController>>>,
    models: RefCell<Option<Rc<ModelManagementController>>>,
    applied_settings: RefCell<Option<Settings>>,
    event_sender: Sender<DesktopEvent>,
    events: RefCell<Receiver<DesktopEvent>>,
    chat_request: Cell<u64>,
    chat_creation_jobs: Cell<usize>,
    pending_first_send: RefCell<Option<ComposerSend>>,
    model_change_pending: Cell<bool>,
}

impl DesktopComposition {
    fn new(
        services: DesktopServices,
        shell_state: Rc<RefCell<ShellState>>,
        shell: slint::Weak<ShellWindow>,
        window_state: WindowStateController,
        onboarding_complete: bool,
    ) -> Result<Self, StartupError> {
        let drafts = Arc::new(PersistentDraftStore::new(Arc::new(
            SettingsDraftPersistence::new(services.runtime.settings()),
        )));
        services.runtime.block_on(drafts.load())?;

        let data_service: Arc<dyn DataService> =
            Arc::new(SqliteDataService::from_runtime(&services.runtime));
        let (event_sender, events) = mpsc::channel();

        Ok(Self {
            services,
            shell_state,
            shell,
            window_state,
            clipboard: NativeClipboard,
            dialogs: Rc::new(NativeDialogService),
            shortcut_map: ShortcutMap::default(),
            menu_bar: MenuBar::default_menu_bar(),
            deep_links: RefCell::new(DeepLinkState::new()),
            onboarding_complete,
            drafts,
            data_service,
            chat: RefCell::new(None),
            history: RefCell::new(None),
            providers: RefCell::new(None),
            settings: RefCell::new(None),
            data: RefCell::new(None),
            models: RefCell::new(None),
            applied_settings: RefCell::new(None),
            event_sender,
            events: RefCell::new(events),
            chat_request: Cell::new(0),
            chat_creation_jobs: Cell::new(0),
            pending_first_send: RefCell::new(None),
            model_change_pending: Cell::new(false),
        })
    }

    fn ensure_controller(self: &Rc<Self>, window: &ShellWindow, destination: Destination) {
        match destination {
            Destination::Chat => self.ensure_chat(window),
            Destination::History => self.ensure_history(window),
            Destination::Providers => self.ensure_providers(window),
            Destination::Settings => self.ensure_settings(window),
            Destination::Data => self.ensure_data(window),
        }
    }

    fn sync_controller(&self, destination: Destination) {
        match destination {
            Destination::Chat => {
                if let Some(chat) = self.chat.borrow().as_ref() {
                    chat.sync();
                }
            }
            Destination::History => {
                if let Some(history) = self.history.borrow().as_ref() {
                    history.sync();
                }
            }
            Destination::Providers => {
                if let Some(providers) = self.providers.borrow().as_ref() {
                    providers.sync();
                }
            }
            Destination::Settings => {
                if let Some(settings) = self.settings.borrow().as_ref() {
                    settings.sync();
                }
            }
            Destination::Data => {
                if let Some(data) = self.data.borrow().as_ref() {
                    data.sync();
                }
            }
        }
    }

    fn ensure_and_sync(self: &Rc<Self>, window: &ShellWindow, destination: Destination) {
        self.ensure_controller(window, destination);
        self.sync_controller(destination);
    }

    fn feature_dark(&self) -> bool {
        self.shell_state.borrow().resolved_theme().is_dark()
    }

    fn apply_theme_to_controllers(&self) {
        let dark = self.feature_dark();

        if let Some(chat) = self.chat.borrow().as_ref() {
            chat.set_dark(dark);
            chat.set_chat_font_size(self.shell_state.borrow().chat_typography().size);
        }
        if let Some(history) = self.history.borrow().as_ref() {
            history.window().set_dark(dark);
        }
        if let Some(providers) = self.providers.borrow().as_ref() {
            providers.window().set_dark(dark);
        }
        if let Some(settings) = self.settings.borrow().as_ref() {
            settings.window().set_dark(dark);
        }
        if let Some(data) = self.data.borrow().as_ref() {
            data.window().set_dark(dark);
        }
        if let Some(models) = self.models.borrow().as_ref() {
            models.window().set_dark(dark);
        }
    }

    fn navigate(self: &Rc<Self>, window: &ShellWindow, destination: Destination) {
        if self.is_data_mutating() {
            self.set_notice("Finishing your local data update. Please wait.");
            return;
        }
        window.set_show_models(false);
        self.shell_state.borrow_mut().navigate(destination);
        sync_window(window, &self.shell_state.borrow());
        self.ensure_and_sync(window, destination);
        if destination == Destination::Chat {
            self.refresh_chat_configuration();
        }
        if destination == Destination::Settings
            && let Some(settings) = self.settings.borrow().as_ref()
        {
            settings.reload();
        }
        if destination == Destination::Providers
            && let Some(providers) = self.providers.borrow().as_ref()
        {
            providers.reload();
        }
        if destination == Destination::Data
            && let Some(data) = self.data.borrow().as_ref()
        {
            data.reload();
            data.set_external_busy(self.data_busy_reason());
            data.sync();
        }
    }

    fn open_conversation(self: &Rc<Self>, window: &ShellWindow, id: &str) {
        if self.is_data_mutating() {
            self.set_notice("Finishing your local data update. Please wait.");
            return;
        }
        self.cancel_first_send();
        self.navigate(window, Destination::Chat);
        if let Some(chat) = self.chat.borrow().as_ref() {
            chat.open(id);
        }
        self.persist_active_conversation(Some(id));
        self.set_notice("");
    }

    fn open_models(self: &Rc<Self>, window: &ShellWindow) {
        if self.is_data_mutating() || self.model_change_pending.get() {
            return;
        }
        self.ensure_models(window);
        if let Some(models) = self.models.borrow().as_ref() {
            models.reload();
            models.set_picker_mode(true);
            if let Some(chat) = self.chat.borrow().as_ref() {
                let model = chat.state().model.clone();
                if let Some(model) = model.as_ref() {
                    models.select_provider(&model.provider_id);
                }
                models.set_selected_model(model.map(|model| model.id));
            }
        }
        window.set_show_models(true);
    }

    fn manage_models(self: &Rc<Self>, window: &ShellWindow) {
        self.open_models(window);
        if let Some(models) = self.models.borrow().as_ref() {
            models.set_picker_mode(false);
        }
    }

    fn persist_active_conversation(&self, id: Option<&str>) {
        let store = self.services.runtime.settings_store();
        if let Ok(mut settings) = store.load() {
            settings.last_active_conversation_id = id.map(str::to_owned);
            if let Err(error) = store.save(&settings) {
                self.set_notice(format!("Could not remember this conversation: {error}"));
            }
        }
    }

    fn set_notice(&self, message: impl Into<String>) {
        if let Some(window) = self.shell.upgrade() {
            window.set_notice(slint::SharedString::from(message.into()));
        }
    }

    // -- Chat ---------------------------------------------------------------

    fn ensure_chat(self: &Rc<Self>, window: &ShellWindow) {
        if self.chat.borrow().is_some() {
            return;
        }

        match self.build_chat() {
            Ok(chat) => {
                let chat = Rc::new(chat);
                let messages = chat.messages_window();
                let composer = chat.composer_window();
                window.set_chat_messages_factory(ComponentFactory::new(move |_| {
                    Some(messages.clone_strong())
                }));
                window.set_chat_composer_factory(ComponentFactory::new(move |_| {
                    Some(composer.clone_strong())
                }));
                chat.set_dark(self.feature_dark());
                self.wire_chat_handlers(&chat);
                *self.chat.borrow_mut() = Some(chat);
                self.refresh_chat_configuration();
            }
            Err(error) => {
                window.set_notice(format!("Could not open chat: {error}").into());
            }
        }
    }

    fn build_chat(&self) -> Result<ChatController, StartupError> {
        let runtime = &self.services.runtime;
        let conversations = runtime.conversations();
        let messages = runtime.messages();
        let providers = runtime.providers();
        let models = runtime.models();
        let secrets = self.services.secrets.clone();

        let store = Arc::new(MemoryStreamingStore::new());
        let client = HttpClient::new()?;
        let adapter = Arc::new(ProviderChatAdapter::new(client));
        let runner = Arc::new(ChatRunner::new(ChatRunnerOptions {
            adapter,
            messages: messages.clone(),
            store: store.clone() as Arc<dyn StreamingStore>,
            clock: Arc::new(SystemClock),
            new_id: Arc::new(chatui_domain::new_id),
            persist_interval: DEFAULT_PERSIST_INTERVAL,
        }));
        let service = ChatService::new(
            conversations.clone(),
            messages.clone(),
            providers,
            models.clone(),
            secrets,
            runner.clone(),
            AppConfig::new(APP_NAME, "https://chatui.example")?,
        );

        let drafts: Arc<dyn DraftStore> = self.drafts.clone();
        let initial_conversation_id = self
            .services
            .settings
            .last_active_conversation_id
            .as_ref()
            .filter(|id| {
                runtime
                    .block_on(conversations.get(id))
                    .ok()
                    .flatten()
                    .is_some_and(|conversation| !conversation.archived)
            })
            .cloned();
        let controller = ChatController::new(ChatControllerDeps {
            service,
            runner,
            store,
            conversations,
            messages,
            models,
            drafts,
            send_key: self.services.settings.send_key,
            runtime: Arc::new(RuntimeSpawner::new(runtime)),
            config: MessageListConfig::default(),
            initial_conversation_id,
        })?;
        controller.set_chat_font_size(self.shell_state.borrow().chat_typography().size);
        controller.set_show_token_usage(self.services.settings.show_token_usage);
        Ok(controller)
    }

    fn wire_chat_handlers(self: &Rc<Self>, chat: &Rc<ChatController>) {
        let weak = Rc::downgrade(self);
        chat.set_on_start_conversation(move |send| {
            if let Some(composition) = weak.upgrade() {
                composition.create_first_conversation(send);
            }
        });

        let weak = Rc::downgrade(self);
        chat.set_on_cancel_start(move || {
            if let Some(composition) = weak.upgrade() {
                composition.cancel_first_send();
            }
        });

        let weak = Rc::downgrade(self);
        chat.set_on_open_providers(move || {
            if let Some(composition) = weak.upgrade()
                && let Some(window) = composition.shell.upgrade()
            {
                let choose_model = composition
                    .chat
                    .borrow()
                    .as_ref()
                    .is_some_and(|chat| chat.messages_window().get_setup_uses_models());
                if choose_model {
                    composition.open_models(&window);
                } else {
                    composition.navigate(&window, Destination::Providers);
                }
            }
        });

        let weak = Rc::downgrade(self);
        chat.set_on_open_link(move |url| {
            if let Some(composition) = weak.upgrade()
                && let Err(error) = open_external_url(&url)
            {
                composition.set_notice(error.to_string());
            }
        });

        let weak = Rc::downgrade(self);
        chat.set_on_copy(move |text| {
            if let Some(composition) = weak.upgrade() {
                composition.copy_text(&text);
            }
        });

        let weak = Rc::downgrade(self);
        chat.set_on_attach(move || {
            if let Some(composition) = weak.upgrade() {
                composition.attach_from_picker();
            }
        });

        let weak = Rc::downgrade(self);
        chat.set_on_paste_image(move || {
            if let Some(composition) = weak.upgrade() {
                composition.paste_image();
            }
        });

        let weak = Rc::downgrade(self);
        chat.set_on_remove_attachment(move |id| {
            if let Some(composition) = weak.upgrade()
                && let Some(chat) = composition.chat.borrow().as_ref()
            {
                chat.remove_attachment(&id);
            }
        });
    }

    // -- History ------------------------------------------------------------

    fn ensure_history(self: &Rc<Self>, window: &ShellWindow) {
        if self.history.borrow().is_some() {
            return;
        }

        match self.build_history(window) {
            Ok(history) => {
                let history = Rc::new(history);
                history.window().set_dark(self.feature_dark());
                let handle = history.window().clone_strong();
                window.set_history_factory(ComponentFactory::new(move |_| {
                    Some(handle.clone_strong())
                }));
                *self.history.borrow_mut() = Some(history);
            }
            Err(error) => {
                window.set_notice(format!("Could not open history: {error}").into());
            }
        }
    }

    fn build_history(
        self: &Rc<Self>,
        window: &ShellWindow,
    ) -> Result<HistoryController, StartupError> {
        let runtime = &self.services.runtime;
        let conversations = runtime.conversations();
        let settings = runtime.settings();
        let providers = runtime.providers();
        let models = runtime.models();
        let search: Arc<dyn SearchRepository> =
            Arc::new(SqliteSearchRepository::new(runtime.pool().clone()));

        let factory: Arc<dyn ConversationFactory> = Arc::new(DefaultConversationFactory::new(
            conversations.clone(),
            providers,
            models,
            settings.clone(),
        ));
        let drafts: Arc<dyn DraftStore> = self.drafts.clone();
        let history = ConversationHistory::new(
            conversations.clone(),
            settings,
            drafts,
            factory,
            HistoryConfig::default(),
        );
        let search = ConversationSearchService::new(search, conversations);
        let mut controller =
            HistoryController::new(history, search, Arc::new(RuntimeSpawner::new(runtime)))?;

        let weak = Rc::downgrade(self);
        let window_weak = window.as_weak();
        controller.set_on_open_conversation(move |id| {
            if let (Some(composition), Some(window)) = (weak.upgrade(), window_weak.upgrade()) {
                composition.open_conversation(&window, id);
            }
        });
        let weak = Rc::downgrade(self);
        let window_weak = window.as_weak();
        controller.window().on_new_chat(move || {
            if let (Some(composition), Some(window)) = (weak.upgrade(), window_weak.upgrade()) {
                composition.new_chat(&window);
            }
        });

        Ok(controller)
    }

    // -- Providers ----------------------------------------------------------

    fn ensure_providers(self: &Rc<Self>, window: &ShellWindow) {
        if self.providers.borrow().is_some() {
            return;
        }

        match self.build_providers() {
            Ok(providers) => {
                let providers = Rc::new(providers);
                providers.window().set_dark(self.feature_dark());
                let handle = providers.window().clone_strong();
                window.set_providers_factory(ComponentFactory::new(move |_| {
                    Some(handle.clone_strong())
                }));
                *self.providers.borrow_mut() = Some(providers);
            }
            Err(error) => {
                window.set_notice(format!("Could not open providers: {error}").into());
            }
        }
    }

    fn build_providers(self: &Rc<Self>) -> Result<ProviderManagementController, StartupError> {
        let runtime = &self.services.runtime;
        let providers = Arc::new(ProviderStoreAdapter::from_runtime(runtime));
        let models = Arc::new(ProviderModelStore::from_runtime(runtime));
        let conversations = Arc::new(ProviderConversationStore::from_runtime(runtime));
        let settings = Arc::new(ProviderSettingsStore::from_runtime(runtime));
        let validator = Arc::new(ProviderKeyValidator::from_runtime(runtime)?);
        let service = Arc::new(ProviderManagementService::new(
            providers,
            models,
            conversations,
            settings,
            self.services.secrets.clone(),
            validator,
        ));
        let controller = ProviderManagementController::new_background(service)?;
        let weak = Rc::downgrade(self);
        controller.window().on_open_docs(move |url| {
            if let Some(composition) = weak.upgrade()
                && let Err(error) = open_external_url(&url)
            {
                composition.set_notice(error.to_string());
            }
        });
        let weak = Rc::downgrade(self);
        controller.window().on_open_models(move || {
            if let Some(composition) = weak.upgrade()
                && let Some(window) = composition.shell.upgrade()
            {
                let id = composition
                    .providers
                    .borrow()
                    .as_ref()
                    .and_then(|providers| {
                        providers
                            .state()
                            .detail
                            .as_ref()
                            .map(|detail| detail.id.clone())
                    });
                composition.manage_models(&window);
                if let Some(models) = composition.models.borrow().as_ref() {
                    if let Some(id) = id {
                        models.select_provider(&id);
                    }
                    if models.state().list.total == 0 {
                        models.window().invoke_refresh();
                    }
                }
            }
        });
        Ok(controller)
    }

    // -- Settings -----------------------------------------------------------

    fn ensure_settings(self: &Rc<Self>, window: &ShellWindow) {
        if self.settings.borrow().is_some() {
            return;
        }

        match self.build_settings(window) {
            Ok(settings) => {
                let settings = Rc::new(settings);
                settings.window().set_dark(self.feature_dark());
                let handle = settings.window().clone_strong();
                window.set_settings_factory(ComponentFactory::new(move |_| {
                    Some(handle.clone_strong())
                }));
                *self.applied_settings.borrow_mut() = Some(settings.state().settings.clone());
                *self.settings.borrow_mut() = Some(settings);
            }
            Err(error) => {
                window.set_notice(format!("Could not open settings: {error}").into());
            }
        }
    }

    fn build_settings(
        self: &Rc<Self>,
        window: &ShellWindow,
    ) -> Result<SettingsController, StartupError> {
        let runtime = &self.services.runtime;
        let store = Arc::new(SettingsStoreAdapter::from_runtime(runtime));
        let catalog = Arc::new(SettingsCatalog::from_runtime(runtime));
        let service = Arc::new(SettingsService::new(store, catalog));
        let backup = Arc::new(BackupServiceAdapter::from_runtime(
            runtime,
            self.services.secrets.clone(),
        ));
        let mut controller = SettingsController::with_backup_background(service, backup)?;
        let weak = Rc::downgrade(self);
        controller.set_on_restored(move |replaced| {
            if let Some(composition) = weak.upgrade() {
                composition.refresh_after_data_change(DataChange::Imported { replaced });
            }
        });
        let weak = Rc::downgrade(self);
        controller.window().on_open_docs(move || {
            if let Some(composition) = weak.upgrade()
                && let Some(window) = composition.shell.upgrade()
            {
                composition.open_docs(&window);
            }
        });

        let weak = Rc::downgrade(self);
        let window_weak = window.as_weak();
        controller.set_on_open_providers(move || {
            if let (Some(composition), Some(window)) = (weak.upgrade(), window_weak.upgrade()) {
                composition.navigate(&window, Destination::Providers);
            }
        });

        let weak = Rc::downgrade(self);
        let window_weak = window.as_weak();
        controller.set_on_open_models(move || {
            if let (Some(composition), Some(window)) = (weak.upgrade(), window_weak.upgrade()) {
                composition.manage_models(&window);
            }
        });

        let weak = Rc::downgrade(self);
        let window_weak = window.as_weak();
        controller.set_on_open_data(move || {
            if let (Some(composition), Some(window)) = (weak.upgrade(), window_weak.upgrade()) {
                composition.navigate(&window, Destination::Data);
            }
        });
        let weak = Rc::downgrade(self);
        controller.set_on_can_restore(move || {
            let Some(composition) = weak.upgrade() else {
                return Ok(());
            };
            if composition.is_data_mutating() {
                return Err(
                    "Wait for the local data update to finish before restoring a backup."
                        .to_owned(),
                );
            }
            composition
                .data_busy_reason()
                .map(str::to_owned)
                .map_or(Ok(()), Err)
        });

        Ok(controller)
    }

    // -- Data ---------------------------------------------------------------

    fn ensure_data(self: &Rc<Self>, window: &ShellWindow) {
        if self.data.borrow().is_some() {
            return;
        }

        match self.build_data() {
            Ok(data) => {
                let data = Rc::new(data);
                data.window().set_dark(self.feature_dark());
                let handle = data.window().clone_strong();
                window
                    .set_data_factory(ComponentFactory::new(move |_| Some(handle.clone_strong())));
                *self.data.borrow_mut() = Some(data);
            }
            Err(error) => {
                window.set_notice(format!("Could not open data settings: {error}").into());
            }
        }
    }

    fn build_data(self: &Rc<Self>) -> Result<DataController, StartupError> {
        let mut controller = DataController::new_background(self.data_service.clone())?;
        let weak = Rc::downgrade(self);
        controller.set_on_save_export(move |artifact| {
            let Some(composition) = weak.upgrade() else {
                return Ok(false);
            };
            composition.save_export(artifact)
        });
        let weak = Rc::downgrade(self);
        controller.set_on_choose_import(move || {
            let Some(composition) = weak.upgrade() else {
                return Ok(None);
            };
            let Some(path) = composition.dialogs.pick_import_path() else {
                return Ok(None);
            };
            composition
                .dialogs
                .read_text(&path)
                .map(Some)
                .map_err(|error| error.to_string())
        });
        let weak = Rc::downgrade(self);
        controller.set_on_open_backup(move || {
            if let Some(composition) = weak.upgrade()
                && let Some(window) = composition.shell.upgrade()
            {
                composition.navigate(&window, Destination::Settings);
                if let Some(settings) = composition.settings.borrow().as_ref() {
                    settings.select_route(SettingsRoute::Backup);
                }
            }
        });
        let weak = Rc::downgrade(self);
        controller.set_on_data_changed(move |change| {
            if let Some(composition) = weak.upgrade() {
                composition.refresh_after_data_change(change);
            }
        });
        let weak = Rc::downgrade(self);
        controller.set_on_can_operate(move || {
            weak.upgrade()
                .and_then(|composition| composition.data_busy_reason().map(str::to_owned))
                .map_or(Ok(()), Err)
        });
        Ok(controller)
    }

    // -- Models overlay -----------------------------------------------------

    fn ensure_models(self: &Rc<Self>, window: &ShellWindow) {
        if self.models.borrow().is_some() {
            return;
        }

        match self.build_models() {
            Ok(models) => {
                let models = Rc::new(models);
                models.window().set_dark(self.feature_dark());
                let handle = models.window().clone_strong();
                window.set_models_factory(ComponentFactory::new(move |_| {
                    Some(handle.clone_strong())
                }));
                *self.models.borrow_mut() = Some(models);
            }
            Err(error) => {
                window.set_notice(format!("Could not open models: {error}").into());
            }
        }
    }

    fn build_models(self: &Rc<Self>) -> Result<ModelManagementController, StartupError> {
        let runtime = &self.services.runtime;
        let models = Arc::new(ModelRepositoryAdapter::from_runtime(runtime));
        let providers = Arc::new(ModelProviderRepository::from_runtime(runtime));
        let settings = Arc::new(ModelSettingsStore::from_runtime(runtime));
        let discovery = Arc::new(ProviderModelDiscovery::from_runtime(
            runtime,
            self.services.secrets.clone(),
        )?);
        let service = Arc::new(ModelManagementService::new(
            models, providers, discovery, settings,
        ));
        let controller = ModelManagementController::new_background(service)?;
        let weak = Rc::downgrade(self);
        controller.set_on_selected(move |model| {
            if let Some(composition) = weak.upgrade() {
                let picker = composition
                    .models
                    .borrow()
                    .as_ref()
                    .is_some_and(|models| models.state().picker_mode);
                if picker {
                    composition.select_chat_model(model);
                } else {
                    composition.refresh_chat_configuration();
                }
                if let Some(settings) = composition.settings.borrow().as_ref() {
                    settings.reload();
                }
            }
        });
        let weak = Rc::downgrade(self);
        controller.window().on_open_docs(move |url| {
            if let Some(composition) = weak.upgrade()
                && let Err(error) = open_external_url(&url)
            {
                composition.set_notice(error.to_string());
            }
        });
        let weak = Rc::downgrade(self);
        controller.window().on_open_providers(move || {
            if let Some(composition) = weak.upgrade()
                && let Some(window) = composition.shell.upgrade()
            {
                composition.navigate(&window, Destination::Providers);
            }
        });
        Ok(controller)
    }

    // -- Commands -----------------------------------------------------------

    fn execute(self: &Rc<Self>, window: &ShellWindow, command: Command) -> CommandOutcome {
        if self.is_data_mutating() && !matches!(command, Command::Quit | Command::StopOrClose) {
            self.set_notice("Finishing your local data update. Please wait.");
            return CommandOutcome::Handled;
        }
        match command {
            Command::NewChat => {
                self.new_chat(window);
                CommandOutcome::Handled
            }
            Command::AttachFile => {
                self.attach_from_picker();
                CommandOutcome::Handled
            }
            Command::Export => {
                self.export(window);
                CommandOutcome::Handled
            }
            Command::Import => {
                self.import(window);
                CommandOutcome::Handled
            }
            Command::Quit => {
                let _ = slint::quit_event_loop();
                CommandOutcome::Handled
            }
            Command::Search => {
                self.search(window);
                CommandOutcome::Handled
            }
            Command::CopyLastResponse => {
                self.copy_last_response(window);
                CommandOutcome::Handled
            }
            Command::ToggleSidebar => {
                self.shell_state.borrow_mut().toggle_sidebar();
                sync_window(window, &self.shell_state.borrow());
                CommandOutcome::Handled
            }
            Command::ShortcutsHelp => {
                window.set_shortcuts_help_open(!window.get_shortcuts_help_open());
                CommandOutcome::Handled
            }
            Command::PreviousConversation => {
                self.navigate_conversation(window, -1);
                CommandOutcome::Handled
            }
            Command::NextConversation => {
                self.navigate_conversation(window, 1);
                CommandOutcome::Handled
            }
            Command::StopOrClose => {
                self.stop_or_close(window);
                CommandOutcome::Handled
            }
            Command::OpenDocs => {
                self.open_docs(window);
                CommandOutcome::Handled
            }
            Command::About => {
                self.about(window);
                CommandOutcome::Handled
            }
        }
    }

    fn new_chat(self: &Rc<Self>, window: &ShellWindow) {
        if self.is_data_mutating() {
            return;
        }
        self.cancel_first_send();
        self.navigate(window, Destination::Chat);
        if let Some(chat) = self.chat.borrow().as_ref() {
            chat.clear();
            chat.composer_window().invoke_focus_editor();
        }
        self.persist_active_conversation(None);
        self.refresh_chat_configuration();
        self.set_notice("");
    }

    fn create_first_conversation(self: &Rc<Self>, send: ComposerSend) {
        if self.pending_first_send.borrow().is_some() {
            return;
        }
        let request = self.chat_request.get().wrapping_add(1);
        self.chat_request.set(request);
        self.chat_creation_jobs
            .set(self.chat_creation_jobs.get() + 1);
        *self.pending_first_send.borrow_mut() = Some(send);
        let runtime = &self.services.runtime;
        let factory = DefaultConversationFactory::new(
            runtime.conversations(),
            runtime.providers(),
            runtime.models(),
            runtime.settings(),
        );
        let sender = self.event_sender.clone();
        RuntimeSpawner::new(runtime).spawn_future(Box::pin(async move {
            let result = factory.create(None).await.map(Box::new);
            let _ = sender.send(DesktopEvent::ChatCreated { request, result });
        }));
    }

    fn cancel_first_send(&self) {
        let pending = self.pending_first_send.borrow_mut().take();
        if let Some(send) = pending {
            self.chat_request
                .set(self.chat_request.get().wrapping_add(1));
            if let Some(chat) = self.chat.borrow().as_ref() {
                chat.restore_unsent_message(send, None);
            }
        }
    }

    fn refresh_chat_configuration(&self) {
        let Some(chat) = self.chat.borrow().as_ref().cloned() else {
            return;
        };
        let runtime = &self.services.runtime;
        let Ok(settings) = runtime.settings_store().load() else {
            return;
        };
        let model = settings
            .default_model_id
            .as_ref()
            .and_then(|id| runtime.block_on(runtime.models().get(id)).ok().flatten());
        let provider = settings
            .default_provider_id
            .as_ref()
            .and_then(|id| runtime.block_on(runtime.providers().get(id)).ok().flatten());
        let connected = provider.as_ref().is_some_and(|provider| {
            provider.enabled
                && (provider.auth_type == ProviderAuthType::None || provider.secret_id.is_some())
        });
        let ready = connected
            && model.as_ref().is_some_and(|model| {
                !model.hidden && Some(&model.provider_id) == settings.default_provider_id.as_ref()
            });
        chat.messages_window().set_needs_setup(!ready);
        chat.messages_window().set_setup_uses_models(connected);
        chat.set_default_model(model);
        chat.set_send_key(settings.send_key);
        chat.set_show_token_usage(settings.show_token_usage);
    }

    fn poll_events(self: &Rc<Self>, window: &ShellWindow) {
        loop {
            let event = self.events.borrow_mut().try_recv();
            match event {
                Ok(DesktopEvent::ChatCreated { request, result }) => {
                    if request != self.chat_request.get() {
                        if let Ok(conversation) = result {
                            let conversations = self.services.runtime.conversations();
                            let sender = self.event_sender.clone();
                            RuntimeSpawner::new(&self.services.runtime).spawn_future(Box::pin(
                                async move {
                                    let result = conversations.delete(&conversation.id).await;
                                    let _ =
                                        sender.send(DesktopEvent::CancelledChatRemoved { result });
                                },
                            ));
                        } else {
                            self.finish_chat_creation();
                        }
                        continue;
                    }
                    self.finish_chat_creation();
                    let Some(mut send) = self.pending_first_send.borrow_mut().take() else {
                        continue;
                    };
                    match result {
                        Ok(conversation) => {
                            if self.shell_state.borrow().active == Destination::Chat {
                                self.open_conversation(window, &conversation.id);
                            } else {
                                if let Some(chat) = self.chat.borrow().as_ref() {
                                    chat.open(&conversation.id);
                                }
                                self.persist_active_conversation(Some(&conversation.id));
                            }
                            send.conversation_id = conversation.id;
                            if let Some(chat) = self.chat.borrow().as_ref() {
                                chat.restore_unsent_message(send, None);
                                chat.send();
                            }
                            if let Some(history) = self.history.borrow().as_ref() {
                                history.refresh();
                            }
                        }
                        Err(error) => {
                            if let Some(chat) = self.chat.borrow().as_ref() {
                                chat.restore_unsent_message(
                                    send,
                                    Some(format!("Could not start this chat: {error}")),
                                );
                            }
                        }
                    }
                }
                Ok(DesktopEvent::CancelledChatRemoved { result }) => {
                    self.finish_chat_creation();
                    if let Err(error) = result {
                        self.set_notice(format!("Could not remove the cancelled chat: {error}"));
                    }
                    if let Some(history) = self.history.borrow().as_ref() {
                        history.refresh();
                    }
                }
                Ok(DesktopEvent::ModelChanged {
                    conversation_id,
                    result,
                }) => {
                    self.model_change_pending.set(false);
                    if let Some(models) = self.models.borrow().as_ref() {
                        models.window().set_selection_pending(false);
                    }
                    let current = self
                        .chat
                        .borrow()
                        .as_ref()
                        .filter(|chat| chat.conversation_id().as_deref() == Some(&conversation_id))
                        .cloned();
                    if let Some(chat) = current {
                        chat.set_model_switching(false);
                        match result {
                            Ok(()) => {
                                chat.open(conversation_id);
                                window.set_show_models(false);
                                self.refresh_chat_configuration();
                            }
                            Err(error) => {
                                self.set_notice(format!("Could not change the model: {error}"))
                            }
                        }
                    }
                }
                Err(_) => break,
            }
        }
    }

    fn finish_chat_creation(&self) {
        self.chat_creation_jobs
            .set(self.chat_creation_jobs.get().saturating_sub(1));
    }

    fn select_chat_model(self: &Rc<Self>, model: Model) {
        if self.model_change_pending.get() {
            return;
        }
        let Some(chat) = self.chat.borrow().as_ref().cloned() else {
            return;
        };
        if let Some(id) = chat.conversation_id() {
            if chat.state().sending || chat.is_streaming() {
                self.set_notice("Stop the current response before changing its model.");
                return;
            }
            self.model_change_pending.set(true);
            chat.set_model_switching(true);
            if let Some(models) = self.models.borrow().as_ref() {
                models.window().set_selection_pending(true);
            }
            let conversations = self.services.runtime.conversations();
            let sender = self.event_sender.clone();
            RuntimeSpawner::new(&self.services.runtime).spawn_future(Box::pin(async move {
                let result = async {
                    let mut conversation = conversations.get(&id).await?.ok_or_else(|| {
                        StorageError::NotFound("The conversation was not found.".to_owned())
                    })?;
                    conversation.model_id = model.id;
                    conversation.provider_id = model.provider_id;
                    conversation.model_label = model.label;
                    conversation.updated_at = chatui_domain::Timestamp::now();
                    conversations.update(&conversation).await
                }
                .await;
                let _ = sender.send(DesktopEvent::ModelChanged {
                    conversation_id: id,
                    result,
                });
            }));
        } else {
            if let Some(window) = self.shell.upgrade() {
                window.set_show_models(false);
            }
            self.refresh_chat_configuration();
        }
    }

    fn search(self: &Rc<Self>, window: &ShellWindow) {
        self.navigate(window, Destination::History);
        window.set_notice("Search conversations from History.".into());
    }

    fn export(self: &Rc<Self>, window: &ShellWindow) {
        self.navigate(window, Destination::Data);
        if let Some(data) = self.data.borrow().as_ref() {
            data.select_section(DataSection::Export);
            data.window().invoke_export_all();
        }
    }

    fn import(self: &Rc<Self>, window: &ShellWindow) {
        self.navigate(window, Destination::Data);
        if let Some(data) = self.data.borrow().as_ref() {
            data.select_section(DataSection::Import);
            data.window().invoke_choose_import_file();
        }
    }

    fn save_export(&self, artifact: &ExportArtifact) -> Result<bool, String> {
        let Some(path) = self.dialogs.pick_export_path(&artifact.filename) else {
            return Ok(false);
        };
        self.dialogs
            .write_text(&path, &artifact.content)
            .map_err(|error| error.to_string())?;
        Ok(true)
    }

    fn refresh_after_data_change(&self, change: DataChange) {
        let reset = matches!(
            change,
            DataChange::Cleared | DataChange::Imported { replaced: true }
        );
        if reset {
            self.cancel_first_send();
            if let Some(chat) = self.chat.borrow().as_ref() {
                chat.stop();
                chat.clear();
                chat.discard_draft();
            }
            let _ = self.services.runtime.block_on(self.drafts.load());
            self.persist_active_conversation(None);
        } else if let Some(chat) = self.chat.borrow().as_ref()
            && let Some(id) = chat.conversation_id()
        {
            chat.open(id);
        }
        if let Some(history) = self.history.borrow().as_ref() {
            history.refresh();
        }
        if let Some(settings) = self.settings.borrow().as_ref() {
            settings.reload();
            if reset {
                settings.refresh_backup();
            }
        }
        if let Some(models) = self.models.borrow().as_ref() {
            models.reload();
        }
        if let Some(providers) = self.providers.borrow().as_ref() {
            if reset {
                providers.reset();
            } else {
                providers.reload();
            }
        }
        self.refresh_chat_configuration();
    }

    fn is_data_mutating(&self) -> bool {
        self.data.borrow().as_ref().is_some_and(|data| {
            matches!(
                data.state().busy,
                Some(DataOperation::Import | DataOperation::ClearAll | DataOperation::RemoveKey)
            )
        }) || self
            .settings
            .borrow()
            .as_ref()
            .is_some_and(|settings| settings.backup_state().restoring)
    }

    fn data_busy_reason(&self) -> Option<&'static str> {
        if self.chat_creation_jobs.get() > 0 {
            return Some("Wait for the chat update to finish before changing local data.");
        }
        if self.model_change_pending.get()
            || self.pending_first_send.borrow().is_some()
            || self
                .chat
                .borrow()
                .as_ref()
                .is_some_and(|chat| chat.state().sending || chat.is_streaming())
        {
            return Some("Finish or stop the current response before changing local data.");
        }
        if self
            .providers
            .borrow()
            .as_ref()
            .is_some_and(|providers| providers.window().get_busy())
        {
            return Some("Wait for the provider connection to finish before changing local data.");
        }
        if self
            .models
            .borrow()
            .as_ref()
            .is_some_and(|models| models.window().get_loading())
        {
            return Some("Wait for model discovery to finish before changing local data.");
        }
        if self
            .settings
            .borrow()
            .as_ref()
            .is_some_and(|settings| settings.backup_state().busy)
        {
            return Some("Wait for the backup operation to finish before changing local data.");
        }
        if self
            .history
            .borrow()
            .as_ref()
            .is_some_and(|history| history.state().history.pending_delete.is_some())
        {
            return Some("Undo or wait for the pending deletion before changing local data.");
        }
        if self
            .history
            .borrow()
            .as_ref()
            .is_some_and(|history| history.window().get_busy())
        {
            return Some("Wait for the conversation update to finish before changing local data.");
        }
        None
    }

    fn copy_last_response(self: &Rc<Self>, window: &ShellWindow) {
        let Some(chat) = self.chat.borrow().as_ref().cloned() else {
            window.set_notice("Open a conversation before copying a response.".into());
            return;
        };
        let Some(conversation_id) = chat.conversation_id() else {
            window.set_notice("Open a conversation before copying a response.".into());
            return;
        };

        let messages = self.services.runtime.messages();
        let page = self
            .services
            .runtime
            .block_on(messages.newest_page(&conversation_id, 50));
        match page {
            Ok(page) => match clipboard::copy_last_response(&page.messages) {
                Ok(payload) => match copy_payload(&self.clipboard, &payload) {
                    Ok(()) => window.set_notice("Copied the last response.".into()),
                    Err(error) => window.set_notice(error.to_string().into()),
                },
                Err(error) => window.set_notice(error.to_string().into()),
            },
            Err(_) => window.set_notice("Could not read the conversation.".into()),
        }
    }

    fn navigate_conversation(self: &Rc<Self>, window: &ShellWindow, direction: i32) {
        let Some(chat) = self.chat.borrow().as_ref().cloned() else {
            window.set_notice("Open a conversation before navigating.".into());
            return;
        };
        let current = chat.conversation_id();
        let conversations = self.services.runtime.conversations();
        let filter = ConversationFilter {
            pinned: None,
            archived: Some(false),
            limit: Some(100),
        };
        let list = match self.services.runtime.block_on(conversations.list(&filter)) {
            Ok(list) => list,
            Err(_) => {
                window.set_notice("Could not read conversations.".into());
                return;
            }
        };
        if list.is_empty() {
            window.set_notice("No conversations yet.".into());
            return;
        }

        let index = current
            .as_deref()
            .and_then(|id| list.iter().position(|conversation| conversation.id == id));
        let next = match (index, direction) {
            (Some(index), direction) if direction < 0 => {
                index.checked_sub(1).unwrap_or(list.len() - 1)
            }
            (Some(index), _) => (index + 1) % list.len(),
            (None, direction) if direction < 0 => list.len() - 1,
            (None, _) => 0,
        };
        let id = list[next].id.clone();
        self.open_conversation(window, &id);
    }

    fn stop_or_close(self: &Rc<Self>, window: &ShellWindow) {
        if window.get_show_models() {
            window.set_show_models(false);
            return;
        }
        if self.pending_first_send.borrow().is_some() {
            self.cancel_first_send();
            return;
        }
        let streaming = self
            .chat
            .borrow()
            .as_ref()
            .is_some_and(|chat| chat.is_streaming());
        let context = ShortcutContext {
            text_input_focused: false,
            streaming,
            top_layer_open: window.get_shortcuts_help_open()
                || self.shell_state.borrow().drawer_visible(),
        };
        match self
            .shortcut_map
            .dispatch(ShortcutKey::Escape, Modifiers::NONE, &context)
        {
            ShortcutAction::StopStreaming => {
                if let Some(chat) = self.chat.borrow().as_ref() {
                    chat.stop();
                }
                window.set_notice("Stopped streaming.".into());
            }
            ShortcutAction::CloseTopLayer => {
                if window.get_shortcuts_help_open() {
                    window.set_shortcuts_help_open(false);
                } else {
                    self.shell_state.borrow_mut().close_drawer();
                    sync_window(window, &self.shell_state.borrow());
                }
            }
            _ => window.set_notice("Nothing to stop or close.".into()),
        }
    }

    fn open_docs(self: &Rc<Self>, window: &ShellWindow) {
        self.navigate(window, Destination::Settings);
        if let Some(settings) = self.settings.borrow().as_ref() {
            settings.select_route(SettingsRoute::About);
        }
        if let Err(error) = open_external_url(DOCS_URL) {
            window.set_notice(error.to_string().into());
        }
    }

    fn about(self: &Rc<Self>, window: &ShellWindow) {
        self.navigate(window, Destination::Settings);
        if let Some(settings) = self.settings.borrow().as_ref() {
            settings.select_route(SettingsRoute::About);
        }
        window.set_notice(
            format!(
                "{APP_NAME} {APP_VERSION}. No telemetry. Every provider and documentation link is visible in settings."
            )
            .into(),
        );
    }

    // -- Native actions -----------------------------------------------------

    fn copy_text(&self, text: &str) {
        let payload = ClipboardPayload {
            text: text.to_owned(),
            kind: ClipboardKind::Message,
        };
        let _ = copy_payload(&self.clipboard, &payload);
    }

    fn attach_from_picker(self: &Rc<Self>) {
        let Some(path) = self.dialogs.pick_attachment() else {
            return;
        };
        self.add_paths(&[path]);
    }

    fn add_paths(self: &Rc<Self>, paths: &[PathBuf]) {
        if self.is_data_mutating() {
            self.set_notice("Wait for the local data update before adding attachments.");
            return;
        }
        let Some(chat) = self.chat.borrow().as_ref().cloned() else {
            return;
        };
        let store = self.services.runtime.attachment_store();
        let mut summary = AttachmentDropSummary::default();

        for (index, path) in paths.iter().enumerate() {
            let Some(info) = attachment_info(path) else {
                summary.rejected += 1;
                summary
                    .errors
                    .push(format!("{} is not a readable file.", path.display()));
                continue;
            };

            let validated = match chat.validate_files(&[info]).into_iter().next() {
                Some(Ok(validated)) => validated,
                Some(Err(error)) => {
                    summary.rejected += 1;
                    summary.errors.push(error.to_string());
                    continue;
                }
                None => continue,
            };

            let bytes = match std::fs::read(path) {
                Ok(bytes) => bytes,
                Err(error) => {
                    summary.rejected += 1;
                    summary
                        .errors
                        .push(format!("{} could not be read: {error}", path.display()));
                    continue;
                }
            };

            let id = format!("attach-{index}");
            match store_pending_attachment(store, id, validated, &bytes) {
                Ok(attachment) => {
                    chat.add_attachment(attachment);
                    summary.accepted += 1;
                }
                Err(error) => {
                    summary.rejected += 1;
                    summary.errors.push(error.to_string());
                }
            }
        }

        if let Some(window) = self.shell.upgrade() {
            window.set_notice(summary.notice().into());
        }
    }

    fn paste_image(self: &Rc<Self>) {
        let Some(chat) = self.chat.borrow().as_ref().cloned() else {
            return;
        };
        let mut clipboard = match arboard::Clipboard::new() {
            Ok(clipboard) => clipboard,
            Err(_) => {
                chat.set_attachment_error("The system clipboard is unavailable.");
                return;
            }
        };
        let image = match clipboard.get_image() {
            Ok(image) => image,
            Err(_) => {
                chat.set_attachment_error("The clipboard does not contain an image.");
                return;
            }
        };
        let Some(buffer) = image::RgbaImage::from_raw(
            image.width as u32,
            image.height as u32,
            image.bytes.into_owned(),
        ) else {
            chat.set_attachment_error("The clipboard image could not be read.");
            return;
        };
        let mut png = Vec::new();
        if buffer
            .write_to(&mut std::io::Cursor::new(&mut png), image::ImageFormat::Png)
            .is_err()
        {
            chat.set_attachment_error("The clipboard image could not be encoded.");
            return;
        }

        let info = AttachmentFileInfo::new("clipboard.png", "image/png", png.len() as u64);
        let validated = match chat.validate_files(&[info]).into_iter().next() {
            Some(Ok(validated)) => validated,
            Some(Err(error)) => {
                chat.set_attachment_error(error.to_string());
                return;
            }
            None => return,
        };
        match store_pending_attachment(
            self.services.runtime.attachment_store(),
            "clipboard",
            validated,
            &png,
        ) {
            Ok(attachment) => chat.add_attachment(attachment),
            Err(error) => chat.set_attachment_error(error.to_string()),
        }
    }

    // -- Deep links and timers ---------------------------------------------

    fn handle_deep_link(self: &Rc<Self>, window: &ShellWindow, input: &str) {
        let route = self
            .deep_links
            .borrow_mut()
            .handle(input, self.onboarding_complete);
        match route {
            DeepLinkRoute::Chat { id } => {
                self.open_conversation(window, &id);
            }
            DeepLinkRoute::OnboardingRequired => {
                window.set_notice("Finish onboarding before opening this conversation.".into());
            }
            DeepLinkRoute::NotFound => {
                window.set_notice("Conversation not found.".into());
            }
        }
    }

    fn handle_forwarded(self: &Rc<Self>, window: &ShellWindow, message: &str) {
        if message == "focus" {
            let _ = window.show();
            window.set_notice("ChatUI is already running.".into());
        } else if message.starts_with("chatui://") {
            self.handle_deep_link(window, message);
        }
    }

    fn poll_controllers(self: &Rc<Self>, window: &ShellWindow) {
        self.poll_events(window);
        window.set_navigation_enabled(!self.is_data_mutating());
        if let Some(chat) = self.chat.borrow().as_ref() {
            chat.poll();
            let state = chat.state();
            let label = state
                .model
                .as_ref()
                .map(|model| model.label.as_str())
                .unwrap_or("Choose model");
            window.set_chat_model_label(label.into());
            window.set_model_selection_enabled(
                !state.loading
                    && !state.sending
                    && !chat.is_streaming()
                    && !self.model_change_pending.get(),
            );
            window.set_composer_height(chat.composer_window().get_preferred_content_height());
        }
        if let Some(history) = self.history.borrow().as_ref() {
            history.poll();
        }
        if let Some(providers) = self.providers.borrow().as_ref() {
            providers.poll();
        }
        if let Some(data) = self.data.borrow().as_ref() {
            data.set_external_busy(self.data_busy_reason());
            data.poll();
        }
        if let Some(models) = self.models.borrow().as_ref() {
            models.poll();
        }
        if let Some(settings) = self.settings.borrow().as_ref() {
            settings.poll();
        }
        self.poll_settings(window);
    }

    fn poll_settings(self: &Rc<Self>, window: &ShellWindow) {
        let Some(settings) = self.settings.borrow().as_ref().cloned() else {
            return;
        };
        let current = settings.state().settings.clone();
        let changed = {
            let mut applied = self.applied_settings.borrow_mut();
            if applied.as_ref() == Some(&current) {
                false
            } else {
                *applied = Some(current.clone());
                true
            }
        };
        if !changed {
            return;
        }

        {
            let mut state = self.shell_state.borrow_mut();
            settings.apply_to_shell(&mut state);
        }
        sync_window(window, &self.shell_state.borrow());

        if let Some(chat) = self.chat.borrow().as_ref() {
            chat.set_send_key(current.send_key);
            chat.set_show_token_usage(current.show_token_usage);
        }

        self.apply_theme_to_controllers();
    }

    fn flush_drafts(&self) {
        if self.drafts.is_dirty() {
            let _ = self.services.runtime.block_on(self.drafts.flush());
        }
    }

    fn poll_system_theme(self: &Rc<Self>, window: &ShellWindow) {
        let theme = NativeSystemThemeProbe.probe();
        let mut state = self.shell_state.borrow_mut();
        if state.system_is_dark == theme.dark && state.system_reduced_motion == theme.reduced_motion
        {
            return;
        }
        state.system_is_dark = theme.dark;
        state.system_reduced_motion = theme.reduced_motion;
        drop(state);
        sync_window(window, &self.shell_state.borrow());
        self.apply_theme_to_controllers();
    }

    fn save_window_state(&self, window: &ShellWindow) {
        let _ = self.window_state.save(window.window());
    }
}

// ---------------------------------------------------------------------------
// Wiring
// ---------------------------------------------------------------------------

fn wire_menu(controller: &ShellController, composition: &Rc<DesktopComposition>) {
    let weak = controller.window().as_weak();
    let composition = Rc::clone(composition);
    controller.window().on_menu_command(move |id| {
        let Some(window) = weak.upgrade() else {
            return;
        };
        let Some(command) = composition.menu_bar.command_for_id(id.as_str()) else {
            return;
        };
        let _ = composition.execute(&window, command);
    });
}

fn wire_drop(controller: &ShellController, composition: &Rc<DesktopComposition>) {
    let weak = controller.window().as_weak();
    let composition = Rc::clone(composition);
    controller.window().on_files_dropped(move |data| {
        let Some(window) = weak.upgrade() else {
            return;
        };
        let paths: Vec<PathBuf> = match data.file_paths() {
            Ok(paths) => paths.map(Path::to_path_buf).collect(),
            Err(_) => Vec::new(),
        };
        if paths.is_empty() {
            window.set_notice("No files were dropped.".into());
            return;
        }
        composition.add_paths(&paths);
    });
}

fn wire_close(controller: &ShellController, composition: &Rc<DesktopComposition>) {
    let weak = controller.window().as_weak();
    let composition = Rc::clone(composition);
    controller.window().window().on_close_requested(move || {
        if let Some(window) = weak.upgrade() {
            composition.save_window_state(&window);
        }
        slint::CloseRequestResponse::HideWindow
    });
}

fn wire_navigation(controller: &ShellController, composition: &Rc<DesktopComposition>) {
    let weak = Rc::downgrade(composition);
    let window_weak = controller.window().as_weak();
    controller.window().on_open_models(move || {
        if let (Some(composition), Some(window)) = (weak.upgrade(), window_weak.upgrade()) {
            composition.open_models(&window);
        }
    });
    let weak = controller.window().as_weak();
    let composition = Rc::clone(composition);
    controller.window().on_navigate(move |index| {
        let Some(destination) = Destination::from_index(index as usize) else {
            return;
        };
        if let Some(window) = weak.upgrade() {
            composition.navigate(&window, destination);
        }
    });
}

fn wire_single_instance(
    controller: &ShellController,
    composition: &Rc<DesktopComposition>,
    guard: InstanceGuard,
) -> slint::Timer {
    let weak = controller.window().as_weak();
    let composition = Rc::clone(composition);
    let timer = slint::Timer::default();
    timer.start(
        slint::TimerMode::Repeated,
        Duration::from_millis(250),
        move || {
            let Some(window) = weak.upgrade() else {
                return;
            };
            while let Some(message) = guard.try_recv() {
                composition.handle_forwarded(&window, &message);
            }
        },
    );
    timer
}

fn wire_system_theme(
    controller: &ShellController,
    composition: &Rc<DesktopComposition>,
) -> slint::Timer {
    let weak = controller.window().as_weak();
    let composition = Rc::clone(composition);
    let timer = slint::Timer::default();
    timer.start(
        slint::TimerMode::Repeated,
        Duration::from_secs(2),
        move || {
            if let Some(window) = weak.upgrade() {
                composition.poll_system_theme(&window);
            }
        },
    );
    timer
}

fn wire_poll(controller: &ShellController, composition: &Rc<DesktopComposition>) -> slint::Timer {
    let weak = controller.window().as_weak();
    let composition = Rc::clone(composition);
    let timer = slint::Timer::default();
    timer.start(
        slint::TimerMode::Repeated,
        Duration::from_millis(50),
        move || {
            if let Some(window) = weak.upgrade() {
                composition.poll_controllers(&window);
            }
        },
    );
    timer
}

fn wire_draft_flush(
    controller: &ShellController,
    composition: &Rc<DesktopComposition>,
) -> slint::Timer {
    let weak = controller.window().as_weak();
    let composition = Rc::clone(composition);
    let timer = slint::Timer::default();
    timer.start(
        slint::TimerMode::Repeated,
        Duration::from_secs(2),
        move || {
            if weak.upgrade().is_some() {
                composition.flush_drafts();
            }
        },
    );
    timer
}

fn wire_smoke(controller: &ShellController, composition: &Rc<DesktopComposition>) -> slint::Timer {
    let weak = controller.window().as_weak();
    let composition = Rc::clone(composition);
    let step = Rc::new(RefCell::new(0usize));
    let timer = slint::Timer::default();
    timer.start(
        slint::TimerMode::Repeated,
        Duration::from_millis(300),
        move || {
            let Some(window) = weak.upgrade() else {
                return;
            };
            let mut step = step.borrow_mut();
            match *step {
                0 => {
                    println!("smoke: chat");
                    composition.navigate(&window, Destination::Chat);
                }
                1 => {
                    println!("smoke: history");
                    composition.navigate(&window, Destination::History);
                }
                2 => {
                    println!("smoke: providers");
                    composition.navigate(&window, Destination::Providers);
                }
                3 => {
                    println!("smoke: settings");
                    composition.navigate(&window, Destination::Settings);
                }
                4 => {
                    println!("smoke: data");
                    composition.navigate(&window, Destination::Data);
                }
                5 => {
                    println!("smoke: models overlay");
                    composition.open_models(&window);
                }
                6 => {
                    println!("smoke: close models");
                    window.set_show_models(false);
                }
                _ => {
                    println!("smoke: done");
                    composition.save_window_state(&window);
                    let _ = slint::quit_event_loop();
                }
            }
            *step += 1;
        },
    );
    timer
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::dialogs::MemoryDialogService;
    use chatui_domain::Theme;
    use window_state::MemoryWindowStateStore;

    fn composition() -> (tempfile::TempDir, Rc<DesktopComposition>, ShellController) {
        composition_with_dialogs(Rc::new(MemoryDialogService::new()))
    }

    fn composition_with_dialogs(
        dialogs: Rc<dyn DialogService>,
    ) -> (tempfile::TempDir, Rc<DesktopComposition>, ShellController) {
        i_slint_backend_testing::init_no_event_loop();
        let directory = tempfile::tempdir().expect("create temporary directory");
        let runtime =
            ServiceRuntime::open_at(directory.path().join("chatui.sqlite3"), directory.path())
                .expect("open runtime");
        let settings = runtime.settings_store().load().expect("load settings");
        let services = DesktopServices {
            runtime,
            secrets: session_only_secret_store(),
            settings,
            _profile: None,
        };
        let shell = ShellController::new(ShellState::new(1024.0)).expect("create shell");
        let mut composition = DesktopComposition::new(
            services,
            shell.state_handle(),
            shell.window().as_weak(),
            WindowStateController::new(Box::new(MemoryWindowStateStore::new())),
            true,
        )
        .expect("create composition");
        composition.dialogs = dialogs;
        (directory, Rc::new(composition), shell)
    }

    fn wait_for(
        composition: &Rc<DesktopComposition>,
        shell: &ShellController,
        mut done: impl FnMut() -> bool,
    ) {
        for _ in 0..200 {
            composition.poll_controllers(shell.window());
            if done() {
                return;
            }
            std::thread::sleep(Duration::from_millis(5));
        }
        panic!("desktop operation did not finish");
    }

    #[test]
    fn new_chat_does_not_create_empty_history_rows_and_failed_first_send_keeps_draft() {
        let (_directory, composition, shell) = composition();
        composition.new_chat(shell.window());
        composition.new_chat(shell.window());
        assert!(
            composition
                .data_service
                .list_conversations()
                .expect("conversations")
                .is_empty()
        );
        let chat = composition.chat.borrow().as_ref().cloned().expect("chat");
        chat.composer_window()
            .invoke_text_changed("Keep this first message".into());
        chat.composer_window().invoke_send();
        assert!(composition.pending_first_send.borrow().is_some());
        wait_for(&composition, &shell, || {
            composition.pending_first_send.borrow().is_none()
        });
        assert_eq!(chat.composer_window().get_text(), "Keep this first message");
        assert!(!chat.state().sending);
        assert!(chat.state().error.is_some());
        assert!(
            composition
                .data_service
                .list_conversations()
                .expect("conversations")
                .is_empty()
        );
    }

    #[test]
    fn cancelled_first_send_does_not_apply_a_late_creation_result() {
        let (_directory, composition, shell) = composition();
        composition.new_chat(shell.window());
        let chat = composition.chat.borrow().as_ref().cloned().expect("chat");
        chat.composer_window()
            .invoke_text_changed("Keep this cancelled draft".into());
        chat.composer_window().invoke_send();
        composition.cancel_first_send();
        assert!(composition.data_busy_reason().is_some());
        wait_for(&composition, &shell, || {
            composition.chat_creation_jobs.get() == 0
        });
        assert!(composition.data_busy_reason().is_none());
        assert_eq!(
            chat.composer_window().get_text(),
            "Keep this cancelled draft"
        );
        assert!(chat.conversation_id().is_none());
        assert!(!chat.state().sending);
        assert!(chat.state().error.is_none());
    }

    #[test]
    fn cancelled_creation_blocks_data_changes_until_its_late_row_is_removed() {
        let (_directory, composition, shell) = composition();
        let now = chatui_domain::Timestamp::now();
        let conversation = Conversation {
            id: "cancelled-chat".to_owned(),
            title: "Cancelled".to_owned(),
            system_prompt: String::new(),
            provider_id: "provider".to_owned(),
            model_id: "model".to_owned(),
            model_label: "Model".to_owned(),
            params: Default::default(),
            pinned: false,
            archived: false,
            temporary: false,
            created_at: now,
            updated_at: now,
            last_message_at: now,
            message_count: 0,
            last_error: None,
            sync: None,
            storage_key: String::new(),
            revision: 0,
        };
        composition
            .services
            .runtime
            .block_on(
                composition
                    .services
                    .runtime
                    .conversations()
                    .create(&conversation),
            )
            .expect("create late conversation");
        composition.chat_request.set(2);
        composition.chat_creation_jobs.set(1);
        assert!(
            composition
                .event_sender
                .send(DesktopEvent::ChatCreated {
                    request: 1,
                    result: Ok(Box::new(conversation)),
                })
                .is_ok()
        );
        assert!(composition.data_busy_reason().is_some());
        composition.poll_events(shell.window());
        wait_for(&composition, &shell, || {
            composition.chat_creation_jobs.get() == 0
        });
        assert!(composition.data_busy_reason().is_none());
        assert!(
            composition
                .services
                .runtime
                .block_on(
                    composition
                        .services
                        .runtime
                        .conversations()
                        .get("cancelled-chat")
                )
                .expect("read cancelled conversation")
                .is_none()
        );
    }

    #[test]
    fn data_screen_export_uses_the_native_save_handler() {
        let path = PathBuf::from("saved-chatui.json");
        let dialogs = Rc::new(MemoryDialogService::new().with_export(&path));
        let (_directory, composition, shell) = composition_with_dialogs(dialogs.clone());
        composition.navigate(shell.window(), Destination::Data);
        let data = composition.data.borrow().as_ref().cloned().expect("data");
        data.window().invoke_export_all();
        wait_for(&composition, &shell, || data.state().busy.is_none());
        let json = dialogs.written(&path).expect("saved export");
        assert!(serde_json::from_str::<serde_json::Value>(&json).is_ok());
        assert!(data.state().last_export.is_some());
    }

    #[test]
    fn menu_import_opens_a_review_instead_of_importing_immediately() {
        let dialogs = Rc::new(
            MemoryDialogService::new()
                .with_import("review.json")
                .with_file("review.json", "{\"review\":true}"),
        );
        let (_directory, composition, shell) = composition_with_dialogs(dialogs);
        composition.execute(shell.window(), Command::Import);
        let data = composition.data.borrow().as_ref().cloned().expect("data");
        assert_eq!(data.state().section(), DataSection::Import);
        assert_eq!(data.state().import_text, "{\"review\":true}");
        assert!(data.state().last_import.is_none());
        assert!(!data.state().replace_confirmed);
    }

    #[test]
    fn restoring_local_data_prevents_navigation_until_the_operation_finishes() {
        let (_directory, composition, shell) = composition();
        composition.navigate(shell.window(), Destination::Settings);
        let settings = composition
            .settings
            .borrow()
            .as_ref()
            .cloned()
            .expect("settings");
        settings
            .window()
            .invoke_backup_passphrase_changed("test passphrase".into());
        settings.window().invoke_backup_restore();
        assert!(composition.is_data_mutating());
        composition.navigate(shell.window(), Destination::Data);
        assert_eq!(
            composition.shell_state.borrow().active,
            Destination::Settings
        );
        wait_for(&composition, &shell, || !composition.is_data_mutating());
        assert!(settings.backup_state().error.is_some());
    }

    #[test]
    fn startup_route_uses_persisted_completion() {
        assert_eq!(startup_route(false), StartupRoute::Onboarding);
        assert_eq!(startup_route(true), StartupRoute::Shell);
    }

    #[test]
    fn controllers_are_created_lazily_and_reused() {
        let (_directory, composition, shell) = composition();

        composition.ensure_controller(shell.window(), Destination::Chat);
        let first = composition
            .chat
            .borrow()
            .as_ref()
            .cloned()
            .expect("chat controller");
        composition.ensure_controller(shell.window(), Destination::Chat);
        let second = composition
            .chat
            .borrow()
            .as_ref()
            .cloned()
            .expect("chat controller");
        assert!(Rc::ptr_eq(&first, &second));

        for destination in [
            Destination::History,
            Destination::Providers,
            Destination::Settings,
            Destination::Data,
        ] {
            composition.ensure_controller(shell.window(), destination);
        }

        assert!(composition.history.borrow().is_some());
        assert!(composition.providers.borrow().is_some());
        assert!(composition.settings.borrow().is_some());
        assert!(composition.data.borrow().is_some());

        composition.open_models(shell.window());
        assert!(shell.window().get_show_models());
        assert!(composition.models.borrow().is_some());

        shell.window().set_show_models(false);
        assert!(!shell.window().get_show_models());
    }

    #[test]
    fn settings_apply_to_the_live_shell_and_composer() {
        let (_directory, composition, shell) = composition();
        composition.ensure_controller(shell.window(), Destination::Chat);
        composition.ensure_controller(shell.window(), Destination::Settings);

        let settings = composition
            .settings
            .borrow()
            .as_ref()
            .cloned()
            .expect("settings controller");

        settings.window().invoke_theme_changed(2);
        composition.poll_settings(shell.window());
        assert_eq!(composition.shell_state.borrow().theme.theme, Theme::Dark);

        let chat = composition
            .chat
            .borrow()
            .as_ref()
            .cloned()
            .expect("chat controller");
        for destination in [
            Destination::History,
            Destination::Providers,
            Destination::Settings,
            Destination::Data,
        ] {
            composition.ensure_controller(shell.window(), destination);
        }
        composition.open_models(shell.window());
        composition.poll_settings(shell.window());

        assert!(settings.window().get_dark());
        assert!(chat.messages_window().get_dark());
        assert!(chat.composer_window().get_dark());
        assert!(
            composition
                .history
                .borrow()
                .as_ref()
                .expect("history")
                .window()
                .get_dark()
        );
        assert!(
            composition
                .providers
                .borrow()
                .as_ref()
                .expect("providers")
                .window()
                .get_dark()
        );
        assert!(
            composition
                .data
                .borrow()
                .as_ref()
                .expect("data")
                .window()
                .get_dark()
        );
        assert!(
            composition
                .models
                .borrow()
                .as_ref()
                .expect("models")
                .window()
                .get_dark()
        );

        settings.window().invoke_send_key_changed(1);
        composition.poll_settings(shell.window());
        assert_eq!(
            chat.composer_window().get_send_key_hint(),
            "Ctrl/Cmd+Enter to send, Enter for a new line"
        );
    }

    #[test]
    fn persisted_dark_theme_applies_to_initial_controllers() {
        i_slint_backend_testing::init_no_event_loop();
        let directory = tempfile::tempdir().expect("create temporary directory");
        let runtime =
            ServiceRuntime::open_at(directory.path().join("chatui.sqlite3"), directory.path())
                .expect("open runtime");
        let mut settings = runtime.settings_store().load().expect("load settings");
        settings.theme = Theme::Dark;
        runtime
            .settings_store()
            .save(&settings)
            .expect("save dark theme");

        let services = DesktopServices {
            runtime,
            secrets: session_only_secret_store(),
            settings: settings.clone(),
            _profile: None,
        };
        let mut state = ShellState::new(1024.0);
        state.theme = ThemeSettings::new(
            settings.theme,
            settings.density,
            settings.chat_font_size,
            ReducedMotion::System,
        );
        let shell = ShellController::new(state).expect("create shell");
        let composition = Rc::new(
            DesktopComposition::new(
                services,
                shell.state_handle(),
                shell.window().as_weak(),
                WindowStateController::new(Box::new(MemoryWindowStateStore::new())),
                true,
            )
            .expect("create composition"),
        );

        composition.ensure_controller(shell.window(), Destination::Chat);
        composition.ensure_controller(shell.window(), Destination::Settings);

        assert!(
            composition
                .chat
                .borrow()
                .as_ref()
                .expect("chat")
                .messages_window()
                .get_dark()
        );
        assert!(
            composition
                .settings
                .borrow()
                .as_ref()
                .expect("settings")
                .window()
                .get_dark()
        );
    }

    #[test]
    fn deep_link_opens_the_chat_destination() {
        let (_directory, composition, shell) = composition();
        composition.handle_deep_link(shell.window(), "chatui://chat/abc-123");
        assert_eq!(composition.shell_state.borrow().active, Destination::Chat);
        assert!(composition.chat.borrow().is_some());
    }
}
