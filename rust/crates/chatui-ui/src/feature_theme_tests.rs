//! Light and dark palette checks for the embedded feature windows.

use i_slint_backend_testing::{AccessibleRole, ElementRoot};
use slint::ComponentHandle;
use slint::{ModelRc, VecModel};
use std::sync::Arc;

use crate::chat::composer::ComposerWindow;
use crate::chat::message_list::MessageListWindow;
use crate::chat::view::ChatWindow;
use crate::chat::{MessageListConfig, MessageListController, MessageListState};
use crate::conversations::view::HistoryRow;
use crate::conversations::view::HistoryWindow;
use crate::data::service::MemoryDataService;
use crate::data::view::DataWindow;
use crate::data::{DataController, DataSection};
use crate::models::view::ModelListRow;
use crate::models::view::ModelManagementWindow;
use crate::onboarding::view::OnboardingWindow;
use crate::providers::view::ProviderManagementWindow;
use crate::providers::view::ProviderRow;
use crate::settings::view::SettingsWindow;
use crate::settings::{
    MemoryProviderModelCatalog, MemorySettingsStore, SettingsController, SettingsRoute,
    SettingsService,
};
use crate::shell::{ShellController, ShellState};
use crate::tokens::{Rgba, Tokens};
use chatui_domain::{ContentPart, Message, MessageRole, MessageStatus, Theme, Timestamp};

fn corner_color(window: &impl ComponentHandle) -> (u8, u8, u8) {
    let snapshot = window
        .window()
        .take_snapshot()
        .expect("snapshot feature window");
    let pixel = snapshot.as_slice()[0];
    (pixel.r, pixel.g, pixel.b)
}

fn rgb(color: Rgba) -> (u8, u8, u8) {
    (color.r, color.g, color.b)
}

fn labelled_bounds(window: &impl ElementRoot, label: &str) -> (f32, f32, f32, f32) {
    let element = window
        .root_element()
        .query_descendants()
        .find_all()
        .into_iter()
        .find(|element| {
            element.accessible_label().as_deref() == Some(label)
                && element.computed_opacity() > 0.0
                && element.size().width > 0.0
                && element.size().height > 0.0
        })
        .unwrap_or_else(|| panic!("missing visible element: {label}"));
    let position = element.absolute_position();
    let size = element.size();
    (position.x, position.y, size.width, size.height)
}

fn assert_below(window: &impl ElementRoot, above: &str, below: &str) {
    let (_, top, _, height) = labelled_bounds(window, above);
    let (_, next_top, _, _) = labelled_bounds(window, below);
    assert!(
        top + height <= next_top + 1.0,
        "{below} overlaps {above}: {next_top} < {}",
        top + height
    );
}

fn assert_in_view(window: &impl ElementRoot, label: &str, height: f32) {
    let (_, top, _, control_height) = labelled_bounds(window, label);
    assert!(
        top >= 0.0 && top + control_height <= height,
        "{label} is not reachable in the viewport: {top} + {control_height} > {height}"
    );
}

macro_rules! assert_window_follows_theme {
    ($window:ty) => {{
        let window = <$window>::new().expect("create feature window");
        assert_eq!(corner_color(&window), rgb(Tokens::light().color_bg_canvas));

        window.set_dark(true);
        assert_eq!(corner_color(&window), rgb(Tokens::dark().color_bg_canvas));

        window.set_dark(false);
        assert_eq!(corner_color(&window), rgb(Tokens::light().color_bg_canvas));
    }};
}

#[test]
fn every_feature_window_follows_the_dark_and_light_palette() {
    slint::platform::set_platform(Box::new(i_slint_backend_testing::TestingBackend::new(
        i_slint_backend_testing::TestingBackendOptions {
            mock_time: true,
            threading: false,
            renderer_name: Some("software".into()),
        },
    )))
    .expect("initialize software testing backend");

    assert_window_follows_theme!(ChatWindow);
    assert_window_follows_theme!(MessageListWindow);
    assert_window_follows_theme!(ComposerWindow);
    assert_window_follows_theme!(HistoryWindow);
    assert_window_follows_theme!(ProviderManagementWindow);
    assert_window_follows_theme!(SettingsWindow);
    assert_window_follows_theme!(DataWindow);
    assert_window_follows_theme!(ModelManagementWindow);
    assert_window_follows_theme!(OnboardingWindow);
}

fn prepare_surface(window: &impl ComponentHandle, width: f32, height: f32) {
    window.show().expect("show native surface");
    window
        .window()
        .set_size(slint::LogicalSize::new(width, height));
    window
        .window()
        .dispatch_event(slint::platform::WindowEvent::Resized {
            size: slint::LogicalSize::new(width, height),
        });
    i_slint_backend_testing::mock_elapsed_time(std::time::Duration::ZERO);
}

