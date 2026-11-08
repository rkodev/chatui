//! Model management failures and the user-facing copy for them.
//!
//! Discovery failures are non-blocking: the service keeps cached or fallback
//! models and returns a notice. The copy helper is used for the blocking
//! failures that reach the view (invalid manual input, storage errors, and a
//! discovery failure with no cached or fallback models).
//!
//! Model metadata never contains provider keys, so the copy helper does not
//! need a secret to redact. It still avoids echoing raw JSON as the primary
//! message and keeps diagnostics in `details`.

use std::fmt;

use chatui_domain::{ChatError, ChatErrorCode};

/// A failure raised while managing models.
#[derive(Debug, Clone, PartialEq)]
pub enum ModelManagementError {
    /// The provider or model record does not exist.
    NotFound(String),
    /// The submitted manual-model input is missing or invalid.
    InvalidInput(String),
    /// A model, provider, or settings record could not be saved.
    Storage(String),
    /// A normalized discovery or network failure.
    Discovery(ChatError),
}

impl ModelManagementError {
    /// Return the normalized chat error code when one is available.
    pub fn chat_code(&self) -> Option<ChatErrorCode> {
        match self {
            Self::Discovery(error) => Some(error.code),
            _ => None,
        }
    }

    /// Return the user-safe message.
    pub fn message(&self) -> &str {
        match self {
            Self::NotFound(message) | Self::InvalidInput(message) | Self::Storage(message) => {
                message
            }
            Self::Discovery(error) => &error.message,
        }
    }
}

impl fmt::Display for ModelManagementError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter.write_str(self.message())
    }
}

impl std::error::Error for ModelManagementError {}

impl From<ChatError> for ModelManagementError {
    fn from(error: ChatError) -> Self {
        Self::Discovery(error)
    }
}

/// Plain-language copy shown for a model management failure.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ModelManagementErrorCopy {
    /// Short heading.
    pub title: String,
    /// Actionable message.
    pub message: String,
    /// Optional non-secret diagnostic details.
    pub details: Option<String>,
}

impl ModelManagementErrorCopy {
    fn new(title: impl Into<String>, message: impl Into<String>, details: Option<String>) -> Self {
        Self {
            title: title.into(),
            message: message.into(),
            details,
        }
    }
}

const DISCOVERY_MESSAGE: &str = "Model discovery failed. Cached models are still available; retry when the provider is reachable.";

fn copy_for_chat_code(code: ChatErrorCode, raw_message: &str) -> ModelManagementErrorCopy {
    let details = Some(raw_message.to_owned());

    match code {
        ChatErrorCode::Auth => ModelManagementErrorCopy::new(
            "API key rejected",
            "Your API key was rejected. Check the key and try again.",
            details,
        ),
        ChatErrorCode::Permission => ModelManagementErrorCopy::new(
            "Permission denied",
            "This key does not have permission to list models. Check the key permissions and try again.",
            details,
        ),
        ChatErrorCode::Quota => ModelManagementErrorCopy::new(
            "Insufficient credits",
            "The provider reports insufficient credits. Add credits or choose a free model.",
            details,
        ),
        ChatErrorCode::RateLimit => ModelManagementErrorCopy::new(
            "Rate limited",
            "The provider is rate limiting requests. Wait a moment and retry.",
            details,
        ),
        ChatErrorCode::ModelNotFound => ModelManagementErrorCopy::new(
            "Model unavailable",
            "The selected model is unavailable. Choose another model.",
            details,
        ),
        ChatErrorCode::Network | ChatErrorCode::Cors => ModelManagementErrorCopy::new(
            "Could not reach the provider",
            DISCOVERY_MESSAGE,
            details,
        ),
        ChatErrorCode::Timeout => ModelManagementErrorCopy::new(
            "Request timed out",
            "Model discovery timed out. Cached models are still available; retry when the provider is reachable.",
            details,
        ),
        ChatErrorCode::Cancelled => ModelManagementErrorCopy::new(
            "Discovery cancelled",
            "Model discovery was cancelled. Retry when you are ready.",
            details,
        ),
        ChatErrorCode::Parse => ModelManagementErrorCopy::new(
            "Unexpected response",
            "The provider returned an unexpected model catalog. Cached models are still available.",
            details,
        ),
        ChatErrorCode::Storage => ModelManagementErrorCopy::new(
            "Could not save the change",
            "ChatUI could not save the model change on this device. Try again.",
            details,
        ),
        ChatErrorCode::Provider | ChatErrorCode::Unknown => {
            ModelManagementErrorCopy::new("Could not load models", DISCOVERY_MESSAGE, details)
        }
        ChatErrorCode::ContextLength
        | ChatErrorCode::ContentFilter
        | ChatErrorCode::InvalidRequest => {
            ModelManagementErrorCopy::new("Could not load models", DISCOVERY_MESSAGE, details)
        }
    }
}

/// Map any model management failure to plain-language, actionable copy.
pub fn describe_model_error(error: &ModelManagementError) -> ModelManagementErrorCopy {
    match error {
        ModelManagementError::Discovery(chat) => copy_for_chat_code(chat.code, &chat.message),
        ModelManagementError::NotFound(message) => {
            ModelManagementErrorCopy::new("Model not found", message.clone(), None)
        }
        ModelManagementError::InvalidInput(message) => {
            ModelManagementErrorCopy::new("Check the model details", message.clone(), None)
        }
        ModelManagementError::Storage(message) => ModelManagementErrorCopy::new(
            "Could not save the change",
            "ChatUI could not save the model change on this device. Try again.",
            Some(message.clone()),
        ),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use chatui_domain::Timestamp;

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

    #[test]
    fn network_discovery_copy_mentions_cached_models() {
        let copy = describe_model_error(&ModelManagementError::Discovery(chat_error(
            ChatErrorCode::Network,
            "offline",
        )));

        assert_eq!(copy.title, "Could not reach the provider");
        assert!(copy.message.contains("Cached models"));
        assert_eq!(copy.details.as_deref(), Some("offline"));
    }

    #[test]
    fn invalid_input_copy_is_actionable() {
        let copy = describe_model_error(&ModelManagementError::InvalidInput(
            "Enter the model id.".to_owned(),
        ));

        assert_eq!(copy.title, "Check the model details");
        assert!(copy.message.contains("model id"));
    }

    #[test]
    fn storage_copy_does_not_echo_raw_json_as_the_message() {
        let copy = describe_model_error(&ModelManagementError::Storage(
            "database is locked".to_owned(),
        ));

        assert_eq!(copy.title, "Could not save the change");
        assert!(!copy.message.contains("database"));
        assert_eq!(copy.details.as_deref(), Some("database is locked"));
    }
}
