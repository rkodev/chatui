//! S3-compatible storage adapter.
//!
//! Supports AWS S3, Contabo Object Storage, Cloudflare R2, MinIO, and
//! Backblaze B2 through a custom endpoint. Requests are signed with SigV4 and
//! sent directly from the client; there is no server proxy.
//!
//! The adapter only accepts byte slices. Callers must encrypt with the backup
//! envelope codec before writing.

use std::time::{Duration, SystemTime};

use async_trait::async_trait;
use quick_xml::Reader;
use quick_xml::events::Event;
use reqwest::Method;
use url::Url;

use crate::storage::sigv4::{
    SigV4Credentials, SigV4Method, SigV4SignOptions, encode_rfc3986, sign_request,
};
use crate::storage::{
    ListObjectsOptions, ListObjectsResult, PutObjectOptions, StorageAdapter, StorageError,
    StorageErrorCode, StorageObject,
};

/// Configuration for an S3-compatible bucket.
#[derive(Clone, Debug)]
pub struct S3StorageConfig {
    /// Service endpoint, for example `https://s3.us-east-1.amazonaws.com`.
    pub endpoint: String,
    /// Bucket name.
    pub bucket: String,
    /// Bucket region.
    pub region: String,
    /// Access key id.
    pub access_key_id: String,
    /// Secret access key.
    pub secret_access_key: String,
    /// Optional temporary session token.
    pub session_token: Option<String>,
    /// Optional key prefix, for example `chatui/backups`.
    pub prefix: Option<String>,
    /// Use `endpoint/bucket/key` instead of `bucket.endpoint/key`.
    /// Defaults to path-style for non-AWS endpoints.
    pub force_path_style: Option<bool>,
    /// Delay between read-after-write retries. Defaults to 100 ms.
    pub retry_delay: Duration,
    /// Number of HEAD attempts after a write that omits an ETag.
    pub read_after_write_attempts: usize,
}

impl Default for S3StorageConfig {
    fn default() -> Self {
        Self {
            endpoint: String::new(),
            bucket: String::new(),
            region: String::new(),
            access_key_id: String::new(),
            secret_access_key: String::new(),
            session_token: None,
            prefix: None,
            force_path_style: None,
            retry_delay: Duration::from_millis(100),
            read_after_write_attempts: 3,
        }
    }
}

/// S3-compatible storage adapter.
pub struct S3StorageAdapter {
    endpoint: Url,
    bucket: String,
    region: String,
    credentials: SigV4Credentials,
    prefix: String,
    force_path_style: bool,
    client: reqwest::Client,
    retry_delay: Duration,
    read_after_write_attempts: usize,
}

impl S3StorageAdapter {
    /// Build an adapter from configuration.
    pub fn new(config: S3StorageConfig) -> Result<Self, StorageError> {
        let endpoint = Url::parse(&config.endpoint)
            .map_err(|error| StorageError::server(format!("invalid S3 endpoint: {error}")))?;
        if endpoint.scheme() != "https" && endpoint.scheme() != "http" {
            return Err(StorageError::server("S3 endpoint must use http or https"));
        }
        if config.bucket.trim().is_empty() {
            return Err(StorageError::server("S3 bucket must not be empty"));
        }
        if config.region.trim().is_empty() {
            return Err(StorageError::server("S3 region must not be empty"));
        }
        if config.access_key_id.is_empty() || config.secret_access_key.is_empty() {
            return Err(StorageError::server(
                "S3 access key id and secret access key are required",
            ));
        }
        if config.read_after_write_attempts == 0 {
            return Err(StorageError::server(
                "read_after_write_attempts must be positive",
            ));
        }

        let force_path_style = config
            .force_path_style
            .unwrap_or_else(|| !is_aws_endpoint(endpoint.host_str().unwrap_or_default()));
        let client = reqwest::Client::builder().build().map_err(|error| {
            StorageError::server(format!("failed to build HTTP client: {error}"))
        })?;

        Ok(Self {
            endpoint,
            bucket: config.bucket,
            region: config.region,
            credentials: SigV4Credentials {
                access_key_id: config.access_key_id,
                secret_access_key: config.secret_access_key,
                session_token: config.session_token,
            },
            prefix: config
                .prefix
                .unwrap_or_default()
                .trim_matches('/')
                .to_string(),
            force_path_style,
            client,
            retry_delay: config.retry_delay,
            read_after_write_attempts: config.read_after_write_attempts,
        })
    }

