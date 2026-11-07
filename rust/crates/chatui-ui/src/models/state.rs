//! Framework-independent model management and picker view models.
//!
//! The view models are free of Slint, storage, and network types. They hold the
//! grouped model list, the picker selection and availability state, and the
//! manual-model form. The Slint controller in [`crate::models::view`] renders
//! this state and forwards user actions.
//!
//! Search matches the label, provider model ID, and canonical slug. Pinned
//! models are shown first in a dedicated group, then the remaining models are
//! grouped by provider. Hidden models are excluded from the picker unless the
//! caller is managing models. Provider model IDs are never rewritten.

#![allow(clippy::result_large_err)]

use std::collections::BTreeMap;

use chatui_contracts::provider_catalog;
use chatui_domain::{
    InputModality, Model, ModelCapability, ModelPricing, ModelSource, OutputModality, Provider,
};

use crate::models::error::{ModelManagementError, ModelManagementErrorCopy};

/// One model row in the management screen or picker.
#[derive(Debug, Clone, PartialEq)]
pub struct ModelRow {
    /// Local model ID.
    pub id: String,
    /// Owning provider ID.
    pub provider_id: String,
    /// Provider display label.
    pub provider_label: String,
    /// Provider model ID. Never rewritten.
    pub remote_id: String,
    /// Display label.
    pub label: String,
    /// Canonical slug, when reported.
    pub canonical_slug: Option<String>,
    /// Context window in tokens.
    pub context_window: Option<u32>,
    /// Maximum output tokens.
    pub max_output_tokens: Option<u32>,
    /// Capability flags.
    pub capabilities: Vec<ModelCapability>,
    /// Pricing, when reported.
    pub pricing: Option<ModelPricing>,
    /// Accepted input modalities.
    pub input_modalities: Vec<InputModality>,
    /// Produced output modalities.
    pub output_modalities: Vec<OutputModality>,
    /// Where the record came from.
    pub source: ModelSource,
    /// Whether the model is pinned.
    pub pinned: bool,
    /// Whether the model is hidden from the normal picker.
    pub hidden: bool,
    /// Whether the effective price is zero.
    pub is_free: bool,
    /// Whether this is the current default model.
    pub is_default: bool,
    /// Provider documentation URL from the preset metadata.
    pub docs_url: Option<String>,
}

impl ModelRow {
    /// Build a row from a model record.
    pub fn from_model(
        model: &Model,
        provider_label: &str,
        default_model_id: Option<&str>,
        docs_url: Option<String>,
    ) -> Self {
        let is_free = is_free_model(model.pricing.as_ref(), &model.remote_id);

        Self {
            id: model.id.clone(),
            provider_id: model.provider_id.clone(),
            provider_label: provider_label.to_owned(),
            remote_id: model.remote_id.clone(),
            label: model.label.clone(),
            canonical_slug: model.canonical_slug.clone(),
            context_window: model.context_window,
            max_output_tokens: model.max_output_tokens,
            capabilities: model.capabilities.clone(),
            pricing: model.pricing.clone(),
            input_modalities: model.input_modalities.clone(),
            output_modalities: model.output_modalities.clone(),
            source: model.source,
            pinned: model.pinned,
            hidden: model.hidden,
            is_free,
            is_default: default_model_id == Some(model.id.as_str()),
            docs_url,
        }
    }

