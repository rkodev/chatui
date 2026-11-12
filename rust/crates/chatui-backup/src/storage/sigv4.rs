//! Minimal AWS Signature Version 4 signer for S3-compatible object storage.
//!
//! This module deliberately has no cloud SDK dependency. It implements the
//! canonical request, string to sign, signing key, and `Authorization` header
//! with `hmac`, `sha2`, and `hex` so the desktop client can talk directly to a
//! bucket.

use std::collections::BTreeMap;
use std::time::{SystemTime, UNIX_EPOCH};

use hmac::{Hmac, Mac};
use sha2::{Digest, Sha256};
use url::Url;

/// SigV4 algorithm identifier.
pub const SIGV4_ALGORITHM: &str = "AWS4-HMAC-SHA256";
/// SigV4 service name for object storage.
pub const SIGV4_SERVICE: &str = "s3";
/// SigV4 request terminator.
pub const SIGV4_TERMINATOR: &str = "aws4_request";

type HmacSha256 = Hmac<Sha256>;

/// HTTP methods supported by the signer.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum SigV4Method {
    /// Read an object.
    Get,
    /// Write an object.
    Put,
    /// Read object metadata.
    Head,
    /// Delete an object.
    Delete,
}

impl SigV4Method {
    /// Uppercase method name used in the canonical request.
    pub fn as_str(self) -> &'static str {
        match self {
            Self::Get => "GET",
            Self::Put => "PUT",
            Self::Head => "HEAD",
            Self::Delete => "DELETE",
        }
    }
}

/// Access key, secret, and optional session token.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct SigV4Credentials {
    /// Access key id.
    pub access_key_id: String,
    /// Secret access key.
    pub secret_access_key: String,
    /// Optional temporary session token.
    pub session_token: Option<String>,
}

/// Inputs for [`sign_request`].
#[derive(Clone, Debug)]
pub struct SigV4SignOptions<'a> {
    /// HTTP method.
    pub method: SigV4Method,
    /// Full request URL.
    pub url: &'a Url,
    /// Bucket region.
    pub region: &'a str,
    /// Credentials.
    pub credentials: &'a SigV4Credentials,
    /// Extra request headers. Names are matched case-insensitively.
    pub headers: &'a [(String, String)],
    /// Request body. Empty for GET, HEAD, and DELETE.
    pub payload: &'a [u8],
    /// Service name. Defaults to `s3`.
    pub service: Option<&'a str>,
    /// Fixed clock for tests.
    pub date: SystemTime,
}

/// A signed request ready to hand to an HTTP client.
#[derive(Clone, Debug)]
pub struct SignedRequest {
    /// HTTP method.
    pub method: SigV4Method,
    /// Request URL.
    pub url: Url,
    /// Lowercase header names plus `authorization`.
    pub headers: Vec<(String, String)>,
}

/// RFC 3986 encoding as required by SigV4.
pub fn encode_rfc3986(value: &str) -> String {
    let mut output = String::with_capacity(value.len());
    for byte in value.bytes() {
        match byte {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'-' | b'_' | b'.' | b'~' => {
                output.push(char::from(byte));
            }
            _ => {
                output.push('%');
                output.push_str(&format!("{byte:02X}"));
            }
        }
    }
    output
}

/// Lowercase hexadecimal SHA-256 digest.
pub fn sha256_hex(data: &[u8]) -> String {
    hex::encode(Sha256::digest(data))
}

fn hmac_sha256(key: &[u8], data: &[u8]) -> Vec<u8> {
    let mut mac = HmacSha256::new_from_slice(key).expect("HMAC accepts any key length");
    mac.update(data);
    mac.finalize().into_bytes().to_vec()
}

/// `YYYYMMDDTHHMMSSZ` without milliseconds.
pub fn format_amz_date(time: SystemTime) -> String {
    let seconds = time
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_secs();
    let days = (seconds / 86_400) as i64;
    let remainder = seconds % 86_400;
    let (year, month, day) = civil_from_days(days);
    format!(
        "{year:04}{month:02}{day:02}T{:02}{:02}{:02}Z",
        remainder / 3_600,
        (remainder % 3_600) / 60,
        remainder % 60
    )
}

fn civil_from_days(days: i64) -> (i64, u32, u32) {
    let z = days + 719_468;
    let era = if z >= 0 { z } else { z - 146_096 } / 146_097;
    let day_of_era = (z - era * 146_097) as u64;
    let year_of_era =
        (day_of_era - day_of_era / 1_460 + day_of_era / 36_524 - day_of_era / 146_096) / 365;
    let year = year_of_era as i64 + era * 400;
    let day_of_year = day_of_era - (365 * year_of_era + year_of_era / 4 - year_of_era / 100);
    let month_prime = (5 * day_of_year + 2) / 153;
    let day = (day_of_year - (153 * month_prime + 2) / 5 + 1) as u32;
    let month = if month_prime < 10 {
        month_prime + 3
    } else {
        month_prime - 9
    } as u32;
    (if month <= 2 { year + 1 } else { year }, month, day)
}

