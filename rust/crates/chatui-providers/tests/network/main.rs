//! Network harness and retry/watchdog integration tests.
//!
//! These tests exercise the reusable harness in `harness.rs` and the shared
//! retry, cancellation, and inactivity-watchdog behavior against real HTTP
//! responses.

mod harness;

use std::sync::Arc;
use std::sync::atomic::{AtomicU32, Ordering};
use std::time::Duration;

use chatui_providers::cancel::CancellationToken;
use chatui_providers::error::{NetworkError, NetworkErrorKind};
use chatui_providers::http::{HttpClient, build_http_client};
use chatui_providers::retry::{
    AttemptFailure, AttemptOutcome, InactivityWatchdog, PartialOutput, RetryError, RetryPolicy,
    retry_after_from_header, retry_delay_for, retry_with,
};
use chatui_providers::sse::{SseError, read_sse};
use futures_util::StreamExt;
use harness::{ChunkedSseServer, NetworkHarness, SseChunk};

fn fast_policy() -> RetryPolicy {
    RetryPolicy {
        base_delay: Duration::from_millis(1),
        max_delay: Duration::from_millis(2),
        ..RetryPolicy::default()
    }
}

#[tokio::test]
async fn harness_serves_chunked_sse() {
    let server = ChunkedSseServer::start(vec![
        SseChunk::new("data: one\n\n"),
        SseChunk::new("data: two\n\n"),
    ])
    .await;

    let client = build_http_client().expect("client");
    let response = client.get(server.uri()).send().await.expect("send");
    let cancel = CancellationToken::new();
    let mut events = Vec::new();

    read_sse(
        response.bytes_stream(),
        |data| events.push(data.to_owned()),
        &cancel,
        1024,
    )
    .await
    .expect("read SSE");

    assert_eq!(events, vec!["one", "two"]);
    server.shutdown();
}

#[tokio::test]
async fn harness_serves_keepalives() {
    let harness = NetworkHarness::start().await;
    harness
        .mount_keepalive_sse("/stream", &["one", "two"])
        .await;

    let client = build_http_client().expect("client");
    let response = client
        .get(format!("{}/stream", harness.uri()))
        .send()
        .await
        .expect("send");
    let cancel = CancellationToken::new();
    let mut events = Vec::new();

    read_sse(
        response.bytes_stream(),
        |data| events.push(data.to_owned()),
        &cancel,
        1024,
    )
    .await
    .expect("read SSE");

    assert_eq!(events, vec!["one", "two"]);
}

#[tokio::test]
async fn harness_serves_sse_events() {
    let harness = NetworkHarness::start().await;
    harness.mount_sse_events("/stream", &["one", "two"]).await;

    let client = build_http_client().expect("client");
    let response = client
        .get(format!("{}/stream", harness.uri()))
        .send()
        .await
        .expect("send");
    let cancel = CancellationToken::new();
    let mut events = Vec::new();

    read_sse(
        response.bytes_stream(),
        |data| events.push(data.to_owned()),
        &cancel,
        1024,
    )
    .await
    .expect("read SSE");

    assert_eq!(events, vec!["one", "two"]);
}

#[tokio::test]
async fn harness_serves_mid_stream_error_event() {
    let harness = NetworkHarness::start().await;
    harness.mount_mid_stream_error("/stream", "partial").await;

    let client = build_http_client().expect("client");
    let response = client
        .get(format!("{}/stream", harness.uri()))
        .send()
        .await
        .expect("send");
    let cancel = CancellationToken::new();
    let mut events = Vec::new();

    read_sse(
        response.bytes_stream(),
        |data| events.push(data.to_owned()),
        &cancel,
        1024,
    )
    .await
    .expect("read SSE");

    assert_eq!(
        events,
        vec!["partial", "{\"error\":{\"message\":\"boom\"}}"]
    );
}

#[tokio::test]
async fn harness_reports_mid_stream_error() {
    let server = ChunkedSseServer::start_abrupt(vec![SseChunk::new("data: partial\n\n")]).await;

    let client = build_http_client().expect("client");
    let response = client.get(server.uri()).send().await.expect("send");
    let cancel = CancellationToken::new();
    let mut events = Vec::new();

    let result = read_sse(
        response.bytes_stream(),
        |data| events.push(data.to_owned()),
        &cancel,
        1024,
    )
    .await;

    assert!(matches!(result, Err(SseError::Stream { .. })));
    assert_eq!(events, vec!["partial"]);
    server.shutdown();
}

#[tokio::test]
async fn harness_honors_retry_after() {
    let harness = NetworkHarness::start().await;
    harness.mount_retry_after("/limited", 429, 2).await;

    let client = build_http_client().expect("client");
    let response = client
        .get(format!("{}/limited", harness.uri()))
        .send()
        .await
        .expect("send");

    assert_eq!(response.status(), reqwest::StatusCode::TOO_MANY_REQUESTS);
    let header = response
        .headers()
        .get("retry-after")
        .and_then(|value| value.to_str().ok())
        .expect("retry-after header");
    let retry_after = retry_after_from_header(header);

    assert_eq!(retry_after, Some(Duration::from_secs(2)));
    assert_eq!(
        retry_delay_for(retry_after, 1, &fast_policy()),
        Duration::from_secs(2)
    );
}

#[tokio::test]
async fn harness_simulates_connection_failure() {
    let uri = NetworkHarness::closed_port_uri();
    let client = build_http_client().expect("client");

    let error = client
        .get(uri)
        .send()
        .await
        .expect_err("connection should fail");
    let mapped = NetworkError::from_reqwest(error);

    assert!(mapped.retryable);
    assert!(matches!(
        mapped.kind,
        NetworkErrorKind::Connection | NetworkErrorKind::Dns
    ));
}

