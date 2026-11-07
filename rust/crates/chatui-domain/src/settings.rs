//! Settings and future backup configuration from spec section 6.2.

use serde::{Deserialize, Serialize};

use crate::conversation::ChatParams;
use crate::time::Timestamp;

/// Theme preference from spec section 6.2.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum Theme {
    /// Follow the operating system theme.
    System,
    /// Light theme.
    Light,
    /// Dark theme.
    Dark,
}

/// UI density preference from spec section 6.2.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum Density {
    /// Comfortable spacing.
    Comfortable,
    /// Compact spacing.
    Compact,
}

/// Chat font size preference from spec section 6.2.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum ChatFontSize {
    /// Small font.
    Sm,
    /// Medium font.
    Md,
    /// Large font.
    Lg,
}

/// Send-key preference from spec section 6.2.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "kebab-case")]
pub enum SendKey {
    /// Enter sends the message.
    Enter,
    /// Modifier plus Enter sends the message.
    ModEnter,
}

/// Future backup backend from spec section 6.2.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
pub enum BackupBackend {
    /// Amazon S3.
    #[serde(rename = "s3")]
    S3,
    /// Azure Blob Storage.
    #[serde(rename = "azure-blob")]
    AzureBlob,
    /// Google Cloud Storage.
    #[serde(rename = "gcs")]
    Gcs,
    /// WebDAV.
    #[serde(rename = "webdav")]
    Webdav,
}

/// Backup encryption algorithm from spec section 6.2.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
pub enum BackupEncryption {
    /// AES-256-GCM.
    #[serde(rename = "aes-256-gcm")]
    Aes256Gcm,
}

/// Backup key derivation function from spec section 6.2.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
pub enum BackupKdf {
    /// PBKDF2-SHA256.
    #[serde(rename = "pbkdf2-sha256")]
    Pbkdf2Sha256,
    /// Argon2id.
    #[serde(rename = "argon2id")]
    Argon2id,
}

/// Future backup configuration from spec section 6.2.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct BackupConfig {
    /// Whether backup is enabled.
    pub enabled: bool,
    /// Backup backend.
    pub backend: BackupBackend,
    /// Custom endpoint, or `null` for the backend default.
    pub endpoint: Option<String>,
    /// Region, or `null` for the backend default.
    pub region: Option<String>,
    /// Bucket or container name.
    pub bucket: String,
    /// Object key prefix.
    pub prefix: String,
    /// Secret-store reference for backup credentials.
    pub credential_secret_id: Option<String>,
    /// Encryption algorithm.
    pub encryption: BackupEncryption,
    /// Key derivation function.
    pub kdf: BackupKdf,
    /// KDF salt, or `null` when not yet initialized.
    pub kdf_salt: Option<String>,
    /// Last successful sync timestamp, or `null` when never synced.
    pub last_sync_at: Option<Timestamp>,
    /// Last manifest ETag, or `null` when never synced.
    pub last_manifest_etag: Option<String>,
    /// Stable device ID.
    pub device_id: String,
}

/// Settings entity from spec section 6.2.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Settings {
    /// Theme preference.
    pub theme: Theme,
    /// UI density preference.
    pub density: Density,
    /// Chat font size preference.
    pub chat_font_size: ChatFontSize,
    /// Send-key preference.
    pub send_key: SendKey,
    /// Default provider ID, or `null`.
    pub default_provider_id: Option<String>,
    /// Default model ID, or `null`.
    pub default_model_id: Option<String>,
    /// Default system prompt.
    pub default_system_prompt: String,
    /// Default chat parameters.
    pub default_params: ChatParams,
    /// Whether web builds remember keys.
    pub remember_keys_on_web: bool,
    /// Whether to show token usage.
    pub show_token_usage: bool,
    /// Whether to load remote images.
    pub load_remote_images: bool,
    /// Last active conversation ID, or `null`.
    pub last_active_conversation_id: Option<String>,
    /// Backup configuration, or `null` when backup is not configured.
    pub backup: Option<BackupConfig>,
    /// Whether onboarding is complete.
    pub onboarding_completed: bool,
}

#[cfg(test)]
mod tests {
    use super::*;

    fn fixed() -> Timestamp {
        Timestamp::parse("2026-11-04T12:00:00.000Z").expect("valid timestamp")
    }

    #[test]
    fn enum_values_match_shared_json() {
        assert_eq!(serde_json::to_string(&Theme::System).unwrap(), "\"system\"");
        assert_eq!(
            serde_json::to_string(&Density::Compact).unwrap(),
            "\"compact\""
        );
        assert_eq!(serde_json::to_string(&ChatFontSize::Lg).unwrap(), "\"lg\"");
        assert_eq!(
            serde_json::to_string(&SendKey::ModEnter).unwrap(),
            "\"mod-enter\""
        );
        assert_eq!(
            serde_json::to_string(&BackupBackend::AzureBlob).unwrap(),
            "\"azure-blob\""
        );
        assert_eq!(
            serde_json::to_string(&BackupEncryption::Aes256Gcm).unwrap(),
            "\"aes-256-gcm\""
        );
        assert_eq!(
            serde_json::to_string(&BackupKdf::Pbkdf2Sha256).unwrap(),
            "\"pbkdf2-sha256\""
        );
        assert_eq!(
            serde_json::to_string(&BackupKdf::Argon2id).unwrap(),
            "\"argon2id\""
        );
    }

    #[test]
    fn settings_round_trip_with_null_backup() {
        let settings = Settings {
            theme: Theme::System,
            density: Density::Comfortable,
            chat_font_size: ChatFontSize::Md,
            send_key: SendKey::Enter,
            default_provider_id: None,
            default_model_id: None,
            default_system_prompt: String::new(),
            default_params: ChatParams::default(),
            remember_keys_on_web: false,
            show_token_usage: true,
            load_remote_images: false,
            last_active_conversation_id: None,
            backup: None,
            onboarding_completed: false,
        };

        let json = serde_json::to_value(&settings).expect("serialize settings");
        assert_eq!(json["defaultProviderId"], serde_json::Value::Null);
        assert_eq!(json["backup"], serde_json::Value::Null);
        assert_eq!(json["defaultParams"]["streaming"], true);

        let parsed: Settings = serde_json::from_value(json).expect("deserialize settings");
        assert_eq!(parsed, settings);
    }

    #[test]
    fn backup_config_round_trips() {
        let backup = BackupConfig {
            enabled: true,
            backend: BackupBackend::S3,
            endpoint: None,
            region: Some("us-east-1".to_owned()),
            bucket: "chatui".to_owned(),
            prefix: "backups/".to_owned(),
            credential_secret_id: None,
            encryption: BackupEncryption::Aes256Gcm,
            kdf: BackupKdf::Argon2id,
            kdf_salt: None,
            last_sync_at: Some(fixed()),
            last_manifest_etag: None,
            device_id: "device-1".to_owned(),
        };

        let json = serde_json::to_value(&backup).expect("serialize backup");
        assert_eq!(json["backend"], "s3");
        assert_eq!(json["encryption"], "aes-256-gcm");
        assert_eq!(json["kdf"], "argon2id");
        assert_eq!(json["lastSyncAt"], "2026-11-04T12:00:00.000Z");
        assert_eq!(json["lastManifestEtag"], serde_json::Value::Null);

        let parsed: BackupConfig = serde_json::from_value(json).expect("deserialize backup");
        assert_eq!(parsed, backup);
    }
}
