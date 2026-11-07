//! Chat composer state, send-key policy, drafts, attachments, and chips.
//!
//! [`ComposerState`] is framework-independent. It owns the draft text, the
//! send-key mode, streaming/offline/sending flags, pending attachments, and the
//! selected model's capabilities. [`ComposerController`] binds that state to the
//! Slint [`ComposerWindow`].
//!
//! # Send-key policy
//!
//! | Mode | Enter | Shift+Enter | Ctrl/Cmd+Enter |
//! | --- | --- | --- | --- |
//! | `enter` | Send | Newline | Send |
//! | `mod-enter` | Newline | Newline | Send |
//!
//! # Failure safety
//!
//! [`ComposerState::begin_send`] snapshots the text and attachments, then clears
//! them. If the send fails or is cancelled, [`ComposerState::restore_after_failure`]
//! restores the snapshot and the draft. A successful send leaves the composer
//! empty.
//!
//! # Auto-grow
//!
//! The editor grows from one to [`MAX_COMPOSER_LINES`] lines and then scrolls.
//! [`ComposerState::visible_lines`] and [`ComposerState::scrolls`] expose the
//! policy to the view.

use std::cell::RefCell;
use std::rc::Rc;

use chatui_domain::{AttachmentKind, ContentPart, InputModality, Model, ModelCapability, SendKey};
use slint::{ComponentHandle, ModelRc, SharedString, VecModel};

use crate::accessibility::{Key, Modifiers};
use crate::chat::attachments::{
    AttachmentFileInfo, AttachmentValidationError, PendingAttachment, ValidatedAttachmentFile,
    format_attachment_size, validate_attachment_file,
};
use crate::chat::drafts::{DraftStore, MemoryDraftStore};

/// Maximum visible editor lines before the composer scrolls.
pub const MAX_COMPOSER_LINES: usize = 8;
/// Default editor line height in logical pixels.
pub const DEFAULT_COMPOSER_LINE_HEIGHT: f32 = 20.0;

/// The action produced by a composer key press.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum ComposerKeyAction {
    /// Send the current message.
    Send,
    /// Insert a newline.
    Newline,
    /// The key is not a composer shortcut.
    Ignore,
}

/// The selected model's attachment capabilities.
#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct ModelCapabilities {
    /// Accepted input modalities.
    pub input_modalities: Vec<InputModality>,
    /// Capability flags.
    pub capabilities: Vec<ModelCapability>,
}

impl ModelCapabilities {
    /// Read capabilities from a model record.
    pub fn from_model(model: &Model) -> Self {
        Self {
            input_modalities: model.input_modalities.clone(),
            capabilities: model.capabilities.clone(),
        }
    }

    /// Whether the model accepts image input.
    pub fn accepts_images(&self) -> bool {
        self.input_modalities.contains(&InputModality::Image)
            || self.capabilities.contains(&ModelCapability::Vision)
    }

    /// Whether the model accepts file input.
    pub fn accepts_files(&self) -> bool {
        self.input_modalities.contains(&InputModality::File)
            || self.capabilities.contains(&ModelCapability::Files)
    }
}

/// A composer attachment chip.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct AttachmentChip {
    /// Attachment ID.
    pub id: String,
    /// Display name.
    pub name: String,
    /// Size in bytes.
    pub size: u64,
    /// Human-readable size.
    pub size_label: String,
    /// Attachment kind.
    pub kind: AttachmentKind,
}

/// A message snapshot taken when the user sends.
#[derive(Debug, Clone, PartialEq)]
pub struct ComposerSend {
    /// Conversation the message belongs to.
    pub conversation_id: String,
    /// Original draft text.
    pub text: String,
    /// Content parts for the user message.
    pub parts: Vec<ContentPart>,
    /// Pending attachments referenced by the content parts.
    pub attachments: Vec<PendingAttachment>,
}

/// Framework-independent composer state.
pub struct ComposerState {
    conversation_id: String,
    send_key: SendKey,
    text: String,
    streaming: bool,
    offline: bool,
    sending: bool,
    model: Option<ModelCapabilities>,
    attachments: Vec<PendingAttachment>,
    attachment_error: Option<String>,
    drafts: std::sync::Arc<dyn DraftStore>,
    max_lines: usize,
}

