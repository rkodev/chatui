//! Framework-independent settings view model.
//!
//! The view model owns the persisted [`Settings`] value, the provider and model
//! options used by the default pickers, and the settings section. It also
//! exposes the wiring methods that apply each setting to its real consumer:
//!
//! * appearance -> [`ShellState`] through [`ThemeSettings`];
//! * send key -> [`ComposerState`];
//! * token usage -> [`MessageListState`];
//! * remote images -> [`RenderOptions`];
//! * default prompt and parameters -> the conversation factory reads the same
//!   persisted settings;
//! * provider/model defaults -> the model picker and conversation factory read
//!   the same persisted settings.
//!
//! Controls that have no desktop consumer are labelled as display-only or
//! future in [`SettingsViewModel::controls`]. They are rendered as text, not as
//! dead controls.

use chatui_domain::{
    ChatFontSize, ChatParams, Density, Model, Provider, ReasoningEffort, SendKey, Settings, Theme,
};

use crate::chat::composer::ComposerState;
use crate::chat::message_list::MessageListState;
use crate::markdown::RenderOptions;
use crate::settings::error::SettingsErrorCopy;
use crate::settings::route::{SettingsNavigation, SettingsRoute};
use crate::shell::ShellState;
use crate::theme::{ReducedMotion, ThemeSettings};

/// Application name shown on the about screen.
pub const APP_NAME: &str = "ChatUI";
/// Crate version shown on the about screen.
pub const APP_VERSION: &str = env!("CARGO_PKG_VERSION");
/// Documentation URL shown on the about screen.
pub const DOCS_URL: &str = "https://github.com/rkodev/chatui";
/// Maximum stored default system prompt length.
pub const MAX_SYSTEM_PROMPT_CHARS: usize = 32_000;

/// A provider option in the default-provider picker.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ProviderOption {
    /// Provider ID.
    pub id: String,
    /// Provider display label.
    pub label: String,
    /// Whether the provider is enabled.
    pub enabled: bool,
}

impl ProviderOption {
    /// Build an option from a provider record.
    pub fn from_provider(provider: &Provider) -> Self {
        Self {
            id: provider.id.clone(),
            label: provider.label.clone(),
            enabled: provider.enabled,
        }
    }

    /// Display string used by the Slint combo box.
    pub fn display(&self) -> String {
        if self.enabled {
            format!("{} ({})", self.label, self.id)
        } else {
            format!("{} ({}, disabled)", self.label, self.id)
        }
    }
}

/// A model option in the default-model picker.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ModelOption {
    /// Local model ID.
    pub id: String,
    /// Owning provider ID.
    pub provider_id: String,
    /// Display label.
    pub label: String,
    /// Provider model ID.
    pub remote_id: String,
    /// Whether the model is hidden from the normal picker.
    pub hidden: bool,
}

impl ModelOption {
    /// Build an option from a model record.
    pub fn from_model(model: &Model) -> Self {
        Self {
            id: model.id.clone(),
            provider_id: model.provider_id.clone(),
            label: model.label.clone(),
            remote_id: model.remote_id.clone(),
            hidden: model.hidden,
        }
    }

    /// Display string used by the Slint combo box.
    pub fn display(&self) -> String {
        if self.hidden {
            format!("{} ({}, hidden)", self.label, self.remote_id)
        } else {
            format!("{} ({})", self.label, self.remote_id)
        }
    }
}

/// Whether a setting has a real desktop consumer.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum SettingAvailability {
    /// The setting is persisted and wired to a consumer.
    Supported,
    /// The setting is shown for transparency but has no desktop control.
    DisplayOnly(&'static str),
    /// The setting belongs to a future milestone.
    Future(&'static str),
}

impl SettingAvailability {
    /// Whether the setting is a live control.
    pub const fn is_supported(self) -> bool {
        matches!(self, Self::Supported)
    }

    /// Why the setting is not a live control.
    pub const fn reason(self) -> Option<&'static str> {
        match self {
            Self::Supported => None,
            Self::DisplayOnly(reason) | Self::Future(reason) => Some(reason),
        }
    }
}

/// A settings control descriptor used to document display-only and future
/// settings.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct SettingDescriptor {
    /// Persisted settings key.
    pub key: &'static str,
    /// Visible label.
    pub label: &'static str,
    /// Whether the setting is live.
    pub availability: SettingAvailability,
}

