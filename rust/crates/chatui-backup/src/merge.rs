//! Deterministic merge rules for the Rust sync engine.
//!
//! The rules are frozen in spec section 13.5 and `DEC-M5-10`:
//!
//! - Messages are immutable and identified by UUID. Merge as a set, ordered by
//!   `sequence`, then `created_at`, then `id`.
//! - Deletion tombstones win over an older update.
//! - Conversation metadata uses last-write-wins by `updated_at`, with
//!   `device_id` as the tiebreaker.
//! - Concurrent additions are both kept and sequences are renumbered
//!   deterministically.
//! - Local messages are never silently dropped.

use std::cmp::Ordering;
use std::collections::{BTreeMap, BTreeSet};

use chatui_domain::Tombstone;
use thiserror::Error;

use crate::conversation_payload::{AttachmentReference, ConversationPayload, PayloadMessage};
use crate::manifest::{AttachmentIndexEntry, ConversationIndexEntry, ManifestTombstone};

/// A merge invariant was violated.
#[derive(Clone, Debug, Eq, Error, PartialEq)]
pub enum MergeError {
    /// The two payloads do not describe the same conversation.
    #[error("cannot merge conversations {local} and {remote}")]
    ConversationIdMismatch {
        /// Local conversation ID.
        local: String,
        /// Remote conversation ID.
        remote: String,
    },
    /// A local message disappeared from the merged set without a tombstone.
    #[error("merge dropped local message {0}")]
    DroppedLocalMessage(String),
}

/// Options for [`merge_conversation`].
#[derive(Clone, Copy, Debug)]
pub struct MergeConversationOptions<'a> {
    /// Device that owns the local payload.
    pub local_device_id: &'a str,
    /// Device that wrote the remote manifest entry.
    pub remote_device_id: &'a str,
    /// Conversation-level tombstones from the merged manifest.
    pub conversation_tombstones: &'a [ManifestTombstone],
    /// Message-level tombstones from the local tombstone store.
    pub message_tombstones: &'a [Tombstone],
}

/// Merged message set.
#[derive(Clone, Debug, PartialEq)]
pub struct MergedMessages {
    /// Messages ordered by sequence, creation time, then ID.
    pub messages: Vec<PayloadMessage>,
    /// True when duplicate sequences forced a deterministic renumber.
    pub renumbered: bool,
}

/// Merged conversation payload.
#[derive(Clone, Debug, PartialEq)]
pub struct MergedConversation {
    /// Merged payload.
    pub payload: ConversationPayload,
    /// True when a conversation tombstone is at or after the winning update.
    pub deleted: bool,
    /// True when duplicate sequences forced a deterministic renumber.
    pub renumbered: bool,
}

/// Merged manifest indexes.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct MergedManifest {
    /// Conversation index entries ordered by ID.
    pub conversations: Vec<ConversationIndexEntry>,
    /// Attachment index entries ordered by SHA-256.
    pub attachments: Vec<AttachmentIndexEntry>,
    /// Tombstones ordered by deletion time, device, then conversation.
    pub tombstones: Vec<ManifestTombstone>,
}

/// Order messages by sequence, then creation time, then ID.
pub fn compare_messages(left: &PayloadMessage, right: &PayloadMessage) -> Ordering {
    left.sequence
        .cmp(&right.sequence)
        .then_with(|| left.created_at.cmp(&right.created_at))
        .then_with(|| left.id.cmp(&right.id))
}

