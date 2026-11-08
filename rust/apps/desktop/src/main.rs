//! Desktop entry point and integration wiring.
//!
//! The shell and feature view models live in `chatui-ui`. This binary owns the
//! desktop-only integration layer: native menus, shortcuts, window state,
//! dialogs, clipboard, drag-and-drop, system theme, deep links, and
//! single-instance forwarding.
//!
//! `--smoke` opens the real window, runs the event loop briefly, and exits so
//! unattended verification can exercise the shell. `--print-menu`,
//! `--print-shortcuts`, and `--copy-test` are headless smoke helpers.

// The integration modules expose a small tested API. Some helpers are used only
// by tests or by platform-specific paths, so dead-code analysis is not useful
// for this binary crate.
#![allow(dead_code)]

mod clipboard;
mod deep_link;
mod dialogs;
mod menu;
mod shortcuts;
mod single_instance;
mod updater;
mod window_state;

use std::cell::{Cell, RefCell};
use std::path::{Path, PathBuf};
use std::rc::Rc;
use std::time::Duration;

use chatui_domain::settings::SendKey;
use chatui_storage::AttachmentStore;
use chatui_ui::chat::ComposerState;
use chatui_ui::settings::{APP_NAME, APP_VERSION, DOCS_URL};
use chatui_ui::shell::{Destination, ShellController, ShellState, ShellWindow, sync_window};
use slint::{ComponentHandle, ModelRc, VecModel};

use clipboard::{
    ClipboardError, ClipboardKind, ClipboardPayload, ClipboardService, NativeClipboard,
    copy_payload,
};
use deep_link::{DeepLinkRoute, DeepLinkState};
use dialogs::{DialogService, NativeDialogService, add_dropped_files_to_composer};
use menu::{Command, CommandOutcome, MenuBar};
use shortcuts::{ShortcutContext, ShortcutKey, ShortcutMap};
use single_instance::{InstanceGuard, SingleInstance, SingleInstanceError};
use window_state::{
    DEFAULT_WIDTH, FileWindowStateStore, NativeSystemThemeProbe, SystemThemeProbe,
    WindowStateController,
};

#[cfg(unix)]
type PlatformSingleInstance = single_instance::UnixSingleInstance;
#[cfg(not(unix))]
type PlatformSingleInstance = single_instance::NoopSingleInstance;

