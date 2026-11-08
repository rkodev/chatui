//! Provider preset catalog loaded from `contracts/providers.json`.
//!
//! The checked-in contract is the only source of provider metadata. This module
//! parses it with `include_str!` and validates the catalog invariants the
//! desktop client relies on. It never contains a second preset list.

use std::collections::{BTreeMap, BTreeSet};
use std::fmt;
use std::sync::OnceLock;

use chatui_domain::{BrowserCors, ProviderAuthType, ProviderKind};
use serde::{Deserialize, Serialize};

/// The checked-in provider preset document.
pub const PROVIDERS_JSON: &str = include_str!("../../../../contracts/providers.json");

/// The preset that must be marked as the default and reference preset.
pub const DEFAULT_PRESET_ID: &str = "openrouter";

/// The user-defined OpenAI-compatible preset.
pub const CUSTOM_PRESET_ID: &str = "custom";

/// Presets that must always be present in the catalog.
pub const REQUIRED_PRESET_IDS: &[&str] = &[DEFAULT_PRESET_ID];

/// Authentication configuration for a provider preset.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ProviderAuth {
    /// Authentication scheme.
    #[serde(rename = "type")]
    pub auth_type: ProviderAuthType,
    /// Header that carries the key, when the scheme uses a header.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub header_name: Option<String>,
    /// Header value prefix, such as `Bearer`.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub scheme: Option<String>,
    /// Query parameter that carries the key, when the scheme uses one.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub query_param: Option<String>,
}

/// HTTP method used to validate a key.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
pub enum KeyValidationMethod {
    /// `GET`.
    #[serde(rename = "GET")]
    Get,
}

/// Key validation endpoint for a provider preset.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct KeyValidation {
    /// HTTP method.
    pub method: KeyValidationMethod,
    /// Path relative to the preset base URL.
    pub path: String,
}

/// Catalog response format used for model discovery.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "kebab-case")]
pub enum ModelDiscoveryFormat {
    /// OpenRouter model catalog.
    Openrouter,
    /// OpenAI-compatible model catalog.
    Openai,
    /// Anthropic model catalog.
    Anthropic,
    /// Google Gemini model catalog.
    Gemini,
    /// Ollama `/api/tags` response.
    OllamaTags,
}

/// Model discovery configuration for a provider preset.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ModelDiscovery {
    /// Path or absolute URL used to discover models.
    pub path: String,
    /// Response format.
    pub format: ModelDiscoveryFormat,
    /// Whether discovery works without an API key.
    pub public: bool,
    /// Cache lifetime in seconds.
    pub cache_ttl_seconds: u64,
}

/// A provider preset from `contracts/providers.json`.
///
/// Unknown fields are ignored so additive catalog changes do not break older
/// clients. See the crate-level contract evolution policy.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ProviderPreset {
    /// Stable preset ID.
    pub id: String,
    /// Display label.
    pub label: String,
    /// Provider transport kind.
    pub kind: ProviderKind,
    /// Base URL for API requests.
    pub base_url: String,
    /// Authentication configuration.
    pub auth: ProviderAuth,
    /// Optional key validation endpoint.
    pub key_validation: Option<KeyValidation>,
    /// Model discovery configuration.
    pub model_discovery: ModelDiscovery,
    /// Non-secret headers sent with every request.
    pub default_headers: BTreeMap<String, String>,
    /// Attribution headers with `app.name` and `app.origin` placeholders.
    pub attribution_headers: BTreeMap<String, String>,
    /// Browser CORS capability advertised by the provider.
    pub browser_cors: BrowserCors,
    /// Documentation URL, or `null` when unavailable.
    pub docs_url: Option<String>,
    /// Default model ID for the preset.
    pub default_model_id: String,
    /// Whether this is the default preset.
    #[serde(rename = "default")]
    pub is_default: bool,
    /// Whether this is the reference preset used for conformance.
    pub reference: bool,
    /// Whether the preset is required in every catalog.
    pub required: bool,
}

/// Errors returned while loading or validating the provider catalog.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ProviderCatalogError {
    /// The document is not valid JSON or does not match the preset shape.
    Json(String),
    /// The catalog is empty.
    Empty,
    /// Two presets share an ID.
    DuplicateId(String),
    /// The catalog does not contain exactly one default preset.
    DefaultCount(usize),
    /// The catalog does not contain exactly one reference preset.
    ReferenceCount(usize),
    /// The default and reference presets are different.
    DefaultReferenceMismatch {
        /// Default preset ID.
        default: String,
        /// Reference preset ID.
        reference: String,
    },
    /// The default preset is not the expected reference provider.
    UnexpectedDefault(String),
    /// A required preset is missing.
    MissingRequiredPreset(String),
    /// The user-defined `custom` preset is missing.
    MissingCustomPreset,
}

