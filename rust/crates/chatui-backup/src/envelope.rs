//! `CHATUI1\n` backup envelope codec.
//!
//! The byte layout is frozen in `docs/operations/M5/M5-decisions.md`
//! (`DEC-M5-05` through `DEC-M5-08`) and exercised by
//! `contracts/fixtures/backup/vectors.json`:
//!
//! ```text
//! magic (8) | uint32be headerLength | UTF-8 header JSON | IV (12) | ciphertext || tag (16)
//! ```
//!
//! This module only produces and consumes bytes. It never writes to storage, so
//! callers cannot accidentally persist plaintext through the codec. The
//! `CHUIENC1` secret-file format in `chatui-secrets` is deliberately unrelated.

use std::io::{Read, Write};

use aes_gcm::aead::{Aead, Payload};
use aes_gcm::{Aes256Gcm, KeyInit, Nonce};
use base64::Engine;
use base64::engine::general_purpose::STANDARD;
use flate2::Compression;
use flate2::read::GzDecoder;
use flate2::write::GzEncoder;
use pbkdf2::pbkdf2_hmac;
use rand::RngCore;
use serde::{Deserialize, Serialize};
use sha2::Sha256;
use zeroize::Zeroizing;

use crate::error::EnvelopeError;

/// Magic bytes at the start of every backup envelope.
pub const MAGIC: &[u8] = b"CHATUI1\n";
/// Envelope format version written by this build.
pub const ENVELOPE_VERSION: u32 = 1;
/// Backup payload format version written by this build.
pub const FORMAT_VERSION: u32 = 1;
/// Cipher identifier written in the header.
pub const ALG: &str = "AES-256-GCM";
/// Key-derivation identifier written in the header.
pub const KDF: &str = "pbkdf2-sha256";
/// PBKDF2 iteration count for v1.
pub const DEFAULT_ITERATIONS: u32 = 600_000;
/// Big-endian header-length field width.
pub const HEADER_LENGTH_BYTES: usize = 4;
/// Maximum accepted header JSON length.
pub const MAX_HEADER_BYTES: usize = 65_536;
/// AES-GCM IV length.
pub const IV_LEN: usize = 12;
/// AES-GCM authentication tag length.
pub const TAG_LEN: usize = 16;
/// PBKDF2 salt length.
pub const SALT_LEN: usize = 16;
/// Derived key length.
pub const KEY_LEN: usize = 32;
/// Upper bound accepted for the PBKDF2 iteration count.
pub const MAX_ITERATIONS: u32 = 10_000_000;
/// Upper bound accepted for an object key.
pub const MAX_OBJECT_KEY_LENGTH: usize = 4096;

/// Reserved verification marker plaintext.
pub const VERIFICATION_MARKER: &str = "chatui-backup-verification-v1";
/// Object type used by the verification token.
pub const VERIFICATION_OBJECT_TYPE: ObjectType = ObjectType::Manifest;
/// Reserved object key used by the verification token.
pub const VERIFICATION_OBJECT_KEY: &str = "verification.enc";

/// A v1 backup object type.
#[derive(Clone, Copy, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(rename_all = "lowercase")]
pub enum ObjectType {
    /// The backup manifest.
    Manifest,
    /// A self-contained conversation payload.
    Conversation,
    /// Raw attachment bytes.
    Attachment,
}

impl ObjectType {
    /// Wire name used in the header and AAD.
    pub fn as_str(self) -> &'static str {
        match self {
            Self::Manifest => "manifest",
            Self::Conversation => "conversation",
            Self::Attachment => "attachment",
        }
    }

    /// Parse a wire name. Returns `None` for reserved or unknown values.
    pub fn from_wire(value: &str) -> Option<Self> {
        match value {
            "manifest" => Some(Self::Manifest),
            "conversation" => Some(Self::Conversation),
            "attachment" => Some(Self::Attachment),
            _ => None,
        }
    }
}

/// Canonical envelope header.
///
/// Field declaration order is the canonical JSON key order frozen in
/// `DEC-M5-06`. Do not reorder these fields.
#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct EnvelopeHeader {
    /// Envelope format version.
    pub v: u32,
    /// Backup payload format version.
    pub format_version: u32,
    /// Cipher identifier.
    pub alg: String,
    /// Key-derivation identifier.
    pub kdf: String,
    /// PBKDF2 iteration count.
    pub iterations: u32,
    /// Standard base64 of the 16-byte salt.
    pub salt: String,
    /// Backup object type.
    pub object_type: ObjectType,
    /// Object storage key.
    pub object_key: String,
}

