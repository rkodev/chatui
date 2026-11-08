//! Responsive application shell.
//!
//! The shell is split into two layers:
//!
//! * [`ShellState`] is framework-independent state: breakpoint, layout mode,
//!   active destination, drawer/sidebar visibility, and appearance settings.
//! * [`ShellWindow`] is the Slint component that renders the state. The
//!   [`ShellController`] binds the two together and keeps the window in sync.
//!
//! Breakpoints follow spec section 11.9:
//!
//! | Width | Layout |
//! | --- | --- |
//! | `< 600 px` | Drawer navigation |
//! | `600-839 px` | Collapsible sidebar |
//! | `840-1279 px` | Persistent sidebar |
//! | `>= 1280 px` | Persistent sidebar plus params panel |

use std::cell::RefCell;
use std::rc::Rc;

use slint::{ModelRc, VecModel};

use crate::accessibility::{
    AccessibleRole, FocusOrder, FocusRing, Focusable, Key, KeyboardAction, Modifiers,
};
use crate::theme::{ResolvedTheme, ThemeSettings};
use crate::tokens::{ChatTypography, DensityScale, Motion, Tokens};

/// Width below which the shell uses drawer navigation.
pub const NARROW_BREAKPOINT: f32 = 600.0;
/// Width at which the sidebar becomes persistent.
pub const COMPACT_BREAKPOINT: f32 = 840.0;
/// Width at which the optional params panel can appear.
pub const WIDE_BREAKPOINT: f32 = 1280.0;
/// Sidebar width from spec section 11.1.
pub const SIDEBAR_WIDTH: f32 = 280.0;
/// Message column maximum width (48 rem at the default 16 px body size).
pub const MESSAGE_MAX_WIDTH: f32 = 768.0;

/// Responsive breakpoint.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum Breakpoint {
    /// `< 600 px`: drawer navigation.
    Narrow,
    /// `600-839 px`: collapsible sidebar.
    Compact,
    /// `840-1279 px`: persistent sidebar.
    Desktop,
    /// `>= 1280 px`: persistent sidebar plus params panel.
    Wide,
}

impl Breakpoint {
    /// Resolve a window width to a breakpoint.
    pub fn from_width(width: f32) -> Self {
        if width < NARROW_BREAKPOINT {
            Self::Narrow
        } else if width < COMPACT_BREAKPOINT {
            Self::Compact
        } else if width < WIDE_BREAKPOINT {
            Self::Desktop
        } else {
            Self::Wide
        }
    }

    /// The layout mode for this breakpoint.
    pub const fn layout(self) -> LayoutMode {
        match self {
            Self::Narrow => LayoutMode::Drawer,
            Self::Compact => LayoutMode::CollapsibleSidebar,
            Self::Desktop => LayoutMode::PersistentSidebar,
            Self::Wide => LayoutMode::PersistentSidebarWithPanel,
        }
    }

    /// Whether this is the narrow drawer layout.
    pub const fn is_narrow(self) -> bool {
        matches!(self, Self::Narrow)
    }

    /// Whether the sidebar is persistent at this breakpoint.
    pub const fn is_desktop(self) -> bool {
        matches!(self, Self::Desktop | Self::Wide)
    }
}

/// How the shell presents navigation at the current width.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum LayoutMode {
    /// Narrow window: navigation is a modal drawer.
    Drawer,
    /// Compact window: the sidebar can be collapsed.
    CollapsibleSidebar,
    /// Desktop window: the sidebar is always visible.
    PersistentSidebar,
    /// Wide window: the sidebar is visible and the params panel may open.
    PersistentSidebarWithPanel,
}

/// A top-level destination in the shell.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum Destination {
    /// Chat.
    Chat,
    /// Conversation history.
    History,
    /// Provider and model management.
    Providers,
    /// Settings.
    Settings,
    /// Data and backup.
    Data,
}

impl Destination {
    /// Every destination in navigation order.
    pub const ALL: [Self; 5] = [
        Self::Chat,
        Self::History,
        Self::Providers,
        Self::Settings,
        Self::Data,
    ];

    /// Visible label.
    pub const fn label(self) -> &'static str {
        match self {
            Self::Chat => "Chat",
            Self::History => "History",
            Self::Providers => "Providers",
            Self::Settings => "Settings",
            Self::Data => "Data",
        }
    }

    /// Accessible name. The visible label is already descriptive, so it is
    /// reused for screen readers.
    pub const fn accessible_label(self) -> &'static str {
        self.label()
    }

    /// Stable focus id.
    pub const fn id(self) -> &'static str {
        match self {
            Self::Chat => "nav-chat",
            Self::History => "nav-history",
            Self::Providers => "nav-providers",
            Self::Settings => "nav-settings",
            Self::Data => "nav-data",
        }
    }

    /// Index in [`Destination::ALL`].
    pub const fn index(self) -> usize {
        match self {
            Self::Chat => 0,
            Self::History => 1,
            Self::Providers => 2,
            Self::Settings => 3,
            Self::Data => 4,
        }
    }

    /// Resolve a destination from its index.
    pub const fn from_index(index: usize) -> Option<Self> {
        match index {
            0 => Some(Self::Chat),
            1 => Some(Self::History),
            2 => Some(Self::Providers),
            3 => Some(Self::Settings),
            4 => Some(Self::Data),
            _ => None,
        }
    }
}

/// Framework-independent shell state.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct ShellState {
    /// Current window width in logical pixels.
    pub width: f32,
    /// Active destination.
    pub active: Destination,
    /// Whether the compact sidebar is open.
    pub sidebar_open: bool,
    /// Whether the narrow drawer is open.
    pub drawer_open: bool,
    /// Appearance settings.
    pub theme: ThemeSettings,
    /// Whether the operating system reports a dark theme.
    pub system_is_dark: bool,
    /// Whether the operating system requests reduced motion.
    pub system_reduced_motion: bool,
}

impl ShellState {
    /// Create shell state for a window width.
    pub fn new(width: f32) -> Self {
        Self {
            width,
            active: Destination::Chat,
            sidebar_open: true,
            drawer_open: false,
            theme: ThemeSettings::default(),
            system_is_dark: false,
            system_reduced_motion: false,
        }
    }

