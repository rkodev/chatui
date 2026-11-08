//! Keyboard shortcut policy for the desktop shell.
//!
//! The Slint menu bar owns the native shortcut bindings. This module is the
//! framework-independent source of truth used by tests, the shortcuts-help
//! overlay, and the menu model. It also encodes the `Escape` precedence from
//! spec section 11.10: stop streaming first, then close the top layer.
//!
//! Shortcuts never consume plain text input. Only modified shortcuts are
//! resolved while a text field has focus.

use chatui_ui::accessibility::Modifiers;

/// Keys used by the section 11.10 shortcut map.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum ShortcutKey {
    /// `K`.
    K,
    /// `O`.
    O,
    /// `C`.
    C,
    /// `/`.
    Slash,
    /// `Q`.
    Q,
    /// `Escape`.
    Escape,
    /// Up arrow.
    Up,
    /// Down arrow.
    Down,
}

/// A section 11.10 shortcut.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum Shortcut {
    /// Command palette / search.
    Search,
    /// New chat.
    NewChat,
    /// Toggle shortcuts help.
    ShortcutsHelp,
    /// Stop streaming, then close the top layer.
    StopOrClose,
    /// Copy the last response.
    CopyLastResponse,
    /// Previous conversation.
    PreviousConversation,
    /// Next conversation.
    NextConversation,
}

impl Shortcut {
    /// Human-readable key combination.
    pub const fn label(self) -> &'static str {
        match self {
            Self::Search => "Ctrl/Cmd+K",
            Self::NewChat => "Ctrl/Cmd+Shift+O",
            Self::ShortcutsHelp => "Ctrl/Cmd+/",
            Self::StopOrClose => "Esc",
            Self::CopyLastResponse => "Ctrl/Cmd+Shift+C",
            Self::PreviousConversation => "Alt+Up",
            Self::NextConversation => "Alt+Down",
        }
    }

    /// Human-readable action.
    pub const fn description(self) -> &'static str {
        match self {
            Self::Search => "Search conversations",
            Self::NewChat => "New chat",
            Self::ShortcutsHelp => "Toggle keyboard shortcuts",
            Self::StopOrClose => "Stop streaming or close the top layer",
            Self::CopyLastResponse => "Copy the last response",
            Self::PreviousConversation => "Previous conversation",
            Self::NextConversation => "Next conversation",
        }
    }
}

/// One key binding.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct ShortcutBinding {
    /// The shortcut this binding activates.
    pub shortcut: Shortcut,
    /// The key.
    pub key: ShortcutKey,
    /// Required modifiers.
    pub modifiers: Modifiers,
}

/// The state that affects shortcut dispatch.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub struct ShortcutContext {
    /// Whether a text field currently has focus.
    pub text_input_focused: bool,
    /// Whether a response is streaming.
    pub streaming: bool,
    /// Whether a dialog, sheet, drawer, or help overlay is open.
    pub top_layer_open: bool,
}

/// The action produced by a shortcut.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum ShortcutAction {
    /// Open search.
    Search,
    /// Start a new chat.
    NewChat,
    /// Toggle the shortcuts help overlay.
    ShortcutsHelp,
    /// Stop the active stream.
    StopStreaming,
    /// Close the top layer.
    CloseTopLayer,
    /// Copy the last response.
    CopyLastResponse,
    /// Navigate to the previous conversation.
    PreviousConversation,
    /// Navigate to the next conversation.
    NextConversation,
    /// The key was not handled.
    None,
}

/// The section 11.10 shortcut map.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ShortcutMap {
    bindings: Vec<ShortcutBinding>,
}

