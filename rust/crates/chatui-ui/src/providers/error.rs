//! Provider management failures and the user-facing copy for them.
//!
//! Every failure that reaches the view is converted to
//! [`ProviderManagementErrorCopy`]. The copy is actionable and never echoes a
//! typed API key. The key is redacted from both the message and the optional
//! details string before the copy is returned.

use std::fmt;

use chatui_domain::{ChatError, ChatErrorCode};
use chatui_secrets::SecretError;

/// A failure raised while managing providers or keys.
///
/// Adapter failures keep their normalized [`ChatError`] so the copy helper can
/// map the shared error code. Service failures use the smaller variants below.
#[derive(Debug, Clone, PartialEq)]
pub enum ProviderManagementError {
    /// The provider record does not exist.
    NotFound(String),
    /// The submitted form input is missing or invalid.
    InvalidInput(String),
    /// The provider has no usable stored key.
    MissingKey(String),
    /// A destructive action needs explicit confirmation.
    ConfirmationRequired(String),
    /// A provider, model, conversation, or settings record could not be saved.
    Storage(String),
    /// The secret store could not read, save, or remove the key.
    Secret(String),
    /// A normalized provider or network failure.
    Chat(ChatError),
}

impl ProviderManagementError {
    /// Return the normalized chat error code when one is available.
    pub fn chat_code(&self) -> Option<ChatErrorCode> {
        match self {
            Self::Chat(error) => Some(error.code),
            _ => None,
        }
    }

    /// Return the user-safe message.
    pub fn message(&self) -> &str {
        match self {
            Self::NotFound(message)
            | Self::InvalidInput(message)
            | Self::MissingKey(message)
            | Self::ConfirmationRequired(message)
            | Self::Storage(message)
            | Self::Secret(message) => message,
            Self::Chat(error) => &error.message,
        }
    }
}

impl fmt::Display for ProviderManagementError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter.write_str(self.message())
    }
}

impl std::error::Error for ProviderManagementError {}

impl From<ChatError> for ProviderManagementError {
    fn from(error: ChatError) -> Self {
        Self::Chat(error)
    }
}

impl From<SecretError> for ProviderManagementError {
    fn from(error: SecretError) -> Self {
        Self::Secret(error.to_string())
    }
}

/// Plain-language copy shown for a provider management failure.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ProviderManagementErrorCopy {
    /// Short heading.
    pub title: String,
    /// Actionable message.
    pub message: String,
    /// Optional non-secret diagnostic details.
    pub details: Option<String>,
}

impl ProviderManagementErrorCopy {
    fn new(title: impl Into<String>, message: impl Into<String>, details: Option<String>) -> Self {
        Self {
            title: title.into(),
            message: message.into(),
            details,
        }
    }
}

const AUTH_MESSAGE: &str = "Your API key was rejected. Check the key and try again.";
const NETWORK_MESSAGE: &str =
    "The request could not reach the provider. Check your connection and try again.";
const STORAGE_MESSAGE: &str =
    "ChatUI could not save the change on this device. Check the OS credential store and try again.";

/// Replace every occurrence of the typed key with a redaction marker.
fn redact(secret: Option<&str>, text: &str) -> String {
    match secret {
        Some(secret) if !secret.is_empty() => text.replace(secret, "[redacted]"),
        _ => text.to_owned(),
    }
}

