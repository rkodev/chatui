//! Shared HTTP client, redirect policy, and secret header handling.
//!
//! Every provider uses one pooled [`reqwest::Client`] built by
//! [`build_http_client`]. The client uses `rustls-tls`, a consistent user
//! agent, a 30 second connect timeout, and no total request timeout so
//! streaming responses are not cut off. Cross-origin redirects are refused so
//! authorization headers cannot leak to another origin.

use std::fmt;
use std::time::Duration;

use reqwest::header::{HeaderName, HeaderValue};
use reqwest::redirect::Policy;
use reqwest::{Client, Method, RequestBuilder, Response, Url};

use crate::error::NetworkError;
use crate::url_policy::{UrlPolicyError, classify_url};

/// Connect timeout for every request.
pub const CONNECT_TIMEOUT: Duration = Duration::from_secs(30);

/// How long an idle pooled connection is kept.
pub const POOL_IDLE_TIMEOUT: Duration = Duration::from_secs(90);

/// Maximum number of idle connections kept per host.
pub const POOL_MAX_IDLE_PER_HOST: usize = 8;

/// TCP keepalive interval for pooled connections.
pub const TCP_KEEPALIVE: Duration = Duration::from_secs(60);

/// Maximum number of redirects before the client stops following.
pub const MAX_REDIRECTS: usize = 10;

/// Consistent user agent. It contains no personal data.
pub const USER_AGENT: &str = concat!("ChatUI/", env!("CARGO_PKG_VERSION"));

/// Build the shared pooled HTTP client.
///
/// The client deliberately has no total request timeout. Streaming requests
/// rely on the inactivity watchdog in M3.5-05.4 instead.
pub fn build_http_client() -> Result<Client, NetworkError> {
    Client::builder()
        .user_agent(USER_AGENT)
        .connect_timeout(CONNECT_TIMEOUT)
        .pool_idle_timeout(POOL_IDLE_TIMEOUT)
        .pool_max_idle_per_host(POOL_MAX_IDLE_PER_HOST)
        .tcp_keepalive(TCP_KEEPALIVE)
        .redirect(redirect_policy())
        .build()
        .map_err(NetworkError::from_reqwest)
}

/// Redirect policy that refuses cross-origin redirects.
///
/// Refusing the redirect is safer than trying to strip individual secret
/// headers: the caller sees the 3xx response and can decide what to do, and no
/// authorization header is ever sent to a different origin.
pub fn redirect_policy() -> Policy {
    Policy::custom(|attempt| {
        if attempt.previous().len() > MAX_REDIRECTS {
            return attempt.error("too many redirects");
        }
        if let Some(previous) = attempt.previous().last()
            && !same_origin(previous, attempt.url())
        {
            return attempt.stop();
        }
        attempt.follow()
    })
}

/// Return whether two URLs share a scheme, host, and port.
pub fn same_origin(left: &Url, right: &Url) -> bool {
    left.origin() == right.origin()
}

/// A secret header value that never appears in `Debug` output.
#[derive(Clone)]
pub struct SecretHeader {
    name: HeaderName,
    value: HeaderValue,
}

impl SecretHeader {
    /// Build a secret header from a name and value.
    ///
    /// The value is validated but never echoed in an error message.
    pub fn new(name: HeaderName, value: &str) -> Result<Self, NetworkError> {
        let value = HeaderValue::from_str(value)
            .map_err(|_| NetworkError::invalid_header(name.as_str()))?;
        Ok(Self { name, value })
    }

    /// Return the header name.
    pub fn name(&self) -> &HeaderName {
        &self.name
    }

    /// Apply this header to a request builder.
    pub fn apply(&self, request: RequestBuilder) -> RequestBuilder {
        request.header(self.name.clone(), self.value.clone())
    }
}

impl fmt::Debug for SecretHeader {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter
            .debug_struct("SecretHeader")
            .field("name", &self.name)
            .field("value", &"<redacted>")
            .finish()
    }
}

/// A request builder that has passed the URL policy.
#[derive(Debug)]
pub struct PreparedRequest {
    /// The request builder to send.
    pub builder: RequestBuilder,
    /// Warning to surface for an allowed cleartext URL.
    pub warning: Option<String>,
}

