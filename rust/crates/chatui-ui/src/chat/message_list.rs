//! Windowed message list, pagination, and scroll anchoring.
//!
//! [`MessageListState`] is framework-independent: it owns the loaded history,
//! the measured row heights, the visible window, pagination cursors, and the
//! scroll policy. [`MessageListController`] binds that state to a Slint window.
//! [`MessageListLoader`] reads bounded pages from the message repository.
//!
//! The list never renders every message. Only the rows inside the viewport plus
//! overscan are composed, and the streaming row is a separate property so token
//! updates do not rebuild completed history.

use std::cell::{Cell, Ref, RefCell};
use std::collections::BTreeMap;
use std::rc::Rc;
use std::sync::Arc;

use chatui_domain::{Conversation, Message, MessageRole, MessageStatus};
use chatui_storage::{MessagePage, MessageRepository, StorageError};
use slint::{ComponentHandle, ModelRc, SharedString, VecModel};

use crate::accessibility::Announcement;
use crate::chat::announcement::{initial_announcement, streaming_announcement};
use crate::chat::message_item::{MessageAction, MessageRow, MessageRowOptions, message_text};
use crate::chat::runner::can_retry;
use crate::chat::state::StreamingState;

/// Default number of messages loaded per page.
pub const DEFAULT_PAGE_SIZE: u32 = 50;
/// Default estimated row height in logical pixels.
pub const DEFAULT_ESTIMATE_HEIGHT: f32 = 120.0;
/// Default number of rows rendered outside the viewport.
pub const DEFAULT_OVERSCAN: usize = 4;
/// Default viewport height in logical pixels.
pub const DEFAULT_VIEWPORT_HEIGHT: f32 = 800.0;
/// Distance from the bottom that still counts as "near the bottom".
pub const DEFAULT_NEAR_BOTTOM_PX: f32 = 80.0;
/// Distance from the top that triggers older-page loading.
pub const DEFAULT_LOAD_OLDER_PX: f32 = 120.0;
/// Hard cap on composed rows, independent of viewport size.
pub const MAX_WINDOW_ROWS: usize = 64;

/// Windowing, pagination, and scroll policy.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct MessageListConfig {
    /// Messages loaded per page.
    pub page_size: u32,
    /// Estimated height of an unmeasured row.
    pub estimate_height: f32,
    /// Rows rendered outside the viewport.
    pub overscan: usize,
    /// Viewport height in logical pixels.
    pub viewport_height: f32,
    /// Distance from the bottom that still counts as near the bottom.
    pub near_bottom_threshold: f32,
    /// Distance from the top that triggers older-page loading.
    pub load_older_threshold: f32,
    /// Hard cap on composed rows.
    pub max_window_rows: usize,
}

impl Default for MessageListConfig {
    fn default() -> Self {
        Self {
            page_size: DEFAULT_PAGE_SIZE,
            estimate_height: DEFAULT_ESTIMATE_HEIGHT,
            overscan: DEFAULT_OVERSCAN,
            viewport_height: DEFAULT_VIEWPORT_HEIGHT,
            near_bottom_threshold: DEFAULT_NEAR_BOTTOM_PX,
            load_older_threshold: DEFAULT_LOAD_OLDER_PX,
            max_window_rows: MAX_WINDOW_ROWS,
        }
    }
}

/// A list-level load error shown in the error banner.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct MessageListError {
    /// Short heading.
    pub title: String,
    /// Actionable message.
    pub message: String,
    /// Optional non-secret details.
    pub details: Option<String>,
}

impl MessageListError {
    /// Build an error with the default heading.
    pub fn new(message: impl Into<String>) -> Self {
        Self {
            title: "Could not load messages".to_owned(),
            message: message.into(),
            details: None,
        }
    }

    /// Build an error from a storage failure.
    pub fn from_storage(error: &StorageError) -> Self {
        Self::new(error.to_string())
    }
}

/// Framework-independent message list state.
#[derive(Debug, Clone, PartialEq)]
pub struct MessageListState {
    conversation_id: String,
    config: MessageListConfig,
    conversation: Option<Conversation>,
    messages: Vec<Message>,
    heights: BTreeMap<String, f32>,
    scroll_top: f32,
    viewport_height: f32,
    near_bottom: bool,
    has_older: bool,
    loading_older: bool,
    loading_newest: bool,
    error: Option<MessageListError>,
    pending_anchor: Option<f32>,
    streaming: Option<StreamingState>,
    announcement: Option<Announcement>,
    show_token_usage: bool,
}

impl MessageListState {
    /// Create an empty list for a conversation.
    pub fn new(conversation_id: impl Into<String>, config: MessageListConfig) -> Self {
        Self {
            conversation_id: conversation_id.into(),
            config,
            conversation: None,
            messages: Vec::new(),
            heights: BTreeMap::new(),
            scroll_top: 0.0,
            viewport_height: config.viewport_height,
            near_bottom: true,
            has_older: false,
            loading_older: false,
            loading_newest: true,
            error: None,
            pending_anchor: None,
            streaming: None,
            announcement: None,
            show_token_usage: true,
        }
    }

