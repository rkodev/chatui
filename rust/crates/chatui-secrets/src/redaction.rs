//! Log redaction and the release logging policy.
//!
//! Secrets must not reach logs, panic reports, or diagnostics. This module
//! provides a [`Redactor`] that scrubs the headers and token shapes the
//! specification calls out, plus a [`RedactingFormat`] wrapper for
//! [`tracing_subscriber`] so redaction does not depend on every log call being
//! written carefully.
//!
//! # Redacted inputs
//!
//! - `Authorization`
//! - `x-api-key`
//! - `x-goog-api-key`
//! - `anthropic-dangerous-direct-browser-access`
//! - custom header names registered with [`Redactor::with_secret_header`]
//! - exact secret values registered with [`Redactor::with_secret_value`]
//! - bearer tokens
//! - API-key-shaped strings such as `sk-...`, `AIza...`, `gsk_...`, and
//!   `github_pat_...`
//! - sensitive query parameters such as `api_key=...` and `access_token=...`
//!
//! # Release logging policy
//!
//! [`init_logging`] installs a redacting subscriber. Debug builds use
//! [`LevelFilter::DEBUG`]; release builds use [`LevelFilter::WARN`]. Request and
//! response bodies are never logged by this crate, and the release filter keeps
//! verbose transport diagnostics out of production logs. There is no telemetry,
//! analytics, or remote logging.

use std::collections::BTreeSet;
use std::fmt;

use tracing::{Event, Subscriber};
use tracing_subscriber::filter::LevelFilter;
use tracing_subscriber::fmt::format::{Format, Writer};
use tracing_subscriber::fmt::{FmtContext, FormatEvent, FormatFields};
use tracing_subscriber::registry::LookupSpan;
use zeroize::Zeroizing;

/// Header names that always carry a secret value.
pub const SENSITIVE_HEADERS: &[&str] = &[
    "authorization",
    "x-api-key",
    "x-goog-api-key",
    "anthropic-dangerous-direct-browser-access",
];

/// Replacement text written in place of a secret.
pub const REDACTED: &str = "[REDACTED]";

/// Prefixes that identify API-key-shaped strings.
const TOKEN_PREFIXES: &[&str] = &[
    "sk-",
    "sk_",
    "AIza",
    "gsk_",
    "xai-",
    "hf_",
    "pk-",
    "rk-",
    "npm_",
    "ghp_",
    "gho_",
    "ghu_",
    "ghs_",
    "ghr_",
    "github_pat_",
    "glpat-",
    "AKIA",
    "ASIA",
    "ya29.",
    "eyJ",
];

/// Query parameter names that carry a secret value.
const SENSITIVE_QUERY_NAMES: &[&str] = &[
    "api_key",
    "apikey",
    "api-key",
    "key",
    "access_token",
    "token",
    "auth",
    "authorization",
    "x-api-key",
    "x-goog-api-key",
    "password",
    "passphrase",
    "secret",
];

/// A reusable redaction policy.
///
/// The default policy covers the headers and token shapes required by the
/// specification. Add application-specific header names or exact secret values
/// with the builder methods.
#[derive(Clone)]
pub struct Redactor {
    secret_headers: BTreeSet<String>,
    secret_values: Vec<Zeroizing<String>>,
}

impl Default for Redactor {
    fn default() -> Self {
        Self {
            secret_headers: SENSITIVE_HEADERS
                .iter()
                .map(|header| header.to_ascii_lowercase())
                .collect(),
            secret_values: Vec::new(),
        }
    }
}

impl fmt::Debug for Redactor {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter
            .debug_struct("Redactor")
            .field("secret_headers", &self.secret_headers)
            .field(
                "secret_values",
                &format_args!("[REDACTED; {} value(s)]", self.secret_values.len()),
            )
            .finish()
    }
}

impl Redactor {
    /// Create a redactor with the default policy.
    pub fn new() -> Self {
        Self::default()
    }

