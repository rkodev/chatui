//! Encrypted backup envelope for the ChatUI desktop client.
//!
//! This crate implements the `CHATUI1\n` backup envelope frozen in
//! `docs/operations/M5/M5-decisions.md` and exercised by
//! `contracts/fixtures/backup/vectors.json`. It is intentionally separate from
//! the `CHUIENC1` secret-file format in `chatui-secrets`.
//!
//! The codec only produces and consumes bytes. It never writes to storage, so
//! callers cannot accidentally persist plaintext through it.

pub mod envelope;
pub mod error;

pub use envelope::{
    ALG, DEFAULT_ITERATIONS, DecryptedObject, ENVELOPE_VERSION, EncryptOptions, EnvelopeHeader,
    EnvelopeParts, FORMAT_VERSION, HEADER_LENGTH_BYTES, IV_LEN, KDF, KEY_LEN, MAGIC,
    MAX_HEADER_BYTES, MAX_ITERATIONS, MAX_OBJECT_KEY_LENGTH, ObjectType, SALT_LEN, TAG_LEN,
    VERIFICATION_MARKER, VERIFICATION_OBJECT_KEY, VERIFICATION_OBJECT_TYPE, build_aad,
    create_verification_token, create_verification_token_with, decode_envelope, decrypt_object,
    encode_envelope, encrypt_object, verify_passphrase,
};
pub use error::EnvelopeError;

#[cfg(test)]
mod tests;