    /// Current breakpoint.
    pub fn breakpoint(&self) -> Breakpoint {
        Breakpoint::from_width(self.width)
    }

    /// Current layout mode.
    pub fn layout(&self) -> LayoutMode {
        self.breakpoint().layout()
    }

    /// Whether the persistent/collapsible sidebar is visible.
    pub fn sidebar_visible(&self) -> bool {
        match self.layout() {
            LayoutMode::Drawer => false,
            LayoutMode::CollapsibleSidebar => self.sidebar_open,
            LayoutMode::PersistentSidebar | LayoutMode::PersistentSidebarWithPanel => true,
        }
    }

    /// Whether the narrow drawer is visible.
    pub fn drawer_visible(&self) -> bool {
        self.layout() == LayoutMode::Drawer && self.drawer_open
    }

    /// Update the window width.
    pub fn set_width(&mut self, width: f32) {
        self.width = width.max(0.0);
        if !self.breakpoint().is_narrow() {
            self.drawer_open = false;
        }
    }

    /// Toggle navigation.
    ///
    /// At narrow widths this opens or closes the drawer. At compact widths it
    /// collapses or expands the sidebar. At desktop widths the sidebar is
    /// persistent, so this is a no-op.
    pub fn toggle_sidebar(&mut self) {
        match self.layout() {
            LayoutMode::Drawer => self.drawer_open = !self.drawer_open,
            LayoutMode::CollapsibleSidebar => self.sidebar_open = !self.sidebar_open,
            LayoutMode::PersistentSidebar | LayoutMode::PersistentSidebarWithPanel => {}
        }
    }

    /// Open the narrow drawer.
    pub fn open_drawer(&mut self) {
        if self.breakpoint().is_narrow() {
            self.drawer_open = true;
        }
    }

    /// Close the narrow drawer.
    pub fn close_drawer(&mut self) {
        self.drawer_open = false;
    }

    /// Navigate to a destination. Navigating closes the narrow drawer.
    pub fn navigate(&mut self, destination: Destination) {
        self.active = destination;
        self.drawer_open = false;
    }

    /// Resolved theme.
    pub fn resolved_theme(&self) -> ResolvedTheme {
        self.theme.resolve_theme(self.system_is_dark)
    }

    /// Resolved tokens.
    pub fn tokens(&self) -> Tokens {
        self.theme.tokens(self.system_is_dark)
    }

    /// Resolved density scale.
    pub fn density_scale(&self) -> DensityScale {
        self.theme.density_scale(&self.tokens())
    }

    /// Resolved chat typography.
    pub fn chat_typography(&self) -> ChatTypography {
        self.theme.chat_typography()
    }

    /// Resolved motion.
    pub fn motion(&self) -> Motion {
        self.theme.motion(self.system_reduced_motion)
    }

    /// Whether reduced motion is active.
    pub fn reduced_motion(&self) -> bool {
        self.theme.reduced_motion(self.system_reduced_motion)
    }

    /// Focus ring for the current theme.
    pub fn focus_ring(&self) -> FocusRing {
        FocusRing::from_tokens(&self.tokens())
    }

    /// Focus order for the current layout.
    pub fn focus_order(&self) -> FocusOrder {
        let mut items = Vec::new();

        if !self.breakpoint().is_desktop() {
            items.push(Focusable::new(
                "toggle",
                "Toggle navigation",
                AccessibleRole::Button,
            ));
        }

        for destination in Destination::ALL {
            items.push(Focusable::new(
                destination.id(),
                destination.accessible_label(),
                AccessibleRole::Button,
            ));
        }

        if self.drawer_visible() {
            items.push(Focusable::new(
                "close",
                "Close navigation",
                AccessibleRole::Button,
            ));
        }

        FocusOrder::new(items)
    }

    /// Handle a key press.
    ///
    /// Slint owns native focus traversal. This helper covers the shell-level
    /// policy (Escape closes the drawer) and exposes the same traversal
    /// primitives to tests and non-Slint callers.
    pub fn handle_key(&mut self, key: Key, modifiers: Modifiers) -> KeyboardAction {
        if key == Key::Escape {
            if self.drawer_visible() {
                self.close_drawer();
            }
            return KeyboardAction::Dismiss;
        }

        let mut order = self.focus_order();
        order.focus(self.active.id());
        order.handle_key(key, modifiers)
    }
}

