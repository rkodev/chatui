//! Theme, density, chat font size, and reduced-motion resolution.
//!
//! The preference types come from `chatui-domain` so the desktop client shares
//! the persisted settings contract with the web and Android clients. This
//! module only resolves those preferences into concrete tokens and scales.

use chatui_domain::settings::{ChatFontSize, Density, Theme};

use crate::tokens::{ChatTypography, DensityScale, Motion, Tokens};

/// The concrete theme after resolving the `system` preference.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum ResolvedTheme {
    /// Light palette.
    Light,
    /// Dark palette.
    Dark,
}

impl ResolvedTheme {
    /// Resolve the token set for this theme.
    pub const fn tokens(self) -> Tokens {
        match self {
            Self::Light => Tokens::light(),
            Self::Dark => Tokens::dark(),
        }
    }

    /// Whether this is the dark palette.
    pub const fn is_dark(self) -> bool {
        matches!(self, Self::Dark)
    }
}

/// Reduced-motion preference.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Default)]
pub enum ReducedMotion {
    /// Follow the operating-system preference.
    #[default]
    System,
    /// Always reduce motion.
    Reduce,
    /// Never reduce motion.
    NoPreference,
}

impl ReducedMotion {
    /// Resolve the preference against the operating-system setting.
    pub const fn resolve(self, system_reduced: bool) -> bool {
        match self {
            Self::System => system_reduced,
            Self::Reduce => true,
            Self::NoPreference => false,
        }
    }
}

/// Appearance settings that feed the shell.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub struct ThemeSettings {
    /// Light, dark, or system theme.
    pub theme: Theme,
    /// Comfortable or compact density.
    pub density: Density,
    /// Small, medium, or large chat text.
    pub chat_font_size: ChatFontSize,
    /// Reduced-motion preference.
    pub reduced_motion: ReducedMotion,
}

impl Default for ThemeSettings {
    fn default() -> Self {
        Self {
            theme: Theme::System,
            density: Density::Comfortable,
            chat_font_size: ChatFontSize::Md,
            reduced_motion: ReducedMotion::System,
        }
    }
}

impl ThemeSettings {
    /// Create appearance settings.
    pub const fn new(
        theme: Theme,
        density: Density,
        chat_font_size: ChatFontSize,
        reduced_motion: ReducedMotion,
    ) -> Self {
        Self {
            theme,
            density,
            chat_font_size,
            reduced_motion,
        }
    }

    /// Resolve the theme, following the OS when `theme` is `System`.
    pub const fn resolve_theme(&self, system_is_dark: bool) -> ResolvedTheme {
        match self.theme {
            Theme::Light => ResolvedTheme::Light,
            Theme::Dark => ResolvedTheme::Dark,
            Theme::System => {
                if system_is_dark {
                    ResolvedTheme::Dark
                } else {
                    ResolvedTheme::Light
                }
            }
        }
    }

    /// Resolve the token set for the current theme.
    pub const fn tokens(&self, system_is_dark: bool) -> Tokens {
        self.resolve_theme(system_is_dark).tokens()
    }

    /// Resolve the density-adjusted spacing scale.
    pub const fn density_scale(&self, tokens: &Tokens) -> DensityScale {
        match self.density {
            Density::Comfortable => DensityScale::comfortable(tokens),
            Density::Compact => DensityScale::compact(),
        }
    }

    /// Resolve the chat text size.
    pub const fn chat_typography(&self) -> ChatTypography {
        match self.chat_font_size {
            ChatFontSize::Sm => ChatTypography::sm(),
            ChatFontSize::Md => ChatTypography::md(),
            ChatFontSize::Lg => ChatTypography::lg(),
        }
    }

    /// Resolve whether reduced motion is active.
    pub const fn reduced_motion(&self, system_reduced: bool) -> bool {
        self.reduced_motion.resolve(system_reduced)
    }

    /// Resolve motion durations.
    pub const fn motion(&self, system_reduced: bool) -> Motion {
        Motion::for_preference(self.reduced_motion(system_reduced))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn system_theme_follows_the_operating_system() {
        let settings = ThemeSettings::default();
        assert_eq!(settings.resolve_theme(false), ResolvedTheme::Light);
        assert_eq!(settings.resolve_theme(true), ResolvedTheme::Dark);
    }

    #[test]
    fn explicit_light_and_dark_ignore_the_operating_system() {
        let light = ThemeSettings::new(
            Theme::Light,
            Density::Comfortable,
            ChatFontSize::Md,
            ReducedMotion::System,
        );
        let dark = ThemeSettings::new(
            Theme::Dark,
            Density::Comfortable,
            ChatFontSize::Md,
            ReducedMotion::System,
        );

        assert_eq!(light.resolve_theme(true), ResolvedTheme::Light);
        assert_eq!(dark.resolve_theme(false), ResolvedTheme::Dark);
    }

    #[test]
    fn resolved_tokens_match_the_theme() {
        let settings = ThemeSettings::default();
        assert_eq!(settings.tokens(false), Tokens::light());
        assert_eq!(settings.tokens(true), Tokens::dark());
        assert!(!settings.resolve_theme(false).is_dark());
        assert!(settings.resolve_theme(true).is_dark());
    }

    #[test]
    fn density_resolves_to_the_expected_scale() {
        let tokens = Tokens::light();
        let comfortable = ThemeSettings::default();
        assert_eq!(
            comfortable.density_scale(&tokens),
            DensityScale::comfortable(&tokens)
        );

        let compact = ThemeSettings::new(
            Theme::System,
            Density::Compact,
            ChatFontSize::Md,
            ReducedMotion::System,
        );
        assert_eq!(compact.density_scale(&tokens), DensityScale::compact());
    }

    #[test]
    fn chat_font_size_resolves_to_the_expected_typography() {
        let sm = ThemeSettings::new(
            Theme::System,
            Density::Comfortable,
            ChatFontSize::Sm,
            ReducedMotion::System,
        );
        let md = ThemeSettings::default();
        let lg = ThemeSettings::new(
            Theme::System,
            Density::Comfortable,
            ChatFontSize::Lg,
            ReducedMotion::System,
        );

        assert_eq!(sm.chat_typography(), ChatTypography::sm());
        assert_eq!(md.chat_typography(), ChatTypography::md());
        assert_eq!(lg.chat_typography(), ChatTypography::lg());
    }

    #[test]
    fn reduced_motion_preference_resolves() {
        assert!(ReducedMotion::System.resolve(true));
        assert!(!ReducedMotion::System.resolve(false));
        assert!(ReducedMotion::Reduce.resolve(false));
        assert!(!ReducedMotion::NoPreference.resolve(true));

        let settings = ThemeSettings::default();
        assert!(settings.reduced_motion(true));
        assert!(!settings.reduced_motion(false));
        assert_eq!(settings.motion(true), Motion::reduced());
        assert_eq!(settings.motion(false), Motion::standard());
    }
}
