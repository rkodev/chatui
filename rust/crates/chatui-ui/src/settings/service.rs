//! Settings orchestration and persistence boundaries.
//!
//! [`SettingsService`] is the synchronous surface used by the Slint controller.
//! It loads and saves the complete [`Settings`] object through an injected
//! [`SettingsStore`]. The desktop app can implement that trait with a blocking
//! adapter over [`chatui_storage::SettingsRepository`], or use
//! [`RepositorySettingsPersistence`] during asynchronous startup.
//!
//! Every update validates the value, persists the complete settings object, and
//! returns a fresh view model. Defaults are loaded from the repository when no
//! row exists, so a fresh database and a partially populated database both
//! produce a complete settings value.

use std::sync::{Arc, Mutex};

use async_trait::async_trait;
use chatui_domain::{ChatFontSize, ChatParams, Density, Model, Provider, SendKey, Settings, Theme};
use chatui_storage::{SettingsRepository, default_settings};

use crate::settings::error::SettingsError;
use crate::settings::route::SettingsRoute;
use crate::settings::state::{MAX_SYSTEM_PROMPT_CHARS, SettingsViewModel};

/// Synchronous settings persistence used by the view controller.
pub trait SettingsStore: Send + Sync {
    /// Load the complete settings object, merging defaults.
    fn load(&self) -> Result<Settings, SettingsError>;
    /// Persist the complete settings object.
    fn save(&self, settings: &Settings) -> Result<(), SettingsError>;
}

/// In-memory settings store used by tests and the desktop app's startup cache.
#[derive(Debug)]
pub struct MemorySettingsStore {
    settings: Mutex<Settings>,
}

impl MemorySettingsStore {
    /// Create a store with spec-aligned defaults.
    pub fn new() -> Self {
        Self {
            settings: Mutex::new(default_settings()),
        }
    }

    /// Create a store with explicit settings.
    pub fn with_settings(settings: Settings) -> Self {
        Self {
            settings: Mutex::new(settings),
        }
    }

    /// Return a snapshot of the stored settings.
    pub fn settings(&self) -> Settings {
        self.lock().clone()
    }

    fn lock(&self) -> std::sync::MutexGuard<'_, Settings> {
        self.settings
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
    }
}

impl Default for MemorySettingsStore {
    fn default() -> Self {
        Self::new()
    }
}

impl SettingsStore for MemorySettingsStore {
    fn load(&self) -> Result<Settings, SettingsError> {
        Ok(self.lock().clone())
    }

    fn save(&self, settings: &Settings) -> Result<(), SettingsError> {
        *self.lock() = settings.clone();
        Ok(())
    }
}

/// Asynchronous persistence boundary over the typed settings repository.
#[async_trait]
pub trait SettingsPersistence: Send + Sync {
    /// Load the complete settings object, merging defaults.
    async fn load(&self) -> Result<Settings, SettingsError>;
    /// Persist the complete settings object.
    async fn save(&self, settings: &Settings) -> Result<(), SettingsError>;
}

/// Persists settings through [`chatui_storage::SettingsRepository`].
pub struct RepositorySettingsPersistence {
    settings: Arc<dyn SettingsRepository>,
}

impl RepositorySettingsPersistence {
    /// Create a persistence boundary over the settings repository.
    pub fn new(settings: Arc<dyn SettingsRepository>) -> Self {
        Self { settings }
    }
}

#[async_trait]
impl SettingsPersistence for RepositorySettingsPersistence {
    async fn load(&self) -> Result<Settings, SettingsError> {
        self.settings.load().await.map_err(SettingsError::from)
    }

    async fn save(&self, settings: &Settings) -> Result<(), SettingsError> {
        self.settings
            .save(settings)
            .await
            .map_err(SettingsError::from)
    }
}

/// Provider and model records used by the default pickers.
pub trait ProviderModelCatalog: Send + Sync {
    /// List every provider.
    fn providers(&self) -> Result<Vec<Provider>, SettingsError>;
    /// List models for one provider.
    fn models(&self, provider_id: &str) -> Result<Vec<Model>, SettingsError>;
}

