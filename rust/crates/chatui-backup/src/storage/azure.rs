//! Azure Blob Storage adapter.
//!
//! Supports Shared Key authentication with an account name and key, or a SAS
//! token. Requests are sent directly from the client; there is no server
//! proxy. The adapter only accepts byte slices, so callers must encrypt with
//! the backup envelope codec before writing.

use std::time::{Duration, SystemTime};

use async_trait::async_trait;
use hmac::{Hmac, Mac};
use quick_xml::Reader;
use quick_xml::events::Event;
use reqwest::Method;
use sha2::Sha256;
use url::Url;

use crate::storage::common::{
    base64_decode, base64_encode, ensure_ok, full_key, full_prefix, header, logical_key,
    normalize_prefix, percent_decode,
};
use crate::storage::{
    ListObjectsOptions, ListObjectsResult, PutObjectOptions, StorageAdapter, StorageError,
    StorageErrorCode, StorageObject,
};

const AZURE_VERSION: &str = "2021-12-02";

/// Configuration for an Azure Blob container.
#[derive(Clone, Debug)]
pub struct AzureBlobStorageConfig {
    /// Storage account name.
    pub account_name: String,
    /// Base64-encoded account key. Mutually exclusive with `sas_token`.
    pub account_key: Option<String>,
    /// SAS token, with or without a leading `?`.
    pub sas_token: Option<String>,
    /// Container name.
    pub container: String,
    /// Optional endpoint. Defaults to `https://{account_name}.blob.core.windows.net`.
    pub endpoint: Option<String>,
    /// Optional key prefix.
    pub prefix: Option<String>,
    /// Delay between read-after-write retries. Defaults to 100 ms.
    pub retry_delay: Duration,
    /// Number of HEAD attempts after a write that omits an ETag.
    pub read_after_write_attempts: usize,
}

impl Default for AzureBlobStorageConfig {
    fn default() -> Self {
        Self {
            account_name: String::new(),
            account_key: None,
            sas_token: None,
            container: String::new(),
            endpoint: None,
            prefix: None,
            retry_delay: Duration::from_millis(100),
            read_after_write_attempts: 3,
        }
    }
}

/// Azure Blob Storage adapter.
pub struct AzureBlobStorageAdapter {
    account_name: String,
    account_key: Option<Vec<u8>>,
    sas_token: Option<String>,
    container: String,
    endpoint: Url,
    prefix: String,
    client: reqwest::Client,
    retry_delay: Duration,
    read_after_write_attempts: usize,
}

impl AzureBlobStorageAdapter {
    /// Build an adapter from configuration.
    pub fn new(config: AzureBlobStorageConfig) -> Result<Self, StorageError> {
        if config.account_name.trim().is_empty() {
            return Err(StorageError::server("Azure account name must not be empty"));
        }
        if config.container.trim().is_empty() {
            return Err(StorageError::server("Azure container must not be empty"));
        }
        let account_key = config
            .account_key
            .as_deref()
            .filter(|value| !value.is_empty())
            .map(base64_decode)
            .transpose()?;
        let sas_token = config
            .sas_token
            .as_deref()
            .map(|value| value.trim().trim_start_matches('?'))
            .filter(|value| !value.is_empty())
            .map(str::to_string);
        if account_key.is_none() && sas_token.is_none() {
            return Err(StorageError::server(
                "Azure account key or SAS token is required",
            ));
        }
        if config.read_after_write_attempts == 0 {
            return Err(StorageError::server(
                "read_after_write_attempts must be positive",
            ));
        }

        let endpoint = config.endpoint.unwrap_or_else(|| {
            format!(
                "https://{}.blob.core.windows.net",
                config.account_name.trim()
            )
        });
        let endpoint = Url::parse(&endpoint)
            .map_err(|error| StorageError::server(format!("invalid Azure endpoint: {error}")))?;
        if endpoint.scheme() != "https" && endpoint.scheme() != "http" {
            return Err(StorageError::server(
                "Azure endpoint must use http or https",
            ));
        }

        let client = reqwest::Client::builder().build().map_err(|error| {
            StorageError::server(format!("failed to build HTTP client: {error}"))
        })?;

        Ok(Self {
            account_name: config.account_name.trim().to_string(),
            account_key,
            sas_token,
            container: config.container,
            endpoint,
            prefix: normalize_prefix(config.prefix.as_deref()),
            client,
            retry_delay: config.retry_delay,
            read_after_write_attempts: config.read_after_write_attempts,
        })
    }

