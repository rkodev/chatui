//! Chat orchestration service.
//!
//! The service resolves the conversation provider, model, and key, builds the
//! normalized request from persisted history, and delegates the stream
//! lifecycle to [`ChatRunner`]. It never talks to SQLite or the network
//! directly; callers inject the repositories, secret store, and runner.
//!
//! Error copy mirrors the shared web copy in
//! `web/src/features/chat/chatErrorCopy.ts`. Raw provider JSON is never the
//! primary message; it is exposed only through the optional details field.

#![allow(clippy::result_large_err)]

use std::collections::BTreeMap;
use std::sync::Arc;

use chatui_contracts::{AppConfig, provider_catalog};
use chatui_domain::{
    ChatError, ChatErrorCode, ContentPart, Conversation, Message, MessageRole, Model,
    OutputModality, Provider, ProviderAuthType,
};
use chatui_providers::{NormalizedChatMessage, NormalizedChatRequest, ProviderContext};
use chatui_secrets::{SecretId, SecretStore};
use chatui_storage::{
    ConversationRepository, MessageRepository, ModelRepository, ProviderRepository, StorageError,
};

use crate::chat::runner::{
    ChatRunInput, ChatRunResult, ChatRunner, RegenerateInput, RunRequest, can_retry,
};

const CORS_MESSAGE: &str = "The request could not reach the provider. This may be a browser CORS restriction. Try the Android app or a self-hosted proxy.";

/// A failure raised while preparing or running a chat request.
#[derive(Debug, Clone, PartialEq)]
pub enum ChatServiceError {
    /// The conversation does not exist.
    ConversationNotFound(String),
    /// The conversation provider does not exist.
    ProviderNotFound(String),
    /// The conversation model does not exist.
    ModelNotFound(String),
    /// The provider or model cannot be used.
    ModelUnavailable(String),
    /// The provider needs a key that is not stored.
    MissingKey(String),
    /// A repository operation failed.
    Storage(String),
    /// The secret store failed.
    Secret(String),
    /// The regenerate or retry target is not allowed.
    InvalidTarget(String),
    /// A stream is already active for the conversation.
    Busy,
    /// The runner returned a normalized chat error.
    Runner(ChatError),
}

/// Why a chat send failed.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ChatSendFailureReason {
    /// The conversation does not exist.
    ConversationNotFound,
    /// No provider is configured or the provider is missing.
    NoProvider,
    /// The model is missing, disabled, or not text-capable.
    ModelUnavailable,
    /// The provider key is missing.
    NoKey,
    /// A stream is already active.
    Busy,
    /// A message could not be persisted.
    Storage,
    /// The regenerate or retry target is not allowed.
    InvalidTarget,
    /// The runner returned a normalized chat error.
    Runner,
}

/// A user-facing chat send failure.
#[derive(Debug, Clone, PartialEq)]
pub struct ChatSendFailure {
    /// Machine-readable reason.
    pub reason: ChatSendFailureReason,
    /// Actionable user-facing message.
    pub message: String,
    /// Normalized chat error, when one is available.
    pub error: Option<ChatError>,
}

/// Result of a chat send, regenerate, or retry.
pub type ChatSendResult = Result<Message, ChatSendFailure>;

impl From<ChatServiceError> for ChatSendFailure {
    fn from(error: ChatServiceError) -> Self {
        match error {
            ChatServiceError::ConversationNotFound(id) => Self {
                reason: ChatSendFailureReason::ConversationNotFound,
                message: format!("Conversation {id} was not found."),
                error: None,
            },
            ChatServiceError::ProviderNotFound(id) => Self {
                reason: ChatSendFailureReason::NoProvider,
                message: format!("Provider {id} was not found. Choose another provider."),
                error: None,
            },
            ChatServiceError::ModelNotFound(id) => Self {
                reason: ChatSendFailureReason::ModelUnavailable,
                message: format!("Model {id} was not found. Choose another model."),
                error: None,
            },
            ChatServiceError::ModelUnavailable(message) => Self {
                reason: ChatSendFailureReason::ModelUnavailable,
                message,
                error: None,
            },
            ChatServiceError::MissingKey(label) => Self {
                reason: ChatSendFailureReason::NoKey,
                message: format!(
                    "The API key for {label} is unavailable. Re-enter the key and try again."
                ),
                error: None,
            },
            ChatServiceError::Storage(message) => Self {
                reason: ChatSendFailureReason::Storage,
                message,
                error: None,
            },
            ChatServiceError::Secret(message) => Self {
                reason: ChatSendFailureReason::NoKey,
                message,
                error: None,
            },
            ChatServiceError::InvalidTarget(message) => Self {
                reason: ChatSendFailureReason::InvalidTarget,
                message,
                error: None,
            },
            ChatServiceError::Busy => Self {
                reason: ChatSendFailureReason::Busy,
                message: "A response is already streaming for this conversation.".to_owned(),
                error: None,
            },
            ChatServiceError::Runner(error) => Self {
                reason: ChatSendFailureReason::Runner,
                message: error.message.clone(),
                error: Some(error),
            },
        }
    }
}