    /// Path-style or virtual-hosted bucket URL. Visible for URL tests.
    pub(crate) fn bucket_url(&self) -> Url {
        let mut url = self.endpoint.clone();
        url.set_query(None);
        url.set_fragment(None);
        if self.force_path_style {
            url.set_path(&join_path(&self.base_path(), &self.bucket));
        } else {
            let host = format!(
                "{}.{}",
                self.bucket,
                self.endpoint.host_str().unwrap_or_default()
            );
            let _ = url.set_host(Some(&host));
            url.set_path(&self.base_path_or_root());
        }
        url
    }

    /// Path-style or virtual-hosted object URL. Visible for URL tests.
    pub(crate) fn object_url(&self, key: &str) -> Result<Url, StorageError> {
        let full_key = self.full_key(key)?;
        let encoded_key = encode_key_path(&full_key);
        let mut url = self.endpoint.clone();
        url.set_query(None);
        url.set_fragment(None);
        if self.force_path_style {
            let base = self.base_path();
            let path = if base.is_empty() {
                format!("/{}/{}", self.bucket, encoded_key)
            } else {
                format!("{base}/{}/{}", self.bucket, encoded_key)
            };
            url.set_path(&path);
        } else {
            let host = format!(
                "{}.{}",
                self.bucket,
                self.endpoint.host_str().unwrap_or_default()
            );
            let _ = url.set_host(Some(&host));
            let base = self.base_path();
            let path = if base.is_empty() {
                format!("/{encoded_key}")
            } else {
                format!("{base}/{encoded_key}")
            };
            url.set_path(&path);
        }
        Ok(url)
    }

    async fn send(
        &self,
        method: SigV4Method,
        url: Url,
        headers: Vec<(String, String)>,
        payload: Option<&[u8]>,
    ) -> Result<reqwest::Response, StorageError> {
        let signed = sign_request(SigV4SignOptions {
            method,
            url: &url,
            region: &self.region,
            credentials: &self.credentials,
            headers: &headers,
            payload: payload.unwrap_or(&[]),
            service: None,
            date: SystemTime::now(),
        });

        let mut builder = self
            .client
            .request(reqwest_method(signed.method), signed.url);
        for (name, value) in &signed.headers {
            // reqwest sets Host from the URL. Signing still includes it.
            if name == "host" {
                continue;
            }
            builder = builder.header(name, value);
        }
        if let Some(bytes) = payload {
            builder = builder.body(bytes.to_vec());
        }

        builder.send().await.map_err(|error| {
            StorageError::network(format!(
                "{} {} failed: {error}",
                method.as_str(),
                url.path()
            ))
        })
    }

    async fn head_after_write(&self, key: &str) -> Result<StorageObject, StorageError> {
        let mut last_error = None;
        for attempt in 0..self.read_after_write_attempts {
            match self.head_object(key).await {
                Ok(object) => return Ok(object),
                Err(error) => {
                    if error.code() != StorageErrorCode::NotFound {
                        return Err(error);
                    }
                    last_error = Some(error);
                    if attempt + 1 < self.read_after_write_attempts && !self.retry_delay.is_zero() {
                        tokio::time::sleep(self.retry_delay * (attempt as u32 + 1)).await;
                    }
                }
            }
        }
        Err(last_error.unwrap_or_else(|| StorageError::NotFound {
            message: format!("HEAD {key} failed after write"),
            status: None,
        }))
    }

    fn base_path(&self) -> String {
        self.endpoint.path().trim_end_matches('/').to_string()
    }

    fn base_path_or_root(&self) -> String {
        let base = self.base_path();
        if base.is_empty() {
            "/".to_string()
        } else {
            base
        }
    }

    fn full_key(&self, key: &str) -> Result<String, StorageError> {
        let normalized = key.trim_start_matches('/');
        if normalized.is_empty() {
            return Err(StorageError::server("Object key must not be empty"));
        }
        if self.prefix.is_empty() {
            Ok(normalized.to_string())
        } else {
            Ok(format!("{}/{normalized}", self.prefix))
        }
    }

    fn full_prefix(&self, prefix: Option<&str>) -> String {
        let extra = prefix.unwrap_or_default().trim_start_matches('/');
        if self.prefix.is_empty() {
            extra.to_string()
        } else if extra.is_empty() {
            format!("{}/", self.prefix)
        } else {
            format!("{}/{extra}", self.prefix)
        }
    }
}