fn copy_for_chat_code(
    code: ChatErrorCode,
    raw_message: &str,
    secret: Option<&str>,
) -> ProviderManagementErrorCopy {
    let details = redact(secret, raw_message);

    match code {
        ChatErrorCode::Auth => {
            ProviderManagementErrorCopy::new("API key rejected", AUTH_MESSAGE, Some(details))
        }
        ChatErrorCode::Permission => ProviderManagementErrorCopy::new(
            "Permission denied",
            "This key does not have permission to use the provider. Check the key permissions and try again.",
            Some(details),
        ),
        ChatErrorCode::Quota => ProviderManagementErrorCopy::new(
            "Insufficient credits",
            "The provider reports insufficient credits. Add credits or choose a free model.",
            Some(details),
        ),
        ChatErrorCode::RateLimit => ProviderManagementErrorCopy::new(
            "Rate limited",
            "The provider is rate limiting requests. Wait a moment and try again.",
            Some(details),
        ),
        ChatErrorCode::ModelNotFound => ProviderManagementErrorCopy::new(
            "Model unavailable",
            "The selected model is unavailable. Choose another model.",
            Some(details),
        ),
        ChatErrorCode::ContextLength => ProviderManagementErrorCopy::new(
            "Conversation too long",
            "The conversation is too long for this model. Start a new chat or reduce the context.",
            Some(details),
        ),
        ChatErrorCode::ContentFilter => ProviderManagementErrorCopy::new(
            "Request blocked",
            "The provider blocked the request. Check the key and try again.",
            Some(details),
        ),
        ChatErrorCode::InvalidRequest => ProviderManagementErrorCopy::new(
            "Request rejected",
            "The provider rejected the request. Check the key and try again.",
            Some(details),
        ),
        ChatErrorCode::Network | ChatErrorCode::Cors => ProviderManagementErrorCopy::new(
            "Could not reach the provider",
            NETWORK_MESSAGE,
            Some(details),
        ),
        ChatErrorCode::Timeout => ProviderManagementErrorCopy::new(
            "Request timed out",
            "The request timed out. Check your connection and try again.",
            Some(details),
        ),
        ChatErrorCode::Cancelled => ProviderManagementErrorCopy::new(
            "Connection cancelled",
            "The connection was cancelled. Try again.",
            Some(details),
        ),
        ChatErrorCode::Parse => ProviderManagementErrorCopy::new(
            "Unexpected response",
            "The provider returned an unexpected response. Try again.",
            Some(details),
        ),
        ChatErrorCode::Storage => ProviderManagementErrorCopy::new(
            "Could not save the change",
            STORAGE_MESSAGE,
            Some(details),
        ),
        ChatErrorCode::Provider | ChatErrorCode::Unknown => ProviderManagementErrorCopy::new(
            "Could not connect",
            "The provider could not complete the request. Try again.",
            Some(details),
        ),
    }
}

/// Map any provider management failure to plain-language, actionable copy.
///
/// The typed key is redacted from every message and detail string so it can
/// never be echoed back into the view or a diagnostics panel.
pub fn describe_provider_error(
    error: &ProviderManagementError,
    secret: Option<&str>,
) -> ProviderManagementErrorCopy {
    match error {
        ProviderManagementError::Chat(chat) => copy_for_chat_code(chat.code, &chat.message, secret),
        ProviderManagementError::NotFound(message) => {
            ProviderManagementErrorCopy::new("Provider not found", message.clone(), None)
        }
        ProviderManagementError::InvalidInput(message) => {
            ProviderManagementErrorCopy::new("Check the form", message.clone(), None)
        }
        ProviderManagementError::MissingKey(message) => {
            ProviderManagementErrorCopy::new("No stored key", message.clone(), None)
        }
        ProviderManagementError::ConfirmationRequired(message) => {
            ProviderManagementErrorCopy::new("Confirm this action", message.clone(), None)
        }
        ProviderManagementError::Storage(message) => ProviderManagementErrorCopy::new(
            "Could not save the change",
            message.clone(),
            Some(redact(secret, message)),
        ),
        ProviderManagementError::Secret(message) => ProviderManagementErrorCopy::new(
            "Could not access the key",
            STORAGE_MESSAGE,
            Some(redact(secret, message)),
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
    fn auth_error_copy_is_actionable() {
        let copy = describe_provider_error(
            &ProviderManagementError::Chat(chat_error(ChatErrorCode::Auth, "401")),
            None,
        );

        assert_eq!(copy.title, "API key rejected");
        assert!(copy.message.contains("Check the key"));
        assert_eq!(copy.details.as_deref(), Some("401"));
    }

    #[test]
    fn key_is_redacted_from_details() {
        let key = "sk-test-not-a-real-key";
        let copy = describe_provider_error(
            &ProviderManagementError::Chat(chat_error(
                ChatErrorCode::Auth,
                &format!("request failed for {key}"),
            )),
            Some(key),
        );

        assert!(!copy.message.contains(key));
        assert!(!copy.details.as_deref().unwrap_or_default().contains(key));
        assert!(
            copy.details
                .as_deref()
                .unwrap_or_default()
                .contains("[redacted]")
        );
    }

    #[test]
    fn secret_error_copy_does_not_echo_the_key() {
        let key = "sk-test-not-a-real-key";
        let copy = describe_provider_error(
            &ProviderManagementError::Secret(format!("backend rejected {key}")),
            Some(key),
        );

        assert_eq!(copy.title, "Could not access the key");
        assert!(!copy.details.as_deref().unwrap_or_default().contains(key));
    }

    #[test]
    fn confirmation_copy_is_explicit() {
        let copy = describe_provider_error(
            &ProviderManagementError::ConfirmationRequired(
                "Confirm that you want to forget this key.".to_owned(),
            ),
            None,
        );

        assert_eq!(copy.title, "Confirm this action");
        assert!(copy.message.contains("Confirm"));
    }
}