/// Derive the SigV4 signing key for a date, region, and service.
pub fn derive_signing_key(
    secret_access_key: &str,
    date_stamp: &str,
    region: &str,
    service: &str,
) -> Vec<u8> {
    let k_date = hmac_sha256(
        format!("AWS4{secret_access_key}").as_bytes(),
        date_stamp.as_bytes(),
    );
    let k_region = hmac_sha256(&k_date, region.as_bytes());
    let k_service = hmac_sha256(&k_region, service.as_bytes());
    hmac_sha256(&k_service, SIGV4_TERMINATOR.as_bytes())
}

fn canonical_query(url: &Url) -> String {
    let mut pairs: Vec<(String, String)> = url
        .query_pairs()
        .map(|(key, value)| (encode_rfc3986(&key), encode_rfc3986(&value)))
        .collect();
    pairs.sort();
    pairs
        .into_iter()
        .map(|(key, value)| format!("{key}={value}"))
        .collect::<Vec<_>>()
        .join("&")
}

fn normalize_header_value(value: &str) -> String {
    value.split_whitespace().collect::<Vec<_>>().join(" ")
}

fn canonical_headers(headers: &BTreeMap<String, String>) -> (String, String) {
    let mut canonical = String::new();
    let mut names = Vec::with_capacity(headers.len());
    for (name, value) in headers {
        canonical.push_str(name);
        canonical.push(':');
        canonical.push_str(&normalize_header_value(value));
        canonical.push('\n');
        names.push(name.as_str());
    }
    (canonical, names.join(";"))
}

/// Build the canonical request and signed-header list.
pub fn build_canonical_request(
    method: SigV4Method,
    url: &Url,
    headers: &BTreeMap<String, String>,
    payload_hash: &str,
) -> (String, String) {
    let canonical_uri = if url.path().is_empty() {
        "/"
    } else {
        url.path()
    };
    let query = canonical_query(url);
    let (canonical, signed_headers) = canonical_headers(headers);
    let canonical_request = [
        method.as_str(),
        canonical_uri,
        &query,
        &canonical,
        &signed_headers,
        payload_hash,
    ]
    .join("\n");
    (canonical_request, signed_headers)
}

/// Build the SigV4 string to sign.
pub fn build_string_to_sign(amz_date: &str, scope: &str, canonical_request_hash: &str) -> String {
    [SIGV4_ALGORITHM, amz_date, scope, canonical_request_hash].join("\n")
}

fn host_header(url: &Url) -> String {
    match url.port() {
        Some(port) => format!("{}:{port}", url.host_str().unwrap_or_default()),
        None => url.host_str().unwrap_or_default().to_string(),
    }
}

/// Sign a request and return the URL, method, and headers to send.
///
/// The returned headers include `host`, `x-amz-date`, `x-amz-content-sha256`,
/// an optional `x-amz-security-token`, and `authorization`.
pub fn sign_request(options: SigV4SignOptions<'_>) -> SignedRequest {
    let service = options.service.unwrap_or(SIGV4_SERVICE);
    let amz_date = format_amz_date(options.date);
    let date_stamp = &amz_date[..8];
    let payload_hash = sha256_hex(options.payload);

    let mut headers = BTreeMap::new();
    for (name, value) in options.headers {
        headers.insert(name.to_ascii_lowercase(), value.clone());
    }
    headers.insert("host".to_string(), host_header(options.url));
    headers.insert("x-amz-content-sha256".to_string(), payload_hash.clone());
    headers.insert("x-amz-date".to_string(), amz_date.clone());
    if let Some(token) = options
        .credentials
        .session_token
        .as_deref()
        .filter(|token| !token.is_empty())
    {
        headers.insert("x-amz-security-token".to_string(), token.to_string());
    }

    let (canonical_request, signed_headers) =
        build_canonical_request(options.method, options.url, &headers, &payload_hash);
    let scope = format!(
        "{date_stamp}/{}/{service}/{SIGV4_TERMINATOR}",
        options.region
    );
    let string_to_sign =
        build_string_to_sign(&amz_date, &scope, &sha256_hex(canonical_request.as_bytes()));
    let signing_key = derive_signing_key(
        &options.credentials.secret_access_key,
        date_stamp,
        options.region,
        service,
    );
    let signature = hex::encode(hmac_sha256(&signing_key, string_to_sign.as_bytes()));

    headers.insert(
        "authorization".to_string(),
        format!(
            "{SIGV4_ALGORITHM} Credential={}/{scope}, SignedHeaders={signed_headers}, Signature={signature}",
            options.credentials.access_key_id
        ),
    );

    SignedRequest {
        method: options.method,
        url: options.url.clone(),
        headers: headers.into_iter().collect(),
    }
}
