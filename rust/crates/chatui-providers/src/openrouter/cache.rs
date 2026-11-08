//! Catalog cache with a 24-hour TTL and last-known-good fallback.
//!
//! The cache stores only public catalog data: model IDs, labels, pricing,
//! modalities, capabilities, and non-secret metadata. It never stores API keys,
//! authorization headers, prompts, or conversation content.
//!
//! The file-backed cache lives under `<app-data>/catalogs/<provider-id>.json`
//! and uses a versioned JSON shape:
//!
//! ```json
//! {
//!   "version": 1,
//!   "providerId": "openrouter",
//!   "fetchedAt": "2026-11-07T12:00:00.000Z",
//!   "models": [ ... ]
//! }
//! ```

use std::collections::HashMap;
use std::fs;
use std::io::ErrorKind;
use std::path::{Path, PathBuf};
use std::sync::Mutex;

use chatui_domain::Timestamp;
use serde::{Deserialize, Serialize};
use thiserror::Error;

use crate::http::HttpClient;
use crate::openrouter::catalog::{CatalogModel, list_models};
use crate::openrouter::key::OpenRouterError;

/// OpenRouter catalog TTL: 24 hours.
pub const OPENROUTER_CATALOG_TTL_SECONDS: u64 = 86_400;

/// Directory under the app-data directory that holds catalog snapshots.
pub const CATALOG_DIR_NAME: &str = "catalogs";

/// Version of the on-disk snapshot shape.
pub const CATALOG_CACHE_VERSION: u32 = 1;

/// A successful catalog snapshot.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CatalogSnapshot {
    /// When the snapshot was fetched.
    pub fetched_at: Timestamp,
    /// Normalized catalog entries.
    pub models: Vec<CatalogModel>,
}

/// Result of a cache-aware catalog load.
#[derive(Debug, Clone)]
pub struct CatalogResult {
    /// Normalized catalog entries.
    pub models: Vec<CatalogModel>,
    /// When the returned snapshot was fetched, or `None` when no snapshot
    /// exists.
    pub fetched_at: Option<Timestamp>,
    /// Whether the result came from the cache.
    pub from_cache: bool,
    /// Whether the result is a last-known-good snapshot after a failed refresh.
    pub stale: bool,
    /// The refresh error when a stale snapshot was returned.
    pub refresh_error: Option<OpenRouterError>,
}

/// A catalog cache failure.
#[derive(Debug, Error)]
pub enum CacheError {
    /// Reading or writing the cache file failed.
    #[error("catalog cache I/O failed: {0}")]
    Io(#[from] std::io::Error),
    /// The cache file was not valid JSON.
    #[error("catalog cache JSON is invalid: {0}")]
    Json(#[from] serde_json::Error),
    /// The cache file uses an unsupported shape version.
    #[error("unsupported catalog cache version {0}")]
    UnsupportedVersion(u32),
    /// The provider ID is not safe to use as a file name.
    #[error("invalid provider id for catalog cache: {0}")]
    InvalidProviderId(String),
    /// The cache file belongs to a different provider.
    #[error("catalog cache provider mismatch: expected {expected}, found {found}")]
    ProviderMismatch {
        /// The requested provider ID.
        expected: String,
        /// The provider ID recorded in the file.
        found: String,
    },
    /// The in-memory cache lock is poisoned.
    #[error("catalog cache lock is poisoned")]
    Lock,
    /// The platform app-data directory is unavailable.
    #[error("the platform app-data directory is unavailable")]
    AppDataUnavailable,
}

/// A cache for normalized catalog snapshots.
pub trait CatalogCache: Send + Sync {
    /// Read the cached snapshot for a provider.
    fn get(&self, provider_id: &str) -> Result<Option<CatalogSnapshot>, CacheError>;

    /// Write a snapshot for a provider.
    ///
    /// `fetched_at` is the authoritative fetch time. It lets callers inject a
    /// clock in tests and keeps the stored timestamp independent of the
    /// snapshot value.
    fn put(
        &self,
        provider_id: &str,
        snapshot: &CatalogSnapshot,
        fetched_at: Timestamp,
    ) -> Result<(), CacheError>;
}

/// An in-memory catalog cache.
#[derive(Debug, Default)]
pub struct MemoryCatalogCache {
    entries: Mutex<HashMap<String, CatalogSnapshot>>,
}

impl MemoryCatalogCache {
    /// Create an empty in-memory cache.
    pub fn new() -> Self {
        Self::default()
    }
}

impl CatalogCache for MemoryCatalogCache {
    fn get(&self, provider_id: &str) -> Result<Option<CatalogSnapshot>, CacheError> {
        let entries = self.entries.lock().map_err(|_| CacheError::Lock)?;
        Ok(entries.get(provider_id).cloned())
    }

    fn put(
        &self,
        provider_id: &str,
        snapshot: &CatalogSnapshot,
        fetched_at: Timestamp,
    ) -> Result<(), CacheError> {
        let mut entries = self.entries.lock().map_err(|_| CacheError::Lock)?;
        entries.insert(
            provider_id.to_owned(),
            CatalogSnapshot {
                fetched_at,
                models: snapshot.models.clone(),
            },
        );
        Ok(())
    }
}

/// A JSON file-backed catalog cache.
///
/// `base_dir` is the catalog directory itself, normally
/// `<app-data>/catalogs`. Use [`FileCatalogCache::default_cache`] for the
/// platform default.
#[derive(Debug, Clone)]
pub struct FileCatalogCache {
    base_dir: PathBuf,
}

impl FileCatalogCache {
    /// Create a cache rooted at a catalog directory.
    pub fn new(base_dir: impl Into<PathBuf>) -> Self {
        Self {
            base_dir: base_dir.into(),
        }
    }

    /// Return the cache directory.
    pub fn base_dir(&self) -> &Path {
        &self.base_dir
    }

    /// Return the default catalog directory under the platform app-data
    /// directory.
    pub fn default_path() -> Result<PathBuf, CacheError> {
        directories::ProjectDirs::from("", "", "chatui")
            .map(|dirs| dirs.data_dir().join(CATALOG_DIR_NAME))
            .ok_or(CacheError::AppDataUnavailable)
    }

    /// Create a cache at the platform default path.
    pub fn default_cache() -> Result<Self, CacheError> {
        Ok(Self::new(Self::default_path()?))
    }

    fn path_for(&self, provider_id: &str) -> Result<PathBuf, CacheError> {
        let valid = !provider_id.is_empty()
            && provider_id != "."
            && provider_id != ".."
            && provider_id.chars().all(|character| {
                character.is_ascii_alphanumeric() || matches!(character, '-' | '_' | '.')
            });
        if !valid {
            return Err(CacheError::InvalidProviderId(provider_id.to_owned()));
        }

        Ok(self.base_dir.join(format!("{provider_id}.json")))
    }
}

#[derive(Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct FileSnapshot {
    version: u32,
    provider_id: String,
    fetched_at: Timestamp,
    models: Vec<CatalogModel>,
}

impl CatalogCache for FileCatalogCache {
    fn get(&self, provider_id: &str) -> Result<Option<CatalogSnapshot>, CacheError> {
        let path = self.path_for(provider_id)?;
        let bytes = match fs::read(&path) {
            Ok(bytes) => bytes,
            Err(error) if error.kind() == ErrorKind::NotFound => return Ok(None),
            Err(error) => return Err(CacheError::Io(error)),
        };

        let file: FileSnapshot = serde_json::from_slice(&bytes)?;
        if file.version != CATALOG_CACHE_VERSION {
            return Err(CacheError::UnsupportedVersion(file.version));
        }
        if file.provider_id != provider_id {
            return Err(CacheError::ProviderMismatch {
                expected: provider_id.to_owned(),
                found: file.provider_id,
            });
        }

        Ok(Some(CatalogSnapshot {
            fetched_at: file.fetched_at,
            models: file.models,
        }))
    }

