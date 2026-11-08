//! URL policy for provider endpoints.
//!
//! HTTPS is the default. Cleartext HTTP is allowed only for loopback,
//! `localhost`, `.local`, and private-range hosts, and always carries an
//! explicit warning that the UI can surface. API keys are rejected in query
//! parameters so they cannot leak into logs, history, or redirects.

use std::net::{Ipv4Addr, Ipv6Addr};

use reqwest::Url;
use thiserror::Error;
use url::Host;

/// Warning attached to every allowed cleartext URL.
pub const CLEARTEXT_WARNING: &str = "This provider uses unencrypted HTTP. Traffic can be read or modified on your network. Use HTTPS unless the provider is on localhost or a private network.";

/// The result of applying the URL policy to a provider endpoint.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct UrlPolicy {
    url: Url,
    cleartext: bool,
    warning: Option<String>,
}

impl UrlPolicy {
    /// Return the validated URL.
    pub fn url(&self) -> &Url {
        &self.url
    }

    /// Consume the policy and return the validated URL.
    pub fn into_url(self) -> Url {
        self.url
    }

    /// Return whether the URL uses cleartext HTTP.
    pub fn is_cleartext(&self) -> bool {
        self.cleartext
    }

    /// Return the warning to surface for an allowed cleartext URL.
    pub fn warning(&self) -> Option<&str> {
        self.warning.as_deref()
    }
}

/// A URL policy violation.
#[derive(Debug, Clone, PartialEq, Eq, Error)]
pub enum UrlPolicyError {
    /// The URL has no host.
    #[error("URL has no host")]
    MissingHost,
    /// The URL uses a scheme other than HTTP or HTTPS.
    #[error("unsupported URL scheme: {0}")]
    UnsupportedScheme(String),
    /// Cleartext HTTP was requested for a public host.
    #[error("cleartext HTTP is only allowed for localhost or private-range hosts, not {host}")]
    CleartextNotAllowed {
        /// The rejected host.
        host: String,
    },
    /// A query parameter looks like an API key or other secret.
    #[error("API keys must not be sent in query parameters (found {parameter})")]
    KeyInQuery {
        /// The rejected query parameter name.
        parameter: String,
    },
    /// The base URL could not be parsed.
    #[error("invalid base URL: {0}")]
    InvalidBaseUrl(String),
    /// The base URL and path could not be joined safely.
    #[error("could not join URL: {0}")]
    Join(String),
}

/// Classify a provider URL against the shared policy.
///
/// HTTPS is always allowed. Cleartext HTTP is allowed only for local or
/// private hosts and returns a warning. Any URL carrying a key-like query
/// parameter is rejected.
pub fn classify_url(url: &Url) -> Result<UrlPolicy, UrlPolicyError> {
    ensure_no_key_in_query(url)?;

    match url.scheme() {
        "https" => Ok(UrlPolicy {
            url: url.clone(),
            cleartext: false,
            warning: None,
        }),
        "http" => {
            let host = url.host().ok_or(UrlPolicyError::MissingHost)?;
            if is_local_or_private_host(&host) {
                Ok(UrlPolicy {
                    url: url.clone(),
                    cleartext: true,
                    warning: Some(CLEARTEXT_WARNING.to_owned()),
                })
            } else {
                Err(UrlPolicyError::CleartextNotAllowed {
                    host: host.to_string(),
                })
            }
        }
        other => Err(UrlPolicyError::UnsupportedScheme(other.to_owned())),
    }
}

/// Reject any query parameter that looks like an API key or other secret.
pub fn ensure_no_key_in_query(url: &Url) -> Result<(), UrlPolicyError> {
    for (name, value) in url.query_pairs() {
        if is_sensitive_query_name(&name) || looks_like_secret_value(&value) {
            return Err(UrlPolicyError::KeyInQuery {
                parameter: name.into_owned(),
            });
        }
    }
    Ok(())
}

