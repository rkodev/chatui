//! Model management orchestration.
//!
//! The service is injected with small traits for model and provider
//! persistence, model discovery, and settings. It never talks to SQLite or the
//! network directly, so the view model and tests can use fakes.
//!
//! Discovery rules:
//!
//! * A successful refresh upserts models by `(provider_id, remote_id)`.
//! * Existing rows keep their local ID, `pinned`, `hidden`, and `source` flags.
//! * Manual rows keep their user-provided label and metadata.
//! * Models that disappear from the catalog are not deleted, so cached and
//!   manual entries remain selectable.
//! * A failed refresh keeps cached models. When the cache is empty, the preset
//!   fallback list is persisted so the picker still works.
//! * Provider model IDs are never rewritten.

#![allow(clippy::result_large_err)]

use std::collections::{BTreeMap, BTreeSet};
use std::sync::Arc;

use chatui_contracts::provider_catalog;
use chatui_domain::{
    ChatError, InputModality, Model, ModelCapability, ModelSource, OutputModality, Provider,
    Settings, Timestamp, new_id,
};
use chatui_providers::{
    CatalogModel, defaults_for_preset, fallback_model_ids_for, map_openai_model,
};

use crate::models::error::ModelManagementError;
use crate::models::state::{
    ManualModelForm, ModelListViewModel, ModelPickerViewModel, build_model_groups,
    model_availability,
};

/// Model record persistence.
pub trait ModelRepository: Send + Sync {
    /// List every model.
    fn list_all(&self) -> Result<Vec<Model>, ModelManagementError>;
    /// List models for one provider.
    fn list_by_provider(&self, provider_id: &str) -> Result<Vec<Model>, ModelManagementError>;
    /// Fetch one model.
    fn get(&self, id: &str) -> Result<Option<Model>, ModelManagementError>;
    /// Insert a model.
    fn insert(&self, model: &Model) -> Result<(), ModelManagementError>;
    /// Update a model.
    fn update(&self, model: &Model) -> Result<(), ModelManagementError>;
    /// Set the pinned flag.
    fn set_pinned(&self, id: &str, pinned: bool) -> Result<(), ModelManagementError>;
    /// Set the hidden flag.
    fn set_hidden(&self, id: &str, hidden: bool) -> Result<(), ModelManagementError>;
}

/// Provider record persistence.
pub trait ProviderRepository: Send + Sync {
    /// List every provider.
    fn list(&self) -> Result<Vec<Provider>, ModelManagementError>;
    /// Fetch one provider.
    fn get(&self, id: &str) -> Result<Option<Provider>, ModelManagementError>;
}

/// Model discovery source.
///
/// Implementations call the provider's model-list endpoint and return the
/// normalized catalog. They never send a paid completion request.
pub trait ModelDiscovery: Send + Sync {
    /// Discover the catalog for one provider.
    fn discover(&self, provider: &Provider) -> Result<Vec<CatalogModel>, ChatError>;
}

/// Settings persistence.
pub trait SettingsStore: Send + Sync {
    /// Load the complete settings object.
    fn load(&self) -> Result<Settings, ModelManagementError>;
    /// Persist the complete settings object.
    fn save(&self, settings: &Settings) -> Result<(), ModelManagementError>;
}

/// Result of a discovery refresh.
#[derive(Debug, Clone, PartialEq)]
pub struct RefreshResult {
    /// Models after the refresh.
    pub models: Vec<Model>,
    /// Number of newly inserted models.
    pub created: usize,
    /// Number of updated models.
    pub updated: usize,
    /// Whether discovery failed and cached or fallback models were used.
    pub degraded: bool,
    /// Non-blocking notice.
    pub notice: Option<String>,
}

struct UpsertResult {
    models: Vec<Model>,
    created: usize,
    updated: usize,
}

/// Model management service over injected traits.
pub struct ModelManagementService {
    models: Arc<dyn ModelRepository>,
    providers: Arc<dyn ProviderRepository>,
    discovery: Arc<dyn ModelDiscovery>,
    settings: Arc<dyn SettingsStore>,
}

