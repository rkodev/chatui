//! Content-addressed attachment byte storage and metadata writes.
//!
//! Bytes live under `<app-data>/attachments/<sha256>` and are addressed by the
//! lowercase hexadecimal SHA-256 digest of their content. Writing identical
//! bytes twice returns the same storage key and does not rewrite the file.
//!
//! The store never stores bytes in the database. [`AttachmentService`] composes
//! the byte store with [`AttachmentRepository`] so callers can write bytes and
//! metadata together while keeping `sha256` and `storageKey` aligned.
//!
//! # In-flight protection
//!
//! Orphan cleanup must not delete a file that an import or an active send is
//! reading. Callers pin a key with [`AttachmentStore::pin`] for the duration of
//! the operation; cleanup skips pinned keys. [`AttachmentStore::put`] pins the
//! key while it writes, so a concurrent cleanup cannot remove a half-written
//! file.

use std::collections::HashSet;
use std::fs;
use std::io::ErrorKind;
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex};

use chatui_domain::Attachment;
use sha2::{Digest, Sha256};

use crate::StorageError;
use crate::paths;
use crate::repositories::AttachmentRepository;

/// Suffix used for atomic in-progress writes.
const TEMP_SUFFIX: &str = ".tmp";

/// Returns the lowercase hexadecimal SHA-256 digest of `bytes`.
pub fn sha256_hex(bytes: &[u8]) -> String {
    hex::encode(Sha256::digest(bytes))
}

/// A pinned attachment key. Dropping the lease releases the pin.
#[derive(Debug)]
pub struct AttachmentLease {
    store: AttachmentStore,
    storage_key: String,
}

impl AttachmentLease {
    /// Returns the pinned storage key.
    pub fn storage_key(&self) -> &str {
        &self.storage_key
    }
}

impl Drop for AttachmentLease {
    fn drop(&mut self) {
        self.store.unpin(&self.storage_key);
    }
}

/// Content-addressed attachment byte store rooted at an app-data directory.
#[derive(Debug, Clone)]
pub struct AttachmentStore {
    app_data_dir: PathBuf,
    in_use: Arc<Mutex<HashSet<String>>>,
}

impl AttachmentStore {
    /// Create a store rooted at `app_data_dir`.
    ///
    /// The `attachments/` directory is created lazily on the first write.
    pub fn new(app_data_dir: impl Into<PathBuf>) -> Self {
        Self {
            app_data_dir: app_data_dir.into(),
            in_use: Arc::new(Mutex::new(HashSet::new())),
        }
    }

    /// Create a store rooted at the platform app-data directory.
    pub fn with_app_data_dir() -> Result<Self, StorageError> {
        Ok(Self::new(crate::app_data_dir()?))
    }

    /// Returns the app-data directory this store is rooted at.
    pub fn app_data_dir(&self) -> &Path {
        &self.app_data_dir
    }

    /// Returns the attachment directory for this store.
    pub fn attachments_dir(&self) -> PathBuf {
        paths::attachments_dir_in(&self.app_data_dir)
    }

    /// Pin a storage key so orphan cleanup leaves it alone.
    ///
    /// The returned lease releases the pin when dropped. Callers that import or
    /// send an attachment should hold a lease for the whole operation.
    pub fn pin(&self, storage_key: &str) -> Result<AttachmentLease, StorageError> {
        paths::validate_storage_key(storage_key)?;
        let mut in_use = self
            .in_use
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        in_use.insert(storage_key.to_owned());
        drop(in_use);

        Ok(AttachmentLease {
            store: self.clone(),
            storage_key: storage_key.to_owned(),
        })
    }

    /// Returns true when `storage_key` is currently pinned.
    pub fn is_pinned(&self, storage_key: &str) -> bool {
        self.pinned_keys().contains(storage_key)
    }

