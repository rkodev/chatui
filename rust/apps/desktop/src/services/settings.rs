//! Settings adapters over the shared SQLite runtime.
//!
//! [`SettingsStore`] persists the complete settings object through
//! [`chatui_storage::SettingsRepository`]. [`ProviderModelCatalog`] feeds the
//! settings default pickers from the provider and model repositories.
//!
//! Both adapters reuse [`ServiceRuntime`] and its [`SyncBridge`]; they never
//! create a second runtime or database pool.

use std::sync::Arc;

use chatui_domain::{Model, Provider, Settings};
use chatui_storage::{ModelRepository, ProviderRepository, SettingsRepository};
use chatui_ui::settings::{
    ProviderModelCatalog as ProviderModelCatalogTrait, SettingsError,
    SettingsStore as SettingsStoreTrait,
};

use crate::services::ServiceRuntime;
use crate::services::sync::SyncBridge;

/// Synchronous settings store over [`chatui_storage::SettingsRepository`].
pub struct SettingsStore {
    bridge: SyncBridge,
    repository: Arc<dyn SettingsRepository>,
}

impl SettingsStore {
    /// Create a settings adapter over the shared bridge and repository.
    pub fn new(bridge: SyncBridge, repository: Arc<dyn SettingsRepository>) -> Self {
        Self { bridge, repository }
    }

    /// Build the adapter from the shared desktop runtime.
    pub fn from_runtime(runtime: &ServiceRuntime) -> Self {
        Self::new(runtime.bridge(), runtime.settings())
    }
}

impl SettingsStoreTrait for SettingsStore {
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

/// Provider and model catalog over the shared SQLite repositories.
pub struct ProviderModelCatalog {
    bridge: SyncBridge,
    providers: Arc<dyn ProviderRepository>,
    models: Arc<dyn ModelRepository>,
}

impl ProviderModelCatalog {
    /// Create a catalog over the shared bridge and repositories.
    pub fn new(
        bridge: SyncBridge,
        providers: Arc<dyn ProviderRepository>,
        models: Arc<dyn ModelRepository>,
    ) -> Self {
        Self {
            bridge,
            providers,
            models,
        }
    }

    /// Build the catalog from the shared desktop runtime.
    pub fn from_runtime(runtime: &ServiceRuntime) -> Self {
        Self::new(runtime.bridge(), runtime.providers(), runtime.models())
    }
}

impl ProviderModelCatalogTrait for ProviderModelCatalog {
    fn providers(&self) -> Result<Vec<Provider>, SettingsError> {
        self.bridge
            .block_on(self.providers.list())
            .map_err(SettingsError::from)
    }

    fn models(&self, provider_id: &str) -> Result<Vec<Model>, SettingsError> {
        self.bridge
            .block_on(self.models.list_by_provider(provider_id))
            .map_err(SettingsError::from)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::BTreeMap;

    use chatui_domain::{
        BrowserCors, ModelCapability, ModelSource, ProviderAuthType, ProviderKind, Theme, Timestamp,
    };
    use chatui_storage::default_settings;

    fn runtime() -> (tempfile::TempDir, ServiceRuntime) {
        let directory = tempfile::tempdir().expect("create temporary directory");
        let runtime =
            ServiceRuntime::open_at(directory.path().join("chatui.sqlite3"), directory.path())
                .expect("open runtime");
        (directory, runtime)
    }

    fn provider(id: &str) -> Provider {
        let now = Timestamp::now();
        Provider {
            id: id.to_owned(),
            preset_id: "custom".to_owned(),
            label: format!("Provider {id}"),
            kind: ProviderKind::OpenaiCompatible,
            base_url: "https://example.com/v1".to_owned(),
            auth_type: ProviderAuthType::Bearer,
            secret_id: None,
            headers: BTreeMap::new(),
            enabled: true,
            browser_cors: BrowserCors::Unknown,
            metadata: None,
            created_at: now,
            updated_at: now,
        }
    }

    fn model(id: &str, provider_id: &str) -> Model {
        let now = Timestamp::now();
        Model {
            id: id.to_owned(),
            provider_id: provider_id.to_owned(),
            remote_id: format!("remote/{id}"),
            label: format!("Model {id}"),
            context_window: Some(128_000),
            max_output_tokens: Some(4_096),
            capabilities: vec![ModelCapability::Text],
            pricing: None,
            canonical_slug: None,
            input_modalities: vec![chatui_domain::InputModality::Text],
            output_modalities: vec![chatui_domain::OutputModality::Text],
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
    fn settings_round_trip_through_sqlite() {
        let (_directory, runtime) = runtime();
        let store = SettingsStore::from_runtime(&runtime);

        let mut settings = store.load().expect("load defaults");
        assert_eq!(settings, default_settings());

        settings.theme = Theme::Dark;
        settings.show_token_usage = false;
        settings.default_system_prompt = "Be concise.".to_owned();
        store.save(&settings).expect("save settings");

        let loaded = store.load().expect("reload settings");
        assert_eq!(loaded, settings);
    }

    #[test]
    fn catalog_lists_providers_and_models() {
        let (_directory, runtime) = runtime();
        let providers = runtime.providers();
        runtime
            .block_on(providers.upsert(&provider("p1")))
            .expect("seed provider");
        let models = runtime.models();
        runtime
            .block_on(models.insert(&model("m1", "p1")))
            .expect("seed model");

        let catalog = ProviderModelCatalog::from_runtime(&runtime);

        let listed = catalog.providers().expect("list providers");
        assert_eq!(listed.len(), 1);
        assert_eq!(listed[0].id, "p1");

        let listed_models = catalog.models("p1").expect("list models");
        assert_eq!(listed_models.len(), 1);
        assert_eq!(listed_models[0].id, "m1");

        assert!(catalog.models("missing").expect("list models").is_empty());
    }
}
