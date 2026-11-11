//! Self-contained conversation payload build and parse.
//!
//! A conversation payload is the plaintext gzip stream stored inside a
//! conversation envelope. It contains conversation metadata, every message, and
//! attachment references. It never contains provider secrets or API keys. Field
//! names match `contracts/schema/backup-conversation.schema.json`.

use std::sync::OnceLock;

use chatui_domain::{
    Attachment, ChatParams, ContentPart, Conversation, Message, MessageRole, MessageStatus,
    Timestamp, Usage,
};
use serde::{Deserialize, Serialize};
use serde_json::Value;

use crate::error::BackupDataError;
use crate::manifest::validate_against_schema;

/// One message in a conversation payload.
#[derive(Clone, Debug, Deserialize, PartialEq, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct PayloadMessage {
    /// Message ID.
    pub id: String,
    /// Monotonic sequence within the conversation.
    pub sequence: u64,
    /// Message author role.
    pub role: MessageRole,
    /// Message content parts.
    pub parts: Vec<ContentPart>,
    /// Message lifecycle status.
    pub status: MessageStatus,
    /// Token and cost usage, or `null` when unavailable.
    pub usage: Option<Usage>,
    /// Creation timestamp.
    pub created_at: Timestamp,
    /// Last update timestamp.
    pub updated_at: Timestamp,
    /// Soft-deletion timestamp, or `null` when not deleted.
    pub deleted_at: Option<Timestamp>,
}

/// One attachment reference in a conversation payload.
#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct AttachmentReference {
    /// Attachment ID.
    pub id: String,
    /// SHA-256 of the attachment bytes.
    pub sha256: String,
    /// MIME type.
    pub mime_type: String,
    /// Original file name.
    pub name: String,
    /// Size in bytes.
    pub size: u64,
    /// Content-addressed attachment storage key.
    pub storage_key: String,
}

/// Self-contained conversation payload.
#[derive(Clone, Debug, Deserialize, PartialEq, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ConversationPayload {
    /// Conversation ID.
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
    /// Monotonic revision.
    pub revision: u64,
    /// Random 128-bit storage key.
    pub storage_key: String,
    /// All messages in the conversation.
    pub messages: Vec<PayloadMessage>,
    /// Attachment references used by the messages.
    pub attachments: Vec<AttachmentReference>,
}

/// Build a self-contained conversation payload.
pub fn build_conversation_payload(
    conversation: &Conversation,
    messages: &[Message],
    attachments: &[Attachment],
) -> Result<ConversationPayload, BackupDataError> {
    validate_storage_key(&conversation.storage_key)?;

    let messages = messages
        .iter()
        .map(|message| PayloadMessage {
            id: message.id.clone(),
            sequence: message.sequence,
            role: message.role,
            parts: message.parts.clone(),
            status: message.status,
            usage: message.usage.clone(),
            created_at: message.created_at,
            updated_at: message.updated_at,
            deleted_at: message.deleted_at,
        })
        .collect();

    let mut references = Vec::with_capacity(attachments.len());
    for attachment in attachments {
        validate_sha256(&attachment.sha256)?;
        validate_sha256(&attachment.storage_key)?;
        references.push(AttachmentReference {
            id: attachment.id.clone(),
            sha256: attachment.sha256.clone(),
            mime_type: attachment.mime_type.clone(),
            name: attachment.name.clone(),
            size: attachment.size,
            storage_key: attachment.storage_key.clone(),
        });
    }

    let payload = ConversationPayload {
        id: conversation.id.clone(),
        title: conversation.title.clone(),
        system_prompt: conversation.system_prompt.clone(),
        provider_id: conversation.provider_id.clone(),
        model_id: conversation.model_id.clone(),
        model_label: conversation.model_label.clone(),
        params: conversation.params.clone(),
        pinned: conversation.pinned,
        archived: conversation.archived,
        temporary: conversation.temporary,
        created_at: conversation.created_at,
        updated_at: conversation.updated_at,
        last_message_at: conversation.last_message_at,
        revision: conversation.revision.max(1),
        storage_key: conversation.storage_key.clone(),
        messages,
        attachments: references,
    };
    validate_conversation_payload(&payload)?;
    Ok(payload)
}

/// Parse and validate a conversation payload.
pub fn parse_conversation_payload(bytes: &[u8]) -> Result<ConversationPayload, BackupDataError> {
    let payload: ConversationPayload =
        serde_json::from_slice(bytes).map_err(|error| BackupDataError::Json(error.to_string()))?;
    validate_conversation_payload(&payload)?;
    Ok(payload)
}

/// Validate a conversation payload against the shared schema.
pub fn validate_conversation_payload(payload: &ConversationPayload) -> Result<(), BackupDataError> {
    let value =
        serde_json::to_value(payload).map_err(|error| BackupDataError::Json(error.to_string()))?;
    validate_against_schema(payload_validator(), &value)
}

fn validate_storage_key(value: &str) -> Result<(), BackupDataError> {
    if value.len() == 32
        && value
            .bytes()
            .all(|byte| byte.is_ascii_digit() || (b'a'..=b'f').contains(&byte))
    {
        Ok(())
    } else {
        Err(BackupDataError::Invalid(format!(
            "conversation storageKey must be 32 lowercase hexadecimal characters, got {value:?}"
        )))
    }
}

fn validate_sha256(value: &str) -> Result<(), BackupDataError> {
    if value.len() == 64
        && value
            .bytes()
            .all(|byte| byte.is_ascii_digit() || (b'a'..=b'f').contains(&byte))
    {
        Ok(())
    } else {
        Err(BackupDataError::Invalid(format!(
            "attachment key must be 64 lowercase hexadecimal characters, got {value:?}"
        )))
    }
}

fn payload_validator() -> &'static jsonschema::Validator {
    static VALIDATOR: OnceLock<jsonschema::Validator> = OnceLock::new();
    VALIDATOR.get_or_init(|| {
        let schema: Value = serde_json::from_str(include_str!(
            "../../../../contracts/schema/backup-conversation.schema.json"
        ))
        .expect("embedded conversation payload schema is valid JSON");
        jsonschema::options()
            .should_validate_formats(true)
            .build(&schema)
            .expect("embedded conversation payload schema compiles")
    })
}
