//! OpenRouter chat request serialization.
//!
//! This module keeps the OpenRouter-specific request types and the
//! deterministic default-model rule. The actual body serialization is the
//! shared OpenAI-compatible implementation in
//! [`crate::openai_compatible::build_body`], driven by the bundled OpenRouter
//! preset. That keeps one serializer for every OpenAI-compatible provider while
//! preserving OpenRouter's curated advanced-JSON behavior.
//!
//! Rules implemented by the shared serializer come from spec section 7.4 and
//! `contracts/fixtures/requests/README.md`:
//!
//! - the system prompt is the first `messages` entry when present;
//! - text and file parts concatenate into `content`, while image parts switch
//!   the message to OpenAI-style content parts with data URLs;
//! - `stream` always mirrors `params.streaming`, and streaming requests always
//!   include `stream_options.include_usage`;
//! - optional controls are emitted only when the selected model advertises the
//!   matching `supported_parameters` entry;
//! - curated routing lives under `provider`, with `order`, `only`, `ignore`,
//!   `models`, and `route` available through advanced JSON;
//! - advanced JSON may not override `model`, `messages`, `stream`, or
//!   `stream_options`;
//! - attribution headers come from the bundled OpenRouter preset and the
//!   deployment [`AppConfig`].

#![allow(clippy::result_large_err)]

use std::collections::BTreeMap;

use chatui_contracts::{AppConfig, provider_catalog};
use chatui_domain::{ChatError, ChatParams, ContentPart, MessageRole};
use serde::{Deserialize, Serialize};
use serde_json::Value;

use crate::openai_compatible::{OpenAiCompatiblePreset, build_body as build_openai_body};

/// A normalized chat request from spec section 7.3.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct NormalizedChatRequest {
    /// Provider model ID.
    pub model: String,
    /// System prompt, or `None` when absent.
    pub system: Option<String>,
    /// Conversation messages.
    pub messages: Vec<NormalizedChatMessage>,
    /// Generation controls.
    pub params: ChatParams,
}

/// One normalized request message.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct NormalizedChatMessage {
    /// Message role.
    pub role: MessageRole,
    /// Ordered content parts.
    pub parts: Vec<ContentPart>,
}

/// Resolved attachment bytes supplied by the caller.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ResolvedAttachment {
    /// MIME type.
    pub mime_type: String,
    /// Original file name.
    pub name: String,
    /// Base64-encoded bytes.
    pub data_base64: String,
}

/// A serialized OpenRouter request body and its attribution headers.
#[derive(Debug, Clone, PartialEq)]
pub struct PreparedChatRequest {
    /// OpenRouter `/chat/completions` JSON body.
    pub body: Value,
    /// Attribution headers resolved from [`AppConfig`].
    pub headers: BTreeMap<String, String>,
}

/// A catalog entry used by the deterministic default-model rule.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DefaultModelCandidate {
    /// Provider model ID.
    pub remote_id: String,
    /// Whether the model is hidden from the picker.
    pub hidden: bool,
    /// Output modalities.
    pub output_modalities: Vec<String>,
}

/// The result of the deterministic default-model rule.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DefaultModelSelection {
    /// Selected model ID, or `None` when no text model exists.
    pub selected_model_id: Option<String>,
    /// Non-blocking notice, or `None` when the preset default was used.
    pub notice: Option<String>,
}

/// Build the OpenRouter request body and attribution headers.
///
/// `supported_parameters` is the effective OpenRouter `supported_parameters`
/// list for the selected model. `attachments` supplies resolved bytes for image
/// and file parts referenced by the normalized request.
pub fn build_request(
    request: &NormalizedChatRequest,
    supported_parameters: &[String],
    attachments: &BTreeMap<String, ResolvedAttachment>,
    app_config: &AppConfig,
) -> Result<PreparedChatRequest, ChatError> {
    Ok(PreparedChatRequest {
        body: build_body(request, supported_parameters, attachments)?,
        headers: attribution_headers(app_config),
    })
}

/// Build only the OpenRouter request body.
///
/// This is the transport-independent half of [`build_request`] and is the
/// entry point used by the shared request vectors. It delegates to the shared
/// OpenAI-compatible serializer with the bundled OpenRouter preset.
pub fn build_body(
    request: &NormalizedChatRequest,
    supported_parameters: &[String],
    attachments: &BTreeMap<String, ResolvedAttachment>,
) -> Result<Value, ChatError> {
    let preset = provider_catalog()
        .get("openrouter")
        .expect("bundled OpenRouter preset must exist");
    let preset = OpenAiCompatiblePreset::from_contract(preset);
    build_openai_body(&preset, request, supported_parameters, attachments)
}

/// Resolve the OpenRouter attribution headers from the bundled preset.
///
/// The preset maps `HTTP-Referer` to `app.origin` and `X-Title` to `app.name`.
/// The values are public deployment configuration and never contain keys or
/// personal data.
pub fn attribution_headers(app_config: &AppConfig) -> BTreeMap<String, String> {
    let preset = provider_catalog()
        .get("openrouter")
        .expect("bundled OpenRouter preset must exist");
    app_config.attribution_headers(preset)
}

/// Apply the deterministic default-model rule from the shared request vectors.
///
/// 1. Use `preset_default_model_id` when it exists in the catalog, even if the
///    model is hidden.
/// 2. Otherwise select the first non-hidden text model ordered by `remote_id`.
/// 3. If no text model exists, return `None` and a non-blocking notice.
pub fn select_default_model(
    preset_default_model_id: &str,
    catalog: &[DefaultModelCandidate],
) -> DefaultModelSelection {
    if catalog
        .iter()
        .any(|model| model.remote_id == preset_default_model_id)
    {
        return DefaultModelSelection {
            selected_model_id: Some(preset_default_model_id.to_owned()),
            notice: None,
        };
    }

    let mut candidates: Vec<&DefaultModelCandidate> = catalog
        .iter()
        .filter(|model| {
            !model.hidden
                && model
                    .output_modalities
                    .iter()
                    .any(|modality| modality == "text")
        })
        .collect();
    candidates.sort_by(|left, right| left.remote_id.cmp(&right.remote_id));

    match candidates.first() {
        Some(model) => DefaultModelSelection {
            selected_model_id: Some(model.remote_id.clone()),
            notice: Some(format!(
                "Default model {preset_default_model_id} is unavailable; using {}.",
                model.remote_id
            )),
        },
        None => DefaultModelSelection {
            selected_model_id: None,
            notice: Some(format!(
                "Default model {preset_default_model_id} is unavailable; no text model is available."
            )),
        },
    }
}
