//! Accessibility primitives: focus rings, focus order, and keyboard traversal.
//!
//! Slint provides the AccessKit bridge and native `FocusScope` traversal. This
//! module owns the framework-independent policy: which elements are focusable,
//! how Tab/Shift+Tab and arrow keys move through them, and when the focus ring
//! is visible. The shell binds these values to Slint properties.

use crate::tokens::{Rgba, Tokens};

/// The visible focus ring.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct FocusRing {
    /// Ring width in logical pixels.
    pub width: f32,
    /// Ring offset in logical pixels.
    pub offset: f32,
    /// Ring color.
    pub color: Rgba,
}

impl FocusRing {
    /// Build the focus ring from the active token set.
    pub const fn from_tokens(tokens: &Tokens) -> Self {
        Self {
            width: tokens.focus_ring_width,
            offset: tokens.focus_ring_offset,
            color: tokens.focus_ring_color,
        }
    }
}

/// Accessible role for a focusable element.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum AccessibleRole {
    /// A button.
    Button,
    /// A link.
    Link,
    /// A text input.
    TextInput,
    /// A heading.
    Heading,
    /// A list.
    List,
    /// A list item.
    ListItem,
    /// A dialog.
    Dialog,
    /// A navigation landmark.
    Navigation,
    /// The main landmark.
    Main,
    /// A status or live region.
    Status,
}

/// A focusable element with an accessible name.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub struct Focusable {
    /// Stable identifier used by tests and callbacks.
    pub id: &'static str,
    /// Accessible name. Icon-only controls must still have one.
    pub label: &'static str,
    /// Accessible role.
    pub role: AccessibleRole,
}

impl Focusable {
    /// Create a focusable element.
    pub const fn new(id: &'static str, label: &'static str, role: AccessibleRole) -> Self {
        Self { id, label, role }
    }
}

/// Keyboard keys used by the shell.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum Key {
    /// Tab.
    Tab,
    /// Shift+Tab.
    BackTab,
    /// Up arrow.
    Up,
    /// Down arrow.
    Down,
    /// Left arrow.
    Left,
    /// Right arrow.
    Right,
    /// Home.
    Home,
    /// End.
    End,
    /// Enter.
    Enter,
    /// Space.
    Space,
    /// Escape.
    Escape,
}

/// Keyboard modifiers.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Default)]
pub struct Modifiers {
    /// Shift.
    pub shift: bool,
    /// Control.
    pub control: bool,
    /// Alt.
    pub alt: bool,
    /// Meta/Command.
    pub meta: bool,
}

impl Modifiers {
    /// No modifiers.
    pub const NONE: Self = Self {
        shift: false,
        control: false,
        alt: false,
        meta: false,
    };

    /// Shift only.
    pub const SHIFT: Self = Self {
        shift: true,
        control: false,
        alt: false,
        meta: false,
    };
}

/// The action produced by a key press.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum KeyboardAction {
    /// Focus moved to the next element.
    FocusNext,
    /// Focus moved to the previous element.
    FocusPrevious,
    /// Focus moved to the first element.
    FocusFirst,
    /// Focus moved to the last element.
    FocusLast,
    /// The focused element should be activated.
    Activate,
    /// The focused element should be dismissed.
    Dismiss,
    /// The key was not handled.
    None,
}

/// An ordered set of focusable elements.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct FocusOrder {
    items: Vec<Focusable>,
    current: Option<usize>,
    focus_visible: bool,
}

impl FocusOrder {
    /// Create a focus order from an ordered list of focusable elements.
    pub fn new(items: Vec<Focusable>) -> Self {
        Self {
            items,
            current: None,
            focus_visible: false,
        }
    }

    /// The focusable elements in traversal order.
    pub fn items(&self) -> &[Focusable] {
        &self.items
    }

    /// The currently focused element.
    pub fn current(&self) -> Option<&Focusable> {
        self.current.and_then(|index| self.items.get(index))
    }

