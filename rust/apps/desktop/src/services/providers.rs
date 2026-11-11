//! Provider management adapters over the shared SQLite runtime and provider
//! crate.
//!
//! The store adapters are thin synchronous wrappers over the async storage
//! repositories. [`ProviderKeyValidator`] maps a provider preset to the matching
//! `chatui-providers` adapter and calls its key-validation endpoint. The key is
//! passed only to the validator and is never persisted or logged here.
//!
//! Network boundary: [`ProviderKeyValidator::validate_key`] blocks the calling
//! thread on the shared runtime for at most [`DEFAULT_NETWORK_TIMEOUT`]. Callers
//! must not invoke it on the Slint thread; `M3.6-06.1` runs it on a worker task.

#![allow(clippy::result_large_err)]

use std::sync::Arc;
use std::time::Duration;

use chatui_domain::{
    ChatError, ChatErrorCode, Conversation, Model, Provider, ProviderKind, Settings, Timestamp,
};
use chatui_providers::{
    CancellationToken, HttpClient, NetworkError, NetworkErrorKind, ProviderAdapter,
    ProviderContext, ProviderKeyInfo, ProviderRegistry,
};
use chatui_secrets::{SecretId, SecretStore, SecretValue};
use chatui_storage::{
    ConversationRepository, ModelRepository, ProviderRepository, SettingsRepository,
};
use chatui_ui::providers::{
    ConversationStore as ConversationStoreTrait, ModelStore as ModelStoreTrait,
    ProviderKeyValidator as ProviderKeyValidatorTrait, ProviderManagementError,
    ProviderStore as ProviderStoreTrait, SettingsStore as SettingsStoreTrait,
};

use crate::services::ServiceRuntime;
use crate::services::sync::SyncBridge;

/// Maximum time a synchronous network adapter waits for a provider response.
pub const DEFAULT_NETWORK_TIMEOUT: Duration = Duration::from_secs(30);

/// Synchronous provider store over [`chatui_storage::ProviderRepository`].
pub struct ProviderStore {
    bridge: SyncBridge,
    repository: Arc<dyn ProviderRepository>,
}

impl ProviderStore {
    /// Create a provider store over the shared bridge and repository.
    pub fn new(bridge: SyncBridge, repository: Arc<dyn ProviderRepository>) -> Self {
        Self { bridge, repository }
    }

    /// Build the store from the shared desktop runtime.
    pub fn from_runtime(runtime: &ServiceRuntime) -> Self {
        Self::new(runtime.bridge(), runtime.providers())
    }
}

impl ProviderStoreTrait for ProviderStore {
    fn list(&self) -> Result<Vec<Provider>, ProviderManagementError> {
        self.bridge
            .block_on(self.repository.list())
            .map_err(storage_error)
    }

    fn get(&self, id: &str) -> Result<Option<Provider>, ProviderManagementError> {
        self.bridge
            .block_on(self.repository.get(id))
            .map_err(storage_error)
    }

    fn save_provider(&self, provider: &Provider) -> Result<(), ProviderManagementError> {
        self.bridge
            .block_on(self.repository.upsert(provider))
            .map_err(storage_error)
    }

    fn delete(&self, id: &str) -> Result<(), ProviderManagementError> {
        self.bridge
            .block_on(self.repository.delete(id))
            .map(|_| ())
            .map_err(storage_error)
    }
}

/// Synchronous model store over [`chatui_storage::ModelRepository`].
pub struct ModelStore {
    bridge: SyncBridge,
    repository: Arc<dyn ModelRepository>,
}

impl ModelStore {
    /// Create a model store over the shared bridge and repository.
    pub fn new(bridge: SyncBridge, repository: Arc<dyn ModelRepository>) -> Self {
        Self { bridge, repository }
    }

    /// Build the store from the shared desktop runtime.
    pub fn from_runtime(runtime: &ServiceRuntime) -> Self {
        Self::new(runtime.bridge(), runtime.models())
    }
}

impl ModelStoreTrait for ModelStore {
    fn list_by_provider(&self, provider_id: &str) -> Result<Vec<Model>, ProviderManagementError> {
        self.bridge
            .block_on(self.repository.list_by_provider(provider_id))
            .map_err(storage_error)
    }

    fn delete_by_provider(&self, provider_id: &str) -> Result<(), ProviderManagementError> {
        self.bridge
            .block_on(self.repository.delete_by_provider(provider_id))
            .map(|_| ())
            .map_err(storage_error)
    }
}

