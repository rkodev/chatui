//! Per-conversation composer drafts.
//!
//! Drafts are keyed by conversation ID so navigating between conversations
//! restores the text the user typed. [`MemoryDraftStore`] is the process-local
//! cache used by the composer and tests. [`PersistentDraftStore`] adds a
//! debounced persistence boundary backed by [`SettingsDraftPersistence`], which
//! stores non-secret draft text in the typed settings repository.
//!
//! Drafts are bounded by [`MAX_DRAFTS`] conversations and [`MAX_DRAFT_LENGTH`]
//! characters. When the conversation cap is exceeded the least recently updated
//! drafts are evicted. Attachments, provider keys, and other secrets never pass
//! through this module.

use std::collections::{BTreeMap, BTreeSet};
use std::sync::{Arc, Mutex, MutexGuard};

use async_trait::async_trait;
use chatui_storage::{SettingsRepository, StorageError};
use serde_json::Value;

/// Settings key prefix for a persisted draft.
pub const DRAFT_KEY_PREFIX: &str = "draft:";
/// Maximum characters stored per draft.
pub const MAX_DRAFT_LENGTH: usize = 32_000;
/// Maximum number of conversations with a stored draft.
pub const MAX_DRAFTS: usize = 50;

/// Synchronous draft cache used by the composer.
pub trait DraftStore: Send + Sync {
    /// Return the draft for a conversation, or `None` when empty.
    fn draft(&self, conversation_id: &str) -> Option<String>;
    /// Store or replace a draft. Empty text clears the draft.
    fn set_draft(&self, conversation_id: &str, text: &str);
    /// Remove the draft for a conversation.
    fn clear_draft(&self, conversation_id: &str);
    /// Return every non-empty draft.
    fn drafts(&self) -> BTreeMap<String, String>;
}

#[derive(Debug, Clone)]
struct DraftEntry {
    text: String,
    updated_at: u64,
}

#[derive(Debug, Default)]
struct DraftCache {
    entries: BTreeMap<String, DraftEntry>,
    sequence: u64,
}

impl DraftCache {
    fn evict(&mut self) {
        while self.entries.len() > MAX_DRAFTS {
            let Some(oldest) = self
                .entries
                .iter()
                .min_by_key(|(_, entry)| entry.updated_at)
                .map(|(id, _)| id.clone())
            else {
                break;
            };
            self.entries.remove(&oldest);
        }
    }
}

fn bound_draft(text: &str) -> String {
    text.chars().take(MAX_DRAFT_LENGTH).collect()
}

/// Thread-safe in-memory draft store used by the application and tests.
#[derive(Debug, Default)]
pub struct MemoryDraftStore {
    inner: Mutex<DraftCache>,
}

impl MemoryDraftStore {
    /// Create an empty store.
    pub fn new() -> Self {
        Self::default()
    }

    /// Replace every cached draft, for example after loading from disk.
    pub fn replace_all(&self, drafts: BTreeMap<String, String>) {
        let mut cache = self.lock();
        cache.entries.clear();
        cache.sequence = 0;

        for (conversation_id, text) in drafts {
            if text.is_empty() {
                continue;
            }

            cache.sequence += 1;
            let updated_at = cache.sequence;
            cache.entries.insert(
                conversation_id,
                DraftEntry {
                    text: bound_draft(&text),
                    updated_at,
                },
            );
        }

        cache.evict();
    }

    fn lock(&self) -> MutexGuard<'_, DraftCache> {
        self.inner
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
    }
}

impl DraftStore for MemoryDraftStore {
    fn draft(&self, conversation_id: &str) -> Option<String> {
        self.lock()
            .entries
            .get(conversation_id)
            .map(|entry| entry.text.clone())
    }

    fn set_draft(&self, conversation_id: &str, text: &str) {
        if text.is_empty() {
            self.clear_draft(conversation_id);
            return;
        }

        let mut cache = self.lock();
        cache.sequence += 1;
        let updated_at = cache.sequence;
        cache.entries.insert(
            conversation_id.to_owned(),
            DraftEntry {
                text: bound_draft(text),
                updated_at,
            },
        );
        cache.evict();
    }

    fn clear_draft(&self, conversation_id: &str) {
        self.lock().entries.remove(conversation_id);
    }

    fn drafts(&self) -> BTreeMap<String, String> {
        self.lock()
            .entries
            .iter()
            .map(|(conversation_id, entry)| (conversation_id.clone(), entry.text.clone()))
            .collect()
    }
}

