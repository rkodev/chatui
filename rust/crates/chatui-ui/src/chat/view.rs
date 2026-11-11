//! Slint chat view and controller.
//!
//! [`ChatController`] composes the existing [`MessageListController`] and
//! [`ComposerController`] into one chat destination and binds them to
//! [`ChatService`]. Every storage, network, and streaming call runs on the
//! injected [`ChatRuntime`]; results are sent back through a channel and applied
//! by [`ChatController::poll`] on the Slint thread. The desktop calls `poll`
//! from a short timer, so no blocking work ever runs on the Slint thread.
//!
//! The live assistant row is read from [`MemoryStreamingStore`] on each poll.
//! Only the isolated streaming row and the scroll offset are updated while text
//! streams; completed history is reloaded once when the stream starts (to show
//! the user message) and once when it finishes (to replace the placeholder with
//! the persisted message).
//!
//! [`ChatWindow`] is a standalone host that stacks the message list and composer
//! through `ComponentContainer`. The shell embeds the two windows directly, so a
//! host must choose either the shell containers or [`ChatWindow`], never both.

use std::cell::{Cell, RefCell};
use std::future::Future;
use std::pin::Pin;
use std::rc::Rc;
use std::sync::Arc;
use std::sync::mpsc::{Receiver, Sender};

use chatui_domain::{Conversation, Model, SendKey};
use chatui_storage::{ConversationRepository, MessagePage, MessageRepository, ModelRepository};
use slint::ComponentHandle;

use crate::accessibility::{Announcement, LiveRegionPoliteness};
use crate::chat::attachments::{
    AttachmentFileInfo, AttachmentValidationError, PendingAttachment, ValidatedAttachmentFile,
};
use crate::chat::composer::{ComposerController, ComposerSend, ComposerState, ComposerWindow};
use crate::chat::drafts::DraftStore;
use crate::chat::message_list::{
    MessageListConfig, MessageListController, MessageListError, MessageListLoader,
    MessageListState, MessageListWindow,
};
use crate::chat::runner::ChatRunner;
use crate::chat::service::{ChatSendFailure, ChatSendResult, ChatService, describe_chat_error};
use crate::chat::state::{MemoryStreamingStore, StreamingState, StreamingStore};

slint::slint! {
    import { Palette } from "std-widgets.slint";

    export component ChatWindow inherits Window {
        title: "ChatUI chat";
        width: 900px;
        height: 720px;

        in property <component-factory> messages-factory;
        in property <component-factory> composer-factory;
        in property <bool> loading: false;
        in property <bool> empty: false;
        in property <string> error: "";
        in property <bool> streaming: false;
        in property <string> announcement: "";
        in property <bool> announcement-assertive: false;
        in property <bool> dark: false;

        background: root.dark ? #0f1115 : #ffffff;
        init => {
            Palette.color-scheme = root.dark ? ColorScheme.dark : ColorScheme.light;
        }
        changed dark => {
            Palette.color-scheme = root.dark ? ColorScheme.dark : ColorScheme.light;
        }

        VerticalLayout {
            spacing: 8px;

            if (root.loading) : Text {
                text: "Loading conversation…";
                color: root.dark ? #9ca3af : #5f6368;
                accessible-live-region: polite;
            }

            if (!root.error.is-empty) : Text {
                text: root.error;
                color: root.dark ? #f87171 : #b91c1c;
                wrap: word-wrap;
                accessible-live-region: assertive;
            }

            ComponentContainer {
                vertical-stretch: 1;
                component-factory: root.messages-factory;
            }

            ComponentContainer {
                height: 160px;
                component-factory: root.composer-factory;
            }
        }
    }
}

/// Spawns async chat work off the Slint thread.
///
/// The desktop implements this over its shared `tokio` runtime. Tests implement
/// it over a private runtime and drive [`ChatController::poll`] directly.
pub trait ChatRuntime: Send + Sync {
    /// Spawn a future on the runtime.
    fn spawn(&self, future: Pin<Box<dyn Future<Output = ()> + Send + 'static>>);
}

/// Slint-facing chat state.
#[derive(Debug, Clone, PartialEq)]
pub struct ChatViewState {
    /// Conversation currently open in the view.
    pub conversation_id: Option<String>,
    /// Conversation metadata used for streaming rows.
    pub conversation: Option<Conversation>,
    /// Whether the newest page is loading.
    pub loading: bool,
    /// Current list-level error.
    pub error: Option<MessageListError>,
    /// Whether a send, regenerate, or retry is in flight.
    pub sending: bool,
    /// Whether an assistant response is streaming.
    pub streaming: bool,
    /// Whether the conversation has no messages and no active stream.
    pub empty: bool,
    /// Latest bounded live-region announcement.
    pub announcement: Option<Announcement>,
    /// Whether the announcement is assertive.
    pub announcement_assertive: bool,
}

impl ChatViewState {
    /// Create the initial view state.
    pub fn new(conversation_id: Option<String>) -> Self {
        Self {
            conversation_id,
            conversation: None,
            loading: false,
            error: None,
            sending: false,
            streaming: false,
            empty: true,
            announcement: None,
            announcement_assertive: false,
        }
    }
}

/// Push the current chat state into a standalone [`ChatWindow`].
pub fn sync_window(window: &ChatWindow, view: &ChatViewState) {
    window.set_loading(view.loading);
    window.set_empty(view.empty);
    window.set_error(
        view.error
            .as_ref()
            .map(|error| error.message.clone())
            .unwrap_or_default()
            .into(),
    );
    window.set_streaming(view.streaming);
    window.set_announcement(
        view.announcement
            .as_ref()
            .map(|announcement| announcement.text.clone())
            .unwrap_or_default()
            .into(),
    );
    window.set_announcement_assertive(view.announcement_assertive);
}

/// Dependencies for [`ChatController::new`].
pub struct ChatControllerDeps {
    /// Chat orchestration service.
    pub service: ChatService,
    /// Runner shared with the service.
    pub runner: Arc<ChatRunner>,
    /// Streaming store shared with the runner.
    pub store: Arc<MemoryStreamingStore>,
    /// Conversation repository used to load metadata.
    pub conversations: Arc<dyn ConversationRepository>,
    /// Message repository used to load pages.
    pub messages: Arc<dyn MessageRepository>,
    /// Model repository used to load composer capabilities.
    pub models: Arc<dyn ModelRepository>,
    /// Draft store used by the composer.
    pub drafts: Arc<dyn DraftStore>,
    /// Send-key policy.
    pub send_key: SendKey,
    /// Runtime used for every async operation.
    pub runtime: Arc<dyn ChatRuntime>,
    /// Message list configuration.
    pub config: MessageListConfig,
    /// Conversation to open immediately, if any.
    pub initial_conversation_id: Option<String>,
}

#[derive(Default)]
struct ChatHandlers {
    copy: Option<Rc<dyn Fn(String)>>,
    attach: Option<Rc<dyn Fn()>>,
    paste_image: Option<Rc<dyn Fn()>>,
    remove_attachment: Option<Rc<dyn Fn(String)>>,
}

enum ChatEvent {
    Loaded {
        conversation: Box<Conversation>,
        page: MessagePage,
        model: Option<Model>,
        clear_streaming: bool,
    },
    LoadFailed {
        error: MessageListError,
        clear_streaming: bool,
    },
    OlderLoaded {
        page: MessagePage,
    },
    OlderFailed {
        error: MessageListError,
    },
    SendFinished {
        result: ChatSendResult,
        restore: Option<ComposerSend>,
    },
    StopFinished,
}

enum StreamPoll {
    Unchanged,
    Changed(Box<StreamingState>),
    Cleared,
}

struct ChatShared {
    service: Arc<ChatService>,
    runner: Arc<ChatRunner>,
    store: Arc<MemoryStreamingStore>,
    conversations: Arc<dyn ConversationRepository>,
    models: Arc<dyn ModelRepository>,
    loader: Arc<MessageListLoader>,
    runtime: Arc<dyn ChatRuntime>,
    sender: Sender<ChatEvent>,
    state: RefCell<ChatViewState>,
    conversation_id: RefCell<Option<String>>,
    handlers: RefCell<ChatHandlers>,
    last_stream: RefCell<Option<StreamingState>>,
    stream_message_id: RefCell<Option<String>>,
    terminal_reload_requested: Cell<bool>,
}

impl ChatShared {
    fn reload_newest(&self, clear_streaming: bool) {
        let Some(conversation_id) = self.conversation_id.borrow().clone() else {
            return;
        };

        if clear_streaming {
            if self.terminal_reload_requested.get() {
                return;
            }

            self.terminal_reload_requested.set(true);
        }

        let loader = Arc::clone(&self.loader);
        let conversations = Arc::clone(&self.conversations);
        let models = Arc::clone(&self.models);
        let runtime = Arc::clone(&self.runtime);
        let sender = self.sender.clone();

        runtime.spawn(Box::pin(async move {
            let conversation = conversations.get(&conversation_id).await.ok().flatten();
            let model = match &conversation {
                Some(conversation) => models.get(&conversation.model_id).await.ok().flatten(),
                None => None,
            };

            match loader.load_newest(&conversation_id).await {
                Ok(page) => match conversation {
                    Some(conversation) => {
                        let _ = sender.send(ChatEvent::Loaded {
                            conversation: Box::new(conversation),
                            page,
                            model,
                            clear_streaming,
                        });
                    }
                    None => {
                        let _ = sender.send(ChatEvent::LoadFailed {
                            error: MessageListError::new("The conversation was not found."),
                            clear_streaming,
                        });
                    }
                },
                Err(error) => {
                    let _ = sender.send(ChatEvent::LoadFailed {
                        error: MessageListError::from_storage(&error),
                        clear_streaming,
                    });
                }
            }
        }));
    }

