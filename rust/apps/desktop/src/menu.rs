//! Native menu model for the desktop shell.
//!
//! The Slint `MenuBar` declares the native menu structure. This module is the
//! framework-independent model used by tests and by the command dispatcher. It
//! maps every menu item to a real [`Command`]; it does not duplicate business
//! logic from the shell or chat view models.

use crate::shortcuts::Shortcut;

/// A real application command.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum Command {
    /// Start a new chat.
    NewChat,
    /// Pick an attachment for the composer.
    AttachFile,
    /// Export data.
    Export,
    /// Import data.
    Import,
    /// Quit the application.
    Quit,
    /// Open search.
    Search,
    /// Copy the last response.
    CopyLastResponse,
    /// Toggle the sidebar or drawer.
    ToggleSidebar,
    /// Toggle the shortcuts help overlay.
    ShortcutsHelp,
    /// Navigate to the previous conversation.
    PreviousConversation,
    /// Navigate to the next conversation.
    NextConversation,
    /// Stop streaming, then close the top layer.
    StopOrClose,
    /// Show the documentation location.
    OpenDocs,
    /// Show the about information.
    About,
}

impl Command {
    /// Stable id used by the Slint menu callbacks.
    pub const fn id(self) -> &'static str {
        match self {
            Self::NewChat => "new-chat",
            Self::AttachFile => "attach-file",
            Self::Export => "export",
            Self::Import => "import",
            Self::Quit => "quit",
            Self::Search => "search",
            Self::CopyLastResponse => "copy-last-response",
            Self::ToggleSidebar => "toggle-sidebar",
            Self::ShortcutsHelp => "shortcuts-help",
            Self::PreviousConversation => "previous-conversation",
            Self::NextConversation => "next-conversation",
            Self::StopOrClose => "stop-or-close",
            Self::OpenDocs => "open-docs",
            Self::About => "about",
        }
    }

    /// Resolve a command from its stable id.
    pub fn from_id(id: &str) -> Option<Self> {
        Some(match id {
            "new-chat" => Self::NewChat,
            "attach-file" => Self::AttachFile,
            "export" => Self::Export,
            "import" => Self::Import,
            "quit" => Self::Quit,
            "search" => Self::Search,
            "copy-last-response" => Self::CopyLastResponse,
            "toggle-sidebar" => Self::ToggleSidebar,
            "shortcuts-help" => Self::ShortcutsHelp,
            "previous-conversation" => Self::PreviousConversation,
            "next-conversation" => Self::NextConversation,
            "stop-or-close" => Self::StopOrClose,
            "open-docs" => Self::OpenDocs,
            "about" => Self::About,
            _ => return None,
        })
    }

    /// The section 11.10 shortcut for this command, when one exists.
    pub const fn shortcut(self) -> Option<Shortcut> {
        match self {
            Self::NewChat => Some(Shortcut::NewChat),
            Self::Search => Some(Shortcut::Search),
            Self::CopyLastResponse => Some(Shortcut::CopyLastResponse),
            Self::ShortcutsHelp => Some(Shortcut::ShortcutsHelp),
            Self::PreviousConversation => Some(Shortcut::PreviousConversation),
            Self::NextConversation => Some(Shortcut::NextConversation),
            Self::StopOrClose => Some(Shortcut::StopOrClose),
            Self::AttachFile
            | Self::Export
            | Self::Import
            | Self::Quit
            | Self::ToggleSidebar
            | Self::OpenDocs
            | Self::About => None,
        }
    }
}

/// One menu item.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct MenuEntry {
    /// The command this item runs.
    pub command: Command,
    /// Visible label.
    pub label: &'static str,
    /// Optional section 11.10 shortcut.
    pub shortcut: Option<Shortcut>,
}

impl MenuEntry {
    const fn new(command: Command, label: &'static str) -> Self {
        Self {
            command,
            label,
            shortcut: command.shortcut(),
        }
    }
}

/// One top-level menu.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Menu {
    /// Menu title.
    pub title: &'static str,
    /// Items in display order.
    pub entries: Vec<MenuEntry>,
}

/// The complete menu bar model.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct MenuBar {
    menus: Vec<Menu>,
}

