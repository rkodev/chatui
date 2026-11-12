use std::time::Duration;

use reqwest::Method;
use wiremock::matchers::{header, method, path, query_param};
use wiremock::{Mock, MockServer, ResponseTemplate};

use crate::storage::common::base64_encode;
use crate::storage::{
    AzureBlobStorageAdapter, AzureBlobStorageConfig, GcsStorageAdapter, GcsStorageConfig,
    GoogleDriveStorageAdapter, GoogleDriveStorageConfig, ListObjectsOptions, PutObjectOptions,
    StorageAdapter, StorageErrorCode, WebDavStorageAdapter, WebDavStorageConfig,
};

/// Synthetic envelope-shaped ciphertext. Tests never pass plaintext to an
/// adapter; these bytes only exercise the transport boundary.
const ENCRYPTED_BYTES: &[u8] = &[
    0x43, 0x48, 0x41, 0x54, 0x55, 0x49, 0x31, 0x0a, 0x00, 0x00, 0x00, 0x02, 0x7b, 0x7d, 0x01, 0x02,
    0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0a, 0x0b, 0x0c, 0xaa, 0xbb, 0xcc, 0xdd,
];

fn gcs_private_key() -> &'static str {
    use std::sync::OnceLock;

    use rsa::Generate;
    use rsa::pkcs8::{EncodePrivateKey, LineEnding};

    static KEY: OnceLock<String> = OnceLock::new();
    KEY.get_or_init(|| {
        let key = rsa::PrivateKey::try_generate().expect("generate test key");
        key.to_pkcs8_pem(LineEnding::LF)
            .expect("encode test key")
            .to_string()
    })
}

fn azure_config(server: &MockServer) -> AzureBlobStorageConfig {
    AzureBlobStorageConfig {
        account_name: "acct".to_string(),
        account_key: Some(base64_encode(b"test-account-key")),
        container: "container".to_string(),
        endpoint: Some(server.uri()),
        retry_delay: Duration::ZERO,
        ..Default::default()
    }
}

fn azure(server: &MockServer) -> AzureBlobStorageAdapter {
    AzureBlobStorageAdapter::new(azure_config(server)).expect("azure adapter")
}

fn gcs_service_account(server: &MockServer) -> String {
    serde_json::json!({
        "client_email": "backup@example.iam.gserviceaccount.com",
        "private_key": gcs_private_key(),
        "token_uri": format!("{}/token", server.uri()),
    })
    .to_string()
}

fn gcs_config(server: &MockServer) -> GcsStorageConfig {
    GcsStorageConfig {
        bucket: "bucket".to_string(),
        service_account_json: gcs_service_account(server),
        endpoint: Some(server.uri()),
        retry_delay: Duration::ZERO,
        ..Default::default()
    }
}

fn gcs(server: &MockServer) -> GcsStorageAdapter {
    GcsStorageAdapter::new(gcs_config(server)).expect("gcs adapter")
}

async fn mount_gcs_token(server: &MockServer) {
    Mock::given(method("POST"))
        .and(path("/token"))
        .respond_with(ResponseTemplate::new(200).set_body_json(serde_json::json!({
            "access_token": "test-token",
            "expires_in": 3600,
        })))
        .mount(server)
        .await;
}

fn drive_config(server: &MockServer) -> GoogleDriveStorageConfig {
    GoogleDriveStorageConfig {
        access_token: "drive-token".to_string(),
        folder_id: Some("folder-1".to_string()),
        endpoint: Some(server.uri()),
        retry_delay: Duration::ZERO,
        ..Default::default()
    }
}

fn drive(server: &MockServer) -> GoogleDriveStorageAdapter {
    GoogleDriveStorageAdapter::new(drive_config(server)).expect("drive adapter")
}

fn webdav_config(server: &MockServer) -> WebDavStorageConfig {
    WebDavStorageConfig {
        url: format!("{}/dav/", server.uri()),
        username: "user".to_string(),
        password: "pass".to_string(),
        retry_delay: Duration::ZERO,
        ..Default::default()
    }
}

fn webdav(server: &MockServer) -> WebDavStorageAdapter {
    WebDavStorageAdapter::new(webdav_config(server)).expect("webdav adapter")
}

fn contains_bytes(haystack: &[u8], needle: &[u8]) -> bool {
    haystack
        .windows(needle.len())
        .any(|window| window == needle)
}

