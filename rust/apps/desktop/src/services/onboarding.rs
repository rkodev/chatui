//! First-run onboarding adapters over the shared SQLite runtime and provider
//! crate.
//!
//! [`OpenRouterCatalogSource`] loads the public `/models` catalog with the
//! shared HTTP client and a bounded timeout. [`PresetKeyValidator`] delegates to
//! the same per-preset validation path used by
//! [`crate::services::providers::ProviderKeyValidator`]. The provider, model,
//! and settings stores are thin synchronous wrappers over the shared SQLite
//! repositories.
//!
//! Network boundary: [`OpenRouterCatalogSource::load_catalog`] and
//! [`PresetKeyValidator::validate_key`] block the calling thread on the shared
//! runtime for at most [`DEFAULT_NETWORK_TIMEOUT`]. Callers must not invoke them
//! on the Slint thread; `M3.6-06.1` runs onboarding on a worker task.
//!
//! The API key is passed only to the validator and the secret store. It is
//! never persisted on a provider record, logged, or included in an error.

#![allow(clippy::result_large_err)]

use std::collections::HashMap;
use std::sync::{Arc, Mutex};
use std::time::Duration;

use chatui_contracts::ProviderPreset;
use chatui_domain::{ChatError, ChatErrorCode, Model, Provider, Settings, Timestamp, new_id};
use chatui_providers::{CatalogModel, HttpClient, OpenRouterError, ProviderKeyInfo, list_models};
use chatui_secrets::{SecretStore, SecretValue};
use chatui_storage::{
    ModelRepository, ProviderRepository, SecretRecord, SecretRepository, SettingsRepository,
};
use chatui_ui::onboarding::{
    CatalogSource, KeyValidator, ModelStore as OnboardingModelStore, OnboardingError,
    OnboardingService, ProviderStore as OnboardingProviderStore,
    SettingsStore as OnboardingSettingsStore,
};
use chatui_ui::providers::ProviderKeyValidator as ProviderKeyValidatorTrait;

use crate::services::ServiceRuntime;
use crate::services::providers::{DEFAULT_NETWORK_TIMEOUT, ProviderKeyValidator, chat_error};
use crate::services::sync::SyncBridge;

/// Public OpenRouter catalog source over the shared HTTP client.
pub struct OpenRouterCatalogSource {
    bridge: SyncBridge,
    client: HttpClient,
    base_url: String,
    provider_id: String,
    timeout: Duration,
}

impl OpenRouterCatalogSource {
    /// Create a catalog source over an injected HTTP client.
    pub fn with_client(bridge: SyncBridge, client: HttpClient, preset: &ProviderPreset) -> Self {
        Self {
            bridge,
            client,
            base_url: preset.base_url.clone(),
            provider_id: preset.id.clone(),
            timeout: DEFAULT_NETWORK_TIMEOUT,
        }
    }

    /// Build the catalog source from the shared desktop runtime.
    pub fn from_runtime(
        runtime: &ServiceRuntime,
        preset: &ProviderPreset,
    ) -> Result<Self, ChatError> {
        let client = HttpClient::new().map_err(|error| error.to_chat_error(None))?;
        Ok(Self::with_client(runtime.bridge(), client, preset))
    }

    /// Override the network timeout.
    pub fn with_timeout(mut self, timeout: Duration) -> Self {
        self.timeout = timeout;
        self
    }
}

impl CatalogSource for OpenRouterCatalogSource {
    fn load_catalog(&self) -> Result<Vec<CatalogModel>, ChatError> {
        let result = self.bridge.block_on(async {
            tokio::time::timeout(self.timeout, list_models(&self.client, &self.base_url)).await
        });

        match result {
            Ok(Ok(models)) => Ok(models),
            Ok(Err(error)) => Err(map_openrouter_error(error, &self.provider_id)),
            Err(_) => Err(chat_error(
                ChatErrorCode::Timeout,
                "The model catalog did not load in time. Check your connection and try again.",
                Some(self.provider_id.clone()),
            )),
        }
    }
}

/// Key validator for one onboarding preset.
///
/// The preset is converted into a transient [`Provider`] so the shared
/// [`ProviderKeyValidator`] can select the matching adapter. The key is passed
/// only to the validator and is never stored on the transient provider.
pub struct PresetKeyValidator {
    provider: Provider,
    validator: Arc<dyn ProviderKeyValidatorTrait>,
}