fn capture_surface(
    name: &str,
    window: &(impl ComponentHandle + ElementRoot),
    width: f32,
    height: f32,
    dark: bool,
) {
    prepare_surface(window, width, height);
    let snapshot = window
        .window()
        .take_snapshot()
        .expect("render native surface");
    if let Some(directory) = std::env::var_os("CHATUI_SCREENSHOT_DIR") {
        let directory = std::path::PathBuf::from(directory);
        std::fs::create_dir_all(&directory).expect("screenshot directory");
        let theme = if dark { "dark" } else { "light" };
        let pixels: Vec<u8> = snapshot
            .as_slice()
            .iter()
            .flat_map(|pixel| [pixel.r, pixel.g, pixel.b])
            .collect();
        image::save_buffer(
            directory.join(format!("{name}-{}-{theme}.png", width as u32)),
            &pixels,
            snapshot.width(),
            snapshot.height(),
            image::ColorType::Rgb8,
        )
        .expect("save native screenshot");
    }
    let mut visible_controls = 0;
    for role in [
        AccessibleRole::Button,
        AccessibleRole::Checkbox,
        AccessibleRole::Combobox,
        AccessibleRole::TextInput,
    ] {
        for control in window
            .root_element()
            .query_descendants()
            .match_accessible_role(role)
            .find_all()
        {
            if control.computed_opacity() <= 0.0 || control.size().width <= 0.0 {
                continue;
            }
            visible_controls += 1;
            let x = control.absolute_position().x;
            let control_width = control.size().width;
            assert!(
                x >= -1.0 && x + control_width <= width + 1.0,
                "{name} at {width}px: {:?} overflows horizontally ({x} + {control_width})",
                control.accessible_label()
            );
        }
    }
    assert!(
        visible_controls > 0,
        "{name} has no visible controls in {:?}",
        window.root_element().size()
    );
    match name {
        "onboarding" => {
            assert_below(
                window,
                "One key. A world of models.",
                "A quick, secure setup",
            );
            assert_below(
                window,
                "Get an API key",
                "Local history. Direct connections. Your choice.",
            );
        }
        "onboarding-ready" => {
            assert_below(window, "One key. A world of models.", "Connected and ready");
        }
        "settings" if width < 680.0 => assert_below(window, "Settings section", "Theme"),
        "settings-return" => assert_in_view(window, "Theme", height),
        "data-import" if width < 680.0 => assert_below(window, "Data section", "Import mode"),
        "backup" | "backup-drive" if width < 680.0 => {
            assert_below(window, "Settings section", "Backup backend");
        }
        "backup-actions" => {
            assert_in_view(window, "Back up now", height);
            assert_in_view(window, "Restore from backup", height);
            assert_in_view(window, "Delete remote backup", height);
        }
        "provider-form" if width >= 680.0 => {
            let (left, _, list_width, _) = labelled_bounds(window, "Add provider");
            let (form_left, _, _, _) = labelled_bounds(window, "Base URL");
            assert!(
                left + list_width < form_left,
                "provider form covers the list"
            );
        }
        "providers-error" => {
            assert_below(window, "Connection could not be verified", "All providers");
        }
        "model-form" | "model-popup-form" => {
            assert_in_view(window, "Add model", height);
        }
        "model-popup" => assert_below(window, "Close models", "Search models"),
        _ => {}
    }
    window.hide().expect("hide native surface");
}

fn sample_message(id: &str, sequence: u64, role: MessageRole, text: &str) -> Message {
    let now = Timestamp::now();
    Message {
        id: id.to_owned(),
        conversation_id: "preview".to_owned(),
        sequence,
        role,
        parts: vec![ContentPart::Text {
            text: text.to_owned(),
        }],
        status: MessageStatus::Complete,
        provider_id: None,
        model_id: None,
        model_label: Some("OpenRouter Auto".to_owned()),
        usage: None,
        finish_reason: None,
        error: None,
        latency_ms: None,
        first_token_ms: None,
        created_at: now,
        updated_at: now,
        deleted_at: None,
    }
}