/// Chat orchestration over injected repositories and a runner.
pub struct ChatService {
    conversations: Arc<dyn ConversationRepository>,
    messages: Arc<dyn MessageRepository>,
    providers: Arc<dyn ProviderRepository>,
    models: Arc<dyn ModelRepository>,
    secrets: Arc<dyn SecretStore>,
    runner: Arc<ChatRunner>,
    app_config: AppConfig,
}

impl ChatService {
    /// Build a service over the injected dependencies.
    pub fn new(
        conversations: Arc<dyn ConversationRepository>,
        messages: Arc<dyn MessageRepository>,
        providers: Arc<dyn ProviderRepository>,
        models: Arc<dyn ModelRepository>,
        secrets: Arc<dyn SecretStore>,
        runner: Arc<ChatRunner>,
        app_config: AppConfig,
    ) -> Self {
        Self {
            conversations,
            messages,
            providers,
            models,
            secrets,
            runner,
            app_config,
        }
    }

    /// Send a new user message and stream the assistant response.
    pub async fn send(
        &self,
        conversation_id: &str,
        user_parts: Vec<ContentPart>,
    ) -> ChatSendResult {
        let conversation = self
            .load_conversation(conversation_id)
            .await
            .map_err(ChatSendFailure::from)?;
        let (_, model, context) = self
            .resolve(&conversation)
            .await
            .map_err(ChatSendFailure::from)?;
        let history = self
            .messages
            .list_all(conversation_id)
            .await
            .map_err(storage_failure)?;
        let request =
            build_chat_request(&conversation, &model.remote_id, &history, Some(&user_parts));

        let input = ChatRunInput {
            run: RunRequest {
                conversation,
                context,
                request,
                supported_parameters: model.supported_parameters.clone(),
                attachments: BTreeMap::new(),
                cancel: None,
            },
            user_parts,
        };

        map_runner_result(self.runner.send(input).await)
    }

    /// Regenerate the last assistant response in place.
    pub async fn regenerate(&self, conversation_id: &str, target_id: &str) -> ChatSendResult {
        let conversation = self
            .load_conversation(conversation_id)
            .await
            .map_err(ChatSendFailure::from)?;
        let (_, model, context) = self
            .resolve(&conversation)
            .await
            .map_err(ChatSendFailure::from)?;
        let target = self
            .load_target(conversation_id, target_id)
            .await
            .map_err(ChatSendFailure::from)?;
        let history = self
            .messages
            .list_all(conversation_id)
            .await
            .map_err(storage_failure)?;
        let before_target: Vec<Message> = history
            .into_iter()
            .filter(|message| message.sequence < target.sequence)
            .collect();
        let request = build_chat_request(&conversation, &model.remote_id, &before_target, None);

        let input = RegenerateInput {
            run: RunRequest {
                conversation,
                context,
                request,
                supported_parameters: model.supported_parameters.clone(),
                attachments: BTreeMap::new(),
                cancel: None,
            },
            target,
        };

        map_runner_result(self.runner.regenerate(input).await)
    }

    /// Retry a failed assistant response when the shared policy allows it.
    pub async fn retry(&self, conversation_id: &str, target_id: &str) -> ChatSendResult {
        let target = self
            .load_target(conversation_id, target_id)
            .await
            .map_err(ChatSendFailure::from)?;

        if !can_retry(&target) {
            return Err(ChatServiceError::InvalidTarget(
                "This message cannot be retried. Regenerate it instead.".to_owned(),
            )
            .into());
        }

        self.regenerate(conversation_id, target_id).await
    }

    /// Cancel the active stream for a conversation.
    pub async fn cancel(&self, conversation_id: &str) -> Option<Message> {
        self.runner.cancel(conversation_id).await
    }

    /// True while a stream is active for the conversation.
    pub fn is_streaming(&self, conversation_id: &str) -> bool {
        self.runner.is_streaming(conversation_id)
    }

    async fn load_conversation(
        &self,
        conversation_id: &str,
    ) -> Result<Conversation, ChatServiceError> {
        self.conversations
            .get(conversation_id)
            .await
            .map_err(|error| ChatServiceError::Storage(error.to_string()))?
            .ok_or_else(|| ChatServiceError::ConversationNotFound(conversation_id.to_owned()))
    }