    /// Capability chip labels in the model's stored order.
    pub fn capability_chips(&self) -> Vec<&'static str> {
        self.capabilities
            .iter()
            .copied()
            .map(capability_chip_label)
            .collect()
    }

    /// Compact context-window label.
    pub fn context_display(&self) -> String {
        format_context_window(self.context_window)
    }

    /// Input and output modality summary.
    pub fn modalities_display(&self) -> String {
        format_modalities(&self.input_modalities, &self.output_modalities)
    }

    /// Per-million input/output price line.
    pub fn price_display(&self) -> String {
        format_model_price(self.pricing.as_ref(), self.is_free)
    }

    /// Source label.
    pub fn source_label(&self) -> &'static str {
        source_label(self.source)
    }

    /// Badges shown on the row.
    pub fn badges(&self) -> Vec<&'static str> {
        let mut badges = Vec::new();

        if self.is_free {
            badges.push("Free");
        }
        if self.hidden {
            badges.push("Hidden");
        }
        if self.is_default {
            badges.push("Default");
        }

        badges
    }

    /// Compact metadata line for the row.
    pub fn meta_line(&self) -> String {
        [
            self.remote_id.clone(),
            self.context_display(),
            self.modalities_display(),
            self.price_display(),
            self.source_label().to_owned(),
        ]
        .join(" · ")
    }
}

/// A group of model rows.
#[derive(Debug, Clone, PartialEq)]
pub struct ModelGroup {
    /// Stable group key.
    pub key: String,
    /// Group heading.
    pub label: String,
    /// Whether this is the pinned group.
    pub pinned: bool,
    /// Rows in display order.
    pub models: Vec<ModelRow>,
}

/// Availability of the selected model.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ModelAvailability {
    /// Whether the model can be used for a send.
    pub available: bool,
    /// Warning copy when the model is unavailable.
    pub message: Option<String>,
}

impl Default for ModelAvailability {
    fn default() -> Self {
        Self {
            available: true,
            message: None,
        }
    }
}

/// Model management list state.
#[derive(Debug, Clone, PartialEq, Default)]
pub struct ModelListViewModel {
    /// Grouped rows.
    pub groups: Vec<ModelGroup>,
    /// Total number of rows.
    pub total: usize,
    /// Number of pinned rows.
    pub pinned_count: usize,
    /// Number of hidden rows.
    pub hidden_count: usize,
    /// Whether a refresh is in flight.
    pub loading: bool,
    /// Non-blocking notice, such as a degraded discovery result.
    pub notice: Option<String>,
    /// Current error copy.
    pub error: Option<ModelManagementErrorCopy>,
}

impl ModelListViewModel {
    /// Whether the list has no rows.
    pub fn is_empty(&self) -> bool {
        self.total == 0
    }

    /// Flatten the groups into rows in display order.
    pub fn rows(&self) -> Vec<&ModelRow> {
        self.groups
            .iter()
            .flat_map(|group| group.models.iter())
            .collect()
    }
}

/// Model picker state.
#[derive(Debug, Clone, PartialEq, Default)]
pub struct ModelPickerViewModel {
    /// Grouped rows after search and hidden filtering.
    pub groups: Vec<ModelGroup>,
    /// Current search query.
    pub query: String,
    /// Selected model ID.
    pub selected_model_id: Option<String>,
    /// Current default model ID.
    pub default_model_id: Option<String>,
    /// Availability of the selected model.
    pub availability: ModelAvailability,
    /// Documentation URL for the selected model's provider.
    pub docs_url: Option<String>,
    /// Non-blocking notice.
    pub notice: Option<String>,
    /// Current error copy.
    pub error: Option<ModelManagementErrorCopy>,
}

impl ModelPickerViewModel {
    /// Whether the picker has no rows.
    pub fn is_empty(&self) -> bool {
        self.groups.iter().all(|group| group.models.is_empty())
    }

    /// Flatten the groups into rows in display order.
    pub fn rows(&self) -> Vec<&ModelRow> {
        self.groups
            .iter()
            .flat_map(|group| group.models.iter())
            .collect()
    }
}

/// Manual model form state.
#[derive(Debug, Clone, PartialEq)]
pub struct ManualModelForm {
    /// Provider the model belongs to.
    pub provider_id: String,
    /// Provider model ID.
    pub remote_id: String,
    /// Display label.
    pub label: String,
    /// Context window in tokens.
    pub context_window: Option<u32>,
    /// Maximum output tokens.
    pub max_output_tokens: Option<u32>,
    /// Capability flags.
    pub capabilities: Vec<ModelCapability>,
    /// Accepted input modalities.
    pub input_modalities: Vec<InputModality>,
    /// Produced output modalities.
    pub output_modalities: Vec<OutputModality>,
    /// Form-level error copy.
    pub error: Option<ModelManagementErrorCopy>,
}

