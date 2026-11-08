//! OS credential-store implementation of [`SecretStore`].
//!
//! [`KeyringStore`] uses the [`keyring`] crate to reach the platform backend:
//!
//! | Platform | Backend | Notes |
//! | --- | --- | --- |
//! | Linux | Secret Service over D-Bus (GNOME Keyring, KWallet, or another provider) | Requires a running D-Bus session and a Secret Service provider. Headless sessions and minimal containers usually have neither. |
//! | macOS | Keychain through the Security framework | The login keychain must be unlocked and the app must be allowed to access it. |
//! | Windows | Credential Manager | Uses the current user's credential vault. |
//!
//! # Availability and fallback
//!
//! There is no silent plaintext fallback. [`SecretStore::is_available`] probes
//! the backend and returns `false` when the platform store cannot be reached.
//! In that case every operation returns [`SecretError::Unavailable`] and the
//! caller must explicitly choose another backend, such as the encrypted-file
//! fallback owned by M3.5-04.2. The probe reads a reserved, never-written
//! account; a missing entry means the store is reachable.
//!
//! # Naming
//!
//! The service name is the stable application identifier
//! [`KEYRING_SERVICE`]. The account name is the caller's [`SecretId`], which is
//! validated to a small ASCII alphabet and must never contain a provider key,
//! a provider label, conversation content, or personal data.
//!
//! # Index
//!
//! `forget_all_keys` needs to know which IDs this store owns. The store keeps a
//! non-secret index in a reserved keyring account. The index contains only
//! validated secret IDs, one per line. It is updated on set and delete and is
//! removed when the last key is forgotten. Keys written by another tool are not
//! in the index and are therefore not removed by `forget_all_keys`.

use std::collections::{BTreeSet, HashMap};
use std::fmt;
use std::sync::{Arc, Mutex};

use keyring::Entry;

use crate::store::{
    RESERVED_INDEX_ACCOUNT, RESERVED_PROBE_ACCOUNT, SecretError, SecretId, SecretStore, SecretValue,
};

/// Stable application identifier used as the keyring service name.
pub const KEYRING_SERVICE: &str = "dev.chatui.desktop";

/// Creates keyring entries for a service/account pair.
///
/// Production code uses [`SystemEntryFactory`]. Tests inject a factory backed by
/// `keyring`'s mock credential builder so they never touch a real OS store.
pub trait EntryFactory: Send + Sync {
    /// Create an entry for `service` and `account`.
    fn create(&self, service: &str, account: &str) -> Result<Entry, SecretError>;
}

/// Factory for the real platform credential store.
#[derive(Debug, Default)]
pub struct SystemEntryFactory;

impl EntryFactory for SystemEntryFactory {
    fn create(&self, service: &str, account: &str) -> Result<Entry, SecretError> {
        Entry::new(service, account).map_err(map_keyring_error)
    }
}

/// [`SecretStore`] backed by the OS credential store.
pub struct KeyringStore {
    service: String,
    factory: Arc<dyn EntryFactory>,
    entries: Mutex<HashMap<String, Entry>>,
    index: Mutex<Option<BTreeSet<SecretId>>>,
}

impl KeyringStore {
    /// Create a store using the stable ChatUI service name and the real OS
    /// credential store.
    pub fn new() -> Self {
        Self::with_service(KEYRING_SERVICE)
    }

    /// Create a store with a custom service name and the real OS credential
    /// store.
    pub fn with_service(service: impl Into<String>) -> Self {
        Self::with_factory(service, Arc::new(SystemEntryFactory))
    }

    /// Create a store with an injected entry factory.
    ///
    /// This is primarily for tests. Production callers should use
    /// [`KeyringStore::new`].
    pub fn with_factory(service: impl Into<String>, factory: Arc<dyn EntryFactory>) -> Self {
        Self {
            service: service.into(),
            factory,
            entries: Mutex::new(HashMap::new()),
            index: Mutex::new(None),
        }
    }

    fn with_entry<T>(
        &self,
        account: &str,
        operation: impl FnOnce(&Entry) -> Result<T, SecretError>,
    ) -> Result<T, SecretError> {
        let mut entries = self
            .entries
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        if !entries.contains_key(account) {
            let entry = self.factory.create(&self.service, account)?;
            entries.insert(account.to_owned(), entry);
        }
        let entry = entries.get(account).expect("entry inserted above");
        operation(entry)
    }

