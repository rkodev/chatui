//! Signed auto-update manifests, channel selection, and stream-safe apply.
//!
//! The updater fetches a channel manifest over HTTPS, verifies an Ed25519
//! signature with a pinned public key before parsing the payload, checks the
//! artifact checksum, rejects downgrades and cross-channel manifests, and
//! requires explicit confirmation when a stream is active.
//!
//! The manifest envelope is:
//!
//! ```json
//! {
//!   "schemaVersion": 1,
//!   "payload": "<base64 of the UTF-8 payload JSON>",
//!   "signature": "<base64 Ed25519 signature over the payload bytes>"
//! }
//! ```
//!
//! The payload is parsed only after the signature verifies. The signature is
//! over the exact payload bytes, so the signer must sign the bytes it publishes.
//! The schema lives in `rust/dist/manifests/schema.json`; the signing process is
//! documented in `docs/operations/M3.5/update-signing.md`.

use std::cell::RefCell;
use std::path::{Path, PathBuf};

use base64::Engine;
use base64::engine::general_purpose::STANDARD as BASE64;
use ed25519_dalek::{Signature, Verifier, VerifyingKey};
use semver::Version;
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use url::Url;

/// Current manifest schema version.
pub const MANIFEST_SCHEMA_VERSION: u32 = 1;
/// Maximum accepted manifest envelope size.
pub const MAX_MANIFEST_BYTES: usize = 64 * 1024;
/// Maximum accepted artifact size declared by a manifest (1 GiB).
pub const MAX_ARTIFACT_BYTES: u64 = 1024 * 1024 * 1024;

/// Pinned stable-channel public key (base64, 32 raw Ed25519 bytes).
///
/// This is a development key generated for this task. The release owner must
/// replace it with the production public key before shipping; the private key
/// lives only in CI secrets. See `docs/operations/M3.5/update-signing.md`.
pub const PINNED_STABLE_PUBLIC_KEY: &str = "m0bukM2y57EI9oWTbTuXZAS1v8oNR9PBAGZky9UPXyM=";
/// Pinned beta-channel public key (base64, 32 raw Ed25519 bytes).
pub const PINNED_BETA_PUBLIC_KEY: &str = "vPDV94Sv93/TPTbkXIqombLzZimHGf6s8Cbzt35rMtI=";

/// Default stable manifest URL.
pub const DEFAULT_STABLE_MANIFEST_URL: &str =
    "https://github.com/rkodev/chatui/releases/latest/download/update-stable.json";
/// Default beta manifest URL.
pub const DEFAULT_BETA_MANIFEST_URL: &str =
    "https://github.com/rkodev/chatui/releases/download/beta/update-beta.json";

/// Update channel.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum Channel {
    /// Stable releases.
    Stable,
    /// Beta releases.
    Beta,
}

impl Channel {
    /// Stable string form used in manifests and URLs.
    pub const fn as_str(self) -> &'static str {
        match self {
            Self::Stable => "stable",
            Self::Beta => "beta",
        }
    }
}

impl std::fmt::Display for Channel {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        formatter.write_str(self.as_str())
    }
}

/// Target operating system.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum TargetOs {
    /// Linux.
    Linux,
    /// macOS.
    Macos,
    /// Windows.
    Windows,
}

/// Target CPU architecture.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum TargetArch {
    /// 64-bit x86.
    X86_64,
    /// 64-bit ARM.
    Aarch64,
}

/// Target OS and architecture pair.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Target {
    /// Operating system.
    pub os: TargetOs,
    /// CPU architecture.
    pub arch: TargetArch,
}

impl Target {
    /// The current build target, or `None` on an unsupported platform.
    pub fn current() -> Option<Self> {
        let os = if cfg!(target_os = "linux") {
            TargetOs::Linux
        } else if cfg!(target_os = "macos") {
            TargetOs::Macos
        } else if cfg!(target_os = "windows") {
            TargetOs::Windows
        } else {
            return None;
        };
        let arch = if cfg!(target_arch = "x86_64") {
            TargetArch::X86_64
        } else if cfg!(target_arch = "aarch64") {
            TargetArch::Aarch64
        } else {
            return None;
        };
        Some(Self { os, arch })
    }
}

impl std::fmt::Display for Target {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        let os = match self.os {
            TargetOs::Linux => "linux",
            TargetOs::Macos => "macos",
            TargetOs::Windows => "windows",
        };
        let arch = match self.arch {
            TargetArch::X86_64 => "x86_64",
            TargetArch::Aarch64 => "aarch64",
        };
        write!(formatter, "{os}/{arch}")
    }
}

/// Artifact reference inside a signed manifest payload.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Artifact {
    /// HTTPS download URL.
    pub url: String,
    /// Lowercase or uppercase SHA-256 hex digest.
    pub sha256: String,
    /// Artifact size in bytes.
    pub size: u64,
}

/// Signed manifest payload.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Manifest {
    /// Payload schema version.
    pub schema_version: u32,
    /// Offered application version.
    pub app_version: String,
    /// Release channel.
    pub channel: Channel,
    /// Target OS and architecture.
    pub target: Target,
    /// Artifact URL, checksum, and size.
    pub artifact: Artifact,
    /// Minimum installed version that may apply this update.
    pub minimum_supported_version: Option<String>,
    /// Publication timestamp, informational only.
    pub published_at: Option<String>,
}