/// Merge two message sets by UUID.
///
/// A message ID present on both sides is the same immutable message; the later
/// `updated_at` wins so a local edit is not lost. A message tombstone removes
/// the message only when the deletion is at or after the message update.
/// Duplicate sequences are renumbered to `0..n-1` in the deterministic sort
/// order.
pub fn merge_messages(
    local: &[PayloadMessage],
    remote: &[PayloadMessage],
    message_tombstones: &[Tombstone],
) -> Result<MergedMessages, MergeError> {
    let mut by_id: BTreeMap<String, PayloadMessage> = BTreeMap::new();

    for message in local.iter().chain(remote.iter()) {
        match by_id.get(&message.id) {
            Some(existing) if !message_wins(message, existing) => {}
            _ => {
                by_id.insert(message.id.clone(), message.clone());
            }
        }
    }

    let tombstones = latest_message_tombstones(message_tombstones);
    let mut kept: Vec<PayloadMessage> = by_id
        .into_values()
        .filter(|message| match tombstones.get(&message.id) {
            Some(tombstone) => message.updated_at > tombstone.deleted_at,
            None => true,
        })
        .collect();

    kept.sort_by(compare_messages);

    let mut seen = BTreeSet::new();
    let mut collision = false;
    for message in &kept {
        if !seen.insert(message.sequence) {
            collision = true;
        }
    }

    if collision {
        for (index, message) in kept.iter_mut().enumerate() {
            message.sequence = index as u64;
        }
    }

    let merged_ids: BTreeSet<&str> = kept.iter().map(|message| message.id.as_str()).collect();
    for message in local {
        let tombstoned = tombstones
            .get(&message.id)
            .is_some_and(|tombstone| message.updated_at <= tombstone.deleted_at);
        if !tombstoned && !merged_ids.contains(message.id.as_str()) {
            return Err(MergeError::DroppedLocalMessage(message.id.clone()));
        }
    }

    Ok(MergedMessages {
        messages: kept,
        renumbered: collision,
    })
}

/// Merge two conversation payloads.
///
/// Metadata is last-write-wins by `updated_at` with `device_id` as the
/// tiebreaker. Messages and attachments are merged as sets. A conversation
/// tombstone at or after the winning update marks the conversation deleted.
pub fn merge_conversation(
    local: &ConversationPayload,
    remote: &ConversationPayload,
    options: MergeConversationOptions<'_>,
) -> Result<MergedConversation, MergeError> {
    if local.id != remote.id {
        return Err(MergeError::ConversationIdMismatch {
            local: local.id.clone(),
            remote: remote.id.clone(),
        });
    }

    let remote_wins = metadata_wins(
        local,
        remote,
        options.local_device_id,
        options.remote_device_id,
    );
    let winner = if remote_wins { remote } else { local };

    let merged = merge_messages(
        &local.messages,
        &remote.messages,
        options.message_tombstones,
    )?;
    let attachments = merge_attachments(&local.attachments, &remote.attachments);

    let payload = ConversationPayload {
        messages: merged.messages,
        attachments,
        revision: local.revision.max(remote.revision),
        ..winner.clone()
    };

    let deleted = latest_conversation_tombstone(options.conversation_tombstones, &local.id)
        .is_some_and(|tombstone| tombstone.deleted_at >= winner.updated_at);

    Ok(MergedConversation {
        payload,
        deleted,
        renumbered: merged.renumbered,
    })
}

/// Merge two manifest indexes.
///
/// Conversations keep the higher revision; on equal revision the later
/// `updated_at` wins, then the higher `device_id`. Attachments are a set keyed
/// by SHA-256. Tombstones are a set keyed by conversation ID. A conversation
/// whose tombstone is at or after its winning update is removed from the index.
pub fn merge_manifests(
    local: &crate::manifest::ManifestBody,
    remote: &crate::manifest::ManifestBody,
) -> MergedManifest {
    let mut conversations: BTreeMap<String, ConversationIndexEntry> = BTreeMap::new();
    for entry in local
        .conversations
        .iter()
        .chain(remote.conversations.iter())
    {
        match conversations.get(&entry.id) {
            Some(existing)
                if compare_conversation_entries(entry, existing) != Ordering::Greater => {}
            _ => {
                conversations.insert(entry.id.clone(), entry.clone());
            }
        }
    }

    let mut attachments: BTreeMap<String, AttachmentIndexEntry> = BTreeMap::new();
    for entry in local.attachments.iter().chain(remote.attachments.iter()) {
        match attachments.get(&entry.sha256) {
            Some(existing) if entry.created_at <= existing.created_at => {}
            _ => {
                attachments.insert(entry.sha256.clone(), entry.clone());
            }
        }
    }

    let mut tombstones: BTreeMap<String, ManifestTombstone> = BTreeMap::new();
    for entry in local.tombstones.iter().chain(remote.tombstones.iter()) {
        match tombstones.get(&entry.conversation_id) {
            Some(existing) if compare_tombstones(entry, existing) != Ordering::Greater => {}
            _ => {
                tombstones.insert(entry.conversation_id.clone(), entry.clone());
            }
        }
    }

    let merged_tombstones: Vec<ManifestTombstone> = {
        let mut values: Vec<ManifestTombstone> = tombstones.into_values().collect();
        values.sort_by(compare_tombstones);
        values
    };

    let deleted_at_by_conversation: BTreeMap<&str, _> = merged_tombstones
        .iter()
        .map(|tombstone| (tombstone.conversation_id.as_str(), tombstone.deleted_at))
        .collect();

    let mut merged_conversations: Vec<ConversationIndexEntry> = conversations
        .into_values()
        .filter(
            |entry| match deleted_at_by_conversation.get(entry.id.as_str()) {
                Some(deleted_at) => *deleted_at < entry.updated_at,
                None => true,
            },
        )
        .collect();
    merged_conversations.sort_by(|left, right| left.id.cmp(&right.id));

    let mut merged_attachments: Vec<AttachmentIndexEntry> = attachments.into_values().collect();
    merged_attachments.sort_by(|left, right| left.sha256.cmp(&right.sha256));

    MergedManifest {
        conversations: merged_conversations,
        attachments: merged_attachments,
        tombstones: merged_tombstones,
    }
}

