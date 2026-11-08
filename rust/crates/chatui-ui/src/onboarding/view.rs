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
    import { Button, CheckBox, LineEdit, Palette, ScrollView } from "std-widgets.slint";

    export component OnboardingWindow inherits Window {
        title: "ChatUI onboarding";
        width: 720px;
        height: 640px;

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
        in property <bool> dark: false;

        background: root.dark ? #0f1115 : #ffffff;
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

        VerticalLayout {
            padding: 24px;
            spacing: 12px;

            Text {
                text: "Welcome to ChatUI";
                font-size: 24px;
                font-weight: 600;
            }

            Text {
                text: "ChatUI is bring-your-own-key (BYOK). Your key and conversations stay on this device.";
                wrap: word-wrap;
            }

            Rectangle {
                height: 48px;
                background: root.dark ? #171a21 : #f7f7f8;
                border-radius: 8px;

                HorizontalLayout {
                    padding: 12px;
                    spacing: 8px;

                    Text {
                        text: root.preset-label;
                        vertical-alignment: center;
                    }

                    if (root.recommended) : Text {
                        text: "Recommended";
                        color: root.dark ? #4ade80 : #166534;
                        vertical-alignment: center;
                    }
                }
            }

            if (root.step == 0) : Button {
                text: "Connect OpenRouter";
                clicked => { root.begin-key-entry(); }
            }

            if (root.step >= 1 && root.step <= 3) : VerticalLayout {
                spacing: 8px;

                Text { text: "OpenRouter API key"; }

                LineEdit {
                    input-type: InputType.password;
                    accessible-label: "OpenRouter API key";
                    text: root.key-input;
                    edited(text) => { root.key-changed(text); }
                }

                Text {
                    text: "Your key is stored on this device and is never sent to a ChatUI server.";
                    wrap: word-wrap;
                }

                Text { text: "Label (optional)"; }

                LineEdit {
                    text: root.key-label;
                    accessible-label: "Key label";
                    edited(text) => { root.label-changed(text); }
                }

                CheckBox {
                    text: "Remember this key on this device";
                    checked: root.remember-key;
                    toggled => { root.remember-changed(self.checked); }
                }

                HorizontalLayout {
                    spacing: 8px;

                    Button {
                        text: root.step == 2 ? "Testing…" : "Connect";
                        enabled: root.step != 2 && !root.key-input.is-empty;
                        clicked => { root.connect(); }
                    }

                    if (root.can-skip) : Button {
                        text: "Skip";
                        clicked => { root.skip(); }
                    }
                }

                if (root.step == 2) : Text {
                    text: "Testing key…";
                    accessible-live-region: polite;
                }

                if (root.step == 3) : Rectangle {
                    background: root.dark ? #3b1d1d : #fde8e8;
                    border-radius: 8px;

                    VerticalLayout {
                        padding: 12px;
                        spacing: 4px;

                        Text {
                            text: root.error-title;
                            font-weight: 600;
                            accessible-live-region: assertive;
                        }

                        Text {
                            text: root.error-message;
                            accessible-live-region: assertive;
                            wrap: word-wrap;
                        }

                        if (!root.error-details.is-empty) : Text {
                            text: root.error-details;
                            wrap: word-wrap;
                            color: root.dark ? #9ca3af : #5f6368;
                        }
                    }
                }
            }

            if (root.step == 4) : Text {
                text: "Loading the public model catalog…";
                accessible-live-region: polite;
            }

            if (root.step == 5) : VerticalLayout {
                spacing: 8px;

                Text {
                    text: "Available models";
                    font-size: 18px;
                    font-weight: 600;
                }

                if (!root.catalog-notice.is-empty) : Text {
                    text: root.catalog-notice;
                    wrap: word-wrap;
                }

                Text {
                    text: root.catalog-summary;
                    wrap: word-wrap;
                }

                ScrollView {
                    min-height: 160px;

                    VerticalLayout {
                        for model in root.models : Text {
                            text: model;
                        }
                    }
                }

                if (!root.default-model-notice.is-empty) : Text {
                    text: root.default-model-notice;
                    wrap: word-wrap;
                }
            }

            if (root.step == 6) : VerticalLayout {
                spacing: 8px;

                Text {
                    text: "Connected";
                    font-size: 20px;
                    font-weight: 600;
                }

                Text {
                    text: "Default model: " + root.selected-model;
                }

                if (!root.default-model-notice.is-empty) : Text {
                    text: root.default-model-notice;
                    wrap: word-wrap;
                }

                Text {
                    text: root.can-send ? "You can start chatting." : "Sending is disabled until a provider and key are configured.";
                    wrap: word-wrap;
                }
            }

            Rectangle { vertical-stretch: 1; }
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
}

impl OnboardingController {
    /// Create the onboarding window and load the public catalog.
    ///
    /// The catalog load is synchronous. Production callers should run the
    /// controller on a worker thread or provide a non-blocking service.
    pub fn new(
        service: Arc<dyn OnboardingServiceLike>,
        preset: &ProviderPreset,
    ) -> Result<Self, slint::PlatformError> {
        let mut view_model = OnboardingViewModel::from_preset(preset);
        match service.load_catalog() {
            Ok(load) => view_model.set_catalog(load.models, load.notice),
            Err(error) => view_model.catalog_failed(describe_catalog_error_from_onboarding(&error)),
        }

        let view_model = Rc::new(RefCell::new(view_model));
        let window = OnboardingWindow::new()?;
        let controller = Self {
            service,
            view_model,
            window,
        };
        controller.wire();
        controller.sync();
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
            let mut on_step = |step: OnboardingStep| {
                {
                    let mut view_model = state.borrow_mut();
                    match step {
                        OnboardingStep::CatalogLoading => view_model.catalog_loading(),
                        OnboardingStep::ModelSelection => view_model.model_selection(),
                        _ => {}
                    }
                }
                if let Some(window) = weak.upgrade() {
                    sync_window(&window, &state.borrow());
                }
            };
            let result = service.connect_with_progress(input, &mut on_step);

            {
                let mut view_model = state.borrow_mut();
                match result {
                    Ok(result) => {
                        view_model
                            .set_catalog(result.catalog.clone(), result.catalog_notice.clone());
                        view_model.complete(
                            result.provider_id.clone(),
                            result.model_id.clone(),
                            result.selected_model.clone(),
                            result.notice.clone(),
                        );
                    }
                    Err(error) => {
                        let copy = describe_onboarding_error(&error, Some(&key));
                        view_model.validation_failed(copy);
                    }
                }
            }

            if let Some(window) = weak.upgrade() {
                sync_window(&window, &state.borrow());
            }
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