    /// The index of the currently focused element.
    pub fn current_index(&self) -> Option<usize> {
        self.current
    }

    /// Whether the focus ring should be visible.
    pub fn focus_visible(&self) -> bool {
        self.focus_visible
    }

    /// Set whether the focus ring should be visible.
    pub fn set_focus_visible(&mut self, visible: bool) {
        self.focus_visible = visible;
    }

    /// Focus the first element.
    pub fn focus_first(&mut self) -> Option<&Focusable> {
        self.current = if self.items.is_empty() { None } else { Some(0) };
        self.focus_visible = true;
        self.current()
    }

    /// Focus the last element.
    pub fn focus_last(&mut self) -> Option<&Focusable> {
        self.current = self.items.len().checked_sub(1);
        self.focus_visible = true;
        self.current()
    }

    /// Focus the next element, wrapping at the end.
    pub fn focus_next(&mut self) -> Option<&Focusable> {
        if self.items.is_empty() {
            self.current = None;
            return None;
        }

        self.current = Some(match self.current {
            Some(index) => (index + 1) % self.items.len(),
            None => 0,
        });
        self.focus_visible = true;
        self.current()
    }

    /// Focus the previous element, wrapping at the start.
    pub fn focus_previous(&mut self) -> Option<&Focusable> {
        if self.items.is_empty() {
            self.current = None;
            return None;
        }

        self.current = Some(match self.current {
            Some(0) | None => self.items.len() - 1,
            Some(index) => index - 1,
        });
        self.focus_visible = true;
        self.current()
    }

    /// Focus a specific element by id.
    pub fn focus(&mut self, id: &str) -> Option<&Focusable> {
        self.current = self.items.iter().position(|item| item.id == id);
        self.focus_visible = true;
        self.current()
    }

    /// Focus a specific element from a pointer interaction.
    ///
    /// Pointer focus does not show the keyboard focus ring.
    pub fn focus_from_pointer(&mut self, id: &str) -> Option<&Focusable> {
        self.current = self.items.iter().position(|item| item.id == id);
        self.focus_visible = false;
        self.current()
    }

    /// Clear focus.
    pub fn clear(&mut self) {
        self.current = None;
        self.focus_visible = false;
    }