impl ManualModelForm {
    /// Create an empty form for one provider.
    pub fn new(provider_id: impl Into<String>) -> Self {
        Self {
            provider_id: provider_id.into(),
            remote_id: String::new(),
            label: String::new(),
            context_window: None,
            max_output_tokens: None,
            capabilities: vec![ModelCapability::Text],
            input_modalities: vec![InputModality::Text],
            output_modalities: vec![OutputModality::Text],
            error: None,
        }
    }

    /// Set the context window from the text field.
    pub fn set_context_window_text(&mut self, text: &str) -> Result<(), ModelManagementError> {
        self.context_window = parse_context_window(text)?;
        Ok(())
    }

    /// Set the capabilities from a comma-separated text field.
    pub fn set_capabilities_text(&mut self, text: &str) -> Result<(), ModelManagementError> {
        self.capabilities = parse_capabilities(text)?;
        Ok(())
    }
}

/// Complete model management view state.
#[derive(Debug, Clone, PartialEq, Default)]
pub struct ModelManagementViewModel {
    /// Selected provider ID.
    pub provider_id: Option<String>,
    /// Selected provider label.
    pub provider_label: String,
    /// Current search query.
    pub query: String,
    /// Whether the picker is shown instead of the management list.
    pub picker_mode: bool,
    /// Management list.
    pub list: ModelListViewModel,
    /// Picker state.
    pub picker: ModelPickerViewModel,
    /// Open manual-model form.
    pub manual: Option<ManualModelForm>,
    /// Non-blocking notice.
    pub notice: Option<String>,
    /// Current error copy.
    pub error: Option<ModelManagementErrorCopy>,
}

impl ModelManagementViewModel {
    /// Clear transient notices and errors.
    pub fn clear_messages(&mut self) {
        self.notice = None;
        self.error = None;
    }
}

/// Capability chip label.
pub fn capability_chip_label(capability: ModelCapability) -> &'static str {
    match capability {
        ModelCapability::Text => "Text",
        ModelCapability::Vision => "Vision",
        ModelCapability::Files => "Files",
        ModelCapability::Tools => "Tools",
        ModelCapability::Reasoning => "Reasoning",
        ModelCapability::Streaming => "Streaming",
    }
}

/// Source label.
pub fn source_label(source: ModelSource) -> &'static str {
    match source {
        ModelSource::Discovered => "Discovered",
        ModelSource::Preset => "Preset",
        ModelSource::Manual => "Manual",
    }
}

fn input_modality_label(modality: InputModality) -> &'static str {
    match modality {
        InputModality::Text => "text",
        InputModality::Image => "image",
        InputModality::File => "file",
        InputModality::Audio => "audio",
        InputModality::Video => "video",
    }
}

fn output_modality_label(modality: OutputModality) -> &'static str {
    match modality {
        OutputModality::Text => "text",
        OutputModality::Image => "image",
        OutputModality::Audio => "audio",
    }
}

fn trim_decimal(value: f64, digits: usize) -> String {
    let text = format!("{value:.digits$}");
    text.trim_end_matches('0').trim_end_matches('.').to_owned()
}

/// Compact context-window label, for example `128K context`.
pub fn format_context_window(value: Option<u32>) -> String {
    let Some(value) = value.filter(|value| *value > 0) else {
        return "Context unknown".to_owned();
    };

    if value >= 1_000_000 {
        format!(
            "{}M context",
            trim_decimal(f64::from(value) / 1_000_000.0, 1)
        )
    } else if value >= 1_000 {
        format!("{}K context", trim_decimal(f64::from(value) / 1_000.0, 1))
    } else {
        format!("{value} context")
    }
}

