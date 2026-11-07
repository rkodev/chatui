//! Explicit encrypted-file fallback for secret storage.
//!
//! The fallback is never selected automatically. When the OS credential store
//! is unavailable, the caller must ask the user to choose between this backend
//! and [`crate::session::SessionStore`]. See [`select_store`] and
//! [`FallbackChoice`].
//!
//! # File format
//!
//! One file holds every secret managed by the store. The file name is fixed
//! (`secrets.enc` by convention) and never contains a secret ID or value.
//!
//! ```text
//! offset  size  field
//! 0       8     magic "CHUIENC1"
//! 8       1     format version (1)
//! 9       1     KDF id (1 = Argon2id)
//! 10      4     Argon2id memory cost in KiB (little endian)
//! 14      4     Argon2id time cost (little endian)
//! 18      4     Argon2id parallelism (little endian)
//! 22      16    random salt
//! 38      12    random AES-GCM nonce
//! 50      4     ciphertext length in bytes, including the tag (little endian)
//! 54      n     AES-256-GCM ciphertext and authentication tag
//! ```
//!
//! The header is authenticated as additional data, so changing the salt, the
//! nonce, the KDF parameters, or the length makes decryption fail closed. The
//! passphrase and derived key are never written to the file.
//!
//! # KDF parameters
//!
//! The default is Argon2id v1.3 with 19 MiB of memory, 2 iterations, and one
//! lane (OWASP's baseline recommendation). Parameters are stored in the header
//! so a future version can raise them without breaking existing files. The
//! reader rejects parameters outside a bounded range to avoid a
//! denial-of-service file.
//!
//! # Permissions
//!
//! On Unix the file is created with mode `0600` and the parent directory with
//! mode `0700`. Other platforms rely on the app-data directory's ACLs.

use std::collections::BTreeMap;
use std::fmt;
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex};

use aes_gcm::aead::{Aead, Payload};
use aes_gcm::{Aes256Gcm, KeyInit, Nonce};
use argon2::{Algorithm, Argon2, Params, Version};
use rand::RngCore;
use thiserror::Error;
use zeroize::Zeroizing;

use crate::session::SessionStore;
use crate::store::{SecretError, SecretId, SecretStore, SecretValue};

/// Magic bytes at the start of every encrypted secret file.
const MAGIC: &[u8; 8] = b"CHUIENC1";
/// Current on-disk format version.
const FORMAT_VERSION: u8 = 1;
/// Key-derivation function identifier for Argon2id.
const KDF_ARGON2ID: u8 = 1;
/// Random salt length in bytes.
const SALT_LEN: usize = 16;
/// AES-GCM nonce length in bytes.
const NONCE_LEN: usize = 12;
/// Derived key length in bytes.
const KEY_LEN: usize = 32;
/// AES-GCM authentication tag length in bytes.
const TAG_LEN: usize = 16;
/// Fixed header length in bytes.
const HEADER_LEN: usize = 8 + 1 + 1 + 4 + 4 + 4 + SALT_LEN + NONCE_LEN + 4;

/// Lower bound accepted for the Argon2id memory cost, in KiB.
const MIN_M_COST_KIB: u32 = 8 * 1024;
/// Upper bound accepted for the Argon2id memory cost, in KiB.
const MAX_M_COST_KIB: u32 = 1024 * 1024;
/// Upper bound accepted for the Argon2id time cost.
const MAX_T_COST: u32 = 16;
/// Upper bound accepted for the Argon2id parallelism.
const MAX_P_COST: u32 = 16;

/// User-facing explanation shown when the OS credential store is unavailable.
pub const ENCRYPTED_FALLBACK_EXPLANATION: &str = "The OS credential store is unavailable. Choose the encrypted-file fallback to store provider keys in a passphrase-protected file, or choose session-only mode to keep keys in memory until the app exits. Keys are never stored in plaintext.";

/// User-facing explanation for session-only mode.
pub const SESSION_ONLY_EXPLANATION: &str = "Session-only mode keeps provider keys in memory and clears them when the app exits or you lock secrets. Nothing is written to disk.";

/// File name used by the encrypted fallback under the app-data directory.
pub const ENCRYPTED_FILE_NAME: &str = "secrets.enc";