impl Default for ShortcutMap {
    fn default() -> Self {
        Self {
            bindings: vec![
                ShortcutBinding {
                    shortcut: Shortcut::Search,
                    key: ShortcutKey::K,
                    modifiers: primary(),
                },
                ShortcutBinding {
                    shortcut: Shortcut::NewChat,
                    key: ShortcutKey::O,
                    modifiers: primary_shift(),
                },
                ShortcutBinding {
                    shortcut: Shortcut::ShortcutsHelp,
                    key: ShortcutKey::Slash,
                    modifiers: primary(),
                },
                ShortcutBinding {
                    shortcut: Shortcut::StopOrClose,
                    key: ShortcutKey::Escape,
                    modifiers: Modifiers::NONE,
                },
                ShortcutBinding {
                    shortcut: Shortcut::CopyLastResponse,
                    key: ShortcutKey::C,
                    modifiers: primary_shift(),
                },
                ShortcutBinding {
                    shortcut: Shortcut::PreviousConversation,
                    key: ShortcutKey::Up,
                    modifiers: alt(),
                },
                ShortcutBinding {
                    shortcut: Shortcut::NextConversation,
                    key: ShortcutKey::Down,
                    modifiers: alt(),
                },
            ],
        }
    }
}

impl ShortcutMap {
    /// Every binding in display order.
    pub fn bindings(&self) -> &[ShortcutBinding] {
        &self.bindings
    }

    /// Resolve a key press to a shortcut.
    ///
    /// `Control` and `Meta` are treated as the same primary modifier so the
    /// same map works on Linux/Windows (`Ctrl`) and macOS (`Cmd`).
    pub fn resolve(
        &self,
        key: ShortcutKey,
        modifiers: Modifiers,
        context: &ShortcutContext,
    ) -> Option<Shortcut> {
        if key == ShortcutKey::Escape {
            return Some(Shortcut::StopOrClose);
        }

        if context.text_input_focused && !has_primary_or_alt(modifiers) {
            return None;
        }

        let normalized = normalize(modifiers);
        self.bindings
            .iter()
            .find(|binding| binding.key == key && normalize(binding.modifiers) == normalized)
            .map(|binding| binding.shortcut)
    }

    /// Dispatch a key press, applying the `Escape` precedence.
    pub fn dispatch(
        &self,
        key: ShortcutKey,
        modifiers: Modifiers,
        context: &ShortcutContext,
    ) -> ShortcutAction {
        if key == ShortcutKey::Escape {
            if context.streaming {
                return ShortcutAction::StopStreaming;
            }
            if context.top_layer_open {
                return ShortcutAction::CloseTopLayer;
            }
            return ShortcutAction::None;
        }

        match self.resolve(key, modifiers, context) {
            Some(Shortcut::Search) => ShortcutAction::Search,
            Some(Shortcut::NewChat) => ShortcutAction::NewChat,
            Some(Shortcut::ShortcutsHelp) => ShortcutAction::ShortcutsHelp,
            Some(Shortcut::StopOrClose) => ShortcutAction::None,
            Some(Shortcut::CopyLastResponse) => ShortcutAction::CopyLastResponse,
            Some(Shortcut::PreviousConversation) => ShortcutAction::PreviousConversation,
            Some(Shortcut::NextConversation) => ShortcutAction::NextConversation,
            None => ShortcutAction::None,
        }
    }

    /// Lines for the shortcuts-help overlay.
    pub fn help_lines(&self) -> Vec<String> {
        self.bindings
            .iter()
            .map(|binding| {
                format!(
                    "{}  {}",
                    binding.shortcut.label(),
                    binding.shortcut.description()
                )
            })
            .collect()
    }
}

fn primary() -> Modifiers {
    Modifiers {
        control: true,
        ..Modifiers::NONE
    }
}

fn primary_shift() -> Modifiers {
    Modifiers {
        control: true,
        shift: true,
        ..Modifiers::NONE
    }
}

fn alt() -> Modifiers {
    Modifiers {
        alt: true,
        ..Modifiers::NONE
    }
}

fn normalize(modifiers: Modifiers) -> Modifiers {
    Modifiers {
        control: modifiers.control || modifiers.meta,
        meta: false,
        ..modifiers
    }
}

fn has_primary_or_alt(modifiers: Modifiers) -> bool {
    modifiers.control || modifiers.meta || modifiers.alt
}

#[cfg(test)]
mod tests {
    use super::*;

    fn context() -> ShortcutContext {
        ShortcutContext::default()
    }