impl PreparedRequest {
    /// Consume the prepared request and return the builder.
    pub fn into_builder(self) -> RequestBuilder {
        self.builder
    }
}

/// A shared HTTP client wrapper that enforces the URL policy.
#[derive(Clone, Debug)]
pub struct HttpClient {
    client: Client,
}

impl HttpClient {
    /// Build a new shared client.
    pub fn new() -> Result<Self, NetworkError> {
        Ok(Self {
            client: build_http_client()?,
        })
    }

    /// Wrap an existing client, for example one supplied by a test harness.
    pub fn from_client(client: Client) -> Self {
        Self { client }
    }

    /// Return the underlying `reqwest` client.
    pub fn client(&self) -> &Client {
        &self.client
    }

    /// Classify a URL and prepare a request builder.
    ///
    /// Cleartext public URLs are rejected. Allowed cleartext local URLs return
    /// a warning that the caller should surface in provider setup.
    pub fn prepare(&self, method: Method, url: &Url) -> Result<PreparedRequest, UrlPolicyError> {
        let policy = classify_url(url)?;
        Ok(PreparedRequest {
            builder: self.client.request(method, policy.url().clone()),
            warning: policy.warning().map(str::to_owned),
        })
    }

    /// Execute a prepared request and normalize transport errors.
    pub async fn execute(&self, request: reqwest::Request) -> Result<Response, NetworkError> {
        self.client
            .execute(request)
            .await
            .map_err(NetworkError::from_reqwest)
    }

    /// Send a request builder and normalize transport errors.
    pub async fn send(&self, request: RequestBuilder) -> Result<Response, NetworkError> {
        request.send().await.map_err(NetworkError::from_reqwest)
    }

