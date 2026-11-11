//! Provider management orchestration.
//!
//! The service is injected with small traits for provider, model, conversation,
//! and settings persistence, the secret store, and key validation. It never
//! talks to SQLite or the network directly, so the view model and tests can use
//! fakes.
//!
//! Key lifecycle rules:
//!
//! * A provider record stores only a `secret_id`; the key lives in the secret
//!   store.
//! * Rotation validates the candidate key before replacing the old one. A
//!   failed validation leaves the old key untouched.
//! * Forget-key and forget-all require explicit confirmation.
//! * Deleting a provider removes its models and secret, clears the active
//!   provider/model selection on conversations, and keeps historical model
//!   labels.

#![allow(clippy::result_large_err)]

use std::sync::Arc;

use chatui_contracts::provider_catalog;
use chatui_domain::{
    BrowserCors, ChatError, Conversation, Model, Provider, ProviderKind, Settings, Timestamp,
    new_id,
};
use chatui_providers::{CLEARTEXT_WARNING, ProviderKeyInfo, classify_url, join_url};
use chatui_secrets::{SecretId, SecretStore, SecretValue};

use crate::providers::error::ProviderManagementError;
use crate::providers::state::{
    CustomProviderForm, ProviderDetailViewModel, ProviderKeyMetadata, ProviderListEntry,
    ProviderListViewModel,
};
use crate::providers::validation::{
    validate_api_key, validate_auth_type, validate_base_url, validate_headers,
};

/// Provider record persistence.
pub trait ProviderStore: Send + Sync {
    /// List every provider.
    fn list(&self) -> Result<Vec<Provider>, ProviderManagementError>;
    /// Fetch one provider.
    fn get(&self, id: &str) -> Result<Option<Provider>, ProviderManagementError>;
    /// Insert or replace a provider.
    fn save_provider(&self, provider: &Provider) -> Result<(), ProviderManagementError>;
    /// Delete a provider record.
    fn delete(&self, id: &str) -> Result<(), ProviderManagementError>;
}

/// Model persistence.
pub trait ModelStore: Send + Sync {
    /// List models for one provider.
    fn list_by_provider(&self, provider_id: &str) -> Result<Vec<Model>, ProviderManagementError>;
    /// Delete every model for one provider.
    fn delete_by_provider(&self, provider_id: &str) -> Result<(), ProviderManagementError>;
}

/// Conversation persistence used by the deletion cascade.
pub trait ConversationStore: Send + Sync {
    /// List every conversation.
    fn list_all(&self) -> Result<Vec<Conversation>, ProviderManagementError>;
    /// Persist a conversation.
    fn update(&self, conversation: &Conversation) -> Result<(), ProviderManagementError>;
}

/// Settings persistence.
pub trait SettingsStore: Send + Sync {
    /// Load the complete settings object.
    fn load(&self) -> Result<Settings, ProviderManagementError>;
    /// Persist the complete settings object.
    fn save(&self, settings: &Settings) -> Result<(), ProviderManagementError>;
}

/// Key validation source.
///
/// Implementations call the provider's validation endpoint (for OpenRouter,
/// `GET /key`) and never a paid completion request. The key is passed only to
/// the validator and is never stored on the provider record.
pub trait ProviderKeyValidator: Send + Sync {
    /// Validate a candidate key for a provider and return non-secret metadata.
    fn validate_key(
        &self,
        provider: &Provider,
        key: &SecretValue,
    ) -> Result<ProviderKeyInfo, ChatError>;
}

/// Provider management service over injected traits.
pub struct ProviderManagementService {
    providers: Arc<dyn ProviderStore>,
    models: Arc<dyn ModelStore>,
    conversations: Arc<dyn ConversationStore>,
    settings: Arc<dyn SettingsStore>,
    secrets: Arc<dyn SecretStore>,
    validator: Arc<dyn ProviderKeyValidator>,
    allow_custom_providers: bool,
}

impl ProviderManagementService {
    /// Build a service over the injected dependencies.
    pub fn new(
        providers: Arc<dyn ProviderStore>,
        models: Arc<dyn ModelStore>,
        conversations: Arc<dyn ConversationStore>,
        settings: Arc<dyn SettingsStore>,
        secrets: Arc<dyn SecretStore>,
        validator: Arc<dyn ProviderKeyValidator>,
    ) -> Self {
        Self {
            providers,
            models,
            conversations,
            settings,
            secrets,
            validator,
            allow_custom_providers: true,
        }
    }

    /// Enable or disable the custom-provider entry point.
    pub fn with_custom_providers_allowed(mut self, allowed: bool) -> Self {
        self.allow_custom_providers = allowed;
        self
    }

    /// Build the provider list view model.
    ///
    /// Key presence is checked against the secret store; the key itself is
    /// never read into the view model.
    pub fn list_providers(&self) -> Result<ProviderListViewModel, ProviderManagementError> {
        let providers = self.providers.list()?;
        let entries = providers
            .iter()
            .map(|provider| self.list_entry(provider))
            .collect();

        Ok(ProviderListViewModel::new(
            entries,
            self.allow_custom_providers,
        ))
    }

