//! Filesystem paths for content-addressed attachment storage.
//!
//! Attachment bytes live under `<app-data>/attachments/<sha256>`, matching the
//! web and Android layout. The storage key is the bare 64-character lowercase
//! hexadecimal SHA-256 digest; the `attachments/` directory is part of the path,
//! not the key. Keeping the key as a bare digest makes validation simple and
//! prevents path traversal.

use std::path::{Path, PathBuf};

use crate::StorageError;

/// Directory name under the app-data directory.
pub const ATTACHMENTS_DIR_NAME: &str = "attachments";

/// Length of a SHA-256 digest in lowercase hexadecimal.
pub const STORAGE_KEY_LEN: usize = 64;

/// Returns the attachment directory under the platform app-data directory.
pub fn attachments_dir() -> Result<PathBuf, StorageError> {
    Ok(attachments_dir_in(crate::app_data_dir()?))
}

/// Returns the attachment directory under a caller-supplied app-data directory.
pub fn attachments_dir_in(app_data_dir: impl AsRef<Path>) -> PathBuf {
    app_data_dir.as_ref().join(ATTACHMENTS_DIR_NAME)
}

/// Returns the file path for a storage key under the platform app-data directory.
pub fn attachment_path(storage_key: &str) -> Result<PathBuf, StorageError> {
    attachment_path_in(crate::app_data_dir()?, storage_key)
}

/// Returns the file path for a storage key under a caller-supplied app-data
/// directory.
pub fn attachment_path_in(
    app_data_dir: impl AsRef<Path>,
    storage_key: &str,
) -> Result<PathBuf, StorageError> {
    validate_storage_key(storage_key)?;
    Ok(attachments_dir_in(app_data_dir).join(storage_key))
}

/// Returns true when `value` is a 64-character lowercase hexadecimal SHA-256
/// digest.
pub fn is_valid_storage_key(value: &str) -> bool {
    value.len() == STORAGE_KEY_LEN
        && value
            .bytes()
            .all(|byte| byte.is_ascii_digit() || (b'a'..=b'f').contains(&byte))
}

/// Validates a storage key, rejecting path traversal and non-hex values.
pub fn validate_storage_key(value: &str) -> Result<(), StorageError> {
    if is_valid_storage_key(value) {
        Ok(())
    } else {
        Err(StorageError::InvalidInput(format!(
            "invalid attachment storage key {value:?}: expected 64 lowercase hexadecimal characters"
        )))
    }
}

/// Returns the storage key for a SHA-256 digest after validating it.
pub fn storage_key_for_sha256(sha256: &str) -> Result<String, StorageError> {
    validate_storage_key(sha256)?;
    Ok(sha256.to_owned())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn accepts_lowercase_sha256_digests() {
        let key = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        assert!(is_valid_storage_key(key));
        assert_eq!(
            storage_key_for_sha256(key).expect("valid key"),
            key.to_owned()
        );
    }

    #[test]
    fn rejects_unsafe_storage_keys() {
        let invalid = [
            String::new(),
            "../escape".to_owned(),
            "a".repeat(63),
            "a".repeat(65),
            "A".repeat(64),
            "g".repeat(64),
            format!("attachments/{}", "a".repeat(64)),
            "a/b".to_owned(),
        ];

        for key in invalid {
            assert!(
                !is_valid_storage_key(&key),
                "accepted unsafe storage key {key:?}"
            );
            assert!(
                matches!(
                    validate_storage_key(&key),
                    Err(StorageError::InvalidInput(_))
                ),
                "did not reject unsafe storage key {key:?}"
            );
        }
    }

    #[test]
    fn attachment_paths_stay_under_the_attachments_directory() {
        let key = "a".repeat(64);
        let path = attachment_path_in("/tmp/chatui", &key).expect("valid path");

        assert_eq!(
            path,
            PathBuf::from("/tmp/chatui")
                .join(ATTACHMENTS_DIR_NAME)
                .join(&key)
        );
        assert!(path.starts_with(PathBuf::from("/tmp/chatui").join(ATTACHMENTS_DIR_NAME)));
    }

    #[test]
    fn attachment_path_rejects_traversal() {
        assert!(matches!(
            attachment_path_in("/tmp/chatui", "../../etc/passwd"),
            Err(StorageError::InvalidInput(_))
        ));
    }
}