    fn put(
        &self,
        provider_id: &str,
        snapshot: &CatalogSnapshot,
        fetched_at: Timestamp,
    ) -> Result<(), CacheError> {
        let path = self.path_for(provider_id)?;
        if let Some(parent) = path.parent() {
            fs::create_dir_all(parent)?;
        }

        let file = FileSnapshot {
            version: CATALOG_CACHE_VERSION,
            provider_id: provider_id.to_owned(),
            fetched_at,
            models: snapshot.models.clone(),
        };
        let bytes = serde_json::to_vec_pretty(&file)?;
        let temporary = path.with_extension("json.tmp");
        fs::write(&temporary, bytes)?;
        fs::rename(&temporary, &path)?;
        Ok(())
    }
}

/// Return whether a snapshot is still inside the TTL.
///
/// A snapshot with a future timestamp is treated as fresh.
pub fn is_fresh(snapshot: &CatalogSnapshot, now: Timestamp, ttl_seconds: u64) -> bool {
    let age = now.as_offset_date_time().unix_timestamp()
        - snapshot.fetched_at.as_offset_date_time().unix_timestamp();
    age < ttl_seconds as i64
}

/// Load the OpenRouter catalog with a 24-hour cache and last-known-good
/// fallback.
///
/// A fresh cache entry is returned without a network request. `force_refresh`
/// bypasses the TTL. When a refresh fails, the last successful snapshot is
/// returned with `stale: true` and `refresh_error` set. If no snapshot exists,
/// the refresh error is returned.
pub async fn load_catalog(
    client: &HttpClient,
    base_url: &str,
    cache: &dyn CatalogCache,
    provider_id: &str,
    now: Timestamp,
    force_refresh: bool,
) -> Result<CatalogResult, OpenRouterError> {
    let cached = cache.get(provider_id).ok().flatten();

    if let Some(snapshot) = &cached
        && !force_refresh
        && is_fresh(snapshot, now, OPENROUTER_CATALOG_TTL_SECONDS)
    {
        return Ok(CatalogResult {
            models: snapshot.models.clone(),
            fetched_at: Some(snapshot.fetched_at),
            from_cache: true,
            stale: false,
            refresh_error: None,
        });
    }

    match list_models(client, base_url).await {
        Ok(models) => {
            let snapshot = CatalogSnapshot {
                fetched_at: now,
                models,
            };
            let _ = cache.put(provider_id, &snapshot, now);
            Ok(CatalogResult {
                models: snapshot.models,
                fetched_at: Some(snapshot.fetched_at),
                from_cache: false,
                stale: false,
                refresh_error: None,
            })
        }
        Err(error) => {
            if let Some(snapshot) = cached {
                Ok(CatalogResult {
                    models: snapshot.models,
                    fetched_at: Some(snapshot.fetched_at),
                    from_cache: true,
                    stale: true,
                    refresh_error: Some(error),
                })
            } else {
                Err(error)
            }
        }
    }
}

/// Force a catalog refresh while preserving last-known-good behavior.
pub async fn refresh_catalog(
    client: &HttpClient,
    base_url: &str,
    cache: &dyn CatalogCache,
    provider_id: &str,
    now: Timestamp,
) -> Result<CatalogResult, OpenRouterError> {
    load_catalog(client, base_url, cache, provider_id, now, true).await
}