/// Manifest envelope. Only this minimal shape is parsed before verification.
#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
struct Envelope {
    schema_version: u32,
    payload: String,
    signature: String,
}

/// Persisted channel preference.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct StoredChannel {
    channel: Channel,
}

/// Update error. Every failure is fail-closed: no artifact is applied.
#[derive(Debug)]
pub enum UpdateError {
    /// A manifest or artifact URL is not HTTPS.
    InsecureUrl,
    /// Network or HTTP failure.
    Fetch(String),
    /// Manifest exceeded [`MAX_MANIFEST_BYTES`].
    ManifestTooLarge,
    /// Envelope JSON was malformed.
    MalformedEnvelope(serde_json::Error),
    /// Envelope schema version is not supported.
    UnsupportedSchema(u32),
    /// Payload JSON was malformed after signature verification.
    MalformedPayload(serde_json::Error),
    /// Signature did not verify with any pinned key.
    InvalidSignature,
    /// A pinned public key is not a valid Ed25519 key.
    InvalidPublicKey,
    /// Manifest channel does not match the requested or signing channel.
    CrossChannel {
        /// Expected channel.
        expected: Channel,
        /// Actual channel.
        actual: Channel,
    },
    /// Manifest target does not match this build.
    WrongTarget {
        /// Expected target.
        expected: Target,
        /// Actual target.
        actual: Target,
    },
    /// Offered version is older than the installed version.
    Downgrade {
        /// Installed version.
        current: Version,
        /// Offered version.
        offered: Version,
    },
    /// Installed version is below the manifest minimum.
    Incompatible {
        /// Installed version.
        current: Version,
        /// Minimum supported version.
        minimum: Version,
    },
    /// A version string was not valid semver.
    InvalidVersion(String),
    /// Artifact URL was malformed or not HTTPS.
    InvalidArtifactUrl,
    /// Artifact checksum was not a 64-character hex digest.
    InvalidChecksum,
    /// Declared artifact size exceeded [`MAX_ARTIFACT_BYTES`].
    ArtifactTooLarge(u64),
    /// Artifact bytes did not match the signed size.
    ArtifactSizeMismatch {
        /// Signed size.
        expected: u64,
        /// Actual size.
        actual: u64,
    },
    /// Artifact bytes did not match the signed checksum.
    ChecksumMismatch {
        /// Signed checksum.
        expected: String,
        /// Actual checksum.
        actual: String,
    },
    /// A stream is active and the user has not confirmed the update.
    ConfirmationRequired,
    /// This build target is not supported by the updater.
    UnsupportedTarget,
    /// Channel-store filesystem error.
    Io(std::io::Error),
    /// Channel-store JSON error.
    Json(serde_json::Error),
    /// Base64 decoding error.
    Base64(base64::DecodeError),
}

impl std::fmt::Display for UpdateError {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::InsecureUrl => formatter.write_str("update URLs must use HTTPS"),
            Self::Fetch(message) => write!(formatter, "update fetch failed: {message}"),
            Self::ManifestTooLarge => formatter.write_str("update manifest is too large"),
            Self::MalformedEnvelope(error) => {
                write!(formatter, "update manifest envelope is malformed: {error}")
            }
            Self::UnsupportedSchema(version) => {
                write!(
                    formatter,
                    "unsupported update manifest schema version {version}"
                )
            }
            Self::MalformedPayload(error) => {
                write!(formatter, "update manifest payload is malformed: {error}")
            }
            Self::InvalidSignature => formatter.write_str("update manifest signature is invalid"),
            Self::InvalidPublicKey => formatter.write_str("pinned update public key is invalid"),
            Self::CrossChannel { expected, actual } => write!(
                formatter,
                "update channel mismatch: expected {expected}, got {actual}"
            ),
            Self::WrongTarget { expected, actual } => write!(
                formatter,
                "update target mismatch: expected {expected}, got {actual}"
            ),
            Self::Downgrade { current, offered } => {
                write!(formatter, "refusing downgrade from {current} to {offered}")
            }
            Self::Incompatible { current, minimum } => write!(
                formatter,
                "installed version {current} is below the update minimum {minimum}"
            ),
            Self::InvalidVersion(version) => {
                write!(formatter, "invalid update version {version}")
            }
            Self::InvalidArtifactUrl => {
                formatter.write_str("update artifact URL must be a valid HTTPS URL")
            }
            Self::InvalidChecksum => {
                formatter.write_str("update artifact checksum must be a SHA-256 hex digest")
            }
            Self::ArtifactTooLarge(size) => {
                write!(formatter, "update artifact is too large: {size} bytes")
            }
            Self::ArtifactSizeMismatch { expected, actual } => write!(
                formatter,
                "update artifact size mismatch: expected {expected}, got {actual}"
            ),
            Self::ChecksumMismatch { expected, actual } => write!(
                formatter,
                "update artifact checksum mismatch: expected {expected}, got {actual}"
            ),
            Self::ConfirmationRequired => {
                formatter.write_str("update requires confirmation during an active stream")
            }
            Self::UnsupportedTarget => {
                formatter.write_str("this build target is not supported by the updater")
            }
            Self::Io(error) => write!(formatter, "update channel I/O error: {error}"),
            Self::Json(error) => write!(formatter, "update channel JSON error: {error}"),
            Self::Base64(error) => write!(formatter, "update manifest base64 error: {error}"),
        }
    }
}

