//! Slint provider management view and controller.
//!
//! The controller binds the framework-independent
//! [`ProviderManagementViewModel`] to a Slint window. The list shows enabled
//! state, the OpenRouter recommended badge, key presence, and CORS. The detail
//! panel shows the non-secret `/key` metadata, the contract docs link, and the
//! non-secret custom headers. The key itself is never rendered outside the
//! password field used to enter a new key.

use std::cell::RefCell;
use std::rc::Rc;
use std::sync::Arc;

use chatui_domain::ProviderAuthType;
use chatui_secrets::SecretValue;
use slint::{ComponentHandle, ModelRc, SharedString, VecModel};

use crate::providers::error::describe_provider_error;
use crate::providers::service::ProviderManagementService;
use crate::providers::state::{
    ConfirmationAction, CustomProviderForm, PendingConfirmation, ProviderManagementViewModel,
};
use crate::providers::validation::{parse_headers_text, validate_base_url};

slint::slint! {
    import { Button, CheckBox, ComboBox, LineEdit, ScrollView } from "std-widgets.slint";

    export struct ProviderRow {
        id: string,
        label: string,
        meta: string,
    }

    export component ProviderManagementWindow inherits Window {
        title: "ChatUI providers";
        width: 1000px;
        height: 760px;

        in property <[ProviderRow]> providers: [];
        in property <bool> add-provider-available: true;
        in property <string> notice: "";
        in property <string> error-title: "";
        in property <string> error-message: "";
        in property <string> error-details: "";

        in property <bool> detail-visible: false;
        in property <string> detail-label: "";
        in property <string> detail-preset: "";
        in property <string> detail-base-url: "";
        in property <string> detail-status: "";
        in property <string> detail-key-label: "";
        in property <string> detail-usage: "";
        in property <string> detail-limit: "";
        in property <string> detail-free-tier: "";
        in property <string> detail-rate-limit: "";
        in property <string> detail-cors: "";
        in property <string> detail-docs-url: "";
        in property <string> detail-headers: "";
        in property <string> detail-cleartext-warning: "";
        in property <string> detail-model-count-text: "";
        in property <bool> detail-key-present: false;
        in property <bool> detail-enabled: false;

        in property <bool> form-visible: false;
        in property <string> form-title: "Add a custom provider";
        in property <string> form-label: "";
        in property <string> form-base-url: "";
        in property <int> form-auth-index: 0;
        in property <string> form-api-key: "";
        in property <string> form-headers: "";
        in property <bool> form-test-connection: true;
        in property <string> form-cleartext-warning: "";
        in property <string> form-notice: "";
        in property <string> form-error: "";

        in-out property <string> rotate-key-input: "";
        in property <string> confirm-label: "";

        callback add-provider();
        callback select-provider(string);
        callback save-provider();
        callback cancel-form();
        callback rotate-key(string);
        callback forget-key();
        callback forget-all-keys();
        callback delete-provider();
        callback test-connection();
        callback toggle-enabled(bool);
        callback form-label-changed(string);
        callback form-base-url-changed(string);
        callback form-auth-changed(string);
        callback form-api-key-changed(string);
        callback form-headers-changed(string);
        callback form-test-connection-changed(bool);

        HorizontalLayout {
            padding: 16px;
            spacing: 16px;

            VerticalLayout {
                width: 340px;
                spacing: 8px;

                Text { text: "Providers"; font-size: 22px; font-weight: 600; }

                if (root.add-provider-available) : Button {
                    text: "Add provider";
                    clicked => { root.add-provider(); }
                }

                if (!root.notice.is-empty) : Text {
                    text: root.notice;
                    accessible-live-region: polite;
                    wrap: word-wrap;
                }

                if (!root.error-title.is-empty) : Rectangle {
                    background: #fde8e8;
                    border-radius: 8px;
                    VerticalLayout {
                        padding: 8px;
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
                        if (!root.error-details.is-empty) : Text { text: root.error-details; wrap: word-wrap; color: #666666; }
                    }
                }

                ScrollView {
                    VerticalLayout {
                        for entry in root.providers : Rectangle {
                            height: 64px;
                            background: #f7f7f7;
                            border-radius: 8px;
                            HorizontalLayout {
                                padding: 8px;
                                spacing: 8px;
                                VerticalLayout {
                                    Text { text: entry.label; font-weight: 600; }
                                    Text { text: entry.meta; color: #666666; wrap: word-wrap; }
                                }
                                Button {
                                    text: "Open";
                                    clicked => { root.select-provider(entry.id); }
                                }
                            }
                        }
                    }
                }
            }

            if (root.detail-visible) : VerticalLayout {
                spacing: 8px;
                Text { text: root.detail-label; font-size: 22px; font-weight: 600; }
                Text { text: root.detail-preset + " · " + root.detail-status; color: #666666; }
                Text { text: root.detail-base-url; wrap: word-wrap; }
                if (!root.detail-cleartext-warning.is-empty) : Text { text: root.detail-cleartext-warning; color: #b45309; wrap: word-wrap; }
                if (!root.detail-docs-url.is-empty) : Text { text: "Docs: " + root.detail-docs-url; wrap: word-wrap; }
                Text { text: "Key label: " + root.detail-key-label; }
                Text { text: "Usage: " + root.detail-usage; }
                Text { text: "Limit: " + root.detail-limit; }
                Text { text: "Free tier: " + root.detail-free-tier; }
                Text { text: "Rate limit: " + root.detail-rate-limit; }
                Text { text: "CORS: " + root.detail-cors; }
                Text { text: root.detail-model-count-text; }
                if (!root.detail-headers.is-empty) : Text { text: "Headers: " + root.detail-headers; wrap: word-wrap; }
                CheckBox {
                    text: "Enable this provider";
                    checked: root.detail-enabled;
                    toggled => { root.toggle-enabled(self.checked); }
                }
                HorizontalLayout {
                    spacing: 8px;
                    Button { text: "Test connection"; enabled: root.detail-key-present; clicked => { root.test-connection(); } }
                    Button { text: "Forget key"; enabled: root.detail-key-present; clicked => { root.forget-key(); } }
                    Button { text: "Forget all keys"; clicked => { root.forget-all-keys(); } }
                    Button { text: "Delete provider"; clicked => { root.delete-provider(); } }
                }
                if (!root.confirm-label.is-empty) : Text { text: root.confirm-label; color: #b45309; wrap: word-wrap; }
                HorizontalLayout {
                    spacing: 8px;
                    LineEdit {
                        placeholder-text: "New API key";
                        accessible-label: "New API key";
                        input-type: InputType.password;
                        text: root.rotate-key-input;
                        edited(text) => { root.rotate-key-input = text; }
                    }
                    Button { text: "Rotate key"; clicked => { root.rotate-key(root.rotate-key-input); } }
                }
            }

            if (root.form-visible) : VerticalLayout {
                spacing: 8px;
                Text { text: root.form-title; font-size: 20px; font-weight: 600; }
                Text { text: "Label (optional)"; }
                LineEdit {
                    text: root.form-label;
                    accessible-label: "Label";
                    edited(text) => { root.form-label-changed(text); }
                }
                Text { text: "Base URL"; }
                LineEdit {
                    text: root.form-base-url;
                    accessible-label: "Base URL";
                    edited(text) => { root.form-base-url-changed(text); }
                }
                Text { text: "Authentication"; }
                ComboBox {
                    model: ["Bearer token", "x-api-key header", "No authentication"];
                    accessible-label: "Authentication";
                    current-index: root.form-auth-index;
                    selected(value) => { root.form-auth-changed(value); }
                }
                if (root.form-auth-index != 2) : VerticalLayout {
                    Text { text: "API key"; }
                    LineEdit {
                        text: root.form-api-key;
                        accessible-label: "API key";
                        input-type: InputType.password;
                        edited(text) => { root.form-api-key-changed(text); }
                    }
                }
                Text { text: "Non-secret headers (Name: value per line)"; }
                LineEdit {
                    text: root.form-headers;
                    accessible-label: "Non-secret headers";
                    edited(text) => { root.form-headers-changed(text); }
                }
                CheckBox {
                    text: "Test connection before saving";
                    checked: root.form-test-connection;
                    toggled => { root.form-test-connection-changed(self.checked); }
                }
                if (!root.form-cleartext-warning.is-empty) : Text { text: root.form-cleartext-warning; color: #b45309; wrap: word-wrap; }
                if (!root.form-notice.is-empty) : Text {
                    text: root.form-notice;
                    accessible-live-region: polite;
                    wrap: word-wrap;
                }
                if (!root.form-error.is-empty) : Text {
                    text: root.form-error;
                    color: #b91c1c;
                    accessible-live-region: assertive;
                    wrap: word-wrap;
                }
                HorizontalLayout {
                    spacing: 8px;
                    Button { text: "Save"; clicked => { root.save-provider(); } }
                    Button { text: "Cancel"; clicked => { root.cancel-form(); } }
                }
            }
        }
    }
}

/// Push the current view model into a Slint window.
pub fn sync_window(window: &ProviderManagementWindow, state: &ProviderManagementViewModel) {
    let rows: Vec<ProviderRow> = state
        .list
        .entries()
        .iter()
        .map(|entry| ProviderRow {
            id: SharedString::from(entry.id.clone()),
            label: SharedString::from(entry.label.clone()),
            meta: SharedString::from(entry.meta_line()),
        })
        .collect();

    window.set_providers(ModelRc::new(VecModel::from(rows)));
    window.set_add_provider_available(state.list.add_provider_available());
    window.set_notice(
        state
            .notice
            .clone()
            .or_else(|| state.list.notice().map(str::to_owned))
            .unwrap_or_default()
            .into(),
    );
    window.set_error_title(
        state
            .error
            .as_ref()
            .map(|error| error.title.clone())
            .unwrap_or_default()
            .into(),
    );
    window.set_error_message(
        state
            .error
            .as_ref()
            .map(|error| error.message.clone())
            .unwrap_or_default()
            .into(),
    );
    window.set_error_details(
        state
            .error
            .as_ref()
            .and_then(|error| error.details.clone())
            .unwrap_or_default()
            .into(),
    );

    if let Some(detail) = state.detail.as_ref() {
        window.set_detail_visible(true);
        window.set_detail_label(detail.label.clone().into());
        window.set_detail_preset(detail.preset_label.clone().into());
        window.set_detail_base_url(detail.base_url.clone().into());
        window.set_detail_status(
            if detail.enabled {
                "Enabled"
            } else {
                "Disabled"
            }
            .into(),
        );
        window.set_detail_key_label(detail.key_label().into());
        window.set_detail_usage(detail.usage_display().into());
        window.set_detail_limit(detail.limit_display().into());
        window.set_detail_free_tier(detail.free_tier_display().into());
        window.set_detail_rate_limit(
            detail
                .rate_limit_display()
                .unwrap_or_else(|| "Not reported".to_owned())
                .into(),
        );
        window.set_detail_cors(detail.cors_display().into());
        window.set_detail_docs_url(detail.docs_url.clone().unwrap_or_default().into());
        window.set_detail_headers(detail.headers_display().into());
        window.set_detail_cleartext_warning(
            detail.cleartext_warning.clone().unwrap_or_default().into(),
        );
        window.set_detail_model_count_text(format!("Models: {}", detail.model_count).into());
        window.set_detail_key_present(detail.key_present);
        window.set_detail_enabled(detail.enabled);
    } else {
        window.set_detail_visible(false);
    }

    if let Some(form) = state.form.as_ref() {
        window.set_form_visible(true);
        window.set_form_title(
            if form.is_editing() {
                "Edit custom provider"
            } else {
                "Add a custom provider"
            }
            .into(),
        );
        window.set_form_label(form.label.clone().into());
        window.set_form_base_url(form.base_url.clone().into());
        window.set_form_auth_index(auth_index(form.auth_type));
        window.set_form_api_key(form.api_key().expose().into());
        window.set_form_headers(form.headers_text().into());
        window.set_form_test_connection(form.test_connection);
        window
            .set_form_cleartext_warning(form.cleartext_warning.clone().unwrap_or_default().into());
        window.set_form_notice(form.notice.clone().unwrap_or_default().into());
        window.set_form_error(
            form.error
                .as_ref()
                .map(|error| error.message.clone())
                .unwrap_or_default()
                .into(),
        );
    } else {
        window.set_form_visible(false);
    }

    window.set_confirm_label(
        state
            .pending_confirmation
            .as_ref()
            .map(PendingConfirmation::prompt)
            .unwrap_or_default()
            .into(),
    );
}

fn auth_index(auth_type: ProviderAuthType) -> i32 {
    match auth_type {
        ProviderAuthType::Bearer | ProviderAuthType::QueryKey => 0,
        ProviderAuthType::XApiKey => 1,
        ProviderAuthType::None => 2,
    }
}

fn auth_from_value(value: &str) -> ProviderAuthType {
    match value {
        "x-api-key header" => ProviderAuthType::XApiKey,
        "No authentication" => ProviderAuthType::None,
        _ => ProviderAuthType::Bearer,
    }
}

fn reload_list(
    service: &ProviderManagementService,
    state: &Rc<RefCell<ProviderManagementViewModel>>,
) {
    match service.list_providers() {
        Ok(list) => {
            let mut state = state.borrow_mut();
            state.list = list;
            state.error = None;
        }
        Err(error) => {
            state.borrow_mut().error = Some(describe_provider_error(&error, None));
        }
    }
}

fn open_detail(
    service: &ProviderManagementService,
    state: &Rc<RefCell<ProviderManagementViewModel>>,
    id: &str,
) {
    match service.provider_detail(id) {
        Ok(detail) => {
            let mut state = state.borrow_mut();
            state.detail = Some(detail);
            state.form = None;
            state.pending_confirmation = None;
            state.error = None;
        }
        Err(error) => {
            state.borrow_mut().error = Some(describe_provider_error(&error, None));
        }
    }
}

fn save_form(
    service: &ProviderManagementService,
    state: &Rc<RefCell<ProviderManagementViewModel>>,
) {
    let Some(mut form) = state.borrow().form.clone() else {
        return;
    };

    match parse_headers_text(&form.headers_text) {
        Ok(headers) => form.headers = headers,
        Err(error) => {
            form.error = Some(describe_provider_error(&error, None));
            state.borrow_mut().form = Some(form);
            return;
        }
    }

    let result = match form.editing_provider_id.as_deref() {
        Some(id) => service.update_custom_provider(id, &form),
        None => service.create_custom_provider(&form),
    };

    match result {
        Ok(provider) => {
            {
                let mut state = state.borrow_mut();
                state.form = None;
                state.notice = Some(format!("Saved {}.", provider.label));
                state.error = None;
            }
            reload_list(service, state);
            open_detail(service, state, &provider.id);
        }
        Err(error) => {
            let copy = describe_provider_error(&error, Some(form.api_key().expose()));
            if let Some(form) = state.borrow_mut().form.as_mut() {
                form.error = Some(copy);
            }
        }
    }
}

fn rotate_key(
    service: &ProviderManagementService,
    state: &Rc<RefCell<ProviderManagementViewModel>>,
    key: &str,
) {
    let Some(id) = state
        .borrow()
        .detail
        .as_ref()
        .map(|detail| detail.id.clone())
    else {
        return;
    };

    let secret = SecretValue::new(key);
    match service.rotate_key(&id, &secret) {
        Ok(_) => {
            {
                let mut state = state.borrow_mut();
                state.notice = Some("Key rotated and validated.".to_owned());
                state.error = None;
                state.pending_confirmation = None;
            }
            open_detail(service, state, &id);
            reload_list(service, state);
        }
        Err(error) => {
            let copy = describe_provider_error(&error, Some(secret.expose()));
            state.borrow_mut().error = Some(copy);
        }
    }
}

fn forget_key(
    service: &ProviderManagementService,
    state: &Rc<RefCell<ProviderManagementViewModel>>,
) {
    let Some(id) = state
        .borrow()
        .detail
        .as_ref()
        .map(|detail| detail.id.clone())
    else {
        return;
    };

    let confirmed = state
        .borrow()
        .pending_confirmation
        .as_ref()
        .is_some_and(|pending| {
            pending.action == ConfirmationAction::ForgetKey
                && pending.provider_id.as_deref() == Some(id.as_str())
        });

    if !confirmed {
        state.borrow_mut().pending_confirmation = Some(PendingConfirmation {
            action: ConfirmationAction::ForgetKey,
            provider_id: Some(id),
        });
        return;
    }

    match service.forget_key(&id, true) {
        Ok(_) => {
            {
                let mut state = state.borrow_mut();
                state.notice = Some("Key forgotten. Add a new key before sending.".to_owned());
                state.error = None;
                state.pending_confirmation = None;
            }
            open_detail(service, state, &id);
            reload_list(service, state);
        }
        Err(error) => {
            state.borrow_mut().error = Some(describe_provider_error(&error, None));
        }
    }
}

fn forget_all_keys(
    service: &ProviderManagementService,
    state: &Rc<RefCell<ProviderManagementViewModel>>,
) {
    let confirmed = state
        .borrow()
        .pending_confirmation
        .as_ref()
        .is_some_and(|pending| pending.action == ConfirmationAction::ForgetAllKeys);

    if !confirmed {
        state.borrow_mut().pending_confirmation = Some(PendingConfirmation {
            action: ConfirmationAction::ForgetAllKeys,
            provider_id: None,
        });
        return;
    }

    match service.forget_all_keys(true) {
        Ok(cleared) => {
            {
                let mut state = state.borrow_mut();
                state.notice = Some(format!("Forgot keys for {cleared} provider(s)."));
                state.error = None;
                state.pending_confirmation = None;
            }
            reload_list(service, state);
            if let Some(id) = state
                .borrow()
                .detail
                .as_ref()
                .map(|detail| detail.id.clone())
            {
                open_detail(service, state, &id);
            }
        }
        Err(error) => {
            state.borrow_mut().error = Some(describe_provider_error(&error, None));
        }
    }
}

fn delete_provider(
    service: &ProviderManagementService,
    state: &Rc<RefCell<ProviderManagementViewModel>>,
) {
    let Some(id) = state
        .borrow()
        .detail
        .as_ref()
        .map(|detail| detail.id.clone())
    else {
        return;
    };

    let confirmed = state
        .borrow()
        .pending_confirmation
        .as_ref()
        .is_some_and(|pending| {
            pending.action == ConfirmationAction::DeleteProvider
                && pending.provider_id.as_deref() == Some(id.as_str())
        });

    if !confirmed {
        state.borrow_mut().pending_confirmation = Some(PendingConfirmation {
            action: ConfirmationAction::DeleteProvider,
            provider_id: Some(id),
        });
        return;
    }

    match service.delete_provider(&id, true) {
        Ok(()) => {
            {
                let mut state = state.borrow_mut();
                state.detail = None;
                state.notice =
                    Some("Provider deleted. Past messages keep their model labels.".to_owned());
                state.error = None;
                state.pending_confirmation = None;
            }
            reload_list(service, state);
        }
        Err(error) => {
            state.borrow_mut().error = Some(describe_provider_error(&error, None));
        }
    }
}

fn test_connection(
    service: &ProviderManagementService,
    state: &Rc<RefCell<ProviderManagementViewModel>>,
) {
    let Some(id) = state
        .borrow()
        .detail
        .as_ref()
        .map(|detail| detail.id.clone())
    else {
        return;
    };

    match service.test_connection(&id) {
        Ok(_) => {
            {
                let mut state = state.borrow_mut();
                state.notice = Some("Connection test succeeded.".to_owned());
                state.error = None;
            }
            open_detail(service, state, &id);
        }
        Err(error) => {
            state.borrow_mut().error = Some(describe_provider_error(&error, None));
        }
    }
}

fn toggle_enabled(
    service: &ProviderManagementService,
    state: &Rc<RefCell<ProviderManagementViewModel>>,
    enabled: bool,
) {
    let Some(id) = state
        .borrow()
        .detail
        .as_ref()
        .map(|detail| detail.id.clone())
    else {
        return;
    };

    match service.set_enabled(&id, enabled) {
        Ok(_) => {
            open_detail(service, state, &id);
            reload_list(service, state);
        }
        Err(error) => {
            state.borrow_mut().error = Some(describe_provider_error(&error, None));
        }
    }
}

/// Owns the provider management view model and the Slint window that renders it.
pub struct ProviderManagementController {
    service: Arc<ProviderManagementService>,
    state: Rc<RefCell<ProviderManagementViewModel>>,
    window: ProviderManagementWindow,
}

impl ProviderManagementController {
    /// Create the provider management window and load the provider list.
    pub fn new(service: Arc<ProviderManagementService>) -> Result<Self, slint::PlatformError> {
        let window = ProviderManagementWindow::new()?;
        let state = Rc::new(RefCell::new(ProviderManagementViewModel::default()));
        let controller = Self {
            service,
            state,
            window,
        };
        reload_list(&controller.service, &controller.state);
        controller.wire();
        controller.sync();
        Ok(controller)
    }

    /// The underlying Slint window.
    pub fn window(&self) -> &ProviderManagementWindow {
        &self.window
    }

    /// Borrow the current view model.
    pub fn state(&self) -> std::cell::Ref<'_, ProviderManagementViewModel> {
        self.state.borrow()
    }

    /// Push the current view model into the window.
    pub fn sync(&self) {
        sync_window(&self.window, &self.state.borrow());
    }

    fn wire(&self) {
        let weak = self.window.as_weak();
        let state = Rc::clone(&self.state);
        self.window.on_add_provider(move || {
            {
                let mut state = state.borrow_mut();
                state.form = Some(CustomProviderForm::new());
                state.pending_confirmation = None;
                state.error = None;
            }
            if let Some(window) = weak.upgrade() {
                sync_window(&window, &state.borrow());
            }
        });

        let weak = self.window.as_weak();
        let state = Rc::clone(&self.state);
        let service = Arc::clone(&self.service);
        self.window.on_select_provider(move |id| {
            open_detail(&service, &state, id.as_str());
            if let Some(window) = weak.upgrade() {
                sync_window(&window, &state.borrow());
            }
        });

        let weak = self.window.as_weak();
        let state = Rc::clone(&self.state);
        let service = Arc::clone(&self.service);
        self.window.on_save_provider(move || {
            save_form(&service, &state);
            if let Some(window) = weak.upgrade() {
                sync_window(&window, &state.borrow());
            }
        });

        let weak = self.window.as_weak();
        let state = Rc::clone(&self.state);
        self.window.on_cancel_form(move || {
            {
                let mut state = state.borrow_mut();
                state.form = None;
                state.pending_confirmation = None;
                state.error = None;
            }
            if let Some(window) = weak.upgrade() {
                sync_window(&window, &state.borrow());
            }
        });

        let weak = self.window.as_weak();
        let state = Rc::clone(&self.state);
        let service = Arc::clone(&self.service);
        self.window.on_rotate_key(move |key| {
            rotate_key(&service, &state, key.as_str());
            if let Some(window) = weak.upgrade() {
                sync_window(&window, &state.borrow());
            }
        });

        let weak = self.window.as_weak();
        let state = Rc::clone(&self.state);
        let service = Arc::clone(&self.service);
        self.window.on_forget_key(move || {
            forget_key(&service, &state);
            if let Some(window) = weak.upgrade() {
                sync_window(&window, &state.borrow());
            }
        });

        let weak = self.window.as_weak();
        let state = Rc::clone(&self.state);
        let service = Arc::clone(&self.service);
        self.window.on_forget_all_keys(move || {
            forget_all_keys(&service, &state);
            if let Some(window) = weak.upgrade() {
                sync_window(&window, &state.borrow());
            }
        });

        let weak = self.window.as_weak();
        let state = Rc::clone(&self.state);
        let service = Arc::clone(&self.service);
        self.window.on_delete_provider(move || {
            delete_provider(&service, &state);
            if let Some(window) = weak.upgrade() {
                sync_window(&window, &state.borrow());
            }
        });

        let weak = self.window.as_weak();
        let state = Rc::clone(&self.state);
        let service = Arc::clone(&self.service);
        self.window.on_test_connection(move || {
            test_connection(&service, &state);
            if let Some(window) = weak.upgrade() {
                sync_window(&window, &state.borrow());
            }
        });

        let weak = self.window.as_weak();
        let state = Rc::clone(&self.state);
        let service = Arc::clone(&self.service);
        self.window.on_toggle_enabled(move |enabled| {
            toggle_enabled(&service, &state, enabled);
            if let Some(window) = weak.upgrade() {
                sync_window(&window, &state.borrow());
            }
        });

        let weak = self.window.as_weak();
        let state = Rc::clone(&self.state);
        self.window.on_form_label_changed(move |value| {
            if let Some(form) = state.borrow_mut().form.as_mut() {
                form.label = value.to_string();
            }
            if let Some(window) = weak.upgrade() {
                sync_window(&window, &state.borrow());
            }
        });

        let weak = self.window.as_weak();
        let state = Rc::clone(&self.state);
        self.window.on_form_base_url_changed(move |value| {
            if let Some(form) = state.borrow_mut().form.as_mut() {
                form.base_url = value.to_string();
                form.cleartext_warning = validate_base_url(&form.base_url)
                    .ok()
                    .and_then(|validated| validated.cleartext_warning);
            }
            if let Some(window) = weak.upgrade() {
                sync_window(&window, &state.borrow());
            }
        });

        let weak = self.window.as_weak();
        let state = Rc::clone(&self.state);
        self.window.on_form_auth_changed(move |value| {
            if let Some(form) = state.borrow_mut().form.as_mut() {
                form.auth_type = auth_from_value(value.as_str());
            }
            if let Some(window) = weak.upgrade() {
                sync_window(&window, &state.borrow());
            }
        });

        let weak = self.window.as_weak();
        let state = Rc::clone(&self.state);
        self.window.on_form_api_key_changed(move |value| {
            if let Some(form) = state.borrow_mut().form.as_mut() {
                form.set_api_key(value.as_str());
            }
            if let Some(window) = weak.upgrade() {
                sync_window(&window, &state.borrow());
            }
        });

        let weak = self.window.as_weak();
        let state = Rc::clone(&self.state);
        self.window.on_form_headers_changed(move |value| {
            if let Some(form) = state.borrow_mut().form.as_mut() {
                form.headers_text = value.to_string();
            }
            if let Some(window) = weak.upgrade() {
                sync_window(&window, &state.borrow());
            }
        });

        let weak = self.window.as_weak();
        let state = Rc::clone(&self.state);
        self.window.on_form_test_connection_changed(move |value| {
            if let Some(form) = state.borrow_mut().form.as_mut() {
                form.test_connection = value;
            }
            if let Some(window) = weak.upgrade() {
                sync_window(&window, &state.borrow());
            }
        });
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::HashMap;
    use std::sync::Mutex;

    use chatui_domain::{
        BrowserCors, ChatError, ChatErrorCode, ChatFontSize, ChatParams, Conversation, Density,
        Model, Provider, ProviderKind, SendKey, Settings, Theme, Timestamp,
    };
    use chatui_providers::ProviderKeyInfo;
    use chatui_secrets::{SecretError, SecretId, SecretStore};
    use slint::Model as _;

    use crate::providers::service::{
        ConversationStore, ModelStore, ProviderKeyValidator, ProviderStore, SettingsStore,
    };

    const FAKE_KEY: &str = "sk-test-not-a-real-key";

    #[derive(Default)]
    struct FakeProviders {
        providers: Mutex<Vec<Provider>>,
    }

    impl ProviderStore for FakeProviders {
        fn list(&self) -> Result<Vec<Provider>, crate::providers::error::ProviderManagementError> {
            Ok(self.providers.lock().expect("providers lock").clone())
        }

        fn get(
            &self,
            id: &str,
        ) -> Result<Option<Provider>, crate::providers::error::ProviderManagementError> {
            Ok(self
                .providers
                .lock()
                .expect("providers lock")
                .iter()
                .find(|provider| provider.id == id)
                .cloned())
        }

        fn save_provider(
            &self,
            provider: &Provider,
        ) -> Result<(), crate::providers::error::ProviderManagementError> {
            let mut providers = self.providers.lock().expect("providers lock");
            if let Some(existing) = providers.iter_mut().find(|item| item.id == provider.id) {
                *existing = provider.clone();
            } else {
                providers.push(provider.clone());
            }
            Ok(())
        }

        fn delete(&self, id: &str) -> Result<(), crate::providers::error::ProviderManagementError> {
            self.providers
                .lock()
                .expect("providers lock")
                .retain(|provider| provider.id != id);
            Ok(())
        }
    }

    #[derive(Default)]
    struct FakeModels {
        models: Mutex<Vec<Model>>,
    }

    impl ModelStore for FakeModels {
        fn list_by_provider(
            &self,
            provider_id: &str,
        ) -> Result<Vec<Model>, crate::providers::error::ProviderManagementError> {
            Ok(self
                .models
                .lock()
                .expect("models lock")
                .iter()
                .filter(|model| model.provider_id == provider_id)
                .cloned()
                .collect())
        }

        fn delete_by_provider(
            &self,
            provider_id: &str,
        ) -> Result<(), crate::providers::error::ProviderManagementError> {
            self.models
                .lock()
                .expect("models lock")
                .retain(|model| model.provider_id != provider_id);
            Ok(())
        }
    }

    #[derive(Default)]
    struct FakeConversations {
        conversations: Mutex<Vec<Conversation>>,
    }

    impl ConversationStore for FakeConversations {
        fn list_all(
            &self,
        ) -> Result<Vec<Conversation>, crate::providers::error::ProviderManagementError> {
            Ok(self
                .conversations
                .lock()
                .expect("conversations lock")
                .clone())
        }

        fn update(
            &self,
            conversation: &Conversation,
        ) -> Result<(), crate::providers::error::ProviderManagementError> {
            let mut conversations = self.conversations.lock().expect("conversations lock");
            if let Some(existing) = conversations
                .iter_mut()
                .find(|item| item.id == conversation.id)
            {
                *existing = conversation.clone();
            }
            Ok(())
        }
    }

    struct FakeSettings {
        settings: Mutex<Settings>,
    }

    impl SettingsStore for FakeSettings {
        fn load(&self) -> Result<Settings, crate::providers::error::ProviderManagementError> {
            Ok(self.settings.lock().expect("settings lock").clone())
        }

        fn save(
            &self,
            settings: &Settings,
        ) -> Result<(), crate::providers::error::ProviderManagementError> {
            *self.settings.lock().expect("settings lock") = settings.clone();
            Ok(())
        }
    }

    #[derive(Default)]
    struct FakeSecrets {
        values: Mutex<HashMap<SecretId, SecretValue>>,
    }

    impl SecretStore for FakeSecrets {
        fn set(&self, id: &SecretId, value: &SecretValue) -> Result<(), SecretError> {
            self.values
                .lock()
                .expect("secrets lock")
                .insert(id.clone(), value.clone());
            Ok(())
        }

        fn get(&self, id: &SecretId) -> Result<Option<SecretValue>, SecretError> {
            Ok(self.values.lock().expect("secrets lock").get(id).cloned())
        }

        fn delete(&self, id: &SecretId) -> Result<(), SecretError> {
            self.values.lock().expect("secrets lock").remove(id);
            Ok(())
        }

        fn forget_key(&self, id: &SecretId) -> Result<(), SecretError> {
            self.delete(id)
        }

        fn forget_all_keys(&self) -> Result<(), SecretError> {
            self.values.lock().expect("secrets lock").clear();
            Ok(())
        }

        fn is_available(&self) -> bool {
            true
        }
    }

    struct FakeValidator;

    impl ProviderKeyValidator for FakeValidator {
        fn validate_key(
            &self,
            _provider: &Provider,
            _key: &SecretValue,
        ) -> Result<ProviderKeyInfo, ChatError> {
            Ok(ProviderKeyInfo {
                label: Some("Work key".to_owned()),
                usage_usd: Some(1.25),
                limit_usd: Some(10.0),
                limit_remaining_usd: Some(8.75),
                is_free_tier: Some(false),
                rate_limit: None,
            })
        }
    }

    fn default_settings() -> Settings {
        Settings {
            theme: Theme::System,
            density: Density::Comfortable,
            chat_font_size: ChatFontSize::Md,
            send_key: SendKey::Enter,
            default_provider_id: None,
            default_model_id: None,
            default_system_prompt: String::new(),
            default_params: ChatParams::default(),
            remember_keys_on_web: true,
            show_token_usage: true,
            load_remote_images: false,
            last_active_conversation_id: None,
            backup: None,
            onboarding_completed: true,
        }
    }

    fn provider() -> Provider {
        let now = Timestamp::now();
        Provider {
            id: "provider-1".to_owned(),
            preset_id: "openrouter".to_owned(),
            label: "Personal".to_owned(),
            kind: ProviderKind::OpenaiCompatible,
            base_url: "https://openrouter.ai/api/v1".to_owned(),
            auth_type: chatui_domain::ProviderAuthType::Bearer,
            secret_id: Some("provider-1".to_owned()),
            headers: std::collections::BTreeMap::new(),
            enabled: true,
            browser_cors: BrowserCors::Supported,
            metadata: Some(serde_json::json!({
                "key": {
                    "label": "Work key",
                    "usageUsd": 1.25,
                    "limitUsd": 10.0,
                    "limitRemainingUsd": 8.75,
                    "isFreeTier": false,
                    "rateLimit": { "requests": 60, "interval": "minute" }
                },
                "validatedAt": "2026-11-07T00:00:00.000Z"
            })),
            created_at: now,
            updated_at: now,
        }
    }

    fn service() -> Arc<ProviderManagementService> {
        let providers = Arc::new(FakeProviders::default());
        let models = Arc::new(FakeModels::default());
        let conversations = Arc::new(FakeConversations::default());
        let settings = Arc::new(FakeSettings {
            settings: Mutex::new(default_settings()),
        });
        let secrets = Arc::new(FakeSecrets::default());
        let provider = provider();
        let secret_id = SecretId::new("provider-1").expect("valid secret ID");
        secrets
            .set(&secret_id, &SecretValue::new(FAKE_KEY))
            .expect("store secret");
        providers.save_provider(&provider).expect("save provider");

        Arc::new(ProviderManagementService::new(
            providers,
            models,
            conversations,
            settings,
            secrets,
            Arc::new(FakeValidator),
        ))
    }

    #[test]
    fn window_renders_the_provider_list_without_the_key() {
        i_slint_backend_testing::init_no_event_loop();

        let controller = ProviderManagementController::new(service()).expect("provider window");
        let window = controller.window();

        let providers = window.get_providers();
        assert_eq!(providers.row_count(), 1);
        let row = providers.row_data(0).expect("provider row");
        assert_eq!(row.label, "Personal");
        assert!(row.meta.contains("Recommended"));
        assert!(row.meta.contains("Key stored"));
        assert!(!row.meta.contains(FAKE_KEY));
        assert!(window.get_add_provider_available());
    }

    #[test]
    fn selecting_a_provider_renders_detail_metadata() {
        i_slint_backend_testing::init_no_event_loop();

        let controller = ProviderManagementController::new(service()).expect("provider window");
        let window = controller.window();

        window.invoke_select_provider("provider-1".into());

        assert!(window.get_detail_visible());
        assert_eq!(window.get_detail_label(), "Personal");
        assert_eq!(window.get_detail_key_label(), "Work key");
        assert_eq!(window.get_detail_usage(), "$1.25");
        assert_eq!(window.get_detail_limit(), "$10");
        assert_eq!(window.get_detail_free_tier(), "No");
        assert_eq!(window.get_detail_rate_limit(), "60 requests per minute");
        assert_eq!(window.get_detail_cors(), "Supported");
        assert_eq!(window.get_detail_docs_url(), "https://openrouter.ai/docs");
        assert_eq!(window.get_detail_model_count_text(), "Models: 0");
        assert!(window.get_detail_key_present());
        assert!(!window.get_detail_usage().contains(FAKE_KEY));
    }

    #[test]
    fn add_provider_opens_the_custom_form() {
        i_slint_backend_testing::init_no_event_loop();

        let controller = ProviderManagementController::new(service()).expect("provider window");
        let window = controller.window();

        window.invoke_add_provider();

        assert!(window.get_form_visible());
        assert_eq!(window.get_form_title(), "Add a custom provider");
        assert_eq!(window.get_form_auth_index(), 0);
        assert!(window.get_form_test_connection());
    }

    #[test]
    fn custom_form_cleartext_warning_is_shown_for_localhost() {
        i_slint_backend_testing::init_no_event_loop();

        let controller = ProviderManagementController::new(service()).expect("provider window");
        let window = controller.window();

        window.invoke_add_provider();
        window.invoke_form_base_url_changed("http://localhost:11434/v1".into());

        assert!(!window.get_form_cleartext_warning().is_empty());
    }

    #[test]
    fn forget_key_requires_two_clicks() {
        i_slint_backend_testing::init_no_event_loop();

        let controller = ProviderManagementController::new(service()).expect("provider window");
        let window = controller.window();

        window.invoke_select_provider("provider-1".into());
        window.invoke_forget_key();

        assert!(!window.get_confirm_label().is_empty());
        assert!(window.get_detail_key_present());

        window.invoke_forget_key();

        assert!(window.get_confirm_label().is_empty());
        assert!(!window.get_detail_key_present());
    }

    #[test]
    fn delete_provider_requires_two_clicks() {
        i_slint_backend_testing::init_no_event_loop();

        let controller = ProviderManagementController::new(service()).expect("provider window");
        let window = controller.window();

        window.invoke_select_provider("provider-1".into());
        window.invoke_delete_provider();

        assert!(!window.get_confirm_label().is_empty());
        assert!(window.get_detail_visible());

        window.invoke_delete_provider();

        assert!(!window.get_detail_visible());
        assert_eq!(window.get_providers().row_count(), 0);
    }

    #[test]
    fn rotate_key_updates_the_detail_without_showing_the_key() {
        i_slint_backend_testing::init_no_event_loop();

        let controller = ProviderManagementController::new(service()).expect("provider window");
        let window = controller.window();

        window.invoke_select_provider("provider-1".into());
        window.invoke_rotate_key("sk-test-other-not-a-real-key".into());

        assert_eq!(window.get_detail_key_label(), "Work key");
        assert!(!window.get_detail_usage().contains("sk-test"));
        assert!(!window.get_notice().contains("sk-test"));
    }

    #[test]
    fn failed_rotation_shows_actionable_copy_without_the_key() {
        i_slint_backend_testing::init_no_event_loop();

        struct FailingValidator;

        impl ProviderKeyValidator for FailingValidator {
            fn validate_key(
                &self,
                _provider: &Provider,
                key: &SecretValue,
            ) -> Result<ProviderKeyInfo, ChatError> {
                Err(ChatError {
                    code: ChatErrorCode::Auth,
                    message: format!("request failed for {}", key.expose()),
                    provider_id: Some("openrouter".to_owned()),
                    status: None,
                    retryable: false,
                    retry_after_seconds: None,
                    request_id: None,
                    details: None,
                    at: Timestamp::now(),
                })
            }
        }

        let providers = Arc::new(FakeProviders::default());
        let models = Arc::new(FakeModels::default());
        let conversations = Arc::new(FakeConversations::default());
        let settings = Arc::new(FakeSettings {
            settings: Mutex::new(default_settings()),
        });
        let secrets = Arc::new(FakeSecrets::default());
        let provider = provider();
        let secret_id = SecretId::new("provider-1").expect("valid secret ID");
        secrets
            .set(&secret_id, &SecretValue::new(FAKE_KEY))
            .expect("store secret");
        providers.save_provider(&provider).expect("save provider");
        let service = Arc::new(ProviderManagementService::new(
            providers,
            models,
            conversations,
            settings,
            secrets,
            Arc::new(FailingValidator),
        ));

        let controller = ProviderManagementController::new(service).expect("provider window");
        let window = controller.window();

        window.invoke_select_provider("provider-1".into());
        window.invoke_rotate_key("sk-test-other-not-a-real-key".into());

        assert_eq!(window.get_error_title(), "API key rejected");
        assert!(!window.get_error_details().contains(FAKE_KEY));
        assert!(!window.get_error_details().contains("sk-test-other"));
    }
}