/// Format a per-million-token price, or `Free` when the effective price is zero.
pub fn format_price_per_million(value: Option<f64>) -> String {
    match value {
        None => "—".to_owned(),
        Some(value) if !value.is_finite() => "—".to_owned(),
        Some(0.0) => "Free".to_owned(),
        Some(value) => format!("{} / M", crate::providers::format_usd(Some(value))),
    }
}

/// True when a model has no effective input, output, or per-request price.
pub fn is_free_model(pricing: Option<&ModelPricing>, remote_id: &str) -> bool {
    if remote_id.ends_with(":free") {
        return true;
    }

    let Some(pricing) = pricing else {
        return false;
    };

    let effective = pricing.input_per_m_tok.unwrap_or(0.0)
        + pricing.output_per_m_tok.unwrap_or(0.0)
        + pricing.per_request.unwrap_or(0.0);

    effective == 0.0
}

/// Human-readable price line for a model row.
pub fn format_model_price(pricing: Option<&ModelPricing>, is_free: bool) -> String {
    if is_free {
        return "Free".to_owned();
    }

    let Some(pricing) = pricing else {
        return "Price unavailable".to_owned();
    };

    let mut parts = Vec::new();

    if let Some(value) = pricing.input_per_m_tok {
        parts.push(format!("{} input", format_price_per_million(Some(value))));
    }
    if let Some(value) = pricing.output_per_m_tok {
        parts.push(format!("{} output", format_price_per_million(Some(value))));
    }
    if let Some(value) = pricing.per_request.filter(|value| *value > 0.0) {
        parts.push(format!(
            "{} / request",
            crate::providers::format_usd(Some(value))
        ));
    }

    if parts.is_empty() {
        "Price unavailable".to_owned()
    } else {
        parts.join(" · ")
    }
}

/// Input and output modality summary.
pub fn format_modalities(input: &[InputModality], output: &[OutputModality]) -> String {
    let input = if input.is_empty() {
        "none".to_owned()
    } else {
        input
            .iter()
            .copied()
            .map(input_modality_label)
            .collect::<Vec<_>>()
            .join(", ")
    };
    let output = if output.is_empty() {
        "none".to_owned()
    } else {
        output
            .iter()
            .copied()
            .map(output_modality_label)
            .collect::<Vec<_>>()
            .join(", ")
    };

    format!("{input} in · {output} out")
}

fn normalize(value: &str) -> String {
    value.trim().to_lowercase()
}

/// True when a model matches a free-text query.
pub fn model_matches_query(model: &Model, query: &str) -> bool {
    let needle = normalize(query);

    if needle.is_empty() {
        return true;
    }

    [model.label.as_str(), model.remote_id.as_str()]
        .into_iter()
        .chain(model.canonical_slug.as_deref())
        .map(normalize)
        .any(|value| value.contains(&needle))
}

fn compare_models(left: &Model, right: &Model) -> std::cmp::Ordering {
    left.label
        .to_lowercase()
        .cmp(&right.label.to_lowercase())
        .then_with(|| left.remote_id.cmp(&right.remote_id))
}

