//! Bounded screen-reader announcements for streaming responses.
//!
//! Spec section 11.11 requires a polite live region that announces response
//! start, completion, and errors, but never every token. This module maps
//! streaming state transitions to at most one announcement per transition so
//! the live region stays quiet while text streams.

use chatui_domain::MessageStatus;

use crate::accessibility::Announcement;
use crate::chat::service::describe_chat_error;
use crate::chat::state::StreamingState;

/// Maximum characters in a live-region announcement.
pub const MAX_ANNOUNCEMENT_CHARS: usize = 160;

/// Return the announcement for a streaming state transition, if any.
///
/// Token deltas return `None`. Start, completion, cancellation, and errors each
/// produce one bounded announcement. The error announcement uses the same
/// plain-language title as the visible error card, so it never exposes raw
/// provider payloads.
pub fn streaming_announcement(
    previous: &StreamingState,
    next: &StreamingState,
) -> Option<Announcement> {
    if next.error.is_some() && previous.error.is_none() {
        return error_announcement(next);
    }

    if next.done && !previous.done {
        return Some(completion_announcement(next));
    }

    if next.started && !previous.started {
        return Some(Announcement::polite("Response started"));
    }

    None
}

/// Return the announcement for a state that was installed without a previous
/// snapshot, such as reconnecting to an already-streaming response.
pub fn initial_announcement(next: &StreamingState) -> Option<Announcement> {
    if next.error.is_some() {
        return error_announcement(next);
    }

    if next.done {
        return Some(completion_announcement(next));
    }

    if next.started {
        return Some(Announcement::polite("Response started"));
    }

    None
}

fn error_announcement(next: &StreamingState) -> Option<Announcement> {
    let title = next
        .error
        .as_ref()
        .map(|error| describe_chat_error(error).title)
        .unwrap_or_else(|| "Response failed".to_owned());
    Some(Announcement::assertive(truncate(&format!(
        "Response failed: {title}"
    ))))
}

fn completion_announcement(next: &StreamingState) -> Announcement {
    let text = if next.status == MessageStatus::Cancelled {
        "Response cancelled"
    } else {
        "Response complete"
    };
    Announcement::polite(text)
}

fn truncate(text: &str) -> String {
    if text.chars().count() <= MAX_ANNOUNCEMENT_CHARS {
        return text.to_owned();
    }

    let mut truncated: String = text.chars().take(MAX_ANNOUNCEMENT_CHARS - 1).collect();
    truncated.push('…');
    truncated
}

#[cfg(test)]
mod tests {
    use chatui_domain::{ChatError, ChatErrorCode, FinishReason, StreamEvent, Timestamp};

    use super::*;
    use crate::chat::state::reduce_stream;

    fn now() -> Timestamp {
        Timestamp::parse("2026-11-08T12:00:00.000Z").expect("valid timestamp")
    }

    fn initial() -> StreamingState {
        StreamingState::new("conversation-1", "message-1", now())
    }

    fn error() -> ChatError {
        ChatError {
            code: ChatErrorCode::RateLimit,
            message: "Too many requests. Try again shortly.".to_owned(),
            provider_id: None,
            status: Some(429),
            retryable: true,
            retry_after_seconds: Some(2.0),
            request_id: None,
            details: None,
            at: now(),
        }
    }

    #[test]
    fn start_is_announced_once() {
        let previous = initial();
        let next = reduce_stream(
            &previous,
            &StreamEvent::Start {
                response_id: Some("response-1".to_owned()),
            },
            now(),
        );

        let announcement = streaming_announcement(&previous, &next).expect("start announcement");
        assert_eq!(announcement.text, "Response started");
        assert_eq!(
            announcement.politeness,
            crate::accessibility::LiveRegionPoliteness::Polite
        );

        let repeated = reduce_stream(
            &next,
            &StreamEvent::Start {
                response_id: Some("response-1".to_owned()),
            },
            now(),
        );
        assert_eq!(streaming_announcement(&next, &repeated), None);
    }

    #[test]
    fn token_deltas_are_not_announced() {
        let previous = reduce_stream(
            &initial(),
            &StreamEvent::Start {
                response_id: Some("response-1".to_owned()),
            },
            now(),
        );
        let next = reduce_stream(
            &previous,
            &StreamEvent::TextDelta {
                text: "Hello".to_owned(),
            },
            now(),
        );

        assert_eq!(streaming_announcement(&previous, &next), None);
    }

    #[test]
    fn completion_is_announced_once() {
        let previous = reduce_stream(
            &initial(),
            &StreamEvent::Start {
                response_id: Some("response-1".to_owned()),
            },
            now(),
        );
        let next = reduce_stream(
            &previous,
            &StreamEvent::Finish {
                reason: FinishReason::Stop,
            },
            now(),
        );

        let announcement = streaming_announcement(&previous, &next).expect("complete announcement");
        assert_eq!(announcement.text, "Response complete");
        assert_eq!(
            announcement.politeness,
            crate::accessibility::LiveRegionPoliteness::Polite
        );
        assert_eq!(streaming_announcement(&next, &next), None);
    }

    #[test]
    fn cancellation_is_announced_as_cancelled() {
        let previous = reduce_stream(
            &initial(),
            &StreamEvent::Start {
                response_id: Some("response-1".to_owned()),
            },
            now(),
        );
        let mut next = reduce_stream(
            &previous,
            &StreamEvent::Finish {
                reason: FinishReason::Stop,
            },
            now(),
        );
        next.status = MessageStatus::Cancelled;

        let announcement = streaming_announcement(&previous, &next).expect("cancel announcement");
        assert_eq!(announcement.text, "Response cancelled");
    }

    #[test]
    fn errors_are_announced_assertively_with_plain_language() {
        let previous = reduce_stream(
            &initial(),
            &StreamEvent::Start {
                response_id: Some("response-1".to_owned()),
            },
            now(),
        );
        let next = reduce_stream(&previous, &StreamEvent::Error { error: error() }, now());

        let announcement = streaming_announcement(&previous, &next).expect("error announcement");
        assert_eq!(announcement.text, "Response failed: Rate limited");
        assert_eq!(
            announcement.politeness,
            crate::accessibility::LiveRegionPoliteness::Assertive
        );
        assert!(!announcement.text.contains("Too many requests"));
        assert_eq!(streaming_announcement(&next, &next), None);
    }

    #[test]
    fn initial_snapshot_announces_once() {
        let mut started = reduce_stream(
            &initial(),
            &StreamEvent::Start {
                response_id: Some("response-1".to_owned()),
            },
            now(),
        );
        let announcement = initial_announcement(&started).expect("start announcement");
        assert_eq!(announcement.text, "Response started");

        started = reduce_stream(
            &started,
            &StreamEvent::Finish {
                reason: FinishReason::Stop,
            },
            now(),
        );
        let announcement = initial_announcement(&started).expect("complete announcement");
        assert_eq!(announcement.text, "Response complete");

        let fresh = initial();
        assert_eq!(initial_announcement(&fresh), None);
    }

    #[test]
    fn announcements_are_bounded() {
        let long = "x".repeat(MAX_ANNOUNCEMENT_CHARS * 2);
        let truncated = truncate(&long);
        assert_eq!(truncated.chars().count(), MAX_ANNOUNCEMENT_CHARS);
        assert!(truncated.ends_with('…'));
    }
}
