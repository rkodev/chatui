//! Conversation history, search, grouping, and navigation.
//!
//! The module is framework-independent. It provides:
//!
//! * [`grouping`] for the pinned and date buckets in the sidebar;
//! * [`search`] for bounded FTS search with snippets;
//! * [`history`] for the sidebar view model, actions, delete undo, drafts, and
//!   last-active restore;
//! * [`factory`] for new-chat creation from the persisted defaults;
//! * [`route`] for the conversation part of the shell navigation state.
//!
//! The Slint shell binds to [`HistoryState`] and [`ConversationNavigation`] and
//! calls the async methods on [`ConversationHistory`] and
//! [`ConversationSearchService`] from its event handlers.

pub mod factory;
pub mod grouping;
pub mod history;
pub mod route;
pub mod search;

#[cfg(test)]
mod tests;

pub use factory::{ConversationFactory, DefaultConversationFactory};
pub use grouping::{
    HISTORY_GROUP_ORDER, HistoryGroup, HistoryGroupKey, format_conversation_timestamp,
    group_conversations, history_group_for_date,
};
pub use history::{
    ConversationHistory, DEFAULT_CONVERSATION_TITLE, DEFAULT_HISTORY_PAGE_SIZE,
    DEFAULT_UNDO_WINDOW, HistoryConfig, HistoryError, HistoryState, HistoryStatus,
    MAX_HISTORY_ROWS,
};
pub use route::{ConversationNavigation, ConversationRoute};
pub use search::{
    ConversationSearchError, ConversationSearchHit, ConversationSearchResult,
    ConversationSearchService, ConversationSearchState, DEFAULT_SEARCH_LIMIT, MAX_SEARCH_LIMIT,
    MAX_SNIPPET_CHARS, search_error,
};
