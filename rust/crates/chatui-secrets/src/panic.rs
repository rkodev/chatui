//! Panic hook that keeps secrets out of panic reports.
//!
//! A panic payload can contain a formatted request, a header value, or a
//! provider key. The hook in this module redacts the payload with the same
//! policy as the logging layer, truncates it to a bounded length, and keeps the
//! source location for diagnostics. It never prints request or response bodies
//! on its own.
//!
//! Install it once during application startup:
//!
//! ```no_run
//! chatui_secrets::panic::install_panic_hook();
//! ```

use std::io::{self, Write};
use std::panic::PanicHookInfo;
use std::sync::Arc;

use crate::redaction::Redactor;

/// Maximum number of characters kept from a panic payload.
pub const MAX_PANIC_PAYLOAD_CHARS: usize = 512;

/// Install the default redacting panic hook.
///
/// The hook writes to standard error. It replaces any previously installed
/// hook.
pub fn install_panic_hook() {
    install_panic_hook_with(Redactor::default());
}

/// Install a redacting panic hook with an explicit redaction policy.
pub fn install_panic_hook_with(redactor: Redactor) {
    let redactor = Arc::new(redactor);
    std::panic::set_hook(Box::new(move |info| {
        let payload = panic_payload(info);
        let mut stderr = io::stderr().lock();
        let _ = report_panic_message(&payload, info.location(), &redactor, &mut stderr);
    }));
}

/// Install a redacting panic hook that sends the formatted message to
/// `reporter`.
///
/// This is useful for applications that route panic reports through their own
/// diagnostics channel. The reporter receives only the redacted, truncated
/// message.
pub fn install_panic_hook_with_reporter<F>(redactor: Redactor, reporter: F)
where
    F: Fn(&str) + Send + Sync + 'static,
{
    let redactor = Arc::new(redactor);
    let reporter = Arc::new(reporter);
    std::panic::set_hook(Box::new(move |info| {
        let payload = panic_payload(info);
        let message = format_panic_message(&payload, info.location(), &redactor);
        reporter(&message);
    }));
}

/// Format a panic payload for a report.
///
/// The payload is redacted and truncated. The location is included when it is
/// available.
pub fn format_panic_message(
    payload: &str,
    location: Option<&std::panic::Location<'_>>,
    redactor: &Redactor,
) -> String {
    let location = location
        .map(|location| {
            format!(
                "{}:{}:{}",
                location.file(),
                location.line(),
                location.column()
            )
        })
        .unwrap_or_else(|| "unknown location".to_owned());
    let payload = redactor.redact(payload);
    let payload = truncate_chars(&payload, MAX_PANIC_PAYLOAD_CHARS);
    format!("chatui panic at {location}: {payload}")
}

fn panic_payload(info: &PanicHookInfo<'_>) -> String {
    let payload = info.payload();
    if let Some(message) = payload.downcast_ref::<&str>() {
        (*message).to_owned()
    } else if let Some(message) = payload.downcast_ref::<String>() {
        message.clone()
    } else {
        "non-string panic payload".to_owned()
    }
}

fn report_panic_message(
    payload: &str,
    location: Option<&std::panic::Location<'_>>,
    redactor: &Redactor,
    writer: &mut dyn Write,
) -> io::Result<()> {
    writeln!(
        writer,
        "{}",
        format_panic_message(payload, location, redactor)
    )
}

fn truncate_chars(value: &str, max: usize) -> String {
    if value.chars().count() <= max {
        return value.to_owned();
    }

    let mut truncated: String = value.chars().take(max).collect();
    truncated.push_str("... [truncated]");
    truncated
}

#[cfg(test)]
mod tests {
    use super::*;

    const SENTINEL: &str = "sk-test-not-a-real-key";

    #[test]
    fn panic_message_redacts_headers_and_tokens() {
        let redactor = Redactor::default();
        let message = format_panic_message(
            &format!("Authorization: Bearer {SENTINEL}"),
            None,
            &redactor,
        );
        assert!(!message.contains(SENTINEL));
        assert!(message.contains("[REDACTED]"));
        assert!(message.contains("unknown location"));
    }

    #[test]
    fn panic_message_redacts_registered_values() {
        let redactor = Redactor::default().with_secret_value("plain-sentinel-value");
        let message = format_panic_message("failed with plain-sentinel-value", None, &redactor);
        assert!(!message.contains("plain-sentinel-value"));
        assert!(message.contains("[REDACTED]"));
    }

    #[test]
    fn panic_message_truncates_long_payloads() {
        let redactor = Redactor::default();
        let payload = "a".repeat(MAX_PANIC_PAYLOAD_CHARS + 100);
        let message = format_panic_message(&payload, None, &redactor);
        assert!(message.contains("[truncated]"));
        assert!(message.chars().count() < payload.chars().count());
    }

    #[test]
    fn panic_report_writes_redacted_output() {
        let redactor = Redactor::default();
        let mut output = Vec::new();
        report_panic_message(
            &format!("x-api-key: {SENTINEL}"),
            None,
            &redactor,
            &mut output,
        )
        .expect("write panic report");
        let output = String::from_utf8(output).expect("utf-8 panic report");
        assert!(!output.contains(SENTINEL));
        assert!(output.contains("[REDACTED]"));
    }

    #[test]
    fn panic_message_keeps_location_context() {
        let redactor = Redactor::default();
        let location = std::panic::Location::caller();
        let message = format_panic_message("boom", Some(location), &redactor);
        assert!(message.contains(location.file()));
        assert!(message.contains(&location.line().to_string()));
    }
}