impl ComposerState {
    /// Create composer state for a conversation, restoring its draft.
    pub fn new(
        conversation_id: impl Into<String>,
        send_key: SendKey,
        drafts: std::sync::Arc<dyn DraftStore>,
    ) -> Self {
        let conversation_id = conversation_id.into();
        let text = drafts.draft(&conversation_id).unwrap_or_default();

        Self {
            conversation_id,
            send_key,
            text,
            streaming: false,
            offline: false,
            sending: false,
            model: None,
            attachments: Vec::new(),
            attachment_error: None,
            drafts,
            max_lines: MAX_COMPOSER_LINES,
        }
    }

    /// Create composer state with an in-memory draft store.
    pub fn with_defaults(conversation_id: impl Into<String>, send_key: SendKey) -> Self {
        Self::new(
            conversation_id,
            send_key,
            std::sync::Arc::new(MemoryDraftStore::new()),
        )
    }

    /// Owning conversation ID.
    pub fn conversation_id(&self) -> &str {
        &self.conversation_id
    }

    /// Current send-key mode.
    pub fn send_key(&self) -> SendKey {
        self.send_key
    }

    /// Current draft text.
    pub fn text(&self) -> &str {
        &self.text
    }

    /// Whether a response is streaming.
    pub fn is_streaming(&self) -> bool {
        self.streaming
    }

    /// Whether the client is offline.
    pub fn is_offline(&self) -> bool {
        self.offline
    }

    /// Whether a send is in flight.
    pub fn is_sending(&self) -> bool {
        self.sending
    }

    /// Pending attachments.
    pub fn attachments(&self) -> &[PendingAttachment] {
        &self.attachments
    }

    /// Current attachment error, if any.
    pub fn attachment_error(&self) -> Option<&str> {
        self.attachment_error.as_deref()
    }

    /// Selected model capabilities, if a model is selected.
    pub fn model_capabilities(&self) -> Option<&ModelCapabilities> {
        self.model.as_ref()
    }

    /// Maximum visible lines before scrolling.
    pub fn max_lines(&self) -> usize {
        self.max_lines
    }

    /// Change the send-key mode.
    pub fn set_send_key(&mut self, send_key: SendKey) {
        self.send_key = send_key;
    }

    /// Replace the draft text and update the draft store.
    pub fn set_text(&mut self, text: impl Into<String>) {
        self.text = text.into();
        self.drafts.set_draft(&self.conversation_id, &self.text);
    }

    /// Set the streaming flag.
    pub fn set_streaming(&mut self, streaming: bool) {
        self.streaming = streaming;
    }

    /// Set the offline flag.
    pub fn set_offline(&mut self, offline: bool) {
        self.offline = offline;
    }

    /// Set the sending flag.
    pub fn set_sending(&mut self, sending: bool) {
        self.sending = sending;
    }

    /// Set the selected model's capabilities.
    pub fn set_model(&mut self, model: Option<&Model>) {
        self.model = model.map(ModelCapabilities::from_model);
    }

    /// Set capabilities directly, for callers that already resolved them.
    pub fn set_capabilities(&mut self, capabilities: Option<ModelCapabilities>) {
        self.model = capabilities;
    }

    /// Show an attachment error.
    pub fn set_attachment_error(&mut self, message: impl Into<String>) {
        self.attachment_error = Some(message.into());
    }

    /// Clear the attachment error.
    pub fn clear_attachment_error(&mut self) {
        self.attachment_error = None;
    }

    /// Switch conversations, saving the current draft and restoring the next.
    pub fn set_conversation(&mut self, conversation_id: impl Into<String>) {
        let conversation_id = conversation_id.into();

        if conversation_id == self.conversation_id {
            return;
        }

        self.drafts.set_draft(&self.conversation_id, &self.text);
        self.conversation_id = conversation_id;
        self.text = self.drafts.draft(&self.conversation_id).unwrap_or_default();
        self.attachments.clear();
        self.attachment_error = None;
    }

    /// Number of logical lines in the draft.
    pub fn line_count(&self) -> usize {
        if self.text.is_empty() {
            1
        } else {
            self.text.split('\n').count()
        }
    }

    /// Number of visible editor lines.
    pub fn visible_lines(&self) -> usize {
        self.line_count().min(self.max_lines)
    }

    /// Whether the editor scrolls instead of growing.
    pub fn scrolls(&self) -> bool {
        self.line_count() > self.max_lines
    }

