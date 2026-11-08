//! Request vectors under `contracts/fixtures/requests/`.

use std::collections::BTreeMap;

use chatui_contracts::AppConfig;
use chatui_domain::{ChatParams, ContentPart, MessageRole};
use serde::{Deserialize, Serialize};
use serde_json::Value;

use crate::streams::ExpectedChatError;

/// The four request-vector kinds defined by the shared contract.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "kebab-case")]
pub enum RequestKind {
    /// A normalized request and its expected provider body.
    ChatRequest,
    /// A request that attempts to override protected fields.
    ProtectedFields,
    /// Runtime app config and expected attribution headers.
    Attribution,
    /// Default-model selection cases.
    DefaultModel,
}

impl RequestKind {
    /// Every request-vector kind.
    pub const ALL: [Self; 4] = [
        Self::ChatRequest,
        Self::ProtectedFields,
        Self::Attribution,
        Self::DefaultModel,
    ];
}

/// Provider IDs accepted by the shared request vectors.
pub const KNOWN_PROVIDER_IDS: &[&str] = &["openrouter", "openai", "anthropic", "google", "ollama"];

/// A normalized request from spec section 7.3.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct NormalizedRequest {
    /// Provider model ID.
    pub model: String,
    /// System prompt, or `null` when absent.
    pub system: Option<String>,
    /// Conversation messages.
    pub messages: Vec<NormalizedMessage>,
    /// Generation controls.
    pub params: ChatParams,
}

/// One normalized request message.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct NormalizedMessage {
    /// Message role.
    pub role: MessageRole,
    /// Ordered content parts.
    pub parts: Vec<ContentPart>,
}

/// Resolved attachment bytes supplied by a request vector.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ResolvedAttachment {
    /// MIME type.
    pub mime_type: String,
    /// Original file name.
    pub name: String,
    /// Base64-encoded bytes.
    pub data_base64: String,
}

/// The expected result of a request vector.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(untagged)]
pub enum RequestExpectation {
    /// Expected provider request body.
    Body {
        /// Provider JSON body.
        body: Value,
    },
    /// Expected normalized error.
    Error {
        /// Expected error.
        error: ExpectedChatError,
    },
    /// Expected attribution headers.
    Headers {
        /// Header name to value.
        headers: BTreeMap<String, String>,
    },
}

/// One default-model selection case.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct DefaultModelCase {
    /// Case name.
    pub name: String,
    /// Preset default model ID.
    pub preset_default_model_id: String,
    /// Catalog entries available to the selector.
    pub catalog: Vec<DefaultModelCatalogEntry>,
    /// Expected selection and notice.
    pub expected: DefaultModelExpected,
}

/// A catalog entry used by a default-model case.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct DefaultModelCatalogEntry {
    /// Provider model ID.
    pub remote_id: String,
    /// Whether the model is hidden from the picker.
    pub hidden: bool,
    /// Output modalities.
    pub output_modalities: Vec<String>,
}

/// Expected default-model selection.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct DefaultModelExpected {
    /// Selected model ID, or `null` when no text model exists.
    pub selected_model_id: Option<String>,
    /// Non-blocking notice, or `null` when the preset default was used.
    pub notice: Option<String>,
}

/// A request vector under `contracts/fixtures/requests/`.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct RequestVector {
    /// Vector name. Must match the file stem.
    pub name: String,
    /// Vector kind.
    pub kind: RequestKind,
    /// Provider preset ID.
    pub provider_id: String,
    /// Normalized request, for `chat-request` and `protected-fields`.
    #[serde(default)]
    pub request: Option<NormalizedRequest>,
    /// Effective supported parameters for the selected model.
    #[serde(default)]
    pub supported_parameters: Vec<String>,
    /// Runtime app config, for `attribution`.
    #[serde(default)]
    pub app_config: Option<AppConfig>,
    /// Default-model cases, for `default-model`.
    #[serde(default)]
    pub cases: Vec<DefaultModelCase>,
    /// Resolved attachment bytes keyed by attachment ID.
    #[serde(default)]
    pub attachments: BTreeMap<String, ResolvedAttachment>,
    /// Expected result, absent for `default-model`.
    #[serde(default)]
    pub expected: Option<RequestExpectation>,
    /// File name. Set by the loader.
    #[serde(skip)]
    pub file: String,
}