#[test]
fn populated_surfaces_fit_compact_and_wide_windows_in_both_themes() {
    slint::platform::set_platform(Box::new(i_slint_backend_testing::TestingBackend::new(
        i_slint_backend_testing::TestingBackendOptions {
            mock_time: true,
            threading: false,
            renderer_name: Some("software".into()),
        },
    )))
    .expect("software renderer");
    for width in [420.0, 1180.0] {
        for dark in [false, true] {
            let height = if width < 600.0 { 680.0 } else { 820.0 };
            let onboarding = OnboardingWindow::new().expect("onboarding");
            onboarding.set_dark(dark);
            onboarding.set_catalog_count(4);
            onboarding.set_catalog_summary("A world of models, with one account.".into());
            capture_surface("onboarding", &onboarding, width, height, dark);
            onboarding.set_step(1);
            capture_surface("onboarding-key", &onboarding, width, height, dark);
            onboarding.set_step(6);
            onboarding.set_can_send(true);
            onboarding.set_selected_model("OpenRouter Auto".into());
            capture_surface("onboarding-ready", &onboarding, width, height, dark);

            let providers = ProviderManagementWindow::new().expect("providers");
            providers.set_dark(dark);
            providers.set_providers(ModelRc::new(VecModel::from(vec![ProviderRow {
                id: "personal".into(),
                label: "Personal workspace".into(),
                meta: "OpenRouter / Key stored".into(),
            }])));
            providers.set_detail_visible(true);
            providers.set_selected_provider_id("personal".into());
            providers.set_detail_label("Personal workspace".into());
            providers.set_detail_preset("OpenRouter".into());
            providers.set_detail_base_url("https://openrouter.ai/api/v1".into());
            providers.set_detail_key_label("Personal key".into());
            providers.set_detail_usage("$1.25".into());
            providers.set_detail_limit("$10.00".into());
            providers.set_detail_status("Connected".into());
            providers.set_detail_key_present(true);
            providers.set_detail_enabled(true);
            providers.set_detail_docs_url("https://openrouter.ai/docs".into());
            providers.set_detail_model_count_text("3 models available".into());
            capture_surface("providers", &providers, width, height, dark);
            if width < 680.0 {
                providers.set_error_title("Connection could not be verified".into());
                providers.set_error_message("Check your API key and try again.".into());
                capture_surface("providers-error", &providers, width, height, dark);
                providers.set_error_title("".into());
                providers.set_error_message("".into());
            }
            providers.set_detail_visible(false);
            providers.set_form_visible(true);
            providers.set_form_label("Local workspace".into());
            providers.set_form_base_url("http://localhost:11434/v1".into());
            capture_surface("provider-form", &providers, width, height, dark);

            let models = ModelManagementWindow::new().expect("models");
            models.set_dark(dark);
            models.set_provider_labels(ModelRc::new(VecModel::from(vec![
                "Personal workspace".into(),
            ])));
            models.set_provider_label("Personal workspace".into());
            models.set_summary("2 models / Choose the right fit for your next idea.".into());
            models.set_models(ModelRc::new(VecModel::from(vec![
                ModelListRow {
                    id: "auto".into(),
                    remote_id: "openrouter/auto".into(),
                    label: "OpenRouter Auto".into(),
                    group_label: "Your models".into(),
                    group_start: true,
                    meta: "128K context / Automatic routing".into(),
                    chips: "Text / Vision / Streaming".into(),
                    is_default: true,
                    ..ModelListRow::default()
                },
                ModelListRow {
                    id: "reasoning".into(),
                    remote_id: "workspace/reasoning".into(),
                    label: "Thoughtful reasoning".into(),
                    meta: "200K context".into(),
                    chips: "Text / Reasoning / Tools".into(),
                    ..ModelListRow::default()
                },
            ])));
            models.set_selected_model_id("auto".into());
            capture_surface("models", &models, width, height, dark);
            models.set_manual_visible(true);
            models.set_manual_remote_id("workspace/my-model".into());
            models.set_manual_label("My local model".into());
            capture_surface("model-form", &models, width, height, dark);

            let settings = SettingsController::new(Arc::new(SettingsService::new(
                Arc::new(MemorySettingsStore::new()),
                Arc::new(MemoryProviderModelCatalog::new(Vec::new(), Vec::new())),
            )))
            .expect("settings");
            settings.window().set_dark(dark);
            capture_surface("settings", settings.window(), width, height, dark);
            settings.select_route(SettingsRoute::Backup);
            capture_surface("backup", settings.window(), width, height, dark);
            prepare_surface(settings.window(), width, height);
            settings.window().window().dispatch_event(
                slint::platform::WindowEvent::PointerScrolled {
                    position: slint::LogicalPosition::new(width - 60.0, height - 80.0),
                    delta_x: 0.0,
                    delta_y: -2_000.0,
                },
            );
            i_slint_backend_testing::mock_elapsed_time(std::time::Duration::from_millis(250));
            assert_in_view(
                settings.window(),
                &settings.window().get_backup_passphrase_warning(),
                height,
            );
            capture_surface("backup-actions", settings.window(), width, height, dark);
            settings.select_route(SettingsRoute::Appearance);
            capture_surface("settings-return", settings.window(), width, height, dark);
            settings.select_route(SettingsRoute::Backup);
            settings.window().invoke_backup_backend_changed(3);
            capture_surface("backup-drive", settings.window(), width, height, dark);

            let data = DataController::new(Arc::new(MemoryDataService::new())).expect("data");
            data.window().set_dark(dark);
            data.select_section(DataSection::Import);
            data.window().invoke_import_text_changed(
                "{\n  \"format\": \"chatui\",\n  \"conversations\": []\n}".into(),
            );
            capture_surface("data-import", data.window(), width, height, dark);

            let history = HistoryWindow::new().expect("history");
            history.set_dark(dark);
            history.set_rows(ModelRc::new(VecModel::from(vec![
                HistoryRow {
                    title: "Today".into(),
                    is_header: true,
                    ..HistoryRow::default()
                },
                HistoryRow {
                    id: "plan".into(),
                    title: "A thoughtful plan for the next product launch".into(),
                    timestamp: "Today, 9:41".into(),
                    pinned: true,
                    draft: true,
                    active: true,
                    ..HistoryRow::default()
                },
                HistoryRow {
                    id: "ideas".into(),
                    title: "Exploring a new idea together".into(),
                    timestamp: "Today, 8:30".into(),
                    ..HistoryRow::default()
                },
            ])));
            capture_surface("history", &history, width, height, dark);
            history.set_archived(true);
            history.set_rows(ModelRc::new(VecModel::from(vec![HistoryRow {
                id: "archive".into(),
                title: "An earlier project worth keeping".into(),
                timestamp: "Yesterday".into(),
                archived: true,
                ..HistoryRow::default()
            }])));
            capture_surface("history-archived", &history, width, height, dark);

            let mut state = ShellState::new(width);
            state.theme.theme = if dark { Theme::Dark } else { Theme::Light };
            let shell = ShellController::new(state).expect("shell");
            let mut messages = MessageListState::new("preview", MessageListConfig::default());
            messages.apply_newest_page(chatui_storage::MessagePage {
                messages: vec![
                    sample_message("question", 1, MessageRole::User, "How can we make this launch feel more thoughtful?"),
                    sample_message("answer", 2, MessageRole::Assistant,
                        "## Start with the people\n\nA great launch makes the next step feel **clear and inviting**.\n\n- Tell one simple story.\n- Make the first action effortless.\n\n```rust\nlet next_step = \"Start small, learn quickly\";\n```\n\n[Read the launch checklist](https://example.com/checklist)."),
                ], has_more: false,
            });
            let messages = MessageListController::new(messages).expect("messages");
            messages.window().set_dark(dark);
            let composer = ComposerWindow::new().expect("composer");
            composer.set_dark(dark);
            composer.set_text("Help me turn this into clear next steps.".into());
            composer.set_can_send(true);
            let handle = messages.window().clone_strong();
            shell
                .window()
                .set_chat_messages_factory(slint::ComponentFactory::new(move |_| {
                    Some(handle.clone_strong())
                }));
            let handle = composer.clone_strong();
            shell
                .window()
                .set_chat_composer_factory(slint::ComponentFactory::new(move |_| {
                    Some(handle.clone_strong())
                }));
            shell
                .window()
                .set_chat_model_label("OpenRouter Auto".into());
            prepare_surface(shell.window(), width, height);
            for _ in 0..8 {
                i_slint_backend_testing::mock_elapsed_time(std::time::Duration::from_millis(16));
                shell
                    .window()
                    .window()
                    .take_snapshot()
                    .expect("chat layout");
                messages.poll_layout();
            }
            capture_surface("chat", shell.window(), width, height, dark);
            messages.poll_layout();
            messages.apply_newest_page(chatui_storage::MessagePage {
                messages: Vec::new(),
                has_more: false,
            });
            composer.set_text("".into());
            composer.set_can_send(false);
            capture_surface("welcome", shell.window(), width, height, dark);
            models.set_manual_visible(false);
            models.set_picker_mode(true);
            let handle = models.clone_strong();
            shell
                .window()
                .set_models_factory(slint::ComponentFactory::new(move |_| {
                    Some(handle.clone_strong())
                }));
            shell.window().set_show_models(true);
            capture_surface("model-popup", shell.window(), width, height, dark);
            shell.window().set_show_models(false);
            i_slint_backend_testing::mock_elapsed_time(std::time::Duration::ZERO);
            models.set_manual_visible(true);
            shell.window().set_show_models(true);
            capture_surface("model-popup-form", shell.window(), width, height, dark);
            shell.window().set_show_models(false);
            i_slint_backend_testing::mock_elapsed_time(std::time::Duration::ZERO);
        }
    }
}