    fn load_index(&self) -> Result<BTreeSet<SecretId>, SecretError> {
        let mut cached = self
            .index
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        if let Some(index) = cached.as_ref() {
            return Ok(index.clone());
        }

        let payload =
            self.with_entry(RESERVED_INDEX_ACCOUNT, |entry| match entry.get_password() {
                Ok(payload) => Ok(Some(payload)),
                Err(keyring::Error::NoEntry) => Ok(None),
                Err(error) => Err(map_keyring_error(error)),
            })?;

        let index = match payload {
            Some(payload) => parse_index(&payload)?,
            None => BTreeSet::new(),
        };
        *cached = Some(index.clone());
        Ok(index)
    }

    fn persist_index(&self, index: &BTreeSet<SecretId>) -> Result<(), SecretError> {
        if index.is_empty() {
            self.with_entry(RESERVED_INDEX_ACCOUNT, |entry| {
                match entry.delete_credential() {
                    Ok(()) | Err(keyring::Error::NoEntry) => Ok(()),
                    Err(error) => Err(map_keyring_error(error)),
                }
            })?;
        } else {
            let payload = index
                .iter()
                .map(SecretId::as_str)
                .collect::<Vec<_>>()
                .join("\n");
            self.with_entry(RESERVED_INDEX_ACCOUNT, |entry| {
                entry.set_password(&payload).map_err(map_keyring_error)
            })?;
        }

        let mut cached = self
            .index
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        *cached = Some(index.clone());
        Ok(())
    }

    fn delete_secret(&self, id: &SecretId) -> Result<(), SecretError> {
        self.with_entry(id.as_str(), |entry| match entry.delete_credential() {
            Ok(()) | Err(keyring::Error::NoEntry) => Ok(()),
            Err(error) => Err(map_keyring_error(error)),
        })
    }
}

impl Default for KeyringStore {
    fn default() -> Self {
        Self::new()
    }
}

impl fmt::Debug for KeyringStore {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter
            .debug_struct("KeyringStore")
            .field("service", &self.service)
            .finish_non_exhaustive()
    }
}

impl SecretStore for KeyringStore {
    fn set(&self, id: &SecretId, value: &SecretValue) -> Result<(), SecretError> {
        // Index the ID before writing the value so a successful write is never
        // left untracked. If the value write fails, roll the index back.
        let mut index = self.load_index()?;
        let newly_indexed = index.insert(id.clone());
        if newly_indexed {
            self.persist_index(&index)?;
        }

        if let Err(error) = self.with_entry(id.as_str(), |entry| {
            entry
                .set_password(value.expose())
                .map_err(map_keyring_error)
        }) {
            if newly_indexed {
                index.remove(id);
                let _ = self.persist_index(&index);
            }
            return Err(error);
        }
        Ok(())
    }

    fn get(&self, id: &SecretId) -> Result<Option<SecretValue>, SecretError> {
        self.with_entry(id.as_str(), |entry| match entry.get_password() {
            Ok(value) => Ok(Some(SecretValue::new(value))),
            Err(keyring::Error::NoEntry) => Ok(None),
            Err(error) => Err(map_keyring_error(error)),
        })
    }

    fn delete(&self, id: &SecretId) -> Result<(), SecretError> {
        self.delete_secret(id)?;

        let mut index = self.load_index()?;
        if index.remove(id) {
            self.persist_index(&index)?;
        }
        Ok(())
    }

    fn forget_key(&self, id: &SecretId) -> Result<(), SecretError> {
        self.delete(id)
    }

    fn forget_all_keys(&self) -> Result<(), SecretError> {
        let index = self.load_index()?;
        let mut remaining = BTreeSet::new();
        let mut first_error = None;

        for id in &index {
            match self.delete_secret(id) {
                Ok(()) => {}
                Err(error) => {
                    remaining.insert(id.clone());
                    if first_error.is_none() {
                        first_error = Some(error);
                    }
                }
            }
        }

        self.persist_index(&remaining)?;

        match first_error {
            Some(error) => Err(error),
            None => Ok(()),
        }
    }

    fn is_available(&self) -> bool {
        self.with_entry(RESERVED_PROBE_ACCOUNT, |entry| match entry.get_password() {
            Ok(_) | Err(keyring::Error::NoEntry) | Err(keyring::Error::BadEncoding(_)) => Ok(true),
            Err(keyring::Error::NoStorageAccess(_)) | Err(keyring::Error::PlatformFailure(_)) => {
                Ok(false)
            }
            Err(_) => Ok(false),
        })
        .unwrap_or(false)
    }
}

