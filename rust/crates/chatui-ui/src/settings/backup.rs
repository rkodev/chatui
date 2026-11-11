//! Backup settings state, validation, and actions.
//!
//! The backup screen is a thin, framework-independent state machine over an
//! injected [`BackupService`]. The desktop app implements that trait with the
//! sync engine and the S3 adapter; tests use an in-memory fake.
//!
//! Credentials and the passphrase are held only in memory. [`BackupForm`]
//! converts to a persisted [`BackupConfig`] that never contains the secret
//! access key or the passphrase.

use chatui_domain::{BackupBackend, BackupConfig, BackupEncryption, BackupKdf, Timestamp};

/// Secret-store ID used for the S3 access key and secret access key.
pub const BACKUP_CREDENTIAL_SECRET_ID: &str = "backup-s3-credentials";

/// Exact typed confirmation required to delete the remote backup.
pub const DELETE_REMOTE_CONFIRMATION: &str = "DELETE REMOTE BACKUP";

/// Exact typed confirmation required to replace local data on restore.
pub const REPLACE_CONFIRMATION: &str = "REPLACE LOCAL DATA";

/// Backend labels shown by the selector. Only S3 is enabled in this release.
pub const BACKUP_BACKEND_LABELS: [&str; 1] = ["S3-compatible"];

/// User-safe backup failure.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum BackupError {
    /// A form field is missing or malformed.
    Validation(String),
    /// The storage endpoint rejected the connection test.
    Connection(String),
    /// A local or remote storage operation failed.
    Storage(String),
    /// The typed confirmation text did not match.
    ConfirmationRequired,
    /// Backup is not configured or not enabled.
    NotConfigured,
    /// The selected backend is not available in this release.
    UnsupportedBackend,
}

impl BackupError {
    /// Plain-language message safe to show in the UI.
    pub fn message(&self) -> &str {
        match self {
            Self::Validation(message) | Self::Connection(message) | Self::Storage(message) => {
                message
            }
            Self::ConfirmationRequired => "Type the exact confirmation text to continue.",
            Self::NotConfigured => "Configure and enable backup first.",
            Self::UnsupportedBackend => "Only S3-compatible backup is available in this release.",
        }
    }
}

impl std::fmt::Display for BackupError {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        formatter.write_str(self.message())
    }
}

impl std::error::Error for BackupError {}

/// Editable backup form. The passphrase and secret key stay in memory.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct BackupForm {
    /// Selected backend.
    pub backend: BackupBackend,
    /// S3-compatible endpoint.
    pub endpoint: String,
    /// Bucket region.
    pub region: String,
    /// Bucket name.
    pub bucket: String,
    /// Object key prefix.
    pub prefix: String,
    /// Access key id.
    pub access_key_id: String,
    /// Secret access key.
    pub secret_access_key: String,
    /// Encryption passphrase. Never persisted.
    pub passphrase: String,
}

impl Default for BackupForm {
    fn default() -> Self {
        Self {
            backend: BackupBackend::S3,
            endpoint: String::new(),
            region: String::new(),
            bucket: String::new(),
            prefix: String::new(),
            access_key_id: String::new(),
            secret_access_key: String::new(),
            passphrase: String::new(),
        }
    }
}

impl BackupForm {
    /// Build a form from a persisted configuration.
    ///
    /// Credentials and the passphrase are intentionally left blank; the user
    /// must re-enter them for a new session.
    pub fn from_config(config: &BackupConfig) -> Self {
        Self {
            backend: config.backend,
            endpoint: config.endpoint.clone().unwrap_or_default(),
            region: config.region.clone().unwrap_or_default(),
            bucket: config.bucket.clone(),
            prefix: config.prefix.clone(),
            access_key_id: String::new(),
            secret_access_key: String::new(),
            passphrase: String::new(),
        }
    }

