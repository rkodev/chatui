//! Secret storage for the ChatUI desktop client.
//!
//! This crate owns the injectable [`SecretStore`] abstraction, the OS
//! credential-store implementation, the explicit encrypted-file fallback, the
//! session-only mode, and provider-key lifecycle operations. Redaction hooks
//! and backup exclusion arrive in M3.5-04.3.
//!
//! # Backends
//!
//! [`KeyringStore`] uses the [`keyring`] crate:
//!
//! | Platform | Backend |
//! | --- | --- |
//! | Linux | Secret Service over D-Bus (GNOME Keyring, KWallet, or another provider) |
//! | macOS | Keychain through the Security framework |
//! | Windows | Credential Manager |
//!
//! [`EncryptedFileStore`] is an explicit fallback that stores every secret in
//! one passphrase-encrypted file. [`SessionStore`] keeps secrets in memory only.
//! [`default_path`] returns the fallback file location under the platform
//! app-data directory.
//!
//! # Availability and fallback decision
//!
//! The OS store is not always present. Linux headless sessions, minimal
//! containers, and locked or denied keychains can all make it unreachable.
//! [`SecretStore::is_available`] probes the backend without writing a value.
//! When it returns `false`, operations return [`SecretError::Unavailable`].
//!
//! There is deliberately no silent plaintext fallback. [`select_store`] honors
//! an explicit [`FallbackChoice`], uses the OS store by default when it is
//! available, and otherwise returns [`SecretError::FallbackRequired`] so the
//! caller can prompt the user. This keeps the user's choice visible and
//! prevents keys from being written to disk in the clear.
//!
//! # Secret handling
//!
//! Secret values are wrapped in [`SecretValue`], which zeroizes its buffer on
//! drop and redacts itself in `Debug` output. Errors never carry a secret value
//! or raw credential material. Secret IDs are stable, non-secret identifiers
//! and are validated before they are used as keyring account names.

pub mod encrypted_file;
pub mod keyring_store;
pub mod lifecycle;
pub mod session;
pub mod store;

pub use encrypted_file::{
    ENCRYPTED_FALLBACK_EXPLANATION, ENCRYPTED_FILE_NAME, EncryptedFile, EncryptedFileError,
    EncryptedFileStore, FallbackChoice, KdfParams, SESSION_ONLY_EXPLANATION, default_path,
    select_store,
};
pub use keyring_store::{EntryFactory, KEYRING_SERVICE, KeyringStore, SystemEntryFactory};
pub use lifecycle::KeyLifecycle;
pub use session::SessionStore;
pub use store::{MAX_SECRET_ID_LEN, SecretError, SecretId, SecretStore, SecretValue};
