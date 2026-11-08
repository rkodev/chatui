//! Shared capability inference for provider catalogs.
//!
//! Providers expose wildly different amounts of model metadata. OpenRouter
//! reports modalities, capabilities, limits, and supported parameters; most
//! other providers report only an ID. This module applies the shared rule from
//! `contracts/fixtures/expected/capability-inference.json`:
//!
//! - raw provider values win;
//! - missing modalities and supported parameters fall back to the preset
//!   defaults;
//! - missing context and output limits stay `None`;
//! - missing capabilities are derived from the effective modalities and
//!   supported parameters in the canonical order `text`, `vision`, `files`,
//!   `tools`, `reasoning`, `streaming`.
//!
//! The module is provider-neutral. It never branches on a provider name and
//! never reads settings or secrets.

use std::collections::HashSet;

use chatui_domain::{InputModality, ModelCapability, OutputModality};
use serde_json::Value;

use crate::openai_catalog::CatalogDefaults;

/// Capability order used by the shared contract vectors.
pub const CAPABILITY_ORDER: [ModelCapability; 6] = [
    ModelCapability::Text,
    ModelCapability::Vision,
    ModelCapability::Files,
    ModelCapability::Tools,
    ModelCapability::Reasoning,
    ModelCapability::Streaming,
];

/// Metadata inferred from raw catalog fields and preset defaults.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct InferredModelMetadata {
    /// Effective input modalities.
    pub input_modalities: Vec<InputModality>,
    /// Effective output modalities.
    pub output_modalities: Vec<OutputModality>,
    /// Effective capability flags in canonical order.
    pub capabilities: Vec<ModelCapability>,
    /// Effective supported request parameters.
    pub supported_parameters: Vec<String>,
    /// Context window, or `None` when the provider omits it.
    pub context_window: Option<u32>,
    /// Maximum output tokens, or `None` when the provider omits it.
    pub max_output_tokens: Option<u32>,
}

/// Infer missing model metadata from the shared rule.
///
/// Raw provider values win. Missing modalities and supported parameters fall
/// back to `defaults`, missing limits are `None`, and missing capabilities are
/// derived in canonical order from the effective modalities and supported
/// parameters.
pub fn infer_model_metadata(raw: &Value, defaults: &CatalogDefaults) -> InferredModelMetadata {
    let declared_input = unique_input_modalities(first_defined(&[
        raw.get("inputModalities"),
        raw.get("input_modalities"),
    ]));
    let declared_output = unique_output_modalities(first_defined(&[
        raw.get("outputModalities"),
        raw.get("output_modalities"),
    ]));
    let declared_parameters = unique_strings(first_defined(&[
        raw.get("supportedParameters"),
        raw.get("supported_parameters"),
    ]));
    let declared_capabilities = ordered_capabilities(raw.get("capabilities"));

    let input_modalities = if declared_input.is_empty() {
        defaults.input_modalities.clone()
    } else {
        declared_input
    };
    let output_modalities = if declared_output.is_empty() {
        defaults.output_modalities.clone()
    } else {
        declared_output
    };
    let supported_parameters = if declared_parameters.is_empty() {
        defaults.supported_parameters.clone()
    } else {
        declared_parameters
    };
    let capabilities = if declared_capabilities.is_empty() {
        infer_capabilities(&input_modalities, &output_modalities, &supported_parameters)
    } else {
        declared_capabilities
    };

    InferredModelMetadata {
        input_modalities,
        output_modalities,
        capabilities,
        supported_parameters,
        context_window: to_positive_integer(first_defined(&[
            raw.get("contextWindow"),
            raw.get("context_window"),
            raw.get("context_length"),
        ])),
        max_output_tokens: to_positive_integer(first_defined(&[
            raw.get("maxOutputTokens"),
            raw.get("max_output_tokens"),
            raw.get("max_completion_tokens"),
        ])),
    }
}

/// Derive capability flags from effective modalities and supported parameters.
///
/// The result is always in [`CAPABILITY_ORDER`].
pub fn infer_capabilities(
    input_modalities: &[InputModality],
    output_modalities: &[OutputModality],
    supported_parameters: &[String],
) -> Vec<ModelCapability> {
    let mut capabilities = HashSet::new();

    if input_modalities.contains(&InputModality::Text)
        || output_modalities.contains(&OutputModality::Text)
    {
        capabilities.insert(ModelCapability::Text);
    }

    if input_modalities.contains(&InputModality::Image) {
        capabilities.insert(ModelCapability::Vision);
    }

    if input_modalities.contains(&InputModality::File) {
        capabilities.insert(ModelCapability::Files);
    }

    if supported_parameters
        .iter()
        .any(|parameter| parameter == "tools" || parameter == "tool_choice")
    {
        capabilities.insert(ModelCapability::Tools);
    }

    if supported_parameters
        .iter()
        .any(|parameter| parameter == "reasoning" || parameter == "include_reasoning")
    {
        capabilities.insert(ModelCapability::Reasoning);
    }

    if supported_parameters
        .iter()
        .any(|parameter| parameter == "stream")
    {
        capabilities.insert(ModelCapability::Streaming);
    }

    CAPABILITY_ORDER
        .iter()
        .copied()
        .filter(|capability| capabilities.contains(capability))
        .collect()
}