    /// Container URL. Visible for URL tests.
    pub(crate) fn container_url(&self) -> Url {
        let mut url = self.endpoint.clone();
        url.set_query(None);
        url.set_fragment(None);
        {
            let mut segments = url
                .path_segments_mut()
                .expect("Azure endpoint is a base URL");
            segments.pop_if_empty();
            segments.push(&self.container);
        }
        if let Some(token) = &self.sas_token {
            url.set_query(Some(token));
        }
        url
    }

    /// Object URL. Visible for URL tests.
    pub(crate) fn object_url(&self, key: &str) -> Result<Url, StorageError> {
        let full_key = full_key(&self.prefix, key)?;
        let mut url = self.container_url();
        {
            let mut segments = url.path_segments_mut().expect("Azure container URL");
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
        let mut headers = headers;
        headers.push((
            "x-ms-date".to_string(),
            httpdate::fmt_http_date(SystemTime::now()),
        ));
        headers.push(("x-ms-version".to_string(), AZURE_VERSION.to_string()));

        if let Some(key) = &self.account_key {
            let content_length = payload
                .map(|bytes| bytes.len().to_string())
                .unwrap_or_default();
            let string_to_sign = string_to_sign(
                method.as_str(),
                &url,
                &headers,
                &content_length,
                &self.account_name,
            );
            let mut mac = Hmac::<Sha256>::new_from_slice(key)
                .map_err(|error| StorageError::server(format!("invalid Azure key: {error}")))?;
            mac.update(string_to_sign.as_bytes());
            let signature = base64_encode(&mac.finalize().into_bytes());
            headers.push((
                "authorization".to_string(),
                format!("SharedKey {}:{signature}", self.account_name),
            ));
        }

        let method_name = method.as_str().to_string();
        let mut builder = self.client.request(method, url.clone());
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
impl StorageAdapter for AzureBlobStorageAdapter {
    async fn test_connection(&self) -> Result<(), StorageError> {
        let mut url = self.container_url();
        append_query(
            &mut url,
            &[("restype".to_string(), "container".to_string())],
        );
        let response = self.send(Method::HEAD, url, Vec::new(), None).await?;
        ensure_ok(response, "HEAD container").await?;
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

        let mut headers = vec![
            (
                "content-type".to_string(),
                options
                    .content_type
                    .unwrap_or_else(|| "application/octet-stream".to_string()),
            ),
            ("x-ms-blob-type".to_string(), "BlockBlob".to_string()),
        ];
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
        ensure_ok(response, &format!("DELETE {key}")).await?;
        Ok(())
    }

    async fn list_objects(
        &self,
        options: ListObjectsOptions,
    ) -> Result<ListObjectsResult, StorageError> {
        let mut url = self.container_url();
        let mut pairs = vec![
            ("restype".to_string(), "container".to_string()),
            ("comp".to_string(), "list".to_string()),
        ];
        let prefix = full_prefix(&self.prefix, options.prefix.as_deref());
        if !prefix.is_empty() {
            pairs.push(("prefix".to_string(), prefix));
        }
        if let Some(token) = options.continuation_token.as_deref() {
            pairs.push(("marker".to_string(), token.to_string()));
        }
        if let Some(max_keys) = options.max_keys {
            pairs.push(("maxresults".to_string(), max_keys.to_string()));
        }
        append_query(&mut url, &pairs);

        let response = self.send(Method::GET, url, Vec::new(), None).await?;
        let response = ensure_ok(response, "LIST container").await?;
        let text = response.text().await.map_err(|error| {
            StorageError::network(format!("LIST container body failed: {error}"))
        })?;
        parse_list_response(&text, &self.prefix)
    }
}

fn append_query(url: &mut Url, pairs: &[(String, String)]) {
    let mut serializer = url::form_urlencoded::Serializer::new(String::new());
    for (name, value) in pairs {
        serializer.append_pair(name, value);
    }
    let encoded = serializer.finish();
    if encoded.is_empty() {
        return;
    }
    let query = match url.query() {
        Some(existing) if !existing.is_empty() => format!("{existing}&{encoded}"),
        _ => encoded,
    };
    url.set_query(Some(&query));
}

fn string_to_sign(
    method: &str,
    url: &Url,
    headers: &[(String, String)],
    content_length: &str,
    account_name: &str,
) -> String {
    let get = |name: &str| {
        headers
            .iter()
            .find(|(key, _)| key.eq_ignore_ascii_case(name))
            .map(|(_, value)| value.as_str())
            .unwrap_or_default()
    };

    let mut canonical_headers: Vec<(String, String)> = headers
        .iter()
        .filter(|(key, _)| key.to_ascii_lowercase().starts_with("x-ms-"))
        .map(|(key, value)| (key.to_ascii_lowercase(), value.trim().to_string()))
        .collect();
    canonical_headers.sort_by(|left, right| left.0.cmp(&right.0));
    let canonical_headers: String = canonical_headers
        .iter()
        .map(|(key, value)| format!("{key}:{value}\n"))
        .collect();

    let canonical_resource = canonicalized_resource(account_name, url);

    format!(
        "{method}\n{}\n{}\n{content_length}\n{}\n{}\n{}\n{}\n{}\n{}\n{}\n{}\n{canonical_headers}{canonical_resource}",
        get("content-encoding"),
        get("content-language"),
        get("content-md5"),
        get("content-type"),
        get("date"),
        get("if-modified-since"),
        get("if-match"),
        get("if-none-match"),
        get("if-unmodified-since"),
        get("range"),
    )
}

fn canonicalized_resource(account_name: &str, url: &Url) -> String {
    let path = percent_decode(url.path());
    let mut resource = format!("/{account_name}{path}");

    let mut parameters: Vec<(String, Vec<String>)> = Vec::new();
    for (name, value) in url.query_pairs() {
        let name = name.to_ascii_lowercase();
        if let Some((_, values)) = parameters.iter_mut().find(|(key, _)| *key == name) {
            values.push(value.into_owned());
        } else {
            parameters.push((name, vec![value.into_owned()]));
        }
    }
    parameters.sort_by(|left, right| left.0.cmp(&right.0));
    for (name, mut values) in parameters {
        values.sort();
        resource.push('\n');
        resource.push_str(&name);
        resource.push(':');
        resource.push_str(&values.join(","));
    }
    resource
}

fn parse_list_response(xml: &str, prefix: &str) -> Result<ListObjectsResult, StorageError> {
    let mut reader = Reader::from_str(xml);
    reader.config_mut().trim_text(true);

    let mut objects = Vec::new();
    let mut current: Option<StorageObject> = None;
    let mut current_tag = String::new();
    let mut next_marker = None;

    loop {
        match reader.read_event() {
            Ok(Event::Start(event)) => {
                let name = String::from_utf8_lossy(event.name().as_ref()).into_owned();
                if name == "Blob" {
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
                        "Name" => object.key = text,
                        "Etag" | "ETag" => object.etag = Some(text),
                        "Last-Modified" => object.last_modified = Some(text),
                        _ => {}
                    }
                } else if current_tag == "NextMarker" {
                    next_marker = Some(text);
                }
            }
            Ok(Event::End(event)) => {
                if event.name().as_ref() == b"Blob"
                    && let Some(mut object) = current.take()
                {
                    if object.key.is_empty() {
                        return Err(StorageError::server("List response entry is missing Name"));
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
        next_continuation_token: next_marker,
    })
}
