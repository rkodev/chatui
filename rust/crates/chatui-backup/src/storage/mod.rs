//! Storage adapter boundary for encrypted backup objects.
//!
//! Adapters only ever receive opaque bytes. Callers must encrypt with the
//! envelope codec before calling [`StorageAdapter::put_object`]; no adapter
//! accepts a string body or exposes a plaintext upload path.

use async_trait::async_trait;
use thiserror::Error;

pub mod s3;
pub mod sigv4;

pub use s3::{S3StorageAdapter, S3StorageConfig};
pub use sigv4::{
    SIGV4_ALGORITHM, SIGV4_SERVICE, SIGV4_TERMINATOR, SigV4Credentials, SigV4Method,
    SigV4SignOptions, SignedRequest, build_canonical_request, build_string_to_sign,
    derive_signing_key, encode_rfc3986, format_amz_date, sha256_hex, sign_request,
};

/// Stable storage failure categories shared with the web and Android clients.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum StorageErrorCode {
    /// Credentials were rejected (HTTP 401).
    Auth,
    /// Credentials were accepted but the action is not allowed (HTTP 403).
    Permission,
    /// The object or bucket does not exist (HTTP 404).
    NotFound,
    /// A conditional write lost a race (HTTP 409 or 412).
    Conflict,
    /// The request never reached the server or the response was unreadable.
    Network,
    /// Any other server or protocol failure.
    Server,
}

impl StorageErrorCode {
    /// Stable lowercase code for logs and UI mapping.
    pub fn as_str(self) -> &'static str {
        match self {
            Self::Auth => "auth",
            Self::Permission => "permission",
            Self::NotFound => "not_found",
            Self::Conflict => "conflict",
            Self::Network => "network",
            Self::Server => "server",
        }
    }
}

/// Typed storage failure. Messages never contain credentials or object bytes.
#[derive(Clone, Debug, Eq, Error, PartialEq)]
pub enum StorageError {
    /// Credentials were rejected.
    #[error("authentication failed: {message}")]
    Auth {
        /// Safe, user-facing detail.
        message: String,
        /// HTTP status when the failure came from a response.
        status: Option<u16>,
    },
    /// The action is not permitted.
    #[error("permission denied: {message}")]
    Permission {
        /// Safe, user-facing detail.
        message: String,
        /// HTTP status when the failure came from a response.
        status: Option<u16>,
    },
    /// The object or bucket does not exist.
    #[error("object not found: {message}")]
    NotFound {
        /// Safe, user-facing detail.
        message: String,
        /// HTTP status when the failure came from a response.
        status: Option<u16>,
    },
    /// A conditional write lost a race.
    #[error("storage conflict: {message}")]
    Conflict {
        /// Safe, user-facing detail.
        message: String,
        /// HTTP status when the failure came from a response.
        status: Option<u16>,
    },
    /// The request never reached the server.
    #[error("network request failed: {message}")]
    Network {
        /// Safe, user-facing detail.
        message: String,
    },
    /// Any other server or protocol failure.
    #[error("storage server error: {message}")]
    Server {
        /// Safe, user-facing detail.
        message: String,
        /// HTTP status when the failure came from a response.
        status: Option<u16>,
    },
}

impl StorageError {
    /// Stable category for this failure.
    pub fn code(&self) -> StorageErrorCode {
        match self {
            Self::Auth { .. } => StorageErrorCode::Auth,
            Self::Permission { .. } => StorageErrorCode::Permission,
            Self::NotFound { .. } => StorageErrorCode::NotFound,
            Self::Conflict { .. } => StorageErrorCode::Conflict,
            Self::Network { .. } => StorageErrorCode::Network,
            Self::Server { .. } => StorageErrorCode::Server,
        }
    }

    /// HTTP status when the failure came from a response.
    pub fn status(&self) -> Option<u16> {
        match self {
            Self::Auth { status, .. }
            | Self::Permission { status, .. }
            | Self::NotFound { status, .. }
            | Self::Conflict { status, .. }
            | Self::Server { status, .. } => *status,
            Self::Network { .. } => None,
        }
    }

    /// Map an HTTP status to a typed error.
    pub fn from_status(status: u16, message: impl Into<String>) -> Self {
        let message = message.into();
        match status {
            401 => Self::Auth {
                message,
                status: Some(status),
            },
            403 => Self::Permission {
                message,
                status: Some(status),
            },
            404 => Self::NotFound {
                message,
                status: Some(status),
            },
            409 | 412 => Self::Conflict {
                message,
                status: Some(status),
            },
            _ => Self::Server {
                message,
                status: Some(status),
            },
        }
    }

    /// A request that never reached the server.
    pub fn network(message: impl Into<String>) -> Self {
        Self::Network {
            message: message.into(),
        }
    }

    /// A protocol or configuration failure without an HTTP status.
    pub fn server(message: impl Into<String>) -> Self {
        Self::Server {
            message: message.into(),
            status: None,
        }
    }
}

/// One object returned by a storage adapter.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct StorageObject {
    /// Logical object key relative to the adapter's configured prefix.
    pub key: String,
    /// Object bytes. `head_object` and `list_objects` return an empty vector.
    pub bytes: Vec<u8>,
    /// Raw `ETag` response header, including quotes when the provider sends them.
    pub etag: Option<String>,
    /// Raw `Last-Modified` response header.
    pub last_modified: Option<String>,
}

/// Options for a conditional object write.
#[derive(Clone, Debug, Default, Eq, PartialEq)]
pub struct PutObjectOptions {
    /// Content type. Defaults to `application/octet-stream`.
    pub content_type: Option<String>,
    /// Send `If-Match` to require the current ETag.
    pub if_match: Option<String>,
    /// Send `If-None-Match: *` (or another value) to require absence.
    pub if_none_match: Option<String>,
}

/// Options for a bucket listing.
#[derive(Clone, Debug, Default, Eq, PartialEq)]
pub struct ListObjectsOptions {
    /// Logical key prefix, relative to the adapter's configured prefix.
    pub prefix: Option<String>,
    /// Continuation token from a previous page.
    pub continuation_token: Option<String>,
    /// Maximum number of keys to return.
    pub max_keys: Option<u32>,
}

/// One page of a bucket listing.
#[derive(Clone, Debug, Default, Eq, PartialEq)]
pub struct ListObjectsResult {
    /// Objects in this page.
    pub objects: Vec<StorageObject>,
    /// Token for the next page, when the provider returned one.
    pub next_continuation_token: Option<String>,
}

/// Direct client-to-storage adapter. There is no server proxy.
///
/// Every write takes a byte slice so callers cannot pass a plaintext string.
/// The adapter does not encrypt; encryption belongs to the envelope codec.
#[async_trait]
pub trait StorageAdapter: Send + Sync {
    /// Verify credentials and bucket access without writing an object.
    async fn test_connection(&self) -> Result<(), StorageError>;

    /// Read an object.
    async fn get_object(&self, key: &str) -> Result<StorageObject, StorageError>;

    /// Write encrypted bytes, optionally with a conditional precondition.
    async fn put_object(
        &self,
        key: &str,
        encrypted_bytes: &[u8],
        options: PutObjectOptions,
    ) -> Result<StorageObject, StorageError>;

    /// Read object metadata without a body.
    async fn head_object(&self, key: &str) -> Result<StorageObject, StorageError>;

    /// Delete an object.
    async fn delete_object(&self, key: &str) -> Result<(), StorageError>;

    /// List objects under the configured prefix.
    async fn list_objects(
        &self,
        options: ListObjectsOptions,
    ) -> Result<ListObjectsResult, StorageError>;
}

#[cfg(test)]
mod tests;