    fn start_send(&self, send: ComposerSend) {
        let Some(conversation_id) = self.conversation_id.borrow().clone() else {
            return;
        };

        if conversation_id != send.conversation_id {
            return;
        }

        self.state.borrow_mut().sending = true;

        let service = Arc::clone(&self.service);
        let runtime = Arc::clone(&self.runtime);
        let sender = self.sender.clone();

        runtime.spawn(Box::pin(async move {
            let parts = send.parts.clone();
            let result = service.send(&conversation_id, parts).await;
            let _ = sender.send(ChatEvent::SendFinished {
                result,
                restore: Some(send),
            });
        }));
    }

    fn stop(&self) {
        let Some(conversation_id) = self.conversation_id.borrow().clone() else {
            return;
        };

        let service = Arc::clone(&self.service);
        let runtime = Arc::clone(&self.runtime);
        let sender = self.sender.clone();

        runtime.spawn(Box::pin(async move {
            let _ = service.cancel(&conversation_id).await;
            let _ = sender.send(ChatEvent::StopFinished);
        }));
    }

    fn regenerate(&self, target_id: String) {
        let Some(conversation_id) = self.conversation_id.borrow().clone() else {
            return;
        };

        self.state.borrow_mut().sending = true;

        let service = Arc::clone(&self.service);
        let runtime = Arc::clone(&self.runtime);
        let sender = self.sender.clone();

        runtime.spawn(Box::pin(async move {
            let result = service.regenerate(&conversation_id, &target_id).await;
            let _ = sender.send(ChatEvent::SendFinished {
                result,
                restore: None,
            });
        }));
    }

    fn retry(&self, target_id: String) {
        let Some(conversation_id) = self.conversation_id.borrow().clone() else {
            return;
        };

        self.state.borrow_mut().sending = true;

        let service = Arc::clone(&self.service);
        let runtime = Arc::clone(&self.runtime);
        let sender = self.sender.clone();

        runtime.spawn(Box::pin(async move {
            let result = service.retry(&conversation_id, &target_id).await;
            let _ = sender.send(ChatEvent::SendFinished {
                result,
                restore: None,
            });
        }));
    }

    fn load_older(&self, cursor: u64) {
        let Some(conversation_id) = self.conversation_id.borrow().clone() else {
            return;
        };

        let loader = Arc::clone(&self.loader);
        let runtime = Arc::clone(&self.runtime);
        let sender = self.sender.clone();

        runtime.spawn(Box::pin(async move {
            match loader.load_older(&conversation_id, cursor).await {
                Ok(page) => {
                    let _ = sender.send(ChatEvent::OlderLoaded { page });
                }
                Err(error) => {
                    let _ = sender.send(ChatEvent::OlderFailed {
                        error: MessageListError::from_storage(&error),
                    });
                }
            }
        }));
    }

