//! Slint model management and picker view and controller.
//!
//! The controller binds the framework-independent
//! [`ModelManagementViewModel`] to a Slint window. The management mode shows
//! every cached model, including hidden rows, with pin/hide controls and the
//! manual-add form. The picker mode applies search, hides hidden rows, marks the
//! default model, and shows an unavailable-model warning plus the provider docs
//! link.

use std::cell::RefCell;
use std::rc::Rc;
use std::sync::Arc;

use slint::{ComponentHandle, ModelRc, SharedString, VecModel};

use crate::models::error::{ModelManagementError, describe_model_error};
use crate::models::service::ModelManagementService;
use crate::models::state::{
    ManualModelForm, ModelGroup, ModelManagementViewModel, ModelRow as StateModelRow,
    capability_chip_label,
};

slint::slint! {
    import { Button, CheckBox, LineEdit, Palette, ScrollView } from "std-widgets.slint";

    export struct ModelListRow {
        id: string,
        group-label: string,
        group-start: bool,
        label: string,
        remote-id: string,
        meta: string,
        chips: string,
        badges: string,
        source: string,
        pinned: bool,
        hidden: bool,
        is-default: bool,
        docs-url: string,
    }

    export component ModelManagementWindow inherits Window {
        title: "ChatUI models";
        width: 1000px;
        height: 760px;

        in property <[ModelListRow]> models: [];
        in property <string> provider-label: "";
        in property <string> summary: "";
        in property <string> notice: "";
        in property <string> error-title: "";
        in property <string> error-message: "";
        in property <string> error-details: "";
        in property <bool> loading: false;
        in property <bool> empty: false;
        in property <string> empty-message: "";
        in property <string> search-query: "";
        in property <string> selected-model-id: "";
        in property <string> default-model-id: "";
        in property <string> availability-warning: "";
        in property <string> docs-url: "";
        in property <int> total-count: 0;
        in property <int> pinned-count: 0;
        in property <int> hidden-count: 0;
        in property <bool> picker-mode: false;

        in property <bool> manual-visible: false;
        in property <string> manual-remote-id: "";
        in property <string> manual-label: "";
        in property <string> manual-context: "";
        in property <string> manual-capabilities: "text";
        in property <string> manual-error: "";
        in property <bool> dark: false;

        background: root.dark ? #0f1115 : #ffffff;
        init => {
            Palette.color-scheme = root.dark ? ColorScheme.dark : ColorScheme.light;
        }
        changed dark => {
            Palette.color-scheme = root.dark ? ColorScheme.dark : ColorScheme.light;
        }

        callback refresh();
        callback retry();
        callback search-changed(string);
        callback pin-model(string, bool);
        callback hide-model(string, bool);
        callback set-default(string);
        callback open-manual();
        callback cancel-manual();
        callback manual-remote-id-changed(string);
        callback manual-label-changed(string);
        callback manual-context-changed(string);
        callback manual-capabilities-changed(string);
        callback save-manual();
        callback toggle-picker-mode(bool);

        VerticalLayout {
            padding: 16px;
            spacing: 8px;

            HorizontalLayout {
                spacing: 8px;
                Text { text: "Models"; font-size: 22px; font-weight: 600; }
                Text { text: root.provider-label; color: root.dark ? #9ca3af : #5f6368; vertical-alignment: center; }
                Rectangle { horizontal-stretch: 1; }
                CheckBox {
                    text: "Picker mode";
                    checked: root.picker-mode;
                    toggled => { root.toggle-picker-mode(self.checked); }
                }
                Button { text: "Refresh models"; clicked => { root.refresh(); } }
            }

            Text { text: root.summary; color: root.dark ? #9ca3af : #5f6368; }

            LineEdit {
                placeholder-text: "Search by name or model id";
                accessible-label: "Search models";
                text: root.search-query;
                edited(text) => { root.search-changed(text); }
            }

            if (!root.notice.is-empty) : Text {
                text: root.notice;
                accessible-live-region: polite;
                wrap: word-wrap;
            }

            if (!root.error-title.is-empty) : Rectangle {
                background: root.dark ? #3b1d1d : #fde8e8;
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
                    if (!root.error-details.is-empty) : Text { text: root.error-details; wrap: word-wrap; color: root.dark ? #9ca3af : #5f6368; }
                    Button { text: "Retry"; clicked => { root.retry(); } }
                }
            }

            if (!root.availability-warning.is-empty) : Text {
                text: root.availability-warning;
                color: root.dark ? #fbbf24 : #b45309;
                wrap: word-wrap;
            }

            if (root.loading) : Text { text: "Loading models…"; }

            if (root.empty && !root.loading) : Text { text: root.empty-message; wrap: word-wrap; }

            ScrollView {
                VerticalLayout {
                    for row in root.models : VerticalLayout {
                        if (row.group-start) : Text {
                            text: row.group-label;
                            font-weight: 600;
                            font-size: 16px;
                        }
                        Rectangle {
                            background: row.is-default ? (root.dark ? #16324f : #e8f0fe) : (root.dark ? #171a21 : #f7f7f8);
                            border-radius: 8px;
                            VerticalLayout {
                                padding: 8px;
                                spacing: 4px;
                                HorizontalLayout {
                                    spacing: 8px;
                                    VerticalLayout {
                                        Text { text: row.label; font-weight: 600; }
                                        Text { text: row.meta; color: root.dark ? #9ca3af : #5f6368; wrap: word-wrap; }
                                    }
                                    Rectangle { horizontal-stretch: 1; }
                                    if (!row.badges.is-empty) : Text {
                                        text: row.badges;
                                        color: root.dark ? #4ade80 : #166534;
                                        vertical-alignment: center;
                                    }
                                    Button {
                                        text: row.pinned ? "Unpin" : "Pin";
                                        clicked => { root.pin-model(row.id, !row.pinned); }
                                    }
                                    Button {
                                        text: row.hidden ? "Unhide" : "Hide";
                                        clicked => { root.hide-model(row.id, !row.hidden); }
                                    }
                                    Button {
                                        text: "Set default";
                                        clicked => { root.set-default(row.id); }
                                    }
                                }
                                if (!row.chips.is-empty) : Text {
                                    text: row.chips;
                                    color: root.dark ? #c7cbd1 : #4a4a4a;
                                    wrap: word-wrap;
                                }
                                if (!row.docs-url.is-empty) : Text {
                                    text: "Docs: " + row.docs-url;
                                    wrap: word-wrap;
                                }
                            }
                        }
                    }
                }
            }

            if (root.manual-visible) : VerticalLayout {
                spacing: 4px;
                Text { text: "Add a model manually"; font-weight: 600; }
                Text { text: "Model id"; }
                LineEdit {
                    text: root.manual-remote-id;
                    accessible-label: "Model id";
                    edited(text) => { root.manual-remote-id-changed(text); }
                }
                Text { text: "Label (optional)"; }
                LineEdit {
                    text: root.manual-label;
                    accessible-label: "Label";
                    edited(text) => { root.manual-label-changed(text); }
                }
                Text { text: "Context window (tokens, optional)"; }
                LineEdit {
                    text: root.manual-context;
                    accessible-label: "Context window";
                    edited(text) => { root.manual-context-changed(text); }
                }
                Text { text: "Capabilities (text, vision, files, tools, reasoning, streaming)"; }
                LineEdit {
                    text: root.manual-capabilities;
                    accessible-label: "Capabilities";
                    edited(text) => { root.manual-capabilities-changed(text); }
                }
                if (!root.manual-error.is-empty) : Text {
                    text: root.manual-error;
                    color: root.dark ? #f87171 : #b91c1c;
                    wrap: word-wrap;
                }
                HorizontalLayout {
                    spacing: 8px;
                    Button { text: "Add model"; clicked => { root.save-manual(); } }
                    Button { text: "Cancel"; clicked => { root.cancel-manual(); } }
                }
            }

            HorizontalLayout {
                spacing: 8px;
                Button { text: "Add model manually"; clicked => { root.open-manual(); } }
            }
        }
    }
}

/// Push the current view model into a Slint window.
pub fn sync_window(window: &ModelManagementWindow, state: &ModelManagementViewModel) {
    let groups: &[ModelGroup] = &state.picker.groups;
    let mut rows = Vec::new();

    for group in groups {
        for (index, row) in group.models.iter().enumerate() {
            rows.push(model_row(row, group, index == 0));
        }
    }

    window.set_models(ModelRc::new(VecModel::from(rows)));
    window.set_provider_label(state.provider_label.clone().into());
    window.set_summary(
        format!(
            "{} models · {} pinned · {} hidden",
            state.list.total, state.list.pinned_count, state.list.hidden_count
        )
        .into(),
    );
    window.set_notice(
        state
            .notice
            .clone()
            .or_else(|| state.list.notice.clone())
            .or_else(|| state.picker.notice.clone())
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
    window.set_loading(state.list.loading);
    window.set_empty(state.picker.is_empty());
    window.set_empty_message(
        if state.picker_mode {
            "No models match your search."
        } else {
            "No models are cached. Refresh discovery or add a model manually."
        }
        .into(),
    );
    window.set_search_query(state.query.clone().into());
    window.set_selected_model_id(
        state
            .picker
            .selected_model_id
            .clone()
            .unwrap_or_default()
            .into(),
    );
    window.set_default_model_id(
        state
            .picker
            .default_model_id
            .clone()
            .unwrap_or_default()
            .into(),
    );
    window.set_availability_warning(
        state
            .picker
            .availability
            .message
            .clone()
            .unwrap_or_default()
            .into(),
    );
    window.set_docs_url(state.picker.docs_url.clone().unwrap_or_default().into());
    window.set_total_count(state.list.total as i32);
    window.set_pinned_count(state.list.pinned_count as i32);
    window.set_hidden_count(state.list.hidden_count as i32);
    window.set_picker_mode(state.picker_mode);

    if let Some(form) = state.manual.as_ref() {
        window.set_manual_visible(true);
        window.set_manual_remote_id(form.remote_id.clone().into());
        window.set_manual_label(form.label.clone().into());
        window.set_manual_context(
            form.context_window
                .map(|value| value.to_string())
                .unwrap_or_default()
                .into(),
        );
        window.set_manual_capabilities(
            form.capabilities
                .iter()
                .map(|capability| capability_chip_label(*capability))
                .collect::<Vec<_>>()
                .join(", ")
                .into(),
        );
        window.set_manual_error(
            form.error
                .as_ref()
                .map(|error| error.message.clone())
                .unwrap_or_default()
                .into(),
        );
    } else {
        window.set_manual_visible(false);
    }
}

fn model_row(row: &StateModelRow, group: &ModelGroup, group_start: bool) -> ModelListRow {
    ModelListRow {
        id: SharedString::from(row.id.clone()),
        group_label: SharedString::from(group.label.clone()),
        group_start,
        label: SharedString::from(row.label.clone()),
        remote_id: SharedString::from(row.remote_id.clone()),
        meta: SharedString::from(row.meta_line()),
        chips: SharedString::from(row.capability_chips().join(" · ")),
        badges: SharedString::from(row.badges().join(" · ")),
        source: SharedString::from(row.source_label()),
        pinned: row.pinned,
        hidden: row.hidden,
        is_default: row.is_default,
        docs_url: SharedString::from(row.docs_url.clone().unwrap_or_default()),
    }
}

fn reload(service: &ModelManagementService, state: &Rc<RefCell<ModelManagementViewModel>>) {
    let (provider_id, query, picker_mode) = {
        let state = state.borrow();
        (
            state.provider_id.clone(),
            state.query.clone(),
            state.picker_mode,
        )
    };

    match service.list_models(provider_id.as_deref()) {
        Ok(list) => {
            state.borrow_mut().list = list;
        }
        Err(error) => {
            state.borrow_mut().error = Some(describe_model_error(&error));
        }
    }

    match service.picker(provider_id.as_deref(), &query, !picker_mode) {
        Ok(picker) => {
            state.borrow_mut().picker = picker;
        }
        Err(error) => {
            state.borrow_mut().error = Some(describe_model_error(&error));
        }
    }
}

fn refresh(service: &ModelManagementService, state: &Rc<RefCell<ModelManagementViewModel>>) {
    let Some(provider_id) = state.borrow().provider_id.clone() else {
        state.borrow_mut().error = Some(describe_model_error(&ModelManagementError::InvalidInput(
            "Add a provider before refreshing models.".to_owned(),
        )));
        return;
    };

    state.borrow_mut().list.loading = true;

    match service.refresh(&provider_id) {
        Ok(result) => {
            let mut state = state.borrow_mut();
            state.notice = result.notice;
            state.error = None;
            state.list.loading = false;
        }
        Err(error) => {
            let mut state = state.borrow_mut();
            state.error = Some(describe_model_error(&error));
            state.list.loading = false;
        }
    }

    reload(service, state);
}

fn select_model(
    service: &ModelManagementService,
    state: &Rc<RefCell<ModelManagementViewModel>>,
    model_id: &str,
) {
    match service.set_default_model(model_id) {
        Ok(model) => {
            let mut state = state.borrow_mut();
            state.notice = Some(format!("Default model set to {}.", model.label));
            state.error = None;
        }
        Err(error) => {
            state.borrow_mut().error = Some(describe_model_error(&error));
        }
    }

    reload(service, state);
}

fn pin_model(
    service: &ModelManagementService,
    state: &Rc<RefCell<ModelManagementViewModel>>,
    model_id: &str,
    pinned: bool,
) {
    match service.set_pinned(model_id, pinned) {
        Ok(_) => {
            let mut state = state.borrow_mut();
            state.notice = Some(if pinned {
                "Model pinned.".to_owned()
            } else {
                "Model unpinned.".to_owned()
            });
            state.error = None;
        }
        Err(error) => {
            state.borrow_mut().error = Some(describe_model_error(&error));
        }
    }

    reload(service, state);
}

fn hide_model(
    service: &ModelManagementService,
    state: &Rc<RefCell<ModelManagementViewModel>>,
    model_id: &str,
    hidden: bool,
) {
    match service.set_hidden(model_id, hidden) {
        Ok(_) => {
            let mut state = state.borrow_mut();
            state.notice = Some(if hidden {
                "Model hidden from the picker.".to_owned()
            } else {
                "Model shown in the picker.".to_owned()
            });
            state.error = None;
        }
        Err(error) => {
            state.borrow_mut().error = Some(describe_model_error(&error));
        }
    }

    reload(service, state);
}

fn open_manual(state: &Rc<RefCell<ModelManagementViewModel>>) {
    let provider_id = state.borrow().provider_id.clone();

    match provider_id {
        Some(provider_id) => {
            state.borrow_mut().manual = Some(ManualModelForm::new(provider_id));
        }
        None => {
            state.borrow_mut().error =
                Some(describe_model_error(&ModelManagementError::InvalidInput(
                    "Add a provider before adding a model.".to_owned(),
                )));
        }
    }
}

fn save_manual(service: &ModelManagementService, state: &Rc<RefCell<ModelManagementViewModel>>) {
    let Some(form) = state.borrow().manual.clone() else {
        return;
    };

    match service.add_manual_model(&form) {
        Ok(model) => {
            let mut state = state.borrow_mut();
            state.manual = None;
            state.notice = Some(format!("Added {}.", model.label));
            state.error = None;
        }
        Err(error) => {
            let copy = describe_model_error(&error);
            if let Some(form) = state.borrow_mut().manual.as_mut() {
                form.error = Some(copy);
            }
        }
    }

    reload(service, state);
}

/// Owns the model management view model and the Slint window that renders it.
pub struct ModelManagementController {
    service: Arc<ModelManagementService>,
    state: Rc<RefCell<ModelManagementViewModel>>,
    window: ModelManagementWindow,
}

impl ModelManagementController {
    /// Create the model management window and load the first provider.
    pub fn new(service: Arc<ModelManagementService>) -> Result<Self, slint::PlatformError> {
        let window = ModelManagementWindow::new()?;
        let mut state = ModelManagementViewModel::default();

        if let Ok(providers) = service.providers() {
            let default_provider_id = service.default_provider_id().ok().flatten();
            let provider = default_provider_id
                .and_then(|id| providers.iter().find(|provider| provider.id == id).cloned())
                .or_else(|| providers.first().cloned());

            if let Some(provider) = provider {
                state.provider_id = Some(provider.id.clone());
                state.provider_label = provider.label.clone();
            }
        }

        let controller = Self {
            service,
            state: Rc::new(RefCell::new(state)),
            window,
        };
        reload(&controller.service, &controller.state);
        controller.wire();
        controller.sync();
        Ok(controller)
    }

    /// The underlying Slint window.
    pub fn window(&self) -> &ModelManagementWindow {
        &self.window
    }

    /// Borrow the current view model.
    pub fn state(&self) -> std::cell::Ref<'_, ModelManagementViewModel> {
        self.state.borrow()
    }

    /// Push the current view model into the window.
    pub fn sync(&self) {
        sync_window(&self.window, &self.state.borrow());
    }

    fn wire(&self) {
        let weak = self.window.as_weak();
        let state = Rc::clone(&self.state);
        let service = Arc::clone(&self.service);
        self.window.on_refresh(move || {
            refresh(&service, &state);
            if let Some(window) = weak.upgrade() {
                sync_window(&window, &state.borrow());
            }
        });

        let weak = self.window.as_weak();
        let state = Rc::clone(&self.state);
        let service = Arc::clone(&self.service);
        self.window.on_retry(move || {
            refresh(&service, &state);
            if let Some(window) = weak.upgrade() {
                sync_window(&window, &state.borrow());
            }
        });

        let weak = self.window.as_weak();
        let state = Rc::clone(&self.state);
        let service = Arc::clone(&self.service);
        self.window.on_search_changed(move |query| {
            state.borrow_mut().query = query.to_string();
            reload(&service, &state);
            if let Some(window) = weak.upgrade() {
                sync_window(&window, &state.borrow());
            }
        });

        let weak = self.window.as_weak();
        let state = Rc::clone(&self.state);
        let service = Arc::clone(&self.service);
        self.window.on_set_default(move |id| {
            select_model(&service, &state, id.as_str());
            if let Some(window) = weak.upgrade() {
                sync_window(&window, &state.borrow());
            }
        });

        let weak = self.window.as_weak();
        let state = Rc::clone(&self.state);
        let service = Arc::clone(&self.service);
        self.window.on_pin_model(move |id, pinned| {
            pin_model(&service, &state, id.as_str(), pinned);
            if let Some(window) = weak.upgrade() {
                sync_window(&window, &state.borrow());
            }
        });

        let weak = self.window.as_weak();
        let state = Rc::clone(&self.state);
        let service = Arc::clone(&self.service);
        self.window.on_hide_model(move |id, hidden| {
            hide_model(&service, &state, id.as_str(), hidden);
            if let Some(window) = weak.upgrade() {
                sync_window(&window, &state.borrow());
            }
        });

        let weak = self.window.as_weak();
        let state = Rc::clone(&self.state);
        self.window.on_open_manual(move || {
            open_manual(&state);
            if let Some(window) = weak.upgrade() {
                sync_window(&window, &state.borrow());
            }
        });

        let weak = self.window.as_weak();
        let state = Rc::clone(&self.state);
        self.window.on_cancel_manual(move || {
            state.borrow_mut().manual = None;
            if let Some(window) = weak.upgrade() {
                sync_window(&window, &state.borrow());
            }
        });

        let weak = self.window.as_weak();
        let state = Rc::clone(&self.state);
        self.window.on_manual_remote_id_changed(move |value| {
            if let Some(form) = state.borrow_mut().manual.as_mut() {
                form.remote_id = value.to_string();
            }
            if let Some(window) = weak.upgrade() {
                sync_window(&window, &state.borrow());
            }
        });

        let weak = self.window.as_weak();
        let state = Rc::clone(&self.state);
        self.window.on_manual_label_changed(move |value| {
            if let Some(form) = state.borrow_mut().manual.as_mut() {
                form.label = value.to_string();
            }
            if let Some(window) = weak.upgrade() {
                sync_window(&window, &state.borrow());
            }
        });

        let weak = self.window.as_weak();
        let state = Rc::clone(&self.state);
        self.window.on_manual_context_changed(move |value| {
            if let Some(form) = state.borrow_mut().manual.as_mut() {
                match form.set_context_window_text(value.as_str()) {
                    Ok(()) => form.error = None,
                    Err(error) => form.error = Some(describe_model_error(&error)),
                }
            }
            if let Some(window) = weak.upgrade() {
                sync_window(&window, &state.borrow());
            }
        });

        let weak = self.window.as_weak();
        let state = Rc::clone(&self.state);
        self.window.on_manual_capabilities_changed(move |value| {
            if let Some(form) = state.borrow_mut().manual.as_mut() {
                match form.set_capabilities_text(value.as_str()) {
                    Ok(()) => form.error = None,
                    Err(error) => form.error = Some(describe_model_error(&error)),
                }
            }
            if let Some(window) = weak.upgrade() {
                sync_window(&window, &state.borrow());
            }
        });

        let weak = self.window.as_weak();
        let state = Rc::clone(&self.state);
        let service = Arc::clone(&self.service);
        self.window.on_save_manual(move || {
            save_manual(&service, &state);
            if let Some(window) = weak.upgrade() {
                sync_window(&window, &state.borrow());
            }
        });

        let weak = self.window.as_weak();
        let state = Rc::clone(&self.state);
        let service = Arc::clone(&self.service);
        self.window.on_toggle_picker_mode(move |value| {
            state.borrow_mut().picker_mode = value;
            reload(&service, &state);
            if let Some(window) = weak.upgrade() {
                sync_window(&window, &state.borrow());
            }
        });
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::BTreeMap;
    use std::sync::Mutex;

    use chatui_domain::{
        BrowserCors, ChatError, ChatErrorCode, ChatFontSize, ChatParams, Density, InputModality,
        Model, ModelCapability, ModelPricing, ModelSource, OutputModality, Provider,
        ProviderAuthType, ProviderKind, SendKey, Settings, Theme, Timestamp,
    };
    use chatui_providers::CatalogModel;
    use slint::Model as _;

    use crate::models::service::{
        ModelDiscovery, ModelRepository, ProviderRepository, SettingsStore,
    };

    #[derive(Default)]
    struct FakeModels {
        models: Mutex<Vec<Model>>,
    }

    impl ModelRepository for FakeModels {
        fn list_all(&self) -> Result<Vec<Model>, ModelManagementError> {
            Ok(self.models.lock().expect("models lock").clone())
        }

        fn list_by_provider(&self, provider_id: &str) -> Result<Vec<Model>, ModelManagementError> {
            Ok(self
                .models
                .lock()
                .expect("models lock")
                .iter()
                .filter(|model| model.provider_id == provider_id)
                .cloned()
                .collect())
        }

        fn get(&self, id: &str) -> Result<Option<Model>, ModelManagementError> {
            Ok(self
                .models
                .lock()
                .expect("models lock")
                .iter()
                .find(|model| model.id == id)
                .cloned())
        }

        fn insert(&self, model: &Model) -> Result<(), ModelManagementError> {
            self.models.lock().expect("models lock").push(model.clone());
            Ok(())
        }

        fn update(&self, model: &Model) -> Result<(), ModelManagementError> {
            let mut models = self.models.lock().expect("models lock");
            if let Some(existing) = models.iter_mut().find(|item| item.id == model.id) {
                *existing = model.clone();
            }
            Ok(())
        }

        fn set_pinned(&self, id: &str, pinned: bool) -> Result<(), ModelManagementError> {
            let mut models = self.models.lock().expect("models lock");
            if let Some(model) = models.iter_mut().find(|model| model.id == id) {
                model.pinned = pinned;
            }
            Ok(())
        }

        fn set_hidden(&self, id: &str, hidden: bool) -> Result<(), ModelManagementError> {
            let mut models = self.models.lock().expect("models lock");
            if let Some(model) = models.iter_mut().find(|model| model.id == id) {
                model.hidden = hidden;
            }
            Ok(())
        }
    }

    #[derive(Default)]
    struct FakeProviders {
        providers: Mutex<Vec<Provider>>,
    }

    impl ProviderRepository for FakeProviders {
        fn list(&self) -> Result<Vec<Provider>, ModelManagementError> {
            Ok(self.providers.lock().expect("providers lock").clone())
        }

        fn get(&self, id: &str) -> Result<Option<Provider>, ModelManagementError> {
            Ok(self
                .providers
                .lock()
                .expect("providers lock")
                .iter()
                .find(|provider| provider.id == id)
                .cloned())
        }
    }

    struct FakeDiscovery {
        result: Mutex<Result<Vec<CatalogModel>, ChatError>>,
    }

    impl ModelDiscovery for FakeDiscovery {
        fn discover(&self, _provider: &Provider) -> Result<Vec<CatalogModel>, ChatError> {
            self.result.lock().expect("discovery lock").clone()
        }
    }

    struct FakeSettings {
        settings: Mutex<Settings>,
    }

    impl SettingsStore for FakeSettings {
        fn load(&self) -> Result<Settings, ModelManagementError> {
            Ok(self.settings.lock().expect("settings lock").clone())
        }

        fn save(&self, settings: &Settings) -> Result<(), ModelManagementError> {
            *self.settings.lock().expect("settings lock") = settings.clone();
            Ok(())
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
            id: "p1".to_owned(),
            preset_id: "openrouter".to_owned(),
            label: "Personal".to_owned(),
            kind: ProviderKind::OpenaiCompatible,
            base_url: "https://openrouter.ai/api/v1".to_owned(),
            auth_type: ProviderAuthType::Bearer,
            secret_id: None,
            headers: BTreeMap::new(),
            enabled: true,
            browser_cors: BrowserCors::Supported,
            metadata: None,
            created_at: now,
            updated_at: now,
        }
    }

    fn model(
        id: &str,
        remote_id: &str,
        label: &str,
        pinned: bool,
        hidden: bool,
        pricing: Option<ModelPricing>,
    ) -> Model {
        let now = Timestamp::now();
        Model {
            id: id.to_owned(),
            provider_id: "p1".to_owned(),
            remote_id: remote_id.to_owned(),
            label: label.to_owned(),
            context_window: Some(128_000),
            max_output_tokens: Some(4096),
            capabilities: vec![ModelCapability::Text, ModelCapability::Streaming],
            pricing,
            canonical_slug: None,
            input_modalities: vec![InputModality::Text],
            output_modalities: vec![OutputModality::Text],
            supported_parameters: Vec::new(),
            metadata: None,
            hidden,
            pinned,
            source: ModelSource::Discovered,
            created_at: now,
            updated_at: now,
        }
    }

    fn chat_error(code: ChatErrorCode, message: &str) -> ChatError {
        ChatError {
            code,
            message: message.to_owned(),
            provider_id: Some("openrouter".to_owned()),
            status: None,
            retryable: false,
            retry_after_seconds: None,
            request_id: None,
            details: None,
            at: Timestamp::now(),
        }
    }

    fn controller(
        discovery: Result<Vec<CatalogModel>, ChatError>,
        models: Vec<Model>,
        settings: Settings,
    ) -> ModelManagementController {
        let model_store = Arc::new(FakeModels {
            models: Mutex::new(models),
        });
        let provider_store = Arc::new(FakeProviders {
            providers: Mutex::new(vec![provider()]),
        });
        let discovery = Arc::new(FakeDiscovery {
            result: Mutex::new(discovery),
        });
        let settings = Arc::new(FakeSettings {
            settings: Mutex::new(settings),
        });
        let service = Arc::new(ModelManagementService::new(
            model_store,
            provider_store,
            discovery,
            settings,
        ));

        ModelManagementController::new(service).expect("model window")
    }

    fn row_labels(window: &ModelManagementWindow) -> Vec<String> {
        let models = window.get_models();
        (0..models.row_count())
            .filter_map(|index| models.row_data(index))
            .map(|row| row.label.to_string())
            .collect()
    }

    #[test]
    fn window_renders_grouped_models_with_badges_and_prices() {
        i_slint_backend_testing::init_no_event_loop();

        let free_pricing = ModelPricing {
            input_per_m_tok: Some(0.0),
            output_per_m_tok: Some(0.0),
            ..ModelPricing::default()
        };
        let paid_pricing = ModelPricing {
            input_per_m_tok: Some(1.5),
            output_per_m_tok: Some(6.0),
            ..ModelPricing::default()
        };
        let controller = controller(
            Ok(Vec::new()),
            vec![
                model(
                    "m1",
                    "author/pinned",
                    "Pinned Model",
                    true,
                    false,
                    Some(paid_pricing),
                ),
                model(
                    "m2",
                    "author/free:free",
                    "Free Model",
                    false,
                    false,
                    Some(free_pricing),
                ),
                model("m3", "author/hidden", "Hidden Model", false, true, None),
            ],
            default_settings(),
        );
        let window = controller.window();

        assert_eq!(window.get_models().row_count(), 3);
        assert_eq!(window.get_provider_label(), "Personal");
        assert_eq!(window.get_docs_url(), "https://openrouter.ai/docs");
        assert!(window.get_summary().contains("3 models"));

        let models = window.get_models();
        let pinned = models.row_data(0).expect("pinned row");
        assert_eq!(pinned.group_label, "Pinned");
        assert!(pinned.group_start);
        assert!(pinned.meta.contains("128K context"));
        assert!(pinned.meta.contains("$1.5 / M input"));
        assert!(pinned.chips.contains("Text"));

        let free = (0..models.row_count())
            .filter_map(|index| models.row_data(index))
            .find(|row| row.label == "Free Model")
            .expect("free row");
        assert!(free.badges.contains("Free"));
        assert!(free.meta.contains("Free"));

        let hidden = (0..models.row_count())
            .filter_map(|index| models.row_data(index))
            .find(|row| row.label == "Hidden Model")
            .expect("hidden row");
        assert!(hidden.badges.contains("Hidden"));
    }

    #[test]
    fn search_filters_the_picker() {
        i_slint_backend_testing::init_no_event_loop();

        let controller = controller(
            Ok(Vec::new()),
            vec![
                model("m1", "alpha/model", "Alpha Model", false, false, None),
                model("m2", "beta/model", "Beta Model", false, false, None),
            ],
            default_settings(),
        );
        let window = controller.window();

        window.invoke_search_changed("alpha".into());
        assert_eq!(row_labels(window), vec!["Alpha Model"]);

        window.invoke_search_changed("missing".into());
        assert_eq!(window.get_models().row_count(), 0);
        assert!(window.get_empty());
    }

    #[test]
    fn pin_and_hide_toggle_persist() {
        i_slint_backend_testing::init_no_event_loop();

        let controller = controller(
            Ok(Vec::new()),
            vec![model("m1", "author/model", "Model", false, false, None)],
            default_settings(),
        );
        let window = controller.window();

        window.invoke_pin_model("m1".into(), true);
        let row = window.get_models().row_data(0).expect("row");
        assert!(row.pinned);
        assert!(controller.state().list.rows()[0].pinned);

        window.invoke_hide_model("m1".into(), true);
        let row = window.get_models().row_data(0).expect("row");
        assert!(row.hidden);
        assert!(controller.state().list.rows()[0].hidden);
    }

    #[test]
    fn refresh_failure_keeps_cached_models_and_shows_notice() {
        i_slint_backend_testing::init_no_event_loop();

        let controller = controller(
            Err(chat_error(ChatErrorCode::Network, "offline")),
            vec![model(
                "m1",
                "author/model",
                "Cached Model",
                false,
                false,
                None,
            )],
            default_settings(),
        );
        let window = controller.window();

        window.invoke_refresh();

        assert_eq!(row_labels(window), vec!["Cached Model"]);
        assert!(window.get_notice().contains("cached"));
        assert!(window.get_error_title().is_empty());
    }

    #[test]
    fn manual_add_opens_and_saves() {
        i_slint_backend_testing::init_no_event_loop();

        let controller = controller(Ok(Vec::new()), Vec::new(), default_settings());
        let window = controller.window();

        window.invoke_open_manual();
        assert!(window.get_manual_visible());

        window.invoke_manual_remote_id_changed("author/manual".into());
        window.invoke_manual_label_changed("Manual Model".into());
        window.invoke_manual_context_changed("32000".into());
        window.invoke_manual_capabilities_changed("text, vision".into());
        window.invoke_save_manual();

        assert!(!window.get_manual_visible());
        assert_eq!(row_labels(window), vec!["Manual Model"]);
        let row = window.get_models().row_data(0).expect("row");
        assert!(row.meta.contains("Manual"));
        assert!(row.chips.contains("Vision"));
        assert_eq!(
            controller.state().list.rows()[0].source,
            ModelSource::Manual
        );
    }

    #[test]
    fn picker_shows_default_and_unavailable_warning() {
        i_slint_backend_testing::init_no_event_loop();

        let mut settings = default_settings();
        settings.default_model_id = Some("missing".to_owned());
        let controller = controller(
            Ok(Vec::new()),
            vec![model("m1", "author/model", "Model", false, false, None)],
            settings,
        );
        let window = controller.window();

        window.invoke_toggle_picker_mode(true);

        assert_eq!(window.get_default_model_id(), "missing");
        assert!(
            window
                .get_availability_warning()
                .contains("no longer available")
        );

        window.invoke_set_default("m1".into());

        assert_eq!(window.get_default_model_id(), "m1");
        assert!(window.get_availability_warning().is_empty());
        assert!(window.get_models().row_data(0).expect("row").is_default);
    }

    #[test]
    fn picker_excludes_hidden_models() {
        i_slint_backend_testing::init_no_event_loop();

        let controller = controller(
            Ok(Vec::new()),
            vec![
                model("m1", "author/visible", "Visible", false, false, None),
                model("m2", "author/hidden", "Hidden", false, true, None),
            ],
            default_settings(),
        );
        let window = controller.window();

        assert_eq!(window.get_models().row_count(), 2);

        window.invoke_toggle_picker_mode(true);

        assert_eq!(row_labels(window), vec!["Visible"]);
    }
}
