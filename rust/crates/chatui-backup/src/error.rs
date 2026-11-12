//! Typed errors for the `CHATUI1\n` backup envelope.
//!
//! The stable [`EnvelopeError::code`] strings match the cross-client failure
//! codes frozen in `docs/operations/M5/M5-decisions.md` (`DEC-M5-15`). No
//! variant carries a passphrase, derived key, salt, IV, or ciphertext.

use thiserror::Error;

/// Errors from the backup envelope codec.
#[derive(Clone, Debug, Eq, Error, PartialEq)]
pub enum EnvelopeError {
    /// The envelope does not start with the `CHATUI1\n` magic bytes.
    #[error("envelope magic does not match")]
    BadMagic,
    /// The header JSON is invalid, a field is missing, or a field is out of
    /// range.
    #[error("envelope header is invalid")]
    BadHeader,
    /// The passphrase is wrong or the IV, ciphertext, tag, or AAD was modified.
    #[error("envelope authentication failed")]
    AuthenticationFailed,
    /// The envelope `v` or `formatVersion` is not supported by this build.
    #[error("unsupported envelope version")]
    UnsupportedVersion,
    /// The envelope `alg` is not supported by this build.
    #[error("unsupported envelope algorithm")]
    UnsupportedAlg,
    /// The envelope `kdf` is not supported by this build.
    #[error("unsupported envelope key-derivation function")]
    UnsupportedKdf,
    /// The magic, header length, or minimum envelope size is invalid.
    #[error("malformed envelope")]
    MalformedEnvelope,
    /// A conversation payload did not decompress as gzip.
    #[error("conversation payload is not valid gzip")]
    DecompressionFailed,
    /// Compression was requested for an object type that must not be
    /// compressed, such as an attachment.
    #[error("compression is not allowed for this object type")]
    CompressionNotAllowed,
    /// Key derivation failed.
    #[error("key derivation failed")]
    Kdf,
    /// Encryption failed.
    #[error("encryption failed")]
    Encryption,
}

impl EnvelopeError {
    /// Stable cross-client failure code for this error.
    pub fn code(&self) -> &'static str {
        match self {
            Self::BadMagic | Self::MalformedEnvelope => "malformed-envelope",
            Self::BadHeader | Self::CompressionNotAllowed => "invalid-header",
            Self::AuthenticationFailed => "authentication-failed",
            Self::UnsupportedVersion => "unsupported-version",
            Self::UnsupportedAlg => "unsupported-alg",
            Self::UnsupportedKdf => "unsupported-kdf",
            Self::DecompressionFailed => "decompression-failed",
            Self::Kdf | Self::Encryption => "internal",
        }
    }
}

/// Errors from manifest and conversation payload parsing.
#[derive(Clone, Debug, Eq, Error, PartialEq)]
pub enum BackupDataError {
    /// The plaintext is not valid JSON.
    #[error("backup data is not valid JSON: {0}")]
    Json(String),
    /// The plaintext does not satisfy the shared schema.
    #[error("backup data does not satisfy the schema: {0}")]
    Schema(String),
    /// The payload format version is not supported by this build.
    #[error("unsupported backup format version")]
    UnsupportedVersion,
    /// A value cannot be represented in the backup format.
    #[error("invalid backup data: {0}")]
    Invalid(String),
}
