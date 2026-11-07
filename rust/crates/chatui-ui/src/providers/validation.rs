//! Custom provider input validation.
//!
//! The rules mirror the shared URL policy in `chatui-providers` and the web
//! provider service:
//!
//! * HTTPS is always allowed.
//! * Cleartext HTTP is allowed only for localhost, `.local`, and private-range
//!   hosts, and always carries an explicit warning.
//! * Public cleartext HTTP is rejected.
//! * Credentials, query strings, and fragments are rejected in a base URL.
//! * Authentication headers are rejected in the non-secret header map.
//! * Query-string API keys are not supported.

#![allow(clippy::result_large_err)]

use std::collections::BTreeMap;

use chatui_domain::ProviderAuthType;
use chatui_providers::{UrlPolicyError, classify_url, join_url};
use chatui_secrets::SecretValue;

use crate::providers::error::ProviderManagementError;

const FORBIDDEN_HEADER_NAMES: &[&str] = &[
    "authorization",
    "proxy-authorization",
    "x-api-key",
    "x-goog-api-key",
    "api-key",
    "cookie",
    "set-cookie",
    "x-auth-token",
    "x-access-token",
];

/// A validated base URL and its optional cleartext warning.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ValidatedBaseUrl {
    /// Trimmed base URL to persist.
    pub url: String,
    /// Warning to show when the URL uses allowed cleartext HTTP.
    pub cleartext_warning: Option<String>,
}

/// Validate a custom provider base URL.
///
/// Returns the trimmed URL and a warning for allowed cleartext HTTP. Public
/// cleartext HTTP, non-HTTP schemes, credentials, query strings, and fragments
/// are rejected before any write.
pub fn validate_base_url(value: &str) -> Result<ValidatedBaseUrl, ProviderManagementError> {
    let trimmed = value.trim();

    if trimmed.is_empty() {
        return Err(ProviderManagementError::InvalidInput(
            "Enter a base URL, for example https://api.example.com/v1.".to_owned(),
        ));
    }

    let url = join_url(trimmed, "").map_err(map_url_error)?;

    if !url.username().is_empty() || url.password().is_some() {
        return Err(ProviderManagementError::InvalidInput(
            "Base URL must not include credentials. Store the key with the auth style instead."
                .to_owned(),
        ));
    }

    if url.query().is_some() {
        return Err(ProviderManagementError::InvalidInput(
            "Base URL must not include a query string. Store the key with the auth style instead."
                .to_owned(),
        ));
    }

    if url.fragment().is_some() {
        return Err(ProviderManagementError::InvalidInput(
            "Base URL must not include a fragment.".to_owned(),
        ));
    }

    let policy = classify_url(&url).map_err(map_url_error)?;

    Ok(ValidatedBaseUrl {
        url: trimmed.to_owned(),
        cleartext_warning: policy.warning().map(str::to_owned),
    })
}

fn map_url_error(error: UrlPolicyError) -> ProviderManagementError {
    match error {
        UrlPolicyError::UnsupportedScheme(_) => ProviderManagementError::InvalidInput(
            "Base URL must start with http:// or https://.".to_owned(),
        ),
        UrlPolicyError::CleartextNotAllowed { host } => {
            ProviderManagementError::InvalidInput(format!(
                "Cleartext HTTP is only allowed for localhost or private-network hosts, not {host}. Use HTTPS."
            ))
        }
        UrlPolicyError::KeyInQuery { parameter } => ProviderManagementError::InvalidInput(format!(
            "Base URL must not include API keys or secrets in the query string (found {parameter})."
        )),
        UrlPolicyError::MissingHost
        | UrlPolicyError::InvalidBaseUrl(_)
        | UrlPolicyError::Join(_) => ProviderManagementError::InvalidInput(
            "Enter a valid http(s) base URL, for example https://api.example.com/v1.".to_owned(),
        ),
    }
}

/// Validate the authentication style for a custom provider.
pub fn validate_auth_type(
    value: ProviderAuthType,
) -> Result<ProviderAuthType, ProviderManagementError> {
    match value {
        ProviderAuthType::Bearer | ProviderAuthType::XApiKey | ProviderAuthType::None => Ok(value),
        ProviderAuthType::QueryKey => Err(ProviderManagementError::InvalidInput(
            "Query-string API keys are not supported. Choose bearer or x-api-key.".to_owned(),
        )),
    }
}

