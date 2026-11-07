//! Secret-store abstraction shared by the OS keyring and the encrypted fallback.
//!
//! The trait in this module is deliberately small and synchronous. Callers
//! inject a [`SecretStore`] implementation; they never reach into a platform
//! credential API directly. Values returned to callers are wrapped in
//! [`SecretValue`], which zeroizes its buffer on drop and redacts itself in
//! `Debug` output.
//!
//! Secret IDs are stable, non-secret identifiers. They are used as the account
//! name in the OS credential store, so they must never contain a provider key,
//! a provider label, conversation content, or personal data.

use std::fmt;

use thiserror::Error;
use zeroize::Zeroizing;

/// Maximum accepted length of a secret ID.
pub const MAX_SECRET_ID_LEN: usize = 128;

/// Reserved keyring account used for the non-secret secret-ID index.
pub(crate) const RESERVED_INDEX_ACCOUNT: &str = "__chatui_secret_index__";

/// Reserved keyring account used for the availability probe.
pub(crate) const RESERVED_PROBE_ACCOUNT: &str = "__chatui_availability_probe__";

/// Errors returned by a secret store.
///
/// No variant carries a secret value or raw credential material. Platform
/// errors are reduced to a static category so that a backend message cannot
/// leak a password into a log or a panic report.
#[derive(Debug, Error)]
pub enum SecretError {
    /// The OS credential store is not available on this machine or session.
    #[error("the OS credential store is unavailable")]
    Unavailable,
    /// The caller supplied an invalid secret ID.
    #[error("invalid secret ID: {0}")]
    InvalidId(String),
    /// The stored value could not be decoded as UTF-8.
    #[error("the stored secret could not be decoded")]
    Encoding,
    /// The non-secret secret-ID index is malformed.
    #[error("the secret index is corrupt")]
    IndexCorrupt,
    /// The backend returned an error that is not otherwise classified.
    #[error("OS credential store error: {0}")]
    Backend(&'static str),
    /// The encrypted-file fallback could not complete the operation.
    ///
    /// The message is a static category so that a passphrase, derived key,
    /// salt, nonce, or ciphertext cannot leak through an error.
    #[error("encrypted secret store error: {0}")]
    Fallback(&'static str),
    /// The OS credential store is unavailable and the caller has not chosen a
    /// fallback backend.
    ///
    /// Callers must explicitly select the encrypted-file fallback or
    /// session-only mode. There is no silent plaintext fallback.
    #[error(
        "the OS credential store is unavailable; choose the encrypted-file fallback or session-only mode"
    )]
    FallbackRequired,
}

/// A stable, non-secret identifier for one stored secret.
///
/// IDs are restricted to a small ASCII alphabet so they are safe to use as
/// keyring account names and as newline-delimited index entries. The reserved
/// index and probe account names are rejected.
#[derive(Clone, Debug, Eq, Hash, Ord, PartialEq, PartialOrd)]
pub struct SecretId(String);

impl SecretId {
    /// Validate and construct a secret ID.
    pub fn new(value: impl Into<String>) -> Result<Self, SecretError> {
        let value = value.into();
        if value.is_empty() {
            return Err(SecretError::InvalidId("must not be empty".to_owned()));
        }
        if value.len() > MAX_SECRET_ID_LEN {
            return Err(SecretError::InvalidId(format!(
                "must be at most {MAX_SECRET_ID_LEN} bytes"
            )));
        }
        if value == RESERVED_INDEX_ACCOUNT || value == RESERVED_PROBE_ACCOUNT {
            return Err(SecretError::InvalidId("reserved account name".to_owned()));
        }
        if !value
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || matches!(byte, b'-' | b'_' | b'.' | b':'))
        {
            return Err(SecretError::InvalidId(
                "only ASCII letters, digits, '-', '_', '.', and ':' are allowed".to_owned(),
            ));
        }
        Ok(Self(value))
    }

    /// Borrow the ID as a string.
    pub fn as_str(&self) -> &str {
        &self.0
    }
}

impl fmt::Display for SecretId {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter.write_str(&self.0)
    }
}

