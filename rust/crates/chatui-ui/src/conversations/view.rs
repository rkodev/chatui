//! Slint history view and controller.
//!
//! [`HistoryController`] binds the framework-independent [`ConversationHistory`]
//! and [`ConversationSearchService`] to a Slint sidebar. Every storage call runs
//! on the injected [`HistoryRuntime`]; results are sent back through a channel
//! and applied by [`HistoryController::poll`] on the Slint thread. The desktop
//! calls `poll` from a short timer, so no blocking storage call ever runs on the
//! Slint thread.
//!
//! The view renders the pinned and calendar groups from
//! [`crate::conversations::grouping::group_conversations`], the active row, draft
//! badges, the archived toggle, load-more, new chat, rename, delete with undo,
//! and bounded search results with snippets.

use std::cell::RefCell;
use std::future::Future;
use std::pin::Pin;
use std::rc::Rc;
use std::sync::Arc;
use std::sync::mpsc::{Receiver, Sender};
use std::time::{Duration, Instant};

use chatui_domain::Timestamp;
use slint::{ComponentHandle, ModelRc, SharedString, VecModel};

use crate::conversations::grouping::format_conversation_timestamp;
use crate::conversations::history::{ConversationHistory, HistoryState, HistoryStatus};
use crate::conversations::search::{ConversationSearchService, ConversationSearchState};

