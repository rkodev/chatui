//! Data-screen integration tests.
//!
//! These tests exercise the controller and view model together with the
//! in-memory data service. They cover usage display and refresh, export
//! options, import merge/replace, invalid imports, clear confirmation, and
//! per-key deletion.

use std::sync::Arc;

use chatui_domain::{
    Attachment, AttachmentKind, BrowserCors, ChatParams, ContentPart, Conversation, Message,
    MessageRole, MessageStatus, Model, ModelCapability, ModelSource, Provider, ProviderAuthType,
    ProviderKind, Timestamp,
};

use crate::data::route::DataSection;
use crate::data::service::{DataService, MemoryDataService};
use crate::data::state::{CLEAR_ALL_CONFIRMATION, ExportFormat, ImportMode};
use crate::data::view::DataController;
use slint::Model as _;

fn timestamp(seconds: u32) -> Timestamp {
    Timestamp::parse(&format!("2026-11-07T12:00:{seconds:02}.000Z")).expect("valid timestamp")
}

fn provider(id: &str, label: &str) -> Provider {
    Provider {
        id: id.to_owned(),
        preset_id: "custom".to_owned(),
        label: label.to_owned(),
        kind: ProviderKind::OpenaiCompatible,
        base_url: "https://example.com/v1".to_owned(),
        auth_type: ProviderAuthType::Bearer,
        secret_id: None,
        headers: std::collections::BTreeMap::new(),
        enabled: true,
        browser_cors: BrowserCors::Unknown,
        metadata: None,
        created_at: timestamp(0),
        updated_at: timestamp(0),
    }
}

fn model(id: &str, provider_id: &str) -> Model {
    Model {
        id: id.to_owned(),
        provider_id: provider_id.to_owned(),
        remote_id: format!("remote-{id}"),
        label: format!("Model {id}"),
        context_window: Some(128_000),
        max_output_tokens: Some(4_096),
        capabilities: vec![ModelCapability::Text],
        pricing: None,
        canonical_slug: None,
        input_modalities: Vec::new(),
        output_modalities: Vec::new(),
        supported_parameters: Vec::new(),
        metadata: None,
        hidden: false,
        pinned: false,
        source: ModelSource::Manual,
        created_at: timestamp(0),
        updated_at: timestamp(0),
    }
}

fn conversation(id: &str, provider_id: &str, model_id: &str, created: u32) -> Conversation {
    Conversation {
        id: id.to_owned(),
        title: format!("Conversation {id}"),
        system_prompt: String::new(),
        provider_id: provider_id.to_owned(),
        model_id: model_id.to_owned(),
        model_label: format!("Model {model_id}"),
        params: ChatParams::default(),
        pinned: false,
        archived: false,
        temporary: false,
        created_at: timestamp(created),
        updated_at: timestamp(created),
        last_message_at: timestamp(created),
        message_count: 0,
        last_error: None,
        sync: None,
        storage_key: String::new(),
        revision: 0,
    }
}

fn message(id: &str, conversation_id: &str, sequence: u64, text: &str) -> Message {
    Message {
        id: id.to_owned(),
        conversation_id: conversation_id.to_owned(),
        sequence,
        role: MessageRole::User,
        parts: vec![ContentPart::Text {
            text: text.to_owned(),
        }],
        status: MessageStatus::Complete,
        provider_id: Some("provider-1".to_owned()),
        model_id: Some("model-1".to_owned()),
        model_label: Some("Model model-1".to_owned()),
        usage: None,
        finish_reason: None,
        error: None,
        latency_ms: None,
        first_token_ms: None,
        created_at: timestamp(0),
        updated_at: timestamp(0),
        deleted_at: None,
    }
}

