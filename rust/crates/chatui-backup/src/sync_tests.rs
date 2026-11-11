use std::collections::BTreeMap;
use std::sync::{Arc, Mutex};
use std::time::Duration;

use async_trait::async_trait;
use base64::Engine;
use base64::engine::general_purpose::STANDARD;
use chatui_domain::{
    Attachment, AttachmentKind, BackupBackend, BackupConfig, BackupEncryption, BackupKdf,
    ChatParams, ContentPart, Conversation, Message, MessageRole, MessageStatus, Timestamp,
    Tombstone,
};
use chatui_storage::{StorageError as LocalStorageError, SyncLocalStore};

use crate::conversation_payload::{ConversationPayload, build_conversation_payload};
use crate::envelope::{EncryptOptions, ObjectType, SALT_LEN, decrypt_object, encrypt_object};
use crate::manifest::{
    MANIFEST_FORMAT_VERSION, ManifestBody, attachment_object_key, build_manifest,
    conversation_object_key,
};
use crate::storage::{
    ListObjectsOptions, ListObjectsResult, PutObjectOptions, StorageAdapter, StorageError,
    StorageObject,
};
use crate::sync::{
    DELETE_REMOTE_CONFIRMATION, MANIFEST_OBJECT_KEY, RestoreOptions, SyncEngine, SyncError,
};

const PASSPHRASE: &str = "correct horse battery staple";
const SALT: [u8; SALT_LEN] = [0x42; SALT_LEN];
const DEVICE_ID: &str = "device-local";
const STORAGE_KEY: &str = "0123456789abcdef0123456789abcdef";
const SHA256: &str = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

fn ts(seconds: u32) -> Timestamp {
    Timestamp::parse(&format!("2026-11-04T12:00:{seconds:02}.000Z")).expect("valid timestamp")
}

fn config(device_id: &str, salt: &[u8; SALT_LEN]) -> BackupConfig {
    BackupConfig {
        enabled: true,
        backend: BackupBackend::S3,
        endpoint: None,
        region: None,
        bucket: "bucket".to_owned(),
        prefix: String::new(),
        credential_secret_id: None,
        encryption: BackupEncryption::Aes256Gcm,
        kdf: BackupKdf::Pbkdf2Sha256,
        kdf_salt: Some(STANDARD.encode(salt)),
        last_sync_at: None,
        last_manifest_etag: None,
        device_id: device_id.to_owned(),
    }
}

fn conversation(id: &str, revision: u64, updated: u32) -> Conversation {
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
        created_at: ts(0),
        updated_at: ts(updated),
        last_message_at: ts(updated),
        message_count: 0,
        last_error: None,
        sync: None,
        storage_key: STORAGE_KEY.to_owned(),
        revision,
    }
}

fn message(id: &str, conversation_id: &str, sequence: u64, updated: u32) -> Message {
    Message {
        id: id.to_owned(),
        conversation_id: conversation_id.to_owned(),
        sequence,
        role: MessageRole::User,
        parts: vec![ContentPart::Text {
            text: format!("message {id}"),
        }],
        status: MessageStatus::Complete,
        provider_id: None,
        model_id: None,
        model_label: None,
        usage: None,
        finish_reason: None,
        error: None,
        latency_ms: None,
        first_token_ms: None,
        created_at: ts(0),
        updated_at: ts(updated),
        deleted_at: None,
    }
}

fn message_with_attachment(
    id: &str,
    conversation_id: &str,
    sequence: u64,
    attachment_id: &str,
) -> Message {
    let mut message = message(id, conversation_id, sequence, 0);
    message.parts = vec![ContentPart::File {
        attachment_id: attachment_id.to_owned(),
        mime_type: "text/plain".to_owned(),
        name: "notes.txt".to_owned(),
    }];
    message
}

fn attachment(id: &str, message_id: &str, sha256: &str) -> Attachment {
    Attachment {
        id: id.to_owned(),
        message_id: Some(message_id.to_owned()),
        kind: AttachmentKind::File,
        mime_type: "text/plain".to_owned(),
        name: "notes.txt".to_owned(),
        size: 5,
        sha256: sha256.to_owned(),
        storage_key: sha256.to_owned(),
        width: None,
        height: None,
        created_at: ts(0),
    }
}