    /// Register an additional header name whose value must be redacted.
    ///
    /// Header names are matched case-insensitively.
    pub fn with_secret_header(mut self, name: impl AsRef<str>) -> Self {
        let name = normalize_header_name(name.as_ref());
        if !name.is_empty() {
            self.secret_headers.insert(name);
        }
        self
    }

    /// Register an exact secret value that must be redacted wherever it
    /// appears.
    ///
    /// The value is held in a zeroizing buffer and is never printed by
    /// `Debug`.
    pub fn with_secret_value(mut self, value: impl Into<String>) -> Self {
        let value = value.into();
        if !value.is_empty() {
            self.secret_values.push(Zeroizing::new(value));
        }
        self
    }

    /// Return whether `name` is a registered secret header.
    pub fn is_secret_header(&self, name: &str) -> bool {
        self.secret_headers.contains(&normalize_header_name(name))
    }

    /// Redact every known secret in `input`.
    ///
    /// The function is intentionally conservative: it may redact a
    /// token-shaped string that is not a real credential, but it must not leave
    /// a known secret in place.
    pub fn redact(&self, input: &str) -> String {
        let mut text = input.to_owned();
        for secret in &self.secret_values {
            if !secret.is_empty() {
                text = text.replace(secret.as_str(), REDACTED);
            }
        }

        let mut output = String::with_capacity(text.len());
        for line in text.split_inclusive('\n') {
            output.push_str(&self.redact_line(line));
        }
        output
    }

    fn redact_line(&self, line: &str) -> String {
        let (body, ending) = split_line_ending(line);
        let mut body = self.redact_header_line(body);
        body = self.redact_query_parameters(&body);
        body = self.redact_bearer_tokens(&body);
        body = self.redact_token_shapes(&body);
        format!("{body}{ending}")
    }

    fn redact_header_line(&self, line: &str) -> String {
        let Some((name, separator_index, separator_len)) = find_header_separator(line) else {
            return line.to_owned();
        };
        if !self.is_secret_header(name) {
            return line.to_owned();
        }

        let value_start = separator_index + separator_len;
        let prefix = &line[..value_start];
        let value = &line[value_start..];
        let leading_whitespace_len =
            value.len() - value.trim_start_matches(is_horizontal_space).len();
        let leading_whitespace = &value[..leading_whitespace_len];
        let value = &value[leading_whitespace_len..];
        format!("{prefix}{leading_whitespace}{}", redact_header_value(value))
    }

    fn redact_query_parameters(&self, line: &str) -> String {
        let mut output = String::with_capacity(line.len());
        let mut cursor = 0;

        while let Some(relative) = line[cursor..].find(['?', '&']) {
            let separator_index = cursor + relative;
            output.push_str(&line[cursor..=separator_index]);

            let name_start = separator_index + 1;
            let Some(equals_relative) = line[name_start..].find('=') else {
                output.push_str(&line[name_start..]);
                return output;
            };
            let equals_index = name_start + equals_relative;
            let name = &line[name_start..equals_index];
            output.push_str(name);
            output.push('=');

            if is_sensitive_query_name(name) {
                let value_start = equals_index + 1;
                let value_end = line[value_start..]
                    .find(|character: char| {
                        matches!(character, '&' | '#' | ' ' | '\t' | '\n' | '\r' | '"' | '\'')
                    })
                    .map(|relative| value_start + relative)
                    .unwrap_or(line.len());
                output.push_str(REDACTED);
                cursor = value_end;
            } else {
                cursor = equals_index + 1;
            }
        }

        output.push_str(&line[cursor..]);
        output
    }