    /// Validate the form before a connection test or enable.
    pub fn validate(&self) -> Result<(), BackupError> {
        if self.backend != BackupBackend::S3 {
            return Err(BackupError::UnsupportedBackend);
        }

        let endpoint = self.endpoint.trim();
        if endpoint.is_empty() {
            return Err(BackupError::Validation(
                "Enter the S3-compatible endpoint.".to_owned(),
            ));
        }
        match url::Url::parse(endpoint) {
            Ok(url) if url.scheme() == "https" || url.scheme() == "http" => {}
            _ => {
                return Err(BackupError::Validation(
                    "The endpoint must be an http or https URL.".to_owned(),
                ));
            }
        }

        if self.region.trim().is_empty() {
            return Err(BackupError::Validation(
                "Enter the bucket region.".to_owned(),
            ));
        }
        if self.bucket.trim().is_empty() {
            return Err(BackupError::Validation("Enter the bucket name.".to_owned()));
        }
        if self.access_key_id.trim().is_empty() {
            return Err(BackupError::Validation(
                "Enter the access key id.".to_owned(),
            ));
        }
        if self.secret_access_key.is_empty() {
            return Err(BackupError::Validation(
                "Enter the secret access key.".to_owned(),
            ));
        }
        if self.passphrase.is_empty() {
            return Err(BackupError::Validation(
                "Enter a backup passphrase. It is never stored.".to_owned(),
            ));
        }

        Ok(())
    }

    /// Convert the form into a persisted configuration.
    ///
    /// The secret access key and passphrase are never copied into the result.
    /// Existing sync metadata is preserved so enabling backup again does not
    /// reset the device identity or salt.
    pub fn to_config(&self, existing: Option<&BackupConfig>) -> BackupConfig {
        BackupConfig {
            enabled: true,
            backend: self.backend,
            endpoint: Some(self.endpoint.trim().to_owned()).filter(|value| !value.is_empty()),
            region: Some(self.region.trim().to_owned()).filter(|value| !value.is_empty()),
            bucket: self.bucket.trim().to_owned(),
            prefix: self.prefix.trim().to_owned(),
            credential_secret_id: Some(BACKUP_CREDENTIAL_SECRET_ID.to_owned()),
            encryption: BackupEncryption::Aes256Gcm,
            kdf: BackupKdf::Pbkdf2Sha256,
            kdf_salt: existing.and_then(|config| config.kdf_salt.clone()),
            last_sync_at: existing.and_then(|config| config.last_sync_at),
            last_manifest_etag: existing.and_then(|config| config.last_manifest_etag.clone()),
            device_id: existing
                .map(|config| config.device_id.clone())
                .unwrap_or_default(),
        }
    }
}

/// Backup status shown on the settings screen.
#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct BackupStatus {
    /// Whether backup is enabled.
    pub enabled: bool,
    /// Last successful backup time.
    pub last_backup: Option<Timestamp>,
    /// Number of queued local changes.
    pub pending_changes: u64,
    /// Last error, or `None` when the last operation succeeded.
    pub last_error: Option<String>,
    /// Approximate size of the local backup payload in bytes.
    pub storage_usage_bytes: Option<u64>,
}

impl BackupStatus {
    /// Last-backup display text.
    pub fn last_backup_text(&self) -> String {
        self.last_backup
            .map(|timestamp| timestamp.to_rfc3339())
            .unwrap_or_else(|| "Never".to_owned())
    }

    /// Pending-change display text.
    pub fn pending_changes_text(&self) -> String {
        match self.pending_changes {
            1 => "1 pending change".to_owned(),
            count => format!("{count} pending changes"),
        }
    }

    /// Storage-usage display text.
    pub fn storage_usage_text(&self) -> String {
        self.storage_usage_bytes
            .map(format_bytes)
            .unwrap_or_else(|| "Unknown".to_owned())
    }

    /// Last-error display text.
    pub fn last_error_text(&self) -> String {
        self.last_error.clone().unwrap_or_else(|| "None".to_owned())
    }
}

/// Result of a restore.
#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct RestoreOutcome {
    /// Conversation IDs merged into local storage.
    pub merged_conversations: Vec<String>,
    /// Whether local conversations were replaced.
    pub replaced: bool,
}

/// A destructive action waiting for typed confirmation.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum BackupConfirmation {
    /// Delete every remote backup object.
    DeleteRemote,
    /// Replace local conversations from the remote backup.
    ReplaceRestore,
}