slint::slint! {
    import { Button } from "std-widgets.slint";

    export struct ThemeTokens {
        bg-canvas: color,
        bg-surface: color,
        bg-surface-raised: color,
        bg-user-bubble: color,
        bg-code: color,
        fg-primary: color,
        fg-secondary: color,
        fg-muted: color,
        fg-on-accent: color,
        accent: color,
        accent-hover: color,
        border-subtle: color,
        border-strong: color,
        danger: color,
        warning: color,
        success: color,
        space-1: length,
        space-2: length,
        space-3: length,
        space-4: length,
        space-5: length,
        space-6: length,
        space-7: length,
        radius-sm: length,
        radius-md: length,
        radius-lg: length,
        radius-full: length,
        font-body-family: string,
        font-body-size: length,
        font-body-line-height: length,
        font-code-family: string,
        font-code-size: length,
        font-heading-family: string,
        font-heading-size: length,
        font-heading-size-1: length,
        font-heading-size-2: length,
        font-heading-size-3: length,
        font-heading-line-height: length,
        font-heading-weight: int,
        focus-ring-width: length,
        focus-ring-offset: length,
        focus-ring-color: color,
        motion-duration-fast: int,
        motion-duration-normal: int,
    }

    export struct DensityTokens {
        space-1: length,
        space-2: length,
        space-3: length,
        space-4: length,
        space-5: length,
        space-6: length,
        space-7: length,
        control-height: length,
        gap: length,
        padding: length,
    }

    export struct ChatTokens {
        size: length,
        line-height: length,
    }

    component NavItem inherits Rectangle {
        in property <string> label;
        in property <bool> active;
        in property <color> fg;
        in property <color> active-fg;
        in property <color> accent;
        in property <color> focus-color;
        in property <length> focus-width;
        in property <length> radius;
        in property <length> pad;
        in property <length> control-height: 40px;
        in property <bool> reduced-motion: false;
        callback clicked;
        out property <bool> has-focus: fs.has-focus;
        out property <bool> focus-visible: false;

        height: root.control-height;
        border-radius: root.radius;
        background: root.active ? root.accent : transparent;
        animate background { duration: root.reduced-motion ? 0ms : 120ms; }

        accessible-role: button;
        accessible-label: root.label;
        accessible-action-default => { root.clicked(); }

        forward-focus: fs;

        fs := FocusScope {
            x: 0;
            width: 0;
            focus-changed-event(reason) => {
                root.focus-visible = reason == FocusReason.tab-navigation;
            }
            key-pressed(event) => {
                if (event.text == Key.Space || event.text == Key.Return) {
                    root.clicked();
                    return accept;
                }
                return reject;
            }
        }

        ta := TouchArea {
            clicked => { root.clicked(); }
        }

        Text {
            x: root.pad;
            text: root.label;
            color: root.active ? root.active-fg : root.fg;
            font-size: 14px;
            vertical-alignment: center;
            accessible-role: none;
        }

        if (root.focus-visible) : Rectangle {
            border-width: root.focus-width;
            border-color: root.focus-color;
            border-radius: root.radius;
            background: transparent;
        }
    }

    component Sidebar inherits Rectangle {
        in property <[string]> destinations;
        in property <int> active-destination;
        in property <color> bg;
        in property <color> fg;
        in property <color> active-fg;
        in property <color> accent;
        in property <color> focus-color;
        in property <length> focus-width;
        in property <length> radius;
        in property <DensityTokens> density;
        in property <bool> show-close;
        in property <bool> reduced-motion: false;
        callback navigate(int);
        callback close-drawer;

        width: 280px;
        background: root.bg;

        accessible-role: navigation;
        accessible-label: "Navigation";

        VerticalLayout {
            padding: root.density.padding;
            spacing: root.density.gap;

            Text {
                text: "ChatUI";
                color: root.fg;
                font-size: 20px;
                font-weight: 600;
                height: 32px;
            }

            for item[index] in root.destinations : NavItem {
                label: item;
                active: index == root.active-destination;
                fg: root.fg;
                active-fg: root.active-fg;
                accent: root.accent;
                focus-color: root.focus-color;
                focus-width: root.focus-width;
                radius: root.radius;
                pad: root.density.space-3;
                control-height: root.density.control-height;
                reduced-motion: root.reduced-motion;
                clicked => { root.navigate(index); }
            }

            Rectangle { vertical-stretch: 1; }

            if (root.show-close) : Button {
                text: "Close navigation";
                clicked => { root.close-drawer(); }
            }
        }
    }

    component MainContent inherits Rectangle {
        in property <string> title;
        in property <string> notice;
        in property <color> bg;
        in property <color> fg;
        in property <color> muted;
        in property <color> border;
        in property <DensityTokens> density;
        in property <ChatTokens> chat;
        in property <bool> show-params-panel;
        in property <int> active-destination: 0;
        in property <component-factory> chat-messages-factory;
        in property <component-factory> chat-composer-factory;
        in property <component-factory> history-factory;
        in property <component-factory> providers-factory;
        in property <component-factory> settings-factory;
        in property <component-factory> data-factory;
        in property <component-factory> models-factory;
        in property <bool> show-models: false;

        callback close-models;

        background: root.bg;

        accessible-role: main;
        accessible-label: root.title;

        VerticalLayout {
            padding: root.density.padding;
            spacing: root.density.gap;

            Text {
                text: root.title;
                color: root.fg;
                font-size: 24px;
                font-weight: 600;
                height: 32px;
            }

            Rectangle {
                height: 1px;
                background: root.border;
            }

            if (root.notice != "") : Text {
                text: root.notice;
                color: root.muted;
                wrap: word-wrap;
                font-size: root.chat.size;
                height: root.chat.line-height;
            }

            content := Rectangle {
                vertical-stretch: 1;

                // Chat: message list stretches, composer keeps a bounded height.
                if (root.active-destination == 0) : VerticalLayout {
                    spacing: root.density.gap;

                    ComponentContainer {
                        vertical-stretch: 1;
                        component-factory: root.chat-messages-factory;
                    }

                    ComponentContainer {
                        height: 160px;
                        component-factory: root.chat-composer-factory;
                    }
                }

                if (root.active-destination == 1) : ComponentContainer {
                    width: parent.width;
                    height: parent.height;
                    component-factory: root.history-factory;
                }

                if (root.active-destination == 2) : ComponentContainer {
                    width: parent.width;
                    height: parent.height;
                    component-factory: root.providers-factory;
                }

                if (root.active-destination == 3) : ComponentContainer {
                    width: parent.width;
                    height: parent.height;
                    component-factory: root.settings-factory;
                }

                if (root.active-destination == 4) : ComponentContainer {
                    width: parent.width;
                    height: parent.height;
                    component-factory: root.data-factory;
                }

                // Models overlay. The factory returns the controller's own
                // handle, so the embedded component stays usable across opens.
                if (root.show-models) : Rectangle {
                    width: parent.width;
                    height: parent.height;
                    background: #00000066;

                    panel := Rectangle {
                        width: min(parent.width - 48px, 720px);
                        height: min(parent.height - 48px, 560px);
                        x: (parent.width - self.width) / 2;
                        y: (parent.height - self.height) / 2;
                        background: root.bg;
                        border-radius: 8px;
                        border-width: 1px;
                        border-color: root.border;

                        VerticalLayout {
                            padding: root.density.padding;
                            spacing: root.density.gap;

                            HorizontalLayout {
                                spacing: root.density.gap;

                                Text {
                                    text: "Models";
                                    color: root.fg;
                                    font-size: 18px;
                                    font-weight: 600;
                                    vertical-alignment: center;
                                }

                                Rectangle { horizontal-stretch: 1; }

                                Button {
                                    text: "Close models";
                                    clicked => { root.close-models(); }
                                }
                            }

                            ComponentContainer {
                                vertical-stretch: 1;
                                component-factory: root.models-factory;
                            }
                        }
                    }
                }
            }
        }
    }

    export component ShellWindow inherits Window {
        title: "ChatUI";
        width: 1024px;
        height: 720px;

        in property <ThemeTokens> tokens;
        in property <DensityTokens> density;
        in property <ChatTokens> chat;
        in property <bool> reduced-motion: false;
        in property <bool> sidebar-visible: true;
        in property <bool> drawer-open: false;
        in property <int> active-destination: 0;
        in property <[string]> destinations: ["Chat", "History", "Providers", "Settings", "Data"];
        in property <string> page-title: "Chat";
        in property <string> notice: "";
        in property <component-factory> chat-messages-factory;
        in property <component-factory> chat-composer-factory;
        in property <component-factory> history-factory;
        in property <component-factory> providers-factory;
        in property <component-factory> settings-factory;
        in property <component-factory> data-factory;
        in property <component-factory> models-factory;
        in-out property <bool> show-models: false;
        in-out property <bool> shortcuts-help-open: false;
        in property <[string]> shortcut-lines: [];
        in property <bool> show-params-panel: false;

        callback navigate(int);
        callback toggle-sidebar();
        callback close-drawer();
        callback width-changed(length);
        callback menu-command(string);
        callback files-dropped(data-transfer);

        changed width => { root.width-changed(self.width); }

        MenuBar {
            Menu {
                title: "File";
                MenuItem {
                    title: "New Chat";
                    shortcut: @keys(Control + Shift + O);
                    activated => { root.menu-command("new-chat"); }
                }
                MenuItem {
                    title: "Attach File…";
                    activated => { root.menu-command("attach-file"); }
                }
                MenuSeparator { }
                MenuItem {
                    title: "Export…";
                    activated => { root.menu-command("export"); }
                }
                MenuItem {
                    title: "Import…";
                    activated => { root.menu-command("import"); }
                }
                MenuSeparator { }
                MenuItem {
                    title: "Quit";
                    shortcut: @keys(Control + Q);
                    activated => { root.menu-command("quit"); }
                }
            }
            Menu {
                title: "Edit";
                MenuItem {
                    title: "Search";
                    shortcut: @keys(Control + K);
                    activated => { root.menu-command("search"); }
                }
                MenuItem {
                    title: "Copy Last Response";
                    shortcut: @keys(Control + Shift + C);
                    activated => { root.menu-command("copy-last-response"); }
                }
            }
            Menu {
                title: "View";
                MenuItem {
                    title: "Toggle Sidebar";
                    activated => { root.menu-command("toggle-sidebar"); }
                }
                MenuItem {
                    title: "Keyboard Shortcuts";
                    shortcut: @keys(Control + Slash);
                    activated => { root.menu-command("shortcuts-help"); }
                }
            }
            Menu {
                title: "Conversation";
                MenuItem {
                    title: "Previous Conversation";
                    shortcut: @keys(Alt + UpArrow);
                    activated => { root.menu-command("previous-conversation"); }
                }
                MenuItem {
                    title: "Next Conversation";
                    shortcut: @keys(Alt + DownArrow);
                    activated => { root.menu-command("next-conversation"); }
                }
                MenuSeparator { }
                MenuItem {
                    title: "Stop";
                    activated => { root.menu-command("stop-or-close"); }
                }
            }
            Menu {
                title: "Help";
                MenuItem {
                    title: "Keyboard Shortcuts";
                    activated => { root.menu-command("shortcuts-help"); }
                }
                MenuItem {
                    title: "Documentation";
                    activated => { root.menu-command("open-docs"); }
                }
                MenuItem {
                    title: "About ChatUI";
                    activated => { root.menu-command("about"); }
                }
            }
        }

        FocusScope {
            KeyBinding {
                keys: @keys(Escape);
                activated => { root.menu-command("stop-or-close"); }
            }
        }

        DropArea {
            width: 100%;
            height: 100%;
            can-drop(event) => { DragAction.copy }
            dropped(event) => {
                root.files-dropped(event.data);
                return event.proposed-action;
            }
        }

        if root.sidebar-visible : HorizontalLayout {
            Sidebar {
                destinations: root.destinations;
                active-destination: root.active-destination;
                bg: root.tokens.bg-surface;
                fg: root.tokens.fg-primary;
                active-fg: root.tokens.fg-on-accent;
                accent: root.tokens.accent;
                focus-color: root.tokens.focus-ring-color;
                focus-width: root.tokens.focus-ring-width;
                radius: root.tokens.radius-sm;
                density: root.density;
                show-close: false;
                reduced-motion: root.reduced-motion;
                navigate(i) => { root.navigate(i); }
                close-drawer => { root.close-drawer(); }
            }

            MainContent {
                title: root.page-title;
                notice: root.notice;
                bg: root.tokens.bg-canvas;
                fg: root.tokens.fg-primary;
                muted: root.tokens.fg-muted;
                border: root.tokens.border-subtle;
                density: root.density;
                chat: root.chat;
                show-params-panel: root.show-params-panel;
                active-destination: root.active-destination;
                chat-messages-factory: root.chat-messages-factory;
                chat-composer-factory: root.chat-composer-factory;
                history-factory: root.history-factory;
                providers-factory: root.providers-factory;
                settings-factory: root.settings-factory;
                data-factory: root.data-factory;
                models-factory: root.models-factory;
                show-models: root.show-models;
                close-models => { root.show-models = false; }
            }
        }

        if !root.sidebar-visible : VerticalLayout {
            Rectangle {
                height: 56px;
                background: root.tokens.bg-surface;
                HorizontalLayout {
                    padding: root.density.space-3;
                    spacing: root.density.space-2;
                    Button {
                        text: "Menu";
                        accessible-label: "Toggle navigation";
                        clicked => { root.toggle-sidebar(); }
                    }
                    Text {
                        text: root.page-title;
                        color: root.tokens.fg-primary;
                        font-size: 18px;
                        vertical-alignment: center;
                    }
                }
            }

            MainContent {
                title: root.page-title;
                notice: root.notice;
                bg: root.tokens.bg-canvas;
                fg: root.tokens.fg-primary;
                muted: root.tokens.fg-muted;
                border: root.tokens.border-subtle;
                density: root.density;
                chat: root.chat;
                show-params-panel: root.show-params-panel;
                active-destination: root.active-destination;
                chat-messages-factory: root.chat-messages-factory;
                chat-composer-factory: root.chat-composer-factory;
                history-factory: root.history-factory;
                providers-factory: root.providers-factory;
                settings-factory: root.settings-factory;
                data-factory: root.data-factory;
                models-factory: root.models-factory;
                show-models: root.show-models;
                close-models => { root.show-models = false; }
            }
        }

        if root.drawer-open : Rectangle {
            background: #00000066;
            ta := TouchArea {
                clicked => { root.close-drawer(); }
            }
            Sidebar {
                x: 0;
                height: parent.height;
                destinations: root.destinations;
                active-destination: root.active-destination;
                bg: root.tokens.bg-surface;
                fg: root.tokens.fg-primary;
                active-fg: root.tokens.fg-on-accent;
                accent: root.tokens.accent;
                focus-color: root.tokens.focus-ring-color;
                focus-width: root.tokens.focus-ring-width;
                radius: root.tokens.radius-sm;
                density: root.density;
                show-close: true;
                reduced-motion: root.reduced-motion;
                navigate(i) => { root.navigate(i); }
                close-drawer => { root.close-drawer(); }
            }
        }

        if root.shortcuts-help-open : Rectangle {
            background: #00000066;
            width: 100%;
            height: 100%;

            panel := Rectangle {
                width: min(parent.width - 48px, 520px);
                height: min(parent.height - 48px, 420px);
                x: (parent.width - self.width) / 2;
                y: (parent.height - self.height) / 2;
                background: root.tokens.bg-surface-raised;
                border-radius: root.tokens.radius-md;
                border-width: 1px;
                border-color: root.tokens.border-subtle;

                VerticalLayout {
                    padding: root.density.padding;
                    spacing: root.density.gap;

                    Text {
                        text: "Keyboard shortcuts";
                        color: root.tokens.fg-primary;
                        font-size: 18px;
                        font-weight: 600;
                    }

                    for line in root.shortcut-lines : Text {
                        text: line;
                        color: root.tokens.fg-secondary;
                        font-size: 14px;
                    }

                    Rectangle { vertical-stretch: 1; }

                    Button {
                        text: "Close";
                        clicked => { root.shortcuts-help-open = false; }
                    }
                }
            }
        }
    }
}