impl ModelManagementService {
    /// Build a service over the injected dependencies.
    pub fn new(
        models: Arc<dyn ModelRepository>,
        providers: Arc<dyn ProviderRepository>,
        discovery: Arc<dyn ModelDiscovery>,
        settings: Arc<dyn SettingsStore>,
    ) -> Self {
        Self {
            models,
            providers,
            discovery,
            settings,
        }
    }

    /// Build the management list view model.
    ///
    /// The management screen includes hidden models so they can be unhidden.
    pub fn list_models(
        &self,
        provider_id: Option<&str>,
    ) -> Result<ModelListViewModel, ModelManagementError> {
        let providers = self.providers.list()?;
        let models = match provider_id {
            Some(id) => self.models.list_by_provider(id)?,
            None => self.models.list_all()?,
        };
        let default_model_id = self.settings.load()?.default_model_id;
        let groups = build_model_groups(&models, &providers, "", true, default_model_id.as_deref());

        Ok(ModelListViewModel {
            groups,
            total: models.len(),
            pinned_count: models.iter().filter(|model| model.pinned).count(),
            hidden_count: models.iter().filter(|model| model.hidden).count(),
            loading: false,
            notice: None,
            error: None,
        })
    }

    /// Refresh the provider catalog.
    ///
    /// On failure, cached models are kept. When the cache is empty, the preset
    /// fallback list is persisted. The result carries a non-blocking notice;
    /// callers never need to treat this as a send failure.
    pub fn refresh(&self, provider_id: &str) -> Result<RefreshResult, ModelManagementError> {
        let provider = self.require_provider(provider_id)?;

        match self.discovery.discover(&provider) {
            Ok(catalog) => {
                let result = self.upsert_catalog(provider_id, &catalog, ModelSource::Discovered)?;
                Ok(RefreshResult {
                    models: result.models,
                    created: result.created,
                    updated: result.updated,
                    degraded: false,
                    notice: None,
                })
            }
            Err(error) => self.degraded_refresh(&provider, error),
        }
    }

    /// Set the pinned flag and return the updated model.
    pub fn set_pinned(&self, model_id: &str, pinned: bool) -> Result<Model, ModelManagementError> {
        let mut model = self.require_model(model_id)?;
        self.models.set_pinned(model_id, pinned)?;
        model.pinned = pinned;
        model.updated_at = Timestamp::now();
        Ok(model)
    }

    /// Set the hidden flag and return the updated model.
    pub fn set_hidden(&self, model_id: &str, hidden: bool) -> Result<Model, ModelManagementError> {
        let mut model = self.require_model(model_id)?;
        self.models.set_hidden(model_id, hidden)?;
        model.hidden = hidden;
        model.updated_at = Timestamp::now();
        Ok(model)
    }

    /// Add a user-defined model that is not present in the catalog.
    pub fn add_manual_model(&self, form: &ManualModelForm) -> Result<Model, ModelManagementError> {
        let provider = self.require_provider(&form.provider_id)?;
        let remote_id = form.remote_id.trim();

        if remote_id.is_empty() {
            return Err(ModelManagementError::InvalidInput(
                "Enter the model id, for example author/model.".to_owned(),
            ));
        }

        let existing = self.models.list_by_provider(&provider.id)?;
        if existing.iter().any(|model| model.remote_id == remote_id) {
            return Err(ModelManagementError::InvalidInput(format!(
                "Model {remote_id} already exists for this provider."
            )));
        }

        if form.context_window == Some(0) {
            return Err(ModelManagementError::InvalidInput(
                "Context window must be greater than zero.".to_owned(),
            ));
        }

        let now = Timestamp::now();
        let model = Model {
            id: new_id(),
            provider_id: provider.id.clone(),
            remote_id: remote_id.to_owned(),
            label: if form.label.trim().is_empty() {
                remote_id.to_owned()
            } else {
                form.label.trim().to_owned()
            },
            context_window: form.context_window,
            max_output_tokens: form.max_output_tokens,
            capabilities: if form.capabilities.is_empty() {
                vec![ModelCapability::Text]
            } else {
                form.capabilities.clone()
            },
            pricing: None,
            canonical_slug: None,
            input_modalities: if form.input_modalities.is_empty() {
                vec![InputModality::Text]
            } else {
                form.input_modalities.clone()
            },
            output_modalities: if form.output_modalities.is_empty() {
                vec![OutputModality::Text]
            } else {
                form.output_modalities.clone()
            },
            supported_parameters: Vec::new(),
            metadata: None,
            hidden: false,
            pinned: false,
            source: ModelSource::Manual,
            created_at: now,
            updated_at: now,
        };

        self.models.insert(&model)?;
        Ok(model)
    }

