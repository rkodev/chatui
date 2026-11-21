//! Slint onboarding view and controller.
//!
//! The controller binds the framework-independent [`OnboardingViewModel`] to a
//! Slint window. The key field uses `InputType.password`, and the view model
//! only exposes a masked representation outside that field. Completion is
//! exposed through [`OnboardingRoute`] so the desktop app can switch to the
//! shell after onboarding.

use std::cell::RefCell;
use std::rc::Rc;
use std::sync::Arc;

use chatui_contracts::ProviderPreset;
use chatui_secrets::SecretValue;
use slint::{ComponentHandle, ModelRc, SharedString, VecModel};

use crate::onboarding::error::{
    OnboardingError, describe_catalog_error, describe_onboarding_error,
};
use crate::onboarding::service::{ConnectInput, OnboardingServiceLike};
use crate::onboarding::state::{OnboardingStep, OnboardingViewModel};
use crate::tasks::UiTasks;

/// Where the app should route after the onboarding controller is created or
/// completes.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum OnboardingRoute {
    /// Show the onboarding window.
    Onboarding,
    /// Onboarding is complete; show the shell.
    Shell,
}

impl OnboardingRoute {
    /// Whether the shell should be shown.
    pub const fn is_shell(self) -> bool {
        matches!(self, Self::Shell)
    }
}

