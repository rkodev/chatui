//! Draft cache bounds and persistence tests.

use std::collections::BTreeMap;
use std::sync::{Arc, Mutex};

use async_trait::async_trait;
use chatui_storage::{SettingsRepository, SqliteSettingsRepository, StorageError, open_in_memory};

use super::drafts::{
    DRAFT_KEY_PREFIX, DraftPersistence, DraftStore, MAX_DRAFT_LENGTH, MAX_DRAFTS, MemoryDraftStore,
    PersistentDraftStore, SettingsDraftPersistence,
};

#[derive(Default)]
struct FakePersistence {
    drafts: Mutex<BTreeMap<String, String>>,
}

#[async_trait]
impl DraftPersistence for FakePersistence {
    async fn load(&self) -> Result<BTreeMap<String, String>, StorageError> {
        Ok(self
            .drafts
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
            .clone())
    }

    async fn save(&self, conversation_id: &str, text: &str) -> Result<(), StorageError> {
        self.drafts
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
            .insert(conversation_id.to_owned(), text.to_owned());
        Ok(())
    }

    async fn delete(&self, conversation_id: &str) -> Result<(), StorageError> {
        self.drafts
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
            .remove(conversation_id);
        Ok(())
    }
}

#[test]
fn drafts_are_bounded_and_evict_oldest() {
    let store = MemoryDraftStore::new();

    for index in 0..=MAX_DRAFTS {
        store.set_draft(&format!("c{index}"), &format!("draft {index}"));
    }

    assert_eq!(store.drafts().len(), MAX_DRAFTS);
    assert!(store.draft("c0").is_none());
    assert!(store.draft(&format!("c{MAX_DRAFTS}")).is_some());
}

#[test]
fn drafts_are_truncated_to_the_maximum_length() {
    let store = MemoryDraftStore::new();
    let text = "a".repeat(MAX_DRAFT_LENGTH + 10);

    store.set_draft("c1", &text);

    assert_eq!(
        store.draft("c1").expect("draft").chars().count(),
        MAX_DRAFT_LENGTH
    );
}

#[test]
fn empty_text_clears_the_draft() {
    let store = MemoryDraftStore::new();
    store.set_draft("c1", "hello");
    assert!(store.draft("c1").is_some());

    store.set_draft("c1", "");
    assert!(store.draft("c1").is_none());
}

#[tokio::test]
async fn persistent_drafts_load_and_flush_through_persistence() {
    let persistence = Arc::new(FakePersistence::default());
    persistence.save("c1", "saved").await.expect("seed");

    let store = PersistentDraftStore::new(persistence.clone());
    store.load().await.expect("load");
    assert_eq!(store.draft("c1").as_deref(), Some("saved"));

    store.set_draft("c2", "new");
    assert!(store.is_dirty());
    store.flush().await.expect("flush");
    assert!(!store.is_dirty());
    assert_eq!(
        persistence
            .drafts
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
            .get("c2")
            .map(String::as_str),
        Some("new")
    );

    store.clear_draft("c1");
    store.flush().await.expect("flush clear");
    assert!(
        !persistence
            .drafts
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
            .contains_key("c1")
    );
}

#[tokio::test]
async fn settings_draft_persistence_round_trips() {
    let pool = open_in_memory().await.expect("open database");
    let settings: Arc<dyn SettingsRepository> = Arc::new(SqliteSettingsRepository::new(pool));
    let persistence = SettingsDraftPersistence::new(settings);

    persistence.save("c1", "hello").await.expect("save");
    let loaded = persistence.load().await.expect("load");
    assert_eq!(loaded.get("c1").map(String::as_str), Some("hello"));
    assert_eq!(
        SettingsDraftPersistence::key("c1"),
        format!("{DRAFT_KEY_PREFIX}c1")
    );

    persistence.delete("c1").await.expect("delete");
    assert!(
        persistence
            .load()
            .await
            .expect("load after delete")
            .is_empty()
    );
}
