//! SSE framing tests against the shared stream fixtures and chunk boundaries.

use std::time::Duration;

use chatui_conformance::{ConformanceFixtures, StreamFixture};
use chatui_providers::sse::{DEFAULT_MAX_SSE_BUFFER_BYTES, SseError, read_sse};
use futures_util::StreamExt;
use tokio_util::sync::CancellationToken;

async fn collect_chunks(chunks: Vec<Vec<u8>>, max_buffer: usize) -> Result<Vec<String>, SseError> {
    let stream = futures_util::stream::iter(chunks.into_iter().map(Ok::<Vec<u8>, std::io::Error>));
    let cancel = CancellationToken::new();
    let mut events = Vec::new();

    read_sse(
        stream,
        |data| events.push(data.to_owned()),
        &cancel,
        max_buffer,
    )
    .await?;

    Ok(events)
}

fn sse_fixtures() -> Vec<StreamFixture> {
    let fixtures = ConformanceFixtures::repository()
        .stream_fixtures()
        .expect("stream fixtures load");
    let sse: Vec<_> = fixtures
        .into_iter()
        .filter(|fixture| fixture.name.ends_with(".sse"))
        .collect();

    assert_eq!(sse.len(), 10, "expected every SSE stream fixture");
    sse
}

fn fixture(name: &str) -> String {
    ConformanceFixtures::repository()
        .root()
        .read(format!("streams/{name}"))
        .expect("fixture loads")
}

/// Independent, deliberately simple oracle for the raw `data:` event sequence.
///
/// This mirrors the SSE framing rules without sharing the parser implementation.
fn expected_data_events(contents: &str) -> Vec<String> {
    let normalized = contents.replace("\r\n", "\n").replace('\r', "\n");
    let mut events = Vec::new();

    for raw_event in normalized.split("\n\n") {
        let mut data_lines: Vec<&str> = Vec::new();

        for line in raw_event.split('\n') {
            if line.is_empty() || line.starts_with(':') {
                continue;
            }

            let (field, value) = match line.split_once(':') {
                Some((field, value)) => (field, value),
                None => (line, ""),
            };

            if field != "data" {
                continue;
            }

            data_lines.push(value.strip_prefix(' ').unwrap_or(value));
        }

        if data_lines.is_empty() {
            continue;
        }

        let joined = data_lines.join("\n");
        if !joined.is_empty() {
            events.push(joined);
        }
    }

    events
}

#[tokio::test]
async fn every_sse_fixture_matches_its_raw_data_events() {
    for fixture in sse_fixtures() {
        let expected = expected_data_events(&fixture.contents);
        let events = collect_chunks(
            vec![fixture.contents.clone().into_bytes()],
            DEFAULT_MAX_SSE_BUFFER_BYTES,
        )
        .await
        .unwrap_or_else(|error| panic!("fixture {} failed: {error}", fixture.name));

        assert_eq!(events, expected, "fixture {}", fixture.name);
    }
}

#[tokio::test]
async fn every_split_point_preserves_fixture_events() {
    for fixture in sse_fixtures() {
        let expected = expected_data_events(&fixture.contents);
        let bytes = fixture.contents.as_bytes();

        for split in 0..=bytes.len() {
            let chunks = vec![bytes[..split].to_vec(), bytes[split..].to_vec()];
            let events = collect_chunks(chunks, DEFAULT_MAX_SSE_BUFFER_BYTES)
                .await
                .unwrap_or_else(|error| {
                    panic!("fixture {} split {split} failed: {error}", fixture.name)
                });

            assert_eq!(events, expected, "fixture {} split {split}", fixture.name);
        }
    }
}

#[tokio::test]
async fn one_byte_chunks_preserve_fixture_events() {
    for fixture in sse_fixtures() {
        let expected = expected_data_events(&fixture.contents);
        let chunks = fixture
            .contents
            .as_bytes()
            .iter()
            .map(|byte| vec![*byte])
            .collect();
        let events = collect_chunks(chunks, DEFAULT_MAX_SSE_BUFFER_BYTES)
            .await
            .unwrap_or_else(|error| panic!("fixture {} failed: {error}", fixture.name));

        assert_eq!(events, expected, "fixture {}", fixture.name);
    }
}