impl std::error::Error for UpdateError {
    fn source(&self) -> Option<&(dyn std::error::Error + 'static)> {
        match self {
            Self::MalformedEnvelope(error) | Self::MalformedPayload(error) | Self::Json(error) => {
                Some(error)
            }
            Self::Io(error) => Some(error),
            Self::Base64(error) => Some(error),
            _ => None,
        }
    }
}

impl From<std::io::Error> for UpdateError {
    fn from(error: std::io::Error) -> Self {
        Self::Io(error)
    }
}

impl From<serde_json::Error> for UpdateError {
    fn from(error: serde_json::Error) -> Self {
        Self::Json(error)
    }
}

impl From<base64::DecodeError> for UpdateError {
    fn from(error: base64::DecodeError) -> Self {
        Self::Base64(error)
    }
}

/// Parse a base64-encoded raw Ed25519 public key.
pub fn parse_verifying_key(encoded: &str) -> Result<VerifyingKey, UpdateError> {
    let bytes = BASE64.decode(encoded)?;
    let bytes: [u8; 32] = bytes
        .try_into()
        .map_err(|_| UpdateError::InvalidPublicKey)?;
    VerifyingKey::from_bytes(&bytes).map_err(|_| UpdateError::InvalidPublicKey)
}

/// The pinned public keys for both channels.
pub fn pinned_keys() -> Result<Vec<(Channel, VerifyingKey)>, UpdateError> {
    Ok(vec![
        (
            Channel::Stable,
            parse_verifying_key(PINNED_STABLE_PUBLIC_KEY)?,
        ),
        (Channel::Beta, parse_verifying_key(PINNED_BETA_PUBLIC_KEY)?),
    ])
}

/// Manifest URLs for each channel.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ManifestUrls {
    /// Stable manifest URL.
    pub stable: String,
    /// Beta manifest URL.
    pub beta: String,
}

impl Default for ManifestUrls {
    fn default() -> Self {
        Self {
            stable: DEFAULT_STABLE_MANIFEST_URL.to_owned(),
            beta: DEFAULT_BETA_MANIFEST_URL.to_owned(),
        }
    }
}

impl ManifestUrls {
    /// The URL for `channel`.
    pub fn for_channel(&self, channel: Channel) -> &str {
        match channel {
            Channel::Stable => &self.stable,
            Channel::Beta => &self.beta,
        }
    }
}

/// A manifest fetch backend.
pub trait ManifestSource {
    /// Fetch the manifest bytes at `url`.
    fn fetch(&self, url: &str) -> Result<Vec<u8>, UpdateError>;
}

/// HTTPS manifest fetcher.
#[derive(Debug, Clone)]
pub struct HttpsManifestSource {
    client: reqwest::blocking::Client,
}

impl HttpsManifestSource {
    /// Create a fetcher with a bounded timeout.
    pub fn new() -> Self {
        let client = reqwest::blocking::Client::builder()
            .timeout(std::time::Duration::from_secs(30))
            .build()
            .unwrap_or_default();
        Self { client }
    }
}

impl Default for HttpsManifestSource {
    fn default() -> Self {
        Self::new()
    }
}

impl ManifestSource for HttpsManifestSource {
    fn fetch(&self, url: &str) -> Result<Vec<u8>, UpdateError> {
        let parsed = Url::parse(url).map_err(|_| UpdateError::InsecureUrl)?;
        if parsed.scheme() != "https" {
            return Err(UpdateError::InsecureUrl);
        }
        let response = self
            .client
            .get(url)
            .send()
            .map_err(|error| UpdateError::Fetch(error.to_string()))?;
        if !response.status().is_success() {
            return Err(UpdateError::Fetch(format!("HTTP {}", response.status())));
        }
        if let Some(length) = response.content_length()
            && length > MAX_MANIFEST_BYTES as u64
        {
            return Err(UpdateError::ManifestTooLarge);
        }
        let bytes = response
            .bytes()
            .map_err(|error| UpdateError::Fetch(error.to_string()))?;
        if bytes.len() > MAX_MANIFEST_BYTES {
            return Err(UpdateError::ManifestTooLarge);
        }
        Ok(bytes.to_vec())
    }
}

/// A channel-preference persistence backend.
pub trait ChannelStore {
    /// Load the saved channel, or `None` when nothing has been saved.
    fn load(&self) -> Result<Option<Channel>, UpdateError>;
    /// Save the channel.
    fn save(&self, channel: Channel) -> Result<(), UpdateError>;
}

/// A JSON file channel store.
#[derive(Debug, Clone)]
pub struct FileChannelStore {
    path: PathBuf,
}

