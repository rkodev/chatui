//! NDJSON framing tests against the shared Ollama fixture and chunk boundaries.

use std::time::Duration;

use chatui_conformance::ConformanceFixtures;
use chatui_providers::ndjson::{DEFAULT_MAX_NDJSON_BUFFER_BYTES, NdjsonError, read_ndjson};
use futures_util::StreamExt;
use tokio_util::sync::CancellationToken;

async fn collect_chunks(
    chunks: Vec<Vec<u8>>,
    max_line_bytes: usize,
) -> Result<Vec<String>, NdjsonError> {
    let stream = futures_util::stream::iter(chunks.into_iter().map(Ok::<Vec<u8>, std::io::Error>));
    let cancel = CancellationToken::new();
    let mut lines = Vec::new();

    read_ndjson(
        stream,
        |line| lines.push(line.to_owned()),
        &cancel,
        max_line_bytes,
    )
    .await?;

    Ok(lines)
}

fn fixture(name: &str) -> String {
    ConformanceFixtures::repository()
        .root()
        .read(format!("streams/{name}"))
        .expect("fixture loads")
}

/// Independent, deliberately simple oracle for the raw NDJSON line sequence.
///
/// This mirrors the framing rules without sharing the parser implementation.
fn expected_lines(contents: &str) -> Vec<String> {
    contents
        .lines()
        .filter(|line| !line.trim().is_empty())
        .map(str::to_owned)
        .collect()
}

#[tokio::test]
async fn ollama_fixture_emits_each_json_line_in_order() {
    let contents = fixture("ollama-ndjson.txt");
    let expected = expected_lines(&contents);

    assert_eq!(expected.len(), 3, "fixture has three NDJSON records");

    let lines = collect_chunks(vec![contents.into_bytes()], DEFAULT_MAX_NDJSON_BUFFER_BYTES)
        .await
        .expect("parse");

    assert_eq!(lines, expected);
    assert!(lines[0].contains("\"content\":\"Hello\""));
    assert!(lines[1].contains("\"content\":\" from Ollama.\""));
    assert!(lines[2].contains("\"done\":true"));
}

#[tokio::test]
async fn every_split_point_preserves_fixture_lines() {
    let contents = fixture("ollama-ndjson.txt");
    let expected = expected_lines(&contents);
    let bytes = contents.as_bytes();

    for split in 0..=bytes.len() {
        let chunks = vec![bytes[..split].to_vec(), bytes[split..].to_vec()];
        let lines = collect_chunks(chunks, DEFAULT_MAX_NDJSON_BUFFER_BYTES)
            .await
            .unwrap_or_else(|error| panic!("fixture split {split} failed: {error}"));

        assert_eq!(lines, expected, "fixture split {split}");
    }
}

#[tokio::test]
async fn one_byte_chunks_preserve_fixture_lines() {
    let contents = fixture("ollama-ndjson.txt");
    let expected = expected_lines(&contents);
    let chunks = contents.as_bytes().iter().map(|byte| vec![*byte]).collect();

    let lines = collect_chunks(chunks, DEFAULT_MAX_NDJSON_BUFFER_BYTES)
        .await
        .expect("parse");

    assert_eq!(lines, expected);
}

#[tokio::test]
async fn fixed_size_chunks_preserve_fixture_lines() {
    let contents = fixture("ollama-ndjson.txt");
    let expected = expected_lines(&contents);
    let bytes = contents.as_bytes();

    for size in 1..=bytes.len() {
        let chunks = bytes.chunks(size).map(<[u8]>::to_vec).collect();
        let lines = collect_chunks(chunks, DEFAULT_MAX_NDJSON_BUFFER_BYTES)
            .await
            .unwrap_or_else(|error| panic!("fixture chunk size {size} failed: {error}"));

        assert_eq!(lines, expected, "fixture chunk size {size}");
    }
}

