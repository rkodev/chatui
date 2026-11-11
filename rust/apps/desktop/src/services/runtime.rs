//! Shared runtime, database bootstrap, and repository construction.
//!
//! [`ServiceRuntime`] is created once during desktop startup. It owns the
//! multi-thread `tokio` runtime, the SQLite pool, every shared repository, and
//! the attachment store. Startup runs migrations, verifies pragmas and FTS5,
//! and performs the recovery pass before any UI service is constructed.

use std::fmt;
use std::future::Future;
use std::path::{Path, PathBuf};
use std::sync::Arc;

use chatui_storage::{
    AttachmentRepository, AttachmentStore, ConversationRepository, MessageRepository,
    ModelRepository, ProviderRepository, RecoveryService, RecoverySummary, SecretRepository,
    SettingsRepository, SqliteAttachmentRepository, SqliteConversationRepository,
    SqliteMessageRepository, SqliteModelRepository, SqliteProviderRepository,
    SqliteSecretRepository, SqliteSettingsRepository, StorageError, open_database,
    open_default_database, run_migrations, verify_fts5, verify_pragmas,
};
use sqlx::SqlitePool;
use tokio::runtime::Runtime;

use crate::services::sync::{SyncBridge, SyncSettingsStore};

/// Failure while creating the desktop service runtime.
#[derive(Debug)]
pub enum ServiceRuntimeError {
    /// The async runtime could not be created.
    Runtime(std::io::Error),
    /// The database could not be opened, migrated, verified, or recovered.
    Storage(StorageError),
}

impl fmt::Display for ServiceRuntimeError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::Runtime(error) => {
                write!(formatter, "could not create the async runtime: {error}")
            }
            Self::Storage(error) => write!(formatter, "could not prepare the database: {error}"),
        }
    }
}

impl std::error::Error for ServiceRuntimeError {
    fn source(&self) -> Option<&(dyn std::error::Error + 'static)> {
        match self {
            Self::Runtime(error) => Some(error),
            Self::Storage(error) => Some(error),
        }
    }
}

impl From<StorageError> for ServiceRuntimeError {
    fn from(error: StorageError) -> Self {
        Self::Storage(error)
    }
}

/// The desktop's single async runtime, database pool, and shared repositories.
pub struct ServiceRuntime {
    runtime: Arc<Runtime>,
    pool: SqlitePool,
    providers: Arc<SqliteProviderRepository>,
    models: Arc<SqliteModelRepository>,
    conversations: Arc<SqliteConversationRepository>,
    messages: Arc<SqliteMessageRepository>,
    settings: Arc<SqliteSettingsRepository>,
    secrets: Arc<SqliteSecretRepository>,
    attachment_repository: Arc<SqliteAttachmentRepository>,
    attachments: AttachmentStore,
    recovery: RecoverySummary,
}

impl ServiceRuntime {
    /// Open the default database under the platform app-data directory.
    ///
    /// This is the production entry point. It opens the database, runs
    /// migrations, verifies pragmas and FTS5, runs startup recovery, and builds
    /// every shared repository.
    pub fn open() -> Result<Self, ServiceRuntimeError> {
        let runtime = Arc::new(build_runtime()?);
        let pool = runtime.block_on(open_default_database())?;
        let attachments = AttachmentStore::with_app_data_dir()?;
        Self::bootstrap(runtime, pool, attachments)
    }

    /// Open an explicit database path and app-data directory.
    ///
    /// Tests use this with a temporary directory. Production callers should use
    /// [`ServiceRuntime::open`].
    pub fn open_at(
        database_path: impl AsRef<Path>,
        app_data_dir: impl Into<PathBuf>,
    ) -> Result<Self, ServiceRuntimeError> {
        let runtime = Arc::new(build_runtime()?);
        let pool = runtime.block_on(open_database(database_path))?;
        Self::bootstrap(runtime, pool, AttachmentStore::new(app_data_dir))
    }

