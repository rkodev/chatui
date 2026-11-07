//! Shared model discovery fallback selection and Ollama `/api/tags` mapping.
//!
//! Discovery is best-effort. When a provider catalog is blocked, empty, or
//! missing the preset default, onboarding still needs a deterministic model.
//! This module implements the shared rule from
//! `contracts/fixtures/expected/fallback-models.json`:
//!
//! 1. An empty catalog means discovery was blocked: pick the first fallback ID,
//!    or report that no fallback exists.
//! 2. Use the preset default when it exists in the catalog.
//! 3. Otherwise use the first fallback ID present in the catalog.
//! 4. Otherwise use the first non-hidden text-output model by `remoteId`.
//! 5. Otherwise report that no text model is available.
//!
//! It also maps Ollama's native `/api/tags` response into the shared
//! [`CatalogModel`] shape. The mapper never fails the whole catalog because one
//! entry is malformed.

use chatui_contracts::ProviderPreset;
use serde_json::Value;

use crate::capabilities::infer_model_metadata;
use crate::openai_catalog::{CatalogDefaults, OpenAiCatalogError, ollama_catalog_defaults};
use crate::openrouter::catalog::CatalogModel;

/// Explicit fallback model IDs for presets with shared catalog fixtures.
///
/// Every other preset falls back to its contract `defaultModelId`. OpenRouter
/// keeps its reference behavior: an empty catalog is a setup error, not a
/// fallback, because `/models` is public and reliable.
const EXPLICIT_FALLBACK_MODEL_IDS: &[(&str, &[&str])] = &[
    ("openai", &["gpt-4o-mini", "gpt-4o"]),
    ("anthropic", &["claude-sonnet-4-5", "claude-haiku-4-5"]),
    ("google", &["gemini-2.5-flash", "gemini-2.5-pro"]),
    ("ollama", &["llama3.2:latest"]),
];

/// Return the ordered fallback model IDs for one preset.
///
/// `preset` supplies the contract default for presets without an explicit
/// fallback list. Pass `None` when the preset is unknown.
pub fn fallback_model_ids_for(preset_id: &str, preset: Option<&ProviderPreset>) -> Vec<String> {
    if let Some((_, ids)) = EXPLICIT_FALLBACK_MODEL_IDS
        .iter()
        .find(|(id, _)| *id == preset_id)
    {
        return ids.iter().map(|id| (*id).to_owned()).collect();
    }

    if preset_id == "openrouter" {
        return Vec::new();
    }

    match preset {
        Some(preset) if !preset.default_model_id.trim().is_empty() => {
            vec![preset.default_model_id.clone()]
        }
        _ => Vec::new(),
    }
}

/// Minimal catalog entry used by the fallback selection rule.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct FallbackCatalogEntry {
    /// Provider model ID.
    pub remote_id: String,
    /// Whether the model is hidden from the picker.
    pub hidden: bool,
    /// Output modalities.
    pub output_modalities: Vec<String>,
}

/// The result of the shared fallback selection rule.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct FallbackSelection {
    /// Selected model ID, or `None` when no text model exists.
    pub selected_model_id: Option<String>,
    /// Non-blocking notice, or `None` when the preset default was used.
    pub notice: Option<String>,
}

fn is_usable_text_model(entry: &FallbackCatalogEntry) -> bool {
    !entry.hidden
        && entry
            .output_modalities
            .iter()
            .any(|modality| modality == "text")
}

/// Apply the shared fallback rule.
///
/// The returned notice is the exact shared string. It is non-blocking: callers
/// show it as a banner and keep chat available.
pub fn select_fallback_model(
    catalog: &[FallbackCatalogEntry],
    preset_default_model_id: &str,
    fallback_model_ids: &[String],
) -> FallbackSelection {
    if catalog.is_empty() {
        return match fallback_model_ids.first() {
            Some(fallback) => FallbackSelection {
                selected_model_id: Some(fallback.clone()),
                notice: Some(format!(
                    "Discovery unavailable; using fallback model {fallback}."
                )),
            },
            None => FallbackSelection {
                selected_model_id: None,
                notice: Some("Discovery unavailable; no fallback model is available.".to_owned()),
            },
        };
    }

    if let Some(preset_default) = catalog
        .iter()
        .find(|entry| entry.remote_id == preset_default_model_id)
    {
        return FallbackSelection {
            selected_model_id: Some(preset_default.remote_id.clone()),
            notice: None,
        };
    }

    for id in fallback_model_ids {
        if let Some(found) = catalog
            .iter()
            .find(|entry| entry.remote_id == *id && is_usable_text_model(entry))
        {
            return FallbackSelection {
                selected_model_id: Some(found.remote_id.clone()),
                notice: Some(format!(
                    "Default model {preset_default_model_id} is unavailable; using {}.",
                    found.remote_id
                )),
            };
        }
    }

    let mut text_models: Vec<&FallbackCatalogEntry> = catalog
        .iter()
        .filter(|entry| is_usable_text_model(entry))
        .collect();
    text_models.sort_by(|left, right| left.remote_id.cmp(&right.remote_id));

    if let Some(first) = text_models.first() {
        return FallbackSelection {
            selected_model_id: Some(first.remote_id.clone()),
            notice: Some(format!(
                "Default model {preset_default_model_id} is unavailable; using {}.",
                first.remote_id
            )),
        };
    }

    FallbackSelection {
        selected_model_id: None,
        notice: Some(format!(
            "Default model {preset_default_model_id} is unavailable; no text model is available."
        )),
    }
}

