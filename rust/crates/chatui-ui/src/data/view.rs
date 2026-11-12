//! Slint data view and controller.
//!
//! The controller binds the framework-independent [`DataViewModel`] to a Slint
//! window. Every destructive action is gated by the view model: clear-all needs
//! the exact typed phrase, replace import needs an explicit checkbox, and
//! per-key removal needs a second click.

use std::cell::RefCell;
use std::rc::Rc;
use std::sync::Arc;

use slint::{ComponentHandle, ModelRc, SharedString, VecModel};

use crate::data::error::DataError;
use crate::data::route::DataSection;
use crate::data::service::DataService;
use crate::data::state::{
    DataOperation, DataViewModel, ExportFormat, ImportMode, KeyRowView, UsageRowView,
};

slint::slint! {
    import { Button, CheckBox, ComboBox, LineEdit, Palette, ScrollView, TextEdit } from "std-widgets.slint";

    export struct DataNavItem {
        id: string,
        label: string,
    }

    export struct UsageRow {
        label: string,
        count: string,
        size: string,
    }

    export struct KeyRow {
        id: string,
        label: string,
        detail: string,
        has-key: bool,
    }

    component DataSectionButton inherits Rectangle {
        in property <string> label;
        in property <bool> active;
        in property <bool> dark: false;
        callback clicked;
        out property <bool> focus-visible: false;

        height: 36px;
        background: root.active ? (root.dark ? #1f232b : #f7f7f8) : transparent;
        border-radius: 6px;

        accessible-role: button;
        accessible-label: root.label;
        accessible-action-default => { root.clicked(); }

        forward-focus: fs;

        fs := FocusScope {
            x: 0;
            width: 0;
            focus-changed-event(reason) => {
                root.focus-visible = reason == FocusReason.tab-navigation;
            }
            key-pressed(event) => {
                if (event.text == Key.Space || event.text == Key.Return) {
                    root.clicked();
                    return accept;
                }
                return reject;
            }
        }

        Text {
            x: 8px;
            text: root.label;
            vertical-alignment: center;
            accessible-role: none;
        }

        TouchArea {
            clicked => { root.clicked(); }
        }

        if (root.focus-visible) : Rectangle {
            border-width: 2px;
            border-color: root.dark ? #60a5fa : #1a56db;
            border-radius: 6px;
            background: transparent;
        }
    }

    export component DataWindow inherits Window {
        title: "ChatUI data";
        width: 1000px;
        height: 760px;

        in property <[DataNavItem]> sections: [];
        in property <int> active-section: 0;
        in property <string> notice: "";
        in property <string> error-title: "";
        in property <string> error-message: "";
        in property <string> error-details: "";
        in property <string> status: "";
        in property <string> busy: "";

        in property <[UsageRow]> usage-rows: [];
        in property <string> usage-total: "";
        in property <string> usage-note: "";

        in property <string> plaintext-warning: "";
        in property <[string]> export-includes: [];
        in property <[string]> export-excludes: [];
        in property <bool> include-attachment-bytes: false;
        in property <[string]> conversations: [];
        in property <int> selected-conversation-index: 0;
        in property <int> export-format-index: 0;
        in property <string> last-export: "";

        in property <int> import-mode-index: 0;
        in property <bool> replace-confirmed: false;
        in-out property <string> import-text: "";
        in property <string> last-import: "";

        in-out property <string> clear-confirmation: "";
        in property <bool> clear-confirmed: false;
        in property <string> last-clear: "";

        in property <[KeyRow]> provider-keys: [];
        in property <string> pending-key-removal: "";

        in property <string> backup-title: "";
        in property <string> backup-message: "";
        in property <string> backup-detail: "";
        in property <string> backup-link-label: "";
        in property <bool> dark: false;

        background: root.dark ? #0f1115 : #ffffff;
        init => {
            Palette.color-scheme = root.dark ? ColorScheme.dark : ColorScheme.light;
        }
        changed dark => {
            Palette.color-scheme = root.dark ? ColorScheme.dark : ColorScheme.light;
        }

        callback select-section(int);
        callback refresh-usage();
        callback export-all();
        callback export-conversation();
        callback conversation-changed(int);
        callback include-attachment-bytes-changed(bool);
        callback export-format-changed(int);
        callback import-mode-changed(int);
        callback replace-confirmed-changed(bool);
        callback import-text-changed(string);
        callback import-archive();
        callback clear-confirmation-changed(string);
        callback clear-all();
        callback remove-key(string);
        callback open-backup();

        HorizontalLayout {
            padding: 16px;
            spacing: 16px;

            VerticalLayout {
                width: 220px;
                spacing: 8px;

                Text {
                    text: "Data";
                    font-size: 22px;
                    font-weight: 600;
                }

                for section[index] in root.sections : DataSectionButton {
                    label: section.label;
                    active: index == root.active-section;
                    dark: root.dark;
                    clicked => { root.select-section(index); }
                }
            }

            ScrollView {
                VerticalLayout {
                    spacing: 12px;

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
                            if (!root.error-details.is-empty) : Text {
                                text: root.error-details;
                                wrap: word-wrap;
                                color: root.dark ? #9ca3af : #5f6368;
                            }
                        }
                    }

                    if (!root.busy.is-empty) : Text {
                        text: root.busy;
                        color: root.dark ? #9ca3af : #5f6368;
                        accessible-live-region: polite;
                    }

                    if (!root.status.is-empty) : Text {
                        text: root.status;
                        color: root.dark ? #9ca3af : #5f6368;
                        accessible-live-region: polite;
                        wrap: word-wrap;
                    }

                    if (root.active-section == 0) : VerticalLayout {
                        spacing: 8px;

                        Text { text: "Storage usage"; font-size: 20px; font-weight: 600; }
                        Text { text: root.usage-note; color: root.dark ? #9ca3af : #5f6368; wrap: word-wrap; }

                        HorizontalLayout {
                            spacing: 8px;
                            Text { text: "Data"; width: 160px; font-weight: 600; }
                            Text { text: "Count"; width: 80px; font-weight: 600; }
                            Text { text: "Size"; font-weight: 600; }
                        }

                        for row in root.usage-rows : HorizontalLayout {
                            spacing: 8px;
                            Text { text: row.label; width: 160px; }
                            Text { text: row.count; width: 80px; }
                            Text { text: row.size; }
                        }

                        HorizontalLayout {
                            spacing: 8px;
                            Text { text: "Total"; width: 160px; font-weight: 600; }
                            Text { text: root.usage-total; font-weight: 600; }
                        }

                        Button {
                            text: "Refresh usage";
                            enabled: root.busy.is-empty;
                            clicked => { root.refresh-usage(); }
                        }
                    }

                    if (root.active-section == 1) : VerticalLayout {
                        spacing: 8px;

                        Text { text: "Export"; font-size: 20px; font-weight: 600; }
                        Text {
                            text: root.plaintext-warning;
                            color: root.dark ? #fbbf24 : #b45309;
                            wrap: word-wrap;
                        }

                        Text { text: "Includes"; font-weight: 600; }
                        for line in root.export-includes : Text {
                            text: "• " + line;
                            wrap: word-wrap;
                        }

                        Text { text: "Excludes"; font-weight: 600; }
                        for line in root.export-excludes : Text {
                            text: "• " + line;
                            wrap: word-wrap;
                        }

                        CheckBox {
                            text: "Include attachment file contents (base64) in JSON exports";
                            checked: root.include-attachment-bytes;
                            toggled => { root.include-attachment-bytes-changed(self.checked); }
                        }

                        Button {
                            text: "Export all data (JSON)";
                            enabled: root.busy.is-empty;
                            clicked => { root.export-all(); }
                        }

                        Text { text: "Conversation export"; font-weight: 600; }
                        if (root.conversations.length == 0) : Text {
                            text: "No conversations are stored yet.";
                            color: root.dark ? #9ca3af : #5f6368;
                        }
                        if (root.conversations.length > 0) : VerticalLayout {
                            spacing: 8px;

                            ComboBox {
                                model: root.conversations;
                                accessible-label: "Conversation";
                                current-index: root.selected-conversation-index;
                                selected(value) => { root.conversation-changed(self.current-index); }
                            }

                            Text { text: "Format"; }
                            ComboBox {
                                model: ["JSON", "Markdown"];
                                accessible-label: "Export format";
                                current-index: root.export-format-index;
                                selected(value) => { root.export-format-changed(self.current-index); }
                            }

                            Button {
                                text: "Export conversation";
                                enabled: root.busy.is-empty;
                                clicked => { root.export-conversation(); }
                            }
                        }

                        if (!root.last-export.is-empty) : Text {
                            text: root.last-export;
                            color: root.dark ? #9ca3af : #5f6368;
                            wrap: word-wrap;
                        }
                    }

                    if (root.active-section == 2) : VerticalLayout {
                        spacing: 8px;

                        Text { text: "Import"; font-size: 20px; font-weight: 600; }
                        Text {
                            text: "Only import chatui.export archives you trust. Imported content is never executed, and invalid archives leave local data unchanged.";
                            color: root.dark ? #9ca3af : #5f6368;
                            wrap: word-wrap;
                        }

                        Text { text: "Import mode"; }
                        ComboBox {
                            model: ["Merge (default)", "Replace"];
                            accessible-label: "Import mode";
                            current-index: root.import-mode-index;
                            selected(value) => { root.import-mode-changed(self.current-index); }
                        }

                        if (root.import-mode-index == 1) : VerticalLayout {
                            spacing: 8px;

                            Text {
                                text: "Replace deletes local conversations, messages, attachments, providers, models, and settings after you confirm.";
                                color: root.dark ? #fbbf24 : #b45309;
                                wrap: word-wrap;
                            }

                            CheckBox {
                                text: "I understand replace deletes local data";
                                checked: root.replace-confirmed;
                                toggled => { root.replace-confirmed-changed(self.checked); }
                            }
                        }

                        Text { text: "Archive JSON"; }
                        TextEdit {
                            text <=> root.import-text;
                            accessible-label: "Archive JSON";
                            edited(text) => { root.import-text-changed(text); }
                            height: 140px;
                            wrap: word-wrap;
                        }

                        Button {
                            text: "Import archive";
                            enabled: root.busy.is-empty;
                            clicked => { root.import-archive(); }
                        }

                        if (!root.last-import.is-empty) : Text {
                            text: root.last-import;
                            color: root.dark ? #9ca3af : #5f6368;
                            wrap: word-wrap;
                        }
                    }

                    if (root.active-section == 3) : VerticalLayout {
                        spacing: 8px;

                        Text { text: "Clear all data"; font-size: 20px; font-weight: 600; }
                        Text {
                            text: "This permanently deletes conversations, messages, attachments, providers, models, and stored API keys from this device. Appearance and chat preferences are kept. This cannot be undone.";
                            color: root.dark ? #fbbf24 : #b45309;
                            wrap: word-wrap;
                        }

                        Text { text: "Type DELETE ALL DATA to confirm"; }
                        LineEdit {
                            text: root.clear-confirmation;
                            accessible-label: "Type DELETE ALL DATA to confirm";
                            edited(text) => { root.clear-confirmation-changed(text); }
                        }

                        Button {
                            text: "Clear all data";
                            enabled: root.clear-confirmed && root.busy.is-empty;
                            clicked => { root.clear-all(); }
                        }

                        if (!root.last-clear.is-empty) : Text {
                            text: root.last-clear;
                            color: root.dark ? #9ca3af : #5f6368;
                            wrap: word-wrap;
                        }
                    }

                    if (root.active-section == 4) : VerticalLayout {
                        spacing: 8px;

                        Text { text: "Stored API keys"; font-size: 20px; font-weight: 600; }
                        Text {
                            text: "Keys are stored separately from conversations and are never included in exports. Removing a key clears its cached metadata and disables sending until a new key is added.";
                            color: root.dark ? #9ca3af : #5f6368;
                            wrap: word-wrap;
                        }

                        if (root.provider-keys.length == 0) : Text {
                            text: "No providers are configured.";
                            color: root.dark ? #9ca3af : #5f6368;
                        }

                        for key in root.provider-keys : HorizontalLayout {
                            spacing: 8px;

                            VerticalLayout {
                                Text { text: key.label; font-weight: 600; }
                                Text { text: key.detail; color: root.dark ? #9ca3af : #5f6368; }
                            }

                            Button {
                                text: key.has-key ? (root.pending-key-removal == key.id ? "Confirm removal" : "Remove key") : "No key";
                                enabled: key.has-key && root.busy.is-empty;
                                clicked => { root.remove-key(key.id); }
                            }
                        }
                    }

                    if (root.active-section == 5) : VerticalLayout {
                        spacing: 8px;

                        Text { text: root.backup-title; font-size: 20px; font-weight: 600; }
                        Text { text: root.backup-message; wrap: word-wrap; }
                        Text {
                            text: root.backup-detail;
                            color: root.dark ? #9ca3af : #5f6368;
                            wrap: word-wrap;
                        }
                        Button {
                            text: root.backup-link-label == "" ? "Open backup" : root.backup-link-label;
                            clicked => { root.open-backup(); }
                        }
                    }
                }
            }
        }
    }
}

/// Push the current view model into a Slint window.
pub fn sync_window(window: &DataWindow, state: &DataViewModel) {
    let sections: Vec<DataNavItem> = DataSection::ALL
        .iter()
        .map(|section| DataNavItem {
            id: SharedString::from(section.id()),
            label: SharedString::from(section.label()),
        })
        .collect();

    window.set_sections(ModelRc::new(VecModel::from(sections)));
    window.set_active_section(state.section.index() as i32);
    window.set_notice(String::new().into());
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
    window.set_status(state.status.clone().unwrap_or_default().into());
    window.set_busy(
        state
            .busy
            .map(|operation| operation.label())
            .unwrap_or_default()
            .into(),
    );

    window.set_usage_rows(ModelRc::new(VecModel::from(
        state
            .usage_rows()
            .into_iter()
            .map(usage_row)
            .collect::<Vec<_>>(),
    )));
    window.set_usage_total(state.usage_total().into());
    window.set_usage_note(crate::data::state::USAGE_NOTE.into());

    let documentation = state.export_documentation();
    window.set_plaintext_warning(documentation.plaintext_warning.into());
    window.set_export_includes(ModelRc::new(VecModel::from(
        documentation
            .includes
            .into_iter()
            .map(SharedString::from)
            .collect::<Vec<_>>(),
    )));
    window.set_export_excludes(ModelRc::new(VecModel::from(
        documentation
            .excludes
            .into_iter()
            .map(SharedString::from)
            .collect::<Vec<_>>(),
    )));
    window.set_include_attachment_bytes(state.include_attachment_bytes);
    window.set_conversations(ModelRc::new(VecModel::from(
        state
            .conversation_display()
            .into_iter()
            .map(SharedString::from)
            .collect::<Vec<_>>(),
    )));
    window.set_selected_conversation_index(state.selected_conversation_index() as i32);
    window.set_export_format_index(state.export_format.index() as i32);
    window.set_last_export(state.last_export_text().into());

    window.set_import_mode_index(state.import_mode.index() as i32);
    window.set_replace_confirmed(state.replace_confirmed);
    window.set_import_text(state.import_text.clone().into());
    window.set_last_import(state.last_import_text().into());

    window.set_clear_confirmation(state.clear_confirmation.clone().into());
    window.set_clear_confirmed(state.clear_confirmed());
    window.set_last_clear(state.last_clear_text().into());

    window.set_provider_keys(ModelRc::new(VecModel::from(
        state
            .key_rows()
            .into_iter()
            .map(key_row)
            .collect::<Vec<_>>(),
    )));
    window.set_pending_key_removal(state.pending_key_removal.clone().unwrap_or_default().into());

    let backup = state.backup_placeholder();
    window.set_backup_title(backup.title.into());
    window.set_backup_message(backup.message.into());
    window.set_backup_detail(backup.detail.into());
    window.set_backup_link_label(backup.link_label.into());
}

fn usage_row(row: UsageRowView) -> UsageRow {
    UsageRow {
        label: row.label.into(),
        count: row.count.into(),
        size: row.size.into(),
    }
}

fn key_row(row: KeyRowView) -> KeyRow {
    KeyRow {
        id: row.id.into(),
        label: row.label.into(),
        detail: row.detail.into(),
        has_key: row.has_key,
    }
}

fn apply_result(
    result: Result<(), DataError>,
    state: &Rc<RefCell<DataViewModel>>,
    weak: &slint::Weak<DataWindow>,
) {
    match result {
        Ok(()) => {}
        Err(error) => state.borrow_mut().set_error(&error),
    }

    if let Some(window) = weak.upgrade() {
        sync_window(&window, &state.borrow());
    }
}

/// Handlers for the backup entry point.
#[derive(Default)]
struct DataHandlers {
    open_backup: Option<Rc<dyn Fn()>>,
}

/// Owns the data view model and the Slint window that renders it.
pub struct DataController {
    service: Arc<dyn DataService>,
    state: Rc<RefCell<DataViewModel>>,
    window: DataWindow,
    handlers: Rc<RefCell<DataHandlers>>,
}

impl DataController {
    /// Create the data window and load usage, conversations, and keys.
    pub fn new(service: Arc<dyn DataService>) -> Result<Self, slint::PlatformError> {
        let window = DataWindow::new()?;
        let state = Rc::new(RefCell::new(DataViewModel::new()));
        let controller = Self {
            service,
            state,
            window,
            handlers: Rc::new(RefCell::new(DataHandlers::default())),
        };
        controller.reload();
        controller.wire();
        controller.sync();
        Ok(controller)
    }

    /// The underlying Slint window.
    pub fn window(&self) -> &DataWindow {
        &self.window
    }

    /// Borrow the current view model.
    pub fn state(&self) -> std::cell::Ref<'_, DataViewModel> {
        self.state.borrow()
    }

    /// Push the current view model into the window.
    pub fn sync(&self) {
        sync_window(&self.window, &self.state.borrow());
    }

    /// Reload usage, conversations, and stored keys.
    pub fn reload(&self) {
        let mut state = self.state.borrow_mut();
        state.error = None;

        match self.service.usage() {
            Ok(usage) => state.set_usage(usage),
            Err(error) => state.set_error(&error),
        }

        match self.service.list_conversations() {
            Ok(conversations) => state.set_conversations(conversations),
            Err(error) => state.set_error(&error),
        }

        match self.service.list_provider_keys() {
            Ok(keys) => state.set_provider_keys(keys),
            Err(error) => state.set_error(&error),
        }
    }

    /// Select a data section and refresh the window.
    pub fn select_section(&self, section: DataSection) {
        self.state.borrow_mut().section.select(section);
        self.sync();
    }

    /// Register the backup entry-point handler.
    pub fn set_on_open_backup(&mut self, handler: impl Fn() + 'static) {
        self.handlers.borrow_mut().open_backup = Some(Rc::new(handler));
    }

    fn wire(&self) {
        let weak = self.window.as_weak();
        let state = Rc::clone(&self.state);
        self.window.on_select_section(move |index| {
            if let Some(section) = DataSection::from_index(index as usize) {
                state.borrow_mut().section.select(section);
            }
            if let Some(window) = weak.upgrade() {
                sync_window(&window, &state.borrow());
            }
        });

        let weak = self.window.as_weak();
        let state = Rc::clone(&self.state);
        let service = Arc::clone(&self.service);
        self.window.on_refresh_usage(move || {
            state.borrow_mut().set_busy(DataOperation::RefreshUsage);
            let result = service.usage().map(|usage| {
                state.borrow_mut().set_usage(usage);
                state.borrow_mut().clear_busy();
            });
            apply_result(result, &state, &weak);
        });

        let weak = self.window.as_weak();
        let state = Rc::clone(&self.state);
        let service = Arc::clone(&self.service);
        self.window.on_export_all(move || {
            let request = state.borrow().export_all_request();
            state.borrow_mut().set_busy(DataOperation::ExportAll);
            let result = service.export_all(&request).map(|artifact| {
                state.borrow_mut().apply_export(&artifact);
            });
            apply_result(result, &state, &weak);
        });

        let weak = self.window.as_weak();
        let state = Rc::clone(&self.state);
        let service = Arc::clone(&self.service);
        self.window.on_export_conversation(move || {
            let request = state.borrow().export_conversation_request();
            let Some(request) = request else {
                state.borrow_mut().set_error(&DataError::InvalidInput(
                    "Choose a conversation before exporting.".to_owned(),
                ));
                if let Some(window) = weak.upgrade() {
                    sync_window(&window, &state.borrow());
                }
                return;
            };
            let conversation_id = request.conversation_id.clone().unwrap_or_default();
            state
                .borrow_mut()
                .set_busy(DataOperation::ExportConversation);
            let result = service
                .export_conversation(&conversation_id, &request)
                .map(|artifact| {
                    state.borrow_mut().apply_export(&artifact);
                });
            apply_result(result, &state, &weak);
        });

        let weak = self.window.as_weak();
        let state = Rc::clone(&self.state);
        self.window.on_conversation_changed(move |index| {
            let id = state
                .borrow()
                .conversations
                .get(index as usize)
                .map(|conversation| conversation.id.clone());
            if let Some(id) = id {
                state.borrow_mut().select_conversation(id);
            }
            if let Some(window) = weak.upgrade() {
                sync_window(&window, &state.borrow());
            }
        });

        let weak = self.window.as_weak();
        let state = Rc::clone(&self.state);
        self.window
            .on_include_attachment_bytes_changed(move |include| {
                state.borrow_mut().set_include_attachment_bytes(include);
                if let Some(window) = weak.upgrade() {
                    sync_window(&window, &state.borrow());
                }
            });

        let weak = self.window.as_weak();
        let state = Rc::clone(&self.state);
        self.window.on_export_format_changed(move |index| {
            state
                .borrow_mut()
                .set_export_format(ExportFormat::from_index(index as usize));
            if let Some(window) = weak.upgrade() {
                sync_window(&window, &state.borrow());
            }
        });

        let weak = self.window.as_weak();
        let state = Rc::clone(&self.state);
        self.window.on_import_mode_changed(move |index| {
            state
                .borrow_mut()
                .set_import_mode(ImportMode::from_index(index as usize));
            if let Some(window) = weak.upgrade() {
                sync_window(&window, &state.borrow());
            }
        });

        let weak = self.window.as_weak();
        let state = Rc::clone(&self.state);
        self.window.on_replace_confirmed_changed(move |confirmed| {
            state.borrow_mut().set_replace_confirmed(confirmed);
            if let Some(window) = weak.upgrade() {
                sync_window(&window, &state.borrow());
            }
        });

        let weak = self.window.as_weak();
        let state = Rc::clone(&self.state);
        self.window.on_import_text_changed(move |text| {
            state.borrow_mut().set_import_text(text.as_str());
            if let Some(window) = weak.upgrade() {
                sync_window(&window, &state.borrow());
            }
        });

        let weak = self.window.as_weak();
        let state = Rc::clone(&self.state);
        let service = Arc::clone(&self.service);
        self.window.on_import_archive(move || {
            let request = state.borrow().import_request();
            let request = match request {
                Ok(request) => request,
                Err(error) => {
                    state.borrow_mut().set_error(&error);
                    if let Some(window) = weak.upgrade() {
                        sync_window(&window, &state.borrow());
                    }
                    return;
                }
            };

            state.borrow_mut().set_busy(DataOperation::Import);
            let result = service
                .import_text(&request.text, request.mode)
                .map(|summary| {
                    state.borrow_mut().apply_import(summary);
                });

            match result {
                Ok(()) => {
                    reload_after_change(&service, &state);
                    if let Some(window) = weak.upgrade() {
                        sync_window(&window, &state.borrow());
                    }
                }
                Err(error) => {
                    state.borrow_mut().set_error(&error);
                    if let Some(window) = weak.upgrade() {
                        sync_window(&window, &state.borrow());
                    }
                }
            }
        });

        let weak = self.window.as_weak();
        let state = Rc::clone(&self.state);
        self.window.on_clear_confirmation_changed(move |text| {
            state.borrow_mut().set_clear_confirmation(text.as_str());
            if let Some(window) = weak.upgrade() {
                sync_window(&window, &state.borrow());
            }
        });

        let weak = self.window.as_weak();
        let state = Rc::clone(&self.state);
        let service = Arc::clone(&self.service);
        self.window.on_clear_all(move || {
            let request = state.borrow().clear_request();
            if let Err(error) = request {
                state.borrow_mut().set_error(&error);
                if let Some(window) = weak.upgrade() {
                    sync_window(&window, &state.borrow());
                }
                return;
            }

            state.borrow_mut().set_busy(DataOperation::ClearAll);
            let result = service.clear_all().map(|summary| {
                state.borrow_mut().apply_clear(summary);
            });

            match result {
                Ok(()) => {
                    reload_after_change(&service, &state);
                    if let Some(window) = weak.upgrade() {
                        sync_window(&window, &state.borrow());
                    }
                }
                Err(error) => {
                    state.borrow_mut().set_error(&error);
                    if let Some(window) = weak.upgrade() {
                        sync_window(&window, &state.borrow());
                    }
                }
            }
        });

        let weak = self.window.as_weak();
        let state = Rc::clone(&self.state);
        let service = Arc::clone(&self.service);
        self.window.on_remove_key(move |provider_id| {
            let provider_id = provider_id.to_string();
            let pending = state.borrow().pending_key_removal.clone();

            if pending.as_deref() != Some(provider_id.as_str()) {
                state.borrow_mut().pending_key_removal = Some(provider_id);
                state.borrow_mut().status =
                    Some("Click Confirm removal to remove this key.".to_owned());
                if let Some(window) = weak.upgrade() {
                    sync_window(&window, &state.borrow());
                }
                return;
            }

            let request = state.borrow().remove_key_request(&provider_id);
            if let Err(error) = request {
                state.borrow_mut().set_error(&error);
                if let Some(window) = weak.upgrade() {
                    sync_window(&window, &state.borrow());
                }
                return;
            }

            state.borrow_mut().set_busy(DataOperation::RemoveKey);
            let result = service.remove_provider_key(&provider_id).map(|()| {
                state.borrow_mut().apply_remove_key(&provider_id);
            });

            match result {
                Ok(()) => {
                    reload_after_change(&service, &state);
                    if let Some(window) = weak.upgrade() {
                        sync_window(&window, &state.borrow());
                    }
                }
                Err(error) => {
                    state.borrow_mut().set_error(&error);
                    if let Some(window) = weak.upgrade() {
                        sync_window(&window, &state.borrow());
                    }
                }
            }
        });

        let weak = self.window.as_weak();
        let state = Rc::clone(&self.state);
        let handlers = Rc::clone(&self.handlers);
        self.window.on_open_backup(move || {
            if let Some(handler) = handlers.borrow().open_backup.clone() {
                handler();
                return;
            }
            state.borrow_mut().section.select(DataSection::Backup);
            if let Some(window) = weak.upgrade() {
                sync_window(&window, &state.borrow());
            }
        });
    }
}