    async fn load_target(
        &self,
        conversation_id: &str,
        target_id: &str,
    ) -> Result<Message, ChatServiceError> {
        let target = self
            .messages
            .get(target_id)
            .await
            .map_err(|error| ChatServiceError::Storage(error.to_string()))?
            .ok_or_else(|| {
                ChatServiceError::InvalidTarget("The message was not found.".to_owned())
            })?;

        if target.conversation_id != conversation_id {
            return Err(ChatServiceError::InvalidTarget(
                "The message belongs to another conversation.".to_owned(),
            ));
        }

        Ok(target)
    }

    async fn resolve(
        &self,
        conversation: &Conversation,
    ) -> Result<(Provider, Model, ProviderContext), ChatServiceError> {
        let provider = self
            .providers
            .get(&conversation.provider_id)
            .await
            .map_err(|error| ChatServiceError::Storage(error.to_string()))?
            .ok_or_else(|| ChatServiceError::ProviderNotFound(conversation.provider_id.clone()))?;

        if !provider.enabled {
            return Err(ChatServiceError::ModelUnavailable(format!(
                "Provider {} is disabled. Enable it or choose another model.",
                provider.label
            )));
        }

        let model = self
            .models
            .get(&conversation.model_id)
            .await
            .map_err(|error| ChatServiceError::Storage(error.to_string()))?
            .ok_or_else(|| ChatServiceError::ModelNotFound(conversation.model_id.clone()))?;

        if model.provider_id != provider.id {
            return Err(ChatServiceError::ModelUnavailable(format!(
                "Model {} does not belong to provider {}.",
                model.remote_id, provider.label
            )));
        }

        if !model.output_modalities.contains(&OutputModality::Text) {
            return Err(ChatServiceError::ModelUnavailable(format!(
                "Model {} does not produce text output. Choose another model.",
                model.remote_id
            )));
        }

        let context = self.provider_context(&provider)?;

        if provider.auth_type != ProviderAuthType::None && context.api_key.is_none() {
            return Err(ChatServiceError::MissingKey(provider.label.clone()));
        }

        Ok((provider, model, context))
    }

    fn provider_context(&self, provider: &Provider) -> Result<ProviderContext, ChatServiceError> {
        let catalog = provider_catalog();
        let preset = catalog
            .get(&provider.preset_id)
            .or_else(|| catalog.get("custom"))
            .ok_or_else(|| ChatServiceError::ProviderNotFound(provider.preset_id.clone()))?;

        let api_key = match &provider.secret_id {
            Some(secret_id) => {
                let id = SecretId::new(secret_id.clone())
                    .map_err(|error| ChatServiceError::Secret(error.to_string()))?;
                self.secrets
                    .get(&id)
                    .map_err(|error| ChatServiceError::Secret(error.to_string()))?
                    .map(|value| value.expose().to_owned())
            }
            None => None,
        };

        let mut auth = preset.auth.clone();
        auth.auth_type = provider.auth_type;

        let mut headers = preset.default_headers.clone();
        headers.extend(self.app_config.attribution_headers(preset));
        headers.extend(provider.headers.clone());

        Ok(ProviderContext {
            preset: preset.clone(),
            base_url: provider.base_url.clone(),
            auth,
            headers,
            api_key,
        })
    }
}

/// Build the normalized request for the next assistant response.
///
/// History is sent in sequence order. Deleted messages, system messages, empty
/// parts, and reasoning parts are skipped; the conversation system prompt is
/// sent separately. `pending_user_parts` appends the new user message that the
/// runner is about to persist.
pub fn build_chat_request(
    conversation: &Conversation,
    model_remote_id: &str,
    history: &[Message],
    pending_user_parts: Option<&[ContentPart]>,
) -> NormalizedChatRequest {
    let mut messages = Vec::new();

    for message in history {
        if message.deleted_at.is_some() || message.role == MessageRole::System {
            continue;
        }

        let parts = normalized_parts(&message.parts);

        if parts.is_empty() {
            continue;
        }

        messages.push(NormalizedChatMessage {
            role: message.role,
            parts,
        });
    }

    if let Some(parts) = pending_user_parts {
        let parts = normalized_parts(parts);

        if !parts.is_empty() {
            messages.push(NormalizedChatMessage {
                role: MessageRole::User,
                parts,
            });
        }
    }

    NormalizedChatRequest {
        model: model_remote_id.to_owned(),
        system: if conversation.system_prompt.trim().is_empty() {
            None
        } else {
            Some(conversation.system_prompt.clone())
        },
        messages,
        params: conversation.params.clone(),
    }
}

