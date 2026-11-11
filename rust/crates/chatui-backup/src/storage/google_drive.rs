//! Google Drive adapter.
//!
//! Uses an OAuth access token and the Drive v3 REST API. Drive is not object
//! storage: it has no ETag/`If-Match` preconditions. Conditional writes are
//! therefore handled conservatively by comparing the file `version` before a
//! write and re-reading metadata after a write. The adapter only accepts byte
//! slices, so callers must encrypt before writing.

use std::time::Duration;

use async_trait::async_trait;
use reqwest::Method;
use serde::Deserialize;
use url::Url;

use crate::storage::common::{ensure_ok, full_key, full_prefix, logical_key, normalize_prefix};
use crate::storage::{
    ListObjectsOptions, ListObjectsResult, PutObjectOptions, StorageAdapter, StorageError,
    StorageErrorCode, StorageObject,
};

const FILE_FIELDS: &str = "id,name,version,modifiedTime";

/// Configuration for a Google Drive folder.
#[derive(Clone, Debug)]
pub struct GoogleDriveStorageConfig {
    /// OAuth access token.
    pub access_token: String,
    /// Folder id, or `appDataFolder` for the hidden app data folder.
    pub folder_id: Option<String>,
    /// Optional key prefix.
    pub prefix: Option<String>,
    /// Optional endpoint. Defaults to `https://www.googleapis.com`.
    pub endpoint: Option<String>,
    /// Delay between read-after-write retries. Defaults to 100 ms.
    pub retry_delay: Duration,
    /// Number of metadata reads after a write.
    pub read_after_write_attempts: usize,
}

impl Default for GoogleDriveStorageConfig {
    fn default() -> Self {
        Self {
            access_token: String::new(),
            folder_id: None,
            prefix: None,
            endpoint: None,
            retry_delay: Duration::from_millis(100),
            read_after_write_attempts: 3,
        }
    }
}

#[derive(Clone, Debug, Deserialize)]
struct DriveFile {
    id: String,
    name: String,
    #[serde(default)]
    version: Option<String>,
    #[serde(default, rename = "modifiedTime")]
    modified_time: Option<String>,
}

/// Google Drive adapter.
pub struct GoogleDriveStorageAdapter {
    access_token: String,
    folder_id: String,
    prefix: String,
    endpoint: Url,
    client: reqwest::Client,
    retry_delay: Duration,
    read_after_write_attempts: usize,
}

impl GoogleDriveStorageAdapter {
    /// Build an adapter from configuration.
    pub fn new(config: GoogleDriveStorageConfig) -> Result<Self, StorageError> {
        if config.access_token.trim().is_empty() {
            return Err(StorageError::server(
                "Google Drive access token must not be empty",
            ));
        }
        if config.read_after_write_attempts == 0 {
            return Err(StorageError::server(
                "read_after_write_attempts must be positive",
            ));
        }

        let endpoint = config
            .endpoint
            .unwrap_or_else(|| "https://www.googleapis.com".to_string());
        let endpoint = Url::parse(&endpoint).map_err(|error| {
            StorageError::server(format!("invalid Google Drive endpoint: {error}"))
        })?;
        if endpoint.scheme() != "https" && endpoint.scheme() != "http" {
            return Err(StorageError::server(
                "Google Drive endpoint must use http or https",
            ));
        }

        let client = reqwest::Client::builder().build().map_err(|error| {
            StorageError::server(format!("failed to build HTTP client: {error}"))
        })?;

        Ok(Self {
            access_token: config.access_token,
            folder_id: config
                .folder_id
                .filter(|value| !value.trim().is_empty())
                .unwrap_or_else(|| "appDataFolder".to_string()),
            prefix: normalize_prefix(config.prefix.as_deref()),
            endpoint,
            client,
            retry_delay: config.retry_delay,
            read_after_write_attempts: config.read_after_write_attempts,
        })
    }

    fn api_url(&self, segments: &[&str]) -> Url {
        let mut url = self.endpoint.clone();
        url.set_query(None);
        url.set_fragment(None);
        {
            let mut path = url
                .path_segments_mut()
                .expect("Drive endpoint is a base URL");
            path.pop_if_empty();
            path.extend(segments);
        }
        url
    }