impl BackupConfirmation {
    /// Exact text the user must type.
    pub const fn required_text(self) -> &'static str {
        match self {
            Self::DeleteRemote => DELETE_REMOTE_CONFIRMATION,
            Self::ReplaceRestore => REPLACE_CONFIRMATION,
        }
    }

    /// Label shown above the confirmation field.
    pub const fn label(self) -> &'static str {
        match self {
            Self::DeleteRemote => {
                "Type DELETE REMOTE BACKUP to delete the remote backup. Local data is kept."
            }
            Self::ReplaceRestore => {
                "Type REPLACE LOCAL DATA to replace local conversations from the backup."
            }
        }
    }
}

/// Backup operations implemented by the desktop app.
pub trait BackupService: Send + Sync {
    /// Read the persisted configuration, if any.
    fn config(&self) -> Result<Option<BackupConfig>, BackupError>;
    /// Read the current status.
    fn status(&self) -> Result<BackupStatus, BackupError>;
    /// Test the endpoint and credentials without writing an object.
    fn test_connection(&self, form: &BackupForm) -> Result<(), BackupError>;
    /// Persist the configuration and credentials, then enable backup.
    fn enable(&self, form: &BackupForm) -> Result<BackupStatus, BackupError>;
    /// Disable backup without deleting the configuration or remote data.
    fn disable(&self) -> Result<BackupStatus, BackupError>;
    /// Run a backup now.
    fn backup_now(&self, passphrase: &str) -> Result<BackupStatus, BackupError>;
    /// Restore remote data. `replace` must only be true after typed confirmation.
    fn restore(&self, passphrase: &str, replace: bool) -> Result<RestoreOutcome, BackupError>;
    /// Delete every remote backup object. Local data is never touched.
    fn delete_remote(&self, passphrase: &str, confirmation: &str) -> Result<(), BackupError>;
}

/// Backup settings state and actions.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct BackupSettingsState {
    /// Editable form.
    pub form: BackupForm,
    /// Current status.
    pub status: BackupStatus,
    /// Non-blocking success or guidance message.
    pub notice: Option<String>,
    /// Current error message.
    pub error: Option<String>,
    /// Destructive action waiting for typed confirmation.
    pub confirmation: Option<BackupConfirmation>,
    /// Text typed into the confirmation field.
    pub confirmation_input: String,
    /// Result of the last restore.
    pub last_restore: Option<RestoreOutcome>,
}

impl BackupSettingsState {
    /// Build state from a form and status.
    pub fn new(form: BackupForm, status: BackupStatus) -> Self {
        Self {
            form,
            status,
            notice: None,
            error: None,
            confirmation: None,
            confirmation_input: String::new(),
            last_restore: None,
        }
    }

    /// Build state from a persisted configuration.
    pub fn from_config(config: Option<&BackupConfig>, status: BackupStatus) -> Self {
        let form = config.map(BackupForm::from_config).unwrap_or_default();
        Self::new(form, status)
    }

    /// Refresh the status from the service.
    pub fn refresh_status(&mut self, service: &dyn BackupService) {
        match service.status() {
            Ok(status) => self.status = status,
            Err(error) => self.error = Some(error.message().to_owned()),
        }
    }

    /// Test the endpoint and credentials.
    pub fn test_connection(&mut self, service: &dyn BackupService) -> Result<(), BackupError> {
        self.clear_messages();
        if let Err(error) = self.form.validate() {
            return self.fail(error);
        }
        match service.test_connection(&self.form) {
            Ok(()) => {
                self.notice = Some("Connection succeeded.".to_owned());
                Ok(())
            }
            Err(error) => self.fail(error),
        }
    }

    /// Enable backup.
    pub fn enable(&mut self, service: &dyn BackupService) -> Result<(), BackupError> {
        self.clear_messages();
        if let Err(error) = self.form.validate() {
            return self.fail(error);
        }
        match service.enable(&self.form) {
            Ok(status) => {
                self.status = status;
                self.notice = Some("Backup enabled.".to_owned());
                Ok(())
            }
            Err(error) => self.fail(error),
        }
    }