    /// Editor height for a line height.
    pub fn textarea_height(&self, line_height: f32) -> f32 {
        self.visible_lines() as f32 * line_height
    }

    /// Whether the send action is enabled.
    pub fn can_send(&self) -> bool {
        !self.offline
            && !self.streaming
            && !self.sending
            && (!self.text.trim().is_empty() || !self.attachments.is_empty())
    }

    /// Whether the send action is disabled.
    pub fn send_disabled(&self) -> bool {
        !self.can_send()
    }

    /// Resolve a key press against the send-key policy.
    pub fn handle_key(&self, key: Key, modifiers: Modifiers) -> ComposerKeyAction {
        if key != Key::Enter {
            return ComposerKeyAction::Ignore;
        }

        if modifiers.shift {
            return ComposerKeyAction::Newline;
        }

        let modifier = modifiers.control || modifiers.meta;
        let should_send = self.send_key == SendKey::Enter || modifier;

        if should_send && self.can_send() {
            ComposerKeyAction::Send
        } else {
            ComposerKeyAction::Newline
        }
    }

    /// Short hint describing the current send-key mode.
    pub fn send_key_hint(&self) -> &'static str {
        match self.send_key {
            SendKey::Enter => "Enter to send, Shift+Enter for a new line",
            SendKey::ModEnter => "Ctrl/Cmd+Enter to send, Enter for a new line",
        }
    }

    /// Add a stored attachment. Duplicate IDs are ignored.
    pub fn add_attachment(&mut self, attachment: PendingAttachment) {
        if !self
            .attachments
            .iter()
            .any(|existing| existing.id == attachment.id)
        {
            self.attachments.push(attachment);
        }
    }

    /// Remove an attachment by ID. Returns whether one was removed.
    pub fn remove_attachment(&mut self, id: &str) -> bool {
        let before = self.attachments.len();
        self.attachments.retain(|attachment| attachment.id != id);
        self.attachments.len() != before
    }

    /// Remove every pending attachment.
    pub fn clear_attachments(&mut self) {
        self.attachments.clear();
    }

    /// Attachment chips for the composer.
    pub fn chips(&self) -> Vec<AttachmentChip> {
        self.attachments
            .iter()
            .map(|attachment| AttachmentChip {
                id: attachment.id.clone(),
                name: attachment.name.clone(),
                size: attachment.size,
                size_label: format_attachment_size(attachment.size),
                kind: attachment.kind,
            })
            .collect()
    }

    /// Capability warning for the current attachments, if any.
    pub fn capability_warning(&self) -> Option<String> {
        let model = self.model.as_ref()?;
        let has_images = self
            .attachments
            .iter()
            .any(|attachment| attachment.kind == AttachmentKind::Image);
        let has_files = self
            .attachments
            .iter()
            .any(|attachment| attachment.kind == AttachmentKind::File);
        let mut warnings = Vec::new();

        if has_images && !model.accepts_images() {
            warnings.push(
                "The selected model cannot read images. Remove the image attachments or choose a vision model.",
            );
        }

        if has_files && !model.accepts_files() {
            warnings.push(
                "The selected model cannot read files. Remove the file attachments or choose a model that supports files.",
            );
        }

        if warnings.is_empty() {
            None
        } else {
            Some(warnings.join(" "))
        }
    }

    /// IDs of attachments the selected model cannot accept.
    pub fn unsupported_attachment_ids(&self) -> Vec<String> {
        let Some(model) = self.model.as_ref() else {
            return Vec::new();
        };

        self.attachments
            .iter()
            .filter(|attachment| match attachment.kind {
                AttachmentKind::Image => !model.accepts_images(),
                AttachmentKind::File => !model.accepts_files(),
            })
            .map(|attachment| attachment.id.clone())
            .collect()
    }

    /// Validate files from the picker, drag-and-drop, or clipboard.
    pub fn validate_files(
        &self,
        files: &[AttachmentFileInfo],
    ) -> Vec<Result<ValidatedAttachmentFile, AttachmentValidationError>> {
        files.iter().map(validate_attachment_file).collect()
    }

    /// Snapshot and clear the composer for sending.
    ///
    /// Returns `None` when the send action is disabled. The caller must call
    /// [`restore_after_failure`] if the send fails or is cancelled.
    ///
    /// [`restore_after_failure`]: ComposerState::restore_after_failure
    pub fn begin_send(&mut self) -> Option<ComposerSend> {
        if !self.can_send() {
            return None;
        }

        let text = self.text.clone();
        let attachments = self.attachments.clone();
        let parts = build_parts(&text, &attachments);

        self.text.clear();
        self.drafts.clear_draft(&self.conversation_id);
        self.attachments.clear();
        self.attachment_error = None;

        Some(ComposerSend {
            conversation_id: self.conversation_id.clone(),
            text,
            parts,
            attachments,
        })
    }

    /// Restore text and attachments after a failed or cancelled send.
    pub fn restore_after_failure(&mut self, send: ComposerSend) {
        if send.conversation_id != self.conversation_id {
            return;
        }

        self.text = send.text;
        self.drafts.set_draft(&self.conversation_id, &self.text);
        self.attachments = send.attachments;
    }

    /// Discard the draft and pending attachments.
    pub fn discard(&mut self) {
        self.text.clear();
        self.drafts.clear_draft(&self.conversation_id);
        self.attachments.clear();
        self.attachment_error = None;
    }
}

