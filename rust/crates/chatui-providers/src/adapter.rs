//! Stable provider adapter surface.
//!
//! The adapter is the only entry point the UI and message layer use. It owns
//! provider-specific key, catalog, request, and stream behavior behind one
//! enum. Adapters never persist data, never read settings, and never log
//! secrets. Everything they need is passed in through [`ProviderContext`].
//!
//! M3.5-06.4 implements OpenRouter only. Every other contract preset gets an
//! [`UnsupportedAdapter`] that returns a clear error until M3.5-07.*.

#![allow(clippy::result_large_err)]

use std::collections::BTreeMap;

use chatui_contracts::{AppConfig, ProviderAuth, ProviderPreset};
use chatui_domain::{ChatError, ChatErrorCode, ProviderKind, StreamEvent, Timestamp};
use tokio_util::sync::CancellationToken;

use crate::http::HttpClient;
use crate::openrouter::catalog::{CatalogModel, list_models};
use crate::openrouter::key::{OpenRouterError, ProviderKeyInfo, validate_key};
use crate::openrouter::request::{
    NormalizedChatRequest, PreparedChatRequest, ResolvedAttachment, build_body,
};
use crate::openrouter::stream::{StreamSummary, stream_chat_completions};

/// Resolved, non-secret context for one adapter call.
///
/// The context carries only the contract preset, its resolved base URL and
/// authentication scheme, non-secret headers, and the resolved API key. It
/// never carries settings, storage handles, or a secret store.
#[derive(Debug, Clone, PartialEq)]
pub struct ProviderContext {
    /// The contract preset this call targets.
    pub preset: ProviderPreset,
    /// Base URL resolved from the preset.
    pub base_url: String,
    /// Authentication scheme resolved from the preset.
    pub auth: ProviderAuth,
    /// Non-secret headers sent with every request.
    pub headers: BTreeMap<String, String>,
    /// Resolved API key, or `None` when the preset needs no key.
    pub api_key: Option<String>,
}

impl ProviderContext {
    /// Build a context from a preset, deployment config, and resolved key.
    ///
    /// `headers` combines the preset's non-secret default headers with its
    /// resolved attribution headers. No secret is stored in `headers`.
    pub fn new(preset: &ProviderPreset, app_config: &AppConfig, api_key: Option<String>) -> Self {
        let mut headers = preset.default_headers.clone();
        headers.extend(app_config.attribution_headers(preset));

        Self {
            preset: preset.clone(),
            base_url: preset.base_url.clone(),
            auth: preset.auth.clone(),
            headers,
            api_key,
        }
    }

    /// Return the preset ID.
    pub fn preset_id(&self) -> &str {
        &self.preset.id
    }

    /// Return the resolved key, or an auth error when it is missing.
    pub fn require_key(&self) -> Result<&str, ChatError> {
        match self.api_key.as_deref() {
            Some(key) if !key.trim().is_empty() => Ok(key),
            _ => Err(ChatError {
                code: ChatErrorCode::Auth,
                message: format!("An {} API key is required.", self.preset.label),
                provider_id: Some(self.preset.id.clone()),
                status: None,
                retryable: false,
                retry_after_seconds: None,
                request_id: None,
                details: None,
                at: Timestamp::now(),
            }),
        }
    }
}

/// The OpenRouter adapter.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub struct OpenRouterAdapter;

/// A placeholder adapter for a contract preset that is not implemented yet.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct UnsupportedAdapter {
    preset_id: String,
    kind: ProviderKind,
}

impl UnsupportedAdapter {
    /// Build an unsupported adapter for a preset.
    pub fn new(preset_id: impl Into<String>, kind: ProviderKind) -> Self {
        Self {
            preset_id: preset_id.into(),
            kind,
        }
    }

    /// Return the preset ID.
    pub fn preset_id(&self) -> &str {
        &self.preset_id
    }

    /// Return the clear unsupported error.
    pub fn unsupported_error(&self) -> ChatError {
        ChatError {
            code: ChatErrorCode::Unknown,
            message: format!("Provider preset {} is not supported yet.", self.preset_id),
            provider_id: Some(self.preset_id.clone()),
            status: None,
            retryable: false,
            retry_after_seconds: None,
            request_id: None,
            details: None,
            at: Timestamp::now(),
        }
    }
}

