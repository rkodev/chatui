//! Data controls: storage usage, export/import, clear data, and stored keys.
//!
//! The module is split into framework-independent sections ([`route`]), the
//! view model ([`state`]), error copy ([`error`]), injected services
//! ([`service`]), and a Slint view/controller ([`view`]).
//!
//! Safety rules enforced by the view model:
//!
//! * exports exclude secrets and exclude attachment bytes unless the user opts
//!   in;
//! * the plaintext warning is always visible before an export;
//! * import defaults to merge and requires an explicit confirmation for
//!   replace;
//! * clear-all requires the exact typed phrase [`state::CLEAR_ALL_CONFIRMATION`];
//! * per-key removal requires a second click and never renders a secret value.

pub mod error;
pub mod route;
pub mod service;
pub mod state;
pub mod view;

#[cfg(test)]
mod tests;

pub use error::{DataError, DataErrorCopy, describe_data_error};
pub use route::{DataNavigation, DataSection};
pub use service::{DataService, MemoryAttachment, MemoryData, MemoryDataService};
pub use state::{
    CLEAR_ALL_CONFIRMATION, ClearRequest, ClearSummary, ConversationOption, DataBackupPlaceholder,
    DataOperation, DataViewModel, EXPORT_EXCLUDES, EXPORT_INCLUDES, EXPORT_WARNING, ExportArtifact,
    ExportDocumentation, ExportFormat, ExportRequest, ExportSummary, ImportMode, ImportRequest,
    ImportSummaryView, KeyRowView, ProviderKeySummary, RemoveKeyRequest, StorageUsage,
    StorageUsageEntry, USAGE_NOTE, UsageRowView, format_bytes,
};
pub use view::{DataController, DataWindow, sync_window};