/// Join a provider base URL and a request path safely.
///
/// - A full absolute URL in `path` replaces the base.
/// - A path starting with `/` is resolved from the base origin, which handles
///   discovery endpoints such as Ollama's `/api/tags`.
/// - A relative path is appended under the base path, so
///   `https://api.example.com/v1` plus `chat/completions` becomes
///   `https://api.example.com/v1/chat/completions`.
/// - Protocol-relative paths (`//host/path`) are rejected.
/// - The joined URL is checked for key-like query parameters.
pub fn join_url(base: &str, path: &str) -> Result<Url, UrlPolicyError> {
    let base =
        Url::parse(base).map_err(|error| UrlPolicyError::InvalidBaseUrl(error.to_string()))?;
    if !base.has_host() {
        return Err(UrlPolicyError::InvalidBaseUrl(
            "base URL has no host".to_owned(),
        ));
    }
    if path.starts_with("//") {
        return Err(UrlPolicyError::Join(
            "protocol-relative URLs are not allowed".to_owned(),
        ));
    }

    let joined = if path.is_empty() {
        base
    } else if let Ok(absolute) = Url::parse(path) {
        if !absolute.has_host() {
            return Err(UrlPolicyError::Join("absolute URL has no host".to_owned()));
        }
        absolute
    } else if path.starts_with('/') || path.starts_with('?') || path.starts_with('#') {
        base.join(path)
            .map_err(|error| UrlPolicyError::Join(error.to_string()))?
    } else {
        let mut base = base;
        if !base.path().ends_with('/') {
            let path = format!("{}/", base.path());
            base.set_path(&path);
        }
        base.join(path)
            .map_err(|error| UrlPolicyError::Join(error.to_string()))?
    };

    ensure_no_key_in_query(&joined)?;
    Ok(joined)
}

/// Join a base URL and path, then classify the result.
pub fn join_and_classify(base: &str, path: &str) -> Result<UrlPolicy, UrlPolicyError> {
    let url = join_url(base, path)?;
    classify_url(&url)
}

fn is_local_or_private_host(host: &Host<&str>) -> bool {
    match host {
        Host::Domain(domain) => is_local_domain(domain),
        Host::Ipv4(ip) => is_private_ipv4(*ip),
        Host::Ipv6(ip) => is_private_ipv6(*ip),
    }
}

fn is_local_domain(domain: &str) -> bool {
    let domain = domain.trim_end_matches('.').to_ascii_lowercase();
    domain == "localhost" || domain.ends_with(".localhost") || domain.ends_with(".local")
}

fn is_private_ipv4(ip: Ipv4Addr) -> bool {
    let octets = ip.octets();
    ip.is_loopback()
        || ip.is_private()
        || ip.is_link_local()
        || ip.is_unspecified()
        || (octets[0] == 100 && (octets[1] & 0b1100_0000) == 64)
}

fn is_private_ipv6(ip: Ipv6Addr) -> bool {
    if let Some(mapped) = ip.to_ipv4_mapped() {
        return is_private_ipv4(mapped);
    }

    let segments = ip.segments();
    ip.is_loopback()
        || ip.is_unspecified()
        || (segments[0] & 0xfe00) == 0xfc00
        || (segments[0] & 0xffc0) == 0xfe80
}

fn is_sensitive_query_name(name: &str) -> bool {
    let normalized: String = name
        .chars()
        .filter(|character| character.is_ascii_alphanumeric())
        .map(|character| character.to_ascii_lowercase())
        .collect();

    const EXACT: &[&str] = &[
        "key",
        "apikey",
        "apikeys",
        "accesskey",
        "secretkey",
        "privatekey",
        "xapikey",
        "xgoogapikey",
        "googapikey",
        "openrouterkey",
        "token",
        "accesstoken",
        "authtoken",
        "bearertoken",
        "refreshtoken",
        "idtoken",
        "sessiontoken",
        "secret",
        "clientsecret",
        "password",
        "passwd",
        "pwd",
        "auth",
        "authorization",
        "credential",
        "credentials",
        "sig",
        "signature",
        "hmac",
    ];
    const CONTAINS: &[&str] = &[
        "apikey",
        "accesskey",
        "secretkey",
        "privatekey",
        "clientsecret",
        "password",
        "authorization",
    ];

    EXACT.contains(&normalized.as_str())
        || CONTAINS.iter().any(|marker| normalized.contains(marker))
}