/// Build the grouped model list.
///
/// Pinned models appear first in a dedicated group. The remaining models are
/// grouped by provider and sorted by provider label. Models are never
/// deduplicated by canonical slug because distinct provider IDs remain
/// separately selectable.
pub fn build_model_groups(
    models: &[Model],
    providers: &[Provider],
    query: &str,
    include_hidden: bool,
    default_model_id: Option<&str>,
) -> Vec<ModelGroup> {
    let provider_labels: BTreeMap<&str, &str> = providers
        .iter()
        .map(|provider| (provider.id.as_str(), provider.label.as_str()))
        .collect();
    let docs_urls: BTreeMap<&str, Option<String>> = providers
        .iter()
        .map(|provider| {
            let docs_url = provider_catalog()
                .get(&provider.preset_id)
                .and_then(|preset| preset.docs_url.clone());
            (provider.id.as_str(), docs_url)
        })
        .collect();

    let mut visible: Vec<&Model> = models
        .iter()
        .filter(|model| include_hidden || !model.hidden)
        .filter(|model| model_matches_query(model, query))
        .collect();
    visible.sort_by(|left, right| compare_models(left, right));

    let row_for = |model: &Model| {
        let provider_label = provider_labels
            .get(model.provider_id.as_str())
            .copied()
            .unwrap_or(model.provider_id.as_str());
        let docs_url = docs_urls.get(model.provider_id.as_str()).cloned().flatten();

        ModelRow::from_model(model, provider_label, default_model_id, docs_url)
    };

    let mut groups = Vec::new();
    let pinned: Vec<ModelRow> = visible
        .iter()
        .filter(|model| model.pinned)
        .map(|model| row_for(model))
        .collect();

    if !pinned.is_empty() {
        groups.push(ModelGroup {
            key: "pinned".to_owned(),
            label: "Pinned".to_owned(),
            pinned: true,
            models: pinned,
        });
    }

    let mut by_provider: BTreeMap<&str, Vec<&Model>> = BTreeMap::new();
    for model in visible.iter().filter(|model| !model.pinned) {
        by_provider
            .entry(model.provider_id.as_str())
            .or_default()
            .push(model);
    }

    let mut provider_groups: Vec<ModelGroup> = by_provider
        .into_iter()
        .map(|(provider_id, provider_models)| {
            let label = provider_labels
                .get(provider_id)
                .copied()
                .unwrap_or(provider_id)
                .to_owned();

            ModelGroup {
                key: provider_id.to_owned(),
                label,
                pinned: false,
                models: provider_models.into_iter().map(row_for).collect(),
            }
        })
        .collect();
    provider_groups.sort_by(|left, right| {
        left.label
            .to_lowercase()
            .cmp(&right.label.to_lowercase())
            .then_with(|| left.key.cmp(&right.key))
    });

    groups.extend(provider_groups);
    groups
}

/// Warn when a selected model cannot be used for a send.
///
/// The check is deliberately non-substituting: callers show the warning and
/// keep the user's selection instead of silently choosing another model.
pub fn model_availability(selected: Option<&Model>, models: &[Model]) -> ModelAvailability {
    let Some(selected) = selected else {
        return ModelAvailability {
            available: false,
            message: Some(
                "The selected model is no longer available. Choose another model before sending."
                    .to_owned(),
            ),
        };
    };

    let Some(current) = models.iter().find(|model| model.id == selected.id) else {
        return ModelAvailability {
            available: false,
            message: Some(format!(
                "Model {} is no longer in the catalog. Choose another model before sending.",
                selected.remote_id
            )),
        };
    };

    if !current.output_modalities.contains(&OutputModality::Text) {
        return ModelAvailability {
            available: false,
            message: Some(format!(
                "Model {} does not produce text output. Choose another model before sending.",
                current.remote_id
            )),
        };
    }

    ModelAvailability {
        available: true,
        message: None,
    }
}

/// Parse the context-window text field.
pub fn parse_context_window(text: &str) -> Result<Option<u32>, ModelManagementError> {
    let trimmed = text.trim();

    if trimmed.is_empty() {
        return Ok(None);
    }

    let value = trimmed.parse::<u32>().map_err(|_| {
        ModelManagementError::InvalidInput(
            "Context window must be a whole number of tokens.".to_owned(),
        )
    })?;

    if value == 0 {
        return Err(ModelManagementError::InvalidInput(
            "Context window must be greater than zero.".to_owned(),
        ));
    }

    Ok(Some(value))
}

