//! Chat runner, streaming state, and orchestration.
//!
//! The module is split into framework-independent state ([`state`]), the
//! stream lifecycle ([`runner`]), and the repository-backed orchestration
//! service ([`service`]). It uses the shared [`chatui_domain::StreamEvent`]
//! model and never defines a second stream event type.

pub mod attachments;
pub mod composer;
pub mod drafts;
pub mod message_item;
pub mod message_list;
pub mod runner;
pub mod service;
pub mod state;

#[cfg(test)]
mod attachment_tests;
#[cfg(test)]
mod composer_tests;
#[cfg(test)]
mod draft_tests;
#[cfg(test)]
mod message_list_tests;
#[cfg(test)]
mod tests;

pub use attachments::{
    AttachmentError, AttachmentFileInfo, AttachmentValidationCode, AttachmentValidationError,
    MAX_FILE_NAME_LENGTH, MAX_IMAGE_BYTES, MAX_IMAGE_EDGE, MAX_TEXT_BYTES, OUTPUT_QUALITY,
    PendingAttachment, ProcessedImage, SUPPORTED_IMAGE_MIME_TYPES, SUPPORTED_TEXT_EXTENSIONS,
    SUPPORTED_TEXT_MIME_TYPES, ValidatedAttachmentFile, clipboard_images, dropped_files,
    file_extension, format_attachment_size, output_mime_type_for, process_image,
    sanitize_file_name, scaled_dimensions, store_pending_attachment, validate_attachment_file,
};
pub use composer::{
    AttachmentChip, ComposerController, ComposerKeyAction, ComposerSend, ComposerState,
    DEFAULT_COMPOSER_LINE_HEIGHT, MAX_COMPOSER_LINES, ModelCapabilities,
    sync_window as sync_composer_window,
};
pub use drafts::{
    DRAFT_KEY_PREFIX, DraftPersistence, DraftStore, MAX_DRAFT_LENGTH, MAX_DRAFTS, MemoryDraftStore,
    PersistentDraftStore, SettingsDraftPersistence,
};
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