    fn spaces(&self) -> &'static str {
        if self.folder_id == "appDataFolder" {
            "appDataFolder"
        } else {
            "drive"
        }
    }

    fn folder_query(&self) -> String {
        format!(
            "'{}' in parents and trashed = false",
            escape_query(&self.folder_id)
        )
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
        builder = builder.header("authorization", format!("Bearer {}", self.access_token));
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

    async fn find_file(&self, key: &str) -> Result<Option<DriveFile>, StorageError> {
        let full_key = full_key(&self.prefix, key)?;
        let mut url = self.api_url(&["drive", "v3", "files"]);
        {
            let mut pairs = url.query_pairs_mut();
            pairs.append_pair(
                "q",
                &format!(
                    "name = '{}' and {}",
                    escape_query(&full_key),
                    self.folder_query()
                ),
            );
            pairs.append_pair("fields", &format!("files({FILE_FIELDS})"));
            pairs.append_pair("spaces", self.spaces());
        }

        let response = self.send(Method::GET, url, Vec::new(), None).await?;
        let response = ensure_ok(response, &format!("FIND {key}")).await?;
        #[derive(Deserialize)]
        struct FindResponse {
            #[serde(default)]
            files: Vec<DriveFile>,
        }
        let body: FindResponse = response.json().await.map_err(|error| {
            StorageError::server(format!("Drive find response is invalid: {error}"))
        })?;
        Ok(body.files.into_iter().next())
    }

    async fn create_file(
        &self,
        key: &str,
        bytes: &[u8],
        content_type: &str,
    ) -> Result<(), StorageError> {
        let boundary = format!(
            "chatui-{}-{:016x}",
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .map(|duration| duration.as_nanos())
                .unwrap_or_default(),
            rand::random::<u64>()
        );
        let metadata = serde_json::json!({
            "name": full_key(&self.prefix, key)?,
            "parents": [self.folder_id],
        });
        let mut body = Vec::new();
        body.extend_from_slice(
            format!(
                "--{boundary}\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n{metadata}\r\n--{boundary}\r\nContent-Type: {content_type}\r\n\r\n"
            )
            .as_bytes(),
        );
        body.extend_from_slice(bytes);
        body.extend_from_slice(format!("\r\n--{boundary}--\r\n").as_bytes());

        let mut url = self.api_url(&["upload", "drive", "v3", "files"]);
        {
            let mut pairs = url.query_pairs_mut();
            pairs.append_pair("uploadType", "multipart");
            pairs.append_pair("fields", FILE_FIELDS);
        }
        let headers = vec![(
            "content-type".to_string(),
            format!("multipart/related; boundary={boundary}"),
        )];
        let response = self.send(Method::POST, url, headers, Some(&body)).await?;
        ensure_ok(response, &format!("PUT {key}")).await?;
        Ok(())
    }

    async fn update_file(
        &self,
        file_id: &str,
        bytes: &[u8],
        content_type: &str,
    ) -> Result<(), StorageError> {
        let mut url = self.api_url(&["upload", "drive", "v3", "files", file_id]);
        {
            let mut pairs = url.query_pairs_mut();
            pairs.append_pair("uploadType", "media");
            pairs.append_pair("fields", FILE_FIELDS);
        }
        let headers = vec![("content-type".to_string(), content_type.to_string())];
        let response = self.send(Method::PATCH, url, headers, Some(bytes)).await?;
        ensure_ok(response, &format!("PUT {file_id}")).await?;
        Ok(())
    }

    async fn read_after_write(&self, key: &str) -> Result<DriveFile, StorageError> {
        let mut last_error = None;
        for attempt in 0..self.read_after_write_attempts {
            match self.find_file(key).await {
                Ok(Some(file)) => return Ok(file),
                Ok(None) => {
                    last_error = Some(StorageError::NotFound {
                        message: format!("PUT {key} failed: file not found"),
                        status: Some(404),
                    });
                }
                Err(error) => {
                    if error.code() != StorageErrorCode::NotFound {
                        return Err(error);
                    }
                    last_error = Some(error);
                }
            }
            if attempt + 1 < self.read_after_write_attempts && !self.retry_delay.is_zero() {
                tokio::time::sleep(self.retry_delay * (attempt as u32 + 1)).await;
            }
        }
        Err(last_error.unwrap_or_else(|| StorageError::NotFound {
            message: format!("PUT {key} failed: file not found"),
            status: Some(404),
        }))
    }
}

#[async_trait]
impl StorageAdapter for GoogleDriveStorageAdapter {
    async fn test_connection(&self) -> Result<(), StorageError> {
        let mut url = self.api_url(&["drive", "v3", "about"]);
        url.query_pairs_mut().append_pair("fields", "user");
        let response = self.send(Method::GET, url, Vec::new(), None).await?;
        ensure_ok(response, "Google Drive about").await?;
        Ok(())
    }