/// Backup placeholder copy. Backup itself is M5.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct BackupPlaceholder {
    /// Section title.
    pub title: &'static str,
    /// Primary message.
    pub message: &'static str,
    /// Future-behavior detail.
    pub detail: &'static str,
    /// Whether backup is available in this release.
    pub available: bool,
}

/// About and privacy copy.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct AboutInfo {
    /// Application name.
    pub app_name: &'static str,
    /// Application version.
    pub version: &'static str,
    /// Local-first and BYOK summary.
    pub body: &'static str,
    /// Privacy statements.
    pub privacy: Vec<&'static str>,
    /// Documentation URL.
    pub docs_url: &'static str,
}

/// Settings view model.
#[derive(Debug, Clone, PartialEq)]
pub struct SettingsViewModel {
    /// Current settings section.
    pub route: SettingsNavigation,
    /// Persisted settings.
    pub settings: Settings,
    /// Provider options for the default-provider picker.
    pub providers: Vec<ProviderOption>,
    /// Model options for the default-model picker.
    pub models: Vec<ModelOption>,
    /// Non-blocking notice, such as an unavailable provider list.
    pub notice: Option<String>,
    /// Current error copy.
    pub error: Option<SettingsErrorCopy>,
}

impl SettingsViewModel {
    /// Build a view model from persisted settings and catalog records.
    pub fn new(
        settings: Settings,
        providers: Vec<Provider>,
        models: Vec<Model>,
        route: SettingsRoute,
    ) -> Self {
        Self {
            route: SettingsNavigation::with_route(route),
            settings,
            providers: providers
                .iter()
                .map(ProviderOption::from_provider)
                .collect(),
            models: models.iter().map(ModelOption::from_model).collect(),
            notice: None,
            error: None,
        }
    }

    /// Resolve appearance settings for the shell.
    ///
    /// Reduced motion is not a persisted desktop setting; it follows the
    /// operating-system preference, matching the web appearance screen.
    pub fn theme_settings(&self) -> ThemeSettings {
        ThemeSettings::new(
            self.settings.theme,
            self.settings.density,
            self.settings.chat_font_size,
            ReducedMotion::System,
        )
    }

    /// Apply appearance settings to the shell.
    pub fn apply_to_shell(&self, state: &mut ShellState) {
        state.theme = self.theme_settings();
    }

    /// Apply the send-key preference to the composer.
    pub fn apply_to_composer(&self, state: &mut ComposerState) {
        state.set_send_key(self.settings.send_key);
    }

    /// Apply token-usage visibility to the message list.
    pub fn apply_to_message_list(&self, state: &mut MessageListState) {
        state.set_show_token_usage(self.settings.show_token_usage);
    }

    /// Resolve Markdown render options from the remote-image preference.
    pub fn render_options(&self) -> RenderOptions {
        RenderOptions {
            load_remote_images: self.settings.load_remote_images,
            highlight_code: true,
        }
    }

    /// Default system prompt used by new conversations.
    pub fn default_system_prompt(&self) -> &str {
        &self.settings.default_system_prompt
    }

    /// Default chat parameters used by new conversations.
    pub fn default_params(&self) -> &ChatParams {
        &self.settings.default_params
    }

    /// Default provider ID, or `None` for the first enabled provider.
    pub fn default_provider_id(&self) -> Option<&str> {
        self.settings.default_provider_id.as_deref()
    }

    /// Default model ID, or `None` for the provider default.
    pub fn default_model_id(&self) -> Option<&str> {
        self.settings.default_model_id.as_deref()
    }

    /// Whether onboarding has completed.
    pub fn onboarding_completed(&self) -> bool {
        self.settings.onboarding_completed
    }

    /// Provider picker display strings, including the "first enabled" option.
    pub fn provider_display(&self) -> Vec<String> {
        let mut options = vec!["First enabled provider".to_owned()];
        options.extend(self.providers.iter().map(ProviderOption::display));
        options
    }

    /// Model picker display strings, including the "provider default" option.
    pub fn model_display(&self) -> Vec<String> {
        let mut options = vec!["Provider default".to_owned()];
        options.extend(self.models.iter().map(ModelOption::display));
        options
    }

