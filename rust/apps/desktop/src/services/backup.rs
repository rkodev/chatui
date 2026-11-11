//! Desktop backup service over the sync engine and S3 adapter.
//!
//! The service is the synchronous boundary used by the settings controller. It
//! stores the S3 access key and secret access key in the existing secret store,
//! persists the non-secret [`BackupConfig`], and builds a [`SyncEngine`] for
//! each operation. The passphrase is accepted per call and is never persisted.

use std::sync::{Arc, Mutex};

use chatui_backup::{
    RestoreOptions, S3StorageAdapter, S3StorageConfig, StorageAdapter, SyncEngine, SyncError,
};
use chatui_domain::BackupConfig;
use chatui_secrets::{SecretId, SecretStore, SecretValue};
use chatui_storage::{
    AttachmentRepository, BackupConfigRepository, SqliteBackupConfigRepository,
    SqliteSyncLocalStore, SqliteSyncQueueRepository, StorageError, SyncLocalStore,
    SyncQueueRepository,
};
use chatui_ui::settings::{
    BACKUP_CREDENTIAL_SECRET_ID, BackupError, BackupForm, BackupService, BackupStatus,
    RestoreOutcome,
};
use serde::{Deserialize, Serialize};

use crate::services::ServiceRuntime;
use crate::services::sync::SyncBridge;

/// Credentials stored as one JSON secret.
#[derive(Debug, Clone, Serialize, Deserialize)]
struct BackupCredentials {
    access_key_id: String,
    secret_access_key: String,
}

/// Desktop backup service.
pub struct BackupServiceAdapter {
    bridge: SyncBridge,
    backup_config: Arc<dyn BackupConfigRepository>,
    secrets: Arc<dyn SecretStore>,
    sync_queue: Arc<dyn SyncQueueRepository>,
    attachments: Arc<dyn AttachmentRepository>,
    local_store: Arc<dyn SyncLocalStore>,
    last_error: Mutex<Option<String>>,
}

impl BackupServiceAdapter {
    /// Build the service from the shared desktop runtime and secret store.
    pub fn from_runtime(runtime: &ServiceRuntime, secrets: Arc<dyn SecretStore>) -> Self {
        let pool = runtime.pool().clone();
        let local_store: Arc<dyn SyncLocalStore> = Arc::new(SqliteSyncLocalStore::new(
            pool.clone(),
            runtime.attachment_store().clone(),
        ));
        Self {
            bridge: runtime.bridge(),
            backup_config: Arc::new(SqliteBackupConfigRepository::new(pool.clone())),
            secrets,
            sync_queue: Arc::new(SqliteSyncQueueRepository::new(pool)),
            attachments: runtime.attachment_repository(),
            local_store,
            last_error: Mutex::new(None),
        }
    }

    fn load_config(&self) -> Result<Option<BackupConfig>, BackupError> {
        self.bridge
            .block_on(self.backup_config.load())
            .map_err(storage_error)
    }

    fn enabled_config(&self) -> Result<BackupConfig, BackupError> {
        let config = self.load_config()?.ok_or(BackupError::NotConfigured)?;
        if !config.enabled {
            return Err(BackupError::NotConfigured);
        }
        Ok(config)
    }

    fn store_credentials(&self, form: &BackupForm) -> Result<(), BackupError> {
        let id = SecretId::new(BACKUP_CREDENTIAL_SECRET_ID)
            .map_err(|error| BackupError::Storage(error.to_string()))?;
        let credentials = BackupCredentials {
            access_key_id: form.access_key_id.trim().to_owned(),
            secret_access_key: form.secret_access_key.clone(),
        };
        let json = serde_json::to_string(&credentials)
            .map_err(|error| BackupError::Storage(error.to_string()))?;
        self.secrets
            .set(&id, &SecretValue::new(json))
            .map_err(|error| BackupError::Storage(error.to_string()))
    }

    fn load_credentials(&self) -> Result<BackupCredentials, BackupError> {
        let id = SecretId::new(BACKUP_CREDENTIAL_SECRET_ID)
            .map_err(|error| BackupError::Storage(error.to_string()))?;
        let value = self
            .secrets
            .get(&id)
            .map_err(|error| BackupError::Storage(error.to_string()))?
            .ok_or(BackupError::NotConfigured)?;
        serde_json::from_str(value.expose())
            .map_err(|error| BackupError::Storage(error.to_string()))
    }

    fn adapter_from_form(&self, form: &BackupForm) -> Result<S3StorageAdapter, BackupError> {
        S3StorageAdapter::new(S3StorageConfig {
            endpoint: form.endpoint.trim().to_owned(),
            bucket: form.bucket.trim().to_owned(),
            region: form.region.trim().to_owned(),
            access_key_id: form.access_key_id.trim().to_owned(),
            secret_access_key: form.secret_access_key.clone(),
            prefix: Some(form.prefix.trim().to_owned()).filter(|value| !value.is_empty()),
            ..S3StorageConfig::default()
        })
        .map_err(|error| BackupError::Connection(error.to_string()))
    }