    /// Build the provider detail view model.
    ///
    /// Uses the non-secret `/key` metadata already stored on the provider
    /// record. It never fetches or displays the key.
    pub fn provider_detail(
        &self,
        id: &str,
    ) -> Result<ProviderDetailViewModel, ProviderManagementError> {
        let provider = self.require_provider(id)?;
        let preset = provider_catalog().get(&provider.preset_id);
        let models = self.models.list_by_provider(id)?;

        Ok(ProviderDetailViewModel {
            id: provider.id.clone(),
            label: provider.label.clone(),
            preset_id: provider.preset_id.clone(),
            preset_label: preset
                .map(|preset| preset.label.clone())
                .unwrap_or_else(|| provider.preset_id.clone()),
            base_url: provider.base_url.clone(),
            auth_type: provider.auth_type,
            enabled: provider.enabled,
            recommended: preset
                .is_some_and(|preset| preset.is_default || preset.id == "openrouter"),
            key_present: self.key_present(&provider),
            key_metadata: ProviderKeyMetadata::from_provider(&provider),
            docs_url: preset.and_then(|preset| preset.docs_url.clone()),
            browser_cors: provider.browser_cors,
            headers: provider.headers.clone(),
            cleartext_warning: cleartext_warning_for(&provider.base_url),
            model_count: models.len(),
        })
    }

    /// Create a custom OpenAI-compatible provider.
    ///
    /// The base URL, auth style, headers, and key are validated before any
    /// write. When `test_connection` is set, the key is validated before the
    /// provider is saved. A failed provider write removes the newly written
    /// secret.
    pub fn create_custom_provider(
        &self,
        form: &CustomProviderForm,
    ) -> Result<Provider, ProviderManagementError> {
        if !self.allow_custom_providers {
            return Err(ProviderManagementError::InvalidInput(
                "Custom providers are not enabled in this deployment.".to_owned(),
            ));
        }

        let validated = validate_base_url(&form.base_url)?;
        let auth_type = validate_auth_type(form.auth_type)?;
        let key = validate_api_key(auth_type, &form.api_key, true)?;
        let headers = validate_headers(&form.headers, auth_type)?;
        let label = self.resolve_label(&form.label, "Custom OpenAI-compatible", None)?;
        let id = new_id();
        let now = Timestamp::now();

        let mut provider = Provider {
            id: id.clone(),
            preset_id: "custom".to_owned(),
            label,
            kind: ProviderKind::OpenaiCompatible,
            base_url: validated.url,
            auth_type,
            secret_id: None,
            headers,
            enabled: true,
            browser_cors: BrowserCors::Unknown,
            metadata: None,
            created_at: now,
            updated_at: now,
        };

        let mut key_info = None;
        if let Some(key) = key.as_ref()
            && form.test_connection
        {
            let info = self
                .validator
                .validate_key(&provider, key)
                .map_err(|error| {
                    ProviderManagementError::Chat(redact_chat_error(error, key.expose()))
                })?;
            key_info = Some(info);
        }

        let secret_id = if let Some(key) = key.as_ref() {
            let secret_id = SecretId::new(format!("provider-{id}"))
                .map_err(|error| ProviderManagementError::Secret(error.to_string()))?;
            self.secrets.set(&secret_id, key)?;
            provider.secret_id = Some(secret_id.as_str().to_owned());
            Some(secret_id)
        } else {
            None
        };

        if let Some(info) = key_info {
            provider.metadata = Some(key_metadata_value(&info, now));
        }

        if let Err(error) = self.providers.save_provider(&provider) {
            if let Some(secret_id) = secret_id {
                let _ = self.secrets.delete(&secret_id);
            }
            return Err(error);
        }

        Ok(provider)
    }

    /// Edit an existing custom provider.
    ///
    /// An empty key field keeps the existing key. A supplied key is validated
    /// before it replaces the old one, so a failed validation preserves the old
    /// key. Switching to the `none` auth style forgets the stored key.
    pub fn update_custom_provider(
        &self,
        id: &str,
        form: &CustomProviderForm,
    ) -> Result<Provider, ProviderManagementError> {
        let mut provider = self.require_provider(id)?;

        if provider.preset_id != "custom" {
            return Err(ProviderManagementError::InvalidInput(
                "Only custom providers can be edited here.".to_owned(),
            ));
        }

        let validated = validate_base_url(&form.base_url)?;
        let auth_type = validate_auth_type(form.auth_type)?;
        let key = validate_api_key(auth_type, &form.api_key, false)?;
        let headers = validate_headers(&form.headers, auth_type)?;
        let label = self.resolve_label(&form.label, &provider.label, Some(id))?;

        if key.is_none()
            && auth_type != chatui_domain::ProviderAuthType::None
            && provider.secret_id.is_none()
        {
            return Err(ProviderManagementError::InvalidInput(
                "Enter an API key for the selected authentication style.".to_owned(),
            ));
        }

        provider.label = label;
        provider.base_url = validated.url;
        provider.auth_type = auth_type;
        provider.headers = headers;
        provider.updated_at = Timestamp::now();

        if auth_type == chatui_domain::ProviderAuthType::None {
            if let Some(old_secret_id) = provider.secret_id.take()
                && let Ok(old_secret_id) = SecretId::new(old_secret_id)
            {
                self.secrets.forget_key(&old_secret_id)?;
            }
            provider.metadata = without_key_metadata(provider.metadata);
            self.providers.save_provider(&provider)?;
            return Ok(provider);
        }

        let Some(key) = key else {
            self.providers.save_provider(&provider)?;
            return Ok(provider);
        };

        let mut key_info = None;
        if form.test_connection {
            let info = self
                .validator
                .validate_key(&provider, &key)
                .map_err(|error| {
                    ProviderManagementError::Chat(redact_chat_error(error, key.expose()))
                })?;
            key_info = Some(info);
        }

        let new_secret_id = SecretId::new(format!("provider-{id}-{}", new_id()))
            .map_err(|error| ProviderManagementError::Secret(error.to_string()))?;
        self.secrets.set(&new_secret_id, &key)?;
        let old_secret_id = provider
            .secret_id
            .replace(new_secret_id.as_str().to_owned());

        if let Some(info) = key_info {
            provider.metadata = Some(key_metadata_value(&info, provider.updated_at));
        } else {
            provider.metadata = without_key_metadata(provider.metadata);
        }

        if let Err(error) = self.providers.save_provider(&provider) {
            let _ = self.secrets.delete(&new_secret_id);
            return Err(error);
        }

        if let Some(old_secret_id) = old_secret_id
            && let Ok(old_secret_id) = SecretId::new(old_secret_id)
        {
            let _ = self.secrets.delete(&old_secret_id);
        }

        Ok(provider)
    }

