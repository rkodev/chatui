//! First-run onboarding orchestration.
//!
//! The service is injected with small traits for the public catalog, key
//! validation, provider persistence, model persistence, settings, and the
//! secret store. It never talks to SQLite or the network directly, so the view
//! model and tests can use fakes.
//!
//! The connect flow is:
//!
//! 1. validate the key with `GET /key`;
//! 2. store the key through [`SecretStore`];
//! 3. load the public catalog, falling back to cached or preset models;
//! 4. select the default model with the shared fallback rule;
//! 5. persist the provider record, models, and default settings;
//! 6. mark onboarding complete.
//!
//! Any failure after the first write rolls the provider, models, secret, and
//! settings back so an invalid key stores nothing.

#![allow(clippy::result_large_err)]

use std::sync::Arc;

use chatui_contracts::ProviderPreset;
use chatui_domain::{ChatError, Model, OutputModality, Provider, Settings, Timestamp, new_id};
use chatui_providers::{
    CatalogModel, FallbackCatalogEntry, FallbackSelection, ProviderKeyInfo, defaults_for_preset,
    fallback_model_ids_for, map_openai_model, select_fallback_model,
};
use chatui_secrets::{SecretId, SecretStore, SecretValue};

use crate::onboarding::error::OnboardingError;
use crate::onboarding::state::OnboardingStep;

/// Public catalog source. The OpenRouter `/models` endpoint is public, so this
/// works before a key is entered.
pub trait CatalogSource: Send + Sync {
    /// Load the public catalog.
    fn load_catalog(&self) -> Result<Vec<CatalogModel>, ChatError>;
}

/// Key validation source. Implementations call `GET /key` and never a paid
/// completion request.
pub trait KeyValidator: Send + Sync {
    /// Validate a key and return its non-secret metadata.
    fn validate_key(&self, key: &SecretValue) -> Result<ProviderKeyInfo, ChatError>;
}

/// Provider record persistence.
pub trait ProviderStore: Send + Sync {
    /// Insert or replace a provider record.
    fn save_provider(&self, provider: &Provider) -> Result<(), OnboardingError>;
    /// Delete a provider record.
    fn delete_provider(&self, id: &str) -> Result<(), OnboardingError>;
}

/// Model persistence.
pub trait ModelStore: Send + Sync {
    /// Return cached models for a provider.
    fn cached_models(&self, provider_id: &str) -> Result<Vec<Model>, OnboardingError>;
    /// Persist a catalog and return the stored models.
    fn save_catalog(
        &self,
        provider_id: &str,
        models: &[CatalogModel],
    ) -> Result<Vec<Model>, OnboardingError>;
    /// Delete every model for a provider.
    fn delete_models(&self, provider_id: &str) -> Result<(), OnboardingError>;
}

/// Settings persistence.
pub trait SettingsStore: Send + Sync {
    /// Load the complete settings object.
    fn load(&self) -> Result<Settings, OnboardingError>;
    /// Persist the complete settings object.
    fn save(&self, settings: &Settings) -> Result<(), OnboardingError>;
}

/// Values submitted by the onboarding key form.
#[derive(Clone)]
pub struct ConnectInput {
    /// Typed API key. `Debug` is redacted.
    pub api_key: SecretValue,
    /// Optional user label.
    pub label: String,
    /// Whether the caller should remember the key.
    pub remember_key: bool,
}

impl std::fmt::Debug for ConnectInput {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        formatter
            .debug_struct("ConnectInput")
            .field("api_key", &"[REDACTED]")
            .field("label", &self.label)
            .field("remember_key", &self.remember_key)
            .finish()
    }
}

/// Result of a successful onboarding connection.
#[derive(Debug, Clone, PartialEq)]
pub struct ConnectResult {
    /// Persisted provider ID.
    pub provider_id: String,
    /// Persisted default model ID.
    pub model_id: String,
    /// Provider model ID of the default model.
    pub model_remote_id: String,
    /// Non-blocking default-model notice.
    pub notice: Option<String>,
    /// Non-blocking catalog notice.
    pub catalog_notice: Option<String>,
    /// Catalog used for the selection.
    pub catalog: Vec<CatalogModel>,
    /// Selected default model.
    pub selected_model: Model,
}

