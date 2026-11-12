//! Database connection lifecycle, pragmas, and migrations.
//!
//! Every connection is opened with the same pragmas:
//!
//! ```sql
//! PRAGMA journal_mode = WAL;
//! PRAGMA foreign_keys = ON;
//! PRAGMA busy_timeout = 5000;
//! ```
//!
//! SQLx applies these through [`SqliteConnectOptions`], so pooled connections
//! inherit them. [`open_database`] verifies the values after connecting and
//! fails closed if they do not match.

use std::path::Path;
use std::time::Duration;

use sqlx::migrate::Migrator;
use sqlx::sqlite::{SqliteConnectOptions, SqliteJournalMode, SqlitePool, SqlitePoolOptions};

use crate::StorageError;

/// Busy timeout applied to every connection, in milliseconds.
pub const BUSY_TIMEOUT_MS: u64 = 5_000;

/// Maximum number of pooled connections for a file-backed database.
const MAX_CONNECTIONS: u32 = 5;

/// Embedded, versioned migrations from `migrations/`.
static MIGRATOR: Migrator = sqlx::migrate!("./migrations");

/// Observed pragma values for a connection.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PragmaState {
    /// Current journal mode, for example `wal` or `memory`.
    pub journal_mode: String,
    /// Whether foreign-key enforcement is enabled.
    pub foreign_keys: bool,
    /// Busy timeout in milliseconds.
    pub busy_timeout_ms: u64,
}

/// Opens or creates a file-backed database, applies pragmas, and runs
/// migrations.
///
/// The parent directory is created when missing. The returned pool is ready for
/// repository use. Callers own the pool and should call [`SqlitePool::close`]
/// during shutdown.
pub async fn open_database(path: impl AsRef<Path>) -> Result<SqlitePool, StorageError> {
    let path = path.as_ref();
    if let Some(parent) = path.parent()
        && !parent.as_os_str().is_empty()
    {
        std::fs::create_dir_all(parent)?;
    }

    let options = SqliteConnectOptions::new()
        .filename(path)
        .create_if_missing(true)
        .journal_mode(SqliteJournalMode::Wal)
        .foreign_keys(true)
        .busy_timeout(Duration::from_millis(BUSY_TIMEOUT_MS));

    let pool = SqlitePoolOptions::new()
        .max_connections(MAX_CONNECTIONS)
        .connect_with(options)
        .await?;

    verify_fts5(&pool).await?;
    run_migrations(&pool).await?;
    verify_pragmas(&pool).await?;

    Ok(pool)
}

/// Opens the default database under the platform app-data directory.
pub async fn open_default_database() -> Result<SqlitePool, StorageError> {
    open_database(crate::database_path()?).await
}

/// Opens a private in-memory database for tests and short-lived work.
///
/// In-memory databases cannot use WAL, so the journal mode is `memory`. Foreign
/// keys and the busy timeout are still applied and verified. The pool is capped
/// at one connection because each SQLite in-memory connection has its own
/// database.
pub async fn open_in_memory() -> Result<SqlitePool, StorageError> {
    let options = SqliteConnectOptions::new()
        .in_memory(true)
        .journal_mode(SqliteJournalMode::Memory)
        .foreign_keys(true)
        .busy_timeout(Duration::from_millis(BUSY_TIMEOUT_MS));

    let pool = SqlitePoolOptions::new()
        .max_connections(1)
        .min_connections(1)
        .idle_timeout(None)
        .max_lifetime(None)
        .connect_with(options)
        .await?;

    verify_fts5(&pool).await?;
    run_migrations(&pool).await?;

    let state = read_pragmas(&pool).await?;
    if !state.foreign_keys {
        return Err(StorageError::PragmaMismatch(
            "expected foreign_keys=ON for the in-memory database".to_owned(),
        ));
    }
    if state.busy_timeout_ms != BUSY_TIMEOUT_MS {
        return Err(StorageError::PragmaMismatch(format!(
            "expected busy_timeout={BUSY_TIMEOUT_MS}, got {}",
            state.busy_timeout_ms
        )));
    }

    Ok(pool)
}

/// Runs all pending migrations.
///
/// SQLx records applied versions and checksums in `_sqlx_migrations`, so calling
/// this again is a no-op. There is no destructive fallback.
///
/// After the migration runner finishes, empty conversation storage keys are
/// backfilled with random 128-bit values. SQLite cannot generate portable random
/// defaults, so the backfill lives in Rust and is idempotent.
pub async fn run_migrations(pool: &SqlitePool) -> Result<(), StorageError> {
    MIGRATOR.run(pool).await?;
    backfill_conversation_storage_keys(pool).await?;
    Ok(())
}