/// The stable adapter surface.
///
/// This is a small enum dispatch rather than an object-safe trait because the
/// async methods would otherwise need boxed futures. The enum keeps the
/// surface stable while the runtime holds adapters for multiple presets.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ProviderAdapter {
    /// OpenRouter, the reference adapter.
    OpenRouter(OpenRouterAdapter),
    /// A contract preset that is not implemented yet.
    Unsupported(UnsupportedAdapter),
}

impl ProviderAdapter {
    /// Return the provider kind.
    pub fn kind(&self) -> ProviderKind {
        match self {
            Self::OpenRouter(_) => ProviderKind::OpenaiCompatible,
            Self::Unsupported(adapter) => adapter.kind,
        }
    }

    /// Return the preset ID.
    pub fn preset_id(&self) -> &str {
        match self {
            Self::OpenRouter(_) => "openrouter",
            Self::Unsupported(adapter) => adapter.preset_id(),
        }
    }

    /// Return whether this adapter is implemented.
    pub fn is_supported(&self) -> bool {
        matches!(self, Self::OpenRouter(_))
    }

    /// Validate the key with `GET /key`.
    ///
    /// Cancellation drops the request future. The key is sent only in the
    /// `Authorization` header.
    pub async fn validate_key(
        &self,
        client: &HttpClient,
        ctx: &ProviderContext,
        cancel: &CancellationToken,
    ) -> Result<ProviderKeyInfo, ChatError> {
        match self {
            Self::OpenRouter(_) => {
                let key = ctx.require_key()?;
                tokio::select! {
                    result = validate_key(client, &ctx.base_url, key) => {
                        result.map_err(|error| map_openrouter_error(error, ctx.preset_id()))
                    }
                    _ = cancel.cancelled() => Err(cancelled_error(ctx.preset_id())),
                }
            }
            Self::Unsupported(adapter) => Err(adapter.unsupported_error()),
        }
    }

    /// Load the public model catalog with `GET /models`.
    ///
    /// No API key is sent. Cancellation drops the request future.
    pub async fn list_models(
        &self,
        client: &HttpClient,
        ctx: &ProviderContext,
        cancel: &CancellationToken,
    ) -> Result<Vec<CatalogModel>, ChatError> {
        match self {
            Self::OpenRouter(_) => {
                tokio::select! {
                    result = list_models(client, &ctx.base_url) => {
                        result.map_err(|error| map_openrouter_error(error, ctx.preset_id()))
                    }
                    _ = cancel.cancelled() => Err(cancelled_error(ctx.preset_id())),
                }
            }
            Self::Unsupported(adapter) => Err(adapter.unsupported_error()),
        }
    }

    /// Build the provider request body and non-secret headers.
    ///
    /// The returned headers are the context headers, so they already include
    /// the preset's default headers and resolved attribution headers.
    pub fn prepare_request(
        &self,
        ctx: &ProviderContext,
        request: &NormalizedChatRequest,
        supported_parameters: &[String],
        attachments: &BTreeMap<String, ResolvedAttachment>,
    ) -> Result<PreparedChatRequest, ChatError> {
        match self {
            Self::OpenRouter(_) => Ok(PreparedChatRequest {
                body: build_body(request, supported_parameters, attachments)?,
                headers: ctx.headers.clone(),
            }),
            Self::Unsupported(adapter) => Err(adapter.unsupported_error()),
        }
    }

    /// Stream a chat completion and deliver normalized events to `on_event`.
    ///
    /// Cancellation stops the stream and preserves any partial text or
    /// reasoning in the returned [`StreamSummary`]. The caller decides whether
    /// a retry is allowed from [`StreamSummary::retry_allowed`].
    #[allow(clippy::too_many_arguments)]
    pub async fn stream_chat(
        &self,
        client: &HttpClient,
        ctx: &ProviderContext,
        request: &NormalizedChatRequest,
        supported_parameters: &[String],
        attachments: &BTreeMap<String, ResolvedAttachment>,
        cancel: &CancellationToken,
        on_event: &mut (dyn FnMut(StreamEvent) + Send),
    ) -> Result<StreamSummary, ChatError> {
        match self {
            Self::OpenRouter(_) => {
                let key = ctx.require_key()?;
                let prepared =
                    self.prepare_request(ctx, request, supported_parameters, attachments)?;
                stream_chat_completions(client, &ctx.base_url, key, &prepared, cancel, |event| {
                    on_event(event)
                })
                .await
            }
            Self::Unsupported(adapter) => Err(adapter.unsupported_error()),
        }
    }
}

