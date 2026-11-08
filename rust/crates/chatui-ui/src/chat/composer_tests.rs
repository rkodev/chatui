//! Composer send-key, draft, chip, capability, and failure-preservation tests.

use std::cell::RefCell;
use std::rc::Rc;
use std::sync::Arc;

use chatui_domain::{
    AttachmentKind, InputModality, Model, ModelCapability, ModelSource, SendKey, Timestamp,
};
use slint::Model as _;

use super::attachments::PendingAttachment;
use super::composer::{
    ComposerController, ComposerKeyAction, ComposerState, MAX_COMPOSER_LINES, ModelCapabilities,
};
use super::drafts::{DraftStore, MemoryDraftStore};
use crate::accessibility::{Key, Modifiers};

fn pending(id: &str, kind: AttachmentKind, name: &str, size: u64) -> PendingAttachment {
    PendingAttachment {
        id: id.to_owned(),
        kind,
        mime_type: if kind == AttachmentKind::Image {
            "image/png".to_owned()
        } else {
            "text/plain".to_owned()
        },
        name: name.to_owned(),
        size,
        sha256: "a".repeat(64),
        storage_key: "a".repeat(64),
        width: None,
        height: None,
    }
}

fn model(input_modalities: Vec<InputModality>, capabilities: Vec<ModelCapability>) -> Model {
    let timestamp = Timestamp::parse("2026-01-01T00:00:00.000Z").expect("timestamp");

    Model {
        id: "model-1".to_owned(),
        provider_id: "provider-1".to_owned(),
        remote_id: "test/model".to_owned(),
        label: "Test Model".to_owned(),
        context_window: None,
        max_output_tokens: None,
        capabilities,
        pricing: None,
        canonical_slug: None,
        input_modalities,
        output_modalities: Vec::new(),
        supported_parameters: Vec::new(),
        metadata: None,
        hidden: false,
        pinned: false,
        source: ModelSource::Manual,
        created_at: timestamp,
        updated_at: timestamp,
    }
}

#[test]
fn enter_mode_sends_on_enter_and_shift_enter_inserts_newline() {
    let mut state = ComposerState::with_defaults("conversation-1", SendKey::Enter);
    state.set_text("hello");

    assert_eq!(
        state.handle_key(Key::Enter, Modifiers::NONE),
        ComposerKeyAction::Send
    );
    assert_eq!(
        state.handle_key(Key::Enter, Modifiers::SHIFT),
        ComposerKeyAction::Newline
    );
    assert_eq!(
        state.handle_key(
            Key::Enter,
            Modifiers {
                control: true,
                ..Modifiers::NONE
            }
        ),
        ComposerKeyAction::Send
    );
    assert_eq!(
        state.handle_key(Key::Escape, Modifiers::NONE),
        ComposerKeyAction::Ignore
    );
}

#[test]
fn mod_enter_mode_sends_only_with_modifier() {
    let mut state = ComposerState::with_defaults("conversation-1", SendKey::ModEnter);
    state.set_text("hello");

    assert_eq!(
        state.handle_key(Key::Enter, Modifiers::NONE),
        ComposerKeyAction::Newline
    );
    assert_eq!(
        state.handle_key(
            Key::Enter,
            Modifiers {
                control: true,
                ..Modifiers::NONE
            }
        ),
        ComposerKeyAction::Send
    );
    assert_eq!(
        state.handle_key(
            Key::Enter,
            Modifiers {
                meta: true,
                ..Modifiers::NONE
            }
        ),
        ComposerKeyAction::Send
    );
    assert_eq!(
        state.handle_key(Key::Enter, Modifiers::SHIFT),
        ComposerKeyAction::Newline
    );
}

#[test]
fn send_is_disabled_when_empty_streaming_or_offline() {
    let mut state = ComposerState::with_defaults("conversation-1", SendKey::Enter);

    assert!(!state.can_send());
    state.set_text("   ");
    assert!(!state.can_send());

    state.set_text("hello");
    assert!(state.can_send());

    state.set_streaming(true);
    assert!(!state.can_send());
    state.set_streaming(false);

    state.set_offline(true);
    assert!(!state.can_send());
    state.set_offline(false);

    state.set_sending(true);
    assert!(!state.can_send());
    state.set_sending(false);

    state.set_text("");
    state.add_attachment(pending("a1", AttachmentKind::File, "notes.txt", 10));
    assert!(state.can_send());
}

#[test]
fn auto_grow_stops_at_eight_lines_and_scrolls() {
    let mut state = ComposerState::with_defaults("conversation-1", SendKey::Enter);

    assert_eq!(state.line_count(), 1);
    assert_eq!(state.visible_lines(), 1);
    assert!(!state.scrolls());

    state.set_text("1\n2\n3\n4\n5\n6\n7\n8");
    assert_eq!(state.line_count(), 8);
    assert_eq!(state.visible_lines(), 8);
    assert!(!state.scrolls());

    state.set_text("1\n2\n3\n4\n5\n6\n7\n8\n9");
    assert_eq!(state.line_count(), 9);
    assert_eq!(state.visible_lines(), MAX_COMPOSER_LINES);
    assert!(state.scrolls());
    assert_eq!(state.textarea_height(20.0), 160.0);
}