    /// Selected provider index in [`Self::provider_display`].
    pub fn default_provider_index(&self) -> usize {
        self.settings
            .default_provider_id
            .as_deref()
            .and_then(|id| self.providers.iter().position(|provider| provider.id == id))
            .map(|index| index + 1)
            .unwrap_or(0)
    }

    /// Selected model index in [`Self::model_display`].
    pub fn default_model_index(&self) -> usize {
        self.settings
            .default_model_id
            .as_deref()
            .and_then(|id| self.models.iter().position(|model| model.id == id))
            .map(|index| index + 1)
            .unwrap_or(0)
    }

    /// Onboarding status line.
    pub fn onboarding_status(&self) -> String {
        if self.settings.onboarding_completed {
            "Onboarding complete".to_owned()
        } else {
            "Onboarding not complete".to_owned()
        }
    }

    /// Reduced-motion explanation.
    pub const fn reduced_motion_text(&self) -> &'static str {
        "Reduced motion follows the operating-system preference. ChatUI disables transitions and animations when the system requests reduced motion."
    }

    /// Remember-keys explanation.
    pub const fn remember_keys_text(&self) -> &'static str {
        "Remember keys on this device is a web-only preference. Desktop keys use the operating-system keyring or the encrypted fallback store."
    }

    /// Provider entry-point copy.
    pub const fn providers_entry_text(&self) -> &'static str {
        "Manage provider keys, base URLs, and connection tests. Keys are stored in the operating-system keyring or the encrypted fallback store."
    }

    /// Model entry-point copy.
    pub const fn models_entry_text(&self) -> &'static str {
        "Refresh model catalogs, pin or hide models, and choose the global default."
    }

    /// Data entry-point copy.
    pub const fn data_entry_text(&self) -> &'static str {
        "Storage usage, export/import, and clear data live in the data screen (M3.5-10.2)."
    }

    /// Data entry-point route.
    pub const fn data_entry_point(&self) -> SettingsRoute {
        SettingsRoute::Data
    }

    /// Backup placeholder copy.
    pub const fn backup_placeholder(&self) -> BackupPlaceholder {
        BackupPlaceholder {
            title: "Backup",
            message: "ChatUI does not back up or sync data in this release. Everything stays on this device until you export it.",
            detail: "When backup arrives it will encrypt data before upload, keep provider keys out of backup metadata, and use a user-owned destination. No backup credentials are stored today.",
            available: false,
        }
    }

    /// About and privacy copy.
    pub fn about(&self) -> AboutInfo {
        AboutInfo {
            app_name: APP_NAME,
            version: APP_VERSION,
            body: "ChatUI is a local-first, bring-your-own-key chat client. Conversations and settings stay on this device, and chat requests go directly to the provider you select.",
            privacy: vec![
                "ChatUI is bring-your-own-key (BYOK). Provider keys are stored in the operating-system keyring or the encrypted fallback store, never in provider records or exports.",
                "No analytics or telemetry are sent by ChatUI.",
                "Chat content is sent only to the provider you select. OpenRouter forwards requests to an upstream provider under its own data policy.",
                "Remote images in model output are blocked until you allow them globally or load one image explicitly.",
                "Conversations and settings stay on this device. Backup and sync are not available in this release.",
                "Every network destination is visible in provider settings; ChatUI has no hidden endpoints.",
            ],
            docs_url: DOCS_URL,
        }
    }

    /// Every settings control and whether it is live.
    pub fn controls(&self) -> Vec<SettingDescriptor> {
        vec![
            SettingDescriptor {
                key: "theme",
                label: "Theme",
                availability: SettingAvailability::Supported,
            },
            SettingDescriptor {
                key: "density",
                label: "Density",
                availability: SettingAvailability::Supported,
            },
            SettingDescriptor {
                key: "chatFontSize",
                label: "Chat font size",
                availability: SettingAvailability::Supported,
            },
            SettingDescriptor {
                key: "reducedMotion",
                label: "Reduced motion",
                availability: SettingAvailability::DisplayOnly(
                    "Follows the operating-system reduced-motion preference; not stored separately.",
                ),
            },
            SettingDescriptor {
                key: "sendKey",
                label: "Send key",
                availability: SettingAvailability::Supported,
            },
            SettingDescriptor {
                key: "showTokenUsage",
                label: "Show token usage",
                availability: SettingAvailability::Supported,
            },
            SettingDescriptor {
                key: "loadRemoteImages",
                label: "Load remote images",
                availability: SettingAvailability::Supported,
            },
            SettingDescriptor {
                key: "defaultSystemPrompt",
                label: "Default system prompt",
                availability: SettingAvailability::Supported,
            },
            SettingDescriptor {
                key: "defaultParams",
                label: "Default parameters",
                availability: SettingAvailability::Supported,
            },
            SettingDescriptor {
                key: "defaultProviderId",
                label: "Default provider",
                availability: SettingAvailability::Supported,
            },
            SettingDescriptor {
                key: "defaultModelId",
                label: "Default model",
                availability: SettingAvailability::Supported,
            },
            SettingDescriptor {
                key: "rememberKeysOnWeb",
                label: "Remember keys on web",
                availability: SettingAvailability::DisplayOnly(
                    "Web-only preference. Desktop keys use the OS keyring or encrypted fallback store.",
                ),
            },
            SettingDescriptor {
                key: "lastActiveConversationId",
                label: "Last active conversation",
                availability: SettingAvailability::DisplayOnly(
                    "Internal resume state; not a user control.",
                ),
            },
            SettingDescriptor {
                key: "backup",
                label: "Backup",
                availability: SettingAvailability::Future(
                    "Backup and sync arrive in M5; no backup controls are active in this release.",
                ),
            },
            SettingDescriptor {
                key: "onboardingCompleted",
                label: "Onboarding status",
                availability: SettingAvailability::DisplayOnly(
                    "Set by the onboarding flow; shown here for status.",
                ),
            },
        ]
    }
}