/// Parse the capabilities text field.
///
/// Accepts comma- or space-separated capability names. An empty field defaults
/// to text. Unknown names are rejected so a typo cannot silently drop a
/// capability.
pub fn parse_capabilities(text: &str) -> Result<Vec<ModelCapability>, ModelManagementError> {
    let mut capabilities = Vec::new();

    for token in text
        .split([',', ' '])
        .filter(|token| !token.trim().is_empty())
    {
        let capability = match token.trim().to_lowercase().as_str() {
            "text" => ModelCapability::Text,
            "vision" => ModelCapability::Vision,
            "files" => ModelCapability::Files,
            "tools" => ModelCapability::Tools,
            "reasoning" => ModelCapability::Reasoning,
            "streaming" => ModelCapability::Streaming,
            other => {
                return Err(ModelManagementError::InvalidInput(format!(
                    "Unknown capability \"{other}\". Use text, vision, files, tools, reasoning, or streaming."
                )));
            }
        };

        if !capabilities.contains(&capability) {
            capabilities.push(capability);
        }
    }

    if capabilities.is_empty() {
        capabilities.push(ModelCapability::Text);
    }

    Ok(capabilities)
}

#[cfg(test)]
mod tests {
    use super::*;
    use chatui_domain::{ModelPricing, ProviderKind, Timestamp};

    fn provider(id: &str, label: &str, preset_id: &str) -> Provider {
        let now = Timestamp::now();
        Provider {
            id: id.to_owned(),
            preset_id: preset_id.to_owned(),
            label: label.to_owned(),
            kind: ProviderKind::OpenaiCompatible,
            base_url: "https://example.com/v1".to_owned(),
            auth_type: chatui_domain::ProviderAuthType::Bearer,
            secret_id: None,
            headers: BTreeMap::new(),
            enabled: true,
            browser_cors: chatui_domain::BrowserCors::Unknown,
            metadata: None,
            created_at: now,
            updated_at: now,
        }
    }

    fn model(
        id: &str,
        provider_id: &str,
        remote_id: &str,
        label: &str,
        pinned: bool,
        hidden: bool,
    ) -> Model {
        let now = Timestamp::now();
        Model {
            id: id.to_owned(),
            provider_id: provider_id.to_owned(),
            remote_id: remote_id.to_owned(),
            label: label.to_owned(),
            context_window: Some(128_000),
            max_output_tokens: Some(4096),
            capabilities: vec![ModelCapability::Text, ModelCapability::Streaming],
            pricing: None,
            canonical_slug: None,
            input_modalities: vec![InputModality::Text],
            output_modalities: vec![OutputModality::Text],
            supported_parameters: Vec::new(),
            metadata: None,
            hidden,
            pinned,
            source: ModelSource::Discovered,
            created_at: now,
            updated_at: now,
        }
    }

    #[test]
    fn search_matches_label_remote_id_and_canonical_slug() {
        let mut model = model("m1", "p1", "author/model", "Friendly Name", false, false);
        model.canonical_slug = Some("author/canonical".to_owned());

        assert!(model_matches_query(&model, "friendly"));
        assert!(model_matches_query(&model, "author/model"));
        assert!(model_matches_query(&model, "canonical"));
        assert!(!model_matches_query(&model, "missing"));
        assert!(model_matches_query(&model, "  "));
    }

    #[test]
    fn pinned_models_come_first_then_provider_groups() {
        let providers = vec![
            provider("p1", "Zeta", "openrouter"),
            provider("p2", "Alpha", "openai"),
        ];
        let models = vec![
            model("m1", "p1", "z/model", "Zed", false, false),
            model("m2", "p2", "a/model", "Able", false, false),
            model("m3", "p1", "pinned/model", "Pinned", true, false),
        ];

        let groups = build_model_groups(&models, &providers, "", false, None);

        assert_eq!(groups[0].key, "pinned");
        assert_eq!(groups[0].models[0].label, "Pinned");
        assert_eq!(groups[1].label, "Alpha");
        assert_eq!(groups[2].label, "Zeta");
    }

