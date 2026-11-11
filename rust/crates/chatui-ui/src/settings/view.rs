//! Slint settings view and controller.
//!
//! The controller binds the framework-independent [`SettingsViewModel`] to a
//! Slint window. Every live control persists through [`SettingsService`] and
//! updates the view model. Display-only and future settings are rendered as
//! explanatory text, not as dead controls.

use std::cell::RefCell;
use std::rc::Rc;
use std::sync::Arc;

use chatui_domain::ChatParams;
use slint::{ComponentHandle, ModelRc, SharedString, VecModel};

use crate::chat::composer::ComposerState;
use crate::chat::message_list::MessageListState;
use crate::markdown::RenderOptions;
use crate::settings::backup::{
    BackupError, BackupService, BackupSettingsState, backend_from_index, backend_index,
};
use crate::settings::error::{SettingsError, describe_settings_error};
use crate::settings::route::SettingsRoute;
use crate::settings::service::SettingsService;
use crate::settings::state::{
    SettingsViewModel, density_from_index, density_index, font_size_from_index, font_size_index,
    reasoning_from_index, reasoning_index, send_key_from_index, send_key_index, theme_from_index,
    theme_index,
};
use crate::shell::ShellState;

slint::slint! {
    import { Button, CheckBox, ComboBox, LineEdit, Palette, ScrollView, TextEdit } from "std-widgets.slint";

    export struct SettingsNavItem {
        id: string,
        label: string,
    }

    component SettingsSectionButton inherits Rectangle {
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

    export component SettingsWindow inherits Window {
        title: "ChatUI settings";
        width: 1000px;
        height: 760px;

        in property <[SettingsNavItem]> sections: [];
        in property <int> active-section: 0;
        in property <string> notice: "";
        in property <string> error-title: "";
        in property <string> error-message: "";
        in property <string> error-details: "";

        in property <int> theme-index: 0;
        in property <int> density-index: 0;
        in property <int> font-size-index: 1;
        in property <string> reduced-motion-text: "";

        in property <int> send-key-index: 0;
        in property <bool> show-token-usage: true;
        in property <bool> load-remote-images: false;
        in-out property <string> default-system-prompt: "";
        in-out property <string> temperature: "";
        in-out property <string> top-p: "";
        in-out property <string> max-output-tokens: "";
        in property <int> reasoning-index: 0;
        in-out property <string> stop-sequences: "";
        in property <bool> streaming: true;
        in property <[string]> providers: [];
        in property <int> default-provider-index: 0;
        in property <[string]> models: [];
        in property <int> default-model-index: 0;
        in property <string> onboarding-status: "";
        in property <string> remember-keys-text: "";

        in property <string> providers-entry-text: "";
        in property <string> models-entry-text: "";
        in property <string> data-entry-text: "";
        in property <string> backup-title: "";
        in property <bool> backup-enabled: false;
        in property <int> backup-backend-index: 0;
        in-out property <string> backup-endpoint: "";
        in-out property <string> backup-region: "";
        in-out property <string> backup-bucket: "";
        in-out property <string> backup-prefix: "";
        in-out property <string> backup-access-key: "";
        in-out property <string> backup-secret-key: "";
        in-out property <string> backup-sas-token: "";
        in-out property <string> backup-service-account-json: "";
        in-out property <string> backup-access-token: "";
        in-out property <string> backup-folder-id: "";
        in-out property <string> backup-username: "";
        in-out property <string> backup-password: "";
        in-out property <string> backup-passphrase: "";
        in property <string> backup-status-last: "";
        in property <string> backup-status-pending: "";
        in property <string> backup-status-error: "";
        in property <string> backup-status-usage: "";
        in property <string> backup-notice: "";
        in property <string> backup-statement: "";
        in property <string> backup-passphrase-warning: "";
        in property <bool> backup-confirmation-visible: false;
        in property <string> backup-confirmation-label: "";
        in-out property <string> backup-confirmation-input: "";
        in property <string> about-title: "";
        in property <string> about-version: "";
        in property <string> about-body: "";
        in property <[string]> privacy-lines: [];
        in property <string> docs-url: "";

        callback select-section(int);
        callback theme-changed(int);
        callback density-changed(int);
        callback font-size-changed(int);
        callback send-key-changed(int);
        callback show-token-usage-changed(bool);
        callback load-remote-images-changed(bool);
        callback default-system-prompt-changed(string);
        callback temperature-changed(string);
        callback top-p-changed(string);
        callback max-output-tokens-changed(string);
        callback reasoning-changed(int);
        callback stop-sequences-changed(string);
        callback streaming-changed(bool);
        callback default-provider-changed(int);
        callback default-model-changed(int);
        callback open-providers();
        callback open-models();
        callback open-data();
        callback backup-backend-changed(int);
        callback backup-endpoint-changed(string);
        callback backup-region-changed(string);
        callback backup-bucket-changed(string);
        callback backup-prefix-changed(string);
        callback backup-access-key-changed(string);
        callback backup-secret-key-changed(string);
        callback backup-sas-token-changed(string);
        callback backup-service-account-json-changed(string);
        callback backup-access-token-changed(string);
        callback backup-folder-id-changed(string);
        callback backup-username-changed(string);
        callback backup-password-changed(string);
        callback backup-passphrase-changed(string);
        callback backup-test-connection();
        callback backup-enable();
        callback backup-disable();
        callback backup-now();
        callback backup-restore();
        callback backup-restore-replace();
        callback backup-delete-remote();
        callback backup-confirm();
        callback backup-cancel-confirmation();

        in property <bool> dark: false;

        background: root.dark ? #0f1115 : #ffffff;
        init => {
            Palette.color-scheme = root.dark ? ColorScheme.dark : ColorScheme.light;
        }
        changed dark => {
            Palette.color-scheme = root.dark ? ColorScheme.dark : ColorScheme.light;
        }

        HorizontalLayout {
            padding: 16px;
            spacing: 16px;

            VerticalLayout {
                width: 220px;
                spacing: 8px;

                Text {
                    text: "Settings";
                    font-size: 22px;
                    font-weight: 600;
                }

                for section[index] in root.sections : SettingsSectionButton {
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
                        wrap: word-wrap;
                    }

                    if (!root.error-title.is-empty) : Rectangle {
                        background: root.dark ? #3b1d1d : #fde8e8;
                        border-radius: 8px;

                        VerticalLayout {
                            padding: 8px;
                            spacing: 4px;

                            Text { text: root.error-title; font-weight: 600; }
                            Text { text: root.error-message; wrap: word-wrap; }
                            if (!root.error-details.is-empty) : Text {
                                text: root.error-details;
                                wrap: word-wrap;
                                color: root.dark ? #9ca3af : #5f6368;
                            }
                        }
                    }

                    if (root.active-section == 0) : VerticalLayout {
                        spacing: 8px;

                        Text { text: "Appearance"; font-size: 20px; font-weight: 600; }

                        Text { text: "Theme"; }
                        ComboBox {
                            model: ["System", "Light", "Dark"];
                            accessible-label: "Theme";
                            current-index: root.theme-index;
                            selected(value) => { root.theme-changed(self.current-index); }
                        }

                        Text { text: "Density"; }
                        ComboBox {
                            model: ["Comfortable", "Compact"];
                            accessible-label: "Density";
                            current-index: root.density-index;
                            selected(value) => { root.density-changed(self.current-index); }
                        }

                        Text { text: "Chat font size"; }
                        ComboBox {
                            model: ["Small", "Medium", "Large"];
                            accessible-label: "Chat font size";
                            current-index: root.font-size-index;
                            selected(value) => { root.font-size-changed(self.current-index); }
                        }

                        Text {
                            text: root.reduced-motion-text;
                            color: root.dark ? #9ca3af : #5f6368;
                            wrap: word-wrap;
                        }
                    }

                    if (root.active-section == 1) : VerticalLayout {
                        spacing: 8px;

                        Text { text: "Chat defaults"; font-size: 20px; font-weight: 600; }

                        Text { text: "Send key"; }
                        ComboBox {
                            model: ["Enter sends, Shift+Enter adds a line", "Ctrl/Cmd+Enter sends, Enter adds a line"];
                            accessible-label: "Send key";
                            current-index: root.send-key-index;
                            selected(value) => { root.send-key-changed(self.current-index); }
                        }

                        CheckBox {
                            text: "Show token usage on assistant messages";
                            checked: root.show-token-usage;
                            toggled => { root.show-token-usage-changed(self.checked); }
                        }

                        CheckBox {
                            text: "Load remote images in Markdown responses";
                            checked: root.load-remote-images;
                            toggled => { root.load-remote-images-changed(self.checked); }
                        }

                        Text { text: "Default system prompt"; }
                        TextEdit {
                            text <=> root.default-system-prompt;
                            accessible-label: "Default system prompt";
                            edited(text) => { root.default-system-prompt-changed(text); }
                            height: 80px;
                            wrap: word-wrap;
                        }

                        Text { text: "Temperature (0-2, blank for model default)"; }
                        LineEdit {
                            text: root.temperature;
                            accessible-label: "Temperature";
                            edited(text) => { root.temperature-changed(text); }
                        }

                        Text { text: "Top P (0-1, blank for model default)"; }
                        LineEdit {
                            text: root.top-p;
                            accessible-label: "Top P";
                            edited(text) => { root.top-p-changed(text); }
                        }

                        Text { text: "Max output tokens (blank for model default)"; }
                        LineEdit {
                            text: root.max-output-tokens;
                            accessible-label: "Max output tokens";
                            edited(text) => { root.max-output-tokens-changed(text); }
                        }

                        Text { text: "Reasoning effort"; }
                        ComboBox {
                            model: ["Provider default", "Low", "Medium", "High"];
                            accessible-label: "Reasoning effort";
                            current-index: root.reasoning-index;
                            selected(value) => { root.reasoning-changed(self.current-index); }
                        }

                        Text { text: "Stop sequences (one per line)"; }
                        TextEdit {
                            text <=> root.stop-sequences;
                            accessible-label: "Stop sequences";
                            edited(text) => { root.stop-sequences-changed(text); }
                            height: 60px;
                            wrap: word-wrap;
                        }

                        CheckBox {
                            text: "Stream responses by default";
                            checked: root.streaming;
                            toggled => { root.streaming-changed(self.checked); }
                        }

                        Text { text: "Default provider"; }
                        ComboBox {
                            model: root.providers;
                            accessible-label: "Default provider";
                            current-index: root.default-provider-index;
                            selected(value) => { root.default-provider-changed(self.current-index); }
                        }

                        Text { text: "Default model"; }
                        ComboBox {
                            model: root.models;
                            accessible-label: "Default model";
                            current-index: root.default-model-index;
                            selected(value) => { root.default-model-changed(self.current-index); }
                        }

                        Text {
                            text: root.onboarding-status;
                            color: root.dark ? #9ca3af : #5f6368;
                        }

                        Text {
                            text: root.remember-keys-text;
                            color: root.dark ? #9ca3af : #5f6368;
                            wrap: word-wrap;
                        }
                    }

                    if (root.active-section == 2) : VerticalLayout {
                        spacing: 8px;

                        Text { text: "Providers"; font-size: 20px; font-weight: 600; }
                        Text { text: root.providers-entry-text; wrap: word-wrap; }
                        Button {
                            text: "Manage providers";
                            clicked => { root.open-providers(); }
                        }
                    }

                    if (root.active-section == 3) : VerticalLayout {
                        spacing: 8px;

                        Text { text: "Models"; font-size: 20px; font-weight: 600; }
                        Text { text: root.models-entry-text; wrap: word-wrap; }
                        Button {
                            text: "Manage models";
                            clicked => { root.open-models(); }
                        }
                    }

                    if (root.active-section == 4) : VerticalLayout {
                        spacing: 8px;

                        Text { text: "Data"; font-size: 20px; font-weight: 600; }
                        Text { text: root.data-entry-text; wrap: word-wrap; }
                        Button {
                            text: "Open data settings";
                            clicked => { root.open-data(); }
                        }
                    }

                    if (root.active-section == 5) : VerticalLayout {
                        spacing: 8px;

                        Text { text: root.backup-title; font-size: 20px; font-weight: 600; }
                        Text { text: root.backup-statement; wrap: word-wrap; }

                        Text { text: "Backend"; }
                        ComboBox {
                            model: ["S3-compatible", "Azure Blob", "Google Cloud Storage", "Google Drive", "WebDAV"];
                            accessible-label: "Backup backend";
                            current-index: root.backup-backend-index;
                            selected(value) => { root.backup-backend-changed(self.current-index); }
                        }

                        if (root.backup-backend-index == 0) : VerticalLayout {
                            spacing: 8px;
                            Text { text: "Endpoint"; }
                            LineEdit {
                                text: root.backup-endpoint;
                                accessible-label: "Backup endpoint";
                                edited(text) => { root.backup-endpoint-changed(text); }
                            }
                            Text { text: "Region"; }
                            LineEdit {
                                text: root.backup-region;
                                accessible-label: "Backup region";
                                edited(text) => { root.backup-region-changed(text); }
                            }
                            Text { text: "Bucket"; }
                            LineEdit {
                                text: root.backup-bucket;
                                accessible-label: "Backup bucket";
                                edited(text) => { root.backup-bucket-changed(text); }
                            }
                            Text { text: "Access key id"; }
                            LineEdit {
                                text: root.backup-access-key;
                                accessible-label: "Backup access key id";
                                edited(text) => { root.backup-access-key-changed(text); }
                            }
                            Text { text: "Secret access key"; }
                            LineEdit {
                                text: root.backup-secret-key;
                                accessible-label: "Backup secret access key";
                                input-type: InputType.password;
                                edited(text) => { root.backup-secret-key-changed(text); }
                            }
                        }

                        if (root.backup-backend-index == 1) : VerticalLayout {
                            spacing: 8px;
                            Text { text: "Endpoint (optional)"; }
                            LineEdit {
                                text: root.backup-endpoint;
                                accessible-label: "Azure endpoint";
                                edited(text) => { root.backup-endpoint-changed(text); }
                            }
                            Text { text: "Container"; }
                            LineEdit {
                                text: root.backup-bucket;
                                accessible-label: "Azure container";
                                edited(text) => { root.backup-bucket-changed(text); }
                            }
                            Text { text: "Account name"; }
                            LineEdit {
                                text: root.backup-access-key;
                                accessible-label: "Azure account name";
                                edited(text) => { root.backup-access-key-changed(text); }
                            }
                            Text { text: "Account key"; }
                            LineEdit {
                                text: root.backup-secret-key;
                                accessible-label: "Azure account key";
                                input-type: InputType.password;
                                edited(text) => { root.backup-secret-key-changed(text); }
                            }
                            Text { text: "SAS token (used when no account key)"; }
                            LineEdit {
                                text: root.backup-sas-token;
                                accessible-label: "Azure SAS token";
                                input-type: InputType.password;
                                edited(text) => { root.backup-sas-token-changed(text); }
                            }
                        }

                        if (root.backup-backend-index == 2) : VerticalLayout {
                            spacing: 8px;
                            Text { text: "Endpoint (optional)"; }
                            LineEdit {
                                text: root.backup-endpoint;
                                accessible-label: "GCS endpoint";
                                edited(text) => { root.backup-endpoint-changed(text); }
                            }
                            Text { text: "Bucket"; }
                            LineEdit {
                                text: root.backup-bucket;
                                accessible-label: "GCS bucket";
                                edited(text) => { root.backup-bucket-changed(text); }
                            }
                            Text { text: "Service account JSON"; }
                            TextEdit {
                                text: root.backup-service-account-json;
                                accessible-label: "GCS service account JSON";
                                edited(text) => { root.backup-service-account-json-changed(text); }
                                height: 100px;
                                wrap: word-wrap;
                            }
                        }

                        if (root.backup-backend-index == 3) : VerticalLayout {
                            spacing: 8px;
                            Text { text: "Endpoint (optional)"; }
                            LineEdit {
                                text: root.backup-endpoint;
                                accessible-label: "Google Drive endpoint";
                                edited(text) => { root.backup-endpoint-changed(text); }
                            }
                            Text { text: "Folder id (blank for app data folder)"; }
                            LineEdit {
                                text: root.backup-folder-id;
                                accessible-label: "Google Drive folder id";
                                edited(text) => { root.backup-folder-id-changed(text); }
                            }
                            Text { text: "OAuth access token"; }
                            LineEdit {
                                text: root.backup-access-token;
                                accessible-label: "Google Drive access token";
                                input-type: InputType.password;
                                edited(text) => { root.backup-access-token-changed(text); }
                            }
                        }

                        if (root.backup-backend-index == 4) : VerticalLayout {
                            spacing: 8px;
                            Text { text: "WebDAV URL"; }
                            LineEdit {
                                text: root.backup-endpoint;
                                accessible-label: "WebDAV URL";
                                edited(text) => { root.backup-endpoint-changed(text); }
                            }
                            Text { text: "Username"; }
                            LineEdit {
                                text: root.backup-username;
                                accessible-label: "WebDAV username";
                                edited(text) => { root.backup-username-changed(text); }
                            }
                            Text { text: "Password"; }
                            LineEdit {
                                text: root.backup-password;
                                accessible-label: "WebDAV password";
                                input-type: InputType.password;
                                edited(text) => { root.backup-password-changed(text); }
                            }
                        }

                        Text { text: "Prefix"; }
                        LineEdit {
                            text: root.backup-prefix;
                            accessible-label: "Backup prefix";
                            edited(text) => { root.backup-prefix-changed(text); }
                        }

                        Text { text: "Passphrase (never stored)"; }
                        LineEdit {
                            text: root.backup-passphrase;
                            accessible-label: "Backup passphrase";
                            input-type: InputType.password;
                            edited(text) => { root.backup-passphrase-changed(text); }
                        }

                        HorizontalLayout {
                            spacing: 8px;
                            Button {
                                text: "Test connection";
                                clicked => { root.backup-test-connection(); }
                            }
                            Button {
                                text: root.backup-enabled ? "Disable backup" : "Enable backup";
                                clicked => {
                                    if (root.backup-enabled) {
                                        root.backup-disable();
                                    } else {
                                        root.backup-enable();
                                    }
                                }
                            }
                        }

                        HorizontalLayout {
                            spacing: 8px;
                            Button {
                                text: "Back up now";
                                clicked => { root.backup-now(); }
                            }
                            Button {
                                text: "Restore from backup";
                                clicked => { root.backup-restore(); }
                            }
                            Button {
                                text: "Restore and replace local data";
                                clicked => { root.backup-restore-replace(); }
                            }
                            Button {
                                text: "Delete remote backup";
                                clicked => { root.backup-delete-remote(); }
                            }
                        }

                        Text { text: "Last backup: " + root.backup-status-last; }
                        Text { text: "Pending changes: " + root.backup-status-pending; }
                        Text { text: "Storage usage: " + root.backup-status-usage; }
                        Text { text: "Last error: " + root.backup-status-error; }

                        if (!root.backup-notice.is-empty) : Text {
                            text: root.backup-notice;
                            wrap: word-wrap;
                        }

                        if (root.backup-confirmation-visible) : Rectangle {
                            background: root.dark ? #3b1d1d : #fde8e8;
                            border-radius: 8px;

                            VerticalLayout {
                                padding: 8px;
                                spacing: 4px;

                                Text { text: root.backup-confirmation-label; wrap: word-wrap; }
                                LineEdit {
                                    text <=> root.backup-confirmation-input;
                                    accessible-label: "Backup confirmation";
                                }
                                HorizontalLayout {
                                    spacing: 8px;
                                    Button {
                                        text: "Confirm";
                                        clicked => { root.backup-confirm(); }
                                    }
                                    Button {
                                        text: "Cancel";
                                        clicked => { root.backup-cancel-confirmation(); }
                                    }
                                }
                            }
                        }

                        Text {
                            text: root.backup-passphrase-warning;
                            color: root.dark ? #9ca3af : #5f6368;
                            wrap: word-wrap;
                        }
                    }

                    if (root.active-section == 6) : VerticalLayout {
                        spacing: 8px;

                        Text { text: root.about-title; font-size: 20px; font-weight: 600; }
                        Text { text: root.about-version; color: root.dark ? #9ca3af : #5f6368; }
                        Text { text: root.about-body; wrap: word-wrap; }
                        for line in root.privacy-lines : Text {
                            text: "• " + line;
                            wrap: word-wrap;
                        }
                        Text { text: "Docs: " + root.docs-url; wrap: word-wrap; }
                    }
                }
            }
        }
    }
}

/// Push the current view model into a Slint window.
pub fn sync_window(window: &SettingsWindow, state: &SettingsViewModel) {
    let sections: Vec<SettingsNavItem> = SettingsRoute::ALL
        .iter()
        .map(|route| SettingsNavItem {
            id: SharedString::from(route.id()),
            label: SharedString::from(route.label()),
        })
        .collect();

    window.set_sections(ModelRc::new(VecModel::from(sections)));
    window.set_active_section(state.route.index() as i32);
    window.set_notice(state.notice.clone().unwrap_or_default().into());
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

    window.set_theme_index(theme_index(state.settings.theme) as i32);
    window.set_density_index(density_index(state.settings.density) as i32);
    window.set_font_size_index(font_size_index(state.settings.chat_font_size) as i32);
    window.set_reduced_motion_text(state.reduced_motion_text().into());

    window.set_send_key_index(send_key_index(state.settings.send_key) as i32);
    window.set_show_token_usage(state.settings.show_token_usage);
    window.set_load_remote_images(state.settings.load_remote_images);
    window.set_default_system_prompt(state.settings.default_system_prompt.clone().into());
    window.set_temperature(optional_f64_text(state.settings.default_params.temperature).into());
    window.set_top_p(optional_f64_text(state.settings.default_params.top_p).into());
    window.set_max_output_tokens(
        optional_u32_text(state.settings.default_params.max_output_tokens).into(),
    );
    window.set_reasoning_index(
        reasoning_index(state.settings.default_params.reasoning_effort) as i32,
    );
    window.set_stop_sequences(stop_sequences_text(&state.settings.default_params).into());
    window.set_streaming(state.settings.default_params.streaming);
    window.set_providers(ModelRc::new(VecModel::from(
        state
            .provider_display()
            .into_iter()
            .map(SharedString::from)
            .collect::<Vec<_>>(),
    )));
    window.set_default_provider_index(state.default_provider_index() as i32);
    window.set_models(ModelRc::new(VecModel::from(
        state
            .model_display()
            .into_iter()
            .map(SharedString::from)
            .collect::<Vec<_>>(),
    )));
    window.set_default_model_index(state.default_model_index() as i32);
    window.set_onboarding_status(state.onboarding_status().into());
    window.set_remember_keys_text(state.remember_keys_text().into());

    window.set_providers_entry_text(state.providers_entry_text().into());
    window.set_models_entry_text(state.models_entry_text().into());
    window.set_data_entry_text(state.data_entry_text().into());
    window.set_backup_title("Backup".into());

    let about = state.about();
    window.set_about_title(format!("About {}", about.app_name).into());
    window.set_about_version(format!("Version {}", about.version).into());
    window.set_about_body(about.body.into());
    window.set_privacy_lines(ModelRc::new(VecModel::from(
        about
            .privacy
            .iter()
            .map(|line| SharedString::from(*line))
            .collect::<Vec<_>>(),
    )));
    window.set_docs_url(about.docs_url.into());
}

/// Push the backup form and status into a Slint window.
pub fn sync_backup_window(window: &SettingsWindow, state: &BackupSettingsState) {
    window.set_backup_enabled(state.status.enabled);
    window.set_backup_backend_index(backend_index(state.form.backend) as i32);
    window.set_backup_endpoint(state.form.endpoint.clone().into());
    window.set_backup_region(state.form.region.clone().into());
    window.set_backup_bucket(state.form.bucket.clone().into());
    window.set_backup_prefix(state.form.prefix.clone().into());
    window.set_backup_access_key(state.form.access_key_id.clone().into());
    window.set_backup_secret_key(state.form.secret_access_key.clone().into());
    window.set_backup_sas_token(state.form.sas_token.clone().into());
    window.set_backup_service_account_json(state.form.service_account_json.clone().into());
    window.set_backup_access_token(state.form.access_token.clone().into());
    window.set_backup_folder_id(state.form.folder_id.clone().into());
    window.set_backup_username(state.form.username.clone().into());
    window.set_backup_password(state.form.password.clone().into());
    window.set_backup_passphrase(state.form.passphrase.clone().into());
    window.set_backup_status_last(state.status.last_backup_text().into());
    window.set_backup_status_pending(state.status.pending_changes_text().into());
    window.set_backup_status_error(state.status.last_error_text().into());
    window.set_backup_status_usage(state.status.storage_usage_text().into());
    window.set_backup_notice(
        state
            .notice
            .clone()
            .or_else(|| state.error.clone())
            .unwrap_or_default()
            .into(),
    );
    window.set_backup_statement(state.client_side_statement().into());
    window.set_backup_passphrase_warning(state.passphrase_warning().into());
    window.set_backup_confirmation_visible(state.confirmation_visible());
    window.set_backup_confirmation_label(state.confirmation_label().into());
    window.set_backup_confirmation_input(state.confirmation_input.clone().into());
}

fn optional_f64_text(value: Option<f64>) -> String {
    value.map(|value| value.to_string()).unwrap_or_default()
}

fn optional_u32_text(value: Option<u32>) -> String {
    value.map(|value| value.to_string()).unwrap_or_default()
}

fn stop_sequences_text(params: &ChatParams) -> String {
    params.stop.join("\n")
}

fn parse_optional_f64(value: &str) -> Result<Option<f64>, SettingsError> {
    let trimmed = value.trim();
    if trimmed.is_empty() {
        return Ok(None);
    }

    trimmed.parse::<f64>().map(Some).map_err(|_| {
        SettingsError::InvalidInput("Enter a number or leave the field blank.".to_owned())
    })
}

fn parse_optional_u32(value: &str) -> Result<Option<u32>, SettingsError> {
    let trimmed = value.trim();
    if trimmed.is_empty() {
        return Ok(None);
    }

    trimmed.parse::<u32>().map(Some).map_err(|_| {
        SettingsError::InvalidInput("Enter a whole number or leave the field blank.".to_owned())
    })
}

fn parse_stop_sequences(value: &str) -> Vec<String> {
    value
        .lines()
        .map(str::trim)
        .filter(|line| !line.is_empty())
        .map(str::to_owned)
        .collect()
}

fn provider_id_for_index(state: &SettingsViewModel, index: i32) -> Option<String> {
    if index <= 0 {
        None
    } else {
        state
            .providers
            .get((index - 1) as usize)
            .map(|provider| provider.id.clone())
    }
}

fn model_for_index(state: &SettingsViewModel, index: i32) -> Option<(String, String)> {
    if index <= 0 {
        None
    } else {
        state
            .models
            .get((index - 1) as usize)
            .map(|model| (model.provider_id.clone(), model.id.clone()))
    }
}

fn apply_result(
    result: Result<SettingsViewModel, SettingsError>,
    state: &Rc<RefCell<SettingsViewModel>>,
    weak: &slint::Weak<SettingsWindow>,
) {
    match result {
        Ok(mut view_model) => {
            let route = state.borrow().route.route();
            view_model.route.select(route);
            *state.borrow_mut() = view_model;
        }
        Err(error) => {
            state.borrow_mut().error = Some(describe_settings_error(&error));
        }
    }

    if let Some(window) = weak.upgrade() {
        sync_window(&window, &state.borrow());
    }
}

fn set_error(
    error: SettingsError,
    state: &Rc<RefCell<SettingsViewModel>>,
    weak: &slint::Weak<SettingsWindow>,
) {
    state.borrow_mut().error = Some(describe_settings_error(&error));
    if let Some(window) = weak.upgrade() {
        sync_window(&window, &state.borrow());
    }
}

fn sync_backup(state: &Rc<RefCell<BackupSettingsState>>, weak: &slint::Weak<SettingsWindow>) {
    if let Some(window) = weak.upgrade() {
        sync_backup_window(&window, &state.borrow());
    }
}

fn backup_unavailable(state: &Rc<RefCell<BackupSettingsState>>) {
    state.borrow_mut().error = Some("Backup is not available in this build.".to_owned());
}

fn run_backup_action<F>(
    backup: &Rc<RefCell<Option<Arc<dyn BackupService>>>>,
    state: &Rc<RefCell<BackupSettingsState>>,
    weak: &slint::Weak<SettingsWindow>,
    action: F,
) where
    F: FnOnce(&dyn BackupService, &mut BackupSettingsState) -> Result<(), BackupError>,
{
    let service = backup.borrow().clone();
    match service {
        Some(service) => {
            let result = action(service.as_ref(), &mut state.borrow_mut());
            if let Err(error) = result {
                state.borrow_mut().error = Some(error.message().to_owned());
            }
        }
        None => backup_unavailable(state),
    }
    sync_backup(state, weak);
}

fn update_params<F>(
    service: &SettingsService,
    state: &Rc<RefCell<SettingsViewModel>>,
    weak: &slint::Weak<SettingsWindow>,
    update: F,
) where
    F: FnOnce(&mut ChatParams),
{
    let mut params = state.borrow().settings.default_params.clone();
    update(&mut params);
    let result = service.set_default_params(params);
    apply_result(result, state, weak);
}

/// Handlers for the provider, model, and data entry points.
#[derive(Default)]
struct SettingsHandlers {
    open_providers: Option<Rc<dyn Fn()>>,
    open_models: Option<Rc<dyn Fn()>>,
    open_data: Option<Rc<dyn Fn()>>,
}

/// Owns the settings view model and the Slint window that renders it.
pub struct SettingsController {
    service: Arc<SettingsService>,
    state: Rc<RefCell<SettingsViewModel>>,
    backup: Rc<RefCell<Option<Arc<dyn BackupService>>>>,
    backup_state: Rc<RefCell<BackupSettingsState>>,
    window: SettingsWindow,
    handlers: Rc<RefCell<SettingsHandlers>>,
}

impl SettingsController {
    /// Create the settings window and load the persisted settings.
    pub fn new(service: Arc<SettingsService>) -> Result<Self, slint::PlatformError> {
        let window = SettingsWindow::new()?;
        let view_model = match service.load() {
            Ok(view_model) => view_model,
            Err(error) => {
                let mut view_model = SettingsViewModel::new(
                    chatui_storage::default_settings(),
                    Vec::new(),
                    Vec::new(),
                    SettingsRoute::Appearance,
                );
                view_model.error = Some(describe_settings_error(&error));
                view_model
            }
        };
        let state = Rc::new(RefCell::new(view_model));
        let controller = Self {
            service,
            state,
            backup: Rc::new(RefCell::new(None)),
            backup_state: Rc::new(RefCell::new(BackupSettingsState::new(
                Default::default(),
                Default::default(),
            ))),
            window,
            handlers: Rc::new(RefCell::new(SettingsHandlers::default())),
        };
        controller.wire();
        controller.sync();
        Ok(controller)
    }

    /// Create the settings window with a backup service.
    pub fn with_backup(
        service: Arc<SettingsService>,
        backup_service: Arc<dyn BackupService>,
    ) -> Result<Self, slint::PlatformError> {
        let controller = Self::new(service)?;
        let config = backup_service.config().ok().flatten();
        let status = backup_service.status().unwrap_or_default();
        *controller.backup_state.borrow_mut() =
            BackupSettingsState::from_config(config.as_ref(), status);
        *controller.backup.borrow_mut() = Some(backup_service);
        controller.sync();
        Ok(controller)
    }

    /// The underlying Slint window.
    pub fn window(&self) -> &SettingsWindow {
        &self.window
    }

    /// Borrow the current view model.
    pub fn state(&self) -> std::cell::Ref<'_, SettingsViewModel> {
        self.state.borrow()
    }

    /// Borrow the current backup state.
    pub fn backup_state(&self) -> std::cell::Ref<'_, BackupSettingsState> {
        self.backup_state.borrow()
    }

    /// Push the current view model into the window.
    pub fn sync(&self) {
        sync_window(&self.window, &self.state.borrow());
        sync_backup_window(&self.window, &self.backup_state.borrow());
    }

    /// Select a settings section and refresh the window.
    pub fn select_route(&self, route: SettingsRoute) {
        self.state.borrow_mut().route.select(route);
        self.sync();
    }

    /// Apply appearance settings to the shell.
    pub fn apply_to_shell(&self, state: &mut ShellState) {
        self.state.borrow().apply_to_shell(state);
    }

    /// Apply the send-key preference to the composer.
    pub fn apply_to_composer(&self, state: &mut ComposerState) {
        self.state.borrow().apply_to_composer(state);
    }

    /// Apply token-usage visibility to the message list.
    pub fn apply_to_message_list(&self, state: &mut MessageListState) {
        self.state.borrow().apply_to_message_list(state);
    }

    /// Resolve Markdown render options from the remote-image preference.
    pub fn render_options(&self) -> RenderOptions {
        self.state.borrow().render_options()
    }

    /// Register the provider-management entry-point handler.
    pub fn set_on_open_providers(&mut self, handler: impl Fn() + 'static) {
        self.handlers.borrow_mut().open_providers = Some(Rc::new(handler));
    }

    /// Register the model-management entry-point handler.
    pub fn set_on_open_models(&mut self, handler: impl Fn() + 'static) {
        self.handlers.borrow_mut().open_models = Some(Rc::new(handler));
    }

    /// Register the data-settings entry-point handler.
    pub fn set_on_open_data(&mut self, handler: impl Fn() + 'static) {
        self.handlers.borrow_mut().open_data = Some(Rc::new(handler));
    }

    fn wire(&self) {
        let weak = self.window.as_weak();
        let state = Rc::clone(&self.state);
        self.window.on_select_section(move |index| {
            if let Some(route) = SettingsRoute::from_index(index as usize) {
                state.borrow_mut().route.select(route);
            }
            if let Some(window) = weak.upgrade() {
                sync_window(&window, &state.borrow());
            }
        });

        let weak = self.window.as_weak();
        let state = Rc::clone(&self.state);
        let service = Arc::clone(&self.service);
        self.window.on_theme_changed(move |index| {
            let result = service.set_theme(theme_from_index(index as usize));
            apply_result(result, &state, &weak);
        });

        let weak = self.window.as_weak();
        let state = Rc::clone(&self.state);
        let service = Arc::clone(&self.service);
        self.window.on_density_changed(move |index| {
            let result = service.set_density(density_from_index(index as usize));
            apply_result(result, &state, &weak);
        });

        let weak = self.window.as_weak();
        let state = Rc::clone(&self.state);
        let service = Arc::clone(&self.service);
        self.window.on_font_size_changed(move |index| {
            let result = service.set_chat_font_size(font_size_from_index(index as usize));
            apply_result(result, &state, &weak);
        });

        let weak = self.window.as_weak();
        let state = Rc::clone(&self.state);
        let service = Arc::clone(&self.service);
        self.window.on_send_key_changed(move |index| {
            let result = service.set_send_key(send_key_from_index(index as usize));
            apply_result(result, &state, &weak);
        });

        let weak = self.window.as_weak();
        let state = Rc::clone(&self.state);
        let service = Arc::clone(&self.service);
        self.window.on_show_token_usage_changed(move |show| {
            let result = service.set_show_token_usage(show);
            apply_result(result, &state, &weak);
        });

        let weak = self.window.as_weak();
        let state = Rc::clone(&self.state);
        let service = Arc::clone(&self.service);
        self.window.on_load_remote_images_changed(move |load| {
            let result = service.set_load_remote_images(load);
            apply_result(result, &state, &weak);
        });

        let weak = self.window.as_weak();
        let state = Rc::clone(&self.state);
        let service = Arc::clone(&self.service);
        self.window.on_default_system_prompt_changed(move |value| {
            let result = service.set_default_system_prompt(value.as_str());
            apply_result(result, &state, &weak);
        });

        let weak = self.window.as_weak();
        let state = Rc::clone(&self.state);
        let service = Arc::clone(&self.service);
        self.window
            .on_temperature_changed(move |value| match parse_optional_f64(value.as_str()) {
                Ok(temperature) => update_params(&service, &state, &weak, |params| {
                    params.temperature = temperature;
                }),
                Err(error) => set_error(error, &state, &weak),
            });

        let weak = self.window.as_weak();
        let state = Rc::clone(&self.state);
        let service = Arc::clone(&self.service);
        self.window
            .on_top_p_changed(move |value| match parse_optional_f64(value.as_str()) {
                Ok(top_p) => update_params(&service, &state, &weak, |params| {
                    params.top_p = top_p;
                }),
                Err(error) => set_error(error, &state, &weak),
            });

        let weak = self.window.as_weak();
        let state = Rc::clone(&self.state);
        let service = Arc::clone(&self.service);
        self.window.on_max_output_tokens_changed(move |value| {
            match parse_optional_u32(value.as_str()) {
                Ok(max_output_tokens) => update_params(&service, &state, &weak, |params| {
                    params.max_output_tokens = max_output_tokens;
                }),
                Err(error) => set_error(error, &state, &weak),
            }
        });

        let weak = self.window.as_weak();
        let state = Rc::clone(&self.state);
        let service = Arc::clone(&self.service);
        self.window.on_reasoning_changed(move |index| {
            let effort = reasoning_from_index(index as usize);
            update_params(&service, &state, &weak, |params| {
                params.reasoning_effort = effort;
            });
        });

        let weak = self.window.as_weak();
        let state = Rc::clone(&self.state);
        let service = Arc::clone(&self.service);
        self.window.on_stop_sequences_changed(move |value| {
            let stop = parse_stop_sequences(value.as_str());
            update_params(&service, &state, &weak, |params| {
                params.stop = stop;
            });
        });

        let weak = self.window.as_weak();
        let state = Rc::clone(&self.state);
        let service = Arc::clone(&self.service);
        self.window.on_streaming_changed(move |streaming| {
            update_params(&service, &state, &weak, |params| {
                params.streaming = streaming;
            });
        });

        let weak = self.window.as_weak();
        let state = Rc::clone(&self.state);
        let service = Arc::clone(&self.service);
        self.window.on_default_provider_changed(move |index| {
            let provider_id = provider_id_for_index(&state.borrow(), index);
            let result = service.set_default_provider_model(provider_id.as_deref(), None);
            apply_result(result, &state, &weak);
        });

        let weak = self.window.as_weak();
        let state = Rc::clone(&self.state);
        let service = Arc::clone(&self.service);
        self.window.on_default_model_changed(move |index| {
            let (provider_id, model_id) = {
                let state = state.borrow();
                match model_for_index(&state, index) {
                    Some((provider_id, model_id)) => (Some(provider_id), Some(model_id)),
                    None => (state.settings.default_provider_id.clone(), None),
                }
            };
            let result =
                service.set_default_provider_model(provider_id.as_deref(), model_id.as_deref());
            apply_result(result, &state, &weak);
        });

        let weak = self.window.as_weak();
        let state = Rc::clone(&self.state);
        let handlers = Rc::clone(&self.handlers);
        self.window.on_open_providers(move || {
            if let Some(handler) = handlers.borrow().open_providers.clone() {
                handler();
                return;
            }
            state.borrow_mut().route.select(SettingsRoute::Providers);
            if let Some(window) = weak.upgrade() {
                sync_window(&window, &state.borrow());
            }
        });

        let weak = self.window.as_weak();
        let state = Rc::clone(&self.state);
        let handlers = Rc::clone(&self.handlers);
        self.window.on_open_models(move || {
            if let Some(handler) = handlers.borrow().open_models.clone() {
                handler();
                return;
            }
            state.borrow_mut().route.select(SettingsRoute::Models);
            if let Some(window) = weak.upgrade() {
                sync_window(&window, &state.borrow());
            }
        });

        let weak = self.window.as_weak();
        let state = Rc::clone(&self.state);
        let handlers = Rc::clone(&self.handlers);
        self.window.on_open_data(move || {
            if let Some(handler) = handlers.borrow().open_data.clone() {
                handler();
                return;
            }
            state.borrow_mut().route.select(SettingsRoute::Data);
            if let Some(window) = weak.upgrade() {
                sync_window(&window, &state.borrow());
            }
        });

        let weak = self.window.as_weak();
        let backup_state = Rc::clone(&self.backup_state);
        self.window.on_backup_backend_changed(move |index| {
            backup_state.borrow_mut().form.backend = backend_from_index(index as usize);
            sync_backup(&backup_state, &weak);
        });

        let weak = self.window.as_weak();
        let backup_state = Rc::clone(&self.backup_state);
        self.window.on_backup_endpoint_changed(move |value| {
            backup_state.borrow_mut().form.endpoint = value.to_string();
            sync_backup(&backup_state, &weak);
        });

        let weak = self.window.as_weak();
        let backup_state = Rc::clone(&self.backup_state);
        self.window.on_backup_region_changed(move |value| {
            backup_state.borrow_mut().form.region = value.to_string();
            sync_backup(&backup_state, &weak);
        });

        let weak = self.window.as_weak();
        let backup_state = Rc::clone(&self.backup_state);
        self.window.on_backup_bucket_changed(move |value| {
            backup_state.borrow_mut().form.bucket = value.to_string();
            sync_backup(&backup_state, &weak);
        });

        let weak = self.window.as_weak();
        let backup_state = Rc::clone(&self.backup_state);
        self.window.on_backup_prefix_changed(move |value| {
            backup_state.borrow_mut().form.prefix = value.to_string();
            sync_backup(&backup_state, &weak);
        });

        let weak = self.window.as_weak();
        let backup_state = Rc::clone(&self.backup_state);
        self.window.on_backup_access_key_changed(move |value| {
            backup_state.borrow_mut().form.access_key_id = value.to_string();
            sync_backup(&backup_state, &weak);
        });

        let weak = self.window.as_weak();
        let backup_state = Rc::clone(&self.backup_state);
        self.window.on_backup_secret_key_changed(move |value| {
            backup_state.borrow_mut().form.secret_access_key = value.to_string();
            sync_backup(&backup_state, &weak);
        });

        let weak = self.window.as_weak();
        let backup_state = Rc::clone(&self.backup_state);
        self.window.on_backup_sas_token_changed(move |value| {
            backup_state.borrow_mut().form.sas_token = value.to_string();
            sync_backup(&backup_state, &weak);
        });

        let weak = self.window.as_weak();
        let backup_state = Rc::clone(&self.backup_state);
        self.window
            .on_backup_service_account_json_changed(move |value| {
                backup_state.borrow_mut().form.service_account_json = value.to_string();
                sync_backup(&backup_state, &weak);
            });

        let weak = self.window.as_weak();
        let backup_state = Rc::clone(&self.backup_state);
        self.window.on_backup_access_token_changed(move |value| {
            backup_state.borrow_mut().form.access_token = value.to_string();
            sync_backup(&backup_state, &weak);
        });

        let weak = self.window.as_weak();
        let backup_state = Rc::clone(&self.backup_state);
        self.window.on_backup_folder_id_changed(move |value| {
            backup_state.borrow_mut().form.folder_id = value.to_string();
            sync_backup(&backup_state, &weak);
        });

        let weak = self.window.as_weak();
        let backup_state = Rc::clone(&self.backup_state);
        self.window.on_backup_username_changed(move |value| {
            backup_state.borrow_mut().form.username = value.to_string();
            sync_backup(&backup_state, &weak);
        });

        let weak = self.window.as_weak();
        let backup_state = Rc::clone(&self.backup_state);
        self.window.on_backup_password_changed(move |value| {
            backup_state.borrow_mut().form.password = value.to_string();
            sync_backup(&backup_state, &weak);
        });

        let weak = self.window.as_weak();
        let backup_state = Rc::clone(&self.backup_state);
        self.window.on_backup_passphrase_changed(move |value| {
            backup_state.borrow_mut().form.passphrase = value.to_string();
            sync_backup(&backup_state, &weak);
        });

        let weak = self.window.as_weak();
        let backup = Rc::clone(&self.backup);
        let backup_state = Rc::clone(&self.backup_state);
        self.window.on_backup_test_connection(move || {
            run_backup_action(&backup, &backup_state, &weak, |service, state| {
                state.test_connection(service)
            });
        });

        let weak = self.window.as_weak();
        let backup = Rc::clone(&self.backup);
        let backup_state = Rc::clone(&self.backup_state);
        self.window.on_backup_enable(move || {
            run_backup_action(&backup, &backup_state, &weak, |service, state| {
                state.enable(service)
            });
        });

        let weak = self.window.as_weak();
        let backup = Rc::clone(&self.backup);
        let backup_state = Rc::clone(&self.backup_state);
        self.window.on_backup_disable(move || {
            run_backup_action(&backup, &backup_state, &weak, |service, state| {
                state.disable(service)
            });
        });

        let weak = self.window.as_weak();
        let backup = Rc::clone(&self.backup);
        let backup_state = Rc::clone(&self.backup_state);
        self.window.on_backup_now(move || {
            run_backup_action(&backup, &backup_state, &weak, |service, state| {
                state.backup_now(service)
            });
        });

        let weak = self.window.as_weak();
        let backup = Rc::clone(&self.backup);
        let backup_state = Rc::clone(&self.backup_state);
        self.window.on_backup_restore(move || {
            run_backup_action(&backup, &backup_state, &weak, |service, state| {
                state.restore(service, false)
            });
        });

        let weak = self.window.as_weak();
        let backup = Rc::clone(&self.backup);
        let backup_state = Rc::clone(&self.backup_state);
        self.window.on_backup_restore_replace(move || {
            run_backup_action(&backup, &backup_state, &weak, |service, state| {
                state.restore(service, true)
            });
        });

        let weak = self.window.as_weak();
        let backup_state = Rc::clone(&self.backup_state);
        self.window.on_backup_delete_remote(move || {
            backup_state.borrow_mut().request_delete_remote();
            sync_backup(&backup_state, &weak);
        });

        let weak = self.window.as_weak();
        let backup = Rc::clone(&self.backup);
        let backup_state = Rc::clone(&self.backup_state);
        self.window.on_backup_confirm(move || {
            if let Some(window) = weak.upgrade() {
                backup_state.borrow_mut().confirmation_input =
                    window.get_backup_confirmation_input().to_string();
            }
            run_backup_action(&backup, &backup_state, &weak, |service, state| {
                state.confirm(service)
            });
        });

        let weak = self.window.as_weak();
        let backup_state = Rc::clone(&self.backup_state);
        self.window.on_backup_cancel_confirmation(move || {
            backup_state.borrow_mut().cancel_confirmation();
            sync_backup(&backup_state, &weak);
        });
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use chatui_domain::{
        BrowserCors, ChatFontSize, Density, InputModality, Model, ModelCapability, ModelSource,
        OutputModality, Provider, ProviderAuthType, ProviderKind, ReasoningEffort, SendKey, Theme,
        Timestamp,
    };
    use slint::Model as _;

    use crate::settings::service::{MemoryProviderModelCatalog, MemorySettingsStore};

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

    fn model(id: &str, provider_id: &str, label: &str) -> Model {
        let now = Timestamp::now();
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
            created_at: now,
            updated_at: now,
        }
    }

    fn controller() -> SettingsController {
        i_slint_backend_testing::init_no_event_loop();

        let store = Arc::new(MemorySettingsStore::new());
        let catalog = Arc::new(MemoryProviderModelCatalog::new(
            vec![provider("p1", "One"), provider("p2", "Two")],
            vec![
                model("m1", "p1", "Model One"),
                model("m2", "p2", "Model Two"),
            ],
        ));
        let service = Arc::new(SettingsService::new(store, catalog));
        SettingsController::new(service).expect("settings window")
    }

    #[derive(Default)]
    struct FakeBackupService {
        calls: std::sync::Mutex<Vec<String>>,
        status: std::sync::Mutex<crate::settings::backup::BackupStatus>,
    }

    impl FakeBackupService {
        fn calls(&self) -> Vec<String> {
            self.calls.lock().unwrap().clone()
        }

        fn record(&self, call: impl Into<String>) {
            self.calls.lock().unwrap().push(call.into());
        }
    }

    impl crate::settings::backup::BackupService for FakeBackupService {
        fn config(&self) -> Result<Option<chatui_domain::BackupConfig>, BackupError> {
            Ok(None)
        }

        fn status(&self) -> Result<crate::settings::backup::BackupStatus, BackupError> {
            Ok(self.status.lock().unwrap().clone())
        }

        fn test_connection(
            &self,
            _form: &crate::settings::backup::BackupForm,
        ) -> Result<(), BackupError> {
            self.record("test_connection");
            Ok(())
        }

        fn enable(
            &self,
            _form: &crate::settings::backup::BackupForm,
        ) -> Result<crate::settings::backup::BackupStatus, BackupError> {
            self.record("enable");
            let mut status = self.status.lock().unwrap();
            status.enabled = true;
            Ok(status.clone())
        }

        fn disable(&self) -> Result<crate::settings::backup::BackupStatus, BackupError> {
            self.record("disable");
            let mut status = self.status.lock().unwrap();
            status.enabled = false;
            Ok(status.clone())
        }

        fn backup_now(
            &self,
            passphrase: &str,
        ) -> Result<crate::settings::backup::BackupStatus, BackupError> {
            self.record(format!("backup_now:{passphrase}"));
            Ok(self.status.lock().unwrap().clone())
        }

        fn restore(
            &self,
            passphrase: &str,
            replace: bool,
        ) -> Result<crate::settings::backup::RestoreOutcome, BackupError> {
            self.record(format!("restore:{passphrase}:{replace}"));
            Ok(crate::settings::backup::RestoreOutcome {
                merged_conversations: vec!["c1".to_owned()],
                replaced: replace,
            })
        }

        fn delete_remote(&self, passphrase: &str, confirmation: &str) -> Result<(), BackupError> {
            self.record(format!("delete_remote:{passphrase}:{confirmation}"));
            Ok(())
        }
    }

    fn backup_controller() -> (SettingsController, Arc<FakeBackupService>) {
        i_slint_backend_testing::init_no_event_loop();

        let store = Arc::new(MemorySettingsStore::new());
        let catalog = Arc::new(MemoryProviderModelCatalog::new(Vec::new(), Vec::new()));
        let service = Arc::new(SettingsService::new(store, catalog));
        let backup = Arc::new(FakeBackupService::default());
        let controller = SettingsController::with_backup(service, backup.clone())
            .expect("settings window with backup");
        (controller, backup)
    }

    fn fill_backup_form(window: &SettingsWindow) {
        window.invoke_backup_endpoint_changed("https://s3.example.com".into());
        window.invoke_backup_region_changed("us-east-1".into());
        window.invoke_backup_bucket_changed("chatui-backups".into());
        window.invoke_backup_access_key_changed("AKIAEXAMPLE".into());
        window.invoke_backup_secret_key_changed("super-secret".into());
        window.invoke_backup_passphrase_changed("correct horse battery staple".into());
    }

    #[test]
    fn backup_callbacks_update_the_form_and_call_the_service() {
        let (controller, backup) = backup_controller();
        let window = controller.window();

        fill_backup_form(window);
        {
            let state = controller.backup_state();
            assert_eq!(state.form.endpoint, "https://s3.example.com");
            assert_eq!(state.form.bucket, "chatui-backups");
            assert_eq!(state.form.passphrase, "correct horse battery staple");
        }

        window.invoke_backup_test_connection();
        assert_eq!(backup.calls(), vec!["test_connection"]);
        assert_eq!(window.get_backup_notice(), "Connection succeeded.");

        window.invoke_backup_enable();
        assert!(window.get_backup_enabled());
        assert_eq!(backup.calls(), vec!["test_connection", "enable"]);

        window.invoke_backup_now();
        assert_eq!(
            backup.calls(),
            vec![
                "test_connection",
                "enable",
                "backup_now:correct horse battery staple"
            ]
        );

        window.invoke_backup_disable();
        assert!(!window.get_backup_enabled());
    }

    #[test]
    fn backup_backend_specific_fields_update_the_form() {
        let (controller, _backup) = backup_controller();
        let window = controller.window();

        window.invoke_backup_backend_changed(1);
        window.invoke_backup_sas_token_changed("sv=2021-12-02&sig=abc".into());
        {
            let state = controller.backup_state();
            assert_eq!(state.form.backend, chatui_domain::BackupBackend::AzureBlob);
            assert_eq!(state.form.sas_token, "sv=2021-12-02&sig=abc");
        }

        window.invoke_backup_backend_changed(2);
        window.invoke_backup_service_account_json_changed("{\"client_email\":\"a\"}".into());
        assert_eq!(
            controller.backup_state().form.service_account_json,
            "{\"client_email\":\"a\"}"
        );

        window.invoke_backup_backend_changed(3);
        window.invoke_backup_access_token_changed("drive-token".into());
        window.invoke_backup_folder_id_changed("folder-1".into());
        {
            let state = controller.backup_state();
            assert_eq!(
                state.form.backend,
                chatui_domain::BackupBackend::GoogleDrive
            );
            assert_eq!(state.form.access_token, "drive-token");
            assert_eq!(state.form.folder_id, "folder-1");
        }

        window.invoke_backup_backend_changed(4);
        window.invoke_backup_username_changed("user".into());
        window.invoke_backup_password_changed("pass".into());
        {
            let state = controller.backup_state();
            assert_eq!(state.form.backend, chatui_domain::BackupBackend::Webdav);
            assert_eq!(state.form.username, "user");
            assert_eq!(state.form.password, "pass");
        }
    }

    #[test]
    fn backup_restore_and_delete_require_typed_confirmation() {
        let (controller, backup) = backup_controller();
        let window = controller.window();
        fill_backup_form(window);

        window.invoke_backup_restore();
        assert_eq!(
            backup.calls(),
            vec!["restore:correct horse battery staple:false"]
        );

        window.invoke_backup_restore_replace();
        assert!(window.get_backup_confirmation_visible());
        assert!(
            window
                .get_backup_confirmation_label()
                .contains("REPLACE LOCAL DATA")
        );
        assert_eq!(
            backup.calls(),
            vec!["restore:correct horse battery staple:false"]
        );

        window.set_backup_confirmation_input("wrong".into());
        window.invoke_backup_confirm();
        assert_eq!(
            backup.calls(),
            vec!["restore:correct horse battery staple:false"]
        );

        window.set_backup_confirmation_input("REPLACE LOCAL DATA".into());
        window.invoke_backup_confirm();
        assert_eq!(
            backup.calls(),
            vec![
                "restore:correct horse battery staple:false",
                "restore:correct horse battery staple:true"
            ]
        );
        assert!(!window.get_backup_confirmation_visible());

        window.invoke_backup_delete_remote();
        assert!(window.get_backup_confirmation_visible());
        window.set_backup_confirmation_input("DELETE REMOTE BACKUP".into());
        window.invoke_backup_confirm();
        assert_eq!(
            backup.calls().last().map(String::as_str),
            Some("delete_remote:correct horse battery staple:DELETE REMOTE BACKUP")
        );
    }

    #[test]
    fn window_renders_every_section_and_defaults() {
        let controller = controller();
        let window = controller.window();

        assert_eq!(window.get_sections().row_count(), 7);
        assert_eq!(window.get_active_section(), 0);
        assert_eq!(window.get_theme_index(), 0);
        assert_eq!(window.get_density_index(), 0);
        assert_eq!(window.get_font_size_index(), 1);
        assert_eq!(window.get_send_key_index(), 0);
        assert!(window.get_show_token_usage());
        assert!(!window.get_load_remote_images());
        assert_eq!(window.get_providers().row_count(), 3);
        assert_eq!(window.get_models().row_count(), 1);
        assert_eq!(window.get_default_provider_index(), 0);
        assert_eq!(window.get_default_model_index(), 0);
        assert_eq!(window.get_onboarding_status(), "Onboarding not complete");
        assert!(!window.get_reduced_motion_text().is_empty());
        assert!(!window.get_remember_keys_text().is_empty());
    }

    #[test]
    fn appearance_callbacks_persist_and_update_the_window() {
        let controller = controller();
        let window = controller.window();

        window.invoke_theme_changed(2);
        window.invoke_density_changed(1);
        window.invoke_font_size_changed(2);

        let state = controller.state();
        assert_eq!(state.settings.theme, Theme::Dark);
        assert_eq!(state.settings.density, Density::Compact);
        assert_eq!(state.settings.chat_font_size, ChatFontSize::Lg);
        assert_eq!(window.get_theme_index(), 2);
        assert_eq!(window.get_density_index(), 1);
        assert_eq!(window.get_font_size_index(), 2);
    }

    #[test]
    fn chat_behavior_callbacks_persist_and_update_the_window() {
        let controller = controller();
        let window = controller.window();

        window.invoke_send_key_changed(1);
        window.invoke_show_token_usage_changed(false);
        window.invoke_load_remote_images_changed(true);

        let state = controller.state();
        assert_eq!(state.settings.send_key, SendKey::ModEnter);
        assert!(!state.settings.show_token_usage);
        assert!(state.settings.load_remote_images);
        assert_eq!(window.get_send_key_index(), 1);
        assert!(!window.get_show_token_usage());
        assert!(window.get_load_remote_images());
    }

    #[test]
    fn prompt_and_parameter_callbacks_persist() {
        let controller = controller();
        let window = controller.window();

        window.invoke_default_system_prompt_changed("Be concise.".into());
        window.invoke_temperature_changed("0.2".into());
        window.invoke_top_p_changed("0.9".into());
        window.invoke_max_output_tokens_changed("256".into());
        window.invoke_reasoning_changed(3);
        window.invoke_stop_sequences_changed("END\nSTOP\n".into());
        window.invoke_streaming_changed(false);

        let state = controller.state();
        assert_eq!(state.settings.default_system_prompt, "Be concise.");
        assert_eq!(state.settings.default_params.temperature, Some(0.2));
        assert_eq!(state.settings.default_params.top_p, Some(0.9));
        assert_eq!(state.settings.default_params.max_output_tokens, Some(256));
        assert_eq!(
            state.settings.default_params.reasoning_effort,
            Some(ReasoningEffort::High)
        );
        assert_eq!(
            state.settings.default_params.stop,
            vec!["END".to_owned(), "STOP".to_owned()]
        );
        assert!(!state.settings.default_params.streaming);
    }

    #[test]
    fn invalid_numeric_input_shows_an_error_without_saving() {
        let controller = controller();
        let window = controller.window();

        window.invoke_temperature_changed("not-a-number".into());

        let state = controller.state();
        assert_eq!(state.settings.default_params.temperature, None);
        assert_eq!(
            state.error.as_ref().map(|error| error.title.as_str()),
            Some("Check the setting")
        );
        assert_eq!(window.get_error_title(), "Check the setting");
    }

    #[test]
    fn provider_and_model_callbacks_persist_and_reload_models() {
        let controller = controller();
        let window = controller.window();

        window.invoke_default_provider_changed(1);
        {
            let state = controller.state();
            assert_eq!(state.settings.default_provider_id.as_deref(), Some("p1"));
            assert_eq!(state.settings.default_model_id, None);
        }
        assert_eq!(window.get_models().row_count(), 2);

        window.invoke_default_model_changed(1);
        {
            let state = controller.state();
            assert_eq!(state.settings.default_provider_id.as_deref(), Some("p1"));
            assert_eq!(state.settings.default_model_id.as_deref(), Some("m1"));
        }
        assert_eq!(window.get_default_model_index(), 1);
    }

    #[test]
    fn section_and_entry_point_callbacks_navigate() {
        let controller = controller();
        let window = controller.window();

        window.invoke_select_section(4);
        assert_eq!(controller.state().route.route(), SettingsRoute::Data);
        assert_eq!(window.get_active_section(), 4);

        window.invoke_open_providers();
        assert_eq!(controller.state().route.route(), SettingsRoute::Providers);
        assert_eq!(window.get_active_section(), 2);

        window.invoke_open_models();
        assert_eq!(controller.state().route.route(), SettingsRoute::Models);
        assert_eq!(window.get_active_section(), 3);

        window.invoke_open_data();
        assert_eq!(controller.state().route.route(), SettingsRoute::Data);
        assert_eq!(window.get_active_section(), 4);

        controller.select_route(SettingsRoute::About);
        assert_eq!(controller.state().route.route(), SettingsRoute::About);
        assert_eq!(window.get_active_section(), 6);
    }

    #[test]
    fn entry_point_handlers_are_invoked() {
        let mut controller = controller();
        let calls = Rc::new(RefCell::new(Vec::new()));

        let providers = Rc::clone(&calls);
        controller.set_on_open_providers(move || providers.borrow_mut().push("providers"));
        let models = Rc::clone(&calls);
        controller.set_on_open_models(move || models.borrow_mut().push("models"));
        let data = Rc::clone(&calls);
        controller.set_on_open_data(move || data.borrow_mut().push("data"));

        let window = controller.window();
        window.invoke_open_providers();
        window.invoke_open_models();
        window.invoke_open_data();

        assert_eq!(calls.borrow().as_slice(), &["providers", "models", "data"]);
    }

    #[test]
    fn window_renders_about_and_backup_copy() {
        let controller = controller();
        let window = controller.window();

        window.invoke_select_section(5);
        assert_eq!(window.get_backup_title(), "Backup");
        assert!(!window.get_backup_statement().is_empty());
        assert!(!window.get_backup_passphrase_warning().is_empty());
        assert_eq!(window.get_backup_status_last(), "Never");
        assert_eq!(window.get_backup_status_pending(), "0 pending changes");
        assert_eq!(window.get_backup_status_error(), "None");
        assert!(!window.get_backup_confirmation_visible());

        window.invoke_select_section(6);
        assert_eq!(window.get_about_title(), "About ChatUI");
        assert!(window.get_about_version().starts_with("Version "));
        assert!(window.get_privacy_lines().row_count() >= 5);
        assert_eq!(window.get_docs_url(), "https://github.com/rkodev/chatui");
    }

    #[test]
    fn controller_applies_settings_to_consumers() {
        let controller = controller();
        let window = controller.window();

        window.invoke_theme_changed(2);
        window.invoke_send_key_changed(1);
        window.invoke_show_token_usage_changed(false);
        window.invoke_load_remote_images_changed(true);

        let mut shell = ShellState::new(1024.0);
        controller.apply_to_shell(&mut shell);
        assert_eq!(shell.resolved_theme(), crate::theme::ResolvedTheme::Dark);

        let mut composer = ComposerState::with_defaults("c1", SendKey::Enter);
        controller.apply_to_composer(&mut composer);
        assert_eq!(composer.send_key(), SendKey::ModEnter);

        let mut list = MessageListState::new("c1", Default::default());
        controller.apply_to_message_list(&mut list);
        assert!(!list.show_token_usage());

        assert!(controller.render_options().load_remote_images);
    }
}