#[tokio::test]
async fn crlf_fixture_matches_lf_lines() {
    let contents = fixture("ollama-ndjson.txt");
    let expected = expected_lines(&contents);
    let crlf = contents.replace('\n', "\r\n");

    let lines = collect_chunks(vec![crlf.into_bytes()], DEFAULT_MAX_NDJSON_BUFFER_BYTES)
        .await
        .expect("parse");

    assert_eq!(lines, expected);
}

#[tokio::test]
async fn handles_a_crlf_pair_split_across_chunks() {
    let lines = collect_chunks(
        vec![b"{\"a\":1}\r".to_vec(), b"\n{\"b\":2}\r\n".to_vec()],
        DEFAULT_MAX_NDJSON_BUFFER_BYTES,
    )
    .await
    .expect("parse");

    assert_eq!(lines, vec!["{\"a\":1}", "{\"b\":2}"]);
}

#[tokio::test]
async fn ignores_blank_and_whitespace_only_lines() {
    let lines = collect_chunks(
        vec![b"\n\n  \n\t\n{\"a\":1}\n\n".to_vec()],
        DEFAULT_MAX_NDJSON_BUFFER_BYTES,
    )
    .await
    .expect("parse");

    assert_eq!(lines, vec!["{\"a\":1}"]);
}

#[tokio::test]
async fn flushes_the_final_line_without_a_trailing_newline() {
    let lines = collect_chunks(vec![b"{\"a\":1}".to_vec()], DEFAULT_MAX_NDJSON_BUFFER_BYTES)
        .await
        .expect("parse");

    assert_eq!(lines, vec!["{\"a\":1}"]);
}

#[tokio::test]
async fn flushes_the_final_line_with_a_trailing_newline() {
    let lines = collect_chunks(
        vec![b"{\"a\":1}\n".to_vec()],
        DEFAULT_MAX_NDJSON_BUFFER_BYTES,
    )
    .await
    .expect("parse");

    assert_eq!(lines, vec!["{\"a\":1}"]);
}

#[tokio::test]
async fn flushes_a_final_line_split_across_chunks() {
    let lines = collect_chunks(
        vec![b"{\"a\":".to_vec(), b"1}".to_vec()],
        DEFAULT_MAX_NDJSON_BUFFER_BYTES,
    )
    .await
    .expect("parse");

    assert_eq!(lines, vec!["{\"a\":1}"]);
}

#[tokio::test]
async fn strips_a_trailing_carriage_return_from_the_final_line() {
    let lines = collect_chunks(
        vec![b"{\"a\":1}\r".to_vec()],
        DEFAULT_MAX_NDJSON_BUFFER_BYTES,
    )
    .await
    .expect("parse");

    assert_eq!(lines, vec!["{\"a\":1}"]);
}

#[tokio::test]
async fn decodes_utf8_split_across_chunks() {
    let bytes = "{\"text\":\"héllo\"}\n".as_bytes();
    let split = bytes
        .iter()
        .position(|byte| !byte.is_ascii())
        .expect("fixture contains a multi-byte character")
        + 1;

    let lines = collect_chunks(
        vec![bytes[..split].to_vec(), bytes[split..].to_vec()],
        DEFAULT_MAX_NDJSON_BUFFER_BYTES,
    )
    .await
    .expect("parse");

    assert_eq!(lines, vec!["{\"text\":\"héllo\"}"]);
}

#[tokio::test]
async fn rejects_an_incomplete_line_over_the_limit() {
    let mut lines = Vec::new();
    let cancel = CancellationToken::new();
    let stream = futures_util::stream::iter(vec![
        Ok::<Vec<u8>, std::io::Error>(b"{\"a\":".to_vec()),
        Ok(vec![b'x'; 100]),
    ]);

    let result = read_ndjson(stream, |line| lines.push(line.to_owned()), &cancel, 50).await;

    assert_eq!(result, Err(NdjsonError::BufferOverflow { max_bytes: 50 }));
    assert!(lines.is_empty());
}

#[tokio::test]
async fn reports_the_configured_limit_on_overflow() {
    let result = collect_chunks(vec![b"{\"a\":".to_vec(), vec![b'x'; 100]], 50).await;

    assert_eq!(result, Err(NdjsonError::BufferOverflow { max_bytes: 50 }));
}

