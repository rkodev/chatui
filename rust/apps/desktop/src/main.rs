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

use std::cell::RefCell;
use std::error::Error;
use std::future::Future;
use std::path::{Path, PathBuf};
use std::pin::Pin;
use std::rc::Rc;
use std::sync::Arc;
use std::time::Duration;

use chatui_contracts::{AppConfig, AppConfigError, provider_catalog};
use chatui_domain::settings::SendKey;
use chatui_domain::{ChatError, Settings};
use chatui_providers::{HttpClient, NetworkError};
use chatui_secrets::SecretStore;
use chatui_storage::{ConversationFilter, SearchRepository, SqliteSearchRepository, StorageError};
use chatui_ui::accessibility::Modifiers;
use chatui_ui::chat::runner::DEFAULT_PERSIST_INTERVAL;
use chatui_ui::chat::{
    AttachmentFileInfo, ChatController, ChatControllerDeps, ChatRunner, ChatRunnerOptions,
    ChatRuntime, ChatService, DraftStore, MemoryStreamingStore, MessageListConfig,
    PersistentDraftStore, ProviderChatAdapter, SettingsDraftPersistence, StreamingStore,
    SystemClock, store_pending_attachment,
};
use chatui_ui::conversations::{
    ConversationFactory, ConversationHistory, ConversationSearchService,
    DefaultConversationFactory, HistoryConfig, HistoryController, HistoryRuntime,
};
use chatui_ui::data::{DataController, DataService, ExportRequest, ImportMode, format_bytes};
use chatui_ui::models::{ModelManagementController, ModelManagementService};
use chatui_ui::onboarding::{OnboardingController, OnboardingRoute};
use chatui_ui::providers::{ProviderManagementController, ProviderManagementService};
use chatui_ui::settings::{
    APP_NAME, APP_VERSION, DOCS_URL, SettingsController, SettingsError, SettingsRoute,
    SettingsService, SettingsStore as SettingsStoreTrait,
};
use chatui_ui::shell::{Destination, ShellController, ShellState, ShellWindow, sync_window};
use slint::{ComponentFactory, ComponentHandle, ModelRc, VecModel};

