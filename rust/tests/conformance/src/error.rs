//! Errors returned by the fixture harness.

use std::fmt;
use std::path::PathBuf;

/// A fixture could not be read, parsed, or classified.
#[derive(Debug)]
pub enum FixtureError {
    /// A file or directory could not be read.
    Io {
        /// Path that failed.
        path: PathBuf,
        /// Underlying I/O error.
        source: std::io::Error,
    },
    /// A JSON fixture could not be parsed.
    Json {
        /// Path that failed.
        path: PathBuf,
        /// Underlying parser error.
        source: serde_json::Error,
    },
    /// A fixture name is not valid for its family.
    InvalidName {
        /// Fixture family, such as `streams` or `requests`.
        family: &'static str,
        /// Offending file name.
        name: String,
    },
}

impl fmt::Display for FixtureError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::Io { path, source } => {
                write!(formatter, "could not read {}: {source}", path.display())
            }
            Self::Json { path, source } => {
                write!(formatter, "could not parse {}: {source}", path.display())
            }
            Self::InvalidName { family, name } => {
                write!(formatter, "invalid {family} fixture name: {name}")
            }
        }
    }
}

impl std::error::Error for FixtureError {
    fn source(&self) -> Option<&(dyn std::error::Error + 'static)> {
        match self {
            Self::Io { source, .. } => Some(source),
            Self::Json { source, .. } => Some(source),
            Self::InvalidName { .. } => None,
        }
    }
}
