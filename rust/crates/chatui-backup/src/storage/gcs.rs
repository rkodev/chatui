//! Google Cloud Storage adapter.
//!
//! Uses a service account JSON key to sign a JWT, exchanges it for an OAuth2
//! access token, and then calls the GCS JSON API directly from the client.
//! There is no server proxy and no cloud SDK. The adapter only accepts byte
//! slices, so callers must encrypt before writing.

use std::sync::Mutex;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use async_trait::async_trait;
use reqwest::Method;
use rsa::PrivateKey;
use rsa::pkcs1v15::SigningKey;
use rsa::pkcs8::DecodePrivateKey;
use rsa::sha2::Sha256;
use rsa::signature::{SignatureEncoding, Signer};
use serde::Deserialize;
use url::Url;

use crate::storage::common::{
    base64_url_no_pad, ensure_ok, full_key, full_prefix, header, logical_key, normalize_prefix,
};
use crate::storage::{
    ListObjectsOptions, ListObjectsResult, PutObjectOptions, StorageAdapter, StorageError,
    StorageErrorCode, StorageObject,
};

const DEFAULT_TOKEN_URI: &str = "https://oauth2.googleapis.com/token";
const GCS_SCOPE: &str = "https://www.googleapis.com/auth/devstorage.read_write";

/// Service account fields used by the GCS adapter.
#[derive(Clone, Debug, Deserialize)]
pub struct GcsServiceAccount {
    /// Service account email.
    pub client_email: String,
    /// PEM-encoded PKCS#8 private key.
    pub private_key: String,
    /// Optional token endpoint override.
    #[serde(default)]
    pub token_uri: Option<String>,
}

/// Configuration for a GCS bucket.
#[derive(Clone, Debug)]
pub struct GcsStorageConfig {
    /// Bucket name.
    pub bucket: String,
    /// Service account JSON, as a string.
    pub service_account_json: String,
    /// Optional key prefix.
    pub prefix: Option<String>,
    /// Optional endpoint. Defaults to `https://storage.googleapis.com`.
    pub endpoint: Option<String>,
    /// Delay between read-after-write retries. Defaults to 100 ms.
    pub retry_delay: Duration,
    /// Number of metadata reads after a write that omits a generation.
    pub read_after_write_attempts: usize,
}

impl Default for GcsStorageConfig {
    fn default() -> Self {
        Self {
            bucket: String::new(),
            service_account_json: String::new(),
            prefix: None,
            endpoint: None,
            retry_delay: Duration::from_millis(100),
            read_after_write_attempts: 3,
        }
    }
}

#[derive(Clone, Debug, Deserialize)]
struct GcsObjectMetadata {
    #[serde(default)]
    name: Option<String>,
    #[serde(default)]
    generation: Option<String>,
    #[serde(default)]
    etag: Option<String>,
    #[serde(default)]
    updated: Option<String>,
}

impl GcsObjectMetadata {
    fn version(&self) -> Option<String> {
        self.generation
            .clone()
            .filter(|value| !value.is_empty())
            .or_else(|| self.etag.clone().filter(|value| !value.is_empty()))
    }
}

#[derive(Clone, Debug)]
struct CachedToken {
    token: String,
    expires_at: SystemTime,
}

/// Google Cloud Storage adapter.
pub struct GcsStorageAdapter {
    bucket: String,
    service_account: GcsServiceAccount,
    prefix: String,
    endpoint: Url,
    client: reqwest::Client,
    retry_delay: Duration,
    read_after_write_attempts: usize,
    token: Mutex<Option<CachedToken>>,
}

impl GcsStorageAdapter {
    /// Build an adapter from configuration.
    pub fn new(config: GcsStorageConfig) -> Result<Self, StorageError> {
        if config.bucket.trim().is_empty() {
            return Err(StorageError::server("GCS bucket must not be empty"));
        }
        let service_account: GcsServiceAccount = serde_json::from_str(&config.service_account_json)
            .map_err(|error| {
                StorageError::server(format!("GCS service account JSON is invalid: {error}"))
            })?;
        if service_account.client_email.trim().is_empty() {
            return Err(StorageError::server(
                "GCS service account JSON is missing client_email",
            ));
        }
        if service_account.private_key.trim().is_empty() {
            return Err(StorageError::server(
                "GCS service account JSON is missing private_key",
            ));
        }
        if config.read_after_write_attempts == 0 {
            return Err(StorageError::server(
                "read_after_write_attempts must be positive",
            ));
        }

        let endpoint = config
            .endpoint
            .unwrap_or_else(|| "https://storage.googleapis.com".to_string());
        let endpoint = Url::parse(&endpoint)
            .map_err(|error| StorageError::server(format!("invalid GCS endpoint: {error}")))?;
        if endpoint.scheme() != "https" && endpoint.scheme() != "http" {
            return Err(StorageError::server("GCS endpoint must use http or https"));
        }

        let client = reqwest::Client::builder().build().map_err(|error| {
            StorageError::server(format!("failed to build HTTP client: {error}"))
        })?;

        Ok(Self {
            bucket: config.bucket,
            service_account,
            prefix: normalize_prefix(config.prefix.as_deref()),
            endpoint,
            client,
            retry_delay: config.retry_delay,
            read_after_write_attempts: config.read_after_write_attempts,
            token: Mutex::new(None),
        })
    }

