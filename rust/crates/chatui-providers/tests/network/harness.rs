//! Reusable network test harness for provider adapter tests.
//!
//! The harness has two parts:
//!
//! - [`NetworkHarness`] wraps a `wiremock` server for ordinary HTTP responses,
//!   SSE bodies, keep-alives, `Retry-After`, delayed responses, and status
//!   errors.
//! - [`ChunkedSseServer`] is a tiny raw TCP server that writes a real
//!   `Transfer-Encoding: chunked` SSE response. `wiremock` buffers a response
//!   body, so this server is the only way to exercise chunk boundaries,
//!   mid-stream connection failures, and cancellation while a stream is open.
//!
//! Later provider tests can include this file with:
//!
//! ```ignore
//! #[path = "network/harness.rs"]
//! mod harness;
//! ```
//!
//! or, from `tests/network/main.rs`, with `mod harness;`.

use std::net::SocketAddr;
use std::time::Duration;

use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::{TcpListener, TcpStream};
use tokio::task::JoinHandle;
use wiremock::matchers::{method, path};
use wiremock::{Mock, MockServer, Request, ResponseTemplate};

/// A `wiremock`-backed harness for provider network tests.
pub struct NetworkHarness {
    server: MockServer,
}

impl NetworkHarness {
    /// Start a new mock server.
    pub async fn start() -> Self {
        Self {
            server: MockServer::start().await,
        }
    }

    /// Return the server base URI, for example `http://127.0.0.1:1234`.
    pub fn uri(&self) -> String {
        self.server.uri()
    }

    /// Mount a raw SSE response body.
    pub async fn mount_sse(&self, route: &str, body: impl Into<String>) {
        Mock::given(method("GET"))
            .and(path(route))
            .respond_with(
                ResponseTemplate::new(200)
                    .insert_header("content-type", "text/event-stream")
                    .set_body_string(body.into()),
            )
            .mount(&self.server)
            .await;
    }

    /// Mount an SSE response with one `data:` event per supplied payload.
    pub async fn mount_sse_events(&self, route: &str, events: &[&str]) {
        let mut body = String::new();
        for event in events {
            body.push_str("data: ");
            body.push_str(event);
            body.push_str("\n\n");
        }
        self.mount_sse(route, body).await;
    }

    /// Mount an SSE response that interleaves keep-alive comments with events.
    pub async fn mount_keepalive_sse(&self, route: &str, events: &[&str]) {
        let mut body = String::new();
        for event in events {
            body.push_str(": OPENROUTER PROCESSING\n\n");
            body.push_str("data: ");
            body.push_str(event);
            body.push_str("\n\n");
        }
        self.mount_sse(route, body).await;
    }

    /// Mount an SSE response that emits partial text and then an error event.
    pub async fn mount_mid_stream_error(&self, route: &str, partial_text: &str) {
        let body =
            format!("data: {partial_text}\n\ndata: {{\"error\":{{\"message\":\"boom\"}}}}\n\n");
        self.mount_sse(route, body).await;
    }

    /// Mount a status response with a `Retry-After` header in whole seconds.
    pub async fn mount_retry_after(&self, route: &str, status: u16, seconds: u64) {
        Mock::given(method("GET"))
            .and(path(route))
            .respond_with(
                ResponseTemplate::new(status).insert_header("retry-after", seconds.to_string()),
            )
            .mount(&self.server)
            .await;
    }

    /// Mount a response that is delayed before the headers are sent.
    pub async fn mount_delayed(&self, route: &str, delay: Duration) {
        Mock::given(method("GET"))
            .and(path(route))
            .respond_with(ResponseTemplate::new(200).set_delay(delay))
            .mount(&self.server)
            .await;
    }

    /// Mount a plain status response.
    pub async fn mount_status(&self, route: &str, status: u16) {
        Mock::given(method("GET"))
            .and(path(route))
            .respond_with(ResponseTemplate::new(status))
            .mount(&self.server)
            .await;
    }

