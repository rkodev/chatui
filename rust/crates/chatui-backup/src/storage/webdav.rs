//! WebDAV adapter.
//!
//! Uses HTTP Basic authentication and the WebDAV methods PUT/GET/HEAD/DELETE
//! plus PROPFIND for listing. WebDAV servers may be eventually consistent, so
//! writes are re-read conservatively. The adapter only accepts byte slices, so
//! callers must encrypt before writing.

use std::time::Duration;

use async_trait::async_trait;
use quick_xml::Reader;
use quick_xml::events::Event;
use reqwest::Method;
use url::Url;

use crate::storage::common::{
    base64_encode, ensure_ok, full_key, header, logical_key, normalize_prefix, percent_decode,
};
use crate::storage::{
    ListObjectsOptions, ListObjectsResult, PutObjectOptions, StorageAdapter, StorageError,
    StorageErrorCode, StorageObject,
};

const PROPFIND_BODY: &str = r#"<?xml version="1.0" encoding="utf-8"?>
<d:propfind xmlns:d="DAV:">
  <d:prop>
    <d:resourcetype/>
    <d:getetag/>
    <d:getlastmodified/>
  </d:prop>
</d:propfind>"#;

/// Configuration for a WebDAV collection.
#[derive(Clone, Debug)]
pub struct WebDavStorageConfig {
    /// Base collection URL.
    pub url: String,
    /// Basic-auth username.
    pub username: String,
    /// Basic-auth password.
    pub password: String,
    /// Optional key prefix.
    pub prefix: Option<String>,
    /// Delay between read-after-write retries. Defaults to 100 ms.
    pub retry_delay: Duration,
    /// Number of HEAD attempts after a write that omits an ETag.
    pub read_after_write_attempts: usize,
}

impl Default for WebDavStorageConfig {
    fn default() -> Self {
        Self {
            url: String::new(),
            username: String::new(),
            password: String::new(),
            prefix: None,
            retry_delay: Duration::from_millis(100),
            read_after_write_attempts: 3,
        }
    }
}

/// WebDAV adapter.
pub struct WebDavStorageAdapter {
    base_url: Url,
    authorization: String,
    prefix: String,
    client: reqwest::Client,
    retry_delay: Duration,
    read_after_write_attempts: usize,
}

impl WebDavStorageAdapter {
    /// Build an adapter from configuration.
    pub fn new(config: WebDavStorageConfig) -> Result<Self, StorageError> {
        if config.url.trim().is_empty() {
            return Err(StorageError::server("WebDAV URL must not be empty"));
        }
        if config.username.is_empty() {
            return Err(StorageError::server("WebDAV username must not be empty"));
        }
        if config.password.is_empty() {
            return Err(StorageError::server("WebDAV password must not be empty"));
        }
        if config.read_after_write_attempts == 0 {
            return Err(StorageError::server(
                "read_after_write_attempts must be positive",
            ));
        }

        let mut base_url = Url::parse(config.url.trim())
            .map_err(|error| StorageError::server(format!("invalid WebDAV URL: {error}")))?;
        if base_url.scheme() != "https" && base_url.scheme() != "http" {
            return Err(StorageError::server("WebDAV URL must use http or https"));
        }
        base_url.set_query(None);
        base_url.set_fragment(None);
        if !base_url.path().ends_with('/') {
            let path = format!("{}/", base_url.path().trim_end_matches('/'));
            base_url.set_path(&path);
        }

        let authorization = format!(
            "Basic {}",
            base64_encode(format!("{}:{}", config.username, config.password).as_bytes())
        );
        let client = reqwest::Client::builder().build().map_err(|error| {
            StorageError::server(format!("failed to build HTTP client: {error}"))
        })?;

        Ok(Self {
            base_url,
            authorization,
            prefix: normalize_prefix(config.prefix.as_deref()),
            client,
            retry_delay: config.retry_delay,
            read_after_write_attempts: config.read_after_write_attempts,
        })
    }

    /// Object URL. Visible for URL tests.
    pub(crate) fn object_url(&self, key: &str) -> Result<Url, StorageError> {
        let full_key = full_key(&self.prefix, key)?;
        let mut url = self.base_url.clone();
        {
            let mut segments = url.path_segments_mut().expect("WebDAV base URL");
            segments.pop_if_empty();
            for segment in full_key.split('/') {
                segments.push(segment);
            }
        }
        Ok(url)
    }

