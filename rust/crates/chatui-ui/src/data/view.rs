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
use crate::data::state::ExportArtifact;
use crate::data::state::{
    DataOperation, DataViewModel, ExportFormat, ImportMode, KeyRowView, UsageRowView,
};
use crate::tasks::{TaskError, UiTasks};

slint::slint! {
    import { AppButton as Button, AppText as Text, AppSectionButton, Design } from "../design.slint";
    import { CheckBox, ComboBox, LineEdit, Palette, ScrollView, TextEdit } from "std-widgets.slint";

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

    component DataSectionButton inherits AppSectionButton {
        in property <string> label;
        text: root.label;
    }

    export component DataWindow inherits Window {
        title: "ChatUI data";
        preferred-width: 1000px;
        preferred-height: 760px;
        min-width: 0px;

        in property <[DataNavItem]> sections: [];
        in property <[string]> section-labels: ["Storage usage", "Export", "Import", "Clear all data", "Stored API keys", "Backup"];
        in property <int> active-section: 0;
        in property <string> notice: "";
        in property <string> error-title: "";
        in property <string> error-message: "";
        in property <string> error-details: "";
        in property <string> status: "";
        in property <string> busy: "";
        in property <string> external-busy-message: "";

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

        background: Design.colors(root.dark).canvas;
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
        callback choose-import-file();
        callback clear-confirmation-changed(string);
        callback clear-all();
        callback remove-key(string);
        callback open-backup();

        Rectangle {
          x: 0px;
          y: 0px;
          width: 100%;
          height: 100%;
          HorizontalLayout {
            padding: root.width < 680px ? 4px : 12px;
            spacing: 20px;

            if (root.width >= 680px) : VerticalLayout {
                width: 184px;
                spacing: 8px;
                alignment: start;

                Text {
                    text: "YOUR DATA";
                    color: Design.colors(root.dark).muted;
                    font-size: 11px;
                    font-weight: 600;
                }

                Text {
                    text: "Keep a copy. Stay in control.";
                    color: Design.colors(root.dark).muted;
                    font-size: 12px;
                    wrap: word-wrap;
                }

                for section[index] in root.sections : DataSectionButton {
                    label: section.label;
                    active: index == root.active-section;
                    dark: root.dark;
                    clicked => { root.select-section(index); }
                }
            }

            Rectangle {
                background: Design.colors(root.dark).raised;
                border-radius: 12px;
                border-width: 1px;
                border-color: Design.colors(root.dark).border;
                ScrollView {
                    x: 20px;
                    y: 20px;
                    width: parent.width - 40px;
                    height: parent.height - 40px;
                    content-width: self.width;
                VerticalLayout {
                    spacing: 16px;
                    alignment: start;

                    if (root.width < 680px) : ComboBox {
                        model: root.section-labels;
                        current-index: root.active-section;
                        accessible-label: "Data section";
                        selected(value) => { root.select-section(self.current-index); }
                    }

                    if (!root.notice.is-empty) : Text {
                        text: root.notice;
                        accessible-live-region: polite;
                        wrap: word-wrap;
                    }

                    if (!root.error-title.is-empty) : Rectangle {
                        background: Design.colors(root.dark).danger-soft;
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
                                color: Design.colors(root.dark).muted;
                            }
                        }
                    }

                    if (!root.busy.is-empty) : Text {
                        text: root.busy;
                        color: Design.colors(root.dark).muted;
                        accessible-live-region: polite;
                    }
                    if (!root.external-busy-message.is-empty) : Text {
                        text: root.external-busy-message;
                        color: Design.colors(root.dark).warning;
                        wrap: word-wrap;
                        accessible-live-region: polite;
                    }

                    if (!root.status.is-empty) : Text {
                        text: root.status;
                        color: Design.colors(root.dark).muted;
                        accessible-live-region: polite;
                        wrap: word-wrap;
                    }

                    if (root.active-section == 0) : VerticalLayout {
                        spacing: 8px;

                        Text { text: "Storage usage"; font-size: 20px; font-weight: 600; }
                        Text { text: root.usage-note; color: Design.colors(root.dark).muted; wrap: word-wrap; }

                        HorizontalLayout {
                            spacing: 8px;
                            Text { text: "Data"; width: root.width < 680px ? 116px : 160px; font-weight: 600; }
                            Text { text: "Count"; width: 56px; font-weight: 600; }
                            Text { text: "Size"; font-weight: 600; }
                        }

                        for row in root.usage-rows : HorizontalLayout {
                            spacing: 8px;
                            Text { text: row.label; width: root.width < 680px ? 116px : 160px; overflow: elide; }
                            Text { text: row.count; width: 56px; }
                            Text { text: row.size; }
                        }

                        HorizontalLayout {
                            spacing: 8px;
                            Text { text: "Total"; width: root.width < 680px ? 116px : 160px; font-weight: 600; }
                            Text { text: root.usage-total; font-weight: 600; }
                        }

                        Button {
                            text: "Refresh usage";
                            enabled: root.busy.is-empty && root.external-busy-message.is-empty;
                            clicked => { root.refresh-usage(); }
                        }
                    }

                    if (root.active-section == 1) : VerticalLayout {
                        spacing: 8px;

                        Text { text: "Export"; font-size: 20px; font-weight: 600; }
                        Text {
                            text: root.plaintext-warning;
                            color: Design.colors(root.dark).warning;
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
                            primary: true;
                            enabled: root.busy.is-empty && root.external-busy-message.is-empty;
                            clicked => { root.export-all(); }
                        }

                        Text { text: "Conversation export"; font-weight: 600; }
                        if (root.conversations.length == 0) : Text {
                            text: "No conversations are stored yet.";
                            color: Design.colors(root.dark).muted;
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
                                enabled: root.busy.is-empty && root.external-busy-message.is-empty;
                                clicked => { root.export-conversation(); }
                            }
                        }

                        if (!root.last-export.is-empty) : Text {
                            text: root.last-export;
                            color: Design.colors(root.dark).muted;
                            wrap: word-wrap;
                        }
                    }

                    if (root.active-section == 2) : VerticalLayout {
                        spacing: 8px;

                        Text { text: "Import"; font-size: 20px; font-weight: 600; }
                        Text {
                            text: "Only import chatui.export archives you trust. Imported content is never executed, and invalid archives leave local data unchanged.";
                            color: Design.colors(root.dark).muted;
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
                                color: Design.colors(root.dark).warning;
                                wrap: word-wrap;
                            }

                            CheckBox {
                                text: "I understand replace deletes local data";
                                checked: root.replace-confirmed;
                                toggled => { root.replace-confirmed-changed(self.checked); }
                            }
                        }

                        Button {
                            text: "Choose JSON file";
                            enabled: root.busy.is-empty && root.external-busy-message.is-empty;
                            clicked => { root.choose-import-file(); }
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
                            enabled: root.busy.is-empty && root.external-busy-message.is-empty;
                            clicked => { root.import-archive(); }
                        }

                        if (!root.last-import.is-empty) : Text {
                            text: root.last-import;
                            color: Design.colors(root.dark).muted;
                            wrap: word-wrap;
                        }
                    }

                    if (root.active-section == 3) : VerticalLayout {
                        spacing: 8px;

                        Text { text: "Clear all data"; font-size: 20px; font-weight: 600; }
                        Text {
                            text: "This permanently deletes conversations, messages, attachments, providers, models, and stored API keys from this device. Appearance and chat preferences are kept. This cannot be undone.";
                            color: Design.colors(root.dark).warning;
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
                            danger: true;
                            enabled: root.clear-confirmed && root.busy.is-empty && root.external-busy-message.is-empty;
                            clicked => { root.clear-all(); }
                        }

                        if (!root.last-clear.is-empty) : Text {
                            text: root.last-clear;
                            color: Design.colors(root.dark).muted;
                            wrap: word-wrap;
                        }
                    }

                    if (root.active-section == 4) : VerticalLayout {
                        spacing: 8px;

                        Text { text: "Stored API keys"; font-size: 20px; font-weight: 600; }
                        Text {
                            text: "Keys are stored separately from conversations and are never included in exports. Removing a key clears its cached metadata and disables sending until a new key is added.";
                            color: Design.colors(root.dark).muted;
                            wrap: word-wrap;
                        }

                        if (root.provider-keys.length == 0) : Text {
                            text: "No providers are configured.";
                            color: Design.colors(root.dark).muted;
                        }

                        for key in root.provider-keys : HorizontalLayout {
                            spacing: 8px;

                            VerticalLayout {
                                Text { text: key.label; font-weight: 600; }
                                Text { text: key.detail; color: Design.colors(root.dark).muted; }
                            }

                            Button {
                                text: key.has-key ? (root.pending-key-removal == key.id ? "Confirm removal" : "Remove key") : "No key";
                                enabled: key.has-key && root.busy.is-empty && root.external-busy-message.is-empty;
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
                            color: Design.colors(root.dark).muted;
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
    }
}

/// Push the current view model into a Slint window.
pub fn sync_window(window: &DataWindow, state: &DataViewModel) {
    window.set_section_labels(ModelRc::new(VecModel::from(
        DataSection::ALL
            .iter()
            .map(|section| SharedString::from(section.label()))
            .collect::<Vec<_>>(),
    )));
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

/// A completed data change that other feature controllers should reflect.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum DataChange {
    /// An archive was imported.
    Imported {
        /// Whether existing local records were replaced.
        replaced: bool,
    },
    /// Local conversations, providers, models, and keys were cleared.
    Cleared,
    /// A stored provider credential was removed.
    KeysChanged,
}

type ExportSaver = Rc<dyn Fn(&ExportArtifact) -> Result<bool, String>>;
type ImportChooser = Rc<dyn Fn() -> Result<Option<String>, String>>;

#[derive(Default)]
struct DataHandlers {
    open_backup: Option<Rc<dyn Fn()>>,
    save_export: Option<ExportSaver>,
    choose_import: Option<ImportChooser>,
    data_changed: Option<Rc<dyn Fn(DataChange)>>,
    can_operate: Option<Rc<dyn Fn() -> Result<(), String>>>,
}

fn can_operate(
    state: &Rc<RefCell<DataViewModel>>,
    weak: &slint::Weak<DataWindow>,
    handlers: &Rc<RefCell<DataHandlers>>,
) -> bool {
    if state.borrow().busy.is_some() {
        return false;
    }
    let check = handlers.borrow().can_operate.clone();
    if let Some(check) = check
        && let Err(message) = check()
    {
        state
            .borrow_mut()
            .set_error(&DataError::InvalidInput(message));
        if let Some(window) = weak.upgrade() {
            sync_window(&window, &state.borrow());
        }
        return false;
    }
    true
}

fn operation_result<T>(outcome: Result<Result<T, DataError>, TaskError>) -> Result<T, DataError> {
    outcome.unwrap_or_else(|error| Err(DataError::Storage(error.to_string())))
}

fn finish_export(
    artifact: ExportArtifact,
    state: &Rc<RefCell<DataViewModel>>,
    handlers: &Rc<RefCell<DataHandlers>>,
) -> Result<(), DataError> {
    let saver = handlers.borrow().save_export.clone();
    let saved = saver
        .map(|save| save(&artifact))
        .transpose()
        .map_err(DataError::Storage)?;
    if saved == Some(false) {
        let mut state = state.borrow_mut();
        state.clear_busy();
        state.status = Some("Export cancelled. No file was written.".to_owned());
    } else {
        state.borrow_mut().apply_export(&artifact);
    }
    Ok(())
}

fn notify_change(handlers: &Rc<RefCell<DataHandlers>>, change: DataChange) {
    let handler = handlers.borrow().data_changed.clone();
    if let Some(handler) = handler {
        handler(change);
    }
}

/// Owns the data view model and the Slint window that renders it.
pub struct DataController {
    service: Arc<dyn DataService>,
    state: Rc<RefCell<DataViewModel>>,
    window: DataWindow,
    handlers: Rc<RefCell<DataHandlers>>,
    tasks: Rc<UiTasks>,
}

impl DataController {
    /// Create the data window and load usage, conversations, and keys.
    pub fn new(service: Arc<dyn DataService>) -> Result<Self, slint::PlatformError> {
        Self::create(service, false)
    }

    /// Create a data screen with background import, export, and deletion.
    pub fn new_background(service: Arc<dyn DataService>) -> Result<Self, slint::PlatformError> {
        Self::create(service, true)
    }

    fn create(
        service: Arc<dyn DataService>,
        background: bool,
    ) -> Result<Self, slint::PlatformError> {
        let window = DataWindow::new()?;
        let state = Rc::new(RefCell::new(DataViewModel::new()));
        let controller = Self {
            service,
            state,
            window,
            handlers: Rc::new(RefCell::new(DataHandlers::default())),
            tasks: Rc::new(UiTasks::new(background)),
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

    /// Save a generated artifact; return false when the user cancels the dialog.
    pub fn set_on_save_export(
        &self,
        handler: impl Fn(&ExportArtifact) -> Result<bool, String> + 'static,
    ) {
        self.handlers.borrow_mut().save_export = Some(Rc::new(handler));
    }

    /// Load a selected archive into the review form without importing it yet.
    pub fn set_on_choose_import(
        &self,
        handler: impl Fn() -> Result<Option<String>, String> + 'static,
    ) {
        self.handlers.borrow_mut().choose_import = Some(Rc::new(handler));
    }

    /// Refresh other screens after a successful local mutation.
    pub fn set_on_data_changed(&self, handler: impl Fn(DataChange) + 'static) {
        self.handlers.borrow_mut().data_changed = Some(Rc::new(handler));
    }

    /// Prevent mutations from racing a response, discovery, or restore.
    pub fn set_on_can_operate(&self, handler: impl Fn() -> Result<(), String> + 'static) {
        self.handlers.borrow_mut().can_operate = Some(Rc::new(handler));
    }

    /// Explain why other application work temporarily blocks data actions.
    pub fn set_external_busy(&self, reason: Option<&str>) {
        self.window
            .set_external_busy_message(reason.unwrap_or_default().into());
    }

    /// Apply completed local operations in deterministic hosts.
    pub fn poll(&self) {
        self.tasks.poll();
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
        let tasks = Rc::clone(&self.tasks);
        let handlers = Rc::clone(&self.handlers);
        self.window.on_export_all(move || {
            if !can_operate(&state, &weak, &handlers) {
                return;
            }
            let request = state.borrow().export_all_request();
            state.borrow_mut().set_busy(DataOperation::ExportAll);
            if let Some(window) = weak.upgrade() {
                sync_window(&window, &state.borrow());
            }
            let work = Arc::clone(&service);
            let state = Rc::clone(&state);
            let handlers = Rc::clone(&handlers);
            let weak = weak.clone();
            tasks.run(
                move || work.export_all(&request),
                move |outcome| {
                    let result = operation_result(outcome)
                        .and_then(|artifact| finish_export(artifact, &state, &handlers));
                    apply_result(result, &state, &weak);
                },
            );
        });

        let weak = self.window.as_weak();
        let state = Rc::clone(&self.state);
        let service = Arc::clone(&self.service);
        let tasks = Rc::clone(&self.tasks);
        let handlers = Rc::clone(&self.handlers);
        self.window.on_export_conversation(move || {
            if !can_operate(&state, &weak, &handlers) {
                return;
            }
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
            if let Some(window) = weak.upgrade() {
                sync_window(&window, &state.borrow());
            }
            let work = Arc::clone(&service);
            let state = Rc::clone(&state);
            let handlers = Rc::clone(&handlers);
            let weak = weak.clone();
            tasks.run(
                move || work.export_conversation(&conversation_id, &request),
                move |outcome| {
                    let result = operation_result(outcome)
                        .and_then(|artifact| finish_export(artifact, &state, &handlers));
                    apply_result(result, &state, &weak);
                },
            );
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
        let handlers = Rc::clone(&self.handlers);
        self.window.on_choose_import_file(move || {
            if state.borrow().busy.is_some() {
                return;
            }
            let chooser = handlers.borrow().choose_import.clone();
            let Some(chooser) = chooser else {
                return;
            };
            match chooser() {
                Ok(Some(text)) => {
                    let mut state = state.borrow_mut();
                    state.set_import_text(text);
                    state.set_replace_confirmed(false);
                    state.status = Some(
                        "Archive loaded. Review the import mode, then choose Import archive."
                            .to_owned(),
                    );
                }
                Ok(None) => {}
                Err(error) => state.borrow_mut().set_error(&DataError::Import(error)),
            }
            if let Some(window) = weak.upgrade() {
                sync_window(&window, &state.borrow());
            }
        });

        let weak = self.window.as_weak();
        let state = Rc::clone(&self.state);
        let service = Arc::clone(&self.service);
        let tasks = Rc::clone(&self.tasks);
        let handlers = Rc::clone(&self.handlers);
        self.window.on_import_archive(move || {
            if !can_operate(&state, &weak, &handlers) {
                return;
            }
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
            if let Some(window) = weak.upgrade() {
                sync_window(&window, &state.borrow());
            }
            let work = Arc::clone(&service);
            let service = Arc::clone(&service);
            let state = Rc::clone(&state);
            let weak = weak.clone();
            let handlers = Rc::clone(&handlers);
            let replaced = request.mode == ImportMode::Replace;
            tasks.run(
                move || work.import_text(&request.text, request.mode),
                move |outcome| match operation_result(outcome) {
                    Ok(summary) => {
                        state.borrow_mut().apply_import(summary);
                        reload_after_change(&service, &state);
                        if let Some(window) = weak.upgrade() {
                            sync_window(&window, &state.borrow());
                        }
                        notify_change(&handlers, DataChange::Imported { replaced });
                    }
                    Err(error) => {
                        state.borrow_mut().set_error(&error);
                        if let Some(window) = weak.upgrade() {
                            sync_window(&window, &state.borrow());
                        }
                    }
                },
            );
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
        let tasks = Rc::clone(&self.tasks);
        let handlers = Rc::clone(&self.handlers);
        self.window.on_clear_all(move || {
            if !can_operate(&state, &weak, &handlers) {
                return;
            }
            let request = state.borrow().clear_request();
            if let Err(error) = request {
                state.borrow_mut().set_error(&error);
                if let Some(window) = weak.upgrade() {
                    sync_window(&window, &state.borrow());
                }
                return;
            }

            state.borrow_mut().set_busy(DataOperation::ClearAll);
            if let Some(window) = weak.upgrade() {
                sync_window(&window, &state.borrow());
            }
            let work = Arc::clone(&service);
            let service = Arc::clone(&service);
            let state = Rc::clone(&state);
            let weak = weak.clone();
            let handlers = Rc::clone(&handlers);
            tasks.run(
                move || work.clear_all(),
                move |outcome| match operation_result(outcome) {
                    Ok(summary) => {
                        state.borrow_mut().apply_clear(summary);
                        reload_after_change(&service, &state);
                        if let Some(window) = weak.upgrade() {
                            sync_window(&window, &state.borrow());
                        }
                        notify_change(&handlers, DataChange::Cleared);
                    }
                    Err(error) => {
                        state.borrow_mut().set_error(&error);
                        if let Some(window) = weak.upgrade() {
                            sync_window(&window, &state.borrow());
                        }
                    }
                },
            );
        });

        let weak = self.window.as_weak();
        let state = Rc::clone(&self.state);
        let service = Arc::clone(&self.service);
        let handlers = Rc::clone(&self.handlers);
        self.window.on_remove_key(move |provider_id| {
            if !can_operate(&state, &weak, &handlers) {
                return;
            }
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
                    notify_change(&handlers, DataChange::KeysChanged);
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
    fn native_export_save_cancellation_and_failure_are_truthful() {
        let controller = controller();
        controller.set_on_save_export(|_| Ok(false));
        controller.window().invoke_export_all();
        assert!(controller.state().last_export.is_none());
        assert!(
            controller
                .state()
                .status
                .as_deref()
                .is_some_and(|status| status.contains("cancelled"))
        );
        assert!(controller.state().busy.is_none());

        controller.set_on_save_export(|_| Err("Destination is read-only.".to_owned()));
        controller.window().invoke_export_all();
        assert!(controller.state().error.is_some());
        assert!(controller.state().last_export.is_none());
        assert!(controller.state().busy.is_none());
    }

    #[test]
    fn choosing_an_archive_only_populates_the_review_form() {
        let controller = controller();
        controller.window().invoke_replace_confirmed_changed(true);
        controller.set_on_choose_import(|| Ok(Some("{\"review\":true}".to_owned())));
        controller.window().invoke_choose_import_file();
        assert_eq!(controller.state().import_text, "{\"review\":true}");
        assert!(!controller.state().replace_confirmed);
        assert!(controller.state().last_import.is_none());
    }

    #[test]
    fn fresh_operation_guards_block_data_changes() {
        let controller = controller();
        let allowed = Rc::new(std::cell::Cell::new(false));
        let checked = Rc::clone(&allowed);
        controller.set_on_can_operate(move || {
            if checked.get() {
                Ok(())
            } else {
                Err("Stop the response first.".to_owned())
            }
        });
        let window = controller.window();
        window.invoke_clear_confirmation_changed("DELETE ALL DATA".into());
        window.invoke_clear_all();
        assert!(controller.state().last_clear.is_none());
        assert_eq!(controller.state().conversations.len(), 1);
        allowed.set(true);
        window.invoke_clear_all();
        assert!(controller.state().last_clear.is_some());
        assert!(controller.state().conversations.is_empty());
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
