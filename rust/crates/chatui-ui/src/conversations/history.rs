//! Conversation history view model.
//!
//! [`ConversationHistory`] owns the sidebar state and the persistence calls
//! behind it. It is framework-independent: the Slint shell binds to the state
//! and calls the async methods from its event handlers.
//!
//! # Loading
//!
//! History is loaded in bounded pages. [`ConversationHistory::load`] asks the
//! repository for at most [`HistoryConfig::page_size`] rows (100 by default) and
//! [`ConversationHistory::load_more`] grows the bounded window up to
//! [`HistoryConfig::max_rows`] (500). The repository has no cursor API yet, so
//! `load_more` re-reads the bounded window rather than loading the whole table.
//!
//! # Delete and undo
//!
//! Deleting is buffered for [`HistoryConfig::undo_window`] (5 seconds by
//! default), matching the web and Android clients. The conversation disappears
//! from the sidebar immediately, but the storage delete only runs when the
//! window expires, another delete starts, or the caller forces a commit. Undo
//! cancels the buffered delete, so messages and attachment references are never
//! removed and need no restore path.
//!
//! # Last active
//!
//! The last-active conversation ID lives in the typed settings repository.
//! [`ConversationHistory::restore_last_active`] clears a stale or archived ID
//! and returns `None` so the shell can fall back to a safe new chat.

use std::collections::BTreeSet;
use std::fmt;
use std::sync::Arc;
use std::time::{Duration, Instant};

use chatui_domain::{Conversation, Timestamp};
use chatui_storage::{
    ConversationFilter, ConversationRepository, SettingsRepository, StorageError,
};

use crate::chat::drafts::DraftStore;

use super::factory::ConversationFactory;
use super::grouping::{HistoryGroup, group_conversations};

/// Default title used before the first user message supplies one.
pub const DEFAULT_CONVERSATION_TITLE: &str = "New chat";
/// Default bounded history page size.
pub const DEFAULT_HISTORY_PAGE_SIZE: u32 = 100;
/// Hard cap on the bounded history window.
pub const MAX_HISTORY_ROWS: u32 = 500;
/// Undo window required by spec section 3.2.
pub const DEFAULT_UNDO_WINDOW: Duration = Duration::from_secs(5);

/// Bounded history loading configuration.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct HistoryConfig {
    /// Rows requested per load.
    pub page_size: u32,
    /// Maximum rows kept in the sidebar window.
    pub max_rows: u32,
    /// Delete undo window.
    pub undo_window: Duration,
}

impl Default for HistoryConfig {
    fn default() -> Self {
        Self {
            page_size: DEFAULT_HISTORY_PAGE_SIZE,
            max_rows: MAX_HISTORY_ROWS,
            undo_window: DEFAULT_UNDO_WINDOW,
        }
    }
}

impl HistoryConfig {
    /// Clamp the configuration to the supported bounds.
    pub fn normalized(self) -> Self {
        let max_rows = self.max_rows.clamp(1, MAX_HISTORY_ROWS);
        let page_size = self.page_size.clamp(1, max_rows);

        Self {
            page_size,
            max_rows,
            undo_window: self.undo_window,
        }
    }
}

/// Loading state for the history sidebar.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum HistoryStatus {
    /// A load is in flight.
    Loading,
    /// The sidebar has data.
    Ready,
    /// The last load failed.
    Error,
}

/// A content-free history failure safe to show in the UI.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum HistoryError {
    /// No provider or model is configured for a new chat.
    NotConfigured(String),
    /// The requested conversation does not exist.
    NotFound(String),
    /// The caller supplied an invalid value.
    InvalidInput(String),
    /// A repository operation failed.
    Storage(String),
}

impl HistoryError {
    /// Wrap a storage failure without exposing message content.
    pub fn storage(error: StorageError) -> Self {
        Self::Storage(error.to_string())
    }

    /// User-facing message.
    pub fn message(&self) -> &str {
        match self {
            Self::NotConfigured(message)
            | Self::NotFound(message)
            | Self::InvalidInput(message) => message,
            Self::Storage(_) => "Conversation history is unavailable right now. Try again.",
        }
    }
}

impl fmt::Display for HistoryError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter.write_str(self.message())
    }
}

impl std::error::Error for HistoryError {}

