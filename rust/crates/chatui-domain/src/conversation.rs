//! Conversation entity, chat parameters, and provisional sync metadata.

use serde::{Deserialize, Serialize};
use serde_json::{Map, Value};

use crate::errors::ChatError;
use crate::time::Timestamp;

/// Reasoning effort control from spec section 6.2.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum ReasoningEffort {
    /// Low reasoning effort.
    Low,
    /// Medium reasoning effort.
    Medium,
    /// High reasoning effort.
    High,
}

/// Chat parameters from spec section 6.2.
///
/// Provider-specific request options live in `extra`, namespaced by preset. The
/// core domain never interprets those values; adapters validate and map them.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ChatParams {
    /// Sampling temperature, or `null` for the provider default.
    pub temperature: Option<f64>,
    /// Nucleus sampling probability, or `null` for the provider default.
    pub top_p: Option<f64>,
    /// Maximum output tokens, or `null` for the provider default.
    pub max_output_tokens: Option<u32>,
    /// Stop sequences.
    pub stop: Vec<String>,
    /// Reasoning effort, or `null` when not applicable.
    pub reasoning_effort: Option<ReasoningEffort>,
    /// Whether to request a streaming response.
    pub streaming: bool,
    /// Provider-specific request options, namespaced by preset.
    pub extra: Map<String, Value>,
}

impl Default for ChatParams {
    fn default() -> Self {
        Self {
            temperature: None,
            top_p: None,
            max_output_tokens: None,
            stop: Vec::new(),
            reasoning_effort: None,
            streaming: true,
            extra: Map::new(),
        }
    }
}

/// Provisional sync state placeholder.
///
/// The spec references `SyncMeta` and `syncState` without defining their final
/// shape. M5 owns the final shape, including conflict resolution, tombstones,
/// and remote revision semantics.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum SyncState {
    /// Local-only conversation.
    Local,
    /// Changes are pending upload.
    Pending,
    /// Conversation is in sync.
    Synced,
    /// A sync conflict needs resolution.
    Conflict,
}

/// Provisional sync metadata placeholder.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SyncMeta {
    /// Local sync state for this conversation.
    pub state: SyncState,
    /// Last successful sync timestamp, or `null` when never synced.
    pub last_synced_at: Option<Timestamp>,
    /// Opaque remote revision identifier, or `null` when never synced.
    pub remote_revision: Option<String>,
}

/// Conversation entity from spec section 6.2.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Conversation {
    /// Local conversation ID.
    pub id: String,
    /// Conversation title.
    pub title: String,
    /// System prompt.
    pub system_prompt: String,
    /// Current provider ID.
    pub provider_id: String,
    /// Current model ID.
    pub model_id: String,
    /// Denormalized model label snapshot.
    pub model_label: String,
    /// Chat parameters.
    pub params: ChatParams,
    /// Whether the conversation is pinned.
    pub pinned: bool,
    /// Whether the conversation is archived.
    pub archived: bool,
    /// Whether the conversation is temporary.
    pub temporary: bool,
    /// Creation timestamp.
    pub created_at: Timestamp,
    /// Last update timestamp.
    pub updated_at: Timestamp,
    /// Timestamp of the last message.
    pub last_message_at: Timestamp,
    /// Number of messages in the conversation.
    pub message_count: u64,
    /// Last error, or `null` when the conversation is healthy.
    pub last_error: Option<ChatError>,
    /// Provisional sync metadata, or `null` when sync is not configured.
    pub sync: Option<SyncMeta>,
    /// Random 128-bit backup storage key as 32 lowercase hexadecimal characters.
    ///
    /// This is sync-only metadata. It is omitted from the plaintext export
    /// contract, which keeps its existing shape, and is carried by the backup
    /// conversation payload instead.
    #[serde(default, skip_serializing_if = "String::is_empty")]
    pub storage_key: String,
    /// Monotonic local revision, starting at 1 and incremented on every change.
    ///
    /// Like [`Conversation::storage_key`], this is sync-only metadata and is
    /// omitted from the plaintext export contract.
    #[serde(default, skip_serializing_if = "is_zero")]
    pub revision: u64,
}