#[async_trait]
impl StorageAdapter for S3StorageAdapter {
    async fn test_connection(&self) -> Result<(), StorageError> {
        let response = self
            .send(SigV4Method::Head, self.bucket_url(), Vec::new(), None)
            .await?;
        ensure_ok(response, "HEAD bucket").await?;
        Ok(())
    }

    async fn get_object(&self, key: &str) -> Result<StorageObject, StorageError> {
        let url = self.object_url(key)?;
        let response = self.send(SigV4Method::Get, url, Vec::new(), None).await?;
        let response = ensure_ok(response, &format!("GET {key}")).await?;
        let etag = header(&response, "etag");
        let last_modified = header(&response, "last-modified");
        let bytes = response
            .bytes()
            .await
            .map_err(|error| StorageError::network(format!("GET {key} body failed: {error}")))?
            .to_vec();
        Ok(StorageObject {
            key: key.to_string(),
            bytes,
            etag,
            last_modified,
        })
    }

    async fn put_object(
        &self,
        key: &str,
        encrypted_bytes: &[u8],
        options: PutObjectOptions,
    ) -> Result<StorageObject, StorageError> {
        if options.if_match.is_some() && options.if_none_match.is_some() {
            return Err(StorageError::Conflict {
                message: "Use either if_match or if_none_match, not both".to_string(),
                status: None,
            });
        }

        let mut headers = vec![(
            "content-type".to_string(),
            options
                .content_type
                .unwrap_or_else(|| "application/octet-stream".to_string()),
        )];
        if let Some(value) = options.if_match {
            headers.push(("if-match".to_string(), value));
        }
        if let Some(value) = options.if_none_match {
            headers.push(("if-none-match".to_string(), value));
        }

        let url = self.object_url(key)?;
        let response = self
            .send(SigV4Method::Put, url, headers, Some(encrypted_bytes))
            .await?;
        let response = ensure_ok(response, &format!("PUT {key}")).await?;
        let etag = header(&response, "etag");
        let last_modified = header(&response, "last-modified");

        if etag.is_some() {
            return Ok(StorageObject {
                key: key.to_string(),
                bytes: encrypted_bytes.to_vec(),
                etag,
                last_modified,
            });
        }

        // Some providers omit ETag on PUT. Re-read conservatively so callers can
        // use the value for the next conditional manifest rewrite.
        match self.head_after_write(key).await {
            Ok(head) => Ok(StorageObject {
                key: key.to_string(),
                bytes: encrypted_bytes.to_vec(),
                etag: head.etag,
                last_modified: head.last_modified.or(last_modified),
            }),
            Err(error) if error.code() == StorageErrorCode::NotFound => Ok(StorageObject {
                key: key.to_string(),
                bytes: encrypted_bytes.to_vec(),
                etag: None,
                last_modified,
            }),
            Err(error) => Err(error),
        }
    }

    async fn head_object(&self, key: &str) -> Result<StorageObject, StorageError> {
        let url = self.object_url(key)?;
        let response = self.send(SigV4Method::Head, url, Vec::new(), None).await?;
        let response = ensure_ok(response, &format!("HEAD {key}")).await?;
        Ok(StorageObject {
            key: key.to_string(),
            bytes: Vec::new(),
            etag: header(&response, "etag"),
            last_modified: header(&response, "last-modified"),
        })
    }

    async fn delete_object(&self, key: &str) -> Result<(), StorageError> {
        let url = self.object_url(key)?;
        let response = self
            .send(SigV4Method::Delete, url, Vec::new(), None)
            .await?;
        ensure_ok(response, &format!("DELETE {key}")).await?;
        Ok(())
    }

    async fn list_objects(
        &self,
        options: ListObjectsOptions,
    ) -> Result<ListObjectsResult, StorageError> {
        let mut url = self.bucket_url();
        {
            let mut pairs = url.query_pairs_mut();
            pairs.append_pair("list-type", "2");
            let prefix = self.full_prefix(options.prefix.as_deref());
            if !prefix.is_empty() {
                pairs.append_pair("prefix", &prefix);
            }
            if let Some(token) = options.continuation_token.as_deref() {
                pairs.append_pair("continuation-token", token);
            }
            if let Some(max_keys) = options.max_keys {
                pairs.append_pair("max-keys", &max_keys.to_string());
            }
        }

        let response = self.send(SigV4Method::Get, url, Vec::new(), None).await?;
        let response = ensure_ok(response, "LIST bucket").await?;
        let text = response
            .text()
            .await
            .map_err(|error| StorageError::network(format!("LIST bucket body failed: {error}")))?;
        parse_list_response(&text, &self.prefix)
    }
}

