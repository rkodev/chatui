//! Model management adapters over the shared SQLite runtime and provider
//! crate.
//!
//! The repository adapters are thin synchronous wrappers over the async storage
//! repositories. [`ProviderModelDiscovery`] maps a provider preset to the
//! matching `chatui-providers` adapter and calls its model-list endpoint.
//!
//! Network boundary: [`ProviderModelDiscovery::discover`] blocks the calling
//! thread on the shared runtime for at most [`DEFAULT_NETWORK_TIMEOUT`]. Callers
//! must not invoke it on the Slint thread; `M3.6-06.1` runs it on a worker task.

#![allow(clippy::result_large_err)]

use std::sync::Arc;
use std::time::Duration;

use chatui_domain::{ChatError, ChatErrorCode, Model, Provider, Settings};
use chatui_providers::{CancellationToken, CatalogModel, HttpClient, ProviderRegistry};
use chatui_secrets::SecretStore;
use chatui_storage::{
    ModelRepository as StorageModelRepository, ProviderRepository as StorageProviderRepository,
    SettingsRepository,
};
use chatui_ui::models::{
    ModelDiscovery as ModelDiscoveryTrait, ModelManagementError,
    ModelRepository as ModelRepositoryTrait, ProviderRepository as ProviderRepositoryTrait,
    SettingsStore as SettingsStoreTrait,
};

use crate::services::ServiceRuntime;
use crate::services::providers::{
    DEFAULT_NETWORK_TIMEOUT, adapter_for_provider, chat_error, network_error, provider_context,
    resolve_api_key, timeout_error,
};
use crate::services::sync::SyncBridge;

/// Synchronous model repository over [`chatui_storage::ModelRepository`].
pub struct ModelRepository {
    bridge: SyncBridge,
    repository: Arc<dyn StorageModelRepository>,
}

impl ModelRepository {
    /// Create a model repository over the shared bridge and storage repository.
    pub fn new(bridge: SyncBridge, repository: Arc<dyn StorageModelRepository>) -> Self {
        Self { bridge, repository }
    }

    /// Build the repository from the shared desktop runtime.
    pub fn from_runtime(runtime: &ServiceRuntime) -> Self {
        Self::new(runtime.bridge(), runtime.models())
    }
}

impl ModelRepositoryTrait for ModelRepository {
    fn list_all(&self) -> Result<Vec<Model>, ModelManagementError> {
        self.bridge
            .block_on(self.repository.list_all())
            .map_err(storage_error)
    }

    fn list_by_provider(&self, provider_id: &str) -> Result<Vec<Model>, ModelManagementError> {
        self.bridge
            .block_on(self.repository.list_by_provider(provider_id))
            .map_err(storage_error)
    }

    fn get(&self, id: &str) -> Result<Option<Model>, ModelManagementError> {
        self.bridge
            .block_on(self.repository.get(id))
            .map_err(storage_error)
    }

    fn insert(&self, model: &Model) -> Result<(), ModelManagementError> {
        self.bridge
            .block_on(self.repository.insert(model))
            .map_err(storage_error)
    }

    fn update(&self, model: &Model) -> Result<(), ModelManagementError> {
        self.bridge
            .block_on(self.repository.update(model))
            .map_err(storage_error)
    }

    fn set_pinned(&self, id: &str, pinned: bool) -> Result<(), ModelManagementError> {
        self.bridge
            .block_on(self.repository.set_pinned(id, pinned))
            .map_err(storage_error)
    }

    fn set_hidden(&self, id: &str, hidden: bool) -> Result<(), ModelManagementError> {
        self.bridge
            .block_on(self.repository.set_hidden(id, hidden))
            .map_err(storage_error)
    }
}

/// Synchronous provider repository over
/// [`chatui_storage::ProviderRepository`].
pub struct ProviderRepository {
    bridge: SyncBridge,
    repository: Arc<dyn StorageProviderRepository>,
}

impl ProviderRepository {
    /// Create a provider repository over the shared bridge and storage
    /// repository.
    pub fn new(bridge: SyncBridge, repository: Arc<dyn StorageProviderRepository>) -> Self {
        Self { bridge, repository }
    }