impl AsRef<str> for SecretId {
    fn as_ref(&self) -> &str {
        &self.0
    }
}

impl TryFrom<&str> for SecretId {
    type Error = SecretError;

    fn try_from(value: &str) -> Result<Self, Self::Error> {
        Self::new(value)
    }
}

impl TryFrom<String> for SecretId {
    type Error = SecretError;

    fn try_from(value: String) -> Result<Self, Self::Error> {
        Self::new(value)
    }
}

/// A secret value held in a zeroizing buffer.
///
/// The value is exposed only through [`SecretValue::expose`]. `Debug` output is
/// always redacted, so accidental formatting cannot print the secret.
#[derive(Clone, Eq, PartialEq)]
pub struct SecretValue(Zeroizing<String>);

impl SecretValue {
    /// Wrap a secret value.
    pub fn new(value: impl Into<String>) -> Self {
        Self(Zeroizing::new(value.into()))
    }

    /// Borrow the secret for the duration of a call.
    ///
    /// Callers must not copy the returned string into a long-lived buffer.
    pub fn expose(&self) -> &str {
        self.0.as_str()
    }

    /// Return whether the secret is empty.
    pub fn is_empty(&self) -> bool {
        self.0.is_empty()
    }
}

impl fmt::Debug for SecretValue {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter.write_str("SecretValue([REDACTED])")
    }
}

impl From<String> for SecretValue {
    fn from(value: String) -> Self {
        Self::new(value)
    }
}

impl From<&str> for SecretValue {
    fn from(value: &str) -> Self {
        Self::new(value)
    }
}

/// Injectable secret storage.
///
/// Implementations must be safe to share across threads. The trait is
/// object-safe so callers can hold `Arc<dyn SecretStore>`.
pub trait SecretStore: Send + Sync {
    /// Store or replace the secret under `id`.
    fn set(&self, id: &SecretId, value: &SecretValue) -> Result<(), SecretError>;

    /// Return the secret under `id`, or `None` when it is not stored.
    fn get(&self, id: &SecretId) -> Result<Option<SecretValue>, SecretError>;

    /// Delete the secret under `id`.
    ///
    /// Deleting a missing secret is not an error.
    fn delete(&self, id: &SecretId) -> Result<(), SecretError>;

    /// Forget one stored key.
    ///
    /// This is the lifecycle operation used by provider key rotation and
    /// removal. It removes the value and its non-secret index entry.
    fn forget_key(&self, id: &SecretId) -> Result<(), SecretError>;

    /// Forget every key managed by this store.
    fn forget_all_keys(&self) -> Result<(), SecretError>;

    /// Report whether the backing OS credential store is currently usable.
    ///
    /// A `false` result means callers must explicitly select another backend,
    /// such as the encrypted-file fallback from M3.5-04.2. There is no silent
    /// plaintext fallback.
    fn is_available(&self) -> bool;
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn secret_id_accepts_stable_identifiers() {
        for value in ["provider-1", "openrouter:key", "a.b_c-1", "A1"] {
            let id = SecretId::new(value).expect("valid ID");
            assert_eq!(id.as_str(), value);
        }
    }

    #[test]
    fn secret_id_rejects_invalid_values() {
        for value in [
            "",
            "has space",
            "has/slash",
            "line\nbreak",
            "__chatui_secret_index__",
            "__chatui_availability_probe__",
        ] {
            assert!(
                SecretId::new(value).is_err(),
                "{value:?} should be rejected"
            );
        }
        assert!(SecretId::new("a".repeat(MAX_SECRET_ID_LEN + 1)).is_err());
    }

    #[test]
    fn secret_value_debug_is_redacted() {
        let value = SecretValue::new("sk-do-not-print");
        let rendered = format!("{value:?}");
        assert_eq!(rendered, "SecretValue([REDACTED])");
        assert!(!rendered.contains("sk-do-not-print"));
    }

    #[test]
    fn secret_value_exposes_only_on_request() {
        let value = SecretValue::new("sk-test");
        assert_eq!(value.expose(), "sk-test");
        assert!(!value.is_empty());
    }
}
