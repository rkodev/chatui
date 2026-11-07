//! Settings routes and navigation state.
//!
//! The settings surface mirrors the web routes in spec section 8.3 and the
//! information architecture in section 11.1. The shell owns the top-level
//! destination; this module owns the settings section and maps it back to the
//! shell destination so the desktop app can keep one navigation model.

use crate::shell::Destination;

/// A settings section.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum SettingsRoute {
    /// Theme, density, chat font size, and reduced motion.
    Appearance,
    /// Send key, token usage, remote images, prompt, and parameters.
    ChatDefaults,
    /// Provider management entry point.
    Providers,
    /// Model management entry point.
    Models,
    /// Data controls entry point (M3.5-10.2).
    Data,
    /// Future backup configuration placeholder.
    Backup,
    /// Version, licenses, and privacy.
    About,
}

impl SettingsRoute {
    /// Every section in navigation order.
    pub const ALL: [Self; 7] = [
        Self::Appearance,
        Self::ChatDefaults,
        Self::Providers,
        Self::Models,
        Self::Data,
        Self::Backup,
        Self::About,
    ];

    /// Visible label.
    pub const fn label(self) -> &'static str {
        match self {
            Self::Appearance => "Appearance",
            Self::ChatDefaults => "Chat defaults",
            Self::Providers => "Providers",
            Self::Models => "Models",
            Self::Data => "Data",
            Self::Backup => "Backup",
            Self::About => "About",
        }
    }

    /// Stable focus id.
    pub const fn id(self) -> &'static str {
        match self {
            Self::Appearance => "settings-appearance",
            Self::ChatDefaults => "settings-chat-defaults",
            Self::Providers => "settings-providers",
            Self::Models => "settings-models",
            Self::Data => "settings-data",
            Self::Backup => "settings-backup",
            Self::About => "settings-about",
        }
    }

    /// Route path from spec section 8.3 and the web settings layout.
    pub const fn path(self) -> &'static str {
        match self {
            Self::Appearance => "/settings/appearance",
            Self::ChatDefaults => "/settings/chat",
            Self::Providers => "/settings/providers",
            Self::Models => "/settings/models",
            Self::Data => "/settings/data",
            Self::Backup => "/settings/backup",
            Self::About => "/settings/about",
        }
    }

    /// Shell destination that owns this section.
    pub const fn destination(self) -> Destination {
        match self {
            Self::Appearance | Self::ChatDefaults | Self::Backup | Self::About => {
                Destination::Settings
            }
            Self::Providers | Self::Models => Destination::Providers,
            Self::Data => Destination::Data,
        }
    }

    /// Index in [`SettingsRoute::ALL`].
    pub const fn index(self) -> usize {
        match self {
            Self::Appearance => 0,
            Self::ChatDefaults => 1,
            Self::Providers => 2,
            Self::Models => 3,
            Self::Data => 4,
            Self::Backup => 5,
            Self::About => 6,
        }
    }

    /// Resolve a section from its index.
    pub const fn from_index(index: usize) -> Option<Self> {
        match index {
            0 => Some(Self::Appearance),
            1 => Some(Self::ChatDefaults),
            2 => Some(Self::Providers),
            3 => Some(Self::Models),
            4 => Some(Self::Data),
            5 => Some(Self::Backup),
            6 => Some(Self::About),
            _ => None,
        }
    }

    /// Resolve a section from a settings path.
    ///
    /// `/settings` redirects to appearance, matching the web router.
    pub fn from_path(path: &str) -> Option<Self> {
        match path.trim_end_matches('/') {
            "/settings" | "/settings/appearance" => Some(Self::Appearance),
            "/settings/chat" | "/settings/chat-defaults" => Some(Self::ChatDefaults),
            "/settings/providers" => Some(Self::Providers),
            "/settings/models" => Some(Self::Models),
            "/settings/data" => Some(Self::Data),
            "/settings/backup" => Some(Self::Backup),
            "/settings/about" => Some(Self::About),
            _ => None,
        }
    }
}

/// Settings navigation state used by the shell.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SettingsNavigation {
    route: SettingsRoute,
}

impl SettingsNavigation {
    /// Start on the appearance section.
    pub fn new() -> Self {
        Self {
            route: SettingsRoute::Appearance,
        }
    }

    /// Start on a specific section.
    pub fn with_route(route: SettingsRoute) -> Self {
        Self { route }
    }

    /// Current section.
    pub fn route(&self) -> SettingsRoute {
        self.route
    }

    /// Select a section.
    pub fn select(&mut self, route: SettingsRoute) {
        self.route = route;
    }

    /// Shell destination for the current section.
    pub fn destination(&self) -> Destination {
        self.route.destination()
    }

    /// Index of the current section.
    pub fn index(&self) -> usize {
        self.route.index()
    }
}

impl Default for SettingsNavigation {
    fn default() -> Self {
        Self::new()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn every_section_has_a_label_id_and_path() {
        for route in SettingsRoute::ALL {
            assert!(!route.label().is_empty());
            assert!(!route.id().is_empty());
            assert!(route.path().starts_with("/settings"));
            assert_eq!(SettingsRoute::from_index(route.index()), Some(route));
            assert_eq!(SettingsRoute::from_path(route.path()), Some(route));
        }
    }

    #[test]
    fn settings_root_redirects_to_appearance() {
        assert_eq!(
            SettingsRoute::from_path("/settings"),
            Some(SettingsRoute::Appearance)
        );
        assert_eq!(
            SettingsRoute::from_path("/settings/"),
            Some(SettingsRoute::Appearance)
        );
        assert_eq!(SettingsRoute::from_path("/settings/unknown"), None);
    }

    #[test]
    fn sections_map_to_shell_destinations() {
        assert_eq!(
            SettingsRoute::Appearance.destination(),
            Destination::Settings
        );
        assert_eq!(
            SettingsRoute::ChatDefaults.destination(),
            Destination::Settings
        );
        assert_eq!(
            SettingsRoute::Providers.destination(),
            Destination::Providers
        );
        assert_eq!(SettingsRoute::Models.destination(), Destination::Providers);
        assert_eq!(SettingsRoute::Data.destination(), Destination::Data);
        assert_eq!(SettingsRoute::Backup.destination(), Destination::Settings);
        assert_eq!(SettingsRoute::About.destination(), Destination::Settings);
    }

    #[test]
    fn navigation_starts_on_appearance_and_selects_sections() {
        let mut navigation = SettingsNavigation::new();
        assert_eq!(navigation.route(), SettingsRoute::Appearance);
        assert_eq!(navigation.destination(), Destination::Settings);
        assert_eq!(navigation.index(), 0);

        navigation.select(SettingsRoute::Data);
        assert_eq!(navigation.route(), SettingsRoute::Data);
        assert_eq!(navigation.destination(), Destination::Data);
        assert_eq!(navigation.index(), 4);
    }
}