// ---------------------------------------------------------------------------
// Azure Blob
// ---------------------------------------------------------------------------

#[tokio::test]
async fn azure_test_connection_signs_a_head_request() {
    let server = MockServer::start().await;
    Mock::given(method("HEAD"))
        .and(path("/container"))
        .and(query_param("restype", "container"))
        .respond_with(ResponseTemplate::new(200))
        .mount(&server)
        .await;

    azure(&server).test_connection().await.expect("connection");

    let requests = server.received_requests().await.expect("requests");
    assert_eq!(requests.len(), 1);
    assert_eq!(requests[0].method, Method::HEAD);
    let authorization = requests[0]
        .headers
        .get("authorization")
        .expect("authorization")
        .to_str()
        .expect("ascii");
    assert!(authorization.starts_with("SharedKey acct:"));
    assert!(requests[0].headers.get("x-ms-date").is_some());
    assert_eq!(
        requests[0]
            .headers
            .get("x-ms-version")
            .expect("version")
            .to_str()
            .expect("ascii"),
        "2021-12-02"
    );
}

#[tokio::test]
async fn azure_put_get_head_delete_round_trip() {
    let server = MockServer::start().await;
    Mock::given(method("PUT"))
        .and(path("/container/manifest.enc"))
        .and(header("x-ms-blob-type", "BlockBlob"))
        .and(header("if-match", "\"etag-old\""))
        .respond_with(ResponseTemplate::new(201).insert_header("ETag", "\"etag-new\""))
        .mount(&server)
        .await;
    Mock::given(method("GET"))
        .and(path("/container/manifest.enc"))
        .respond_with(
            ResponseTemplate::new(200)
                .insert_header("ETag", "\"etag-get\"")
                .insert_header("Last-Modified", "Wed, 21 Oct 2015 07:28:00 GMT")
                .set_body_bytes(ENCRYPTED_BYTES),
        )
        .mount(&server)
        .await;
    Mock::given(method("HEAD"))
        .and(path("/container/manifest.enc"))
        .respond_with(ResponseTemplate::new(200).insert_header("ETag", "\"etag-head\""))
        .mount(&server)
        .await;
    Mock::given(method("DELETE"))
        .and(path("/container/manifest.enc"))
        .respond_with(ResponseTemplate::new(204))
        .mount(&server)
        .await;

    let adapter = azure(&server);
    let put = adapter
        .put_object(
            "manifest.enc",
            ENCRYPTED_BYTES,
            PutObjectOptions {
                if_match: Some("\"etag-old\"".to_string()),
                ..Default::default()
            },
        )
        .await
        .expect("put");
    assert_eq!(put.etag.as_deref(), Some("\"etag-new\""));

    let get = adapter.get_object("manifest.enc").await.expect("get");
    assert_eq!(get.bytes, ENCRYPTED_BYTES);
    assert_eq!(get.etag.as_deref(), Some("\"etag-get\""));

    let head = adapter.head_object("manifest.enc").await.expect("head");
    assert!(head.bytes.is_empty());
    assert_eq!(head.etag.as_deref(), Some("\"etag-head\""));

    adapter.delete_object("manifest.enc").await.expect("delete");

    let requests = server.received_requests().await.expect("requests");
    let put_request = requests
        .iter()
        .find(|request| request.method == Method::PUT)
        .expect("put request");
    assert_eq!(put_request.body, ENCRYPTED_BYTES);
    assert_eq!(
        put_request
            .headers
            .get("content-type")
            .expect("content type")
            .to_str()
            .expect("ascii"),
        "application/octet-stream"
    );
}

#[tokio::test]
async fn azure_conditional_write_conflict_is_typed() {
    let server = MockServer::start().await;
    Mock::given(method("PUT"))
        .and(path("/container/manifest.enc"))
        .respond_with(ResponseTemplate::new(412).set_body_string("precondition failed"))
        .mount(&server)
        .await;

    let error = azure(&server)
        .put_object(
            "manifest.enc",
            ENCRYPTED_BYTES,
            PutObjectOptions {
                if_match: Some("\"stale\"".to_string()),
                ..Default::default()
            },
        )
        .await
        .expect_err("conflict");
    assert_eq!(error.code(), StorageErrorCode::Conflict);
    assert_eq!(error.status(), Some(412));
}

