//! Chat runner, streaming state, and orchestration.
//!
//! The module is split into framework-independent state ([`state`]), the
//! stream lifecycle ([`runner`]), and the repository-backed orchestration
//! service ([`service`]). It uses the shared [`chatui_domain::StreamEvent`]
//! model and never defines a second stream event type.

pub mod message_item;
pub mod message_list;
pub mod runner;
pub mod service;
pub mod state;

#[cfg(test)]
mod message_list_tests;
#[cfg(test)]
mod tests;

pub use message_item::{
    MessageAction, MessageErrorView, MessageMetadata, MessageRow, MessageRowOptions, message_text,
    reasoning_text, role_label,
};
pub use message_list::{
    DEFAULT_ESTIMATE_HEIGHT, DEFAULT_LOAD_OLDER_PX, DEFAULT_NEAR_BOTTOM_PX, DEFAULT_OVERSCAN,
    DEFAULT_PAGE_SIZE, DEFAULT_VIEWPORT_HEIGHT, MAX_WINDOW_ROWS, MessageListConfig,
    MessageListController, MessageListError, MessageListLoader, MessageListRow, MessageListState,
    MessageListWindow, sync_window as sync_message_list_window,
};
pub use runner::{
    ChatRunInput, ChatRunResult, ChatRunner, ChatRunnerOptions, ChatStreamAdapter, IdGenerator,
    ProviderChatAdapter, RegenerateInput, RetryInput, RunRequest, can_retry, has_partial_output,
};
pub use service::{
    ChatErrorCopy, ChatSendFailure, ChatSendFailureReason, ChatSendResult, ChatService,
    ChatServiceError, build_chat_request, describe_chat_error,
};
pub use state::{
    Clock, MemoryStreamingStore, RunnerState, StreamingState, StreamingStore, SystemClock,
    reduce_stream,
};
