//! OpenRouter public model catalog mapping.
//!
//! `GET /models` is public and can populate onboarding before a key is entered.
//! The mapper follows spec section 7.4: it normalizes the fields the shared
//! [`Model`] shape needs, keeps raw pricing and small non-secret unknown fields
//! in `metadata`, and never fails the whole catalog because one entry is
//! malformed.

use std::collections::HashSet;

use chatui_domain::{
    InputModality, Model, ModelCapability, ModelPricing, ModelSource, OutputModality, Timestamp,
};
use serde::{Deserialize, Serialize};
use serde_json::{Map, Value};

use crate::http::HttpClient;
use crate::openrouter::key::{OpenRouterError, get_json};
use crate::url_policy::join_url;

/// Capability order used by the shared contract vectors.
const CAPABILITY_ORDER: [ModelCapability; 6] = [
    ModelCapability::Text,
    ModelCapability::Vision,
    ModelCapability::Files,
    ModelCapability::Tools,
    ModelCapability::Reasoning,
    ModelCapability::Streaming,
];

/// Fields the mapper consumes directly. Everything else is a candidate for
/// `metadata`.
const KNOWN_MODEL_FIELDS: [&str; 12] = [
    "id",
    "canonical_slug",
    "name",
    "created",
    "description",
    "context_length",
    "architecture",
    "pricing",
    "top_provider",
    "per_request_limits",
    "supported_parameters",
    "default_parameters",
];

/// A normalized OpenRouter catalog entry.
///
/// This is the catalog-shaped subset of [`Model`]. Callers assign the local
/// `id`, `provider_id`, and timestamps with [`CatalogModel::to_model`].
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CatalogModel {
    /// Provider model ID. The `:free` suffix is preserved.
    pub remote_id: String,
    /// Canonical slug used for display-time deduplication.
    pub canonical_slug: Option<String>,
    /// Display label.
    pub label: String,
    /// Context window in tokens.
    pub context_window: Option<u32>,
    /// Maximum output tokens.
    pub max_output_tokens: Option<u32>,
    /// Capability flags in canonical order.
    pub capabilities: Vec<ModelCapability>,
    /// Per-million pricing, or `null` when the catalog omits pricing.
    pub pricing: Option<ModelPricing>,
    /// Accepted input modalities.
    pub input_modalities: Vec<InputModality>,
    /// Produced output modalities.
    pub output_modalities: Vec<OutputModality>,
    /// Provider-supported request parameters.
    pub supported_parameters: Vec<String>,
    /// Non-secret provider metadata.
    pub metadata: Option<Value>,
    /// Whether the effective price is zero or the ID ends in `:free`.
    pub is_free: bool,
    /// Whether the model is hidden from the default picker.
    pub hidden: bool,
}

impl CatalogModel {
    /// Convert this catalog entry into the shared [`Model`] shape.
    ///
    /// The caller owns local identity and timestamps so the same catalog can be
    /// persisted per provider without rewriting the remote ID.
    pub fn to_model(
        &self,
        id: impl Into<String>,
        provider_id: impl Into<String>,
        created_at: Timestamp,
        updated_at: Timestamp,
    ) -> Model {
        Model {
            id: id.into(),
            provider_id: provider_id.into(),
            remote_id: self.remote_id.clone(),
            label: self.label.clone(),
            context_window: self.context_window,
            max_output_tokens: self.max_output_tokens,
            capabilities: self.capabilities.clone(),
            pricing: self.pricing.clone(),
            canonical_slug: self.canonical_slug.clone(),
            input_modalities: self.input_modalities.clone(),
            output_modalities: self.output_modalities.clone(),
            supported_parameters: self.supported_parameters.clone(),
            metadata: self.metadata.clone(),
            hidden: self.hidden,
            pinned: false,
            source: ModelSource::Discovered,
            created_at,
            updated_at,
        }
    }
}

