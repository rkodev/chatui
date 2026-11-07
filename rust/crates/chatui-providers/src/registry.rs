//! Provider registry built from `contracts/providers.json`.
//!
//! The registry is the only place that maps a contract preset to an adapter.
//! It never hardcodes provider metadata: every preset comes from the checked-in
//! contract. OpenRouter is the reference adapter, OpenAI-format presets use the
//! shared OpenAI-compatible transport, Anthropic uses the native Messages
//! adapter, and Gemini uses the native generateContent adapter. Ollama gets an
//! [`UnsupportedAdapter`] until its own task lands.

use std::collections::{BTreeMap, BTreeSet};
use std::fmt;

use chatui_contracts::{
    AppConfig, ModelDiscoveryFormat, ProviderCatalog, ProviderCatalogError, ProviderPreset,
    provider_catalog,
};
use chatui_domain::ProviderAuthType;

use crate::adapter::{
    OpenAiCompatibleAdapter, OpenRouterAdapter, ProviderAdapter, ProviderContext,
    UnsupportedAdapter,
};
use crate::anthropic::AnthropicAdapter;
use crate::gemini::GeminiAdapter;

/// Return whether a contract preset has an implemented adapter.
///
/// Enablement is driven by the discovery format, not by a hardcoded provider
/// name. OpenRouter keeps its reference adapter; every OpenAI-format preset uses
/// the shared OpenAI-compatible transport; Anthropic uses the native Messages
/// adapter; Gemini uses the native generateContent adapter. Ollama discovery
/// remains unsupported until its own task lands.
pub fn is_implemented_preset(preset: &ProviderPreset) -> bool {
    matches!(
        preset.model_discovery.format,
        ModelDiscoveryFormat::Openrouter
            | ModelDiscoveryFormat::Openai
            | ModelDiscoveryFormat::Anthropic
            | ModelDiscoveryFormat::Gemini
    )
}

/// Errors returned while building or querying the registry.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum RegistryError {
    /// The contract catalog is invalid.
    Catalog(ProviderCatalogError),
    /// Two presets share an ID.
    DuplicateId(String),
    /// A preset is missing a required field.
    MissingRequiredField {
        /// Preset ID.
        preset_id: String,
        /// Missing field name.
        field: &'static str,
    },
    /// The requested preset is not in the catalog.
    UnknownPreset(String),
}

impl fmt::Display for RegistryError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::Catalog(error) => write!(formatter, "provider catalog is invalid: {error}"),
            Self::DuplicateId(id) => write!(formatter, "duplicate provider preset id: {id}"),
            Self::MissingRequiredField { preset_id, field } => write!(
                formatter,
                "provider preset {preset_id} is missing required field {field}"
            ),
            Self::UnknownPreset(id) => write!(formatter, "unknown provider preset: {id}"),
        }
    }
}

impl std::error::Error for RegistryError {}

impl From<ProviderCatalogError> for RegistryError {
    fn from(error: ProviderCatalogError) -> Self {
        Self::Catalog(error)
    }
}

/// A validated registry of contract presets and their adapters.
#[derive(Debug, Clone, PartialEq)]
pub struct ProviderRegistry {
    presets: Vec<ProviderPreset>,
    adapters: BTreeMap<String, ProviderAdapter>,
}

impl ProviderRegistry {
    /// Load the registry from the checked-in contract.
    pub fn load() -> Result<Self, RegistryError> {
        Self::from_catalog(provider_catalog())
    }

    /// Build a registry from a validated contract catalog.
    pub fn from_catalog(catalog: &ProviderCatalog) -> Result<Self, RegistryError> {
        Self::from_presets(catalog.presets().to_vec())
    }