impl FileChannelStore {
    /// Create a store at `path`.
    pub fn new(path: impl Into<PathBuf>) -> Self {
        Self { path: path.into() }
    }

    /// The default per-user config path.
    pub fn default_path() -> PathBuf {
        config_dir().join("chatui").join("update-channel.json")
    }

    /// The file path.
    pub fn path(&self) -> &Path {
        &self.path
    }
}

impl ChannelStore for FileChannelStore {
    fn load(&self) -> Result<Option<Channel>, UpdateError> {
        match std::fs::read_to_string(&self.path) {
            Ok(text) => {
                let stored: StoredChannel = serde_json::from_str(&text)?;
                Ok(Some(stored.channel))
            }
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => Ok(None),
            Err(error) => Err(UpdateError::Io(error)),
        }
    }

    fn save(&self, channel: Channel) -> Result<(), UpdateError> {
        if let Some(parent) = self.path.parent() {
            std::fs::create_dir_all(parent)?;
        }
        let text = serde_json::to_string_pretty(&StoredChannel { channel })?;
        std::fs::write(&self.path, text)?;
        Ok(())
    }
}

/// An in-memory channel store for tests and headless runs.
#[derive(Debug, Default)]
pub struct MemoryChannelStore {
    channel: RefCell<Option<Channel>>,
}

impl MemoryChannelStore {
    /// Create an empty store.
    pub fn new() -> Self {
        Self::default()
    }

    /// Create a store with an initial channel.
    pub fn with_channel(channel: Channel) -> Self {
        Self {
            channel: RefCell::new(Some(channel)),
        }
    }

    /// The current stored channel.
    pub fn channel(&self) -> Option<Channel> {
        *self.channel.borrow()
    }
}

impl ChannelStore for MemoryChannelStore {
    fn load(&self) -> Result<Option<Channel>, UpdateError> {
        Ok(self.channel())
    }

    fn save(&self, channel: Channel) -> Result<(), UpdateError> {
        *self.channel.borrow_mut() = Some(channel);
        Ok(())
    }
}

/// A verified update that is ready to apply.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct UpdatePlan {
    manifest: Manifest,
    requires_confirmation: bool,
}

impl UpdatePlan {
    /// The signed manifest.
    pub fn manifest(&self) -> &Manifest {
        &self.manifest
    }

    /// Whether the user must confirm before applying.
    pub fn requires_confirmation(&self) -> bool {
        self.requires_confirmation
    }
}

/// Result of an update check.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum UpdateCheck {
    /// The installed version is current.
    UpToDate,
    /// An update is available and may be applied.
    Available(UpdatePlan),
    /// An update is available but a stream is active; ask first.
    ConfirmationRequired(UpdatePlan),
}

/// Artifact bytes that matched the signed checksum.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct VerifiedArtifact {
    /// Verified version.
    pub version: Version,
    /// Verified channel.
    pub channel: Channel,
    /// Verified artifact bytes.
    pub bytes: Vec<u8>,
}

/// Signed-manifest updater.
pub struct Updater {
    source: Box<dyn ManifestSource>,
    channel_store: Box<dyn ChannelStore>,
    pinned_keys: Vec<(Channel, VerifyingKey)>,
    urls: ManifestUrls,
    current_version: Version,
    target: Option<Target>,
}

impl Updater {
    /// Create an updater with the pinned production keys and default URLs.
    pub fn new(
        source: Box<dyn ManifestSource>,
        channel_store: Box<dyn ChannelStore>,
    ) -> Result<Self, UpdateError> {
        let current_version = Version::parse(env!("CARGO_PKG_VERSION"))
            .map_err(|_| UpdateError::InvalidVersion(env!("CARGO_PKG_VERSION").to_owned()))?;
        Ok(Self {
            source,
            channel_store,
            pinned_keys: pinned_keys()?,
            urls: ManifestUrls::default(),
            current_version,
            target: Target::current(),
        })
    }

    /// Create an updater with explicit parts. Used by tests and headless tools.
    pub fn with_parts(
        source: Box<dyn ManifestSource>,
        channel_store: Box<dyn ChannelStore>,
        pinned_keys: Vec<(Channel, VerifyingKey)>,
        urls: ManifestUrls,
        current_version: Version,
        target: Target,
    ) -> Self {
        Self {
            source,
            channel_store,
            pinned_keys,
            urls,
            current_version,
            target: Some(target),
        }
    }

    /// The persisted channel, defaulting to stable.
    pub fn channel(&self) -> Result<Channel, UpdateError> {
        Ok(self.channel_store.load()?.unwrap_or(Channel::Stable))
    }

    /// Persist the channel preference.
    pub fn set_channel(&self, channel: Channel) -> Result<(), UpdateError> {
        self.channel_store.save(channel)
    }

    /// Fetch, verify, and evaluate the current channel manifest.
    ///
    /// When `streaming` is true, an available update is returned as
    /// [`UpdateCheck::ConfirmationRequired`] and cannot be applied without
    /// explicit confirmation.
    pub fn check(&self, streaming: bool) -> Result<UpdateCheck, UpdateError> {
        let channel = self.channel()?;
        let url = self.urls.for_channel(channel);
        let bytes = self.source.fetch(url)?;
        let manifest = self.verify_and_parse(&bytes, channel)?;
        self.plan(manifest, streaming)
    }