    /// Enable or disable a provider.
    pub fn set_enabled(
        &self,
        id: &str,
        enabled: bool,
    ) -> Result<Provider, ProviderManagementError> {
        let mut provider = self.require_provider(id)?;
        provider.enabled = enabled;
        provider.updated_at = Timestamp::now();
        self.providers.save_provider(&provider)?;
        Ok(provider)
    }

    /// Rotate a provider key.
    ///
    /// The candidate key is validated before the old key is replaced. A failed
    /// validation returns an error and leaves the old key in place.
    pub fn rotate_key(
        &self,
        id: &str,
        new_key: &SecretValue,
    ) -> Result<Provider, ProviderManagementError> {
        let mut provider = self.require_provider(id)?;
        let trimmed = new_key.expose().trim();

        if trimmed.is_empty() {
            return Err(ProviderManagementError::InvalidInput(
                "Enter a new API key before rotating.".to_owned(),
            ));
        }

        let key = SecretValue::new(trimmed);
        let info = self
            .validator
            .validate_key(&provider, &key)
            .map_err(|error| {
                ProviderManagementError::Chat(redact_chat_error(error, key.expose()))
            })?;

        let new_secret_id = SecretId::new(format!("provider-{id}-{}", new_id()))
            .map_err(|error| ProviderManagementError::Secret(error.to_string()))?;
        self.secrets.set(&new_secret_id, &key)?;

        let old_secret_id = provider
            .secret_id
            .replace(new_secret_id.as_str().to_owned());
        provider.metadata = Some(key_metadata_value(&info, Timestamp::now()));
        provider.updated_at = Timestamp::now();

        if let Err(error) = self.providers.save_provider(&provider) {
            let _ = self.secrets.delete(&new_secret_id);
            return Err(error);
        }

        if let Some(old_secret_id) = old_secret_id
            && let Ok(old_secret_id) = SecretId::new(old_secret_id)
        {
            let _ = self.secrets.delete(&old_secret_id);
        }

        Ok(provider)
    }

    /// Forget one provider key after explicit confirmation.
    pub fn forget_key(
        &self,
        id: &str,
        confirmed: bool,
    ) -> Result<Provider, ProviderManagementError> {
        if !confirmed {
            return Err(ProviderManagementError::ConfirmationRequired(
                "Confirm that you want to forget this key.".to_owned(),
            ));
        }

        let mut provider = self.require_provider(id)?;

        if let Some(secret_id) = provider.secret_id.take()
            && let Ok(secret_id) = SecretId::new(secret_id)
        {
            self.secrets.forget_key(&secret_id)?;
        }

        provider.metadata = without_key_metadata(provider.metadata);
        provider.updated_at = Timestamp::now();
        self.providers.save_provider(&provider)?;

        Ok(provider)
    }

    /// Forget every stored key after explicit confirmation.
    ///
    /// Returns the number of provider records whose key state was cleared.
    pub fn forget_all_keys(&self, confirmed: bool) -> Result<usize, ProviderManagementError> {
        if !confirmed {
            return Err(ProviderManagementError::ConfirmationRequired(
                "Confirm that you want to forget every stored key.".to_owned(),
            ));
        }

        self.secrets.forget_all_keys()?;

        let providers = self.providers.list()?;
        let mut cleared = 0;

        for mut provider in providers {
            let has_key_metadata = provider
                .metadata
                .as_ref()
                .and_then(|metadata| metadata.get("key"))
                .is_some();

            if provider.secret_id.is_some() || has_key_metadata {
                provider.secret_id = None;
                provider.metadata = without_key_metadata(provider.metadata);
                provider.updated_at = Timestamp::now();
                self.providers.save_provider(&provider)?;
                cleared += 1;
            }
        }

        Ok(cleared)
    }

    /// Delete a provider and its dependent records after explicit confirmation.
    ///
    /// Removes the provider's models and secret, clears the active
    /// provider/model selection on conversations while keeping their historical
    /// model labels, and clears matching global defaults.
    pub fn delete_provider(
        &self,
        id: &str,
        confirmed: bool,
    ) -> Result<(), ProviderManagementError> {
        if !confirmed {
            return Err(ProviderManagementError::ConfirmationRequired(
                "Confirm that you want to delete this provider and its models.".to_owned(),
            ));
        }

        let Some(provider) = self.providers.get(id)? else {
            return Ok(());
        };

        self.models.delete_by_provider(id)?;

        if let Some(secret_id) = provider.secret_id.as_deref()
            && let Ok(secret_id) = SecretId::new(secret_id)
        {
            self.secrets.forget_key(&secret_id)?;
        }

        let conversations = self.conversations.list_all()?;
        for mut conversation in conversations {
            if conversation.provider_id == id {
                conversation.provider_id = String::new();
                conversation.model_id = String::new();
                conversation.updated_at = Timestamp::now();
                self.conversations.update(&conversation)?;
            }
        }

        let mut settings = self.settings.load()?;
        if settings.default_provider_id.as_deref() == Some(id) {
            settings.default_provider_id = None;
            settings.default_model_id = None;
            self.settings.save(&settings)?;
        }

        self.providers.delete(id)?;

        Ok(())
    }