    async fn get_object(&self, key: &str) -> Result<StorageObject, StorageError> {
        let file = self
            .find_file(key)
            .await?
            .ok_or_else(|| StorageError::NotFound {
                message: format!("GET {key} failed: file not found"),
                status: Some(404),
            })?;

        let mut url = self.api_url(&["drive", "v3", "files", &file.id]);
        url.query_pairs_mut().append_pair("alt", "media");
        let response = self.send(Method::GET, url, Vec::new(), None).await?;
        let response = ensure_ok(response, &format!("GET {key}")).await?;
        let bytes = response
            .bytes()
            .await
            .map_err(|error| StorageError::network(format!("GET {key} body failed: {error}")))?
            .to_vec();
        Ok(StorageObject {
            key: key.to_string(),
            bytes,
            etag: file.version,
            last_modified: file.modified_time,
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

        let existing = self.find_file(key).await?;
        if let Some(expected) = options.if_match.as_deref()
            && existing.as_ref().and_then(|file| file.version.as_deref()) != Some(expected)
        {
            return Err(StorageError::Conflict {
                message: format!("PUT {key} failed: version conflict"),
                status: Some(409),
            });
        }
        if options.if_none_match.is_some() && existing.is_some() {
            return Err(StorageError::Conflict {
                message: format!("PUT {key} failed: file already exists"),
                status: Some(409),
            });
        }

        let content_type = options
            .content_type
            .unwrap_or_else(|| "application/octet-stream".to_string());
        if let Some(file) = &existing {
            self.update_file(&file.id, encrypted_bytes, &content_type)
                .await?;
        } else {
            self.create_file(key, encrypted_bytes, &content_type)
                .await?;
        }

        let written = self.read_after_write(key).await?;
        Ok(StorageObject {
            key: key.to_string(),
            bytes: encrypted_bytes.to_vec(),
            etag: written.version,
            last_modified: written.modified_time,
        })
    }

    async fn head_object(&self, key: &str) -> Result<StorageObject, StorageError> {
        let file = self
            .find_file(key)
            .await?
            .ok_or_else(|| StorageError::NotFound {
                message: format!("HEAD {key} failed: file not found"),
                status: Some(404),
            })?;
        Ok(StorageObject {
            key: key.to_string(),
            bytes: Vec::new(),
            etag: file.version,
            last_modified: file.modified_time,
        })
    }

    async fn delete_object(&self, key: &str) -> Result<(), StorageError> {
        let Some(file) = self.find_file(key).await? else {
            return Ok(());
        };
        let url = self.api_url(&["drive", "v3", "files", &file.id]);
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
        let mut url = self.api_url(&["drive", "v3", "files"]);
        {
            let mut pairs = url.query_pairs_mut();
            pairs.append_pair("q", &self.folder_query());
            pairs.append_pair("fields", &format!("nextPageToken,files({FILE_FIELDS})"));
            pairs.append_pair("spaces", self.spaces());
            pairs.append_pair(
                "pageSize",
                &options.max_keys.unwrap_or(1000).min(1000).to_string(),
            );
            if let Some(token) = options.continuation_token.as_deref() {
                pairs.append_pair("pageToken", token);
            }
        }

        let response = self.send(Method::GET, url, Vec::new(), None).await?;
        let response = ensure_ok(response, "LIST folder").await?;
        #[derive(Deserialize)]
        struct ListResponse {
            #[serde(default)]
            files: Vec<DriveFile>,
            #[serde(default, rename = "nextPageToken")]
            next_page_token: Option<String>,
        }
        let body: ListResponse = response.json().await.map_err(|error| {
            StorageError::server(format!("Drive list response is invalid: {error}"))
        })?;

        let prefix = full_prefix(&self.prefix, options.prefix.as_deref());
        let objects = body
            .files
            .into_iter()
            .filter(|file| prefix.is_empty() || file.name.starts_with(&prefix))
            .map(|file| StorageObject {
                key: logical_key(&self.prefix, &file.name),
                bytes: Vec::new(),
                etag: file.version,
                last_modified: file.modified_time,
            })
            .collect();

        Ok(ListObjectsResult {
            objects,
            next_continuation_token: body.next_page_token,
        })
    }
}

fn escape_query(value: &str) -> String {
    value.replace('\\', "\\\\").replace('\'', "\\'")
}