fn looks_like_secret_value(value: &str) -> bool {
    let value = value.trim();
    const PREFIXES: &[&str] = &[
        "sk-", "sk_", "sk-or-", "AIza", "gsk_", "xai-", "hf_", "Bearer ", "Basic ",
    ];
    PREFIXES.iter().any(|prefix| value.starts_with(prefix))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn parse(value: &str) -> Url {
        Url::parse(value).expect("valid test URL")
    }

    #[test]
    fn https_is_allowed_without_warning() {
        let policy = classify_url(&parse("https://api.example.com/v1")).expect("allowed");
        assert!(!policy.is_cleartext());
        assert!(policy.warning().is_none());
        assert_eq!(policy.url().as_str(), "https://api.example.com/v1");
    }

    #[test]
    fn cleartext_localhost_is_allowed_with_warning() {
        for value in [
            "http://localhost:11434/api/tags",
            "http://127.0.0.1:8080/",
            "http://[::1]:8080/",
            "http://my-box.local/",
            "http://app.localhost/",
        ] {
            let policy = classify_url(&parse(value)).expect(value);
            assert!(policy.is_cleartext(), "{value}");
            assert_eq!(policy.warning(), Some(CLEARTEXT_WARNING), "{value}");
        }
    }

    #[test]
    fn cleartext_private_ranges_are_allowed() {
        for value in [
            "http://10.0.0.5/",
            "http://172.16.4.2/",
            "http://192.168.1.10/",
            "http://169.254.1.1/",
            "http://100.64.0.1/",
            "http://[fd00::1]/",
            "http://[fe80::1]/",
            "http://[::ffff:192.168.1.10]/",
        ] {
            let policy = classify_url(&parse(value)).expect(value);
            assert!(policy.is_cleartext(), "{value}");
            assert!(policy.warning().is_some(), "{value}");
        }
    }

    #[test]
    fn cleartext_public_host_is_rejected() {
        let error = classify_url(&parse("http://api.example.com/v1")).expect_err("rejected");
        assert!(matches!(error, UrlPolicyError::CleartextNotAllowed { .. }));
    }

    #[test]
    fn unsupported_schemes_are_rejected() {
        let error = classify_url(&parse("ftp://example.com/file")).expect_err("rejected");
        assert!(matches!(error, UrlPolicyError::UnsupportedScheme(_)));
    }

    #[test]
    fn rejects_keys_in_query_parameters() {
        for value in [
            "https://api.example.com/v1?api_key=abc",
            "https://api.example.com/v1?api-key=abc",
            "https://api.example.com/v1?key=abc",
            "https://api.example.com/v1?x-goog-api-key=abc",
            "https://api.example.com/v1?access_token=abc",
            "https://api.example.com/v1?client_secret=abc",
            "https://api.example.com/v1?Authorization=abc",
            "https://api.example.com/v1?foo=sk-or-v1-abc",
            "https://api.example.com/v1?foo=AIzaSyExample",
        ] {
            let error = classify_url(&parse(value)).expect_err(value);
            assert!(
                matches!(error, UrlPolicyError::KeyInQuery { .. }),
                "{value}"
            );
        }
    }

    #[test]
    fn allows_non_secret_query_parameters() {
        let policy =
            classify_url(&parse("https://api.example.com/v1?page=2&limit=10")).expect("allowed");
        assert!(!policy.is_cleartext());
    }

    #[test]
    fn joins_relative_paths_under_base_path() {
        assert_eq!(
            join_url("https://api.openai.com/v1", "chat/completions")
                .expect("joined")
                .as_str(),
            "https://api.openai.com/v1/chat/completions"
        );
        assert_eq!(
            join_url("https://api.openai.com/v1/", "chat/completions")
                .expect("joined")
                .as_str(),
            "https://api.openai.com/v1/chat/completions"
        );
        assert_eq!(
            join_url("https://api.openai.com", "v1/chat/completions")
                .expect("joined")
                .as_str(),
            "https://api.openai.com/v1/chat/completions"
        );
    }

    #[test]
    fn joins_absolute_paths_from_origin() {
        assert_eq!(
            join_url("http://localhost:11434", "/api/tags")
                .expect("joined")
                .as_str(),
            "http://localhost:11434/api/tags"
        );
    }

    #[test]
    fn joins_absolute_urls() {
        assert_eq!(
            join_url(
                "https://api.example.com/v1",
                "https://other.example.com/models"
            )
            .expect("joined")
            .as_str(),
            "https://other.example.com/models"
        );
    }

    #[test]
    fn rejects_protocol_relative_paths() {
        let error = join_url("https://api.example.com/v1", "//evil.example.com/path")
            .expect_err("rejected");
        assert!(matches!(error, UrlPolicyError::Join(_)));
    }

    #[test]
    fn join_rejects_keys_in_query() {
        let error =
            join_url("https://api.example.com/v1", "chat?api_key=abc").expect_err("rejected");
        assert!(matches!(error, UrlPolicyError::KeyInQuery { .. }));
    }

    #[test]
    fn join_and_classify_applies_both_rules() {
        let policy = join_and_classify("http://localhost:11434", "/api/tags").expect("allowed");
        assert!(policy.is_cleartext());
        assert!(policy.warning().is_some());

        let error = join_and_classify("http://api.example.com", "/v1").expect_err("rejected");
        assert!(matches!(error, UrlPolicyError::CleartextNotAllowed { .. }));
    }

    #[test]
    fn rejects_base_urls_without_hosts() {
        let error = join_url("mailto:someone@example.com", "path").expect_err("rejected");
        assert!(matches!(error, UrlPolicyError::InvalidBaseUrl(_)));
    }
}
