//! Typed expected vectors other than stream and error vectors.

use chatui_domain::FinishReason;
use serde::{Deserialize, Serialize};
use serde_json::Value;

use crate::streams::ExpectedStreamEvent;

/// Catalog mapping vectors from `catalogs.json`.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CatalogVectors {
    /// Vector family name.
    pub source: String,
    /// One case per provider catalog.
    pub cases: Vec<CatalogCase>,
}

/// One provider catalog mapping case.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CatalogCase {
    /// Case name.
    pub name: String,
    /// Provider preset ID.
    pub provider_id: String,
    /// Raw model fixture path relative to `contracts/fixtures/`.
    pub fixture: String,
    /// Preset defaults used for inference.
    pub preset: CatalogDefaults,
    /// Expected normalized catalog entries.
    pub expected: Vec<CatalogEntry>,
}

/// Preset defaults used when a raw catalog omits a field.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CatalogDefaults {
    /// Input modalities.
    pub input_modalities: Vec<String>,
    /// Output modalities.
    pub output_modalities: Vec<String>,
    /// Capability flags.
    pub capabilities: Vec<String>,
    /// Supported request parameters.
    pub supported_parameters: Vec<String>,
}

/// One expected normalized catalog entry.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CatalogEntry {
    /// Provider model ID.
    pub remote_id: String,
    /// Display label.
    pub label: String,
    /// Context window, or `null` when unknown.
    pub context_window: Option<u32>,
    /// Maximum output tokens, or `null` when unknown.
    pub max_output_tokens: Option<u32>,
    /// Input modalities.
    pub input_modalities: Vec<String>,
    /// Output modalities.
    pub output_modalities: Vec<String>,
    /// Capability flags.
    pub capabilities: Vec<String>,
    /// Supported request parameters.
    pub supported_parameters: Vec<String>,
    /// Pricing, or `null` when unknown.
    pub pricing: Option<Value>,
    /// Whether the model is hidden from the picker.
    pub hidden: bool,
}

/// Capability inference vectors from `capability-inference.json`.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CapabilityInference {
    /// Vector family name.
    pub source: String,
    /// One case per inference rule.
    pub cases: Vec<CapabilityCase>,
}

/// One capability inference case.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CapabilityCase {
    /// Case name.
    pub name: String,
    /// Preset defaults.
    pub preset: CapabilityPreset,
    /// Raw provider model fields.
    pub raw: Value,
    /// Expected inferred fields.
    pub expected: CapabilityExpected,
}

/// Preset defaults for capability inference.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CapabilityPreset {
    /// Preset ID.
    pub id: String,
    /// Provider kind.
    pub kind: String,
    /// Input modalities.
    pub input_modalities: Vec<String>,
    /// Output modalities.
    pub output_modalities: Vec<String>,
    /// Capability flags.
    pub capabilities: Vec<String>,
    /// Supported request parameters.
    pub supported_parameters: Vec<String>,
}

/// Expected inferred capability fields.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CapabilityExpected {
    /// Input modalities.
    pub input_modalities: Vec<String>,
    /// Output modalities.
    pub output_modalities: Vec<String>,
    /// Capability flags.
    pub capabilities: Vec<String>,
    /// Supported request parameters.
    pub supported_parameters: Vec<String>,
    /// Context window, or `null` when unknown.
    pub context_window: Option<u32>,
    /// Maximum output tokens, or `null` when unknown.
    pub max_output_tokens: Option<u32>,
}

/// Fallback model vectors from `fallback-models.json`.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct FallbackModels {
    /// Vector family name.
    pub source: String,
    /// Fallback IDs per non-OpenRouter preset.
    pub presets: Vec<FallbackPreset>,
    /// Selection cases.
    pub cases: Vec<FallbackCase>,
}

/// Fallback model IDs for one preset.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct FallbackPreset {
    /// Provider preset ID.
    pub provider_id: String,
    /// Ordered fallback model IDs.
    pub fallback_model_ids: Vec<String>,
}