    fn redact_bearer_tokens(&self, line: &str) -> String {
        let lowercase = line.to_ascii_lowercase();
        let mut output = String::with_capacity(line.len());
        let mut cursor = 0;

        while let Some(relative) = lowercase[cursor..].find("bearer") {
            let start = cursor + relative;
            if start > 0 && previous_char(line, start).is_some_and(is_token_char) {
                cursor = start + "bearer".len();
                continue;
            }

            let after = start + "bearer".len();
            let rest = &line[after..];
            let whitespace_len = rest.len() - rest.trim_start_matches(char::is_whitespace).len();
            if whitespace_len == 0 {
                cursor = after;
                continue;
            }

            let token_start = after + whitespace_len;
            let token_end = line[token_start..]
                .find(|character: char| !is_token_char(character))
                .map(|relative| token_start + relative)
                .unwrap_or(line.len());

            output.push_str(&line[cursor..token_start]);
            output.push_str(REDACTED);
            cursor = token_end;
        }

        output.push_str(&line[cursor..]);
        output
    }

    fn redact_token_shapes(&self, line: &str) -> String {
        let mut output = String::with_capacity(line.len());
        let mut cursor = 0;

        while cursor < line.len() {
            let matched = TOKEN_PREFIXES.iter().find(|prefix| {
                line[cursor..].starts_with(*prefix)
                    && (cursor == 0
                        || previous_char(line, cursor)
                            .is_none_or(|character| !is_token_char(character)))
            });

            if matched.is_some() {
                let token_start = cursor;
                let token_end = line[token_start..]
                    .find(|character: char| !is_token_char(character))
                    .map(|relative| token_start + relative)
                    .unwrap_or(line.len());
                output.push_str(REDACTED);
                cursor = token_end;
            } else {
                let character = line[cursor..]
                    .chars()
                    .next()
                    .expect("cursor is inside the string");
                output.push(character);
                cursor += character.len_utf8();
            }
        }

        output
    }
}

/// A [`FormatEvent`] wrapper that redacts the fully formatted event.
///
/// Formatting first and redacting second means a secret cannot escape through
/// a field, a message, or a span attribute that the log call did not anticipate.
pub struct RedactingFormat<F> {
    inner: F,
    redactor: Redactor,
}

impl<F> RedactingFormat<F> {
    /// Wrap `inner` with the default redaction policy.
    pub fn new(inner: F) -> Self {
        Self {
            inner,
            redactor: Redactor::default(),
        }
    }

    /// Wrap `inner` with an explicit redaction policy.
    pub fn with_redactor(inner: F, redactor: Redactor) -> Self {
        Self { inner, redactor }
    }
}

impl<S, N, F> FormatEvent<S, N> for RedactingFormat<F>
where
    S: Subscriber + for<'lookup> LookupSpan<'lookup>,
    N: for<'writer> FormatFields<'writer> + 'static,
    F: FormatEvent<S, N>,
{
    fn format_event(
        &self,
        context: &FmtContext<'_, S, N>,
        mut writer: Writer<'_>,
        event: &Event<'_>,
    ) -> fmt::Result {
        let mut buffer = String::new();
        self.inner
            .format_event(context, Writer::new(&mut buffer), event)?;
        writer.write_str(&self.redactor.redact(&buffer))
    }
}

/// Return the maximum log level for a build.
///
/// Debug builds may emit `DEBUG` diagnostics. Release builds are limited to
/// `WARN` so verbose request and response diagnostics are not configured in
/// production.
pub fn filter_for(debug_build: bool) -> LevelFilter {
    if debug_build {
        LevelFilter::DEBUG
    } else {
        LevelFilter::WARN
    }
}

/// Return the maximum log level for the current build.
pub fn logging_filter() -> LevelFilter {
    filter_for(cfg!(debug_assertions))
}

/// Install the redacting global subscriber.
///
/// The subscriber is installed once per process. A second call returns the
/// error from [`tracing::subscriber::set_global_default`].
pub fn init_logging() -> Result<(), tracing::subscriber::SetGlobalDefaultError> {
    let subscriber = tracing_subscriber::fmt::Subscriber::builder()
        .with_max_level(logging_filter())
        .event_format(RedactingFormat::new(Format::default()))
        .finish();
    tracing::subscriber::set_global_default(subscriber)
}

fn split_line_ending(line: &str) -> (&str, &str) {
    if let Some(body) = line.strip_suffix("\r\n") {
        (body, "\r\n")
    } else if let Some(body) = line.strip_suffix('\n') {
        (body, "\n")
    } else {
        (line, "")
    }
}

