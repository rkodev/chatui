//! Automated accessibility audits across the desktop Slint windows.
//!
//! These tests use Slint's testing backend to walk the accessibility tree. They
//! assert that every interactive control exposes an accessible name and that
//! the streaming live region is bounded and polite.

use i_slint_backend_testing::{AccessibleLiveness, AccessibleRole, ElementRoot};
use slint::{ComponentHandle, ModelRc, VecModel};

use crate::chat::composer::ComposerWindow;
use crate::chat::message_list::MessageListWindow;
use crate::conversations::view::HistoryWindow;
use crate::data::view::{DataNavItem, DataWindow};
use crate::models::view::ModelManagementWindow;
use crate::onboarding::view::OnboardingWindow;
use crate::providers::view::ProviderManagementWindow;
use crate::settings::view::{SettingsNavItem, SettingsWindow};
use crate::shell::ShellWindow;

fn accessible_labels(root: &impl ElementRoot) -> Vec<String> {
    root.root_element()
        .query_descendants()
        .find_all()
        .into_iter()
        .filter_map(|element| element.accessible_label())
        .map(|label| label.to_string())
        .collect()
}

fn assert_controls_have_names(root: &impl ElementRoot, surface: &str) {
    for role in [
        AccessibleRole::Button,
        AccessibleRole::Checkbox,
        AccessibleRole::Combobox,
        AccessibleRole::TextInput,
    ] {
        let controls = root
            .root_element()
            .query_descendants()
            .match_accessible_role(role)
            .find_all();

        for control in controls {
            let label = control.accessible_label().unwrap_or_default();
            assert!(
                !label.trim().is_empty(),
                "{surface} exposes a {role:?} without an accessible name"
            );
        }
    }
}

#[test]
fn shell_controls_have_accessible_names_in_both_layouts() {
    i_slint_backend_testing::init_no_event_loop();

    for width in [1024.0, 500.0] {
        let window = ShellWindow::new().expect("shell window");
        window.set_sidebar_visible(width >= 600.0);
        window.set_page_title("Chat".into());
        assert_controls_have_names(&window, &format!("shell at {width}px"));

        let buttons = window
            .root_element()
            .query_descendants()
            .match_accessible_role(AccessibleRole::Button)
            .find_all();
        assert!(!buttons.is_empty(), "shell has no accessible buttons");
    }
}

#[test]
fn settings_controls_have_accessible_names() {
    i_slint_backend_testing::init_no_event_loop();

    for section in 0..7 {
        let window = SettingsWindow::new().expect("settings window");
        window.set_sections(ModelRc::new(VecModel::from(vec![
            SettingsNavItem {
                id: "appearance".into(),
                label: "Appearance".into(),
            },
            SettingsNavItem {
                id: "chat".into(),
                label: "Chat defaults".into(),
            },
            SettingsNavItem {
                id: "providers".into(),
                label: "Providers".into(),
            },
        ])));
        window.set_active_section(section);
        assert_controls_have_names(&window, &format!("settings section {section}"));
    }
}

#[test]
fn data_controls_have_accessible_names() {
    i_slint_backend_testing::init_no_event_loop();

    for section in 0..6 {
        let window = DataWindow::new().expect("data window");
        window.set_sections(ModelRc::new(VecModel::from(vec![
            DataNavItem {
                id: "usage".into(),
                label: "Storage usage".into(),
            },
            DataNavItem {
                id: "export".into(),
                label: "Export".into(),
            },
            DataNavItem {
                id: "import".into(),
                label: "Import".into(),
            },
        ])));
        window.set_active_section(section);
        assert_controls_have_names(&window, &format!("data section {section}"));
    }
}

#[test]
fn provider_controls_have_accessible_names() {
    i_slint_backend_testing::init_no_event_loop();

    let window = ProviderManagementWindow::new().expect("provider window");
    window.set_detail_visible(true);
    window.set_form_visible(true);
    assert_controls_have_names(&window, "providers");
}