/// Reasoning-effort index used by the Slint combo box.
pub const fn reasoning_index(effort: Option<ReasoningEffort>) -> usize {
    match effort {
        None => 0,
        Some(ReasoningEffort::Low) => 1,
        Some(ReasoningEffort::Medium) => 2,
        Some(ReasoningEffort::High) => 3,
    }
}

/// Resolve a reasoning-effort index from the Slint combo box.
pub const fn reasoning_from_index(index: usize) -> Option<ReasoningEffort> {
    match index {
        1 => Some(ReasoningEffort::Low),
        2 => Some(ReasoningEffort::Medium),
        3 => Some(ReasoningEffort::High),
        _ => None,
    }
}

/// Theme index used by the Slint combo box.
pub const fn theme_index(theme: Theme) -> usize {
    match theme {
        Theme::System => 0,
        Theme::Light => 1,
        Theme::Dark => 2,
    }
}

/// Resolve a theme from the Slint combo box.
pub const fn theme_from_index(index: usize) -> Theme {
    match index {
        1 => Theme::Light,
        2 => Theme::Dark,
        _ => Theme::System,
    }
}

/// Density index used by the Slint combo box.
pub const fn density_index(density: Density) -> usize {
    match density {
        Density::Comfortable => 0,
        Density::Compact => 1,
    }
}

/// Resolve a density from the Slint combo box.
pub const fn density_from_index(index: usize) -> Density {
    match index {
        1 => Density::Compact,
        _ => Density::Comfortable,
    }
}

/// Chat font-size index used by the Slint combo box.
pub const fn font_size_index(size: ChatFontSize) -> usize {
    match size {
        ChatFontSize::Sm => 0,
        ChatFontSize::Md => 1,
        ChatFontSize::Lg => 2,
    }
}

/// Resolve a chat font size from the Slint combo box.
pub const fn font_size_from_index(index: usize) -> ChatFontSize {
    match index {
        0 => ChatFontSize::Sm,
        2 => ChatFontSize::Lg,
        _ => ChatFontSize::Md,
    }
}

/// Send-key index used by the Slint combo box.
pub const fn send_key_index(send_key: SendKey) -> usize {
    match send_key {
        SendKey::Enter => 0,
        SendKey::ModEnter => 1,
    }
}