/// Convert design tokens to the Slint token struct.
pub fn theme_tokens(tokens: &Tokens) -> ThemeTokens {
    ThemeTokens {
        bg_canvas: tokens.color_bg_canvas.to_slint(),
        bg_surface: tokens.color_bg_surface.to_slint(),
        bg_surface_raised: tokens.color_bg_surface_raised.to_slint(),
        bg_user_bubble: tokens.color_bg_user_bubble.to_slint(),
        bg_code: tokens.color_bg_code.to_slint(),
        fg_primary: tokens.color_fg_primary.to_slint(),
        fg_secondary: tokens.color_fg_secondary.to_slint(),
        fg_muted: tokens.color_fg_muted.to_slint(),
        fg_on_accent: tokens.color_fg_on_accent.to_slint(),
        accent: tokens.color_accent.to_slint(),
        accent_hover: tokens.color_accent_hover.to_slint(),
        border_subtle: tokens.color_border_subtle.to_slint(),
        border_strong: tokens.color_border_strong.to_slint(),
        danger: tokens.color_danger.to_slint(),
        warning: tokens.color_warning.to_slint(),
        success: tokens.color_success.to_slint(),
        space_1: tokens.space_1,
        space_2: tokens.space_2,
        space_3: tokens.space_3,
        space_4: tokens.space_4,
        space_5: tokens.space_5,
        space_6: tokens.space_6,
        space_7: tokens.space_7,
        radius_sm: tokens.radius_sm,
        radius_md: tokens.radius_md,
        radius_lg: tokens.radius_lg,
        radius_full: tokens.radius_full,
        font_body_family: tokens.font_body_family.into(),
        font_body_size: tokens.font_body_size,
        font_body_line_height: tokens.font_body_line_height,
        font_code_family: tokens.font_code_family.into(),
        font_code_size: tokens.font_code_size,
        font_heading_family: tokens.font_heading_family.into(),
        font_heading_size: tokens.font_heading_size,
        font_heading_size_1: tokens.font_heading_size_1,
        font_heading_size_2: tokens.font_heading_size_2,
        font_heading_size_3: tokens.font_heading_size_3,
        font_heading_line_height: tokens.font_heading_line_height,
        font_heading_weight: i32::from(tokens.font_heading_weight),
        focus_ring_width: tokens.focus_ring_width,
        focus_ring_offset: tokens.focus_ring_offset,
        focus_ring_color: tokens.focus_ring_color.to_slint(),
        motion_duration_fast: tokens.motion_duration_fast as i32,
        motion_duration_normal: tokens.motion_duration_normal as i32,
    }
}