/// Map one Ollama `/api/tags` entry.
///
/// Ollama reports the model ID in `model` (with `name` as a fallback) and no
/// capability metadata. Missing fields therefore fall back to the shared Ollama
/// preset defaults. Embedding models are marked hidden so they never become the
/// default chat model.
pub fn map_ollama_model(raw: &Value, defaults: &CatalogDefaults) -> Option<CatalogModel> {
    if !raw.is_object() {
        return None;
    }

    let remote_id = raw
        .get("model")
        .and_then(Value::as_str)
        .map(str::trim)
        .filter(|value| !value.is_empty())
        .or_else(|| {
            raw.get("name")
                .and_then(Value::as_str)
                .map(str::trim)
                .filter(|value| !value.is_empty())
        })?;

    let inferred = infer_model_metadata(raw, defaults);

    Some(CatalogModel {
        remote_id: remote_id.to_owned(),
        canonical_slug: None,
        label: remote_id.to_owned(),
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

/// Map an Ollama `/api/tags` envelope to normalized entries.
///
/// The envelope must contain a `models` array. Malformed entries are skipped; a
/// malformed envelope is an error.
pub fn map_ollama_tags(payload: &Value) -> Result<Vec<CatalogModel>, OpenAiCatalogError> {
    let Some(entries) = payload.get("models").and_then(Value::as_array) else {
        return Err(OpenAiCatalogError::new(
            "Ollama /api/tags response must contain a models array",
        ));
    };

    let defaults = ollama_catalog_defaults();
    Ok(entries
        .iter()
        .filter_map(|entry| map_ollama_model(entry, &defaults))
        .collect())
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    fn entry(remote_id: &str, hidden: bool, output_modalities: &[&str]) -> FallbackCatalogEntry {
        FallbackCatalogEntry {
            remote_id: remote_id.to_owned(),
            hidden,
            output_modalities: output_modalities
                .iter()
                .map(|value| (*value).to_owned())
                .collect(),
        }
    }

    #[test]
    fn preset_default_wins_when_present() {
        let selection = select_fallback_model(
            &[entry("gpt-4o-mini", false, &["text"])],
            "gpt-4o-mini",
            &["gpt-4o-mini".to_owned()],
        );

        assert_eq!(selection.selected_model_id.as_deref(), Some("gpt-4o-mini"));
        assert_eq!(selection.notice, None);
    }

    #[test]
    fn empty_catalog_uses_the_first_fallback() {
        let selection =
            select_fallback_model(&[], "gemini-2.5-flash", &["gemini-2.5-flash".to_owned()]);

        assert_eq!(
            selection.selected_model_id.as_deref(),
            Some("gemini-2.5-flash")
        );
        assert_eq!(
            selection.notice.as_deref(),
            Some("Discovery unavailable; using fallback model gemini-2.5-flash.")
        );
    }

    #[test]
    fn no_text_model_reports_the_shared_notice() {
        let selection = select_fallback_model(
            &[
                entry("aaa-hidden-text", true, &["text"]),
                entry("example/image-only", false, &["image"]),
            ],
            "llama3.2:latest",
            &["llama3.2:latest".to_owned()],
        );

        assert_eq!(selection.selected_model_id, None);
        assert_eq!(
            selection.notice.as_deref(),
            Some("Default model llama3.2:latest is unavailable; no text model is available.")
        );
    }

    #[test]
    fn first_text_model_is_selected_by_remote_id() {
        let selection = select_fallback_model(
            &[
                entry("zeta", false, &["text"]),
                entry("alpha", false, &["text"]),
            ],
            "missing",
            &[],
        );

        assert_eq!(selection.selected_model_id.as_deref(), Some("alpha"));
        assert_eq!(
            selection.notice.as_deref(),
            Some("Default model missing is unavailable; using alpha.")
        );
    }

    #[test]
    fn ollama_tags_map_into_the_shared_catalog_shape() {
        let models = map_ollama_tags(&json!({
            "models": [
                { "name": "llama3.2:latest", "model": "llama3.2:latest" },
                { "name": "nomic-embed-text:latest", "model": "nomic-embed-text:latest" }
            ]
        }))
        .expect("tags map");

        assert_eq!(models.len(), 2);
        assert_eq!(models[0].remote_id, "llama3.2:latest");
        assert!(!models[0].hidden);
        assert_eq!(models[1].remote_id, "nomic-embed-text:latest");
        assert!(models[1].hidden);
    }

    #[test]
    fn malformed_tags_envelope_is_an_error() {
        let error = map_ollama_tags(&json!({ "object": "list" })).expect_err("malformed envelope");
        assert!(error.message().contains("models array"));
    }
}