    /// Build a registry from presets, rejecting duplicates and missing fields.
    pub fn from_presets(presets: Vec<ProviderPreset>) -> Result<Self, RegistryError> {
        let mut seen = BTreeSet::new();
        let mut validated = Vec::with_capacity(presets.len());
        let mut adapters = BTreeMap::new();

        for preset in presets {
            validate_preset(&preset)?;
            if !seen.insert(preset.id.clone()) {
                return Err(RegistryError::DuplicateId(preset.id));
            }
            adapters.insert(preset.id.clone(), adapter_for(&preset));
            validated.push(preset);
        }

        Ok(Self {
            presets: validated,
            adapters,
        })
    }

    /// Return every preset in contract order.
    pub fn presets(&self) -> &[ProviderPreset] {
        &self.presets
    }

    /// Return the preset with `id`, if present.
    pub fn get(&self, id: &str) -> Option<&ProviderPreset> {
        self.presets.iter().find(|preset| preset.id == id)
    }

    /// Return the adapter for `id`, if present.
    pub fn adapter(&self, id: &str) -> Option<&ProviderAdapter> {
        self.adapters.get(id)
    }

    /// Return the single default preset.
    pub fn default_preset(&self) -> &ProviderPreset {
        self.presets
            .iter()
            .find(|preset| preset.is_default)
            .expect("validated registries have exactly one default preset")
    }

    /// Return the single reference preset.
    pub fn reference_preset(&self) -> &ProviderPreset {
        self.presets
            .iter()
            .find(|preset| preset.reference)
            .expect("validated registries have exactly one reference preset")
    }

    /// Build a call context for a preset.
    pub fn context(
        &self,
        preset_id: &str,
        app_config: &AppConfig,
        api_key: Option<String>,
    ) -> Result<ProviderContext, RegistryError> {
        let preset = self
            .get(preset_id)
            .ok_or_else(|| RegistryError::UnknownPreset(preset_id.to_owned()))?;
        Ok(ProviderContext::new(preset, app_config, api_key))
    }
}

/// Create an adapter for a preset ID from the bundled contract.
pub fn create_adapter(preset_id: &str) -> Result<ProviderAdapter, RegistryError> {
    let preset = provider_catalog()
        .get(preset_id)
        .ok_or_else(|| RegistryError::UnknownPreset(preset_id.to_owned()))?;
    Ok(adapter_for(preset))
}

fn adapter_for(preset: &ProviderPreset) -> ProviderAdapter {
    match preset.model_discovery.format {
        ModelDiscoveryFormat::Openrouter => ProviderAdapter::OpenRouter(OpenRouterAdapter),
        ModelDiscoveryFormat::Openai => {
            ProviderAdapter::OpenAiCompatible(OpenAiCompatibleAdapter::new(preset.id.clone()))
        }
        ModelDiscoveryFormat::Anthropic => {
            ProviderAdapter::Anthropic(AnthropicAdapter::new(preset.id.clone()))
        }
        ModelDiscoveryFormat::Gemini => {
            ProviderAdapter::Gemini(GeminiAdapter::new(preset.id.clone()))
        }
        _ => ProviderAdapter::Unsupported(UnsupportedAdapter::new(preset.id.clone(), preset.kind)),
    }
}