fn parse_index(payload: &str) -> Result<BTreeSet<SecretId>, SecretError> {
    let mut index = BTreeSet::new();
    for line in payload.lines() {
        if line.is_empty() {
            continue;
        }
        let id = SecretId::new(line).map_err(|_| SecretError::IndexCorrupt)?;
        index.insert(id);
    }
    Ok(index)
}

fn map_keyring_error(error: keyring::Error) -> SecretError {
    match error {
        keyring::Error::NoEntry => SecretError::Backend("no entry"),
        keyring::Error::NoStorageAccess(_) => SecretError::Unavailable,
        keyring::Error::PlatformFailure(_) => SecretError::Backend("platform failure"),
        keyring::Error::BadEncoding(_) => SecretError::Encoding,
        keyring::Error::TooLong(_, _) => SecretError::Backend("attribute too long"),
        keyring::Error::Invalid(_, _) => SecretError::Backend("invalid attribute"),
        keyring::Error::Ambiguous(_) => SecretError::Backend("ambiguous entry"),
        _ => SecretError::Backend("unknown error"),
    }
}

#[cfg(test)]
mod tests {
    use std::any::Any;
    use std::sync::Arc;

    use keyring::credential::{CredentialApi, CredentialBuilderApi};
    use keyring::mock::{MockCredential, MockCredentialBuilder};

    use super::*;

    #[derive(Default)]
    struct MockFactory {
        errors: Mutex<HashMap<String, keyring::Error>>,
    }

    impl MockFactory {
        fn fail_next(&self, account: &str, error: keyring::Error) {
            self.errors
                .lock()
                .unwrap_or_else(|poisoned| poisoned.into_inner())
                .insert(account.to_owned(), error);
        }
    }

    impl EntryFactory for MockFactory {
        fn create(&self, service: &str, account: &str) -> Result<Entry, SecretError> {
            let credential = MockCredentialBuilder {}
                .build(None, service, account)
                .map_err(map_keyring_error)?;
            if let Some(error) = self
                .errors
                .lock()
                .unwrap_or_else(|poisoned| poisoned.into_inner())
                .remove(account)
            {
                let mock = credential
                    .as_any()
                    .downcast_ref::<MockCredential>()
                    .expect("mock credential");
                mock.set_error(error);
            }
            Ok(Entry::new_with_credential(credential))
        }
    }

    /// A mock factory that keeps one mock credential per account so that a new
    /// store instance sees the same data, like a persistent OS store.
    #[derive(Default)]
    struct SharedMockFactory {
        credentials: Mutex<HashMap<String, Arc<MockCredential>>>,
    }

    struct SharedMock(Arc<MockCredential>);

    impl CredentialApi for SharedMock {
        fn set_secret(&self, secret: &[u8]) -> keyring::Result<()> {
            self.0.set_secret(secret)
        }

        fn get_secret(&self) -> keyring::Result<Vec<u8>> {
            self.0.get_secret()
        }

        fn delete_credential(&self) -> keyring::Result<()> {
            self.0.delete_credential()
        }

        fn as_any(&self) -> &dyn Any {
            self.0.as_any()
        }
    }

    impl EntryFactory for SharedMockFactory {
        fn create(&self, _service: &str, account: &str) -> Result<Entry, SecretError> {
            let credential = self
                .credentials
                .lock()
                .unwrap_or_else(|poisoned| poisoned.into_inner())
                .entry(account.to_owned())
                .or_insert_with(|| Arc::new(MockCredential::default()))
                .clone();
            Ok(Entry::new_with_credential(Box::new(SharedMock(credential))))
        }
    }

    fn store() -> (KeyringStore, Arc<MockFactory>) {
        let factory = Arc::new(MockFactory::default());
        let store = KeyringStore::with_factory(KEYRING_SERVICE, factory.clone());
        (store, factory)
    }

    fn id(value: &str) -> SecretId {
        SecretId::new(value).expect("valid ID")
    }

    #[test]
    fn set_get_delete_round_trip() {
        let (store, _) = store();
        let id = id("provider-1");

        assert_eq!(store.get(&id).expect("get"), None);
        store
            .set(&id, &SecretValue::new("sk-test-value"))
            .expect("set");
        assert_eq!(
            store.get(&id).expect("get").expect("present").expose(),
            "sk-test-value"
        );

        store.delete(&id).expect("delete");
        assert_eq!(store.get(&id).expect("get"), None);
    }

    #[test]
    fn delete_missing_key_is_idempotent() {
        let (store, _) = store();
        let id = id("provider-1");

        store.delete(&id).expect("first delete");
        store.delete(&id).expect("second delete");
        store.forget_key(&id).expect("forget missing key");
    }