    #[test]
    fn default_map_has_every_spec_shortcut() {
        let map = ShortcutMap::default();
        let shortcuts: Vec<_> = map
            .bindings()
            .iter()
            .map(|binding| binding.shortcut)
            .collect();
        assert_eq!(
            shortcuts,
            vec![
                Shortcut::Search,
                Shortcut::NewChat,
                Shortcut::ShortcutsHelp,
                Shortcut::StopOrClose,
                Shortcut::CopyLastResponse,
                Shortcut::PreviousConversation,
                Shortcut::NextConversation,
            ]
        );
    }

    #[test]
    fn resolves_primary_shortcuts_with_control_or_meta() {
        let map = ShortcutMap::default();
        for modifiers in [
            primary(),
            Modifiers {
                meta: true,
                ..Modifiers::NONE
            },
        ] {
            assert_eq!(
                map.resolve(ShortcutKey::K, modifiers, &context()),
                Some(Shortcut::Search)
            );
            assert_eq!(
                map.resolve(ShortcutKey::Slash, modifiers, &context()),
                Some(Shortcut::ShortcutsHelp)
            );
        }
    }

    #[test]
    fn resolves_shifted_shortcuts() {
        let map = ShortcutMap::default();
        assert_eq!(
            map.resolve(ShortcutKey::O, primary_shift(), &context()),
            Some(Shortcut::NewChat)
        );
        assert_eq!(
            map.resolve(ShortcutKey::C, primary_shift(), &context()),
            Some(Shortcut::CopyLastResponse)
        );
    }

    #[test]
    fn resolves_alt_navigation() {
        let map = ShortcutMap::default();
        assert_eq!(
            map.resolve(ShortcutKey::Up, alt(), &context()),
            Some(Shortcut::PreviousConversation)
        );
        assert_eq!(
            map.resolve(ShortcutKey::Down, alt(), &context()),
            Some(Shortcut::NextConversation)
        );
    }

    #[test]
    fn escape_stops_streaming_before_closing_the_top_layer() {
        let map = ShortcutMap::default();
        let streaming = ShortcutContext {
            streaming: true,
            top_layer_open: true,
            ..ShortcutContext::default()
        };
        assert_eq!(
            map.dispatch(ShortcutKey::Escape, Modifiers::NONE, &streaming),
            ShortcutAction::StopStreaming
        );

        let layer = ShortcutContext {
            streaming: false,
            top_layer_open: true,
            ..ShortcutContext::default()
        };
        assert_eq!(
            map.dispatch(ShortcutKey::Escape, Modifiers::NONE, &layer),
            ShortcutAction::CloseTopLayer
        );

        assert_eq!(
            map.dispatch(ShortcutKey::Escape, Modifiers::NONE, &context()),
            ShortcutAction::None
        );
    }

    #[test]
    fn text_input_does_not_steal_plain_keys() {
        let map = ShortcutMap::default();
        let focused = ShortcutContext {
            text_input_focused: true,
            ..ShortcutContext::default()
        };
        assert_eq!(map.resolve(ShortcutKey::K, Modifiers::NONE, &focused), None);
        assert_eq!(map.resolve(ShortcutKey::O, Modifiers::NONE, &focused), None);
        assert_eq!(map.resolve(ShortcutKey::C, Modifiers::NONE, &focused), None);
        assert_eq!(
            map.resolve(ShortcutKey::Slash, Modifiers::NONE, &focused),
            None
        );
    }

    #[test]
    fn text_input_allows_modified_shortcuts() {
        let map = ShortcutMap::default();
        let focused = ShortcutContext {
            text_input_focused: true,
            ..ShortcutContext::default()
        };
        assert_eq!(
            map.resolve(ShortcutKey::K, primary(), &focused),
            Some(Shortcut::Search)
        );
        assert_eq!(
            map.resolve(ShortcutKey::Up, alt(), &focused),
            Some(Shortcut::PreviousConversation)
        );
    }

    #[test]
    fn help_lines_cover_every_shortcut() {
        let map = ShortcutMap::default();
        let lines = map.help_lines();
        assert_eq!(lines.len(), map.bindings().len());
        for binding in map.bindings() {
            assert!(
                lines
                    .iter()
                    .any(|line| line.contains(binding.shortcut.label()))
            );
            assert!(
                lines
                    .iter()
                    .any(|line| line.contains(binding.shortcut.description()))
            );
        }
    }
}
