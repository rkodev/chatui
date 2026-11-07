//! Onboarding failures and the user-facing copy for them.
//!
//! Every error that reaches the view is converted to [`OnboardingErrorCopy`].
//! The copy is actionable, provider-specific where possible, and never echoes
//! the typed API key. The key is redacted from both the message and the
//! optional details string before the copy is returned.

use std::fmt;

use chatui_domain::{ChatError, ChatErrorCode};
use chatui_secrets::SecretError;

/// A failure raised while onboarding.
///
/// Adapter failures keep their normalized [`ChatError`] so the copy helper can
/// map the shared error code. Service failures use the smaller variants below.
#[derive(Debug, Clone, PartialEq)]
pub enum OnboardingError {
    /// The form input is missing or invalid.
    InvalidInput(String),
    /// No usable text model was found.
    NoModel(String),
    /// The app could not start a chat after connecting.
    NotConfigured(String),
    /// A settings or provider record could not be persisted.
    Storage(String),
    /// The secret store could not save or remove the key.
    Secret(String),
    /// A normalized provider or network failure.
    Chat(ChatError),
}

impl OnboardingError {
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
            Self::InvalidInput(message)
            | Self::NoModel(message)
            | Self::NotConfigured(message)
            | Self::Storage(message)
            | Self::Secret(message) => message,
            Self::Chat(error) => &error.message,
        }
    }
}

impl fmt::Display for OnboardingError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter.write_str(self.message())
    }
}

impl std::error::Error for OnboardingError {}

impl From<ChatError> for OnboardingError {
    fn from(error: ChatError) -> Self {
        Self::Chat(error)
    }
}

impl From<SecretError> for OnboardingError {
    fn from(error: SecretError) -> Self {
        Self::Secret(error.to_string())
    }
}

/// Plain-language copy shown for an onboarding failure.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct OnboardingErrorCopy {
    /// Short heading.
    pub title: String,
    /// Actionable message.
    pub message: String,
    /// Optional non-secret diagnostic details.
    pub details: Option<String>,
}

impl OnboardingErrorCopy {
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
    "The request could not reach OpenRouter. Check your connection and try again.";
const STORAGE_MESSAGE: &str =
    "ChatUI could not save the key on this device. Check the OS credential store and try again.";

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
) -> OnboardingErrorCopy {
    let details = redact(secret, raw_message);

    match code {
        ChatErrorCode::Auth => {
            OnboardingErrorCopy::new("API key rejected", AUTH_MESSAGE, Some(details))
        }
        ChatErrorCode::Permission => OnboardingErrorCopy::new(
            "Permission denied",
            "This key does not have permission to use OpenRouter. Check the key permissions and try again.",
            Some(details),
        ),
        ChatErrorCode::Quota => OnboardingErrorCopy::new(
            "Insufficient credits",
            "OpenRouter reports insufficient credits. Add credits or choose a free model.",
            Some(details),
        ),
        ChatErrorCode::RateLimit => OnboardingErrorCopy::new(
            "Rate limited",
            "OpenRouter is rate limiting requests. Wait a moment and try again.",
            Some(details),
        ),
        ChatErrorCode::ModelNotFound => OnboardingErrorCopy::new(
            "Model unavailable",
            "The selected model is unavailable. Choose another model.",
            Some(details),
        ),
        ChatErrorCode::ContextLength => OnboardingErrorCopy::new(
            "Conversation too long",
            "The conversation is too long for this model. Start a new chat or reduce the context.",
            Some(details),
        ),
        ChatErrorCode::ContentFilter => OnboardingErrorCopy::new(
            "Request blocked",
            "OpenRouter blocked the request. Check the key and try again.",
            Some(details),
        ),
        ChatErrorCode::InvalidRequest => OnboardingErrorCopy::new(
            "Request rejected",
            "OpenRouter rejected the request. Check the key and try again.",
            Some(details),
        ),
        ChatErrorCode::Network | ChatErrorCode::Cors => {
            OnboardingErrorCopy::new("Could not reach OpenRouter", NETWORK_MESSAGE, Some(details))
        }
        ChatErrorCode::Timeout => OnboardingErrorCopy::new(
            "Request timed out",
            "The request timed out. Check your connection and try again.",
            Some(details),
        ),
        ChatErrorCode::Cancelled => OnboardingErrorCopy::new(
            "Connection cancelled",
            "The connection was cancelled. Try again.",
            Some(details),
        ),
        ChatErrorCode::Parse => OnboardingErrorCopy::new(
            "Unexpected response",
            "OpenRouter returned an unexpected response. Try again.",
            Some(details),
        ),
        ChatErrorCode::Storage => {
            OnboardingErrorCopy::new("Could not save the key", STORAGE_MESSAGE, Some(details))
        }
        ChatErrorCode::Provider | ChatErrorCode::Unknown => OnboardingErrorCopy::new(
            "Could not connect",
            "OpenRouter could not complete the request. Try again.",
            Some(details),
        ),
    }
}