fn encrypt_manifest(manifest: &ManifestBody, passphrase: &str, salt: &[u8; SALT_LEN]) -> Vec<u8> {
    let plaintext = serde_json::to_vec(manifest).expect("serialize manifest");
    encrypt_object(EncryptOptions {
        passphrase,
        salt,
        object_type: ObjectType::Manifest,
        object_key: MANIFEST_OBJECT_KEY,
        plaintext: &plaintext,
        compress: Some(false),
        iv: None,
        iterations: Some(1),
    })
    .expect("encrypt manifest")
}

fn encrypt_payload(
    payload: &ConversationPayload,
    object_key: &str,
    passphrase: &str,
    salt: &[u8; SALT_LEN],
) -> Vec<u8> {
    let plaintext = serde_json::to_vec(payload).expect("serialize payload");
    encrypt_object(EncryptOptions {
        passphrase,
        salt,
        object_type: ObjectType::Conversation,
        object_key,
        plaintext: &plaintext,
        compress: None,
        iv: None,
        iterations: Some(1),
    })
    .expect("encrypt payload")
}

#[derive(Default)]
struct FakeStore {
    config: Mutex<Option<BackupConfig>>,
    conversations: Mutex<BTreeMap<String, Conversation>>,
    messages: Mutex<BTreeMap<String, Vec<Message>>>,
    attachments: Mutex<BTreeMap<String, Attachment>>,
    tombstones: Mutex<Vec<Tombstone>>,
    attachment_bytes: Mutex<BTreeMap<String, Vec<u8>>>,
    queue: Mutex<Vec<String>>,
}

impl FakeStore {
    fn with_config(device_id: &str, salt: &[u8; SALT_LEN]) -> Self {
        let store = Self::default();
        *store.config.lock().unwrap() = Some(config(device_id, salt));
        store
    }

    fn put_conversation(&self, conversation: Conversation, messages: Vec<Message>) {
        self.conversations
            .lock()
            .unwrap()
            .insert(conversation.id.clone(), conversation);
        self.messages
            .lock()
            .unwrap()
            .insert(messages[0].conversation_id.clone(), messages);
    }

    fn put_attachment(&self, attachment: Attachment, bytes: Vec<u8>) {
        self.attachment_bytes
            .lock()
            .unwrap()
            .insert(attachment.sha256.clone(), bytes);
        self.attachments
            .lock()
            .unwrap()
            .insert(attachment.id.clone(), attachment);
    }

    fn enqueue(&self, id: &str) {
        self.queue.lock().unwrap().push(id.to_owned());
    }

    fn conversation(&self, id: &str) -> Option<Conversation> {
        self.conversations.lock().unwrap().get(id).cloned()
    }

    fn messages(&self, conversation_id: &str) -> Vec<Message> {
        self.messages
            .lock()
            .unwrap()
            .get(conversation_id)
            .cloned()
            .unwrap_or_default()
    }

    fn attachment(&self, id: &str) -> Option<Attachment> {
        self.attachments.lock().unwrap().get(id).cloned()
    }

    fn attachment_bytes(&self, sha256: &str) -> Option<Vec<u8>> {
        self.attachment_bytes.lock().unwrap().get(sha256).cloned()
    }

    fn queue_len(&self) -> usize {
        self.queue.lock().unwrap().len()
    }
}

#[async_trait]
impl SyncLocalStore for FakeStore {
    async fn get_backup_config(&self) -> Result<Option<BackupConfig>, LocalStorageError> {
        Ok(self.config.lock().unwrap().clone())
    }

    async fn save_backup_config(&self, config: &BackupConfig) -> Result<(), LocalStorageError> {
        *self.config.lock().unwrap() = Some(config.clone());
        Ok(())
    }

    async fn list_conversations(&self) -> Result<Vec<Conversation>, LocalStorageError> {
        Ok(self
            .conversations
            .lock()
            .unwrap()
            .values()
            .cloned()
            .collect())
    }