/// Backfill empty conversation storage keys with random 128-bit values.
///
/// The partial unique index on `conversations(storage_key)` rejects collisions.
/// A collision is retried with a fresh random key; the loop is bounded so a
/// persistent failure surfaces instead of spinning.
pub async fn backfill_conversation_storage_keys(pool: &SqlitePool) -> Result<(), StorageError> {
    let ids: Vec<String> =
        sqlx::query_scalar("SELECT id FROM conversations WHERE storage_key = ''")
            .fetch_all(pool)
            .await?;

    for id in ids {
        let mut stored = false;
        for _ in 0..8 {
            let key = crate::paths::new_conversation_storage_key();
            let result = sqlx::query(
                "UPDATE conversations SET storage_key = ? WHERE id = ? AND storage_key = ''",
            )
            .bind(&key)
            .bind(&id)
            .execute(pool)
            .await;

            match result {
                Ok(_) => {
                    stored = true;
                    break;
                }
                Err(sqlx::Error::Database(error)) if error.is_unique_violation() => continue,
                Err(error) => return Err(error.into()),
            }
        }

        if !stored {
            return Err(StorageError::Conflict(format!(
                "could not allocate a unique storage key for conversation {id}"
            )));
        }
    }

    Ok(())
}

/// Reads the pragma values from one pooled connection.
pub async fn read_pragmas(pool: &SqlitePool) -> Result<PragmaState, StorageError> {
    let mut connection = pool.acquire().await?;

    let journal_mode: String = sqlx::query_scalar("PRAGMA journal_mode")
        .fetch_one(&mut *connection)
        .await?;
    let foreign_keys: i64 = sqlx::query_scalar("PRAGMA foreign_keys")
        .fetch_one(&mut *connection)
        .await?;
    let busy_timeout_ms: i64 = sqlx::query_scalar("PRAGMA busy_timeout")
        .fetch_one(&mut *connection)
        .await?;

    Ok(PragmaState {
        journal_mode,
        foreign_keys: foreign_keys != 0,
        busy_timeout_ms: u64::try_from(busy_timeout_ms).unwrap_or_default(),
    })
}

/// Verifies that WAL, foreign keys, and the busy timeout are active.
pub async fn verify_pragmas(pool: &SqlitePool) -> Result<PragmaState, StorageError> {
    let state = read_pragmas(pool).await?;

    if !state.journal_mode.eq_ignore_ascii_case("wal") {
        return Err(StorageError::PragmaMismatch(format!(
            "expected journal_mode=wal, got {}",
            state.journal_mode
        )));
    }
    if !state.foreign_keys {
        return Err(StorageError::PragmaMismatch(
            "expected foreign_keys=ON".to_owned(),
        ));
    }
    if state.busy_timeout_ms != BUSY_TIMEOUT_MS {
        return Err(StorageError::PragmaMismatch(format!(
            "expected busy_timeout={BUSY_TIMEOUT_MS}, got {}",
            state.busy_timeout_ms
        )));
    }

    Ok(state)
}

