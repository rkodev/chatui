//! Secret storage for the ChatUI desktop client.
//!
//! This crate owns the injectable [`SecretStore`] abstraction and the OS
//! credential-store implementation. It does not own the encrypted-file
//! fallback, session-only mode, redaction hooks, or backup exclusion; those
//! arrive in M3.5-04.2 and M3.5-04.3.
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
//! # Availability and fallback decision
//!
//! The OS store is not always present. Linux headless sessions, minimal
//! containers, and locked or denied keychains can all make it unreachable.
//! [`SecretStore::is_available`] probes the backend without writing a value.
//! When it returns `false`, operations return [`SecretError::Unavailable`].
//!
//! There is deliberately no silent plaintext fallback. A caller that sees an
//! unavailable store must explicitly select another backend, such as the
//! encrypted-file fallback owned by M3.5-04.2. This keeps the user's choice
//! visible and prevents keys from being written to disk in the clear.
//!
//! # Secret handling
//!
//! Secret values are wrapped in [`SecretValue`], which zeroizes its buffer on
//! drop and redacts itself in `Debug` output. Errors never carry a secret value
//! or raw credential material. Secret IDs are stable, non-secret identifiers
//! and are validated before they are used as keyring account names.

pub mod keyring_store;
pub mod store;

pub use keyring_store::{EntryFactory, KEYRING_SERVICE, KeyringStore, SystemEntryFactory};
pub use store::{MAX_SECRET_ID_LEN, SecretError, SecretId, SecretStore, SecretValue};