slint::slint! {
    import { AppButton as Button, AppText as Text, AppIcon, Design } from "../design.slint";
    import { CheckBox, LineEdit, Palette, ScrollView } from "std-widgets.slint";

    export component OnboardingWindow inherits Window {
        title: "Welcome to ChatUI";
        preferred-width: 720px;
        preferred-height: 760px;
        min-width: 420px;
        min-height: 560px;

        in property <int> step: 0;
        in property <string> preset-label: "OpenRouter";
        in property <bool> recommended: true;
        in property <string> key-input: "";
        in property <string> key-masked: "";
        in property <string> key-label: "";
        in property <bool> remember-key: true;
        in property <int> catalog-count: 0;
        in property <string> catalog-summary: "";
        in property <string> catalog-notice: "";
        in property <[string]> models: [];
        in property <string> selected-model: "";
        in property <string> default-model-notice: "";
        in property <string> error-title: "";
        in property <string> error-message: "";
        in property <string> error-details: "";
        in property <bool> can-send: false;
        in property <bool> can-skip: false;
        in property <bool> onboarding-completed: false;
        in-out property <bool> continue-requested: false;
        in-out property <string> link-error: "";
        in property <bool> dark: false;

        background: Design.colors(root.dark).canvas;
        init => {
            Palette.color-scheme = root.dark ? ColorScheme.dark : ColorScheme.light;
        }
        changed dark => {
            Palette.color-scheme = root.dark ? ColorScheme.dark : ColorScheme.light;
        }

        callback begin-key-entry();
        callback key-changed(string);
        callback label-changed(string);
        callback remember-changed(bool);
        callback connect();
        callback skip();
        callback open-key-portal();

        Rectangle {
          x: 0px;
          y: 0px;
          width: 100%;
          height: 100%;
          VerticalLayout {
            padding: 24px;
            spacing: 24px;

            HorizontalLayout {
                spacing: 10px;
                AppIcon { kind: 0; tint: Design.colors(root.dark).accent; width: 26px; height: 26px; }
                Text { text: "ChatUI"; font-size: 20px; font-weight: 700; }
                Rectangle { horizontal-stretch: 1; }
                Text { text: "YOUR PRIVATE WORKSPACE"; color: Design.colors(root.dark).muted; font-size: 10px; }
            }

            if (!root.link-error.is-empty) : Text {
                text: root.link-error;
                color: Design.colors(root.dark).danger;
                wrap: word-wrap;
                accessible-live-region: polite;
            }

            HorizontalLayout {
                alignment: center;
                vertical-stretch: 0;
                VerticalLayout {
                    width: min(520px, root.width - 48px);
                    spacing: 12px;

                    Text {
                        text: root.step == 0 ? "Good ideas start with\na conversation."
                            : root.step == 6 ? "You're all set."
                            : "Make your first connection.";
                        font-size: root.width < 520px ? 28px : 34px;
                        font-weight: 700;
                        wrap: word-wrap;
                    }
                    Text {
                        text: root.step == 6 ? "Your models are ready. Let's make something great."
                            : "Your favorite AI models, one calm place to think. Bring your own key to get started.";
                        color: Design.colors(root.dark).muted;
                        wrap: word-wrap;
                        font-size: 14px;
                    }

                    HorizontalLayout {
                        spacing: 12px;
                        for label[index] in ["1  Connect", "2  Verify", "3  Start"] : VerticalLayout {
                            spacing: 6px;
                            Rectangle {
                                height: 3px;
                                border-radius: 2px;
                                background: (index == 0 || (index == 1 && root.step >= 2) || root.step == 6)
                                    ? Design.colors(root.dark).accent : Design.colors(root.dark).border;
                            }
                            Text {
                                text: label;
                                color: Design.colors(root.dark).muted;
                                font-size: 11px;
                            }
                        }
                    }
                }
            }

            HorizontalLayout {
                alignment: center;
                vertical-stretch: 1;
                Rectangle {
                    width: min(520px, root.width - 48px);
                    min-height: 200px;
                    preferred-height: 340px;
                    vertical-stretch: 1;
                    background: Design.colors(root.dark).raised;
                    border-color: Design.colors(root.dark).border;
                    border-width: 1px;
                    border-radius: 16px;

                    ScrollView {
                        x: 24px;
                        y: 24px;
                        width: parent.width - 48px;
                        height: parent.height - 48px;
                        content-width: self.width;

                        VerticalLayout {
                            spacing: 16px;
                            alignment: start;
                            HorizontalLayout {
                                spacing: 12px;
                                Rectangle {
                                    width: 42px;
                                    height: 42px;
                                    border-radius: 12px;
                                    background: Design.colors(root.dark).accent-soft;
                                    AppIcon { kind: 2; x: 11px; y: 11px; tint: Design.colors(root.dark).accent; }
                                }
                                VerticalLayout {
                                    spacing: 3px;
                                    Text { text: root.preset-label; font-size: 17px; font-weight: 600; }
                                    Text { text: "One key. A world of models."; color: Design.colors(root.dark).muted; font-size: 12px; }
                                }
                                if (root.recommended && root.width >= 520px) : Text {
                                    text: "Recommended";
                                    color: Design.colors(root.dark).success;
                                    font-size: 11px;
                                    vertical-alignment: center;
                                }
                            }

                            if (root.step == 0) : VerticalLayout {
                                spacing: 14px;
                                Text { text: "A quick, secure setup"; font-weight: 600; }
                                Text {
                                    text: "Connect your OpenRouter account to browse leading models and start your first conversation. No ChatUI account is needed.";
                                    color: Design.colors(root.dark).secondary;
                                    wrap: word-wrap;
                                }
                                Button {
                                    text: "Connect OpenRouter";
                                    primary: true;
                                    dark: root.dark;
                                    horizontal-stretch: 1;
                                    clicked => { root.begin-key-entry(); }
                                }
                                Button {
                                    text: "Get an API key";
                                    quiet: true;
                                    dark: root.dark;
                                    horizontal-stretch: 1;
                                    clicked => { root.open-key-portal(); }
                                }
                            }

                            if (root.step >= 1 && root.step <= 3) : VerticalLayout {
                                spacing: 10px;
                                Text { text: "OpenRouter API key"; font-weight: 600; }
                                LineEdit {
                                    input-type: InputType.password;
                                    accessible-label: "OpenRouter API key";
                                    placeholder-text: "Paste your API key";
                                    enabled: root.step != 2;
                                    text: root.key-input;
                                    edited(text) => { root.key-changed(text); }
                                    accepted(text) => { if (!root.key-input.is-empty && root.step != 2) { root.connect(); } }
                                }
                                Text {
                                    text: "Used only to connect directly to OpenRouter. Never sent to a ChatUI server.";
                                    color: Design.colors(root.dark).muted;
                                    font-size: 12px;
                                    wrap: word-wrap;
                                }
                                Text { text: "Key label (optional)"; }
                                LineEdit {
                                    text: root.key-label;
                                    placeholder-text: "For example, Personal";
                                    accessible-label: "Key label";
                                    enabled: root.step != 2;
                                    edited(text) => { root.label-changed(text); }
                                }
                                CheckBox {
                                    text: "Remember this key on this device";
                                    checked: root.remember-key;
                                    enabled: root.step != 2;
                                    toggled => { root.remember-changed(self.checked); }
                                }
                                Button {
                                    text: root.step == 2 ? "Verifying connection..." : root.step == 3 ? "Try connecting again" : "Connect and continue";
                                    primary: true;
                                    dark: root.dark;
                                    horizontal-stretch: 1;
                                    enabled: root.step != 2 && !root.key-input.is-empty;
                                    clicked => { root.connect(); }
                                }
                                if (root.can-skip) : Button {
                                    text: "Skip";
                                    quiet: true;
                                    dark: root.dark;
                                    clicked => { root.skip(); }
                                }
                                if (root.step == 2) : Text {
                                    text: "Checking your key and preparing your models...";
                                    color: Design.colors(root.dark).muted;
                                    wrap: word-wrap;
                                    accessible-live-region: polite;
                                }
                                if (root.step == 3) : Rectangle {
                                    background: Design.colors(root.dark).danger-soft;
                                    border-radius: 10px;
                                    VerticalLayout {
                                        padding: 14px;
                                        spacing: 6px;
                                        Text { text: root.error-title; color: Design.colors(root.dark).danger; font-weight: 600; wrap: word-wrap; accessible-live-region: assertive; }
                                        Text { text: root.error-message; wrap: word-wrap; }
                                        if (!root.error-details.is-empty) : Text {
                                            text: root.error-details;
                                            wrap: word-wrap;
                                            color: Design.colors(root.dark).muted;
                                            font-size: 12px;
                                        }
                                    }
                                }
                                Button {
                                    text: "Get an API key";
                                    quiet: true;
                                    dark: root.dark;
                                    clicked => { root.open-key-portal(); }
                                }
                            }

                            if (root.step == 4 || root.step == 5) : VerticalLayout {
                                spacing: 12px;
                                Text { text: root.step == 4 ? "Preparing your model library..." : "Choosing your first model..."; wrap: word-wrap; accessible-live-region: polite; }
                                Text { text: root.catalog-summary; color: Design.colors(root.dark).muted; wrap: word-wrap; }
                                if (!root.catalog-notice.is-empty) : Text { text: root.catalog-notice; wrap: word-wrap; }
                            }

                            if (root.step == 6) : VerticalLayout {
                                spacing: 14px;
                                Text { text: "Connected and ready"; color: Design.colors(root.dark).success; font-size: 18px; font-weight: 600; }
                                Text { text: "Your first model"; color: Design.colors(root.dark).muted; font-size: 12px; }
                                Text { text: root.selected-model; font-size: 18px; font-weight: 600; wrap: word-wrap; }
                                Text { text: "You can choose a different model at any time."; color: Design.colors(root.dark).muted; wrap: word-wrap; }
                                if (!root.default-model-notice.is-empty) : Text { text: root.default-model-notice; wrap: word-wrap; }
                                Button {
                                    text: "Start chatting";
                                    primary: true;
                                    dark: root.dark;
                                    horizontal-stretch: 1;
                                    clicked => { root.continue-requested = true; }
                                }
                            }
                        }
                    }
                }
            }

            Text {
                text: "Local history. Direct connections. Your choice.";
                color: Design.colors(root.dark).muted;
                font-size: 12px;
                horizontal-alignment: center;
                wrap: word-wrap;
            }
          }
        }
    }
}