/// Sidebar state.
#[derive(Debug, Clone, PartialEq)]
pub struct HistoryState {
    /// Loading status.
    pub status: HistoryStatus,
    /// Content-free error message when `status` is [`HistoryStatus::Error`].
    pub error: Option<String>,
    /// Bounded conversation window.
    pub conversations: Vec<Conversation>,
    /// Grouped conversations in display order.
    pub groups: Vec<HistoryGroup>,
    /// Whether the archived view is active.
    pub archived: bool,
    /// Last-active conversation ID.
    pub active_conversation_id: Option<String>,
    /// Conversations with a persisted draft.
    pub draft_ids: BTreeSet<String>,
    /// Conversation waiting for the undo window to expire.
    pub pending_delete: Option<Conversation>,
}

impl HistoryState {
    /// Create the initial loading state.
    pub fn new() -> Self {
        Self {
            status: HistoryStatus::Loading,
            error: None,
            conversations: Vec::new(),
            groups: Vec::new(),
            archived: false,
            active_conversation_id: None,
            draft_ids: BTreeSet::new(),
            pending_delete: None,
        }
    }

    /// True when the current view has no conversations.
    pub fn is_empty(&self) -> bool {
        self.conversations.is_empty()
    }
}

impl Default for HistoryState {
    fn default() -> Self {
        Self::new()
    }
}

struct PendingDelete {
    conversation: Conversation,
    deadline: Instant,
    was_active: bool,
}

/// Conversation history view model over the injected repositories.
pub struct ConversationHistory {
    repository: Arc<dyn ConversationRepository>,
    settings: Arc<dyn SettingsRepository>,
    drafts: Arc<dyn DraftStore>,
    factory: Arc<dyn ConversationFactory>,
    config: HistoryConfig,
    state: HistoryState,
    pending: Option<PendingDelete>,
}

impl ConversationHistory {
    /// Build a history view model.
    pub fn new(
        repository: Arc<dyn ConversationRepository>,
        settings: Arc<dyn SettingsRepository>,
        drafts: Arc<dyn DraftStore>,
        factory: Arc<dyn ConversationFactory>,
        config: HistoryConfig,
    ) -> Self {
        Self {
            repository,
            settings,
            drafts,
            factory,
            config: config.normalized(),
            state: HistoryState::new(),
            pending: None,
        }
    }

    /// Current sidebar state.
    pub fn state(&self) -> &HistoryState {
        &self.state
    }

    /// Effective configuration.
    pub fn config(&self) -> HistoryConfig {
        self.config
    }

    /// True when the current view has no conversations.
    pub fn is_empty(&self) -> bool {
        self.state.is_empty()
    }

    /// True when the conversation has a persisted draft.
    pub fn has_draft(&self, conversation_id: &str) -> bool {
        self.state.draft_ids.contains(conversation_id)
    }

    /// Conversation IDs with a persisted draft.
    pub fn draft_ids(&self) -> &BTreeSet<String> {
        &self.state.draft_ids
    }

    /// Conversation waiting for the undo window to expire.
    pub fn pending_delete(&self) -> Option<&Conversation> {
        self.state.pending_delete.as_ref()
    }

    /// Load the active or archived history view.
    pub async fn load(&mut self, archived: bool) -> Result<(), HistoryError> {
        self.state.status = HistoryStatus::Loading;
        self.state.error = None;
        self.state.archived = archived;

        let result = async {
            let conversations = self.fetch(archived).await?;
            let draft_ids = self.drafts.drafts().into_keys().collect();
            let settings = self.settings.load().await.map_err(HistoryError::storage)?;

            Ok::<_, HistoryError>((
                conversations,
                draft_ids,
                settings.last_active_conversation_id,
            ))
        }
        .await;

        match result {
            Ok((conversations, draft_ids, active_conversation_id)) => {
                let pending_id = self
                    .state
                    .pending_delete
                    .as_ref()
                    .map(|pending| pending.id.as_str());
                self.state.conversations = conversations
                    .into_iter()
                    .filter(|conversation| Some(conversation.id.as_str()) != pending_id)
                    .collect();
                self.state.groups =
                    group_conversations(&self.state.conversations, Timestamp::now());
                self.state.draft_ids = draft_ids;
                self.state.active_conversation_id = active_conversation_id;
                self.state.status = HistoryStatus::Ready;
                Ok(())
            }
            Err(error) => {
                self.state.status = HistoryStatus::Error;
                self.state.error = Some(error.message().to_owned());
                self.state.conversations.clear();
                self.state.groups.clear();
                Err(error)
            }
        }
    }

    /// Reload the current view.
    pub async fn refresh(&mut self) -> Result<(), HistoryError> {
        self.load(self.state.archived).await
    }