    /// Build the repository from the shared desktop runtime.
    pub fn from_runtime(runtime: &ServiceRuntime) -> Self {
        Self::new(runtime.bridge(), runtime.providers())
    }
}

impl ProviderRepositoryTrait for ProviderRepository {
    fn list(&self) -> Result<Vec<Provider>, ModelManagementError> {
        self.bridge
            .block_on(self.repository.list())
            .map_err(storage_error)
    }

    fn get(&self, id: &str) -> Result<Option<Provider>, ModelManagementError> {
        self.bridge
            .block_on(self.repository.get(id))
            .map_err(storage_error)
    }
}

/// Synchronous settings store over [`chatui_storage::SettingsRepository`].
pub struct SettingsStore {
    bridge: SyncBridge,
    repository: Arc<dyn SettingsRepository>,
}

impl SettingsStore {
    /// Create a settings store over the shared bridge and repository.
    pub fn new(bridge: SyncBridge, repository: Arc<dyn SettingsRepository>) -> Self {
        Self { bridge, repository }
    }

    /// Build the store from the shared desktop runtime.
    pub fn from_runtime(runtime: &ServiceRuntime) -> Self {
        Self::new(runtime.bridge(), runtime.settings())
    }
}

impl SettingsStoreTrait for SettingsStore {
    fn load(&self) -> Result<Settings, ModelManagementError> {
        self.bridge
            .block_on(self.repository.load())
            .map_err(storage_error)
    }

    fn save(&self, settings: &Settings) -> Result<(), ModelManagementError> {
        self.bridge
            .block_on(self.repository.save(settings))
            .map_err(storage_error)
    }
}

/// Model discovery backed by the `chatui-providers` per-preset adapters.
///
/// The adapter is selected from the provider preset, falling back to the
/// provider kind when the preset is unknown. The stored key is read from the
/// secret store only to build the request context; it is never persisted or
/// logged here.
pub struct ProviderModelDiscovery {
    bridge: SyncBridge,
    client: HttpClient,
    registry: ProviderRegistry,
    secrets: Arc<dyn SecretStore>,
    timeout: Duration,
}

impl ProviderModelDiscovery {
    /// Build discovery over the shared runtime, secret store, and a fresh HTTP
    /// client.
    pub fn new(bridge: SyncBridge, secrets: Arc<dyn SecretStore>) -> Result<Self, ChatError> {
        let client = HttpClient::new().map_err(|error| network_error(error, None))?;
        Self::with_client(bridge, client, secrets)
    }

    /// Build discovery from the shared desktop runtime.
    pub fn from_runtime(
        runtime: &ServiceRuntime,
        secrets: Arc<dyn SecretStore>,
    ) -> Result<Self, ChatError> {
        Self::new(runtime.bridge(), secrets)
    }

    /// Build discovery over an injected HTTP client.
    pub fn with_client(
        bridge: SyncBridge,
        client: HttpClient,
        secrets: Arc<dyn SecretStore>,
    ) -> Result<Self, ChatError> {
        let registry = ProviderRegistry::load().map_err(|error| {
            chat_error(
                ChatErrorCode::Unknown,
                format!("The provider catalog is unavailable: {error}"),
                None,
            )
        })?;

        Ok(Self {
            bridge,
            client,
            registry,
            secrets,
            timeout: DEFAULT_NETWORK_TIMEOUT,
        })
    }

    /// Override the network timeout.
    pub fn with_timeout(mut self, timeout: Duration) -> Self {
        self.timeout = timeout;
        self
    }
}

impl ModelDiscoveryTrait for ProviderModelDiscovery {
    fn discover(&self, provider: &Provider) -> Result<Vec<CatalogModel>, ChatError> {
        let api_key = resolve_api_key(self.secrets.as_ref(), provider)?;
        let context = provider_context(&self.registry, provider, api_key)?;
        let adapter = adapter_for_provider(&self.registry, provider)?;
        let cancel = CancellationToken::new();

        let result = self.bridge.block_on(async {
            tokio::time::timeout(
                self.timeout,
                adapter.list_models(&self.client, &context, &cancel),
            )
            .await
        });

        match result {
            Ok(result) => result,
            Err(_) => Err(timeout_error(provider)),
        }
    }
}

