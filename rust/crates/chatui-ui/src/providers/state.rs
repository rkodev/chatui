//! Framework-independent provider management view models.
//!
//! The view models are free of Slint, storage, and network types. They hold the
//! provider list, the selected provider detail, the custom provider form, and
//! the pending destructive-action confirmation. The Slint controller in
//! [`crate::providers::view`] renders this state and forwards user actions.
//!
//! Keys are never part of a view model. The form holds a [`SecretValue`] whose
//! `Debug` output is redacted, and the detail view only exposes the non-secret
//! `/key` metadata cached on the provider record.

#![allow(clippy::result_large_err)]

use std::collections::BTreeMap;
use std::fmt;

use chatui_domain::{BrowserCors, Provider, ProviderAuthType};
use chatui_providers::ProviderKeyInfo;
use chatui_secrets::SecretValue;

use crate::providers::error::ProviderManagementErrorCopy;
use crate::providers::validation::{format_headers_text, parse_headers_text};

/// Non-secret `/key` metadata cached on a provider record.
#[derive(Debug, Clone, PartialEq)]
pub struct ProviderKeyMetadata {
    /// Normalized `/key` fields.
    pub info: ProviderKeyInfo,
    /// When the metadata was last refreshed.
    pub validated_at: Option<String>,
}

impl ProviderKeyMetadata {
    /// Read the cached `/key` metadata from a provider record.
    ///
    /// Returns `None` when the provider has never been validated. The returned
    /// value never contains the API key.
    pub fn from_provider(provider: &Provider) -> Option<Self> {
        let metadata = provider.metadata.as_ref()?;
        let key = metadata.get("key")?;
        let info = serde_json::from_value::<ProviderKeyInfo>(key.clone()).ok()?;
        let validated_at = metadata
            .get("validatedAt")
            .and_then(serde_json::Value::as_str)
            .map(str::to_owned);

        Some(Self { info, validated_at })
    }
}

/// One row in the provider list.
#[derive(Debug, Clone, PartialEq)]
pub struct ProviderListEntry {
    /// Local provider ID.
    pub id: String,
    /// Display label.
    pub label: String,
    /// Preset ID.
    pub preset_id: String,
    /// Preset display label from the contract.
    pub preset_label: String,
    /// Whether the provider is enabled.
    pub enabled: bool,
    /// Whether the preset is the recommended default.
    pub recommended: bool,
    /// Whether a key is present in the secret store.
    pub key_present: bool,
    /// Browser CORS capability.
    pub browser_cors: BrowserCors,
    /// Documentation URL from the contract.
    pub docs_url: Option<String>,
}

impl ProviderListEntry {
    /// Enabled/disabled label.
    pub fn status_label(&self) -> &'static str {
        if self.enabled { "Enabled" } else { "Disabled" }
    }

    /// Key-presence label. Never includes the key.
    pub fn key_label(&self) -> &'static str {
        if self.key_present {
            "Key stored"
        } else {
            "No key"
        }
    }

    /// CORS label.
    pub fn cors_label(&self) -> &'static str {
        cors_label(self.browser_cors)
    }

    /// Compact metadata line for the list row.
    pub fn meta_line(&self) -> String {
        let mut parts = vec![
            self.preset_label.clone(),
            self.status_label().to_owned(),
            self.key_label().to_owned(),
            format!("CORS {}", self.cors_label()),
        ];

        if self.recommended {
            parts.push("Recommended".to_owned());
        }

        parts.join(" · ")
    }
}

/// Provider list view model.
#[derive(Debug, Clone, PartialEq, Default)]
pub struct ProviderListViewModel {
    entries: Vec<ProviderListEntry>,
    add_provider_available: bool,
    notice: Option<String>,
    error: Option<ProviderManagementErrorCopy>,
}

impl ProviderListViewModel {
    /// Build a list view model.
    pub fn new(entries: Vec<ProviderListEntry>, add_provider_available: bool) -> Self {
        Self {
            entries,
            add_provider_available,
            notice: None,
            error: None,
        }
    }

    /// Provider rows.
    pub fn entries(&self) -> &[ProviderListEntry] {
        &self.entries
    }

    /// Whether the add-provider entry point is available.
    pub fn add_provider_available(&self) -> bool {
        self.add_provider_available
    }

    /// Non-blocking notice.
    pub fn notice(&self) -> Option<&str> {
        self.notice.as_deref()
    }

    /// Current error copy.
    pub fn error(&self) -> Option<&ProviderManagementErrorCopy> {
        self.error.as_ref()
    }

    /// Whether the list is empty.
    pub fn is_empty(&self) -> bool {
        self.entries.is_empty()
    }