    async fn get_conversation(&self, id: &str) -> Result<Option<Conversation>, LocalStorageError> {
        Ok(self.conversation(id))
    }

    async fn list_messages(
        &self,
        conversation_id: &str,
    ) -> Result<Vec<Message>, LocalStorageError> {
        Ok(self.messages(conversation_id))
    }

    async fn list_attachments(&self) -> Result<Vec<Attachment>, LocalStorageError> {
        Ok(self.attachments.lock().unwrap().values().cloned().collect())
    }

    async fn list_tombstones(&self) -> Result<Vec<Tombstone>, LocalStorageError> {
        Ok(self.tombstones.lock().unwrap().clone())
    }

    async fn read_attachment(
        &self,
        storage_key: &str,
    ) -> Result<Option<Vec<u8>>, LocalStorageError> {
        Ok(self.attachment_bytes(storage_key))
    }

    async fn write_attachment(
        &self,
        storage_key: &str,
        bytes: &[u8],
    ) -> Result<(), LocalStorageError> {
        self.attachment_bytes
            .lock()
            .unwrap()
            .insert(storage_key.to_owned(), bytes.to_vec());
        Ok(())
    }

    async fn write_conversation(
        &self,
        conversation: &Conversation,
        messages: &[Message],
        attachments: &[Attachment],
    ) -> Result<(), LocalStorageError> {
        self.conversations
            .lock()
            .unwrap()
            .insert(conversation.id.clone(), conversation.clone());
        self.messages
            .lock()
            .unwrap()
            .insert(conversation.id.clone(), messages.to_vec());
        for attachment in attachments {
            self.attachments
                .lock()
                .unwrap()
                .insert(attachment.id.clone(), attachment.clone());
        }
        Ok(())
    }

    async fn clear_conversations(&self) -> Result<(), LocalStorageError> {
        self.conversations.lock().unwrap().clear();
        self.messages.lock().unwrap().clear();
        self.attachments.lock().unwrap().clear();
        Ok(())
    }

    async fn drain_sync_queue(&self) -> Result<u64, LocalStorageError> {
        let mut queue = self.queue.lock().unwrap();
        let count = queue.len() as u64;
        queue.clear();
        Ok(count)
    }
}

#[derive(Default)]
struct FakeStorage {
    objects: Mutex<BTreeMap<String, StorageObject>>,
    conflicts: Mutex<usize>,
    fail_put_key: Mutex<Option<String>>,
    puts: Mutex<Vec<String>>,
    manifest_gets: Mutex<usize>,
    etag_counter: Mutex<u64>,
}

impl FakeStorage {
    fn put(&self, key: &str, bytes: Vec<u8>) {
        let mut counter = self.etag_counter.lock().unwrap();
        *counter += 1;
        let etag = format!("\"etag-{counter}\"");
        self.objects.lock().unwrap().insert(
            key.to_owned(),
            StorageObject {
                key: key.to_owned(),
                bytes,
                etag: Some(etag),
                last_modified: None,
            },
        );
    }

    fn object(&self, key: &str) -> Option<StorageObject> {
        self.objects.lock().unwrap().get(key).cloned()
    }

    fn has(&self, key: &str) -> bool {
        self.objects.lock().unwrap().contains_key(key)
    }

    fn put_count(&self, key: &str) -> usize {
        self.puts
            .lock()
            .unwrap()
            .iter()
            .filter(|candidate| candidate.as_str() == key)
            .count()
    }

    fn manifest_gets(&self) -> usize {
        *self.manifest_gets.lock().unwrap()
    }

    fn set_conflicts(&self, conflicts: usize) {
        *self.conflicts.lock().unwrap() = conflicts;
    }

    fn fail_put(&self, key: &str) {
        *self.fail_put_key.lock().unwrap() = Some(key.to_owned());
    }
}

#[async_trait]
impl StorageAdapter for FakeStorage {
    async fn test_connection(&self) -> Result<(), StorageError> {
        Ok(())
    }