/// Push the current view model into a Slint window.
pub fn sync_window(window: &OnboardingWindow, view_model: &OnboardingViewModel) {
    window.set_step(view_model.step().index());
    window.set_preset_label(view_model.preset_label().into());
    window.set_recommended(view_model.recommended());
    window.set_key_input(view_model.key_input().into());
    window.set_key_masked(view_model.masked_key().into());
    window.set_key_label(view_model.key_label().into());
    window.set_remember_key(view_model.remember_key());
    window.set_catalog_count(view_model.catalog().len() as i32);
    window.set_catalog_summary(
        format!(
            "{} models are available. Chat stays disabled until your key is validated.",
            view_model.catalog().len()
        )
        .into(),
    );
    window.set_catalog_notice(view_model.catalog_notice().unwrap_or_default().into());
    window.set_models(ModelRc::new(VecModel::from(
        view_model
            .catalog()
            .iter()
            .map(|model| SharedString::from(model.label.clone()))
            .collect::<Vec<_>>(),
    )));
    window.set_selected_model(
        view_model
            .selected_model()
            .map(|model| model.label.clone())
            .unwrap_or_default()
            .into(),
    );
    window.set_default_model_notice(view_model.default_model_notice().unwrap_or_default().into());
    window.set_error_title(
        view_model
            .error()
            .map(|error| error.title.clone())
            .unwrap_or_default()
            .into(),
    );
    window.set_error_message(
        view_model
            .error()
            .map(|error| error.message.clone())
            .unwrap_or_default()
            .into(),
    );
    window.set_error_details(
        view_model
            .error()
            .and_then(|error| error.details.clone())
            .unwrap_or_default()
            .into(),
    );
    window.set_can_send(view_model.can_send());
    window.set_can_skip(view_model.can_skip());
    window.set_onboarding_completed(view_model.onboarding_completed());
}

