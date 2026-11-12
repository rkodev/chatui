use std::time::{Duration, SystemTime, UNIX_EPOCH};

use reqwest::Method;
use url::Url;
use wiremock::matchers::{header, method, path, query_param};
use wiremock::{Mock, MockServer, ResponseTemplate};

use crate::storage::sigv4::{
    SigV4Credentials, SigV4Method, SigV4SignOptions, sha256_hex, sign_request,
};
use crate::storage::{
    ListObjectsOptions, PutObjectOptions, S3StorageAdapter, S3StorageConfig, StorageAdapter,
    StorageErrorCode,
};

/// Synthetic envelope-shaped ciphertext. Tests never pass plaintext to the
/// adapter; these bytes only exercise the transport boundary.
const ENCRYPTED_BYTES: &[u8] = &[
    0x43, 0x48, 0x41, 0x54, 0x55, 0x49, 0x31, 0x0a, 0x00, 0x00, 0x00, 0x02, 0x7b, 0x7d, 0x01, 0x02,
    0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0a, 0x0b, 0x0c, 0xaa, 0xbb, 0xcc, 0xdd,
];

const AWS_ACCESS_KEY: &str = "AKIAIOSFODNN7EXAMPLE";
const AWS_SECRET_KEY: &str = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY";

fn aws_date() -> SystemTime {
    UNIX_EPOCH + Duration::from_secs(1_369_353_600)
}

fn aws_credentials() -> SigV4Credentials {
    SigV4Credentials {
        access_key_id: AWS_ACCESS_KEY.to_string(),
        secret_access_key: AWS_SECRET_KEY.to_string(),
        session_token: None,
    }
}

fn config(endpoint: &str) -> S3StorageConfig {
    S3StorageConfig {
        endpoint: endpoint.to_string(),
        bucket: "test-bucket".to_string(),
        region: "us-east-1".to_string(),
        access_key_id: AWS_ACCESS_KEY.to_string(),
        secret_access_key: AWS_SECRET_KEY.to_string(),
        force_path_style: Some(true),
        retry_delay: Duration::ZERO,
        ..Default::default()
    }
}

fn adapter(server: &MockServer) -> S3StorageAdapter {
    S3StorageAdapter::new(config(&server.uri())).expect("adapter")
}

fn url_adapter(force_path_style: bool, prefix: Option<&str>) -> S3StorageAdapter {
    S3StorageAdapter::new(S3StorageConfig {
        endpoint: "https://s3.us-east-1.amazonaws.com".to_string(),
        bucket: "my-bucket".to_string(),
        region: "us-east-1".to_string(),
        access_key_id: "key".to_string(),
        secret_access_key: "secret".to_string(),
        prefix: prefix.map(str::to_string),
        force_path_style: Some(force_path_style),
        ..Default::default()
    })
    .expect("adapter")
}

#[test]
fn sigv4_matches_the_aws_get_object_known_answer_vector() {
    let url = Url::parse("https://examplebucket.s3.amazonaws.com/test.txt").expect("url");
    let headers = vec![("range".to_string(), "bytes=0-9".to_string())];
    let credentials = aws_credentials();

    let signed = sign_request(SigV4SignOptions {
        method: SigV4Method::Get,
        url: &url,
        region: "us-east-1",
        credentials: &credentials,
        headers: &headers,
        payload: &[],
        service: None,
        date: aws_date(),
    });

    let headers: std::collections::BTreeMap<_, _> = signed.headers.into_iter().collect();
    assert_eq!(headers["x-amz-date"], "20130524T000000Z");
    assert_eq!(
        headers["x-amz-content-sha256"],
        "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
    );
    assert_eq!(
        headers["authorization"],
        "AWS4-HMAC-SHA256 Credential=AKIAIOSFODNN7EXAMPLE/20130524/us-east-1/s3/aws4_request, \
         SignedHeaders=host;range;x-amz-content-sha256;x-amz-date, \
         Signature=f0e8bdb87c964420e857bd35b5d6ed310bd44f0170aba48dd91039c6036bdb41"
    );
}