    async fn get_object(&self, key: &str) -> Result<StorageObject, StorageError> {
        if key == MANIFEST_OBJECT_KEY {
            *self.manifest_gets.lock().unwrap() += 1;
        }
        self.object(key).ok_or_else(|| StorageError::NotFound {
            message: format!("missing {key}"),
            status: Some(404),
        })
    }

    async fn put_object(
        &self,
        key: &str,
        encrypted_bytes: &[u8],
        options: PutObjectOptions,
    ) -> Result<StorageObject, StorageError> {
        self.puts.lock().unwrap().push(key.to_owned());

        if self
            .fail_put_key
            .lock()
            .unwrap()
            .as_deref()
            .is_some_and(|failed| failed == key)
        {
            return Err(StorageError::server("injected upload failure"));
        }

        if key == MANIFEST_OBJECT_KEY {
            let mut conflicts = self.conflicts.lock().unwrap();
            if *conflicts > 0 {
                *conflicts -= 1;
                return Err(StorageError::Conflict {
                    message: "injected manifest conflict".to_owned(),
                    status: Some(409),
                });
            }
        }

        let current = self.object(key);
        if let Some(expected) = options.if_match.as_deref() {
            let actual = current.as_ref().and_then(|object| object.etag.as_deref());
            if actual != Some(expected) {
                return Err(StorageError::Conflict {
                    message: "if-match precondition failed".to_owned(),
                    status: Some(412),
                });
            }
        }
        if options.if_none_match.as_deref() == Some("*") && current.is_some() {
            return Err(StorageError::Conflict {
                message: "if-none-match precondition failed".to_owned(),
                status: Some(412),
            });
        }

        self.put(key, encrypted_bytes.to_vec());
        Ok(self.object(key).expect("object was written"))
    }

    async fn head_object(&self, key: &str) -> Result<StorageObject, StorageError> {
        let mut object = self.get_object(key).await?;
        object.bytes.clear();
        Ok(object)
    }

    async fn delete_object(&self, key: &str) -> Result<(), StorageError> {
        match self.objects.lock().unwrap().remove(key) {
            Some(_) => Ok(()),
            None => Err(StorageError::NotFound {
                message: format!("missing {key}"),
                status: Some(404),
            }),
        }
    }

    async fn list_objects(
        &self,
        options: ListObjectsOptions,
    ) -> Result<ListObjectsResult, StorageError> {
        let prefix = options.prefix.unwrap_or_default();
        let objects = self
            .objects
            .lock()
            .unwrap()
            .values()
            .filter(|object| object.key.starts_with(&prefix))
            .map(|object| StorageObject {
                key: object.key.clone(),
                bytes: Vec::new(),
                etag: object.etag.clone(),
                last_modified: object.last_modified.clone(),
            })
            .collect();
        Ok(ListObjectsResult {
            objects,
            next_continuation_token: None,
        })
    }
}

fn engine(store: Arc<FakeStore>, storage: Arc<FakeStorage>) -> SyncEngine {
    let store: Arc<dyn SyncLocalStore> = store;
    let storage: Arc<dyn StorageAdapter> = storage;
    SyncEngine::new(store, storage, PASSPHRASE)
        .with_iterations(1)
        .with_base_backoff(Duration::ZERO)
}

fn remote_manifest(
    device_id: &str,
    conversations: &[Conversation],
    attachments: &[Attachment],
    tombstones: &[Tombstone],
) -> ManifestBody {
    build_manifest(
        device_id,
        ts(0),
        ts(0),
        conversations,
        attachments,
        tombstones,
    )
    .expect("build remote manifest")
}

#[tokio::test]
async fn empty_remote_creates_a_manifest() {
    let store = Arc::new(FakeStore::with_config(DEVICE_ID, &SALT));
    let storage = Arc::new(FakeStorage::default());

    let result = engine(store.clone(), storage.clone())
        .sync()
        .await
        .expect("sync");

    assert!(result.manifest_etag.is_some());
    let object = storage
        .object(MANIFEST_OBJECT_KEY)
        .expect("manifest exists");
    let decrypted = decrypt_object(PASSPHRASE, &object.bytes).expect("decrypt manifest");
    let manifest =
        crate::manifest::parse_manifest(decrypted.plaintext.as_slice()).expect("parse manifest");
    assert!(manifest.conversations.is_empty());
    assert_eq!(manifest.format_version, MANIFEST_FORMAT_VERSION);
}