/// Validate the key for the selected authentication style.
///
/// `require_key` is true when creating a provider and false when editing one,
/// where an empty field means "keep the existing key". A key supplied for the
/// `none` style is rejected so it cannot be stored without being used.
pub fn validate_api_key(
    auth_type: ProviderAuthType,
    key: &SecretValue,
    require_key: bool,
) -> Result<Option<SecretValue>, ProviderManagementError> {
    let trimmed = key.expose().trim();

    match auth_type {
        ProviderAuthType::None => {
            if !trimmed.is_empty() {
                return Err(ProviderManagementError::InvalidInput(
                    "This authentication style does not use an API key. Remove the key or choose bearer or x-api-key."
                        .to_owned(),
                ));
            }
            Ok(None)
        }
        ProviderAuthType::QueryKey => Err(ProviderManagementError::InvalidInput(
            "Query-string API keys are not supported. Choose bearer or x-api-key.".to_owned(),
        )),
        ProviderAuthType::Bearer | ProviderAuthType::XApiKey => {
            if trimmed.is_empty() {
                if require_key {
                    return Err(ProviderManagementError::InvalidInput(
                        "Enter an API key for the selected authentication style.".to_owned(),
                    ));
                }
                return Ok(None);
            }
            Ok(Some(SecretValue::new(trimmed)))
        }
    }
}

/// Validate and normalize non-secret custom headers.
///
/// Authentication headers are rejected because the auth style owns them.
pub fn validate_headers(
    headers: &BTreeMap<String, String>,
    auth_type: ProviderAuthType,
) -> Result<BTreeMap<String, String>, ProviderManagementError> {
    let auth_header = match auth_type {
        ProviderAuthType::Bearer => Some("authorization"),
        ProviderAuthType::XApiKey => Some("x-api-key"),
        ProviderAuthType::QueryKey | ProviderAuthType::None => None,
    };

    let mut result = BTreeMap::new();

    for (raw_name, raw_value) in headers {
        let name = raw_name.trim();

        if name.is_empty() {
            return Err(ProviderManagementError::InvalidInput(
                "Header names must not be empty.".to_owned(),
            ));
        }

        if !is_valid_header_name(name) {
            return Err(ProviderManagementError::InvalidInput(format!(
                "Header \"{name}\" is not a valid HTTP header name."
            )));
        }

        let lower = name.to_ascii_lowercase();

        if FORBIDDEN_HEADER_NAMES.contains(&lower.as_str()) || auth_header == Some(lower.as_str()) {
            return Err(ProviderManagementError::InvalidInput(format!(
                "Header \"{name}\" is reserved for authentication. Remove it and use the auth style instead."
            )));
        }

        let value = raw_value.trim();

        if value.is_empty() {
            return Err(ProviderManagementError::InvalidInput(format!(
                "Header \"{name}\" must have a value."
            )));
        }

        if value.contains('\r') || value.contains('\n') {
            return Err(ProviderManagementError::InvalidInput(format!(
                "Header \"{name}\" must not contain line breaks."
            )));
        }

        result.insert(name.to_owned(), value.to_owned());
    }

    Ok(result)
}

fn is_valid_header_name(name: &str) -> bool {
    !name.is_empty()
        && name.bytes().all(|byte| {
            byte.is_ascii_alphanumeric()
                || matches!(
                    byte,
                    b'!' | b'#'
                        | b'$'
                        | b'%'
                        | b'&'
                        | b'\''
                        | b'*'
                        | b'+'
                        | b'-'
                        | b'.'
                        | b'^'
                        | b'_'
                        | b'`'
                        | b'|'
                        | b'~'
                )
        })
}

/// Parse the textarea form of non-secret headers.
///
/// Each non-empty line must use `Name: value`.
pub fn parse_headers_text(text: &str) -> Result<BTreeMap<String, String>, ProviderManagementError> {
    let mut headers = BTreeMap::new();

    for line in text.lines() {
        let trimmed = line.trim();

        if trimmed.is_empty() {
            continue;
        }

        let Some((name, value)) = trimmed.split_once(':') else {
            return Err(ProviderManagementError::InvalidInput(format!(
                "Header lines must use \"Name: value\". Invalid line: {trimmed}"
            )));
        };

        let name = name.trim();

        if name.is_empty() {
            return Err(ProviderManagementError::InvalidInput(format!(
                "Header lines must use \"Name: value\". Invalid line: {trimmed}"
            )));
        }

        headers.insert(name.to_owned(), value.trim().to_owned());
    }

    Ok(headers)
}

/// Render non-secret headers for the edit form.
pub fn format_headers_text(headers: &BTreeMap<String, String>) -> String {
    headers
        .iter()
        .map(|(name, value)| format!("{name}: {value}"))
        .collect::<Vec<_>>()
        .join("\n")
}

#[cfg(test)]
mod tests {
    use super::*;

    fn headers(pairs: &[(&str, &str)]) -> BTreeMap<String, String> {
        pairs
            .iter()
            .map(|(name, value)| ((*name).to_owned(), (*value).to_owned()))
            .collect()
    }

