//! Provider management: list, detail, custom providers, and key lifecycle.
//!
//! The module is split into framework-independent view models ([`state`]),
//! input validation ([`validation`]), error copy ([`error`]), injected services
//! ([`service`]), and a Slint view/controller ([`view`]). The service never
//! talks to SQLite or the network directly; callers inject the provider, model,
//! conversation, settings, secret, and key-validation dependencies.
//!
//! Keys are never stored on provider records or rendered in the UI. Provider
//! records carry only a `secret_id`; the key lives in the injected
//! [`chatui_secrets::SecretStore`]. The `/key` metadata cached on the provider
//! record is non-secret and is the only key-related data the detail view shows.

pub mod error;
pub mod service;
pub mod state;
pub mod validation;
pub mod view;

pub use error::{ProviderManagementError, ProviderManagementErrorCopy, describe_provider_error};
pub use service::{
    ConversationStore, ModelStore, ProviderKeyValidator, ProviderManagementService, ProviderStore,
    SettingsStore,
};
pub use state::{
    ConfirmationAction, CustomProviderForm, PendingConfirmation, ProviderDetailViewModel,
    ProviderKeyMetadata, ProviderListEntry, ProviderListViewModel, ProviderManagementViewModel,
    cors_label, format_rate_limit, format_usd,
};
pub use validation::{
    ValidatedBaseUrl, format_headers_text, parse_headers_text, validate_api_key,
    validate_auth_type, validate_base_url, validate_headers,
};
pub use view::{ProviderManagementController, ProviderManagementWindow, sync_window};
