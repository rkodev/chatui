//! Secret-store selection for the desktop app.
//!
//! The desktop default is the OS credential store when it is available. When it
//! is not, selection returns [`SecretStoreError::FallbackRequired`] so the
//! onboarding flow can ask the user to choose the encrypted-file fallback or
//! session-only mode. There is no silent plaintext fallback.

use std::fmt;
use std::sync::Arc;

use chatui_secrets::{FallbackChoice, KeyringStore, SecretError, SecretStore, select_store};

/// User-safe failure while selecting a secret store.
///
/// No variant carries a secret value, passphrase, or backend diagnostic.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum SecretStoreError {
    /// The OS store is unavailable and the user has not chosen a fallback.
    FallbackRequired,
    /// The OS credential store is unavailable on this device.
    Unavailable,
    /// The selected backend could not be opened.
    Backend,
}

impl SecretStoreError {
    /// Plain-language message safe to show in the UI.
    pub fn message(self) -> &'static str {
        match self {
            Self::FallbackRequired => {
                "Choose how ChatUI should store provider keys: the encrypted-file fallback or session-only mode."
            }
            Self::Unavailable => "The OS credential store is unavailable on this device.",
            Self::Backend => "ChatUI could not open the selected secret store.",
        }
    }
}

impl fmt::Display for SecretStoreError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter.write_str(self.message())
    }
}

impl std::error::Error for SecretStoreError {}

impl From<SecretError> for SecretStoreError {
    fn from(error: SecretError) -> Self {
        match error {
            SecretError::FallbackRequired => Self::FallbackRequired,
            SecretError::Unavailable => Self::Unavailable,
            _ => Self::Backend,
        }
    }
}

/// Select a secret store through [`chatui_secrets::select_store`].
///
/// An explicit `choice` is always honored. With no choice, the OS store is used
/// when available; otherwise [`SecretStoreError::FallbackRequired`] is returned
/// so the caller can prompt the user.
pub fn select_secret_store(
    os_store: Arc<dyn SecretStore>,
    choice: Option<FallbackChoice>,
) -> Result<Arc<dyn SecretStore>, SecretStoreError> {
    select_store(os_store, choice).map_err(SecretStoreError::from)
}

/// The desktop default: OS keyring when available, otherwise an explicit prompt.
pub fn default_secret_store() -> Result<Arc<dyn SecretStore>, SecretStoreError> {
    select_secret_store(Arc::new(KeyringStore::new()), None)
}

/// Resolve the explicit session-only fallback chosen by the user.
pub fn session_only_secret_store() -> Arc<dyn SecretStore> {
    select_store(
        Arc::new(KeyringStore::new()),
        Some(FallbackChoice::SessionOnly),
    )
    .expect("an explicit session-only choice always resolves")
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::path::PathBuf;

    use chatui_secrets::{SecretId, SecretValue};

    struct UnavailableStore;

    impl SecretStore for UnavailableStore {
        fn set(&self, _id: &SecretId, _value: &SecretValue) -> Result<(), SecretError> {
            Err(SecretError::Unavailable)
        }

        fn get(&self, _id: &SecretId) -> Result<Option<SecretValue>, SecretError> {
            Err(SecretError::Unavailable)
        }

        fn delete(&self, _id: &SecretId) -> Result<(), SecretError> {
            Err(SecretError::Unavailable)
        }

        fn forget_key(&self, _id: &SecretId) -> Result<(), SecretError> {
            Err(SecretError::Unavailable)
        }

        fn forget_all_keys(&self) -> Result<(), SecretError> {
            Err(SecretError::Unavailable)
        }

        fn is_available(&self) -> bool {
            false
        }
    }

    #[test]
    fn unavailable_os_store_requires_explicit_choice() {
        let error = select_secret_store(Arc::new(UnavailableStore), None)
            .err()
            .expect("selection must fail");

        assert_eq!(error, SecretStoreError::FallbackRequired);
        assert!(error.message().contains("encrypted-file"));
        assert!(!format!("{error:?}").contains("sk-"));
    }

    #[test]
    fn explicit_session_only_choice_resolves() {
        let store = select_secret_store(
            Arc::new(UnavailableStore),
            Some(FallbackChoice::SessionOnly),
        )
        .expect("explicit choice resolves");

        assert!(store.is_available());
    }

    #[test]
    fn fallback_choice_debug_redacts_passphrase() {
        let choice = FallbackChoice::EncryptedFile {
            path: PathBuf::from("/tmp/chatui-secrets"),
            passphrase: SecretValue::new("correct horse battery staple"),
        };

        let rendered = format!("{choice:?}");
        assert!(!rendered.contains("correct horse battery staple"));
        assert!(rendered.contains("REDACTED"));
    }
}
