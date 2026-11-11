//! Rust sync engine.
//!
//! The engine reads the encrypted remote manifest, merges it with the local
//! index by conversation revision, uploads dirty encrypted objects, and
//! rewrites the manifest with an `If-Match` precondition. Restore is
//! non-destructive by default and a wrong passphrase fails before any local
//! conversation mutation.
//!
//! The engine never uploads plaintext: every object goes through the envelope
//! codec and the storage adapter only accepts byte slices.

use std::collections::{BTreeMap, BTreeSet};
use std::sync::Arc;
use std::time::Duration;

use base64::Engine;
use base64::engine::general_purpose::STANDARD;
use chatui_domain::{
    Attachment, AttachmentKind, BackupBackend, BackupConfig, BackupEncryption, BackupKdf,
    ContentPart, Conversation, Message, Timestamp, Tombstone,
};
use chatui_storage::{StorageError as LocalStorageError, SyncLocalStore};
use rand::RngCore;
use thiserror::Error;

use crate::conversation_payload::{
    AttachmentReference, ConversationPayload, PayloadMessage, build_conversation_payload,
    parse_conversation_payload,
};
use crate::envelope::{
    DecryptedObject, EncryptOptions, ObjectType, SALT_LEN, decrypt_object, encrypt_object,
};
use crate::error::{BackupDataError, EnvelopeError};
use crate::manifest::{
    ConversationIndexEntry, MANIFEST_FORMAT_VERSION, ManifestBody, ManifestTombstone,
    build_manifest, parse_manifest,
};
use crate::merge::{MergeConversationOptions, MergeError, merge_conversation, merge_manifests};
use crate::storage::{ListObjectsOptions, PutObjectOptions, StorageAdapter, StorageError};

/// Object key of the encrypted manifest.
pub const MANIFEST_OBJECT_KEY: &str = "manifest.enc";

/// Exact typed confirmation required by [`SyncEngine::delete_remote`].
pub const DELETE_REMOTE_CONFIRMATION: &str = "DELETE REMOTE BACKUP";

const DEFAULT_MAX_CONFLICT_RETRIES: usize = 4;
const DEFAULT_BASE_BACKOFF: Duration = Duration::from_millis(50);