#[tokio::test]
async fn harness_simulates_timeout() {
    let harness = NetworkHarness::start().await;
    harness.mount_delayed("/slow", Duration::from_secs(5)).await;

    let client = HttpClient::new().expect("client");
    let request = client
        .client()
        .get(format!("{}/slow", harness.uri()))
        .build()
        .expect("request");
    let error = client
        .execute_with_timeout(request, Duration::from_millis(10))
        .await
        .expect_err("timeout");

    assert_eq!(error.kind, NetworkErrorKind::Timeout);
    assert!(error.retryable);
}

#[tokio::test]
async fn cancellation_interrupts_stream_reads() {
    let server = ChunkedSseServer::start(vec![
        SseChunk::new("data: one\n\n"),
        SseChunk::new("data: two\n\n").with_delay(Duration::from_secs(30)),
    ])
    .await;

    let client = build_http_client().expect("client");
    let response = client.get(server.uri()).send().await.expect("send");
    let cancel = CancellationToken::new();
    let cancel_for_task = cancel.clone();

    tokio::spawn(async move {
        tokio::time::sleep(Duration::from_millis(50)).await;
        cancel_for_task.cancel();
    });

    let mut events = Vec::new();
    let result = tokio::time::timeout(
        Duration::from_secs(2),
        read_sse(
            response.bytes_stream(),
            |data| events.push(data.to_owned()),
            &cancel,
            1024,
        ),
    )
    .await
    .expect("read should be interrupted by cancellation");

    assert_eq!(result, Err(SseError::Cancelled));
    assert_eq!(events, vec!["one"]);
    server.shutdown();
}

#[tokio::test]
async fn watchdog_cancels_stalled_stream() {
    let server = ChunkedSseServer::start(vec![
        SseChunk::new("data: one\n\n"),
        SseChunk::new("data: two\n\n").with_delay(Duration::from_secs(30)),
    ])
    .await;

    let client = build_http_client().expect("client");
    let response = client.get(server.uri()).send().await.expect("send");
    let cancel = CancellationToken::new();
    let watchdog = InactivityWatchdog::new(Duration::from_millis(50), cancel.clone());
    let mut stream = response.bytes_stream();
    let mut chunks = 0usize;

    let result = tokio::time::timeout(Duration::from_secs(2), async {
        loop {
            tokio::select! {
                biased;
                _ = cancel.cancelled() => return Err(NetworkError::cancelled()),
                _ = watchdog.expired() => return Err(NetworkError::timeout()),
                item = stream.next() => {
                    match item {
                        Some(Ok(_)) => {
                            chunks += 1;
                            watchdog.reset();
                        }
                        Some(Err(_)) => return Err(NetworkError::timeout()),
                        None => return Ok(()),
                    }
                }
            }
        }
    })
    .await
    .expect("watchdog should fire");

    assert!(result.is_err());
    assert!(watchdog.timed_out());
    assert!(cancel.is_cancelled());
    assert_eq!(chunks, 1);
    server.shutdown();
}

#[tokio::test]
async fn retry_attempts_three_times_before_giving_up() {
    let harness = NetworkHarness::start().await;
    harness.mount_status("/fail", 503).await;

    let client = build_http_client().expect("client");
    let policy = fast_policy();
    let cancel = CancellationToken::new();

    let result: Result<(), RetryError> = retry_with(&policy, &cancel, |_| {
        let client = client.clone();
        let uri = format!("{}/fail", harness.uri());
        async move {
            let response = client.get(uri).send().await.expect("send");
            let error = NetworkError::from_status(response.status().as_u16(), None);
            AttemptOutcome::Failed(AttemptFailure::new(error))
        }
    })
    .await;

    assert!(matches!(
        result,
        Err(RetryError::Network(error)) if error.status == Some(503)
    ));
    assert_eq!(harness.received_requests().await.len(), 3);
}

#[tokio::test]
async fn retry_does_not_repeat_after_partial_output() {
    let harness = NetworkHarness::start().await;
    harness.mount_status("/fail", 500).await;

    let client = build_http_client().expect("client");
    let policy = fast_policy();
    let cancel = CancellationToken::new();

    let result: Result<(), RetryError> = retry_with(&policy, &cancel, |_| {
        let client = client.clone();
        let uri = format!("{}/fail", harness.uri());
        async move {
            let response = client.get(uri).send().await.expect("send");
            let error = NetworkError::from_status(response.status().as_u16(), None);
            AttemptOutcome::Failed(
                AttemptFailure::new(error).with_partial_output(PartialOutput::text()),
            )
        }
    })
    .await;

    assert!(matches!(result, Err(RetryError::Network(_))));
    assert_eq!(harness.received_requests().await.len(), 1);
}

#[tokio::test]
async fn retry_loop_uses_retry_after_from_failure() {
    let policy = fast_policy();
    let cancel = CancellationToken::new();
    let attempts = Arc::new(AtomicU32::new(0));
    let attempts_for_closure = Arc::clone(&attempts);
    let started = std::time::Instant::now();

    let result: Result<(), RetryError> = retry_with(&policy, &cancel, move |_| {
        let attempts = Arc::clone(&attempts_for_closure);
        async move {
            let attempt = attempts.fetch_add(1, Ordering::SeqCst);
            if attempt == 0 {
                AttemptOutcome::Failed(
                    AttemptFailure::new(NetworkError::from_status(429, Some(0.05)))
                        .with_retry_after(Some(Duration::from_millis(50))),
                )
            } else {
                AttemptOutcome::Success(())
            }
        }
    })
    .await;

    assert!(result.is_ok());
    assert_eq!(attempts.load(Ordering::SeqCst), 2);
    assert!(
        started.elapsed() >= Duration::from_millis(40),
        "retry should wait for the provider Retry-After"
    );
}