    /// Owning conversation ID.
    pub fn conversation_id(&self) -> &str {
        &self.conversation_id
    }

    /// Current configuration.
    pub fn config(&self) -> MessageListConfig {
        self.config
    }

    /// Loaded history in ascending sequence order.
    pub fn messages(&self) -> &[Message] {
        &self.messages
    }

    /// Number of loaded history messages.
    pub fn message_count(&self) -> usize {
        self.messages.len()
    }

    /// Whether older pages are available.
    pub fn has_older(&self) -> bool {
        self.has_older
    }

    /// Whether an older page is in flight.
    pub fn loading_older(&self) -> bool {
        self.loading_older
    }

    /// Whether the initial newest page is loading.
    pub fn is_loading(&self) -> bool {
        self.loading_newest && self.messages.is_empty()
    }

    /// Whether the list has no history and no active stream.
    pub fn is_empty(&self) -> bool {
        self.messages.is_empty() && self.streaming.is_none()
    }

    /// Current list-level error.
    pub fn error(&self) -> Option<&MessageListError> {
        self.error.as_ref()
    }

    /// Current scroll offset.
    pub fn scroll_top(&self) -> f32 {
        self.scroll_top
    }

    /// Current viewport height.
    pub fn viewport_height(&self) -> f32 {
        self.viewport_height
    }

    /// Whether the viewport is near the bottom.
    pub fn near_bottom(&self) -> bool {
        self.near_bottom
    }

    /// Whether the list should follow new content.
    pub fn should_auto_scroll(&self) -> bool {
        self.near_bottom
    }

    /// Whether token usage is shown in metadata.
    pub fn show_token_usage(&self) -> bool {
        self.show_token_usage
    }

    /// Current streaming snapshot.
    pub fn streaming(&self) -> Option<&StreamingState> {
        self.streaming.as_ref()
    }

    /// Set the conversation used for streaming metadata.
    pub fn set_conversation(&mut self, conversation: Option<Conversation>) {
        self.conversation = conversation;
    }

    /// Show or hide token usage in metadata.
    pub fn set_show_token_usage(&mut self, show: bool) {
        self.show_token_usage = show;
    }

    /// Update the viewport height and clamp the scroll offset.
    pub fn set_viewport_height(&mut self, height: f32) {
        self.viewport_height = height.max(0.0);
        self.scroll_top = self.scroll_top.clamp(0.0, self.max_scroll_top());
        self.update_near_bottom();
    }

    /// Mark the initial newest page as loading or loaded.
    pub fn set_loading_newest(&mut self, loading: bool) {
        self.loading_newest = loading;

        if loading {
            self.error = None;
        }
    }

    /// Replace the list with the newest page.
    pub fn apply_newest_page(&mut self, page: MessagePage) {
        self.messages = normalize(page.messages);
        self.has_older = page.has_more;
        self.loading_newest = false;
        self.loading_older = false;
        self.error = None;
        self.pending_anchor = None;
        self.near_bottom = true;
        self.scroll_top = self.max_scroll_top();
    }

    /// Merge an older page and preserve the visible position.
    pub fn apply_older_page(&mut self, page: MessagePage) {
        let anchor = self.pending_anchor.take();
        self.messages = merge_messages(std::mem::take(&mut self.messages), page.messages);
        self.has_older = page.has_more;
        self.loading_older = false;
        self.error = None;

        if let Some(offset_from_bottom) = anchor {
            self.scroll_top = (self.content_height() - offset_from_bottom).max(0.0);
        }

        self.update_near_bottom();
    }

    /// Insert or replace one message, keeping sequence order.
    pub fn merge_message(&mut self, message: Message) {
        self.messages = merge_messages(std::mem::take(&mut self.messages), vec![message]);

        if self.near_bottom {
            self.scroll_top = self.max_scroll_top();
        }
    }

    /// Start an older-page load and return the cursor, if allowed.
    ///
    /// The cursor is the oldest loaded sequence. The current distance from the
    /// bottom is recorded so [`Self::apply_older_page`] can restore the visible
    /// position after the prepend.
    pub fn begin_load_older(&mut self) -> Option<u64> {
        if !self.has_older || self.loading_older {
            return None;
        }

        let cursor = self.oldest_sequence()?;
        self.loading_older = true;
        self.pending_anchor = Some((self.content_height() - self.scroll_top).max(0.0));
        Some(cursor)
    }

    /// Update the scroll offset and report whether an older page should load.
    pub fn handle_scroll(&mut self, scroll_top: f32) -> bool {
        self.scroll_top = scroll_top.clamp(0.0, self.max_scroll_top());
        self.update_near_bottom();
        self.scroll_top < self.config.load_older_threshold && self.has_older && !self.loading_older
    }