fn reqwest_method(method: SigV4Method) -> Method {
    match method {
        SigV4Method::Get => Method::GET,
        SigV4Method::Put => Method::PUT,
        SigV4Method::Head => Method::HEAD,
        SigV4Method::Delete => Method::DELETE,
    }
}

async fn ensure_ok(
    response: reqwest::Response,
    action: &str,
) -> Result<reqwest::Response, StorageError> {
    if response.status().is_success() {
        return Ok(response);
    }
    let status = response.status().as_u16();
    let detail = response.text().await.unwrap_or_default();
    let detail: String = detail.trim().chars().take(500).collect();
    let suffix = if detail.is_empty() {
        String::new()
    } else {
        format!(": {detail}")
    };
    Err(StorageError::from_status(
        status,
        format!("{action} failed with HTTP {status}{suffix}"),
    ))
}

fn header(response: &reqwest::Response, name: &str) -> Option<String> {
    response
        .headers()
        .get(name)
        .and_then(|value| value.to_str().ok())
        .filter(|value| !value.is_empty())
        .map(str::to_string)
}

fn join_path(base: &str, segment: &str) -> String {
    if base.is_empty() {
        format!("/{segment}")
    } else {
        format!("{base}/{segment}")
    }
}

fn encode_key_path(key: &str) -> String {
    key.split('/')
        .map(encode_rfc3986)
        .collect::<Vec<_>>()
        .join("/")
}

fn logical_key(prefix: &str, actual: &str) -> String {
    if prefix.is_empty() {
        return actual.to_string();
    }
    let prefix_with_slash = format!("{prefix}/");
    actual
        .strip_prefix(&prefix_with_slash)
        .unwrap_or(actual)
        .to_string()
}

fn is_aws_endpoint(hostname: &str) -> bool {
    hostname == "s3.amazonaws.com" || hostname.ends_with(".amazonaws.com")
}

fn parse_list_response(xml: &str, prefix: &str) -> Result<ListObjectsResult, StorageError> {
    let mut reader = Reader::from_str(xml);
    reader.config_mut().trim_text(true);

    let mut objects = Vec::new();
    let mut current: Option<StorageObject> = None;
    let mut current_tag = String::new();
    let mut next_continuation_token = None;

    loop {
        match reader.read_event() {
            Ok(Event::Start(event)) => {
                let name = String::from_utf8_lossy(event.name().as_ref()).into_owned();
                if name == "Contents" {
                    current = Some(StorageObject {
                        key: String::new(),
                        bytes: Vec::new(),
                        etag: None,
                        last_modified: None,
                    });
                }
                current_tag = name;
            }
            Ok(Event::Text(event)) => {
                let raw = event.xml10_content().map_err(|error| {
                    StorageError::server(format!("List response is not valid XML: {error}"))
                })?;
                let text = quick_xml::escape::unescape(&raw)
                    .map_err(|error| {
                        StorageError::server(format!("List response is not valid XML: {error}"))
                    })?
                    .into_owned();
                if let Some(object) = current.as_mut() {
                    match current_tag.as_str() {
                        "Key" => object.key = text,
                        "ETag" => object.etag = Some(text),
                        "LastModified" => object.last_modified = Some(text),
                        _ => {}
                    }
                } else if current_tag == "NextContinuationToken" {
                    next_continuation_token = Some(text);
                }
            }
            Ok(Event::End(event)) => {
                if event.name().as_ref() == b"Contents"
                    && let Some(mut object) = current.take()
                {
                    if object.key.is_empty() {
                        return Err(StorageError::server("List response entry is missing Key"));
                    }
                    object.key = logical_key(prefix, &object.key);
                    objects.push(object);
                }
                current_tag.clear();
            }
            Ok(Event::Eof) => break,
            Ok(_) => {}
            Err(error) => {
                return Err(StorageError::server(format!(
                    "List response is not valid XML: {error}"
                )));
            }
        }
    }

    Ok(ListObjectsResult {
        objects,
        next_continuation_token,
    })
}