/// Proves that the linked SQLite build exposes FTS5.
///
/// The check creates and drops a temporary FTS5 virtual table. It fails closed
/// with [`StorageError::Fts5Unavailable`] when the build lacks FTS5.
pub async fn verify_fts5(pool: &SqlitePool) -> Result<(), StorageError> {
    let mut connection = pool.acquire().await?;

    let compile_option: i64 = sqlx::query_scalar("SELECT sqlite_compileoption_used('ENABLE_FTS5')")
        .fetch_one(&mut *connection)
        .await?;

    let created =
        sqlx::query("CREATE VIRTUAL TABLE IF NOT EXISTS temp.fts5_probe USING fts5(content)")
            .execute(&mut *connection)
            .await;

    match created {
        Ok(_) => {
            sqlx::query("DROP TABLE IF EXISTS temp.fts5_probe")
                .execute(&mut *connection)
                .await?;
            Ok(())
        }
        Err(error) => Err(StorageError::Fts5Unavailable(format!(
            "SQLITE_ENABLE_FTS5 compile option = {compile_option}; creating an FTS5 table failed: {error}"
        ))),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::{DATABASE_FILE_NAME, app_data_dir, database_path};

    async fn temp_database() -> (tempfile::TempDir, SqlitePool) {
        let directory = tempfile::tempdir().expect("create temporary directory");
        let path = directory.path().join(DATABASE_FILE_NAME);
        let pool = open_database(&path).await.expect("open temporary database");
        (directory, pool)
    }

    async fn table_names(pool: &SqlitePool) -> Vec<String> {
        sqlx::query_scalar("SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name")
            .fetch_all(pool)
            .await
            .expect("list tables")
    }

    async fn index_names(pool: &SqlitePool) -> Vec<String> {
        sqlx::query_scalar("SELECT name FROM sqlite_master WHERE type = 'index' ORDER BY name")
            .fetch_all(pool)
            .await
            .expect("list indexes")
    }

    #[tokio::test]
    async fn migrations_create_expected_schema() {
        let (_directory, pool) = temp_database().await;

        let tables = table_names(&pool).await;
        for expected in [
            "conversations",
            "messages",
            "attachments",
            "providers",
            "models",
            "secrets",
            "settings",
            "backup_config",
            "sync_queue",
            "messages_fts",
            "_sqlx_migrations",
        ] {
            assert!(
                tables.iter().any(|table| table == expected),
                "missing table {expected}; found {tables:?}"
            );
        }

        let indexes = index_names(&pool).await;
        for expected in [
            "idx_messages_conversation_sequence",
            "idx_models_provider_remote",
            "idx_attachments_sha256",
        ] {
            assert!(
                indexes.iter().any(|index| index == expected),
                "missing index {expected}; found {indexes:?}"
            );
        }

        let fts_sql: String =
            sqlx::query_scalar("SELECT sql FROM sqlite_master WHERE name = 'messages_fts'")
                .fetch_one(&pool)
                .await
                .expect("read messages_fts definition");
        assert!(
            fts_sql.to_ascii_uppercase().contains("VIRTUAL TABLE"),
            "messages_fts is not a virtual table: {fts_sql}"
        );

        pool.close().await;
    }

    #[tokio::test]
    async fn migration_0002_preserves_existing_data_and_backfills_storage_keys() {
        let options = SqliteConnectOptions::new()
            .in_memory(true)
            .foreign_keys(true);
        let pool = SqlitePoolOptions::new()
            .max_connections(1)
            .min_connections(1)
            .connect_with(options)
            .await
            .expect("open raw in-memory database");

        sqlx::raw_sql(include_str!("../migrations/0001_initial.sql"))
            .execute(&pool)
            .await
            .expect("apply 0001");

        sqlx::query(
            "INSERT INTO conversations \
             (id, title, system_prompt, params_json, created_at, updated_at) \
             VALUES ('conversation-1', 'Existing', 'Be concise.', '{}', \
                     '2026-01-01T00:00:00.000Z', '2026-01-01T00:00:00.000Z')",
        )
        .execute(&pool)
        .await
        .expect("insert pre-migration conversation");

        sqlx::raw_sql(include_str!("../migrations/0002_backup_sync.sql"))
            .execute(&pool)
            .await
            .expect("apply 0002");

        let (title, storage_key, revision): (String, String, i64) = sqlx::query_as(
            "SELECT title, storage_key, revision FROM conversations WHERE id = 'conversation-1'",
        )
        .fetch_one(&pool)
        .await
        .expect("read migrated conversation");
        assert_eq!(title, "Existing");
        assert_eq!(storage_key, "");
        assert_eq!(revision, 1);

        backfill_conversation_storage_keys(&pool)
            .await
            .expect("backfill storage keys");

        let storage_key: String =
            sqlx::query_scalar("SELECT storage_key FROM conversations WHERE id = 'conversation-1'")
                .fetch_one(&pool)
                .await
                .expect("read backfilled key");
        assert_eq!(storage_key.len(), 32);
        assert!(crate::paths::is_valid_conversation_storage_key(
            &storage_key
        ));

        sqlx::query(
            "INSERT INTO conversations \
             (id, title, system_prompt, params_json, created_at, updated_at) \
             VALUES ('conversation-2', 'Second', '', '{}', \
                     '2026-01-01T00:00:00.000Z', '2026-01-01T00:00:00.000Z')",
        )
        .execute(&pool)
        .await
        .expect("insert second conversation");
        backfill_conversation_storage_keys(&pool)
            .await
            .expect("backfill second storage key");

        let keys: Vec<String> =
            sqlx::query_scalar("SELECT storage_key FROM conversations ORDER BY id")
                .fetch_all(&pool)
                .await
                .expect("read storage keys");
        assert_eq!(keys.len(), 2);
        assert_ne!(keys[0], keys[1]);
        assert!(keys.iter().all(|key| key.len() == 32));

        pool.close().await;
    }

    #[tokio::test]
    async fn migrations_are_idempotent() {
        let directory = tempfile::tempdir().expect("create temporary directory");
        let path = directory.path().join(DATABASE_FILE_NAME);

        let pool = open_database(&path).await.expect("open database");
        let before: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM _sqlx_migrations")
            .fetch_one(&pool)
            .await
            .expect("count migrations");
        run_migrations(&pool).await.expect("run migrations again");
        let after: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM _sqlx_migrations")
            .fetch_one(&pool)
            .await
            .expect("count migrations");
        assert_eq!(before, after, "re-running migrations added versions");
        pool.close().await;

        let pool = open_database(&path).await.expect("reopen database");
        let reopened: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM _sqlx_migrations")
            .fetch_one(&pool)
            .await
            .expect("count migrations after reopen");
        assert_eq!(before, reopened, "reopening the database re-ran migrations");
        pool.close().await;
    }

    #[tokio::test]
    async fn pragmas_are_active_on_every_connection() {
        let (_directory, pool) = temp_database().await;

        let state = verify_pragmas(&pool).await.expect("verify pragmas");
        assert_eq!(state.journal_mode.to_ascii_lowercase(), "wal");
        assert!(state.foreign_keys);
        assert_eq!(state.busy_timeout_ms, BUSY_TIMEOUT_MS);

        let mut first = pool.acquire().await.expect("acquire first connection");
        let mut second = pool.acquire().await.expect("acquire second connection");

        for connection in [&mut *first, &mut *second] {
            let journal_mode: String = sqlx::query_scalar("PRAGMA journal_mode")
                .fetch_one(&mut *connection)
                .await
                .expect("read journal_mode");
            let foreign_keys: i64 = sqlx::query_scalar("PRAGMA foreign_keys")
                .fetch_one(&mut *connection)
                .await
                .expect("read foreign_keys");
            let busy_timeout: i64 = sqlx::query_scalar("PRAGMA busy_timeout")
                .fetch_one(&mut *connection)
                .await
                .expect("read busy_timeout");

            assert_eq!(journal_mode.to_ascii_lowercase(), "wal");
            assert_eq!(foreign_keys, 1);
            assert_eq!(busy_timeout, BUSY_TIMEOUT_MS as i64);
        }

        drop(first);
        drop(second);
        pool.close().await;
    }

    #[tokio::test]
    async fn foreign_keys_are_enforced() {
        let (_directory, pool) = temp_database().await;

        let result = sqlx::query(
            "INSERT INTO messages \
             (id, conversation_id, sequence, role, parts_json, status, created_at, updated_at) \
             VALUES ('message-1', 'missing-conversation', 0, 'user', '[]', 'complete', 0, 0)",
        )
        .execute(&pool)
        .await;

        assert!(
            result.is_err(),
            "foreign-key violation was accepted; foreign_keys is not active"
        );

        pool.close().await;
    }

    #[tokio::test]
    async fn fts5_is_available_and_searchable() {
        let (_directory, pool) = temp_database().await;

        verify_fts5(&pool).await.expect("FTS5 is available");

        sqlx::query(
            "INSERT INTO messages_fts (message_id, conversation_id, content) \
             VALUES ('message-1', 'conversation-1', 'hello searchable world')",
        )
        .execute(&pool)
        .await
        .expect("insert into FTS5 table");

        let matches: Vec<String> = sqlx::query_scalar(
            "SELECT message_id FROM messages_fts WHERE messages_fts MATCH 'searchable'",
        )
        .fetch_all(&pool)
        .await
        .expect("run FTS5 MATCH query");

        assert_eq!(matches, vec!["message-1".to_owned()]);

        pool.close().await;
    }

    #[tokio::test]
    async fn in_memory_database_runs_migrations() {
        let pool = open_in_memory().await.expect("open in-memory database");

        let tables = table_names(&pool).await;
        assert!(tables.iter().any(|table| table == "messages"));
        assert!(tables.iter().any(|table| table == "messages_fts"));

        let state = read_pragmas(&pool).await.expect("read pragmas");
        assert_eq!(state.journal_mode.to_ascii_lowercase(), "memory");
        assert!(state.foreign_keys);
        assert_eq!(state.busy_timeout_ms, BUSY_TIMEOUT_MS);

        pool.close().await;
    }

    #[tokio::test]
    async fn open_database_creates_parent_directory() {
        let directory = tempfile::tempdir().expect("create temporary directory");
        let path = directory.path().join("nested").join(DATABASE_FILE_NAME);

        let pool = open_database(&path).await.expect("open nested database");
        assert!(path.exists(), "database file was not created at {path:?}");

        pool.close().await;
    }

    #[test]
    fn database_path_is_under_app_data() {
        let app_data = app_data_dir().expect("app-data directory");
        let path = database_path().expect("database path");

        assert_eq!(path, app_data.join(DATABASE_FILE_NAME));
        assert_eq!(
            path.file_name().and_then(|name| name.to_str()),
            Some(DATABASE_FILE_NAME)
        );
    }
}