impl PresetKeyValidator {
    /// Create a validator over a transient provider and an injected validator.
    pub fn new(provider: Provider, validator: Arc<dyn ProviderKeyValidatorTrait>) -> Self {
        Self {
            provider,
            validator,
        }
    }

    /// Build the validator from the shared desktop runtime.
    pub fn from_runtime(
        runtime: &ServiceRuntime,
        preset: &ProviderPreset,
    ) -> Result<Self, ChatError> {
        let validator = Arc::new(ProviderKeyValidator::from_runtime(runtime)?);
        Ok(Self::new(provider_from_preset(preset), validator))
    }

    /// Build the validator over an injected HTTP client.
    pub fn with_client(
        bridge: SyncBridge,
        client: HttpClient,
        preset: &ProviderPreset,
    ) -> Result<Self, ChatError> {
        let validator = Arc::new(ProviderKeyValidator::with_client(bridge, client)?);
        Ok(Self::new(provider_from_preset(preset), validator))
    }
}

impl KeyValidator for PresetKeyValidator {
    fn validate_key(&self, key: &SecretValue) -> Result<ProviderKeyInfo, ChatError> {
        self.validator.validate_key(&self.provider, key)
    }
}

/// Synchronous provider store over [`chatui_storage::ProviderRepository`].
///
/// The onboarding service saves the catalog before the provider record, but the
/// `models` table has an immediate foreign key to `providers`. The model store
/// therefore buffers catalog writes and this store flushes them after the
/// provider upsert succeeds.
pub struct ProviderStore {
    bridge: SyncBridge,
    repository: Arc<dyn ProviderRepository>,
    secrets: Arc<dyn SecretRepository>,
    models: Arc<ModelStore>,
}

impl ProviderStore {
    /// Create a provider store over the shared bridge, repositories, and model
    /// store.
    pub fn new(
        bridge: SyncBridge,
        repository: Arc<dyn ProviderRepository>,
        secrets: Arc<dyn SecretRepository>,
        models: Arc<ModelStore>,
    ) -> Self {
        Self {
            bridge,
            repository,
            secrets,
            models,
        }
    }

    /// Build the store from the shared desktop runtime.
    pub fn from_runtime(runtime: &ServiceRuntime, models: Arc<ModelStore>) -> Self {
        Self::new(
            runtime.bridge(),
            runtime.providers(),
            runtime.secrets(),
            models,
        )
    }
}

impl OnboardingProviderStore for ProviderStore {
    fn save_provider(&self, provider: &Provider) -> Result<(), OnboardingError> {
        // The provider row references the secret metadata table. The actual key
        // lives in the injected secret store, so this row is a non-secret
        // placeholder that satisfies the foreign key and lets backup/export
        // report the reference.
        if let Some(secret_id) = provider.secret_id.as_deref() {
            let exists = self
                .bridge
                .block_on(self.secrets.exists(secret_id))
                .map_err(storage_error)?;
            if !exists {
                let now = Timestamp::now();
                self.bridge
                    .block_on(self.secrets.put(&SecretRecord {
                        id: secret_id.to_owned(),
                        ciphertext: Vec::new(),
                        created_at: now,
                        updated_at: now,
                    }))
                    .map_err(storage_error)?;
            }
        }

        self.bridge
            .block_on(self.repository.upsert(provider))
            .map_err(storage_error)?;
        self.models.flush(&provider.id)
    }

    fn delete_provider(&self, id: &str) -> Result<(), OnboardingError> {
        self.models.clear_pending(id);
        self.bridge
            .block_on(self.repository.delete(id))
            .map(|_| ())
            .map_err(storage_error)
    }
}

/// Synchronous model store over [`chatui_storage::ModelRepository`].
///
/// Catalog writes are buffered until [`ProviderStore::save_provider`] flushes
/// them, because the provider row must exist before its models can be inserted.
pub struct ModelStore {
    bridge: SyncBridge,
    repository: Arc<dyn ModelRepository>,
    pending: Mutex<HashMap<String, Vec<Model>>>,
}

impl ModelStore {
    /// Create a model store over the shared bridge and repository.
    pub fn new(bridge: SyncBridge, repository: Arc<dyn ModelRepository>) -> Self {
        Self {
            bridge,
            repository,
            pending: Mutex::new(HashMap::new()),
        }
    }