    /// Handle a key press and update focus.
    pub fn handle_key(&mut self, key: Key, modifiers: Modifiers) -> KeyboardAction {
        match key {
            Key::Tab if modifiers.shift => {
                self.focus_previous();
                KeyboardAction::FocusPrevious
            }
            Key::Tab => {
                self.focus_next();
                KeyboardAction::FocusNext
            }
            Key::BackTab => {
                self.focus_previous();
                KeyboardAction::FocusPrevious
            }
            Key::Down | Key::Right => {
                self.focus_next();
                KeyboardAction::FocusNext
            }
            Key::Up | Key::Left => {
                self.focus_previous();
                KeyboardAction::FocusPrevious
            }
            Key::Home => {
                self.focus_first();
                KeyboardAction::FocusFirst
            }
            Key::End => {
                self.focus_last();
                KeyboardAction::FocusLast
            }
            Key::Enter | Key::Space => {
                if self.current.is_some() {
                    KeyboardAction::Activate
                } else {
                    KeyboardAction::None
                }
            }
            Key::Escape => KeyboardAction::Dismiss,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn order() -> FocusOrder {
        FocusOrder::new(vec![
            Focusable::new("toggle", "Toggle navigation", AccessibleRole::Button),
            Focusable::new("chat", "Chat", AccessibleRole::Button),
            Focusable::new("history", "History", AccessibleRole::Button),
            Focusable::new("providers", "Providers", AccessibleRole::Button),
            Focusable::new("settings", "Settings", AccessibleRole::Button),
            Focusable::new("data", "Data", AccessibleRole::Button),
        ])
    }

    #[test]
    fn focus_ring_comes_from_tokens() {
        let tokens = Tokens::light();
        let ring = FocusRing::from_tokens(&tokens);
        assert_eq!(ring.width, 2.0);
        assert_eq!(ring.offset, 2.0);
        assert_eq!(ring.color, tokens.focus_ring_color);
    }

    #[test]
    fn tab_moves_forward_and_wraps() {
        let mut order = order();
        assert_eq!(order.focus_next().map(|item| item.id), Some("toggle"));
        assert_eq!(order.focus_next().map(|item| item.id), Some("chat"));
        assert!(order.focus_visible());

        for _ in 0..4 {
            order.focus_next();
        }
        assert_eq!(order.current().map(|item| item.id), Some("data"));
        assert_eq!(order.focus_next().map(|item| item.id), Some("toggle"));
    }

    #[test]
    fn shift_tab_moves_backward_and_wraps() {
        let mut order = order();
        assert_eq!(order.focus_previous().map(|item| item.id), Some("data"));
        assert_eq!(order.focus_previous().map(|item| item.id), Some("settings"));
        assert_eq!(order.focus_first().map(|item| item.id), Some("toggle"));
        assert_eq!(order.focus_previous().map(|item| item.id), Some("data"));
    }

    #[test]
    fn home_and_end_jump_to_the_edges() {
        let mut order = order();
        assert_eq!(order.focus_last().map(|item| item.id), Some("data"));
        assert_eq!(order.focus_first().map(|item| item.id), Some("toggle"));
    }

    #[test]
    fn every_destination_is_reachable_by_keyboard() {
        let mut order = order();
        let mut seen = Vec::new();
        for _ in 0..order.items().len() {
            if let Some(item) = order.focus_next() {
                seen.push(item.id);
            }
        }
        assert_eq!(
            seen,
            ["toggle", "chat", "history", "providers", "settings", "data"]
        );
    }

    #[test]
    fn pointer_focus_hides_the_ring() {
        let mut order = order();
        order.focus("chat");
        assert!(order.focus_visible());
        order.focus_from_pointer("history");
        assert!(!order.focus_visible());
        assert_eq!(order.current().map(|item| item.id), Some("history"));
    }

    #[test]
    fn key_handling_maps_to_actions() {
        let mut order = order();
        assert_eq!(
            order.handle_key(Key::Tab, Modifiers::NONE),
            KeyboardAction::FocusNext
        );
        assert_eq!(
            order.handle_key(Key::Tab, Modifiers::SHIFT),
            KeyboardAction::FocusPrevious
        );
        assert_eq!(
            order.handle_key(Key::BackTab, Modifiers::NONE),
            KeyboardAction::FocusPrevious
        );
        assert_eq!(
            order.handle_key(Key::Down, Modifiers::NONE),
            KeyboardAction::FocusNext
        );
        assert_eq!(
            order.handle_key(Key::Up, Modifiers::NONE),
            KeyboardAction::FocusPrevious
        );
        assert_eq!(
            order.handle_key(Key::Home, Modifiers::NONE),
            KeyboardAction::FocusFirst
        );
        assert_eq!(
            order.handle_key(Key::End, Modifiers::NONE),
            KeyboardAction::FocusLast
        );
        assert_eq!(
            order.handle_key(Key::Enter, Modifiers::NONE),
            KeyboardAction::Activate
        );
        assert_eq!(
            order.handle_key(Key::Escape, Modifiers::NONE),
            KeyboardAction::Dismiss
        );
    }

    #[test]
    fn empty_order_handles_keys_without_panicking() {
        let mut order = FocusOrder::new(Vec::new());
        assert_eq!(order.focus_next(), None);
        assert_eq!(order.focus_previous(), None);
        assert_eq!(order.focus_first(), None);
        assert_eq!(order.focus_last(), None);
        assert_eq!(
            order.handle_key(Key::Enter, Modifiers::NONE),
            KeyboardAction::None
        );
    }
}
