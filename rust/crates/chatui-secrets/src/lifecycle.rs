//! Provider-key lifecycle operations.
//!
//! [`KeyLifecycle`] wraps a [`SecretStore`] and exposes the three operations
//! the UI needs: rotate a provider key, forget one key, and forget every key.
//! Rotation is a replace, so the old value is overwritten in the backend and
//! never returned to the caller.

use std::sync::Arc;

use crate::store::{SecretError, SecretId, SecretStore, SecretValue};

/// Key lifecycle operations over an injected [`SecretStore`].
#[derive(Clone)]
pub struct KeyLifecycle {
    store: Arc<dyn SecretStore>,
}

impl KeyLifecycle {
    /// Create lifecycle operations for `store`.
    pub fn new(store: Arc<dyn SecretStore>) -> Self {
        Self { store }
    }

    /// Replace the provider key stored under `id`.
    ///
    /// The previous value is overwritten. A missing key is created.
    pub fn rotate_provider_key(
        &self,
        id: &SecretId,
        new_value: &SecretValue,
    ) -> Result<(), SecretError> {
        self.store.set(id, new_value)
    }

    /// Forget one provider key.
    ///
    /// Forgetting a missing key is not an error.
    pub fn forget_key(&self, id: &SecretId) -> Result<(), SecretError> {
        self.store.forget_key(id)
    }

    /// Forget every key managed by the store.
    pub fn forget_all_keys(&self) -> Result<(), SecretError> {
        self.store.forget_all_keys()
    }
}

impl std::fmt::Debug for KeyLifecycle {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        formatter.write_str("KeyLifecycle([REDACTED])")
    }
}

#[cfg(test)]
mod tests {
    use std::sync::Arc;

    use super::*;
    use crate::session::SessionStore;

    fn id(value: &str) -> SecretId {
        SecretId::new(value).expect("valid ID")
    }

    fn lifecycle() -> KeyLifecycle {
        KeyLifecycle::new(Arc::new(SessionStore::new()))
    }

    #[test]
    fn rotate_replaces_the_existing_value() {
        let store = Arc::new(SessionStore::new());
        let lifecycle = KeyLifecycle::new(store.clone());
        let id = id("provider-1");

        lifecycle
            .rotate_provider_key(&id, &SecretValue::new("old-value"))
            .expect("initial set");
        lifecycle
            .rotate_provider_key(&id, &SecretValue::new("new-value"))
            .expect("rotate");

        assert_eq!(
            store.get(&id).expect("get").expect("present").expose(),
            "new-value"
        );
    }

    #[test]
    fn rotate_creates_a_missing_key() {
        let store = Arc::new(SessionStore::new());
        let lifecycle = KeyLifecycle::new(store.clone());
        let id = id("provider-1");

        lifecycle
            .rotate_provider_key(&id, &SecretValue::new("value"))
            .expect("rotate");

        assert_eq!(
            store.get(&id).expect("get").expect("present").expose(),
            "value"
        );
    }

    #[test]
    fn forget_key_removes_only_the_requested_key() {
        let store = Arc::new(SessionStore::new());
        let lifecycle = KeyLifecycle::new(store.clone());
        let first = id("provider-1");
        let second = id("provider-2");

        lifecycle
            .rotate_provider_key(&first, &SecretValue::new("first"))
            .expect("set first");
        lifecycle
            .rotate_provider_key(&second, &SecretValue::new("second"))
            .expect("set second");

        lifecycle.forget_key(&first).expect("forget key");

        assert_eq!(store.get(&first).expect("get"), None);
        assert_eq!(
            store.get(&second).expect("get").expect("present").expose(),
            "second"
        );
    }

    #[test]
    fn forget_all_keys_removes_every_key() {
        let store = Arc::new(SessionStore::new());
        let lifecycle = KeyLifecycle::new(store.clone());
        let first = id("provider-1");
        let second = id("provider-2");

        lifecycle
            .rotate_provider_key(&first, &SecretValue::new("first"))
            .expect("set first");
        lifecycle
            .rotate_provider_key(&second, &SecretValue::new("second"))
            .expect("set second");

        lifecycle.forget_all_keys().expect("forget all");

        assert_eq!(store.get(&first).expect("get"), None);
        assert_eq!(store.get(&second).expect("get"), None);
    }

    #[test]
    fn forgetting_a_missing_key_is_not_an_error() {
        lifecycle()
            .forget_key(&id("provider-1"))
            .expect("forget missing key");
    }

    #[test]
    fn debug_output_is_redacted() {
        let rendered = format!("{:?}", lifecycle());
        assert_eq!(rendered, "KeyLifecycle([REDACTED])");
    }
}