impl fmt::Display for ProviderCatalogError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::Json(message) => write!(formatter, "provider catalog is invalid: {message}"),
            Self::Empty => formatter.write_str("provider catalog is empty"),
            Self::DuplicateId(id) => write!(formatter, "duplicate provider preset id: {id}"),
            Self::DefaultCount(count) => {
                write!(
                    formatter,
                    "expected exactly one default preset, found {count}"
                )
            }
            Self::ReferenceCount(count) => {
                write!(
                    formatter,
                    "expected exactly one reference preset, found {count}"
                )
            }
            Self::DefaultReferenceMismatch { default, reference } => write!(
                formatter,
                "default preset {default} and reference preset {reference} differ"
            ),
            Self::UnexpectedDefault(id) => {
                write!(
                    formatter,
                    "default preset must be {DEFAULT_PRESET_ID}, found {id}"
                )
            }
            Self::MissingRequiredPreset(id) => {
                write!(formatter, "required provider preset is missing: {id}")
            }
            Self::MissingCustomPreset => {
                write!(
                    formatter,
                    "user-defined provider preset is missing: {CUSTOM_PRESET_ID}"
                )
            }
        }
    }
}

impl std::error::Error for ProviderCatalogError {}

/// A validated provider preset catalog.
#[derive(Debug, Clone, PartialEq)]
pub struct ProviderCatalog {
    presets: Vec<ProviderPreset>,
}

impl ProviderCatalog {
    /// Load and validate the checked-in catalog.
    pub fn load() -> Result<Self, ProviderCatalogError> {
        Self::from_json(PROVIDERS_JSON)
    }

    /// Parse and validate a catalog document.
    pub fn from_json(json: &str) -> Result<Self, ProviderCatalogError> {
        let presets: Vec<ProviderPreset> = serde_json::from_str(json)
            .map_err(|error| ProviderCatalogError::Json(error.to_string()))?;

        if presets.is_empty() {
            return Err(ProviderCatalogError::Empty);
        }

        let mut seen = BTreeSet::new();
        for preset in &presets {
            if !seen.insert(preset.id.as_str()) {
                return Err(ProviderCatalogError::DuplicateId(preset.id.clone()));
            }
        }

        let defaults: Vec<&ProviderPreset> =
            presets.iter().filter(|preset| preset.is_default).collect();
        if defaults.len() != 1 {
            return Err(ProviderCatalogError::DefaultCount(defaults.len()));
        }

        let references: Vec<&ProviderPreset> =
            presets.iter().filter(|preset| preset.reference).collect();
        if references.len() != 1 {
            return Err(ProviderCatalogError::ReferenceCount(references.len()));
        }

        if defaults[0].id != references[0].id {
            return Err(ProviderCatalogError::DefaultReferenceMismatch {
                default: defaults[0].id.clone(),
                reference: references[0].id.clone(),
            });
        }

        if defaults[0].id != DEFAULT_PRESET_ID {
            return Err(ProviderCatalogError::UnexpectedDefault(
                defaults[0].id.clone(),
            ));
        }

        for id in REQUIRED_PRESET_IDS {
            if !seen.contains(id) {
                return Err(ProviderCatalogError::MissingRequiredPreset(
                    (*id).to_owned(),
                ));
            }
        }

        if !seen.contains(CUSTOM_PRESET_ID) {
            return Err(ProviderCatalogError::MissingCustomPreset);
        }

        Ok(Self { presets })
    }

    /// Return every preset in contract order.
    pub fn presets(&self) -> &[ProviderPreset] {
        &self.presets
    }

    /// Return the preset with `id`, if present.
    pub fn get(&self, id: &str) -> Option<&ProviderPreset> {
        self.presets.iter().find(|preset| preset.id == id)
    }

    /// Return the single default preset.
    pub fn default_preset(&self) -> &ProviderPreset {
        self.presets
            .iter()
            .find(|preset| preset.is_default)
            .expect("validated catalogs have exactly one default preset")
    }

    /// Return the single reference preset.
    pub fn reference_preset(&self) -> &ProviderPreset {
        self.presets
            .iter()
            .find(|preset| preset.reference)
            .expect("validated catalogs have exactly one reference preset")
    }

    /// Return every preset marked as required.
    pub fn required_presets(&self) -> impl Iterator<Item = &ProviderPreset> {
        self.presets.iter().filter(|preset| preset.required)
    }
}

static CATALOG: OnceLock<ProviderCatalog> = OnceLock::new();