/// One default-model selection case.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct FallbackCase {
    /// Case name.
    pub name: String,
    /// Provider preset ID.
    pub provider_id: String,
    /// Preset default model ID.
    pub preset_default_model_id: String,
    /// Ordered fallback model IDs.
    pub fallback_model_ids: Vec<String>,
    /// Catalog entries available to the selector.
    pub catalog: Vec<FallbackCatalogEntry>,
    /// Expected selection and notice.
    pub expected: FallbackExpected,
}

/// A catalog entry used by a fallback case.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct FallbackCatalogEntry {
    /// Provider model ID.
    pub remote_id: String,
    /// Whether the model is hidden from the picker.
    pub hidden: bool,
    /// Output modalities.
    pub output_modalities: Vec<String>,
}

/// Expected fallback selection.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct FallbackExpected {
    /// Selected model ID, or `null` when no text model exists.
    pub selected_model_id: Option<String>,
    /// Non-blocking notice, or `null` when the preset default was used.
    pub notice: Option<String>,
}

/// Math segmentation vectors from `math.json`.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct MathVectors {
    /// Vector family name.
    pub source: String,
    /// One case per math input.
    pub cases: Vec<MathCase>,
}

/// One math segmentation case.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct MathCase {
    /// Case name.
    pub name: String,
    /// Raw input text.
    pub input: String,
    /// Whether the input is valid math.
    pub valid: bool,
    /// Expected segments and fallback mode.
    pub expected: MathExpected,
}

/// Expected math segmentation.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct MathExpected {
    /// Ordered text and math segments.
    pub segments: Vec<MathSegment>,
    /// Fallback mode, or `null` when the input is valid.
    pub fallback: Option<String>,
}

/// One math segmentation segment.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(tag = "type", rename_all = "lowercase")]
pub enum MathSegment {
    /// Plain text.
    Text {
        /// Text content.
        text: String,
    },
    /// Math source.
    Math {
        /// Whether the math is display-style.
        display: bool,
        /// Math source without delimiters.
        source: String,
    },
}

/// Gemini finish-reason mapping vectors.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct GeminiFinishReasons {
    /// Vector family name.
    pub source: String,
    /// One case per Gemini finish reason.
    pub cases: Vec<GeminiFinishCase>,
}

/// One Gemini finish-reason mapping case.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct GeminiFinishCase {
    /// Case name.
    pub name: String,
    /// Raw Gemini finish reason.
    pub finish_reason: String,
    /// Expected normalized finish reason.
    pub expected: FinishReason,
}

/// Synthetic Anthropic thinking/text separation vectors.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AnthropicThinking {
    /// Vector family name.
    pub source: String,
    /// One case per thinking/text sequence.
    pub cases: Vec<AnthropicThinkingCase>,
}

/// One Anthropic thinking/text case.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AnthropicThinkingCase {
    /// Case name.
    pub name: String,
    /// Raw provider events.
    pub raw_events: Vec<Value>,
    /// Expected normalized events.
    pub events: Vec<ExpectedStreamEvent>,
    /// Expected concatenated text.
    pub text: String,
    /// Expected concatenated reasoning.
    pub reasoning: String,
}

/// OpenRouter key introspection expectation.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct KeyExpectation {
    /// Vector family name.
    pub source: String,
    /// Provider preset ID.
    pub provider_id: String,
    /// Expected key metadata.
    pub key: KeyInfo,
}

/// Expected OpenRouter key metadata.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct KeyInfo {
    /// Key label.
    pub label: String,
    /// Cumulative usage in USD.
    pub usage_usd: f64,
    /// Credit limit, or `null` when none.
    pub limit_usd: Option<f64>,
    /// Remaining credit, or `null` when none.
    pub limit_remaining_usd: Option<f64>,
    /// Whether the key is on the free tier.
    pub is_free_tier: bool,
    /// Rate limit, or `null` when not reported.
    pub rate_limit: Option<RateLimit>,
}

/// Expected rate limit metadata.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct RateLimit {
    /// Requests allowed per interval.
    pub requests: u32,
    /// Interval string, such as `1m`.
    pub interval: String,
}
