//! Attachment metadata from spec section 6.2.
//!
//! Binary data lives outside the domain model in content-addressed storage.

use serde::{Deserialize, Serialize};

use crate::time::Timestamp;

/// Attachment kind from spec section 6.2.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum AttachmentKind {
    /// Image attachment.
    Image,
    /// Generic file attachment.
    File,
}

/// Attachment metadata from spec section 6.2.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Attachment {
    /// Local attachment ID.
    pub id: String,
    /// Owning message ID, or `null` before the attachment is linked.
    pub message_id: Option<String>,
    /// Attachment kind.
    pub kind: AttachmentKind,
    /// MIME type.
    pub mime_type: String,
    /// Original file name.
    pub name: String,
    /// Size in bytes.
    pub size: u64,
    /// SHA-256 content hash.
    pub sha256: String,
    /// Content-addressed storage key.
    pub storage_key: String,
    /// Image width in pixels, or `null` for non-images.
    pub width: Option<u32>,
    /// Image height in pixels, or `null` for non-images.
    pub height: Option<u32>,
    /// Creation timestamp.
    pub created_at: Timestamp,
}

#[cfg(test)]
mod tests {
    use super::*;

    fn fixed() -> Timestamp {
        Timestamp::parse("2026-11-04T12:00:00.000Z").expect("valid timestamp")
    }

    #[test]
    fn enum_values_match_shared_json() {
        assert_eq!(
            serde_json::to_string(&AttachmentKind::Image).unwrap(),
            "\"image\""
        );
        assert_eq!(
            serde_json::to_string(&AttachmentKind::File).unwrap(),
            "\"file\""
        );
    }

    #[test]
    fn round_trips_with_nulls() {
        let attachment = Attachment {
            id: "attachment-1".to_owned(),
            message_id: None,
            kind: AttachmentKind::File,
            mime_type: "text/plain".to_owned(),
            name: "notes.txt".to_owned(),
            size: 17,
            sha256: "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789".to_owned(),
            storage_key: "0123456789abcdef0123456789abcdef".to_owned(),
            width: None,
            height: None,
            created_at: fixed(),
        };

        let json = serde_json::to_value(&attachment).expect("serialize attachment");
        assert_eq!(json["messageId"], serde_json::Value::Null);
        assert_eq!(json["width"], serde_json::Value::Null);
        assert_eq!(json["height"], serde_json::Value::Null);
        assert_eq!(json["mimeType"], "text/plain");
        assert_eq!(json["storageKey"], "0123456789abcdef0123456789abcdef");

        let parsed: Attachment = serde_json::from_value(json).expect("deserialize attachment");
        assert_eq!(parsed, attachment);
    }
}