    fn poll_stream(&self) -> StreamPoll {
        let Some(conversation_id) = self.conversation_id.borrow().clone() else {
            return StreamPoll::Unchanged;
        };

        let Some(stream) = self.store.stream(&conversation_id) else {
            let had = self.stream_message_id.borrow_mut().take().is_some();
            self.last_stream.borrow_mut().take();
            self.terminal_reload_requested.set(false);

            return if had {
                StreamPoll::Cleared
            } else {
                StreamPoll::Unchanged
            };
        };

        let is_new = self.stream_message_id.borrow().as_deref() != Some(stream.message_id.as_str());

        if is_new {
            *self.stream_message_id.borrow_mut() = Some(stream.message_id.clone());
            self.terminal_reload_requested.set(false);
            self.reload_newest(false);
        }

        if stream.done && !self.terminal_reload_requested.get() {
            self.reload_newest(true);
        }

        let changed = self.last_stream.borrow().as_ref() != Some(&stream);

        if changed {
            *self.last_stream.borrow_mut() = Some(stream.clone());
            StreamPoll::Changed(Box::new(stream))
        } else {
            StreamPoll::Unchanged
        }
    }
}

fn message_list_factory(window: &MessageListWindow) -> slint::ComponentFactory {
    let handle = window.clone_strong();
    slint::ComponentFactory::new(move |_context| Some(handle.clone_strong()))
}

fn composer_factory(window: &ComposerWindow) -> slint::ComponentFactory {
    let handle = window.clone_strong();
    slint::ComponentFactory::new(move |_context| Some(handle.clone_strong()))
}

fn wire_message_list(shared: &Rc<ChatShared>, message_list: &Rc<RefCell<MessageListController>>) {
    let shared_copy = Rc::clone(shared);
    message_list.borrow_mut().set_on_copy(move |text| {
        if let Some(handler) = shared_copy.handlers.borrow().copy.clone() {
            handler(text);
        }
    });

    let shared_older = Rc::clone(shared);
    message_list.borrow_mut().set_on_load_older(move |cursor| {
        shared_older.load_older(cursor);
    });

    let shared_retry = Rc::clone(shared);
    message_list.borrow_mut().set_on_retry(move || {
        shared_retry.reload_newest(false);
    });

    let shared_retry_message = Rc::clone(shared);
    message_list.borrow_mut().set_on_retry_message(move |id| {
        shared_retry_message.retry(id);
    });

    let shared_regenerate = Rc::clone(shared);
    message_list.borrow_mut().set_on_regenerate(move |id| {
        shared_regenerate.regenerate(id);
    });
}

fn wire_composer(shared: &Rc<ChatShared>, composer: &Rc<RefCell<ComposerController>>) {
    let shared_send = Rc::clone(shared);
    let composer_weak = Rc::downgrade(composer);
    composer.borrow_mut().set_on_send(move |send| {
        if let Some(composer) = composer_weak.upgrade() {
            composer.borrow().set_sending(true);
        }

        shared_send.state.borrow_mut().sending = true;
        shared_send.start_send(send);
    });

    let shared_stop = Rc::clone(shared);
    composer.borrow_mut().set_on_stop(move || {
        shared_stop.stop();
    });

    let shared_attach = Rc::clone(shared);
    composer.borrow_mut().set_on_attach(move || {
        if let Some(handler) = shared_attach.handlers.borrow().attach.clone() {
            handler();
        }
    });

    let shared_paste = Rc::clone(shared);
    composer.borrow_mut().set_on_paste_image(move || {
        if let Some(handler) = shared_paste.handlers.borrow().paste_image.clone() {
            handler();
        }
    });

    let shared_remove = Rc::clone(shared);
    composer.borrow_mut().set_on_remove_attachment(move |id| {
        if let Some(handler) = shared_remove.handlers.borrow().remove_attachment.clone() {
            handler(id);
        }
    });
}

fn send_failure_error(failure: &ChatSendFailure) -> MessageListError {
    if let Some(error) = &failure.error {
        let copy = describe_chat_error(error);
        MessageListError {
            title: copy.title,
            message: copy.message,
            details: copy.details,
        }
    } else {
        MessageListError {
            title: "Could not send message".to_owned(),
            message: failure.message.clone(),
            details: None,
        }
    }
}

/// Owns the chat service, runner, streaming store, and the two feature
/// controllers.
pub struct ChatController {
    shared: Rc<ChatShared>,
    message_list: Rc<RefCell<MessageListController>>,
    composer: Rc<RefCell<ComposerController>>,
    receiver: RefCell<Receiver<ChatEvent>>,
    dark: Cell<bool>,
}

impl ChatController {
    /// Create the chat controller and, when given, open the initial
    /// conversation.
    pub fn new(deps: ChatControllerDeps) -> Result<Self, slint::PlatformError> {
        let ChatControllerDeps {
            service,
            runner,
            store,
            conversations,
            messages,
            models,
            drafts,
            send_key,
            runtime,
            config,
            initial_conversation_id,
        } = deps;

        let initial_id = initial_conversation_id.clone().unwrap_or_default();
        let message_list = Rc::new(RefCell::new(MessageListController::new(
            MessageListState::new(initial_id.clone(), config),
        )?));
        let composer = Rc::new(RefCell::new(ComposerController::new(ComposerState::new(
            initial_id, send_key, drafts,
        ))?));
        let (sender, receiver) = std::sync::mpsc::channel();
        let shared = Rc::new(ChatShared {
            service: Arc::new(service),
            runner,
            store,
            conversations,
            models,
            loader: Arc::new(MessageListLoader::new(messages, config.page_size)),
            runtime,
            sender,
            state: RefCell::new(ChatViewState::new(initial_conversation_id.clone())),
            conversation_id: RefCell::new(initial_conversation_id.clone()),
            handlers: RefCell::new(ChatHandlers::default()),
            last_stream: RefCell::new(None),
            stream_message_id: RefCell::new(None),
            terminal_reload_requested: Cell::new(false),
        });

        wire_message_list(&shared, &message_list);
        wire_composer(&shared, &composer);

        let controller = Self {
            shared,
            message_list,
            composer,
            receiver: RefCell::new(receiver),
            dark: Cell::new(false),
        };

        if let Some(conversation_id) = initial_conversation_id {
            controller.open(conversation_id);
        } else {
            controller.sync();
        }

        Ok(controller)
    }

    /// The message list window for the shell `chat-messages-factory`.
    pub fn messages_window(&self) -> MessageListWindow {
        self.message_list.borrow().window().clone_strong()
    }

    /// The composer window for the shell `chat-composer-factory`.
    pub fn composer_window(&self) -> ComposerWindow {
        self.composer.borrow().window().clone_strong()
    }

    /// Show or hide token usage in the message list metadata.
    pub fn set_show_token_usage(&self, show: bool) {
        self.message_list.borrow().set_show_token_usage(show);
    }

    /// Apply the dark or light palette to the message list and composer.
    pub fn set_dark(&self, dark: bool) {
        self.dark.set(dark);
        self.message_list.borrow().window().set_dark(dark);
        self.composer.borrow().window().set_dark(dark);
    }

    /// Create a standalone host that stacks the message list and composer.
    ///
    /// A host must use either this window or the shell's two containers, never
    /// both, because both embed the same controller handles.
    pub fn standalone_window(&self) -> Result<ChatWindow, slint::PlatformError> {
        let window = ChatWindow::new()?;
        window.set_dark(self.dark.get());
        window.set_messages_factory(message_list_factory(self.message_list.borrow().window()));
        window.set_composer_factory(composer_factory(self.composer.borrow().window()));
        sync_window(&window, &self.state());
        Ok(window)
    }

    /// Borrow the current view state.
    pub fn state(&self) -> std::cell::Ref<'_, ChatViewState> {
        self.shared.state.borrow()
    }

    /// Current conversation ID.
    pub fn conversation_id(&self) -> Option<String> {
        self.shared.conversation_id.borrow().clone()
    }

    /// Whether a stream is active for the current conversation.
    pub fn is_streaming(&self) -> bool {
        self.shared
            .conversation_id
            .borrow()
            .as_deref()
            .is_some_and(|conversation_id| self.shared.runner.is_streaming(conversation_id))
    }

    /// Open a conversation, restore its draft, and load its newest page.
    pub fn open(&self, conversation_id: impl Into<String>) {
        let conversation_id = conversation_id.into();

        {
            let mut view = self.shared.state.borrow_mut();
            view.conversation_id = Some(conversation_id.clone());
            view.conversation = None;
            view.loading = true;
            view.error = None;
            view.sending = false;
            view.streaming = false;
            view.empty = false;
            view.announcement = None;
            view.announcement_assertive = false;
        }

        *self.shared.conversation_id.borrow_mut() = Some(conversation_id.clone());
        self.shared.stream_message_id.borrow_mut().take();
        self.shared.last_stream.borrow_mut().take();
        self.shared.terminal_reload_requested.set(false);
        self.composer.borrow().set_conversation(conversation_id);
        self.message_list.borrow().apply_newest_page(MessagePage {
            messages: Vec::new(),
            has_more: false,
        });
        self.message_list.borrow().set_loading_newest(true);
        self.shared.reload_newest(false);
        self.sync();
    }