    /// Store `bytes` under their SHA-256 digest and return the storage key.
    ///
    /// Identical bytes deduplicate: when the target file already exists it is
    /// left untouched and the same key is returned.
    pub fn put(&self, bytes: &[u8]) -> Result<String, StorageError> {
        let storage_key = paths::storage_key_for_sha256(&sha256_hex(bytes))?;
        let _lease = self.pin(&storage_key)?;

        let directory = self.attachments_dir();
        fs::create_dir_all(&directory)?;

        let target = directory.join(&storage_key);
        match fs::symlink_metadata(&target) {
            Ok(metadata) if metadata.file_type().is_file() => return Ok(storage_key),
            Ok(_) => {
                return Err(StorageError::InvalidInput(format!(
                    "attachment path is not a regular file: {storage_key}"
                )));
            }
            Err(error) if error.kind() == ErrorKind::NotFound => {}
            Err(error) => return Err(error.into()),
        }

        let temporary = self.temp_path(&storage_key);
        fs::write(&temporary, bytes)?;
        match fs::rename(&temporary, &target) {
            Ok(()) => {}
            Err(_) => {
                // Some filesystems refuse to rename over an existing entry.
                // Fall back to a direct write and remove the temporary file.
                fs::write(&target, bytes)?;
                let _ = fs::remove_file(&temporary);
            }
        }

        Ok(storage_key)
    }

    /// Read the bytes for a storage key.
    ///
    /// Returns [`StorageError::NotFound`] when the file is missing.
    pub fn get(&self, storage_key: &str) -> Result<Vec<u8>, StorageError> {
        let path = self.path_for(storage_key)?;

        match fs::symlink_metadata(&path) {
            Ok(metadata) if metadata.file_type().is_file() => Ok(fs::read(&path)?),
            Ok(_) => Err(StorageError::InvalidInput(format!(
                "attachment path is not a regular file: {storage_key}"
            ))),
            Err(error) if error.kind() == ErrorKind::NotFound => {
                Err(StorageError::NotFound(format!("attachment {storage_key}")))
            }
            Err(error) => Err(error.into()),
        }
    }

    /// Delete the bytes for a storage key.
    ///
    /// Returns `true` when a file was removed and `false` when it was already
    /// missing. A stale temporary file for the same key is removed too.
    pub fn delete(&self, storage_key: &str) -> Result<bool, StorageError> {
        let path = self.path_for(storage_key)?;
        let mut removed = false;

        match fs::remove_file(&path) {
            Ok(()) => removed = true,
            Err(error) if error.kind() == ErrorKind::NotFound => {}
            Err(error) => return Err(error.into()),
        }

        match fs::remove_file(self.temp_path(storage_key)) {
            Ok(()) => removed = true,
            Err(error) if error.kind() == ErrorKind::NotFound => {}
            Err(error) => return Err(error.into()),
        }

        Ok(removed)
    }

    /// Returns true when the bytes for a storage key exist.
    pub fn exists(&self, storage_key: &str) -> Result<bool, StorageError> {
        let path = self.path_for(storage_key)?;

        match fs::symlink_metadata(&path) {
            Ok(metadata) => Ok(metadata.file_type().is_file()),
            Err(error) if error.kind() == ErrorKind::NotFound => Ok(false),
            Err(error) => Err(error.into()),
        }
    }

    /// Remove attachment files whose key is not in `referenced`.
    ///
    /// Pinned keys are never removed. Stale `.tmp` files are removed unless
    /// their base key is pinned. Returns the removed file names in sorted order.
    pub fn cleanup_orphans(
        &self,
        referenced: &HashSet<String>,
    ) -> Result<Vec<String>, StorageError> {
        let directory = self.attachments_dir();
        let entries = match fs::read_dir(&directory) {
            Ok(entries) => entries,
            Err(error) if error.kind() == ErrorKind::NotFound => return Ok(Vec::new()),
            Err(error) => return Err(error.into()),
        };

        let pinned = self.pinned_keys();
        let mut removed = Vec::new();

        for entry in entries {
            let entry = entry?;
            if !entry.file_type()?.is_file() {
                continue;
            }

            let Some(name) = entry.file_name().to_str().map(str::to_owned) else {
                continue;
            };

            let (base, is_temporary) = match name.strip_suffix(TEMP_SUFFIX) {
                Some(base) => (base.to_owned(), true),
                None => (name.clone(), false),
            };

            if pinned.contains(&base) {
                continue;
            }
            if !is_temporary && referenced.contains(&name) {
                continue;
            }

            match fs::remove_file(entry.path()) {
                Ok(()) => removed.push(name),
                Err(error) if error.kind() == ErrorKind::NotFound => {}
                Err(error) => return Err(error.into()),
            }
        }

        removed.sort();
        Ok(removed)
    }

