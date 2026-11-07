//! OpenAI-compatible `/models` catalog mapping.
//!
//! OpenAI-compatible providers expose a small catalog: usually only `id` and
//! `object`. Capability metadata is therefore inferred from any raw fields the
//! provider does expose, falling back to the preset defaults from the shared
//! contract vectors. The mapper never fails the whole catalog because one entry
//! is malformed.
//!
//! The output is [`CatalogModel`], the catalog-shaped subset of the shared
//! [`chatui_domain::Model`] shape. Callers assign local identity and timestamps
//! with [`CatalogModel::to_model`].

use std::fmt;

use chatui_contracts::ProviderPreset;
use chatui_domain::{InputModality, ModelCapability, OutputModality, ProviderKind};
use serde_json::Value;

use crate::capabilities::infer_model_metadata;
use crate::openrouter::catalog::CatalogModel;

/// Defaults used when an OpenAI-compatible `/models` response omits metadata.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct CatalogDefaults {
    /// Input modalities.
    pub input_modalities: Vec<InputModality>,
    /// Output modalities.
    pub output_modalities: Vec<OutputModality>,
    /// Capability flags.
    pub capabilities: Vec<ModelCapability>,
    /// Supported request parameters.
    pub supported_parameters: Vec<String>,
}

/// Defaults for OpenAI-compatible presets.
pub fn openai_catalog_defaults() -> CatalogDefaults {
    CatalogDefaults {
        input_modalities: vec![InputModality::Text],
        output_modalities: vec![OutputModality::Text],
        capabilities: vec![ModelCapability::Text, ModelCapability::Streaming],
        supported_parameters: vec![
            "temperature".to_owned(),
            "top_p".to_owned(),
            "max_tokens".to_owned(),
            "stop".to_owned(),
            "stream".to_owned(),
        ],
    }
}

/// Defaults for the Anthropic preset.
pub fn anthropic_catalog_defaults() -> CatalogDefaults {
    CatalogDefaults {
        input_modalities: vec![InputModality::Text, InputModality::Image],
        output_modalities: vec![OutputModality::Text],
        capabilities: vec![
            ModelCapability::Text,
            ModelCapability::Vision,
            ModelCapability::Streaming,
        ],
        supported_parameters: openai_catalog_defaults().supported_parameters,
    }
}

/// Defaults for the Google Gemini preset.
pub fn google_catalog_defaults() -> CatalogDefaults {
    CatalogDefaults {
        input_modalities: vec![
            InputModality::Text,
            InputModality::Image,
            InputModality::File,
        ],
        output_modalities: vec![OutputModality::Text],
        capabilities: vec![
            ModelCapability::Text,
            ModelCapability::Vision,
            ModelCapability::Files,
            ModelCapability::Streaming,
        ],
        supported_parameters: openai_catalog_defaults().supported_parameters,
    }
}

/// Defaults for the Ollama preset.
///
/// Ollama's `/api/tags` response reports no capability metadata, so the shared
/// text-only defaults apply. This is the same shape as the OpenAI defaults but
/// is named separately so the Ollama discovery mapper does not depend on a
/// provider-name branch.
pub fn ollama_catalog_defaults() -> CatalogDefaults {
    openai_catalog_defaults()
}

/// Return the capability-inference defaults for one contract preset.
///
/// The defaults are selected by provider kind, not by a hardcoded provider
/// name. Ollama is an OpenAI-compatible kind and therefore uses the same
/// text-only defaults as the other OpenAI-compatible presets.
pub fn defaults_for_preset(preset: &ProviderPreset) -> CatalogDefaults {
    match preset.kind {
        ProviderKind::Anthropic => anthropic_catalog_defaults(),
        ProviderKind::GoogleGemini => google_catalog_defaults(),
        ProviderKind::OpenaiCompatible => openai_catalog_defaults(),
    }
}

/// Raised when an OpenAI-compatible `/models` payload cannot be normalized.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct OpenAiCatalogError {
    message: String,
}

impl OpenAiCatalogError {
    /// Build a catalog error with a user-safe message.
    pub fn new(message: impl Into<String>) -> Self {
        Self {
            message: message.into(),
        }
    }

    /// Return the error message.
    pub fn message(&self) -> &str {
        &self.message
    }
}

impl fmt::Display for OpenAiCatalogError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter.write_str(&self.message)
    }
}

impl std::error::Error for OpenAiCatalogError {}

fn is_record(value: &Value) -> bool {
    value.is_object()
}