    fn engine(&self, config: &BackupConfig, passphrase: &str) -> Result<SyncEngine, BackupError> {
        let credentials = self.load_credentials()?;
        let adapter = S3StorageAdapter::new(S3StorageConfig {
            endpoint: config.endpoint.clone().unwrap_or_default(),
            bucket: config.bucket.clone(),
            region: config.region.clone().unwrap_or_default(),
            access_key_id: credentials.access_key_id,
            secret_access_key: credentials.secret_access_key,
            prefix: Some(config.prefix.clone()).filter(|value| !value.is_empty()),
            ..S3StorageConfig::default()
        })
        .map_err(|error| BackupError::Connection(error.to_string()))?;

        Ok(SyncEngine::new(
            Arc::clone(&self.local_store),
            Arc::new(adapter),
            passphrase,
        ))
    }

    fn record_error(&self, error: &SyncError) -> BackupError {
        let message = error.to_string();
        *self
            .last_error
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner()) = Some(message.clone());
        BackupError::Storage(message)
    }

    fn clear_error(&self) {
        *self
            .last_error
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner()) = None;
    }
}

impl BackupService for BackupServiceAdapter {
    fn config(&self) -> Result<Option<BackupConfig>, BackupError> {
        self.load_config()
    }

    fn status(&self) -> Result<BackupStatus, BackupError> {
        let config = self.load_config()?;
        let pending_changes = self
            .bridge
            .block_on(self.sync_queue.list(1_000))
            .map_err(storage_error)?
            .len() as u64;
        let attachments = self
            .bridge
            .block_on(self.attachments.list_all_unbounded())
            .map_err(storage_error)?;
        let storage_usage_bytes = attachments.iter().map(|attachment| attachment.size).sum();

        Ok(BackupStatus {
            enabled: config.as_ref().is_some_and(|config| config.enabled),
            last_backup: config.as_ref().and_then(|config| config.last_sync_at),
            pending_changes,
            last_error: self
                .last_error
                .lock()
                .unwrap_or_else(|poisoned| poisoned.into_inner())
                .clone(),
            storage_usage_bytes: Some(storage_usage_bytes),
        })
    }

    fn test_connection(&self, form: &BackupForm) -> Result<(), BackupError> {
        form.validate()?;
        let adapter = self.adapter_from_form(form)?;
        self.bridge
            .block_on(adapter.test_connection())
            .map_err(|error| BackupError::Connection(error.to_string()))
    }

    fn enable(&self, form: &BackupForm) -> Result<BackupStatus, BackupError> {
        form.validate()?;
        self.store_credentials(form)?;
        let existing = self.load_config()?;
        let config = form.to_config(existing.as_ref());
        self.bridge
            .block_on(self.backup_config.save(&config))
            .map_err(storage_error)?;

        // Initialize the device identity and backup salt before the first sync.
        let engine = self.engine(&config, &form.passphrase)?;
        self.bridge
            .block_on(engine.ensure_identity())
            .map_err(|error| self.record_error(&error))?;

        self.clear_error();
        self.status()
    }

    fn disable(&self) -> Result<BackupStatus, BackupError> {
        let mut config = self.load_config()?.ok_or(BackupError::NotConfigured)?;
        config.enabled = false;
        self.bridge
            .block_on(self.backup_config.save(&config))
            .map_err(storage_error)?;
        self.status()
    }

    fn backup_now(&self, passphrase: &str) -> Result<BackupStatus, BackupError> {
        let config = self.enabled_config()?;
        let engine = self.engine(&config, passphrase)?;
        match self.bridge.block_on(engine.backup_now()) {
            Ok(_) => {
                self.clear_error();
                self.status()
            }
            Err(error) => Err(self.record_error(&error)),
        }
    }

    fn restore(&self, passphrase: &str, replace: bool) -> Result<RestoreOutcome, BackupError> {
        let config = self.enabled_config()?;
        let engine = self.engine(&config, passphrase)?;
        let result = self
            .bridge
            .block_on(engine.restore(RestoreOptions { replace }))
            .map_err(|error| self.record_error(&error))?;
        self.clear_error();
        Ok(RestoreOutcome {
            merged_conversations: result.merged_conversations,
            replaced: result.replaced,
        })
    }

    fn delete_remote(&self, passphrase: &str, confirmation: &str) -> Result<(), BackupError> {
        let config = self.enabled_config()?;
        let engine = self.engine(&config, passphrase)?;
        self.bridge
            .block_on(engine.delete_remote(confirmation))
            .map_err(|error| self.record_error(&error))?;
        self.clear_error();
        Ok(())
    }
}