    /// Build the picker view model.
    ///
    /// Search matches the label, provider model ID, and canonical slug. Pinned
    /// models come first, then provider groups. Hidden models are excluded
    /// unless `include_hidden` is set.
    pub fn picker(
        &self,
        provider_id: Option<&str>,
        query: &str,
        include_hidden: bool,
    ) -> Result<ModelPickerViewModel, ModelManagementError> {
        let providers = self.providers.list()?;
        let models = match provider_id {
            Some(id) => self.models.list_by_provider(id)?,
            None => self.models.list_all()?,
        };
        let settings = self.settings.load()?;
        let default_model_id = settings.default_model_id.clone();
        let groups = build_model_groups(
            &models,
            &providers,
            query,
            include_hidden,
            default_model_id.as_deref(),
        );
        let selected = default_model_id
            .as_deref()
            .and_then(|id| models.iter().find(|model| model.id == id));
        let availability = model_availability(selected, &models);
        let docs_url = selected
            .and_then(|model| {
                providers
                    .iter()
                    .find(|provider| provider.id == model.provider_id)
            })
            .or_else(|| {
                provider_id.and_then(|id| providers.iter().find(|provider| provider.id == id))
            })
            .or_else(|| providers.first())
            .and_then(|provider| provider_catalog().get(&provider.preset_id))
            .and_then(|preset| preset.docs_url.clone());

        Ok(ModelPickerViewModel {
            groups,
            query: query.to_owned(),
            selected_model_id: default_model_id.clone(),
            default_model_id,
            availability,
            docs_url,
            notice: None,
            error: None,
        })
    }

    /// Persist a model as the global default.
    pub fn set_default_model(&self, model_id: &str) -> Result<Model, ModelManagementError> {
        let model = self.require_model(model_id)?;
        let mut settings = self.settings.load()?;
        settings.default_provider_id = Some(model.provider_id.clone());
        settings.default_model_id = Some(model.id.clone());
        self.settings.save(&settings)?;
        Ok(model)
    }

    /// Return the persisted default model ID.
    pub fn default_model_id(&self) -> Result<Option<String>, ModelManagementError> {
        Ok(self.settings.load()?.default_model_id)
    }

    /// Return the persisted default provider ID.
    pub fn default_provider_id(&self) -> Result<Option<String>, ModelManagementError> {
        Ok(self.settings.load()?.default_provider_id)
    }

    /// List every provider.
    pub fn providers(&self) -> Result<Vec<Provider>, ModelManagementError> {
        self.providers.list()
    }