#[tokio::test]
async fn azure_sas_token_skips_authorization_and_appends_the_token() {
    let server = MockServer::start().await;
    Mock::given(method("HEAD"))
        .and(path("/container"))
        .and(query_param("restype", "container"))
        .and(query_param("sv", "2021-12-02"))
        .respond_with(ResponseTemplate::new(200))
        .mount(&server)
        .await;

    let adapter = AzureBlobStorageAdapter::new(AzureBlobStorageConfig {
        account_name: "acct".to_string(),
        sas_token: Some("?sv=2021-12-02&sig=abc".to_string()),
        container: "container".to_string(),
        endpoint: Some(server.uri()),
        retry_delay: Duration::ZERO,
        ..Default::default()
    })
    .expect("adapter");
    adapter.test_connection().await.expect("connection");

    let requests = server.received_requests().await.expect("requests");
    assert!(requests[0].headers.get("authorization").is_none());
}

#[tokio::test]
async fn azure_list_parses_blobs_and_next_marker() {
    let server = MockServer::start().await;
    let xml = r#"<EnumerationResults>
      <Blobs>
        <Blob>
          <Name>chatui/backups/manifest.enc</Name>
          <Etag>"etag-manifest"</Etag>
          <Last-Modified>2026-01-01T00:00:00.000Z</Last-Modified>
        </Blob>
        <Blob>
          <Name>chatui/backups/conversations/abc.enc</Name>
          <Etag>"etag-conversation"</Etag>
        </Blob>
      </Blobs>
      <NextMarker>next-page</NextMarker>
    </EnumerationResults>"#;
    Mock::given(method("GET"))
        .and(path("/container"))
        .and(query_param("restype", "container"))
        .and(query_param("comp", "list"))
        .and(query_param("prefix", "chatui/backups/conversations/"))
        .respond_with(ResponseTemplate::new(200).set_body_string(xml))
        .mount(&server)
        .await;

    let adapter = AzureBlobStorageAdapter::new(AzureBlobStorageConfig {
        prefix: Some("chatui/backups".to_string()),
        ..azure_config(&server)
    })
    .expect("adapter");
    let result = adapter
        .list_objects(ListObjectsOptions {
            prefix: Some("conversations/".to_string()),
            ..Default::default()
        })
        .await
        .expect("list");

    assert_eq!(
        result
            .objects
            .iter()
            .map(|object| object.key.as_str())
            .collect::<Vec<_>>(),
        vec!["manifest.enc", "conversations/abc.enc"]
    );
    assert_eq!(result.next_continuation_token.as_deref(), Some("next-page"));
}

#[tokio::test]
async fn azure_test_connection_maps_http_statuses_to_typed_errors() {
    for (status, expected) in [
        (401, StorageErrorCode::Auth),
        (403, StorageErrorCode::Permission),
        (404, StorageErrorCode::NotFound),
        (500, StorageErrorCode::Server),
    ] {
        let server = MockServer::start().await;
        Mock::given(method("HEAD"))
            .and(path("/container"))
            .respond_with(ResponseTemplate::new(status))
            .mount(&server)
            .await;

        let error = azure(&server)
            .test_connection()
            .await
            .expect_err("status error");
        assert_eq!(error.code(), expected);
        assert_eq!(error.status(), Some(status));
    }
}

// ---------------------------------------------------------------------------
// Google Cloud Storage
// ---------------------------------------------------------------------------

#[tokio::test]
async fn gcs_test_connection_exchanges_a_jwt_and_lists_the_bucket() {
    let server = MockServer::start().await;
    mount_gcs_token(&server).await;
    Mock::given(method("GET"))
        .and(path("/storage/v1/b/bucket/o"))
        .and(query_param("maxResults", "1"))
        .respond_with(ResponseTemplate::new(200).set_body_json(serde_json::json!({})))
        .mount(&server)
        .await;

    gcs(&server).test_connection().await.expect("connection");

    let requests = server.received_requests().await.expect("requests");
    let token_request = requests
        .iter()
        .find(|request| request.url.path() == "/token")
        .expect("token request");
    let body = String::from_utf8_lossy(&token_request.body);
    assert!(body.contains("grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Ajwt-bearer"));
    assert!(body.contains("assertion="));

    let list_request = requests
        .iter()
        .find(|request| request.url.path() == "/storage/v1/b/bucket/o")
        .expect("list request");
    assert_eq!(
        list_request
            .headers
            .get("authorization")
            .expect("authorization")
            .to_str()
            .expect("ascii"),
        "Bearer test-token"
    );
}

