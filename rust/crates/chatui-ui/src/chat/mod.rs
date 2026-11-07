//! Chat runner, streaming state, and orchestration.
//!
//! The module is split into framework-independent state ([`state`]), the
//! stream lifecycle ([`runner`]), and the repository-backed orchestration
//! service ([`service`]). It uses the shared [`chatui_domain::StreamEvent`]
//! model and never defines a second stream event type.

pub mod runner;
pub mod service;
pub mod state;

#[cfg(test)]
mod tests;

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