/// Multiply a non-negative decimal string by one million exactly.
///
/// The result is a canonical decimal string. Invalid input, including exponent
/// notation, returns `None`. This avoids binary floating point for the
/// per-million conversion.
pub fn multiply_decimal_by_million(value: &str) -> Option<String> {
    let value = value.trim();
    let (sign, rest) = match value.strip_prefix('-') {
        Some(rest) => ("-", rest),
        None => ("", value.strip_prefix('+').unwrap_or(value)),
    };
    let (integer, fraction) = match rest.split_once('.') {
        Some((integer, fraction)) => (integer, fraction),
        None => (rest, ""),
    };

    if integer.is_empty()
        || !integer.bytes().all(|byte| byte.is_ascii_digit())
        || (rest.contains('.') && fraction.is_empty())
        || !fraction.bytes().all(|byte| byte.is_ascii_digit())
    {
        return None;
    }

    let digits = format!("{integer}{fraction}");
    let point_index = integer.len() + 6;
    let raw = if point_index >= digits.len() {
        format!("{digits}{}", "0".repeat(point_index - digits.len()))
    } else {
        format!("{}.{}", &digits[..point_index], &digits[point_index..])
    };

    let (raw_integer, raw_fraction) = raw.split_once('.').unwrap_or((raw.as_str(), ""));
    let normalized_integer = raw_integer.trim_start_matches('0');
    let normalized_integer = if normalized_integer.is_empty() {
        "0"
    } else {
        normalized_integer
    };
    let normalized_fraction = raw_fraction.trim_end_matches('0');
    let normalized = if normalized_fraction.is_empty() {
        normalized_integer.to_owned()
    } else {
        format!("{normalized_integer}.{normalized_fraction}")
    };

    if normalized == "0" {
        Some("0".to_owned())
    } else {
        Some(format!("{sign}{normalized}"))
    }
}

/// Convert a USD-per-token decimal string to USD per million tokens.
pub fn usd_per_million(value: &Value) -> Option<f64> {
    let value = value.as_str()?;
    multiply_decimal_by_million(value)?.parse::<f64>().ok()
}

/// Parse a USD amount without multiplying it.
///
/// Strings must be plain decimal values. Numbers are accepted as-is. No
/// arithmetic is performed here, so no rounding decision depends on binary
/// floating point.
pub fn usd_amount(value: &Value) -> Option<f64> {
    match value {
        Value::String(value) => {
            if is_decimal_string(value) {
                value.trim().parse::<f64>().ok()
            } else {
                None
            }
        }
        Value::Number(number) => number.as_f64(),
        _ => None,
    }
}

/// Map an OpenRouter `/models` envelope to normalized entries.
///
/// The envelope may be `{ "data": [...] }`, `{ "models": [...] }`, or a bare
/// array. Malformed entries are skipped; a malformed envelope is an error.
pub fn map_models(payload: &Value) -> Result<Vec<CatalogModel>, OpenRouterError> {
    let entries = if let Some(entries) = payload.as_array() {
        entries
    } else if let Some(entries) = payload.get("data").and_then(Value::as_array) {
        entries
    } else if let Some(entries) = payload.get("models").and_then(Value::as_array) {
        entries
    } else {
        return Err(OpenRouterError::Parse(
            "OpenRouter /models response must contain a data array".to_owned(),
        ));
    };

    Ok(entries.iter().filter_map(map_model).collect())
}

/// Map one raw OpenRouter model entry.
///
/// Entries without a usable `id` return `None` so the rest of the catalog still
/// loads.
pub fn map_model(raw: &Value) -> Option<CatalogModel> {
    let raw = raw.as_object()?;
    let remote_id = raw.get("id").and_then(Value::as_str)?.trim();
    if remote_id.is_empty() {
        return None;
    }

    let architecture = raw.get("architecture").and_then(Value::as_object);
    let top_provider = raw.get("top_provider").and_then(Value::as_object);
    let modality = architecture
        .and_then(|architecture| architecture.get("modality"))
        .and_then(Value::as_str)
        .unwrap_or("");
    let (parsed_input, parsed_output) = parse_modality(modality);

    let declared_input =
        unique_input_modalities(architecture.and_then(|value| value.get("input_modalities")));
    let declared_output =
        unique_output_modalities(architecture.and_then(|value| value.get("output_modalities")));
    let input_modalities = if declared_input.is_empty() {
        parsed_input
    } else {
        declared_input
    };
    let output_modalities = if declared_output.is_empty() {
        parsed_output
    } else {
        declared_output
    };

    let supported_parameters = unique_strings(raw.get("supported_parameters"));
    let context_window = raw
        .get("context_length")
        .and_then(to_positive_integer)
        .or_else(|| {
            top_provider
                .and_then(|value| value.get("context_length"))
                .and_then(to_positive_integer)
        });
    let max_output_tokens = top_provider
        .and_then(|value| value.get("max_completion_tokens"))
        .and_then(to_positive_integer)
        .or(context_window);
    let pricing = map_pricing(raw.get("pricing"));
    let label = raw
        .get("name")
        .and_then(Value::as_str)
        .filter(|name| !name.trim().is_empty())
        .unwrap_or(remote_id)
        .to_owned();
    let canonical_slug = raw
        .get("canonical_slug")
        .and_then(Value::as_str)
        .filter(|slug| !slug.trim().is_empty())
        .map(str::to_owned);

    Some(CatalogModel {
        remote_id: remote_id.to_owned(),
        canonical_slug,
        label,
        context_window,
        max_output_tokens,
        capabilities: infer_capabilities(
            &input_modalities,
            &output_modalities,
            &supported_parameters,
            modality,
        ),
        pricing: pricing.clone(),
        input_modalities,
        output_modalities,
        supported_parameters,
        metadata: build_metadata(raw),
        is_free: is_free_model(remote_id, raw.get("pricing"), pricing.as_ref()),
        hidden: remote_id.to_ascii_lowercase().contains("embed"),
    })
}