    /// Disable backup.
    pub fn disable(&mut self, service: &dyn BackupService) -> Result<(), BackupError> {
        self.clear_messages();
        match service.disable() {
            Ok(status) => {
                self.status = status;
                self.notice = Some("Backup disabled. Remote data was not deleted.".to_owned());
                Ok(())
            }
            Err(error) => self.fail(error),
        }
    }

    /// Run a backup now.
    pub fn backup_now(&mut self, service: &dyn BackupService) -> Result<(), BackupError> {
        self.clear_messages();
        if self.form.passphrase.is_empty() {
            return self.fail(BackupError::Validation(
                "Enter your backup passphrase.".to_owned(),
            ));
        }
        match service.backup_now(&self.form.passphrase) {
            Ok(status) => {
                self.status = status;
                self.notice = Some("Backup complete.".to_owned());
                Ok(())
            }
            Err(error) => self.fail(error),
        }
    }

    /// Restore from the remote backup.
    ///
    /// The default merges. `replace` only stages a typed confirmation; the
    /// service is not called until [`Self::confirm`] receives the exact text.
    pub fn restore(
        &mut self,
        service: &dyn BackupService,
        replace: bool,
    ) -> Result<(), BackupError> {
        self.clear_messages();
        if self.form.passphrase.is_empty() {
            return self.fail(BackupError::Validation(
                "Enter your backup passphrase.".to_owned(),
            ));
        }
        if replace {
            self.confirmation = Some(BackupConfirmation::ReplaceRestore);
            self.confirmation_input.clear();
            self.notice = Some(BackupConfirmation::ReplaceRestore.label().to_owned());
            return Ok(());
        }

        match service.restore(&self.form.passphrase, false) {
            Ok(outcome) => {
                self.notice = Some(format!(
                    "Restore merged {} conversation(s). Local data was kept.",
                    outcome.merged_conversations.len()
                ));
                self.last_restore = Some(outcome);
                self.refresh_status(service);
                Ok(())
            }
            Err(error) => self.fail(error),
        }
    }

    /// Stage the delete-remote confirmation.
    pub fn request_delete_remote(&mut self) {
        self.clear_messages();
        self.confirmation = Some(BackupConfirmation::DeleteRemote);
        self.confirmation_input.clear();
        self.notice = Some(BackupConfirmation::DeleteRemote.label().to_owned());
    }

    /// Confirm the staged destructive action.
    pub fn confirm(&mut self, service: &dyn BackupService) -> Result<(), BackupError> {
        let Some(confirmation) = self.confirmation else {
            return self.fail(BackupError::ConfirmationRequired);
        };
        if self.confirmation_input.trim() != confirmation.required_text() {
            return self.fail(BackupError::ConfirmationRequired);
        }
        if self.form.passphrase.is_empty() {
            return self.fail(BackupError::Validation(
                "Enter your backup passphrase.".to_owned(),
            ));
        }

        match confirmation {
            BackupConfirmation::DeleteRemote => {
                match service.delete_remote(&self.form.passphrase, self.confirmation_input.trim()) {
                    Ok(()) => {
                        self.confirmation = None;
                        self.confirmation_input.clear();
                        self.notice =
                            Some("Remote backup deleted. Local data was not changed.".to_owned());
                        self.refresh_status(service);
                        Ok(())
                    }
                    Err(error) => self.fail(error),
                }
            }
            BackupConfirmation::ReplaceRestore => {
                match service.restore(&self.form.passphrase, true) {
                    Ok(outcome) => {
                        self.confirmation = None;
                        self.confirmation_input.clear();
                        self.notice = Some(format!(
                            "Restore replaced local data with {} conversation(s).",
                            outcome.merged_conversations.len()
                        ));
                        self.last_restore = Some(outcome);
                        self.refresh_status(service);
                        Ok(())
                    }
                    Err(error) => self.fail(error),
                }
            }
        }
    }

    /// Cancel the staged destructive action.
    pub fn cancel_confirmation(&mut self) {
        self.confirmation = None;
        self.confirmation_input.clear();
        self.notice = None;
    }

