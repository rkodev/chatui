//! Backup manifest body build and parse.
//!
//! The manifest body is the plaintext stored inside `manifest.enc`. It contains
//! only indexes and tombstones, never conversation content, provider secrets, or
//! API keys. Field names and object-key patterns match
//! `contracts/schema/backup-manifest-body.schema.json`.

use std::sync::OnceLock;

use chatui_domain::{Attachment, Conversation, Timestamp, Tombstone};
use serde::{Deserialize, Serialize};
use serde_json::Value;

use crate::error::BackupDataError;

/// Manifest body format version written by this build.
pub const MANIFEST_FORMAT_VERSION: u32 = 1;

/// One conversation index entry.
#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ConversationIndexEntry {
    /// Conversation UUID. Never used as an object name.
    pub id: String,
    /// Random 128-bit storage key as 32 lowercase hexadecimal characters.
    pub storage_key: String,
    /// Monotonic revision starting at 1.
    pub revision: u64,
    /// Last update timestamp.
    pub updated_at: Timestamp,
    /// Deletion timestamp, or `null` when the conversation is live.
    pub deleted_at: Option<Timestamp>,
    /// Device that last wrote this entry.
    pub device_id: String,
    /// Opaque conversation object key.
    pub object_key: String,
}

/// One attachment index entry.
#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct AttachmentIndexEntry {
    /// SHA-256 of the attachment bytes as 64 lowercase hexadecimal characters.
    pub sha256: String,
    /// Attachment size in bytes.
    pub size: u64,
    /// Opaque attachment object key.
    pub object_key: String,
    /// Attachment creation timestamp.
    pub created_at: Timestamp,
}

/// One conversation tombstone in the manifest.
#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ManifestTombstone {
    /// Deleted conversation ID.
    pub conversation_id: String,
    /// Deletion timestamp.
    pub deleted_at: Timestamp,
    /// Device that observed the deletion.
    pub device_id: String,
}

/// Plaintext backup manifest body.
#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ManifestBody {
    /// Backup payload format version.
    pub format_version: u32,
    /// Manifest creation timestamp.
    pub created_at: Timestamp,
    /// Manifest last-update timestamp.
    pub updated_at: Timestamp,
    /// Device that last wrote the manifest.
    pub device_id: String,
    /// Conversation index.
    pub conversations: Vec<ConversationIndexEntry>,
    /// Attachment index.
    pub attachments: Vec<AttachmentIndexEntry>,
    /// Deletion tombstones.
    pub tombstones: Vec<ManifestTombstone>,
}

/// Build a manifest body from local records.
///
/// Conversation storage keys and attachment SHA-256 values are validated before
/// they become object names. Tombstones are matched to conversation entries so
/// the index carries the deletion timestamp.
pub fn build_manifest(
    device_id: &str,
    created_at: Timestamp,
    updated_at: Timestamp,
    conversations: &[Conversation],
    attachments: &[Attachment],
    tombstones: &[Tombstone],
) -> Result<ManifestBody, BackupDataError> {
    if device_id.is_empty() {
        return Err(BackupDataError::Invalid(
            "manifest deviceId must not be empty".to_owned(),
        ));
    }

    let mut conversation_entries = Vec::with_capacity(conversations.len());
    for conversation in conversations {
        validate_storage_key(&conversation.storage_key)?;
        let deleted_at = tombstones
            .iter()
            .find(|tombstone| {
                tombstone.conversation_id == conversation.id && tombstone.message_id.is_none()
            })
            .map(|tombstone| tombstone.deleted_at);

        conversation_entries.push(ConversationIndexEntry {
            id: conversation.id.clone(),
            storage_key: conversation.storage_key.clone(),
            revision: conversation.revision.max(1),
            updated_at: conversation.updated_at,
            deleted_at,
            device_id: device_id.to_owned(),
            object_key: conversation_object_key(&conversation.storage_key),
        });
    }

    let mut attachment_entries = Vec::with_capacity(attachments.len());
    for attachment in attachments {
        validate_sha256(&attachment.sha256)?;
        attachment_entries.push(AttachmentIndexEntry {
            sha256: attachment.sha256.clone(),
            size: attachment.size,
            object_key: attachment_object_key(&attachment.sha256),
            created_at: attachment.created_at,
        });
    }

    let tombstones = tombstones
        .iter()
        .map(|tombstone| ManifestTombstone {
            conversation_id: tombstone.conversation_id.clone(),
            deleted_at: tombstone.deleted_at,
            device_id: tombstone.device_id.clone(),
        })
        .collect();

    let body = ManifestBody {
        format_version: MANIFEST_FORMAT_VERSION,
        created_at,
        updated_at,
        device_id: device_id.to_owned(),
        conversations: conversation_entries,
        attachments: attachment_entries,
        tombstones,
    };
    validate_manifest(&body)?;
    Ok(body)
}

/// Parse and validate a manifest body.
pub fn parse_manifest(bytes: &[u8]) -> Result<ManifestBody, BackupDataError> {
    let body: ManifestBody =
        serde_json::from_slice(bytes).map_err(|error| BackupDataError::Json(error.to_string()))?;
    if body.format_version != MANIFEST_FORMAT_VERSION {
        return Err(BackupDataError::UnsupportedVersion);
    }
    validate_manifest(&body)?;
    Ok(body)
}

/// Validate a manifest body against the shared schema.
pub fn validate_manifest(body: &ManifestBody) -> Result<(), BackupDataError> {
    let value =
        serde_json::to_value(body).map_err(|error| BackupDataError::Json(error.to_string()))?;
    validate_against_schema(manifest_validator(), &value)
}

/// Opaque conversation object key for a storage key.
pub fn conversation_object_key(storage_key: &str) -> String {
    format!("conversations/{storage_key}.enc")
}

/// Opaque attachment object key for a SHA-256 digest.
pub fn attachment_object_key(sha256: &str) -> String {
    format!("attachments/{sha256}.enc")
}

fn validate_storage_key(value: &str) -> Result<(), BackupDataError> {
    if value.len() == 32
        && value
            .bytes()
            .all(|byte| byte.is_ascii_digit() || (b'a'..=b'f').contains(&byte))
    {
        Ok(())
    } else {
        Err(BackupDataError::Invalid(format!(
            "conversation storageKey must be 32 lowercase hexadecimal characters, got {value:?}"
        )))
    }
}

fn validate_sha256(value: &str) -> Result<(), BackupDataError> {
    if value.len() == 64
        && value
            .bytes()
            .all(|byte| byte.is_ascii_digit() || (b'a'..=b'f').contains(&byte))
    {
        Ok(())
    } else {
        Err(BackupDataError::Invalid(format!(
            "attachment sha256 must be 64 lowercase hexadecimal characters, got {value:?}"
        )))
    }
}

fn manifest_validator() -> &'static jsonschema::Validator {
    static VALIDATOR: OnceLock<jsonschema::Validator> = OnceLock::new();
    VALIDATOR.get_or_init(|| {
        let schema: Value = serde_json::from_str(include_str!(
            "../../../../contracts/schema/backup-manifest-body.schema.json"
        ))
        .expect("embedded manifest body schema is valid JSON");
        jsonschema::options()
            .should_validate_formats(true)
            .build(&schema)
            .expect("embedded manifest body schema compiles")
    })
}

pub(crate) fn validate_against_schema(
    validator: &jsonschema::Validator,
    value: &Value,
) -> Result<(), BackupDataError> {
    validator
        .validate(value)
        .map_err(|error| BackupDataError::Schema(error.to_string()))
}