    #[test]
    fn https_url_is_allowed_without_a_warning() {
        let validated = validate_base_url("https://api.example.com/v1").expect("valid URL");

        assert_eq!(validated.url, "https://api.example.com/v1");
        assert_eq!(validated.cleartext_warning, None);
    }

    #[test]
    fn localhost_cleartext_is_allowed_with_a_warning() {
        for url in [
            "http://localhost:11434/v1",
            "http://127.0.0.1:1234/v1",
            "http://192.168.1.10:8080/v1",
            "http://10.0.0.5/v1",
            "http://[::1]:8080/v1",
        ] {
            let validated = validate_base_url(url).expect("local cleartext is allowed");
            assert!(
                validated.cleartext_warning.is_some(),
                "{url} should carry a warning"
            );
        }
    }

    #[test]
    fn public_cleartext_is_rejected() {
        let error = validate_base_url("http://api.example.com/v1").expect_err("public HTTP");

        assert!(error.message().contains("Cleartext HTTP"));
        assert!(error.message().contains("Use HTTPS"));
    }

    #[test]
    fn non_http_schemes_are_rejected() {
        let error = validate_base_url("ftp://example.com/v1").expect_err("bad scheme");

        assert!(error.message().contains("http:// or https://"));
    }

    #[test]
    fn credentials_query_and_fragment_are_rejected() {
        for url in [
            "https://user:pass@example.com/v1",
            "https://example.com/v1?key=abc",
            "https://example.com/v1#fragment",
        ] {
            let error = validate_base_url(url).expect_err("invalid base URL");
            assert!(
                error.message().contains("must not include"),
                "{url}: {}",
                error.message()
            );
        }
    }

    #[test]
    fn query_key_is_rejected() {
        let error =
            validate_base_url("https://example.com/v1?api_key=abc").expect_err("key in query");

        assert!(error.message().contains("query string"));
    }

    #[test]
    fn query_key_auth_style_is_rejected() {
        let error = validate_auth_type(ProviderAuthType::QueryKey).expect_err("query key");

        assert!(error.message().contains("Query-string API keys"));
    }

    #[test]
    fn key_is_required_for_bearer_and_x_api_key() {
        let empty = SecretValue::new("");

        assert!(validate_api_key(ProviderAuthType::Bearer, &empty, true).is_err());
        assert!(validate_api_key(ProviderAuthType::XApiKey, &empty, true).is_err());
        assert!(validate_api_key(ProviderAuthType::Bearer, &empty, false).is_ok());
    }

    #[test]
    fn key_is_rejected_for_none_auth() {
        let key = SecretValue::new("sk-test-not-a-real-key");
        let error = validate_api_key(ProviderAuthType::None, &key, true).expect_err("no key");

        assert!(error.message().contains("does not use an API key"));
    }

    #[test]
    fn auth_headers_are_rejected() {
        for (auth_type, name) in [
            (ProviderAuthType::Bearer, "Authorization"),
            (ProviderAuthType::XApiKey, "x-api-key"),
            (ProviderAuthType::None, "Cookie"),
        ] {
            let error = validate_headers(&headers(&[(name, "value")]), auth_type)
                .expect_err("reserved header");
            assert!(error.message().contains("reserved for authentication"));
        }
    }

    #[test]
    fn invalid_header_names_and_values_are_rejected() {
        assert!(
            validate_headers(
                &headers(&[("Bad Header", "value")]),
                ProviderAuthType::Bearer
            )
            .is_err()
        );
        assert!(validate_headers(&headers(&[("X-Team", "")]), ProviderAuthType::Bearer).is_err());
        assert!(
            validate_headers(
                &headers(&[("X-Team", "line\nbreak")]),
                ProviderAuthType::Bearer
            )
            .is_err()
        );
    }

    #[test]
    fn non_secret_headers_are_trimmed_and_kept() {
        let validated = validate_headers(
            &headers(&[(" X-Team ", " chat "), ("X-Project", "demo")]),
            ProviderAuthType::Bearer,
        )
        .expect("valid headers");

        assert_eq!(validated.get("X-Team").map(String::as_str), Some("chat"));
        assert_eq!(validated.get("X-Project").map(String::as_str), Some("demo"));
    }

    #[test]
    fn header_text_round_trips() {
        let text = "X-Team: chat\nX-Project: demo";
        let parsed = parse_headers_text(text).expect("valid header text");

        assert_eq!(parsed.get("X-Team").map(String::as_str), Some("chat"));
        let formatted = format_headers_text(&parsed);
        let reparsed = parse_headers_text(&formatted).expect("formatted header text");
        assert_eq!(reparsed, parsed);
    }

    #[test]
    fn invalid_header_text_is_rejected() {
        let error = parse_headers_text("not a header").expect_err("invalid line");

        assert!(error.message().contains("Name: value"));
    }
}
