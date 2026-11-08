//! Runtime application configuration for attribution headers.
//!
//! `AppConfig` is deployment-level configuration. It must not contain user
//! names, email addresses, API keys, or other personal data. The desktop app
//! supplies the values at startup; this crate never reads `.env` files or
//! secrets.

use std::collections::BTreeMap;
use std::fmt;

use serde::{Deserialize, Serialize};

use crate::providers::ProviderPreset;

/// Placeholder replaced with [`AppConfig::origin`].
pub const APP_ORIGIN_PLACEHOLDER: &str = "app.origin";

/// Placeholder replaced with [`AppConfig::name`].
pub const APP_NAME_PLACEHOLDER: &str = "app.name";

/// Application name and public origin used for attribution headers.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct AppConfig {
    /// Public application name, such as `ChatUI`.
    pub name: String,
    /// Public application origin, such as `https://chatui.example`.
    pub origin: String,
}

/// Errors returned when constructing an [`AppConfig`].
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum AppConfigError {
    /// The application name is empty.
    EmptyName,
    /// The origin is not an absolute HTTP(S) URL.
    InvalidOrigin(String),
}

impl fmt::Display for AppConfigError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::EmptyName => formatter.write_str("application name must not be empty"),
            Self::InvalidOrigin(origin) => {
                write!(
                    formatter,
                    "application origin must be an absolute HTTP(S) URL: {origin}"
                )
            }
        }
    }
}

impl std::error::Error for AppConfigError {}

impl AppConfig {
    /// Create a validated application configuration.
    pub fn new(name: impl Into<String>, origin: impl Into<String>) -> Result<Self, AppConfigError> {
        let name = name.into();
        let origin = origin.into();

        if name.trim().is_empty() {
            return Err(AppConfigError::EmptyName);
        }

        if !is_http_url(&origin) {
            return Err(AppConfigError::InvalidOrigin(origin));
        }

        Ok(Self { name, origin })
    }

    /// Resolve one attribution header value.
    ///
    /// `app.name` and `app.origin` are replaced with the configured values.
    /// Any other value is returned unchanged so presets can add literal
    /// attribution headers without a code change.
    pub fn resolve_attribution_value(&self, value: &str) -> String {
        match value {
            APP_NAME_PLACEHOLDER => self.name.clone(),
            APP_ORIGIN_PLACEHOLDER => self.origin.clone(),
            other => other.to_owned(),
        }
    }

    /// Resolve every attribution header declared by a provider preset.
    pub fn attribution_headers(&self, preset: &ProviderPreset) -> BTreeMap<String, String> {
        preset
            .attribution_headers
            .iter()
            .map(|(name, value)| (name.clone(), self.resolve_attribution_value(value)))
            .collect()
    }
}

fn is_http_url(value: &str) -> bool {
    let Some(rest) = value
        .strip_prefix("https://")
        .or_else(|| value.strip_prefix("http://"))
    else {
        return false;
    };

    !rest.is_empty() && !rest.chars().any(char::is_whitespace)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::providers::provider_catalog;

    #[test]
    fn resolves_attribution_placeholders() {
        let config = AppConfig::new("ChatUI", "https://chatui.example").expect("valid config");
        let preset = provider_catalog()
            .get("openrouter")
            .expect("openrouter preset exists");

        let headers = config.attribution_headers(preset);

        assert_eq!(
            headers.get("HTTP-Referer").map(String::as_str),
            Some("https://chatui.example")
        );
        assert_eq!(headers.get("X-Title").map(String::as_str), Some("ChatUI"));
    }

    #[test]
    fn leaves_literal_attribution_values_unchanged() {
        let config = AppConfig::new("ChatUI", "https://chatui.example").expect("valid config");

        assert_eq!(config.resolve_attribution_value("literal"), "literal");
        assert_eq!(
            config.resolve_attribution_value(APP_NAME_PLACEHOLDER),
            "ChatUI"
        );
        assert_eq!(
            config.resolve_attribution_value(APP_ORIGIN_PLACEHOLDER),
            "https://chatui.example"
        );
    }

    #[test]
    fn rejects_empty_name_and_non_http_origin() {
        assert_eq!(
            AppConfig::new("  ", "https://chatui.example").unwrap_err(),
            AppConfigError::EmptyName
        );
        assert_eq!(
            AppConfig::new("ChatUI", "chatui.example").unwrap_err(),
            AppConfigError::InvalidOrigin("chatui.example".to_owned())
        );
        assert_eq!(
            AppConfig::new("ChatUI", "https://chatui.example/path with space").unwrap_err(),
            AppConfigError::InvalidOrigin("https://chatui.example/path with space".to_owned())
        );
    }

    #[test]
    fn app_config_round_trips_with_exact_field_names() {
        let config = AppConfig::new("ChatUI", "https://chatui.example").expect("valid config");
        let value = serde_json::to_value(&config).expect("config serializes");

        assert_eq!(
            value,
            serde_json::json!({
                "name": "ChatUI",
                "origin": "https://chatui.example"
            })
        );

        let parsed: AppConfig = serde_json::from_value(value).expect("config deserializes");
        assert_eq!(parsed, config);
    }

    #[test]
    fn unknown_app_config_fields_are_rejected() {
        let value = serde_json::json!({
            "name": "ChatUI",
            "origin": "https://chatui.example",
            "personalEmail": "person@example.com"
        });

        assert!(serde_json::from_value::<AppConfig>(value).is_err());
    }
}