fn find_header_separator(line: &str) -> Option<(&str, usize, usize)> {
    let bytes = line.as_bytes();
    for (index, byte) in bytes.iter().enumerate() {
        if *byte == b':' {
            if bytes.get(index + 1) == Some(&b'/') && bytes.get(index + 2) == Some(&b'/') {
                continue;
            }
            let name = &line[..index];
            if is_header_name(name) {
                return Some((name, index, 1));
            }
        } else if *byte == b'=' {
            let name = &line[..index];
            if is_header_name(name) {
                return Some((name, index, 1));
            }
        }
    }
    None
}

fn is_header_name(name: &str) -> bool {
    let name = normalize_header_name(name);
    !name.is_empty()
        && name.chars().all(|character| {
            character.is_ascii_alphanumeric() || matches!(character, '-' | '_' | '.')
        })
}

fn normalize_header_name(name: &str) -> String {
    name.trim()
        .trim_start_matches(['{', '[', ',', ' ', '\t'])
        .trim_matches(['"', '\'', ' ', '\t'])
        .to_ascii_lowercase()
}

fn redact_header_value(value: &str) -> String {
    if value.is_empty() {
        return String::new();
    }

    if let Some(rest) = value.strip_prefix('"')
        && let Some(end) = rest.find('"')
    {
        return format!("\"{REDACTED}\"{}", &rest[end..]);
    }
    if let Some(rest) = value.strip_prefix('\'')
        && let Some(end) = rest.find('\'')
    {
        return format!("'{REDACTED}'{}", &rest[end..]);
    }

    REDACTED.to_owned()
}

fn is_sensitive_query_name(name: &str) -> bool {
    let name = name.trim().to_ascii_lowercase();
    SENSITIVE_QUERY_NAMES.contains(&name.as_str())
}

fn is_horizontal_space(character: char) -> bool {
    matches!(character, ' ' | '\t')
}

fn is_token_char(character: char) -> bool {
    character.is_ascii_alphanumeric() || matches!(character, '-' | '_' | '.' | '~' | '+' | '/')
}

fn previous_char(value: &str, index: usize) -> Option<char> {
    value[..index].chars().next_back()
}

#[cfg(test)]
mod tests {
    use std::sync::{Arc, Mutex};

    use super::*;

    const SENTINEL: &str = "sk-test-not-a-real-key";

    #[test]
    fn redacts_authorization_header() {
        let redactor = Redactor::default();
        let rendered = redactor.redact(&format!("Authorization: Bearer {SENTINEL}"));
        assert_eq!(rendered, "Authorization: [REDACTED]");
        assert!(!rendered.contains(SENTINEL));
    }

    #[test]
    fn redacts_required_api_key_headers() {
        let redactor = Redactor::default();
        for header in [
            "x-api-key",
            "x-goog-api-key",
            "anthropic-dangerous-direct-browser-access",
        ] {
            let rendered = redactor.redact(&format!("{header}: {SENTINEL}"));
            assert_eq!(rendered, format!("{header}: [REDACTED]"));
            assert!(!rendered.contains(SENTINEL));
        }
    }

    #[test]
    fn redacts_custom_secret_header() {
        let redactor = Redactor::default().with_secret_header("x-custom-secret");
        let rendered = redactor.redact(&format!("x-custom-secret: {SENTINEL}"));
        assert_eq!(rendered, "x-custom-secret: [REDACTED]");
        assert!(!rendered.contains(SENTINEL));
    }

    #[test]
    fn redacts_json_style_header() {
        let redactor = Redactor::default();
        let rendered = redactor.redact(&format!(
            "{{\"authorization\":\"Bearer {SENTINEL}\",\"other\":\"kept\"}}"
        ));
        assert!(!rendered.contains(SENTINEL));
        assert!(rendered.contains("\"other\":\"kept\""));
    }

