//! Startup recovery for interrupted streams and orphaned attachment files.
//!
//! # Ordering
//!
//! Call [`RecoveryService::recover`] once during startup, before the UI shell
//! renders and before any new send or import starts. The pass:
//!
//! 1. selects every non-deleted message with `status = 'streaming'`;
//! 2. rewrites each as `cancelled` with a `cancelled` finish reason and an
//!    interruption note, preserving `parts`, `usage`, and every other field;
//! 3. collects the storage keys referenced by attachment metadata and removes
//!    attachment files that no metadata row references.
//!
//! Running the pass again is safe: the second pass finds no streaming messages
//! and removes no referenced files.
//!
//! # One-second persistence trade-off
//!
//! Streaming text is persisted at most once per second (spec section 10.9), so
//! a process kill can lose up to about one second of the newest tokens. Recovery
//! preserves exactly the content that reached the database; it never invents or
//! truncates partial text. The interruption note explains the gap to the user.

use std::sync::Arc;

use chatui_domain::{ChatError, ChatErrorCode, FinishReason, Message, MessageStatus, Timestamp};

use crate::StorageError;
use crate::attachments::AttachmentStore;
use crate::repositories::{AttachmentRepository, MessageRepository};

/// Explanation stored on a message that was left streaming when the app closed.
pub const INTERRUPTION_NOTE: &str =
    "The app closed before this response finished. Partial content is preserved.";

/// Clock used to stamp recovered messages.
type Clock = Arc<dyn Fn() -> Timestamp + Send + Sync>;

/// A message that could not be recovered, with the failure that stopped it.
#[derive(Debug)]
pub struct RecoveryFailure {
    /// The message as it was before recovery.
    pub message: Message,
    /// The storage error that prevented the update.
    pub error: StorageError,
}

/// Outcome of one startup recovery pass.
#[derive(Debug, Default)]
pub struct RecoverySummary {
    /// Messages rewritten from `streaming` to `cancelled`.
    pub recovered: Vec<Message>,
    /// Messages that could not be rewritten.
    pub failed: Vec<RecoveryFailure>,
    /// Orphan attachment file names removed after recovery.
    pub removed_orphans: Vec<String>,
}

/// Marks interrupted streams cancelled and removes orphan attachment files.
#[derive(Clone)]
pub struct RecoveryService {
    messages: Arc<dyn MessageRepository>,
    attachments: Arc<dyn AttachmentRepository>,
    store: AttachmentStore,
    now: Clock,
}

impl RecoveryService {
    /// Create a recovery service using the system clock.
    pub fn new(
        messages: Arc<dyn MessageRepository>,
        attachments: Arc<dyn AttachmentRepository>,
        store: AttachmentStore,
    ) -> Self {
        Self {
            messages,
            attachments,
            store,
            now: Arc::new(Timestamp::now),
        }
    }

    /// Replace the clock. Tests use this to make `updatedAt` deterministic.
    pub fn with_clock(mut self, now: impl Fn() -> Timestamp + Send + Sync + 'static) -> Self {
        self.now = Arc::new(now);
        self
    }

    /// Run one recovery pass.
    ///
    /// Per-message write failures are collected in [`RecoverySummary::failed`]
    /// so one bad row does not block the rest of the scan. Orphan cleanup runs
    /// after every streaming message has been handled.
    pub async fn recover(&self) -> Result<RecoverySummary, StorageError> {
        let streaming = self
            .messages
            .list_by_status(MessageStatus::Streaming)
            .await?;
        let mut summary = RecoverySummary::default();

        for message in streaming {
            let at = (self.now)();
            let updated = Message {
                status: MessageStatus::Cancelled,
                finish_reason: Some(FinishReason::Cancelled),
                error: Some(interruption_error(&message, at)),
                updated_at: at,
                ..message.clone()
            };

            match self.messages.update(&updated).await {
                Ok(()) => summary.recovered.push(updated),
                Err(error) => summary.failed.push(RecoveryFailure { message, error }),
            }
        }

        summary.removed_orphans = self
            .store
            .cleanup_orphans_for_repository(self.attachments.as_ref())
            .await?;

        Ok(summary)
    }
}