    /// Number of providers with a stored key.
    pub fn key_present_count(&self) -> usize {
        self.entries
            .iter()
            .filter(|entry| entry.key_present)
            .count()
    }

    /// Set the non-blocking notice.
    pub fn set_notice(&mut self, notice: Option<String>) {
        self.notice = notice;
    }

    /// Set the current error copy.
    pub fn set_error(&mut self, error: Option<ProviderManagementErrorCopy>) {
        self.error = error;
    }
}

/// Provider detail view model.
#[derive(Debug, Clone, PartialEq)]
pub struct ProviderDetailViewModel {
    /// Local provider ID.
    pub id: String,
    /// Display label.
    pub label: String,
    /// Preset ID.
    pub preset_id: String,
    /// Preset display label from the contract.
    pub preset_label: String,
    /// Base URL.
    pub base_url: String,
    /// Authentication style.
    pub auth_type: ProviderAuthType,
    /// Whether the provider is enabled.
    pub enabled: bool,
    /// Whether the preset is the recommended default.
    pub recommended: bool,
    /// Whether a key is present in the secret store.
    pub key_present: bool,
    /// Non-secret `/key` metadata, when available.
    pub key_metadata: Option<ProviderKeyMetadata>,
    /// Documentation URL from the contract.
    pub docs_url: Option<String>,
    /// Browser CORS capability.
    pub browser_cors: BrowserCors,
    /// Non-secret custom headers.
    pub headers: BTreeMap<String, String>,
    /// Warning for allowed cleartext HTTP.
    pub cleartext_warning: Option<String>,
    /// Number of cached models.
    pub model_count: usize,
}

impl ProviderDetailViewModel {
    /// Key label from `/key`, falling back to the provider label.
    pub fn key_label(&self) -> String {
        self.key_metadata
            .as_ref()
            .and_then(|metadata| metadata.info.label.clone())
            .unwrap_or_else(|| self.label.clone())
    }

    /// Usage display.
    pub fn usage_display(&self) -> String {
        format_usd(self.key_metadata.as_ref().and_then(|m| m.info.usage_usd))
    }

    /// Limit display.
    pub fn limit_display(&self) -> String {
        match self.key_metadata.as_ref() {
            None => "Not available".to_owned(),
            Some(metadata) => match metadata.info.limit_usd {
                None => "No limit".to_owned(),
                Some(value) => format_usd(Some(value)),
            },
        }
    }

    /// Free-tier display.
    pub fn free_tier_display(&self) -> &'static str {
        match self.key_metadata.as_ref().and_then(|m| m.info.is_free_tier) {
            None => "Unknown",
            Some(true) => "Yes",
            Some(false) => "No",
        }
    }

    /// Rate-limit display, when reported.
    pub fn rate_limit_display(&self) -> Option<String> {
        format_rate_limit(
            self.key_metadata
                .as_ref()
                .and_then(|m| m.info.rate_limit.as_ref()),
        )
    }

    /// CORS display.
    pub fn cors_display(&self) -> &'static str {
        cors_label(self.browser_cors)
    }

    /// Authentication display.
    pub fn auth_display(&self) -> &'static str {
        match self.auth_type {
            ProviderAuthType::Bearer => "Bearer token",
            ProviderAuthType::XApiKey => "x-api-key header",
            ProviderAuthType::QueryKey => "Query-string key",
            ProviderAuthType::None => "No authentication",
        }
    }

    /// Non-secret headers display.
    pub fn headers_display(&self) -> String {
        format_headers_text(&self.headers)
    }

    /// Whether the detail view has any key metadata to show.
    pub fn has_key_metadata(&self) -> bool {
        self.key_metadata.is_some()
    }
}

/// Custom provider form state.
///
/// The API key is held in a [`SecretValue`] and is never included in `Debug`
/// output. Editing an existing provider starts with an empty key field; an
/// empty field means "keep the existing key".
#[derive(Clone, PartialEq)]
pub struct CustomProviderForm {
    /// Provider being edited, or `None` when creating.
    pub editing_provider_id: Option<String>,
    /// Display label.
    pub label: String,
    /// Base URL.
    pub base_url: String,
    /// Authentication style.
    pub auth_type: ProviderAuthType,
    /// Typed API key. Never logged.
    pub api_key: SecretValue,
    /// Non-secret custom headers.
    pub headers: BTreeMap<String, String>,
    /// Raw textarea text for the non-secret headers.
    pub headers_text: String,
    /// Whether to test the connection before saving.
    pub test_connection: bool,
    /// Cleartext warning for the current base URL.
    pub cleartext_warning: Option<String>,
    /// Form-level error copy.
    pub error: Option<ProviderManagementErrorCopy>,
    /// Form-level notice.
    pub notice: Option<String>,
}