fn reload_after_change(service: &Arc<dyn DataService>, state: &Rc<RefCell<DataViewModel>>) {
    let mut state = state.borrow_mut();

    if let Ok(usage) = service.usage() {
        state.set_usage(usage);
    }

    if let Ok(conversations) = service.list_conversations() {
        state.set_conversations(conversations);
    }

    if let Ok(keys) = service.list_provider_keys() {
        state.set_provider_keys(keys);
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use chatui_domain::{
        BrowserCors, ChatParams, Model, ModelCapability, ModelSource, Provider, ProviderAuthType,
        ProviderKind, Timestamp,
    };
    use slint::Model as _;

    use crate::data::service::MemoryDataService;

    fn provider(id: &str, label: &str) -> Provider {
        let now = Timestamp::now();
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
            created_at: now,
            updated_at: now,
        }
    }

    fn model(id: &str, provider_id: &str) -> Model {
        let now = Timestamp::now();
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
            created_at: now,
            updated_at: now,
        }
    }

    fn conversation(id: &str, provider_id: &str, model_id: &str) -> chatui_domain::Conversation {
        let now = Timestamp::now();
        chatui_domain::Conversation {
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
            created_at: now,
            updated_at: now,
            last_message_at: now,
            message_count: 0,
            last_error: None,
            sync: None,
            storage_key: String::new(),
            revision: 0,
        }
    }

    fn service() -> Arc<MemoryDataService> {
        let service = MemoryDataService::new();
        let mut provider = provider("provider-1", "One");
        provider.secret_id = Some("provider-provider-1".to_owned());
        service.add_provider(provider);
        service.add_model(model("model-1", "provider-1"));
        service.add_conversation(conversation("conversation-1", "provider-1", "model-1"));
        service.add_secret("provider-provider-1", b"secret".to_vec());
        Arc::new(service)
    }

    fn controller() -> DataController {
        i_slint_backend_testing::init_no_event_loop();
        DataController::new(service()).expect("data window")
    }

    #[test]
    fn window_renders_usage_export_import_clear_and_keys() {
        let controller = controller();
        let window = controller.window();

        assert_eq!(window.get_sections().row_count(), 6);
        assert_eq!(window.get_active_section(), 0);
        assert_eq!(window.get_usage_rows().row_count(), 6);
        assert!(!window.get_usage_total().is_empty());
        assert!(!window.get_plaintext_warning().is_empty());
        assert_eq!(window.get_export_includes().row_count(), 5);
        assert_eq!(window.get_export_excludes().row_count(), 3);
        assert!(!window.get_include_attachment_bytes());
        assert_eq!(window.get_conversations().row_count(), 1);
        assert_eq!(window.get_import_mode_index(), 0);
        assert!(!window.get_replace_confirmed());
        assert!(!window.get_clear_confirmed());
        assert_eq!(window.get_provider_keys().row_count(), 1);
        assert!(window.get_provider_keys().row_data(0).expect("key").has_key);
    }

    #[test]
    fn export_callbacks_update_the_window() {
        let controller = controller();
        let window = controller.window();

        window.invoke_include_attachment_bytes_changed(true);
        assert!(controller.state().include_attachment_bytes);
        assert!(window.get_include_attachment_bytes());

        window.invoke_export_format_changed(1);
        assert_eq!(controller.state().export_format, ExportFormat::Markdown);
        assert_eq!(window.get_export_format_index(), 1);

        window.invoke_export_all();
        assert!(controller.state().last_export.is_some());
        assert!(!window.get_last_export().is_empty());

        window.invoke_export_conversation();
        assert!(controller.state().last_export.is_some());
    }

    #[test]
    fn import_callback_requires_replace_confirmation() {
        let controller = controller();
        let window = controller.window();

        window.invoke_import_mode_changed(1);
        window.invoke_import_text_changed("{}".into());
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
        assert!(controller.state().error.is_some());
    }

    #[test]
    fn clear_callback_requires_the_typed_phrase() {
        let controller = controller();
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

        window.invoke_clear_confirmation_changed("DELETE ALL DATA".into());
        assert!(window.get_clear_confirmed());
        window.invoke_clear_all();
        assert!(controller.state().last_clear.is_some());
        assert_eq!(window.get_usage_rows().row_data(0).expect("row").count, "0");
    }

    #[test]
    fn per_key_removal_needs_a_second_click() {
        let controller = controller();
        let window = controller.window();

        window.invoke_remove_key("provider-1".into());
        assert_eq!(
            controller.state().pending_key_removal.as_deref(),
            Some("provider-1")
        );
        assert!(controller.state().provider_keys[0].has_key);

        window.invoke_remove_key("provider-1".into());
        assert!(!controller.state().provider_keys[0].has_key);
        assert!(controller.state().pending_key_removal.is_none());
    }

    #[test]
    fn section_and_backup_callbacks_navigate() {
        let controller = controller();
        let window = controller.window();

        window.invoke_select_section(3);
        assert_eq!(controller.state().section(), DataSection::Clear);
        assert_eq!(window.get_active_section(), 3);

        window.invoke_open_backup();
        assert_eq!(controller.state().section(), DataSection::Backup);
        assert_eq!(window.get_active_section(), 5);
    }

    #[test]
    fn backup_handler_is_invoked() {
        let mut controller = controller();
        let calls = Rc::new(RefCell::new(Vec::new()));
        let recorded = Rc::clone(&calls);
        controller.set_on_open_backup(move || recorded.borrow_mut().push("backup"));

        controller.window().invoke_open_backup();
        assert_eq!(calls.borrow().as_slice(), &["backup"]);
    }
}