impl EnvelopeHeader {
    /// Validate every header field before it is used.
    pub fn validate(&self) -> Result<(), EnvelopeError> {
        if self.v != ENVELOPE_VERSION {
            return Err(EnvelopeError::UnsupportedVersion);
        }
        if self.format_version != FORMAT_VERSION {
            return Err(EnvelopeError::UnsupportedVersion);
        }
        if self.alg != ALG {
            return Err(EnvelopeError::UnsupportedAlg);
        }
        if self.kdf != KDF {
            return Err(EnvelopeError::UnsupportedKdf);
        }
        if self.iterations == 0 || self.iterations > MAX_ITERATIONS {
            return Err(EnvelopeError::BadHeader);
        }

        let salt = STANDARD
            .decode(&self.salt)
            .map_err(|_| EnvelopeError::BadHeader)?;
        if salt.len() != SALT_LEN {
            return Err(EnvelopeError::BadHeader);
        }

        if self.object_key.is_empty() || self.object_key.len() > MAX_OBJECT_KEY_LENGTH {
            return Err(EnvelopeError::BadHeader);
        }
        if !self
            .object_key
            .bytes()
            .all(|byte| (0x20..=0x7e).contains(&byte))
        {
            return Err(EnvelopeError::BadHeader);
        }

        Ok(())
    }

    /// Canonical UTF-8 JSON with no insignificant whitespace.
    pub fn canonical_json(&self) -> Result<Vec<u8>, EnvelopeError> {
        serde_json::to_vec(self).map_err(|_| EnvelopeError::BadHeader)
    }
}

/// Decoded envelope parts.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct EnvelopeParts {
    /// Parsed and validated header.
    pub header: EnvelopeHeader,
    /// 12-byte IV.
    pub iv: [u8; IV_LEN],
    /// AES-GCM ciphertext with the 16-byte tag appended.
    pub ciphertext: Vec<u8>,
}

/// Options for [`encrypt_object`].
#[derive(Clone, Copy, Debug)]
pub struct EncryptOptions<'a> {
    /// Passphrase used to derive the key.
    pub passphrase: &'a str,
    /// 16-byte backup-set salt.
    pub salt: &'a [u8],
    /// Backup object type.
    pub object_type: ObjectType,
    /// Object storage key.
    pub object_key: &'a str,
    /// Plaintext bytes.
    pub plaintext: &'a [u8],
    /// Override compression. Defaults to `true` for conversations and `false`
    /// for every other object type.
    pub compress: Option<bool>,
    /// Test-only deterministic IV. Production callers must omit this.
    pub iv: Option<[u8; IV_LEN]>,
    /// Test-only iteration override. Production callers must omit this.
    pub iterations: Option<u32>,
}

/// Decrypted object.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct DecryptedObject {
    /// Parsed and validated header.
    pub header: EnvelopeHeader,
    /// Plaintext bytes. Conversation payloads are already gunzipped.
    pub plaintext: Zeroizing<Vec<u8>>,
}

/// Build the AAD exactly as frozen in `DEC-M5-07`:
/// `uint32be(len(objectType)) || objectType || uint32be(len(objectKey)) || objectKey`.
pub fn build_aad(object_type: ObjectType, object_key: &str) -> Result<Vec<u8>, EnvelopeError> {
    let type_bytes = object_type.as_str().as_bytes();
    let key_bytes = object_key.as_bytes();
    let type_len = u32::try_from(type_bytes.len()).map_err(|_| EnvelopeError::BadHeader)?;
    let key_len = u32::try_from(key_bytes.len()).map_err(|_| EnvelopeError::BadHeader)?;

    let mut aad = Vec::with_capacity(8 + type_bytes.len() + key_bytes.len());
    aad.extend_from_slice(&type_len.to_be_bytes());
    aad.extend_from_slice(type_bytes);
    aad.extend_from_slice(&key_len.to_be_bytes());
    aad.extend_from_slice(key_bytes);
    Ok(aad)
}