impl Default for CustomProviderForm {
    fn default() -> Self {
        Self {
            editing_provider_id: None,
            label: String::new(),
            base_url: String::new(),
            auth_type: ProviderAuthType::Bearer,
            api_key: SecretValue::new(String::new()),
            headers: BTreeMap::new(),
            headers_text: String::new(),
            test_connection: true,
            cleartext_warning: None,
            error: None,
            notice: None,
        }
    }
}

impl fmt::Debug for CustomProviderForm {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter
            .debug_struct("CustomProviderForm")
            .field("editing_provider_id", &self.editing_provider_id)
            .field("label", &self.label)
            .field("base_url", &self.base_url)
            .field("auth_type", &self.auth_type)
            .field("api_key", &"[REDACTED]")
            .field("headers", &self.headers)
            .field("headers_text", &self.headers_text)
            .field("test_connection", &self.test_connection)
            .field("cleartext_warning", &self.cleartext_warning)
            .field("error", &self.error)
            .field("notice", &self.notice)
            .finish()
    }
}

impl CustomProviderForm {
    /// Create an empty form.
    pub fn new() -> Self {
        Self::default()
    }

    /// Create a form pre-filled for editing an existing custom provider.
    ///
    /// The key field is always empty; the stored key is never loaded into the
    /// form.
    pub fn for_edit(provider: &Provider) -> Self {
        Self {
            editing_provider_id: Some(provider.id.clone()),
            label: provider.label.clone(),
            base_url: provider.base_url.clone(),
            auth_type: provider.auth_type,
            api_key: SecretValue::new(String::new()),
            headers: provider.headers.clone(),
            headers_text: format_headers_text(&provider.headers),
            test_connection: true,
            cleartext_warning: None,
            error: None,
            notice: None,
        }
    }

    /// Masked key representation for display outside the password field.
    pub fn masked_key(&self) -> String {
        let length = self.api_key.expose().chars().count();
        if length == 0 {
            String::new()
        } else {
            "•".repeat(length.min(12))
        }
    }

    /// Replace the typed key.
    pub fn set_api_key(&mut self, value: impl Into<String>) {
        self.api_key = SecretValue::new(value);
    }

    /// Borrow the typed key.
    pub fn api_key(&self) -> &SecretValue {
        &self.api_key
    }

    /// Render the headers for the textarea.
    pub fn headers_text(&self) -> &str {
        &self.headers_text
    }

    /// Parse the textarea into the header map.
    pub fn set_headers_text(
        &mut self,
        text: &str,
    ) -> Result<(), crate::providers::error::ProviderManagementError> {
        self.headers_text = text.to_owned();
        self.headers = parse_headers_text(text)?;
        Ok(())
    }

    /// Whether this form edits an existing provider.
    pub fn is_editing(&self) -> bool {
        self.editing_provider_id.is_some()
    }
}

/// A destructive action waiting for explicit confirmation.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ConfirmationAction {
    /// Forget one provider key.
    ForgetKey,
    /// Forget every stored key.
    ForgetAllKeys,
    /// Delete a provider and its models.
    DeleteProvider,
}

/// Pending confirmation state.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PendingConfirmation {
    /// Action awaiting confirmation.
    pub action: ConfirmationAction,
    /// Provider the action applies to, when relevant.
    pub provider_id: Option<String>,
}

impl PendingConfirmation {
    /// Confirmation prompt copy.
    pub fn prompt(&self) -> &'static str {
        match self.action {
            ConfirmationAction::ForgetKey => {
                "Click again to confirm forgetting this key. Sending is disabled until a new key is added."
            }
            ConfirmationAction::ForgetAllKeys => {
                "Click again to confirm forgetting every stored key. Sending is disabled until keys are added again."
            }
            ConfirmationAction::DeleteProvider => {
                "Click again to confirm deleting this provider. Its models and key are removed; past messages keep their model labels."
            }
        }
    }
}

/// Complete provider management view state.
#[derive(Debug, Clone, PartialEq, Default)]
pub struct ProviderManagementViewModel {
    /// Provider list.
    pub list: ProviderListViewModel,
    /// Selected provider detail.
    pub detail: Option<ProviderDetailViewModel>,
    /// Open custom provider form.
    pub form: Option<CustomProviderForm>,
    /// Pending destructive-action confirmation.
    pub pending_confirmation: Option<PendingConfirmation>,
    /// Non-blocking notice.
    pub notice: Option<String>,
    /// Current error copy.
    pub error: Option<ProviderManagementErrorCopy>,
}