    #[test]
    fn forget_key_removes_only_the_requested_key() {
        let (store, _) = store();
        let first = id("provider-1");
        let second = id("provider-2");

        store.set(&first, &SecretValue::new("first")).expect("set");
        store
            .set(&second, &SecretValue::new("second"))
            .expect("set");

        store.forget_key(&first).expect("forget key");

        assert_eq!(store.get(&first).expect("get"), None);
        assert_eq!(
            store.get(&second).expect("get").expect("present").expose(),
            "second"
        );
    }

    #[test]
    fn forget_all_keys_removes_every_indexed_key() {
        let (store, _) = store();
        let first = id("provider-1");
        let second = id("provider-2");

        store.set(&first, &SecretValue::new("first")).expect("set");
        store
            .set(&second, &SecretValue::new("second"))
            .expect("set");

        store.forget_all_keys().expect("forget all");

        assert_eq!(store.get(&first).expect("get"), None);
        assert_eq!(store.get(&second).expect("get"), None);
        assert!(
            !store
                .with_entry(RESERVED_INDEX_ACCOUNT, |entry| {
                    match entry.get_password() {
                        Ok(_) => Ok(true),
                        Err(keyring::Error::NoEntry) => Ok(false),
                        Err(error) => Err(map_keyring_error(error)),
                    }
                })
                .expect("index probe")
        );
    }

    #[test]
    fn index_survives_a_new_store_instance() {
        let factory = Arc::new(SharedMockFactory::default());
        let first = KeyringStore::with_factory(KEYRING_SERVICE, factory.clone());
        let id = id("provider-1");

        first
            .set(&id, &SecretValue::new("sk-test-value"))
            .expect("set");
        drop(first);

        let second = KeyringStore::with_factory(KEYRING_SERVICE, factory.clone());
        assert_eq!(
            second.get(&id).expect("get").expect("present").expose(),
            "sk-test-value"
        );
        second.forget_all_keys().expect("forget all");
        drop(second);

        let third = KeyringStore::with_factory(KEYRING_SERVICE, factory);
        assert_eq!(third.get(&id).expect("get"), None);
    }

    #[test]
    fn available_store_reports_true() {
        let (store, _) = store();
        assert!(store.is_available());
    }

    #[test]
    fn store_is_injectable_as_a_trait_object() {
        let (store, _) = store();
        let store: Arc<dyn SecretStore> = Arc::new(store);
        let id = id("provider-1");

        store
            .set(&id, &SecretValue::new("sk-test-value"))
            .expect("set");
        assert_eq!(
            store.get(&id).expect("get").expect("present").expose(),
            "sk-test-value"
        );
    }

    #[test]
    fn unavailable_store_reports_false() {
        let (store, factory) = store();
        factory.fail_next(
            RESERVED_PROBE_ACCOUNT,
            keyring::Error::NoStorageAccess(Box::new(std::io::Error::other("no secret service"))),
        );

        assert!(!store.is_available());
    }

    #[test]
    fn unavailable_store_returns_unavailable_error() {
        let (store, factory) = store();
        factory.fail_next(
            "provider-1",
            keyring::Error::NoStorageAccess(Box::new(std::io::Error::other("no secret service"))),
        );

        let error = store
            .set(&id("provider-1"), &SecretValue::new("sk-test-value"))
            .expect_err("set should fail");
        assert!(matches!(error, SecretError::Unavailable));
    }

    #[test]
    fn errors_do_not_contain_the_secret() {
        let (store, factory) = store();
        let secret = "sk-super-secret-value";

        factory.fail_next(
            "provider-1",
            keyring::Error::BadEncoding(secret.as_bytes().to_vec()),
        );
        let error = store.get(&id("provider-1")).expect_err("get should fail");
        assert!(matches!(error, SecretError::Encoding));
        assert!(!format!("{error}").contains(secret));

        factory.fail_next(
            "provider-2",
            keyring::Error::PlatformFailure(Box::new(std::io::Error::other(secret))),
        );
        let error = store
            .set(&id("provider-2"), &SecretValue::new(secret))
            .expect_err("set should fail");
        assert!(!format!("{error}").contains(secret));
    }

    #[test]
    fn corrupt_index_is_reported() {
        let (store, _) = store();
        store
            .with_entry(RESERVED_INDEX_ACCOUNT, |entry| {
                entry
                    .set_password("not a valid id")
                    .map_err(map_keyring_error)
            })
            .expect("seed corrupt index");

        let error = store
            .forget_all_keys()
            .expect_err("index should be corrupt");
        assert!(matches!(error, SecretError::IndexCorrupt));
    }
}
