//! Provider adapters, stream parsers, and retry policy.
//!
//! This crate owns the shared HTTP client, URL policy, and normalized network
//! errors. Provider-specific request logic arrives in M3.5-06 and M3.5-07.

pub mod adapter;
pub mod anthropic;
pub mod cancel;
pub mod error;
pub mod gemini;
pub mod http;
pub mod ndjson;
pub mod openai_catalog;
pub mod openai_compatible;
pub mod openrouter {
    pub mod cache;
    pub mod catalog;
    pub mod errors;
    pub mod key;
    pub mod request;
    pub mod stream;
}
pub mod registry;
pub mod retry;
pub mod sse;
pub mod url_policy;

pub use adapter::{
    OpenAiCompatibleAdapter, OpenRouterAdapter, ProviderAdapter, ProviderContext,
    UnsupportedAdapter,
};
pub use anthropic::{
    ANTHROPIC_PROVIDER_ID, ANTHROPIC_VERSION, AnthropicAdapter, AnthropicStreamDecoder,
    BROWSER_ACCESS_HEADER, DEFAULT_MAX_TOKENS, build_body as build_anthropic_body,
    build_headers as build_anthropic_headers, map_finish_reason as map_anthropic_finish_reason,
    map_models as map_anthropic_models, map_stream_error as map_anthropic_stream_error,
    prepare_request as prepare_anthropic_request,
};
pub use cancel::{Cancellation, CancellationToken};
pub use error::{NetworkError, NetworkErrorKind, is_retryable_status, parse_retry_after};
pub use gemini::{
    GEMINI_EXTRA_NAMESPACE, GEMINI_PROVIDER_ID, GeminiAdapter, GeminiStreamDecoder,
    build_body as build_gemini_body, build_chat_url as build_gemini_chat_url,
    build_headers as build_gemini_headers, map_finish_reason as map_gemini_finish_reason,
    map_http_error as map_gemini_http_error, map_model as map_gemini_model,
    map_models as map_gemini_models, map_stream_error as map_gemini_stream_error,
    prepare_request as prepare_gemini_request,
};
pub use http::{
    CONNECT_TIMEOUT, HttpClient, MAX_REDIRECTS, POOL_IDLE_TIMEOUT, POOL_MAX_IDLE_PER_HOST,
    PreparedRequest, SecretHeader, TCP_KEEPALIVE, USER_AGENT, build_http_client, redirect_policy,
    same_origin,
};
pub use ndjson::{DEFAULT_MAX_NDJSON_BUFFER_BYTES, NdjsonError, read_ndjson};
pub use openai_catalog::{
    CatalogDefaults, OpenAiCatalogError, anthropic_catalog_defaults, defaults_for_preset,
    google_catalog_defaults, map_model as map_openai_model, map_models as map_openai_models,
    openai_catalog_defaults,
};
pub use openai_compatible::{
    ExtraMode, OpenAiCompatiblePreset, build_body as build_openai_compatible_body,
    default_message as openai_compatible_default_message, network_error_to_chat_error,
    prepare_request as prepare_openai_compatible_request,
};
pub use openrouter::cache::{
    CATALOG_CACHE_VERSION, CATALOG_DIR_NAME, CacheError, CatalogCache, CatalogResult,
    CatalogSnapshot, FileCatalogCache, MemoryCatalogCache, OPENROUTER_CATALOG_TTL_SECONDS,
    is_fresh, load_catalog, refresh_catalog,
};
pub use openrouter::catalog::{
    CatalogModel, dedupe_by_canonical_slug, list_models, map_model, map_models,
    multiply_decimal_by_million, usd_amount, usd_per_million,
};
pub use openrouter::errors::{
    OPENROUTER_PROVIDER_ID, map_error_object, map_error_object_for, map_http_error,
    map_http_error_for, map_stream_error, map_stream_error_for,
};
pub use openrouter::key::{OpenRouterError, ProviderKeyInfo, map_key, validate_key};
pub use openrouter::request::{
    DefaultModelCandidate, DefaultModelSelection, NormalizedChatMessage, NormalizedChatRequest,
    PreparedChatRequest, ResolvedAttachment, attribution_headers, build_body, build_request,
    select_default_model,
};
pub use openrouter::stream::{
    ReasoningStyle, StreamConfig, StreamDecoder, StreamSummary, map_finish_reason, map_usage,
    stream_chat, stream_chat_completions, stream_chat_with,
};
pub use registry::{ProviderRegistry, RegistryError, create_adapter, is_implemented_preset};
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