/// Errors returned by the sync engine.
#[derive(Debug, Error)]
pub enum SyncError {
    /// Envelope codec failure.
    #[error(transparent)]
    Envelope(#[from] EnvelopeError),
    /// Manifest or payload parsing failure.
    #[error(transparent)]
    Data(#[from] BackupDataError),
    /// Remote storage failure.
    #[error(transparent)]
    Storage(#[from] StorageError),
    /// Local persistence failure.
    #[error(transparent)]
    Local(#[from] LocalStorageError),
    /// Merge invariant failure.
    #[error(transparent)]
    Merge(#[from] MergeError),
    /// JSON serialization failure.
    #[error("JSON error: {0}")]
    Json(#[from] serde_json::Error),
    /// A remote object carried an unexpected envelope header.
    #[error("remote object has an unexpected envelope header")]
    UnexpectedHeader,
    /// Remote deletion was requested without the typed confirmation token.
    #[error("remote backup deletion requires the typed confirmation token")]
    ConfirmationRequired,
    /// The stored backup salt is not valid base64.
    #[error("backup salt is not valid base64")]
    InvalidSalt,
    /// The stored backup salt is not the expected length.
    #[error("backup salt must be {SALT_LEN} bytes")]
    InvalidSaltLength,
}

/// Device identity and backup-set salt.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct BackupIdentity {
    /// Stable device ID.
    pub device_id: String,
    /// 16-byte backup-set salt.
    pub salt: [u8; SALT_LEN],
}

/// Result of a successful sync.
#[derive(Clone, Debug, Default, Eq, PartialEq)]
pub struct SyncResult {
    /// Conversation IDs uploaded.
    pub uploaded_conversations: Vec<String>,
    /// Attachment SHA-256 values uploaded.
    pub uploaded_attachments: Vec<String>,
    /// Conversation IDs downloaded and merged.
    pub downloaded_conversations: Vec<String>,
    /// ETag of the written manifest.
    pub manifest_etag: Option<String>,
    /// Number of sync-queue entries drained.
    pub drained_queue: u64,
}

/// Options for [`SyncEngine::restore`].
#[derive(Clone, Copy, Debug, Default, Eq, PartialEq)]
pub struct RestoreOptions {
    /// Replace local conversations instead of merging. Requires explicit opt-in.
    pub replace: bool,
}

/// Result of a restore.
#[derive(Clone, Debug, Default, Eq, PartialEq)]
pub struct RestoreResult {
    /// Conversation IDs merged into local storage.
    pub merged_conversations: Vec<String>,
    /// Whether local conversations were replaced.
    pub replaced: bool,
}

/// Rust sync engine.
pub struct SyncEngine {
    store: Arc<dyn SyncLocalStore>,
    storage: Arc<dyn StorageAdapter>,
    passphrase: String,
    max_conflict_retries: usize,
    base_backoff: Duration,
    iterations: Option<u32>,
}

impl SyncEngine {
    /// Build an engine over a local store and a remote storage adapter.
    pub fn new(
        store: Arc<dyn SyncLocalStore>,
        storage: Arc<dyn StorageAdapter>,
        passphrase: impl Into<String>,
    ) -> Self {
        Self {
            store,
            storage,
            passphrase: passphrase.into(),
            max_conflict_retries: DEFAULT_MAX_CONFLICT_RETRIES,
            base_backoff: DEFAULT_BASE_BACKOFF,
            iterations: None,
        }
    }

    /// Override the bounded manifest conflict retry count.
    pub fn with_conflict_retries(mut self, retries: usize) -> Self {
        self.max_conflict_retries = retries;
        self
    }

    /// Override the base conflict backoff.
    pub fn with_base_backoff(mut self, backoff: Duration) -> Self {
        self.base_backoff = backoff;
        self
    }

    /// Override the PBKDF2 iteration count. Test-only.
    pub fn with_iterations(mut self, iterations: u32) -> Self {
        self.iterations = Some(iterations);
        self
    }

    /// Create and persist the device ID and backup salt on first setup.
    pub async fn ensure_identity(&self) -> Result<BackupIdentity, SyncError> {
        let existing = self.store.get_backup_config().await?;
        let device_id = existing
            .as_ref()
            .map(|config| config.device_id.trim().to_owned())
            .filter(|value| !value.is_empty())
            .unwrap_or_else(new_device_id);
        let kdf_salt = existing
            .as_ref()
            .and_then(|config| config.kdf_salt.clone())
            .unwrap_or_else(|| STANDARD.encode(random_salt()));

        let needs_save = match &existing {
            None => true,
            Some(config) => {
                config.device_id != device_id
                    || config.kdf_salt.as_deref() != Some(kdf_salt.as_str())
            }
        };

        if needs_save {
            let mut config = existing.unwrap_or_else(default_backup_config);
            config.device_id = device_id.clone();
            config.kdf_salt = Some(kdf_salt.clone());
            self.store.save_backup_config(&config).await?;
        }

        let salt_bytes = STANDARD
            .decode(&kdf_salt)
            .map_err(|_| SyncError::InvalidSalt)?;
        let salt: [u8; SALT_LEN] = salt_bytes
            .try_into()
            .map_err(|_| SyncError::InvalidSaltLength)?;

        Ok(BackupIdentity { device_id, salt })
    }

    /// Back up local changes and merge remote changes.
    pub async fn sync(&self) -> Result<SyncResult, SyncError> {
        let identity = self.ensure_identity().await?;
        let remote = self.read_remote_manifest(&identity.device_id).await?;
        let local_tombstones = self.store.list_tombstones().await?;
        let message_tombstones: Vec<Tombstone> = local_tombstones
            .iter()
            .filter(|tombstone| tombstone.message_id.is_some())
            .cloned()
            .collect();

        let mut downloaded_conversations = Vec::new();
        let mut snapshot = self.read_snapshot().await?;

        for entry in &remote.manifest.conversations {
            let local = snapshot.conversations.get(&entry.id).cloned();
            if local
                .as_ref()
                .is_some_and(|conversation| conversation.revision > entry.revision)
            {
                continue;
            }

            let payload = self.download_conversation(entry).await?;
            let changed = self
                .apply_remote_conversation(
                    &payload,
                    entry,
                    local.as_ref(),
                    ApplyContext {
                        snapshot: &snapshot,
                        conversation_tombstones: &remote.manifest.tombstones,
                        message_tombstones: &message_tombstones,
                        local_device_id: &identity.device_id,
                    },
                )
                .await?;
            if changed {
                downloaded_conversations.push(entry.id.clone());
            }
        }

        snapshot = self.read_snapshot().await?;

        let conversation_tombstones: Vec<Tombstone> = local_tombstones
            .iter()
            .filter(|tombstone| tombstone.message_id.is_none())
            .cloned()
            .collect();
        let now = Timestamp::now();
        let local_manifest = build_manifest(
            &identity.device_id,
            now,
            now,
            &snapshot.conversations.values().cloned().collect::<Vec<_>>(),
            &snapshot.attachments,
            &conversation_tombstones,
        )?;
        let merged = merge_manifests(&local_manifest, &remote.manifest);

        let remote_by_id: BTreeMap<&str, &ConversationIndexEntry> = remote
            .manifest
            .conversations
            .iter()
            .map(|entry| (entry.id.as_str(), entry))
            .collect();

        let mut uploaded_conversations = Vec::new();
        for entry in &merged.conversations {
            let Some(local) = snapshot.conversations.get(&entry.id) else {
                continue;
            };
            if remote_by_id
                .get(entry.id.as_str())
                .is_some_and(|remote_entry| remote_entry.revision >= local.revision)
            {
                continue;
            }

            let messages = snapshot
                .messages
                .get(&local.id)
                .cloned()
                .unwrap_or_default();
            let payload = build_local_payload(local, &messages, &snapshot.attachments)?;
            let plaintext = serde_json::to_vec(&payload)?;
            let bytes = encrypt_object(EncryptOptions {
                passphrase: &self.passphrase,
                salt: &identity.salt,
                object_type: ObjectType::Conversation,
                object_key: &entry.object_key,
                plaintext: &plaintext,
                compress: None,
                iv: None,
                iterations: self.iterations,
            })?;
            self.storage
                .put_object(&entry.object_key, &bytes, PutObjectOptions::default())
                .await?;
            uploaded_conversations.push(entry.id.clone());
        }

        let remote_attachment_shas: BTreeSet<&str> = remote
            .manifest
            .attachments
            .iter()
            .map(|entry| entry.sha256.as_str())
            .collect();

        let mut uploaded_attachments = Vec::new();
        for entry in &merged.attachments {
            if remote_attachment_shas.contains(entry.sha256.as_str()) {
                continue;
            }
            let Some(bytes) = self.store.read_attachment(&entry.sha256).await? else {
                continue;
            };
            let encrypted = encrypt_object(EncryptOptions {
                passphrase: &self.passphrase,
                salt: &identity.salt,
                object_type: ObjectType::Attachment,
                object_key: &entry.object_key,
                plaintext: &bytes,
                compress: Some(false),
                iv: None,
                iterations: self.iterations,
            })?;
            self.storage
                .put_object(&entry.object_key, &encrypted, PutObjectOptions::default())
                .await?;
            uploaded_attachments.push(entry.sha256.clone());
        }

        let written = self
            .write_manifest(&local_manifest, &remote, &identity)
            .await?;
        let drained_queue = self.store.drain_sync_queue().await?;
        self.update_config(Timestamp::now(), written.etag.clone())
            .await?;

        Ok(SyncResult {
            uploaded_conversations,
            uploaded_attachments,
            downloaded_conversations,
            manifest_etag: written.etag,
            drained_queue,
        })
    }

    /// Explicit "back up now" entry point.
    pub async fn backup_now(&self) -> Result<SyncResult, SyncError> {
        self.sync().await
    }

    /// Restore remote data into local storage.
    ///
    /// The default merges and never deletes local conversations. `replace:
    /// true` is the only path that clears local conversations, and callers must
    /// obtain explicit user confirmation before passing it.
    pub async fn restore(&self, options: RestoreOptions) -> Result<RestoreResult, SyncError> {
        let identity = self.ensure_identity().await?;
        let remote = self.read_remote_manifest(&identity.device_id).await?;
        let local_tombstones = self.store.list_tombstones().await?;
        let message_tombstones: Vec<Tombstone> = local_tombstones
            .iter()
            .filter(|tombstone| tombstone.message_id.is_some())
            .cloned()
            .collect();

        if options.replace {
            self.store.clear_conversations().await?;
        }

        let snapshot = self.read_snapshot().await?;
        let mut merged_conversations = Vec::new();

        for entry in &remote.manifest.conversations {
            let local = snapshot.conversations.get(&entry.id).cloned();
            let payload = self.download_conversation(entry).await?;
            let changed = self
                .apply_remote_conversation(
                    &payload,
                    entry,
                    local.as_ref(),
                    ApplyContext {
                        snapshot: &snapshot,
                        conversation_tombstones: &remote.manifest.tombstones,
                        message_tombstones: &message_tombstones,
                        local_device_id: &identity.device_id,
                    },
                )
                .await?;
            if changed {
                merged_conversations.push(entry.id.clone());
            }
        }

        Ok(RestoreResult {
            merged_conversations,
            replaced: options.replace,
        })
    }

    /// Delete every remote backup object.
    ///
    /// The caller must pass the exact typed confirmation token. Local data is
    /// never touched.
    pub async fn delete_remote(&self, confirmation: &str) -> Result<(), SyncError> {
        if confirmation != DELETE_REMOTE_CONFIRMATION {
            return Err(SyncError::ConfirmationRequired);
        }

        match self.storage.delete_object(MANIFEST_OBJECT_KEY).await {
            Ok(()) => {}
            Err(StorageError::NotFound { .. }) => {}
            Err(error) => return Err(error.into()),
        }

        let mut continuation_token = None;
        loop {
            let page = self
                .storage
                .list_objects(ListObjectsOptions {
                    prefix: None,
                    continuation_token,
                    max_keys: None,
                })
                .await?;
            for object in page.objects {
                if object.key == MANIFEST_OBJECT_KEY {
                    continue;
                }
                match self.storage.delete_object(&object.key).await {
                    Ok(()) => {}
                    Err(StorageError::NotFound { .. }) => {}
                    Err(error) => return Err(error.into()),
                }
            }
            continuation_token = page.next_continuation_token;
            if continuation_token.is_none() {
                break;
            }
        }

        Ok(())
    }

    async fn read_remote_manifest(&self, device_id: &str) -> Result<RemoteManifest, SyncError> {
        match self.storage.get_object(MANIFEST_OBJECT_KEY).await {
            Ok(object) => {
                let decrypted = decrypt_object(&self.passphrase, &object.bytes)?;
                ensure_header(&decrypted, ObjectType::Manifest, MANIFEST_OBJECT_KEY)?;
                let manifest = parse_manifest(&decrypted.plaintext)?;
                Ok(RemoteManifest {
                    manifest,
                    etag: object.etag,
                })
            }
            Err(StorageError::NotFound { .. }) => Ok(RemoteManifest {
                manifest: empty_manifest(device_id, Timestamp::now()),
                etag: None,
            }),
            Err(error) => Err(error.into()),
        }
    }

    async fn download_conversation(
        &self,
        entry: &ConversationIndexEntry,
    ) -> Result<ConversationPayload, SyncError> {
        let object = self.storage.get_object(&entry.object_key).await?;
        let decrypted = decrypt_object(&self.passphrase, &object.bytes)?;
        ensure_header(&decrypted, ObjectType::Conversation, &entry.object_key)?;
        Ok(parse_conversation_payload(&decrypted.plaintext)?)
    }

    async fn apply_remote_conversation(
        &self,
        payload: &ConversationPayload,
        entry: &ConversationIndexEntry,
        local: Option<&Conversation>,
        context: ApplyContext<'_>,
    ) -> Result<bool, SyncError> {
        let Some(local) = local else {
            if latest_conversation_tombstone(context.conversation_tombstones, &payload.id)
                .is_some_and(|tombstone| tombstone.deleted_at >= payload.updated_at)
            {
                return Ok(false);
            }
            self.write_conversation_payload(payload, payload.revision)
                .await?;
            return Ok(true);
        };

        let local_messages = context
            .snapshot
            .messages
            .get(&local.id)
            .cloned()
            .unwrap_or_default();
        let local_payload =
            build_local_payload(local, &local_messages, &context.snapshot.attachments)?;
        let merged = merge_conversation(
            &local_payload,
            payload,
            MergeConversationOptions {
                local_device_id: context.local_device_id,
                remote_device_id: &entry.device_id,
                conversation_tombstones: context.conversation_tombstones,
                message_tombstones: context.message_tombstones,
            },
        )?;

        if merged.deleted {
            return Ok(false);
        }
        if merged.payload == local_payload {
            return Ok(false);
        }

        let revision = local.revision.max(entry.revision) + 1;
        self.write_conversation_payload(&merged.payload, revision)
            .await?;
        Ok(true)
    }

    async fn write_conversation_payload(
        &self,
        payload: &ConversationPayload,
        revision: u64,
    ) -> Result<(), SyncError> {
        let messages: Vec<Message> = payload
            .messages
            .iter()
            .map(|message| payload_message_to_message(message, &payload.id))
            .collect();
        let attachments: Vec<Attachment> = payload
            .attachments
            .iter()
            .filter_map(|attachment| payload_attachment_to_attachment(attachment, payload))
            .collect();
        let existing = self.store.get_conversation(&payload.id).await?;

        let conversation = Conversation {
            id: payload.id.clone(),
            title: payload.title.clone(),
            system_prompt: payload.system_prompt.clone(),
            provider_id: payload.provider_id.clone(),
            model_id: payload.model_id.clone(),
            model_label: payload.model_label.clone(),
            params: payload.params.clone(),
            pinned: payload.pinned,
            archived: payload.archived,
            temporary: payload.temporary,
            created_at: payload.created_at,
            updated_at: payload.updated_at,
            last_message_at: payload.last_message_at,
            message_count: messages.len() as u64,
            last_error: existing.as_ref().and_then(|value| value.last_error.clone()),
            sync: existing.as_ref().and_then(|value| value.sync.clone()),
            storage_key: payload.storage_key.clone(),
            revision,
        };

        self.store
            .write_conversation(&conversation, &messages, &attachments)
            .await?;
        Ok(())
    }

    async fn write_manifest(
        &self,
        local_manifest: &ManifestBody,
        initial_remote: &RemoteManifest,
        identity: &BackupIdentity,
    ) -> Result<WrittenManifest, SyncError> {
        let mut remote = initial_remote.clone();
        let mut attempt = 0;

        loop {
            let merged = merge_manifests(local_manifest, &remote.manifest);
            let body = ManifestBody {
                format_version: MANIFEST_FORMAT_VERSION,
                created_at: remote.manifest.created_at,
                updated_at: Timestamp::now(),
                device_id: identity.device_id.clone(),
                conversations: merged.conversations,
                attachments: merged.attachments,
                tombstones: merged.tombstones,
            };
            let plaintext = serde_json::to_vec(&body)?;
            let bytes = encrypt_object(EncryptOptions {
                passphrase: &self.passphrase,
                salt: &identity.salt,
                object_type: ObjectType::Manifest,
                object_key: MANIFEST_OBJECT_KEY,
                plaintext: &plaintext,
                compress: Some(false),
                iv: None,
                iterations: self.iterations,
            })?;

            let options = match &remote.etag {
                Some(etag) => PutObjectOptions {
                    if_match: Some(etag.clone()),
                    ..PutObjectOptions::default()
                },
                None => PutObjectOptions {
                    if_none_match: Some("*".to_owned()),
                    ..PutObjectOptions::default()
                },
            };

            match self
                .storage
                .put_object(MANIFEST_OBJECT_KEY, &bytes, options)
                .await
            {
                Ok(object) => {
                    return Ok(WrittenManifest { etag: object.etag });
                }
                Err(StorageError::Conflict { .. }) if attempt < self.max_conflict_retries => {
                    if !self.base_backoff.is_zero() {
                        let factor = 1u32 << attempt.min(31);
                        tokio::time::sleep(self.base_backoff * factor).await;
                    }
                    remote = self.read_remote_manifest(&identity.device_id).await?;
                    attempt += 1;
                }
                Err(error) => return Err(error.into()),
            }
        }
    }

    async fn update_config(
        &self,
        last_sync_at: Timestamp,
        etag: Option<String>,
    ) -> Result<(), SyncError> {
        if let Some(mut config) = self.store.get_backup_config().await? {
            config.last_sync_at = Some(last_sync_at);
            config.last_manifest_etag = etag;
            self.store.save_backup_config(&config).await?;
        }
        Ok(())
    }

    async fn read_snapshot(&self) -> Result<LocalSnapshot, SyncError> {
        let conversations = self.store.list_conversations().await?;
        let mut by_id = BTreeMap::new();
        let mut messages = BTreeMap::new();
        for conversation in conversations {
            let conversation_messages = self.store.list_messages(&conversation.id).await?;
            messages.insert(conversation.id.clone(), conversation_messages);
            by_id.insert(conversation.id.clone(), conversation);
        }
        let attachments = self.store.list_attachments().await?;
        Ok(LocalSnapshot {
            conversations: by_id,
            messages,
            attachments,
        })
    }
}

#[derive(Clone, Debug)]
struct RemoteManifest {
    manifest: ManifestBody,
    etag: Option<String>,
}

#[derive(Clone, Debug)]
struct WrittenManifest {
    etag: Option<String>,
}

struct LocalSnapshot {
    conversations: BTreeMap<String, Conversation>,
    messages: BTreeMap<String, Vec<Message>>,
    attachments: Vec<Attachment>,
}

struct ApplyContext<'a> {
    snapshot: &'a LocalSnapshot,
    conversation_tombstones: &'a [ManifestTombstone],
    message_tombstones: &'a [Tombstone],
    local_device_id: &'a str,
}

fn ensure_header(
    decrypted: &DecryptedObject,
    object_type: ObjectType,
    object_key: &str,
) -> Result<(), SyncError> {
    if decrypted.header.object_type != object_type || decrypted.header.object_key != object_key {
        return Err(SyncError::UnexpectedHeader);
    }
    Ok(())
}

fn empty_manifest(device_id: &str, now: Timestamp) -> ManifestBody {
    ManifestBody {
        format_version: MANIFEST_FORMAT_VERSION,
        created_at: now,
        updated_at: now,
        device_id: device_id.to_owned(),
        conversations: Vec::new(),
        attachments: Vec::new(),
        tombstones: Vec::new(),
    }
}

fn default_backup_config() -> BackupConfig {
    BackupConfig {
        enabled: false,
        backend: BackupBackend::S3,
        endpoint: None,
        region: None,
        bucket: String::new(),
        prefix: String::new(),
        credential_secret_id: None,
        encryption: BackupEncryption::Aes256Gcm,
        kdf: BackupKdf::Pbkdf2Sha256,
        kdf_salt: None,
        last_sync_at: None,
        last_manifest_etag: None,
        device_id: String::new(),
    }
}

fn new_device_id() -> String {
    let mut bytes = [0u8; 16];
    rand::rngs::OsRng.fill_bytes(&mut bytes);
    hex::encode(bytes)
}

fn random_salt() -> [u8; SALT_LEN] {
    let mut salt = [0u8; SALT_LEN];
    rand::rngs::OsRng.fill_bytes(&mut salt);
    salt
}

fn build_local_payload(
    conversation: &Conversation,
    messages: &[Message],
    attachments: &[Attachment],
) -> Result<ConversationPayload, SyncError> {
    let referenced = referenced_attachment_ids(messages);
    let attachments: Vec<Attachment> = attachments
        .iter()
        .filter(|attachment| referenced.contains(&attachment.id))
        .cloned()
        .collect();
    Ok(build_conversation_payload(
        conversation,
        messages,
        &attachments,
    )?)
}

fn referenced_attachment_ids(messages: &[Message]) -> BTreeSet<String> {
    let mut ids = BTreeSet::new();
    for message in messages {
        for part in &message.parts {
            match part {
                ContentPart::Image { attachment_id, .. }
                | ContentPart::File { attachment_id, .. } => {
                    ids.insert(attachment_id.clone());
                }
                _ => {}
            }
        }
    }
    ids
}

fn payload_message_to_message(message: &PayloadMessage, conversation_id: &str) -> Message {
    Message {
        id: message.id.clone(),
        conversation_id: conversation_id.to_owned(),
        sequence: message.sequence,
        role: message.role,
        parts: message.parts.clone(),
        status: message.status,
        provider_id: None,
        model_id: None,
        model_label: None,
        usage: message.usage.clone(),
        finish_reason: None,
        error: None,
        latency_ms: None,
        first_token_ms: None,
        created_at: message.created_at,
        updated_at: message.updated_at,
        deleted_at: message.deleted_at,
    }
}

fn payload_attachment_to_attachment(
    reference: &AttachmentReference,
    payload: &ConversationPayload,
) -> Option<Attachment> {
    let message_id = payload.messages.iter().find_map(|message| {
        message.parts.iter().find_map(|part| match part {
            ContentPart::Image { attachment_id, .. } | ContentPart::File { attachment_id, .. }
                if attachment_id == &reference.id =>
            {
                Some(message.id.clone())
            }
            _ => None,
        })
    })?;

    Some(Attachment {
        id: reference.id.clone(),
        message_id: Some(message_id),
        kind: if reference.mime_type.starts_with("image/") {
            AttachmentKind::Image
        } else {
            AttachmentKind::File
        },
        mime_type: reference.mime_type.clone(),
        name: reference.name.clone(),
        size: reference.size,
        sha256: reference.sha256.clone(),
        storage_key: reference.storage_key.clone(),
        width: None,
        height: None,
        created_at: payload.created_at,
    })
}

fn latest_conversation_tombstone<'a>(
    tombstones: &'a [ManifestTombstone],
    conversation_id: &str,
) -> Option<&'a ManifestTombstone> {
    let mut latest: Option<&ManifestTombstone> = None;
    for tombstone in tombstones {
        if tombstone.conversation_id != conversation_id {
            continue;
        }
        match latest {
            Some(existing)
                if tombstone.deleted_at < existing.deleted_at
                    || (tombstone.deleted_at == existing.deleted_at
                        && tombstone.device_id <= existing.device_id) => {}
            _ => latest = Some(tombstone),
        }
    }
    latest
}