fn is_zero(value: &u64) -> bool {
    *value == 0
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
            serde_json::to_string(&ReasoningEffort::Medium).unwrap(),
            "\"medium\""
        );
        assert_eq!(
            serde_json::to_string(&SyncState::Conflict).unwrap(),
            "\"conflict\""
        );
    }

    #[test]
    fn chat_params_round_trip_with_nulls_and_empty_arrays() {
        let params = ChatParams {
            temperature: None,
            top_p: None,
            max_output_tokens: None,
            stop: Vec::new(),
            reasoning_effort: None,
            streaming: true,
            extra: Map::new(),
        };

        let json = serde_json::to_value(&params).expect("serialize params");
        assert_eq!(json["temperature"], Value::Null);
        assert_eq!(json["stop"], serde_json::json!([]));
        assert_eq!(json["extra"], serde_json::json!({}));

        let parsed: ChatParams = serde_json::from_value(json).expect("deserialize params");
        assert_eq!(parsed, params);
    }

    #[test]
    fn chat_params_preserve_unknown_extra() {
        let params = ChatParams {
            extra: serde_json::json!({
                "openrouter": { "provider": { "order": ["a", "b"] } }
            })
            .as_object()
            .expect("object")
            .clone(),
            ..ChatParams::default()
        };

        let json = serde_json::to_value(&params).expect("serialize params");
        assert_eq!(json["extra"]["openrouter"]["provider"]["order"][1], "b");

        let parsed: ChatParams = serde_json::from_value(json).expect("deserialize params");
        assert_eq!(parsed, params);
    }

    #[test]
    fn conversation_round_trips_with_null_error_and_sync() {
        let conversation = Conversation {
            id: "conversation-1".to_owned(),
            title: "Contract export".to_owned(),
            system_prompt: "You are a concise assistant.".to_owned(),
            provider_id: "provider-1".to_owned(),
            model_id: "model-1".to_owned(),
            model_label: "gpt-4o-mini".to_owned(),
            params: ChatParams::default(),
            pinned: false,
            archived: false,
            temporary: false,
            created_at: fixed(),
            updated_at: fixed(),
            last_message_at: fixed(),
            message_count: 0,
            last_error: None,
            sync: None,
            storage_key: String::new(),
            revision: 0,
        };

        let json = serde_json::to_value(&conversation).expect("serialize conversation");
        assert_eq!(json["lastError"], Value::Null);
        assert_eq!(json["sync"], Value::Null);
        assert!(json.get("storageKey").is_none());
        assert!(json.get("revision").is_none());

        let parsed: Conversation = serde_json::from_value(json).expect("deserialize conversation");
        assert_eq!(parsed, conversation);
    }

    #[test]
    fn sync_only_conversation_fields_serialize_when_present() {
        let conversation = Conversation {
            id: "conversation-1".to_owned(),
            title: "Contract export".to_owned(),
            system_prompt: String::new(),
            provider_id: "provider-1".to_owned(),
            model_id: "model-1".to_owned(),
            model_label: "gpt-4o-mini".to_owned(),
            params: ChatParams::default(),
            pinned: false,
            archived: false,
            temporary: false,
            created_at: fixed(),
            updated_at: fixed(),
            last_message_at: fixed(),
            message_count: 0,
            last_error: None,
            sync: None,
            storage_key: "00112233445566778899aabbccddeeff".to_owned(),
            revision: 3,
        };

        let json = serde_json::to_value(&conversation).expect("serialize conversation");
        assert_eq!(json["storageKey"], "00112233445566778899aabbccddeeff");
        assert_eq!(json["revision"], 3);

        let parsed: Conversation = serde_json::from_value(json).expect("deserialize conversation");
        assert_eq!(parsed, conversation);
    }

    #[test]
    fn sync_meta_round_trips() {
        let sync = SyncMeta {
            state: SyncState::Synced,
            last_synced_at: Some(fixed()),
            remote_revision: Some("rev-1".to_owned()),
        };

        let json = serde_json::to_value(&sync).expect("serialize sync");
        assert_eq!(json["lastSyncedAt"], "2026-11-04T12:00:00.000Z");
        assert_eq!(json["remoteRevision"], "rev-1");

        let parsed: SyncMeta = serde_json::from_value(json).expect("deserialize sync");
        assert_eq!(parsed, sync);
    }
}