/// Encode an envelope from its parts.
pub fn encode_envelope(
    header: &EnvelopeHeader,
    iv: &[u8],
    ciphertext: &[u8],
) -> Result<Vec<u8>, EnvelopeError> {
    header.validate()?;
    if iv.len() != IV_LEN {
        return Err(EnvelopeError::MalformedEnvelope);
    }
    if ciphertext.len() < TAG_LEN {
        return Err(EnvelopeError::MalformedEnvelope);
    }

    let header_bytes = header.canonical_json()?;
    if header_bytes.is_empty() || header_bytes.len() > MAX_HEADER_BYTES {
        return Err(EnvelopeError::MalformedEnvelope);
    }
    let header_len =
        u32::try_from(header_bytes.len()).map_err(|_| EnvelopeError::MalformedEnvelope)?;

    let mut output = Vec::with_capacity(
        MAGIC.len() + HEADER_LENGTH_BYTES + header_bytes.len() + iv.len() + ciphertext.len(),
    );
    output.extend_from_slice(MAGIC);
    output.extend_from_slice(&header_len.to_be_bytes());
    output.extend_from_slice(&header_bytes);
    output.extend_from_slice(iv);
    output.extend_from_slice(ciphertext);
    Ok(output)
}

/// Decode an envelope into its parts.
pub fn decode_envelope(bytes: &[u8]) -> Result<EnvelopeParts, EnvelopeError> {
    let header_start = MAGIC.len() + HEADER_LENGTH_BYTES;
    if bytes.len() < header_start {
        return Err(EnvelopeError::MalformedEnvelope);
    }
    if &bytes[..MAGIC.len()] != MAGIC {
        return Err(EnvelopeError::BadMagic);
    }

    let header_len = u32::from_be_bytes(
        bytes[MAGIC.len()..header_start]
            .try_into()
            .expect("fixed slice"),
    ) as usize;
    if header_len == 0 || header_len > MAX_HEADER_BYTES {
        return Err(EnvelopeError::MalformedEnvelope);
    }

    let header_end = header_start
        .checked_add(header_len)
        .ok_or(EnvelopeError::MalformedEnvelope)?;
    let iv_end = header_end
        .checked_add(IV_LEN)
        .ok_or(EnvelopeError::MalformedEnvelope)?;
    let minimum_end = iv_end
        .checked_add(TAG_LEN)
        .ok_or(EnvelopeError::MalformedEnvelope)?;
    if bytes.len() < minimum_end {
        return Err(EnvelopeError::MalformedEnvelope);
    }

    let header: EnvelopeHeader = serde_json::from_slice(&bytes[header_start..header_end])
        .map_err(|_| EnvelopeError::BadHeader)?;
    header.validate()?;

    let iv: [u8; IV_LEN] = bytes[header_end..iv_end].try_into().expect("fixed slice");
    let ciphertext = bytes[iv_end..].to_vec();

    Ok(EnvelopeParts {
        header,
        iv,
        ciphertext,
    })
}

/// Encrypt one backup object.
///
/// A fresh random IV is generated for every call unless `options.iv` is set for
/// a deterministic test. Conversation payloads are gzipped by default;
/// attachments are never gzipped.
pub fn encrypt_object(options: EncryptOptions<'_>) -> Result<Vec<u8>, EnvelopeError> {
    if options.salt.len() != SALT_LEN {
        return Err(EnvelopeError::BadHeader);
    }

    let iterations = options.iterations.unwrap_or(DEFAULT_ITERATIONS);
    if iterations == 0 || iterations > MAX_ITERATIONS {
        return Err(EnvelopeError::BadHeader);
    }

    let compress = options
        .compress
        .unwrap_or(options.object_type == ObjectType::Conversation);
    if options.object_type == ObjectType::Attachment && compress {
        return Err(EnvelopeError::CompressionNotAllowed);
    }

    let header = EnvelopeHeader {
        v: ENVELOPE_VERSION,
        format_version: FORMAT_VERSION,
        alg: ALG.to_owned(),
        kdf: KDF.to_owned(),
        iterations,
        salt: STANDARD.encode(options.salt),
        object_type: options.object_type,
        object_key: options.object_key.to_owned(),
    };
    header.validate()?;

    let key = derive_key(options.passphrase, options.salt, iterations)?;
    let payload = if compress {
        gzip(options.plaintext)?
    } else {
        options.plaintext.to_vec()
    };

    let iv = options.iv.unwrap_or_else(random_iv);
    let aad = build_aad(header.object_type, &header.object_key)?;
    let cipher = Aes256Gcm::new_from_slice(&key[..]).map_err(|_| EnvelopeError::Kdf)?;
    let ciphertext = cipher
        .encrypt(
            Nonce::from_slice(&iv),
            Payload {
                msg: &payload,
                aad: &aad,
            },
        )
        .map_err(|_| EnvelopeError::Encryption)?;

    encode_envelope(&header, &iv, &ciphertext)
}