/// Return the validated checked-in catalog, loading it once.
///
/// # Panics
///
/// Panics when the checked-in contract is invalid. Contract tests catch this
/// before release; runtime callers that need recoverable errors should use
/// [`ProviderCatalog::load`].
pub fn provider_catalog() -> &'static ProviderCatalog {
    CATALOG.get_or_init(|| ProviderCatalog::load().expect("bundled provider presets must be valid"))
}

/// Return every checked-in provider preset.
pub fn provider_presets() -> &'static [ProviderPreset] {
    provider_catalog().presets()
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::{Value, json};

    fn catalog() -> ProviderCatalog {
        ProviderCatalog::load().expect("checked-in catalog is valid")
    }

    #[test]
    fn loads_all_fourteen_presets() {
        assert_eq!(catalog().presets().len(), 14);
    }

    #[test]
    fn required_presets_and_custom_are_present() {
        let catalog = catalog();
        for id in REQUIRED_PRESET_IDS {
            assert!(catalog.get(id).is_some(), "missing required preset {id}");
        }
        let custom = catalog.get(CUSTOM_PRESET_ID).expect("custom preset exists");
        assert!(!custom.required);
        assert_eq!(custom.kind, ProviderKind::OpenaiCompatible);
    }

    #[test]
    fn openrouter_is_the_single_default_and_reference() {
        let catalog = catalog();
        assert_eq!(catalog.default_preset().id, DEFAULT_PRESET_ID);
        assert_eq!(catalog.reference_preset().id, DEFAULT_PRESET_ID);
        assert_eq!(
            catalog
                .presets()
                .iter()
                .filter(|preset| preset.is_default)
                .count(),
            1
        );
        assert_eq!(
            catalog
                .presets()
                .iter()
                .filter(|preset| preset.reference)
                .count(),
            1
        );
        assert_eq!(catalog.required_presets().count(), 1);
    }

    #[test]
    fn every_preset_has_the_required_fields() {
        for preset in catalog().presets() {
            assert!(!preset.id.is_empty());
            assert!(!preset.label.is_empty());
            assert!(
                preset.base_url.starts_with("http://") || preset.base_url.starts_with("https://")
            );
            assert!(!preset.default_model_id.is_empty());
        }
    }

    #[test]
    fn unknown_catalog_fields_are_ignored() {
        let mut value = serde_json::to_value(&catalog().presets()[0]).expect("serialize preset");
        value["futureCatalogField"] = json!({ "nested": [true, null] });

        let preset: ProviderPreset =
            serde_json::from_value(value).expect("unknown catalog fields are tolerated");
        assert_eq!(preset.id, DEFAULT_PRESET_ID);
    }

    #[test]
    fn duplicate_ids_are_rejected() {
        let preset = serde_json::to_value(&catalog().presets()[0]).expect("serialize preset");
        let json = serde_json::to_string(&[preset.clone(), preset]).expect("serialize catalog");

        assert_eq!(
            ProviderCatalog::from_json(&json).unwrap_err(),
            ProviderCatalogError::DuplicateId(DEFAULT_PRESET_ID.to_owned())
        );
    }

    #[test]
    fn missing_default_is_rejected() {
        let mut presets: Vec<Value> =
            serde_json::from_str(PROVIDERS_JSON).expect("parse providers");
        for preset in &mut presets {
            preset["default"] = json!(false);
        }
        let json = serde_json::to_string(&presets).expect("serialize catalog");

        assert_eq!(
            ProviderCatalog::from_json(&json).unwrap_err(),
            ProviderCatalogError::DefaultCount(0)
        );
    }

    #[test]
    fn missing_custom_is_rejected() {
        let mut presets: Vec<Value> =
            serde_json::from_str(PROVIDERS_JSON).expect("parse providers");
        presets.retain(|preset| preset["id"] != json!(CUSTOM_PRESET_ID));
        let json = serde_json::to_string(&presets).expect("serialize catalog");

        assert_eq!(
            ProviderCatalog::from_json(&json).unwrap_err(),
            ProviderCatalogError::MissingCustomPreset
        );
    }

    #[test]
    fn preset_data_is_not_duplicated_in_other_modules() {
        let sources = [
            include_str!("lib.rs"),
            include_str!("export.rs"),
            include_str!("schema.rs"),
            include_str!("config.rs"),
        ];
        let preset_urls = [
            "https://openrouter.ai/api/v1",
            "https://api.openai.com/v1",
            "https://api.anthropic.com/v1",
            "https://generativelanguage.googleapis.com/v1beta",
        ];

        for source in sources {
            for url in preset_urls {
                assert!(
                    !source.contains(url),
                    "provider preset URL {url} is duplicated in Rust source"
                );
            }
        }
    }
}