impl ProviderManagementViewModel {
    /// Clear transient notices and errors.
    pub fn clear_messages(&mut self) {
        self.notice = None;
        self.error = None;
    }
}

/// CORS display label.
pub fn cors_label(value: BrowserCors) -> &'static str {
    match value {
        BrowserCors::Supported => "Supported",
        BrowserCors::Unsupported => "Not supported",
        BrowserCors::Unknown => "Unknown",
    }
}

/// Format a USD amount without losing small per-million prices.
pub fn format_usd(value: Option<f64>) -> String {
    let Some(value) = value else {
        return "Not available".to_owned();
    };

    if !value.is_finite() {
        return "Not available".to_owned();
    }

    if value == 0.0 {
        return "$0".to_owned();
    }

    let absolute = value.abs();
    let digits = if absolute >= 1.0 {
        2
    } else if absolute >= 0.01 {
        4
    } else {
        6
    };
    let text = format!("{value:.digits$}");
    let trimmed = text.trim_end_matches('0').trim_end_matches('.');

    format!("${trimmed}")
}

/// Format the non-secret `/key` rate-limit object for display.
pub fn format_rate_limit(value: Option<&serde_json::Value>) -> Option<String> {
    let value = value?;
    let object = value.as_object()?;

    if let (Some(requests), Some(interval)) = (
        object.get("requests").and_then(serde_json::Value::as_u64),
        object.get("interval").and_then(serde_json::Value::as_str),
    ) {
        return Some(format!("{requests} requests per {interval}"));
    }

    if object.is_empty() {
        return None;
    }

    Some(
        object
            .iter()
            .map(|(key, entry)| {
                let rendered = entry
                    .as_str()
                    .map(str::to_owned)
                    .unwrap_or_else(|| entry.to_string());
                format!("{key}: {rendered}")
            })
            .collect::<Vec<_>>()
            .join(", "),
    )
}

#[cfg(test)]
mod tests {
    use super::*;
    use chatui_domain::{ProviderKind, Timestamp};

    fn provider() -> Provider {
        let now = Timestamp::now();
        Provider {
            id: "provider-1".to_owned(),
            preset_id: "openrouter".to_owned(),
            label: "Personal".to_owned(),
            kind: ProviderKind::OpenaiCompatible,
            base_url: "https://openrouter.ai/api/v1".to_owned(),
            auth_type: ProviderAuthType::Bearer,
            secret_id: Some("provider-1".to_owned()),
            headers: BTreeMap::new(),
            enabled: true,
            browser_cors: BrowserCors::Supported,
            metadata: Some(serde_json::json!({
                "key": {
                    "label": "Work key",
                    "usageUsd": 1.25,
                    "limitUsd": 10.0,
                    "limitRemainingUsd": 8.75,
                    "isFreeTier": false,
                    "rateLimit": { "requests": 60, "interval": "minute" }
                },
                "validatedAt": "2026-11-07T00:00:00.000Z"
            })),
            created_at: now,
            updated_at: now,
        }
    }

    #[test]
    fn key_metadata_is_read_from_the_provider_record() {
        let metadata = ProviderKeyMetadata::from_provider(&provider()).expect("key metadata");

        assert_eq!(metadata.info.label.as_deref(), Some("Work key"));
        assert_eq!(metadata.info.usage_usd, Some(1.25));
        assert_eq!(metadata.info.limit_usd, Some(10.0));
        assert_eq!(metadata.info.is_free_tier, Some(false));
        assert_eq!(
            metadata.validated_at.as_deref(),
            Some("2026-11-07T00:00:00.000Z")
        );
    }

    #[test]
    fn key_metadata_is_absent_without_a_key_object() {
        let mut provider = provider();
        provider.metadata = Some(serde_json::json!({ "other": true }));

        assert!(ProviderKeyMetadata::from_provider(&provider).is_none());
    }

    #[test]
    fn detail_formats_openrouter_metadata() {
        let detail = ProviderDetailViewModel {
            id: "provider-1".to_owned(),
            label: "Personal".to_owned(),
            preset_id: "openrouter".to_owned(),
            preset_label: "OpenRouter".to_owned(),
            base_url: "https://openrouter.ai/api/v1".to_owned(),
            auth_type: ProviderAuthType::Bearer,
            enabled: true,
            recommended: true,
            key_present: true,
            key_metadata: ProviderKeyMetadata::from_provider(&provider()),
            docs_url: Some("https://openrouter.ai/docs".to_owned()),
            browser_cors: BrowserCors::Supported,
            headers: BTreeMap::new(),
            cleartext_warning: None,
            model_count: 3,
        };

        assert_eq!(detail.key_label(), "Work key");
        assert_eq!(detail.usage_display(), "$1.25");
        assert_eq!(detail.limit_display(), "$10");
        assert_eq!(detail.free_tier_display(), "No");
        assert_eq!(
            detail.rate_limit_display().as_deref(),
            Some("60 requests per minute")
        );
        assert_eq!(detail.cors_display(), "Supported");
        assert_eq!(detail.auth_display(), "Bearer token");
        assert!(detail.has_key_metadata());
    }