fn main() -> Result<(), Box<dyn std::error::Error>> {
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

    let deep_link = deep_link_arg(&args);
    let onboarding_complete = !args.iter().any(|arg| arg == "--onboarding-incomplete");
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

    let context = Rc::new(DesktopContext {
        state: controller.state_handle(),
        composer: RefCell::new(ComposerState::with_defaults("desktop", SendKey::Enter)),
        attachment_store: AttachmentStore::with_app_data_dir().ok(),
        clipboard: NativeClipboard,
        dialogs: NativeDialogService,
        last_response: RefCell::new(None),
        streaming: Cell::new(false),
        deep_links: RefCell::new(DeepLinkState::new()),
        shortcut_map,
        menu_bar: MenuBar::default_menu_bar(),
        window_state,
        onboarding_complete,
    });

    if let Some(link) = deep_link.as_deref() {
        context.handle_deep_link(controller.window(), link);
    }

    wire_menu(&controller, &context);
    wire_drop(&controller, &context);
    wire_close(&controller, &context);

    let mut timers = Vec::new();
    if let Some(guard) = guard {
        timers.push(wire_single_instance(&controller, &context, guard));
    }
    timers.push(wire_system_theme(&controller, &context));

    if args.iter().any(|arg| arg == "--smoke") {
        let weak = controller.window().as_weak();
        let context = Rc::clone(&context);
        slint::Timer::single_shot(Duration::from_millis(1500), move || {
            if let Some(window) = weak.upgrade() {
                context.save_window_state(&window);
            }
            let _ = slint::quit_event_loop();
        });
    }

    let result = controller.run();
    context.save_window_state(controller.window());
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

fn wire_menu(controller: &ShellController, context: &Rc<DesktopContext>) {
    let weak = controller.window().as_weak();
    let context = Rc::clone(context);
    controller.window().on_menu_command(move |id| {
        let Some(window) = weak.upgrade() else {
            return;
        };
        let Some(command) = context.menu_bar.command_for_id(id.as_str()) else {
            return;
        };
        let _ = context.execute(&window, command);
    });
}

fn wire_drop(controller: &ShellController, context: &Rc<DesktopContext>) {
    let weak = controller.window().as_weak();
    let context = Rc::clone(context);
    controller.window().on_files_dropped(move |data| {
        let Some(window) = weak.upgrade() else {
            return;
        };
        let paths: Vec<PathBuf> = match data.file_paths() {
            Ok(paths) => paths.map(Path::to_path_buf).collect(),
            Err(_) => Vec::new(),
        };
        context.add_paths(&window, &paths);
    });
}

fn wire_close(controller: &ShellController, context: &Rc<DesktopContext>) {
    let weak = controller.window().as_weak();
    let context = Rc::clone(context);
    controller.window().window().on_close_requested(move || {
        if let Some(window) = weak.upgrade() {
            context.save_window_state(&window);
        }
        slint::CloseRequestResponse::HideWindow
    });
}

fn wire_single_instance(
    controller: &ShellController,
    context: &Rc<DesktopContext>,
    guard: InstanceGuard,
) -> slint::Timer {
    let weak = controller.window().as_weak();
    let context = Rc::clone(context);
    let timer = slint::Timer::default();
    timer.start(
        slint::TimerMode::Repeated,
        Duration::from_millis(250),
        move || {
            let Some(window) = weak.upgrade() else {
                return;
            };
            while let Some(message) = guard.try_recv() {
                context.handle_forwarded(&window, &message);
            }
        },
    );
    timer
}

fn wire_system_theme(controller: &ShellController, context: &Rc<DesktopContext>) -> slint::Timer {
    let weak = controller.window().as_weak();
    let context = Rc::clone(context);
    let timer = slint::Timer::default();
    timer.start(
        slint::TimerMode::Repeated,
        Duration::from_secs(2),
        move || {
            if let Some(window) = weak.upgrade() {
                context.poll_system_theme(&window);
            }
        },
    );
    timer
}

struct DesktopContext {
    state: Rc<RefCell<ShellState>>,
    composer: RefCell<ComposerState>,
    attachment_store: Option<AttachmentStore>,
    clipboard: NativeClipboard,
    dialogs: NativeDialogService,
    last_response: RefCell<Option<String>>,
    streaming: Cell<bool>,
    deep_links: RefCell<DeepLinkState>,
    shortcut_map: ShortcutMap,
    menu_bar: MenuBar,
    window_state: WindowStateController,
    onboarding_complete: bool,
}

impl DesktopContext {
    fn execute(&self, window: &ShellWindow, command: Command) -> CommandOutcome {
        match command {
            Command::NewChat => {
                self.state.borrow_mut().navigate(Destination::Chat);
                self.sync(window);
                window.set_notice("Started a new chat.".into());
                CommandOutcome::Handled
            }
            Command::AttachFile => {
                if let Some(path) = self.dialogs.pick_attachment() {
                    self.add_paths(window, &[path]);
                }
                CommandOutcome::Handled
            }
            Command::Export => {
                if let Some(path) = self.dialogs.pick_export_path("chatui-export.json") {
                    window.set_notice(
                        format!("Export destination selected: {}", path.display()).into(),
                    );
                }
                CommandOutcome::Handled
            }
            Command::Import => {
                if let Some(path) = self.dialogs.pick_import_path() {
                    match self.dialogs.read_text(&path) {
                        Ok(text) => window.set_notice(
                            format!(
                                "Import file selected: {} ({} bytes).",
                                path.display(),
                                text.len()
                            )
                            .into(),
                        ),
                        Err(error) => window.set_notice(error.to_string().into()),
                    }
                }
                CommandOutcome::Handled
            }
            Command::Quit => {
                let _ = slint::quit_event_loop();
                CommandOutcome::Handled
            }
            Command::Search => {
                self.state.borrow_mut().navigate(Destination::History);
                self.sync(window);
                window.set_notice("Search conversations from History.".into());
                CommandOutcome::Handled
            }
            Command::CopyLastResponse => {
                let payload = self
                    .last_response
                    .borrow()
                    .clone()
                    .map(|text| ClipboardPayload {
                        text,
                        kind: ClipboardKind::Message,
                    })
                    .ok_or(ClipboardError::Empty);
                match payload.and_then(|payload| copy_payload(&self.clipboard, &payload)) {
                    Ok(()) => window.set_notice("Copied the last response.".into()),
                    Err(error) => window.set_notice(error.to_string().into()),
                }
                CommandOutcome::Handled
            }
            Command::ToggleSidebar => {
                self.state.borrow_mut().toggle_sidebar();
                self.sync(window);
                CommandOutcome::Handled
            }
            Command::ShortcutsHelp => {
                window.set_shortcuts_help_open(!window.get_shortcuts_help_open());
                CommandOutcome::Handled
            }
            Command::PreviousConversation => {
                window.set_notice("Conversation navigation is available from History.".into());
                CommandOutcome::Handled
            }
            Command::NextConversation => {
                window.set_notice("Conversation navigation is available from History.".into());
                CommandOutcome::Handled
            }
            Command::StopOrClose => {
                self.stop_or_close(window);
                CommandOutcome::Handled
            }
            Command::OpenDocs => {
                window.set_notice(format!("Documentation: {DOCS_URL}").into());
                CommandOutcome::Handled
            }
            Command::About => {
                window.set_notice(
                    format!(
                        "{APP_NAME} {APP_VERSION}. No telemetry. Every provider and documentation link is visible in settings."
                    )
                    .into(),
                );
                CommandOutcome::Handled
            }
        }
    }

    fn stop_or_close(&self, window: &ShellWindow) {
        let context = ShortcutContext {
            text_input_focused: false,
            streaming: self.streaming.get(),
            top_layer_open: window.get_shortcuts_help_open()
                || self.state.borrow().drawer_visible(),
        };
        match self.shortcut_map.dispatch(
            ShortcutKey::Escape,
            chatui_ui::accessibility::Modifiers::NONE,
            &context,
        ) {
            shortcuts::ShortcutAction::StopStreaming => {
                self.streaming.set(false);
                window.set_notice("Stopped streaming.".into());
            }
            shortcuts::ShortcutAction::CloseTopLayer => {
                if window.get_shortcuts_help_open() {
                    window.set_shortcuts_help_open(false);
                } else {
                    self.state.borrow_mut().close_drawer();
                    self.sync(window);
                }
            }
            _ => window.set_notice("Nothing to stop or close.".into()),
        }
    }

    fn add_paths(&self, window: &ShellWindow, paths: &[PathBuf]) {
        if paths.is_empty() {
            window.set_notice("No files were dropped.".into());
            return;
        }

        if let Some(store) = &self.attachment_store {
            let summary = add_dropped_files_to_composer(
                &mut self.composer.borrow_mut(),
                store,
                paths,
                "drop",
            );
            window.set_notice(summary.notice().into());
        } else {
            let results = dialogs::validate_dropped_files(paths);
            let accepted = results.iter().filter(|result| result.is_ok()).count();
            let rejected = results.len() - accepted;
            window.set_notice(
                format!("Validated {accepted} attachment(s); {rejected} rejected.").into(),
            );
        }
    }

    fn handle_deep_link(&self, window: &ShellWindow, input: &str) {
        let route = self
            .deep_links
            .borrow_mut()
            .handle(input, self.onboarding_complete);
        match route {
            DeepLinkRoute::Chat { id } => {
                self.state.borrow_mut().navigate(Destination::Chat);
                self.sync(window);
                window.set_notice(format!("Opened conversation {id}.").into());
            }
            DeepLinkRoute::OnboardingRequired => {
                window.set_notice("Finish onboarding before opening this conversation.".into());
            }
            DeepLinkRoute::NotFound => {
                window.set_notice("Conversation not found.".into());
            }
        }
    }

    fn handle_forwarded(&self, window: &ShellWindow, message: &str) {
        if message == "focus" {
            let _ = window.show();
            window.set_notice("ChatUI is already running.".into());
        } else if message.starts_with("chatui://") {
            self.handle_deep_link(window, message);
        }
    }

    fn poll_system_theme(&self, window: &ShellWindow) {
        let theme = NativeSystemThemeProbe.probe();
        let mut state = self.state.borrow_mut();
        if state.system_is_dark == theme.dark && state.system_reduced_motion == theme.reduced_motion
        {
            return;
        }
        state.system_is_dark = theme.dark;
        state.system_reduced_motion = theme.reduced_motion;
        drop(state);
        self.sync(window);
    }

    fn save_window_state(&self, window: &ShellWindow) {
        let _ = self.window_state.save(window.window());
    }

    fn sync(&self, window: &ShellWindow) {
        sync_window(window, &self.state.borrow());
    }
}