/// Return the default encrypted-file path under the platform app-data
/// directory.
///
/// The directory is not created here; [`EncryptedFileStore`] creates it with
/// restrictive permissions on the first write.
pub fn default_path() -> Result<PathBuf, SecretError> {
    directories::ProjectDirs::from("", "", "chatui")
        .map(|dirs| dirs.data_dir().join(ENCRYPTED_FILE_NAME))
        .ok_or(SecretError::Fallback(
            "the platform app-data directory is unavailable",
        ))
}

/// Argon2id parameters recorded in the file header.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct KdfParams {
    /// Memory cost in KiB.
    pub m_cost_kib: u32,
    /// Number of iterations.
    pub t_cost: u32,
    /// Degree of parallelism.
    pub p_cost: u32,
}

impl Default for KdfParams {
    fn default() -> Self {
        Self {
            m_cost_kib: 19_456,
            t_cost: 2,
            p_cost: 1,
        }
    }
}

impl KdfParams {
    fn validate(self) -> Result<Self, EncryptedFileError> {
        if !(MIN_M_COST_KIB..=MAX_M_COST_KIB).contains(&self.m_cost_kib)
            || !(1..=MAX_T_COST).contains(&self.t_cost)
            || !(1..=MAX_P_COST).contains(&self.p_cost)
        {
            return Err(EncryptedFileError::UnsupportedKdf);
        }
        Ok(self)
    }
}

/// Errors from the encrypted-file format.
///
/// No variant carries a passphrase, derived key, salt, nonce, or ciphertext.
#[derive(Debug, Error)]
pub enum EncryptedFileError {
    /// The file is too short, has a bad magic value, or has an inconsistent
    /// length.
    #[error("the encrypted secret file is corrupt")]
    Corrupt,
    /// The file uses a format version this build does not understand.
    #[error("the encrypted secret file uses an unsupported format version")]
    UnsupportedVersion,
    /// The file uses an unsupported KDF or out-of-range KDF parameters.
    #[error("the encrypted secret file uses unsupported key-derivation parameters")]
    UnsupportedKdf,
    /// The passphrase is wrong or the ciphertext or header was modified.
    #[error("the passphrase is incorrect or the encrypted secret file was tampered with")]
    AuthenticationFailed,
    /// Key derivation failed.
    #[error("key derivation failed")]
    Kdf,
    /// Encryption failed.
    #[error("encryption failed")]
    Encryption,
    /// The plaintext payload is too large or otherwise invalid.
    #[error("the encrypted secret payload is invalid")]
    Payload,
}

/// Encrypts and decrypts the fallback file format.
#[derive(Clone, Copy, Debug, Default)]
pub struct EncryptedFile {
    params: KdfParams,
}

impl EncryptedFile {
    /// Create a codec with the default Argon2id parameters.
    pub fn new() -> Self {
        Self::default()
    }

    /// Create a codec with explicit Argon2id parameters.
    pub fn with_params(params: KdfParams) -> Self {
        Self { params }
    }

    /// Encrypt `plaintext` with `passphrase`.
    ///
    /// A fresh random salt and nonce are generated for every call.
    pub fn encrypt(
        &self,
        plaintext: &[u8],
        passphrase: &SecretValue,
    ) -> Result<Vec<u8>, EncryptedFileError> {
        let params = self.params.validate()?;
        let mut salt = [0u8; SALT_LEN];
        let mut nonce_bytes = [0u8; NONCE_LEN];
        rand::rngs::OsRng.fill_bytes(&mut salt);
        rand::rngs::OsRng.fill_bytes(&mut nonce_bytes);

        let ciphertext_len = plaintext
            .len()
            .checked_add(TAG_LEN)
            .and_then(|len| u32::try_from(len).ok())
            .ok_or(EncryptedFileError::Payload)?;
        let header = build_header(params, &salt, &nonce_bytes, ciphertext_len);

        let key = derive_key(params, &salt, passphrase)?;
        let cipher = Aes256Gcm::new_from_slice(&key[..]).map_err(|_| EncryptedFileError::Kdf)?;
        let nonce = Nonce::from_slice(&nonce_bytes);
        let ciphertext = cipher
            .encrypt(
                nonce,
                Payload {
                    msg: plaintext,
                    aad: &header,
                },
            )
            .map_err(|_| EncryptedFileError::Encryption)?;

        let mut output = Vec::with_capacity(HEADER_LEN + ciphertext.len());
        output.extend_from_slice(&header);
        output.extend_from_slice(&ciphertext);
        Ok(output)
    }