    /// Validate the stored key and refresh the cached `/key` metadata.
    pub fn test_connection(&self, id: &str) -> Result<Provider, ProviderManagementError> {
        let mut provider = self.require_provider(id)?;
        let secret_id = provider.secret_id.as_deref().ok_or_else(|| {
            ProviderManagementError::MissingKey(
                "This provider has no stored key. Add a key before testing the connection."
                    .to_owned(),
            )
        })?;
        let secret_id = SecretId::new(secret_id)
            .map_err(|error| ProviderManagementError::Secret(error.to_string()))?;
        let key = self.secrets.get(&secret_id)?.ok_or_else(|| {
            ProviderManagementError::MissingKey(
                "The stored key is unavailable. Re-enter the key to continue.".to_owned(),
            )
        })?;

        let info = self
            .validator
            .validate_key(&provider, &key)
            .map_err(|error| {
                ProviderManagementError::Chat(redact_chat_error(error, key.expose()))
            })?;

        provider.metadata = Some(key_metadata_value(&info, Timestamp::now()));
        provider.updated_at = Timestamp::now();
        self.providers.save_provider(&provider)?;

        Ok(provider)
    }

    fn list_entry(&self, provider: &Provider) -> ProviderListEntry {
        let preset = provider_catalog().get(&provider.preset_id);

        ProviderListEntry {
            id: provider.id.clone(),
            label: provider.label.clone(),
            preset_id: provider.preset_id.clone(),
            preset_label: preset
                .map(|preset| preset.label.clone())
                .unwrap_or_else(|| provider.preset_id.clone()),
            enabled: provider.enabled,
            recommended: preset
                .is_some_and(|preset| preset.is_default || preset.id == "openrouter"),
            key_present: self.key_present(provider),
            browser_cors: provider.browser_cors,
            docs_url: preset.and_then(|preset| preset.docs_url.clone()),
        }
    }

    fn key_present(&self, provider: &Provider) -> bool {
        let Some(secret_id) = provider.secret_id.as_deref() else {
            return false;
        };
        let Ok(secret_id) = SecretId::new(secret_id) else {
            return false;
        };

        self.secrets.get(&secret_id).ok().flatten().is_some()
    }

    fn require_provider(&self, id: &str) -> Result<Provider, ProviderManagementError> {
        self.providers.get(id)?.ok_or_else(|| {
            ProviderManagementError::NotFound(format!("Provider {id} was not found."))
        })
    }

    fn resolve_label(
        &self,
        input: &str,
        fallback: &str,
        exclude_id: Option<&str>,
    ) -> Result<String, ProviderManagementError> {
        let trimmed = input.trim();
        let label = if trimmed.is_empty() {
            fallback.to_owned()
        } else {
            trimmed.to_owned()
        };

        let existing = self.providers.list()?;
        let duplicate = existing.iter().any(|provider| {
            let is_excluded = exclude_id.is_some_and(|exclude| provider.id == exclude);
            !is_excluded && provider.label.trim().eq_ignore_ascii_case(&label)
        });

        if duplicate {
            return Err(ProviderManagementError::InvalidInput(format!(
                "A provider named \"{label}\" already exists. Choose a different label."
            )));
        }

        Ok(label)
    }
}

fn key_metadata_value(info: &ProviderKeyInfo, now: Timestamp) -> serde_json::Value {
    serde_json::json!({
        "key": info,
        "validatedAt": now.to_rfc3339(),
    })
}

fn without_key_metadata(metadata: Option<serde_json::Value>) -> Option<serde_json::Value> {
    let mut object = metadata?.as_object()?.clone();
    object.remove("key");
    object.remove("validatedAt");

    if object.is_empty() {
        None
    } else {
        Some(serde_json::Value::Object(object))
    }
}