fn storage_error(error: chatui_storage::StorageError) -> ModelManagementError {
    ModelManagementError::Storage(error.to_string())
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::BTreeMap;

    use chatui_domain::{
        BrowserCors, ChatErrorCode, InputModality, ModelCapability, ModelSource, OutputModality,
        ProviderAuthType, ProviderKind, Timestamp,
    };
    use chatui_secrets::SessionStore;
    use chatui_ui::models::ModelManagementService;

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

    fn catalog_model(remote_id: &str) -> CatalogModel {
        CatalogModel {
            remote_id: remote_id.to_owned(),
            canonical_slug: None,
            label: remote_id.to_owned(),
            context_window: Some(128_000),
            max_output_tokens: Some(4_096),
            capabilities: vec![ModelCapability::Text],
            pricing: None,
            input_modalities: vec![InputModality::Text],
            output_modalities: vec![OutputModality::Text],
            supported_parameters: Vec::new(),
            metadata: None,
            is_free: false,
            hidden: false,
        }
    }

    struct FakeDiscovery {
        result: Result<Vec<CatalogModel>, ChatError>,
    }

    impl ModelDiscoveryTrait for FakeDiscovery {
        fn discover(&self, _provider: &Provider) -> Result<Vec<CatalogModel>, ChatError> {
            self.result.clone()
        }
    }

    fn service(
        runtime: &ServiceRuntime,
        discovery: Arc<dyn ModelDiscoveryTrait>,
    ) -> ModelManagementService {
        ModelManagementService::new(
            Arc::new(ModelRepository::from_runtime(runtime)),
            Arc::new(ProviderRepository::from_runtime(runtime)),
            discovery,
            Arc::new(SettingsStore::from_runtime(runtime)),
        )
    }

    #[test]
    fn model_listing_pin_hide_and_default_selection_persist() {
        let (_directory, runtime) = runtime();
        let discovery = Arc::new(FakeDiscovery {
            result: Ok(Vec::new()),
        });
        let service = service(&runtime, discovery);

        let providers = runtime.providers();
        runtime
            .block_on(providers.upsert(&provider("p1")))
            .expect("seed provider");
        let models = runtime.models();
        runtime
            .block_on(models.insert(&model("m1", "p1")))
            .expect("seed model");

        let list = service.list_models(Some("p1")).expect("list models");
        assert_eq!(list.total, 1);

        service.set_pinned("m1", true).expect("pin model");
        service.set_hidden("m1", true).expect("hide model");

        let stored = runtime
            .block_on(models.get("m1"))
            .expect("get model")
            .expect("model exists");
        assert!(stored.pinned);
        assert!(stored.hidden);

        service.set_default_model("m1").expect("set default model");

        let settings = runtime
            .block_on(runtime.settings().load())
            .expect("load settings");
        assert_eq!(settings.default_provider_id.as_deref(), Some("p1"));
        assert_eq!(settings.default_model_id.as_deref(), Some("m1"));
    }

    #[test]
    fn discovery_refresh_persists_models_offline() {
        let (_directory, runtime) = runtime();
        let discovery = Arc::new(FakeDiscovery {
            result: Ok(vec![catalog_model("author/model")]),
        });
        let service = service(&runtime, discovery);

        let providers = runtime.providers();
        runtime
            .block_on(providers.upsert(&provider("p1")))
            .expect("seed provider");

        let result = service.refresh("p1").expect("refresh models");
        assert!(!result.degraded);
        assert_eq!(result.created, 1);

        let models = runtime
            .block_on(runtime.models().list_by_provider("p1"))
            .expect("list models");
        assert_eq!(models.len(), 1);
        assert_eq!(models[0].remote_id, "author/model");
    }

    #[test]
    fn production_discovery_rejects_cleartext_without_network() {
        let (_directory, runtime) = runtime();
        let secrets = Arc::new(SessionStore::new());
        let discovery =
            ProviderModelDiscovery::from_runtime(&runtime, secrets).expect("build discovery");

        let mut provider = provider("p1");
        provider.preset_id = "openrouter".to_owned();
        provider.base_url = "http://api.example.com/v1".to_owned();

        let error = discovery
            .discover(&provider)
            .expect_err("public cleartext is rejected before any request");

        assert_eq!(error.code, ChatErrorCode::InvalidRequest);
    }
}