fn attachment(id: &str, message_id: &str, name: &str) -> Attachment {
    Attachment {
        id: id.to_owned(),
        message_id: Some(message_id.to_owned()),
        kind: AttachmentKind::File,
        mime_type: "text/plain".to_owned(),
        name: name.to_owned(),
        size: 5,
        sha256: "a".repeat(64),
        storage_key: "a".repeat(64),
        width: None,
        height: None,
        created_at: timestamp(0),
    }
}

fn seeded_service() -> Arc<MemoryDataService> {
    let service = MemoryDataService::new();
    let mut provider = provider("provider-1", "One");
    provider.secret_id = Some("provider-provider-1".to_owned());
    provider.metadata = Some(serde_json::json!({
        "key": { "label": "Personal key" }
    }));
    service.add_provider(provider);
    service.add_model(model("model-1", "provider-1"));
    service.add_conversation(conversation("conversation-1", "provider-1", "model-1", 0));
    let mut first = message("message-1", "conversation-1", 0, "hello");
    first.parts.push(ContentPart::File {
        attachment_id: "attachment-1".to_owned(),
        mime_type: "text/plain".to_owned(),
        name: "notes.txt".to_owned(),
    });
    service.add_message(first);
    service.add_message(message("message-2", "conversation-1", 1, "world"));
    service.add_attachment(
        attachment("attachment-1", "message-1", "notes.txt"),
        Some("aGVsbG8=".to_owned()),
    );
    service.add_secret("provider-provider-1", b"super-secret".to_vec());
    Arc::new(service)
}

fn controller(service: Arc<MemoryDataService>) -> DataController {
    i_slint_backend_testing::init_no_event_loop();
    DataController::new(service).expect("data window")
}

#[test]
fn usage_is_displayed_and_refreshable() {
    let service = seeded_service();
    let controller = controller(Arc::clone(&service));
    let window = controller.window();

    assert_eq!(window.get_usage_rows().row_count(), 6);
    assert_eq!(
        window.get_usage_rows().row_data(0).expect("row").label,
        "Conversations"
    );
    assert_eq!(window.get_usage_rows().row_data(0).expect("row").count, "1");
    assert!(!window.get_usage_total().is_empty());

    service.clear_all().expect("clear");
    window.invoke_refresh_usage();

    assert_eq!(window.get_usage_rows().row_data(0).expect("row").count, "0");
    assert_eq!(window.get_usage_total(), "0 B");
}

#[test]
fn export_options_are_explicit_and_attachment_bytes_are_opt_in() {
    let controller = controller(seeded_service());
    let window = controller.window();

    assert!(!window.get_include_attachment_bytes());
    assert_eq!(window.get_export_includes().row_count(), 5);
    assert_eq!(window.get_export_excludes().row_count(), 3);
    assert!(window.get_plaintext_warning().contains("plaintext"));

    window.invoke_include_attachment_bytes_changed(true);
    assert!(controller.state().include_attachment_bytes);

    window.invoke_export_all();
    let summary = controller.state().last_export.expect("export summary");
    assert!(summary.includes_attachment_bytes);
    assert_eq!(summary.conversations, 1);
    assert_eq!(summary.messages, 2);
    assert_eq!(summary.attachments, 1);
    assert_eq!(summary.providers, 1);
    assert_eq!(summary.models, 1);
    assert!(summary.includes_settings);
}

#[test]
fn per_conversation_json_and_markdown_export_work() {
    let controller = controller(seeded_service());
    let window = controller.window();

    window.invoke_export_format_changed(ExportFormat::Json.index() as i32);
    window.invoke_export_conversation();
    let json = controller.state().last_export.expect("json summary");
    assert_eq!(json.conversations, 1);
    assert!(json.includes_settings);

    window.invoke_export_format_changed(ExportFormat::Markdown.index() as i32);
    window.invoke_export_conversation();
    let markdown = controller.state().last_export.expect("markdown summary");
    assert_eq!(markdown.conversations, 1);
    assert!(!markdown.includes_settings);
    assert!(!markdown.includes_attachment_bytes);
}