    /// Decrypt a file produced by [`EncryptedFile::encrypt`].
    ///
    /// Authentication failure is returned as
    /// [`EncryptedFileError::AuthenticationFailed`]. The caller must not fall
    /// back to plaintext.
    pub fn decrypt(
        &self,
        data: &[u8],
        passphrase: &SecretValue,
    ) -> Result<Zeroizing<Vec<u8>>, EncryptedFileError> {
        let parsed = parse_header(data)?;
        let key = derive_key(parsed.params, &parsed.salt, passphrase)?;
        let cipher = Aes256Gcm::new_from_slice(&key[..]).map_err(|_| EncryptedFileError::Kdf)?;
        let nonce = Nonce::from_slice(&parsed.nonce);
        let ciphertext = &data[HEADER_LEN..];
        let plaintext = cipher
            .decrypt(
                nonce,
                Payload {
                    msg: ciphertext,
                    aad: &parsed.header,
                },
            )
            .map_err(|_| EncryptedFileError::AuthenticationFailed)?;
        Ok(Zeroizing::new(plaintext))
    }
}

struct ParsedHeader {
    params: KdfParams,
    salt: [u8; SALT_LEN],
    nonce: [u8; NONCE_LEN],
    header: Vec<u8>,
}

fn build_header(
    params: KdfParams,
    salt: &[u8; SALT_LEN],
    nonce: &[u8; NONCE_LEN],
    ciphertext_len: u32,
) -> Vec<u8> {
    let mut header = Vec::with_capacity(HEADER_LEN);
    header.extend_from_slice(MAGIC);
    header.push(FORMAT_VERSION);
    header.push(KDF_ARGON2ID);
    header.extend_from_slice(&params.m_cost_kib.to_le_bytes());
    header.extend_from_slice(&params.t_cost.to_le_bytes());
    header.extend_from_slice(&params.p_cost.to_le_bytes());
    header.extend_from_slice(salt);
    header.extend_from_slice(nonce);
    header.extend_from_slice(&ciphertext_len.to_le_bytes());
    header
}

fn parse_header(data: &[u8]) -> Result<ParsedHeader, EncryptedFileError> {
    if data.len() < HEADER_LEN {
        return Err(EncryptedFileError::Corrupt);
    }
    if &data[0..8] != MAGIC {
        return Err(EncryptedFileError::Corrupt);
    }
    if data[8] != FORMAT_VERSION {
        return Err(EncryptedFileError::UnsupportedVersion);
    }
    if data[9] != KDF_ARGON2ID {
        return Err(EncryptedFileError::UnsupportedKdf);
    }

    let params = KdfParams {
        m_cost_kib: u32::from_le_bytes(data[10..14].try_into().expect("fixed slice")),
        t_cost: u32::from_le_bytes(data[14..18].try_into().expect("fixed slice")),
        p_cost: u32::from_le_bytes(data[18..22].try_into().expect("fixed slice")),
    }
    .validate()?;

    let salt: [u8; SALT_LEN] = data[22..38].try_into().expect("fixed slice");
    let nonce: [u8; NONCE_LEN] = data[38..50].try_into().expect("fixed slice");
    let ciphertext_len = u32::from_le_bytes(data[50..54].try_into().expect("fixed slice")) as usize;

    if data.len() != HEADER_LEN + ciphertext_len {
        return Err(EncryptedFileError::Corrupt);
    }

    Ok(ParsedHeader {
        params,
        salt,
        nonce,
        header: data[..HEADER_LEN].to_vec(),
    })
}

fn derive_key(
    params: KdfParams,
    salt: &[u8; SALT_LEN],
    passphrase: &SecretValue,
) -> Result<Zeroizing<[u8; KEY_LEN]>, EncryptedFileError> {
    let params = Params::new(
        params.m_cost_kib,
        params.t_cost,
        params.p_cost,
        Some(KEY_LEN),
    )
    .map_err(|_| EncryptedFileError::UnsupportedKdf)?;
    let argon2 = Argon2::new(Algorithm::Argon2id, Version::V0x13, params);
    let mut key = Zeroizing::new([0u8; KEY_LEN]);
    argon2
        .hash_password_into(passphrase.expose().as_bytes(), salt, &mut key[..])
        .map_err(|_| EncryptedFileError::Kdf)?;
    Ok(key)
}