    fn list_url(&self) -> Url {
        let mut url = self.endpoint.clone();
        url.set_query(None);
        url.set_fragment(None);
        {
            let mut segments = url.path_segments_mut().expect("GCS endpoint is a base URL");
            segments.pop_if_empty();
            segments.extend(["storage", "v1", "b", &self.bucket, "o"]);
        }
        url
    }

    fn object_url(&self, key: &str) -> Result<Url, StorageError> {
        let full_key = full_key(&self.prefix, key)?;
        let mut url = self.endpoint.clone();
        url.set_query(None);
        url.set_fragment(None);
        {
            let mut segments = url.path_segments_mut().expect("GCS endpoint is a base URL");
            segments.pop_if_empty();
            segments.extend(["storage", "v1", "b", &self.bucket, "o", &full_key]);
        }
        Ok(url)
    }

    fn upload_url(&self) -> Url {
        let mut url = self.endpoint.clone();
        url.set_query(None);
        url.set_fragment(None);
        {
            let mut segments = url.path_segments_mut().expect("GCS endpoint is a base URL");
            segments.pop_if_empty();
            segments.extend(["upload", "storage", "v1", "b", &self.bucket, "o"]);
        }
        url
    }

    async fn send(
        &self,
        method: Method,
        url: Url,
        headers: Vec<(String, String)>,
        payload: Option<&[u8]>,
    ) -> Result<reqwest::Response, StorageError> {
        let token = self.access_token().await?;
        let method_name = method.as_str().to_string();
        let mut builder = self.client.request(method, url.clone());
        builder = builder.header("authorization", format!("Bearer {token}"));
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

    async fn access_token(&self) -> Result<String, StorageError> {
        let now = SystemTime::now();
        if let Some(cached) = self.token.lock().expect("token lock").as_ref()
            && cached.expires_at > now + Duration::from_secs(60)
        {
            return Ok(cached.token.clone());
        }

        let token = self.exchange_token(now).await?;
        *self.token.lock().expect("token lock") = Some(token.clone());
        Ok(token.token)
    }

    async fn exchange_token(&self, now: SystemTime) -> Result<CachedToken, StorageError> {
        let issued_at = now
            .duration_since(UNIX_EPOCH)
            .map_err(|error| StorageError::server(format!("system clock error: {error}")))?
            .as_secs();
        let token_uri = self
            .service_account
            .token_uri
            .clone()
            .unwrap_or_else(|| DEFAULT_TOKEN_URI.to_string());

        let header = base64_url_no_pad(br#"{"alg":"RS256","typ":"JWT"}"#);
        let claims = serde_json::json!({
            "iss": self.service_account.client_email,
            "scope": GCS_SCOPE,
            "aud": token_uri,
            "iat": issued_at,
            "exp": issued_at + 3600,
        });
        let claims = base64_url_no_pad(claims.to_string().as_bytes());
        let signing_input = format!("{header}.{claims}");

        let key =
            PrivateKey::from_pkcs8_pem(&self.service_account.private_key).map_err(|error| {
                StorageError::Auth {
                    message: format!("Failed to parse GCS private key: {error}"),
                    status: None,
                }
            })?;
        let signing_key = SigningKey::<Sha256>::new(key);
        let signature = signing_key.sign(signing_input.as_bytes()).to_vec();
        let assertion = format!("{signing_input}.{}", base64_url_no_pad(&signature));

        let response = self
            .client
            .post(&token_uri)
            .form(&[
                ("grant_type", "urn:ietf:params:oauth:grant-type:jwt-bearer"),
                ("assertion", assertion.as_str()),
            ])
            .send()
            .await
            .map_err(|error| {
                StorageError::network(format!("GCS OAuth token exchange failed: {error}"))
            })?;

        if !response.status().is_success() {
            let status = response.status().as_u16();
            let detail = response.text().await.unwrap_or_default();
            let detail: String = detail.trim().chars().take(500).collect();
            let message = format!("GCS OAuth token exchange failed with HTTP {status}: {detail}");
            if status == 400 || status == 401 {
                return Err(StorageError::Auth {
                    message,
                    status: Some(status),
                });
            }
            return Err(StorageError::from_status(status, message));
        }

        #[derive(Deserialize)]
        struct TokenResponse {
            access_token: Option<String>,
            expires_in: Option<u64>,
        }
        let token: TokenResponse = response.json().await.map_err(|error| {
            StorageError::server(format!("GCS OAuth token response is invalid: {error}"))
        })?;
        let access_token =
            token
                .access_token
                .filter(|value| !value.is_empty())
                .ok_or(StorageError::Auth {
                    message: "GCS OAuth token response is missing access_token".to_string(),
                    status: None,
                })?;
        Ok(CachedToken {
            token: access_token,
            expires_at: now + Duration::from_secs(token.expires_in.unwrap_or(3600)),
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
impl StorageAdapter for GcsStorageAdapter {
    async fn test_connection(&self) -> Result<(), StorageError> {
        let mut url = self.list_url();
        url.query_pairs_mut().append_pair("maxResults", "1");
        let response = self.send(Method::GET, url, Vec::new(), None).await?;
        ensure_ok(response, "LIST bucket").await?;
        Ok(())
    }

    async fn get_object(&self, key: &str) -> Result<StorageObject, StorageError> {
        let mut url = self.object_url(key)?;
        url.query_pairs_mut().append_pair("alt", "media");
        let response = self.send(Method::GET, url, Vec::new(), None).await?;
        let response = ensure_ok(response, &format!("GET {key}")).await?;
        let etag = header(&response, "x-goog-generation").or_else(|| header(&response, "etag"));
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

        let mut url = self.upload_url();
        {
            let mut pairs = url.query_pairs_mut();
            pairs.append_pair("uploadType", "media");
            pairs.append_pair("name", &full_key(&self.prefix, key)?);
            if let Some(value) = options.if_match.as_deref() {
                pairs.append_pair("ifGenerationMatch", value);
            }
            if let Some(value) = options.if_none_match.as_deref() {
                if value == "*" {
                    pairs.append_pair("ifGenerationMatch", "0");
                } else {
                    pairs.append_pair("ifGenerationNotMatch", value);
                }
            }
        }

        let headers = vec![(
            "content-type".to_string(),
            options
                .content_type
                .unwrap_or_else(|| "application/octet-stream".to_string()),
        )];
        let response = self
            .send(Method::POST, url, headers, Some(encrypted_bytes))
            .await?;
        let response = ensure_ok(response, &format!("PUT {key}")).await?;
        let metadata: GcsObjectMetadata = response.json().await.map_err(|error| {
            StorageError::server(format!("GCS upload response is invalid: {error}"))
        })?;

        if let Some(etag) = metadata.version() {
            return Ok(StorageObject {
                key: key.to_string(),
                bytes: encrypted_bytes.to_vec(),
                etag: Some(etag),
                last_modified: metadata.updated,
            });
        }

        match self.head_after_write(key).await {
            Ok(head) => Ok(StorageObject {
                key: key.to_string(),
                bytes: encrypted_bytes.to_vec(),
                etag: head.etag,
                last_modified: head.last_modified,
            }),
            Err(error) if error.code() == StorageErrorCode::NotFound => Ok(StorageObject {
                key: key.to_string(),
                bytes: encrypted_bytes.to_vec(),
                etag: None,
                last_modified: None,
            }),
            Err(error) => Err(error),
        }
    }

    async fn head_object(&self, key: &str) -> Result<StorageObject, StorageError> {
        let url = self.object_url(key)?;
        let response = self.send(Method::GET, url, Vec::new(), None).await?;
        let response = ensure_ok(response, &format!("HEAD {key}")).await?;
        let metadata: GcsObjectMetadata = response.json().await.map_err(|error| {
            StorageError::server(format!("GCS metadata response is invalid: {error}"))
        })?;
        Ok(StorageObject {
            key: key.to_string(),
            bytes: Vec::new(),
            etag: metadata.version(),
            last_modified: metadata.updated,
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
        let mut url = self.list_url();
        {
            let mut pairs = url.query_pairs_mut();
            let prefix = full_prefix(&self.prefix, options.prefix.as_deref());
            if !prefix.is_empty() {
                pairs.append_pair("prefix", &prefix);
            }
            if let Some(token) = options.continuation_token.as_deref() {
                pairs.append_pair("pageToken", token);
            }
            if let Some(max_keys) = options.max_keys {
                pairs.append_pair("maxResults", &max_keys.to_string());
            }
        }

        let response = self.send(Method::GET, url, Vec::new(), None).await?;
        let response = ensure_ok(response, "LIST bucket").await?;
        #[derive(Deserialize)]
        struct ListResponse {
            #[serde(default)]
            items: Vec<GcsObjectMetadata>,
            #[serde(default, rename = "nextPageToken")]
            next_page_token: Option<String>,
        }
        let body: ListResponse = response.json().await.map_err(|error| {
            StorageError::server(format!("GCS list response is invalid: {error}"))
        })?;

        let mut objects = Vec::with_capacity(body.items.len());
        for item in body.items {
            let name = item
                .name
                .clone()
                .ok_or_else(|| StorageError::server("List response entry is missing name"))?;
            objects.push(StorageObject {
                key: logical_key(&self.prefix, &name),
                bytes: Vec::new(),
                etag: item.version(),
                last_modified: item.updated,
            });
        }

        Ok(ListObjectsResult {
            objects,
            next_continuation_token: body.next_page_token,
        })
    }
}