#[test]
fn import_merges_by_default_and_replace_is_explicit() {
    let source = seeded_service();
    let archive = source
        .export_all(&crate::data::state::ExportRequest::all())
        .expect("source export")
        .content;

    let target = MemoryDataService::new();
    target.add_provider(provider("provider-2", "Two"));
    target.add_model(model("model-2", "provider-2"));
    target.add_conversation(conversation("conversation-2", "provider-2", "model-2", 5));
    let controller = controller(Arc::new(target));
    let window = controller.window();

    assert_eq!(controller.state().import_mode, ImportMode::Merge);
    window.invoke_import_text_changed(archive.clone().into());
    window.invoke_import_archive();

    let summary = controller.state().last_import.expect("merge summary");
    assert_eq!(summary.mode, ImportMode::Merge);
    assert_eq!(window.get_conversations().row_count(), 2);

    window.invoke_import_mode_changed(ImportMode::Replace.index() as i32);
    window.invoke_import_text_changed(archive.into());
    window.invoke_import_archive();
    assert_eq!(
        controller
            .state()
            .error
            .as_ref()
            .map(|error| error.title.as_str()),
        Some("Confirmation required")
    );

    window.invoke_replace_confirmed_changed(true);
    window.invoke_import_archive();
    let summary = controller.state().last_import.expect("replace summary");
    assert_eq!(summary.mode, ImportMode::Replace);
    assert_eq!(window.get_conversations().row_count(), 1);
}

#[test]
fn invalid_import_is_rejected_without_data_loss() {
    let controller = controller(seeded_service());
    let window = controller.window();

    window.invoke_import_text_changed("not json".into());
    window.invoke_import_archive();

    assert_eq!(
        controller
            .state()
            .error
            .as_ref()
            .map(|error| error.title.as_str()),
        Some("Import rejected")
    );
    assert_eq!(window.get_conversations().row_count(), 1);
    assert!(controller.state().last_import.is_none());
}

#[test]
fn clear_all_requires_typed_confirmation() {
    let controller = controller(seeded_service());
    let window = controller.window();

    window.invoke_clear_confirmation_changed("wrong".into());
    window.invoke_clear_all();
    assert_eq!(
        controller
            .state()
            .error
            .as_ref()
            .map(|error| error.title.as_str()),
        Some("Confirmation required")
    );
    assert!(controller.state().last_clear.is_none());

    window.invoke_clear_confirmation_changed(CLEAR_ALL_CONFIRMATION.into());
    assert!(window.get_clear_confirmed());
    window.invoke_clear_all();

    let summary = controller.state().last_clear.expect("clear summary");
    assert_eq!(summary.conversations, 1);
    assert_eq!(summary.messages, 2);
    assert_eq!(summary.attachments, 1);
    assert_eq!(summary.secrets, 1);
    assert_eq!(window.get_conversations().row_count(), 0);
    assert_eq!(window.get_provider_keys().row_count(), 0);
}

#[test]
fn per_key_deletion_requires_a_second_click() {
    let controller = controller(seeded_service());
    let window = controller.window();

    assert!(window.get_provider_keys().row_data(0).expect("key").has_key);

    window.invoke_remove_key("provider-1".into());
    assert_eq!(
        controller.state().pending_key_removal.as_deref(),
        Some("provider-1")
    );
    assert!(window.get_provider_keys().row_data(0).expect("key").has_key);

    window.invoke_remove_key("provider-1".into());
    assert!(!window.get_provider_keys().row_data(0).expect("key").has_key);
    assert!(controller.state().pending_key_removal.is_none());
}

#[test]
fn backup_placeholder_links_to_the_backup_section() {
    let controller = controller(seeded_service());
    let window = controller.window();

    window.invoke_select_section(DataSection::Backup.index() as i32);
    assert_eq!(window.get_backup_title(), "Backup");
    assert!(window.get_backup_message().contains("does not back up"));
    assert_eq!(window.get_backup_link_label(), "Open backup settings");
}