/// Asynchronous persistence boundary for drafts.
#[async_trait]
pub trait DraftPersistence: Send + Sync {
    /// Load every persisted draft.
    async fn load(&self) -> Result<BTreeMap<String, String>, StorageError>;
    /// Persist one draft.
    async fn save(&self, conversation_id: &str, text: &str) -> Result<(), StorageError>;
    /// Delete one persisted draft.
    async fn delete(&self, conversation_id: &str) -> Result<(), StorageError>;
}

/// Persists drafts in the non-secret typed settings repository.
pub struct SettingsDraftPersistence {
    settings: Arc<dyn SettingsRepository>,
}

impl SettingsDraftPersistence {
    /// Create a persistence boundary over the settings repository.
    pub fn new(settings: Arc<dyn SettingsRepository>) -> Self {
        Self { settings }
    }

    /// Settings key for a conversation draft.
    pub fn key(conversation_id: &str) -> String {
        format!("{DRAFT_KEY_PREFIX}{conversation_id}")
    }
}

#[async_trait]
impl DraftPersistence for SettingsDraftPersistence {
    async fn load(&self) -> Result<BTreeMap<String, String>, StorageError> {
        let mut drafts = BTreeMap::new();

        for record in self.settings.list().await? {
            let Some(conversation_id) = record.key.strip_prefix(DRAFT_KEY_PREFIX) else {
                continue;
            };

            if let Value::String(text) = record.value
                && !text.is_empty()
            {
                drafts.insert(conversation_id.to_owned(), text);
            }
        }

        Ok(drafts)
    }

    async fn save(&self, conversation_id: &str, text: &str) -> Result<(), StorageError> {
        self.settings
            .set(&Self::key(conversation_id), &Value::String(text.to_owned()))
            .await
    }

    async fn delete(&self, conversation_id: &str) -> Result<(), StorageError> {
        self.settings.delete(&Self::key(conversation_id)).await?;
        Ok(())
    }
}

/// Draft store with a synchronous cache and an asynchronous persistence flush.
///
/// The composer reads and writes the cache synchronously. The application calls
/// [`PersistentDraftStore::load`] once at startup and [`flush`] on a debounce or
/// when the conversation changes. A failed flush leaves the cache usable and the
/// affected keys dirty for the next attempt.
///
/// [`flush`]: PersistentDraftStore::flush
pub struct PersistentDraftStore {
    cache: MemoryDraftStore,
    persistence: Arc<dyn DraftPersistence>,
    dirty: Mutex<BTreeSet<String>>,
}

impl PersistentDraftStore {
    /// Create a store over a persistence boundary.
    pub fn new(persistence: Arc<dyn DraftPersistence>) -> Self {
        Self {
            cache: MemoryDraftStore::new(),
            persistence,
            dirty: Mutex::new(BTreeSet::new()),
        }
    }

    /// Load persisted drafts into the cache.
    pub async fn load(&self) -> Result<(), StorageError> {
        let drafts = self.persistence.load().await?;
        self.cache.replace_all(drafts);
        self.dirty
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
            .clear();
        Ok(())
    }

    /// Persist every dirty draft.
    pub async fn flush(&self) -> Result<(), StorageError> {
        let dirty: Vec<String> = {
            let mut guard = self
                .dirty
                .lock()
                .unwrap_or_else(|poisoned| poisoned.into_inner());
            std::mem::take(&mut *guard).into_iter().collect()
        };

        for conversation_id in dirty {
            match self.cache.draft(&conversation_id) {
                Some(text) => self.persistence.save(&conversation_id, &text).await?,
                None => self.persistence.delete(&conversation_id).await?,
            }
        }

        Ok(())
    }

    /// True when a draft change has not been flushed.
    pub fn is_dirty(&self) -> bool {
        !self
            .dirty
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
            .is_empty()
    }

    fn mark_dirty(&self, conversation_id: &str) {
        self.dirty
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
            .insert(conversation_id.to_owned());
    }
}

impl DraftStore for PersistentDraftStore {
    fn draft(&self, conversation_id: &str) -> Option<String> {
        self.cache.draft(conversation_id)
    }

    fn set_draft(&self, conversation_id: &str, text: &str) {
        self.cache.set_draft(conversation_id, text);
        self.mark_dirty(conversation_id);
    }

    fn clear_draft(&self, conversation_id: &str) {
        self.cache.clear_draft(conversation_id);
        self.mark_dirty(conversation_id);
    }

    fn drafts(&self) -> BTreeMap<String, String> {
        self.cache.drafts()
    }
}