    /// Whether a confirmation field should be visible.
    pub fn confirmation_visible(&self) -> bool {
        self.confirmation.is_some()
    }

    /// Confirmation label, or an empty string when no action is staged.
    pub fn confirmation_label(&self) -> &'static str {
        self.confirmation
            .map(BackupConfirmation::label)
            .unwrap_or_default()
    }

    /// Client-side statement shown on the backup screen.
    pub const fn client_side_statement(&self) -> &'static str {
        "Backup credentials and the passphrase stay on this device. ChatUI encrypts every object before upload, so the storage provider only sees opaque bytes."
    }

    /// Passphrase-loss warning shown on the backup screen.
    pub const fn passphrase_warning(&self) -> &'static str {
        "If you lose the passphrase, the remote backup cannot be decrypted. ChatUI cannot recover it."
    }

    fn clear_messages(&mut self) {
        self.notice = None;
        self.error = None;
    }

    fn fail<T>(&mut self, error: BackupError) -> Result<T, BackupError> {
        self.error = Some(error.message().to_owned());
        Err(error)
    }
}

/// Backend index used by the Slint combo box.
pub const fn backend_index(backend: BackupBackend) -> usize {
    match backend {
        BackupBackend::S3 => 0,
        BackupBackend::AzureBlob | BackupBackend::Gcs | BackupBackend::Webdav => 0,
    }
}

/// Resolve a backend from the Slint combo box. Only S3 is selectable.
pub const fn backend_from_index(index: usize) -> BackupBackend {
    match index {
        0 => BackupBackend::S3,
        _ => BackupBackend::S3,
    }
}

fn format_bytes(bytes: u64) -> String {
    const UNITS: [&str; 5] = ["B", "KB", "MB", "GB", "TB"];
    let mut value = bytes as f64;
    let mut unit = 0;
    while value >= 1024.0 && unit < UNITS.len() - 1 {
        value /= 1024.0;
        unit += 1;
    }
    if unit == 0 {
        format!("{bytes} B")
    } else {
        format!("{value:.1} {}", UNITS[unit])
    }
}

#[cfg(test)]
mod tests {
    use std::sync::Mutex;

    use super::*;

    struct FakeService {
        calls: Mutex<Vec<String>>,
        status: Mutex<BackupStatus>,
        test_result: Mutex<Result<(), BackupError>>,
        restore_result: Mutex<Result<RestoreOutcome, BackupError>>,
    }

    impl Default for FakeService {
        fn default() -> Self {
            Self {
                calls: Mutex::new(Vec::new()),
                status: Mutex::new(BackupStatus::default()),
                test_result: Mutex::new(Ok(())),
                restore_result: Mutex::new(Ok(RestoreOutcome::default())),
            }
        }
    }

    impl FakeService {
        fn calls(&self) -> Vec<String> {
            self.calls.lock().unwrap().clone()
        }

        fn record(&self, call: impl Into<String>) {
            self.calls.lock().unwrap().push(call.into());
        }
    }

    impl BackupService for FakeService {
        fn config(&self) -> Result<Option<BackupConfig>, BackupError> {
            Ok(None)
        }

        fn status(&self) -> Result<BackupStatus, BackupError> {
            Ok(self.status.lock().unwrap().clone())
        }

        fn test_connection(&self, _form: &BackupForm) -> Result<(), BackupError> {
            self.record("test_connection");
            self.test_result.lock().unwrap().clone()
        }

        fn enable(&self, _form: &BackupForm) -> Result<BackupStatus, BackupError> {
            self.record("enable");
            let mut status = self.status.lock().unwrap();
            status.enabled = true;
            Ok(status.clone())
        }

        fn disable(&self) -> Result<BackupStatus, BackupError> {
            self.record("disable");
            let mut status = self.status.lock().unwrap();
            status.enabled = false;
            Ok(status.clone())
        }

        fn backup_now(&self, passphrase: &str) -> Result<BackupStatus, BackupError> {
            self.record(format!("backup_now:{passphrase}"));
            let mut status = self.status.lock().unwrap();
            status.last_backup = Some(Timestamp::now());
            Ok(status.clone())
        }