fn normalized_parts(parts: &[ContentPart]) -> Vec<ContentPart> {
    parts
        .iter()
        .filter_map(|part| match part {
            ContentPart::Text { text } if !text.is_empty() => Some(part.clone()),
            ContentPart::Image { .. } | ContentPart::File { .. } => Some(part.clone()),
            _ => None,
        })
        .collect()
}

fn map_runner_result(result: ChatRunResult) -> ChatSendResult {
    match result {
        ChatRunResult::Ok { message, .. } => Ok(message),
        ChatRunResult::Busy => Err(ChatServiceError::Busy.into()),
        ChatRunResult::Storage { error } => Err(ChatSendFailure {
            reason: ChatSendFailureReason::Storage,
            message: error.message.clone(),
            error: Some(error),
        }),
        ChatRunResult::InvalidTarget => Err(ChatServiceError::InvalidTarget(
            "This message cannot be regenerated.".to_owned(),
        )
        .into()),
    }
}

fn storage_failure(error: StorageError) -> ChatSendFailure {
    ChatSendFailure {
        reason: ChatSendFailureReason::Storage,
        message: error.to_string(),
        error: None,
    }
}

/// Plain-language copy shown for a normalized chat error.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ChatErrorCopy {
    /// Short heading.
    pub title: String,
    /// Actionable message.
    pub message: String,
    /// Optional non-secret diagnostic details.
    pub details: Option<String>,
}

/// Map a normalized chat error to plain-language, actionable copy.
///
/// Raw provider JSON is never the primary message. It is exposed only through
/// the optional details disclosure.
pub fn describe_chat_error(error: &ChatError) -> ChatErrorCopy {
    let (title, message) = match error.code {
        ChatErrorCode::Auth => (
            "API key rejected",
            "Your API key was rejected. Check the key and try again.",
        ),
        ChatErrorCode::Permission => (
            "Permission denied",
            "This key does not have permission to use the provider. Check the key permissions.",
        ),
        ChatErrorCode::RateLimit => (
            "Rate limited",
            "This provider is rate limiting requests. Wait a moment and try again.",
        ),
        ChatErrorCode::Quota => (
            "Insufficient credits",
            "The provider reports insufficient credits. Add credits or choose a free model.",
        ),
        ChatErrorCode::ModelNotFound => (
            "Model unavailable",
            "The selected model is unavailable. Choose another model.",
        ),
        ChatErrorCode::ContextLength => (
            "Conversation too long",
            "The conversation is too long for this model. Start a new chat or reduce the context.",
        ),
        ChatErrorCode::ContentFilter => (
            "Request blocked",
            "The provider blocked the request. Try a different prompt or model.",
        ),
        ChatErrorCode::InvalidRequest => (
            "Request rejected",
            "The provider rejected the request. Check the model settings and try again.",
        ),
        ChatErrorCode::Network => ("Could not reach the provider", CORS_MESSAGE),
        ChatErrorCode::Cors => (
            "Could not reach the provider",
            if error.message.trim().is_empty() {
                CORS_MESSAGE
            } else {
                error.message.as_str()
            },
        ),
        ChatErrorCode::Timeout => (
            "Request timed out",
            "The request timed out. Check your connection and try again.",
        ),
        ChatErrorCode::Cancelled => (
            "Response cancelled",
            "The response was cancelled. Partial content is preserved.",
        ),
        ChatErrorCode::Parse => (
            "Unexpected response",
            "The provider returned an unexpected response. Try again.",
        ),
        ChatErrorCode::Storage => (
            "Could not save the message",
            "ChatUI could not save the message on this device. Check storage permissions and try again.",
        ),
        ChatErrorCode::Provider | ChatErrorCode::Unknown => (
            "Could not complete the request",
            "The provider could not complete the request. Try again.",
        ),
    };

    ChatErrorCopy {
        title: title.to_owned(),
        message: message.to_owned(),
        details: details_for(error),
    }
}

fn details_for(error: &ChatError) -> Option<String> {
    if let Some(details) = &error.details {
        return Some(serde_json::to_string_pretty(details).unwrap_or_else(|_| details.to_string()));
    }

    let mut parts = Vec::new();

    if let Some(status) = error.status {
        parts.push(format!("HTTP {status}"));
    }

    if let Some(request_id) = &error.request_id {
        parts.push(format!("Request {request_id}"));
    }

    if let Some(retry_after) = error.retry_after_seconds {
        parts.push(format!("Retry after {retry_after}s"));
    }

    if parts.is_empty() {
        None
    } else {
        Some(parts.join("\n"))
    }
}