    /// Jump to the bottom and re-enable auto-scroll.
    pub fn scroll_to_bottom(&mut self) {
        self.scroll_top = self.max_scroll_top();
        self.near_bottom = true;
    }

    /// Set or clear the isolated streaming snapshot.
    ///
    /// The latest bounded live-region announcement is retained so screen
    /// readers can read the last start, completion, cancellation, or error
    /// message without hearing every token.
    pub fn set_streaming(&mut self, streaming: Option<StreamingState>) {
        if let Some(announcement) = match (&self.streaming, &streaming) {
            (Some(previous), Some(next)) => streaming_announcement(previous, next),
            (None, Some(next)) => initial_announcement(next),
            (Some(_), None) | (None, None) => None,
        } {
            self.announcement = Some(announcement);
        }

        self.streaming = streaming;

        if self.near_bottom {
            self.scroll_top = self.max_scroll_top();
        } else {
            self.scroll_top = self.scroll_top.clamp(0.0, self.max_scroll_top());
        }

        self.update_near_bottom();
    }

    /// Clear the isolated streaming snapshot.
    pub fn clear_streaming(&mut self) {
        self.set_streaming(None);
    }

    /// Latest bounded live-region announcement, if any.
    pub fn announcement(&self) -> Option<&Announcement> {
        self.announcement.as_ref()
    }

    /// Show a list-level error.
    pub fn set_error(&mut self, error: MessageListError) {
        self.error = Some(error);
        self.loading_newest = false;
        self.loading_older = false;
    }

    /// Clear the list-level error.
    pub fn clear_error(&mut self) {
        self.error = None;
    }

    /// Record a measured row height.
    pub fn measure_row(&mut self, id: &str, height: f32) {
        if height <= 0.0 {
            return;
        }

        if self
            .heights
            .get(id)
            .is_some_and(|previous| (previous - height).abs() < 1.0)
        {
            return;
        }

        self.heights.insert(id.to_owned(), height);
        self.scroll_top = self.scroll_top.clamp(0.0, self.max_scroll_top());
        self.update_near_bottom();
    }

    /// Total height of the loaded history.
    pub fn total_height(&self) -> f32 {
        let streaming_id = self
            .streaming
            .as_ref()
            .map(|state| state.message_id.as_str());

        self.messages
            .iter()
            .filter(|message| Some(message.id.as_str()) != streaming_id)
            .map(|message| self.row_height(&message.id))
            .sum()
    }

    /// Estimated height of the isolated streaming row.
    pub fn streaming_height(&self) -> f32 {
        if self.streaming.is_some() {
            self.config.estimate_height
        } else {
            0.0
        }
    }

    /// Total height of the scroll content, including the streaming row.
    pub fn content_height(&self) -> f32 {
        self.total_height() + self.streaming_height()
    }

    /// Maximum scroll offset for the current content and viewport.
    pub fn max_scroll_top(&self) -> f32 {
        (self.content_height() - self.viewport_height).max(0.0)
    }

    /// Oldest loaded sequence, used as the older-page cursor.
    pub fn oldest_sequence(&self) -> Option<u64> {
        self.messages.iter().map(|message| message.sequence).min()
    }

    /// Newest loaded sequence.
    pub fn newest_sequence(&self) -> Option<u64> {
        self.messages.iter().map(|message| message.sequence).max()
    }

    /// Text of a loaded or streaming message.
    pub fn message_text(&self, id: &str) -> Option<String> {
        self.messages
            .iter()
            .find(|message| message.id == id)
            .map(message_text)
            .or_else(|| {
                self.streaming
                    .as_ref()
                    .filter(|state| state.message_id == id)
                    .map(|state| state.text.clone())
            })
    }

    /// The first history row at or below the viewport top.
    ///
    /// This is the row a scroll-anchoring check should compare: the composed
    /// window also includes overscan rows above the viewport.
    pub fn viewport_top_id(&self) -> Option<&str> {
        let messages = self.visible_messages();
        let mut offset = 0.0;

        for message in &messages {
            let height = self.row_height(&message.id);

            if offset + height > self.scroll_top {
                return Some(message.id.as_str());
            }

            offset += height;
        }

        None
    }

    /// The half-open range of history rows inside the window.
    pub fn visible_range(&self) -> (usize, usize) {
        let messages = self.visible_messages();
        let count = messages.len();

        if count == 0 {
            return (0, 0);
        }

        if self.viewport_height <= 0.0 {
            return (0, count.min(self.config.max_window_rows.max(1)));
        }

        let mut offsets = Vec::with_capacity(count + 1);
        offsets.push(0.0);

        for message in &messages {
            let next = offsets[offsets.len() - 1] + self.row_height(&message.id);
            offsets.push(next);
        }

        let overscan = self.config.overscan as f32 * self.config.estimate_height;
        let start = find_start(&offsets, self.scroll_top - overscan);
        let end = find_end(&offsets, self.scroll_top + self.viewport_height + overscan);
        let end = end.min(count);
        let start = start.min(end);
        let end = end.min(start + self.config.max_window_rows.max(1));

        (start, end)
    }