#[test]
fn sigv4_matches_the_aws_put_object_known_answer_vector() {
    let url = Url::parse("https://examplebucket.s3.amazonaws.com/test%24file.text").expect("url");
    let headers = vec![
        (
            "date".to_string(),
            "Fri, 24 May 2013 00:00:00 GMT".to_string(),
        ),
        (
            "x-amz-storage-class".to_string(),
            "REDUCED_REDUNDANCY".to_string(),
        ),
    ];
    let credentials = aws_credentials();
    let payload = b"Welcome to Amazon S3.";

    let signed = sign_request(SigV4SignOptions {
        method: SigV4Method::Put,
        url: &url,
        region: "us-east-1",
        credentials: &credentials,
        headers: &headers,
        payload,
        service: None,
        date: aws_date(),
    });

    let headers: std::collections::BTreeMap<_, _> = signed.headers.into_iter().collect();
    assert_eq!(
        headers["x-amz-content-sha256"],
        "44ce7dd67c959e0d3524ffac1771dfbba87d2b6b4b4e99e42034a8b803f8b072"
    );
    assert_eq!(
        headers["authorization"],
        "AWS4-HMAC-SHA256 Credential=AKIAIOSFODNN7EXAMPLE/20130524/us-east-1/s3/aws4_request, \
         SignedHeaders=date;host;x-amz-content-sha256;x-amz-date;x-amz-storage-class, \
         Signature=98ad721746da40c64f1a55b78f14c238d841ea1380cd77a1b5971af0ece108bd"
    );
}

#[tokio::test]
async fn test_connection_sends_a_signed_head_to_the_bucket() {
    let server = MockServer::start().await;
    Mock::given(method("HEAD"))
        .and(path("/test-bucket"))
        .respond_with(ResponseTemplate::new(200))
        .mount(&server)
        .await;

    adapter(&server)
        .test_connection()
        .await
        .expect("connection");

    let requests = server.received_requests().await.expect("requests");
    assert_eq!(requests.len(), 1);
    assert_eq!(requests[0].method, Method::HEAD);
    assert_eq!(requests[0].url.path(), "/test-bucket");
    let authorization = requests[0]
        .headers
        .get("authorization")
        .expect("authorization")
        .to_str()
        .expect("ascii");
    assert!(authorization.starts_with("AWS4-HMAC-SHA256 "));
    assert_eq!(
        requests[0]
            .headers
            .get("x-amz-content-sha256")
            .expect("payload hash")
            .to_str()
            .expect("ascii"),
        sha256_hex(b"")
    );
}

#[tokio::test]
async fn get_object_returns_encrypted_bytes_and_captures_the_etag() {
    let server = MockServer::start().await;
    Mock::given(method("GET"))
        .and(path("/test-bucket/manifest.enc"))
        .respond_with(
            ResponseTemplate::new(200)
                .insert_header("ETag", "\"etag-get\"")
                .insert_header("Last-Modified", "Wed, 21 Oct 2015 07:28:00 GMT")
                .set_body_bytes(ENCRYPTED_BYTES),
        )
        .mount(&server)
        .await;

    let object = adapter(&server)
        .get_object("manifest.enc")
        .await
        .expect("get");

    assert_eq!(object.key, "manifest.enc");
    assert_eq!(object.bytes, ENCRYPTED_BYTES);
    assert_eq!(object.etag.as_deref(), Some("\"etag-get\""));
    assert_eq!(
        object.last_modified.as_deref(),
        Some("Wed, 21 Oct 2015 07:28:00 GMT")
    );
}

#[tokio::test]
async fn put_object_sends_if_match_and_returns_the_etag() {
    let server = MockServer::start().await;
    Mock::given(method("PUT"))
        .and(path("/test-bucket/manifest.enc"))
        .and(header("if-match", "\"etag-old\""))
        .respond_with(ResponseTemplate::new(200).insert_header("ETag", "\"etag-put\""))
        .mount(&server)
        .await;

    let object = adapter(&server)
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

    assert_eq!(object.etag.as_deref(), Some("\"etag-put\""));
    let requests = server.received_requests().await.expect("requests");
    assert_eq!(requests[0].method, Method::PUT);
    assert_eq!(
        requests[0]
            .headers
            .get("content-type")
            .expect("content type")
            .to_str()
            .expect("ascii"),
        "application/octet-stream"
    );
    assert_eq!(requests[0].body, ENCRYPTED_BYTES);
}