fn validate_preset(preset: &ProviderPreset) -> Result<(), RegistryError> {
    let required = [
        ("id", !preset.id.trim().is_empty()),
        ("label", !preset.label.trim().is_empty()),
        (
            "baseUrl",
            preset.base_url.starts_with("http://") || preset.base_url.starts_with("https://"),
        ),
        ("defaultModelId", !preset.default_model_id.trim().is_empty()),
        (
            "modelDiscovery.path",
            !preset.model_discovery.path.trim().is_empty(),
        ),
    ];

    for (field, present) in required {
        if !present {
            return Err(RegistryError::MissingRequiredField {
                preset_id: preset.id.clone(),
                field,
            });
        }
    }

    match preset.auth.auth_type {
        ProviderAuthType::Bearer | ProviderAuthType::XApiKey => {
            if preset
                .auth
                .header_name
                .as_deref()
                .is_none_or(|name| name.trim().is_empty())
            {
                return Err(RegistryError::MissingRequiredField {
                    preset_id: preset.id.clone(),
                    field: "auth.headerName",
                });
            }
        }
        ProviderAuthType::QueryKey => {
            if preset
                .auth
                .query_param
                .as_deref()
                .is_none_or(|name| name.trim().is_empty())
            {
                return Err(RegistryError::MissingRequiredField {
                    preset_id: preset.id.clone(),
                    field: "auth.queryParam",
                });
            }
        }
        ProviderAuthType::None => {}
    }

    if let Some(validation) = &preset.key_validation
        && validation.path.trim().is_empty()
    {
        return Err(RegistryError::MissingRequiredField {
            preset_id: preset.id.clone(),
            field: "keyValidation.path",
        });
    }

    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn openrouter_preset() -> ProviderPreset {
        provider_catalog()
            .get("openrouter")
            .expect("openrouter preset")
            .clone()
    }

    #[test]
    fn loads_every_contract_preset() {
        let registry = ProviderRegistry::load().expect("registry loads");

        assert_eq!(registry.presets().len(), 14);
        assert_eq!(registry.default_preset().id, "openrouter");
        assert_eq!(registry.reference_preset().id, "openrouter");

        for preset in registry.presets() {
            let adapter = registry.adapter(&preset.id).expect("adapter exists");
            assert_eq!(adapter.preset_id(), preset.id);
            assert_eq!(adapter.kind(), preset.kind);
        }
    }

    #[test]
    fn openai_compatible_presets_are_supported() {
        let registry = ProviderRegistry::load().expect("registry loads");

        assert!(
            registry
                .adapter("openrouter")
                .expect("openrouter adapter")
                .is_supported()
        );

        for preset in registry.presets() {
            let adapter = registry.adapter(&preset.id).expect("adapter exists");
            let expected = matches!(
                preset.model_discovery.format,
                ModelDiscoveryFormat::Openrouter
                    | ModelDiscoveryFormat::Openai
                    | ModelDiscoveryFormat::Anthropic
                    | ModelDiscoveryFormat::Gemini
            );
            assert_eq!(
                adapter.is_supported(),
                expected,
                "{} support does not match its discovery format",
                preset.id
            );
        }

        assert!(
            !registry
                .adapter("ollama")
                .expect("adapter exists")
                .is_supported(),
            "ollama must remain unsupported"
        );
    }

    #[test]
    fn rejects_duplicate_ids() {
        let preset = openrouter_preset();
        let error = ProviderRegistry::from_presets(vec![preset.clone(), preset])
            .expect_err("duplicate IDs are rejected");

        assert_eq!(error, RegistryError::DuplicateId("openrouter".to_owned()));
    }

    #[test]
    fn rejects_missing_required_fields() {
        let mut preset = openrouter_preset();
        preset.base_url = String::new();

        let error =
            ProviderRegistry::from_presets(vec![preset]).expect_err("missing base URL is rejected");
        assert!(matches!(
            error,
            RegistryError::MissingRequiredField {
                field: "baseUrl",
                ..
            }
        ));
    }

    #[test]
    fn rejects_unknown_presets() {
        assert_eq!(
            create_adapter("nope").expect_err("unknown preset"),
            RegistryError::UnknownPreset("nope".to_owned())
        );
    }

    #[test]
    fn builds_a_context_from_the_contract_preset() {
        let registry = ProviderRegistry::load().expect("registry loads");
        let config = AppConfig::new("ChatUI", "https://chatui.example").expect("valid config");
        let ctx = registry
            .context("openrouter", &config, Some("sk-test".to_owned()))
            .expect("context");

        assert_eq!(ctx.preset_id(), "openrouter");
        assert_eq!(ctx.base_url, "https://openrouter.ai/api/v1");
        assert_eq!(
            ctx.headers.get("X-Title").map(String::as_str),
            Some("ChatUI")
        );
    }
}