/// Explicit fallback choice made by the user.
#[derive(Debug)]
pub enum FallbackChoice {
    /// Store secrets in a passphrase-protected encrypted file.
    EncryptedFile {
        /// Path to the encrypted file.
        path: PathBuf,
        /// Passphrase used to derive the encryption key.
        passphrase: SecretValue,
    },
    /// Keep secrets in memory only for the lifetime of the process.
    SessionOnly,
}

impl FallbackChoice {
    /// User-facing explanation for this choice.
    pub fn explanation(&self) -> &'static str {
        match self {
            Self::EncryptedFile { .. } => ENCRYPTED_FALLBACK_EXPLANATION,
            Self::SessionOnly => SESSION_ONLY_EXPLANATION,
        }
    }
}

/// Select a secret store without ever falling back silently.
///
/// An explicit `choice` is always honored. With no choice, the OS credential
/// store is used when it is available. When it is unavailable and no choice was
/// made, [`SecretError::FallbackRequired`] is returned so the caller can prompt
/// the user. No plaintext file is ever written.
pub fn select_store(
    os_store: Arc<dyn SecretStore>,
    choice: Option<FallbackChoice>,
) -> Result<Arc<dyn SecretStore>, SecretError> {
    match choice {
        Some(FallbackChoice::EncryptedFile { path, passphrase }) => {
            Ok(Arc::new(EncryptedFileStore::new(path, passphrase)))
        }
        Some(FallbackChoice::SessionOnly) => Ok(Arc::new(SessionStore::new())),
        None if os_store.is_available() => Ok(os_store),
        None => Err(SecretError::FallbackRequired),
    }
}

/// [`SecretStore`] backed by one passphrase-encrypted file.
///
/// The whole secret map is decrypted and re-encrypted on each operation. This
/// is intentionally simple: the fallback holds a small number of provider keys,
/// not bulk data.
pub struct EncryptedFileStore {
    path: PathBuf,
    passphrase: SecretValue,
    file: EncryptedFile,
    lock: Mutex<()>,
}

impl EncryptedFileStore {
    /// Create a store for `path` using `passphrase`.
    ///
    /// The file is created on the first write. The parent directory is created
    /// with restrictive permissions on Unix.
    pub fn new(path: impl Into<PathBuf>, passphrase: SecretValue) -> Self {
        Self {
            path: path.into(),
            passphrase,
            file: EncryptedFile::new(),
            lock: Mutex::new(()),
        }
    }

    /// Path of the encrypted file.
    pub fn path(&self) -> &Path {
        &self.path
    }

    fn load(&self) -> Result<BTreeMap<SecretId, SecretValue>, SecretError> {
        let data = match std::fs::read(&self.path) {
            Ok(data) => data,
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => {
                return Ok(BTreeMap::new());
            }
            Err(_) => {
                return Err(SecretError::Fallback(
                    "could not read the encrypted secret file",
                ));
            }
        };

        let plaintext = self
            .file
            .decrypt(&data, &self.passphrase)
            .map_err(map_encrypted_error)?;
        let raw: BTreeMap<String, String> = serde_json::from_slice(&plaintext)
            .map_err(|_| SecretError::Fallback("the encrypted secret payload is corrupt"))?;

        let mut secrets = BTreeMap::new();
        for (key, value) in raw {
            // Wrap the plaintext value before validating the ID so an early
            // return still zeroizes every remaining value on drop.
            let value = Zeroizing::new(value);
            let id = SecretId::new(key).map_err(|_| SecretError::IndexCorrupt)?;
            secrets.insert(id, SecretValue::from_zeroizing(value));
        }
        Ok(secrets)
    }

