//! Settings integration tests.
//!
//! These tests cover the boundaries that unit tests cannot: persistence through
//! the SQLite settings repository, new-conversation seeding from the persisted
//! defaults, and the real Markdown and message-list consumers.

use std::sync::Arc;

use chatui_domain::{
    BrowserCors, ChatFontSize, ChatParams, ContentPart, Density, InputModality, Message,
    MessageRole, MessageStatus, Model, ModelCapability, ModelSource, OutputModality, Provider,
    ProviderAuthType, ProviderKind, ReasoningEffort, SendKey, Settings, Theme, Timestamp, Usage,
};
use chatui_storage::{
    ConversationRepository, MessagePage, ModelRepository, ProviderRepository, SettingsRepository,
    SqliteConversationRepository, SqliteModelRepository, SqliteProviderRepository,
    SqliteSettingsRepository, open_database, open_in_memory,
};

use crate::chat::message_list::{MessageListConfig, MessageListState};
use crate::conversations::factory::{ConversationFactory, DefaultConversationFactory};
use crate::markdown::{Block, Inline, render_markdown};
use crate::settings::route::SettingsRoute;
use crate::settings::service::{
    MemoryProviderModelCatalog, MemorySettingsStore, RepositorySettingsPersistence,
    SettingsPersistence, SettingsService,
};
use crate::settings::state::SettingsViewModel;

fn ts(value: &str) -> Timestamp {
    Timestamp::parse(value).expect("valid timestamp")
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
        created_at: ts("2026-11-07T09:00:00.000Z"),
        updated_at: ts("2026-11-07T09:00:00.000Z"),
    }
}

fn model(id: &str, provider_id: &str, label: &str) -> Model {
    Model {
        id: id.to_owned(),
        provider_id: provider_id.to_owned(),
        remote_id: format!("remote/{id}"),
        label: label.to_owned(),
        context_window: Some(128_000),
        max_output_tokens: Some(4_096),
        capabilities: vec![ModelCapability::Text],
        pricing: None,
        canonical_slug: None,
        input_modalities: vec![InputModality::Text],
        output_modalities: vec![OutputModality::Text],
        supported_parameters: Vec::new(),
        metadata: None,
        hidden: false,
        pinned: false,
        source: ModelSource::Manual,
        created_at: ts("2026-11-07T09:00:00.000Z"),
        updated_at: ts("2026-11-07T09:00:00.000Z"),
    }
}

fn message_with_usage() -> Message {
    Message {
        id: "message-1".to_owned(),
        conversation_id: "conversation-1".to_owned(),
        sequence: 1,
        role: MessageRole::Assistant,
        parts: vec![ContentPart::Text {
            text: "Hello".to_owned(),
        }],
        status: MessageStatus::Complete,
        provider_id: Some("p1".to_owned()),
        model_id: Some("m1".to_owned()),
        model_label: Some("Model One".to_owned()),
        usage: Some(Usage {
            input_tokens: Some(10),
            output_tokens: Some(20),
            total_tokens: Some(30),
            cost_usd: Some(0.001),
            estimated: false,
        }),
        finish_reason: None,
        error: None,
        latency_ms: Some(120),
        first_token_ms: Some(40),
        created_at: ts("2026-11-07T09:00:00.000Z"),
        updated_at: ts("2026-11-07T09:00:00.000Z"),
        deleted_at: None,
    }
}

#[tokio::test]
async fn settings_persist_across_repository_instances() {
    let pool = open_in_memory().await.expect("open in-memory database");
    let repository: Arc<dyn SettingsRepository> =
        Arc::new(SqliteSettingsRepository::new(pool.clone()));
    let persistence = RepositorySettingsPersistence::new(repository);

    let mut settings = persistence.load().await.expect("load defaults");
    settings.theme = Theme::Dark;
    settings.density = Density::Compact;
    settings.chat_font_size = ChatFontSize::Lg;
    settings.send_key = SendKey::ModEnter;
    settings.show_token_usage = false;
    settings.load_remote_images = true;
    settings.default_system_prompt = "Be concise.".to_owned();
    settings.default_params.temperature = Some(0.2);
    settings.default_params.max_output_tokens = Some(256);
    settings.onboarding_completed = true;
    persistence.save(&settings).await.expect("save settings");

    let reopened: Arc<dyn SettingsRepository> = Arc::new(SqliteSettingsRepository::new(pool));
    let reopened = RepositorySettingsPersistence::new(reopened);
    let loaded = reopened.load().await.expect("reload settings");

    assert_eq!(loaded.theme, Theme::Dark);
    assert_eq!(loaded.density, Density::Compact);
    assert_eq!(loaded.chat_font_size, ChatFontSize::Lg);
    assert_eq!(loaded.send_key, SendKey::ModEnter);
    assert!(!loaded.show_token_usage);
    assert!(loaded.load_remote_images);
    assert_eq!(loaded.default_system_prompt, "Be concise.");
    assert_eq!(loaded.default_params.temperature, Some(0.2));
    assert_eq!(loaded.default_params.max_output_tokens, Some(256));
    assert!(loaded.onboarding_completed);
}