    /// Grow the bounded window by one page.
    ///
    /// Returns `true` when the window grew. The repository has no cursor API,
    /// so this re-reads the bounded window instead of loading the whole table.
    pub async fn load_more(&mut self) -> Result<bool, HistoryError> {
        let current = self.state.conversations.len();

        if current >= self.config.max_rows as usize {
            return Ok(false);
        }

        let next = (current + self.config.page_size as usize).min(self.config.max_rows as usize);
        let filter = ConversationFilter {
            pinned: None,
            archived: Some(self.state.archived),
            limit: Some(next as u32),
        };
        let conversations = self
            .repository
            .list(&filter)
            .await
            .map_err(HistoryError::storage)?;
        let pending_id = self
            .state
            .pending_delete
            .as_ref()
            .map(|pending| pending.id.as_str());
        let conversations: Vec<Conversation> = conversations
            .into_iter()
            .filter(|conversation| Some(conversation.id.as_str()) != pending_id)
            .collect();
        let grew = conversations.len() > current;

        self.state.conversations = conversations;
        self.state.groups = group_conversations(&self.state.conversations, Timestamp::now());

        Ok(grew)
    }

    /// Create a new chat, persist it, and make it active.
    pub async fn new_chat(&mut self) -> Result<Conversation, HistoryError> {
        let conversation = self.factory.create(None).await?;
        self.set_last_active(Some(&conversation.id)).await?;

        if self.state.archived {
            // A new chat is always active, so leave the archived view.
            self.load(false).await?;
        } else {
            self.state
                .conversations
                .retain(|existing| existing.id != conversation.id);
            self.state.conversations.push(conversation.clone());
            self.state.groups = group_conversations(&self.state.conversations, Timestamp::now());
        }

        self.state.active_conversation_id = Some(conversation.id.clone());
        self.state.status = HistoryStatus::Ready;
        self.state.error = None;

        Ok(conversation)
    }

    /// Select a conversation and persist it as the last-active conversation.
    pub async fn select(&mut self, conversation_id: &str) -> Result<(), HistoryError> {
        self.set_last_active(Some(conversation_id)).await?;
        self.state.active_conversation_id = Some(conversation_id.to_owned());
        Ok(())
    }

    /// Rename a conversation. Blank titles are rejected.
    pub async fn rename(
        &mut self,
        conversation_id: &str,
        title: &str,
    ) -> Result<Conversation, HistoryError> {
        let trimmed = title.trim();

        if trimmed.is_empty() {
            return Err(HistoryError::InvalidInput(
                "Conversation title must not be empty.".to_owned(),
            ));
        }

        self.repository
            .rename(conversation_id, trimmed)
            .await
            .map_err(HistoryError::storage)?;
        let updated = self.require(conversation_id).await?;
        self.replace_in_state(updated.clone());

        Ok(updated)
    }

    /// Pin or unpin a conversation.
    pub async fn set_pinned(
        &mut self,
        conversation_id: &str,
        pinned: bool,
    ) -> Result<Conversation, HistoryError> {
        self.repository
            .set_pinned(conversation_id, pinned)
            .await
            .map_err(HistoryError::storage)?;
        let updated = self.require(conversation_id).await?;
        self.replace_in_state(updated.clone());

        Ok(updated)
    }

    /// Archive or unarchive a conversation.
    ///
    /// Archiving the active conversation clears the last-active pointer so a
    /// restart cannot restore a hidden conversation.
    pub async fn set_archived(
        &mut self,
        conversation_id: &str,
        archived: bool,
    ) -> Result<Conversation, HistoryError> {
        self.repository
            .set_archived(conversation_id, archived)
            .await
            .map_err(HistoryError::storage)?;
        let updated = self.require(conversation_id).await?;

        if archived != self.state.archived {
            self.state
                .conversations
                .retain(|existing| existing.id != conversation_id);
            self.state.groups = group_conversations(&self.state.conversations, Timestamp::now());

            if self.state.active_conversation_id.as_deref() == Some(conversation_id) {
                self.state.active_conversation_id = None;
                self.set_last_active(None).await?;
            }
        } else {
            self.replace_in_state(updated.clone());
        }

        Ok(updated)
    }

    /// Buffer a delete for the undo window.
    ///
    /// Any previous pending delete is committed first. The conversation is
    /// removed from the sidebar immediately but remains in storage until
    /// [`ConversationHistory::commit_delete`] or
    /// [`ConversationHistory::commit_pending`] runs.
    pub async fn request_delete(
        &mut self,
        conversation_id: &str,
        now: Instant,
    ) -> Result<(), HistoryError> {
        self.commit_pending().await?;

        let conversation = match self
            .state
            .conversations
            .iter()
            .find(|conversation| conversation.id == conversation_id)
            .cloned()
        {
            Some(conversation) => conversation,
            None => self.require(conversation_id).await?,
        };
        let was_active = self.state.active_conversation_id.as_deref() == Some(conversation_id);

        self.state
            .conversations
            .retain(|existing| existing.id != conversation_id);
        self.state.groups = group_conversations(&self.state.conversations, Timestamp::now());
        self.state.pending_delete = Some(conversation.clone());
        self.pending = Some(PendingDelete {
            conversation,
            deadline: now + self.config.undo_window,
            was_active,
        });

        if was_active {
            self.state.active_conversation_id = None;
            self.set_last_active(None).await?;
        }

        Ok(())
    }