    fn save(&self, secrets: &BTreeMap<SecretId, SecretValue>) -> Result<(), SecretError> {
        if secrets.is_empty() {
            return match std::fs::remove_file(&self.path) {
                Ok(()) => Ok(()),
                Err(error) if error.kind() == std::io::ErrorKind::NotFound => Ok(()),
                Err(_) => Err(SecretError::Fallback(
                    "could not remove the encrypted secret file",
                )),
            };
        }

        let raw: BTreeMap<&str, &str> = secrets
            .iter()
            .map(|(id, value)| (id.as_str(), value.expose()))
            .collect();
        let plaintext = Zeroizing::new(
            serde_json::to_vec(&raw)
                .map_err(|_| SecretError::Fallback("could not encode the secret payload"))?,
        );
        let ciphertext = self
            .file
            .encrypt(&plaintext, &self.passphrase)
            .map_err(map_encrypted_error)?;
        self.write_atomic(&ciphertext)
    }

    fn write_atomic(&self, data: &[u8]) -> Result<(), SecretError> {
        let parent = self.path.parent().ok_or(SecretError::Fallback(
            "the encrypted secret path has no parent directory",
        ))?;
        ensure_private_dir(parent)?;

        let temp_path = parent.join(".chatui-secrets.enc.tmp");
        write_private_file(&temp_path, data)?;

        #[cfg(windows)]
        {
            // Windows does not replace an existing file with rename.
            let _ = std::fs::remove_file(&self.path);
        }

        std::fs::rename(&temp_path, &self.path)
            .map_err(|_| SecretError::Fallback("could not replace the encrypted secret file"))
    }
}

impl fmt::Debug for EncryptedFileStore {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter
            .debug_struct("EncryptedFileStore")
            .field("path", &self.path)
            .finish_non_exhaustive()
    }
}

impl SecretStore for EncryptedFileStore {
    fn set(&self, id: &SecretId, value: &SecretValue) -> Result<(), SecretError> {
        let _guard = self
            .lock
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        let mut secrets = self.load()?;
        secrets.insert(id.clone(), value.clone());
        self.save(&secrets)
    }

    fn get(&self, id: &SecretId) -> Result<Option<SecretValue>, SecretError> {
        let _guard = self
            .lock
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        Ok(self.load()?.get(id).cloned())
    }

    fn delete(&self, id: &SecretId) -> Result<(), SecretError> {
        let _guard = self
            .lock
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        let mut secrets = self.load()?;
        if secrets.remove(id).is_some() {
            self.save(&secrets)?;
        }
        Ok(())
    }

    fn forget_key(&self, id: &SecretId) -> Result<(), SecretError> {
        self.delete(id)
    }

    fn forget_all_keys(&self) -> Result<(), SecretError> {
        let _guard = self
            .lock
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        match std::fs::remove_file(&self.path) {
            Ok(()) => Ok(()),
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => Ok(()),
            Err(_) => Err(SecretError::Fallback(
                "could not remove the encrypted secret file",
            )),
        }
    }

    fn is_available(&self) -> bool {
        match self.path.parent() {
            Some(parent) => parent.is_dir() || ensure_private_dir(parent).is_ok(),
            None => false,
        }
    }
}

fn map_encrypted_error(error: EncryptedFileError) -> SecretError {
    match error {
        EncryptedFileError::AuthenticationFailed => SecretError::Fallback(
            "authentication failed; the passphrase is wrong or the file was tampered with",
        ),
        EncryptedFileError::UnsupportedVersion => {
            SecretError::Fallback("unsupported encrypted secret file version")
        }
        EncryptedFileError::UnsupportedKdf => {
            SecretError::Fallback("unsupported encrypted secret file key-derivation parameters")
        }
        EncryptedFileError::Corrupt | EncryptedFileError::Payload => {
            SecretError::Fallback("the encrypted secret file is corrupt")
        }
        EncryptedFileError::Kdf | EncryptedFileError::Encryption => {
            SecretError::Fallback("the encrypted secret file could not be processed")
        }
    }
}

fn ensure_private_dir(path: &Path) -> Result<(), SecretError> {
    if path.is_dir() {
        return Ok(());
    }

    #[cfg(unix)]
    {
        use std::os::unix::fs::DirBuilderExt;

        let mut builder = std::fs::DirBuilder::new();
        builder.recursive(true).mode(0o700);
        builder.create(path).map_err(|_| {
            SecretError::Fallback("could not create the encrypted secret directory")
        })?;
    }

    #[cfg(not(unix))]
    {
        std::fs::create_dir_all(path).map_err(|_| {
            SecretError::Fallback("could not create the encrypted secret directory")
        })?;
    }

    Ok(())
}

