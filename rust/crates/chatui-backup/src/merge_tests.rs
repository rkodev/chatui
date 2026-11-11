use chatui_domain::{ChatParams, ContentPart, MessageRole, MessageStatus, Timestamp, Tombstone};

use crate::conversation_payload::{AttachmentReference, ConversationPayload, PayloadMessage};
use crate::manifest::{
    AttachmentIndexEntry, ConversationIndexEntry, MANIFEST_FORMAT_VERSION, ManifestBody,
    ManifestTombstone,
};
use crate::merge::{
    MergeConversationOptions, MergeError, compare_messages, merge_conversation, merge_manifests,
    merge_messages,
};

fn ts(seconds: u32) -> Timestamp {
    Timestamp::parse(&format!("2026-11-04T12:00:{seconds:02}.000Z")).expect("valid timestamp")
}

fn message(id: &str, sequence: u64, created: u32, updated: u32) -> PayloadMessage {
    PayloadMessage {
        id: id.to_owned(),
        sequence,
        role: MessageRole::User,
        parts: vec![ContentPart::Text {
            text: format!("message {id}"),
        }],
        status: MessageStatus::Complete,
        usage: None,
        created_at: ts(created),
        updated_at: ts(updated),
        deleted_at: None,
    }
}

fn attachment(id: &str, sha256: &str) -> AttachmentReference {
    AttachmentReference {
        id: id.to_owned(),
        sha256: sha256.to_owned(),
        mime_type: "text/plain".to_owned(),
        name: "notes.txt".to_owned(),
        size: 5,
        storage_key: sha256.to_owned(),
    }
}

fn payload(
    id: &str,
    revision: u64,
    updated: u32,
    messages: Vec<PayloadMessage>,
    attachments: Vec<AttachmentReference>,
) -> ConversationPayload {
    ConversationPayload {
        id: id.to_owned(),
        title: format!("Conversation {id}"),
        system_prompt: String::new(),
        provider_id: "provider-1".to_owned(),
        model_id: "model-1".to_owned(),
        model_label: "Model One".to_owned(),
        params: ChatParams::default(),
        pinned: false,
        archived: false,
        temporary: false,
        created_at: ts(0),
        updated_at: ts(updated),
        last_message_at: ts(updated),
        revision,
        storage_key: "a".repeat(32),
        messages,
        attachments,
    }
}

fn conversation_entry(
    id: &str,
    revision: u64,
    updated: u32,
    device_id: &str,
) -> ConversationIndexEntry {
    ConversationIndexEntry {
        id: id.to_owned(),
        storage_key: "a".repeat(32),
        revision,
        updated_at: ts(updated),
        deleted_at: None,
        device_id: device_id.to_owned(),
        object_key: format!("conversations/{}.enc", "a".repeat(32)),
    }
}

fn manifest_tombstone(conversation_id: &str, deleted: u32, device_id: &str) -> ManifestTombstone {
    ManifestTombstone {
        conversation_id: conversation_id.to_owned(),
        deleted_at: ts(deleted),
        device_id: device_id.to_owned(),
    }
}

fn manifest(
    conversations: Vec<ConversationIndexEntry>,
    attachments: Vec<AttachmentIndexEntry>,
    tombstones: Vec<ManifestTombstone>,
) -> ManifestBody {
    ManifestBody {
        format_version: MANIFEST_FORMAT_VERSION,
        created_at: ts(0),
        updated_at: ts(0),
        device_id: "device-local".to_owned(),
        conversations,
        attachments,
        tombstones,
    }
}

fn options<'a>(
    local_device_id: &'a str,
    remote_device_id: &'a str,
    conversation_tombstones: &'a [ManifestTombstone],
    message_tombstones: &'a [Tombstone],
) -> MergeConversationOptions<'a> {
    MergeConversationOptions {
        local_device_id,
        remote_device_id,
        conversation_tombstones,
        message_tombstones,
    }
}