        fn restore(&self, passphrase: &str, replace: bool) -> Result<RestoreOutcome, BackupError> {
            self.record(format!("restore:{passphrase}:{replace}"));
            self.restore_result.lock().unwrap().clone()
        }

        fn delete_remote(&self, passphrase: &str, confirmation: &str) -> Result<(), BackupError> {
            self.record(format!("delete_remote:{passphrase}:{confirmation}"));
            Ok(())
        }
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

    fn state() -> BackupSettingsState {
        BackupSettingsState::new(form(), BackupStatus::default())
    }

    #[test]
    fn validation_rejects_missing_fields_and_other_backends() {
        let mut invalid = form();
        invalid.bucket.clear();
        assert!(matches!(
            invalid.validate(),
            Err(BackupError::Validation(message)) if message.contains("bucket")
        ));

        let mut invalid = form();
        invalid.endpoint = "ftp://example.com".to_owned();
        assert!(matches!(
            invalid.validate(),
            Err(BackupError::Validation(_))
        ));

        let mut invalid = form();
        invalid.passphrase.clear();
        assert!(matches!(
            invalid.validate(),
            Err(BackupError::Validation(_))
        ));

        let mut invalid = form();
        invalid.backend = BackupBackend::Gcs;
        assert_eq!(invalid.validate(), Err(BackupError::UnsupportedBackend));
    }

    #[test]
    fn config_never_contains_credentials_or_passphrase() {
        let config = form().to_config(None);
        let json = serde_json::to_string(&config).expect("serialize config");

        assert!(!json.contains("super-secret"));
        assert!(!json.contains("correct horse battery staple"));
        assert!(!json.contains("AKIAEXAMPLE"));
        assert_eq!(
            config.credential_secret_id.as_deref(),
            Some(BACKUP_CREDENTIAL_SECRET_ID)
        );
        assert!(config.enabled);
    }

    #[test]
    fn config_preserves_existing_sync_metadata() {
        let existing = BackupConfig {
            enabled: false,
            backend: BackupBackend::S3,
            endpoint: None,
            region: None,
            bucket: "old".to_owned(),
            prefix: String::new(),
            credential_secret_id: None,
            encryption: BackupEncryption::Aes256Gcm,
            kdf: BackupKdf::Pbkdf2Sha256,
            kdf_salt: Some("c2FsdA==".to_owned()),
            last_sync_at: Some(Timestamp::now()),
            last_manifest_etag: Some("\"etag\"".to_owned()),
            device_id: "device-1".to_owned(),
        };

        let config = form().to_config(Some(&existing));
        assert_eq!(config.kdf_salt, existing.kdf_salt);
        assert_eq!(config.last_sync_at, existing.last_sync_at);
        assert_eq!(config.last_manifest_etag, existing.last_manifest_etag);
        assert_eq!(config.device_id, "device-1");
    }

    #[test]
    fn connectivity_test_reports_success_and_failure() {
        let service = FakeService::default();
        let mut state = state();

        state
            .test_connection(&service)
            .expect("connection succeeds");
        assert_eq!(state.notice.as_deref(), Some("Connection succeeded."));
        assert!(state.error.is_none());

        *service.test_result.lock().unwrap() =
            Err(BackupError::Connection("access denied".to_owned()));
        state
            .test_connection(&service)
            .expect_err("connection fails");
        assert_eq!(state.error.as_deref(), Some("access denied"));
        assert!(state.notice.is_none());
    }

    #[test]
    fn enable_and_disable_update_status() {
        let service = FakeService::default();
        let mut state = state();

        state.enable(&service).expect("enable");
        assert!(state.status.enabled);
        assert_eq!(state.notice.as_deref(), Some("Backup enabled."));

        state.disable(&service).expect("disable");
        assert!(!state.status.enabled);
        assert_eq!(
            state.notice.as_deref(),
            Some("Backup disabled. Remote data was not deleted.")
        );
        assert_eq!(service.calls(), vec!["enable", "disable"]);
    }

