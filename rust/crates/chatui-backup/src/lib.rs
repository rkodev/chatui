//! Encrypted backup envelope for the ChatUI desktop client.
//!
//! This crate implements the `CHATUI1\n` backup envelope frozen in
//! `docs/operations/M5/M5-decisions.md` and exercised by
//! `contracts/fixtures/backup/vectors.json`. It is intentionally separate from
//! the `CHUIENC1` secret-file format in `chatui-secrets`.
//!
//! The codec only produces and consumes bytes. It never writes to storage, so
//! callers cannot accidentally persist plaintext through it.

pub mod conversation_payload;
pub mod envelope;
pub mod error;
pub mod manifest;
pub mod merge;
pub mod storage;
pub mod sync;

pub use conversation_payload::{
    AttachmentReference, ConversationPayload, PayloadMessage, build_conversation_payload,
    parse_conversation_payload, validate_conversation_payload,
};
pub use envelope::{
    ALG, DEFAULT_ITERATIONS, DecryptedObject, ENVELOPE_VERSION, EncryptOptions, EnvelopeHeader,
    EnvelopeParts, FORMAT_VERSION, HEADER_LENGTH_BYTES, IV_LEN, KDF, KEY_LEN, MAGIC,
    MAX_HEADER_BYTES, MAX_ITERATIONS, MAX_OBJECT_KEY_LENGTH, ObjectType, SALT_LEN, TAG_LEN,
    VERIFICATION_MARKER, VERIFICATION_OBJECT_KEY, VERIFICATION_OBJECT_TYPE, build_aad,
    create_verification_token, create_verification_token_with, decode_envelope, decrypt_object,
    encode_envelope, encrypt_object, verify_passphrase,
};
pub use error::{BackupDataError, EnvelopeError};
pub use manifest::{
    AttachmentIndexEntry, ConversationIndexEntry, MANIFEST_FORMAT_VERSION, ManifestBody,
    ManifestTombstone, attachment_object_key, build_manifest, conversation_object_key,
    parse_manifest, validate_manifest,
};
pub use merge::{
    MergeConversationOptions, MergeError, MergedConversation, MergedManifest, MergedMessages,
    compare_messages, merge_conversation, merge_manifests, merge_messages,
};
pub use storage::{
    AzureBlobStorageAdapter, AzureBlobStorageConfig, GcsServiceAccount, GcsStorageAdapter,
    GcsStorageConfig, GoogleDriveStorageAdapter, GoogleDriveStorageConfig, ListObjectsOptions,
    ListObjectsResult, PutObjectOptions, S3StorageAdapter, S3StorageConfig, StorageAdapter,
    StorageError, StorageErrorCode, StorageObject, WebDavStorageAdapter, WebDavStorageConfig,
};
pub use sync::{
    BackupIdentity, DELETE_REMOTE_CONFIRMATION, MANIFEST_OBJECT_KEY, RestoreOptions, RestoreResult,
    SyncEngine, SyncError, SyncResult,
};

#[cfg(test)]
mod merge_tests;
#[cfg(test)]
mod sync_tests;
#[cfg(test)]
mod tests;