/// Deduplicate catalog entries by `canonical_slug` for display.
///
/// Distinct remote IDs remain selectable; this helper only collapses the
/// display list. Entries without a canonical slug are always kept.
pub fn dedupe_by_canonical_slug(models: &[CatalogModel]) -> Vec<CatalogModel> {
    let mut seen = HashSet::new();
    models
        .iter()
        .filter(|model| match &model.canonical_slug {
            Some(slug) => seen.insert(slug.clone()),
            None => true,
        })
        .cloned()
        .collect()
}

/// Load the public OpenRouter catalog.
///
/// No API key is sent. This works before key validation so onboarding can show
/// available models.
pub async fn list_models(
    client: &HttpClient,
    base_url: &str,
) -> Result<Vec<CatalogModel>, OpenRouterError> {
    let url = join_url(base_url, "models")?;
    let payload = get_json(client, &url, None).await?;
    map_models(&payload)
}

fn is_decimal_string(value: &str) -> bool {
    let value = value.trim();
    let value = value
        .strip_prefix('+')
        .or_else(|| value.strip_prefix('-'))
        .unwrap_or(value);
    if value.is_empty() {
        return false;
    }

    let mut parts = value.split('.');
    let integer = parts.next().unwrap_or_default();
    let fraction = parts.next();
    if parts.next().is_some() {
        return false;
    }

    !integer.is_empty()
        && integer.bytes().all(|byte| byte.is_ascii_digit())
        && fraction.is_none_or(|fraction| {
            !fraction.is_empty() && fraction.bytes().all(|byte| byte.is_ascii_digit())
        })
}