    /// Verify the artifact size and checksum and return the bytes ready for the
    /// installer.
    ///
    /// A plan produced during an active stream requires `confirmed == true`.
    pub fn apply(
        &self,
        plan: &UpdatePlan,
        artifact: &[u8],
        confirmed: bool,
    ) -> Result<VerifiedArtifact, UpdateError> {
        if plan.requires_confirmation && !confirmed {
            return Err(UpdateError::ConfirmationRequired);
        }
        let expected_size = plan.manifest.artifact.size;
        let actual_size = artifact.len() as u64;
        if actual_size != expected_size {
            return Err(UpdateError::ArtifactSizeMismatch {
                expected: expected_size,
                actual: actual_size,
            });
        }
        let expected = plan.manifest.artifact.sha256.to_ascii_lowercase();
        let actual = sha256_hex(artifact);
        if actual != expected {
            return Err(UpdateError::ChecksumMismatch { expected, actual });
        }
        let version = Version::parse(&plan.manifest.app_version)
            .map_err(|_| UpdateError::InvalidVersion(plan.manifest.app_version.clone()))?;
        Ok(VerifiedArtifact {
            version,
            channel: plan.manifest.channel,
            bytes: artifact.to_vec(),
        })
    }

    fn verify_and_parse(
        &self,
        bytes: &[u8],
        expected_channel: Channel,
    ) -> Result<Manifest, UpdateError> {
        if bytes.len() > MAX_MANIFEST_BYTES {
            return Err(UpdateError::ManifestTooLarge);
        }
        let envelope: Envelope =
            serde_json::from_slice(bytes).map_err(UpdateError::MalformedEnvelope)?;
        if envelope.schema_version != MANIFEST_SCHEMA_VERSION {
            return Err(UpdateError::UnsupportedSchema(envelope.schema_version));
        }

        let payload_bytes = BASE64.decode(&envelope.payload)?;
        let signature_bytes = BASE64.decode(&envelope.signature)?;
        let signature =
            Signature::from_slice(&signature_bytes).map_err(|_| UpdateError::InvalidSignature)?;

        let mut verified_channel = None;
        for (channel, key) in &self.pinned_keys {
            if key.verify(&payload_bytes, &signature).is_ok() {
                verified_channel = Some(*channel);
                break;
            }
        }
        let verified_channel = verified_channel.ok_or(UpdateError::InvalidSignature)?;

        let manifest: Manifest =
            serde_json::from_slice(&payload_bytes).map_err(UpdateError::MalformedPayload)?;
        if manifest.schema_version != envelope.schema_version {
            return Err(UpdateError::UnsupportedSchema(manifest.schema_version));
        }
        if manifest.channel != expected_channel {
            return Err(UpdateError::CrossChannel {
                expected: expected_channel,
                actual: manifest.channel,
            });
        }
        if verified_channel != manifest.channel {
            return Err(UpdateError::CrossChannel {
                expected: verified_channel,
                actual: manifest.channel,
            });
        }
        Ok(manifest)
    }

    fn plan(&self, manifest: Manifest, streaming: bool) -> Result<UpdateCheck, UpdateError> {
        let target = self.target.ok_or(UpdateError::UnsupportedTarget)?;
        if manifest.target != target {
            return Err(UpdateError::WrongTarget {
                expected: target,
                actual: manifest.target,
            });
        }

        let offered = Version::parse(&manifest.app_version)
            .map_err(|_| UpdateError::InvalidVersion(manifest.app_version.clone()))?;
        if offered < self.current_version {
            return Err(UpdateError::Downgrade {
                current: self.current_version.clone(),
                offered,
            });
        }
        if offered == self.current_version {
            return Ok(UpdateCheck::UpToDate);
        }

        if let Some(minimum) = &manifest.minimum_supported_version {
            let minimum = Version::parse(minimum)
                .map_err(|_| UpdateError::InvalidVersion(minimum.clone()))?;
            if self.current_version < minimum {
                return Err(UpdateError::Incompatible {
                    current: self.current_version.clone(),
                    minimum,
                });
            }
        }

        let artifact_url =
            Url::parse(&manifest.artifact.url).map_err(|_| UpdateError::InvalidArtifactUrl)?;
        if artifact_url.scheme() != "https" {
            return Err(UpdateError::InvalidArtifactUrl);
        }
        if manifest.artifact.size > MAX_ARTIFACT_BYTES {
            return Err(UpdateError::ArtifactTooLarge(manifest.artifact.size));
        }
        if manifest.artifact.sha256.len() != 64
            || !manifest
                .artifact
                .sha256
                .chars()
                .all(|character| character.is_ascii_hexdigit())
        {
            return Err(UpdateError::InvalidChecksum);
        }

        let plan = UpdatePlan {
            manifest,
            requires_confirmation: streaming,
        };
        if streaming {
            Ok(UpdateCheck::ConfirmationRequired(plan))
        } else {
            Ok(UpdateCheck::Available(plan))
        }
    }
}