#[test]
fn messages_merge_by_uuid_and_order_by_sequence_created_at_id() {
    let local = vec![message("b", 1, 1, 1), message("a", 0, 2, 2)];
    let remote = vec![message("c", 2, 0, 0)];

    let merged = merge_messages(&local, &remote, &[]).expect("merge messages");

    assert!(!merged.renumbered);
    assert_eq!(
        merged
            .messages
            .iter()
            .map(|message| message.id.as_str())
            .collect::<Vec<_>>(),
        vec!["a", "b", "c"]
    );
    assert!(
        merged
            .messages
            .windows(2)
            .all(|pair| { compare_messages(&pair[0], &pair[1]) == std::cmp::Ordering::Less })
    );
}

#[test]
fn concurrent_additions_keep_both_and_renumber_deterministically() {
    let local = vec![message("a", 0, 0, 0), message("b", 1, 1, 1)];
    let remote = vec![message("a", 0, 0, 0), message("c", 1, 2, 2)];

    let first = merge_messages(&local, &remote, &[]).expect("first merge");
    let second = merge_messages(&local, &remote, &[]).expect("second merge");

    assert!(first.renumbered);
    assert_eq!(first, second);
    assert_eq!(
        first
            .messages
            .iter()
            .map(|message| (message.id.as_str(), message.sequence))
            .collect::<Vec<_>>(),
        vec![("a", 0), ("b", 1), ("c", 2)]
    );
}

#[test]
fn later_message_update_wins_for_the_same_uuid() {
    let local = vec![message("a", 0, 0, 1)];
    let remote = vec![message("a", 0, 0, 2)];

    let merged = merge_messages(&local, &remote, &[]).expect("merge messages");

    assert_eq!(merged.messages.len(), 1);
    assert_eq!(merged.messages[0].updated_at, ts(2));
}

#[test]
fn message_tombstone_wins_over_an_older_update() {
    let local = vec![message("a", 0, 0, 1)];
    let tombstone = Tombstone {
        id: "tombstone-1".to_owned(),
        conversation_id: "conversation-1".to_owned(),
        message_id: Some("a".to_owned()),
        deleted_at: ts(2),
        device_id: "device-remote".to_owned(),
    };

    let merged = merge_messages(&local, &[], &[tombstone]).expect("merge messages");

    assert!(merged.messages.is_empty());
}

#[test]
fn message_tombstone_does_not_remove_a_newer_update() {
    let local = vec![message("a", 0, 0, 3)];
    let tombstone = Tombstone {
        id: "tombstone-1".to_owned(),
        conversation_id: "conversation-1".to_owned(),
        message_id: Some("a".to_owned()),
        deleted_at: ts(2),
        device_id: "device-remote".to_owned(),
    };

    let merged = merge_messages(&local, &[], &[tombstone]).expect("merge messages");

    assert_eq!(merged.messages.len(), 1);
    assert_eq!(merged.messages[0].id, "a");
}

#[test]
fn merge_never_drops_a_local_message_without_a_tombstone() {
    let local = vec![message("a", 0, 0, 0), message("b", 1, 1, 1)];
    let remote = vec![message("a", 0, 0, 0)];

    let merged = merge_messages(&local, &remote, &[]).expect("merge messages");

    assert_eq!(merged.messages.len(), 2);
    assert!(merged.messages.iter().any(|message| message.id == "b"));
}

#[test]
fn conversation_metadata_uses_lww_then_device_id() {
    let local = payload("conversation-1", 1, 1, vec![], vec![]);
    let mut remote = payload("conversation-1", 1, 2, vec![], vec![]);
    remote.title = "Remote title".to_owned();

    let merged = merge_conversation(&local, &remote, options("device-a", "device-b", &[], &[]))
        .expect("merge conversation");
    assert_eq!(merged.payload.title, "Remote title");

    let mut tie = payload("conversation-1", 1, 2, vec![], vec![]);
    tie.title = "Tie title".to_owned();
    let local_tie = payload("conversation-1", 1, 2, vec![], vec![]);
    let merged = merge_conversation(&local_tie, &tie, options("device-z", "device-a", &[], &[]))
        .expect("merge conversation");
    assert_eq!(merged.payload.title, "Conversation conversation-1");
}