    /// Rows composed for the current window.
    pub fn visible_rows(&self) -> Vec<MessageRow> {
        let messages = self.visible_messages();
        let (start, end) = self.visible_range();
        let last_assistant = messages
            .iter()
            .rev()
            .find(|message| message.role == MessageRole::Assistant)
            .map(|message| message.id.as_str());
        let is_streaming = self.streaming.as_ref().is_some_and(|state| !state.done);

        messages[start..end]
            .iter()
            .map(|message| {
                let options = MessageRowOptions {
                    show_token_usage: self.show_token_usage,
                    can_regenerate: !is_streaming && Some(message.id.as_str()) == last_assistant,
                    can_retry: !is_streaming
                        && message.role == MessageRole::Assistant
                        && message.error.is_some()
                        && can_retry(message),
                    is_active_stream: false,
                };

                MessageRow::from_message(message, options)
            })
            .collect()
    }

    /// The isolated streaming row, when a stream is active or just finished.
    pub fn streaming_row(&self) -> Option<MessageRow> {
        let state = self.streaming.as_ref()?;
        let options = MessageRowOptions {
            show_token_usage: self.show_token_usage,
            can_regenerate: false,
            can_retry: state.done && state.error.is_some(),
            is_active_stream: !state.done,
        };

        Some(MessageRow::from_streaming(
            state,
            self.conversation.as_ref(),
            options,
        ))
    }

    /// Height of the rows above the window.
    pub fn top_padding(&self) -> f32 {
        let messages = self.visible_messages();
        let (start, _) = self.visible_range();

        messages
            .iter()
            .take(start)
            .map(|message| self.row_height(&message.id))
            .sum()
    }

    /// Height of the rows below the window.
    pub fn bottom_padding(&self) -> f32 {
        let messages = self.visible_messages();
        let (_, end) = self.visible_range();

        messages
            .iter()
            .skip(end)
            .map(|message| self.row_height(&message.id))
            .sum()
    }

    fn visible_messages(&self) -> Vec<&Message> {
        let streaming_id = self
            .streaming
            .as_ref()
            .map(|state| state.message_id.as_str());

        self.messages
            .iter()
            .filter(|message| Some(message.id.as_str()) != streaming_id)
            .collect()
    }

    fn row_height(&self, id: &str) -> f32 {
        self.heights
            .get(id)
            .copied()
            .unwrap_or(self.config.estimate_height)
            .max(1.0)
    }

    fn update_near_bottom(&mut self) {
        let distance = self.content_height() - self.scroll_top - self.viewport_height;
        self.near_bottom = distance <= self.config.near_bottom_threshold;
    }
}

/// Bounded page loader over the message repository.
pub struct MessageListLoader {
    messages: Arc<dyn MessageRepository>,
    page_size: u32,
}

impl MessageListLoader {
    /// Build a loader with a bounded page size.
    pub fn new(messages: Arc<dyn MessageRepository>, page_size: u32) -> Self {
        Self {
            messages,
            page_size: page_size.max(1),
        }
    }

    /// Page size used by the loader.
    pub fn page_size(&self) -> u32 {
        self.page_size
    }

    /// Load the newest page of a conversation.
    pub async fn load_newest(&self, conversation_id: &str) -> Result<MessagePage, StorageError> {
        self.messages
            .newest_page(conversation_id, self.page_size)
            .await
    }

    /// Load the page immediately older than `before_sequence`.
    pub async fn load_older(
        &self,
        conversation_id: &str,
        before_sequence: u64,
    ) -> Result<MessagePage, StorageError> {
        self.messages
            .older_page(conversation_id, before_sequence, self.page_size)
            .await
    }
}

fn normalize(messages: Vec<Message>) -> Vec<Message> {
    merge_messages(Vec::new(), messages)
}

fn merge_messages(existing: Vec<Message>, incoming: Vec<Message>) -> Vec<Message> {
    let mut by_id: BTreeMap<String, Message> = BTreeMap::new();

    for message in existing.into_iter().chain(incoming) {
        by_id.insert(message.id.clone(), message);
    }

    let mut messages: Vec<Message> = by_id.into_values().collect();
    messages.sort_by_key(|message| message.sequence);
    messages
}

fn find_start(offsets: &[f32], value: f32) -> usize {
    let mut low = 0;
    let mut high = offsets.len().saturating_sub(1);

    while low < high {
        let middle = (low + high) / 2;

        if offsets[middle] < value {
            low = middle + 1;
        } else {
            high = middle;
        }
    }

    low.saturating_sub(1)
}