/// Public catalog result.
#[derive(Debug, Clone, PartialEq)]
pub struct CatalogLoad {
    /// Catalog entries.
    pub models: Vec<CatalogModel>,
    /// Non-blocking notice.
    pub notice: Option<String>,
}

/// The onboarding service surface used by the view controller.
pub trait OnboardingServiceLike {
    /// Load the public catalog.
    fn load_catalog(&self) -> Result<CatalogLoad, OnboardingError>;
    /// Connect with the default progress reporting.
    fn connect(&self, input: ConnectInput) -> Result<ConnectResult, OnboardingError>;
    /// Connect and report each state-machine step.
    fn connect_with_progress(
        &self,
        input: ConnectInput,
        on_step: &mut dyn FnMut(OnboardingStep),
    ) -> Result<ConnectResult, OnboardingError>;
    /// Return whether onboarding has already completed.
    fn is_onboarding_complete(&self) -> Result<bool, OnboardingError>;
}

enum CatalogOutcome {
    Fresh(Vec<CatalogModel>),
    Cached(Vec<Model>, String),
    Fallback(Vec<CatalogModel>, String),
}

/// First-run onboarding orchestration over injected traits.
pub struct OnboardingService {
    preset: ProviderPreset,
    catalog: Arc<dyn CatalogSource>,
    validator: Arc<dyn KeyValidator>,
    providers: Arc<dyn ProviderStore>,
    models: Arc<dyn ModelStore>,
    settings: Arc<dyn SettingsStore>,
    secrets: Arc<dyn SecretStore>,
}

impl OnboardingService {
    /// Build a service for one provider preset.
    pub fn new(
        preset: ProviderPreset,
        catalog: Arc<dyn CatalogSource>,
        validator: Arc<dyn KeyValidator>,
        providers: Arc<dyn ProviderStore>,
        models: Arc<dyn ModelStore>,
        settings: Arc<dyn SettingsStore>,
        secrets: Arc<dyn SecretStore>,
    ) -> Self {
        Self {
            preset,
            catalog,
            validator,
            providers,
            models,
            settings,
            secrets,
        }
    }

    /// Return the preset this service onboards.
    pub fn preset(&self) -> &ProviderPreset {
        &self.preset
    }

    /// Load the public catalog. No key is required or sent.
    pub fn load_catalog(&self) -> Result<CatalogLoad, OnboardingError> {
        self.catalog
            .load_catalog()
            .map(|models| CatalogLoad {
                models,
                notice: None,
            })
            .map_err(OnboardingError::Chat)
    }

    /// Return whether onboarding has already completed.
    pub fn is_onboarding_complete(&self) -> Result<bool, OnboardingError> {
        Ok(self.settings.load()?.onboarding_completed)
    }

    /// Connect with the default progress reporting.
    pub fn connect(&self, input: ConnectInput) -> Result<ConnectResult, OnboardingError> {
        self.connect_inner(input, &mut |_| {})
    }

    /// Connect and report each state-machine step.
    pub fn connect_with_progress<F>(
        &self,
        input: ConnectInput,
        mut on_step: F,
    ) -> Result<ConnectResult, OnboardingError>
    where
        F: FnMut(OnboardingStep),
    {
        self.connect_inner(input, &mut on_step)
    }