#[tokio::test]
async fn crlf_and_lone_cr_fixtures_match_lf_events() {
    for fixture in sse_fixtures() {
        let expected = expected_data_events(&fixture.contents);

        for (label, contents) in [
            ("crlf", fixture.contents.replace('\n', "\r\n")),
            ("cr", fixture.contents.replace('\n', "\r")),
        ] {
            let events = collect_chunks(vec![contents.into_bytes()], DEFAULT_MAX_SSE_BUFFER_BYTES)
                .await
                .unwrap_or_else(|error| panic!("fixture {} {label} failed: {error}", fixture.name));

            assert_eq!(events, expected, "fixture {} {label}", fixture.name);
        }
    }
}

#[tokio::test]
async fn parses_lf_events_and_ignores_keepalive_comments() {
    let events = collect_chunks(
        vec![
            b": OPENROUTER PROCESSING\n\n".to_vec(),
            b"data: {\"a\":1}\n\n".to_vec(),
            b": OPENROUTER PROCESSING\n\n".to_vec(),
            b"data: {\"b\":2}\n\n".to_vec(),
        ],
        DEFAULT_MAX_SSE_BUFFER_BYTES,
    )
    .await
    .expect("parse");

    assert_eq!(events, vec!["{\"a\":1}", "{\"b\":2}"]);
}

#[tokio::test]
async fn normalizes_crlf_and_lone_cr_line_endings() {
    let events = collect_chunks(
        vec![
            b"data: one\r\n\r\n".to_vec(),
            b"data: two\r\r".to_vec(),
            b"data: three\n\n".to_vec(),
        ],
        DEFAULT_MAX_SSE_BUFFER_BYTES,
    )
    .await
    .expect("parse");

    assert_eq!(events, vec!["one", "two", "three"]);
}

#[tokio::test]
async fn handles_a_crlf_pair_split_across_chunks() {
    let events = collect_chunks(
        vec![
            b"data: one\r".to_vec(),
            b"\n\r".to_vec(),
            b"\ndata: two\r\n\r\n".to_vec(),
        ],
        DEFAULT_MAX_SSE_BUFFER_BYTES,
    )
    .await
    .expect("parse");

    assert_eq!(events, vec!["one", "two"]);
}

#[tokio::test]
async fn handles_a_chunk_split_inside_a_data_line() {
    let events = collect_chunks(
        vec![b"data: hel".to_vec(), b"lo\n\n".to_vec()],
        DEFAULT_MAX_SSE_BUFFER_BYTES,
    )
    .await
    .expect("parse");

    assert_eq!(events, vec!["hello"]);
}

#[tokio::test]
async fn handles_a_chunk_split_inside_json() {
    let events = collect_chunks(
        vec![b"data: {\"text\":\"hel".to_vec(), b"lo\"}\n\n".to_vec()],
        DEFAULT_MAX_SSE_BUFFER_BYTES,
    )
    .await
    .expect("parse");

    assert_eq!(events, vec!["{\"text\":\"hello\"}"]);
}

#[tokio::test]
async fn concatenates_multiline_data_fields_with_a_single_newline() {
    let events = collect_chunks(
        vec![b"data: line one\ndata: line two\ndata:line three\n\n".to_vec()],
        DEFAULT_MAX_SSE_BUFFER_BYTES,
    )
    .await
    .expect("parse");

    assert_eq!(events, vec!["line one\nline two\nline three"]);
}

#[tokio::test]
async fn strips_exactly_one_leading_space_from_data_values() {
    let events = collect_chunks(
        vec![
            b"data:  two spaces\n\n".to_vec(),
            b"data:no space\n\n".to_vec(),
        ],
        DEFAULT_MAX_SSE_BUFFER_BYTES,
    )
    .await
    .expect("parse");

    assert_eq!(events, vec![" two spaces", "no space"]);
}

#[tokio::test]
async fn ignores_comments_empty_events_and_unknown_fields() {
    let events = collect_chunks(
        vec![
            b": OPENROUTER PROCESSING\n\n".to_vec(),
            b"\n\n".to_vec(),
            b"event: message\nid: 1\nretry: 100\ndata: payload\n\n".to_vec(),
            b"data:\n\n".to_vec(),
            b"data: \n\n".to_vec(),
        ],
        DEFAULT_MAX_SSE_BUFFER_BYTES,
    )
    .await
    .expect("parse");

    assert_eq!(events, vec!["payload"]);
}

