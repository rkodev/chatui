//! Provider entity and provider enums from spec section 6.2.

use std::collections::BTreeMap;

use serde::{Deserialize, Serialize};
use serde_json::Value;

use crate::time::Timestamp;

/// Provider kind from spec section 6.2.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "kebab-case")]
pub enum ProviderKind {
    /// OpenAI-compatible HTTP API.
    OpenaiCompatible,
    /// Anthropic Messages API.
    Anthropic,
    /// Google Gemini API.
    GoogleGemini,
}

/// How a provider authenticates requests.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "kebab-case")]
pub enum ProviderAuthType {
    /// `Authorization: Bearer <key>`.
    Bearer,
    /// `x-api-key: <key>`.
    XApiKey,
    /// API key in a query parameter.
    QueryKey,
    /// No authentication.
    None,
}

/// Browser CORS capability advertised by a provider preset.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum BrowserCors {
    /// The provider supports browser CORS.
    Supported,
    /// The provider does not support browser CORS.
    Unsupported,
    /// Browser CORS support is unknown.
    Unknown,
}

/// Provider entity from spec section 6.2.
///
/// `secret_id` references the secret store; the key itself is never stored in
/// the provider record. `headers` may contain non-secret headers only.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Provider {
    /// Local provider ID.
    pub id: String,
    /// Preset ID, or `custom` for user-defined providers.
    pub preset_id: String,
    /// Display label.
    pub label: String,
    /// Provider kind.
    pub kind: ProviderKind,
    /// Base URL for API requests.
    pub base_url: String,
    /// Authentication scheme.
    pub auth_type: ProviderAuthType,
    /// Secret-store reference, or `null` when no secret is stored.
    pub secret_id: Option<String>,
    /// Non-secret custom headers.
    pub headers: BTreeMap<String, String>,
    /// Whether the provider is enabled.
    pub enabled: bool,
    /// Browser CORS capability.
    pub browser_cors: BrowserCors,
    /// Non-secret provider metadata.
    pub metadata: Option<Value>,
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
            serde_json::to_string(&ProviderKind::OpenaiCompatible).unwrap(),
            "\"openai-compatible\""
        );
        assert_eq!(
            serde_json::to_string(&ProviderKind::Anthropic).unwrap(),
            "\"anthropic\""
        );
        assert_eq!(
            serde_json::to_string(&ProviderKind::GoogleGemini).unwrap(),
            "\"google-gemini\""
        );
        assert_eq!(
            serde_json::to_string(&ProviderAuthType::XApiKey).unwrap(),
            "\"x-api-key\""
        );
        assert_eq!(
            serde_json::to_string(&ProviderAuthType::QueryKey).unwrap(),
            "\"query-key\""
        );
        assert_eq!(
            serde_json::to_string(&BrowserCors::Unsupported).unwrap(),
            "\"unsupported\""
        );
    }

    #[test]
    fn round_trips_with_nulls_empty_headers_and_unknown_metadata() {
        let provider = Provider {
            id: "provider-1".to_owned(),
            preset_id: "custom".to_owned(),
            label: "Custom".to_owned(),
            kind: ProviderKind::OpenaiCompatible,
            base_url: "https://example.com/v1".to_owned(),
            auth_type: ProviderAuthType::Bearer,
            secret_id: None,
            headers: BTreeMap::new(),
            enabled: true,
            browser_cors: BrowserCors::Unknown,
            metadata: Some(serde_json::json!({
                "unknown": { "nested": [1, 2, 3] },
                "freeTier": true
            })),
            created_at: fixed(),
            updated_at: fixed(),
        };

        let json = serde_json::to_value(&provider).expect("serialize provider");
        assert_eq!(json["secretId"], Value::Null);
        assert_eq!(json["headers"], serde_json::json!({}));
        assert_eq!(json["metadata"]["unknown"]["nested"][2], 3);

        let parsed: Provider = serde_json::from_value(json).expect("deserialize provider");
        assert_eq!(parsed, provider);
    }
}