#[tokio::test]
async fn gcs_put_get_head_delete_round_trip() {
    let server = MockServer::start().await;
    mount_gcs_token(&server).await;
    Mock::given(method("POST"))
        .and(path("/upload/storage/v1/b/bucket/o"))
        .and(query_param("uploadType", "media"))
        .and(query_param("name", "manifest.enc"))
        .and(query_param("ifGenerationMatch", "7"))
        .respond_with(ResponseTemplate::new(200).set_body_json(serde_json::json!({
            "name": "manifest.enc",
            "generation": "8",
            "updated": "2026-01-01T00:00:00.000Z",
        })))
        .mount(&server)
        .await;
    Mock::given(method("GET"))
        .and(path("/storage/v1/b/bucket/o/manifest.enc"))
        .and(query_param("alt", "media"))
        .respond_with(
            ResponseTemplate::new(200)
                .insert_header("x-goog-generation", "8")
                .set_body_bytes(ENCRYPTED_BYTES),
        )
        .mount(&server)
        .await;
    Mock::given(method("GET"))
        .and(path("/storage/v1/b/bucket/o/manifest.enc"))
        .respond_with(ResponseTemplate::new(200).set_body_json(serde_json::json!({
            "name": "manifest.enc",
            "generation": "8",
            "updated": "2026-01-01T00:00:00.000Z",
        })))
        .mount(&server)
        .await;
    Mock::given(method("DELETE"))
        .and(path("/storage/v1/b/bucket/o/manifest.enc"))
        .respond_with(ResponseTemplate::new(204))
        .mount(&server)
        .await;

    let adapter = gcs(&server);
    let put = adapter
        .put_object(
            "manifest.enc",
            ENCRYPTED_BYTES,
            PutObjectOptions {
                if_match: Some("7".to_string()),
                ..Default::default()
            },
        )
        .await
        .expect("put");
    assert_eq!(put.etag.as_deref(), Some("8"));

    let get = adapter.get_object("manifest.enc").await.expect("get");
    assert_eq!(get.bytes, ENCRYPTED_BYTES);
    assert_eq!(get.etag.as_deref(), Some("8"));

    let head = adapter.head_object("manifest.enc").await.expect("head");
    assert!(head.bytes.is_empty());
    assert_eq!(head.etag.as_deref(), Some("8"));

    adapter.delete_object("manifest.enc").await.expect("delete");

    let requests = server.received_requests().await.expect("requests");
    let upload = requests
        .iter()
        .find(|request| request.url.path() == "/upload/storage/v1/b/bucket/o")
        .expect("upload request");
    assert_eq!(upload.body, ENCRYPTED_BYTES);
}

#[tokio::test]
async fn gcs_conditional_write_conflict_is_typed() {
    let server = MockServer::start().await;
    mount_gcs_token(&server).await;
    Mock::given(method("POST"))
        .and(path("/upload/storage/v1/b/bucket/o"))
        .respond_with(ResponseTemplate::new(412).set_body_string("precondition failed"))
        .mount(&server)
        .await;

    let error = gcs(&server)
        .put_object(
            "manifest.enc",
            ENCRYPTED_BYTES,
            PutObjectOptions {
                if_match: Some("7".to_string()),
                ..Default::default()
            },
        )
        .await
        .expect_err("conflict");
    assert_eq!(error.code(), StorageErrorCode::Conflict);
    assert_eq!(error.status(), Some(412));
}