    fn connect_inner(
        &self,
        input: ConnectInput,
        on_step: &mut dyn FnMut(OnboardingStep),
    ) -> Result<ConnectResult, OnboardingError> {
        let key = input.api_key.expose().trim().to_owned();
        if key.is_empty() {
            return Err(OnboardingError::InvalidInput(
                "Enter an OpenRouter API key to continue.".to_owned(),
            ));
        }
        let key = SecretValue::new(key);

        let previous_settings = self.settings.load()?;
        let provider_id = new_id();
        let secret_id = SecretId::new(format!("provider-{provider_id}"))
            .map_err(|error| OnboardingError::Secret(error.to_string()))?;

        let mut secret_saved = false;
        let mut provider_saved = false;
        let mut models_saved = false;

        let result = (|| {
            on_step(OnboardingStep::Validating);
            let key_info = self
                .validator
                .validate_key(&key)
                .map_err(|error| OnboardingError::Chat(redact_chat_error(error, key.expose())))?;

            self.secrets.set(&secret_id, &key)?;
            secret_saved = true;

            on_step(OnboardingStep::CatalogLoading);
            let catalog_outcome = match self.catalog.load_catalog() {
                Ok(models) => CatalogOutcome::Fresh(models),
                Err(error) => self.degraded_catalog(&provider_id, error)?,
            };

            on_step(OnboardingStep::ModelSelection);
            let (catalog, catalog_notice, models) = match catalog_outcome {
                CatalogOutcome::Fresh(models) => {
                    let saved = self.models.save_catalog(&provider_id, &models)?;
                    models_saved = true;
                    (models, None, saved)
                }
                CatalogOutcome::Cached(models, notice) => {
                    let catalog = models.iter().map(catalog_from_model).collect();
                    (catalog, Some(notice), models)
                }
                CatalogOutcome::Fallback(models, notice) => {
                    let saved = self.models.save_catalog(&provider_id, &models)?;
                    models_saved = true;
                    (models, Some(notice), saved)
                }
            };

            let fallback_ids = fallback_model_ids_for(&self.preset.id, Some(&self.preset));
            let selection = select_default_model_for(&self.preset, &models, &fallback_ids);
            let selected_remote_id = selection.selected_model_id.clone().ok_or_else(|| {
                OnboardingError::NoModel(
                    selection
                        .notice
                        .clone()
                        .unwrap_or_else(|| "No text model is available.".to_owned()),
                )
            })?;
            let selected_model = models
                .iter()
                .find(|model| model.remote_id == selected_remote_id)
                .cloned()
                .ok_or_else(|| {
                    OnboardingError::NoModel(selection.notice.clone().unwrap_or_else(|| {
                        format!("Default model {selected_remote_id} is unavailable.")
                    }))
                })?;

            let label = resolve_label(&input.label, key_info.label.as_deref(), &self.preset.label);
            let now = Timestamp::now();
            let provider = Provider {
                id: provider_id.clone(),
                preset_id: self.preset.id.clone(),
                label,
                kind: self.preset.kind,
                base_url: self.preset.base_url.clone(),
                auth_type: self.preset.auth.auth_type,
                secret_id: Some(secret_id.as_str().to_owned()),
                headers: self.preset.default_headers.clone(),
                enabled: true,
                browser_cors: self.preset.browser_cors,
                metadata: Some(serde_json::json!({
                    "key": key_info,
                    "validatedAt": now.to_rfc3339(),
                })),
                created_at: now,
                updated_at: now,
            };
            self.providers.save_provider(&provider)?;
            provider_saved = true;

            let mut settings = previous_settings.clone();
            settings.default_provider_id = Some(provider_id.clone());
            settings.default_model_id = Some(selected_model.id.clone());
            settings.onboarding_completed = true;
            self.settings.save(&settings)?;

            on_step(OnboardingStep::Complete);
            Ok(ConnectResult {
                provider_id: provider_id.clone(),
                model_id: selected_model.id.clone(),
                model_remote_id: selected_model.remote_id.clone(),
                notice: selection.notice,
                catalog_notice,
                catalog,
                selected_model,
            })
        })();

        if result.is_err() {
            if provider_saved {
                let _ = self.providers.delete_provider(&provider_id);
            }
            if models_saved {
                let _ = self.models.delete_models(&provider_id);
            }
            if secret_saved {
                let _ = self.secrets.delete(&secret_id);
            }
            let _ = self.settings.save(&previous_settings);
        }

        result
    }