#[tokio::test]
async fn local_only_changes_are_uploaded_encrypted() {
    let store = Arc::new(FakeStore::with_config(DEVICE_ID, &SALT));
    let storage = Arc::new(FakeStorage::default());
    let local = conversation("conversation-1", 1, 1);
    store.put_conversation(
        local.clone(),
        vec![message("message-1", "conversation-1", 0, 0)],
    );

    let result = engine(store.clone(), storage.clone())
        .sync()
        .await
        .expect("sync");

    assert_eq!(result.uploaded_conversations, vec!["conversation-1"]);
    let key = conversation_object_key(&local.storage_key);
    let object = storage.object(&key).expect("conversation object exists");
    let decrypted = decrypt_object(PASSPHRASE, &object.bytes).expect("decrypt conversation");
    let payload =
        crate::conversation_payload::parse_conversation_payload(decrypted.plaintext.as_slice())
            .expect("parse payload");
    assert_eq!(payload.messages.len(), 1);
    assert_eq!(payload.messages[0].id, "message-1");
}

#[tokio::test]
async fn remote_only_changes_are_downloaded() {
    let store = Arc::new(FakeStore::with_config(DEVICE_ID, &SALT));
    let storage = Arc::new(FakeStorage::default());
    let remote = conversation("conversation-1", 1, 1);
    let remote_messages = vec![message("message-1", "conversation-1", 0, 0)];
    let payload = build_conversation_payload(&remote, &remote_messages, &[]).expect("payload");
    let key = conversation_object_key(&remote.storage_key);
    storage.put(&key, encrypt_payload(&payload, &key, PASSPHRASE, &SALT));
    let manifest = remote_manifest("device-remote", &[remote], &[], &[]);
    storage.put(
        MANIFEST_OBJECT_KEY,
        encrypt_manifest(&manifest, PASSPHRASE, &SALT),
    );

    let result = engine(store.clone(), storage.clone())
        .sync()
        .await
        .expect("sync");

    assert_eq!(result.downloaded_conversations, vec!["conversation-1"]);
    assert!(store.conversation("conversation-1").is_some());
    assert_eq!(store.messages("conversation-1").len(), 1);
}

#[tokio::test]
async fn both_sides_changes_merge_and_upload() {
    let store = Arc::new(FakeStore::with_config(DEVICE_ID, &SALT));
    let storage = Arc::new(FakeStorage::default());
    let local = conversation("conversation-1", 1, 1);
    store.put_conversation(
        local.clone(),
        vec![message("message-1", "conversation-1", 0, 0)],
    );

    let remote = conversation("conversation-1", 2, 2);
    let remote_messages = vec![
        message("message-1", "conversation-1", 0, 0),
        message("message-2", "conversation-1", 1, 1),
    ];
    let payload = build_conversation_payload(&remote, &remote_messages, &[]).expect("payload");
    let key = conversation_object_key(&remote.storage_key);
    storage.put(&key, encrypt_payload(&payload, &key, PASSPHRASE, &SALT));
    let manifest = remote_manifest("device-remote", &[remote], &[], &[]);
    storage.put(
        MANIFEST_OBJECT_KEY,
        encrypt_manifest(&manifest, PASSPHRASE, &SALT),
    );

    let result = engine(store.clone(), storage.clone())
        .sync()
        .await
        .expect("sync");

    assert_eq!(result.downloaded_conversations, vec!["conversation-1"]);
    assert_eq!(result.uploaded_conversations, vec!["conversation-1"]);
    let messages = store.messages("conversation-1");
    assert_eq!(messages.len(), 2);
    assert!(messages.iter().any(|message| message.id == "message-1"));
    assert!(messages.iter().any(|message| message.id == "message-2"));
}

