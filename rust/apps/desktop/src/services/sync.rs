//! Synchronous bridge over the shared async runtime.
//!
//! The Slint-facing services are synchronous. [`SyncBridge`] runs local SQLite
//! futures on the one desktop runtime, and [`SyncSettingsStore`] is the first
//! adapter built on it. Later adapters should reuse the same bridge.

use std::future::Future;
use std::sync::Arc;

use chatui_domain::Settings;
use chatui_storage::SettingsRepository;
use chatui_ui::settings::{SettingsError, SettingsStore};
use tokio::runtime::Runtime;

/// A cloneable handle that runs futures on the shared desktop runtime.
#[derive(Clone)]
pub struct SyncBridge {
    runtime: Arc<Runtime>,
}

impl SyncBridge {
    /// Create a bridge over the shared runtime.
    pub fn new(runtime: Arc<Runtime>) -> Self {
        Self { runtime }
    }

    /// Run a future to completion.
    ///
    /// This must not be called from inside an async task on the same runtime.
    pub fn block_on<F: Future>(&self, future: F) -> F::Output {
        self.runtime.block_on(future)
    }
}

/// Synchronous [`SettingsStore`] over [`chatui_storage::SettingsRepository`].
pub struct SyncSettingsStore {
    bridge: SyncBridge,
    repository: Arc<dyn SettingsRepository>,
}

impl SyncSettingsStore {
    /// Create a settings adapter over the shared bridge and repository.
    pub fn new(bridge: SyncBridge, repository: Arc<dyn SettingsRepository>) -> Self {
        Self { bridge, repository }
    }
}

impl SettingsStore for SyncSettingsStore {
    fn load(&self) -> Result<Settings, SettingsError> {
        self.bridge
            .block_on(self.repository.load())
            .map_err(SettingsError::from)
    }

    fn save(&self, settings: &Settings) -> Result<(), SettingsError> {
        self.bridge
            .block_on(self.repository.save(settings))
            .map_err(SettingsError::from)
    }
}