#[test]
fn drafts_are_keyed_by_conversation_and_restore() {
    let drafts = Arc::new(MemoryDraftStore::new());
    let mut state = ComposerState::new("conversation-1", SendKey::Enter, drafts.clone());

    state.set_text("first draft");
    state.set_conversation("conversation-2");
    assert_eq!(state.text(), "");
    state.set_text("second draft");

    state.set_conversation("conversation-1");
    assert_eq!(state.text(), "first draft");

    state.set_conversation("conversation-2");
    assert_eq!(state.text(), "second draft");
    assert_eq!(
        drafts.draft("conversation-1").as_deref(),
        Some("first draft")
    );
}

#[test]
fn send_failure_restores_text_and_attachments() {
    let mut state = ComposerState::with_defaults("conversation-1", SendKey::Enter);
    state.set_text("hello");
    state.add_attachment(pending("a1", AttachmentKind::File, "notes.txt", 10));

    let send = state.begin_send().expect("send snapshot");
    assert_eq!(send.text, "hello");
    assert_eq!(send.parts.len(), 2);
    assert_eq!(state.text(), "");
    assert!(state.attachments().is_empty());

    state.restore_after_failure(send);
    assert_eq!(state.text(), "hello");
    assert_eq!(state.attachments().len(), 1);
    assert_eq!(state.chips()[0].name, "notes.txt");
}

#[test]
fn cancelled_send_restores_text_and_attachments() {
    let mut state = ComposerState::with_defaults("conversation-1", SendKey::Enter);
    state.set_text("cancel me");
    state.add_attachment(pending("a1", AttachmentKind::Image, "photo.png", 10));

    let send = state.begin_send().expect("send snapshot");
    // The runner reports cancellation through the same failure path.
    state.restore_after_failure(send);

    assert_eq!(state.text(), "cancel me");
    assert_eq!(state.attachments().len(), 1);
}

#[test]
fn chips_show_name_size_and_remove() {
    let mut state = ComposerState::with_defaults("conversation-1", SendKey::Enter);
    state.add_attachment(pending("a1", AttachmentKind::Image, "photo.png", 2048));

    let chips = state.chips();
    assert_eq!(chips.len(), 1);
    assert_eq!(chips[0].name, "photo.png");
    assert_eq!(chips[0].size_label, "2.0 KB");

    assert!(state.remove_attachment("a1"));
    assert!(state.chips().is_empty());
    assert!(!state.remove_attachment("missing"));
}

#[test]
fn capability_warning_for_unsupported_attachments() {
    let mut state = ComposerState::with_defaults("conversation-1", SendKey::Enter);
    state.set_model(Some(&model(
        vec![InputModality::Text],
        vec![ModelCapability::Text],
    )));
    state.add_attachment(pending("a1", AttachmentKind::Image, "photo.png", 10));

    assert!(state.capability_warning().is_some());
    assert_eq!(state.unsupported_attachment_ids(), vec!["a1".to_owned()]);

    state.set_model(Some(&model(
        vec![InputModality::Text, InputModality::Image],
        vec![ModelCapability::Vision],
    )));
    assert!(state.capability_warning().is_none());
    assert!(state.unsupported_attachment_ids().is_empty());
}

#[test]
fn model_capabilities_fall_back_to_capability_flags() {
    let capabilities = ModelCapabilities {
        input_modalities: Vec::new(),
        capabilities: vec![ModelCapability::Vision, ModelCapability::Files],
    };

    assert!(capabilities.accepts_images());
    assert!(capabilities.accepts_files());
}

#[test]
fn slint_window_renders_composer_state() {
    i_slint_backend_testing::init_no_event_loop();

    let mut state = ComposerState::with_defaults("conversation-1", SendKey::Enter);
    state.set_text("hello\nworld");
    state.add_attachment(pending("a1", AttachmentKind::File, "notes.txt", 10));

    let controller = ComposerController::new(state).expect("composer window");
    let window = controller.window();

    assert_eq!(window.get_text(), "hello\nworld");
    assert_eq!(window.get_visible_lines(), 2);
    assert!(!window.get_scrolls());
    assert_eq!(window.get_chips().row_count(), 1);
    assert!(window.get_can_send());
    assert_eq!(window.get_editor_height(), 40.0);
}

#[test]
fn slint_window_invokes_send_and_remove_handlers() {
    i_slint_backend_testing::init_no_event_loop();

    let mut state = ComposerState::with_defaults("conversation-1", SendKey::Enter);
    state.set_text("hello");
    state.add_attachment(pending("a1", AttachmentKind::File, "notes.txt", 10));

    let mut controller = ComposerController::new(state).expect("composer window");
    let sent = Rc::new(RefCell::new(None));
    let sent_clone = Rc::clone(&sent);
    controller.set_on_send(move |send| {
        *sent_clone.borrow_mut() = Some(send);
    });

    let removed = Rc::new(RefCell::new(None));
    let removed_clone = Rc::clone(&removed);
    controller.set_on_remove_attachment(move |id| {
        *removed_clone.borrow_mut() = Some(id);
    });

    controller.window().invoke_remove_attachment("a1".into());
    assert_eq!(removed.borrow().as_deref(), Some("a1"));
    assert!(controller.state().attachments().is_empty());

    controller.window().invoke_send();
    assert!(sent.borrow().is_some());
    assert_eq!(controller.state().text(), "");
}