    fn degraded_catalog(
        &self,
        provider_id: &str,
        error: ChatError,
    ) -> Result<CatalogOutcome, OnboardingError> {
        let cached = self.models.cached_models(provider_id)?;
        if !cached.is_empty() {
            let notice = format!(
                "Discovery unavailable; using {} cached model{}.",
                cached.len(),
                if cached.len() == 1 { "" } else { "s" }
            );
            return Ok(CatalogOutcome::Cached(cached, notice));
        }

        let fallback_ids = fallback_model_ids_for(&self.preset.id, Some(&self.preset));
        if fallback_ids.is_empty() {
            return Err(OnboardingError::Chat(error));
        }

        let defaults = defaults_for_preset(&self.preset);
        let mut models = Vec::new();
        for remote_id in &fallback_ids {
            let raw = serde_json::json!({ "id": remote_id });
            if let Some(mut model) = map_openai_model(&raw, &defaults) {
                model.metadata = Some(serde_json::json!({ "fallback": true }));
                models.push(model);
            }
        }

        if models.is_empty() {
            return Err(OnboardingError::Chat(error));
        }

        let notice = if fallback_ids.len() == 1 {
            format!(
                "Discovery unavailable; using fallback model {}.",
                fallback_ids[0]
            )
        } else {
            format!(
                "Discovery unavailable; using {} fallback models.",
                models.len()
            )
        };
        Ok(CatalogOutcome::Fallback(models, notice))
    }
}

impl OnboardingServiceLike for OnboardingService {
    fn load_catalog(&self) -> Result<CatalogLoad, OnboardingError> {
        OnboardingService::load_catalog(self)
    }

    fn connect(&self, input: ConnectInput) -> Result<ConnectResult, OnboardingError> {
        OnboardingService::connect(self, input)
    }

    fn connect_with_progress(
        &self,
        input: ConnectInput,
        on_step: &mut dyn FnMut(OnboardingStep),
    ) -> Result<ConnectResult, OnboardingError> {
        self.connect_inner(input, on_step)
    }

    fn is_onboarding_complete(&self) -> Result<bool, OnboardingError> {
        OnboardingService::is_onboarding_complete(self)
    }
}

fn resolve_label(input: &str, key_label: Option<&str>, preset_label: &str) -> String {
    let input = input.trim();
    if !input.is_empty() {
        return input.to_owned();
    }

    if let Some(label) = key_label.map(str::trim).filter(|label| !label.is_empty()) {
        return label.to_owned();
    }

    preset_label.to_owned()
}

fn redact_chat_error(mut error: ChatError, secret: &str) -> ChatError {
    if secret.is_empty() {
        return error;
    }

    error.message = error.message.replace(secret, "[redacted]");
    if let Some(details) = error.details.take() {
        let text = details.to_string().replace(secret, "[redacted]");
        error.details = serde_json::from_str(&text).ok();
    }
    error
}

fn catalog_from_model(model: &Model) -> CatalogModel {
    CatalogModel {
        remote_id: model.remote_id.clone(),
        canonical_slug: model.canonical_slug.clone(),
        label: model.label.clone(),
        context_window: model.context_window,
        max_output_tokens: model.max_output_tokens,
        capabilities: model.capabilities.clone(),
        pricing: model.pricing.clone(),
        input_modalities: model.input_modalities.clone(),
        output_modalities: model.output_modalities.clone(),
        supported_parameters: model.supported_parameters.clone(),
        metadata: model.metadata.clone(),
        is_free: model.pricing.as_ref().is_some_and(|pricing| {
            pricing.input_per_m_tok == Some(0.0) && pricing.output_per_m_tok == Some(0.0)
        }),
        hidden: model.hidden,
    }
}