#[tokio::test]
async fn dirty_attachments_are_uploaded_encrypted() {
    let store = Arc::new(FakeStore::with_config(DEVICE_ID, &SALT));
    let storage = Arc::new(FakeStorage::default());
    let local = conversation("conversation-1", 1, 1);
    let bytes = b"hello".to_vec();
    let sha256 = chatui_storage::sha256_hex(&bytes);
    let attachment = attachment("attachment-1", "message-1", &sha256);
    store.put_conversation(
        local.clone(),
        vec![message_with_attachment(
            "message-1",
            "conversation-1",
            0,
            "attachment-1",
        )],
    );
    store.put_attachment(attachment, bytes.clone());

    let result = engine(store.clone(), storage.clone())
        .sync()
        .await
        .expect("sync");

    assert_eq!(result.uploaded_attachments, vec![sha256.clone()]);
    let key = attachment_object_key(&sha256);
    let object = storage.object(&key).expect("attachment object exists");
    let decrypted = decrypt_object(PASSPHRASE, &object.bytes).expect("decrypt attachment");
    assert_eq!(decrypted.plaintext.as_slice(), bytes.as_slice());
}

#[tokio::test]
async fn manifest_conflict_retries_and_rereads() {
    let store = Arc::new(FakeStore::with_config(DEVICE_ID, &SALT));
    let storage = Arc::new(FakeStorage::default());
    store.put_conversation(
        conversation("conversation-1", 1, 1),
        vec![message("message-1", "conversation-1", 0, 0)],
    );
    storage.set_conflicts(2);

    let result = engine(store.clone(), storage.clone())
        .sync()
        .await
        .expect("sync");

    assert!(result.manifest_etag.is_some());
    assert!(storage.put_count(MANIFEST_OBJECT_KEY) >= 3);
    assert!(storage.manifest_gets() >= 2);
    assert!(storage.has(MANIFEST_OBJECT_KEY));
}

#[tokio::test]
async fn interrupted_upload_leaves_local_data_unchanged() {
    let store = Arc::new(FakeStore::with_config(DEVICE_ID, &SALT));
    let storage = Arc::new(FakeStorage::default());
    let local = conversation("conversation-1", 1, 1);
    store.put_conversation(
        local.clone(),
        vec![message("message-1", "conversation-1", 0, 0)],
    );
    storage.fail_put(&conversation_object_key(&local.storage_key));

    let error = engine(store.clone(), storage.clone())
        .sync()
        .await
        .expect_err("upload must fail");

    assert!(matches!(error, SyncError::Storage(_)));
    let unchanged = store.conversation("conversation-1").expect("conversation");
    assert_eq!(unchanged, local);
    assert_eq!(store.messages("conversation-1").len(), 1);
    assert!(!storage.has(MANIFEST_OBJECT_KEY));
}

#[tokio::test]
async fn restore_merges_by_default_and_never_wipes_local_data() {
    let store = Arc::new(FakeStore::with_config(DEVICE_ID, &SALT));
    let storage = Arc::new(FakeStorage::default());
    store.put_conversation(
        conversation("conversation-1", 1, 1),
        vec![message("message-1", "conversation-1", 0, 0)],
    );

    let remote = conversation("conversation-2", 1, 1);
    let remote_messages = vec![message("message-2", "conversation-2", 0, 0)];
    let payload = build_conversation_payload(&remote, &remote_messages, &[]).expect("payload");
    let key = conversation_object_key(&remote.storage_key);
    storage.put(&key, encrypt_payload(&payload, &key, PASSPHRASE, &SALT));
    let manifest = remote_manifest("device-remote", &[remote], &[], &[]);
    storage.put(
        MANIFEST_OBJECT_KEY,
        encrypt_manifest(&manifest, PASSPHRASE, &SALT),
    );

    let result = engine(store.clone(), storage.clone())
        .restore(RestoreOptions::default())
        .await
        .expect("restore");

    assert!(!result.replaced);
    assert_eq!(result.merged_conversations, vec!["conversation-2"]);
    assert!(store.conversation("conversation-1").is_some());
    assert!(store.conversation("conversation-2").is_some());
    assert_eq!(store.messages("conversation-1").len(), 1);
    assert_eq!(store.messages("conversation-2").len(), 1);
}

