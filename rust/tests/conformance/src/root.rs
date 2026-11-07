//! Repository-relative fixture root resolution.
//!
//! The harness resolves the repository root from `CARGO_MANIFEST_DIR` at
//! compile time. The conformance package lives at
//! `rust/tests/conformance`, so the repository root is three ancestors above
//! the manifest directory. Fixtures are always read from `contracts/fixtures`
//! at test time; no fixture content is copied into Rust source.

use std::fs;
use std::path::{Path, PathBuf};

use serde::de::DeserializeOwned;

use crate::error::FixtureError;

/// A resolved repository root and its `contracts/fixtures` directory.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct FixtureRoot {
    root: PathBuf,
}

impl FixtureRoot {
    /// Resolve the repository root from this package's `CARGO_MANIFEST_DIR`.
    pub fn repository() -> Self {
        let manifest_dir = PathBuf::from(env!("CARGO_MANIFEST_DIR"));
        let root = manifest_dir
            .ancestors()
            .nth(3)
            .expect("conformance package is three levels below the repository root")
            .to_path_buf();
        Self { root }
    }

    /// Use an explicit repository root.
    ///
    /// This is useful for tests that exercise the loader against a temporary
    /// tree. Production conformance tests should use [`Self::repository`].
    pub fn from_repository_root(root: impl Into<PathBuf>) -> Self {
        Self { root: root.into() }
    }

    /// Return the repository root.
    pub fn root(&self) -> &Path {
        &self.root
    }

    /// Return the `contracts` directory.
    pub fn contracts(&self) -> PathBuf {
        self.root.join("contracts")
    }

    /// Return the `contracts/fixtures` directory.
    pub fn fixtures(&self) -> PathBuf {
        self.contracts().join("fixtures")
    }

    /// Resolve a path relative to `contracts/fixtures`.
    pub fn path(&self, relative: impl AsRef<Path>) -> PathBuf {
        self.fixtures().join(relative)
    }

    /// Read a UTF-8 fixture relative to `contracts/fixtures`.
    pub fn read(&self, relative: impl AsRef<Path>) -> Result<String, FixtureError> {
        let path = self.path(relative);
        fs::read_to_string(&path).map_err(|source| FixtureError::Io { path, source })
    }

    /// Read and deserialize a JSON fixture relative to `contracts/fixtures`.
    pub fn read_json<T: DeserializeOwned>(
        &self,
        relative: impl AsRef<Path>,
    ) -> Result<T, FixtureError> {
        let path = self.path(relative);
        let contents = fs::read_to_string(&path).map_err(|source| FixtureError::Io {
            path: path.clone(),
            source,
        })?;
        serde_json::from_str(&contents).map_err(|source| FixtureError::Json { path, source })
    }

    /// List file names in a fixture directory, sorted ascending.
    pub fn list_files(&self, relative_dir: impl AsRef<Path>) -> Result<Vec<String>, FixtureError> {
        let path = self.path(relative_dir);
        let entries = fs::read_dir(&path).map_err(|source| FixtureError::Io {
            path: path.clone(),
            source,
        })?;

        let mut names = Vec::new();
        for entry in entries {
            let entry = entry.map_err(|source| FixtureError::Io {
                path: path.clone(),
                source,
            })?;
            let file_type = entry.file_type().map_err(|source| FixtureError::Io {
                path: entry.path(),
                source,
            })?;
            if file_type.is_file() {
                names.push(entry.file_name().to_string_lossy().into_owned());
            }
        }

        names.sort();
        Ok(names)
    }

    /// List `*.json` file names in a fixture directory, sorted ascending.
    pub fn list_json_files(
        &self,
        relative_dir: impl AsRef<Path>,
    ) -> Result<Vec<String>, FixtureError> {
        let mut names = self.list_files(relative_dir)?;
        names.retain(|name| name.ends_with(".json"));
        Ok(names)
    }
}