    /// Execute a non-streaming request with a total timeout.
    ///
    /// Streaming requests must use [`HttpClient::execute`] or
    /// [`HttpClient::send`] so the inactivity watchdog, not a total timeout,
    /// governs them.
    pub async fn execute_with_timeout(
        &self,
        request: reqwest::Request,
        timeout: Duration,
    ) -> Result<Response, NetworkError> {
        match tokio::time::timeout(timeout, self.client.execute(request)).await {
            Ok(Ok(response)) => Ok(response),
            Ok(Err(error)) => Err(NetworkError::from_reqwest(error)),
            Err(_) => Err(NetworkError::timeout()),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::error::NetworkErrorKind;
    use wiremock::matchers::{header, method, path};
    use wiremock::{Mock, MockServer, ResponseTemplate};

    #[test]
    fn client_constants_match_spec() {
        assert_eq!(CONNECT_TIMEOUT, Duration::from_secs(30));
        assert_eq!(MAX_REDIRECTS, 10);
        assert!(USER_AGENT.starts_with("ChatUI/"));
        assert!(!USER_AGENT.contains('@'));
    }

    #[test]
    fn compares_origins() {
        let a = Url::parse("https://example.com/a").expect("url");
        let b = Url::parse("https://example.com/b").expect("url");
        let c = Url::parse("https://other.example.com/a").expect("url");
        let d = Url::parse("http://example.com/a").expect("url");

        assert!(same_origin(&a, &b));
        assert!(!same_origin(&a, &c));
        assert!(!same_origin(&a, &d));
    }

    #[test]
    fn prepare_rejects_cleartext_public_url() {
        let client = HttpClient::new().expect("client");
        let url = Url::parse("http://api.example.com/v1").expect("url");
        let error = client.prepare(Method::GET, &url).expect_err("rejected");
        assert!(matches!(error, UrlPolicyError::CleartextNotAllowed { .. }));
    }

    #[test]
    fn prepare_warns_for_local_cleartext() {
        let client = HttpClient::new().expect("client");
        let url = Url::parse("http://localhost:11434/api/tags").expect("url");
        let prepared = client.prepare(Method::GET, &url).expect("prepared");
        assert!(prepared.warning.is_some());
    }

    #[tokio::test]
    async fn sends_consistent_user_agent() {
        let server = MockServer::start().await;
        Mock::given(method("GET"))
            .and(path("/"))
            .and(header("user-agent", USER_AGENT))
            .respond_with(ResponseTemplate::new(200))
            .mount(&server)
            .await;

        let client = build_http_client().expect("client");
        let response = client
            .get(format!("{}/", server.uri()))
            .send()
            .await
            .expect("send");
        assert_eq!(response.status(), reqwest::StatusCode::OK);
    }

    #[tokio::test]
    async fn refuses_cross_origin_redirects() {
        let target = MockServer::start().await;
        Mock::given(method("GET"))
            .and(path("/secret"))
            .respond_with(ResponseTemplate::new(200))
            .mount(&target)
            .await;

        let source = MockServer::start().await;
        Mock::given(method("GET"))
            .and(path("/start"))
            .respond_with(
                ResponseTemplate::new(302)
                    .insert_header("Location", format!("{}/secret", target.uri())),
            )
            .mount(&source)
            .await;

        let client = build_http_client().expect("client");
        let response = client
            .get(format!("{}/start", source.uri()))
            .send()
            .await
            .expect("send");

        assert_eq!(response.status(), reqwest::StatusCode::FOUND);
        let requests = target.received_requests().await.expect("recording enabled");
        assert!(
            requests.is_empty(),
            "cross-origin redirect must not be followed"
        );
    }

    #[tokio::test]
    async fn follows_same_origin_redirects() {
        let server = MockServer::start().await;
        Mock::given(method("GET"))
            .and(path("/start"))
            .respond_with(ResponseTemplate::new(302).insert_header("Location", "/end"))
            .mount(&server)
            .await;
        Mock::given(method("GET"))
            .and(path("/end"))
            .respond_with(ResponseTemplate::new(200).set_body_string("ok"))
            .mount(&server)
            .await;

        let client = build_http_client().expect("client");
        let response = client
            .get(format!("{}/start", server.uri()))
            .send()
            .await
            .expect("send");
        assert_eq!(response.status(), reqwest::StatusCode::OK);
        assert_eq!(response.text().await.expect("body"), "ok");
    }

    #[tokio::test]
    async fn applies_secret_header_and_redacts_debug() {
        let server = MockServer::start().await;
        Mock::given(method("GET"))
            .and(path("/"))
            .and(header("authorization", "Bearer test-secret-value"))
            .respond_with(ResponseTemplate::new(200))
            .mount(&server)
            .await;

        let header = SecretHeader::new(reqwest::header::AUTHORIZATION, "Bearer test-secret-value")
            .expect("header");
        let debug = format!("{header:?}");
        assert!(!debug.contains("test-secret-value"));
        assert!(debug.contains("<redacted>"));

        let client = build_http_client().expect("client");
        let response = header
            .apply(client.get(format!("{}/", server.uri())))
            .send()
            .await
            .expect("send");
        assert_eq!(response.status(), reqwest::StatusCode::OK);
    }

    #[tokio::test]
    async fn execute_with_timeout_maps_timeout() {
        let server = MockServer::start().await;
        Mock::given(method("GET"))
            .and(path("/slow"))
            .respond_with(ResponseTemplate::new(200).set_delay(Duration::from_secs(5)))
            .mount(&server)
            .await;

        let client = HttpClient::new().expect("client");
        let request = client
            .client()
            .get(format!("{}/slow", server.uri()))
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
    async fn maps_connection_failure() {
        let listener = std::net::TcpListener::bind("127.0.0.1:0").expect("bind");
        let port = listener.local_addr().expect("addr").port();
        drop(listener);

        let client = build_http_client().expect("client");
        let error = client
            .get(format!("http://127.0.0.1:{port}/"))
            .send()
            .await
            .expect_err("connection should fail");
        let mapped = NetworkError::from_reqwest(error);
        assert!(matches!(
            mapped.kind,
            NetworkErrorKind::Connection | NetworkErrorKind::Dns
        ));
        assert!(mapped.retryable);
        assert!(!mapped.message.contains("127.0.0.1"));
    }
}