fn map_openrouter_error(error: OpenRouterError, provider_id: &str) -> ChatError {
    match error {
        OpenRouterError::Network(error) => error.to_chat_error(Some(provider_id.to_owned())),
        OpenRouterError::Url(error) => ChatError {
            code: ChatErrorCode::InvalidRequest,
            message: format!("The provider URL is invalid: {error}"),
            provider_id: Some(provider_id.to_owned()),
            status: None,
            retryable: false,
            retry_after_seconds: None,
            request_id: None,
            details: None,
            at: Timestamp::now(),
        },
        OpenRouterError::Parse(message) => ChatError {
            code: ChatErrorCode::Parse,
            message,
            provider_id: Some(provider_id.to_owned()),
            status: None,
            retryable: false,
            retry_after_seconds: None,
            request_id: None,
            details: None,
            at: Timestamp::now(),
        },
        OpenRouterError::MissingKey => ChatError {
            code: ChatErrorCode::Auth,
            message: "An OpenRouter API key is required.".to_owned(),
            provider_id: Some(provider_id.to_owned()),
            status: None,
            retryable: false,
            retry_after_seconds: None,
            request_id: None,
            details: None,
            at: Timestamp::now(),
        },
    }
}

fn cancelled_error(provider_id: &str) -> ChatError {
    ChatError {
        code: ChatErrorCode::Cancelled,
        message: "The request was cancelled.".to_owned(),
        provider_id: Some(provider_id.to_owned()),
        status: None,
        retryable: false,
        retry_after_seconds: None,
        request_id: None,
        details: None,
        at: Timestamp::now(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use chatui_contracts::provider_catalog;

    fn app_config() -> AppConfig {
        AppConfig::new("ChatUI", "https://chatui.example").expect("valid app config")
    }

    fn context() -> ProviderContext {
        let preset = provider_catalog()
            .get("openrouter")
            .expect("openrouter preset");
        ProviderContext::new(preset, &app_config(), Some("sk-test".to_owned()))
    }

    #[test]
    fn context_combines_default_and_attribution_headers() {
        let ctx = context();

        assert_eq!(ctx.preset_id(), "openrouter");
        assert_eq!(ctx.base_url, "https://openrouter.ai/api/v1");
        assert_eq!(
            ctx.headers.get("HTTP-Referer").map(String::as_str),
            Some("https://chatui.example")
        );
        assert_eq!(
            ctx.headers.get("X-Title").map(String::as_str),
            Some("ChatUI")
        );
        assert!(!ctx.headers.values().any(|value| value.contains("sk-")));
    }

    #[test]
    fn require_key_rejects_missing_and_blank_keys() {
        let preset = provider_catalog()
            .get("openrouter")
            .expect("openrouter preset");

        for key in [None, Some(String::new()), Some("   ".to_owned())] {
            let ctx = ProviderContext::new(preset, &app_config(), key);
            let error = ctx.require_key().expect_err("missing key");
            assert_eq!(error.code, ChatErrorCode::Auth);
            assert!(!error.retryable);
        }
    }

    #[test]
    fn unsupported_adapter_returns_a_clear_error() {
        let adapter = UnsupportedAdapter::new("openai", ProviderKind::OpenaiCompatible);
        let error = adapter.unsupported_error();

        assert_eq!(error.code, ChatErrorCode::Unknown);
        assert_eq!(error.provider_id.as_deref(), Some("openai"));
        assert!(error.message.contains("not supported"));
        assert!(!error.retryable);
    }

    #[test]
    fn adapter_reports_kind_and_preset_id() {
        let adapter = ProviderAdapter::OpenRouter(OpenRouterAdapter);
        assert_eq!(adapter.kind(), ProviderKind::OpenaiCompatible);
        assert_eq!(adapter.preset_id(), "openrouter");
        assert!(adapter.is_supported());

        let unsupported = ProviderAdapter::Unsupported(UnsupportedAdapter::new(
            "anthropic",
            ProviderKind::Anthropic,
        ));
        assert_eq!(unsupported.kind(), ProviderKind::Anthropic);
        assert_eq!(unsupported.preset_id(), "anthropic");
        assert!(!unsupported.is_supported());
    }
}