#[tokio::test]
async fn gcs_list_parses_items_and_page_tokens() {
    let server = MockServer::start().await;
    mount_gcs_token(&server).await;
    Mock::given(method("GET"))
        .and(path("/storage/v1/b/bucket/o"))
        .and(query_param("prefix", "chatui/backups/conversations/"))
        .respond_with(ResponseTemplate::new(200).set_body_json(serde_json::json!({
            "items": [
                {"name": "chatui/backups/manifest.enc", "generation": "1"},
                {"name": "chatui/backups/conversations/abc.enc", "generation": "2"},
            ],
            "nextPageToken": "next-page",
        })))
        .mount(&server)
        .await;

    let adapter = GcsStorageAdapter::new(GcsStorageConfig {
        prefix: Some("chatui/backups".to_string()),
        ..gcs_config(&server)
    })
    .expect("adapter");
    let result = adapter
        .list_objects(ListObjectsOptions {
            prefix: Some("conversations/".to_string()),
            ..Default::default()
        })
        .await
        .expect("list");

    assert_eq!(
        result
            .objects
            .iter()
            .map(|object| object.key.as_str())
            .collect::<Vec<_>>(),
        vec!["manifest.enc", "conversations/abc.enc"]
    );
    assert_eq!(result.next_continuation_token.as_deref(), Some("next-page"));
}

#[tokio::test]
async fn gcs_test_connection_maps_http_statuses_to_typed_errors() {
    for (status, expected) in [
        (401, StorageErrorCode::Auth),
        (403, StorageErrorCode::Permission),
        (404, StorageErrorCode::NotFound),
        (500, StorageErrorCode::Server),
    ] {
        let server = MockServer::start().await;
        mount_gcs_token(&server).await;
        Mock::given(method("GET"))
            .and(path("/storage/v1/b/bucket/o"))
            .respond_with(ResponseTemplate::new(status))
            .mount(&server)
            .await;

        let error = gcs(&server)
            .test_connection()
            .await
            .expect_err("status error");
        assert_eq!(error.code(), expected);
        assert_eq!(error.status(), Some(status));
    }
}

// ---------------------------------------------------------------------------
// Google Drive
// ---------------------------------------------------------------------------

fn drive_file_json(version: &str) -> serde_json::Value {
    serde_json::json!({
        "id": "file-1",
        "name": "manifest.enc",
        "version": version,
        "modifiedTime": "2026-01-01T00:00:00.000Z",
    })
}

#[tokio::test]
async fn drive_test_connection_uses_the_bearer_token() {
    let server = MockServer::start().await;
    Mock::given(method("GET"))
        .and(path("/drive/v3/about"))
        .and(query_param("fields", "user"))
        .respond_with(ResponseTemplate::new(200).set_body_json(serde_json::json!({"user": {}})))
        .mount(&server)
        .await;

    drive(&server).test_connection().await.expect("connection");

    let requests = server.received_requests().await.expect("requests");
    assert_eq!(
        requests[0]
            .headers
            .get("authorization")
            .expect("authorization")
            .to_str()
            .expect("ascii"),
        "Bearer drive-token"
    );
}

#[tokio::test]
async fn drive_put_get_head_delete_round_trip() {
    let server = MockServer::start().await;
    Mock::given(method("GET"))
        .and(path("/drive/v3/files"))
        .and(query_param(
            "q",
            "name = 'manifest.enc' and 'folder-1' in parents and trashed = false",
        ))
        .respond_with(ResponseTemplate::new(200).set_body_json(serde_json::json!({
            "files": [drive_file_json("1")],
        })))
        .up_to_n_times(1)
        .mount(&server)
        .await;
    Mock::given(method("GET"))
        .and(path("/drive/v3/files"))
        .and(query_param(
            "q",
            "name = 'manifest.enc' and 'folder-1' in parents and trashed = false",
        ))
        .respond_with(ResponseTemplate::new(200).set_body_json(serde_json::json!({
            "files": [drive_file_json("2")],
        })))
        .mount(&server)
        .await;
    Mock::given(method("PATCH"))
        .and(path("/upload/drive/v3/files/file-1"))
        .and(query_param("uploadType", "media"))
        .respond_with(ResponseTemplate::new(200).set_body_json(drive_file_json("2")))
        .mount(&server)
        .await;
    Mock::given(method("GET"))
        .and(path("/drive/v3/files/file-1"))
        .and(query_param("alt", "media"))
        .respond_with(ResponseTemplate::new(200).set_body_bytes(ENCRYPTED_BYTES))
        .mount(&server)
        .await;
    Mock::given(method("DELETE"))
        .and(path("/drive/v3/files/file-1"))
        .respond_with(ResponseTemplate::new(204))
        .mount(&server)
        .await;

    let adapter = drive(&server);
    let put = adapter
        .put_object(
            "manifest.enc",
            ENCRYPTED_BYTES,
            PutObjectOptions {
                if_match: Some("1".to_string()),
                ..Default::default()
            },
        )
        .await
        .expect("put");
    assert_eq!(put.etag.as_deref(), Some("2"));

    let get = adapter.get_object("manifest.enc").await.expect("get");
    assert_eq!(get.bytes, ENCRYPTED_BYTES);
    assert_eq!(get.etag.as_deref(), Some("2"));

    let head = adapter.head_object("manifest.enc").await.expect("head");
    assert!(head.bytes.is_empty());
    assert_eq!(head.etag.as_deref(), Some("2"));

    adapter.delete_object("manifest.enc").await.expect("delete");

    let requests = server.received_requests().await.expect("requests");
    let patch = requests
        .iter()
        .find(|request| request.method == Method::PATCH)
        .expect("patch request");
    assert_eq!(patch.body, ENCRYPTED_BYTES);
}