    #[test]
    fn redacts_bearer_tokens_anywhere() {
        let redactor = Redactor::default();
        let rendered = redactor.redact(&format!("request used Bearer {SENTINEL} for auth"));
        assert_eq!(rendered, "request used Bearer [REDACTED] for auth");
        assert!(!rendered.contains(SENTINEL));
    }

    #[test]
    fn redacts_api_key_shaped_strings() {
        let redactor = Redactor::default();
        for value in [
            "sk-test-not-a-real-key",
            "sk-ant-test-not-a-real-key",
            "AIzaTestNotARealKey",
            "gsk_test_not_a_real_key",
            "xai-test-not-a-real-key",
            "github_pat_test_not_a_real_key",
            "AKIATESTNOTAREALKEY",
            "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ0ZXN0In0.signature",
        ] {
            let rendered = redactor.redact(&format!("value={value}"));
            assert!(
                !rendered.contains(value),
                "{value} was not redacted: {rendered}"
            );
            assert!(rendered.contains(REDACTED));
        }
    }

    #[test]
    fn redacts_sensitive_query_parameters() {
        let redactor = Redactor::default();
        let rendered = redactor.redact(&format!(
            "GET https://example.test/v1?api_key={SENTINEL}&model=test HTTP/1.1"
        ));
        assert!(!rendered.contains(SENTINEL));
        assert!(rendered.contains("api_key=[REDACTED]"));
        assert!(rendered.contains("model=test"));
    }

    #[test]
    fn redacts_registered_secret_values() {
        let redactor = Redactor::default().with_secret_value("plain-sentinel-value");
        let rendered = redactor.redact("the value plain-sentinel-value appears here");
        assert_eq!(rendered, "the value [REDACTED] appears here");
    }

    #[test]
    fn preserves_ordinary_text() {
        let redactor = Redactor::default();
        let text = "model=gpt-test status=200 task-complete";
        assert_eq!(redactor.redact(text), text);
    }

    #[test]
    fn preserves_line_endings() {
        let redactor = Redactor::default();
        let rendered = redactor.redact(&format!("Authorization: Bearer {SENTINEL}\r\nnext line\n"));
        assert_eq!(rendered, "Authorization: [REDACTED]\r\nnext line\n");
    }

    #[test]
    fn redactor_debug_does_not_print_registered_values() {
        let redactor = Redactor::default().with_secret_value(SENTINEL);
        let rendered = format!("{redactor:?}");
        assert!(!rendered.contains(SENTINEL));
        assert!(rendered.contains("REDACTED"));
    }

    #[test]
    fn release_filter_is_not_verbose() {
        assert_eq!(filter_for(false), LevelFilter::WARN);
        assert_eq!(filter_for(true), LevelFilter::DEBUG);
    }

    #[test]
    fn redacting_format_redacts_event_fields() {
        let buffer = Arc::new(Mutex::new(Vec::new()));
        let writer = SharedWriter(buffer.clone());
        let subscriber = tracing_subscriber::fmt::Subscriber::builder()
            .with_max_level(LevelFilter::DEBUG)
            .with_writer(move || writer.clone())
            .event_format(RedactingFormat::new(Format::default()))
            .finish();

        tracing::subscriber::with_default(subscriber, || {
            tracing::info!(
                authorization = %format!("Bearer {SENTINEL}"),
                "request"
            );
        });

        let output = String::from_utf8(buffer.lock().expect("buffer lock").clone())
            .expect("utf-8 log output");
        assert!(
            !output.contains(SENTINEL),
            "log output leaked the sentinel: {output}"
        );
        assert!(output.contains(REDACTED));
    }

    #[derive(Clone)]
    struct SharedWriter(Arc<Mutex<Vec<u8>>>);

    impl std::io::Write for SharedWriter {
        fn write(&mut self, bytes: &[u8]) -> std::io::Result<usize> {
            self.0.lock().expect("buffer lock").extend_from_slice(bytes);
            Ok(bytes.len())
        }

        fn flush(&mut self) -> std::io::Result<()> {
            Ok(())
        }
    }
}