    /// Return every request the server has received.
    pub async fn received_requests(&self) -> Vec<Request> {
        self.server.received_requests().await.unwrap_or_default()
    }

    /// Return a URI whose port is closed, for connection-failure tests.
    pub fn closed_port_uri() -> String {
        let listener = std::net::TcpListener::bind("127.0.0.1:0").expect("bind a free port");
        let port = listener
            .local_addr()
            .expect("read the bound address")
            .port();
        drop(listener);
        format!("http://127.0.0.1:{port}")
    }
}

/// One chunk written by [`ChunkedSseServer`].
#[derive(Debug, Clone)]
pub struct SseChunk {
    /// Raw bytes written as one HTTP chunk.
    pub data: Vec<u8>,
    /// Delay before writing this chunk.
    pub delay: Duration,
}

impl SseChunk {
    /// Build a chunk with no delay.
    pub fn new(data: impl Into<Vec<u8>>) -> Self {
        Self {
            data: data.into(),
            delay: Duration::ZERO,
        }
    }

    /// Add a delay before this chunk is written.
    pub fn with_delay(mut self, delay: Duration) -> Self {
        self.delay = delay;
        self
    }
}

/// A raw TCP server that writes a chunked SSE response.
///
/// The server accepts one connection, reads the request headers, and writes
/// each [`SseChunk`] as a separate HTTP chunk. Use [`ChunkedSseServer::start`]
/// for a well-formed response and [`ChunkedSseServer::start_abrupt`] to close
/// the connection without the terminating zero-length chunk, which simulates a
/// mid-stream transport failure.
pub struct ChunkedSseServer {
    addr: SocketAddr,
    handle: JoinHandle<()>,
}

impl ChunkedSseServer {
    /// Start a server that terminates the chunked response cleanly.
    pub async fn start(chunks: Vec<SseChunk>) -> Self {
        Self::spawn(chunks, true).await
    }

    /// Start a server that closes the connection without the final chunk.
    pub async fn start_abrupt(chunks: Vec<SseChunk>) -> Self {
        Self::spawn(chunks, false).await
    }

    async fn spawn(chunks: Vec<SseChunk>, terminate: bool) -> Self {
        let listener = TcpListener::bind("127.0.0.1:0")
            .await
            .expect("bind the chunked SSE server");
        let addr = listener.local_addr().expect("read the chunked SSE address");

        let handle = tokio::spawn(async move {
            if let Ok((mut socket, _)) = listener.accept().await {
                let _ = serve_chunked(&mut socket, chunks, terminate).await;
            }
        });

        Self { addr, handle }
    }

    /// Return the server base URI.
    pub fn uri(&self) -> String {
        format!("http://{}", self.addr)
    }

    /// Stop the server.
    pub fn shutdown(self) {
        drop(self);
    }
}

impl Drop for ChunkedSseServer {
    fn drop(&mut self) {
        self.handle.abort();
    }
}

async fn serve_chunked(
    socket: &mut TcpStream,
    chunks: Vec<SseChunk>,
    terminate: bool,
) -> std::io::Result<()> {
    let mut request = [0u8; 2048];
    let _ = socket.read(&mut request).await?;

    socket
        .write_all(
            b"HTTP/1.1 200 OK\r\n\
              Content-Type: text/event-stream\r\n\
              Transfer-Encoding: chunked\r\n\
              Connection: close\r\n\
              \r\n",
        )
        .await?;
    socket.flush().await?;

    for chunk in chunks {
        if !chunk.delay.is_zero() {
            tokio::time::sleep(chunk.delay).await;
        }

        let header = format!("{:x}\r\n", chunk.data.len());
        socket.write_all(header.as_bytes()).await?;
        socket.write_all(&chunk.data).await?;
        socket.write_all(b"\r\n").await?;
        socket.flush().await?;
    }

    if terminate {
        socket.write_all(b"0\r\n\r\n").await?;
        socket.flush().await?;
    }

    Ok(())
}