#[tokio::test]
async fn restore_replace_clears_local_conversations() {
    let store = Arc::new(FakeStore::with_config(DEVICE_ID, &SALT));
    let storage = Arc::new(FakeStorage::default());
    store.put_conversation(
        conversation("conversation-1", 1, 1),
        vec![message("message-1", "conversation-1", 0, 0)],
    );

    let remote = conversation("conversation-2", 1, 1);
    let remote_messages = vec![message("message-2", "conversation-2", 0, 0)];
    let payload = build_conversation_payload(&remote, &remote_messages, &[]).expect("payload");
    let key = conversation_object_key(&remote.storage_key);
    storage.put(&key, encrypt_payload(&payload, &key, PASSPHRASE, &SALT));
    let manifest = remote_manifest("device-remote", &[remote], &[], &[]);
    storage.put(
        MANIFEST_OBJECT_KEY,
        encrypt_manifest(&manifest, PASSPHRASE, &SALT),
    );

    let result = engine(store.clone(), storage.clone())
        .restore(RestoreOptions { replace: true })
        .await
        .expect("restore");

    assert!(result.replaced);
    assert!(store.conversation("conversation-1").is_none());
    assert!(store.conversation("conversation-2").is_some());
}

#[tokio::test]
async fn wrong_passphrase_fails_before_local_mutation() {
    let store = Arc::new(FakeStore::with_config(DEVICE_ID, &SALT));
    let storage = Arc::new(FakeStorage::default());
    let local = conversation("conversation-1", 1, 1);
    store.put_conversation(
        local.clone(),
        vec![message("message-1", "conversation-1", 0, 0)],
    );

    let remote = conversation("conversation-2", 1, 1);
    let payload = build_conversation_payload(&remote, &[], &[]).expect("payload");
    let key = conversation_object_key(&remote.storage_key);
    storage.put(
        &key,
        encrypt_payload(&payload, &key, "different passphrase", &SALT),
    );
    let manifest = remote_manifest("device-remote", &[remote], &[], &[]);
    storage.put(
        MANIFEST_OBJECT_KEY,
        encrypt_manifest(&manifest, "different passphrase", &SALT),
    );

    let error = engine(store.clone(), storage.clone())
        .sync()
        .await
        .expect_err("wrong passphrase must fail");

    assert!(matches!(error, SyncError::Envelope(_)));
    assert_eq!(store.conversation("conversation-1"), Some(local));
    assert_eq!(store.messages("conversation-1").len(), 1);
    assert!(store.conversation("conversation-2").is_none());
}

#[tokio::test]
async fn deletion_tombstone_wins_over_an_older_update() {
    let store = Arc::new(FakeStore::with_config(DEVICE_ID, &SALT));
    let storage = Arc::new(FakeStorage::default());
    store.put_conversation(
        conversation("conversation-1", 1, 1),
        vec![message("message-1", "conversation-1", 0, 0)],
    );
    let tombstone = Tombstone {
        id: "tombstone-1".to_owned(),
        conversation_id: "conversation-1".to_owned(),
        message_id: None,
        deleted_at: ts(2),
        device_id: "device-remote".to_owned(),
    };
    let manifest = remote_manifest("device-remote", &[], &[], &[tombstone]);
    storage.put(
        MANIFEST_OBJECT_KEY,
        encrypt_manifest(&manifest, PASSPHRASE, &SALT),
    );

    let result = engine(store.clone(), storage.clone())
        .sync()
        .await
        .expect("sync");

    assert!(result.uploaded_conversations.is_empty());
    let object = storage.object(MANIFEST_OBJECT_KEY).expect("manifest");
    let decrypted = decrypt_object(PASSPHRASE, &object.bytes).expect("decrypt manifest");
    let written =
        crate::manifest::parse_manifest(decrypted.plaintext.as_slice()).expect("parse manifest");
    assert!(written.conversations.is_empty());
    assert_eq!(written.tombstones.len(), 1);
    assert!(store.conversation("conversation-1").is_some());
}