    /// Remove files not referenced by attachment metadata.
    ///
    /// The referenced set is read from [`AttachmentRepository::list_storage_keys`].
    /// Storage keys are the bare SHA-256 digests, so they match file names.
    pub async fn cleanup_orphans_for_repository(
        &self,
        repository: &dyn AttachmentRepository,
    ) -> Result<Vec<String>, StorageError> {
        let referenced: HashSet<String> =
            repository.list_storage_keys().await?.into_iter().collect();
        self.cleanup_orphans(&referenced)
    }

    fn path_for(&self, storage_key: &str) -> Result<PathBuf, StorageError> {
        paths::attachment_path_in(&self.app_data_dir, storage_key)
    }

    fn temp_path(&self, storage_key: &str) -> PathBuf {
        self.attachments_dir()
            .join(format!("{storage_key}{TEMP_SUFFIX}"))
    }

    fn pinned_keys(&self) -> HashSet<String> {
        self.in_use
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
            .clone()
    }

    fn unpin(&self, storage_key: &str) {
        let mut in_use = self
            .in_use
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        in_use.remove(storage_key);
    }
}

/// Byte storage plus attachment metadata writes.
///
/// [`AttachmentService::add`] hashes the bytes, stores them, and inserts
/// metadata with matching `sha256` and `storageKey` values. [`delete`] removes
/// the metadata row and deletes the bytes only when no other row shares the
/// same content hash.
///
/// [`delete`]: AttachmentService::delete
#[derive(Clone)]
pub struct AttachmentService {
    store: AttachmentStore,
    repository: Arc<dyn AttachmentRepository>,
}

impl AttachmentService {
    /// Create a service over a byte store and an attachment repository.
    pub fn new(store: AttachmentStore, repository: Arc<dyn AttachmentRepository>) -> Self {
        Self { store, repository }
    }

    /// Borrow the byte store.
    pub fn store(&self) -> &AttachmentStore {
        &self.store
    }

    /// Borrow the metadata repository.
    pub fn repository(&self) -> &Arc<dyn AttachmentRepository> {
        &self.repository
    }

    /// Store `bytes` and insert metadata for `attachment`.
    ///
    /// The attachment's `sha256`, `storageKey`, and `size` are replaced with the
    /// values derived from `bytes`, so the metadata can never point at a
    /// different file than the one that was written.
    pub async fn add(
        &self,
        mut attachment: Attachment,
        bytes: &[u8],
    ) -> Result<Attachment, StorageError> {
        let storage_key = self.store.put(bytes)?;
        // Hold the pin across the metadata insert so a concurrent orphan
        // cleanup cannot remove bytes that are about to become referenced.
        let _lease = self.store.pin(&storage_key)?;
        attachment.sha256 = storage_key.clone();
        attachment.storage_key = storage_key;
        attachment.size = bytes.len() as u64;

        self.repository.insert(&attachment).await?;
        Ok(attachment)
    }

    /// Update attachment metadata after validating the content keys.
    pub async fn update(&self, attachment: &Attachment) -> Result<(), StorageError> {
        validate_attachment_keys(attachment)?;
        self.repository.update(attachment).await
    }