fn select_default_model_for(
    preset: &ProviderPreset,
    models: &[Model],
    fallback_ids: &[String],
) -> FallbackSelection {
    let only_fallback = !models.is_empty()
        && models.iter().all(|model| {
            model
                .metadata
                .as_ref()
                .and_then(|value| value.get("fallback"))
                .and_then(serde_json::Value::as_bool)
                .unwrap_or(false)
        });

    let entries = if only_fallback {
        Vec::new()
    } else {
        models
            .iter()
            .map(|model| FallbackCatalogEntry {
                remote_id: model.remote_id.clone(),
                hidden: model.hidden,
                output_modalities: model
                    .output_modalities
                    .iter()
                    .copied()
                    .map(modality_to_string)
                    .collect(),
            })
            .collect()
    };

    select_fallback_model(&entries, &preset.default_model_id, fallback_ids)
}

fn modality_to_string(modality: OutputModality) -> String {
    match modality {
        OutputModality::Text => "text".to_owned(),
        OutputModality::Image => "image".to_owned(),
        OutputModality::Audio => "audio".to_owned(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::HashMap;
    use std::sync::Mutex;

    use chatui_contracts::provider_catalog;
    use chatui_domain::{
        ChatErrorCode, InputModality, ModelCapability, ModelSource, OutputModality,
    };
    use chatui_secrets::SecretError;

    struct FakeCatalog {
        result: Mutex<Result<Vec<CatalogModel>, ChatError>>,
    }

    impl CatalogSource for FakeCatalog {
        fn load_catalog(&self) -> Result<Vec<CatalogModel>, ChatError> {
            self.result.lock().expect("catalog lock").clone()
        }
    }

    struct FakeValidator {
        result: Mutex<Result<ProviderKeyInfo, ChatError>>,
    }

    impl KeyValidator for FakeValidator {
        fn validate_key(&self, _key: &SecretValue) -> Result<ProviderKeyInfo, ChatError> {
            self.result.lock().expect("validator lock").clone()
        }
    }

    #[derive(Default)]
    struct FakeProviders {
        providers: Mutex<Vec<Provider>>,
    }

    impl ProviderStore for FakeProviders {
        fn save_provider(&self, provider: &Provider) -> Result<(), OnboardingError> {
            self.providers
                .lock()
                .expect("providers lock")
                .push(provider.clone());
            Ok(())
        }

        fn delete_provider(&self, id: &str) -> Result<(), OnboardingError> {
            self.providers
                .lock()
                .expect("providers lock")
                .retain(|provider| provider.id != id);
            Ok(())
        }
    }

    #[derive(Default)]
    struct FakeModels {
        cached: Mutex<Vec<Model>>,
        saved: Mutex<Vec<Model>>,
    }

    impl ModelStore for FakeModels {
        fn cached_models(&self, _provider_id: &str) -> Result<Vec<Model>, OnboardingError> {
            Ok(self.cached.lock().expect("cached lock").clone())
        }

        fn save_catalog(
            &self,
            provider_id: &str,
            models: &[CatalogModel],
        ) -> Result<Vec<Model>, OnboardingError> {
            let now = Timestamp::now();
            let saved: Vec<Model> = models
                .iter()
                .map(|model| model.to_model(new_id(), provider_id, now, now))
                .collect();
            self.saved.lock().expect("saved lock").extend(saved.clone());
            Ok(saved)
        }

        fn delete_models(&self, provider_id: &str) -> Result<(), OnboardingError> {
            self.saved
                .lock()
                .expect("saved lock")
                .retain(|model| model.provider_id != provider_id);
            Ok(())
        }
    }

    struct FakeSettings {
        settings: Mutex<Settings>,
    }

    impl SettingsStore for FakeSettings {
        fn load(&self) -> Result<Settings, OnboardingError> {
            Ok(self.settings.lock().expect("settings lock").clone())
        }

        fn save(&self, settings: &Settings) -> Result<(), OnboardingError> {
            *self.settings.lock().expect("settings lock") = settings.clone();
            Ok(())
        }
    }

    #[derive(Default)]
    struct FakeSecrets {
        values: Mutex<HashMap<SecretId, SecretValue>>,
    }

    impl SecretStore for FakeSecrets {
        fn set(&self, id: &SecretId, value: &SecretValue) -> Result<(), SecretError> {
            self.values
                .lock()
                .expect("secrets lock")
                .insert(id.clone(), value.clone());
            Ok(())
        }

        fn get(&self, id: &SecretId) -> Result<Option<SecretValue>, SecretError> {
            Ok(self.values.lock().expect("secrets lock").get(id).cloned())
        }

        fn delete(&self, id: &SecretId) -> Result<(), SecretError> {
            self.values.lock().expect("secrets lock").remove(id);
            Ok(())
        }

        fn forget_key(&self, id: &SecretId) -> Result<(), SecretError> {
            self.delete(id)
        }

        fn forget_all_keys(&self) -> Result<(), SecretError> {
            self.values.lock().expect("secrets lock").clear();
            Ok(())
        }

        fn is_available(&self) -> bool {
            true
        }
    }

    fn preset(id: &str) -> ProviderPreset {
        provider_catalog().get(id).expect("preset exists").clone()
    }

    fn default_settings() -> Settings {
        Settings {
            theme: chatui_domain::Theme::System,
            density: chatui_domain::Density::Comfortable,
            chat_font_size: chatui_domain::ChatFontSize::Md,
            send_key: chatui_domain::SendKey::Enter,
            default_provider_id: None,
            default_model_id: None,
            default_system_prompt: String::new(),
            default_params: chatui_domain::ChatParams::default(),
            remember_keys_on_web: true,
            show_token_usage: true,
            load_remote_images: false,
            last_active_conversation_id: None,
            backup: None,
            onboarding_completed: false,
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

    fn catalog_model(
        remote_id: &str,
        hidden: bool,
        output_modalities: &[OutputModality],
    ) -> CatalogModel {
        CatalogModel {
            remote_id: remote_id.to_owned(),
            canonical_slug: None,
            label: remote_id.to_owned(),
            context_window: Some(128_000),
            max_output_tokens: Some(4096),
            capabilities: vec![ModelCapability::Text],
            pricing: None,
            input_modalities: vec![InputModality::Text],
            output_modalities: output_modalities.to_vec(),
            supported_parameters: Vec::new(),
            metadata: None,
            is_free: false,
            hidden,
        }
    }

    fn model(remote_id: &str, hidden: bool, output_modalities: &[OutputModality]) -> Model {
        let now = Timestamp::now();
        Model {
            id: format!("model-{remote_id}"),
            provider_id: "cached-provider".to_owned(),
            remote_id: remote_id.to_owned(),
            label: remote_id.to_owned(),
            context_window: Some(128_000),
            max_output_tokens: Some(4096),
            capabilities: vec![ModelCapability::Text],
            pricing: None,
            canonical_slug: None,
            input_modalities: vec![InputModality::Text],
            output_modalities: output_modalities.to_vec(),
            supported_parameters: Vec::new(),
            metadata: None,
            hidden,
            pinned: false,
            source: ModelSource::Discovered,
            created_at: now,
            updated_at: now,
        }
    }

    struct Harness {
        service: OnboardingService,
        providers: Arc<FakeProviders>,
        models: Arc<FakeModels>,
        settings: Arc<FakeSettings>,
        secrets: Arc<FakeSecrets>,
    }

    fn harness(
        preset_id: &str,
        catalog: Result<Vec<CatalogModel>, ChatError>,
        validation: Result<ProviderKeyInfo, ChatError>,
    ) -> Harness {
        let providers = Arc::new(FakeProviders::default());
        let models = Arc::new(FakeModels::default());
        let settings = Arc::new(FakeSettings {
            settings: Mutex::new(default_settings()),
        });
        let secrets = Arc::new(FakeSecrets::default());
        let service = OnboardingService::new(
            preset(preset_id),
            Arc::new(FakeCatalog {
                result: Mutex::new(catalog),
            }),
            Arc::new(FakeValidator {
                result: Mutex::new(validation),
            }),
            providers.clone(),
            models.clone(),
            settings.clone(),
            secrets.clone(),
        );

        Harness {
            service,
            providers,
            models,
            settings,
            secrets,
        }
    }

    fn input(key: &str) -> ConnectInput {
        ConnectInput {
            api_key: SecretValue::new(key),
            label: String::new(),
            remember_key: true,
        }
    }

    #[test]
    fn invalid_key_is_rejected_and_nothing_is_stored() {
        let key = "sk-test-not-a-real-key";
        let harness = harness(
            "openrouter",
            Ok(vec![catalog_model(
                "openrouter/auto",
                false,
                &[OutputModality::Text],
            )]),
            Err(chat_error(
                ChatErrorCode::Auth,
                &format!("request failed for {key}"),
            )),
        );

        let error = harness
            .service
            .connect(input(key))
            .expect_err("invalid key is rejected");

        assert_eq!(error.chat_code(), Some(ChatErrorCode::Auth));
        assert!(harness.secrets.values.lock().unwrap().is_empty());
        assert!(harness.providers.providers.lock().unwrap().is_empty());
        assert!(harness.models.saved.lock().unwrap().is_empty());
        let settings = harness.settings.settings.lock().unwrap();
        assert!(!settings.onboarding_completed);
        assert_eq!(settings.default_provider_id, None);
        assert_eq!(settings.default_model_id, None);
        assert!(!format!("{error:?}").contains(key));
    }

    #[test]
    fn valid_key_stores_secret_and_persists_defaults() {
        let harness = harness(
            "openrouter",
            Ok(vec![
                catalog_model("openrouter/auto", false, &[OutputModality::Text]),
                catalog_model("openrouter/free", false, &[OutputModality::Text]),
            ]),
            Ok(key_info()),
        );

        let result = harness
            .service
            .connect(input("sk-test-not-a-real-key"))
            .expect("valid key connects");

        assert_eq!(result.model_remote_id, "openrouter/auto");
        assert_eq!(result.notice, None);
        assert_eq!(result.catalog_notice, None);
        assert_eq!(result.catalog.len(), 2);

        let secrets = harness.secrets.values.lock().unwrap();
        assert_eq!(secrets.len(), 1);
        let stored = secrets.values().next().expect("stored secret");
        assert_eq!(stored.expose(), "sk-test-not-a-real-key");

        let providers = harness.providers.providers.lock().unwrap();
        assert_eq!(providers.len(), 1);
        let provider = &providers[0];
        assert_eq!(provider.id, result.provider_id);
        assert_eq!(provider.preset_id, "openrouter");
        assert_eq!(provider.label, "Personal");
        assert!(
            provider
                .secret_id
                .as_deref()
                .unwrap()
                .starts_with("provider-")
        );
        assert!(provider.metadata.is_some());
        assert!(
            !provider
                .metadata
                .as_ref()
                .unwrap()
                .to_string()
                .contains("sk-test")
        );

        let settings = harness.settings.settings.lock().unwrap();
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
    fn catalog_failure_uses_cached_models() {
        let harness = harness(
            "openrouter",
            Err(chat_error(ChatErrorCode::Network, "offline")),
            Ok(key_info()),
        );
        harness.models.cached.lock().unwrap().push(model(
            "cached/model",
            false,
            &[OutputModality::Text],
        ));

        let result = harness
            .service
            .connect(input("sk-test-not-a-real-key"))
            .expect("cached models keep onboarding usable");

        assert_eq!(result.model_remote_id, "cached/model");
        assert_eq!(
            result.catalog_notice.as_deref(),
            Some("Discovery unavailable; using 1 cached model.")
        );
        assert_eq!(
            result.notice.as_deref(),
            Some("Default model openrouter/auto is unavailable; using cached/model.")
        );
        assert!(
            harness
                .settings
                .settings
                .lock()
                .unwrap()
                .onboarding_completed
        );
    }

    #[test]
    fn catalog_failure_uses_preset_fallback_models() {
        let harness = harness(
            "openai",
            Err(chat_error(ChatErrorCode::Network, "offline")),
            Ok(key_info()),
        );

        let result = harness
            .service
            .connect(input("sk-test-not-a-real-key"))
            .expect("fallback models keep onboarding usable");

        assert_eq!(result.model_remote_id, "gpt-4o-mini");
        assert_eq!(
            result.catalog_notice.as_deref(),
            Some("Discovery unavailable; using 2 fallback models.")
        );
        assert_eq!(
            result.notice.as_deref(),
            Some("Discovery unavailable; using fallback model gpt-4o-mini.")
        );
        assert!(
            harness
                .settings
                .settings
                .lock()
                .unwrap()
                .onboarding_completed
        );
    }

    #[test]
    fn missing_preset_default_uses_first_text_model() {
        let harness = harness(
            "openrouter",
            Ok(vec![
                catalog_model("zeta/model", false, &[OutputModality::Text]),
                catalog_model("alpha/model", false, &[OutputModality::Text]),
            ]),
            Ok(key_info()),
        );

        let result = harness
            .service
            .connect(input("sk-test-not-a-real-key"))
            .expect("first text model is selected");

        assert_eq!(result.model_remote_id, "alpha/model");
        assert_eq!(
            result.notice.as_deref(),
            Some("Default model openrouter/auto is unavailable; using alpha/model.")
        );
    }

    #[test]
    fn no_text_model_fails_and_rolls_back() {
        let harness = harness(
            "openrouter",
            Ok(vec![
                catalog_model("hidden/text", true, &[OutputModality::Text]),
                catalog_model("image/only", false, &[OutputModality::Image]),
            ]),
            Ok(key_info()),
        );

        let error = harness
            .service
            .connect(input("sk-test-not-a-real-key"))
            .expect_err("no text model is a setup error");

        assert!(matches!(error, OnboardingError::NoModel(_)));
        assert!(harness.secrets.values.lock().unwrap().is_empty());
        assert!(harness.providers.providers.lock().unwrap().is_empty());
        assert!(harness.models.saved.lock().unwrap().is_empty());
        assert!(
            !harness
                .settings
                .settings
                .lock()
                .unwrap()
                .onboarding_completed
        );
    }

    #[test]
    fn load_catalog_works_before_a_key_is_entered() {
        let harness = harness(
            "openrouter",
            Ok(vec![catalog_model(
                "openrouter/auto",
                false,
                &[OutputModality::Text],
            )]),
            Ok(key_info()),
        );

        let load = harness
            .service
            .load_catalog()
            .expect("public catalog loads");

        assert_eq!(load.models.len(), 1);
        assert!(harness.secrets.values.lock().unwrap().is_empty());
        assert!(harness.providers.providers.lock().unwrap().is_empty());
    }

    #[test]
    fn onboarding_completion_is_read_from_settings() {
        let harness = harness("openrouter", Ok(Vec::new()), Ok(key_info()));
        assert!(!harness.service.is_onboarding_complete().unwrap());

        harness
            .settings
            .settings
            .lock()
            .unwrap()
            .onboarding_completed = true;
        assert!(harness.service.is_onboarding_complete().unwrap());
    }

    #[test]
    fn progress_reports_every_state() {
        let harness = harness(
            "openrouter",
            Ok(vec![catalog_model(
                "openrouter/auto",
                false,
                &[OutputModality::Text],
            )]),
            Ok(key_info()),
        );
        let mut steps = Vec::new();

        harness
            .service
            .connect_with_progress(input("sk-test-not-a-real-key"), |step| steps.push(step))
            .expect("valid key connects");

        assert_eq!(
            steps,
            vec![
                OnboardingStep::Validating,
                OnboardingStep::CatalogLoading,
                OnboardingStep::ModelSelection,
                OnboardingStep::Complete,
            ]
        );
    }

    #[test]
    fn connect_input_debug_redacts_the_key() {
        let input = input("sk-test-not-a-real-key");
        let rendered = format!("{input:?}");

        assert!(!rendered.contains("sk-test-not-a-real-key"));
        assert!(rendered.contains("[REDACTED]"));
    }
}