    fn bootstrap(
        runtime: Arc<Runtime>,
        pool: SqlitePool,
        attachments: AttachmentStore,
    ) -> Result<Self, ServiceRuntimeError> {
        runtime.block_on(async {
            run_migrations(&pool).await?;
            verify_pragmas(&pool).await?;
            verify_fts5(&pool).await?;
            Ok::<(), StorageError>(())
        })?;

        let providers = Arc::new(SqliteProviderRepository::new(pool.clone()));
        let models = Arc::new(SqliteModelRepository::new(pool.clone()));
        let conversations = Arc::new(SqliteConversationRepository::new(pool.clone()));
        let messages = Arc::new(SqliteMessageRepository::new(pool.clone()));
        let settings = Arc::new(SqliteSettingsRepository::new(pool.clone()));
        let secrets = Arc::new(SqliteSecretRepository::new(pool.clone()));
        let attachment_repository = Arc::new(SqliteAttachmentRepository::new(pool.clone()));

        let messages_trait: Arc<dyn MessageRepository> = messages.clone();
        let attachments_trait: Arc<dyn AttachmentRepository> = attachment_repository.clone();
        let recovery = runtime.block_on(
            RecoveryService::new(messages_trait, attachments_trait, attachments.clone()).recover(),
        )?;

        Ok(Self {
            runtime,
            pool,
            providers,
            models,
            conversations,
            messages,
            settings,
            secrets,
            attachment_repository,
            attachments,
            recovery,
        })
    }

    /// Run a future on the shared runtime.
    ///
    /// This must not be called from inside an async task on the same runtime.
    /// The Slint thread is synchronous, so local SQLite work is safe here.
    pub fn block_on<F: Future>(&self, future: F) -> F::Output {
        self.runtime.block_on(future)
    }

    /// Borrow the shared SQLite pool.
    pub fn pool(&self) -> &SqlitePool {
        &self.pool
    }

    /// Borrow the shared attachment byte store.
    pub fn attachment_store(&self) -> &AttachmentStore {
        &self.attachments
    }

    /// Summary of the startup recovery pass.
    pub fn recovery_summary(&self) -> &RecoverySummary {
        &self.recovery
    }

    /// Shared provider repository.
    pub fn providers(&self) -> Arc<dyn ProviderRepository> {
        self.providers.clone()
    }

    /// Shared model repository.
    pub fn models(&self) -> Arc<dyn ModelRepository> {
        self.models.clone()
    }

    /// Shared conversation repository.
    pub fn conversations(&self) -> Arc<dyn ConversationRepository> {
        self.conversations.clone()
    }

    /// Shared message repository.
    pub fn messages(&self) -> Arc<dyn MessageRepository> {
        self.messages.clone()
    }

    /// Shared settings repository.
    pub fn settings(&self) -> Arc<dyn SettingsRepository> {
        self.settings.clone()
    }

    /// Shared secret metadata repository.
    pub fn secrets(&self) -> Arc<dyn SecretRepository> {
        self.secrets.clone()
    }

    /// Shared attachment metadata repository.
    pub fn attachment_repository(&self) -> Arc<dyn AttachmentRepository> {
        self.attachment_repository.clone()
    }

    /// A synchronous bridge over the shared runtime for later adapters.
    pub fn bridge(&self) -> SyncBridge {
        SyncBridge::new(Arc::clone(&self.runtime))
    }

    /// A synchronous settings store over the shared SQLite repository.
    pub fn settings_store(&self) -> SyncSettingsStore {
        SyncSettingsStore::new(self.bridge(), self.settings.clone())
    }
}

fn build_runtime() -> Result<Runtime, ServiceRuntimeError> {
    Runtime::new().map_err(ServiceRuntimeError::Runtime)
}