/// In-memory provider/model catalog used by tests and the desktop app.
#[derive(Debug, Default)]
pub struct MemoryProviderModelCatalog {
    providers: Mutex<Vec<Provider>>,
    models: Mutex<Vec<Model>>,
}

impl MemoryProviderModelCatalog {
    /// Create a catalog with explicit records.
    pub fn new(providers: Vec<Provider>, models: Vec<Model>) -> Self {
        Self {
            providers: Mutex::new(providers),
            models: Mutex::new(models),
        }
    }

    /// Add a provider.
    pub fn add_provider(&self, provider: Provider) {
        self.providers
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
            .push(provider);
    }

    /// Add a model.
    pub fn add_model(&self, model: Model) {
        self.models
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
            .push(model);
    }
}

impl ProviderModelCatalog for MemoryProviderModelCatalog {
    fn providers(&self) -> Result<Vec<Provider>, SettingsError> {
        Ok(self
            .providers
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
            .clone())
    }

    fn models(&self, provider_id: &str) -> Result<Vec<Model>, SettingsError> {
        Ok(self
            .models
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
            .iter()
            .filter(|model| model.provider_id == provider_id)
            .cloned()
            .collect())
    }
}

/// Settings orchestration over injected persistence and catalog traits.
pub struct SettingsService {
    store: Arc<dyn SettingsStore>,
    catalog: Arc<dyn ProviderModelCatalog>,
}

impl SettingsService {
    /// Build a service over the injected dependencies.
    pub fn new(store: Arc<dyn SettingsStore>, catalog: Arc<dyn ProviderModelCatalog>) -> Self {
        Self { store, catalog }
    }

    /// Load settings and build the appearance view model.
    pub fn load(&self) -> Result<SettingsViewModel, SettingsError> {
        self.load_with_route(SettingsRoute::Appearance)
    }

    /// Load settings and build a view model for a specific section.
    pub fn load_with_route(
        &self,
        route: SettingsRoute,
    ) -> Result<SettingsViewModel, SettingsError> {
        let settings = self.store.load()?;
        let mut notice = None;

        let providers = match self.catalog.providers() {
            Ok(providers) => providers,
            Err(error) => {
                notice = Some(format!("Provider list unavailable: {}", error.message()));
                Vec::new()
            }
        };

        let models = match settings.default_provider_id.as_deref() {
            Some(provider_id) => match self.catalog.models(provider_id) {
                Ok(models) => models,
                Err(error) => {
                    notice = Some(format!("Model list unavailable: {}", error.message()));
                    Vec::new()
                }
            },
            None => Vec::new(),
        };

        if notice.is_none()
            && let Some(provider_id) = settings.default_provider_id.as_deref()
            && !providers.iter().any(|provider| provider.id == provider_id)
        {
            notice = Some(format!(
                "Default provider {provider_id} is no longer available."
            ));
        }

        if notice.is_none()
            && let Some(model_id) = settings.default_model_id.as_deref()
            && !models.iter().any(|model| model.id == model_id)
        {
            notice = Some(format!("Default model {model_id} is no longer available."));
        }

        let mut view_model = SettingsViewModel::new(settings, providers, models, route);
        view_model.notice = notice;
        Ok(view_model)
    }

    /// Persist the theme preference.
    pub fn set_theme(&self, theme: Theme) -> Result<SettingsViewModel, SettingsError> {
        self.update(|settings| {
            settings.theme = theme;
            Ok(())
        })
    }

    /// Persist the density preference.
    pub fn set_density(&self, density: Density) -> Result<SettingsViewModel, SettingsError> {
        self.update(|settings| {
            settings.density = density;
            Ok(())
        })
    }

    /// Persist the chat font-size preference.
    pub fn set_chat_font_size(
        &self,
        size: ChatFontSize,
    ) -> Result<SettingsViewModel, SettingsError> {
        self.update(|settings| {
            settings.chat_font_size = size;
            Ok(())
        })
    }

    /// Persist the send-key preference.
    pub fn set_send_key(&self, send_key: SendKey) -> Result<SettingsViewModel, SettingsError> {
        self.update(|settings| {
            settings.send_key = send_key;
            Ok(())
        })
    }