/// Synchronous conversation store over
/// [`chatui_storage::ConversationRepository`].
pub struct ConversationStore {
    bridge: SyncBridge,
    repository: Arc<dyn ConversationRepository>,
}

impl ConversationStore {
    /// Create a conversation store over the shared bridge and repository.
    pub fn new(bridge: SyncBridge, repository: Arc<dyn ConversationRepository>) -> Self {
        Self { bridge, repository }
    }

    /// Build the store from the shared desktop runtime.
    pub fn from_runtime(runtime: &ServiceRuntime) -> Self {
        Self::new(runtime.bridge(), runtime.conversations())
    }
}

impl ConversationStoreTrait for ConversationStore {
    fn list_all(&self) -> Result<Vec<Conversation>, ProviderManagementError> {
        self.bridge
            .block_on(self.repository.list_all())
            .map_err(storage_error)
    }

    fn update(&self, conversation: &Conversation) -> Result<(), ProviderManagementError> {
        self.bridge
            .block_on(self.repository.update(conversation))
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
    fn load(&self) -> Result<Settings, ProviderManagementError> {
        self.bridge
            .block_on(self.repository.load())
            .map_err(storage_error)
    }

    fn save(&self, settings: &Settings) -> Result<(), ProviderManagementError> {
        self.bridge
            .block_on(self.repository.save(settings))
            .map_err(storage_error)
    }
}

/// Key validator backed by the `chatui-providers` per-preset adapters.
///
/// The adapter is selected from the provider preset, falling back to the
/// provider kind when the preset is unknown. The key is placed only in the
/// [`ProviderContext`] passed to the adapter; it is never stored on the provider
/// record or included in an error.
pub struct ProviderKeyValidator {
    bridge: SyncBridge,
    client: HttpClient,
    registry: ProviderRegistry,
    timeout: Duration,
}

impl ProviderKeyValidator {
    /// Build a validator over the shared runtime and a fresh HTTP client.
    pub fn new(bridge: SyncBridge) -> Result<Self, ChatError> {
        let client = HttpClient::new().map_err(|error| network_error(error, None))?;
        Self::with_client(bridge, client)
    }

    /// Build the validator from the shared desktop runtime.
    pub fn from_runtime(runtime: &ServiceRuntime) -> Result<Self, ChatError> {
        Self::new(runtime.bridge())
    }

    /// Build a validator over an injected HTTP client.
    pub fn with_client(bridge: SyncBridge, client: HttpClient) -> Result<Self, ChatError> {
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
            timeout: DEFAULT_NETWORK_TIMEOUT,
        })
    }

    /// Override the network timeout.
    pub fn with_timeout(mut self, timeout: Duration) -> Self {
        self.timeout = timeout;
        self
    }
}

impl ProviderKeyValidatorTrait for ProviderKeyValidator {
    fn validate_key(
        &self,
        provider: &Provider,
        key: &SecretValue,
    ) -> Result<ProviderKeyInfo, ChatError> {
        let context = provider_context(&self.registry, provider, Some(key.expose().to_owned()))?;
        let adapter = adapter_for_provider(&self.registry, provider)?;
        let cancel = CancellationToken::new();

        let result = self.bridge.block_on(async {
            tokio::time::timeout(
                self.timeout,
                adapter.validate_key(&self.client, &context, &cancel),
            )
            .await
        });

        match result {
            Ok(result) => result,
            Err(_) => Err(timeout_error(provider)),
        }
    }
}

/// Build a provider context from a domain provider and an optional key.
///
/// The preset supplies the adapter behavior and non-secret default headers. The
/// provider supplies the resolved base URL, auth style, and custom headers.
pub(crate) fn provider_context(
    registry: &ProviderRegistry,
    provider: &Provider,
    api_key: Option<String>,
) -> Result<ProviderContext, ChatError> {
    let preset_id = preset_id_for_provider(registry, provider).ok_or_else(|| {
        chat_error(
            ChatErrorCode::Unknown,
            format!("Provider preset {} is not available.", provider.preset_id),
            Some(provider.id.clone()),
        )
    })?;
    let preset = registry.get(preset_id).ok_or_else(|| {
        chat_error(
            ChatErrorCode::Unknown,
            format!("Provider preset {preset_id} is not available."),
            Some(provider.id.clone()),
        )
    })?;

    let mut auth = preset.auth.clone();
    auth.auth_type = provider.auth_type;

    let mut headers = preset.default_headers.clone();
    headers.extend(provider.headers.clone());

    Ok(ProviderContext {
        preset: preset.clone(),
        base_url: provider.base_url.clone(),
        auth,
        headers,
        api_key,
    })
}