    #[test]
    fn hidden_models_are_excluded_unless_requested() {
        let providers = vec![provider("p1", "OpenRouter", "openrouter")];
        let models = vec![
            model("m1", "p1", "visible/model", "Visible", false, false),
            model("m2", "p1", "hidden/model", "Hidden", false, true),
        ];

        let picker = build_model_groups(&models, &providers, "", false, None);
        let management = build_model_groups(&models, &providers, "", true, None);

        assert_eq!(picker.iter().map(|g| g.models.len()).sum::<usize>(), 1);
        assert_eq!(management.iter().map(|g| g.models.len()).sum::<usize>(), 2);
    }

    #[test]
    fn default_model_is_marked() {
        let providers = vec![provider("p1", "OpenRouter", "openrouter")];
        let models = vec![model("m1", "p1", "author/model", "Model", false, false)];

        let groups = build_model_groups(&models, &providers, "", false, Some("m1"));

        assert!(groups[0].models[0].is_default);
        assert!(groups[0].models[0].badges().contains(&"Default"));
    }

    #[test]
    fn free_badge_uses_zero_price_and_free_suffix() {
        let mut free = model("m1", "p1", "author/free", "Free", false, false);
        free.pricing = Some(ModelPricing {
            input_per_m_tok: Some(0.0),
            output_per_m_tok: Some(0.0),
            ..ModelPricing::default()
        });
        let suffix = model("m2", "p1", "author/model:free", "Suffix", false, false);

        assert!(is_free_model(free.pricing.as_ref(), &free.remote_id));
        assert!(is_free_model(suffix.pricing.as_ref(), &suffix.remote_id));
        assert!(!is_free_model(None, "author/paid"));
    }

    #[test]
    fn price_formatting_uses_per_million_and_free() {
        let pricing = ModelPricing {
            input_per_m_tok: Some(1.5),
            output_per_m_tok: Some(6.0),
            ..ModelPricing::default()
        };

        assert_eq!(format_price_per_million(Some(0.0)), "Free");
        assert_eq!(format_price_per_million(None), "—");
        assert_eq!(format_price_per_million(Some(1.5)), "$1.5 / M");
        assert_eq!(
            format_model_price(Some(&pricing), false),
            "$1.5 / M input · $6 / M output"
        );
        assert_eq!(format_model_price(None, true), "Free");
        assert_eq!(format_model_price(None, false), "Price unavailable");
    }

    #[test]
    fn context_and_modalities_format_compactly() {
        assert_eq!(format_context_window(Some(128_000)), "128K context");
        assert_eq!(format_context_window(Some(1_000_000)), "1M context");
        assert_eq!(format_context_window(Some(512)), "512 context");
        assert_eq!(format_context_window(None), "Context unknown");
        assert_eq!(
            format_modalities(
                &[InputModality::Text, InputModality::Image],
                &[OutputModality::Text]
            ),
            "text, image in · text out"
        );
    }

    #[test]
    fn availability_warns_for_missing_and_non_text_models() {
        let text = model("m1", "p1", "author/text", "Text", false, false);
        let mut image = model("m2", "p1", "author/image", "Image", false, false);
        image.output_modalities = vec![OutputModality::Image];

        assert!(model_availability(Some(&text), std::slice::from_ref(&text)).available);
        assert!(
            model_availability(Some(&image), &[image.clone()])
                .message
                .unwrap()
                .contains("does not produce text")
        );
        assert!(
            model_availability(None, &[])
                .message
                .unwrap()
                .contains("no longer available")
        );
    }

    #[test]
    fn manual_form_parses_context_and_capabilities() {
        let mut form = ManualModelForm::new("p1");
        form.set_context_window_text("128000").expect("context");
        form.set_capabilities_text("text, vision, tools")
            .expect("capabilities");

        assert_eq!(form.context_window, Some(128_000));
        assert_eq!(
            form.capabilities,
            vec![
                ModelCapability::Text,
                ModelCapability::Vision,
                ModelCapability::Tools
            ]
        );
        assert!(form.set_context_window_text("0").is_err());
        assert!(form.set_capabilities_text("telepathy").is_err());
    }
}