#[test]
fn model_controls_have_accessible_names() {
    i_slint_backend_testing::init_no_event_loop();

    let window = ModelManagementWindow::new().expect("model window");
    window.set_manual_visible(true);
    assert_controls_have_names(&window, "models");
}

#[test]
fn onboarding_controls_have_accessible_names() {
    i_slint_backend_testing::init_no_event_loop();

    for step in 0..7 {
        let window = OnboardingWindow::new().expect("onboarding window");
        window.set_step(step);
        window.set_can_skip(step == 1);
        assert_controls_have_names(&window, &format!("onboarding step {step}"));
    }
}

#[test]
fn composer_controls_have_accessible_names() {
    i_slint_backend_testing::init_no_event_loop();

    let window = ComposerWindow::new().expect("composer window");
    assert_controls_have_names(&window, "composer");
}

#[test]
fn message_list_announcement_is_a_bounded_polite_live_region() {
    i_slint_backend_testing::init_no_event_loop();

    let window = MessageListWindow::new().expect("message list window");
    window.set_announcement("Response started".into());
    window.set_announcement_assertive(false);

    let announcement = window
        .root_element()
        .query_descendants()
        .match_accessible_role(AccessibleRole::Text)
        .find_all()
        .into_iter()
        .find(|element| element.accessible_label().as_deref() == Some("Response started"))
        .expect("announcement text node");
    assert_eq!(
        announcement.accessible_live_region(),
        Some(AccessibleLiveness::Polite)
    );
}

/// The shell must host the real feature windows, not the M3.5 placeholder body.
///
/// This is the integration boundary the desktop composition uses: each
/// destination factory returns a real feature window, and the shell embeds it
/// in the matching `ComponentContainer`. The test walks the accessibility tree
/// for a control that only exists in the real view and rejects the retired
/// placeholder copy.
#[test]
fn every_destination_hosts_a_real_feature_view() {
    i_slint_backend_testing::init_no_event_loop();

    let shell = ShellWindow::new().expect("shell window");
    let messages = MessageListWindow::new().expect("message list window");
    let composer = ComposerWindow::new().expect("composer window");
    let history = HistoryWindow::new().expect("history window");
    let providers = ProviderManagementWindow::new().expect("provider window");
    let settings = SettingsWindow::new().expect("settings window");
    let data = DataWindow::new().expect("data window");
    let models = ModelManagementWindow::new().expect("model window");

    macro_rules! factory {
        ($window:expr) => {{
            let handle = $window.clone_strong();
            slint::ComponentFactory::new(move |_context| Some(handle.clone_strong()))
        }};
    }

    shell.set_chat_messages_factory(factory!(messages));
    shell.set_chat_composer_factory(factory!(composer));
    shell.set_history_factory(factory!(history));
    shell.set_providers_factory(factory!(providers));
    shell.set_settings_factory(factory!(settings));
    shell.set_data_factory(factory!(data));
    shell.set_models_factory(factory!(models));

    let destinations = [
        (0, "Chat", "Message"),
        (1, "History", "Search conversations"),
        (2, "Providers", "Add provider"),
        (3, "Settings", "Theme"),
        (4, "Data", "Refresh usage"),
    ];

    for (index, name, control) in destinations {
        shell.set_active_destination(index);
        let labels = accessible_labels(&shell);
        assert!(
            labels.iter().any(|label| label == control),
            "{name} did not render its real control {control:?}: {labels:?}"
        );
        assert!(
            !labels.iter().any(|label| {
                label.contains("placeholder") || label.contains("arrives in M3.5")
            }),
            "{name} still shows retired placeholder copy: {labels:?}"
        );
    }

    shell.set_show_models(true);
    let labels = accessible_labels(&shell);
    assert!(
        labels.iter().any(|label| label == "Search models"),
        "models overlay did not render its real search control: {labels:?}"
    );
    assert!(
        labels.iter().any(|label| label == "Close models"),
        "models overlay did not render its close control: {labels:?}"
    );
}
