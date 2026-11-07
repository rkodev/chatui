//! Provider adapters, stream parsers, and retry policy.
//!
//! This crate owns the shared HTTP client, URL policy, and normalized network
//! errors. Provider-specific request logic arrives in M3.5-06 and M3.5-07.

pub mod cancel;
pub mod error;
pub mod http;
pub mod ndjson;
pub mod retry;
pub mod sse;
pub mod url_policy;

pub use cancel::{Cancellation, CancellationToken};
pub use error::{NetworkError, NetworkErrorKind, is_retryable_status, parse_retry_after};
pub use http::{
    CONNECT_TIMEOUT, HttpClient, MAX_REDIRECTS, POOL_IDLE_TIMEOUT, POOL_MAX_IDLE_PER_HOST,
    PreparedRequest, SecretHeader, TCP_KEEPALIVE, USER_AGENT, build_http_client, redirect_policy,
    same_origin,
};
pub use ndjson::{DEFAULT_MAX_NDJSON_BUFFER_BYTES, NdjsonError, read_ndjson};
pub use retry::{
    AttemptFailure, AttemptOutcome, DEFAULT_INACTIVITY_TIMEOUT, InactivityWatchdog, PartialOutput,
    RETRY_BASE_DELAY, RETRY_JITTER_RATIO, RETRY_MAX_ATTEMPTS, RETRY_MAX_DELAY, RetryError,
    RetryPolicy, compute_retry_delay, compute_retry_delay_with_jitter, retry_after_from_header,
    retry_delay_for, retry_with, sleep,
};
pub use sse::{DEFAULT_MAX_SSE_BUFFER_BYTES, SseError, read_sse};
pub use url_policy::{
    CLEARTEXT_WARNING, UrlPolicy, UrlPolicyError, classify_url, ensure_no_key_in_query,
    join_and_classify, join_url,
};