slint::slint! {
    import { Button, LineEdit, Palette, ScrollView } from "std-widgets.slint";

    export struct HistoryRow {
        id: string,
        title: string,
        timestamp: string,
        group: string,
        is-header: bool,
        active: bool,
        draft: bool,
        pinned: bool,
        archived: bool,
    }

    export struct SearchRow {
        conversation-id: string,
        title: string,
        snippet: string,
    }

    export component HistoryWindow inherits Window {
        title: "ChatUI history";
        width: 420px;
        height: 760px;

        in property <[HistoryRow]> rows: [];
        in property <bool> archived: false;
        in property <bool> loading: false;
        in property <bool> empty: false;
        in property <string> error: "";
        in property <string> status: "";
        in property <bool> busy: false;
        in property <bool> can-load-more: false;
        in property <string> pending-delete-title: "";
        in property <string> rename-id: "";
        in-out property <string> rename-text: "";
        in-out property <string> search-query: "";
        in property <[SearchRow]> search-results: [];
        in property <string> search-status: "";
        in property <bool> search-truncated: false;
        in property <bool> dark: false;

        background: root.dark ? #0f1115 : #ffffff;
        init => {
            Palette.color-scheme = root.dark ? ColorScheme.dark : ColorScheme.light;
        }
        changed dark => {
            Palette.color-scheme = root.dark ? ColorScheme.dark : ColorScheme.light;
        }

        callback new-chat();
        callback select-conversation(string);
        callback toggle-archived();
        callback load-more();
        callback begin-rename(string);
        callback rename-text-changed(string);
        callback commit-rename();
        callback cancel-rename();
        callback toggle-archive(string);
        callback delete-conversation(string);
        callback undo-delete();
        callback search-query-changed(string);
        callback search();
        callback clear-search();
        callback open-search-result(string);

        VerticalLayout {
            padding: 12px;
            spacing: 8px;

            HorizontalLayout {
                spacing: 8px;

                Button {
                    text: "New chat";
                    enabled: !root.busy;
                    clicked => { root.new-chat(); }
                }

                Button {
                    text: root.archived ? "Show active" : "Show archived";
                    clicked => { root.toggle-archived(); }
                }
            }

            LineEdit {
                text <=> root.search-query;
                placeholder-text: "Search conversations";
                accessible-label: "Search conversations";
                edited(text) => { root.search-query-changed(text); }
                accepted(text) => { root.search(); }
            }

            HorizontalLayout {
                spacing: 8px;

                Button {
                    text: "Search";
                    enabled: !root.busy;
                    clicked => { root.search(); }
                }

                Button {
                    text: "Clear search";
                    clicked => { root.clear-search(); }
                }
            }

            if (!root.search-status.is-empty) : Text {
                text: root.search-status;
                color: root.dark ? #9ca3af : #5f6368;
                wrap: word-wrap;
                accessible-live-region: polite;
            }

            if (root.search-results.length > 0) : ScrollView {
                height: 180px;

                VerticalLayout {
                    spacing: 4px;

                    for result in root.search-results : Rectangle {
                        height: 52px;
                        background: transparent;
                        border-radius: 6px;

                        TouchArea {
                            clicked => { root.open-search-result(result.conversation-id); }
                        }

                        VerticalLayout {
                            padding: 4px;
                            spacing: 2px;

                            Text {
                                text: result.title;
                                font-weight: 600;
                                overflow: elide;
                            }

                            Text {
                                text: result.snippet;
                                color: root.dark ? #9ca3af : #5f6368;
                                font-size: 12px;
                                wrap: word-wrap;
                            }
                        }
                    }
                }
            }

            if (!root.error.is-empty) : Text {
                text: root.error;
                color: root.dark ? #f87171 : #b91c1c;
                wrap: word-wrap;
                accessible-live-region: assertive;
            }

            if (root.loading) : Text {
                text: "Loading conversations...";
                color: root.dark ? #9ca3af : #5f6368;
                accessible-live-region: polite;
            }

            if (root.empty && !root.loading) : Text {
                text: root.archived ? "No archived conversations." : "No conversations yet. Start a new chat.";
                color: root.dark ? #9ca3af : #5f6368;
                wrap: word-wrap;
            }

            if (!root.pending-delete-title.is-empty) : Rectangle {
                background: root.dark ? #3a2d12 : #fef3c7;
                border-radius: 6px;

                HorizontalLayout {
                    padding: 8px;
                    spacing: 8px;

                    Text {
                        text: "Deleted " + root.pending-delete-title;
                        vertical-alignment: center;
                        horizontal-stretch: 1;
                    }

                    Button {
                        text: "Undo";
                        clicked => { root.undo-delete(); }
                    }
                }
            }

            if (!root.rename-id.is-empty) : VerticalLayout {
                spacing: 4px;

                Text {
                    text: "Rename conversation";
                    font-weight: 600;
                }

                LineEdit {
                    text <=> root.rename-text;
                    accessible-label: "Conversation title";
                    edited(text) => { root.rename-text-changed(text); }
                    accepted(text) => { root.commit-rename(); }
                }

                HorizontalLayout {
                    spacing: 8px;

                    Button {
                        text: "Save";
                        enabled: !root.busy;
                        clicked => { root.commit-rename(); }
                    }

                    Button {
                        text: "Cancel";
                        clicked => { root.cancel-rename(); }
                    }
                }
            }

            ScrollView {
                VerticalLayout {
                    spacing: 2px;

                    for row in root.rows : Rectangle {
                        height: row.is-header ? 24px : 44px;
                        background: row.active ? (root.dark ? #1f232b : #f7f7f8) : transparent;
                        border-radius: 6px;

                        if (row.is-header) : Text {
                            text: row.title;
                            color: root.dark ? #9ca3af : #5f6368;
                            font-size: 12px;
                            vertical-alignment: center;
                            x: 8px;
                        }

                        if (!row.is-header) : TouchArea {
                            clicked => { root.select-conversation(row.id); }
                        }

                        if (!row.is-header) : HorizontalLayout {
                            padding-left: 8px;
                            padding-right: 8px;
                            spacing: 6px;

                            Text {
                                text: row.title;
                                vertical-alignment: center;
                                horizontal-stretch: 1;
                                overflow: elide;
                            }

                            if (row.draft) : Text {
                                text: "Draft";
                                color: root.dark ? #fbbf24 : #b45309;
                                font-size: 11px;
                                vertical-alignment: center;
                            }

                            if (row.pinned) : Text {
                                text: "Pinned";
                                color: root.dark ? #9ca3af : #5f6368;
                                font-size: 11px;
                                vertical-alignment: center;
                            }

                            Text {
                                text: row.timestamp;
                                color: root.dark ? #9ca3af : #5f6368;
                                font-size: 11px;
                                vertical-alignment: center;
                            }

                            Button {
                                text: "Rename";
                                clicked => { root.begin-rename(row.id); }
                            }

                            Button {
                                text: row.archived ? "Unarchive" : "Archive";
                                clicked => { root.toggle-archive(row.id); }
                            }

                            Button {
                                text: "Delete";
                                clicked => { root.delete-conversation(row.id); }
                            }
                        }
                    }
                }
            }

            if (root.can-load-more) : Button {
                text: "Load more";
                enabled: !root.busy;
                clicked => { root.load-more(); }
            }

            if (!root.status.is-empty) : Text {
                text: root.status;
                color: root.dark ? #9ca3af : #5f6368;
                wrap: word-wrap;
                accessible-live-region: polite;
            }
        }
    }
}

/// Spawns async history work off the Slint thread.
///
/// The desktop implements this over its shared `tokio` runtime. Tests implement
/// it over a private runtime and drive [`HistoryController::poll`] directly.
pub trait HistoryRuntime: Send + Sync {
    /// Spawn a future on the runtime.
    fn spawn(&self, future: Pin<Box<dyn Future<Output = ()> + Send + 'static>>);
}

/// Active rename editor.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct RenameState {
    /// Conversation being renamed.
    pub conversation_id: String,
    /// Current editor text.
    pub text: String,
}

/// Slint-facing history state.
#[derive(Debug, Clone, PartialEq)]
pub struct HistoryViewState {
    /// History view model state.
    pub history: HistoryState,
    /// Search box state.
    pub search: ConversationSearchState,
    /// Current search query.
    pub search_query: String,
    /// Active rename editor.
    pub rename: Option<RenameState>,
    /// Whether an async operation is in flight.
    pub busy: bool,
    /// Optional status or notice text.
    pub notice: Option<String>,
    /// Bounded window cap from the history configuration.
    pub max_rows: u32,
    /// Delete undo window from the history configuration.
    pub undo_window: Duration,
}

impl HistoryViewState {
    /// Create the initial view state.
    pub fn new(history: HistoryState, max_rows: u32, undo_window: Duration) -> Self {
        Self {
            history,
            search: ConversationSearchState::Idle,
            search_query: String::new(),
            rename: None,
            busy: false,
            notice: None,
            max_rows,
            undo_window,
        }
    }
}

/// Push the current history and search state into a Slint window.
pub fn sync_window(window: &HistoryWindow, view: &HistoryViewState) {
    window.set_rows(ModelRc::new(VecModel::from(history_rows(&view.history))));
    window.set_archived(view.history.archived);
    window.set_loading(view.history.status == HistoryStatus::Loading);
    window.set_empty(view.history.status == HistoryStatus::Ready && view.history.is_empty());
    window.set_error(view.history.error.clone().unwrap_or_default().into());
    window.set_status(view.notice.clone().unwrap_or_default().into());
    window.set_busy(view.busy);
    window.set_can_load_more(can_load_more(view));
    window.set_pending_delete_title(
        view.history
            .pending_delete
            .as_ref()
            .map(|conversation| conversation.title.clone())
            .unwrap_or_default()
            .into(),
    );
    window.set_rename_id(
        view.rename
            .as_ref()
            .map(|rename| rename.conversation_id.clone())
            .unwrap_or_default()
            .into(),
    );

    let rename_text = view
        .rename
        .as_ref()
        .map(|rename| rename.text.clone())
        .unwrap_or_default();

    if window.get_rename_text().as_str() != rename_text {
        window.set_rename_text(rename_text.into());
    }

    if window.get_search_query().as_str() != view.search_query {
        window.set_search_query(view.search_query.clone().into());
    }

    window.set_search_results(ModelRc::new(VecModel::from(search_rows(&view.search))));
    window.set_search_status(search_status(&view.search).into());
    window.set_search_truncated(matches!(
        &view.search,
        ConversationSearchState::Ready(result) if result.truncated
    ));
}

fn history_rows(state: &HistoryState) -> Vec<HistoryRow> {
    let now = Timestamp::now();
    let mut rows = Vec::new();

    for group in &state.groups {
        rows.push(HistoryRow {
            id: SharedString::default(),
            title: SharedString::from(group.label),
            timestamp: SharedString::default(),
            group: SharedString::from(group.label),
            is_header: true,
            active: false,
            draft: false,
            pinned: false,
            archived: false,
        });

        for conversation in &group.conversations {
            rows.push(HistoryRow {
                id: SharedString::from(conversation.id.clone()),
                title: SharedString::from(conversation.title.clone()),
                timestamp: SharedString::from(format_conversation_timestamp(
                    conversation.last_message_at,
                    now,
                )),
                group: SharedString::from(group.label),
                is_header: false,
                active: state.active_conversation_id.as_deref() == Some(conversation.id.as_str()),
                draft: state.draft_ids.contains(&conversation.id),
                pinned: conversation.pinned,
                archived: conversation.archived,
            });
        }
    }

    rows
}

fn search_rows(state: &ConversationSearchState) -> Vec<SearchRow> {
    match state {
        ConversationSearchState::Ready(result) => result
            .hits
            .iter()
            .map(|hit| SearchRow {
                conversation_id: SharedString::from(hit.conversation_id.clone()),
                title: SharedString::from(hit.conversation_title.clone()),
                snippet: SharedString::from(hit.snippet.clone()),
            })
            .collect(),
        _ => Vec::new(),
    }
}

fn search_status(state: &ConversationSearchState) -> String {
    match state {
        ConversationSearchState::Idle => String::new(),
        ConversationSearchState::Loading { query } => format!("Searching for \"{query}\"..."),
        ConversationSearchState::Ready(result) if result.truncated => {
            format!("Showing the first {} results.", result.hits.len())
        }
        ConversationSearchState::Ready(_) => String::new(),
        ConversationSearchState::Empty { query } => format!("No results for \"{query}\"."),
        ConversationSearchState::Error { message, .. } => message.clone(),
    }
}

fn can_load_more(view: &HistoryViewState) -> bool {
    view.history.status == HistoryStatus::Ready
        && !view.history.is_empty()
        && view.history.conversations.len() < view.max_rows as usize
}

struct HistorySnapshot {
    history: HistoryState,
    search: Option<ConversationSearchState>,
    busy: bool,
    notice: Option<String>,
    navigation: Option<String>,
    clear_rename: bool,
}

fn set_busy(state: &Rc<RefCell<HistoryViewState>>, weak: &slint::Weak<HistoryWindow>, busy: bool) {
    state.borrow_mut().busy = busy;

    if let Some(window) = weak.upgrade() {
        sync_window(&window, &state.borrow());
    }
}

fn spawn_load(
    history: Arc<tokio::sync::Mutex<ConversationHistory>>,
    runtime: Arc<dyn HistoryRuntime>,
    sender: Sender<HistorySnapshot>,
    archived: bool,
) {
    runtime.spawn(Box::pin(async move {
        let history_state = {
            let mut history = history.lock().await;
            let _ = history.load(archived).await;
            history.state().clone()
        };

        let _ = sender.send(HistorySnapshot {
            history: history_state,
            search: None,
            busy: false,
            notice: None,
            navigation: None,
            clear_rename: false,
        });
    }));
}

fn spawn_select(
    history: Arc<tokio::sync::Mutex<ConversationHistory>>,
    runtime: Arc<dyn HistoryRuntime>,
    sender: Sender<HistorySnapshot>,
    conversation_id: String,
) {
    runtime.spawn(Box::pin(async move {
        let history_state = {
            let mut history = history.lock().await;
            let _ = history.select(&conversation_id).await;
            history.state().clone()
        };

        let _ = sender.send(HistorySnapshot {
            history: history_state,
            search: None,
            busy: false,
            notice: None,
            navigation: Some(conversation_id),
            clear_rename: false,
        });
    }));
}

fn spawn_new_chat(
    history: Arc<tokio::sync::Mutex<ConversationHistory>>,
    runtime: Arc<dyn HistoryRuntime>,
    sender: Sender<HistorySnapshot>,
) {
    runtime.spawn(Box::pin(async move {
        let (history_state, navigation) = {
            let mut history = history.lock().await;

            match history.new_chat().await {
                Ok(conversation) => (history.state().clone(), Some(conversation.id)),
                Err(_) => (history.state().clone(), None),
            }
        };

        let _ = sender.send(HistorySnapshot {
            history: history_state,
            search: None,
            busy: false,
            notice: None,
            navigation,
            clear_rename: false,
        });
    }));
}

fn spawn_load_more(
    history: Arc<tokio::sync::Mutex<ConversationHistory>>,
    runtime: Arc<dyn HistoryRuntime>,
    sender: Sender<HistorySnapshot>,
) {
    runtime.spawn(Box::pin(async move {
        let history_state = {
            let mut history = history.lock().await;
            let _ = history.load_more().await;
            history.state().clone()
        };

        let _ = sender.send(HistorySnapshot {
            history: history_state,
            search: None,
            busy: false,
            notice: None,
            navigation: None,
            clear_rename: false,
        });
    }));
}

fn spawn_rename(
    history: Arc<tokio::sync::Mutex<ConversationHistory>>,
    runtime: Arc<dyn HistoryRuntime>,
    sender: Sender<HistorySnapshot>,
    conversation_id: String,
    title: String,
) {
    runtime.spawn(Box::pin(async move {
        let (history_state, clear_rename) = {
            let mut history = history.lock().await;
            let result = history.rename(&conversation_id, &title).await;
            (history.state().clone(), result.is_ok())
        };

        let _ = sender.send(HistorySnapshot {
            history: history_state,
            search: None,
            busy: false,
            notice: None,
            navigation: None,
            clear_rename,
        });
    }));
}

fn spawn_archive(
    history: Arc<tokio::sync::Mutex<ConversationHistory>>,
    runtime: Arc<dyn HistoryRuntime>,
    sender: Sender<HistorySnapshot>,
    conversation_id: String,
    archived: bool,
) {
    runtime.spawn(Box::pin(async move {
        let history_state = {
            let mut history = history.lock().await;
            let _ = history.set_archived(&conversation_id, archived).await;
            history.state().clone()
        };

        let _ = sender.send(HistorySnapshot {
            history: history_state,
            search: None,
            busy: false,
            notice: None,
            navigation: None,
            clear_rename: false,
        });
    }));
}

fn spawn_delete(
    history: Arc<tokio::sync::Mutex<ConversationHistory>>,
    runtime: Arc<dyn HistoryRuntime>,
    sender: Sender<HistorySnapshot>,
    conversation_id: String,
    undo_window: Duration,
) {
    runtime.spawn(Box::pin(async move {
        let history_state = {
            let mut history = history.lock().await;
            let _ = history
                .request_delete(&conversation_id, Instant::now())
                .await;
            history.state().clone()
        };

        let _ = sender.send(HistorySnapshot {
            history: history_state,
            search: None,
            busy: false,
            notice: None,
            navigation: None,
            clear_rename: false,
        });

        tokio::time::sleep(undo_window + Duration::from_millis(10)).await;

        let history_state = {
            let mut history = history.lock().await;
            let _ = history.commit_delete(Instant::now()).await;
            history.state().clone()
        };

        let _ = sender.send(HistorySnapshot {
            history: history_state,
            search: None,
            busy: false,
            notice: None,
            navigation: None,
            clear_rename: false,
        });
    }));
}

fn spawn_undo(
    history: Arc<tokio::sync::Mutex<ConversationHistory>>,
    runtime: Arc<dyn HistoryRuntime>,
    sender: Sender<HistorySnapshot>,
) {
    runtime.spawn(Box::pin(async move {
        let history_state = {
            let mut history = history.lock().await;
            let _ = history.undo_delete().await;
            history.state().clone()
        };

        let _ = sender.send(HistorySnapshot {
            history: history_state,
            search: None,
            busy: false,
            notice: None,
            navigation: None,
            clear_rename: false,
        });
    }));
}

fn spawn_search(
    history: Arc<tokio::sync::Mutex<ConversationHistory>>,
    search: Arc<ConversationSearchService>,
    runtime: Arc<dyn HistoryRuntime>,
    sender: Sender<HistorySnapshot>,
    query: String,
) {
    runtime.spawn(Box::pin(async move {
        let result = search.search(&query).await;
        let search_state = ConversationSearchState::from_result(result, &query);
        let history_state = history.lock().await.state().clone();

        let _ = sender.send(HistorySnapshot {
            history: history_state,
            search: Some(search_state),
            busy: false,
            notice: None,
            navigation: None,
            clear_rename: false,
        });
    }));
}

/// Handler invoked when a conversation is selected or created.
type OpenConversationHandler = Rc<dyn Fn(&str)>;

/// Handlers for navigation out of the history view.
#[derive(Default)]
struct HistoryHandlers {
    open_conversation: Option<OpenConversationHandler>,
}

/// Owns the async history and search services and the Slint window.
pub struct HistoryController {
    history: Arc<tokio::sync::Mutex<ConversationHistory>>,
    search: Arc<ConversationSearchService>,
    runtime: Arc<dyn HistoryRuntime>,
    state: Rc<RefCell<HistoryViewState>>,
    receiver: Rc<RefCell<Receiver<HistorySnapshot>>>,
    handlers: Rc<RefCell<HistoryHandlers>>,
    sender: Sender<HistorySnapshot>,
    window: HistoryWindow,
}

impl HistoryController {
    /// Create the history window and start loading the first page.
    pub fn new(
        history: ConversationHistory,
        search: ConversationSearchService,
        runtime: Arc<dyn HistoryRuntime>,
    ) -> Result<Self, slint::PlatformError> {
        let window = HistoryWindow::new()?;
        let max_rows = history.config().max_rows;
        let undo_window = history.config().undo_window;
        let initial = history.state().clone();
        let (sender, receiver) = std::sync::mpsc::channel();
        let controller = Self {
            history: Arc::new(tokio::sync::Mutex::new(history)),
            search: Arc::new(search),
            runtime,
            state: Rc::new(RefCell::new(HistoryViewState::new(
                initial,
                max_rows,
                undo_window,
            ))),
            receiver: Rc::new(RefCell::new(receiver)),
            handlers: Rc::new(RefCell::new(HistoryHandlers::default())),
            sender,
            window,
        };

        controller.wire();
        controller.load(false);
        Ok(controller)
    }

    /// The underlying Slint window.
    pub fn window(&self) -> &HistoryWindow {
        &self.window
    }

    /// Borrow the current view state.
    pub fn state(&self) -> std::cell::Ref<'_, HistoryViewState> {
        self.state.borrow()
    }

    /// Push the current view state into the window.
    pub fn sync(&self) {
        sync_window(&self.window, &self.state.borrow());
    }

    /// Drain async results and update the window.
    ///
    /// The desktop calls this from a short Slint timer. Tests call it directly.
    pub fn poll(&self) {
        let mut navigation = None;
        let mut changed = false;

        {
            let mut view = self.state.borrow_mut();
            let receiver = self.receiver.borrow();

            while let Ok(snapshot) = receiver.try_recv() {
                view.history = snapshot.history;

                if let Some(search) = snapshot.search {
                    view.search = search;
                }

                view.busy = snapshot.busy;

                if let Some(notice) = snapshot.notice {
                    view.notice = Some(notice);
                }

                if snapshot.clear_rename {
                    view.rename = None;
                }

                if let Some(id) = snapshot.navigation {
                    navigation = Some(id);
                }

                changed = true;
            }
        }

        if changed {
            self.sync();
        }

        let handler = self.handlers.borrow().open_conversation.clone();

        if let Some(id) = navigation
            && let Some(handler) = handler
        {
            handler(&id);
        }
    }

    /// Load the active or archived history view.
    pub fn load(&self, archived: bool) {
        {
            let mut view = self.state.borrow_mut();
            view.busy = true;
            view.notice = None;
            view.history.status = HistoryStatus::Loading;
            view.history.error = None;
        }

        self.sync();
        spawn_load(
            Arc::clone(&self.history),
            Arc::clone(&self.runtime),
            self.sender.clone(),
            archived,
        );
    }

    /// Reload the current view.
    pub fn refresh(&self) {
        let archived = self.state.borrow().history.archived;
        self.load(archived);
    }

    /// Register the handler called when a conversation is selected or created.
    pub fn set_on_open_conversation(&mut self, handler: impl Fn(&str) + 'static) {
        self.handlers.borrow_mut().open_conversation = Some(Rc::new(handler));
    }

    fn wire(&self) {
        let weak = self.window.as_weak();
        let state = Rc::clone(&self.state);
        let history = Arc::clone(&self.history);
        let runtime = Arc::clone(&self.runtime);
        let sender = self.sender.clone();
        self.window.on_new_chat(move || {
            set_busy(&state, &weak, true);
            spawn_new_chat(Arc::clone(&history), Arc::clone(&runtime), sender.clone());
        });

        let weak = self.window.as_weak();
        let state = Rc::clone(&self.state);
        let history = Arc::clone(&self.history);
        let runtime = Arc::clone(&self.runtime);
        let sender = self.sender.clone();
        self.window.on_select_conversation(move |id| {
            set_busy(&state, &weak, true);
            spawn_select(
                Arc::clone(&history),
                Arc::clone(&runtime),
                sender.clone(),
                id.to_string(),
            );
        });

        let weak = self.window.as_weak();
        let state = Rc::clone(&self.state);
        let history = Arc::clone(&self.history);
        let runtime = Arc::clone(&self.runtime);
        let sender = self.sender.clone();
        self.window.on_toggle_archived(move || {
            let archived = !state.borrow().history.archived;
            {
                let mut view = state.borrow_mut();
                view.busy = true;
                view.notice = None;
                view.history.status = HistoryStatus::Loading;
                view.history.error = None;
            }

            if let Some(window) = weak.upgrade() {
                sync_window(&window, &state.borrow());
            }

            spawn_load(
                Arc::clone(&history),
                Arc::clone(&runtime),
                sender.clone(),
                archived,
            );
        });

        let weak = self.window.as_weak();
        let state = Rc::clone(&self.state);
        let history = Arc::clone(&self.history);
        let runtime = Arc::clone(&self.runtime);
        let sender = self.sender.clone();
        self.window.on_load_more(move || {
            set_busy(&state, &weak, true);
            spawn_load_more(Arc::clone(&history), Arc::clone(&runtime), sender.clone());
        });

        let weak = self.window.as_weak();
        let state = Rc::clone(&self.state);
        self.window.on_begin_rename(move |id| {
            let title = state
                .borrow()
                .history
                .conversations
                .iter()
                .find(|conversation| conversation.id == id.as_str())
                .map(|conversation| conversation.title.clone())
                .unwrap_or_default();
            state.borrow_mut().rename = Some(RenameState {
                conversation_id: id.to_string(),
                text: title,
            });

            if let Some(window) = weak.upgrade() {
                sync_window(&window, &state.borrow());
            }
        });

        let weak = self.window.as_weak();
        let state = Rc::clone(&self.state);
        self.window.on_rename_text_changed(move |text| {
            if let Some(rename) = state.borrow_mut().rename.as_mut() {
                rename.text = text.to_string();
            }

            if let Some(window) = weak.upgrade() {
                sync_window(&window, &state.borrow());
            }
        });

        let weak = self.window.as_weak();
        let state = Rc::clone(&self.state);
        let history = Arc::clone(&self.history);
        let runtime = Arc::clone(&self.runtime);
        let sender = self.sender.clone();
        self.window.on_commit_rename(move || {
            let Some(rename) = state.borrow().rename.clone() else {
                return;
            };

            set_busy(&state, &weak, true);
            spawn_rename(
                Arc::clone(&history),
                Arc::clone(&runtime),
                sender.clone(),
                rename.conversation_id,
                rename.text,
            );
        });

        let weak = self.window.as_weak();
        let state = Rc::clone(&self.state);
        self.window.on_cancel_rename(move || {
            state.borrow_mut().rename = None;

            if let Some(window) = weak.upgrade() {
                sync_window(&window, &state.borrow());
            }
        });

        let weak = self.window.as_weak();
        let state = Rc::clone(&self.state);
        let history = Arc::clone(&self.history);
        let runtime = Arc::clone(&self.runtime);
        let sender = self.sender.clone();
        self.window.on_toggle_archive(move |id| {
            let archived = state
                .borrow()
                .history
                .conversations
                .iter()
                .find(|conversation| conversation.id == id.as_str())
                .map(|conversation| !conversation.archived);
            let Some(archived) = archived else {
                return;
            };

            set_busy(&state, &weak, true);
            spawn_archive(
                Arc::clone(&history),
                Arc::clone(&runtime),
                sender.clone(),
                id.to_string(),
                archived,
            );
        });

        let weak = self.window.as_weak();
        let state = Rc::clone(&self.state);
        let history = Arc::clone(&self.history);
        let runtime = Arc::clone(&self.runtime);
        let sender = self.sender.clone();
        self.window.on_delete_conversation(move |id| {
            let undo_window = state.borrow().undo_window;
            set_busy(&state, &weak, true);
            spawn_delete(
                Arc::clone(&history),
                Arc::clone(&runtime),
                sender.clone(),
                id.to_string(),
                undo_window,
            );
        });

        let weak = self.window.as_weak();
        let state = Rc::clone(&self.state);
        let history = Arc::clone(&self.history);
        let runtime = Arc::clone(&self.runtime);
        let sender = self.sender.clone();
        self.window.on_undo_delete(move || {
            set_busy(&state, &weak, true);
            spawn_undo(Arc::clone(&history), Arc::clone(&runtime), sender.clone());
        });

        let weak = self.window.as_weak();
        let state = Rc::clone(&self.state);
        self.window.on_search_query_changed(move |text| {
            state.borrow_mut().search_query = text.to_string();

            if let Some(window) = weak.upgrade() {
                sync_window(&window, &state.borrow());
            }
        });

        let weak = self.window.as_weak();
        let state = Rc::clone(&self.state);
        let history = Arc::clone(&self.history);
        let search = Arc::clone(&self.search);
        let runtime = Arc::clone(&self.runtime);
        let sender = self.sender.clone();
        self.window.on_search(move || {
            let query = state.borrow().search_query.trim().to_owned();

            if query.is_empty() {
                state.borrow_mut().search = ConversationSearchState::Idle;

                if let Some(window) = weak.upgrade() {
                    sync_window(&window, &state.borrow());
                }

                return;
            }

            {
                let mut view = state.borrow_mut();
                view.search = ConversationSearchState::loading(&query);
                view.busy = true;
            }

            if let Some(window) = weak.upgrade() {
                sync_window(&window, &state.borrow());
            }

            spawn_search(
                Arc::clone(&history),
                Arc::clone(&search),
                Arc::clone(&runtime),
                sender.clone(),
                query,
            );
        });

        let weak = self.window.as_weak();
        let state = Rc::clone(&self.state);
        self.window.on_clear_search(move || {
            {
                let mut view = state.borrow_mut();
                view.search_query.clear();
                view.search = ConversationSearchState::Idle;
            }

            if let Some(window) = weak.upgrade() {
                sync_window(&window, &state.borrow());
            }
        });

        let weak = self.window.as_weak();
        let state = Rc::clone(&self.state);
        let history = Arc::clone(&self.history);
        let runtime = Arc::clone(&self.runtime);
        let sender = self.sender.clone();
        self.window.on_open_search_result(move |id| {
            {
                let mut view = state.borrow_mut();
                view.search_query.clear();
                view.search = ConversationSearchState::Idle;
                view.busy = true;
            }

            if let Some(window) = weak.upgrade() {
                sync_window(&window, &state.borrow());
            }

            spawn_select(
                Arc::clone(&history),
                Arc::clone(&runtime),
                sender.clone(),
                id.to_string(),
            );
        });
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::Arc;
    use std::time::Duration;

    use async_trait::async_trait;
    use chatui_domain::{
        BrowserCors, ChatParams, Conversation, Message, MessageRole, MessageStatus, Model,
        ModelCapability, ModelSource, Provider, ProviderAuthType, ProviderKind, Timestamp,
    };
    use chatui_storage::{
        ConversationRepository, MessageRepository, ModelRepository, ProviderRepository, SearchHit,
        SearchOptions, SearchRepository, SettingsRepository, SqliteConversationRepository,
        SqliteMessageRepository, SqliteModelRepository, SqliteProviderRepository,
        SqliteSearchRepository, SqliteSettingsRepository, StorageError, open_database,
        open_in_memory,
    };
    use slint::Model as _;

    use crate::chat::drafts::{DraftStore, MemoryDraftStore};
    use crate::conversations::factory::{ConversationFactory, DefaultConversationFactory};
    use crate::conversations::grouping::group_conversations;
    use crate::conversations::history::{DEFAULT_UNDO_WINDOW, HistoryConfig};
    use crate::conversations::search::{
        ConversationSearchHit, ConversationSearchResult, ConversationSearchService,
    };

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

    impl HistoryRuntime for TestRuntime {
        fn spawn(&self, future: Pin<Box<dyn Future<Output = ()> + Send + 'static>>) {
            self.runtime.spawn(future);
        }
    }

    struct Harness {
        controller: HistoryController,
        runtime: Arc<TestRuntime>,
        conversations: Arc<dyn ConversationRepository>,
        messages: Arc<dyn MessageRepository>,
        settings: Arc<dyn SettingsRepository>,
        providers: Arc<dyn ProviderRepository>,
        models: Arc<dyn ModelRepository>,
        drafts: Arc<MemoryDraftStore>,
        _directory: tempfile::TempDir,
    }

    fn ts(value: &str) -> Timestamp {
        Timestamp::parse(value).expect("valid timestamp")
    }

    fn conversation(
        id: &str,
        title: &str,
        pinned: bool,
        archived: bool,
        last_message_at: Timestamp,
    ) -> Conversation {
        Conversation {
            id: id.to_owned(),
            title: title.to_owned(),
            system_prompt: String::new(),
            provider_id: "provider-1".to_owned(),
            model_id: "model-1".to_owned(),
            model_label: "Model One".to_owned(),
            params: ChatParams::default(),
            pinned,
            archived,
            temporary: false,
            created_at: last_message_at,
            updated_at: last_message_at,
            last_message_at,
            message_count: 0,
            last_error: None,
            sync: None,
            storage_key: String::new(),
            revision: 0,
        }
    }

    fn message(id: &str, conversation_id: &str, sequence: u64, text: &str) -> Message {
        Message {
            id: id.to_owned(),
            conversation_id: conversation_id.to_owned(),
            sequence,
            role: MessageRole::User,
            parts: vec![chatui_domain::ContentPart::Text {
                text: text.to_owned(),
            }],
            status: MessageStatus::Complete,
            provider_id: Some("provider-1".to_owned()),
            model_id: Some("model-1".to_owned()),
            model_label: Some("Model One".to_owned()),
            usage: None,
            finish_reason: None,
            error: None,
            latency_ms: None,
            first_token_ms: None,
            created_at: ts("2026-11-07T09:00:00.000Z"),
            updated_at: ts("2026-11-07T09:00:00.000Z"),
            deleted_at: None,
        }
    }

    fn provider(id: &str) -> Provider {
        Provider {
            id: id.to_owned(),
            preset_id: "custom".to_owned(),
            label: format!("Provider {id}"),
            kind: ProviderKind::OpenaiCompatible,
            base_url: "https://example.com/v1".to_owned(),
            auth_type: ProviderAuthType::Bearer,
            secret_id: None,
            headers: std::collections::BTreeMap::new(),
            enabled: true,
            browser_cors: BrowserCors::Unknown,
            metadata: None,
            created_at: ts("2026-11-07T09:00:00.000Z"),
            updated_at: ts("2026-11-07T09:00:00.000Z"),
        }
    }

    fn model(id: &str, provider_id: &str) -> Model {
        Model {
            id: id.to_owned(),
            provider_id: provider_id.to_owned(),
            remote_id: "remote-model".to_owned(),
            label: "Model One".to_owned(),
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
            created_at: ts("2026-11-07T09:00:00.000Z"),
            updated_at: ts("2026-11-07T09:00:00.000Z"),
        }
    }

    fn harness_with_config(config: HistoryConfig) -> Harness {
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
        let settings: Arc<dyn SettingsRepository> =
            Arc::new(SqliteSettingsRepository::new(pool.clone()));
        let providers: Arc<dyn ProviderRepository> =
            Arc::new(SqliteProviderRepository::new(pool.clone()));
        let models: Arc<dyn ModelRepository> = Arc::new(SqliteModelRepository::new(pool.clone()));
        let search_repository: Arc<dyn SearchRepository> =
            Arc::new(SqliteSearchRepository::new(pool));
        let drafts = Arc::new(MemoryDraftStore::new());
        let factory: Arc<dyn ConversationFactory> = Arc::new(DefaultConversationFactory::new(
            Arc::clone(&conversations),
            Arc::clone(&providers),
            Arc::clone(&models),
            Arc::clone(&settings),
        ));
        let history = ConversationHistory::new(
            Arc::clone(&conversations),
            Arc::clone(&settings),
            drafts.clone(),
            factory,
            config,
        );
        let search = ConversationSearchService::new(search_repository, Arc::clone(&conversations));
        let controller = HistoryController::new(history, search, runtime.clone())
            .expect("create history controller");

        Harness {
            controller,
            runtime,
            conversations,
            messages,
            settings,
            providers,
            models,
            drafts,
            _directory: directory,
        }
    }

    fn harness() -> Harness {
        harness_with_config(HistoryConfig::default())
    }

    fn wait_until(
        controller: &HistoryController,
        mut predicate: impl FnMut(&HistoryViewState) -> bool,
    ) {
        for _ in 0..300 {
            controller.poll();

            if predicate(&controller.state()) {
                return;
            }

            std::thread::sleep(Duration::from_millis(10));
        }

        panic!(
            "timed out waiting for history state: {:?}",
            controller.state()
        );
    }

    fn wait_idle(controller: &HistoryController) {
        wait_until(controller, |view| !view.busy);
    }

    fn seed_provider_and_model(harness: &Harness) {
        harness
            .runtime
            .runtime
            .block_on(async {
                harness.providers.insert(&provider("p1")).await?;
                harness.models.insert(&model("m1", "p1")).await?;
                let mut settings = harness.settings.load().await?;
                settings.default_provider_id = Some("p1".to_owned());
                settings.default_model_id = Some("m1".to_owned());
                harness.settings.save(&settings).await
            })
            .expect("seed provider and model");
    }

    #[test]
    fn controller_loads_first_page_and_groups() {
        let harness = harness();
        wait_idle(&harness.controller);
        let now = Timestamp::now();
        harness
            .runtime
            .runtime
            .block_on(async {
                harness
                    .conversations
                    .create(&conversation("today", "Today", false, false, now))
                    .await?;
                harness
                    .conversations
                    .create(&conversation(
                        "older",
                        "Older",
                        false,
                        false,
                        ts("2020-01-01T00:00:00.000Z"),
                    ))
                    .await
            })
            .expect("seed conversations");
        harness.drafts.set_draft("today", "draft text");
        harness.controller.refresh();
        wait_until(&harness.controller, |view| {
            view.history.status == HistoryStatus::Ready && !view.busy
        });

        let window = harness.controller.window();
        let rows = window.get_rows();
        assert_eq!(rows.row_count(), 4);
        assert!(rows.row_data(0).expect("header").is_header);
        assert_eq!(rows.row_data(0).expect("header").title.as_str(), "Today");
        assert_eq!(rows.row_data(1).expect("row").id.as_str(), "today");
        assert!(rows.row_data(1).expect("row").draft);
        assert!(rows.row_data(2).expect("header").is_header);
        assert_eq!(rows.row_data(2).expect("header").title.as_str(), "Older");
        assert_eq!(rows.row_data(3).expect("row").id.as_str(), "older");
    }

    #[test]
    fn new_chat_creates_and_selects() {
        let mut harness = harness();
        wait_idle(&harness.controller);
        seed_provider_and_model(&harness);
        let opened = Arc::new(std::sync::Mutex::new(Vec::new()));
        let recorded = Arc::clone(&opened);
        harness.controller.set_on_open_conversation(move |id| {
            recorded.lock().expect("opened").push(id.to_owned());
        });

        harness.controller.window().invoke_new_chat();
        wait_until(&harness.controller, |view| {
            view.history.active_conversation_id.is_some() && !view.busy
        });

        let active = harness
            .controller
            .state()
            .history
            .active_conversation_id
            .clone()
            .expect("active conversation");
        assert_eq!(
            opened.lock().expect("opened").as_slice(),
            std::slice::from_ref(&active)
        );
        assert!(
            harness
                .runtime
                .runtime
                .block_on(harness.conversations.get(&active))
                .expect("get conversation")
                .is_some()
        );
    }

    #[test]
    fn rename_persists() {
        let harness = harness();
        wait_idle(&harness.controller);
        let now = Timestamp::now();
        harness
            .runtime
            .runtime
            .block_on(
                harness
                    .conversations
                    .create(&conversation("c1", "Old", false, false, now)),
            )
            .expect("create conversation");
        harness.controller.refresh();
        wait_until(&harness.controller, |view| {
            view.history.conversations.len() == 1 && !view.busy
        });

        harness.controller.window().invoke_begin_rename("c1".into());
        harness
            .controller
            .window()
            .invoke_rename_text_changed("New title".into());
        harness.controller.window().invoke_commit_rename();
        wait_until(&harness.controller, |view| {
            view.history
                .conversations
                .iter()
                .any(|conversation| conversation.id == "c1" && conversation.title == "New title")
                && !view.busy
        });

        let stored = harness
            .runtime
            .runtime
            .block_on(harness.conversations.get("c1"))
            .expect("get conversation")
            .expect("conversation");
        assert_eq!(stored.title, "New title");
        assert!(harness.controller.state().rename.is_none());
    }

    #[test]
    fn archive_persists() {
        let harness = harness();
        wait_idle(&harness.controller);
        let now = Timestamp::now();
        harness
            .runtime
            .runtime
            .block_on(
                harness
                    .conversations
                    .create(&conversation("c1", "Chat", false, false, now)),
            )
            .expect("create conversation");
        harness.controller.refresh();
        wait_until(&harness.controller, |view| {
            view.history.conversations.len() == 1 && !view.busy
        });

        harness
            .controller
            .window()
            .invoke_toggle_archive("c1".into());
        wait_until(&harness.controller, |view| {
            view.history.conversations.is_empty() && !view.busy
        });

        let stored = harness
            .runtime
            .runtime
            .block_on(harness.conversations.get("c1"))
            .expect("get conversation")
            .expect("conversation");
        assert!(stored.archived);
    }

    #[test]
    fn delete_undo_restores() {
        let harness = harness();
        wait_idle(&harness.controller);
        let now = Timestamp::now();
        harness
            .runtime
            .runtime
            .block_on(
                harness
                    .conversations
                    .create(&conversation("c1", "Chat", false, false, now)),
            )
            .expect("create conversation");
        harness.controller.refresh();
        wait_until(&harness.controller, |view| {
            view.history.conversations.len() == 1 && !view.busy
        });

        harness
            .controller
            .window()
            .invoke_delete_conversation("c1".into());
        wait_until(&harness.controller, |view| {
            view.history.pending_delete.is_some() && !view.busy
        });
        harness.controller.window().invoke_undo_delete();
        wait_until(&harness.controller, |view| {
            view.history.pending_delete.is_none()
                && view.history.conversations.len() == 1
                && !view.busy
        });

        assert!(
            harness
                .runtime
                .runtime
                .block_on(harness.conversations.get("c1"))
                .expect("get conversation")
                .is_some()
        );
    }

    #[test]
    fn delete_commits_after_undo_window() {
        let harness = harness_with_config(HistoryConfig {
            undo_window: Duration::from_millis(50),
            ..HistoryConfig::default()
        });
        wait_idle(&harness.controller);
        let now = Timestamp::now();
        harness
            .runtime
            .runtime
            .block_on(
                harness
                    .conversations
                    .create(&conversation("c1", "Chat", false, false, now)),
            )
            .expect("create conversation");
        harness.controller.refresh();
        wait_until(&harness.controller, |view| {
            view.history.conversations.len() == 1 && !view.busy
        });

        harness
            .controller
            .window()
            .invoke_delete_conversation("c1".into());
        wait_until(&harness.controller, |view| {
            view.history.pending_delete.is_none()
                && view.history.conversations.is_empty()
                && !view.busy
        });

        assert!(
            harness
                .runtime
                .runtime
                .block_on(harness.conversations.get("c1"))
                .expect("get conversation")
                .is_none()
        );
    }

    #[test]
    fn search_returns_bounded_results() {
        let harness = harness();
        wait_idle(&harness.controller);
        let now = Timestamp::now();
        harness
            .runtime
            .runtime
            .block_on(async {
                harness
                    .conversations
                    .create(&conversation("c1", "Alpha chat", false, false, now))
                    .await?;

                for index in 0..5 {
                    harness
                        .messages
                        .insert(&message(
                            &format!("m{index}"),
                            "c1",
                            index,
                            "alpha beta gamma",
                        ))
                        .await?;
                }

                Ok::<(), StorageError>(())
            })
            .expect("seed messages");

        harness
            .controller
            .window()
            .invoke_search_query_changed("alpha".into());
        harness.controller.window().invoke_search();
        wait_until(&harness.controller, |view| {
            matches!(view.search, ConversationSearchState::Ready(_)) && !view.busy
        });

        let window = harness.controller.window();
        assert_eq!(window.get_search_results().row_count(), 5);
        assert!(
            window
                .get_search_results()
                .row_data(0)
                .expect("search row")
                .snippet
                .contains("alpha")
        );
    }

    #[test]
    fn search_empty_state_is_reported() {
        let harness = harness();
        wait_idle(&harness.controller);
        harness
            .controller
            .window()
            .invoke_search_query_changed("zzzz".into());
        harness.controller.window().invoke_search();
        wait_until(&harness.controller, |view| {
            matches!(view.search, ConversationSearchState::Empty { .. }) && !view.busy
        });

        assert!(!harness.controller.window().get_search_status().is_empty());
    }

    struct FailingSearch;

    #[async_trait]
    impl SearchRepository for FailingSearch {
        async fn search(
            &self,
            _query: &str,
            _options: &SearchOptions,
        ) -> Result<Vec<SearchHit>, StorageError> {
            Err(StorageError::InvalidInput("boom".to_owned()))
        }
    }

    #[test]
    fn search_error_is_content_free() {
        i_slint_backend_testing::init_no_event_loop();
        let runtime = Arc::new(TestRuntime::new());
        let pool = runtime
            .runtime
            .block_on(open_in_memory())
            .expect("open in-memory database");
        let conversations: Arc<dyn ConversationRepository> =
            Arc::new(SqliteConversationRepository::new(pool.clone()));
        let settings: Arc<dyn SettingsRepository> =
            Arc::new(SqliteSettingsRepository::new(pool.clone()));
        let providers: Arc<dyn ProviderRepository> =
            Arc::new(SqliteProviderRepository::new(pool.clone()));
        let models: Arc<dyn ModelRepository> = Arc::new(SqliteModelRepository::new(pool.clone()));
        let drafts = Arc::new(MemoryDraftStore::new());
        let factory: Arc<dyn ConversationFactory> = Arc::new(DefaultConversationFactory::new(
            Arc::clone(&conversations),
            providers,
            models,
            Arc::clone(&settings),
        ));
        let history = ConversationHistory::new(
            Arc::clone(&conversations),
            settings,
            drafts,
            factory,
            HistoryConfig::default(),
        );
        let search = ConversationSearchService::new(Arc::new(FailingSearch), conversations);
        let controller =
            HistoryController::new(history, search, runtime).expect("create history controller");
        wait_until(&controller, |view| {
            view.history.status == HistoryStatus::Ready && !view.busy
        });

        controller
            .window()
            .invoke_search_query_changed("secret message content".into());
        controller.window().invoke_search();
        wait_until(&controller, |view| {
            matches!(view.search, ConversationSearchState::Error { .. }) && !view.busy
        });

        let status = controller.window().get_search_status().to_string();
        assert_eq!(status, "Search is unavailable right now. Try again.");
        assert!(!status.contains("secret"));
    }

    #[test]
    fn draft_badges_appear() {
        let harness = harness();
        wait_idle(&harness.controller);
        let now = Timestamp::now();
        harness
            .runtime
            .runtime
            .block_on(
                harness
                    .conversations
                    .create(&conversation("c1", "Chat", false, false, now)),
            )
            .expect("create conversation");
        harness.drafts.set_draft("c1", "draft text");
        harness.controller.refresh();
        wait_until(&harness.controller, |view| {
            view.history.conversations.len() == 1 && !view.busy
        });

        let rows = harness.controller.window().get_rows();
        let row = (0..rows.row_count())
            .find_map(|index| {
                let row = rows.row_data(index)?;
                (!row.is_header && row.id == "c1").then_some(row)
            })
            .expect("conversation row");
        assert!(row.draft);
    }

    #[test]
    fn load_more_grows_window() {
        let harness = harness_with_config(HistoryConfig {
            page_size: 50,
            max_rows: 100,
            undo_window: DEFAULT_UNDO_WINDOW,
        });
        wait_idle(&harness.controller);
        let now = Timestamp::now();
        harness
            .runtime
            .runtime
            .block_on(async {
                for index in 0..150 {
                    harness
                        .conversations
                        .create(&conversation(
                            &format!("c{index}"),
                            "Chat",
                            false,
                            false,
                            now,
                        ))
                        .await?;
                }

                Ok::<(), StorageError>(())
            })
            .expect("seed conversations");
        harness.controller.refresh();
        wait_until(&harness.controller, |view| {
            view.history.conversations.len() == 50 && !view.busy
        });
        assert!(harness.controller.window().get_can_load_more());

        harness.controller.window().invoke_load_more();
        wait_until(&harness.controller, |view| {
            view.history.conversations.len() == 100 && !view.busy
        });
        assert!(!harness.controller.window().get_can_load_more());
    }

    #[test]
    fn navigation_handler_receives_selection() {
        let mut harness = harness();
        wait_idle(&harness.controller);
        let now = Timestamp::now();
        harness
            .runtime
            .runtime
            .block_on(
                harness
                    .conversations
                    .create(&conversation("c1", "Chat", false, false, now)),
            )
            .expect("create conversation");
        harness.controller.refresh();
        wait_until(&harness.controller, |view| {
            view.history.conversations.len() == 1 && !view.busy
        });

        let opened = Arc::new(std::sync::Mutex::new(Vec::new()));
        let recorded = Arc::clone(&opened);
        harness.controller.set_on_open_conversation(move |id| {
            recorded.lock().expect("opened").push(id.to_owned());
        });

        harness
            .controller
            .window()
            .invoke_select_conversation("c1".into());
        wait_until(&harness.controller, |view| {
            view.history.active_conversation_id.as_deref() == Some("c1") && !view.busy
        });

        assert_eq!(
            opened.lock().expect("opened").as_slice(),
            &["c1".to_owned()]
        );
    }

    #[test]
    fn empty_loading_and_error_states() {
        let harness = harness();
        assert!(harness.controller.window().get_loading());
        wait_until(&harness.controller, |view| {
            view.history.status == HistoryStatus::Ready && !view.busy
        });
        assert!(harness.controller.window().get_empty());
    }

    #[test]
    fn load_error_sets_error_state() {
        i_slint_backend_testing::init_no_event_loop();
        let runtime = Arc::new(TestRuntime::new());
        let pool = runtime
            .runtime
            .block_on(open_in_memory())
            .expect("open in-memory database");
        let conversations: Arc<dyn ConversationRepository> =
            Arc::new(SqliteConversationRepository::new(pool.clone()));
        let settings: Arc<dyn SettingsRepository> =
            Arc::new(SqliteSettingsRepository::new(pool.clone()));
        let providers: Arc<dyn ProviderRepository> =
            Arc::new(SqliteProviderRepository::new(pool.clone()));
        let models: Arc<dyn ModelRepository> = Arc::new(SqliteModelRepository::new(pool.clone()));
        let search_repository: Arc<dyn SearchRepository> =
            Arc::new(SqliteSearchRepository::new(pool.clone()));
        let drafts = Arc::new(MemoryDraftStore::new());
        let factory: Arc<dyn ConversationFactory> = Arc::new(DefaultConversationFactory::new(
            Arc::clone(&conversations),
            providers,
            models,
            Arc::clone(&settings),
        ));
        let history = ConversationHistory::new(
            Arc::clone(&conversations),
            settings,
            drafts,
            factory,
            HistoryConfig::default(),
        );
        let search = ConversationSearchService::new(search_repository, conversations);
        runtime.runtime.block_on(pool.close());
        let controller =
            HistoryController::new(history, search, runtime).expect("create history controller");
        wait_until(&controller, |view| {
            view.history.status == HistoryStatus::Error && !view.busy
        });

        assert!(!controller.window().get_error().is_empty());
    }

    #[test]
    fn sync_window_maps_groups_and_search() {
        i_slint_backend_testing::init_no_event_loop();
        let window = HistoryWindow::new().expect("create history window");
        let now = Timestamp::now();
        let conversations = vec![
            conversation("today", "Today", false, false, now),
            conversation(
                "older",
                "Older",
                false,
                false,
                ts("2020-01-01T00:00:00.000Z"),
            ),
        ];
        let groups = group_conversations(&conversations, now);
        let mut history = HistoryState::new();
        history.status = HistoryStatus::Ready;
        history.conversations = conversations;
        history.groups = groups;
        history.active_conversation_id = Some("today".to_owned());
        history.draft_ids.insert("today".to_owned());
        let mut view = HistoryViewState::new(history, 500, DEFAULT_UNDO_WINDOW);
        view.search = ConversationSearchState::Ready(ConversationSearchResult {
            query: "alpha".to_owned(),
            hits: vec![ConversationSearchHit {
                conversation_id: "today".to_owned(),
                conversation_title: "Today".to_owned(),
                message_id: "m1".to_owned(),
                snippet: "alpha beta".to_owned(),
                rank: 1.0,
                sequence: 0,
            }],
            truncated: true,
        });

        sync_window(&window, &view);

        let rows = window.get_rows();
        assert_eq!(rows.row_count(), 4);
        assert!(rows.row_data(0).expect("header").is_header);
        assert_eq!(rows.row_data(1).expect("row").id.as_str(), "today");
        assert!(rows.row_data(1).expect("row").active);
        assert!(rows.row_data(1).expect("row").draft);
        assert_eq!(window.get_search_results().row_count(), 1);
        assert!(window.get_search_truncated());
        assert!(!window.get_search_status().is_empty());
    }
}