#[tokio::test]
async fn put_object_sends_if_none_match_for_create_only_writes() {
    let server = MockServer::start().await;
    Mock::given(method("PUT"))
        .and(path("/test-bucket/manifest.enc"))
        .and(header("if-none-match", "*"))
        .respond_with(ResponseTemplate::new(200).insert_header("ETag", "\"etag-create\""))
        .mount(&server)
        .await;

    adapter(&server)
        .put_object(
            "manifest.enc",
            ENCRYPTED_BYTES,
            PutObjectOptions {
                if_none_match: Some("*".to_string()),
                ..Default::default()
            },
        )
        .await
        .expect("put");

    let requests = server.received_requests().await.expect("requests");
    assert!(requests[0].headers.get("if-match").is_none());
}

#[tokio::test]
async fn conditional_write_conflict_returns_a_typed_conflict_error() {
    let server = MockServer::start().await;
    Mock::given(method("PUT"))
        .and(path("/test-bucket/manifest.enc"))
        .respond_with(ResponseTemplate::new(412).set_body_string("precondition failed"))
        .mount(&server)
        .await;

    let error = adapter(&server)
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
async fn head_object_returns_metadata_without_a_body() {
    let server = MockServer::start().await;
    Mock::given(method("HEAD"))
        .and(path("/test-bucket/manifest.enc"))
        .respond_with(ResponseTemplate::new(200).insert_header("ETag", "\"etag-head\""))
        .mount(&server)
        .await;

    let object = adapter(&server)
        .head_object("manifest.enc")
        .await
        .expect("head");

    assert!(object.bytes.is_empty());
    assert_eq!(object.etag.as_deref(), Some("\"etag-head\""));
}

#[tokio::test]
async fn delete_object_sends_a_signed_delete() {
    let server = MockServer::start().await;
    Mock::given(method("DELETE"))
        .and(path("/test-bucket/manifest.enc"))
        .respond_with(ResponseTemplate::new(204))
        .mount(&server)
        .await;

    adapter(&server)
        .delete_object("manifest.enc")
        .await
        .expect("delete");

    let requests = server.received_requests().await.expect("requests");
    assert_eq!(requests[0].method, Method::DELETE);
    assert_eq!(requests[0].url.path(), "/test-bucket/manifest.enc");
    assert!(
        requests[0]
            .headers
            .get("authorization")
            .expect("authorization")
            .to_str()
            .expect("ascii")
            .starts_with("AWS4-HMAC-SHA256 ")
    );
}

#[tokio::test]
async fn list_objects_parses_keys_etags_and_continuation_tokens() {
    let server = MockServer::start().await;
    let xml = r#"<ListBucketResult>
      <Contents>
        <Key>chatui/backups/manifest.enc</Key>
        <ETag>"etag-manifest"</ETag>
        <LastModified>2026-01-01T00:00:00.000Z</LastModified>
      </Contents>
      <Contents>
        <Key>chatui/backups/conversations/abc.enc</Key>
        <ETag>"etag-conversation"</ETag>
      </Contents>
      <NextContinuationToken>next-page</NextContinuationToken>
    </ListBucketResult>"#;
    Mock::given(method("GET"))
        .and(path("/test-bucket"))
        .and(query_param("list-type", "2"))
        .and(query_param("prefix", "chatui/backups/conversations/"))
        .and(query_param("max-keys", "10"))
        .respond_with(ResponseTemplate::new(200).set_body_string(xml))
        .mount(&server)
        .await;

    let adapter = S3StorageAdapter::new(S3StorageConfig {
        prefix: Some("chatui/backups".to_string()),
        ..config(&server.uri())
    })
    .expect("adapter");
    let result = adapter
        .list_objects(ListObjectsOptions {
            prefix: Some("conversations/".to_string()),
            max_keys: Some(10),
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
    assert_eq!(result.objects[0].etag.as_deref(), Some("\"etag-manifest\""));
    assert_eq!(
        result.objects[0].last_modified.as_deref(),
        Some("2026-01-01T00:00:00.000Z")
    );
    assert_eq!(result.next_continuation_token.as_deref(), Some("next-page"));
}

#[tokio::test]
async fn test_connection_maps_http_statuses_to_typed_errors() {
    let cases = [
        (401, StorageErrorCode::Auth),
        (403, StorageErrorCode::Permission),
        (404, StorageErrorCode::NotFound),
        (500, StorageErrorCode::Server),
    ];

    for (status, expected) in cases {
        let server = MockServer::start().await;
        Mock::given(method("HEAD"))
            .and(path("/test-bucket"))
            .respond_with(ResponseTemplate::new(status))
            .mount(&server)
            .await;

        let error = adapter(&server)
            .test_connection()
            .await
            .expect_err("status error");
        assert_eq!(error.code(), expected);
        assert_eq!(error.status(), Some(status));
    }
}

#[tokio::test]
async fn test_connection_maps_network_failures_to_a_typed_error() {
    let adapter = S3StorageAdapter::new(config("http://127.0.0.1:1")).expect("adapter");
    let error = adapter.test_connection().await.expect_err("network error");
    assert_eq!(error.code(), StorageErrorCode::Network);
}

#[tokio::test]
async fn put_object_re_reads_when_the_provider_omits_an_etag() {
    let server = MockServer::start().await;
    Mock::given(method("PUT"))
        .and(path("/test-bucket/manifest.enc"))
        .respond_with(ResponseTemplate::new(200))
        .mount(&server)
        .await;
    Mock::given(method("HEAD"))
        .and(path("/test-bucket/manifest.enc"))
        .respond_with(ResponseTemplate::new(404))
        .up_to_n_times(1)
        .mount(&server)
        .await;
    Mock::given(method("HEAD"))
        .and(path("/test-bucket/manifest.enc"))
        .respond_with(ResponseTemplate::new(200).insert_header("ETag", "\"etag-late\""))
        .mount(&server)
        .await;

    let object = adapter(&server)
        .put_object("manifest.enc", ENCRYPTED_BYTES, PutObjectOptions::default())
        .await
        .expect("put");

    assert_eq!(object.etag.as_deref(), Some("\"etag-late\""));
    let requests = server.received_requests().await.expect("requests");
    assert_eq!(requests.len(), 3);
    assert_eq!(requests[0].method, Method::PUT);
    assert_eq!(requests[1].method, Method::HEAD);
    assert_eq!(requests[2].method, Method::HEAD);
}

#[test]
fn builds_path_style_and_virtual_hosted_urls() {
    let path_style = url_adapter(true, None);
    assert_eq!(
        path_style.bucket_url().as_str(),
        "https://s3.us-east-1.amazonaws.com/my-bucket"
    );
    assert_eq!(
        path_style.object_url("manifest.enc").expect("url").as_str(),
        "https://s3.us-east-1.amazonaws.com/my-bucket/manifest.enc"
    );

    let virtual_hosted = url_adapter(false, None);
    assert_eq!(
        virtual_hosted.bucket_url().as_str(),
        "https://my-bucket.s3.us-east-1.amazonaws.com/"
    );
    assert_eq!(
        virtual_hosted
            .object_url("manifest.enc")
            .expect("url")
            .as_str(),
        "https://my-bucket.s3.us-east-1.amazonaws.com/manifest.enc"
    );
}

#[test]
fn applies_the_prefix_and_encodes_object_key_segments() {
    let prefixed = url_adapter(true, Some("chatui/backups"));
    assert_eq!(
        prefixed
            .object_url("conversations/a b.enc")
            .expect("url")
            .as_str(),
        "https://s3.us-east-1.amazonaws.com/my-bucket/chatui/backups/conversations/a%20b.enc"
    );
}

#[tokio::test]
async fn put_object_sends_only_the_provided_encrypted_bytes() {
    let server = MockServer::start().await;
    Mock::given(method("PUT"))
        .and(path("/test-bucket/manifest.enc"))
        .respond_with(ResponseTemplate::new(200).insert_header("ETag", "\"etag-bytes\""))
        .mount(&server)
        .await;

    adapter(&server)
        .put_object("manifest.enc", ENCRYPTED_BYTES, PutObjectOptions::default())
        .await
        .expect("put");

    let requests = server.received_requests().await.expect("requests");
    assert_eq!(requests[0].body, ENCRYPTED_BYTES);
    assert_eq!(
        requests[0]
            .headers
            .get("content-type")
            .expect("content type")
            .to_str()
            .expect("ascii"),
        "application/octet-stream"
    );
}