/// Select the provider adapter for a domain provider.
pub(crate) fn adapter_for_provider(
    registry: &ProviderRegistry,
    provider: &Provider,
) -> Result<ProviderAdapter, ChatError> {
    let preset_id = preset_id_for_provider(registry, provider).ok_or_else(|| {
        chat_error(
            ChatErrorCode::Unknown,
            format!("Provider preset {} is not available.", provider.preset_id),
            Some(provider.id.clone()),
        )
    })?;

    registry.adapter(preset_id).cloned().ok_or_else(|| {
        chat_error(
            ChatErrorCode::Unknown,
            format!("Provider preset {preset_id} has no adapter."),
            Some(provider.id.clone()),
        )
    })
}

fn preset_id_for_provider<'a>(
    registry: &'a ProviderRegistry,
    provider: &Provider,
) -> Option<&'a str> {
    registry
        .get(&provider.preset_id)
        .map(|preset| preset.id.as_str())
        .or_else(|| {
            let fallback = match provider.kind {
                ProviderKind::OpenaiCompatible => "custom",
                ProviderKind::Anthropic => "anthropic",
                ProviderKind::GoogleGemini => "google",
            };
            registry.get(fallback).map(|preset| preset.id.as_str())
        })
}

/// Build a normalized chat error.
pub(crate) fn chat_error(
    code: ChatErrorCode,
    message: impl Into<String>,
    provider_id: Option<String>,
) -> ChatError {
    ChatError {
        code,
        message: message.into(),
        provider_id,
        status: None,
        retryable: false,
        retry_after_seconds: None,
        request_id: None,
        details: None,
        at: Timestamp::now(),
    }
}

/// Build the timeout error returned when a network adapter exceeds its budget.
pub(crate) fn timeout_error(provider: &Provider) -> ChatError {
    chat_error(
        ChatErrorCode::Timeout,
        "The provider did not respond in time. Check your connection and try again.",
        Some(provider.id.clone()),
    )
}

/// Map a transport error to a normalized chat error.
pub(crate) fn network_error(error: NetworkError, provider_id: Option<String>) -> ChatError {
    let code = match error.kind {
        NetworkErrorKind::Timeout => ChatErrorCode::Timeout,
        NetworkErrorKind::Cancelled => ChatErrorCode::Cancelled,
        NetworkErrorKind::InvalidUrl | NetworkErrorKind::InvalidHeader => {
            ChatErrorCode::InvalidRequest
        }
        _ => ChatErrorCode::Network,
    };

    ChatError {
        code,
        message: error.message,
        provider_id,
        status: error.status,
        retryable: error.retryable,
        retry_after_seconds: error.retry_after_seconds,
        request_id: error.request_id,
        details: None,
        at: Timestamp::now(),
    }
}

fn storage_error(error: chatui_storage::StorageError) -> ProviderManagementError {
    ProviderManagementError::Storage(error.to_string())
}