    /// Persist token-usage visibility.
    pub fn set_show_token_usage(&self, show: bool) -> Result<SettingsViewModel, SettingsError> {
        self.update(|settings| {
            settings.show_token_usage = show;
            Ok(())
        })
    }

    /// Persist the remote-image preference.
    pub fn set_load_remote_images(&self, load: bool) -> Result<SettingsViewModel, SettingsError> {
        self.update(|settings| {
            settings.load_remote_images = load;
            Ok(())
        })
    }

    /// Persist the default system prompt.
    pub fn set_default_system_prompt(
        &self,
        prompt: &str,
    ) -> Result<SettingsViewModel, SettingsError> {
        if prompt.chars().count() > MAX_SYSTEM_PROMPT_CHARS {
            return Err(SettingsError::InvalidInput(format!(
                "System prompt is too long (maximum {MAX_SYSTEM_PROMPT_CHARS} characters)."
            )));
        }

        self.update(|settings| {
            settings.default_system_prompt = prompt.to_owned();
            Ok(())
        })
    }

    /// Persist the default chat parameters after normalizing them.
    pub fn set_default_params(
        &self,
        params: ChatParams,
    ) -> Result<SettingsViewModel, SettingsError> {
        let params = normalize_params(params);
        self.update(|settings| {
            settings.default_params = params;
            Ok(())
        })
    }

    /// Persist the default provider and model.
    ///
    /// A model requires a provider. A provider without a model means "use the
    /// provider default". Passing `None` for both clears the defaults.
    pub fn set_default_provider_model(
        &self,
        provider_id: Option<&str>,
        model_id: Option<&str>,
    ) -> Result<SettingsViewModel, SettingsError> {
        let providers = self.catalog.providers()?;
        let provider = match provider_id {
            Some(id) => Some(
                providers
                    .iter()
                    .find(|provider| provider.id == id)
                    .cloned()
                    .ok_or_else(|| {
                        SettingsError::NotFound(format!("Provider {id} was not found."))
                    })?,
            ),
            None => None,
        };

        let model = match model_id {
            Some(id) => {
                let provider = provider.as_ref().ok_or_else(|| {
                    SettingsError::InvalidInput(
                        "Choose a provider before choosing a model.".to_owned(),
                    )
                })?;
                let models = self.catalog.models(&provider.id)?;
                Some(
                    models
                        .into_iter()
                        .find(|model| model.id == id)
                        .ok_or_else(|| {
                            SettingsError::NotFound(format!(
                                "Model {id} was not found for this provider."
                            ))
                        })?,
                )
            }
            None => None,
        };

        self.update(|settings| {
            settings.default_provider_id = provider.map(|provider| provider.id);
            settings.default_model_id = model.map(|model| model.id);
            Ok(())
        })
    }

    /// Persist the onboarding completion flag.
    pub fn set_onboarding_completed(
        &self,
        completed: bool,
    ) -> Result<SettingsViewModel, SettingsError> {
        self.update(|settings| {
            settings.onboarding_completed = completed;
            Ok(())
        })
    }

    fn update<F>(&self, update: F) -> Result<SettingsViewModel, SettingsError>
    where
        F: FnOnce(&mut Settings) -> Result<(), SettingsError>,
    {
        let mut settings = self.store.load()?;
        update(&mut settings)?;
        self.store.save(&settings)?;
        self.load()
    }
}

fn normalize_params(mut params: ChatParams) -> ChatParams {
    params.temperature = params.temperature.map(|value| value.clamp(0.0, 2.0));
    params.top_p = params.top_p.map(|value| value.clamp(0.0, 1.0));
    params.max_output_tokens = params.max_output_tokens.map(|value| value.max(1));
    params.stop = params
        .stop
        .into_iter()
        .map(|line| line.trim().to_owned())
        .filter(|line| !line.is_empty())
        .collect();
    params
}

#[cfg(test)]
mod tests {
    use super::*;
    use chatui_domain::{
        BrowserCors, InputModality, ModelCapability, ModelSource, OutputModality, ProviderAuthType,
        ProviderKind, ReasoningEffort, Timestamp,
    };