#[test]
fn conversation_tombstone_wins_over_an_older_update() {
    let local = payload("conversation-1", 1, 1, vec![], vec![]);
    let remote = payload("conversation-1", 1, 2, vec![], vec![]);
    let tombstones = vec![manifest_tombstone("conversation-1", 2, "device-b")];

    let merged = merge_conversation(
        &local,
        &remote,
        options("device-a", "device-b", &tombstones, &[]),
    )
    .expect("merge conversation");

    assert!(merged.deleted);
}

#[test]
fn conversation_merge_keeps_both_sides_messages_and_attachments() {
    let local = payload(
        "conversation-1",
        1,
        1,
        vec![message("a", 0, 0, 0)],
        vec![attachment("attachment-1", &"b".repeat(64))],
    );
    let remote = payload(
        "conversation-1",
        2,
        2,
        vec![message("a", 0, 0, 0), message("b", 1, 1, 1)],
        vec![attachment("attachment-2", &"c".repeat(64))],
    );

    let merged = merge_conversation(&local, &remote, options("device-a", "device-b", &[], &[]))
        .expect("merge conversation");

    assert_eq!(merged.payload.revision, 2);
    assert_eq!(merged.payload.messages.len(), 2);
    assert_eq!(merged.payload.attachments.len(), 2);
}

#[test]
fn conversation_id_mismatch_is_rejected() {
    let local = payload("conversation-1", 1, 1, vec![], vec![]);
    let remote = payload("conversation-2", 1, 1, vec![], vec![]);

    let error = merge_conversation(&local, &remote, options("device-a", "device-b", &[], &[]))
        .expect_err("mismatched conversations must fail");

    assert!(matches!(error, MergeError::ConversationIdMismatch { .. }));
}

#[test]
fn manifest_merge_uses_revision_then_updated_at_then_device_id() {
    let local = manifest(
        vec![
            conversation_entry("conversation-1", 2, 1, "device-a"),
            conversation_entry("conversation-2", 1, 1, "device-a"),
        ],
        vec![],
        vec![],
    );
    let remote = manifest(
        vec![
            conversation_entry("conversation-1", 1, 9, "device-z"),
            conversation_entry("conversation-2", 1, 2, "device-b"),
        ],
        vec![],
        vec![],
    );

    let merged = merge_manifests(&local, &remote);

    assert_eq!(merged.conversations.len(), 2);
    assert_eq!(merged.conversations[0].id, "conversation-1");
    assert_eq!(merged.conversations[0].revision, 2);
    assert_eq!(merged.conversations[1].id, "conversation-2");
    assert_eq!(merged.conversations[1].device_id, "device-b");
}

#[test]
fn manifest_merge_drops_conversations_deleted_at_or_after_the_update() {
    let local = manifest(
        vec![conversation_entry("conversation-1", 1, 1, "device-a")],
        vec![],
        vec![],
    );
    let remote = manifest(
        vec![],
        vec![],
        vec![manifest_tombstone("conversation-1", 1, "device-b")],
    );

    let merged = merge_manifests(&local, &remote);

    assert!(merged.conversations.is_empty());
    assert_eq!(merged.tombstones.len(), 1);
}

#[test]
fn manifest_merge_unions_attachments_by_sha256() {
    let local = manifest(
        vec![],
        vec![AttachmentIndexEntry {
            sha256: "b".repeat(64),
            size: 1,
            object_key: format!("attachments/{}.enc", "b".repeat(64)),
            created_at: ts(1),
        }],
        vec![],
    );
    let remote = manifest(
        vec![],
        vec![
            AttachmentIndexEntry {
                sha256: "b".repeat(64),
                size: 1,
                object_key: format!("attachments/{}.enc", "b".repeat(64)),
                created_at: ts(2),
            },
            AttachmentIndexEntry {
                sha256: "a".repeat(64),
                size: 2,
                object_key: format!("attachments/{}.enc", "a".repeat(64)),
                created_at: ts(1),
            },
        ],
        vec![],
    );

    let merged = merge_manifests(&local, &remote);

    assert_eq!(merged.attachments.len(), 2);
    assert_eq!(merged.attachments[0].sha256, "a".repeat(64));
    assert_eq!(merged.attachments[1].sha256, "b".repeat(64));
}