/// Map one OpenAI-compatible model entry.
///
/// Raw provider values win. Missing modalities, capabilities, and supported
/// parameters fall back to the preset defaults. Missing limits stay `None`.
/// Embedding models are marked hidden so they never become the default chat
/// model.
pub fn map_model(raw: &Value, defaults: &CatalogDefaults) -> Option<CatalogModel> {
    if !is_record(raw) {
        return None;
    }

    let remote_id = raw.get("id").and_then(Value::as_str)?.trim();
    if remote_id.is_empty() {
        return None;
    }

    let inferred = infer_model_metadata(raw, defaults);

    let label = raw
        .get("name")
        .and_then(Value::as_str)
        .or_else(|| raw.get("display_name").and_then(Value::as_str))
        .or_else(|| raw.get("displayName").and_then(Value::as_str))
        .filter(|label| !label.trim().is_empty())
        .unwrap_or(remote_id)
        .to_owned();

    Some(CatalogModel {
        remote_id: remote_id.to_owned(),
        canonical_slug: None,
        label,
        context_window: inferred.context_window,
        max_output_tokens: inferred.max_output_tokens,
        capabilities: inferred.capabilities,
        pricing: None,
        input_modalities: inferred.input_modalities,
        output_modalities: inferred.output_modalities,
        supported_parameters: inferred.supported_parameters,
        metadata: None,
        is_free: false,
        hidden: remote_id.to_ascii_lowercase().contains("embed"),
    })
}

/// Map an OpenAI-compatible `/models` envelope to normalized entries.
///
/// The envelope may be `{ "data": [...] }` or a bare array. Malformed entries
/// are skipped; a malformed envelope is an error.
pub fn map_models(
    payload: &Value,
    defaults: &CatalogDefaults,
) -> Result<Vec<CatalogModel>, OpenAiCatalogError> {
    let entries = if let Some(entries) = payload.as_array() {
        entries
    } else if let Some(entries) = payload.get("data").and_then(Value::as_array) {
        entries
    } else {
        return Err(OpenAiCatalogError::new(
            "OpenAI /models response must contain a data array",
        ));
    };

    Ok(entries
        .iter()
        .filter_map(|entry| map_model(entry, defaults))
        .collect())
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn missing_metadata_uses_preset_defaults() {
        let model = map_model(&json!({ "id": "gpt-4o-mini" }), &openai_catalog_defaults())
            .expect("model maps");

        assert_eq!(model.remote_id, "gpt-4o-mini");
        assert_eq!(model.label, "gpt-4o-mini");
        assert_eq!(model.input_modalities, vec![InputModality::Text]);
        assert_eq!(model.output_modalities, vec![OutputModality::Text]);
        assert_eq!(
            model.capabilities,
            vec![ModelCapability::Text, ModelCapability::Streaming]
        );
        assert_eq!(
            model.supported_parameters,
            vec!["temperature", "top_p", "max_tokens", "stop", "stream"]
        );
        assert_eq!(model.context_window, None);
        assert_eq!(model.max_output_tokens, None);
        assert!(!model.hidden);
    }

    #[test]
    fn raw_modalities_override_preset_defaults() {
        let model = map_model(
            &json!({
                "id": "vision-model",
                "inputModalities": ["text", "image"],
                "outputModalities": ["text"]
            }),
            &openai_catalog_defaults(),
        )
        .expect("model maps");

        assert_eq!(
            model.input_modalities,
            vec![InputModality::Text, InputModality::Image]
        );
        assert_eq!(
            model.capabilities,
            vec![
                ModelCapability::Text,
                ModelCapability::Vision,
                ModelCapability::Streaming
            ]
        );
    }

    #[test]
    fn declared_capabilities_are_ordered() {
        let model = map_model(
            &json!({
                "id": "reasoning-model",
                "capabilities": ["streaming", "reasoning", "text"]
            }),
            &openai_catalog_defaults(),
        )
        .expect("model maps");

        assert_eq!(
            model.capabilities,
            vec![
                ModelCapability::Text,
                ModelCapability::Reasoning,
                ModelCapability::Streaming
            ]
        );
    }

    #[test]
    fn embedding_models_are_hidden() {
        let model = map_model(
            &json!({ "id": "text-embedding-3-small" }),
            &openai_catalog_defaults(),
        )
        .expect("model maps");

        assert!(model.hidden);
    }

    #[test]
    fn malformed_entries_are_skipped() {
        let models = map_models(
            &json!({
                "data": [
                    { "id": "gpt-4o-mini" },
                    { "id": "" },
                    { "object": "model" },
                    "not-an-object"
                ]
            }),
            &openai_catalog_defaults(),
        )
        .expect("catalog maps");

        assert_eq!(models.len(), 1);
        assert_eq!(models[0].remote_id, "gpt-4o-mini");
    }

    #[test]
    fn malformed_envelope_is_an_error() {
        let error = map_models(&json!({ "object": "list" }), &openai_catalog_defaults())
            .expect_err("malformed envelope");
        assert!(error.message().contains("data array"));
    }

    #[test]
    fn bare_array_envelopes_are_accepted() {
        let models = map_models(
            &json!([{ "id": "gpt-4o-mini" }]),
            &openai_catalog_defaults(),
        )
        .expect("catalog maps");
        assert_eq!(models.len(), 1);
    }
}