    fn provider(id: &str, label: &str) -> Provider {
        let now = Timestamp::now();
        Provider {
            id: id.to_owned(),
            preset_id: "custom".to_owned(),
            label: label.to_owned(),
            kind: ProviderKind::OpenaiCompatible,
            base_url: "https://example.com/v1".to_owned(),
            auth_type: ProviderAuthType::Bearer,
            secret_id: None,
            headers: std::collections::BTreeMap::new(),
            enabled: true,
            browser_cors: BrowserCors::Unknown,
            metadata: None,
            created_at: now,
            updated_at: now,
        }
    }

    fn model(id: &str, provider_id: &str, label: &str) -> Model {
        let now = Timestamp::now();
        Model {
            id: id.to_owned(),
            provider_id: provider_id.to_owned(),
            remote_id: format!("remote/{id}"),
            label: label.to_owned(),
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

    fn service() -> (SettingsService, Arc<MemorySettingsStore>) {
        let store = Arc::new(MemorySettingsStore::new());
        let catalog = Arc::new(MemoryProviderModelCatalog::new(
            vec![provider("p1", "One"), provider("p2", "Two")],
            vec![
                model("m1", "p1", "Model One"),
                model("m2", "p2", "Model Two"),
            ],
        ));
        (SettingsService::new(store.clone(), catalog), store)
    }

    #[test]
    fn defaults_load_when_no_rows_exist() {
        let (service, _) = service();
        let view_model = service.load().expect("load defaults");

        assert_eq!(view_model.settings.theme, Theme::System);
        assert_eq!(view_model.settings.density, Density::Comfortable);
        assert_eq!(view_model.settings.chat_font_size, ChatFontSize::Md);
        assert_eq!(view_model.settings.send_key, SendKey::Enter);
        assert!(view_model.settings.show_token_usage);
        assert!(!view_model.settings.load_remote_images);
        assert!(!view_model.settings.onboarding_completed);
        assert_eq!(view_model.providers.len(), 2);
        assert!(view_model.models.is_empty());
    }

    #[test]
    fn appearance_updates_persist() {
        let (service, store) = service();

        service.set_theme(Theme::Dark).expect("theme");
        service.set_density(Density::Compact).expect("density");
        service
            .set_chat_font_size(ChatFontSize::Lg)
            .expect("font size");

        let settings = store.settings();
        assert_eq!(settings.theme, Theme::Dark);
        assert_eq!(settings.density, Density::Compact);
        assert_eq!(settings.chat_font_size, ChatFontSize::Lg);
    }

    #[test]
    fn chat_behavior_updates_persist() {
        let (service, store) = service();

        service.set_send_key(SendKey::ModEnter).expect("send key");
        service.set_show_token_usage(false).expect("token usage");
        service.set_load_remote_images(true).expect("remote images");

        let settings = store.settings();
        assert_eq!(settings.send_key, SendKey::ModEnter);
        assert!(!settings.show_token_usage);
        assert!(settings.load_remote_images);
    }

    #[test]
    fn default_prompt_and_params_persist_and_normalize() {
        let (service, store) = service();

        service
            .set_default_system_prompt("Be concise.")
            .expect("prompt");
        let params = ChatParams {
            temperature: Some(3.0),
            top_p: Some(-1.0),
            max_output_tokens: Some(0),
            stop: vec!["  END  ".to_owned(), String::new()],
            reasoning_effort: Some(ReasoningEffort::High),
            streaming: false,
            extra: serde_json::Map::new(),
        };
        service.set_default_params(params).expect("params");

        let settings = store.settings();
        assert_eq!(settings.default_system_prompt, "Be concise.");
        assert_eq!(settings.default_params.temperature, Some(2.0));
        assert_eq!(settings.default_params.top_p, Some(0.0));
        assert_eq!(settings.default_params.max_output_tokens, Some(1));
        assert_eq!(settings.default_params.stop, vec!["END".to_owned()]);
        assert_eq!(
            settings.default_params.reasoning_effort,
            Some(ReasoningEffort::High)
        );
        assert!(!settings.default_params.streaming);
    }

    #[test]
    fn overlong_system_prompt_is_rejected() {
        let (service, store) = service();
        let prompt = "x".repeat(MAX_SYSTEM_PROMPT_CHARS + 1);

        let error = service
            .set_default_system_prompt(&prompt)
            .expect_err("prompt too long");

        assert!(matches!(error, SettingsError::InvalidInput(_)));
        assert_eq!(store.settings().default_system_prompt, "");
    }

    #[test]
    fn provider_and_model_defaults_persist_and_validate() {
        let (service, store) = service();

        service
            .set_default_provider_model(Some("p1"), Some("m1"))
            .expect("set defaults");
        let settings = store.settings();
        assert_eq!(settings.default_provider_id.as_deref(), Some("p1"));
        assert_eq!(settings.default_model_id.as_deref(), Some("m1"));

        service
            .set_default_provider_model(Some("p2"), None)
            .expect("provider default");
        let settings = store.settings();
        assert_eq!(settings.default_provider_id.as_deref(), Some("p2"));
        assert_eq!(settings.default_model_id, None);

        service
            .set_default_provider_model(None, None)
            .expect("clear defaults");
        let settings = store.settings();
        assert_eq!(settings.default_provider_id, None);
        assert_eq!(settings.default_model_id, None);

        let missing = service
            .set_default_provider_model(Some("missing"), None)
            .expect_err("missing provider");
        assert!(matches!(missing, SettingsError::NotFound(_)));

        let no_provider = service
            .set_default_provider_model(None, Some("m1"))
            .expect_err("model without provider");
        assert!(matches!(no_provider, SettingsError::InvalidInput(_)));

        let wrong_provider = service
            .set_default_provider_model(Some("p1"), Some("m2"))
            .expect_err("model belongs to another provider");
        assert!(matches!(wrong_provider, SettingsError::NotFound(_)));
    }

    #[test]
    fn onboarding_completion_persists() {
        let (service, store) = service();

        let view_model = service
            .set_onboarding_completed(true)
            .expect("onboarding complete");

        assert!(store.settings().onboarding_completed);
        assert!(view_model.onboarding_completed());
        assert_eq!(view_model.onboarding_status(), "Onboarding complete");
    }

    #[test]
    fn catalog_failure_is_a_non_blocking_notice() {
        struct FailingCatalog;

        impl ProviderModelCatalog for FailingCatalog {
            fn providers(&self) -> Result<Vec<Provider>, SettingsError> {
                Err(SettingsError::Storage("catalog offline".to_owned()))
            }

            fn models(&self, _provider_id: &str) -> Result<Vec<Model>, SettingsError> {
                Err(SettingsError::Storage("catalog offline".to_owned()))
            }
        }

        let store = Arc::new(MemorySettingsStore::new());
        let service = SettingsService::new(store, Arc::new(FailingCatalog));
        let view_model = service.load().expect("settings still load");

        assert!(view_model.providers.is_empty());
        assert!(
            view_model
                .notice
                .as_deref()
                .unwrap()
                .contains("Provider list unavailable")
        );
    }

    #[test]
    fn stale_defaults_are_reported_as_a_notice() {
        let store = Arc::new(MemorySettingsStore::new());
        let mut settings = store.settings();
        settings.default_provider_id = Some("missing".to_owned());
        settings.default_model_id = Some("missing-model".to_owned());
        store.save(&settings).expect("save stale defaults");

        let catalog = Arc::new(MemoryProviderModelCatalog::new(
            vec![provider("p1", "One")],
            vec![model("m1", "p1", "Model One")],
        ));
        let service = SettingsService::new(store, catalog);
        let view_model = service.load().expect("load");

        assert!(
            view_model
                .notice
                .as_deref()
                .unwrap()
                .contains("Default provider missing")
        );
    }

    #[test]
    fn settings_persist_across_service_instances() {
        let store = Arc::new(MemorySettingsStore::new());
        let catalog = Arc::new(MemoryProviderModelCatalog::default());
        let first = SettingsService::new(store.clone(), catalog.clone());
        first.set_theme(Theme::Dark).expect("theme");
        first.set_send_key(SendKey::ModEnter).expect("send key");

        let second = SettingsService::new(store, catalog);
        let view_model = second.load().expect("reload");

        assert_eq!(view_model.settings.theme, Theme::Dark);
        assert_eq!(view_model.settings.send_key, SendKey::ModEnter);
    }
}