/// Convert a density scale to the Slint density struct.
pub fn density_tokens(density: &DensityScale) -> DensityTokens {
    DensityTokens {
        space_1: density.space_1,
        space_2: density.space_2,
        space_3: density.space_3,
        space_4: density.space_4,
        space_5: density.space_5,
        space_6: density.space_6,
        space_7: density.space_7,
        control_height: density.control_height,
        gap: density.gap,
        padding: density.padding,
    }
}

/// Convert chat typography to the Slint chat struct.
pub fn chat_tokens(chat: &ChatTypography) -> ChatTokens {
    ChatTokens {
        size: chat.size,
        line_height: chat.line_height,
    }
}

/// Push the current shell state into a Slint window.
pub fn sync_window(window: &ShellWindow, state: &ShellState) {
    window.set_tokens(theme_tokens(&state.tokens()));
    window.set_density(density_tokens(&state.density_scale()));
    window.set_chat(chat_tokens(&state.chat_typography()));
    window.set_reduced_motion(state.reduced_motion());
    window.set_sidebar_visible(state.sidebar_visible());
    window.set_drawer_open(state.drawer_visible());
    window.set_active_destination(state.active.index() as i32);
    window.set_destinations(ModelRc::new(VecModel::from(
        Destination::ALL
            .iter()
            .map(|destination| slint::SharedString::from(destination.label()))
            .collect::<Vec<_>>(),
    )));
    window.set_page_title(state.active.label().into());
    window.set_show_params_panel(state.layout() == LayoutMode::PersistentSidebarWithPanel);
}