#[tokio::test]
async fn settings_persist_across_database_reopen() {
    let directory = tempfile::tempdir().expect("temporary directory");
    let path = directory.path().join("chatui.sqlite3");

    let pool = open_database(&path).await.expect("open database");
    let repository: Arc<dyn SettingsRepository> =
        Arc::new(SqliteSettingsRepository::new(pool.clone()));
    let persistence = RepositorySettingsPersistence::new(repository);
    let mut settings = persistence.load().await.expect("load defaults");
    settings.theme = Theme::Dark;
    settings.send_key = SendKey::ModEnter;
    settings.default_system_prompt = "Be concise.".to_owned();
    persistence.save(&settings).await.expect("save settings");
    pool.close().await;

    let pool = open_database(&path).await.expect("reopen database");
    let repository: Arc<dyn SettingsRepository> = Arc::new(SqliteSettingsRepository::new(pool));
    let persistence = RepositorySettingsPersistence::new(repository);
    let loaded = persistence.load().await.expect("reload settings");

    assert_eq!(loaded.theme, Theme::Dark);
    assert_eq!(loaded.send_key, SendKey::ModEnter);
    assert_eq!(loaded.default_system_prompt, "Be concise.");
}

#[tokio::test]
async fn default_prompt_and_params_seed_new_conversations() {
    let pool = open_in_memory().await.expect("open in-memory database");
    let conversations: Arc<dyn ConversationRepository> =
        Arc::new(SqliteConversationRepository::new(pool.clone()));
    let providers: Arc<dyn ProviderRepository> =
        Arc::new(SqliteProviderRepository::new(pool.clone()));
    let models: Arc<dyn ModelRepository> = Arc::new(SqliteModelRepository::new(pool.clone()));
    let settings_repository: Arc<dyn SettingsRepository> =
        Arc::new(SqliteSettingsRepository::new(pool.clone()));

    providers
        .insert(&provider("p1", "One"))
        .await
        .expect("insert provider");
    models
        .insert(&model("m1", "p1", "Model One"))
        .await
        .expect("insert model");

    let persistence = RepositorySettingsPersistence::new(Arc::clone(&settings_repository));
    let mut settings = persistence.load().await.expect("load defaults");
    settings.default_provider_id = Some("p1".to_owned());
    settings.default_model_id = Some("m1".to_owned());
    settings.default_system_prompt = "Be concise.".to_owned();
    settings.default_params = ChatParams {
        temperature: Some(0.2),
        top_p: Some(0.9),
        max_output_tokens: Some(256),
        stop: vec!["END".to_owned()],
        reasoning_effort: Some(ReasoningEffort::High),
        streaming: false,
        extra: serde_json::Map::new(),
    };
    persistence.save(&settings).await.expect("save settings");

    let factory =
        DefaultConversationFactory::new(conversations, providers, models, settings_repository);
    let conversation = factory.create(None).await.expect("create conversation");

    assert_eq!(conversation.system_prompt, "Be concise.");
    assert_eq!(conversation.params.temperature, Some(0.2));
    assert_eq!(conversation.params.top_p, Some(0.9));
    assert_eq!(conversation.params.max_output_tokens, Some(256));
    assert_eq!(conversation.params.stop, vec!["END".to_owned()]);
    assert_eq!(
        conversation.params.reasoning_effort,
        Some(ReasoningEffort::High)
    );
    assert!(!conversation.params.streaming);
}

#[test]
fn token_usage_setting_hides_message_metadata() {
    let mut settings = chatui_storage::default_settings();
    settings.show_token_usage = false;
    let view_model = SettingsViewModel::new(
        settings,
        Vec::new(),
        Vec::new(),
        SettingsRoute::ChatDefaults,
    );

    let mut list = MessageListState::new("conversation-1", MessageListConfig::default());
    list.apply_newest_page(MessagePage {
        messages: vec![message_with_usage()],
        has_more: false,
    });
    view_model.apply_to_message_list(&mut list);

    let rows = list.visible_rows();
    assert_eq!(rows.len(), 1);
    assert_eq!(rows[0].metadata.usage, None);

    let mut settings = chatui_storage::default_settings();
    settings.show_token_usage = true;
    let view_model = SettingsViewModel::new(
        settings,
        Vec::new(),
        Vec::new(),
        SettingsRoute::ChatDefaults,
    );
    view_model.apply_to_message_list(&mut list);

    let rows = list.visible_rows();
    assert!(rows[0].metadata.usage.is_some());
}