fn write_private_file(path: &Path, data: &[u8]) -> Result<(), SecretError> {
    #[cfg(unix)]
    {
        use std::io::Write;
        use std::os::unix::fs::{OpenOptionsExt, PermissionsExt};

        let mut options = std::fs::OpenOptions::new();
        options.write(true).create_new(true).mode(0o600);
        let mut file = match options.open(path) {
            Ok(file) => file,
            Err(error) if error.kind() == std::io::ErrorKind::AlreadyExists => {
                // Remove a stale temporary file or a planted symlink, then
                // create a fresh file with restrictive permissions.
                std::fs::remove_file(path).map_err(|_| {
                    SecretError::Fallback("could not replace the encrypted secret file")
                })?;
                options.open(path).map_err(|_| {
                    SecretError::Fallback("could not create the encrypted secret file")
                })?
            }
            Err(_) => {
                return Err(SecretError::Fallback(
                    "could not create the encrypted secret file",
                ));
            }
        };
        file.write_all(data)
            .map_err(|_| SecretError::Fallback("could not write the encrypted secret file"))?;
        file.sync_all()
            .map_err(|_| SecretError::Fallback("could not flush the encrypted secret file"))?;
        std::fs::set_permissions(path, std::fs::Permissions::from_mode(0o600)).map_err(|_| {
            SecretError::Fallback("could not restrict the encrypted secret file permissions")
        })?;
    }

    #[cfg(not(unix))]
    {
        std::fs::write(path, data)
            .map_err(|_| SecretError::Fallback("could not write the encrypted secret file"))?;
    }

    Ok(())
}

#[cfg(test)]
mod tests {
    use std::sync::Arc;

    use tempfile::TempDir;

    use super::*;

    fn passphrase(value: &str) -> SecretValue {
        SecretValue::new(value)
    }

    fn id(value: &str) -> SecretId {
        SecretId::new(value).expect("valid ID")
    }

    fn store(dir: &TempDir) -> EncryptedFileStore {
        EncryptedFileStore::new(
            dir.path().join("secrets.enc"),
            passphrase("correct horse battery staple"),
        )
    }

    #[test]
    fn encrypt_decrypt_round_trip() {
        let file = EncryptedFile::new();
        let plaintext = b"sk-round-trip-value";
        let encrypted = file
            .encrypt(plaintext, &passphrase("passphrase"))
            .expect("encrypt");
        let decrypted = file
            .decrypt(&encrypted, &passphrase("passphrase"))
            .expect("decrypt");
        assert_eq!(decrypted.as_slice(), plaintext);
    }

    #[test]
    fn wrong_passphrase_fails_closed() {
        let file = EncryptedFile::new();
        let encrypted = file
            .encrypt(b"sk-secret", &passphrase("right"))
            .expect("encrypt");
        let error = file
            .decrypt(&encrypted, &passphrase("wrong"))
            .expect_err("wrong passphrase must fail");
        assert!(matches!(error, EncryptedFileError::AuthenticationFailed));
    }

    #[test]
    fn tampered_ciphertext_fails_closed() {
        let file = EncryptedFile::new();
        let mut encrypted = file
            .encrypt(b"sk-secret", &passphrase("passphrase"))
            .expect("encrypt");
        let last = encrypted.len() - 1;
        encrypted[last] ^= 0x01;
        let error = file
            .decrypt(&encrypted, &passphrase("passphrase"))
            .expect_err("tampering must fail");
        assert!(matches!(error, EncryptedFileError::AuthenticationFailed));
    }

    #[test]
    fn tampered_header_fails_closed() {
        let file = EncryptedFile::new();
        let encrypted = file
            .encrypt(b"sk-secret", &passphrase("passphrase"))
            .expect("encrypt");

        for offset in [22, 38, 50] {
            let mut tampered = encrypted.clone();
            tampered[offset] ^= 0x01;
            let error = file
                .decrypt(&tampered, &passphrase("passphrase"))
                .expect_err("header tampering must fail");
            assert!(
                matches!(
                    error,
                    EncryptedFileError::AuthenticationFailed | EncryptedFileError::Corrupt
                ),
                "offset {offset} produced {error:?}"
            );
        }
    }