use clipboard::{ClipboardKind, ClipboardPayload, ClipboardService, NativeClipboard, copy_payload};
use deep_link::{DeepLinkRoute, DeepLinkState};
use dialogs::{AttachmentDropSummary, DialogService, NativeDialogService, attachment_info};
use menu::{Command, CommandOutcome, MenuBar};
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
    DEFAULT_WIDTH, FileWindowStateStore, NativeSystemThemeProbe, SystemThemeProbe,
    WindowStateController,
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

    let window_state = WindowStateController::new(Box::new(FileWindowStateStore::new(
        FileWindowStateStore::default_path(),
    )));
    let saved = window_state.load();
    let mut state = ShellState::new(saved.map(|state| state.width).unwrap_or(DEFAULT_WIDTH));
    let theme = NativeSystemThemeProbe.probe();
    state.system_is_dark = theme.dark;
    state.system_reduced_motion = theme.reduced_motion;

    let mut services = match DesktopServices::open(smoke) {
        Ok(services) => services,
        Err(error) => return show_startup_error(&error, smoke),
    };

    let mut onboarding_complete = services.settings.onboarding_completed;
    if !onboarding_complete && !smoke {
        match run_onboarding(&services) {
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

fn run_onboarding(services: &DesktopServices) -> Result<bool, StartupError> {
    let preset = provider_catalog()
        .get("openrouter")
        .cloned()
        .ok_or_else(|| StartupError::Message("The OpenRouter preset is missing.".to_owned()))?;
    let service = build_onboarding_service(&services.runtime, &preset, services.secrets.clone())?;
    let controller = OnboardingController::new(Arc::new(service), &preset)?;

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
                && window.get_onboarding_completed()
            {
                let _ = slint::quit_event_loop();
            }
        },
    );

    controller.window().run()?;
    drop(timer);

    let completed = controller.route() == OnboardingRoute::Shell;
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

/// Owns the production services and the lazily created feature controllers.
struct DesktopComposition {
    services: DesktopServices,
    shell_state: Rc<RefCell<ShellState>>,
    shell: slint::Weak<ShellWindow>,
    window_state: WindowStateController,
    clipboard: NativeClipboard,
    dialogs: NativeDialogService,
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

        Ok(Self {
            services,
            shell_state,
            shell,
            window_state,
            clipboard: NativeClipboard,
            dialogs: NativeDialogService,
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

    fn navigate(self: &Rc<Self>, window: &ShellWindow, destination: Destination) {
        self.shell_state.borrow_mut().navigate(destination);
        sync_window(window, &self.shell_state.borrow());
        self.ensure_and_sync(window, destination);
    }

    fn open_conversation(self: &Rc<Self>, window: &ShellWindow, id: &str) {
        self.navigate(window, Destination::Chat);
        if let Some(chat) = self.chat.borrow().as_ref() {
            chat.open(id);
        }
        self.set_notice(format!("Opened conversation {id}."));
    }

    fn open_models(self: &Rc<Self>, window: &ShellWindow) {
        self.ensure_models(window);
        window.set_show_models(true);
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
                self.wire_chat_handlers(&chat);
                *self.chat.borrow_mut() = Some(chat);
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
            initial_conversation_id: None,
        })?;
        Ok(controller)
    }

    fn wire_chat_handlers(self: &Rc<Self>, chat: &Rc<ChatController>) {
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

    fn build_providers(&self) -> Result<ProviderManagementController, StartupError> {
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
        Ok(ProviderManagementController::new(service)?)
    }

    // -- Settings -----------------------------------------------------------

    fn ensure_settings(self: &Rc<Self>, window: &ShellWindow) {
        if self.settings.borrow().is_some() {
            return;
        }

        match self.build_settings(window) {
            Ok(settings) => {
                let settings = Rc::new(settings);
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
        let mut controller = SettingsController::new(service)?;

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
                composition.open_models(&window);
            }
        });

        let weak = Rc::downgrade(self);
        let window_weak = window.as_weak();
        controller.set_on_open_data(move || {
            if let (Some(composition), Some(window)) = (weak.upgrade(), window_weak.upgrade()) {
                composition.navigate(&window, Destination::Data);
            }
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

    fn build_data(&self) -> Result<DataController, StartupError> {
        Ok(DataController::new(self.data_service.clone())?)
    }

    // -- Models overlay -----------------------------------------------------

    fn ensure_models(self: &Rc<Self>, window: &ShellWindow) {
        if self.models.borrow().is_some() {
            return;
        }

        match self.build_models() {
            Ok(models) => {
                let models = Rc::new(models);
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

    fn build_models(&self) -> Result<ModelManagementController, StartupError> {
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
        Ok(ModelManagementController::new(service)?)
    }

    // -- Commands -----------------------------------------------------------

    fn execute(self: &Rc<Self>, window: &ShellWindow, command: Command) -> CommandOutcome {
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
        self.ensure_and_sync(window, Destination::History);
        if let Some(history) = self.history.borrow().as_ref() {
            history.window().invoke_new_chat();
        }
    }

    fn search(self: &Rc<Self>, window: &ShellWindow) {
        self.navigate(window, Destination::History);
        window.set_notice("Search conversations from History.".into());
    }

    fn export(self: &Rc<Self>, window: &ShellWindow) {
        let Some(path) = self.dialogs.pick_export_path("chatui-export.json") else {
            return;
        };

        match self.data_service.export_all(&ExportRequest::all()) {
            Ok(artifact) => match self.dialogs.write_text(&path, &artifact.content) {
                Ok(()) => window.set_notice(
                    format!(
                        "Exported {} ({}).",
                        path.display(),
                        format_bytes(artifact.summary.bytes as u64)
                    )
                    .into(),
                ),
                Err(error) => window.set_notice(error.to_string().into()),
            },
            Err(error) => window.set_notice(error.to_string().into()),
        }

        if let Some(data) = self.data.borrow().as_ref() {
            data.reload();
            data.sync();
        }
    }

    fn import(self: &Rc<Self>, window: &ShellWindow) {
        let Some(path) = self.dialogs.pick_import_path() else {
            return;
        };
        let text = match self.dialogs.read_text(&path) {
            Ok(text) => text,
            Err(error) => {
                window.set_notice(error.to_string().into());
                return;
            }
        };

        match self.data_service.import_text(&text, ImportMode::Merge) {
            Ok(summary) => {
                window.set_notice(
                    format!(
                        "Imported {} conversation(s), {} message(s), and {} attachment(s).",
                        summary.conversations, summary.messages, summary.attachments
                    )
                    .into(),
                );
                if let Some(history) = self.history.borrow().as_ref() {
                    history.refresh();
                }
                if let Some(data) = self.data.borrow().as_ref() {
                    data.reload();
                    data.sync();
                }
                if let Some(chat) = self.chat.borrow().as_ref()
                    && let Some(id) = chat.conversation_id()
                {
                    chat.open(id);
                }
            }
            Err(error) => window.set_notice(error.to_string().into()),
        }
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
        window.set_notice(format!("Documentation: {DOCS_URL}").into());
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
        if let Some(chat) = self.chat.borrow().as_ref() {
            chat.poll();
        }
        if let Some(history) = self.history.borrow().as_ref() {
            history.poll();
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
            let hint = match current.send_key {
                SendKey::Enter => "Enter to send, Shift+Enter for a new line",
                SendKey::ModEnter => "Ctrl/Cmd+Enter to send, Enter for a new line",
            };
            chat.composer_window().set_send_key_hint(hint.into());
            chat.set_show_token_usage(current.show_token_usage);
        }
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
    let weak = controller.window().as_weak();
    let state = controller.state_handle();
    let composition = Rc::clone(composition);
    controller.window().on_navigate(move |index| {
        let Some(destination) = Destination::from_index(index as usize) else {
            return;
        };
        state.borrow_mut().navigate(destination);
        if let Some(window) = weak.upgrade() {
            sync_window(&window, &state.borrow());
            composition.ensure_and_sync(&window, destination);
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
    use chatui_domain::Theme;
    use window_state::MemoryWindowStateStore;

    fn composition() -> (tempfile::TempDir, Rc<DesktopComposition>, ShellController) {
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
        (directory, composition, shell)
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

        settings.window().invoke_send_key_changed(1);
        composition.poll_settings(shell.window());
        let chat = composition
            .chat
            .borrow()
            .as_ref()
            .cloned()
            .expect("chat controller");
        assert_eq!(
            chat.composer_window().get_send_key_hint(),
            "Ctrl/Cmd+Enter to send, Enter for a new line"
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