/// Decrypt one backup object.
///
/// Conversation payloads are gunzipped after authentication. A wrong
/// passphrase or any tampering returns [`EnvelopeError::AuthenticationFailed`]
/// before any plaintext is returned.
pub fn decrypt_object(passphrase: &str, bytes: &[u8]) -> Result<DecryptedObject, EnvelopeError> {
    let parts = decode_envelope(bytes)?;
    let salt = STANDARD
        .decode(&parts.header.salt)
        .map_err(|_| EnvelopeError::BadHeader)?;
    let key = derive_key(passphrase, &salt, parts.header.iterations)?;
    let aad = build_aad(parts.header.object_type, &parts.header.object_key)?;

    let cipher = Aes256Gcm::new_from_slice(&key[..]).map_err(|_| EnvelopeError::Kdf)?;
    let plaintext = cipher
        .decrypt(
            Nonce::from_slice(&parts.iv),
            Payload {
                msg: &parts.ciphertext,
                aad: &aad,
            },
        )
        .map_err(|_| EnvelopeError::AuthenticationFailed)?;

    let plaintext = if parts.header.object_type == ObjectType::Conversation {
        Zeroizing::new(gunzip(&plaintext)?)
    } else {
        Zeroizing::new(plaintext)
    };

    Ok(DecryptedObject {
        header: parts.header,
        plaintext,
    })
}

/// Create the small encrypted marker used to reject a wrong passphrase before
/// touching local data.
pub fn create_verification_token(passphrase: &str, salt: &[u8]) -> Result<Vec<u8>, EnvelopeError> {
    create_verification_token_with(passphrase, salt, None, None)
}

/// Create a verification token with optional deterministic IV and iteration
/// override for tests.
pub fn create_verification_token_with(
    passphrase: &str,
    salt: &[u8],
    iv: Option<[u8; IV_LEN]>,
    iterations: Option<u32>,
) -> Result<Vec<u8>, EnvelopeError> {
    encrypt_object(EncryptOptions {
        passphrase,
        salt,
        object_type: VERIFICATION_OBJECT_TYPE,
        object_key: VERIFICATION_OBJECT_KEY,
        plaintext: VERIFICATION_MARKER.as_bytes(),
        compress: Some(false),
        iv,
        iterations,
    })
}

/// Verify a passphrase against a token created by
/// [`create_verification_token`]. Returns `false` for a wrong passphrase or a
/// tampered token.
pub fn verify_passphrase(passphrase: &str, token: &[u8]) -> bool {
    match decrypt_object(passphrase, token) {
        Ok(decrypted) => {
            decrypted.header.object_type == VERIFICATION_OBJECT_TYPE
                && decrypted.header.object_key == VERIFICATION_OBJECT_KEY
                && decrypted.plaintext.as_slice() == VERIFICATION_MARKER.as_bytes()
        }
        Err(_) => false,
    }
}

fn random_iv() -> [u8; IV_LEN] {
    let mut iv = [0u8; IV_LEN];
    rand::rngs::OsRng.fill_bytes(&mut iv);
    iv
}

fn derive_key(
    passphrase: &str,
    salt: &[u8],
    iterations: u32,
) -> Result<Zeroizing<[u8; KEY_LEN]>, EnvelopeError> {
    if salt.len() != SALT_LEN || iterations == 0 || iterations > MAX_ITERATIONS {
        return Err(EnvelopeError::BadHeader);
    }

    let mut key = Zeroizing::new([0u8; KEY_LEN]);
    pbkdf2_hmac::<Sha256>(passphrase.as_bytes(), salt, iterations, &mut key[..]);
    Ok(key)
}

fn gzip(data: &[u8]) -> Result<Vec<u8>, EnvelopeError> {
    let mut encoder = GzEncoder::new(Vec::new(), Compression::best());
    encoder
        .write_all(data)
        .map_err(|_| EnvelopeError::Encryption)?;
    encoder.finish().map_err(|_| EnvelopeError::Encryption)
}

fn gunzip(data: &[u8]) -> Result<Vec<u8>, EnvelopeError> {
    let mut decoder = GzDecoder::new(data);
    let mut output = Vec::new();
    decoder
        .read_to_end(&mut output)
        .map_err(|_| EnvelopeError::DecompressionFailed)?;
    Ok(output)
}