impl Default for MenuBar {
    fn default() -> Self {
        Self {
            menus: vec![
                Menu {
                    title: "File",
                    entries: vec![
                        MenuEntry::new(Command::NewChat, "New Chat"),
                        MenuEntry::new(Command::AttachFile, "Attach File…"),
                        MenuEntry::new(Command::Export, "Export…"),
                        MenuEntry::new(Command::Import, "Import…"),
                        MenuEntry::new(Command::Quit, "Quit"),
                    ],
                },
                Menu {
                    title: "Edit",
                    entries: vec![
                        MenuEntry::new(Command::Search, "Search"),
                        MenuEntry::new(Command::CopyLastResponse, "Copy Last Response"),
                    ],
                },
                Menu {
                    title: "View",
                    entries: vec![MenuEntry::new(Command::ToggleSidebar, "Toggle Sidebar")],
                },
                Menu {
                    title: "Conversation",
                    entries: vec![
                        MenuEntry::new(Command::PreviousConversation, "Previous Conversation"),
                        MenuEntry::new(Command::NextConversation, "Next Conversation"),
                        MenuEntry::new(Command::StopOrClose, "Stop"),
                    ],
                },
                Menu {
                    title: "Help",
                    entries: vec![
                        MenuEntry::new(Command::ShortcutsHelp, "Keyboard Shortcuts"),
                        MenuEntry::new(Command::OpenDocs, "Documentation"),
                        MenuEntry::new(Command::About, "About ChatUI"),
                    ],
                },
            ],
        }
    }
}

impl MenuBar {
    /// The default File/Edit/View/Conversation/Help menu bar.
    pub fn default_menu_bar() -> Self {
        Self::default()
    }

    /// Top-level menus.
    pub fn menus(&self) -> &[Menu] {
        &self.menus
    }

    /// Every entry in display order.
    pub fn entries(&self) -> impl Iterator<Item = &MenuEntry> {
        self.menus.iter().flat_map(|menu| menu.entries.iter())
    }

    /// Resolve a command from a Slint callback id.
    pub fn command_for_id(&self, id: &str) -> Option<Command> {
        Command::from_id(id).filter(|command| self.find(*command).is_some())
    }

    /// Find the entry for a command.
    pub fn find(&self, command: Command) -> Option<&MenuEntry> {
        self.entries().find(|entry| entry.command == command)
    }
}

/// The result of executing a command.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum CommandOutcome {
    /// The command ran.
    Handled,
    /// The command is not available in the current state.
    Unhandled,
    /// The command failed with a user-safe message.
    Error(String),
}

/// A command executor.
pub trait CommandHandler {
    /// Execute a command.
    fn execute(&mut self, command: Command) -> CommandOutcome;
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::shortcuts::ShortcutMap;

    #[test]
    fn default_menu_bar_has_the_required_menus() {
        let bar = MenuBar::default_menu_bar();
        let titles: Vec<_> = bar.menus().iter().map(|menu| menu.title).collect();
        assert_eq!(titles, vec!["File", "Edit", "View", "Conversation", "Help"]);
    }

    #[test]
    fn every_entry_maps_to_a_real_command() {
        let bar = MenuBar::default_menu_bar();
        for entry in bar.entries() {
            assert_eq!(bar.command_for_id(entry.command.id()), Some(entry.command));
            assert!(!entry.label.is_empty());
        }
    }

    #[test]
    fn command_ids_round_trip() {
        for command in [
            Command::NewChat,
            Command::AttachFile,
            Command::Export,
            Command::Import,
            Command::Quit,
            Command::Search,
            Command::CopyLastResponse,
            Command::ToggleSidebar,
            Command::ShortcutsHelp,
            Command::PreviousConversation,
            Command::NextConversation,
            Command::StopOrClose,
            Command::OpenDocs,
            Command::About,
        ] {
            assert_eq!(Command::from_id(command.id()), Some(command));
        }
        assert_eq!(Command::from_id("not-a-command"), None);
    }

    #[test]
    fn menu_shortcuts_match_the_shortcut_map() {
        let bar = MenuBar::default_menu_bar();
        let map = ShortcutMap::default();
        let map_shortcuts: Vec<_> = map
            .bindings()
            .iter()
            .map(|binding| binding.shortcut)
            .collect();

        for entry in bar.entries() {
            if let Some(shortcut) = entry.shortcut {
                assert!(
                    map_shortcuts.contains(&shortcut),
                    "{} is not in the shortcut map",
                    shortcut.label()
                );
            }
        }

        for shortcut in map_shortcuts {
            assert!(
                bar.entries().any(|entry| entry.shortcut == Some(shortcut)),
                "{} is missing from the menu model",
                shortcut.label()
            );
        }
    }

    #[test]
    fn no_duplicate_commands() {
        let bar = MenuBar::default_menu_bar();
        let mut seen = Vec::new();
        for entry in bar.entries() {
            assert!(
                !seen.contains(&entry.command),
                "duplicate command {}",
                entry.command.id()
            );
            seen.push(entry.command);
        }
    }

    #[test]
    fn menu_model_contains_no_hidden_urls() {
        let bar = MenuBar::default_menu_bar();
        for entry in bar.entries() {
            assert!(!entry.label.contains("http"));
            assert!(!entry.command.id().contains("http"));
        }
    }
}