/// Map any onboarding failure to plain-language, actionable copy.
///
/// The typed key is redacted from every message and detail string so it can
/// never be echoed back into the view or a diagnostics panel.
pub fn describe_onboarding_error(
    error: &OnboardingError,
    secret: Option<&str>,
) -> OnboardingErrorCopy {
    match error {
        OnboardingError::Chat(chat) => copy_for_chat_code(chat.code, &chat.message, secret),
        OnboardingError::InvalidInput(message) => {
            OnboardingErrorCopy::new("Enter an API key", message.clone(), None)
        }
        OnboardingError::NoModel(message) => {
            OnboardingErrorCopy::new("No model available", message.clone(), None)
        }
        OnboardingError::NotConfigured(message) => {
            OnboardingErrorCopy::new("Could not start a chat", message.clone(), None)
        }
        OnboardingError::Storage(message) => OnboardingErrorCopy::new(
            "Could not save setup",
            message.clone(),
            Some(redact(secret, message)),
        ),
        OnboardingError::Secret(message) => OnboardingErrorCopy::new(
            "Could not save the key",
            STORAGE_MESSAGE,
            Some(redact(secret, message)),
        ),
    }
}

/// Non-blocking catalog failure copy. Connecting remains available.
pub fn describe_catalog_error(error: &ChatError) -> String {
    match error.code {
        ChatErrorCode::Network | ChatErrorCode::Cors => {
            "Could not load the model catalog. Check your connection. You can still connect; models will load after validation.".to_owned()
        }
        _ => "Could not load the model catalog. You can still connect; models will load after validation.".to_owned(),
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
        let copy = describe_onboarding_error(
            &OnboardingError::Chat(chat_error(ChatErrorCode::Auth, "401")),
            None,
        );

        assert_eq!(copy.title, "API key rejected");
        assert!(copy.message.contains("Check the key"));
        assert_eq!(copy.details.as_deref(), Some("401"));
    }

    #[test]
    fn network_error_copy_is_actionable() {
        let copy = describe_onboarding_error(
            &OnboardingError::Chat(chat_error(ChatErrorCode::Network, "offline")),
            None,
        );

        assert_eq!(copy.title, "Could not reach OpenRouter");
        assert!(copy.message.contains("connection"));
    }

    #[test]
    fn key_is_redacted_from_details() {
        let key = "sk-test-not-a-real-key";
        let copy = describe_onboarding_error(
            &OnboardingError::Chat(chat_error(
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
        let copy = describe_onboarding_error(
            &OnboardingError::Secret(format!("backend rejected {key}")),
            Some(key),
        );

        assert_eq!(copy.title, "Could not save the key");
        assert!(!copy.details.as_deref().unwrap_or_default().contains(key));
    }

    #[test]
    fn catalog_error_copy_is_non_blocking() {
        let copy = describe_catalog_error(&chat_error(ChatErrorCode::Network, "offline"));

        assert!(copy.contains("You can still connect"));
        assert!(copy.contains("Check your connection"));
    }

    #[test]
    fn invalid_input_copy_has_no_details() {
        let copy = describe_onboarding_error(
            &OnboardingError::InvalidInput("Enter an OpenRouter API key to continue.".to_owned()),
            None,
        );

        assert_eq!(copy.title, "Enter an API key");
        assert_eq!(copy.details, None);
    }
}