#[tokio::test]
async fn sync_drains_the_queue_after_success() {
    let store = Arc::new(FakeStore::with_config(DEVICE_ID, &SALT));
    let storage = Arc::new(FakeStorage::default());
    store.put_conversation(
        conversation("conversation-1", 1, 1),
        vec![message("message-1", "conversation-1", 0, 0)],
    );
    store.enqueue("queue-1");
    store.enqueue("queue-2");

    let result = engine(store.clone(), storage.clone())
        .sync()
        .await
        .expect("sync");

    assert_eq!(result.drained_queue, 2);
    assert_eq!(store.queue_len(), 0);
}

#[tokio::test]
async fn device_id_and_salt_are_created_on_first_setup() {
    let store = Arc::new(FakeStore::default());
    let storage = Arc::new(FakeStorage::default());
    let engine = engine(store.clone(), storage);

    let first = engine.ensure_identity().await.expect("identity");
    assert!(!first.device_id.is_empty());
    assert_eq!(first.salt.len(), SALT_LEN);

    let saved = store
        .get_backup_config()
        .await
        .expect("config")
        .expect("config exists");
    assert_eq!(saved.device_id, first.device_id);
    assert_eq!(
        STANDARD
            .decode(saved.kdf_salt.expect("salt"))
            .expect("base64"),
        first.salt
    );

    let second = engine.ensure_identity().await.expect("identity");
    assert_eq!(first, second);
}

#[tokio::test]
async fn delete_remote_requires_confirmation_and_never_touches_local_data() {
    let store = Arc::new(FakeStore::with_config(DEVICE_ID, &SALT));
    let storage = Arc::new(FakeStorage::default());
    let local = conversation("conversation-1", 1, 1);
    store.put_conversation(
        local.clone(),
        vec![message("message-1", "conversation-1", 0, 0)],
    );
    storage.put(MANIFEST_OBJECT_KEY, vec![1, 2, 3]);
    storage.put(&conversation_object_key(&local.storage_key), vec![4, 5, 6]);

    let engine = engine(store.clone(), storage.clone());
    let error = engine
        .delete_remote("wrong token")
        .await
        .expect_err("confirmation required");
    assert!(matches!(error, SyncError::ConfirmationRequired));
    assert!(storage.has(MANIFEST_OBJECT_KEY));

    engine
        .delete_remote(DELETE_REMOTE_CONFIRMATION)
        .await
        .expect("delete remote");
    assert!(!storage.has(MANIFEST_OBJECT_KEY));
    assert!(!storage.has(&conversation_object_key(&local.storage_key)));
    assert_eq!(store.conversation("conversation-1"), Some(local));
    assert_eq!(store.messages("conversation-1").len(), 1);
}

#[tokio::test]
async fn attachment_metadata_is_written_when_restoring() {
    let store = Arc::new(FakeStore::with_config(DEVICE_ID, &SALT));
    let storage = Arc::new(FakeStorage::default());
    let remote = conversation("conversation-1", 1, 1);
    let remote_messages = vec![message_with_attachment(
        "message-1",
        "conversation-1",
        0,
        "attachment-1",
    )];
    let remote_attachment = attachment("attachment-1", "message-1", SHA256);
    let payload = build_conversation_payload(
        &remote,
        &remote_messages,
        std::slice::from_ref(&remote_attachment),
    )
    .expect("payload");
    let key = conversation_object_key(&remote.storage_key);
    storage.put(&key, encrypt_payload(&payload, &key, PASSPHRASE, &SALT));
    let manifest = remote_manifest(
        "device-remote",
        &[remote],
        std::slice::from_ref(&remote_attachment),
        &[],
    );
    storage.put(
        MANIFEST_OBJECT_KEY,
        encrypt_manifest(&manifest, PASSPHRASE, &SALT),
    );

    engine(store.clone(), storage.clone())
        .restore(RestoreOptions::default())
        .await
        .expect("restore");

    let restored = store.attachment("attachment-1").expect("attachment");
    assert_eq!(restored.message_id.as_deref(), Some("message-1"));
    assert_eq!(restored.sha256, SHA256);
}