/// Owns the onboarding view model and the Slint window that renders it.
pub struct OnboardingController {
    service: Arc<dyn OnboardingServiceLike>,
    view_model: Rc<RefCell<OnboardingViewModel>>,
    window: OnboardingWindow,
    tasks: Rc<UiTasks>,
}

impl OnboardingController {
    /// Create the onboarding window and load the public catalog.
    ///
    /// Services run synchronously. Desktop hosts should use
    /// [`Self::new_background`] to keep network calls off the UI thread.
    pub fn new(
        service: Arc<dyn OnboardingServiceLike>,
        preset: &ProviderPreset,
    ) -> Result<Self, slint::PlatformError> {
        Self::create(service, preset, false)
    }

    /// Create an onboarding window with background catalog and key validation.
    pub fn new_background(
        service: Arc<dyn OnboardingServiceLike>,
        preset: &ProviderPreset,
    ) -> Result<Self, slint::PlatformError> {
        Self::create(service, preset, true)
    }

    fn create(
        service: Arc<dyn OnboardingServiceLike>,
        preset: &ProviderPreset,
        background: bool,
    ) -> Result<Self, slint::PlatformError> {
        let window = OnboardingWindow::new()?;
        let controller = Self {
            service,
            view_model: Rc::new(RefCell::new(OnboardingViewModel::from_preset(preset))),
            window,
            tasks: Rc::new(UiTasks::new(background)),
        };
        controller.wire();
        controller.sync();
        controller.load_catalog();
        Ok(controller)
    }

    /// The underlying Slint window.
    pub fn window(&self) -> &OnboardingWindow {
        &self.window
    }