fn interruption_error(message: &Message, at: Timestamp) -> ChatError {
    ChatError {
        code: ChatErrorCode::Cancelled,
        message: INTERRUPTION_NOTE.to_owned(),
        provider_id: message.provider_id.clone(),
        status: None,
        retryable: false,
        retry_after_seconds: None,
        request_id: None,
        details: None,
        at,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use chatui_domain::{
        Attachment, AttachmentKind, ChatParams, ContentPart, Conversation, MessageRole, Usage,
    };
    use sqlx::SqlitePool;

    use crate::attachments::AttachmentService;
    use crate::db::open_database;
    use crate::repositories::{
        ConversationRepository, SqliteAttachmentRepository, SqliteConversationRepository,
        SqliteMessageRepository,
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

    fn streaming_message(id: &str, conversation_id: &str, sequence: u64) -> Message {
        Message {
            id: id.to_owned(),
            conversation_id: conversation_id.to_owned(),
            sequence,
            role: MessageRole::Assistant,
            parts: vec![
                ContentPart::Text {
                    text: "partial answer".to_owned(),
                },
                ContentPart::Reasoning {
                    text: "partial reasoning".to_owned(),
                },
            ],
            status: MessageStatus::Streaming,
            provider_id: Some("provider-1".to_owned()),
            model_id: Some("model-1".to_owned()),
            model_label: Some("Model One".to_owned()),
            usage: Some(Usage {
                input_tokens: Some(10),
                output_tokens: Some(5),
                total_tokens: Some(15),
                cost_usd: Some(0.001),
                estimated: false,
            }),
            finish_reason: None,
            error: None,
            latency_ms: Some(100),
            first_token_ms: Some(20),
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

    async fn setup() -> (
        tempfile::TempDir,
        SqlitePool,
        Arc<dyn MessageRepository>,
        Arc<dyn AttachmentRepository>,
        AttachmentStore,
    ) {
        let directory = tempfile::tempdir().expect("create temporary directory");
        let pool = open_database(directory.path().join("chatui.sqlite3"))
            .await
            .expect("open temporary database");

        let conversations = SqliteConversationRepository::new(pool.clone());
        conversations
            .create(&conversation("conversation-1"))
            .await
            .expect("create conversation");

        let messages: Arc<dyn MessageRepository> =
            Arc::new(SqliteMessageRepository::new(pool.clone()));
        let attachments: Arc<dyn AttachmentRepository> =
            Arc::new(SqliteAttachmentRepository::new(pool.clone()));
        let store = AttachmentStore::new(directory.path());

        (directory, pool, messages, attachments, store)
    }

    #[tokio::test]
    async fn recovery_marks_streaming_cancelled_and_preserves_partial_content() {
        let (_directory, pool, messages, attachments, store) = setup().await;
        let original = streaming_message("message-1", "conversation-1", 0);
        messages.insert(&original).await.expect("insert message");

        let service = RecoveryService::new(messages.clone(), attachments.clone(), store.clone())
            .with_clock(|| timestamp(5));
        let summary = service.recover().await.expect("recover");

        assert_eq!(summary.recovered.len(), 1);
        assert!(summary.failed.is_empty());

        let recovered = &summary.recovered[0];
        assert_eq!(recovered.status, MessageStatus::Cancelled);
        assert_eq!(recovered.finish_reason, Some(FinishReason::Cancelled));
        assert_eq!(recovered.parts, original.parts);
        assert_eq!(recovered.usage, original.usage);
        assert_eq!(recovered.updated_at, timestamp(5));

        let error = recovered.error.as_ref().expect("interruption error");
        assert_eq!(error.code, ChatErrorCode::Cancelled);
        assert_eq!(error.message, INTERRUPTION_NOTE);
        assert_eq!(error.provider_id.as_deref(), Some("provider-1"));
        assert!(!error.retryable);

        let loaded = messages
            .get("message-1")
            .await
            .expect("get message")
            .expect("message exists");
        assert_eq!(loaded.status, MessageStatus::Cancelled);
        assert_eq!(loaded.parts, original.parts);
        assert_eq!(loaded.usage, original.usage);

        pool.close().await;
    }

    #[tokio::test]
    async fn recovery_is_idempotent() {
        let (_directory, pool, messages, attachments, store) = setup().await;
        messages
            .insert(&streaming_message("message-1", "conversation-1", 0))
            .await
            .expect("insert message");

        let service = RecoveryService::new(messages.clone(), attachments.clone(), store.clone())
            .with_clock(|| timestamp(5));

        let first = service.recover().await.expect("first recovery");
        assert_eq!(first.recovered.len(), 1);

        let second = service.recover().await.expect("second recovery");
        assert!(second.recovered.is_empty());
        assert!(second.failed.is_empty());
        assert!(second.removed_orphans.is_empty());

        pool.close().await;
    }

    #[tokio::test]
    async fn recovery_cleans_orphan_attachments_after_recovery() {
        let (_directory, pool, messages, attachments, store) = setup().await;
        messages
            .insert(&streaming_message("message-1", "conversation-1", 0))
            .await
            .expect("insert message");

        let service = AttachmentService::new(store.clone(), attachments.clone());
        let kept = service
            .add(attachment("attachment-1", "message-1"), b"keep")
            .await
            .expect("add attachment");
        let orphan = store.put(b"orphan").expect("store orphan");

        let recovery = RecoveryService::new(messages.clone(), attachments.clone(), store.clone())
            .with_clock(|| timestamp(5));
        let summary = recovery.recover().await.expect("recover");

        assert_eq!(summary.removed_orphans, vec![orphan.clone()]);
        assert!(store.exists(&kept.storage_key).expect("exists kept"));
        assert!(!store.exists(&orphan).expect("exists orphan"));

        pool.close().await;
    }

    #[tokio::test]
    async fn recovery_leaves_complete_messages_alone() {
        let (_directory, pool, messages, attachments, store) = setup().await;
        let mut complete = streaming_message("message-1", "conversation-1", 0);
        complete.status = MessageStatus::Complete;
        complete.finish_reason = Some(FinishReason::Stop);
        messages.insert(&complete).await.expect("insert message");

        let service = RecoveryService::new(messages.clone(), attachments.clone(), store.clone())
            .with_clock(|| timestamp(5));
        let summary = service.recover().await.expect("recover");

        assert!(summary.recovered.is_empty());
        assert!(summary.failed.is_empty());

        let loaded = messages
            .get("message-1")
            .await
            .expect("get message")
            .expect("message exists");
        assert_eq!(loaded.status, MessageStatus::Complete);
        assert_eq!(loaded.finish_reason, Some(FinishReason::Stop));

        pool.close().await;
    }
}
