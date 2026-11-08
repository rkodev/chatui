//! Model entity and model enums from spec section 6.2.

use serde::{Deserialize, Serialize};
use serde_json::Value;

use crate::time::Timestamp;

/// Capability flags from spec section 6.2.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum ModelCapability {
    /// Text generation.
    Text,
    /// Image understanding.
    Vision,
    /// File understanding.
    Files,
    /// Tool calling.
    Tools,
    /// Reasoning output.
    Reasoning,
    /// Streaming responses.
    Streaming,
}

/// Input modalities accepted by a model.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum InputModality {
    /// Text input.
    Text,
    /// Image input.
    Image,
    /// File input.
    File,
    /// Audio input.
    Audio,
    /// Video input.
    Video,
}

/// Output modalities produced by a model.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum OutputModality {
    /// Text output.
    Text,
    /// Image output.
    Image,
    /// Audio output.
    Audio,
}

/// Where a model record came from.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum ModelSource {
    /// Discovered from a provider catalog.
    Discovered,
    /// Seeded from a provider preset.
    Preset,
    /// Added manually by the user.
    Manual,
}

/// Optional pricing fields.
///
/// Absent fields are omitted from JSON rather than encoded as `null`, matching
/// the contract schema.
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ModelPricing {
    /// Input price in USD per million tokens.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub input_per_m_tok: Option<f64>,
    /// Output price in USD per million tokens.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub output_per_m_tok: Option<f64>,
    /// Price in USD per request.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub per_request: Option<f64>,
    /// Currency code.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub currency: Option<String>,
}

/// Model entity from spec section 6.2.
///
/// Models are unique on `(provider_id, remote_id)`. Provider-specific catalog
/// fields belong in `metadata`; this type does not contain request payloads.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Model {
    /// Local model ID.
    pub id: String,
    /// Owning provider ID.
    pub provider_id: String,
    /// Provider-specific model identifier.
    pub remote_id: String,
    /// Display label.
    pub label: String,
    /// Context window in tokens, or `null` when unknown.
    pub context_window: Option<u32>,
    /// Maximum output tokens, or `null` when unknown.
    pub max_output_tokens: Option<u32>,
    /// Capability flags.
    pub capabilities: Vec<ModelCapability>,
    /// Pricing information, or `null` when unknown.
    pub pricing: Option<ModelPricing>,
    /// Canonical slug for deduplication and aliases.
    pub canonical_slug: Option<String>,
    /// Accepted input modalities.
    pub input_modalities: Vec<InputModality>,
    /// Produced output modalities.
    pub output_modalities: Vec<OutputModality>,
    /// Provider-supported request parameters.
    pub supported_parameters: Vec<String>,
    /// Non-secret provider metadata.
    pub metadata: Option<Value>,
    /// Whether the model is hidden from the default picker.
    pub hidden: bool,
    /// Whether the model is pinned.
    pub pinned: bool,
    /// Where the model record came from.
    pub source: ModelSource,
    /// Creation timestamp.
    pub created_at: Timestamp,
    /// Last update timestamp.
    pub updated_at: Timestamp,
}

#[cfg(test)]
mod tests {
    use super::*;

    fn fixed() -> Timestamp {
        Timestamp::parse("2026-11-04T12:00:00.000Z").expect("valid timestamp")
    }

    #[test]
    fn enum_values_match_shared_json() {
        assert_eq!(
            serde_json::to_string(&ModelCapability::Text).unwrap(),
            "\"text\""
        );
        assert_eq!(
            serde_json::to_string(&ModelCapability::Reasoning).unwrap(),
            "\"reasoning\""
        );
        assert_eq!(
            serde_json::to_string(&InputModality::Image).unwrap(),
            "\"image\""
        );
        assert_eq!(
            serde_json::to_string(&OutputModality::Audio).unwrap(),
            "\"audio\""
        );
        assert_eq!(
            serde_json::to_string(&ModelSource::Discovered).unwrap(),
            "\"discovered\""
        );
    }

    #[test]
    fn pricing_omits_absent_fields() {
        let pricing = ModelPricing {
            input_per_m_tok: Some(1.5),
            ..ModelPricing::default()
        };
        let json = serde_json::to_value(&pricing).expect("serialize pricing");
        assert_eq!(json, serde_json::json!({ "inputPerMTok": 1.5 }));
    }

    #[test]
    fn round_trips_with_nulls_empty_arrays_and_unknown_metadata() {
        let model = Model {
            id: "model-1".to_owned(),
            provider_id: "provider-1".to_owned(),
            remote_id: "author/model".to_owned(),
            label: "Model".to_owned(),
            context_window: None,
            max_output_tokens: None,
            capabilities: Vec::new(),
            pricing: None,
            canonical_slug: None,
            input_modalities: Vec::new(),
            output_modalities: Vec::new(),
            supported_parameters: Vec::new(),
            metadata: Some(serde_json::json!({
                "top_provider": { "is_moderated": false },
                "unknown": [true, null]
            })),
            hidden: false,
            pinned: false,
            source: ModelSource::Discovered,
            created_at: fixed(),
            updated_at: fixed(),
        };

        let json = serde_json::to_value(&model).expect("serialize model");
        assert_eq!(json["contextWindow"], Value::Null);
        assert_eq!(json["capabilities"], serde_json::json!([]));
        assert_eq!(json["pricing"], Value::Null);
        assert_eq!(json["metadata"]["unknown"][1], Value::Null);

        let parsed: Model = serde_json::from_value(json).expect("deserialize model");
        assert_eq!(parsed, model);
    }
}