fn cleartext_warning_for(base_url: &str) -> Option<String> {
    let url = join_url(base_url, "").ok()?;

    if url.scheme() != "http" {
        return None;
    }

    classify_url(&url)
        .ok()
        .and_then(|policy| policy.warning().map(str::to_owned))
        .or_else(|| Some(CLEARTEXT_WARNING.to_owned()))
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

#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::{BTreeMap, HashMap};
    use std::sync::Mutex;

    use chatui_domain::{
        ChatErrorCode, ChatFontSize, ChatParams, Density, InputModality, ModelCapability,
        ModelSource, OutputModality, ProviderAuthType, SendKey, Theme,
    };
    use chatui_secrets::SecretError;

    use crate::providers::state::CustomProviderForm;

    const FAKE_KEY: &str = "sk-test-not-a-real-key";
    const OTHER_FAKE_KEY: &str = "sk-test-other-not-a-real-key";

    #[derive(Default)]
    struct FakeProviders {
        providers: Mutex<Vec<Provider>>,
    }

    impl ProviderStore for FakeProviders {
        fn list(&self) -> Result<Vec<Provider>, ProviderManagementError> {
            Ok(self.providers.lock().expect("providers lock").clone())
        }

        fn get(&self, id: &str) -> Result<Option<Provider>, ProviderManagementError> {
            Ok(self
                .providers
                .lock()
                .expect("providers lock")
                .iter()
                .find(|provider| provider.id == id)
                .cloned())
        }

        fn save_provider(&self, provider: &Provider) -> Result<(), ProviderManagementError> {
            let mut providers = self.providers.lock().expect("providers lock");
            if let Some(existing) = providers.iter_mut().find(|item| item.id == provider.id) {
                *existing = provider.clone();
            } else {
                providers.push(provider.clone());
            }
            Ok(())
        }

        fn delete(&self, id: &str) -> Result<(), ProviderManagementError> {
            self.providers
                .lock()
                .expect("providers lock")
                .retain(|provider| provider.id != id);
            Ok(())
        }
    }

    #[derive(Default)]
    struct FakeModels {
        models: Mutex<Vec<Model>>,
    }

    impl ModelStore for FakeModels {
        fn list_by_provider(
            &self,
            provider_id: &str,
        ) -> Result<Vec<Model>, ProviderManagementError> {
            Ok(self
                .models
                .lock()
                .expect("models lock")
                .iter()
                .filter(|model| model.provider_id == provider_id)
                .cloned()
                .collect())
        }

        fn delete_by_provider(&self, provider_id: &str) -> Result<(), ProviderManagementError> {
            self.models
                .lock()
                .expect("models lock")
                .retain(|model| model.provider_id != provider_id);
            Ok(())
        }
    }

    #[derive(Default)]
    struct FakeConversations {
        conversations: Mutex<Vec<Conversation>>,
    }

    impl ConversationStore for FakeConversations {
        fn list_all(&self) -> Result<Vec<Conversation>, ProviderManagementError> {
            Ok(self
                .conversations
                .lock()
                .expect("conversations lock")
                .clone())
        }

        fn update(&self, conversation: &Conversation) -> Result<(), ProviderManagementError> {
            let mut conversations = self.conversations.lock().expect("conversations lock");
            if let Some(existing) = conversations
                .iter_mut()
                .find(|item| item.id == conversation.id)
            {
                *existing = conversation.clone();
            }
            Ok(())
        }
    }

    struct FakeSettings {
        settings: Mutex<Settings>,
    }

    impl SettingsStore for FakeSettings {
        fn load(&self) -> Result<Settings, ProviderManagementError> {
            Ok(self.settings.lock().expect("settings lock").clone())
        }

        fn save(&self, settings: &Settings) -> Result<(), ProviderManagementError> {
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

    struct FakeValidator {
        result: Mutex<Result<ProviderKeyInfo, ChatError>>,
        calls: Mutex<usize>,
    }

    impl ProviderKeyValidator for FakeValidator {
        fn validate_key(
            &self,
            _provider: &Provider,
            _key: &SecretValue,
        ) -> Result<ProviderKeyInfo, ChatError> {
            *self.calls.lock().expect("calls lock") += 1;
            self.result.lock().expect("validator lock").clone()
        }
    }

    struct Harness {
        service: ProviderManagementService,
        providers: Arc<FakeProviders>,
        models: Arc<FakeModels>,
        conversations: Arc<FakeConversations>,
        settings: Arc<FakeSettings>,
        secrets: Arc<FakeSecrets>,
        validator: Arc<FakeValidator>,
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

    fn key_info() -> ProviderKeyInfo {
        ProviderKeyInfo {
            label: Some("Work key".to_owned()),
            usage_usd: Some(1.25),
            limit_usd: Some(10.0),
            limit_remaining_usd: Some(8.75),
            is_free_tier: Some(false),
            rate_limit: Some(serde_json::json!({ "requests": 60, "interval": "minute" })),
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

    fn harness(validation: Result<ProviderKeyInfo, ChatError>) -> Harness {
        let providers = Arc::new(FakeProviders::default());
        let models = Arc::new(FakeModels::default());
        let conversations = Arc::new(FakeConversations::default());
        let settings = Arc::new(FakeSettings {
            settings: Mutex::new(default_settings()),
        });
        let secrets = Arc::new(FakeSecrets::default());
        let validator = Arc::new(FakeValidator {
            result: Mutex::new(validation),
            calls: Mutex::new(0),
        });
        let service = ProviderManagementService::new(
            providers.clone(),
            models.clone(),
            conversations.clone(),
            settings.clone(),
            secrets.clone(),
            validator.clone(),
        );

        Harness {
            service,
            providers,
            models,
            conversations,
            settings,
            secrets,
            validator,
        }
    }

    fn openrouter_provider(secret_id: Option<&str>) -> Provider {
        let now = Timestamp::now();
        Provider {
            id: "provider-1".to_owned(),
            preset_id: "openrouter".to_owned(),
            label: "Personal".to_owned(),
            kind: ProviderKind::OpenaiCompatible,
            base_url: "https://openrouter.ai/api/v1".to_owned(),
            auth_type: ProviderAuthType::Bearer,
            secret_id: secret_id.map(str::to_owned),
            headers: BTreeMap::new(),
            enabled: true,
            browser_cors: BrowserCors::Supported,
            metadata: Some(serde_json::json!({
                "key": {
                    "label": "Work key",
                    "usageUsd": 1.25,
                    "limitUsd": 10.0,
                    "limitRemainingUsd": 8.75,
                    "isFreeTier": false,
                    "rateLimit": { "requests": 60, "interval": "minute" }
                },
                "validatedAt": "2026-11-07T00:00:00.000Z"
            })),
            created_at: now,
            updated_at: now,
        }
    }

    fn model(provider_id: &str, remote_id: &str) -> Model {
        let now = Timestamp::now();
        Model {
            id: format!("model-{remote_id}"),
            provider_id: provider_id.to_owned(),
            remote_id: remote_id.to_owned(),
            label: remote_id.to_owned(),
            context_window: Some(128_000),
            max_output_tokens: Some(4096),
            capabilities: vec![ModelCapability::Text],
            pricing: None,
            canonical_slug: None,
            input_modalities: vec![InputModality::Text],
            output_modalities: vec![OutputModality::Text],
            supported_parameters: Vec::new(),
            metadata: None,
            hidden: false,
            pinned: false,
            source: ModelSource::Discovered,
            created_at: now,
            updated_at: now,
        }
    }

    fn conversation(provider_id: &str, model_id: &str, model_label: &str) -> Conversation {
        let now = Timestamp::now();
        Conversation {
            id: "conversation-1".to_owned(),
            title: "Chat".to_owned(),
            system_prompt: String::new(),
            provider_id: provider_id.to_owned(),
            model_id: model_id.to_owned(),
            model_label: model_label.to_owned(),
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

    fn custom_form(base_url: &str, key: &str) -> CustomProviderForm {
        let mut form = CustomProviderForm::new();
        form.label = "Local".to_owned();
        form.base_url = base_url.to_owned();
        form.auth_type = ProviderAuthType::Bearer;
        form.set_api_key(key);
        form.test_connection = true;
        form
    }

    fn seed_provider(harness: &Harness, provider: Provider, key: Option<&str>) {
        if let (Some(secret_id), Some(key)) = (provider.secret_id.as_deref(), key) {
            let secret_id = SecretId::new(secret_id).expect("valid secret ID");
            harness
                .secrets
                .set(&secret_id, &SecretValue::new(key))
                .expect("store secret");
        }
        harness
            .providers
            .save_provider(&provider)
            .expect("save provider");
    }

    #[test]
    fn list_shows_enabled_state_recommended_badge_and_key_presence() {
        let harness = harness(Ok(key_info()));
        seed_provider(
            &harness,
            openrouter_provider(Some("provider-1")),
            Some(FAKE_KEY),
        );

        let list = harness.service.list_providers().expect("list providers");

        assert_eq!(list.entries().len(), 1);
        let entry = &list.entries()[0];
        assert!(entry.enabled);
        assert!(entry.recommended);
        assert!(entry.key_present);
        assert_eq!(entry.preset_label, "OpenRouter");
        assert_eq!(
            entry.docs_url.as_deref(),
            Some("https://openrouter.ai/docs")
        );
        assert!(list.add_provider_available());
        assert_eq!(list.key_present_count(), 1);
        assert!(!format!("{list:?}").contains(FAKE_KEY));
    }

    #[test]
    fn list_reports_missing_key_without_reading_it() {
        let harness = harness(Ok(key_info()));
        seed_provider(&harness, openrouter_provider(Some("provider-1")), None);

        let list = harness.service.list_providers().expect("list providers");

        assert!(!list.entries()[0].key_present);
        assert_eq!(list.entries()[0].key_label(), "No key");
    }

    #[test]
    fn detail_shows_openrouter_metadata_and_contract_docs_link() {
        let harness = harness(Ok(key_info()));
        seed_provider(
            &harness,
            openrouter_provider(Some("provider-1")),
            Some(FAKE_KEY),
        );
        harness
            .models
            .models
            .lock()
            .unwrap()
            .push(model("provider-1", "openrouter/auto"));

        let detail = harness
            .service
            .provider_detail("provider-1")
            .expect("provider detail");

        assert_eq!(detail.key_label(), "Work key");
        assert_eq!(detail.usage_display(), "$1.25");
        assert_eq!(detail.limit_display(), "$10");
        assert_eq!(detail.free_tier_display(), "No");
        assert_eq!(
            detail.rate_limit_display().as_deref(),
            Some("60 requests per minute")
        );
        assert_eq!(
            detail.docs_url.as_deref(),
            Some("https://openrouter.ai/docs")
        );
        assert_eq!(detail.cors_display(), "Supported");
        assert_eq!(detail.model_count, 1);
        assert!(detail.key_present);
        assert!(!format!("{detail:?}").contains(FAKE_KEY));
    }

    #[test]
    fn detail_shows_non_secret_custom_headers() {
        let harness = harness(Ok(key_info()));
        let mut provider = openrouter_provider(None);
        provider
            .headers
            .insert("X-Team".to_owned(), "chat".to_owned());
        seed_provider(&harness, provider, None);

        let detail = harness
            .service
            .provider_detail("provider-1")
            .expect("provider detail");

        assert_eq!(detail.headers_display(), "X-Team: chat");
    }

    #[test]
    fn create_custom_provider_stores_secret_and_metadata() {
        let harness = harness(Ok(key_info()));
        let form = custom_form("https://api.example.com/v1", FAKE_KEY);

        let provider = harness
            .service
            .create_custom_provider(&form)
            .expect("create custom provider");

        assert_eq!(provider.preset_id, "custom");
        assert_eq!(provider.base_url, "https://api.example.com/v1");
        assert!(provider.secret_id.is_some());
        assert!(provider.metadata.is_some());
        assert_eq!(harness.validator.calls.lock().unwrap().to_owned(), 1);

        let secrets = harness.secrets.values.lock().unwrap();
        assert_eq!(secrets.len(), 1);
        assert_eq!(secrets.values().next().unwrap().expose(), FAKE_KEY);
        assert!(
            !provider
                .metadata
                .as_ref()
                .unwrap()
                .to_string()
                .contains(FAKE_KEY)
        );
    }

    #[test]
    fn create_custom_provider_allows_local_cleartext_with_a_warning() {
        let harness = harness(Ok(key_info()));
        let form = custom_form("http://localhost:11434/v1", FAKE_KEY);

        let provider = harness
            .service
            .create_custom_provider(&form)
            .expect("local cleartext is allowed");

        assert_eq!(provider.base_url, "http://localhost:11434/v1");
        let detail = harness
            .service
            .provider_detail(&provider.id)
            .expect("provider detail");
        assert!(detail.cleartext_warning.is_some());
    }

    #[test]
    fn create_custom_provider_rejects_public_cleartext() {
        let harness = harness(Ok(key_info()));
        let form = custom_form("http://api.example.com/v1", FAKE_KEY);

        let error = harness
            .service
            .create_custom_provider(&form)
            .expect_err("public cleartext is rejected");

        assert!(error.message().contains("Cleartext HTTP"));
        assert!(harness.providers.providers.lock().unwrap().is_empty());
        assert!(harness.secrets.values.lock().unwrap().is_empty());
    }

    #[test]
    fn create_custom_provider_rejects_invalid_url() {
        let harness = harness(Ok(key_info()));
        let form = custom_form("ftp://example.com/v1", FAKE_KEY);

        let error = harness
            .service
            .create_custom_provider(&form)
            .expect_err("invalid URL is rejected");

        assert!(error.message().contains("http:// or https://"));
        assert!(harness.providers.providers.lock().unwrap().is_empty());
    }

    #[test]
    fn create_custom_provider_rejects_duplicate_label() {
        let harness = harness(Ok(key_info()));
        seed_provider(&harness, openrouter_provider(None), None);
        let mut form = custom_form("https://api.example.com/v1", FAKE_KEY);
        form.label = "personal".to_owned();

        let error = harness
            .service
            .create_custom_provider(&form)
            .expect_err("duplicate label is rejected");

        assert!(error.message().contains("already exists"));
    }

    #[test]
    fn create_custom_provider_rejects_auth_headers() {
        let harness = harness(Ok(key_info()));
        let mut form = custom_form("https://api.example.com/v1", FAKE_KEY);
        form.headers
            .insert("Authorization".to_owned(), "Bearer nope".to_owned());

        let error = harness
            .service
            .create_custom_provider(&form)
            .expect_err("auth header is rejected");

        assert!(error.message().contains("reserved for authentication"));
    }

    #[test]
    fn create_custom_provider_failed_test_connection_stores_nothing() {
        let harness = harness(Err(chat_error(ChatErrorCode::Auth, "401")));
        let form = custom_form("https://api.example.com/v1", FAKE_KEY);

        let error = harness
            .service
            .create_custom_provider(&form)
            .expect_err("failed test connection");

        assert_eq!(error.chat_code(), Some(ChatErrorCode::Auth));
        assert!(harness.providers.providers.lock().unwrap().is_empty());
        assert!(harness.secrets.values.lock().unwrap().is_empty());
        assert!(!format!("{error:?}").contains(FAKE_KEY));
    }

    #[test]
    fn update_custom_provider_edits_fields_without_replacing_the_key() {
        let harness = harness(Ok(key_info()));
        let form = custom_form("https://api.example.com/v1", FAKE_KEY);
        let provider = harness
            .service
            .create_custom_provider(&form)
            .expect("create custom provider");
        let original_secret_id = provider.secret_id.clone();

        let mut edit = CustomProviderForm::for_edit(&provider);
        edit.label = "Renamed".to_owned();
        edit.base_url = "https://api.example.com/v2".to_owned();
        edit.test_connection = false;

        let updated = harness
            .service
            .update_custom_provider(&provider.id, &edit)
            .expect("update custom provider");

        assert_eq!(updated.label, "Renamed");
        assert_eq!(updated.base_url, "https://api.example.com/v2");
        assert_eq!(updated.secret_id, original_secret_id);
        assert_eq!(harness.secrets.values.lock().unwrap().len(), 1);
    }

    #[test]
    fn update_custom_provider_rejects_non_custom_provider() {
        let harness = harness(Ok(key_info()));
        seed_provider(&harness, openrouter_provider(None), None);
        let form = custom_form("https://api.example.com/v1", FAKE_KEY);

        let error = harness
            .service
            .update_custom_provider("provider-1", &form)
            .expect_err("preset providers are not editable here");

        assert!(error.message().contains("Only custom providers"));
    }

    #[test]
    fn rotate_key_validates_before_replacing() {
        let harness = harness(Ok(key_info()));
        seed_provider(
            &harness,
            openrouter_provider(Some("provider-1")),
            Some(FAKE_KEY),
        );

        let updated = harness
            .service
            .rotate_key("provider-1", &SecretValue::new(OTHER_FAKE_KEY))
            .expect("rotate key");

        assert_eq!(harness.validator.calls.lock().unwrap().to_owned(), 1);
        assert_ne!(updated.secret_id.as_deref(), Some("provider-1"));
        let secrets = harness.secrets.values.lock().unwrap();
        assert_eq!(secrets.len(), 1);
        assert_eq!(secrets.values().next().unwrap().expose(), OTHER_FAKE_KEY);
    }

    #[test]
    fn failed_rotation_preserves_the_old_key() {
        let harness = harness(Err(chat_error(ChatErrorCode::Auth, "401")));
        seed_provider(
            &harness,
            openrouter_provider(Some("provider-1")),
            Some(FAKE_KEY),
        );

        let error = harness
            .service
            .rotate_key("provider-1", &SecretValue::new(OTHER_FAKE_KEY))
            .expect_err("rotation fails validation");

        assert_eq!(error.chat_code(), Some(ChatErrorCode::Auth));
        let secrets = harness.secrets.values.lock().unwrap();
        assert_eq!(secrets.len(), 1);
        let stored = secrets.values().next().unwrap();
        assert_eq!(stored.expose(), FAKE_KEY);
        assert!(!format!("{error:?}").contains(OTHER_FAKE_KEY));
    }

    #[test]
    fn forget_key_requires_confirmation_and_clears_key_state() {
        let harness = harness(Ok(key_info()));
        seed_provider(
            &harness,
            openrouter_provider(Some("provider-1")),
            Some(FAKE_KEY),
        );

        let error = harness
            .service
            .forget_key("provider-1", false)
            .expect_err("confirmation is required");
        assert!(matches!(
            error,
            ProviderManagementError::ConfirmationRequired(_)
        ));
        assert_eq!(harness.secrets.values.lock().unwrap().len(), 1);

        let updated = harness
            .service
            .forget_key("provider-1", true)
            .expect("forget key");

        assert!(updated.secret_id.is_none());
        assert!(
            updated
                .metadata
                .as_ref()
                .and_then(|m| m.get("key"))
                .is_none()
        );
        assert!(harness.secrets.values.lock().unwrap().is_empty());

        let list = harness.service.list_providers().expect("list providers");
        assert!(!list.entries()[0].key_present);
    }

    #[test]
    fn forget_all_keys_requires_confirmation_and_clears_every_provider() {
        let harness = harness(Ok(key_info()));
        seed_provider(
            &harness,
            openrouter_provider(Some("provider-1")),
            Some(FAKE_KEY),
        );
        let mut second = openrouter_provider(Some("provider-2"));
        second.id = "provider-2".to_owned();
        second.label = "Work".to_owned();
        seed_provider(&harness, second, Some(OTHER_FAKE_KEY));

        let error = harness
            .service
            .forget_all_keys(false)
            .expect_err("confirmation is required");
        assert!(matches!(
            error,
            ProviderManagementError::ConfirmationRequired(_)
        ));

        let cleared = harness.service.forget_all_keys(true).expect("forget all");

        assert_eq!(cleared, 2);
        assert!(harness.secrets.values.lock().unwrap().is_empty());
        let providers = harness.providers.providers.lock().unwrap();
        assert!(
            providers
                .iter()
                .all(|provider| provider.secret_id.is_none())
        );
        assert!(providers.iter().all(|provider| {
            provider
                .metadata
                .as_ref()
                .and_then(|metadata| metadata.get("key"))
                .is_none()
        }));
    }

    #[test]
    fn delete_provider_removes_models_and_secret_and_preserves_labels() {
        let harness = harness(Ok(key_info()));
        seed_provider(
            &harness,
            openrouter_provider(Some("provider-1")),
            Some(FAKE_KEY),
        );
        harness
            .models
            .models
            .lock()
            .unwrap()
            .push(model("provider-1", "openrouter/auto"));
        harness
            .conversations
            .conversations
            .lock()
            .unwrap()
            .push(conversation("provider-1", "model-openrouter/auto", "Auto"));
        {
            let mut settings = harness.settings.settings.lock().unwrap();
            settings.default_provider_id = Some("provider-1".to_owned());
            settings.default_model_id = Some("model-openrouter/auto".to_owned());
        }

        let error = harness
            .service
            .delete_provider("provider-1", false)
            .expect_err("confirmation is required");
        assert!(matches!(
            error,
            ProviderManagementError::ConfirmationRequired(_)
        ));

        harness
            .service
            .delete_provider("provider-1", true)
            .expect("delete provider");

        assert!(harness.providers.providers.lock().unwrap().is_empty());
        assert!(harness.models.models.lock().unwrap().is_empty());
        assert!(harness.secrets.values.lock().unwrap().is_empty());

        let conversations = harness.conversations.conversations.lock().unwrap();
        assert_eq!(conversations.len(), 1);
        assert!(conversations[0].provider_id.is_empty());
        assert!(conversations[0].model_id.is_empty());
        assert_eq!(conversations[0].model_label, "Auto");

        let settings = harness.settings.settings.lock().unwrap();
        assert_eq!(settings.default_provider_id, None);
        assert_eq!(settings.default_model_id, None);
    }

    #[test]
    fn test_connection_refreshes_key_metadata() {
        let harness = harness(Ok(key_info()));
        seed_provider(
            &harness,
            openrouter_provider(Some("provider-1")),
            Some(FAKE_KEY),
        );

        let updated = harness
            .service
            .test_connection("provider-1")
            .expect("test connection");

        assert_eq!(harness.validator.calls.lock().unwrap().to_owned(), 1);
        assert!(updated.metadata.as_ref().unwrap().get("key").is_some());
    }

    #[test]
    fn test_connection_requires_a_stored_key() {
        let harness = harness(Ok(key_info()));
        seed_provider(&harness, openrouter_provider(None), None);

        let error = harness
            .service
            .test_connection("provider-1")
            .expect_err("missing key");

        assert!(matches!(error, ProviderManagementError::MissingKey(_)));
    }

    #[test]
    fn set_enabled_updates_the_provider() {
        let harness = harness(Ok(key_info()));
        seed_provider(&harness, openrouter_provider(None), None);

        let updated = harness
            .service
            .set_enabled("provider-1", false)
            .expect("disable provider");

        assert!(!updated.enabled);
        let list = harness.service.list_providers().expect("list providers");
        assert!(!list.entries()[0].enabled);
    }

    #[test]
    fn custom_providers_can_be_disabled() {
        let harness = harness(Ok(key_info()));
        let service = ProviderManagementService::new(
            harness.providers.clone(),
            harness.models.clone(),
            harness.conversations.clone(),
            harness.settings.clone(),
            harness.secrets.clone(),
            harness.validator.clone(),
        )
        .with_custom_providers_allowed(false);

        let list = service.list_providers().expect("list providers");
        assert!(!list.add_provider_available());

        let form = custom_form("https://api.example.com/v1", FAKE_KEY);
        let error = service
            .create_custom_provider(&form)
            .expect_err("custom providers are disabled");
        assert!(error.message().contains("not enabled"));
    }
}