fn build_parts(text: &str, attachments: &[PendingAttachment]) -> Vec<ContentPart> {
    let mut parts = Vec::new();
    let trimmed = text.trim();

    if !trimmed.is_empty() {
        parts.push(ContentPart::Text {
            text: trimmed.to_owned(),
        });
    }

    for attachment in attachments {
        match attachment.kind {
            AttachmentKind::Image => parts.push(ContentPart::Image {
                attachment_id: attachment.id.clone(),
                mime_type: attachment.mime_type.clone(),
                alt: attachment.name.clone(),
            }),
            AttachmentKind::File => parts.push(ContentPart::File {
                attachment_id: attachment.id.clone(),
                mime_type: attachment.mime_type.clone(),
                name: attachment.name.clone(),
            }),
        }
    }

    parts
}

slint::slint! {
    import { Button, ScrollView, TextEdit } from "std-widgets.slint";

    export struct ComposerChip {
        id: string,
        name: string,
        size-label: string,
        kind: string,
    }

    export component ComposerWindow inherits Window {
        title: "ChatUI composer";
        width: 900px;
        height: 260px;

        in-out property <string> text: "";
        in property <bool> streaming: false;
        in property <bool> offline: false;
        in property <bool> sending: false;
        in property <bool> can-send: false;
        in property <int> visible-lines: 1;
        in property <int> content-lines: 1;
        in property <bool> scrolls: false;
        in property <length> editor-height: 20px;
        in property <length> editor-content-height: 20px;
        in property <[ComposerChip]> chips: [];
        in property <string> capability-warning: "";
        in property <string> attachment-error: "";
        in property <string> send-key-hint: "Enter to send";

        callback text-changed(string);
        callback send();
        callback stop();
        callback attach();
        callback paste-image();
        callback remove-attachment(string);

        VerticalLayout {
            padding: 12px;
            spacing: 8px;

            if (root.offline) : Text {
                text: "You are offline. Sending is disabled.";
                color: #b91c1c;
                wrap: word-wrap;
            }

            if (!root.capability-warning.is-empty) : Text {
                text: root.capability-warning;
                color: #b45309;
                wrap: word-wrap;
            }

            if (!root.attachment-error.is-empty) : Text {
                text: root.attachment-error;
                color: #b91c1c;
                wrap: word-wrap;
            }

            for chip in root.chips : Rectangle {
                height: 32px;
                background: #f3f4f6;
                border-radius: 8px;
                HorizontalLayout {
                    padding: 4px;
                    spacing: 8px;
                    Text { text: chip.name; vertical-alignment: center; }
                    Text { text: chip.size-label; color: #666666; vertical-alignment: center; }
                    Rectangle { horizontal-stretch: 1; }
                    Button { text: "Remove"; clicked => { root.remove-attachment(chip.id); } }
                }
            }

            ScrollView {
                height: root.editor-height;
                content-width: self.width;
                content-height: root.editor-content-height;

                TextEdit {
                    width: parent.width;
                    height: root.editor-content-height;
                    text <=> root.text;
                    edited(text) => { root.text-changed(text); }
                    wrap: word-wrap;
                }
            }

            HorizontalLayout {
                spacing: 8px;
                Button {
                    text: "Attach";
                    clicked => { root.attach(); }
                }
                Button {
                    text: "Paste image";
                    clicked => { root.paste-image(); }
                }
                Rectangle { horizontal-stretch: 1; }
                Text {
                    text: root.send-key-hint;
                    color: #666666;
                    vertical-alignment: center;
                }
                if (root.streaming || root.sending) : Button {
                    text: "Stop";
                    clicked => { root.stop(); }
                }
                if (!root.streaming && !root.sending) : Button {
                    text: "Send";
                    enabled: root.can-send;
                    clicked => { root.send(); }
                }
            }
        }
    }
}

fn slint_chip(chip: &AttachmentChip) -> ComposerChip {
    ComposerChip {
        id: SharedString::from(chip.id.clone()),
        name: SharedString::from(chip.name.clone()),
        size_label: SharedString::from(chip.size_label.clone()),
        kind: SharedString::from(match chip.kind {
            AttachmentKind::Image => "image",
            AttachmentKind::File => "file",
        }),
    }
}

/// Push the current state into a Slint window.
pub fn sync_window(window: &ComposerWindow, state: &ComposerState) {
    if window.get_text().as_str() != state.text() {
        window.set_text(state.text().into());
    }

    window.set_streaming(state.is_streaming());
    window.set_offline(state.is_offline());
    window.set_sending(state.is_sending());
    window.set_can_send(state.can_send());
    window.set_visible_lines(state.visible_lines() as i32);
    window.set_content_lines(state.line_count() as i32);
    window.set_scrolls(state.scrolls());
    window.set_editor_height(state.textarea_height(DEFAULT_COMPOSER_LINE_HEIGHT));
    window.set_editor_content_height(state.line_count() as f32 * DEFAULT_COMPOSER_LINE_HEIGHT);
    window.set_chips(ModelRc::new(VecModel::from(
        state.chips().iter().map(slint_chip).collect::<Vec<_>>(),
    )));
    window.set_capability_warning(state.capability_warning().unwrap_or_default().into());
    window.set_attachment_error(state.attachment_error().unwrap_or_default().into());
    window.set_send_key_hint(state.send_key_hint().into());
}

#[derive(Default)]
struct ComposerHandlers {
    send: Option<Rc<dyn Fn(ComposerSend)>>,
    stop: Option<Rc<dyn Fn()>>,
    attach: Option<Rc<dyn Fn()>>,
    paste_image: Option<Rc<dyn Fn()>>,
    remove_attachment: Option<Rc<dyn Fn(String)>>,
}

/// Owns the composer state and the Slint window that renders it.
pub struct ComposerController {
    state: Rc<RefCell<ComposerState>>,
    window: ComposerWindow,
    handlers: Rc<RefCell<ComposerHandlers>>,
}

impl ComposerController {
    /// Create the composer window and bind it to `state`.
    pub fn new(state: ComposerState) -> Result<Self, slint::PlatformError> {
        let window = ComposerWindow::new()?;
        let controller = Self {
            state: Rc::new(RefCell::new(state)),
            window,
            handlers: Rc::new(RefCell::new(ComposerHandlers::default())),
        };
        controller.wire();
        controller.sync();
        Ok(controller)
    }

    /// The underlying Slint window.
    pub fn window(&self) -> &ComposerWindow {
        &self.window
    }

    /// Borrow the current state.
    pub fn state(&self) -> std::cell::Ref<'_, ComposerState> {
        self.state.borrow()
    }

    /// Push the current state into the window.
    pub fn sync(&self) {
        sync_window(&self.window, &self.state.borrow());
    }

    /// Set the streaming flag and refresh the window.
    pub fn set_streaming(&self, streaming: bool) {
        self.state.borrow_mut().set_streaming(streaming);
        self.sync();
    }

    /// Set the offline flag and refresh the window.
    pub fn set_offline(&self, offline: bool) {
        self.state.borrow_mut().set_offline(offline);
        self.sync();
    }

    /// Set the sending flag and refresh the window.
    pub fn set_sending(&self, sending: bool) {
        self.state.borrow_mut().set_sending(sending);
        self.sync();
    }

    /// Set the selected model and refresh the window.
    pub fn set_model(&self, model: Option<&Model>) {
        self.state.borrow_mut().set_model(model);
        self.sync();
    }

    /// Set model capabilities directly and refresh the window.
    pub fn set_capabilities(&self, capabilities: Option<ModelCapabilities>) {
        self.state.borrow_mut().set_capabilities(capabilities);
        self.sync();
    }

    /// Add a stored attachment and refresh the window.
    pub fn add_attachment(&self, attachment: PendingAttachment) {
        self.state.borrow_mut().add_attachment(attachment);
        self.sync();
    }

    /// Remove an attachment and refresh the window.
    pub fn remove_attachment(&self, id: &str) -> bool {
        let removed = self.state.borrow_mut().remove_attachment(id);
        self.sync();
        removed
    }

    /// Show an attachment error and refresh the window.
    pub fn set_attachment_error(&self, message: impl Into<String>) {
        self.state.borrow_mut().set_attachment_error(message);
        self.sync();
    }

    /// Clear the attachment error and refresh the window.
    pub fn clear_attachment_error(&self) {
        self.state.borrow_mut().clear_attachment_error();
        self.sync();
    }

    /// Switch conversations and refresh the window.
    pub fn set_conversation(&self, conversation_id: impl Into<String>) {
        self.state.borrow_mut().set_conversation(conversation_id);
        self.sync();
    }

    /// Restore text and attachments after a failed or cancelled send.
    pub fn restore_after_failure(&self, send: ComposerSend) {
        self.state.borrow_mut().restore_after_failure(send);
        self.sync();
    }

    /// Discard the draft and pending attachments.
    pub fn discard(&self) {
        self.state.borrow_mut().discard();
        self.sync();
    }

    /// Register the send handler. It receives the snapshot to send.
    pub fn set_on_send(&mut self, handler: impl Fn(ComposerSend) + 'static) {
        self.handlers.borrow_mut().send = Some(Rc::new(handler));
    }

    /// Register the stop handler.
    pub fn set_on_stop(&mut self, handler: impl Fn() + 'static) {
        self.handlers.borrow_mut().stop = Some(Rc::new(handler));
    }

    /// Register the native file-picker handler.
    pub fn set_on_attach(&mut self, handler: impl Fn() + 'static) {
        self.handlers.borrow_mut().attach = Some(Rc::new(handler));
    }

    /// Register the clipboard-image handler.
    pub fn set_on_paste_image(&mut self, handler: impl Fn() + 'static) {
        self.handlers.borrow_mut().paste_image = Some(Rc::new(handler));
    }

    /// Register the attachment-removal handler.
    pub fn set_on_remove_attachment(&mut self, handler: impl Fn(String) + 'static) {
        self.handlers.borrow_mut().remove_attachment = Some(Rc::new(handler));
    }

    fn wire(&self) {
        let weak = self.window.as_weak();
        let state = Rc::clone(&self.state);
        self.window.on_text_changed(move |text| {
            state.borrow_mut().set_text(text.as_str());

            if let Some(window) = weak.upgrade() {
                sync_window(&window, &state.borrow());
            }
        });

        let weak = self.window.as_weak();
        let state = Rc::clone(&self.state);
        let handlers = Rc::clone(&self.handlers);
        self.window.on_send(move || {
            let send = state.borrow_mut().begin_send();

            if let Some(send) = send
                && let Some(handler) = handlers.borrow().send.clone()
            {
                handler(send);
            }

            if let Some(window) = weak.upgrade() {
                sync_window(&window, &state.borrow());
            }
        });

        let handlers = Rc::clone(&self.handlers);
        self.window.on_stop(move || {
            if let Some(handler) = handlers.borrow().stop.clone() {
                handler();
            }
        });

        let handlers = Rc::clone(&self.handlers);
        self.window.on_attach(move || {
            if let Some(handler) = handlers.borrow().attach.clone() {
                handler();
            }
        });

        let handlers = Rc::clone(&self.handlers);
        self.window.on_paste_image(move || {
            if let Some(handler) = handlers.borrow().paste_image.clone() {
                handler();
            }
        });

        let weak = self.window.as_weak();
        let state = Rc::clone(&self.state);
        let handlers = Rc::clone(&self.handlers);
        self.window.on_remove_attachment(move |id| {
            state.borrow_mut().remove_attachment(id.as_str());

            if let Some(handler) = handlers.borrow().remove_attachment.clone() {
                handler(id.to_string());
            }

            if let Some(window) = weak.upgrade() {
                sync_window(&window, &state.borrow());
            }
        });
    }
}
