//! Model management, discovery refresh, and the model picker.
//!
//! The module is split into framework-independent view models ([`state`]),
//! error copy ([`error`]), injected services ([`service`]), and a Slint
//! view/controller ([`view`]). The service never talks to SQLite or the network
//! directly; callers inject the model repository, provider repository,
//! discovery source, and settings.
//!
//! Discovery is best-effort. A failed refresh keeps cached models and shows a
//! non-blocking notice with retry. When the cache is empty, the preset fallback
//! list is persisted so the picker still works. Provider model IDs are never
//! rewritten, and model metadata never contains provider keys.

pub mod error;
pub mod service;
pub mod state;
pub mod view;

pub use error::{ModelManagementError, ModelManagementErrorCopy, describe_model_error};
pub use service::{
    ModelDiscovery, ModelManagementService, ModelRepository, ProviderRepository, RefreshResult,
    SettingsStore,
};
pub use state::{
    ManualModelForm, ModelAvailability, ModelGroup, ModelListViewModel, ModelManagementViewModel,
    ModelPickerViewModel, ModelRow, build_model_groups, capability_chip_label,
    format_context_window, format_modalities, format_model_price, format_price_per_million,
    is_free_model, model_availability, model_matches_query, parse_capabilities,
    parse_context_window, source_label,
};
pub use view::{ModelManagementController, ModelManagementWindow, sync_window};