#[tokio::test]
async fn drive_create_uses_multipart_and_only_encrypted_bytes() {
    let server = MockServer::start().await;
    Mock::given(method("GET"))
        .and(path("/drive/v3/files"))
        .and(query_param(
            "q",
            "name = 'manifest.enc' and 'folder-1' in parents and trashed = false",
        ))
        .respond_with(ResponseTemplate::new(200).set_body_json(serde_json::json!({"files": []})))
        .up_to_n_times(1)
        .mount(&server)
        .await;
    Mock::given(method("POST"))
        .and(path("/upload/drive/v3/files"))
        .and(query_param("uploadType", "multipart"))
        .respond_with(ResponseTemplate::new(200).set_body_json(drive_file_json("1")))
        .mount(&server)
        .await;
    Mock::given(method("GET"))
        .and(path("/drive/v3/files"))
        .and(query_param(
            "q",
            "name = 'manifest.enc' and 'folder-1' in parents and trashed = false",
        ))
        .respond_with(ResponseTemplate::new(200).set_body_json(serde_json::json!({
            "files": [drive_file_json("1")],
        })))
        .mount(&server)
        .await;

    let object = drive(&server)
        .put_object("manifest.enc", ENCRYPTED_BYTES, PutObjectOptions::default())
        .await
        .expect("put");
    assert_eq!(object.etag.as_deref(), Some("1"));

    let requests = server.received_requests().await.expect("requests");
    let post = requests
        .iter()
        .find(|request| request.method == Method::POST)
        .expect("post request");
    assert!(contains_bytes(&post.body, ENCRYPTED_BYTES));
    assert!(
        post.headers
            .get("content-type")
            .expect("content type")
            .to_str()
            .expect("ascii")
            .starts_with("multipart/related; boundary=")
    );
}

#[tokio::test]
async fn drive_conditional_write_conflicts_are_typed() {
    let server = MockServer::start().await;
    Mock::given(method("GET"))
        .and(path("/drive/v3/files"))
        .respond_with(ResponseTemplate::new(200).set_body_json(serde_json::json!({
            "files": [drive_file_json("2")],
        })))
        .mount(&server)
        .await;

    let adapter = drive(&server);
    let stale = adapter
        .put_object(
            "manifest.enc",
            ENCRYPTED_BYTES,
            PutObjectOptions {
                if_match: Some("1".to_string()),
                ..Default::default()
            },
        )
        .await
        .expect_err("stale version");
    assert_eq!(stale.code(), StorageErrorCode::Conflict);
    assert_eq!(stale.status(), Some(409));

    let exists = adapter
        .put_object(
            "manifest.enc",
            ENCRYPTED_BYTES,
            PutObjectOptions {
                if_none_match: Some("*".to_string()),
                ..Default::default()
            },
        )
        .await
        .expect_err("already exists");
    assert_eq!(exists.code(), StorageErrorCode::Conflict);
}