/// Owns the shell state and the Slint window that renders it.
pub struct ShellController {
    state: Rc<RefCell<ShellState>>,
    window: ShellWindow,
}

impl ShellController {
    /// Create the shell window and bind it to `state`.
    pub fn new(state: ShellState) -> Result<Self, slint::PlatformError> {
        let window = ShellWindow::new()?;
        let state = Rc::new(RefCell::new(state));
        let controller = Self { state, window };
        controller.wire();
        controller.sync();
        Ok(controller)
    }

    /// The underlying Slint window.
    pub fn window(&self) -> &ShellWindow {
        &self.window
    }

    /// Borrow the current shell state.
    pub fn state(&self) -> std::cell::Ref<'_, ShellState> {
        self.state.borrow()
    }

    /// Clone the shared state handle for desktop integration callbacks.
    pub fn state_handle(&self) -> Rc<RefCell<ShellState>> {
        Rc::clone(&self.state)
    }

    /// Run the Slint event loop.
    pub fn run(&self) -> Result<(), slint::PlatformError> {
        self.window.run()
    }

    /// Push the current state into the window.
    pub fn sync(&self) {
        sync_window(&self.window, &self.state.borrow());
    }

    fn wire(&self) {
        let weak = self.window.as_weak();
        let state = Rc::clone(&self.state);
        self.window.on_navigate(move |index| {
            if let Some(destination) = Destination::from_index(index as usize) {
                state.borrow_mut().navigate(destination);
                if let Some(window) = weak.upgrade() {
                    sync_window(&window, &state.borrow());
                }
            }
        });

        let weak = self.window.as_weak();
        let state = Rc::clone(&self.state);
        self.window.on_toggle_sidebar(move || {
            state.borrow_mut().toggle_sidebar();
            if let Some(window) = weak.upgrade() {
                sync_window(&window, &state.borrow());
            }
        });

        let weak = self.window.as_weak();
        let state = Rc::clone(&self.state);
        self.window.on_close_drawer(move || {
            state.borrow_mut().close_drawer();
            if let Some(window) = weak.upgrade() {
                sync_window(&window, &state.borrow());
            }
        });

        let weak = self.window.as_weak();
        let state = Rc::clone(&self.state);
        self.window.on_width_changed(move |width| {
            state.borrow_mut().set_width(width);
            if let Some(window) = weak.upgrade() {
                sync_window(&window, &state.borrow());
            }
        });
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::theme::ReducedMotion;
    use chatui_domain::settings::{ChatFontSize, Density, Theme};
    use i_slint_backend_testing::ElementRoot;
    use slint::ComponentHandle;
    use slint::Model;

    slint::slint! {
        import { Button } from "std-widgets.slint";

        export component TestFeatureWindow inherits Window {
            in-out property <string> label: "Test feature";
            callback clicked();

            VerticalLayout {
                padding: 8px;
                spacing: 4px;

                Text {
                    text: root.label;
                }

                Button {
                    text: "Test action";
                    clicked => { root.clicked(); }
                }
            }
        }
    }

    fn factory_for(window: &TestFeatureWindow) -> slint::ComponentFactory {
        let handle = window.clone_strong();
        slint::ComponentFactory::new(move |_context| Some(handle.clone_strong()))
    }

    fn accessible_labels(window: &ShellWindow) -> Vec<String> {
        window
            .root_element()
            .query_descendants()
            .find_all()
            .into_iter()
            .filter_map(|element| element.accessible_label())
            .map(|label| label.to_string())
            .collect()
    }

    #[test]
    fn breakpoints_match_spec_11_9() {
        assert_eq!(Breakpoint::from_width(0.0), Breakpoint::Narrow);
        assert_eq!(Breakpoint::from_width(599.9), Breakpoint::Narrow);
        assert_eq!(Breakpoint::from_width(600.0), Breakpoint::Compact);
        assert_eq!(Breakpoint::from_width(839.9), Breakpoint::Compact);
        assert_eq!(Breakpoint::from_width(840.0), Breakpoint::Desktop);
        assert_eq!(Breakpoint::from_width(1279.9), Breakpoint::Desktop);
        assert_eq!(Breakpoint::from_width(1280.0), Breakpoint::Wide);
    }

    #[test]
    fn layout_modes_follow_breakpoints() {
        assert_eq!(Breakpoint::Narrow.layout(), LayoutMode::Drawer);
        assert_eq!(Breakpoint::Compact.layout(), LayoutMode::CollapsibleSidebar);
        assert_eq!(Breakpoint::Desktop.layout(), LayoutMode::PersistentSidebar);
        assert_eq!(
            Breakpoint::Wide.layout(),
            LayoutMode::PersistentSidebarWithPanel
        );
    }

    #[test]
    fn desktop_widths_show_a_persistent_sidebar() {
        let state = ShellState::new(1024.0);
        assert!(state.sidebar_visible());
        assert!(!state.drawer_visible());
        assert_eq!(state.layout(), LayoutMode::PersistentSidebar);
    }

    #[test]
    fn narrow_widths_use_a_drawer() {
        let mut state = ShellState::new(500.0);
        assert!(!state.sidebar_visible());
        assert!(!state.drawer_visible());

        state.toggle_sidebar();
        assert!(state.drawer_visible());
        assert!(!state.sidebar_visible());

        state.close_drawer();
        assert!(!state.drawer_visible());
    }

    #[test]
    fn compact_widths_collapse_the_sidebar() {
        let mut state = ShellState::new(700.0);
        assert!(state.sidebar_visible());

        state.toggle_sidebar();
        assert!(!state.sidebar_visible());

        state.toggle_sidebar();
        assert!(state.sidebar_visible());
    }

    #[test]
    fn desktop_toggle_is_a_no_op() {
        let mut state = ShellState::new(1024.0);
        state.toggle_sidebar();
        assert!(state.sidebar_visible());
        assert!(!state.drawer_visible());
    }

    #[test]
    fn navigating_closes_the_drawer() {
        let mut state = ShellState::new(500.0);
        state.open_drawer();
        assert!(state.drawer_visible());

        state.navigate(Destination::Settings);
        assert_eq!(state.active, Destination::Settings);
        assert!(!state.drawer_visible());
    }

    #[test]
    fn resizing_out_of_narrow_closes_the_drawer() {
        let mut state = ShellState::new(500.0);
        state.open_drawer();
        state.set_width(1024.0);
        assert!(!state.drawer_visible());
        assert!(state.sidebar_visible());
    }

    #[test]
    fn every_destination_is_reachable_in_both_layouts() {
        for width in [500.0, 1024.0] {
            let state = ShellState::new(width);
            let order = state.focus_order();
            let ids: Vec<_> = order.items().iter().map(|item| item.id).collect();

            for destination in Destination::ALL {
                assert!(
                    ids.contains(&destination.id()),
                    "{} missing at width {width}",
                    destination.label()
                );
            }
        }
    }

    #[test]
    fn focus_order_includes_the_drawer_close_action() {
        let mut state = ShellState::new(500.0);
        state.open_drawer();
        let order = state.focus_order();
        assert!(order.items().iter().any(|item| item.id == "close"));
    }

    #[test]
    fn escape_closes_the_drawer() {
        let mut state = ShellState::new(500.0);
        state.open_drawer();
        assert_eq!(
            state.handle_key(Key::Escape, Modifiers::NONE),
            KeyboardAction::Dismiss
        );
        assert!(!state.drawer_visible());
    }

    #[test]
    fn theme_density_font_size_and_motion_flow_through_the_shell() {
        let mut state = ShellState::new(1024.0);
        state.theme = ThemeSettings::new(
            Theme::Dark,
            Density::Compact,
            ChatFontSize::Lg,
            ReducedMotion::Reduce,
        );
        state.system_is_dark = false;
        state.system_reduced_motion = false;

        assert_eq!(state.resolved_theme(), ResolvedTheme::Dark);
        assert_eq!(state.tokens(), Tokens::dark());
        assert_eq!(state.density_scale(), DensityScale::compact());
        assert_eq!(state.chat_typography(), ChatTypography::lg());
        assert!(state.reduced_motion());
        assert_eq!(state.motion(), Motion::reduced());
        assert_eq!(state.focus_ring().color, Tokens::dark().focus_ring_color);
    }

    #[test]
    fn system_theme_and_reduced_motion_follow_the_os() {
        let mut state = ShellState::new(1024.0);
        state.system_is_dark = true;
        state.system_reduced_motion = true;

        assert_eq!(state.resolved_theme(), ResolvedTheme::Dark);
        assert!(state.reduced_motion());
    }

    #[test]
    fn slint_window_renders_the_shell_and_exposes_destinations() {
        i_slint_backend_testing::init_no_event_loop();

        let state = ShellState::new(1024.0);
        let controller = ShellController::new(state).expect("shell window");
        let window = controller.window();

        assert!(window.get_sidebar_visible());
        assert!(!window.get_drawer_open());
        assert_eq!(window.get_active_destination(), 0);
        assert_eq!(window.get_destinations().row_count(), 5);
        assert_eq!(window.get_page_title(), "Chat");
        assert_eq!(
            window.get_tokens().bg_canvas,
            Tokens::light().color_bg_canvas.to_slint()
        );

        for (index, destination) in Destination::ALL.iter().enumerate() {
            assert_eq!(
                window.get_destinations().row_data(index).as_deref(),
                Some(destination.label())
            );
            assert!(!destination.accessible_label().is_empty());
        }
    }

    #[test]
    fn slint_window_navigates_through_the_shell_callback() {
        i_slint_backend_testing::init_no_event_loop();

        let state = ShellState::new(1024.0);
        let controller = ShellController::new(state).expect("shell window");
        let window = controller.window();

        window.invoke_navigate(1);

        assert_eq!(controller.state().active, Destination::History);
        assert_eq!(window.get_active_destination(), 1);
        assert_eq!(window.get_page_title(), "History");
    }

    #[test]
    fn slint_window_opens_the_drawer_at_narrow_widths() {
        i_slint_backend_testing::init_no_event_loop();

        let state = ShellState::new(500.0);
        let controller = ShellController::new(state).expect("shell window");
        let window = controller.window();

        assert!(!window.get_sidebar_visible());
        assert!(!window.get_drawer_open());

        window.invoke_toggle_sidebar();
        assert!(window.get_drawer_open());
        assert!(controller.state().drawer_visible());

        window.invoke_close_drawer();
        assert!(!window.get_drawer_open());
    }

    #[test]
    fn slint_window_reacts_to_width_changes() {
        i_slint_backend_testing::init_no_event_loop();

        let state = ShellState::new(1024.0);
        let controller = ShellController::new(state).expect("shell window");
        let window = controller.window();

        window.invoke_width_changed(500.0);
        assert!(!window.get_sidebar_visible());
        assert_eq!(controller.state().breakpoint(), Breakpoint::Narrow);

        window.invoke_width_changed(1280.0);
        assert!(window.get_sidebar_visible());
        assert!(window.get_show_params_panel());
    }

    #[test]
    fn slint_window_applies_density_font_size_and_reduced_motion() {
        i_slint_backend_testing::init_no_event_loop();

        let mut state = ShellState::new(1024.0);
        state.theme = ThemeSettings::new(
            Theme::Light,
            Density::Compact,
            ChatFontSize::Lg,
            ReducedMotion::Reduce,
        );
        let controller = ShellController::new(state).expect("shell window");
        let window = controller.window();

        assert_eq!(window.get_density().control_height, 32.0);
        assert_eq!(window.get_chat().size, 18.0);
        assert!(window.get_reduced_motion());
    }

    #[test]
    fn slint_window_exposes_accessible_names_for_navigation() {
        i_slint_backend_testing::init_no_event_loop();

        let state = ShellState::new(1024.0);
        let controller = ShellController::new(state).expect("shell window");
        let window = controller.window();

        for destination in Destination::ALL {
            let matches: Vec<_> = i_slint_backend_testing::ElementHandle::find_by_accessible_label(
                window,
                destination.accessible_label(),
            )
            .collect();
            assert!(
                !matches.is_empty(),
                "{} has no accessible label",
                destination.label()
            );
            assert!(matches.iter().any(|element| {
                element.accessible_role() == Some(i_slint_backend_testing::AccessibleRole::Button)
            }));
        }
    }

    #[test]
    fn slint_window_exposes_an_accessible_name_for_the_icon_only_menu_button() {
        i_slint_backend_testing::init_no_event_loop();

        let state = ShellState::new(500.0);
        let controller = ShellController::new(state).expect("shell window");
        let window = controller.window();

        let matches: Vec<_> = i_slint_backend_testing::ElementHandle::find_by_accessible_label(
            window,
            "Toggle navigation",
        )
        .collect();
        assert!(!matches.is_empty());
        assert!(matches.iter().any(|element| {
            element.accessible_role() == Some(i_slint_backend_testing::AccessibleRole::Button)
        }));
    }

    #[test]
    fn every_slint_button_has_an_accessible_name() {
        i_slint_backend_testing::init_no_event_loop();

        let state = ShellState::new(1024.0);
        let controller = ShellController::new(state).expect("shell window");
        let window = controller.window();

        let buttons: Vec<_> = window
            .root_element()
            .query_descendants()
            .match_accessible_role(i_slint_backend_testing::AccessibleRole::Button)
            .find_all();
        assert!(!buttons.is_empty());
        for button in buttons {
            let label = button.accessible_label().unwrap_or_default();
            assert!(
                !label.trim().is_empty(),
                "button without an accessible name"
            );
        }
    }

    #[test]
    fn slint_window_applies_dark_theme_tokens() {
        i_slint_backend_testing::init_no_event_loop();

        let mut state = ShellState::new(1024.0);
        state.theme = ThemeSettings::new(
            Theme::Dark,
            Density::Comfortable,
            ChatFontSize::Md,
            ReducedMotion::System,
        );
        let controller = ShellController::new(state).expect("shell window");
        let window = controller.window();

        assert_eq!(
            window.get_tokens().bg_canvas,
            Tokens::dark().color_bg_canvas.to_slint()
        );
        assert_eq!(
            window.get_tokens().fg_primary,
            Tokens::dark().color_fg_primary.to_slint()
        );
    }

    #[test]
    fn shell_embeds_a_factory_product_and_updates_it() {
        i_slint_backend_testing::init_no_event_loop();

        let window = ShellWindow::new().expect("shell window");
        let feature = TestFeatureWindow::new().expect("test feature");
        feature.set_label("Embedded feature".into());
        window.set_chat_messages_factory(factory_for(&feature));

        let labels = accessible_labels(&window);
        assert!(
            labels.iter().any(|label| label == "Embedded feature"),
            "embedded feature missing from the shell tree: {labels:?}"
        );

        feature.set_label("Updated feature".into());
        let labels = accessible_labels(&window);
        assert!(
            labels.iter().any(|label| label == "Updated feature"),
            "setter did not update the embedded UI: {labels:?}"
        );

        let embedded_button = i_slint_backend_testing::ElementHandle::find_by_accessible_label(
            &window,
            "Test action",
        )
        .find(|element| {
            element.accessible_role() == Some(i_slint_backend_testing::AccessibleRole::Button)
        })
        .expect("embedded button is missing from the shell accessibility tree");
        assert!(
            !embedded_button
                .accessible_label()
                .unwrap_or_default()
                .trim()
                .is_empty(),
            "embedded button has no accessible name"
        );
    }

    #[test]
    fn destination_switching_keeps_embedded_handles_usable() {
        i_slint_backend_testing::init_no_event_loop();

        let window = ShellWindow::new().expect("shell window");
        let chat = TestFeatureWindow::new().expect("chat feature");
        let history = TestFeatureWindow::new().expect("history feature");
        chat.set_label("Chat feature".into());
        history.set_label("History feature".into());
        window.set_chat_messages_factory(factory_for(&chat));
        window.set_history_factory(factory_for(&history));

        let labels = accessible_labels(&window);
        assert!(
            labels.iter().any(|label| label == "Chat feature"),
            "chat not shown: {labels:?}"
        );
        assert!(
            !labels.iter().any(|label| label == "History feature"),
            "history shown while chat is active: {labels:?}"
        );

        window.set_active_destination(1);
        let labels = accessible_labels(&window);
        assert!(
            labels.iter().any(|label| label == "History feature"),
            "history not shown: {labels:?}"
        );
        assert!(
            !labels.iter().any(|label| label == "Chat feature"),
            "chat shown while history is active: {labels:?}"
        );

        // Handles stay usable while their containers are hidden.
        chat.set_label("Chat updated".into());
        history.set_label("History updated".into());

        window.set_active_destination(0);
        let labels = accessible_labels(&window);
        assert!(
            labels.iter().any(|label| label == "Chat updated"),
            "chat handle lost after destination switching: {labels:?}"
        );
        assert!(
            !labels.iter().any(|label| label == "History updated"),
            "history shown while chat is active: {labels:?}"
        );
    }

    #[test]
    fn models_overlay_opens_and_closes() {
        i_slint_backend_testing::init_no_event_loop();

        let window = ShellWindow::new().expect("shell window");
        let models = TestFeatureWindow::new().expect("models feature");
        models.set_label("Models feature".into());
        window.set_models_factory(factory_for(&models));

        assert!(!window.get_show_models());
        assert!(
            !accessible_labels(&window)
                .iter()
                .any(|label| label == "Models feature")
        );

        window.set_show_models(true);
        let labels = accessible_labels(&window);
        assert!(
            labels.iter().any(|label| label == "Models feature"),
            "models overlay did not open: {labels:?}"
        );

        let close = i_slint_backend_testing::ElementHandle::find_by_accessible_label(
            &window,
            "Close models",
        )
        .find(|element| {
            element.accessible_role() == Some(i_slint_backend_testing::AccessibleRole::Button)
        })
        .expect("close models button");
        close.invoke_accessible_default_action();

        assert!(
            !window.get_show_models(),
            "close action did not close the overlay"
        );
        assert!(
            !accessible_labels(&window)
                .iter()
                .any(|label| label == "Models feature")
        );
    }
}
