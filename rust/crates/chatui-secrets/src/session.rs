//! Session-only secret storage.
//!
//! [`SessionStore`] keeps provider keys in memory and never writes them to
//! disk. Keys are cleared when the store is dropped (application exit) and when
//! [`SessionStore::lock`] is called (an explicit lock action). Values are held
//! in [`SecretValue`], which zeroizes its buffer on drop.

use std::collections::HashMap;
use std::fmt;
use std::sync::Mutex;

use crate::store::{SecretError, SecretId, SecretStore, SecretValue};

/// [`SecretStore`] that holds secrets in memory only.
#[derive(Default)]
pub struct SessionStore {
    keys: Mutex<HashMap<SecretId, SecretValue>>,
}

impl SessionStore {
    /// Create an empty session store.
    pub fn new() -> Self {
        Self::default()
    }

    /// Clear every key held in memory.
    ///
    /// This is the explicit lock operation. It is safe to call repeatedly.
    pub fn lock(&self) {
        self.keys
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
            .clear();
    }

    /// Return whether the store currently holds no keys.
    pub fn is_empty(&self) -> bool {
        self.keys
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
            .is_empty()
    }
}

impl Drop for SessionStore {
    fn drop(&mut self) {
        // Dropping each SecretValue zeroizes its buffer.
        self.keys
            .get_mut()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
            .clear();
    }
}

impl fmt::Debug for SessionStore {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter.write_str("SessionStore([REDACTED])")
    }
}

impl SecretStore for SessionStore {
    fn set(&self, id: &SecretId, value: &SecretValue) -> Result<(), SecretError> {
        self.keys
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
            .insert(id.clone(), value.clone());
        Ok(())
    }

    fn get(&self, id: &SecretId) -> Result<Option<SecretValue>, SecretError> {
        Ok(self
            .keys
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
            .get(id)
            .cloned())
    }

    fn delete(&self, id: &SecretId) -> Result<(), SecretError> {
        self.keys
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
            .remove(id);
        Ok(())
    }

    fn forget_key(&self, id: &SecretId) -> Result<(), SecretError> {
        self.delete(id)
    }

    fn forget_all_keys(&self) -> Result<(), SecretError> {
        self.lock();
        Ok(())
    }

    fn is_available(&self) -> bool {
        true
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn id(value: &str) -> SecretId {
        SecretId::new(value).expect("valid ID")
    }

    #[test]
    fn set_get_delete_round_trip() {
        let store = SessionStore::new();
        let id = id("provider-1");

        assert_eq!(store.get(&id).expect("get"), None);
        store
            .set(&id, &SecretValue::new("sk-session-value"))
            .expect("set");
        assert_eq!(
            store.get(&id).expect("get").expect("present").expose(),
            "sk-session-value"
        );

        store.delete(&id).expect("delete");
        assert_eq!(store.get(&id).expect("get"), None);
    }

    #[test]
    fn lock_clears_every_key() {
        let store = SessionStore::new();
        store
            .set(&id("provider-1"), &SecretValue::new("first"))
            .expect("set");
        store
            .set(&id("provider-2"), &SecretValue::new("second"))
            .expect("set");
        assert!(!store.is_empty());

        store.lock();

        assert!(store.is_empty());
        assert_eq!(store.get(&id("provider-1")).expect("get"), None);
        assert_eq!(store.get(&id("provider-2")).expect("get"), None);
    }

    #[test]
    fn forget_key_removes_only_the_requested_key() {
        let store = SessionStore::new();
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
    fn forget_all_keys_clears_the_store() {
        let store = SessionStore::new();
        store
            .set(&id("provider-1"), &SecretValue::new("first"))
            .expect("set");
        store
            .set(&id("provider-2"), &SecretValue::new("second"))
            .expect("set");

        store.forget_all_keys().expect("forget all");

        assert!(store.is_empty());
    }

    #[test]
    fn session_store_is_always_available() {
        assert!(SessionStore::new().is_available());
    }

    #[test]
    fn debug_output_is_redacted() {
        let store = SessionStore::new();
        store
            .set(&id("provider-1"), &SecretValue::new("sk-do-not-print"))
            .expect("set");
        let rendered = format!("{store:?}");
        assert_eq!(rendered, "SessionStore([REDACTED])");
        assert!(!rendered.contains("sk-do-not-print"));
    }
}
