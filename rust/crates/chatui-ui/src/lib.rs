//! Shared views, design tokens, router, and view models.
//!
//! The UI foundation is framework-independent where possible. Slint is used
//! only for the shell component and the desktop entry point.

pub mod accessibility;
pub mod onboarding;
pub mod providers;
pub mod shell;
pub mod theme;
pub mod tokens;

pub use accessibility::{
    AccessibleRole, FocusOrder, FocusRing, Focusable, Key, KeyboardAction, Modifiers,
};
pub use onboarding::{
    CatalogLoad, CatalogSource, ConnectInput, ConnectResult, KeyValidator, ModelStore,
    OnboardingController, OnboardingError, OnboardingErrorCopy, OnboardingRoute, OnboardingService,
    OnboardingServiceLike, OnboardingStep, OnboardingViewModel, OnboardingWindow, ProviderStore,
    SettingsStore, describe_catalog_error, describe_onboarding_error, sync_window,
};
pub use providers::{
    ConfirmationAction, ConversationStore, CustomProviderForm, ModelStore as ProviderModelStore,
    PendingConfirmation, ProviderDetailViewModel, ProviderKeyMetadata, ProviderKeyValidator,
    ProviderListEntry, ProviderListViewModel, ProviderManagementController,
    ProviderManagementError, ProviderManagementErrorCopy, ProviderManagementService,
    ProviderManagementViewModel, ProviderManagementWindow,
    ProviderStore as ProviderManagementStore, SettingsStore as ProviderSettingsStore,
    ValidatedBaseUrl, cors_label, describe_provider_error, format_headers_text, format_rate_limit,
    format_usd, parse_headers_text, sync_window as sync_provider_window, validate_api_key,
    validate_auth_type, validate_base_url, validate_headers,
};
pub use shell::{Breakpoint, Destination, LayoutMode, ShellController, ShellState, ShellWindow};
pub use theme::{ReducedMotion, ResolvedTheme, ThemeSettings};
pub use tokens::{ChatTypography, DensityScale, Motion, Rgba, Tokens};