/// Lowercase SHA-256 hex digest of `bytes`.
pub fn sha256_hex(bytes: &[u8]) -> String {
    hex::encode(Sha256::digest(bytes))
}

fn config_dir() -> PathBuf {
    if let Ok(path) = std::env::var("XDG_CONFIG_HOME")
        && !path.is_empty()
    {
        return PathBuf::from(path);
    }
    if let Ok(home) = std::env::var("HOME")
        && !home.is_empty()
    {
        return PathBuf::from(home).join(".config");
    }
    std::env::temp_dir()
}

#[cfg(test)]
mod tests {
    use std::collections::HashMap;

    use ed25519_dalek::{Signer, SigningKey};

    use super::*;

    const STABLE_URL: &str = "https://example.test/stable.json";
    const BETA_URL: &str = "https://example.test/beta.json";

    fn test_key(seed: u8) -> SigningKey {
        SigningKey::from_bytes(&[seed; 32])
    }

    fn target() -> Target {
        Target {
            os: TargetOs::Linux,
            arch: TargetArch::X86_64,
        }
    }

    fn manifest(channel: Channel, version: &str) -> Manifest {
        Manifest {
            schema_version: MANIFEST_SCHEMA_VERSION,
            app_version: version.to_owned(),
            channel,
            target: target(),
            artifact: Artifact {
                url: "https://example.test/chatui.tar.gz".to_owned(),
                sha256: sha256_hex(b"artifact"),
                size: 8,
            },
            minimum_supported_version: Some("0.1.0".to_owned()),
            published_at: Some("2026-11-08T00:00:00Z".to_owned()),
        }
    }

    fn sign_envelope(key: &SigningKey, payload: &Manifest) -> Vec<u8> {
        let payload_bytes = serde_json::to_vec(payload).expect("serialize payload");
        let signature = key.sign(&payload_bytes);
        let envelope = serde_json::json!({
            "schemaVersion": MANIFEST_SCHEMA_VERSION,
            "payload": BASE64.encode(&payload_bytes),
            "signature": BASE64.encode(signature.to_bytes()),
        });
        serde_json::to_vec(&envelope).expect("serialize envelope")
    }

    #[derive(Default)]
    struct MemorySource {
        responses: RefCell<HashMap<String, Vec<u8>>>,
    }

    impl MemorySource {
        fn new() -> Self {
            Self::default()
        }

        fn insert(&self, url: &str, bytes: Vec<u8>) {
            self.responses.borrow_mut().insert(url.to_owned(), bytes);
        }
    }

    impl ManifestSource for MemorySource {
        fn fetch(&self, url: &str) -> Result<Vec<u8>, UpdateError> {
            self.responses
                .borrow()
                .get(url)
                .cloned()
                .ok_or_else(|| UpdateError::Fetch(format!("no response for {url}")))
        }
    }

    fn updater_with(source: MemorySource, channel: Channel) -> Updater {
        Updater::with_parts(
            Box::new(source),
            Box::new(MemoryChannelStore::with_channel(channel)),
            vec![
                (Channel::Stable, test_key(1).verifying_key()),
                (Channel::Beta, test_key(2).verifying_key()),
            ],
            ManifestUrls {
                stable: STABLE_URL.to_owned(),
                beta: BETA_URL.to_owned(),
            },
            Version::parse("0.1.0").expect("version"),
            target(),
        )
    }

    fn available_plan(updater: &Updater) -> UpdatePlan {
        match updater.check(false).expect("check") {
            UpdateCheck::Available(plan) => plan,
            other => panic!("expected an available update, got {other:?}"),
        }
    }

    #[test]
    fn pinned_keys_parse() {
        let keys = pinned_keys().expect("pinned keys");
        assert_eq!(keys.len(), 2);
        assert_eq!(keys[0].0, Channel::Stable);
        assert_eq!(keys[1].0, Channel::Beta);
    }

    #[test]
    fn valid_manifest_verifies_and_parses() {
        let source = MemorySource::new();
        source.insert(
            STABLE_URL,
            sign_envelope(&test_key(1), &manifest(Channel::Stable, "0.2.0")),
        );
        let updater = updater_with(source, Channel::Stable);

        let plan = available_plan(&updater);
        assert_eq!(plan.manifest().app_version, "0.2.0");
        assert_eq!(plan.manifest().channel, Channel::Stable);
        assert!(!plan.requires_confirmation());
    }

    #[test]
    fn beta_channel_uses_beta_url() {
        let source = MemorySource::new();
        source.insert(
            BETA_URL,
            sign_envelope(&test_key(2), &manifest(Channel::Beta, "0.3.0-beta.1")),
        );
        let updater = updater_with(source, Channel::Beta);

        let plan = available_plan(&updater);
        assert_eq!(plan.manifest().channel, Channel::Beta);
    }

