//! FTS5 search over message text.
//!
//! The `messages_fts` virtual table is kept in sync by the message and
//! conversation repositories. Only [`ContentPart::Text`] parts are indexed;
//! reasoning, tool, and attachment parts are not searchable.
//!
//! User input is tokenized into alphanumeric words and each word is quoted
//! before being joined with `AND`. This prevents FTS5 syntax errors from
//! punctuation, quotes, or operators in the search box.

use async_trait::async_trait;
use chatui_domain::{ContentPart, Message};
use sqlx::SqlitePool;
use sqlx::sqlite::SqliteConnection;

use crate::StorageError;

/// Options for a bounded conversation search.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct SearchOptions {
    /// Restrict results to one conversation.
    pub conversation_id: Option<String>,
    /// Maximum number of results. Defaults to 20 and is capped at 100.
    pub limit: Option<u32>,
}

/// One search result.
#[derive(Debug, Clone, PartialEq)]
pub struct SearchHit {
    /// Conversation that owns the matching message.
    pub conversation_id: String,
    /// Matching message ID.
    pub message_id: String,
    /// Plain-text snippet around the match.
    pub snippet: String,
    /// FTS5 rank; lower values are better.
    pub rank: f64,
    /// Message sequence, used as a deterministic tie-breaker.
    pub sequence: u64,
}

/// Search repository abstraction.
#[async_trait]
pub trait SearchRepository: Send + Sync {
    /// Search message text and return at most the requested number of hits.
    async fn search(
        &self,
        query: &str,
        options: &SearchOptions,
    ) -> Result<Vec<SearchHit>, StorageError>;
}

/// SQLite-backed search repository.
#[derive(Debug, Clone)]
pub struct SqliteSearchRepository {
    pool: SqlitePool,
}

impl SqliteSearchRepository {
    /// Create a repository over an existing pool.
    pub fn new(pool: SqlitePool) -> Self {
        Self { pool }
    }

    /// Borrow the underlying pool.
    pub fn pool(&self) -> &SqlitePool {
        &self.pool
    }
}

#[async_trait]
impl SearchRepository for SqliteSearchRepository {
    async fn search(
        &self,
        query: &str,
        options: &SearchOptions,
    ) -> Result<Vec<SearchHit>, StorageError> {
        let Some(fts_query) = build_fts_query(query) else {
            return Ok(Vec::new());
        };

        let limit = options.limit.unwrap_or(20).clamp(1, 100) as i64;
        let conversation_id = options.conversation_id.as_deref();

        let rows = sqlx::query_as::<_, SearchRow>(
            "SELECT messages_fts.conversation_id AS conversation_id, \
                    messages_fts.message_id AS message_id, \
                    snippet(messages_fts, 2, '', '', '...', 12) AS snippet, \
                    messages_fts.rank AS rank, \
                    m.sequence AS sequence \
             FROM messages_fts \
             JOIN messages AS m ON m.id = messages_fts.message_id \
             WHERE messages_fts MATCH ? \
               AND (? IS NULL OR messages_fts.conversation_id = ?) \
             ORDER BY messages_fts.rank, m.sequence ASC \
             LIMIT ?",
        )
        .bind(&fts_query)
        .bind(conversation_id)
        .bind(conversation_id)
        .bind(limit)
        .fetch_all(&self.pool)
        .await?;

        rows.into_iter()
            .map(|row| {
                Ok(SearchHit {
                    conversation_id: row.conversation_id,
                    message_id: row.message_id,
                    snippet: row.snippet,
                    rank: row.rank,
                    sequence: u64::try_from(row.sequence).map_err(|_| {
                        StorageError::Mapping("search result sequence is negative".to_owned())
                    })?,
                })
            })
            .collect()
    }
}

#[derive(Debug, Clone, sqlx::FromRow)]
struct SearchRow {
    conversation_id: String,
    message_id: String,
    snippet: String,
    rank: f64,
    sequence: i64,
}

/// Extract the searchable text from a message's content parts.
pub fn extract_search_text(parts: &[ContentPart]) -> String {
    parts
        .iter()
        .filter_map(|part| match part {
            ContentPart::Text { text } => Some(text.as_str()),
            _ => None,
        })
        .collect::<Vec<_>>()
        .join("\n")
}

/// Insert the FTS row for a newly created message inside an existing
/// transaction.
pub(crate) async fn insert_fts_row(
    connection: &mut SqliteConnection,
    message: &Message,
) -> Result<(), StorageError> {
    let content = extract_search_text(&message.parts);
    if content.is_empty() {
        return Ok(());
    }

    sqlx::query("INSERT INTO messages_fts (message_id, conversation_id, content) VALUES (?, ?, ?)")
        .bind(&message.id)
        .bind(&message.conversation_id)
        .bind(content)
        .execute(&mut *connection)
        .await?;

    Ok(())
}

/// Replace the FTS row for a message inside an existing transaction.
pub(crate) async fn replace_fts_row(
    connection: &mut SqliteConnection,
    message: &Message,
) -> Result<(), StorageError> {
    sqlx::query("DELETE FROM messages_fts WHERE message_id = ?")
        .bind(&message.id)
        .execute(&mut *connection)
        .await?;

    insert_fts_row(connection, message).await
}

/// Remove the FTS row for one message inside an existing transaction.
pub(crate) async fn delete_fts_for_message(
    connection: &mut SqliteConnection,
    message_id: &str,
) -> Result<(), StorageError> {
    sqlx::query("DELETE FROM messages_fts WHERE message_id = ?")
        .bind(message_id)
        .execute(&mut *connection)
        .await?;
    Ok(())
}

/// Remove every FTS row for a conversation inside an existing transaction.
pub(crate) async fn delete_fts_for_conversation(
    connection: &mut SqliteConnection,
    conversation_id: &str,
) -> Result<(), StorageError> {
    sqlx::query("DELETE FROM messages_fts WHERE conversation_id = ?")
        .bind(conversation_id)
        .execute(&mut *connection)
        .await?;
    Ok(())
}

fn build_fts_query(input: &str) -> Option<String> {
    let tokens: Vec<String> = input
        .split(|character: char| !(character.is_alphanumeric() || character == '_'))
        .filter(|token| !token.is_empty())
        .map(|token| format!("\"{}\"", token.replace('"', "\"\"")))
        .collect();

    if tokens.is_empty() {
        None
    } else {
        Some(tokens.join(" AND "))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn extracts_only_text_parts() {
        let parts = vec![
            ContentPart::Text {
                text: "hello".to_owned(),
            },
            ContentPart::Reasoning {
                text: "hidden".to_owned(),
            },
            ContentPart::Text {
                text: "world".to_owned(),
            },
        ];

        assert_eq!(extract_search_text(&parts), "hello\nworld");
    }

    #[test]
    fn builds_quoted_and_query() {
        assert_eq!(
            build_fts_query("hello, world!"),
            Some("\"hello\" AND \"world\"".to_owned())
        );
        assert_eq!(build_fts_query("   "), None);
        assert_eq!(
            build_fts_query("say \"hi\""),
            Some("\"say\" AND \"hi\"".to_owned())
        );
    }
}