#[tokio::test]
async fn allows_a_complete_line_larger_than_the_limit() {
    let mut line = b"{\"a\":\"".to_vec();
    line.extend(std::iter::repeat_n(b'x', 100));
    line.extend_from_slice(b"\"}\n");

    let lines = collect_chunks(vec![line], 50).await.expect("parse");

    assert_eq!(lines.len(), 1);
    assert_eq!(lines[0].len(), 108);
}

#[tokio::test]
async fn does_not_overflow_when_complete_lines_are_drained() {
    let chunks = (0..100)
        .map(|index| format!("{{\"n\":{index}}}\n").into_bytes())
        .collect();

    let lines = collect_chunks(chunks, 32).await.expect("parse");

    assert_eq!(lines.len(), 100);
    assert_eq!(lines[0], "{\"n\":0}");
    assert_eq!(lines[99], "{\"n\":99}");
}

#[tokio::test]
async fn rejects_immediately_when_already_cancelled() {
    let cancel = CancellationToken::new();
    cancel.cancel();

    let stream =
        futures_util::stream::iter(vec![Ok::<Vec<u8>, std::io::Error>(b"{\"a\":1}\n".to_vec())]);
    let mut lines = Vec::new();

    let result = read_ndjson(
        stream,
        |line| lines.push(line.to_owned()),
        &cancel,
        DEFAULT_MAX_NDJSON_BUFFER_BYTES,
    )
    .await;

    assert_eq!(result, Err(NdjsonError::Cancelled));
    assert!(lines.is_empty());
}

#[tokio::test]
async fn stops_reading_when_cancelled() {
    let cancel = CancellationToken::new();
    let cancel_on_line = cancel.clone();
    let stream =
        futures_util::stream::iter(vec![Ok::<Vec<u8>, std::io::Error>(b"{\"a\":1}\n".to_vec())])
            .chain(futures_util::stream::pending());
    let mut lines = Vec::new();

    let result = tokio::time::timeout(
        Duration::from_secs(1),
        read_ndjson(
            stream,
            |line| {
                lines.push(line.to_owned());
                cancel_on_line.cancel();
            },
            &cancel,
            DEFAULT_MAX_NDJSON_BUFFER_BYTES,
        ),
    )
    .await
    .expect("read_ndjson should return promptly after cancellation");

    assert_eq!(result, Err(NdjsonError::Cancelled));
    assert_eq!(lines, vec!["{\"a\":1}"]);
}

#[tokio::test]
async fn wraps_stream_read_failures() {
    let cancel = CancellationToken::new();
    let stream = futures_util::stream::iter(vec![Err::<Vec<u8>, std::io::Error>(
        std::io::Error::other("boom"),
    )]);
    let mut lines = Vec::new();

    let result = read_ndjson(
        stream,
        |line| lines.push(line.to_owned()),
        &cancel,
        DEFAULT_MAX_NDJSON_BUFFER_BYTES,
    )
    .await;

    assert!(matches!(
        result,
        Err(NdjsonError::Stream { message }) if message.contains("boom")
    ));
    assert!(lines.is_empty());
}

#[tokio::test]
async fn rejects_invalid_utf8() {
    let result = collect_chunks(
        vec![b"{\"a\":\"".to_vec(), vec![0xff], b"\"}\n".to_vec()],
        DEFAULT_MAX_NDJSON_BUFFER_BYTES,
    )
    .await;

    assert_eq!(result, Err(NdjsonError::InvalidUtf8));
}

#[tokio::test]
async fn does_not_parse_provider_json() {
    let lines = collect_chunks(
        vec![b"{not valid json}\n".to_vec()],
        DEFAULT_MAX_NDJSON_BUFFER_BYTES,
    )
    .await
    .expect("parse");

    assert_eq!(lines, vec!["{not valid json}"]);
}

#[test]
fn default_buffer_limit_is_one_mib() {
    assert_eq!(DEFAULT_MAX_NDJSON_BUFFER_BYTES, 1024 * 1024);
}