fn to_positive_integer(value: &Value) -> Option<u32> {
    let number = value.as_u64()?;
    if number == 0 || number > u64::from(u32::MAX) {
        return None;
    }
    u32::try_from(number).ok()
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

fn parse_modality(value: &str) -> (Vec<InputModality>, Vec<OutputModality>) {
    let (raw_input, raw_output) = value.split_once("->").unwrap_or((value, ""));
    let mut input = Vec::new();
    let mut seen_input = HashSet::new();
    for part in raw_input.split('+') {
        if let Some(modality) = input_modality(part.trim())
            && seen_input.insert(modality)
        {
            input.push(modality);
        }
    }

    let mut output = Vec::new();
    let mut seen_output = HashSet::new();
    for part in raw_output.split('+') {
        if let Some(modality) = output_modality(part.trim())
            && seen_output.insert(modality)
        {
            output.push(modality);
        }
    }

    (input, output)
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

fn infer_capabilities(
    input_modalities: &[InputModality],
    output_modalities: &[OutputModality],
    supported_parameters: &[String],
    modality: &str,
) -> Vec<ModelCapability> {
    let mut capabilities = HashSet::new();

    if input_modalities.contains(&InputModality::Text)
        || output_modalities.contains(&OutputModality::Text)
        || modality.contains("text")
    {
        capabilities.insert(ModelCapability::Text);
    }

    if input_modalities.contains(&InputModality::Image) || modality.contains("image") {
        capabilities.insert(ModelCapability::Vision);
    }

    if input_modalities.contains(&InputModality::File) || modality.contains("file") {
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

fn map_pricing(raw: Option<&Value>) -> Option<ModelPricing> {
    let raw = raw?.as_object()?;
    let mut pricing = ModelPricing::default();

    if let Some(value) = raw.get("prompt").and_then(usd_per_million) {
        pricing.input_per_m_tok = Some(value);
    }
    if let Some(value) = raw.get("completion").and_then(usd_per_million) {
        pricing.output_per_m_tok = Some(value);
    }
    if let Some(value) = raw.get("request").and_then(usd_amount) {
        pricing.per_request = Some(value);
    }

    if pricing.input_per_m_tok.is_none()
        && pricing.output_per_m_tok.is_none()
        && pricing.per_request.is_none()
    {
        return None;
    }

    pricing.currency = Some("USD".to_owned());
    Some(pricing)
}

fn is_free_model(
    remote_id: &str,
    raw_pricing: Option<&Value>,
    pricing: Option<&ModelPricing>,
) -> bool {
    if remote_id.ends_with(":free") {
        return true;
    }

    let Some(raw_pricing) = raw_pricing.and_then(Value::as_object) else {
        return false;
    };
    if pricing.is_none() {
        return false;
    }

    let prompt = raw_pricing.get("prompt").and_then(usd_amount);
    let completion = raw_pricing.get("completion").and_then(usd_amount);
    if prompt != Some(0.0) || completion != Some(0.0) {
        return false;
    }

    ["request", "image", "internal_reasoning"]
        .iter()
        .all(|key| {
            raw_pricing
                .get(*key)
                .and_then(usd_amount)
                .is_none_or(|charge| charge == 0.0)
        })
}

fn build_metadata(raw: &Map<String, Value>) -> Option<Value> {
    let mut metadata = Map::new();

    if let Some(architecture) = raw.get("architecture").and_then(Value::as_object) {
        let mut value = Map::new();
        if let Some(modality) = architecture.get("modality").and_then(Value::as_str) {
            value.insert("modality".to_owned(), Value::String(modality.to_owned()));
        }
        if let Some(tokenizer) = architecture.get("tokenizer").and_then(Value::as_str) {
            value.insert("tokenizer".to_owned(), Value::String(tokenizer.to_owned()));
        }
        if let Some(instruct_type) = architecture.get("instruct_type") {
            value.insert("instructType".to_owned(), instruct_type.clone());
        }
        if !value.is_empty() {
            metadata.insert("architecture".to_owned(), Value::Object(value));
        }
    }

    if let Some(pricing) = raw.get("pricing").and_then(Value::as_object) {
        metadata.insert("pricing".to_owned(), Value::Object(pricing.clone()));
    }

    if let Some(defaults) = raw.get("default_parameters").and_then(Value::as_object) {
        metadata.insert(
            "defaultParameters".to_owned(),
            Value::Object(defaults.clone()),
        );
    }

    if let Some(top_provider) = raw.get("top_provider").and_then(Value::as_object) {
        let mut value = Map::new();
        if let Some(is_moderated) = top_provider.get("is_moderated").and_then(Value::as_bool) {
            value.insert("isModerated".to_owned(), Value::Bool(is_moderated));
        }
        if let Some(context_length) = top_provider.get("context_length") {
            value.insert("contextLength".to_owned(), context_length.clone());
        }
        if let Some(max_completion_tokens) = top_provider.get("max_completion_tokens") {
            value.insert(
                "maxCompletionTokens".to_owned(),
                max_completion_tokens.clone(),
            );
        }
        if !value.is_empty() {
            metadata.insert("topProvider".to_owned(), Value::Object(value));
        }
    }

    if let Some(limits) = raw.get("per_request_limits") {
        metadata.insert("perRequestLimits".to_owned(), limits.clone());
    }

    if let Some(description) = raw.get("description").and_then(Value::as_str)
        && description.len() <= 512
    {
        metadata.insert(
            "description".to_owned(),
            Value::String(description.to_owned()),
        );
    }

    if let Some(created) = raw.get("created").and_then(Value::as_i64) {
        metadata.insert("created".to_owned(), Value::Number(created.into()));
    }

    copy_small_unknown_fields(raw, &mut metadata);

    if metadata.is_empty() {
        None
    } else {
        Some(Value::Object(metadata))
    }
}

fn copy_small_unknown_fields(raw: &Map<String, Value>, metadata: &mut Map<String, Value>) {
    for (key, value) in raw {
        if KNOWN_MODEL_FIELDS.contains(&key.as_str()) || is_secret_like_field(key) {
            continue;
        }

        let Ok(serialized) = serde_json::to_string(value) else {
            continue;
        };
        if serialized.len() > 512 {
            continue;
        }

        metadata.insert(key.clone(), value.clone());
    }
}

fn is_secret_like_field(key: &str) -> bool {
    let key = key.to_ascii_lowercase();
    ["key", "token", "secret", "auth", "password", "credential"]
        .iter()
        .any(|marker| key.contains(marker))
}