    async fn send(
        &self,
        method: Method,
        url: Url,
        headers: Vec<(String, String)>,
        payload: Option<&[u8]>,
    ) -> Result<reqwest::Response, StorageError> {
        let method_name = method.as_str().to_string();
        let mut builder = self.client.request(method, url.clone());
        builder = builder.header("authorization", &self.authorization);
        for (name, value) in &headers {
            builder = builder.header(name, value);
        }
        if let Some(bytes) = payload {
            builder = builder.body(bytes.to_vec());
        }
        builder.send().await.map_err(|error| {
            StorageError::network(format!("{} {} failed: {error}", method_name, url.path()))
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
}

#[async_trait]
impl StorageAdapter for WebDavStorageAdapter {
    async fn test_connection(&self) -> Result<(), StorageError> {
        let response = self
            .send(
                propfind_method(),
                self.base_url.clone(),
                vec![
                    ("depth".to_string(), "0".to_string()),
                    (
                        "content-type".to_string(),
                        "application/xml; charset=utf-8".to_string(),
                    ),
                ],
                Some(PROPFIND_BODY.as_bytes()),
            )
            .await?;
        ensure_ok(response, "PROPFIND root").await?;
        Ok(())
    }

    async fn get_object(&self, key: &str) -> Result<StorageObject, StorageError> {
        let url = self.object_url(key)?;
        let response = self.send(Method::GET, url, Vec::new(), None).await?;
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
            .send(Method::PUT, url, headers, Some(encrypted_bytes))
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
        let response = self.send(Method::HEAD, url, Vec::new(), None).await?;
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
        let response = self.send(Method::DELETE, url, Vec::new(), None).await?;
        if response.status().as_u16() == 404 {
            return Ok(());
        }
        ensure_ok(response, &format!("DELETE {key}")).await?;
        Ok(())
    }

    async fn list_objects(
        &self,
        options: ListObjectsOptions,
    ) -> Result<ListObjectsResult, StorageError> {
        let response = self
            .send(
                propfind_method(),
                self.base_url.clone(),
                vec![
                    ("depth".to_string(), "1".to_string()),
                    (
                        "content-type".to_string(),
                        "application/xml; charset=utf-8".to_string(),
                    ),
                ],
                Some(PROPFIND_BODY.as_bytes()),
            )
            .await?;
        let response = ensure_ok(response, "PROPFIND root").await?;
        let text = response
            .text()
            .await
            .map_err(|error| StorageError::network(format!("PROPFIND body failed: {error}")))?;

        let prefix = options
            .prefix
            .as_deref()
            .unwrap_or_default()
            .trim_start_matches('/');
        let objects = parse_list_response(&text, &self.base_url, &self.prefix)?
            .into_iter()
            .filter(|object| prefix.is_empty() || object.key.starts_with(prefix))
            .collect();
        Ok(ListObjectsResult {
            objects,
            next_continuation_token: None,
        })
    }
}

fn propfind_method() -> Method {
    Method::from_bytes(b"PROPFIND").expect("PROPFIND is a valid method")
}

fn local_name(name: &[u8]) -> String {
    let name = String::from_utf8_lossy(name);
    name.rsplit(':').next().unwrap_or(&name).to_string()
}

fn parse_list_response(
    xml: &str,
    base_url: &Url,
    prefix: &str,
) -> Result<Vec<StorageObject>, StorageError> {
    let mut reader = Reader::from_str(xml);
    reader.config_mut().trim_text(true);

    let mut objects = Vec::new();
    let mut current: Option<StorageObject> = None;
    let mut href = None;
    let mut is_collection = false;
    let mut current_tag = String::new();

    loop {
        match reader.read_event() {
            Ok(Event::Start(event)) => {
                let name = local_name(event.name().as_ref());
                if name == "response" {
                    current = Some(StorageObject {
                        key: String::new(),
                        bytes: Vec::new(),
                        etag: None,
                        last_modified: None,
                    });
                    href = None;
                    is_collection = false;
                } else if name == "collection" {
                    is_collection = true;
                }
                current_tag = name;
            }
            Ok(Event::Text(event)) => {
                let raw = event.xml10_content().map_err(|error| {
                    StorageError::server(format!("PROPFIND response is not valid XML: {error}"))
                })?;
                let text = quick_xml::escape::unescape(&raw)
                    .map_err(|error| {
                        StorageError::server(format!("PROPFIND response is not valid XML: {error}"))
                    })?
                    .into_owned();
                if let Some(object) = current.as_mut() {
                    match current_tag.as_str() {
                        "href" => href = Some(text),
                        "getetag" => object.etag = Some(text),
                        "getlastmodified" => object.last_modified = Some(text),
                        _ => {}
                    }
                }
            }
            Ok(Event::End(event)) => {
                if local_name(event.name().as_ref()) == "response"
                    && let Some(mut object) = current.take()
                    && !is_collection
                    && let Some(href) = href.take()
                    && let Some(key) = href_to_key(&href, base_url, prefix)
                {
                    object.key = key;
                    objects.push(object);
                }
                current_tag.clear();
            }
            Ok(Event::Eof) => break,
            Ok(_) => {}
            Err(error) => {
                return Err(StorageError::server(format!(
                    "PROPFIND response is not valid XML: {error}"
                )));
            }
        }
    }

    Ok(objects)
}

fn href_to_key(href: &str, base_url: &Url, prefix: &str) -> Option<String> {
    let url = Url::parse(href).or_else(|_| base_url.join(href)).ok()?;
    let path = percent_decode(url.path());
    let base_path = percent_decode(base_url.path());
    let relative = path.strip_prefix(&base_path)?;
    let relative = relative.trim_start_matches('/');
    if relative.is_empty() {
        return None;
    }
    Some(logical_key(prefix, relative))
}