#[tokio::test]
async fn does_not_parse_provider_json() {
    let events = collect_chunks(
        vec![b"data: {not valid json}\n\n".to_vec()],
        DEFAULT_MAX_SSE_BUFFER_BYTES,
    )
    .await
    .expect("parse");

    assert_eq!(events, vec!["{not valid json}"]);
}

#[tokio::test]
async fn decodes_utf8_split_across_chunks() {
    let bytes = "data: héllo\n\n".as_bytes();
    let split = bytes
        .iter()
        .position(|byte| !byte.is_ascii())
        .expect("fixture contains a multi-byte character")
        + 1;

    let events = collect_chunks(
        vec![bytes[..split].to_vec(), bytes[split..].to_vec()],
        DEFAULT_MAX_SSE_BUFFER_BYTES,
    )
    .await
    .expect("parse");

    assert_eq!(events, vec!["héllo"]);
}

#[tokio::test]
async fn flushes_the_final_event_without_a_blank_line() {
    let events = collect_chunks(vec![b"data: final".to_vec()], DEFAULT_MAX_SSE_BUFFER_BYTES)
        .await
        .expect("parse");

    assert_eq!(events, vec!["final"]);
}

#[tokio::test]
async fn flushes_the_final_event_with_a_single_newline() {
    let events = collect_chunks(
        vec![b"data: final\n".to_vec()],
        DEFAULT_MAX_SSE_BUFFER_BYTES,
    )
    .await
    .expect("parse");

    assert_eq!(events, vec!["final"]);
}

#[tokio::test]
async fn flushes_a_final_event_split_across_chunks() {
    let events = collect_chunks(
        vec![b"data: fi".to_vec(), b"nal".to_vec()],
        DEFAULT_MAX_SSE_BUFFER_BYTES,
    )
    .await
    .expect("parse");

    assert_eq!(events, vec!["final"]);
}

#[tokio::test]
async fn flushes_multiline_data_in_the_final_buffer() {
    let events = collect_chunks(
        vec![b"data: one\ndata: two".to_vec()],
        DEFAULT_MAX_SSE_BUFFER_BYTES,
    )
    .await
    .expect("parse");

    assert_eq!(events, vec!["one\ntwo"]);
}

#[tokio::test]
async fn does_not_duplicate_an_event_that_already_ended() {
    let events = collect_chunks(
        vec![b"data: one\n\n".to_vec()],
        DEFAULT_MAX_SSE_BUFFER_BYTES,
    )
    .await
    .expect("parse");

    assert_eq!(events, vec!["one"]);
}

#[tokio::test]
async fn does_not_emit_a_final_comment_as_an_event() {
    let events = collect_chunks(
        vec![b": OPENROUTER PROCESSING".to_vec()],
        DEFAULT_MAX_SSE_BUFFER_BYTES,
    )
    .await
    .expect("parse");

    assert!(events.is_empty());
}

#[test]
fn default_buffer_limit_is_one_mib() {
    assert_eq!(DEFAULT_MAX_SSE_BUFFER_BYTES, 1024 * 1024);
}

#[tokio::test]
async fn rejects_an_incomplete_event_over_the_limit() {
    let mut events = Vec::new();
    let cancel = CancellationToken::new();
    let stream = futures_util::stream::iter(vec![
        Ok::<Vec<u8>, std::io::Error>(b"data: ".to_vec()),
        Ok(vec![b'x'; 100]),
    ]);

    let result = read_sse(stream, |data| events.push(data.to_owned()), &cancel, 50).await;

    assert_eq!(result, Err(SseError::BufferOverflow { max_bytes: 50 }));
    assert!(events.is_empty());
}

#[tokio::test]
async fn reports_the_configured_limit_on_overflow() {
    let result = collect_chunks(vec![b"data: ".to_vec(), vec![b'x'; 100]], 50).await;

    assert_eq!(result, Err(SseError::BufferOverflow { max_bytes: 50 }));
}

#[tokio::test]
async fn does_not_overflow_when_complete_events_are_drained() {
    let chunks = (0..100)
        .map(|index| format!("data: {index}\n\n").into_bytes())
        .collect();

    let events = collect_chunks(chunks, 32).await.expect("parse");

    assert_eq!(events.len(), 100);
    assert_eq!(events[0], "0");
    assert_eq!(events[99], "99");
}