#[tokio::test]
async fn drive_list_filters_by_prefix_and_returns_page_tokens() {
    let server = MockServer::start().await;
    Mock::given(method("GET"))
        .and(path("/drive/v3/files"))
        .and(query_param(
            "q",
            "'folder-1' in parents and trashed = false",
        ))
        .respond_with(ResponseTemplate::new(200).set_body_json(serde_json::json!({
            "files": [
                {"id": "1", "name": "chatui/backups/manifest.enc", "version": "1"},
                {"id": "2", "name": "chatui/backups/conversations/abc.enc", "version": "2"},
                {"id": "3", "name": "other.enc", "version": "3"},
            ],
            "nextPageToken": "next-page",
        })))
        .mount(&server)
        .await;

    let adapter = GoogleDriveStorageAdapter::new(GoogleDriveStorageConfig {
        prefix: Some("chatui/backups".to_string()),
        ..drive_config(&server)
    })
    .expect("adapter");
    let result = adapter
        .list_objects(ListObjectsOptions {
            prefix: Some("conversations/".to_string()),
            ..Default::default()
        })
        .await
        .expect("list");

    assert_eq!(
        result
            .objects
            .iter()
            .map(|object| object.key.as_str())
            .collect::<Vec<_>>(),
        vec!["conversations/abc.enc"]
    );
    assert_eq!(result.next_continuation_token.as_deref(), Some("next-page"));
}

#[tokio::test]
async fn drive_test_connection_maps_http_statuses_to_typed_errors() {
    for (status, expected) in [
        (401, StorageErrorCode::Auth),
        (403, StorageErrorCode::Permission),
        (404, StorageErrorCode::NotFound),
        (500, StorageErrorCode::Server),
    ] {
        let server = MockServer::start().await;
        Mock::given(method("GET"))
            .and(path("/drive/v3/about"))
            .respond_with(ResponseTemplate::new(status))
            .mount(&server)
            .await;

        let error = drive(&server)
            .test_connection()
            .await
            .expect_err("status error");
        assert_eq!(error.code(), expected);
        assert_eq!(error.status(), Some(status));
    }
}

// ---------------------------------------------------------------------------
// WebDAV
// ---------------------------------------------------------------------------

#[tokio::test]
async fn webdav_test_connection_sends_basic_auth_and_propfind() {
    let server = MockServer::start().await;
    Mock::given(method("PROPFIND"))
        .and(path("/dav/"))
        .and(header("depth", "0"))
        .respond_with(ResponseTemplate::new(207))
        .mount(&server)
        .await;

    webdav(&server).test_connection().await.expect("connection");

    let requests = server.received_requests().await.expect("requests");
    assert_eq!(requests[0].method.as_str(), "PROPFIND");
    assert_eq!(
        requests[0]
            .headers
            .get("authorization")
            .expect("authorization")
            .to_str()
            .expect("ascii"),
        format!("Basic {}", base64_encode(b"user:pass"))
    );
}

#[tokio::test]
async fn webdav_put_get_head_delete_round_trip() {
    let server = MockServer::start().await;
    Mock::given(method("PUT"))
        .and(path("/dav/manifest.enc"))
        .and(header("if-match", "\"etag-old\""))
        .respond_with(ResponseTemplate::new(201).insert_header("ETag", "\"etag-new\""))
        .mount(&server)
        .await;
    Mock::given(method("GET"))
        .and(path("/dav/manifest.enc"))
        .respond_with(
            ResponseTemplate::new(200)
                .insert_header("ETag", "\"etag-get\"")
                .set_body_bytes(ENCRYPTED_BYTES),
        )
        .mount(&server)
        .await;
    Mock::given(method("HEAD"))
        .and(path("/dav/manifest.enc"))
        .respond_with(ResponseTemplate::new(200).insert_header("ETag", "\"etag-head\""))
        .mount(&server)
        .await;
    Mock::given(method("DELETE"))
        .and(path("/dav/manifest.enc"))
        .respond_with(ResponseTemplate::new(204))
        .mount(&server)
        .await;

    let adapter = webdav(&server);
    let put = adapter
        .put_object(
            "manifest.enc",
            ENCRYPTED_BYTES,
            PutObjectOptions {
                if_match: Some("\"etag-old\"".to_string()),
                ..Default::default()
            },
        )
        .await
        .expect("put");
    assert_eq!(put.etag.as_deref(), Some("\"etag-new\""));

    let get = adapter.get_object("manifest.enc").await.expect("get");
    assert_eq!(get.bytes, ENCRYPTED_BYTES);

    let head = adapter.head_object("manifest.enc").await.expect("head");
    assert!(head.bytes.is_empty());

    adapter.delete_object("manifest.enc").await.expect("delete");

    let requests = server.received_requests().await.expect("requests");
    let put_request = requests
        .iter()
        .find(|request| request.method == Method::PUT)
        .expect("put request");
    assert_eq!(put_request.body, ENCRYPTED_BYTES);
}