    /// Push the current message-list summary into the view state.
    pub fn sync(&self) {
        let list = self.message_list.borrow();
        let list_state = list.state();
        let mut view = self.shared.state.borrow_mut();
        view.loading = list_state.is_loading();
        view.empty = list_state.is_empty();
        view.error = list_state.error().cloned();
        view.streaming = list_state.streaming().is_some_and(|state| !state.done);
        view.announcement = list_state.announcement().cloned();
        view.announcement_assertive = view
            .announcement
            .as_ref()
            .is_some_and(|announcement| announcement.politeness == LiveRegionPoliteness::Assertive);
    }

    /// Drain async results and update the live row.
    ///
    /// The desktop calls this from a short Slint timer (50-100 ms). Tests call
    /// it directly.
    pub fn poll(&self) {
        let mut changed = false;

        loop {
            let event = self.receiver.borrow().try_recv();

            match event {
                Ok(event) => {
                    changed |= self.apply_event(event);
                }
                Err(_) => break,
            }
        }

        match self.shared.poll_stream() {
            StreamPoll::Changed(stream) => {
                self.message_list.borrow().set_streaming(Some(*stream));
                changed = true;
            }
            StreamPoll::Cleared => {
                self.message_list.borrow().set_streaming(None);
                changed = true;
            }
            StreamPoll::Unchanged => {}
        }

        if changed {
            self.sync();
        }
    }

    /// Send the current composer contents through [`ChatService`].
    pub fn send(&self) {
        self.composer.borrow().window().invoke_send();
    }

    /// Stop the active stream.
    pub fn stop(&self) {
        self.shared.stop();
    }

    /// Regenerate the last assistant response.
    pub fn regenerate(&self, target_id: impl Into<String>) {
        self.shared.regenerate(target_id.into());
    }

    /// Retry a failed assistant response when the shared policy allows it.
    pub fn retry(&self, target_id: impl Into<String>) {
        self.shared.retry(target_id.into());
    }

    /// Load the page older than `cursor`.
    pub fn load_older(&self, cursor: u64) {
        self.shared.load_older(cursor);
    }

    /// Register the copy handler. It receives the exact message text.
    pub fn set_on_copy(&self, handler: impl Fn(String) + 'static) {
        self.shared.handlers.borrow_mut().copy = Some(Rc::new(handler));
    }

    /// Register the native file-picker handler.
    pub fn set_on_attach(&self, handler: impl Fn() + 'static) {
        self.shared.handlers.borrow_mut().attach = Some(Rc::new(handler));
    }

    /// Register the clipboard-image handler.
    pub fn set_on_paste_image(&self, handler: impl Fn() + 'static) {
        self.shared.handlers.borrow_mut().paste_image = Some(Rc::new(handler));
    }

    /// Register the attachment-removal handler.
    pub fn set_on_remove_attachment(&self, handler: impl Fn(String) + 'static) {
        self.shared.handlers.borrow_mut().remove_attachment = Some(Rc::new(handler));
    }

    /// Add a stored attachment to the composer.
    pub fn add_attachment(&self, attachment: PendingAttachment) {
        self.composer.borrow().add_attachment(attachment);
    }

    /// Remove an attachment from the composer.
    pub fn remove_attachment(&self, id: &str) -> bool {
        self.composer.borrow().remove_attachment(id)
    }

    /// Validate files from the picker, drag-and-drop, or clipboard.
    pub fn validate_files(
        &self,
        files: &[AttachmentFileInfo],
    ) -> Vec<Result<ValidatedAttachmentFile, AttachmentValidationError>> {
        self.composer.borrow().state().validate_files(files)
    }

    /// Show an attachment error in the composer.
    pub fn set_attachment_error(&self, message: impl Into<String>) {
        self.composer.borrow().set_attachment_error(message);
    }

    /// Set the selected model's capabilities on the composer.
    pub fn set_model(&self, model: Option<&Model>) {
        self.composer.borrow().set_model(model);
    }

