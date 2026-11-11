//! Settings screens and behavior wiring.
//!
//! The module is split into framework-independent routes ([`route`]), the view
//! model ([`state`]), error copy ([`error`]), injected services ([`service`]),
//! and a Slint view/controller ([`view`]).
//!
//! Every live setting is persisted through the settings repository and applied
//! to its real consumer:
//!
//! * appearance -> shell theme, density, chat typography, and reduced motion;
//! * send key -> composer key policy;
//! * token usage -> message metadata;
//! * remote images -> Markdown render options;
//! * default prompt and parameters -> new conversations;
//! * provider/model defaults -> the model picker and new conversations;
//! * onboarding completion -> the onboarding route.
//!
//! Settings without a desktop consumer are labelled as display-only or future
//! in [`SettingsViewModel::controls`] and rendered as text, not dead controls.

pub mod backup;
pub mod error;
pub mod route;
pub mod service;
pub mod state;
pub mod view;

#[cfg(test)]
mod tests;

pub use backup::{
    BACKUP_BACKEND_LABELS, BACKUP_CREDENTIAL_SECRET_ID, BackupConfirmation, BackupError,
    BackupForm, BackupService, BackupSettingsState, BackupStatus, DELETE_REMOTE_CONFIRMATION,
    REPLACE_CONFIRMATION, RestoreOutcome, backend_from_index, backend_index,
};
pub use error::{SettingsError, SettingsErrorCopy, describe_settings_error};
pub use route::{SettingsNavigation, SettingsRoute};
pub use service::{
    MemoryProviderModelCatalog, MemorySettingsStore, ProviderModelCatalog,
    RepositorySettingsPersistence, SettingsPersistence, SettingsService, SettingsStore,
};
pub use state::{
    APP_NAME, APP_VERSION, AboutInfo, DOCS_URL, MAX_SYSTEM_PROMPT_CHARS, ModelOption,
    ProviderOption, SettingAvailability, SettingDescriptor, SettingsViewModel, density_from_index,
    density_index, font_size_from_index, font_size_index, reasoning_from_index, reasoning_index,
    send_key_from_index, send_key_index, theme_from_index, theme_index,
};
pub use view::{SettingsController, SettingsWindow, sync_backup_window, sync_window};