    #[test]
    fn tampered_payload_fails_signature() {
        let source = MemorySource::new();
        let signed = sign_envelope(&test_key(1), &manifest(Channel::Stable, "0.2.0"));
        let mut envelope: serde_json::Value = serde_json::from_slice(&signed).expect("envelope");
        let tampered =
            serde_json::to_vec(&manifest(Channel::Stable, "9.9.9")).expect("tampered payload");
        envelope["payload"] = serde_json::Value::String(BASE64.encode(tampered));
        source.insert(
            STABLE_URL,
            serde_json::to_vec(&envelope).expect("tampered envelope"),
        );
        let updater = updater_with(source, Channel::Stable);

        assert!(matches!(
            updater.check(false),
            Err(UpdateError::InvalidSignature)
        ));
    }

    #[test]
    fn unsigned_manifest_fails_closed() {
        let source = MemorySource::new();
        let payload = serde_json::to_vec(&manifest(Channel::Stable, "0.2.0")).expect("payload");
        let envelope = serde_json::json!({
            "schemaVersion": MANIFEST_SCHEMA_VERSION,
            "payload": BASE64.encode(payload),
            "signature": "",
        });
        source.insert(STABLE_URL, serde_json::to_vec(&envelope).expect("envelope"));
        let updater = updater_with(source, Channel::Stable);

        assert!(matches!(
            updater.check(false),
            Err(UpdateError::InvalidSignature)
        ));
    }

    #[test]
    fn wrong_channel_signature_is_rejected() {
        let source = MemorySource::new();
        source.insert(
            STABLE_URL,
            sign_envelope(&test_key(1), &manifest(Channel::Beta, "0.2.0")),
        );
        let updater = updater_with(source, Channel::Stable);

        assert!(matches!(
            updater.check(false),
            Err(UpdateError::CrossChannel {
                expected: Channel::Stable,
                actual: Channel::Beta,
            })
        ));
    }

    #[test]
    fn cross_channel_key_is_rejected() {
        let source = MemorySource::new();
        source.insert(
            STABLE_URL,
            sign_envelope(&test_key(2), &manifest(Channel::Stable, "0.2.0")),
        );
        let updater = updater_with(source, Channel::Stable);

        assert!(matches!(
            updater.check(false),
            Err(UpdateError::CrossChannel {
                expected: Channel::Beta,
                actual: Channel::Stable,
            })
        ));
    }

    #[test]
    fn tampered_artifact_fails_checksum() {
        let source = MemorySource::new();
        source.insert(
            STABLE_URL,
            sign_envelope(&test_key(1), &manifest(Channel::Stable, "0.2.0")),
        );
        let updater = updater_with(source, Channel::Stable);
        let plan = available_plan(&updater);

        assert!(matches!(
            updater.apply(&plan, b"short", false),
            Err(UpdateError::ArtifactSizeMismatch { .. })
        ));
        assert!(matches!(
            updater.apply(&plan, b"tampered", false),
            Err(UpdateError::ChecksumMismatch { .. })
        ));
        let verified = updater
            .apply(&plan, b"artifact", false)
            .expect("verified artifact");
        assert_eq!(verified.version, Version::parse("0.2.0").expect("version"));
        assert_eq!(verified.channel, Channel::Stable);
    }

    #[test]
    fn downgrade_is_rejected() {
        let source = MemorySource::new();
        source.insert(
            STABLE_URL,
            sign_envelope(&test_key(1), &manifest(Channel::Stable, "0.0.9")),
        );
        let updater = updater_with(source, Channel::Stable);

        assert!(matches!(
            updater.check(false),
            Err(UpdateError::Downgrade { .. })
        ));
    }

    #[test]
    fn up_to_date_is_reported() {
        let source = MemorySource::new();
        source.insert(
            STABLE_URL,
            sign_envelope(&test_key(1), &manifest(Channel::Stable, "0.1.0")),
        );
        let updater = updater_with(source, Channel::Stable);

        assert_eq!(updater.check(false).expect("check"), UpdateCheck::UpToDate);
    }

    #[test]
    fn minimum_supported_version_is_enforced() {
        let source = MemorySource::new();
        let mut payload = manifest(Channel::Stable, "0.2.0");
        payload.minimum_supported_version = Some("0.2.0".to_owned());
        source.insert(STABLE_URL, sign_envelope(&test_key(1), &payload));
        let updater = updater_with(source, Channel::Stable);

        assert!(matches!(
            updater.check(false),
            Err(UpdateError::Incompatible { .. })
        ));
    }

    #[test]
    fn wrong_target_is_rejected() {
        let source = MemorySource::new();
        let mut payload = manifest(Channel::Stable, "0.2.0");
        payload.target = Target {
            os: TargetOs::Macos,
            arch: TargetArch::Aarch64,
        };
        source.insert(STABLE_URL, sign_envelope(&test_key(1), &payload));
        let updater = updater_with(source, Channel::Stable);

        assert!(matches!(
            updater.check(false),
            Err(UpdateError::WrongTarget { .. })
        ));
    }

    #[test]
    fn insecure_artifact_url_is_rejected() {
        let source = MemorySource::new();
        let mut payload = manifest(Channel::Stable, "0.2.0");
        payload.artifact.url = "http://example.test/chatui.tar.gz".to_owned();
        source.insert(STABLE_URL, sign_envelope(&test_key(1), &payload));
        let updater = updater_with(source, Channel::Stable);

        assert!(matches!(
            updater.check(false),
            Err(UpdateError::InvalidArtifactUrl)
        ));
    }