    /// Borrow the current view model.
    pub fn view_model(&self) -> std::cell::Ref<'_, OnboardingViewModel> {
        self.view_model.borrow()
    }

    /// Where the app should route now.
    pub fn route(&self) -> OnboardingRoute {
        if self.view_model.borrow().onboarding_completed() {
            OnboardingRoute::Shell
        } else {
            OnboardingRoute::Onboarding
        }
    }

    /// Push the current view model into the window.
    pub fn sync(&self) {
        sync_window(&self.window, &self.view_model.borrow());
    }

    /// Apply completed background work. Native hosts also poll automatically.
    pub fn poll(&self) {
        self.tasks.poll();
    }

    fn load_catalog(&self) {
        let service = Arc::clone(&self.service);
        let state = Rc::clone(&self.view_model);
        let weak = self.window.as_weak();
        self.tasks.run(
            move || service.load_catalog().map_err(Box::new),
            move |outcome| {
                if state.borrow().onboarding_completed() {
                    return;
                }
                match outcome {
                    Ok(Ok(load)) => state.borrow_mut().set_catalog(load.models, load.notice),
                    Ok(Err(error)) => state
                        .borrow_mut()
                        .catalog_failed(describe_catalog_error_from_onboarding(&error)),
                    Err(error) => state.borrow_mut().catalog_failed(error.to_string()),
                }
                if let Some(window) = weak.upgrade() {
                    sync_window(&window, &state.borrow());
                }
            },
        );
    }

    fn wire(&self) {
        let weak = self.window.as_weak();
        let state = Rc::clone(&self.view_model);
        self.window.on_begin_key_entry(move || {
            state.borrow_mut().begin_key_entry();
            if let Some(window) = weak.upgrade() {
                sync_window(&window, &state.borrow());
            }
        });

        let weak = self.window.as_weak();
        let state = Rc::clone(&self.view_model);
        self.window.on_key_changed(move |value| {
            state.borrow_mut().set_key_input(value.as_str());
            if let Some(window) = weak.upgrade() {
                sync_window(&window, &state.borrow());
            }
        });

        let weak = self.window.as_weak();
        let state = Rc::clone(&self.view_model);
        self.window.on_label_changed(move |value| {
            state.borrow_mut().set_key_label(value.as_str());
            if let Some(window) = weak.upgrade() {
                sync_window(&window, &state.borrow());
            }
        });

        let weak = self.window.as_weak();
        let state = Rc::clone(&self.view_model);
        self.window.on_remember_changed(move |value| {
            state.borrow_mut().set_remember_key(value);
            if let Some(window) = weak.upgrade() {
                sync_window(&window, &state.borrow());
            }
        });

        let weak = self.window.as_weak();
        let state = Rc::clone(&self.view_model);
        let service = Arc::clone(&self.service);
        let tasks = Rc::clone(&self.tasks);
        self.window.on_connect(move || {
            let (key, label, remember) = {
                let mut view_model = state.borrow_mut();
                if view_model.step() == OnboardingStep::Validating
                    || view_model.step() == OnboardingStep::Complete
                {
                    return;
                }
                view_model.start_validation();
                (
                    view_model.key_input().to_owned(),
                    view_model.key_label().to_owned(),
                    view_model.remember_key(),
                )
            };

            if let Some(window) = weak.upgrade() {
                sync_window(&window, &state.borrow());
            }

            let input = ConnectInput {
                api_key: SecretValue::new(key.clone()),
                label,
                remember_key: remember,
            };
            let service = Arc::clone(&service);
            let progress_state = Rc::clone(&state);
            let progress_window = weak.clone();
            let finished_state = Rc::clone(&state);
            let finished_window = weak.clone();
            tasks.run_with_progress(
                move |progress| {
                    service
                        .connect_with_progress(input, &mut |step| {
                            let _ = progress.send(step);
                        })
                        .map_err(Box::new)
                },
                move |step| {
                    {
                        let mut view_model = progress_state.borrow_mut();
                        match step {
                            OnboardingStep::CatalogLoading => view_model.catalog_loading(),
                            OnboardingStep::ModelSelection => view_model.model_selection(),
                            _ => {}
                        }
                    }
                    if let Some(window) = progress_window.upgrade() {
                        sync_window(&window, &progress_state.borrow());
                    }
                },
                move |outcome| {
                    {
                        let mut view_model = finished_state.borrow_mut();
                        match outcome {
                            Ok(Ok(result)) => {
                                view_model.set_catalog(result.catalog, result.catalog_notice);
                                view_model.complete(
                                    result.provider_id,
                                    result.model_id,
                                    result.selected_model,
                                    result.notice,
                                );
                            }
                            Ok(Err(error)) => {
                                view_model.validation_failed(describe_onboarding_error(
                                    &error,
                                    Some(&key),
                                ));
                            }
                            Err(error) => {
                                view_model.validation_failed(
                                    crate::onboarding::error::OnboardingErrorCopy {
                                        title: "Connection interrupted".to_owned(),
                                        message: error.to_string(),
                                        details: None,
                                    },
                                );
                            }
                        }
                    }
                    if let Some(window) = finished_window.upgrade() {
                        sync_window(&window, &finished_state.borrow());
                    }
                },
            );
        });

        let weak = self.window.as_weak();
        let state = Rc::clone(&self.view_model);
        self.window.on_skip(move || {
            let _ = state.borrow_mut().skip();
            if let Some(window) = weak.upgrade() {
                sync_window(&window, &state.borrow());
            }
        });
    }
}