#[tokio::test]
async fn allows_a_complete_event_larger_than_the_limit() {
    let mut event = b"data: ".to_vec();
    event.extend(std::iter::repeat_n(b'x', 100));
    event.extend_from_slice(b"\n\n");

    let events = collect_chunks(vec![event], 50).await.expect("parse");

    assert_eq!(events.len(), 1);
    assert_eq!(events[0].len(), 100);
}

#[tokio::test]
async fn rejects_immediately_when_already_cancelled() {
    let cancel = CancellationToken::new();
    cancel.cancel();

    let stream = futures_util::stream::iter(vec![Ok::<Vec<u8>, std::io::Error>(
        b"data: one\n\n".to_vec(),
    )]);
    let mut events = Vec::new();

    let result = read_sse(
        stream,
        |data| events.push(data.to_owned()),
        &cancel,
        DEFAULT_MAX_SSE_BUFFER_BYTES,
    )
    .await;

    assert_eq!(result, Err(SseError::Cancelled));
    assert!(events.is_empty());
}

#[tokio::test]
async fn stops_reading_when_cancelled() {
    let cancel = CancellationToken::new();
    let cancel_on_event = cancel.clone();
    let stream = futures_util::stream::iter(vec![Ok::<Vec<u8>, std::io::Error>(
        b"data: one\n\n".to_vec(),
    )])
    .chain(futures_util::stream::pending());
    let mut events = Vec::new();

    let result = tokio::time::timeout(
        Duration::from_secs(1),
        read_sse(
            stream,
            |data| {
                events.push(data.to_owned());
                cancel_on_event.cancel();
            },
            &cancel,
            DEFAULT_MAX_SSE_BUFFER_BYTES,
        ),
    )
    .await
    .expect("read_sse should return promptly after cancellation");

    assert_eq!(result, Err(SseError::Cancelled));
    assert_eq!(events, vec!["one"]);
}

#[tokio::test]
async fn wraps_stream_read_failures() {
    let cancel = CancellationToken::new();
    let stream = futures_util::stream::iter(vec![Err::<Vec<u8>, std::io::Error>(
        std::io::Error::other("boom"),
    )]);
    let mut events = Vec::new();

    let result = read_sse(
        stream,
        |data| events.push(data.to_owned()),
        &cancel,
        DEFAULT_MAX_SSE_BUFFER_BYTES,
    )
    .await;

    assert!(matches!(
        result,
        Err(SseError::Stream { message }) if message.contains("boom")
    ));
    assert!(events.is_empty());
}

#[tokio::test]
async fn openrouter_keepalive_fixture_emits_only_data_payloads() {
    let contents = fixture("openrouter-keepalive.sse");
    let events = collect_chunks(vec![contents.into_bytes()], DEFAULT_MAX_SSE_BUFFER_BYTES)
        .await
        .expect("parse");

    assert_eq!(events.len(), 5);
    assert!(events[0].contains("\"content\":\"Keep\""));
    assert!(events[1].contains("\"content\":\" alive\""));
    assert!(events[2].contains("\"content\":\" comments\""));
    assert!(events[3].contains("\"finish_reason\":\"stop\""));
    assert_eq!(events[4], "[DONE]");
    assert!(events.iter().all(|event| !event.is_empty()));
    assert!(
        events
            .iter()
            .all(|event| !event.contains("OPENROUTER PROCESSING"))
    );
}

#[tokio::test]
async fn openrouter_keepalive_fixture_handles_arbitrary_chunk_boundaries() {
    let contents = fixture("openrouter-keepalive.sse");
    let chunks = contents
        .as_bytes()
        .chunks(7)
        .map(|chunk| chunk.to_vec())
        .collect();

    let events = collect_chunks(chunks, DEFAULT_MAX_SSE_BUFFER_BYTES)
        .await
        .expect("parse");

    assert_eq!(events.len(), 5);
    assert_eq!(events[4], "[DONE]");
}

#[tokio::test]
async fn openrouter_keepalive_fixture_handles_crlf() {
    let contents = fixture("openrouter-keepalive.sse").replace('\n', "\r\n");
    let events = collect_chunks(vec![contents.into_bytes()], DEFAULT_MAX_SSE_BUFFER_BYTES)
        .await
        .expect("parse");

    assert_eq!(events.len(), 5);
    assert_eq!(events[4], "[DONE]");
}