    fn apply_event(&self, event: ChatEvent) -> bool {
        match event {
            ChatEvent::Loaded {
                conversation,
                page,
                model,
                clear_streaming,
            } => {
                let conversation = *conversation;
                {
                    let mut view = self.shared.state.borrow_mut();
                    view.conversation = Some(conversation.clone());
                    view.loading = false;
                    view.error = None;
                }

                if clear_streaming {
                    self.message_list.borrow().set_streaming(None);
                    self.shared.store.clear_stream(&conversation.id);
                    self.shared.stream_message_id.borrow_mut().take();
                    self.shared.last_stream.borrow_mut().take();
                    self.shared.terminal_reload_requested.set(false);
                }

                self.message_list
                    .borrow()
                    .set_conversation(Some(conversation.clone()));
                self.message_list.borrow().apply_newest_page(page);

                if let Some(model) = model {
                    self.composer.borrow().set_model(Some(&model));
                }

                true
            }
            ChatEvent::LoadFailed {
                error,
                clear_streaming,
            } => {
                {
                    let mut view = self.shared.state.borrow_mut();
                    view.loading = false;
                    view.error = Some(error.clone());
                }

                if clear_streaming {
                    self.message_list.borrow().set_streaming(None);

                    if let Some(conversation_id) = self.shared.conversation_id.borrow().clone() {
                        self.shared.store.clear_stream(&conversation_id);
                    }

                    self.shared.stream_message_id.borrow_mut().take();
                    self.shared.last_stream.borrow_mut().take();
                    self.shared.terminal_reload_requested.set(false);
                }

                self.message_list.borrow().set_error(error);

                true
            }
            ChatEvent::OlderLoaded { page } => {
                self.message_list.borrow().apply_older_page(page);
                true
            }
            ChatEvent::OlderFailed { error } => {
                self.message_list.borrow().set_error(error);
                true
            }
            ChatEvent::SendFinished { result, restore } => {
                self.shared.state.borrow_mut().sending = false;
                self.composer.borrow().set_sending(false);

                match result {
                    Ok(_) => {
                        self.shared.reload_newest(true);
                    }
                    Err(failure) => {
                        if let Some(send) = restore {
                            self.composer.borrow().restore_after_failure(send);
                        }

                        let error = send_failure_error(&failure);
                        self.shared.state.borrow_mut().error = Some(error.clone());
                        self.message_list.borrow().set_error(error);
                        self.shared.reload_newest(true);
                    }
                }

                true
            }
            ChatEvent::StopFinished => {
                self.shared.reload_newest(true);
                true
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use std::collections::BTreeMap;
    use std::sync::Mutex;
    use std::time::Duration;

    use async_trait::async_trait;
    use chatui_contracts::AppConfig;
    use chatui_domain::{
        AttachmentKind, BrowserCors, ChatError, ChatErrorCode, ChatParams, ContentPart,
        FinishReason, InputModality, Message, MessageRole, MessageStatus, ModelCapability,
        ModelSource, OutputModality, Provider, ProviderAuthType, ProviderKind, StreamEvent,
        Timestamp, Usage,
    };
    use chatui_providers::{
        CancellationToken, NormalizedChatRequest, PartialOutput, ProviderContext,
        ResolvedAttachment, StreamSummary,
    };
    use chatui_secrets::{SecretError, SecretId, SecretStore, SecretValue};
    use chatui_storage::{
        ConversationRepository, MessageRepository, ModelRepository, ProviderRepository,
        SqliteConversationRepository, SqliteMessageRepository, SqliteModelRepository,
        SqliteProviderRepository, open_database,
    };
    use slint::Model as _;
    use tokio::sync::Notify;

    use super::*;
    use crate::chat::drafts::MemoryDraftStore;
    use crate::chat::runner::{ChatRunnerOptions, ChatStreamAdapter};
    use crate::chat::state::SystemClock;

    struct TestRuntime {
        runtime: tokio::runtime::Runtime,
    }

    impl TestRuntime {
        fn new() -> Self {
            Self {
                runtime: tokio::runtime::Runtime::new().expect("create test runtime"),
            }
        }
    }

    impl ChatRuntime for TestRuntime {
        fn spawn(&self, future: Pin<Box<dyn Future<Output = ()> + Send + 'static>>) {
            self.runtime.spawn(future);
        }
    }

    struct NoSecretStore;

    impl SecretStore for NoSecretStore {
        fn set(&self, _id: &SecretId, _value: &SecretValue) -> Result<(), SecretError> {
            Ok(())
        }

        fn get(&self, _id: &SecretId) -> Result<Option<SecretValue>, SecretError> {
            Ok(None)
        }

        fn delete(&self, _id: &SecretId) -> Result<(), SecretError> {
            Ok(())
        }

        fn forget_key(&self, _id: &SecretId) -> Result<(), SecretError> {
            Ok(())
        }

        fn forget_all_keys(&self) -> Result<(), SecretError> {
            Ok(())
        }

        fn is_available(&self) -> bool {
            true
        }
    }

    struct FakeAdapter {
        events: Vec<StreamEvent>,
        summary: StreamSummary,
        started: Arc<Notify>,
        gate: Arc<Notify>,
        wait: bool,
        wait_for_cancel: bool,
        captured_request: Mutex<Option<NormalizedChatRequest>>,
    }

    impl FakeAdapter {
        fn new(events: Vec<StreamEvent>, summary: StreamSummary) -> Self {
            Self {
                events,
                summary,
                started: Arc::new(Notify::new()),
                gate: Arc::new(Notify::new()),
                wait: false,
                wait_for_cancel: false,
                captured_request: Mutex::new(None),
            }
        }

        fn cancel_waiting(events: Vec<StreamEvent>, summary: StreamSummary) -> Self {
            Self {
                wait: true,
                wait_for_cancel: true,
                ..Self::new(events, summary)
            }
        }
    }

    #[async_trait]
    impl ChatStreamAdapter for FakeAdapter {
        async fn stream_chat(
            &self,
            _context: &ProviderContext,
            request: &NormalizedChatRequest,
            _supported_parameters: &[String],
            _attachments: &BTreeMap<String, ResolvedAttachment>,
            cancel: &CancellationToken,
            on_event: &mut (dyn FnMut(StreamEvent) + Send),
        ) -> Result<StreamSummary, ChatError> {
            *self
                .captured_request
                .lock()
                .unwrap_or_else(|poisoned| poisoned.into_inner()) = Some(request.clone());

            for event in &self.events {
                on_event(event.clone());
            }

            self.started.notify_one();

            if self.wait {
                if self.wait_for_cancel {
                    cancel.cancelled().await;
                } else {
                    self.gate.notified().await;
                }
            }

            Ok(self.summary.clone())
        }
    }

    struct StagedAdapter {
        first: Vec<StreamEvent>,
        second: Vec<StreamEvent>,
        summary: StreamSummary,
        gate1: Arc<Notify>,
        gate2: Arc<Notify>,
    }

    impl StagedAdapter {
        fn new(first: Vec<StreamEvent>, second: Vec<StreamEvent>, summary: StreamSummary) -> Self {
            Self {
                first,
                second,
                summary,
                gate1: Arc::new(Notify::new()),
                gate2: Arc::new(Notify::new()),
            }
        }
    }

    #[async_trait]
    impl ChatStreamAdapter for StagedAdapter {
        async fn stream_chat(
            &self,
            _context: &ProviderContext,
            _request: &NormalizedChatRequest,
            _supported_parameters: &[String],
            _attachments: &BTreeMap<String, ResolvedAttachment>,
            _cancel: &CancellationToken,
            on_event: &mut (dyn FnMut(StreamEvent) + Send),
        ) -> Result<StreamSummary, ChatError> {
            for event in &self.first {
                on_event(event.clone());
            }

            self.gate1.notified().await;

            for event in &self.second {
                on_event(event.clone());
            }

            self.gate2.notified().await;

            Ok(self.summary.clone())
        }
    }

    struct Harness {
        controller: ChatController,
        runtime: Arc<TestRuntime>,
        conversations: Arc<dyn ConversationRepository>,
        messages: Arc<dyn MessageRepository>,
        providers: Arc<dyn ProviderRepository>,
        models: Arc<dyn ModelRepository>,
        drafts: Arc<MemoryDraftStore>,
        store: Arc<MemoryStreamingStore>,
        _directory: tempfile::TempDir,
    }

    fn ts(value: &str) -> Timestamp {
        Timestamp::parse(value).expect("valid timestamp")
    }

    fn conversation(id: &str) -> Conversation {
        Conversation {
            id: id.to_owned(),
            title: "Chat".to_owned(),
            system_prompt: String::new(),
            provider_id: "provider-1".to_owned(),
            model_id: "model-1".to_owned(),
            model_label: "Model One".to_owned(),
            params: ChatParams::default(),
            pinned: false,
            archived: false,
            temporary: false,
            created_at: ts("2026-11-08T12:00:00.000Z"),
            updated_at: ts("2026-11-08T12:00:00.000Z"),
            last_message_at: ts("2026-11-08T12:00:00.000Z"),
            message_count: 0,
            last_error: None,
            sync: None,
            storage_key: String::new(),
            revision: 0,
        }
    }

    fn message(
        id: &str,
        conversation_id: &str,
        sequence: u64,
        role: MessageRole,
        text: &str,
        status: MessageStatus,
    ) -> Message {
        Message {
            id: id.to_owned(),
            conversation_id: conversation_id.to_owned(),
            sequence,
            role,
            parts: if text.is_empty() {
                Vec::new()
            } else {
                vec![ContentPart::Text {
                    text: text.to_owned(),
                }]
            },
            status,
            provider_id: Some("provider-1".to_owned()),
            model_id: Some("model-1".to_owned()),
            model_label: Some("Model One".to_owned()),
            usage: None,
            finish_reason: None,
            error: None,
            latency_ms: None,
            first_token_ms: None,
            created_at: ts("2026-11-08T12:00:00.000Z"),
            updated_at: ts("2026-11-08T12:00:00.000Z"),
            deleted_at: None,
        }
    }

    fn provider() -> Provider {
        Provider {
            id: "provider-1".to_owned(),
            preset_id: "custom".to_owned(),
            label: "Custom".to_owned(),
            kind: ProviderKind::OpenaiCompatible,
            base_url: "https://example.com/v1".to_owned(),
            auth_type: ProviderAuthType::None,
            secret_id: None,
            headers: BTreeMap::new(),
            enabled: true,
            browser_cors: BrowserCors::Unknown,
            metadata: None,
            created_at: ts("2026-11-08T12:00:00.000Z"),
            updated_at: ts("2026-11-08T12:00:00.000Z"),
        }
    }

    fn model() -> Model {
        Model {
            id: "model-1".to_owned(),
            provider_id: "provider-1".to_owned(),
            remote_id: "remote-model".to_owned(),
            label: "Model One".to_owned(),
            context_window: Some(128_000),
            max_output_tokens: Some(4_096),
            capabilities: vec![ModelCapability::Text, ModelCapability::Streaming],
            pricing: None,
            canonical_slug: None,
            input_modalities: vec![
                InputModality::Text,
                InputModality::Image,
                InputModality::File,
            ],
            output_modalities: vec![OutputModality::Text],
            supported_parameters: Vec::new(),
            metadata: None,
            hidden: false,
            pinned: false,
            source: ModelSource::Manual,
            created_at: ts("2026-11-08T12:00:00.000Z"),
            updated_at: ts("2026-11-08T12:00:00.000Z"),
        }
    }

    fn summary(
        text: &str,
        reasoning: &str,
        usage: Option<Usage>,
        finish_reason: Option<FinishReason>,
        error: Option<ChatError>,
    ) -> StreamSummary {
        StreamSummary {
            response_id: Some("response-1".to_owned()),
            text: text.to_owned(),
            reasoning: reasoning.to_owned(),
            usage,
            finish_reason,
            error,
            partial_output: PartialOutput::none(),
            retry_allowed: true,
            first_token_ms: Some(10),
            latency_ms: 20,
        }
    }

    fn harness(adapter: Arc<dyn ChatStreamAdapter>) -> Harness {
        i_slint_backend_testing::init_no_event_loop();
        let directory = tempfile::tempdir().expect("create temporary directory");
        let path = directory.path().join("chatui.sqlite3");
        let runtime = Arc::new(TestRuntime::new());
        let pool = runtime
            .runtime
            .block_on(open_database(&path))
            .expect("open temporary database");
        let conversations: Arc<dyn ConversationRepository> =
            Arc::new(SqliteConversationRepository::new(pool.clone()));
        let messages: Arc<dyn MessageRepository> =
            Arc::new(SqliteMessageRepository::new(pool.clone()));
        let providers: Arc<dyn ProviderRepository> =
            Arc::new(SqliteProviderRepository::new(pool.clone()));
        let models: Arc<dyn ModelRepository> = Arc::new(SqliteModelRepository::new(pool.clone()));
        let store = Arc::new(MemoryStreamingStore::new());
        let runner = Arc::new(ChatRunner::new(ChatRunnerOptions {
            adapter,
            messages: Arc::clone(&messages),
            store: Arc::clone(&store) as Arc<dyn StreamingStore>,
            clock: Arc::new(SystemClock),
            new_id: Arc::new(chatui_domain::new_id),
            persist_interval: Duration::from_millis(10),
        }));
        let service = ChatService::new(
            Arc::clone(&conversations),
            Arc::clone(&messages),
            Arc::clone(&providers),
            Arc::clone(&models),
            Arc::new(NoSecretStore),
            Arc::clone(&runner),
            AppConfig::new("ChatUI", "https://chatui.example").expect("app config"),
        );
        let drafts = Arc::new(MemoryDraftStore::new());
        let controller = ChatController::new(ChatControllerDeps {
            service,
            runner,
            store: Arc::clone(&store),
            conversations: Arc::clone(&conversations),
            messages: Arc::clone(&messages),
            models: Arc::clone(&models),
            drafts: drafts.clone(),
            send_key: SendKey::Enter,
            runtime: runtime.clone(),
            config: MessageListConfig::default(),
            initial_conversation_id: None,
        })
        .expect("create chat controller");

        Harness {
            controller,
            runtime,
            conversations,
            messages,
            providers,
            models,
            drafts,
            store,
            _directory: directory,
        }
    }

    fn seed(harness: &Harness, conversation: &Conversation, messages: &[Message]) {
        harness.runtime.runtime.block_on(async {
            harness
                .conversations
                .upsert(conversation)
                .await
                .expect("upsert conversation");
            harness
                .providers
                .upsert(&provider())
                .await
                .expect("upsert provider");
            harness.models.upsert(&model()).await.expect("upsert model");

            for message in messages {
                harness
                    .messages
                    .insert(message)
                    .await
                    .expect("insert message");
            }
        });
    }

    fn wait_until(controller: &ChatController, mut predicate: impl FnMut(&ChatViewState) -> bool) {
        for _ in 0..300 {
            controller.poll();

            if predicate(&controller.state()) {
                return;
            }

            std::thread::sleep(Duration::from_millis(10));
        }

        panic!("timed out waiting for chat state: {:?}", controller.state());
    }

    fn wait_for_rows(controller: &ChatController, count: usize) {
        for _ in 0..300 {
            controller.poll();

            if controller.messages_window().get_rows().row_count() == count {
                return;
            }

            std::thread::sleep(Duration::from_millis(10));
        }

        let state = controller.message_list.borrow();
        let state = state.state();
        panic!(
            "timed out waiting for {count} rows, got {}; messages: {}, visible: {}, streaming: {:?}, range: {:?}, scroll: {}, viewport: {}",
            controller.messages_window().get_rows().row_count(),
            state.messages().len(),
            state.visible_rows().len(),
            state
                .streaming()
                .map(|stream| (stream.message_id.clone(), stream.done, stream.status)),
            state.visible_range(),
            state.scroll_top(),
            state.viewport_height(),
        );
    }

    #[test]
    fn loads_conversation_and_renders_messages() {
        let adapter = Arc::new(FakeAdapter::new(
            Vec::new(),
            summary("", "", None, None, None),
        ));
        let harness = harness(adapter);
        seed(
            &harness,
            &conversation("conversation-1"),
            &[
                message(
                    "user-1",
                    "conversation-1",
                    0,
                    MessageRole::User,
                    "Hello",
                    MessageStatus::Complete,
                ),
                message(
                    "assistant-1",
                    "conversation-1",
                    1,
                    MessageRole::Assistant,
                    "Hi",
                    MessageStatus::Complete,
                ),
            ],
        );

        harness.controller.open("conversation-1");
        wait_until(&harness.controller, |view| !view.loading);

        assert_eq!(
            harness.controller.state().conversation_id.as_deref(),
            Some("conversation-1")
        );
        assert!(!harness.controller.state().empty);

        let rows = harness.controller.messages_window().get_rows();
        assert_eq!(rows.row_count(), 2);
        assert_eq!(rows.row_data(0).expect("row").id.as_str(), "user-1");
        assert_eq!(rows.row_data(1).expect("row").id.as_str(), "assistant-1");
    }

    #[test]
    fn token_usage_toggle_updates_message_rows() {
        let adapter = Arc::new(FakeAdapter::new(
            Vec::new(),
            summary("", "", None, None, None),
        ));
        let harness = harness(adapter);
        let mut response = message(
            "assistant-1",
            "conversation-1",
            0,
            MessageRole::Assistant,
            "Hi",
            MessageStatus::Complete,
        );
        response.usage = Some(Usage {
            input_tokens: Some(3),
            output_tokens: Some(5),
            total_tokens: Some(8),
            cost_usd: None,
            estimated: false,
        });
        seed(&harness, &conversation("conversation-1"), &[response]);

        harness.controller.open("conversation-1");
        wait_for_rows(&harness.controller, 1);

        let rows = harness.controller.messages_window().get_rows();
        let meta = rows.row_data(0).expect("row").meta;
        assert!(meta.contains("3 in"), "unexpected metadata: {meta}");

        harness.controller.set_show_token_usage(false);

        let rows = harness.controller.messages_window().get_rows();
        let meta = rows.row_data(0).expect("row").meta;
        assert!(!meta.contains("3 in"), "unexpected metadata: {meta}");

        harness.controller.set_show_token_usage(true);

        let rows = harness.controller.messages_window().get_rows();
        let meta = rows.row_data(0).expect("row").meta;
        assert!(meta.contains("3 in"), "unexpected metadata: {meta}");
    }

    #[test]
    fn send_persists_user_message_and_streams_assistant() {
        let adapter = Arc::new(FakeAdapter::new(
            vec![
                StreamEvent::Start {
                    response_id: Some("response-1".to_owned()),
                },
                StreamEvent::TextDelta {
                    text: "Hello".to_owned(),
                },
                StreamEvent::Finish {
                    reason: FinishReason::Stop,
                },
            ],
            summary("Hello", "", None, Some(FinishReason::Stop), None),
        ));
        let harness = harness(adapter);
        seed(&harness, &conversation("conversation-1"), &[]);
        harness.controller.open("conversation-1");
        wait_until(&harness.controller, |view| !view.loading);

        harness
            .controller
            .composer_window()
            .invoke_text_changed("Hi".into());
        harness.controller.send();
        wait_for_rows(&harness.controller, 2);
        wait_until(&harness.controller, |_| {
            let rows = harness.controller.messages_window().get_rows();
            rows.row_count() == 2
                && rows
                    .row_data(1)
                    .is_some_and(|row| row.markdown.as_str() == "Hello")
                && !harness.controller.is_streaming()
        });

        let messages = harness
            .runtime
            .runtime
            .block_on(harness.messages.list_all("conversation-1"))
            .expect("messages");
        assert_eq!(messages.len(), 2);
        assert_eq!(messages[0].role, MessageRole::User);
        assert_eq!(messages[1].role, MessageRole::Assistant);
        assert_eq!(messages[1].status, MessageStatus::Complete);
        assert_eq!(
            messages[1].parts,
            vec![ContentPart::Text {
                text: "Hello".to_owned(),
            }]
        );
    }

    #[test]
    fn stop_cancels_and_preserves_partial_content() {
        let adapter = Arc::new(FakeAdapter::cancel_waiting(
            vec![StreamEvent::TextDelta {
                text: "partial".to_owned(),
            }],
            summary("partial", "", None, Some(FinishReason::Stop), None),
        ));
        let harness = harness(adapter);
        seed(&harness, &conversation("conversation-1"), &[]);
        harness.controller.open("conversation-1");
        wait_until(&harness.controller, |view| !view.loading);

        harness
            .controller
            .composer_window()
            .invoke_text_changed("Hi".into());
        harness.controller.send();
        wait_until(&harness.controller, |view| view.streaming);

        harness.controller.stop();
        wait_for_rows(&harness.controller, 2);

        let messages = harness
            .runtime
            .runtime
            .block_on(harness.messages.list_all("conversation-1"))
            .expect("messages");
        let assistant = messages
            .iter()
            .find(|message| message.role == MessageRole::Assistant)
            .expect("assistant");
        assert_eq!(assistant.status, MessageStatus::Cancelled);
        assert_eq!(
            assistant.parts,
            vec![ContentPart::Text {
                text: "partial".to_owned(),
            }]
        );
    }

    #[test]
    fn regenerate_targets_last_assistant() {
        let adapter = Arc::new(FakeAdapter::new(
            vec![
                StreamEvent::TextDelta {
                    text: "new".to_owned(),
                },
                StreamEvent::Finish {
                    reason: FinishReason::Stop,
                },
            ],
            summary("new", "", None, Some(FinishReason::Stop), None),
        ));
        let harness = harness(adapter);
        seed(
            &harness,
            &conversation("conversation-1"),
            &[
                message(
                    "user-1",
                    "conversation-1",
                    0,
                    MessageRole::User,
                    "Hi",
                    MessageStatus::Complete,
                ),
                message(
                    "assistant-1",
                    "conversation-1",
                    1,
                    MessageRole::Assistant,
                    "old",
                    MessageStatus::Complete,
                ),
            ],
        );
        harness.controller.open("conversation-1");
        wait_until(&harness.controller, |view| !view.loading);

        harness.controller.regenerate("assistant-1");
        wait_until(&harness.controller, |_| {
            let rows = harness.controller.messages_window().get_rows();
            rows.row_count() == 2
                && rows
                    .row_data(1)
                    .is_some_and(|row| row.markdown.as_str() == "new")
        });

        let messages = harness
            .runtime
            .runtime
            .block_on(harness.messages.list_all("conversation-1"))
            .expect("messages");
        assert_eq!(messages.len(), 2);
        let assistant = messages
            .iter()
            .find(|message| message.role == MessageRole::Assistant)
            .expect("assistant");
        assert_eq!(assistant.id, "assistant-1");
        assert_eq!(
            assistant.parts,
            vec![ContentPart::Text {
                text: "new".to_owned(),
            }]
        );
    }

    #[test]
    fn retry_targets_failed_assistant() {
        let adapter = Arc::new(FakeAdapter::new(
            vec![
                StreamEvent::TextDelta {
                    text: "recovered".to_owned(),
                },
                StreamEvent::Finish {
                    reason: FinishReason::Stop,
                },
            ],
            summary("recovered", "", None, Some(FinishReason::Stop), None),
        ));
        let harness = harness(adapter);
        let mut failed = message(
            "assistant-1",
            "conversation-1",
            1,
            MessageRole::Assistant,
            "",
            MessageStatus::Error,
        );
        failed.error = Some(ChatError {
            code: ChatErrorCode::Network,
            message: "network".to_owned(),
            provider_id: None,
            status: None,
            retryable: true,
            retry_after_seconds: None,
            request_id: None,
            details: None,
            at: ts("2026-11-08T12:00:00.000Z"),
        });
        seed(
            &harness,
            &conversation("conversation-1"),
            &[
                message(
                    "user-1",
                    "conversation-1",
                    0,
                    MessageRole::User,
                    "Hi",
                    MessageStatus::Complete,
                ),
                failed,
            ],
        );
        harness.controller.open("conversation-1");
        wait_until(&harness.controller, |view| !view.loading);

        harness.controller.retry("assistant-1");
        wait_until(&harness.controller, |_| {
            let rows = harness.controller.messages_window().get_rows();
            rows.row_count() == 2
                && rows
                    .row_data(1)
                    .is_some_and(|row| row.markdown.as_str() == "recovered")
        });

        let messages = harness
            .runtime
            .runtime
            .block_on(harness.messages.list_all("conversation-1"))
            .expect("messages");
        let assistant = messages
            .iter()
            .find(|message| message.role == MessageRole::Assistant)
            .expect("assistant");
        assert_eq!(assistant.status, MessageStatus::Complete);
        assert_eq!(
            assistant.parts,
            vec![ContentPart::Text {
                text: "recovered".to_owned(),
            }]
        );
    }

    #[test]
    fn streaming_updates_live_row_without_reloading_history() {
        let adapter = Arc::new(StagedAdapter::new(
            vec![StreamEvent::TextDelta {
                text: "a".to_owned(),
            }],
            vec![StreamEvent::TextDelta {
                text: "b".to_owned(),
            }],
            summary("ab", "", None, Some(FinishReason::Stop), None),
        ));
        let harness = harness(adapter.clone());
        seed(&harness, &conversation("conversation-1"), &[]);
        harness.controller.open("conversation-1");
        wait_until(&harness.controller, |view| !view.loading);

        harness
            .controller
            .composer_window()
            .invoke_text_changed("Hi".into());
        harness.controller.send();

        wait_until(&harness.controller, |_| {
            harness
                .controller
                .messages_window()
                .get_streaming_row()
                .markdown
                .as_str()
                == "a"
        });
        wait_for_rows(&harness.controller, 1);

        let rows_before = harness.controller.messages_window().get_rows().row_count();
        adapter.gate1.notify_one();

        wait_until(&harness.controller, |_| {
            harness
                .controller
                .messages_window()
                .get_streaming_row()
                .markdown
                .as_str()
                == "ab"
        });

        let rows_after = harness.controller.messages_window().get_rows().row_count();
        assert_eq!(rows_before, rows_after);

        adapter.gate2.notify_one();
        wait_for_rows(&harness.controller, 2);
    }

    #[test]
    fn empty_state() {
        let adapter = Arc::new(FakeAdapter::new(
            Vec::new(),
            summary("", "", None, None, None),
        ));
        let harness = harness(adapter);
        seed(&harness, &conversation("conversation-1"), &[]);
        harness.controller.open("conversation-1");
        wait_until(&harness.controller, |view| !view.loading);

        assert!(harness.controller.state().empty);
        assert!(harness.controller.messages_window().get_empty());
    }

    #[test]
    fn error_state() {
        let adapter = Arc::new(FakeAdapter::new(
            Vec::new(),
            summary("", "", None, None, None),
        ));
        let harness = harness(adapter);
        harness.controller.open("missing");
        wait_until(&harness.controller, |view| !view.loading);

        assert!(harness.controller.state().error.is_some());
        assert!(harness.controller.messages_window().get_error_visible());
    }

    #[test]
    fn attachments_flow_through_composer() {
        let adapter = Arc::new(FakeAdapter::new(
            Vec::new(),
            summary("", "", None, None, None),
        ));
        let harness = harness(adapter);
        seed(&harness, &conversation("conversation-1"), &[]);
        harness.controller.open("conversation-1");
        wait_until(&harness.controller, |view| !view.loading);

        let attachment = PendingAttachment {
            id: "attachment-1".to_owned(),
            kind: AttachmentKind::File,
            mime_type: "text/plain".to_owned(),
            name: "notes.txt".to_owned(),
            size: 12,
            sha256: "hash".to_owned(),
            storage_key: "key".to_owned(),
            width: None,
            height: None,
        };
        harness.controller.add_attachment(attachment);
        assert_eq!(
            harness.controller.composer_window().get_chips().row_count(),
            1
        );
        assert!(harness.controller.composer_window().get_can_send());

        assert!(harness.controller.remove_attachment("attachment-1"));
        assert_eq!(
            harness.controller.composer_window().get_chips().row_count(),
            0
        );

        let valid = harness.controller.validate_files(&[AttachmentFileInfo::new(
            "notes.txt",
            "text/plain",
            12,
        )]);
        assert!(valid[0].is_ok());

        let invalid = harness.controller.validate_files(&[AttachmentFileInfo::new(
            "binary.exe",
            "application/octet-stream",
            12,
        )]);
        assert!(invalid[0].is_err());
    }

    #[test]
    fn drafts_persist_and_restore() {
        let adapter = Arc::new(FakeAdapter::new(
            Vec::new(),
            summary("", "", None, None, None),
        ));
        let harness = harness(adapter);
        seed(&harness, &conversation("conversation-1"), &[]);
        seed(&harness, &conversation("conversation-2"), &[]);
        harness.controller.open("conversation-1");
        wait_until(&harness.controller, |view| !view.loading);

        harness
            .controller
            .composer_window()
            .invoke_text_changed("draft one".into());
        assert_eq!(
            harness.drafts.draft("conversation-1").as_deref(),
            Some("draft one")
        );

        harness.controller.open("conversation-2");
        wait_until(&harness.controller, |view| !view.loading);
        assert_eq!(harness.controller.composer_window().get_text().as_str(), "");

        harness
            .controller
            .composer_window()
            .invoke_text_changed("draft two".into());
        harness.controller.open("conversation-1");
        wait_until(&harness.controller, |view| !view.loading);
        assert_eq!(
            harness.controller.composer_window().get_text().as_str(),
            "draft one"
        );
    }

    #[test]
    fn announcements_are_polite_and_bounded() {
        let adapter = Arc::new(StagedAdapter::new(
            vec![
                StreamEvent::Start {
                    response_id: Some("response-1".to_owned()),
                },
                StreamEvent::TextDelta {
                    text: "Hello".to_owned(),
                },
            ],
            vec![StreamEvent::Finish {
                reason: FinishReason::Stop,
            }],
            summary("Hello", "", None, Some(FinishReason::Stop), None),
        ));
        let harness = harness(adapter.clone());
        seed(&harness, &conversation("conversation-1"), &[]);
        harness.controller.open("conversation-1");
        wait_until(&harness.controller, |view| !view.loading);

        harness
            .controller
            .composer_window()
            .invoke_text_changed("Hi".into());
        harness.controller.send();

        wait_until(&harness.controller, |view| {
            view.announcement
                .as_ref()
                .is_some_and(|announcement| announcement.text == "Response started")
        });

        let started = harness
            .controller
            .state()
            .announcement
            .clone()
            .expect("start announcement");
        assert_eq!(started.politeness, LiveRegionPoliteness::Polite);
        assert!(started.text.chars().count() <= crate::chat::MAX_ANNOUNCEMENT_CHARS);

        adapter.gate1.notify_one();

        wait_until(&harness.controller, |view| {
            view.announcement
                .as_ref()
                .is_some_and(|announcement| announcement.text == "Response complete")
        });

        let complete = harness
            .controller
            .state()
            .announcement
            .clone()
            .expect("complete announcement");
        assert_eq!(complete.politeness, LiveRegionPoliteness::Polite);
        assert!(complete.text.chars().count() <= crate::chat::MAX_ANNOUNCEMENT_CHARS);

        adapter.gate2.notify_one();
        wait_for_rows(&harness.controller, 2);
    }

    #[test]
    fn standalone_window_maps_state() {
        let adapter = Arc::new(FakeAdapter::new(
            Vec::new(),
            summary("", "", None, None, None),
        ));
        let harness = harness(adapter);
        seed(&harness, &conversation("conversation-1"), &[]);
        harness.controller.open("conversation-1");
        wait_until(&harness.controller, |view| !view.loading);

        let window = harness
            .controller
            .standalone_window()
            .expect("standalone window");
        assert!(!window.get_loading());
        assert!(window.get_empty());
        assert!(window.get_error().is_empty());
        assert!(!window.get_streaming());
    }

    #[test]
    fn store_is_cleared_after_terminal_reload() {
        let adapter = Arc::new(FakeAdapter::new(
            vec![
                StreamEvent::TextDelta {
                    text: "Hello".to_owned(),
                },
                StreamEvent::Finish {
                    reason: FinishReason::Stop,
                },
            ],
            summary("Hello", "", None, Some(FinishReason::Stop), None),
        ));
        let harness = harness(adapter);
        seed(&harness, &conversation("conversation-1"), &[]);
        harness.controller.open("conversation-1");
        wait_until(&harness.controller, |view| !view.loading);

        harness
            .controller
            .composer_window()
            .invoke_text_changed("Hi".into());
        harness.controller.send();
        wait_for_rows(&harness.controller, 2);

        assert!(harness.store.stream("conversation-1").is_none());
        assert!(!harness.controller.is_streaming());
    }
}