/// Resolve a send key from the Slint combo box.
pub const fn send_key_from_index(index: usize) -> SendKey {
    match index {
        1 => SendKey::ModEnter,
        _ => SendKey::Enter,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use chatui_domain::{
        BrowserCors, InputModality, ModelCapability, ModelSource, OutputModality, ProviderAuthType,
        ProviderKind, Timestamp,
    };

    fn settings() -> Settings {
        chatui_storage::default_settings()
    }

    fn provider(id: &str, label: &str) -> Provider {
        let now = Timestamp::now();
        Provider {
            id: id.to_owned(),
            preset_id: "custom".to_owned(),
            label: label.to_owned(),
            kind: ProviderKind::OpenaiCompatible,
            base_url: "https://example.com/v1".to_owned(),
            auth_type: ProviderAuthType::Bearer,
            secret_id: None,
            headers: std::collections::BTreeMap::new(),
            enabled: true,
            browser_cors: BrowserCors::Unknown,
            metadata: None,
            created_at: now,
            updated_at: now,
        }
    }

    fn model(id: &str, provider_id: &str, label: &str) -> Model {
        let now = Timestamp::now();
        Model {
            id: id.to_owned(),
            provider_id: provider_id.to_owned(),
            remote_id: format!("remote/{id}"),
            label: label.to_owned(),
            context_window: Some(128_000),
            max_output_tokens: Some(4_096),
            capabilities: vec![ModelCapability::Text],
            pricing: None,
            canonical_slug: None,
            input_modalities: vec![InputModality::Text],
            output_modalities: vec![OutputModality::Text],
            supported_parameters: Vec::new(),
            metadata: None,
            hidden: false,
            pinned: false,
            source: ModelSource::Manual,
            created_at: now,
            updated_at: now,
        }
    }

    #[test]
    fn appearance_resolves_to_shell_settings() {
        let mut settings = settings();
        settings.theme = Theme::Dark;
        settings.density = Density::Compact;
        settings.chat_font_size = ChatFontSize::Lg;
        let view_model =
            SettingsViewModel::new(settings, Vec::new(), Vec::new(), SettingsRoute::Appearance);

        let theme = view_model.theme_settings();
        assert_eq!(theme.theme, Theme::Dark);
        assert_eq!(theme.density, Density::Compact);
        assert_eq!(theme.chat_font_size, ChatFontSize::Lg);
        assert_eq!(theme.reduced_motion, ReducedMotion::System);

        let mut shell = ShellState::new(1024.0);
        view_model.apply_to_shell(&mut shell);
        assert_eq!(shell.resolved_theme(), crate::theme::ResolvedTheme::Dark);
        assert_eq!(
            shell.density_scale(),
            crate::tokens::DensityScale::compact()
        );
        assert_eq!(shell.chat_typography(), crate::tokens::ChatTypography::lg());
    }

    #[test]
    fn system_theme_and_reduced_motion_follow_the_os() {
        let view_model = SettingsViewModel::new(
            settings(),
            Vec::new(),
            Vec::new(),
            SettingsRoute::Appearance,
        );
        let mut shell = ShellState::new(1024.0);
        shell.system_is_dark = true;
        shell.system_reduced_motion = true;
        view_model.apply_to_shell(&mut shell);

        assert_eq!(shell.resolved_theme(), crate::theme::ResolvedTheme::Dark);
        assert!(shell.reduced_motion());
    }

    #[test]
    fn provider_and_model_indices_include_the_default_options() {
        let mut settings = settings();
        settings.default_provider_id = Some("p2".to_owned());
        settings.default_model_id = Some("m2".to_owned());
        let view_model = SettingsViewModel::new(
            settings,
            vec![provider("p1", "One"), provider("p2", "Two")],
            vec![
                model("m1", "p1", "Model One"),
                model("m2", "p2", "Model Two"),
            ],
            SettingsRoute::ChatDefaults,
        );

        assert_eq!(
            view_model.provider_display(),
            vec![
                "First enabled provider".to_owned(),
                "One (p1)".to_owned(),
                "Two (p2)".to_owned()
            ]
        );
        assert_eq!(view_model.default_provider_index(), 2);
        assert_eq!(
            view_model.model_display(),
            vec![
                "Provider default".to_owned(),
                "Model One (remote/m1)".to_owned(),
                "Model Two (remote/m2)".to_owned()
            ]
        );
        assert_eq!(view_model.default_model_index(), 2);
    }

    #[test]
    fn disabled_providers_and_hidden_models_are_labelled() {
        let mut provider = provider("p1", "One");
        provider.enabled = false;
        let mut model = model("m1", "p1", "Model One");
        model.hidden = true;
        let view_model = SettingsViewModel::new(
            settings(),
            vec![provider],
            vec![model],
            SettingsRoute::ChatDefaults,
        );

        assert_eq!(view_model.provider_display()[1], "One (p1, disabled)");
        assert_eq!(
            view_model.model_display()[1],
            "Model One (remote/m1, hidden)"
        );
    }

    #[test]
    fn controls_document_display_only_and_future_settings() {
        let view_model = SettingsViewModel::new(
            settings(),
            Vec::new(),
            Vec::new(),
            SettingsRoute::Appearance,
        );
        let controls = view_model.controls();

        let keys: Vec<_> = controls.iter().map(|control| control.key).collect();
        for key in [
            "theme",
            "density",
            "chatFontSize",
            "sendKey",
            "defaultProviderId",
            "defaultModelId",
            "defaultSystemPrompt",
            "defaultParams",
            "rememberKeysOnWeb",
            "showTokenUsage",
            "loadRemoteImages",
            "lastActiveConversationId",
            "backup",
            "onboardingCompleted",
        ] {
            assert!(keys.contains(&key), "missing control {key}");
        }

        let remember = controls
            .iter()
            .find(|control| control.key == "rememberKeysOnWeb")
            .expect("remember keys control");
        assert!(!remember.availability.is_supported());
        assert!(remember.availability.reason().unwrap().contains("Web-only"));

        let reduced = controls
            .iter()
            .find(|control| control.key == "reducedMotion")
            .expect("reduced motion control");
        assert!(!reduced.availability.is_supported());
        assert!(
            reduced
                .availability
                .reason()
                .unwrap()
                .contains("operating-system")
        );

        let backup = controls
            .iter()
            .find(|control| control.key == "backup")
            .expect("backup control");
        assert!(!backup.availability.is_supported());
        assert!(backup.availability.reason().unwrap().contains("M5"));
    }

    #[test]
    fn about_and_backup_copy_state_the_privacy_boundaries() {
        let view_model =
            SettingsViewModel::new(settings(), Vec::new(), Vec::new(), SettingsRoute::About);
        let about = view_model.about();
        assert_eq!(about.app_name, "ChatUI");
        assert!(about.privacy.iter().any(|line| line.contains("BYOK")));
        assert!(
            about
                .privacy
                .iter()
                .any(|line| line.contains("No analytics or telemetry"))
        );
        assert!(about.docs_url.starts_with("https://"));

        let backup = view_model.backup_placeholder();
        assert!(!backup.available);
        assert!(backup.message.contains("does not back up"));
        assert!(backup.detail.contains("encrypt"));
    }

    #[test]
    fn data_entry_point_is_the_data_route() {
        let view_model = SettingsViewModel::new(
            settings(),
            Vec::new(),
            Vec::new(),
            SettingsRoute::Appearance,
        );
        assert_eq!(view_model.data_entry_point(), SettingsRoute::Data);
        assert_eq!(
            view_model.data_entry_point().destination(),
            crate::shell::Destination::Data
        );
    }

    #[test]
    fn combo_box_indices_round_trip() {
        for theme in [Theme::System, Theme::Light, Theme::Dark] {
            assert_eq!(theme_from_index(theme_index(theme)), theme);
        }
        for density in [Density::Comfortable, Density::Compact] {
            assert_eq!(density_from_index(density_index(density)), density);
        }
        for size in [ChatFontSize::Sm, ChatFontSize::Md, ChatFontSize::Lg] {
            assert_eq!(font_size_from_index(font_size_index(size)), size);
        }
        for send_key in [SendKey::Enter, SendKey::ModEnter] {
            assert_eq!(send_key_from_index(send_key_index(send_key)), send_key);
        }
        for effort in [
            None,
            Some(ReasoningEffort::Low),
            Some(ReasoningEffort::Medium),
            Some(ReasoningEffort::High),
        ] {
            assert_eq!(reasoning_from_index(reasoning_index(effort)), effort);
        }
    }
}