    #[test]
    fn detail_without_metadata_shows_placeholders() {
        let detail = ProviderDetailViewModel {
            id: "provider-1".to_owned(),
            label: "Custom".to_owned(),
            preset_id: "custom".to_owned(),
            preset_label: "Custom OpenAI-compatible".to_owned(),
            base_url: "https://example.com/v1".to_owned(),
            auth_type: ProviderAuthType::None,
            enabled: false,
            recommended: false,
            key_present: false,
            key_metadata: None,
            docs_url: None,
            browser_cors: BrowserCors::Unknown,
            headers: BTreeMap::new(),
            cleartext_warning: None,
            model_count: 0,
        };

        assert_eq!(detail.key_label(), "Custom");
        assert_eq!(detail.usage_display(), "Not available");
        assert_eq!(detail.limit_display(), "Not available");
        assert_eq!(detail.free_tier_display(), "Unknown");
        assert_eq!(detail.rate_limit_display(), None);
        assert_eq!(detail.cors_display(), "Unknown");
        assert_eq!(detail.auth_display(), "No authentication");
        assert!(!detail.has_key_metadata());
    }

    #[test]
    fn list_entry_meta_line_includes_recommended_and_key_state() {
        let entry = ProviderListEntry {
            id: "provider-1".to_owned(),
            label: "OpenRouter".to_owned(),
            preset_id: "openrouter".to_owned(),
            preset_label: "OpenRouter".to_owned(),
            enabled: true,
            recommended: true,
            key_present: true,
            browser_cors: BrowserCors::Supported,
            docs_url: Some("https://openrouter.ai/docs".to_owned()),
        };

        let meta = entry.meta_line();
        assert!(meta.contains("Enabled"));
        assert!(meta.contains("Key stored"));
        assert!(meta.contains("Recommended"));
        assert!(!meta.contains("sk-"));
    }

    #[test]
    fn form_debug_redacts_the_key() {
        let mut form = CustomProviderForm::new();
        form.set_api_key("sk-test-not-a-real-key");

        let rendered = format!("{form:?}");
        assert!(!rendered.contains("sk-test-not-a-real-key"));
        assert!(rendered.contains("[REDACTED]"));
    }

    #[test]
    fn form_masks_the_key() {
        let mut form = CustomProviderForm::new();
        form.set_api_key("sk-test-not-a-real-key");

        let masked = form.masked_key();
        assert!(!masked.contains("sk-test-not-a-real-key"));
        assert!(!masked.is_empty());
    }

    #[test]
    fn edit_form_never_loads_the_stored_key() {
        let form = CustomProviderForm::for_edit(&provider());

        assert_eq!(form.editing_provider_id.as_deref(), Some("provider-1"));
        assert!(form.api_key().is_empty());
        assert!(form.is_editing());
    }

    #[test]
    fn usd_formatting_keeps_small_values() {
        assert_eq!(format_usd(None), "Not available");
        assert_eq!(format_usd(Some(0.0)), "$0");
        assert_eq!(format_usd(Some(1.25)), "$1.25");
        assert_eq!(format_usd(Some(0.000001)), "$0.000001");
    }

    #[test]
    fn rate_limit_falls_back_to_a_compact_summary() {
        let value = serde_json::json!({ "tier": "free" });

        assert_eq!(
            format_rate_limit(Some(&value)).as_deref(),
            Some("tier: free")
        );
        assert_eq!(format_rate_limit(None), None);
    }

    #[test]
    fn confirmation_prompts_are_explicit() {
        assert!(
            PendingConfirmation {
                action: ConfirmationAction::ForgetKey,
                provider_id: Some("provider-1".to_owned()),
            }
            .prompt()
            .contains("confirm")
        );
        assert!(
            PendingConfirmation {
                action: ConfirmationAction::ForgetAllKeys,
                provider_id: None,
            }
            .prompt()
            .contains("every stored key")
        );
        assert!(
            PendingConfirmation {
                action: ConfirmationAction::DeleteProvider,
                provider_id: Some("provider-1".to_owned()),
            }
            .prompt()
            .contains("model labels")
        );
    }
}