#[test]
fn remote_image_setting_changes_markdown_rendering() {
    let mut settings = chatui_storage::default_settings();
    settings.load_remote_images = false;
    let view_model = SettingsViewModel::new(
        settings,
        Vec::new(),
        Vec::new(),
        SettingsRoute::ChatDefaults,
    );

    let rendered = render_markdown(
        "![diagram](https://example.com/diagram.png)",
        &view_model.render_options(),
    );
    let image = first_image(&rendered.blocks).expect("image block");
    assert!(!image.loaded);
    assert!(image.is_blocked());

    let mut settings = chatui_storage::default_settings();
    settings.load_remote_images = true;
    let view_model = SettingsViewModel::new(
        settings,
        Vec::new(),
        Vec::new(),
        SettingsRoute::ChatDefaults,
    );
    let rendered = render_markdown(
        "![diagram](https://example.com/diagram.png)",
        &view_model.render_options(),
    );
    let image = first_image(&rendered.blocks).expect("image block");
    assert!(image.loaded);
    assert!(!image.is_blocked());
}

fn first_image(blocks: &[Block]) -> Option<&crate::markdown::ImageRef> {
    for block in blocks {
        match block {
            Block::Paragraph(inlines) => {
                for inline in inlines {
                    if let Inline::Image(image) = inline {
                        return Some(image);
                    }
                }
            }
            Block::BlockQuote { blocks, .. } => {
                if let Some(image) = first_image(blocks) {
                    return Some(image);
                }
            }
            Block::List { items, .. } => {
                for item in items {
                    if let Some(image) = first_image(item) {
                        return Some(image);
                    }
                }
            }
            _ => {}
        }
    }

    None
}

#[test]
fn settings_view_model_does_not_expose_secrets() {
    let settings = chatui_storage::default_settings();
    let mut provider = provider("p1", "One");
    provider.secret_id = Some("provider-secret-id".to_owned());
    let view_model = SettingsViewModel::new(
        settings,
        vec![provider],
        vec![model("m1", "p1", "Model One")],
        SettingsRoute::ChatDefaults,
    );

    let rendered = format!("{view_model:?}");
    assert!(!rendered.contains("sk-test"));
    assert!(!rendered.contains("provider-secret-id"));
    assert!(
        !view_model
            .about()
            .privacy
            .iter()
            .any(|line| line.contains("sk-"))
    );
}

#[test]
fn service_updates_are_visible_to_the_view_model() {
    let store = Arc::new(MemorySettingsStore::new());
    let catalog = Arc::new(MemoryProviderModelCatalog::new(
        vec![provider("p1", "One")],
        vec![model("m1", "p1", "Model One")],
    ));
    let service = SettingsService::new(store, catalog);

    service.set_theme(Theme::Dark).expect("theme");
    service.set_send_key(SendKey::ModEnter).expect("send key");
    service.set_show_token_usage(false).expect("token usage");
    service.set_load_remote_images(true).expect("remote images");
    service
        .set_default_provider_model(Some("p1"), Some("m1"))
        .expect("defaults");

    let view_model = service.load().expect("load");
    assert_eq!(view_model.settings.theme, Theme::Dark);
    assert_eq!(view_model.settings.send_key, SendKey::ModEnter);
    assert!(!view_model.settings.show_token_usage);
    assert!(view_model.settings.load_remote_images);
    assert_eq!(view_model.default_provider_id(), Some("p1"));
    assert_eq!(view_model.default_model_id(), Some("m1"));
    assert_eq!(view_model.default_provider_index(), 1);
    assert_eq!(view_model.default_model_index(), 1);
}

#[test]
fn settings_defaults_are_complete() {
    let settings: Settings = chatui_storage::default_settings();
    assert_eq!(settings.theme, Theme::System);
    assert_eq!(settings.density, Density::Comfortable);
    assert_eq!(settings.chat_font_size, ChatFontSize::Md);
    assert_eq!(settings.send_key, SendKey::Enter);
    assert!(settings.show_token_usage);
    assert!(!settings.load_remote_images);
    assert!(!settings.onboarding_completed);
}