#[cfg(test)]
mod tests {
    use super::*;
    use chatui_domain::{
        ChatParams, ContentPart, Conversation, Message, MessageRole, MessageStatus, Theme,
        Timestamp,
    };
    use chatui_storage::{BUSY_TIMEOUT_MS, default_settings, read_pragmas};
    use chatui_ui::settings::SettingsStore;

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
            revision: 0,
        }
    }

    fn streaming_message(id: &str, conversation_id: &str) -> Message {
        Message {
            id: id.to_owned(),
            conversation_id: conversation_id.to_owned(),
            sequence: 0,
            role: MessageRole::Assistant,
            parts: vec![ContentPart::Text {
                text: "partial answer".to_owned(),
            }],
            status: MessageStatus::Streaming,
            provider_id: Some("provider-1".to_owned()),
            model_id: Some("model-1".to_owned()),
            model_label: Some("Model One".to_owned()),
            usage: None,
            finish_reason: None,
            error: None,
            latency_ms: None,
            first_token_ms: None,
            created_at: timestamp(0),
            updated_at: timestamp(0),
            deleted_at: None,
        }
    }

    fn seed_streaming_message(path: &Path) {
        let runtime = Runtime::new().expect("create seeding runtime");
        runtime.block_on(async {
            let pool = open_database(path).await.expect("open seed database");
            let conversations = SqliteConversationRepository::new(pool.clone());
            conversations
                .create(&conversation("conversation-1"))
                .await
                .expect("create conversation");
            let messages = SqliteMessageRepository::new(pool.clone());
            messages
                .insert(&streaming_message("message-1", "conversation-1"))
                .await
                .expect("insert streaming message");
            pool.close().await;
        });
    }

    #[test]
    fn bootstrap_opens_migrates_and_verifies() {
        let directory = tempfile::tempdir().expect("create temporary directory");
        let database_path = directory.path().join("chatui.sqlite3");

        let runtime =
            ServiceRuntime::open_at(&database_path, directory.path()).expect("open runtime");

        assert!(database_path.exists(), "database file was not created");

        let pragmas = runtime
            .block_on(read_pragmas(runtime.pool()))
            .expect("read pragmas");
        assert_eq!(pragmas.journal_mode.to_ascii_lowercase(), "wal");
        assert!(pragmas.foreign_keys);
        assert_eq!(pragmas.busy_timeout_ms, BUSY_TIMEOUT_MS);

        runtime
            .block_on(verify_fts5(runtime.pool()))
            .expect("FTS5 is available");

        // Loading settings proves the migrations created the settings table.
        let settings = runtime.settings_store().load().expect("load settings");
        assert_eq!(settings, default_settings());
    }

    #[test]
    fn bootstrap_runs_startup_recovery() {
        let directory = tempfile::tempdir().expect("create temporary directory");
        let database_path = directory.path().join("chatui.sqlite3");
        seed_streaming_message(&database_path);

        let runtime =
            ServiceRuntime::open_at(&database_path, directory.path()).expect("open runtime");

        assert_eq!(runtime.recovery_summary().recovered.len(), 1);

        let messages = runtime.messages();
        let loaded = runtime
            .block_on(messages.get("message-1"))
            .expect("get message")
            .expect("message exists");
        assert_eq!(loaded.status, MessageStatus::Cancelled);
    }

    #[test]
    fn settings_store_round_trips_through_sqlite() {
        let directory = tempfile::tempdir().expect("create temporary directory");
        let runtime =
            ServiceRuntime::open_at(directory.path().join("chatui.sqlite3"), directory.path())
                .expect("open runtime");
        let store = runtime.settings_store();

        let mut settings = store.load().expect("load defaults");
        assert_eq!(settings, default_settings());

        settings.theme = Theme::Dark;
        settings.show_token_usage = false;
        settings.default_system_prompt = "Be concise.".to_owned();
        store.save(&settings).expect("save settings");

        let loaded = store.load().expect("reload settings");
        assert_eq!(loaded, settings);
    }
}
