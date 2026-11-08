//! First-run onboarding: welcome, key entry, connection test, default model,
//! and completion.
//!
//! The module is split into a framework-independent state machine
//! ([`state`]), error copy ([`error`]), injected services ([`service`]), and a
//! Slint view/controller ([`view`]). The service never talks to SQLite or the
//! network directly; callers inject the catalog, validator, repositories, and
//! secret store.

pub mod error;
pub mod service;
pub mod state;
pub mod view;

pub use error::{
    OnboardingError, OnboardingErrorCopy, describe_catalog_error, describe_onboarding_error,
};
pub use service::{
    CatalogLoad, CatalogSource, ConnectInput, ConnectResult, KeyValidator, ModelStore,
    OnboardingService, OnboardingServiceLike, ProviderStore, SettingsStore,
};
pub use state::{OnboardingStep, OnboardingViewModel};
pub use view::{OnboardingController, OnboardingRoute, OnboardingWindow, sync_window};