fn message_wins(candidate: &PayloadMessage, existing: &PayloadMessage) -> bool {
    candidate.updated_at > existing.updated_at
        || (candidate.updated_at == existing.updated_at && candidate.id > existing.id)
}

fn latest_message_tombstones(tombstones: &[Tombstone]) -> BTreeMap<String, Tombstone> {
    let mut latest: BTreeMap<String, Tombstone> = BTreeMap::new();

    for tombstone in tombstones {
        let Some(message_id) = tombstone.message_id.as_deref() else {
            continue;
        };

        match latest.get(message_id) {
            Some(existing)
                if tombstone.deleted_at < existing.deleted_at
                    || (tombstone.deleted_at == existing.deleted_at
                        && tombstone.device_id <= existing.device_id) => {}
            _ => {
                latest.insert(message_id.to_owned(), tombstone.clone());
            }
        }
    }

    latest
}

fn latest_conversation_tombstone<'a>(
    tombstones: &'a [ManifestTombstone],
    conversation_id: &str,
) -> Option<&'a ManifestTombstone> {
    let mut latest: Option<&ManifestTombstone> = None;

    for tombstone in tombstones {
        if tombstone.conversation_id != conversation_id {
            continue;
        }
        match latest {
            Some(existing)
                if tombstone.deleted_at < existing.deleted_at
                    || (tombstone.deleted_at == existing.deleted_at
                        && tombstone.device_id <= existing.device_id) => {}
            _ => latest = Some(tombstone),
        }
    }

    latest
}

fn merge_attachments(
    local: &[AttachmentReference],
    remote: &[AttachmentReference],
) -> Vec<AttachmentReference> {
    let mut by_id: BTreeMap<String, AttachmentReference> = BTreeMap::new();

    for attachment in local.iter().chain(remote.iter()) {
        by_id
            .entry(attachment.id.clone())
            .or_insert_with(|| attachment.clone());
    }

    by_id.into_values().collect()
}

fn metadata_wins(
    local: &ConversationPayload,
    remote: &ConversationPayload,
    local_device_id: &str,
    remote_device_id: &str,
) -> bool {
    match remote.updated_at.cmp(&local.updated_at) {
        Ordering::Greater => true,
        Ordering::Less => false,
        Ordering::Equal => remote_device_id > local_device_id,
    }
}

fn compare_conversation_entries(
    left: &ConversationIndexEntry,
    right: &ConversationIndexEntry,
) -> Ordering {
    left.revision
        .cmp(&right.revision)
        .then_with(|| left.updated_at.cmp(&right.updated_at))
        .then_with(|| left.device_id.cmp(&right.device_id))
        .then_with(|| left.id.cmp(&right.id))
}

fn compare_tombstones(left: &ManifestTombstone, right: &ManifestTombstone) -> Ordering {
    left.deleted_at
        .cmp(&right.deleted_at)
        .then_with(|| left.device_id.cmp(&right.device_id))
        .then_with(|| left.conversation_id.cmp(&right.conversation_id))
}
