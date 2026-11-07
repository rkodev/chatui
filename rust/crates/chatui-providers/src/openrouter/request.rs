//! OpenRouter chat request serialization.
//!
//! This module turns a normalized chat request into the exact OpenRouter
//! `/chat/completions` body and attribution headers. It is deliberately free of
//! HTTP transport so the shared request vectors can exercise it directly.
//!
//! Rules implemented here come from spec section 7.4 and
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

use base64::Engine as _;
use base64::engine::general_purpose::STANDARD as BASE64_STANDARD;
use chatui_contracts::{AppConfig, provider_catalog};
use chatui_domain::{ChatError, ChatErrorCode, ChatParams, ContentPart, MessageRole, Timestamp};
use serde::{Deserialize, Serialize};
use serde_json::{Map, Value, json};

/// Fields advanced JSON may never override.
const PROTECTED_FIELDS: [&str; 4] = ["model", "messages", "stream", "stream_options"];

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
/// entry point used by the shared request vectors.
pub fn build_body(
    request: &NormalizedChatRequest,
    supported_parameters: &[String],
    attachments: &BTreeMap<String, ResolvedAttachment>,
) -> Result<Value, ChatError> {
    let advanced = advanced_namespace(&request.params.extra)?;
    reject_protected_fields(advanced)?;

    let mut body = Map::new();
    body.insert("model".to_owned(), Value::String(request.model.clone()));

    let mut messages = Vec::with_capacity(request.messages.len() + 1);
    if let Some(system) = &request.system {
        messages.push(json!({ "role": "system", "content": system }));
    }
    for message in &request.messages {
        messages.push(serialize_message(message, attachments)?);
    }
    body.insert("messages".to_owned(), Value::Array(messages));

    body.insert("stream".to_owned(), Value::Bool(request.params.streaming));
    if request.params.streaming {
        body.insert(
            "stream_options".to_owned(),
            json!({ "include_usage": true }),
        );
    }

    if let Some(max_tokens) = request.params.max_output_tokens
        && supports(supported_parameters, "max_tokens")
    {
        body.insert("max_tokens".to_owned(), json!(max_tokens));
    }
    if let Some(temperature) = request.params.temperature
        && supports(supported_parameters, "temperature")
    {
        body.insert("temperature".to_owned(), json!(temperature));
    }
    if let Some(top_p) = request.params.top_p
        && supports(supported_parameters, "top_p")
    {
        body.insert("top_p".to_owned(), json!(top_p));
    }
    if !request.params.stop.is_empty() && supports(supported_parameters, "stop") {
        body.insert("stop".to_owned(), json!(request.params.stop));
    }
    if let Some(effort) = request.params.reasoning_effort
        && supports(supported_parameters, "reasoning")
    {
        body.insert("reasoning".to_owned(), json!({ "effort": effort }));
    }

    if let Some(advanced) = advanced {
        apply_advanced(advanced, &mut body)?;
    }

    Ok(Value::Object(body))
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

fn supports(supported_parameters: &[String], name: &str) -> bool {
    supported_parameters
        .iter()
        .any(|parameter| parameter == name)
}

fn advanced_namespace(
    extra: &Map<String, Value>,
) -> Result<Option<&Map<String, Value>>, ChatError> {
    match extra.get("openrouter") {
        None | Some(Value::Null) => Ok(None),
        Some(Value::Object(map)) => Ok(Some(map)),
        Some(_) => Err(invalid_request(
            "OpenRouter advanced JSON must be a JSON object.",
            None,
        )),
    }
}

fn reject_protected_fields(advanced: Option<&Map<String, Value>>) -> Result<(), ChatError> {
    let Some(advanced) = advanced else {
        return Ok(());
    };

    let mut protected: Vec<&str> = PROTECTED_FIELDS
        .iter()
        .copied()
        .filter(|field| advanced.contains_key(*field))
        .collect();

    if protected.is_empty() {
        return Ok(());
    }

    protected.sort_unstable();
    let message = format!(
        "Advanced JSON cannot override protected fields: {}.",
        protected.join(", ")
    );

    Err(invalid_request(
        message,
        Some(json!({ "protectedFields": protected })),
    ))
}

fn apply_advanced(
    advanced: &Map<String, Value>,
    body: &mut Map<String, Value>,
) -> Result<(), ChatError> {
    for (key, value) in advanced {
        match key.as_str() {
            "provider" => {
                let provider = value.as_object().ok_or_else(|| {
                    invalid_request("OpenRouter provider routing must be a JSON object.", None)
                })?;
                let mut provider_body = Map::new();

                for (provider_key, provider_value) in provider {
                    match provider_key.as_str() {
                        "allow_fallbacks" => {
                            if !provider_value.is_boolean() {
                                return Err(invalid_request(
                                    "OpenRouter provider.allow_fallbacks must be a boolean.",
                                    None,
                                ));
                            }
                            provider_body.insert(provider_key.clone(), provider_value.clone());
                        }
                        "sort" => {
                            let sort = provider_value.as_str().ok_or_else(|| {
                                invalid_request("OpenRouter provider.sort must be a string.", None)
                            })?;
                            if !matches!(sort, "price" | "throughput" | "latency") {
                                return Err(invalid_request(
                                    "OpenRouter provider.sort must be one of price, throughput, or latency.",
                                    None,
                                ));
                            }
                            provider_body.insert(provider_key.clone(), provider_value.clone());
                        }
                        "data_collection" => {
                            let data_collection = provider_value.as_str().ok_or_else(|| {
                                invalid_request(
                                    "OpenRouter provider.data_collection must be a string.",
                                    None,
                                )
                            })?;
                            if !matches!(data_collection, "allow" | "deny") {
                                return Err(invalid_request(
                                    "OpenRouter provider.data_collection must be allow or deny.",
                                    None,
                                ));
                            }
                            provider_body.insert(provider_key.clone(), provider_value.clone());
                        }
                        "order" | "only" | "ignore" => {
                            validate_string_array(provider_key, provider_value)?;
                            provider_body.insert(provider_key.clone(), provider_value.clone());
                        }
                        _ => {
                            return Err(invalid_request(
                                format!("Unsupported OpenRouter provider field: {provider_key}."),
                                None,
                            ));
                        }
                    }
                }

                body.insert("provider".to_owned(), Value::Object(provider_body));
            }
            "models" => {
                validate_string_array("models", value)?;
                body.insert("models".to_owned(), value.clone());
            }
            "route" => {
                let route = value
                    .as_str()
                    .ok_or_else(|| invalid_request("OpenRouter route must be a string.", None))?;
                if route != "fallback" {
                    return Err(invalid_request("OpenRouter route must be fallback.", None));
                }
                body.insert("route".to_owned(), value.clone());
            }
            _ => {
                return Err(invalid_request(
                    format!("Unsupported OpenRouter advanced field: {key}."),
                    None,
                ));
            }
        }
    }

    Ok(())
}

fn validate_string_array(field: &str, value: &Value) -> Result<(), ChatError> {
    let array = value.as_array().ok_or_else(|| {
        invalid_request(
            format!("OpenRouter {field} must be an array of strings."),
            None,
        )
    })?;

    if !array.iter().all(Value::is_string) {
        return Err(invalid_request(
            format!("OpenRouter {field} must be an array of strings."),
            None,
        ));
    }

    Ok(())
}

fn serialize_message(
    message: &NormalizedChatMessage,
    attachments: &BTreeMap<String, ResolvedAttachment>,
) -> Result<Value, ChatError> {
    let mut text = String::new();
    let mut parts = Vec::new();
    let mut has_image = false;

    for part in &message.parts {
        match part {
            ContentPart::Text { text: part_text } => {
                text.push_str(part_text);
                parts.push(json!({ "type": "text", "text": part_text }));
            }
            ContentPart::File {
                attachment_id,
                name,
                ..
            } => {
                let attachment = attachments.get(attachment_id).ok_or_else(|| {
                    invalid_request(
                        format!("Attachment {attachment_id} is missing from the resolved request."),
                        None,
                    )
                })?;
                let bytes = BASE64_STANDARD
                    .decode(&attachment.data_base64)
                    .map_err(|_| {
                        invalid_request(
                            format!("Attachment {attachment_id} is not valid base64."),
                            None,
                        )
                    })?;
                let content = String::from_utf8(bytes).map_err(|_| {
                    invalid_request(
                        format!("Attachment {attachment_id} is not valid UTF-8 text."),
                        None,
                    )
                })?;
                let file_text = format!("\n\n[File: {name}]\n{content}");
                text.push_str(&file_text);
                parts.push(json!({ "type": "text", "text": file_text }));
            }
            ContentPart::Image {
                attachment_id,
                mime_type,
                ..
            } => {
                let attachment = attachments.get(attachment_id).ok_or_else(|| {
                    invalid_request(
                        format!("Attachment {attachment_id} is missing from the resolved request."),
                        None,
                    )
                })?;
                has_image = true;
                let url = format!("data:{mime_type};base64,{}", attachment.data_base64);
                parts.push(json!({
                    "type": "image_url",
                    "image_url": { "url": url }
                }));
            }
            ContentPart::Reasoning { .. } => {
                // Reasoning content is not sent back to providers.
            }
            ContentPart::ToolCall { .. } | ContentPart::ToolResult { .. } => {
                // Tools are not sent in phase 1.
            }
        }
    }

    let content = if has_image {
        Value::Array(parts)
    } else {
        Value::String(text)
    };

    Ok(json!({
        "role": message.role,
        "content": content,
    }))
}

fn invalid_request(message: impl Into<String>, details: Option<Value>) -> ChatError {
    ChatError {
        code: ChatErrorCode::InvalidRequest,
        message: message.into(),
        provider_id: Some("openrouter".to_owned()),
        status: None,
        retryable: false,
        retry_after_seconds: None,
        request_id: None,
        details,
        at: Timestamp::now(),
    }
}