    /// Build the store from the shared desktop runtime.
    pub fn from_runtime(runtime: &ServiceRuntime) -> Self {
        Self::new(runtime.bridge(), runtime.models())
    }

    fn flush(&self, provider_id: &str) -> Result<(), OnboardingError> {
        let models = self
            .pending
            .lock()
            .expect("pending models lock")
            .remove(provider_id)
            .unwrap_or_default();

        for model in models {
            self.bridge
                .block_on(self.repository.upsert(&model))
                .map_err(storage_error)?;
        }

        Ok(())
    }

    fn clear_pending(&self, provider_id: &str) {
        self.pending
            .lock()
            .expect("pending models lock")
            .remove(provider_id);
    }
}

impl OnboardingModelStore for ModelStore {
    fn cached_models(&self, provider_id: &str) -> Result<Vec<Model>, OnboardingError> {
        self.bridge
            .block_on(self.repository.list_by_provider(provider_id))
            .map_err(storage_error)
    }

    fn save_catalog(
        &self,
        provider_id: &str,
        models: &[CatalogModel],
    ) -> Result<Vec<Model>, OnboardingError> {
        let now = Timestamp::now();
        let mut saved = Vec::with_capacity(models.len());

        for catalog in models {
            let existing = self
                .bridge
                .block_on(
                    self.repository
                        .get_by_remote_id(provider_id, &catalog.remote_id),
                )
                .map_err(storage_error)?;

            let model = match existing {
                Some(existing) => {
                    let mut model = catalog.to_model(
                        existing.id.clone(),
                        provider_id,
                        existing.created_at,
                        now,
                    );
                    model.pinned = existing.pinned;
                    model.hidden = existing.hidden;
                    model
                }
                None => catalog.to_model(new_id(), provider_id, now, now),
            };

            saved.push(model);
        }

        self.pending
            .lock()
            .expect("pending models lock")
            .insert(provider_id.to_owned(), saved.clone());

        Ok(saved)
    }