fn find_end(offsets: &[f32], value: f32) -> usize {
    let mut low = 0;
    let mut high = offsets.len().saturating_sub(1);

    while low < high {
        let middle = (low + high) / 2;

        if offsets[middle] <= value {
            low = middle + 1;
        } else {
            high = middle;
        }
    }

    low.min(offsets.len().saturating_sub(1))
}

slint::slint! {
    import { Button, ScrollView } from "std-widgets.slint";

    export struct MessageListRow {
        id: string,
        role: string,
        role-label: string,
        markdown: string,
        reasoning: string,
        status: string,
        status-label: string,
        time: string,
        meta: string,
        error-title: string,
        error-message: string,
        error-details: string,
        can-copy: bool,
        can-regenerate: bool,
        can-retry: bool,
        is-user: bool,
        is-assistant: bool,
        is-error: bool,
        is-cancelled: bool,
        is-interrupted: bool,
        is-streaming: bool,
        has-content: bool,
        show-typing: bool,
    }

    component MessageRowView inherits Rectangle {
        in property <MessageListRow> message;
        in property <bool> show-actions: true;
        callback copy(string);
        callback regenerate(string);
        callback retry(string);

        background: message.is-user ? #eef6ff : transparent;
        border-radius: 8px;

        accessible-role: list-item;
        accessible-label: message.role-label;

        VerticalLayout {
            padding: 8px;
            spacing: 4px;

            HorizontalLayout {
                spacing: 8px;
                Text { text: message.role-label; font-weight: 600; }
                Rectangle { horizontal-stretch: 1; }
                Text { text: message.time; color: #666666; }
            }

            if (!message.reasoning.is-empty) : Text {
                text: "Reasoning: " + message.reasoning;
                color: #666666;
                wrap: word-wrap;
            }

            if (!message.markdown.is-empty) : Text {
                text: message.markdown;
                wrap: word-wrap;
            }

            if (message.show-typing) : Text {
                text: "…";
                color: #666666;
            }

            if (!message.status-label.is-empty) : Text {
                text: message.status-label;
                color: #b45309;
                wrap: word-wrap;
            }

            if (!message.error-title.is-empty) : Rectangle {
                background: #fde8e8;
                border-radius: 8px;
                VerticalLayout {
                    padding: 8px;
                    spacing: 4px;
                    Text { text: message.error-title; font-weight: 600; }
                    Text { text: message.error-message; wrap: word-wrap; }
                    if (!message.error-details.is-empty) : Text {
                        text: message.error-details;
                        color: #666666;
                        wrap: word-wrap;
                    }
                    if (message.can-retry) : Button {
                        text: "Retry";
                        clicked => { root.retry(message.id); }
                    }
                }
            }

            if (root.show-actions && (message.can-copy || message.can-regenerate || message.can-retry)) : HorizontalLayout {
                spacing: 4px;
                if (message.can-copy) : Button {
                    text: "Copy";
                    clicked => { root.copy(message.id); }
                }
                if (message.can-regenerate) : Button {
                    text: "Regenerate";
                    clicked => { root.regenerate(message.id); }
                }
                if (message.can-retry) : Button {
                    text: "Retry";
                    clicked => { root.retry(message.id); }
                }
            }

            if (!message.meta.is-empty) : Text {
                text: message.meta;
                color: #666666;
                wrap: word-wrap;
            }
        }
    }

    export component MessageListWindow inherits Window {
        title: "ChatUI chat";
        width: 900px;
        height: 720px;

        in property <[MessageListRow]> rows: [];
        in property <MessageListRow> streaming-row: {
            id: "", role: "", role-label: "", markdown: "", reasoning: "",
            status: "", status-label: "", time: "", meta: "",
            error-title: "", error-message: "", error-details: "",
            can-copy: false, can-regenerate: false, can-retry: false,
            is-user: false, is-assistant: false, is-error: false,
            is-cancelled: false, is-interrupted: false, is-streaming: false,
            has-content: false, show-typing: false,
        };
        in property <bool> streaming-visible: false;
        in property <bool> has-older: false;
        in property <bool> loading-older: false;
        in property <bool> loading: false;
        in property <bool> empty: false;
        in property <string> empty-message: "No messages yet. Send a message to start the conversation.";
        in property <string> announcement: "";
        in property <bool> announcement-assertive: false;
        in property <bool> error-visible: false;
        in property <string> error-title: "";
        in property <string> error-message: "";
        in property <string> error-details: "";
        in property <length> top-padding: 0px;
        in property <length> bottom-padding: 0px;
        in-out property <length> content-y: 0px;

        callback load-older();
        callback retry();
        callback copy(string);
        callback regenerate(string);
        callback retry-message(string);
        callback scroll-changed(length);

        changed content-y => { root.scroll-changed(self.content-y); }

        VerticalLayout {
            padding: 12px;
            spacing: 8px;

            if (root.announcement != "") : Text {
                text: root.announcement;
                height: 1px;
                color: transparent;
                accessible-live-region: root.announcement-assertive ? assertive : polite;
            }

            if (root.error-visible) : Rectangle {
                background: #fde8e8;
                border-radius: 8px;
                VerticalLayout {
                    padding: 8px;
                    spacing: 4px;
                    Text { text: root.error-title; font-weight: 600; }
                    Text { text: root.error-message; wrap: word-wrap; }
                    if (!root.error-details.is-empty) : Text {
                        text: root.error-details;
                        color: #666666;
                        wrap: word-wrap;
                    }
                    Button { text: "Retry"; clicked => { root.retry(); } }
                }
            }

            if (root.loading) : VerticalLayout {
                spacing: 8px;
                Text { text: "Loading messages…"; color: #666666; }
                Rectangle { height: 16px; background: #eeeeee; border-radius: 4px; }
                Rectangle { height: 16px; background: #eeeeee; border-radius: 4px; }
                Rectangle { height: 16px; background: #eeeeee; border-radius: 4px; }
            }

            if (root.empty && !root.loading) : Text {
                text: root.empty-message;
                color: #666666;
                wrap: word-wrap;
            }

            ScrollView {
                content-y <=> root.content-y;

                VerticalLayout {
                    spacing: 8px;
                    accessible-role: list;

                    if (root.has-older || root.loading-older) : Button {
                        text: root.loading-older ? "Loading older messages…" : "Load older messages";
                        enabled: !root.loading-older;
                        clicked => { root.load-older(); }
                    }

                    Rectangle { height: root.top-padding; }

                    for item in root.rows : MessageRowView {
                        message: item;
                        show-actions: true;
                        copy(id) => { root.copy(id); }
                        regenerate(id) => { root.regenerate(id); }
                        retry(id) => { root.retry-message(id); }
                    }

                    Rectangle { height: root.bottom-padding; }

                    if (root.streaming-visible) : MessageRowView {
                        message: root.streaming-row;
                        show-actions: false;
                        copy(id) => { root.copy(id); }
                        regenerate(id) => { root.regenerate(id); }
                        retry(id) => { root.retry-message(id); }
                    }
                }
            }
        }
    }
}

fn role_id(role: MessageRole) -> &'static str {
    match role {
        MessageRole::System => "system",
        MessageRole::User => "user",
        MessageRole::Assistant => "assistant",
        MessageRole::Tool => "tool",
    }
}

fn status_id(status: MessageStatus) -> &'static str {
    match status {
        MessageStatus::Pending => "pending",
        MessageStatus::Streaming => "streaming",
        MessageStatus::Complete => "complete",
        MessageStatus::Cancelled => "cancelled",
        MessageStatus::Error => "error",
    }
}

fn slint_row(row: &MessageRow) -> MessageListRow {
    MessageListRow {
        id: SharedString::from(row.id.clone()),
        role: SharedString::from(role_id(row.role)),
        role_label: SharedString::from(row.role_label.clone()),
        markdown: SharedString::from(row.markdown.clone()),
        reasoning: SharedString::from(row.reasoning.clone()),
        status: SharedString::from(status_id(row.status)),
        status_label: SharedString::from(row.status_label.clone().unwrap_or_default()),
        time: SharedString::from(row.metadata.time.clone()),
        meta: SharedString::from(row.metadata.summary()),
        error_title: SharedString::from(
            row.error
                .as_ref()
                .map(|error| error.title.clone())
                .unwrap_or_default(),
        ),
        error_message: SharedString::from(
            row.error
                .as_ref()
                .map(|error| error.message.clone())
                .unwrap_or_default(),
        ),
        error_details: SharedString::from(
            row.error
                .as_ref()
                .and_then(|error| error.details.clone())
                .unwrap_or_default(),
        ),
        can_copy: row.has_action(MessageAction::Copy),
        can_regenerate: row.has_action(MessageAction::Regenerate),
        can_retry: row.has_action(MessageAction::Retry),
        is_user: row.role == MessageRole::User,
        is_assistant: row.role == MessageRole::Assistant,
        is_error: row.status == MessageStatus::Error,
        is_cancelled: row.status == MessageStatus::Cancelled,
        is_interrupted: row.is_interrupted,
        is_streaming: row.is_streaming,
        has_content: row.has_content,
        show_typing: row.show_typing,
    }
}

/// Push the current state into a Slint window.
pub fn sync_window(window: &MessageListWindow, state: &MessageListState) {
    let rows: Vec<MessageListRow> = state.visible_rows().iter().map(slint_row).collect();
    window.set_rows(ModelRc::new(VecModel::from(rows)));
    window.set_top_padding(state.top_padding());
    window.set_bottom_padding(state.bottom_padding());
    window.set_has_older(state.has_older());
    window.set_loading_older(state.loading_older());
    window.set_loading(state.is_loading());
    window.set_empty(state.is_empty());
    window.set_announcement(
        state
            .announcement()
            .map(|announcement| announcement.text.clone())
            .unwrap_or_default()
            .into(),
    );
    window.set_announcement_assertive(state.announcement().is_some_and(|announcement| {
        announcement.politeness == crate::accessibility::LiveRegionPoliteness::Assertive
    }));
    window.set_error_visible(state.error().is_some());
    window.set_error_title(
        state
            .error()
            .map(|error| error.title.clone())
            .unwrap_or_default()
            .into(),
    );
    window.set_error_message(
        state
            .error()
            .map(|error| error.message.clone())
            .unwrap_or_default()
            .into(),
    );
    window.set_error_details(
        state
            .error()
            .and_then(|error| error.details.clone())
            .unwrap_or_default()
            .into(),
    );

    if let Some(row) = state.streaming_row() {
        window.set_streaming_visible(true);
        window.set_streaming_row(slint_row(&row));
    } else {
        window.set_streaming_visible(false);
    }

    let scroll_top = state.scroll_top();

    if (window.get_content_y() - scroll_top).abs() > 0.5 {
        window.set_content_y(scroll_top);
    }
}

#[derive(Default)]
struct MessageListHandlers {
    load_older: Option<Rc<dyn Fn(u64)>>,
    retry: Option<Rc<dyn Fn()>>,
    retry_message: Option<Rc<dyn Fn(String)>>,
    regenerate: Option<Rc<dyn Fn(String)>>,
    copy: Option<Rc<dyn Fn(String)>>,
}

/// Owns the message list state and the Slint window that renders it.
pub struct MessageListController {
    state: Rc<RefCell<MessageListState>>,
    window: MessageListWindow,
    handlers: Rc<RefCell<MessageListHandlers>>,
    last_window: Rc<Cell<Option<(usize, usize)>>>,
}

impl MessageListController {
    /// Create the message list window and bind it to `state`.
    pub fn new(state: MessageListState) -> Result<Self, slint::PlatformError> {
        let window = MessageListWindow::new()?;
        let controller = Self {
            state: Rc::new(RefCell::new(state)),
            window,
            handlers: Rc::new(RefCell::new(MessageListHandlers::default())),
            last_window: Rc::new(Cell::new(None)),
        };
        controller.wire();
        controller.sync();
        Ok(controller)
    }

    /// The underlying Slint window.
    pub fn window(&self) -> &MessageListWindow {
        &self.window
    }

    /// Borrow the current state.
    pub fn state(&self) -> Ref<'_, MessageListState> {
        self.state.borrow()
    }

    /// Push the current state into the window.
    pub fn sync(&self) {
        let state = self.state.borrow();
        self.last_window.set(Some(state.visible_range()));
        sync_window(&self.window, &state);
    }

    /// Push only the streaming row and scroll offset into the window.
    fn sync_streaming(&self) {
        let state = self.state.borrow();

        if let Some(row) = state.streaming_row() {
            self.window.set_streaming_visible(true);
            self.window.set_streaming_row(slint_row(&row));
        } else {
            self.window.set_streaming_visible(false);
        }

        self.window.set_announcement(
            state
                .announcement()
                .map(|announcement| announcement.text.clone())
                .unwrap_or_default()
                .into(),
        );
        self.window
            .set_announcement_assertive(state.announcement().is_some_and(|announcement| {
                announcement.politeness == crate::accessibility::LiveRegionPoliteness::Assertive
            }));

        let scroll_top = state.scroll_top();

        if (self.window.get_content_y() - scroll_top).abs() > 0.5 {
            self.window.set_content_y(scroll_top);
        }
    }

    /// Replace the list with the newest page.
    pub fn apply_newest_page(&self, page: MessagePage) {
        self.state.borrow_mut().apply_newest_page(page);
        self.sync();
    }

    /// Merge an older page and preserve the visible position.
    pub fn apply_older_page(&self, page: MessagePage) {
        self.state.borrow_mut().apply_older_page(page);
        self.sync();
    }

    /// Set or clear the isolated streaming snapshot.
    ///
    /// Only the streaming row and the scroll offset are updated. The completed
    /// history model is left untouched, so token updates do not rebuild it.
    pub fn set_streaming(&self, streaming: Option<StreamingState>) {
        self.state.borrow_mut().set_streaming(streaming);
        self.sync_streaming();
    }

    /// Set the conversation used for streaming metadata.
    pub fn set_conversation(&self, conversation: Option<Conversation>) {
        self.state.borrow_mut().set_conversation(conversation);
        self.sync_streaming();
    }

    /// Show a list-level error.
    pub fn set_error(&self, error: MessageListError) {
        self.state.borrow_mut().set_error(error);
        self.sync();
    }

    /// Clear the list-level error.
    pub fn clear_error(&self) {
        self.state.borrow_mut().clear_error();
        self.sync();
    }

    /// Mark the initial newest page as loading or loaded.
    pub fn set_loading_newest(&self, loading: bool) {
        self.state.borrow_mut().set_loading_newest(loading);
        self.sync();
    }

    /// Show or hide token usage in metadata.
    pub fn set_show_token_usage(&self, show: bool) {
        self.state.borrow_mut().set_show_token_usage(show);
        self.sync();
    }

    /// Update the viewport height after a resize.
    pub fn set_viewport_height(&self, height: f32) {
        self.state.borrow_mut().set_viewport_height(height);
        self.sync();
    }

    /// Jump to the bottom and re-enable auto-scroll.
    pub fn scroll_to_bottom(&self) {
        self.state.borrow_mut().scroll_to_bottom();
        self.sync();
    }

    /// Register the older-page loader.
    pub fn set_on_load_older(&mut self, handler: impl Fn(u64) + 'static) {
        self.handlers.borrow_mut().load_older = Some(Rc::new(handler));
    }

    /// Register the error-banner retry handler.
    pub fn set_on_retry(&mut self, handler: impl Fn() + 'static) {
        self.handlers.borrow_mut().retry = Some(Rc::new(handler));
    }

    /// Register the failed-message retry handler.
    pub fn set_on_retry_message(&mut self, handler: impl Fn(String) + 'static) {
        self.handlers.borrow_mut().retry_message = Some(Rc::new(handler));
    }

    /// Register the regenerate handler.
    pub fn set_on_regenerate(&mut self, handler: impl Fn(String) + 'static) {
        self.handlers.borrow_mut().regenerate = Some(Rc::new(handler));
    }

    /// Register the copy handler. It receives the message text.
    pub fn set_on_copy(&mut self, handler: impl Fn(String) + 'static) {
        self.handlers.borrow_mut().copy = Some(Rc::new(handler));
    }

    fn wire(&self) {
        let weak = self.window.as_weak();
        let state = Rc::clone(&self.state);
        let handlers = Rc::clone(&self.handlers);
        let last_window = Rc::clone(&self.last_window);
        self.window.on_load_older(move || {
            let cursor = state.borrow_mut().begin_load_older();

            if let Some(cursor) = cursor
                && let Some(handler) = handlers.borrow().load_older.clone()
            {
                handler(cursor);
            }

            if let Some(window) = weak.upgrade() {
                last_window.set(Some(state.borrow().visible_range()));
                sync_window(&window, &state.borrow());
            }
        });

        let weak = self.window.as_weak();
        let state = Rc::clone(&self.state);
        let handlers = Rc::clone(&self.handlers);
        self.window.on_retry(move || {
            if let Some(handler) = handlers.borrow().retry.clone() {
                handler();
            }

            if let Some(window) = weak.upgrade() {
                sync_window(&window, &state.borrow());
            }
        });

        let weak = self.window.as_weak();
        let state = Rc::clone(&self.state);
        let handlers = Rc::clone(&self.handlers);
        self.window.on_retry_message(move |id| {
            if let Some(handler) = handlers.borrow().retry_message.clone() {
                handler(id.to_string());
            }

            if let Some(window) = weak.upgrade() {
                sync_window(&window, &state.borrow());
            }
        });

        let weak = self.window.as_weak();
        let state = Rc::clone(&self.state);
        let handlers = Rc::clone(&self.handlers);
        self.window.on_regenerate(move |id| {
            if let Some(handler) = handlers.borrow().regenerate.clone() {
                handler(id.to_string());
            }

            if let Some(window) = weak.upgrade() {
                sync_window(&window, &state.borrow());
            }
        });

        let weak = self.window.as_weak();
        let state = Rc::clone(&self.state);
        let handlers = Rc::clone(&self.handlers);
        self.window.on_copy(move |id| {
            let text = state.borrow().message_text(id.as_str());

            if let (Some(text), Some(handler)) = (text, handlers.borrow().copy.clone()) {
                handler(text);
            }

            if let Some(window) = weak.upgrade() {
                sync_window(&window, &state.borrow());
            }
        });

        let weak = self.window.as_weak();
        let state = Rc::clone(&self.state);
        let handlers = Rc::clone(&self.handlers);
        let last_window = Rc::clone(&self.last_window);
        self.window.on_scroll_changed(move |offset| {
            let should_load = state.borrow_mut().handle_scroll(offset);

            if should_load {
                let cursor = state.borrow_mut().begin_load_older();

                if let Some(cursor) = cursor
                    && let Some(handler) = handlers.borrow().load_older.clone()
                {
                    handler(cursor);
                }

                if let Some(window) = weak.upgrade() {
                    last_window.set(Some(state.borrow().visible_range()));
                    sync_window(&window, &state.borrow());
                }

                return;
            }

            let range = state.borrow().visible_range();

            if last_window.get() != Some(range) {
                last_window.set(Some(range));

                if let Some(window) = weak.upgrade() {
                    sync_window(&window, &state.borrow());
                }
            }
        });
    }
}