    #[test]
    fn invalid_checksum_is_rejected() {
        let source = MemorySource::new();
        let mut payload = manifest(Channel::Stable, "0.2.0");
        payload.artifact.sha256 = "not-a-checksum".to_owned();
        source.insert(STABLE_URL, sign_envelope(&test_key(1), &payload));
        let updater = updater_with(source, Channel::Stable);

        assert!(matches!(
            updater.check(false),
            Err(UpdateError::InvalidChecksum)
        ));
    }

    #[test]
    fn unsupported_schema_is_rejected() {
        let source = MemorySource::new();
        let payload = serde_json::to_vec(&manifest(Channel::Stable, "0.2.0")).expect("payload");
        let envelope = serde_json::json!({
            "schemaVersion": 2,
            "payload": BASE64.encode(payload),
            "signature": BASE64.encode(test_key(1).sign(b"payload").to_bytes()),
        });
        source.insert(STABLE_URL, serde_json::to_vec(&envelope).expect("envelope"));
        let updater = updater_with(source, Channel::Stable);

        assert!(matches!(
            updater.check(false),
            Err(UpdateError::UnsupportedSchema(2))
        ));
    }

    #[test]
    fn oversized_manifest_is_rejected() {
        let source = MemorySource::new();
        source.insert(STABLE_URL, vec![0; MAX_MANIFEST_BYTES + 1]);
        let updater = updater_with(source, Channel::Stable);

        assert!(matches!(
            updater.check(false),
            Err(UpdateError::ManifestTooLarge)
        ));
    }

    #[test]
    fn insecure_manifest_url_is_rejected_before_network() {
        let source = HttpsManifestSource::new();
        assert!(matches!(
            source.fetch("http://example.test/manifest.json"),
            Err(UpdateError::InsecureUrl)
        ));
    }

    #[test]
    fn stream_guard_requires_confirmation() {
        let source = MemorySource::new();
        source.insert(
            STABLE_URL,
            sign_envelope(&test_key(1), &manifest(Channel::Stable, "0.2.0")),
        );
        let updater = updater_with(source, Channel::Stable);

        let plan = match updater.check(true).expect("check") {
            UpdateCheck::ConfirmationRequired(plan) => plan,
            other => panic!("expected confirmation, got {other:?}"),
        };
        assert!(plan.requires_confirmation());
        assert!(matches!(
            updater.apply(&plan, b"artifact", false),
            Err(UpdateError::ConfirmationRequired)
        ));
        assert!(updater.apply(&plan, b"artifact", true).is_ok());
    }

    #[test]
    fn channel_setting_persists_in_memory() {
        let source = MemorySource::new();
        let updater = updater_with(source, Channel::Stable);
        assert_eq!(updater.channel().expect("channel"), Channel::Stable);

        updater.set_channel(Channel::Beta).expect("set channel");
        assert_eq!(updater.channel().expect("channel"), Channel::Beta);
    }

    #[test]
    fn file_channel_store_round_trips() {
        let directory = tempfile::tempdir().expect("tempdir");
        let store = FileChannelStore::new(directory.path().join("update-channel.json"));
        assert_eq!(store.load().expect("load empty"), None);

        store.save(Channel::Beta).expect("save");
        assert_eq!(store.load().expect("load"), Some(Channel::Beta));
    }

    #[test]
    fn example_manifests_verify_with_pinned_keys() {
        let stable = std::fs::read(example_path("example-stable.json")).expect("stable example");
        let beta = std::fs::read(example_path("example-beta.json")).expect("beta example");

        let stable_source = MemorySource::new();
        stable_source.insert(STABLE_URL, stable);
        let stable_updater = Updater::with_parts(
            Box::new(stable_source),
            Box::new(MemoryChannelStore::with_channel(Channel::Stable)),
            pinned_keys().expect("pinned keys"),
            ManifestUrls {
                stable: STABLE_URL.to_owned(),
                beta: BETA_URL.to_owned(),
            },
            Version::parse("0.1.0").expect("version"),
            target(),
        );
        assert!(matches!(
            stable_updater.check(false).expect("stable check"),
            UpdateCheck::Available(_)
        ));

        let beta_source = MemorySource::new();
        beta_source.insert(BETA_URL, beta);
        let beta_updater = Updater::with_parts(
            Box::new(beta_source),
            Box::new(MemoryChannelStore::with_channel(Channel::Beta)),
            pinned_keys().expect("pinned keys"),
            ManifestUrls {
                stable: STABLE_URL.to_owned(),
                beta: BETA_URL.to_owned(),
            },
            Version::parse("0.1.0").expect("version"),
            target(),
        );
        assert!(matches!(
            beta_updater.check(false).expect("beta check"),
            UpdateCheck::Available(_)
        ));
    }

    fn example_path(name: &str) -> PathBuf {
        PathBuf::from(env!("CARGO_MANIFEST_DIR"))
            .join("../../dist/manifests")
            .join(name)
    }
}