/// Resolve a provider's stored key from the secret store.
pub(crate) fn resolve_api_key(
    secrets: &dyn SecretStore,
    provider: &Provider,
) -> Result<Option<String>, ChatError> {
    let Some(secret_id) = provider.secret_id.as_deref() else {
        return Ok(None);
    };

    let id = SecretId::new(secret_id).map_err(|error| {
        chat_error(
            ChatErrorCode::Storage,
            error.to_string(),
            Some(provider.id.clone()),
        )
    })?;

    let value = secrets.get(&id).map_err(|error| {
        chat_error(
            ChatErrorCode::Storage,
            error.to_string(),
            Some(provider.id.clone()),
        )
    })?;

    Ok(value.map(|value| value.expose().to_owned()))
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::BTreeMap;

    use chatui_domain::{
        BrowserCors, ChatFontSize, ChatParams, Density, ModelCapability, ModelSource,
        ProviderAuthType, SendKey, Theme,
    };
    use chatui_secrets::SessionStore;
    use chatui_ui::providers::ProviderManagementService;

    const FAKE_KEY: &str = "sk-test-not-a-real-key";

    fn runtime() -> (tempfile::TempDir, ServiceRuntime) {
        let directory = tempfile::tempdir().expect("create temporary directory");
        let runtime =
            ServiceRuntime::open_at(directory.path().join("chatui.sqlite3"), directory.path())
                .expect("open runtime");
        (directory, runtime)
    }

    fn provider(id: &str, secret_id: Option<&str>) -> Provider {
        let now = Timestamp::parse("2026-11-04T12:00:00.000Z").expect("valid timestamp");
        Provider {
            id: id.to_owned(),
            preset_id: "custom".to_owned(),
            label: format!("Provider {id}"),
            kind: ProviderKind::OpenaiCompatible,
            base_url: "https://example.com/v1".to_owned(),
            auth_type: ProviderAuthType::Bearer,
            secret_id: secret_id.map(str::to_owned),
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

    fn conversation(id: &str, provider_id: &str, model_id: &str) -> Conversation {
        let now = Timestamp::now();
        Conversation {
            id: id.to_owned(),
            title: "Chat".to_owned(),
            system_prompt: String::new(),
            provider_id: provider_id.to_owned(),
            model_id: model_id.to_owned(),
            model_label: "Model".to_owned(),
            params: ChatParams::default(),
            pinned: false,
            archived: false,
            temporary: false,
            created_at: now,
            updated_at: now,
            last_message_at: now,
            message_count: 0,
            last_error: None,
            sync: None,
            storage_key: String::new(),
            revision: 0,
        }
    }

    fn key_info() -> ProviderKeyInfo {
        ProviderKeyInfo {
            label: Some("Work key".to_owned()),
            usage_usd: Some(1.25),
            limit_usd: Some(10.0),
            limit_remaining_usd: Some(8.75),
            is_free_tier: Some(false),
            rate_limit: None,
        }
    }

    struct FakeValidator {
        result: Result<ProviderKeyInfo, ChatError>,
    }

    impl ProviderKeyValidatorTrait for FakeValidator {
        fn validate_key(
            &self,
            _provider: &Provider,
            _key: &SecretValue,
        ) -> Result<ProviderKeyInfo, ChatError> {
            self.result.clone()
        }
    }

    fn service(
        runtime: &ServiceRuntime,
        secrets: Arc<dyn SecretStore>,
        validator: Arc<dyn ProviderKeyValidatorTrait>,
    ) -> ProviderManagementService {
        ProviderManagementService::new(
            Arc::new(ProviderStore::from_runtime(runtime)),
            Arc::new(ModelStore::from_runtime(runtime)),
            Arc::new(ConversationStore::from_runtime(runtime)),
            Arc::new(SettingsStore::from_runtime(runtime)),
            secrets,
            validator,
        )
    }

    #[test]
    fn provider_crud_round_trip() {
        let (_directory, runtime) = runtime();
        let store = ProviderStore::from_runtime(&runtime);
        let original = provider("p1", None);

        store.save_provider(&original).expect("save provider");
        assert_eq!(
            store.get("p1").expect("get provider"),
            Some(original.clone())
        );
        assert_eq!(store.list().expect("list providers").len(), 1);

        let mut updated = original.clone();
        updated.label = "Renamed".to_owned();
        store.save_provider(&updated).expect("update provider");
        assert_eq!(
            store.get("p1").expect("get provider").unwrap().label,
            "Renamed"
        );

        store.delete("p1").expect("delete provider");
        assert!(store.get("p1").expect("get provider").is_none());
    }

    #[test]
    fn delete_cascade_removes_models_and_clears_references() {
        let (_directory, runtime) = runtime();
        let secrets = Arc::new(SessionStore::new());
        let validator = Arc::new(FakeValidator {
            result: Ok(key_info()),
        });
        let service = service(&runtime, secrets, validator);

        let providers = runtime.providers();
        runtime
            .block_on(providers.upsert(&provider("p1", None)))
            .expect("seed provider");
        runtime
            .block_on(providers.upsert(&provider("p2", None)))
            .expect("seed unrelated provider");

        let models = runtime.models();
        runtime
            .block_on(models.insert(&model("m1", "p1")))
            .expect("seed model");
        runtime
            .block_on(models.insert(&model("m2", "p2")))
            .expect("seed unrelated model");

        let conversations = runtime.conversations();
        runtime
            .block_on(conversations.create(&conversation("c1", "p1", "m1")))
            .expect("seed conversation");

        let settings_repository = runtime.settings();
        let mut settings = runtime
            .block_on(settings_repository.load())
            .expect("load settings");
        settings.default_provider_id = Some("p1".to_owned());
        settings.default_model_id = Some("m1".to_owned());
        runtime
            .block_on(settings_repository.save(&settings))
            .expect("save settings");

        service
            .delete_provider("p1", true)
            .expect("delete provider");

        assert!(
            runtime
                .block_on(providers.get("p1"))
                .expect("get provider")
                .is_none()
        );
        assert!(
            runtime
                .block_on(models.list_by_provider("p1"))
                .expect("list models")
                .is_empty()
        );
        assert!(
            runtime
                .block_on(providers.get("p2"))
                .expect("get unrelated provider")
                .is_some()
        );
        assert_eq!(
            runtime
                .block_on(models.list_by_provider("p2"))
                .expect("list unrelated models")
                .len(),
            1
        );

        let conversation = runtime
            .block_on(conversations.get("c1"))
            .expect("get conversation")
            .expect("conversation exists");
        assert!(conversation.provider_id.is_empty());
        assert!(conversation.model_id.is_empty());
        assert_eq!(conversation.model_label, "Model");

        let settings = runtime
            .block_on(settings_repository.load())
            .expect("reload settings");
        assert_eq!(settings.default_provider_id, None);
        assert_eq!(settings.default_model_id, None);
    }

    #[test]
    fn key_presence_is_reported_without_disclosing_the_key() {
        let (_directory, runtime) = runtime();
        let secrets = Arc::new(SessionStore::new());
        let secret_id = SecretId::new("provider-p1").expect("valid secret id");
        secrets
            .set(&secret_id, &SecretValue::new(FAKE_KEY))
            .expect("store key");
        let validator = Arc::new(FakeValidator {
            result: Ok(key_info()),
        });
        let service = service(&runtime, secrets, validator);

        let secret_metadata = runtime.secrets();
        runtime
            .block_on(secret_metadata.put(&chatui_storage::SecretRecord {
                id: "provider-p1".to_owned(),
                ciphertext: Vec::new(),
                created_at: Timestamp::now(),
                updated_at: Timestamp::now(),
            }))
            .expect("seed secret metadata");

        let providers = runtime.providers();
        runtime
            .block_on(providers.upsert(&provider("p1", Some("provider-p1"))))
            .expect("seed provider");

        let list = service.list_providers().expect("list providers");
        assert!(list.entries()[0].key_present);
        assert!(!format!("{list:?}").contains(FAKE_KEY));

        let detail = service.provider_detail("p1").expect("provider detail");
        assert!(detail.key_present);
        assert!(!format!("{detail:?}").contains(FAKE_KEY));
    }

    #[test]
    fn validator_maps_errors_without_exposing_the_key() {
        let (_directory, runtime) = runtime();
        let validator = ProviderKeyValidator::from_runtime(&runtime).expect("build validator");

        let mut provider = provider("p1", None);
        provider.preset_id = "openrouter".to_owned();
        provider.base_url = "http://api.example.com/v1".to_owned();

        let error = validator
            .validate_key(&provider, &SecretValue::new(FAKE_KEY))
            .expect_err("public cleartext is rejected before any request");

        assert_eq!(error.code, ChatErrorCode::InvalidRequest);
        assert!(!error.message.contains(FAKE_KEY));
        assert!(!format!("{error:?}").contains(FAKE_KEY));
    }

    #[test]
    fn settings_store_round_trips_through_sqlite() {
        let (_directory, runtime) = runtime();
        let store = SettingsStore::from_runtime(&runtime);

        let mut settings = store.load().expect("load settings");
        settings.theme = Theme::Dark;
        settings.density = Density::Compact;
        settings.chat_font_size = ChatFontSize::Lg;
        settings.send_key = SendKey::ModEnter;
        store.save(&settings).expect("save settings");

        let loaded = store.load().expect("reload settings");
        assert_eq!(loaded.theme, Theme::Dark);
        assert_eq!(loaded.density, Density::Compact);
        assert_eq!(loaded.chat_font_size, ChatFontSize::Lg);
        assert_eq!(loaded.send_key, SendKey::ModEnter);
    }
}
