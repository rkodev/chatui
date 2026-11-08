//! Bounded conversation search with snippets.
//!
//! Search runs through the storage FTS repository. The service never loads the
//! whole message history: it asks for at most [`MAX_SEARCH_LIMIT`] hits and
//! resolves only the conversation titles needed for the returned rows.
//!
//! Snippets are plain text around the match. They are whitespace-normalized and
//! bounded to [`MAX_SNIPPET_CHARS`] characters before they reach the UI. Search
//! failures use a generic message so message content never appears in an error.

use std::collections::BTreeMap;
use std::fmt;
use std::sync::Arc;

use chatui_storage::{ConversationRepository, SearchOptions, SearchRepository, StorageError};

/// Default number of search hits returned to the sidebar.
pub const DEFAULT_SEARCH_LIMIT: u32 = 20;
/// Hard cap on search hits, matching the storage repository cap.
pub const MAX_SEARCH_LIMIT: u32 = 100;
/// Maximum characters in a displayed snippet.
pub const MAX_SNIPPET_CHARS: usize = 160;

/// One search hit enriched with the owning conversation title.
#[derive(Debug, Clone, PartialEq)]
pub struct ConversationSearchHit {
    /// Conversation that owns the matching message.
    pub conversation_id: String,
    /// Conversation title for the result row.
    pub conversation_title: String,
    /// Matching message ID.
    pub message_id: String,
    /// Bounded plain-text snippet around the match.
    pub snippet: String,
    /// FTS rank; lower values are better.
    pub rank: f64,
    /// Message sequence, used as a deterministic tie-breaker.
    pub sequence: u64,
}

/// A bounded search response.
#[derive(Debug, Clone, PartialEq)]
pub struct ConversationSearchResult {
    /// Trimmed query that produced the result.
    pub query: String,
    /// Matching rows in rank order.
    pub hits: Vec<ConversationSearchHit>,
    /// Whether more hits exist beyond the requested limit.
    pub truncated: bool,
}

impl ConversationSearchResult {
    /// True when the query matched nothing.
    pub fn is_empty(&self) -> bool {
        self.hits.is_empty()
    }
}

/// UI state for the search box.
#[derive(Debug, Clone, PartialEq)]
pub enum ConversationSearchState {
    /// The query is empty; no request has been made.
    Idle,
    /// A request is in flight for the query.
    Loading {
        /// Trimmed query.
        query: String,
    },
    /// Results are available.
    Ready(ConversationSearchResult),
    /// The query completed with no matches.
    Empty {
        /// Trimmed query.
        query: String,
    },
    /// The request failed.
    Error {
        /// Trimmed query.
        query: String,
        /// Generic, content-free error message.
        message: String,
    },
}

impl ConversationSearchState {
    /// Build a loading state for a query.
    pub fn loading(query: &str) -> Self {
        Self::Loading {
            query: query.trim().to_owned(),
        }
    }

    /// Map a service result to the UI state.
    pub fn from_result(
        result: Result<ConversationSearchResult, ConversationSearchError>,
        query: &str,
    ) -> Self {
        let trimmed = query.trim();

        match result {
            Ok(result) if result.hits.is_empty() => Self::Empty {
                query: trimmed.to_owned(),
            },
            Ok(result) => Self::Ready(result),
            Err(error) => Self::Error {
                query: trimmed.to_owned(),
                message: error.message,
            },
        }
    }
}

/// A content-free search failure safe to show in the UI.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ConversationSearchError {
    /// Generic user-facing message.
    pub message: String,
}

impl ConversationSearchError {
    /// Build the standard search failure.
    pub fn new() -> Self {
        Self {
            message: "Search is unavailable right now. Try again.".to_owned(),
        }
    }
}

impl Default for ConversationSearchError {
    fn default() -> Self {
        Self::new()
    }
}

impl fmt::Display for ConversationSearchError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter.write_str(&self.message)
    }
}

impl std::error::Error for ConversationSearchError {}

/// Bounded search over the FTS repository.
pub struct ConversationSearchService {
    search: Arc<dyn SearchRepository>,
    conversations: Arc<dyn ConversationRepository>,
    limit: u32,
}

impl ConversationSearchService {
    /// Create a service with the default result limit.
    pub fn new(
        search: Arc<dyn SearchRepository>,
        conversations: Arc<dyn ConversationRepository>,
    ) -> Self {
        Self {
            search,
            conversations,
            limit: DEFAULT_SEARCH_LIMIT,
        }
    }

    /// Create a service with an explicit result limit.
    ///
    /// The limit is clamped to `1..=`[`MAX_SEARCH_LIMIT`].
    pub fn with_limit(
        search: Arc<dyn SearchRepository>,
        conversations: Arc<dyn ConversationRepository>,
        limit: u32,
    ) -> Self {
        Self {
            search,
            conversations,
            limit: limit.clamp(1, MAX_SEARCH_LIMIT),
        }
    }

    /// Configured result limit.
    pub fn limit(&self) -> u32 {
        self.limit
    }

    /// Search message text and return at most the configured number of hits.
    ///
    /// An empty or whitespace-only query returns an empty result without
    /// touching storage.
    pub async fn search(
        &self,
        query: &str,
    ) -> Result<ConversationSearchResult, ConversationSearchError> {
        let trimmed = query.trim();

        if trimmed.is_empty() {
            return Ok(ConversationSearchResult {
                query: String::new(),
                hits: Vec::new(),
                truncated: false,
            });
        }

        let request_limit = (self.limit + 1).min(MAX_SEARCH_LIMIT);
        let options = SearchOptions {
            conversation_id: None,
            limit: Some(request_limit),
        };
        let hits = self
            .search
            .search(trimmed, &options)
            .await
            .map_err(|_| ConversationSearchError::new())?;
        let truncated = hits.len() > self.limit as usize;
        let mut titles: BTreeMap<String, String> = BTreeMap::new();
        let mut results = Vec::new();

        for hit in hits.into_iter().take(self.limit as usize) {
            let title = match titles.get(&hit.conversation_id) {
                Some(title) => title.clone(),
                None => {
                    let title = self
                        .conversations
                        .get(&hit.conversation_id)
                        .await
                        .map_err(|_| ConversationSearchError::new())?
                        .map(|conversation| conversation.title)
                        .unwrap_or_else(|| "Untitled conversation".to_owned());
                    titles.insert(hit.conversation_id.clone(), title.clone());
                    title
                }
            };

            results.push(ConversationSearchHit {
                conversation_id: hit.conversation_id,
                conversation_title: title,
                message_id: hit.message_id,
                snippet: bound_snippet(&hit.snippet),
                rank: hit.rank,
                sequence: hit.sequence,
            });
        }

        Ok(ConversationSearchResult {
            query: trimmed.to_owned(),
            hits: results,
            truncated,
        })
    }
}

fn bound_snippet(snippet: &str) -> String {
    let normalized = snippet.split_whitespace().collect::<Vec<_>>().join(" ");
    let mut characters = normalized.chars();
    let bounded: String = characters.by_ref().take(MAX_SNIPPET_CHARS).collect();

    if characters.next().is_some() {
        format!("{bounded}...")
    } else {
        bounded
    }
}

/// Map a storage failure to the content-free search error.
///
/// Kept for callers that need to translate a repository error without exposing
/// its text.
pub fn search_error(_error: StorageError) -> ConversationSearchError {
    ConversationSearchError::new()
}