    /// Cancel the buffered delete and restore the conversation to the sidebar.
    ///
    /// Storage was never touched, so messages and attachment references are
    /// still present.
    pub async fn undo_delete(&mut self) -> Result<(), HistoryError> {
        let Some(pending) = self.pending.take() else {
            return Ok(());
        };

        self.state.pending_delete = None;

        if pending.conversation.archived == self.state.archived {
            self.state
                .conversations
                .retain(|existing| existing.id != pending.conversation.id);
            self.state.conversations.push(pending.conversation.clone());
            self.state.groups = group_conversations(&self.state.conversations, Timestamp::now());
        }

        if pending.was_active {
            self.state.active_conversation_id = Some(pending.conversation.id.clone());
            self.set_last_active(Some(&pending.conversation.id)).await?;
        }

        Ok(())
    }

    /// Commit the buffered delete when the undo window has expired.
    ///
    /// Returns `true` when a delete was committed.
    pub async fn commit_delete(&mut self, now: Instant) -> Result<bool, HistoryError> {
        let Some(pending) = &self.pending else {
            return Ok(false);
        };

        if now < pending.deadline {
            return Ok(false);
        }

        self.commit_pending().await?;
        Ok(true)
    }

    /// Commit the buffered delete immediately.
    pub async fn commit_pending(&mut self) -> Result<(), HistoryError> {
        let Some(pending) = self.pending.take() else {
            return Ok(());
        };

        match self.repository.delete(&pending.conversation.id).await {
            Ok(_) => {
                self.state.pending_delete = None;
                Ok(())
            }
            Err(error) => {
                // Keep the buffered delete so the user can retry or undo.
                self.state.pending_delete = Some(pending.conversation.clone());
                self.pending = Some(pending);
                Err(HistoryError::storage(error))
            }
        }
    }

    /// Restore the last-active conversation from settings.
    ///
    /// A missing or archived conversation clears the pointer and returns
    /// `None`.
    pub async fn restore_last_active(&mut self) -> Result<Option<Conversation>, HistoryError> {
        let settings = self.settings.load().await.map_err(HistoryError::storage)?;
        let Some(conversation_id) = settings.last_active_conversation_id else {
            self.state.active_conversation_id = None;
            return Ok(None);
        };
        let conversation = self
            .repository
            .get(&conversation_id)
            .await
            .map_err(HistoryError::storage)?;

        match conversation {
            Some(conversation) if !conversation.archived => {
                self.state.active_conversation_id = Some(conversation_id);
                Ok(Some(conversation))
            }
            _ => {
                self.set_last_active(None).await?;
                self.state.active_conversation_id = None;
                Ok(None)
            }
        }
    }

    async fn fetch(&self, archived: bool) -> Result<Vec<Conversation>, HistoryError> {
        let filter = ConversationFilter {
            pinned: None,
            archived: Some(archived),
            limit: Some(self.config.page_size),
        };

        self.repository
            .list(&filter)
            .await
            .map_err(HistoryError::storage)
    }

    async fn require(&self, conversation_id: &str) -> Result<Conversation, HistoryError> {
        self.repository
            .get(conversation_id)
            .await
            .map_err(HistoryError::storage)?
            .ok_or_else(|| HistoryError::NotFound(conversation_id.to_owned()))
    }

    async fn set_last_active(&self, conversation_id: Option<&str>) -> Result<(), HistoryError> {
        if let Some(conversation_id) = conversation_id {
            self.require(conversation_id).await?;
        }

        let mut settings = self.settings.load().await.map_err(HistoryError::storage)?;
        settings.last_active_conversation_id = conversation_id.map(str::to_owned);
        self.settings
            .save(&settings)
            .await
            .map_err(HistoryError::storage)
    }

    fn replace_in_state(&mut self, conversation: Conversation) {
        if let Some(existing) = self
            .state
            .conversations
            .iter_mut()
            .find(|existing| existing.id == conversation.id)
        {
            *existing = conversation;
        } else if conversation.archived == self.state.archived {
            self.state.conversations.push(conversation);
        }

        self.state.groups = group_conversations(&self.state.conversations, Timestamp::now());
    }
}