    #[test]
    fn backup_now_passes_the_in_memory_passphrase() {
        let service = FakeService::default();
        let mut state = state();

        state.backup_now(&service).expect("backup now");
        assert_eq!(
            service.calls(),
            vec!["backup_now:correct horse battery staple"]
        );
        assert!(state.status.last_backup.is_some());
        assert_eq!(state.notice.as_deref(), Some("Backup complete."));
    }

    #[test]
    fn restore_defaults_to_merge() {
        let service = FakeService::default();
        *service.restore_result.lock().unwrap() = Ok(RestoreOutcome {
            merged_conversations: vec!["c1".to_owned(), "c2".to_owned()],
            replaced: false,
        });
        let mut state = state();

        state.restore(&service, false).expect("merge restore");
        assert_eq!(
            service.calls(),
            vec!["restore:correct horse battery staple:false"]
        );
        assert!(state.confirmation.is_none());
        assert_eq!(
            state.last_restore.as_ref().map(|outcome| outcome.replaced),
            Some(false)
        );
    }

    #[test]
    fn replace_restore_requires_typed_confirmation() {
        let service = FakeService::default();
        *service.restore_result.lock().unwrap() = Ok(RestoreOutcome {
            merged_conversations: vec!["c1".to_owned()],
            replaced: true,
        });
        let mut state = state();

        state.restore(&service, true).expect("stage replace");
        assert_eq!(state.confirmation, Some(BackupConfirmation::ReplaceRestore));
        assert!(service.calls().is_empty());

        state.confirmation_input = "wrong".to_owned();
        assert_eq!(
            state.confirm(&service),
            Err(BackupError::ConfirmationRequired)
        );
        assert!(service.calls().is_empty());

        state.confirmation_input = REPLACE_CONFIRMATION.to_owned();
        state.confirm(&service).expect("confirmed replace");
        assert_eq!(
            service.calls(),
            vec!["restore:correct horse battery staple:true"]
        );
        assert!(state.confirmation.is_none());
        assert_eq!(
            state.last_restore.as_ref().map(|outcome| outcome.replaced),
            Some(true)
        );
    }

    #[test]
    fn delete_remote_requires_typed_confirmation_and_keeps_local_data() {
        let service = FakeService::default();
        let mut state = state();

        state.request_delete_remote();
        assert_eq!(state.confirmation, Some(BackupConfirmation::DeleteRemote));
        assert!(service.calls().is_empty());

        state.confirmation_input = "delete".to_owned();
        assert_eq!(
            state.confirm(&service),
            Err(BackupError::ConfirmationRequired)
        );
        assert!(service.calls().is_empty());

        state.confirmation_input = DELETE_REMOTE_CONFIRMATION.to_owned();
        state.confirm(&service).expect("confirmed delete");
        assert_eq!(
            service.calls(),
            vec!["delete_remote:correct horse battery staple:DELETE REMOTE BACKUP"]
        );
        assert!(state.confirmation.is_none());
        assert_eq!(
            state.notice.as_deref(),
            Some("Remote backup deleted. Local data was not changed.")
        );
    }

    #[test]
    fn cancel_confirmation_clears_the_staged_action() {
        let mut state = state();
        state.request_delete_remote();
        state.confirmation_input = "partial".to_owned();

        state.cancel_confirmation();
        assert!(state.confirmation.is_none());
        assert!(state.confirmation_input.is_empty());
        assert!(state.notice.is_none());
    }

    #[test]
    fn status_text_helpers_are_human_readable() {
        let status = BackupStatus {
            enabled: true,
            last_backup: None,
            pending_changes: 1,
            last_error: None,
            storage_usage_bytes: Some(2048),
        };
        assert_eq!(status.last_backup_text(), "Never");
        assert_eq!(status.pending_changes_text(), "1 pending change");
        assert_eq!(status.storage_usage_text(), "2.0 KB");
        assert_eq!(status.last_error_text(), "None");
    }

    #[test]
    fn backend_indices_round_trip_to_s3() {
        assert_eq!(backend_index(BackupBackend::S3), 0);
        assert_eq!(backend_from_index(0), BackupBackend::S3);
        assert_eq!(backend_from_index(3), BackupBackend::S3);
    }
}