    /// Delete one attachment and its bytes when no other row shares the hash.
    ///
    /// Returns `false` when the metadata row does not exist.
    pub async fn delete(&self, id: &str) -> Result<bool, StorageError> {
        let Some(attachment) = self.repository.get(id).await? else {
            return Ok(false);
        };

        // Keep the bytes pinned until the metadata row is gone and the
        // remaining-reference check has run.
        let _lease = self.store.pin(&attachment.storage_key)?;
        let deleted = self.repository.delete(id).await?;
        if deleted {
            let remaining = self.repository.list_by_sha256(&attachment.sha256).await?;
            if remaining.is_empty() {
                self.store.delete(&attachment.storage_key)?;
            }
        }

        Ok(deleted)
    }
}

fn validate_attachment_keys(attachment: &Attachment) -> Result<(), StorageError> {
    paths::validate_storage_key(&attachment.sha256)?;
    paths::validate_storage_key(&attachment.storage_key)?;

    if attachment.sha256 != attachment.storage_key {
        return Err(StorageError::InvalidInput(format!(
            "attachment {} sha256 and storage_key must match",
            attachment.id
        )));
    }

    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use chatui_domain::{
        AttachmentKind, ChatParams, ContentPart, Conversation, FinishReason, Message, MessageRole,
        MessageStatus, Timestamp,
    };
    use sqlx::SqlitePool;

    use crate::db::open_in_memory;
    use crate::repositories::{
        ConversationRepository, MessageRepository, SqliteAttachmentRepository,
        SqliteConversationRepository, SqliteMessageRepository,
    };

    fn timestamp(seconds: u32) -> Timestamp {
        Timestamp::parse(&format!("2026-11-04T12:00:{seconds:02}.000Z")).expect("valid timestamp")
    }

    fn conversation(id: &str) -> Conversation {
        Conversation {
            id: id.to_owned(),
            title: format!("Conversation {id}"),
            system_prompt: String::new(),
            provider_id: "provider-1".to_owned(),
            model_id: "model-1".to_owned(),
            model_label: "Model One".to_owned(),
            params: ChatParams::default(),
            pinned: false,
            archived: false,
            temporary: false,
            created_at: timestamp(0),
            updated_at: timestamp(0),
            last_message_at: timestamp(0),
            message_count: 0,
            last_error: None,
            sync: None,
            storage_key: String::new(),
            revision: 1,
        }
    }

    fn message(id: &str, conversation_id: &str, sequence: u64) -> Message {
        Message {
            id: id.to_owned(),
            conversation_id: conversation_id.to_owned(),
            sequence,
            role: MessageRole::User,
            parts: vec![ContentPart::Text {
                text: "hello".to_owned(),
            }],
            status: MessageStatus::Complete,
            provider_id: Some("provider-1".to_owned()),
            model_id: Some("model-1".to_owned()),
            model_label: Some("Model One".to_owned()),
            usage: None,
            finish_reason: Some(FinishReason::Stop),
            error: None,
            latency_ms: None,
            first_token_ms: None,
            created_at: timestamp(0),
            updated_at: timestamp(0),
            deleted_at: None,
        }
    }

    fn attachment(id: &str, message_id: &str) -> Attachment {
        Attachment {
            id: id.to_owned(),
            message_id: Some(message_id.to_owned()),
            kind: AttachmentKind::File,
            mime_type: "text/plain".to_owned(),
            name: "notes.txt".to_owned(),
            size: 0,
            sha256: String::new(),
            storage_key: String::new(),
            width: None,
            height: None,
            created_at: timestamp(0),
        }
    }

    fn temp_store() -> (tempfile::TempDir, AttachmentStore) {
        let directory = tempfile::tempdir().expect("create temporary directory");
        let store = AttachmentStore::new(directory.path());
        (directory, store)
    }

    async fn setup_service() -> (
        tempfile::TempDir,
        SqlitePool,
        AttachmentStore,
        Arc<dyn AttachmentRepository>,
        AttachmentService,
    ) {
        let (directory, store) = temp_store();
        let pool = open_in_memory().await.expect("open in-memory database");
        let conversations = SqliteConversationRepository::new(pool.clone());
        let messages = SqliteMessageRepository::new(pool.clone());
        let attachments: Arc<dyn AttachmentRepository> =
            Arc::new(SqliteAttachmentRepository::new(pool.clone()));

        conversations
            .create(&conversation("conversation-1"))
            .await
            .expect("create conversation");
        messages
            .insert(&message("message-1", "conversation-1", 0))
            .await
            .expect("insert message");

        let service = AttachmentService::new(store.clone(), attachments.clone());
        (directory, pool, store, attachments, service)
    }

    #[test]
    fn put_stores_bytes_under_attachments_sha256() {
        let (directory, store) = temp_store();
        let key = store.put(b"hello").expect("store bytes");

        assert_eq!(key, sha256_hex(b"hello"));
        assert_eq!(key.len(), 64);

        let path = directory.path().join("attachments").join(&key);
        assert!(path.is_file(), "missing attachment file at {path:?}");
        assert_eq!(fs::read(&path).expect("read file"), b"hello");
        assert_eq!(store.get(&key).expect("read bytes"), b"hello");
    }

    #[test]
    fn put_deduplicates_identical_bytes() {
        let (_directory, store) = temp_store();

        let first = store.put(b"same").expect("first write");
        let second = store.put(b"same").expect("second write");

        assert_eq!(first, second);
        let entries = fs::read_dir(store.attachments_dir())
            .expect("list attachments")
            .count();
        assert_eq!(entries, 1, "identical bytes created more than one file");
    }

    #[test]
    fn get_missing_file_returns_not_found() {
        let (_directory, store) = temp_store();
        let key = "a".repeat(64);

        assert!(matches!(store.get(&key), Err(StorageError::NotFound(_))));
        assert!(!store.exists(&key).expect("exists"));
        assert!(!store.delete(&key).expect("delete"));
    }

    #[test]
    fn rejects_unsafe_storage_keys() {
        let (_directory, store) = temp_store();
        let invalid = [
            String::new(),
            "../escape".to_owned(),
            "a".repeat(63),
            "a".repeat(65),
            "A".repeat(64),
            "g".repeat(64),
            format!("attachments/{}", "a".repeat(64)),
        ];

        for key in invalid {
            assert!(
                matches!(store.get(&key), Err(StorageError::InvalidInput(_))),
                "get accepted unsafe key {key:?}"
            );
            assert!(
                matches!(store.delete(&key), Err(StorageError::InvalidInput(_))),
                "delete accepted unsafe key {key:?}"
            );
            assert!(
                matches!(store.exists(&key), Err(StorageError::InvalidInput(_))),
                "exists accepted unsafe key {key:?}"
            );
        }
    }

    #[test]
    fn cleanup_orphans_removes_unreferenced_files_only() {
        let (_directory, store) = temp_store();
        let keep_a = store.put(b"a").expect("store a");
        let orphan = store.put(b"b").expect("store b");
        let keep_c = store.put(b"c").expect("store c");

        let referenced: HashSet<String> = [keep_a.clone(), keep_c.clone()].into_iter().collect();
        let removed = store.cleanup_orphans(&referenced).expect("cleanup");

        assert_eq!(removed, vec![orphan.clone()]);
        assert!(store.exists(&keep_a).expect("exists a"));
        assert!(!store.exists(&orphan).expect("exists orphan"));
        assert!(store.exists(&keep_c).expect("exists c"));
    }

    #[test]
    fn cleanup_orphans_skips_pinned_files() {
        let (_directory, store) = temp_store();
        let orphan = store.put(b"a").expect("store a");
        let pinned = store.put(b"b").expect("store b");
        let lease = store.pin(&pinned).expect("pin");

        let removed = store.cleanup_orphans(&HashSet::new()).expect("cleanup");
        assert_eq!(removed, vec![orphan.clone()]);
        assert!(store.exists(&pinned).expect("exists pinned"));

        drop(lease);
        let removed = store.cleanup_orphans(&HashSet::new()).expect("cleanup");
        assert_eq!(removed, vec![pinned.clone()]);
        assert!(!store.exists(&pinned).expect("exists pinned"));
    }

    #[test]
    fn cleanup_orphans_removes_stale_temporary_files() {
        let (_directory, store) = temp_store();
        let key = "a".repeat(64);
        fs::create_dir_all(store.attachments_dir()).expect("create attachments directory");
        fs::write(
            store.attachments_dir().join(format!("{key}.tmp")),
            b"partial",
        )
        .expect("write temporary file");

        let removed = store.cleanup_orphans(&HashSet::new()).expect("cleanup");
        assert_eq!(removed, vec![format!("{key}.tmp")]);
    }

    #[tokio::test]
    async fn cleanup_orphans_for_repository_keeps_referenced_files() {
        let (_directory, _pool, store, attachments, service) = setup_service().await;

        let kept = service
            .add(attachment("attachment-1", "message-1"), b"keep")
            .await
            .expect("add attachment");
        let orphan = store.put(b"orphan").expect("store orphan");

        let removed = store
            .cleanup_orphans_for_repository(attachments.as_ref())
            .await
            .expect("cleanup");

        assert_eq!(removed, vec![orphan.clone()]);
        assert!(store.exists(&kept.storage_key).expect("exists kept"));
        assert!(!store.exists(&orphan).expect("exists orphan"));
    }

    #[tokio::test]
    async fn service_add_aligns_sha256_and_storage_key() {
        let (_directory, _pool, store, attachments, service) = setup_service().await;

        let stored = service
            .add(attachment("attachment-1", "message-1"), b"hello")
            .await
            .expect("add attachment");

        assert_eq!(stored.sha256, sha256_hex(b"hello"));
        assert_eq!(stored.storage_key, stored.sha256);
        assert_eq!(stored.size, 5);

        let loaded = attachments
            .get("attachment-1")
            .await
            .expect("get metadata")
            .expect("metadata exists");
        assert_eq!(loaded.sha256, stored.sha256);
        assert_eq!(loaded.storage_key, stored.storage_key);
        assert_eq!(
            store.get(&stored.storage_key).expect("read bytes"),
            b"hello"
        );
    }

    #[tokio::test]
    async fn service_update_writes_metadata_and_rejects_misaligned_keys() {
        let (_directory, _pool, _store, attachments, service) = setup_service().await;

        let mut stored = service
            .add(attachment("attachment-1", "message-1"), b"hello")
            .await
            .expect("add attachment");
        stored.name = "renamed.txt".to_owned();
        service.update(&stored).await.expect("update metadata");

        let loaded = attachments
            .get("attachment-1")
            .await
            .expect("get metadata")
            .expect("metadata exists");
        assert_eq!(loaded.name, "renamed.txt");

        stored.storage_key = "b".repeat(64);
        assert!(matches!(
            service.update(&stored).await,
            Err(StorageError::InvalidInput(_))
        ));
    }

    #[tokio::test]
    async fn service_delete_removes_bytes_only_after_the_last_reference() {
        let (_directory, _pool, store, _attachments, service) = setup_service().await;

        let first = service
            .add(attachment("attachment-1", "message-1"), b"same")
            .await
            .expect("add first attachment");
        let second = service
            .add(attachment("attachment-2", "message-1"), b"same")
            .await
            .expect("add second attachment");
        assert_eq!(first.storage_key, second.storage_key);

        assert!(service.delete("attachment-1").await.expect("delete first"));
        assert!(
            store
                .exists(&first.storage_key)
                .expect("exists after first"),
            "bytes were removed while another row still referenced them"
        );

        assert!(service.delete("attachment-2").await.expect("delete second"));
        assert!(
            !store
                .exists(&first.storage_key)
                .expect("exists after second"),
            "bytes were not removed after the last reference"
        );

        assert!(!service.delete("missing").await.expect("delete missing"));
    }
}