#[tokio::test]
async fn webdav_conditional_write_conflict_is_typed() {
    let server = MockServer::start().await;
    Mock::given(method("PUT"))
        .and(path("/dav/manifest.enc"))
        .respond_with(ResponseTemplate::new(412).set_body_string("precondition failed"))
        .mount(&server)
        .await;

    let error = webdav(&server)
        .put_object(
            "manifest.enc",
            ENCRYPTED_BYTES,
            PutObjectOptions {
                if_match: Some("\"stale\"".to_string()),
                ..Default::default()
            },
        )
        .await
        .expect_err("conflict");
    assert_eq!(error.code(), StorageErrorCode::Conflict);
    assert_eq!(error.status(), Some(412));
}

#[tokio::test]
async fn webdav_list_parses_propfind_and_skips_collections() {
    let server = MockServer::start().await;
    let xml = r#"<?xml version="1.0" encoding="utf-8"?>
<d:multistatus xmlns:d="DAV:">
  <d:response>
    <d:href>/dav/</d:href>
    <d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop></d:propstat>
  </d:response>
  <d:response>
    <d:href>/dav/chatui/backups/manifest.enc</d:href>
    <d:propstat><d:prop>
      <d:resourcetype/>
      <d:getetag>"etag-manifest"</d:getetag>
      <d:getlastmodified>Wed, 21 Oct 2015 07:28:00 GMT</d:getlastmodified>
    </d:prop></d:propstat>
  </d:response>
  <d:response>
    <d:href>/dav/chatui/backups/conversations/abc.enc</d:href>
    <d:propstat><d:prop><d:resourcetype/><d:getetag>"etag-conversation"</d:getetag></d:prop></d:propstat>
  </d:response>
</d:multistatus>"#;
    Mock::given(method("PROPFIND"))
        .and(path("/dav/"))
        .and(header("depth", "1"))
        .respond_with(ResponseTemplate::new(207).set_body_string(xml))
        .mount(&server)
        .await;

    let adapter = WebDavStorageAdapter::new(WebDavStorageConfig {
        prefix: Some("chatui/backups".to_string()),
        ..webdav_config(&server)
    })
    .expect("adapter");
    let result = adapter
        .list_objects(ListObjectsOptions {
            prefix: Some("conversations/".to_string()),
            ..Default::default()
        })
        .await
        .expect("list");

    assert_eq!(
        result
            .objects
            .iter()
            .map(|object| object.key.as_str())
            .collect::<Vec<_>>(),
        vec!["conversations/abc.enc"]
    );
}

#[tokio::test]
async fn webdav_test_connection_maps_http_statuses_to_typed_errors() {
    for (status, expected) in [
        (401, StorageErrorCode::Auth),
        (403, StorageErrorCode::Permission),
        (404, StorageErrorCode::NotFound),
        (500, StorageErrorCode::Server),
    ] {
        let server = MockServer::start().await;
        Mock::given(method("PROPFIND"))
            .and(path("/dav/"))
            .respond_with(ResponseTemplate::new(status))
            .mount(&server)
            .await;

        let error = webdav(&server)
            .test_connection()
            .await
            .expect_err("status error");
        assert_eq!(error.code(), expected);
        assert_eq!(error.status(), Some(status));
    }
}

#[tokio::test]
async fn additional_adapters_map_network_failures_to_typed_errors() {
    let azure = AzureBlobStorageAdapter::new(AzureBlobStorageConfig {
        account_name: "acct".to_string(),
        account_key: Some(base64_encode(b"key")),
        container: "container".to_string(),
        endpoint: Some("http://127.0.0.1:1".to_string()),
        retry_delay: Duration::ZERO,
        ..Default::default()
    })
    .expect("azure adapter");
    assert_eq!(
        azure.test_connection().await.expect_err("network").code(),
        StorageErrorCode::Network
    );

    let webdav = WebDavStorageAdapter::new(WebDavStorageConfig {
        url: "http://127.0.0.1:1/dav/".to_string(),
        username: "user".to_string(),
        password: "pass".to_string(),
        retry_delay: Duration::ZERO,
        ..Default::default()
    })
    .expect("webdav adapter");
    assert_eq!(
        webdav.test_connection().await.expect_err("network").code(),
        StorageErrorCode::Network
    );
}