    #[test]
    fn salt_and_nonce_are_random_per_write() {
        let file = EncryptedFile::new();
        let first = file
            .encrypt(b"same plaintext", &passphrase("passphrase"))
            .expect("encrypt");
        let second = file
            .encrypt(b"same plaintext", &passphrase("passphrase"))
            .expect("encrypt");

        assert_ne!(first, second);
        assert_ne!(&first[22..38], &second[22..38], "salt must be random");
        assert_ne!(&first[38..50], &second[38..50], "nonce must be random");
    }

    #[test]
    fn corrupt_file_is_rejected() {
        let file = EncryptedFile::new();
        let error = file
            .decrypt(b"not an encrypted file", &passphrase("passphrase"))
            .expect_err("corrupt file must fail");
        assert!(matches!(error, EncryptedFileError::Corrupt));
    }

    #[test]
    fn store_round_trip_and_forget_flows() {
        let dir = tempfile::tempdir().expect("temp dir");
        let store = store(&dir);
        let first = id("provider-1");
        let second = id("provider-2");

        assert_eq!(store.get(&first).expect("get"), None);
        store
            .set(&first, &SecretValue::new("first-value"))
            .expect("set first");
        store
            .set(&second, &SecretValue::new("second-value"))
            .expect("set second");
        assert_eq!(
            store.get(&first).expect("get").expect("present").expose(),
            "first-value"
        );

        store.forget_key(&first).expect("forget key");
        assert_eq!(store.get(&first).expect("get"), None);
        assert_eq!(
            store.get(&second).expect("get").expect("present").expose(),
            "second-value"
        );

        store.forget_all_keys().expect("forget all");
        assert_eq!(store.get(&second).expect("get"), None);
        assert!(!store.path().exists());
    }

    #[test]
    fn no_plaintext_appears_on_disk() {
        let dir = tempfile::tempdir().expect("temp dir");
        let store = store(&dir);
        let sentinel = "sk-plaintext-sentinel-do-not-store";
        store
            .set(&id("provider-1"), &SecretValue::new(sentinel))
            .expect("set");

        let bytes = std::fs::read(store.path()).expect("read encrypted file");
        assert!(
            !bytes
                .windows(sentinel.len())
                .any(|window| window == sentinel.as_bytes()),
            "plaintext sentinel must not appear in the encrypted file"
        );
    }

    #[cfg(unix)]
    #[test]
    fn encrypted_file_has_restrictive_permissions() {
        use std::os::unix::fs::PermissionsExt;

        let dir = tempfile::tempdir().expect("temp dir");
        let store = store(&dir);
        store
            .set(&id("provider-1"), &SecretValue::new("value"))
            .expect("set");

        let mode = std::fs::metadata(store.path())
            .expect("metadata")
            .permissions()
            .mode()
            & 0o777;
        assert_eq!(mode, 0o600);
    }

    #[test]
    fn wrong_passphrase_on_store_fails_closed() {
        let dir = tempfile::tempdir().expect("temp dir");
        let path = dir.path().join("secrets.enc");
        let first = EncryptedFileStore::new(path.clone(), passphrase("right"));
        first
            .set(&id("provider-1"), &SecretValue::new("value"))
            .expect("set");

        let second = EncryptedFileStore::new(path, passphrase("wrong"));
        let error = second
            .get(&id("provider-1"))
            .expect_err("wrong passphrase must fail");
        assert!(matches!(error, SecretError::Fallback(_)));
    }

    #[test]
    fn corrupt_store_file_is_reported() {
        let dir = tempfile::tempdir().expect("temp dir");
        let store = store(&dir);
        std::fs::write(store.path(), b"not an encrypted file").expect("write corrupt file");

        let error = store
            .get(&id("provider-1"))
            .expect_err("corrupt file must fail");
        assert!(matches!(error, SecretError::Fallback(_)));
    }

    #[test]
    fn selection_requires_an_explicit_choice_when_os_store_is_unavailable() {
        let os: Arc<dyn SecretStore> = Arc::new(FakeStore { available: false });
        let error = match select_store(os, None) {
            Ok(_) => panic!("choice is required"),
            Err(error) => error,
        };
        assert!(matches!(error, SecretError::FallbackRequired));
    }

    #[test]
    fn selection_uses_the_os_store_when_available() {
        let os: Arc<dyn SecretStore> = Arc::new(FakeStore { available: true });
        let selected = select_store(os, None).expect("select OS store");
        assert!(selected.is_available());
    }