    fn delete_models(&self, provider_id: &str) -> Result<(), OnboardingError> {
        self.clear_pending(provider_id);
        self.bridge
            .block_on(self.repository.delete_by_provider(provider_id))
            .map(|_| ())
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

impl OnboardingSettingsStore for SettingsStore {
    fn load(&self) -> Result<Settings, OnboardingError> {
        self.bridge
            .block_on(self.repository.load())
            .map_err(storage_error)
    }

    fn save(&self, settings: &Settings) -> Result<(), OnboardingError> {
        self.bridge
            .block_on(self.repository.save(settings))
            .map_err(storage_error)
    }
}

/// Build the production onboarding service for one provider preset.
///
/// The catalog source and key validator share one HTTP client. The selected
/// secret store is injected so the caller can honor the explicit fallback
/// choice from `M3.6-02.1`.
pub fn build_onboarding_service(
    runtime: &ServiceRuntime,
    preset: &ProviderPreset,
    secrets: Arc<dyn SecretStore>,
) -> Result<OnboardingService, ChatError> {
    let client = HttpClient::new().map_err(|error| error.to_chat_error(None))?;
    let catalog = Arc::new(OpenRouterCatalogSource::with_client(
        runtime.bridge(),
        client.clone(),
        preset,
    ));
    let validator = Arc::new(PresetKeyValidator::with_client(
        runtime.bridge(),
        client,
        preset,
    )?);
    let models = Arc::new(ModelStore::from_runtime(runtime));
    let providers = Arc::new(ProviderStore::from_runtime(runtime, models.clone()));
    let settings = Arc::new(SettingsStore::from_runtime(runtime));

    Ok(OnboardingService::new(
        preset.clone(),
        catalog,
        validator,
        providers,
        models,
        settings,
        secrets,
    ))
}

fn provider_from_preset(preset: &ProviderPreset) -> Provider {
    let now = Timestamp::now();
    Provider {
        id: format!("onboarding-{}", preset.id),
        preset_id: preset.id.clone(),
        label: preset.label.clone(),
        kind: preset.kind,
        base_url: preset.base_url.clone(),
        auth_type: preset.auth.auth_type,
        secret_id: None,
        headers: preset.default_headers.clone(),
        enabled: true,
        browser_cors: preset.browser_cors,
        metadata: None,
        created_at: now,
        updated_at: now,
    }
}

fn map_openrouter_error(error: OpenRouterError, provider_id: &str) -> ChatError {
    match error {
        OpenRouterError::Network(error) => error.to_chat_error(Some(provider_id.to_owned())),
        OpenRouterError::Url(error) => chat_error(
            ChatErrorCode::InvalidRequest,
            format!("The provider URL is invalid: {error}"),
            Some(provider_id.to_owned()),
        ),
        OpenRouterError::Parse(message) => {
            chat_error(ChatErrorCode::Parse, message, Some(provider_id.to_owned()))
        }
        OpenRouterError::MissingKey => chat_error(
            ChatErrorCode::Auth,
            "An OpenRouter API key is required.",
            Some(provider_id.to_owned()),
        ),
    }
}

fn storage_error(error: chatui_storage::StorageError) -> OnboardingError {
    OnboardingError::Storage(error.to_string())
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::Mutex;

    use chatui_contracts::provider_catalog;
    use chatui_domain::{InputModality, ModelCapability, OutputModality};
    use chatui_secrets::SessionStore;
    use chatui_ui::onboarding::{ConnectInput, describe_catalog_error, describe_onboarding_error};

    const FAKE_KEY: &str = "sk-test-not-a-real-key";

    struct FakeCatalog {
        result: Mutex<Result<Vec<CatalogModel>, ChatError>>,
    }

    impl CatalogSource for FakeCatalog {
        fn load_catalog(&self) -> Result<Vec<CatalogModel>, ChatError> {
            self.result.lock().expect("catalog lock").clone()
        }
    }

    struct FakeKeyValidator {
        result: Mutex<Result<ProviderKeyInfo, ChatError>>,
    }

    impl KeyValidator for FakeKeyValidator {
        fn validate_key(&self, _key: &SecretValue) -> Result<ProviderKeyInfo, ChatError> {
            self.result.lock().expect("validator lock").clone()
        }
    }

    struct FakeProviderValidator {
        result: Result<ProviderKeyInfo, ChatError>,
        seen_key: Mutex<Option<String>>,
    }

    impl ProviderKeyValidatorTrait for FakeProviderValidator {
        fn validate_key(
            &self,
            _provider: &Provider,
            key: &SecretValue,
        ) -> Result<ProviderKeyInfo, ChatError> {
            *self.seen_key.lock().expect("seen key lock") = Some(key.expose().to_owned());
            self.result.clone()
        }
    }

    fn runtime() -> (tempfile::TempDir, ServiceRuntime) {
        let directory = tempfile::tempdir().expect("create temporary directory");
        let runtime =
            ServiceRuntime::open_at(directory.path().join("chatui.sqlite3"), directory.path())
                .expect("open runtime");
        (directory, runtime)
    }

    fn preset() -> ProviderPreset {
        provider_catalog()
            .get("openrouter")
            .expect("openrouter preset")
            .clone()
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

    fn key_info() -> ProviderKeyInfo {
        ProviderKeyInfo {
            label: Some("Personal".to_owned()),
            usage_usd: Some(1.25),
            limit_usd: Some(10.0),
            limit_remaining_usd: Some(8.75),
            is_free_tier: Some(false),
            rate_limit: None,
        }
    }

    fn chat_error(code: ChatErrorCode, message: &str) -> ChatError {
        ChatError {
            code,
            message: message.to_owned(),
            provider_id: Some("openrouter".to_owned()),
            status: None,
            retryable: false,
            retry_after_seconds: None,
            request_id: None,
            details: None,
            at: Timestamp::now(),
        }
    }

    fn input(key: &str) -> ConnectInput {
        ConnectInput {
            api_key: SecretValue::new(key),
            label: String::new(),
            remember_key: true,
        }
    }

    fn service(
        runtime: &ServiceRuntime,
        catalog: Result<Vec<CatalogModel>, ChatError>,
        validation: Result<ProviderKeyInfo, ChatError>,
    ) -> (OnboardingService, Arc<SessionStore>) {
        let secrets = Arc::new(SessionStore::new());
        let models = Arc::new(ModelStore::from_runtime(runtime));
        let providers = Arc::new(ProviderStore::from_runtime(runtime, models.clone()));
        let service = OnboardingService::new(
            preset(),
            Arc::new(FakeCatalog {
                result: Mutex::new(catalog),
            }),
            Arc::new(FakeKeyValidator {
                result: Mutex::new(validation),
            }),
            providers,
            models,
            Arc::new(SettingsStore::from_runtime(runtime)),
            secrets.clone(),
        );
        (service, secrets)
    }

    #[test]
    fn catalog_failure_is_user_safe_and_rolls_back() {
        let (_directory, runtime) = runtime();
        let (service, secrets) = service(
            &runtime,
            Err(chat_error(ChatErrorCode::Network, "offline")),
            Ok(key_info()),
        );

        let error = service
            .connect(input(FAKE_KEY))
            .expect_err("catalog failure");

        assert_eq!(error.chat_code(), Some(ChatErrorCode::Network));
        let copy = describe_onboarding_error(&error, Some(FAKE_KEY));
        assert!(copy.message.contains("connection") || copy.message.contains("OpenRouter"));
        assert!(!copy.details.unwrap_or_default().contains(FAKE_KEY));
        assert!(secrets.is_empty());
        assert!(
            runtime
                .block_on(runtime.providers().list())
                .expect("list providers")
                .is_empty()
        );
        assert!(
            !runtime
                .block_on(runtime.settings().load())
                .expect("load settings")
                .onboarding_completed
        );
    }

    #[test]
    fn validation_failure_is_user_safe_and_rolls_back() {
        let (_directory, runtime) = runtime();
        let (service, secrets) = service(
            &runtime,
            Ok(vec![catalog_model("openrouter/auto")]),
            Err(chat_error(
                ChatErrorCode::Auth,
                &format!("rejected {FAKE_KEY}"),
            )),
        );

        let error = service
            .connect(input(FAKE_KEY))
            .expect_err("validation failure");

        assert_eq!(error.chat_code(), Some(ChatErrorCode::Auth));
        let copy = describe_onboarding_error(&error, Some(FAKE_KEY));
        assert_eq!(copy.title, "API key rejected");
        assert!(!copy.details.unwrap_or_default().contains(FAKE_KEY));
        assert!(secrets.is_empty());
        assert!(
            runtime
                .block_on(runtime.providers().list())
                .expect("list providers")
                .is_empty()
        );
        assert!(
            !runtime
                .block_on(runtime.settings().load())
                .expect("load settings")
                .onboarding_completed
        );
    }

    #[test]
    fn provider_and_models_persist_across_reopen() {
        let directory = tempfile::tempdir().expect("create temporary directory");
        let database = directory.path().join("chatui.sqlite3");

        let result = {
            let runtime =
                ServiceRuntime::open_at(&database, directory.path()).expect("open runtime");
            let (service, _secrets) = service(
                &runtime,
                Ok(vec![
                    catalog_model("openrouter/auto"),
                    catalog_model("openrouter/free"),
                ]),
                Ok(key_info()),
            );
            service.connect(input(FAKE_KEY)).expect("connect")
        };

        let runtime = ServiceRuntime::open_at(&database, directory.path()).expect("reopen runtime");
        let providers = runtime
            .block_on(runtime.providers().list())
            .expect("list providers");
        assert_eq!(providers.len(), 1);
        assert_eq!(providers[0].id, result.provider_id);
        assert_eq!(providers[0].preset_id, "openrouter");

        let models = runtime
            .block_on(runtime.models().list_by_provider(&result.provider_id))
            .expect("list models");
        assert_eq!(models.len(), 2);
        assert!(
            models
                .iter()
                .any(|model| model.remote_id == result.model_remote_id)
        );

        let settings = runtime
            .block_on(runtime.settings().load())
            .expect("load settings");
        assert!(settings.onboarding_completed);
        assert_eq!(
            settings.default_provider_id.as_deref(),
            Some(result.provider_id.as_str())
        );
        assert_eq!(
            settings.default_model_id.as_deref(),
            Some(result.model_id.as_str())
        );
    }

    #[test]
    fn completion_is_reported_after_reopen() {
        let directory = tempfile::tempdir().expect("create temporary directory");
        let database = directory.path().join("chatui.sqlite3");

        {
            let runtime =
                ServiceRuntime::open_at(&database, directory.path()).expect("open runtime");
            let (service, _secrets) = service(
                &runtime,
                Ok(vec![catalog_model("openrouter/auto")]),
                Ok(key_info()),
            );
            service.connect(input(FAKE_KEY)).expect("connect");
        }

        let runtime = ServiceRuntime::open_at(&database, directory.path()).expect("reopen runtime");
        let (service, _secrets) = service(&runtime, Ok(Vec::new()), Ok(key_info()));
        assert!(service.is_onboarding_complete().expect("completion"));
    }

    #[test]
    fn already_completed_onboarding_is_detected() {
        let (_directory, runtime) = runtime();
        let mut settings = runtime
            .block_on(runtime.settings().load())
            .expect("load settings");
        settings.onboarding_completed = true;
        runtime
            .block_on(runtime.settings().save(&settings))
            .expect("save settings");

        let (service, _secrets) = service(&runtime, Ok(Vec::new()), Ok(key_info()));
        assert!(service.is_onboarding_complete().expect("completion"));
    }

    #[test]
    fn model_upsert_preserves_existing_identity_and_flags() {
        let (_directory, runtime) = runtime();
        let mut provider = provider_from_preset(&preset());
        provider.id = "provider-1".to_owned();
        runtime
            .block_on(runtime.providers().upsert(&provider))
            .expect("seed provider");
        let store = ModelStore::from_runtime(&runtime);

        let first = store
            .save_catalog("provider-1", &[catalog_model("author/model")])
            .expect("first save");
        store.flush("provider-1").expect("flush first save");
        let mut existing = first[0].clone();
        existing.pinned = true;
        existing.hidden = true;
        runtime
            .block_on(runtime.models().upsert(&existing))
            .expect("pin and hide");

        let second = store
            .save_catalog("provider-1", &[catalog_model("author/model")])
            .expect("second save");
        assert_eq!(second[0].id, first[0].id);
        assert!(second[0].pinned);
        assert!(second[0].hidden);
    }

    #[test]
    fn catalog_error_mapping_is_user_safe() {
        let error = map_openrouter_error(
            OpenRouterError::Parse(format!("bad payload for {FAKE_KEY}")),
            "openrouter",
        );
        assert_eq!(error.code, ChatErrorCode::Parse);

        let copy = describe_onboarding_error(&OnboardingError::Chat(error), Some(FAKE_KEY));
        let details = copy.details.unwrap_or_default();
        assert!(!details.contains(FAKE_KEY));
        assert!(details.contains("[redacted]"));

        let catalog_copy = describe_catalog_error(&chat_error(ChatErrorCode::Network, "offline"));
        assert!(catalog_copy.contains("Could not load the model catalog"));
    }

    #[test]
    fn production_catalog_source_rejects_cleartext_without_network() {
        let (_directory, runtime) = runtime();
        let mut preset = preset();
        preset.base_url = "http://api.example.com/v1".to_owned();
        let source =
            OpenRouterCatalogSource::from_runtime(&runtime, &preset).expect("build source");

        let error = source.load_catalog().expect_err("cleartext rejected");
        assert_eq!(error.code, ChatErrorCode::InvalidRequest);
        assert!(!error.message.contains(FAKE_KEY));
    }

    #[test]
    fn production_key_validator_rejects_cleartext_without_network() {
        let (_directory, runtime) = runtime();
        let mut preset = preset();
        preset.base_url = "http://api.example.com/v1".to_owned();
        let validator =
            PresetKeyValidator::from_runtime(&runtime, &preset).expect("build validator");

        let error = validator
            .validate_key(&SecretValue::new(FAKE_KEY))
            .expect_err("cleartext rejected");
        assert_eq!(error.code, ChatErrorCode::InvalidRequest);
        assert!(!error.message.contains(FAKE_KEY));
        assert!(!format!("{error:?}").contains(FAKE_KEY));
    }

    #[test]
    fn preset_key_validator_delegates_and_returns_metadata() {
        let (_directory, _runtime) = runtime();
        let inner = Arc::new(FakeProviderValidator {
            result: Ok(key_info()),
            seen_key: Mutex::new(None),
        });
        let validator = PresetKeyValidator::new(provider_from_preset(&preset()), inner.clone());

        let info = validator
            .validate_key(&SecretValue::new(FAKE_KEY))
            .expect("validate");
        assert_eq!(info, key_info());
        assert_eq!(
            inner.seen_key.lock().expect("seen key lock").as_deref(),
            Some(FAKE_KEY)
        );
    }

    #[test]
    fn build_onboarding_service_wires_adapters() {
        let (_directory, runtime) = runtime();
        let service = build_onboarding_service(&runtime, &preset(), Arc::new(SessionStore::new()))
            .expect("build service");

        assert_eq!(service.preset().id, "openrouter");
    }
}
