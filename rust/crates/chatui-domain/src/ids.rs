//! UUIDv7 generation and validation helpers.
//!
//! Entity IDs are generated client-side as UUIDv7 strings. Provider IDs are
//! never used for local identity.

use std::fmt;

use uuid::{Uuid, Variant};

/// Errors returned when validating a UUIDv7 string.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum UuidV7Error {
    /// The value is not a valid UUID.
    InvalidUuid(uuid::Error),
    /// The UUID is valid but is not version 7.
    NotVersion7,
    /// The UUID is not in canonical hyphenated form.
    NotCanonical,
}

impl fmt::Display for UuidV7Error {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::InvalidUuid(error) => write!(formatter, "invalid UUID: {error}"),
            Self::NotVersion7 => formatter.write_str("UUID is not version 7"),
            Self::NotCanonical => formatter.write_str("UUID is not in canonical form"),
        }
    }
}

impl std::error::Error for UuidV7Error {
    fn source(&self) -> Option<&(dyn std::error::Error + 'static)> {
        match self {
            Self::InvalidUuid(error) => Some(error),
            Self::NotVersion7 | Self::NotCanonical => None,
        }
    }
}

impl From<uuid::Error> for UuidV7Error {
    fn from(value: uuid::Error) -> Self {
        Self::InvalidUuid(value)
    }
}

/// Generate a time-ordered UUIDv7 entity ID.
pub fn new_id() -> String {
    Uuid::now_v7().to_string()
}

/// Validate a canonical UUIDv7 string and return the parsed UUID.
pub fn validate_uuid_v7(value: &str) -> Result<Uuid, UuidV7Error> {
    let uuid = Uuid::parse_str(value)?;

    if uuid.get_version_num() != 7 {
        return Err(UuidV7Error::NotVersion7);
    }

    if uuid.get_variant() != Variant::RFC4122 {
        return Err(UuidV7Error::NotVersion7);
    }

    if uuid.hyphenated().to_string() != value.to_ascii_lowercase() {
        return Err(UuidV7Error::NotCanonical);
    }

    Ok(uuid)
}

/// Return true when `value` is a canonical UUIDv7 string.
pub fn is_uuid_v7(value: &str) -> bool {
    validate_uuid_v7(value).is_ok()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn generated_ids_are_uuid_v7() {
        let id = new_id();
        let uuid = validate_uuid_v7(&id).expect("generated id is valid");
        assert_eq!(uuid.get_version_num(), 7);
        assert_eq!(uuid.get_variant(), Variant::RFC4122);
        assert!(is_uuid_v7(&id));
    }

    #[test]
    fn rejects_uuid_v4() {
        let id = "f47ac10b-58cc-4372-a567-0e02b2c3d479";
        assert!(!is_uuid_v7(id));
        assert_eq!(validate_uuid_v7(id), Err(UuidV7Error::NotVersion7));
    }

    #[test]
    fn rejects_malformed_values() {
        assert!(!is_uuid_v7(""));
        assert!(!is_uuid_v7("not-a-uuid"));
        assert!(!is_uuid_v7("018f3f7e8a2b7c4d9e0f1a2b3c4d5e6f"));
    }

    #[test]
    fn accepts_uppercase_canonical_ids() {
        let id = new_id();
        assert!(is_uuid_v7(&id.to_ascii_uppercase()));
    }

    #[test]
    fn rejects_non_canonical_forms() {
        let id = new_id();
        let simple = id.replace('-', "");
        assert_eq!(validate_uuid_v7(&simple), Err(UuidV7Error::NotCanonical));
    }
}
