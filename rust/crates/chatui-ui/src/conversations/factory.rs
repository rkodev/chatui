//! New-chat creation from the persisted defaults.
//!
//! The history view model does not resolve providers or models itself. It calls
//! a [`ConversationFactory`], which keeps the sidebar testable and lets the
//! application reuse the same default-resolution rules as the chat surface.
//!
//! [`DefaultConversationFactory`] prefers the configured default provider and
//! model, then falls back to the first enabled provider and its first visible
//! model. When nothing is configured it returns a typed
//! [`HistoryError::NotConfigured`] instead of creating an unusable chat.

use std::sync::Arc;

use async_trait::async_trait;
use chatui_domain::{Conversation, Model, Provider, Timestamp, new_id};
use chatui_storage::{
    ConversationRepository, ModelRepository, ProviderRepository, SettingsRepository,
};

use super::history::{DEFAULT_CONVERSATION_TITLE, HistoryError};

/// Creates and persists a new conversation.
#[async_trait]
pub trait ConversationFactory: Send + Sync {
    /// Create a conversation using the current defaults.
    async fn create(&self, title: Option<&str>) -> Result<Conversation, HistoryError>;
}

/// Default factory backed by the provider, model, and settings repositories.
pub struct DefaultConversationFactory {
    conversations: Arc<dyn ConversationRepository>,
    providers: Arc<dyn ProviderRepository>,
    models: Arc<dyn ModelRepository>,
    settings: Arc<dyn SettingsRepository>,
}

impl DefaultConversationFactory {
    /// Build a factory over the injected repositories.
    pub fn new(
        conversations: Arc<dyn ConversationRepository>,
        providers: Arc<dyn ProviderRepository>,
        models: Arc<dyn ModelRepository>,
        settings: Arc<dyn SettingsRepository>,
    ) -> Self {
        Self {
            conversations,
            providers,
            models,
            settings,
        }
    }

    async fn resolve_provider(&self, preferred_id: Option<&str>) -> Result<Provider, HistoryError> {
        if let Some(id) = preferred_id
            && let Some(provider) = self
                .providers
                .get(id)
                .await
                .map_err(HistoryError::storage)?
            && provider.enabled
        {
            return Ok(provider);
        }

        let providers = self.providers.list().await.map_err(HistoryError::storage)?;

        providers
            .into_iter()
            .find(|provider| provider.enabled)
            .ok_or_else(|| {
                HistoryError::NotConfigured(
                    "No provider is configured. Add a provider before starting a chat.".to_owned(),
                )
            })
    }

    async fn resolve_model(
        &self,
        provider: &Provider,
        preferred_id: Option<&str>,
    ) -> Result<Model, HistoryError> {
        let models = self
            .models
            .list_by_provider(&provider.id)
            .await
            .map_err(HistoryError::storage)?;

        if let Some(id) = preferred_id
            && let Some(model) = models.iter().find(|model| model.id == id)
        {
            return Ok(model.clone());
        }

        models
            .iter()
            .find(|model| !model.hidden)
            .or_else(|| models.first())
            .cloned()
            .ok_or_else(|| {
                HistoryError::NotConfigured(format!(
                    "No model is available for {}. Refresh the model catalog before starting a chat.",
                    provider.label
                ))
            })
    }
}

#[async_trait]
impl ConversationFactory for DefaultConversationFactory {
    async fn create(&self, title: Option<&str>) -> Result<Conversation, HistoryError> {
        let settings = self.settings.load().await.map_err(HistoryError::storage)?;
        let provider = self
            .resolve_provider(settings.default_provider_id.as_deref())
            .await?;
        let model = self
            .resolve_model(&provider, settings.default_model_id.as_deref())
            .await?;
        let now = Timestamp::now();
        let title = title
            .map(str::trim)
            .filter(|title| !title.is_empty())
            .unwrap_or(DEFAULT_CONVERSATION_TITLE)
            .to_owned();
        let conversation = Conversation {
            id: new_id(),
            title,
            system_prompt: settings.default_system_prompt.clone(),
            provider_id: provider.id.clone(),
            model_id: model.id.clone(),
            model_label: model.label.clone(),
            params: settings.default_params.clone(),
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
        };

        self.conversations
            .create(&conversation)
            .await
            .map_err(HistoryError::storage)?;

        Ok(conversation)
    }
}
