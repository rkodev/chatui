//! Shared views, design tokens, router, and view models.
//!
//! The UI foundation is framework-independent where possible. Slint is used
//! only for the shell component and the desktop entry point.

pub mod accessibility;
pub mod onboarding;
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
pub use shell::{Breakpoint, Destination, LayoutMode, ShellController, ShellState, ShellWindow};
pub use theme::{ReducedMotion, ResolvedTheme, ThemeSettings};
pub use tokens::{ChatTypography, DensityScale, Motion, Rgba, Tokens};