    fn upsert_catalog(
        &self,
        provider_id: &str,
        catalog: &[CatalogModel],
        source: ModelSource,
    ) -> Result<UpsertResult, ModelManagementError> {
        let existing = self.models.list_by_provider(provider_id)?;
        let by_remote_id: BTreeMap<&str, &Model> = existing
            .iter()
            .map(|model| (model.remote_id.as_str(), model))
            .collect();
        let now = Timestamp::now();
        let mut models = Vec::new();
        let mut created = 0;
        let mut updated = 0;
        let mut seen = BTreeSet::new();

        for entry in catalog {
            if !seen.insert(entry.remote_id.as_str()) {
                continue;
            }

            let current = by_remote_id.get(entry.remote_id.as_str()).copied();
            let model = model_from_catalog(entry, provider_id, current, now, source);

            if current.is_some() {
                self.models.update(&model)?;
                updated += 1;
            } else {
                self.models.insert(&model)?;
                created += 1;
            }

            models.push(model);
        }

        Ok(UpsertResult {
            models,
            created,
            updated,
        })
    }

    fn degraded_refresh(
        &self,
        provider: &Provider,
        error: ChatError,
    ) -> Result<RefreshResult, ModelManagementError> {
        let existing = self.models.list_by_provider(&provider.id)?;

        if !existing.is_empty() {
            let notice = format!(
                "Discovery unavailable; using {} cached model{}.",
                existing.len(),
                if existing.len() == 1 { "" } else { "s" }
            );
            return Ok(RefreshResult {
                models: existing,
                created: 0,
                updated: 0,
                degraded: true,
                notice: Some(notice),
            });
        }

        let Some(preset) = provider_catalog().get(&provider.preset_id) else {
            return Err(ModelManagementError::Discovery(error));
        };
        let fallback_ids = fallback_model_ids_for(&preset.id, Some(preset));

        if fallback_ids.is_empty() {
            return Err(ModelManagementError::Discovery(error));
        }

        let defaults = defaults_for_preset(preset);
        let mut catalog = Vec::new();

        for remote_id in &fallback_ids {
            let raw = serde_json::json!({ "id": remote_id });
            if let Some(mut model) = map_openai_model(&raw, &defaults) {
                model.metadata = Some(serde_json::json!({ "fallback": true }));
                catalog.push(model);
            }
        }

        if catalog.is_empty() {
            return Err(ModelManagementError::Discovery(error));
        }

        let result = self.upsert_catalog(&provider.id, &catalog, ModelSource::Preset)?;
        let notice = if fallback_ids.len() == 1 {
            format!(
                "Discovery unavailable; using fallback model {}.",
                fallback_ids[0]
            )
        } else {
            format!(
                "Discovery unavailable; using {} fallback models.",
                result.models.len()
            )
        };

        Ok(RefreshResult {
            models: result.models,
            created: result.created,
            updated: result.updated,
            degraded: true,
            notice: Some(notice),
        })
    }

    fn require_provider(&self, id: &str) -> Result<Provider, ModelManagementError> {
        self.providers
            .get(id)?
            .ok_or_else(|| ModelManagementError::NotFound(format!("Provider {id} was not found.")))
    }

    fn require_model(&self, id: &str) -> Result<Model, ModelManagementError> {
        self.models
            .get(id)?
            .ok_or_else(|| ModelManagementError::NotFound(format!("Model {id} was not found.")))
    }
}