fn storage_error(error: StorageError) -> BackupError {
    BackupError::Storage(error.to_string())
}

#[cfg(test)]
mod tests {
    use super::*;
    use chatui_domain::{BackupBackend, BackupEncryption, BackupKdf};
    use chatui_secrets::SessionStore;

    fn runtime() -> (tempfile::TempDir, ServiceRuntime) {
        let directory = tempfile::tempdir().expect("create temporary directory");
        let runtime =
            ServiceRuntime::open_at(directory.path().join("chatui.sqlite3"), directory.path())
                .expect("open runtime");
        (directory, runtime)
    }

    fn form() -> BackupForm {
        BackupForm {
            backend: BackupBackend::S3,
            endpoint: "https://s3.example.com".to_owned(),
            region: "us-east-1".to_owned(),
            bucket: "chatui-backups".to_owned(),
            prefix: "backups".to_owned(),
            access_key_id: "AKIAEXAMPLE".to_owned(),
            secret_access_key: "super-secret".to_owned(),
            passphrase: "correct horse battery staple".to_owned(),
        }
    }

    fn service(runtime: &ServiceRuntime) -> (Arc<SessionStore>, BackupServiceAdapter) {
        let secrets = Arc::new(SessionStore::new());
        let service = BackupServiceAdapter::from_runtime(runtime, secrets.clone());
        (secrets, service)
    }

    #[test]
    fn enable_stores_credentials_as_secrets_and_persists_config() {
        let (_directory, runtime) = runtime();
        let (secrets, service) = service(&runtime);

        let status = service.enable(&form()).expect("enable backup");
        assert!(status.enabled);

        let config = service.config().expect("load config").expect("config");
        assert!(config.enabled);
        assert_eq!(config.backend, BackupBackend::S3);
        assert_eq!(config.bucket, "chatui-backups");
        assert_eq!(config.prefix, "backups");
        assert_eq!(
            config.credential_secret_id.as_deref(),
            Some(BACKUP_CREDENTIAL_SECRET_ID)
        );
        assert_eq!(config.encryption, BackupEncryption::Aes256Gcm);
        assert_eq!(config.kdf, BackupKdf::Pbkdf2Sha256);

        let json = serde_json::to_string(&config).expect("serialize config");
        assert!(!json.contains("super-secret"));
        assert!(!json.contains("correct horse battery staple"));
        assert!(!json.contains("AKIAEXAMPLE"));

        let id = SecretId::new(BACKUP_CREDENTIAL_SECRET_ID).expect("secret id");
        let stored = secrets.get(&id).expect("read secret").expect("secret");
        assert!(stored.expose().contains("AKIAEXAMPLE"));
        assert!(stored.expose().contains("super-secret"));
    }

    #[test]
    fn disable_keeps_the_configuration_and_remote_data() {
        let (_directory, runtime) = runtime();
        let (_secrets, service) = service(&runtime);
        service.enable(&form()).expect("enable backup");

        let status = service.disable().expect("disable backup");
        assert!(!status.enabled);

        let config = service.config().expect("load config").expect("config");
        assert!(!config.enabled);
        assert_eq!(config.bucket, "chatui-backups");
    }

    #[test]
    fn status_reports_pending_changes_and_storage_usage() {
        let (_directory, runtime) = runtime();
        let (_secrets, service) = service(&runtime);

        let status = service.status().expect("status");
        assert!(!status.enabled);
        assert_eq!(status.pending_changes, 0);
        assert_eq!(status.storage_usage_bytes, Some(0));
        assert!(status.last_error.is_none());
    }

    #[test]
    fn test_connection_rejects_an_invalid_form_without_network() {
        let (_directory, runtime) = runtime();
        let (_secrets, service) = service(&runtime);

        let mut invalid = form();
        invalid.bucket.clear();
        assert!(matches!(
            service.test_connection(&invalid),
            Err(BackupError::Validation(_))
        ));

        let mut invalid = form();
        invalid.endpoint = "not-a-url".to_owned();
        assert!(matches!(
            service.test_connection(&invalid),
            Err(BackupError::Validation(_))
        ));
    }

    #[test]
    fn backup_now_requires_an_enabled_configuration() {
        let (_directory, runtime) = runtime();
        let (_secrets, service) = service(&runtime);

        assert_eq!(
            service.backup_now("passphrase"),
            Err(BackupError::NotConfigured)
        );
    }

    #[test]
    fn delete_remote_requires_the_typed_confirmation_before_network() {
        let (_directory, runtime) = runtime();
        let (_secrets, service) = service(&runtime);
        service.enable(&form()).expect("enable backup");

        let error = service
            .delete_remote("passphrase", "wrong")
            .expect_err("confirmation required");
        assert!(matches!(error, BackupError::Storage(message) if message.contains("confirmation")));
    }
}