fn describe_catalog_error_from_onboarding(error: &OnboardingError) -> String {
    match error {
        OnboardingError::Chat(chat) => describe_catalog_error(chat),
        other => format!("Could not load the model catalog. {other}"),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::Mutex;

    use chatui_contracts::provider_catalog;
    use chatui_domain::{
        ChatError, ChatErrorCode, InputModality, Model, ModelCapability, ModelSource,
        OutputModality, Timestamp,
    };
    use chatui_providers::CatalogModel;

    use crate::onboarding::error::OnboardingError;
    use crate::onboarding::service::{CatalogLoad, ConnectResult};

    struct FakeService {
        catalog: Mutex<Result<CatalogLoad, OnboardingError>>,
        connect: Mutex<Result<ConnectResult, OnboardingError>>,
        complete: Mutex<bool>,
    }

    impl OnboardingServiceLike for FakeService {
        fn load_catalog(&self) -> Result<CatalogLoad, OnboardingError> {
            self.catalog.lock().expect("catalog lock").clone()
        }

        fn connect(&self, _input: ConnectInput) -> Result<ConnectResult, OnboardingError> {
            self.connect.lock().expect("connect lock").clone()
        }

        fn connect_with_progress(
            &self,
            _input: ConnectInput,
            on_step: &mut dyn FnMut(OnboardingStep),
        ) -> Result<ConnectResult, OnboardingError> {
            on_step(OnboardingStep::Validating);
            on_step(OnboardingStep::CatalogLoading);
            on_step(OnboardingStep::ModelSelection);
            let result = self.connect.lock().expect("connect lock").clone();
            if result.is_ok() {
                on_step(OnboardingStep::Complete);
            }
            result
        }

        fn is_onboarding_complete(&self) -> Result<bool, OnboardingError> {
            Ok(*self.complete.lock().expect("complete lock"))
        }
    }

    fn preset() -> ProviderPreset {
        provider_catalog()
            .get("openrouter")
            .expect("openrouter preset")
            .clone()
    }

    fn catalog_model(remote_id: &str) -> CatalogModel {
        CatalogModel {
            remote_id: remote_id.to_owned(),
            canonical_slug: None,
            label: remote_id.to_owned(),
            context_window: Some(128_000),
            max_output_tokens: Some(4096),
            capabilities: vec![ModelCapability::Text],
            pricing: None,
            input_modalities: vec![InputModality::Text],
            output_modalities: vec![OutputModality::Text],
            supported_parameters: Vec::new(),
            metadata: None,
            is_free: false,
            hidden: false,
        }
    }

    fn model(remote_id: &str) -> Model {
        let now = Timestamp::now();
        Model {
            id: format!("model-{remote_id}"),
            provider_id: "provider-1".to_owned(),
            remote_id: remote_id.to_owned(),
            label: remote_id.to_owned(),
            context_window: Some(128_000),
            max_output_tokens: Some(4096),
            capabilities: vec![ModelCapability::Text],
            pricing: None,
            canonical_slug: None,
            input_modalities: vec![InputModality::Text],
            output_modalities: vec![OutputModality::Text],
            supported_parameters: Vec::new(),
            metadata: None,
            hidden: false,
            pinned: false,
            source: ModelSource::Discovered,
            created_at: now,
            updated_at: now,
        }
    }

    fn success() -> ConnectResult {
        ConnectResult {
            provider_id: "provider-1".to_owned(),
            model_id: "model-1".to_owned(),
            model_remote_id: "openrouter/auto".to_owned(),
            notice: None,
            catalog_notice: None,
            catalog: vec![catalog_model("openrouter/auto")],
            selected_model: model("openrouter/auto"),
        }
    }

    fn service(
        catalog: Result<CatalogLoad, OnboardingError>,
        connect: Result<ConnectResult, OnboardingError>,
    ) -> Arc<FakeService> {
        Arc::new(FakeService {
            catalog: Mutex::new(catalog),
            connect: Mutex::new(connect),
            complete: Mutex::new(false),
        })
    }

    #[test]
    fn background_connection_waits_for_explicit_start_chatting() {
        use i_slint_backend_testing::ElementRoot;
        i_slint_backend_testing::init_no_event_loop();
        let service = service(
            Ok(CatalogLoad {
                models: vec![catalog_model("openrouter/auto")],
                notice: None,
            }),
            Ok(success()),
        );
        let controller =
            OnboardingController::new_background(service, &preset()).expect("onboarding");
        for _ in 0..200 {
            controller.poll();
            if controller.window().get_catalog_count() == 1 {
                break;
            }
            std::thread::sleep(std::time::Duration::from_millis(5));
        }
        let window = controller.window();
        window.show().expect("show onboarding");
        window
            .window()
            .dispatch_event(slint::platform::WindowEvent::Resized {
                size: slint::LogicalSize::new(720.0, 760.0),
            });
        window.invoke_begin_key_entry();
        window.invoke_key_changed("test-key".into());
        window.invoke_connect();
        assert!(!window.get_can_send());
        for _ in 0..200 {
            controller.poll();
            if window.get_can_send() {
                break;
            }
            std::thread::sleep(std::time::Duration::from_millis(5));
        }
        assert!(window.get_can_send());
        assert!(!window.get_continue_requested());
        i_slint_backend_testing::mock_elapsed_time(std::time::Duration::ZERO);
        let controls = window.root_element().query_descendants().find_all();
        let start = controls
            .into_iter()
            .find(|element| element.accessible_label().as_deref() == Some("Start chatting"))
            .unwrap_or_else(|| {
                panic!(
                    "missing Start chatting at step {} in {:?}",
                    window.get_step(),
                    window.root_element().size()
                )
            });
        start.invoke_accessible_default_action();
        assert!(window.get_continue_requested());
        window.hide().expect("hide onboarding");
    }

    #[test]
    fn window_starts_on_welcome_with_openrouter_recommended() {
        i_slint_backend_testing::init_no_event_loop();

        let service = service(
            Ok(CatalogLoad {
                models: vec![catalog_model("openrouter/auto")],
                notice: None,
            }),
            Ok(success()),
        );
        let controller = OnboardingController::new(service, &preset()).expect("onboarding window");
        let window = controller.window();

        assert_eq!(window.get_step(), 0);
        assert_eq!(window.get_preset_label(), "OpenRouter");
        assert!(window.get_recommended());
        assert_eq!(window.get_catalog_count(), 1);
        assert!(!window.get_can_send());
        assert_eq!(controller.route(), OnboardingRoute::Onboarding);
    }

    #[test]
    fn key_entry_is_masked_and_never_echoed() {
        i_slint_backend_testing::init_no_event_loop();

        let service = service(
            Ok(CatalogLoad {
                models: Vec::new(),
                notice: None,
            }),
            Ok(success()),
        );
        let controller = OnboardingController::new(service, &preset()).expect("onboarding window");
        let window = controller.window();

        window.invoke_begin_key_entry();
        assert_eq!(window.get_step(), 1);

        window.invoke_key_changed("sk-test-not-a-real-key".into());
        assert_eq!(window.get_key_input(), "sk-test-not-a-real-key");
        assert!(!window.get_key_masked().contains("sk-test-not-a-real-key"));
        assert!(!window.get_key_masked().is_empty());
    }

    #[test]
    fn successful_connect_completes_and_routes_to_the_shell() {
        i_slint_backend_testing::init_no_event_loop();

        let service = service(
            Ok(CatalogLoad {
                models: vec![catalog_model("openrouter/auto")],
                notice: None,
            }),
            Ok(success()),
        );
        let controller = OnboardingController::new(service, &preset()).expect("onboarding window");
        let window = controller.window();

        window.invoke_begin_key_entry();
        window.invoke_key_changed("sk-test-not-a-real-key".into());
        window.invoke_connect();

        assert_eq!(window.get_step(), 6);
        assert!(window.get_can_send());
        assert!(window.get_onboarding_completed());
        assert_eq!(window.get_selected_model(), "openrouter/auto");
        assert_eq!(controller.route(), OnboardingRoute::Shell);
    }

    #[test]
    fn failed_connect_preserves_the_form_and_shows_actionable_copy() {
        i_slint_backend_testing::init_no_event_loop();

        let key = "sk-test-not-a-real-key";
        let service = service(
            Ok(CatalogLoad {
                models: Vec::new(),
                notice: None,
            }),
            Err(OnboardingError::Chat(ChatError {
                code: ChatErrorCode::Auth,
                message: format!("request failed for {key}"),
                provider_id: Some("openrouter".to_owned()),
                status: None,
                retryable: false,
                retry_after_seconds: None,
                request_id: None,
                details: None,
                at: Timestamp::now(),
            })),
        );
        let controller = OnboardingController::new(service, &preset()).expect("onboarding window");
        let window = controller.window();

        window.invoke_begin_key_entry();
        window.invoke_key_changed(key.into());
        window.invoke_connect();

        assert_eq!(window.get_step(), 3);
        assert_eq!(window.get_key_input(), key);
        assert_eq!(window.get_error_title(), "API key rejected");
        assert!(!window.get_error_details().contains(key));
        assert!(!window.get_can_send());
        assert_eq!(controller.route(), OnboardingRoute::Onboarding);
    }

    #[test]
    fn skip_is_unavailable_on_first_run() {
        i_slint_backend_testing::init_no_event_loop();

        let service = service(
            Ok(CatalogLoad {
                models: Vec::new(),
                notice: None,
            }),
            Ok(success()),
        );
        let controller = OnboardingController::new(service, &preset()).expect("onboarding window");
        let window = controller.window();

        assert!(!window.get_can_skip());
        window.invoke_skip();
        assert_eq!(window.get_step(), 0);
        assert!(!window.get_onboarding_completed());
    }

    #[test]
    fn catalog_failure_is_non_blocking() {
        i_slint_backend_testing::init_no_event_loop();

        let service = service(
            Err(OnboardingError::Chat(ChatError {
                code: ChatErrorCode::Network,
                message: "offline".to_owned(),
                provider_id: Some("openrouter".to_owned()),
                status: None,
                retryable: false,
                retry_after_seconds: None,
                request_id: None,
                details: None,
                at: Timestamp::now(),
            })),
            Ok(success()),
        );
        let controller = OnboardingController::new(service, &preset()).expect("onboarding window");
        let window = controller.window();

        assert!(
            window
                .get_catalog_notice()
                .contains("You can still connect")
        );
        assert_eq!(window.get_step(), 0);
    }
}
