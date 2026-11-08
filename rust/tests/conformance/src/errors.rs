//! Raw error fixtures and expected error vectors.

use serde::{Deserialize, Serialize};
use serde_json::Value;

use crate::streams::ExpectedChatError;

/// The transport shape of a raw error fixture.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ErrorFixtureKind {
    /// HTTP error envelope stored as JSON.
    HttpJson,
    /// In-stream SSE error.
    StreamSse,
    /// In-stream NDJSON error.
    StreamNdjson,
    /// A future fixture shape the harness does not classify yet.
    Other,
}

impl ErrorFixtureKind {
    /// Classify an error fixture by file name.
    pub fn from_name(name: &str) -> Self {
        if name.ends_with(".json") {
            Self::HttpJson
        } else if name.ends_with(".sse") {
            Self::StreamSse
        } else if name.ends_with(".txt") {
            Self::StreamNdjson
        } else {
            Self::Other
        }
    }
}

/// The parsed body of a raw error fixture.
#[derive(Debug, Clone, PartialEq)]
pub enum ErrorFixtureBody {
    /// Parsed JSON envelope.
    Json(Value),
    /// Raw SSE or NDJSON text.
    Stream(String),
}

/// A raw error fixture under `contracts/fixtures/errors/`.
#[derive(Debug, Clone, PartialEq)]
pub struct ErrorFixture {
    /// File name, such as `openai-429.json`.
    pub name: String,
    /// Classified transport shape.
    pub kind: ErrorFixtureKind,
    /// Parsed body.
    pub body: ErrorFixtureBody,
}

/// An expected error-vector file.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ErrorVectors {
    /// Vector family name.
    pub source: String,
    /// One case per normalized error.
    pub cases: Vec<ErrorCase>,
}

/// One expected normalized error case.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ErrorCase {
    /// Case name.
    pub name: String,
    /// Provider ID, when the vector file carries it.
    #[serde(default)]
    pub provider_id: Option<String>,
    /// Transport kind, such as `http`, `stream`, or `synthetic`.
    #[serde(default)]
    pub kind: Option<String>,
    /// Raw fixture path relative to `contracts/fixtures/`, when present.
    #[serde(default)]
    pub fixture: Option<String>,
    /// Whether the case is constructed inline without a raw fixture.
    #[serde(default)]
    pub synthetic: Option<bool>,
    /// Normalized HTTP status, or `null` when not applicable.
    #[serde(default)]
    pub status: Option<u16>,
    /// HTTP status of the in-stream response, when present.
    #[serde(default)]
    pub http_status: Option<u16>,
    /// Expected normalized error.
    pub error: ExpectedChatError,
}
