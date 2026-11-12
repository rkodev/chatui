//! Deletion tombstones used by backup and sync.
//!
//! A tombstone records that a conversation or message was deleted on a device.
//! Tombstones are retained until sync confirms the deletion so a remote copy
//! cannot resurrect deleted content.

use serde::{Deserialize, Serialize};

use crate::time::Timestamp;

/// A deletion tombstone.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Tombstone {
    /// Local tombstone identifier.
    pub id: String,
    /// Conversation the tombstone applies to.
    pub conversation_id: String,
    /// Message the tombstone applies to, or `null` for a conversation deletion.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub message_id: Option<String>,
    /// Deletion timestamp.
    pub deleted_at: Timestamp,
    /// Device that observed the deletion.
    pub device_id: String,
}

#[cfg(test)]
mod tests {
    use super::*;

    fn fixed() -> Timestamp {
        Timestamp::parse("2026-11-04T12:00:00.000Z").expect("valid timestamp")
    }

    #[test]
    fn round_trips_with_and_without_message_id() {
        let conversation = Tombstone {
            id: "tombstone-1".to_owned(),
            conversation_id: "conversation-1".to_owned(),
            message_id: None,
            deleted_at: fixed(),
            device_id: "device-1".to_owned(),
        };

        let json = serde_json::to_value(&conversation).expect("serialize tombstone");
        assert_eq!(json["conversationId"], "conversation-1");
        assert_eq!(json["deletedAt"], "2026-11-04T12:00:00.000Z");
        assert_eq!(json["deviceId"], "device-1");
        assert!(json.get("messageId").is_none());

        let parsed: Tombstone = serde_json::from_value(json).expect("deserialize tombstone");
        assert_eq!(parsed, conversation);

        let message = Tombstone {
            message_id: Some("message-1".to_owned()),
            ..conversation
        };
        let json = serde_json::to_value(&message).expect("serialize message tombstone");
        assert_eq!(json["messageId"], "message-1");
        let parsed: Tombstone = serde_json::from_value(json).expect("deserialize tombstone");
        assert_eq!(parsed, message);
    }
}