fn model_from_catalog(
    entry: &CatalogModel,
    provider_id: &str,
    existing: Option<&Model>,
    now: Timestamp,
    source: ModelSource,
) -> Model {
    let is_manual = existing.is_some_and(|model| model.source == ModelSource::Manual);

    Model {
        id: existing
            .map(|model| model.id.clone())
            .unwrap_or_else(new_id),
        provider_id: provider_id.to_owned(),
        remote_id: entry.remote_id.clone(),
        label: if is_manual {
            existing
                .map(|model| model.label.clone())
                .unwrap_or_default()
        } else {
            entry.label.clone()
        },
        context_window: entry.context_window,
        max_output_tokens: entry.max_output_tokens,
        capabilities: entry.capabilities.clone(),
        pricing: entry.pricing.clone(),
        canonical_slug: entry.canonical_slug.clone(),
        input_modalities: entry.input_modalities.clone(),
        output_modalities: entry.output_modalities.clone(),
        supported_parameters: entry.supported_parameters.clone(),
        metadata: if is_manual {
            existing.and_then(|model| model.metadata.clone())
        } else {
            entry.metadata.clone()
        },
        hidden: existing.map(|model| model.hidden).unwrap_or(false),
        pinned: existing.map(|model| model.pinned).unwrap_or(false),
        source: if is_manual {
            ModelSource::Manual
        } else {
            source
        },
        created_at: existing.map(|model| model.created_at).unwrap_or(now),
        updated_at: now,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::Mutex;

    use chatui_domain::{
        BrowserCors, ChatErrorCode, ChatFontSize, ChatParams, Density, ModelPricing,
        ProviderAuthType, ProviderKind, SendKey, Theme,
    };

    #[derive(Default)]
    struct FakeModels {
        models: Mutex<Vec<Model>>,
    }

    impl ModelRepository for FakeModels {
        fn list_all(&self) -> Result<Vec<Model>, ModelManagementError> {
            Ok(self.models.lock().expect("models lock").clone())
        }

        fn list_by_provider(&self, provider_id: &str) -> Result<Vec<Model>, ModelManagementError> {
            Ok(self
                .models
                .lock()
                .expect("models lock")
                .iter()
                .filter(|model| model.provider_id == provider_id)
                .cloned()
                .collect())
        }

        fn get(&self, id: &str) -> Result<Option<Model>, ModelManagementError> {
            Ok(self
                .models
                .lock()
                .expect("models lock")
                .iter()
                .find(|model| model.id == id)
                .cloned())
        }

        fn insert(&self, model: &Model) -> Result<(), ModelManagementError> {
            self.models.lock().expect("models lock").push(model.clone());
            Ok(())
        }

        fn update(&self, model: &Model) -> Result<(), ModelManagementError> {
            let mut models = self.models.lock().expect("models lock");
            if let Some(existing) = models.iter_mut().find(|item| item.id == model.id) {
                *existing = model.clone();
            }
            Ok(())
        }

        fn set_pinned(&self, id: &str, pinned: bool) -> Result<(), ModelManagementError> {
            let mut models = self.models.lock().expect("models lock");
            if let Some(model) = models.iter_mut().find(|model| model.id == id) {
                model.pinned = pinned;
            }
            Ok(())
        }

        fn set_hidden(&self, id: &str, hidden: bool) -> Result<(), ModelManagementError> {
            let mut models = self.models.lock().expect("models lock");
            if let Some(model) = models.iter_mut().find(|model| model.id == id) {
                model.hidden = hidden;
            }
            Ok(())
        }
    }

    #[derive(Default)]
    struct FakeProviders {
        providers: Mutex<Vec<Provider>>,
    }

    impl ProviderRepository for FakeProviders {
        fn list(&self) -> Result<Vec<Provider>, ModelManagementError> {
            Ok(self.providers.lock().expect("providers lock").clone())
        }

        fn get(&self, id: &str) -> Result<Option<Provider>, ModelManagementError> {
            Ok(self
                .providers
                .lock()
                .expect("providers lock")
                .iter()
                .find(|provider| provider.id == id)
                .cloned())
        }
    }

    struct FakeDiscovery {
        result: Mutex<Result<Vec<CatalogModel>, ChatError>>,
        calls: Mutex<usize>,
    }

    impl ModelDiscovery for FakeDiscovery {
        fn discover(&self, _provider: &Provider) -> Result<Vec<CatalogModel>, ChatError> {
            *self.calls.lock().expect("calls lock") += 1;
            self.result.lock().expect("discovery lock").clone()
        }
    }

    struct FakeSettings {
        settings: Mutex<Settings>,
    }

    impl SettingsStore for FakeSettings {
        fn load(&self) -> Result<Settings, ModelManagementError> {
            Ok(self.settings.lock().expect("settings lock").clone())
        }

        fn save(&self, settings: &Settings) -> Result<(), ModelManagementError> {
            *self.settings.lock().expect("settings lock") = settings.clone();
            Ok(())
        }
    }

    struct Harness {
        service: ModelManagementService,
        models: Arc<FakeModels>,
        providers: Arc<FakeProviders>,
        discovery: Arc<FakeDiscovery>,
        settings: Arc<FakeSettings>,
    }

    fn default_settings() -> Settings {
        Settings {
            theme: Theme::System,
            density: Density::Comfortable,
            chat_font_size: ChatFontSize::Md,
            send_key: SendKey::Enter,
            default_provider_id: None,
            default_model_id: None,
            default_system_prompt: String::new(),
            default_params: ChatParams::default(),
            remember_keys_on_web: true,
            show_token_usage: true,
            load_remote_images: false,
            last_active_conversation_id: None,
            backup: None,
            onboarding_completed: true,
        }
    }

    fn provider(id: &str, preset_id: &str) -> Provider {
        let now = Timestamp::now();
        Provider {
            id: id.to_owned(),
            preset_id: preset_id.to_owned(),
            label: "Personal".to_owned(),
            kind: ProviderKind::OpenaiCompatible,
            base_url: "https://openrouter.ai/api/v1".to_owned(),
            auth_type: ProviderAuthType::Bearer,
            secret_id: None,
            headers: BTreeMap::new(),
            enabled: true,
            browser_cors: BrowserCors::Supported,
            metadata: None,
            created_at: now,
            updated_at: now,
        }
    }

    fn catalog_model(remote_id: &str, label: &str) -> CatalogModel {
        CatalogModel {
            remote_id: remote_id.to_owned(),
            canonical_slug: None,
            label: label.to_owned(),
            context_window: Some(128_000),
            max_output_tokens: Some(4096),
            capabilities: vec![ModelCapability::Text, ModelCapability::Streaming],
            pricing: Some(ModelPricing {
                input_per_m_tok: Some(1.5),
                output_per_m_tok: Some(6.0),
                ..ModelPricing::default()
            }),
            input_modalities: vec![InputModality::Text],
            output_modalities: vec![OutputModality::Text],
            supported_parameters: Vec::new(),
            metadata: None,
            is_free: false,
            hidden: false,
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

    fn harness(discovery: Result<Vec<CatalogModel>, ChatError>) -> Harness {
        let models = Arc::new(FakeModels::default());
        let providers = Arc::new(FakeProviders::default());
        let discovery = Arc::new(FakeDiscovery {
            result: Mutex::new(discovery),
            calls: Mutex::new(0),
        });
        let settings = Arc::new(FakeSettings {
            settings: Mutex::new(default_settings()),
        });
        let service = ModelManagementService::new(
            models.clone(),
            providers.clone(),
            discovery.clone(),
            settings.clone(),
        );

        Harness {
            service,
            models,
            providers,
            discovery,
            settings,
        }
    }

    fn seed_provider(harness: &Harness, provider: Provider) {
        harness
            .providers
            .providers
            .lock()
            .expect("providers lock")
            .push(provider);
    }

    fn seed_model(harness: &Harness, model: Model) {
        harness
            .models
            .models
            .lock()
            .expect("models lock")
            .push(model);
    }

    fn existing_model(id: &str, provider_id: &str, remote_id: &str, source: ModelSource) -> Model {
        let now = Timestamp::now();
        Model {
            id: id.to_owned(),
            provider_id: provider_id.to_owned(),
            remote_id: remote_id.to_owned(),
            label: "User label".to_owned(),
            context_window: Some(64_000),
            max_output_tokens: None,
            capabilities: vec![ModelCapability::Text],
            pricing: None,
            canonical_slug: None,
            input_modalities: vec![InputModality::Text],
            output_modalities: vec![OutputModality::Text],
            supported_parameters: Vec::new(),
            metadata: Some(serde_json::json!({ "user": true })),
            hidden: true,
            pinned: true,
            source,
            created_at: now,
            updated_at: now,
        }
    }

    #[test]
    fn refresh_upserts_and_preserves_user_state() {
        let harness = harness(Ok(vec![
            catalog_model("author/model", "Model"),
            catalog_model("author/new", "New"),
        ]));
        seed_provider(&harness, provider("p1", "openrouter"));
        seed_model(
            &harness,
            existing_model("m1", "p1", "author/model", ModelSource::Manual),
        );

        let result = harness.service.refresh("p1").expect("refresh");

        assert!(!result.degraded);
        assert_eq!(result.created, 1);
        assert_eq!(result.updated, 1);
        assert_eq!(harness.discovery.calls.lock().unwrap().to_owned(), 1);

        let models = harness.models.models.lock().unwrap();
        let manual = models
            .iter()
            .find(|model| model.remote_id == "author/model")
            .expect("manual model");
        assert_eq!(manual.id, "m1");
        assert_eq!(manual.label, "User label");
        assert!(manual.pinned);
        assert!(manual.hidden);
        assert_eq!(manual.source, ModelSource::Manual);
        assert_eq!(manual.remote_id, "author/model");

        let discovered = models
            .iter()
            .find(|model| model.remote_id == "author/new")
            .expect("new model");
        assert_eq!(discovered.source, ModelSource::Discovered);
        assert_eq!(discovered.label, "New");
    }

    #[test]
    fn refresh_failure_keeps_cached_models() {
        let harness = harness(Err(chat_error(ChatErrorCode::Network, "offline")));
        seed_provider(&harness, provider("p1", "openrouter"));
        seed_model(
            &harness,
            existing_model("m1", "p1", "author/model", ModelSource::Discovered),
        );

        let result = harness.service.refresh("p1").expect("degraded refresh");

        assert!(result.degraded);
        assert_eq!(result.models.len(), 1);
        assert_eq!(
            result.notice.as_deref(),
            Some("Discovery unavailable; using 1 cached model.")
        );
        assert_eq!(harness.models.models.lock().unwrap().len(), 1);
    }

    #[test]
    fn refresh_failure_with_empty_cache_uses_preset_fallbacks() {
        let harness = harness(Err(chat_error(ChatErrorCode::Network, "offline")));
        seed_provider(&harness, provider("p1", "openai"));

        let result = harness.service.refresh("p1").expect("fallback refresh");

        assert!(result.degraded);
        assert_eq!(result.models.len(), 2);
        assert_eq!(
            result.notice.as_deref(),
            Some("Discovery unavailable; using 2 fallback models.")
        );
        assert!(
            result
                .models
                .iter()
                .all(|model| model.source == ModelSource::Preset)
        );
        assert!(
            result
                .models
                .iter()
                .all(|model| model.metadata.as_ref().unwrap()["fallback"] == true)
        );
    }

    #[test]
    fn refresh_failure_without_fallbacks_is_an_error() {
        let harness = harness(Err(chat_error(ChatErrorCode::Network, "offline")));
        seed_provider(&harness, provider("p1", "openrouter"));

        let error = harness.service.refresh("p1").expect_err("no fallback");

        assert_eq!(error.chat_code(), Some(ChatErrorCode::Network));
    }

    #[test]
    fn pinned_and_hidden_state_persists() {
        let harness = harness(Ok(Vec::new()));
        seed_provider(&harness, provider("p1", "openrouter"));
        seed_model(
            &harness,
            existing_model("m1", "p1", "author/model", ModelSource::Discovered),
        );

        harness.service.set_pinned("m1", false).expect("unpin");
        harness.service.set_hidden("m1", false).expect("unhide");

        let model = harness.models.get("m1").unwrap().unwrap();
        assert!(!model.pinned);
        assert!(!model.hidden);
    }

    #[test]
    fn manual_add_validates_and_defaults() {
        let harness = harness(Ok(Vec::new()));
        seed_provider(&harness, provider("p1", "openrouter"));

        let mut form = ManualModelForm::new("p1");
        form.remote_id = "  author/manual  ".to_owned();
        form.context_window = Some(32_000);
        form.capabilities = vec![ModelCapability::Text, ModelCapability::Vision];

        let model = harness.service.add_manual_model(&form).expect("manual add");

        assert_eq!(model.remote_id, "author/manual");
        assert_eq!(model.label, "author/manual");
        assert_eq!(model.context_window, Some(32_000));
        assert_eq!(model.source, ModelSource::Manual);
        assert_eq!(
            model.capabilities,
            vec![ModelCapability::Text, ModelCapability::Vision]
        );

        let duplicate = harness
            .service
            .add_manual_model(&form)
            .expect_err("duplicate rejected");
        assert!(duplicate.message().contains("already exists"));

        let mut empty = ManualModelForm::new("p1");
        empty.remote_id = "   ".to_owned();
        let error = harness
            .service
            .add_manual_model(&empty)
            .expect_err("empty id rejected");
        assert!(error.message().contains("Enter the model id"));
    }

    #[test]
    fn picker_marks_default_and_warns_when_unavailable() {
        let harness = harness(Ok(Vec::new()));
        seed_provider(&harness, provider("p1", "openrouter"));
        seed_model(
            &harness,
            existing_model("m1", "p1", "author/model", ModelSource::Discovered),
        );
        harness.service.set_hidden("m1", false).expect("unhide");
        harness.settings.settings.lock().unwrap().default_model_id = Some("m1".to_owned());

        let picker = harness
            .service
            .picker(Some("p1"), "", false)
            .expect("picker");

        assert_eq!(picker.default_model_id.as_deref(), Some("m1"));
        assert!(picker.availability.available);
        assert_eq!(
            picker.docs_url.as_deref(),
            Some("https://openrouter.ai/docs")
        );
        assert!(picker.rows()[0].is_default);

        harness.settings.settings.lock().unwrap().default_model_id = Some("missing".to_owned());
        let picker = harness
            .service
            .picker(Some("p1"), "", false)
            .expect("picker");
        assert!(!picker.availability.available);
        assert!(
            picker
                .availability
                .message
                .unwrap()
                .contains("no longer available")
        );
    }

    #[test]
    fn set_default_model_persists_provider_and_model() {
        let harness = harness(Ok(Vec::new()));
        seed_provider(&harness, provider("p1", "openrouter"));
        seed_model(
            &harness,
            existing_model("m1", "p1", "author/model", ModelSource::Discovered),
        );

        harness
            .service
            .set_default_model("m1")
            .expect("set default");

        let settings = harness.settings.settings.lock().unwrap();
        assert_eq!(settings.default_provider_id.as_deref(), Some("p1"));
        assert_eq!(settings.default_model_id.as_deref(), Some("m1"));
    }

    #[test]
    fn list_models_includes_hidden_and_counts_state() {
        let harness = harness(Ok(Vec::new()));
        seed_provider(&harness, provider("p1", "openrouter"));
        seed_model(
            &harness,
            existing_model("m1", "p1", "author/model", ModelSource::Discovered),
        );

        let list = harness.service.list_models(Some("p1")).expect("list");

        assert_eq!(list.total, 1);
        assert_eq!(list.pinned_count, 1);
        assert_eq!(list.hidden_count, 1);
        assert_eq!(list.rows().len(), 1);
        assert!(list.rows()[0].hidden);
    }

    #[test]
    fn unknown_provider_is_not_found() {
        let harness = harness(Ok(Vec::new()));

        let error = harness
            .service
            .refresh("missing")
            .expect_err("missing provider");

        assert!(matches!(error, ModelManagementError::NotFound(_)));
    }

    #[test]
    fn fake_settings_round_trip_is_used_by_default_model_id() {
        let harness = harness(Ok(Vec::new()));
        let mut settings = default_settings();
        settings.default_model_id = Some("m1".to_owned());
        *harness.settings.settings.lock().unwrap() = settings;

        assert_eq!(
            harness.service.default_model_id().unwrap().as_deref(),
            Some("m1")
        );
    }
}