    #[test]
    fn explicit_choice_is_honored_when_the_os_store_is_available() {
        let os: Arc<dyn SecretStore> = Arc::new(FakeStore { available: true });
        let selected =
            select_store(os, Some(FallbackChoice::SessionOnly)).expect("select session mode");
        selected
            .set(&id("provider-1"), &SecretValue::new("value"))
            .expect("set");
        assert_eq!(
            selected
                .get(&id("provider-1"))
                .expect("get")
                .expect("present")
                .expose(),
            "value"
        );
    }

    #[test]
    fn selection_uses_the_encrypted_file_when_chosen() {
        let dir = tempfile::tempdir().expect("temp dir");
        let os: Arc<dyn SecretStore> = Arc::new(FakeStore { available: false });
        let selected = select_store(
            os,
            Some(FallbackChoice::EncryptedFile {
                path: dir.path().join("secrets.enc"),
                passphrase: passphrase("passphrase"),
            }),
        )
        .expect("select encrypted fallback");

        selected
            .set(&id("provider-1"), &SecretValue::new("value"))
            .expect("set");
        assert_eq!(
            selected
                .get(&id("provider-1"))
                .expect("get")
                .expect("present")
                .expose(),
            "value"
        );
    }

    #[test]
    fn selection_uses_session_mode_when_chosen() {
        let os: Arc<dyn SecretStore> = Arc::new(FakeStore { available: false });
        let selected =
            select_store(os, Some(FallbackChoice::SessionOnly)).expect("select session mode");

        selected
            .set(&id("provider-1"), &SecretValue::new("value"))
            .expect("set");
        assert_eq!(
            selected
                .get(&id("provider-1"))
                .expect("get")
                .expect("present")
                .expose(),
            "value"
        );
    }

    #[test]
    fn fallback_choices_have_user_facing_explanations() {
        let encrypted = FallbackChoice::EncryptedFile {
            path: PathBuf::from("secrets.enc"),
            passphrase: passphrase("passphrase"),
        };
        assert!(encrypted.explanation().contains("encrypted-file fallback"));
        assert!(FallbackChoice::SessionOnly.explanation().contains("memory"));
    }

    #[test]
    fn fallback_choice_debug_redacts_the_passphrase() {
        let choice = FallbackChoice::EncryptedFile {
            path: PathBuf::from("secrets.enc"),
            passphrase: passphrase("sk-test-not-a-real-key"),
        };
        let rendered = format!("{choice:?}");
        assert!(!rendered.contains("sk-test-not-a-real-key"));
        assert!(rendered.contains("SecretValue([REDACTED])"));
    }

    #[test]
    fn encrypted_file_store_debug_omits_the_passphrase() {
        let dir = tempfile::tempdir().expect("temp dir");
        let store = store(&dir);
        let rendered = format!("{store:?}");
        assert!(!rendered.contains("correct horse battery staple"));
        assert!(rendered.contains("EncryptedFileStore"));
    }

    #[test]
    fn default_path_is_under_app_data() {
        let path = default_path().expect("app-data path");
        assert_eq!(
            path.file_name(),
            Some(std::ffi::OsStr::new(ENCRYPTED_FILE_NAME))
        );
        assert!(path.to_string_lossy().contains("chatui"));
    }

    struct FakeStore {
        available: bool,
    }

    impl SecretStore for FakeStore {
        fn set(&self, _id: &SecretId, _value: &SecretValue) -> Result<(), SecretError> {
            if self.available {
                Ok(())
            } else {
                Err(SecretError::Unavailable)
            }
        }

        fn get(&self, _id: &SecretId) -> Result<Option<SecretValue>, SecretError> {
            if self.available {
                Ok(None)
            } else {
                Err(SecretError::Unavailable)
            }
        }

        fn delete(&self, _id: &SecretId) -> Result<(), SecretError> {
            if self.available {
                Ok(())
            } else {
                Err(SecretError::Unavailable)
            }
        }

        fn forget_key(&self, id: &SecretId) -> Result<(), SecretError> {
            self.delete(id)
        }

        fn forget_all_keys(&self) -> Result<(), SecretError> {
            if self.available {
                Ok(())
            } else {
                Err(SecretError::Unavailable)
            }
        }

        fn is_available(&self) -> bool {
            self.available
        }
    }
}