/// Normalize a declared capability array into canonical order.
///
/// Unknown values are ignored. An empty or missing array returns an empty
/// vector so the caller can infer capabilities instead.
pub fn ordered_capabilities(value: Option<&Value>) -> Vec<ModelCapability> {
    let Some(Value::Array(items)) = value else {
        return Vec::new();
    };

    let declared: HashSet<ModelCapability> = items
        .iter()
        .filter_map(Value::as_str)
        .filter_map(|value| match value {
            "text" => Some(ModelCapability::Text),
            "vision" => Some(ModelCapability::Vision),
            "files" => Some(ModelCapability::Files),
            "tools" => Some(ModelCapability::Tools),
            "reasoning" => Some(ModelCapability::Reasoning),
            "streaming" => Some(ModelCapability::Streaming),
            _ => None,
        })
        .collect();

    CAPABILITY_ORDER
        .iter()
        .copied()
        .filter(|capability| declared.contains(capability))
        .collect()
}

fn first_defined<'a>(values: &[Option<&'a Value>]) -> Option<&'a Value> {
    values
        .iter()
        .flatten()
        .copied()
        .find(|value| !value.is_null())
}

fn input_modality(value: &str) -> Option<InputModality> {
    match value {
        "text" => Some(InputModality::Text),
        "image" => Some(InputModality::Image),
        "file" => Some(InputModality::File),
        "audio" => Some(InputModality::Audio),
        "video" => Some(InputModality::Video),
        _ => None,
    }
}

fn output_modality(value: &str) -> Option<OutputModality> {
    match value {
        "text" => Some(OutputModality::Text),
        "image" => Some(OutputModality::Image),
        "audio" => Some(OutputModality::Audio),
        _ => None,
    }
}

fn unique_input_modalities(value: Option<&Value>) -> Vec<InputModality> {
    let Some(Value::Array(items)) = value else {
        return Vec::new();
    };

    let mut seen = HashSet::new();
    items
        .iter()
        .filter_map(Value::as_str)
        .filter_map(input_modality)
        .filter(|modality| seen.insert(*modality))
        .collect()
}

fn unique_output_modalities(value: Option<&Value>) -> Vec<OutputModality> {
    let Some(Value::Array(items)) = value else {
        return Vec::new();
    };

    let mut seen = HashSet::new();
    items
        .iter()
        .filter_map(Value::as_str)
        .filter_map(output_modality)
        .filter(|modality| seen.insert(*modality))
        .collect()
}

fn unique_strings(value: Option<&Value>) -> Vec<String> {
    let Some(Value::Array(items)) = value else {
        return Vec::new();
    };

    let mut seen = HashSet::new();
    items
        .iter()
        .filter_map(Value::as_str)
        .filter(|item| seen.insert((*item).to_owned()))
        .map(str::to_owned)
        .collect()
}

fn to_positive_integer(value: Option<&Value>) -> Option<u32> {
    let number = value?.as_u64()?;
    if number == 0 || number > u64::from(u32::MAX) {
        return None;
    }
    u32::try_from(number).ok()
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::openai_catalog::openai_catalog_defaults;
    use serde_json::json;

    #[test]
    fn missing_metadata_uses_preset_defaults() {
        let inferred =
            infer_model_metadata(&json!({ "id": "gpt-4o-mini" }), &openai_catalog_defaults());

        assert_eq!(inferred.input_modalities, vec![InputModality::Text]);
        assert_eq!(inferred.output_modalities, vec![OutputModality::Text]);
        assert_eq!(
            inferred.capabilities,
            vec![ModelCapability::Text, ModelCapability::Streaming]
        );
        assert_eq!(
            inferred.supported_parameters,
            vec!["temperature", "top_p", "max_tokens", "stop", "stream"]
        );
        assert_eq!(inferred.context_window, None);
        assert_eq!(inferred.max_output_tokens, None);
    }

    #[test]
    fn raw_modalities_override_preset_defaults() {
        let inferred = infer_model_metadata(
            &json!({
                "id": "vision-model",
                "inputModalities": ["text", "image"],
                "outputModalities": ["text"]
            }),
            &openai_catalog_defaults(),
        );

        assert_eq!(
            inferred.input_modalities,
            vec![InputModality::Text, InputModality::Image]
        );
        assert_eq!(
            inferred.capabilities,
            vec![
                ModelCapability::Text,
                ModelCapability::Vision,
                ModelCapability::Streaming
            ]
        );
    }

    #[test]
    fn declared_capabilities_are_ordered() {
        let inferred = infer_model_metadata(
            &json!({
                "id": "reasoning-model",
                "capabilities": ["streaming", "reasoning", "text"]
            }),
            &openai_catalog_defaults(),
        );

        assert_eq!(
            inferred.capabilities,
            vec![
                ModelCapability::Text,
                ModelCapability::Reasoning,
                ModelCapability::Streaming
            ]
        );
    }

    #[test]
    fn missing_capabilities_are_derived_from_parameters() {
        let inferred = infer_model_metadata(
            &json!({
                "id": "tool-model",
                "supportedParameters": ["temperature", "stream", "tools", "reasoning"]
            }),
            &openai_catalog_defaults(),
        );

        assert_eq!(
            inferred.capabilities,
            vec![
                ModelCapability::Text,
                ModelCapability::Tools,
                ModelCapability::Reasoning,
                ModelCapability::Streaming
            ]
        );
    }
}
