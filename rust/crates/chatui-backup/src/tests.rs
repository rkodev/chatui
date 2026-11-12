use base64::Engine;
use base64::engine::general_purpose::STANDARD;
use chatui_domain::{
    Attachment, AttachmentKind, ChatParams, ContentPart, Conversation, Message, MessageRole,
    MessageStatus, Timestamp, Tombstone, Usage,
};
use serde_json::Value;
use sha2::{Digest, Sha256};

use crate::*;

const PASSPHRASE: &str = "correct horse battery staple";
const SALT: [u8; SALT_LEN] = [0x42; SALT_LEN];

fn vectors() -> Value {
    let path = concat!(
        env!("CARGO_MANIFEST_DIR"),
        "/../../../contracts/fixtures/backup/vectors.json"
    );
    let text = std::fs::read_to_string(path).expect("read backup vectors");
    serde_json::from_str(&text).expect("parse backup vectors")
}

fn b64(value: &Value, key: &str) -> Vec<u8> {
    STANDARD
        .decode(value[key].as_str().expect("base64 string"))
        .expect("valid base64")
}

fn text<'a>(value: &'a Value, key: &str) -> &'a str {
    value[key].as_str().expect("string field")
}

fn header_from(value: &Value) -> EnvelopeHeader {
    serde_json::from_value(value["header"].clone()).expect("valid header")
}

fn hex(bytes: &[u8]) -> String {
    bytes.iter().map(|byte| format!("{byte:02x}")).collect()
}

fn encrypt(
    object_type: ObjectType,
    object_key: &str,
    plaintext: &[u8],
    compress: Option<bool>,
) -> Vec<u8> {
    encrypt_object(EncryptOptions {
        passphrase: PASSPHRASE,
        salt: &SALT,
        object_type,
        object_key,
        plaintext,
        compress,
        iv: None,
        iterations: None,
    })
    .expect("encrypt")
}

#[test]
fn shared_envelope_vector_matches_byte_for_byte() {
    let vectors = vectors();
    let vector = &vectors["envelopeVectors"][0];
    let header = header_from(vector);
    let iv = b64(vector, "ivBase64");
    let mut ciphertext = b64(vector, "ciphertextBase64");
    ciphertext.extend_from_slice(&b64(vector, "tagBase64"));

    let envelope = encode_envelope(&header, &iv, &ciphertext).expect("encode");
    assert_eq!(envelope, b64(vector, "envelopeBase64"));
    assert_eq!(
        header.canonical_json().expect("canonical json"),
        b64(vector, "headerJsonBase64")
    );
    assert_eq!(
        header.canonical_json().expect("canonical json").len(),
        vector["headerLength"].as_u64().expect("header length") as usize
    );
    assert_eq!(
        build_aad(header.object_type, &header.object_key).expect("aad"),
        b64(vector, "aadBase64")
    );
}

#[test]
fn shared_envelope_vector_encrypts_byte_for_byte() {
    let vectors = vectors();
    let vector = &vectors["envelopeVectors"][0];
    let header = header_from(vector);
    let salt = b64(vector, "saltBase64");
    let iv: [u8; IV_LEN] = b64(vector, "ivBase64").try_into().expect("12-byte IV");
    let plaintext = b64(vector, "plaintextBase64");

    let envelope = encrypt_object(EncryptOptions {
        passphrase: text(vector, "passphrase"),
        salt: &salt,
        object_type: header.object_type,
        object_key: &header.object_key,
        plaintext: &plaintext,
        compress: Some(false),
        iv: Some(iv),
        iterations: Some(vector["iterations"].as_u64().expect("iterations") as u32),
    })
    .expect("encrypt");

    assert_eq!(envelope, b64(vector, "envelopeBase64"));
}

#[test]
fn shared_envelope_vector_decrypts_to_reference_plaintext() {
    let vectors = vectors();
    let vector = &vectors["envelopeVectors"][0];
    let envelope = b64(vector, "envelopeBase64");

    let decrypted = decrypt_object(text(vector, "passphrase"), &envelope).expect("decrypt");
    assert_eq!(decrypted.header, header_from(vector));
    assert_eq!(
        decrypted.plaintext.as_slice(),
        b64(vector, "uncompressedBase64").as_slice()
    );

    let parts = decode_envelope(&envelope).expect("decode");
    assert_eq!(
        parts.ciphertext.len(),
        b64(vector, "ciphertextBase64").len() + TAG_LEN
    );
}

#[test]
fn shared_aad_vectors_match() {
    let vectors = vectors();
    for vector in vectors["aadVectors"].as_array().expect("aad vectors") {
        let object_type = ObjectType::from_wire(text(vector, "objectType")).expect("object type");
        let aad = build_aad(object_type, text(vector, "objectKey")).expect("aad");
        assert_eq!(aad, b64(vector, "aadBase64"));
        assert_eq!(
            aad.len(),
            vector["aadLength"].as_u64().expect("aad length") as usize
        );
    }
}

#[test]
fn shared_gzip_vector_round_trips() {
    let vectors = vectors();
    let vector = &vectors["gzipVector"];
    let uncompressed = b64(vector, "uncompressedBase64");
    let reference_gzip = b64(vector, "gzipBase64");

    assert_eq!(
        hex(&Sha256::digest(&uncompressed)),
        text(vector, "uncompressedSha256")
    );

    let envelope = encrypt(
        ObjectType::Conversation,
        "conversations/00112233445566778899aabbccddeeff.enc",
        &uncompressed,
        Some(true),
    );
    let decrypted = decrypt_object(PASSPHRASE, &envelope).expect("decrypt");
    assert_eq!(decrypted.plaintext.as_slice(), uncompressed.as_slice());

    let envelope = encrypt(
        ObjectType::Conversation,
        "conversations/00112233445566778899aabbccddeeff.enc",
        &reference_gzip,
        Some(false),
    );
    let decrypted = decrypt_object(PASSPHRASE, &envelope).expect("decrypt reference gzip");
    assert_eq!(decrypted.plaintext.as_slice(), uncompressed.as_slice());
}

#[test]
fn manifest_round_trip() {
    let plaintext = br#"{"format":"chatui.backup.manifest","formatVersion":1}"#;
    let envelope = encrypt(ObjectType::Manifest, "manifest.enc", plaintext, None);
    let decrypted = decrypt_object(PASSPHRASE, &envelope).expect("decrypt");
    assert_eq!(decrypted.header.object_type, ObjectType::Manifest);
    assert_eq!(decrypted.header.object_key, "manifest.enc");
    assert_eq!(decrypted.plaintext.as_slice(), plaintext);
}

#[test]
fn conversation_round_trip() {
    let plaintext = br#"{"id":"11111111-1111-4111-8111-111111111111","messages":[]}"#;
    let envelope = encrypt(
        ObjectType::Conversation,
        "conversations/00112233445566778899aabbccddeeff.enc",
        plaintext,
        None,
    );
    let decrypted = decrypt_object(PASSPHRASE, &envelope).expect("decrypt");
    assert_eq!(decrypted.header.object_type, ObjectType::Conversation);
    assert_eq!(decrypted.plaintext.as_slice(), plaintext);
}

#[test]
fn attachment_round_trip() {
    let plaintext = b"raw attachment bytes that are already compressed";
    let envelope = encrypt(
        ObjectType::Attachment,
        "attachments/0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef.enc",
        plaintext,
        None,
    );
    let decrypted = decrypt_object(PASSPHRASE, &envelope).expect("decrypt");
    assert_eq!(decrypted.header.object_type, ObjectType::Attachment);
    assert_eq!(decrypted.plaintext.as_slice(), plaintext);
}

#[test]
fn header_contains_every_required_field() {
    let envelope = encrypt(ObjectType::Manifest, "manifest.enc", b"{}", None);
    let parts = decode_envelope(&envelope).expect("decode");
    let json: Value =
        serde_json::from_slice(&parts.header.canonical_json().expect("json")).expect("parse");

    for field in [
        "v",
        "formatVersion",
        "alg",
        "kdf",
        "iterations",
        "salt",
        "objectType",
        "objectKey",
    ] {
        assert!(json.get(field).is_some(), "missing header field {field}");
    }
    assert_eq!(json.as_object().expect("object").len(), 8);
    assert_eq!(json["v"], 1);
    assert_eq!(json["formatVersion"], 1);
    assert_eq!(json["alg"], ALG);
    assert_eq!(json["kdf"], KDF);
    assert_eq!(json["iterations"], DEFAULT_ITERATIONS);
}

#[test]
fn kdf_parameters_match_the_frozen_vector() {
    assert_eq!(DEFAULT_ITERATIONS, 600_000);
    assert_eq!(SALT_LEN, 16);
    assert_eq!(KEY_LEN, 32);
    assert_eq!(IV_LEN, 12);
    assert_eq!(TAG_LEN, 16);
}

#[test]
fn fresh_iv_per_write() {
    let first = encrypt(
        ObjectType::Manifest,
        "manifest.enc",
        b"same plaintext",
        None,
    );
    let second = encrypt(
        ObjectType::Manifest,
        "manifest.enc",
        b"same plaintext",
        None,
    );

    let first_parts = decode_envelope(&first).expect("decode first");
    let second_parts = decode_envelope(&second).expect("decode second");
    assert_ne!(first_parts.iv, second_parts.iv);
    assert_ne!(first, second);
}

#[test]
fn wrong_passphrase_fails() {
    let envelope = encrypt(ObjectType::Manifest, "manifest.enc", b"secret", None);
    let error = decrypt_object("wrong horse battery staple", &envelope).expect_err("must fail");
    assert_eq!(error, EnvelopeError::AuthenticationFailed);
    assert_eq!(error.code(), "authentication-failed");
}

#[test]
fn tampered_magic_fails() {
    let mut envelope = encrypt(ObjectType::Manifest, "manifest.enc", b"secret", None);
    envelope[0] ^= 0x01;
    let error = decode_envelope(&envelope).expect_err("must fail");
    assert_eq!(error, EnvelopeError::BadMagic);
    assert_eq!(error.code(), "malformed-envelope");
}

#[test]
fn tampered_header_fails() {
    let envelope = encrypt(ObjectType::Manifest, "manifest.enc", b"secret", None);
    let header_len =
        u32::from_be_bytes(envelope[8..12].try_into().expect("header length")) as usize;
    let mut tampered = envelope.clone();
    tampered[12] = 0xff;
    assert!(header_len > 0);
    let error = decode_envelope(&tampered).expect_err("must fail");
    assert_eq!(error, EnvelopeError::BadHeader);
}

#[test]
fn tampered_iv_fails() {
    let envelope = encrypt(ObjectType::Manifest, "manifest.enc", b"secret", None);
    let mut parts = decode_envelope(&envelope).expect("decode");
    parts.iv[0] ^= 0x01;
    let tampered = encode_envelope(&parts.header, &parts.iv, &parts.ciphertext).expect("encode");
    let error = decrypt_object(PASSPHRASE, &tampered).expect_err("must fail");
    assert_eq!(error, EnvelopeError::AuthenticationFailed);
}

#[test]
fn tampered_ciphertext_fails() {
    let envelope = encrypt(ObjectType::Manifest, "manifest.enc", b"secret", None);
    let mut tampered = envelope.clone();
    let last = tampered.len() - 1;
    tampered[last] ^= 0x01;
    let error = decrypt_object(PASSPHRASE, &tampered).expect_err("must fail");
    assert_eq!(error, EnvelopeError::AuthenticationFailed);
}

#[test]
fn tampered_aad_fails() {
    let envelope = encrypt(ObjectType::Manifest, "manifest.enc", b"secret", None);
    let mut parts = decode_envelope(&envelope).expect("decode");
    parts.header.object_key = "other.enc".to_owned();
    let tampered = encode_envelope(&parts.header, &parts.iv, &parts.ciphertext).expect("encode");
    let error = decrypt_object(PASSPHRASE, &tampered).expect_err("must fail");
    assert_eq!(error, EnvelopeError::AuthenticationFailed);
}

#[test]
fn unknown_versions_are_rejected() {
    let vectors = vectors();
    let vector = &vectors["envelopeVectors"][0];
    let mut header = header_from(vector);

    header.v = 2;
    let header_bytes = serde_json::to_vec(&header).expect("json");
    let mut envelope = MAGIC.to_vec();
    envelope.extend_from_slice(&(header_bytes.len() as u32).to_be_bytes());
    envelope.extend_from_slice(&header_bytes);
    envelope.extend_from_slice(&[0u8; IV_LEN]);
    envelope.extend_from_slice(&[0u8; TAG_LEN]);
    assert_eq!(
        decode_envelope(&envelope).expect_err("v must fail"),
        EnvelopeError::UnsupportedVersion
    );

    header.v = ENVELOPE_VERSION;
    header.format_version = 2;
    let header_bytes = serde_json::to_vec(&header).expect("json");
    let mut envelope = MAGIC.to_vec();
    envelope.extend_from_slice(&(header_bytes.len() as u32).to_be_bytes());
    envelope.extend_from_slice(&header_bytes);
    envelope.extend_from_slice(&[0u8; IV_LEN]);
    envelope.extend_from_slice(&[0u8; TAG_LEN]);
    assert_eq!(
        decode_envelope(&envelope).expect_err("formatVersion must fail"),
        EnvelopeError::UnsupportedVersion
    );
}

#[test]
fn shared_failure_vectors_match_expected_codes() {
    let vectors = vectors();
    let base = &vectors["envelopeVectors"][0];
    let base_envelope = b64(base, "envelopeBase64");

    for failure in vectors["failureVectors"]
        .as_array()
        .expect("failure vectors")
    {
        let name = text(failure, "name");
        let expected = text(failure, "expected");
        let error = match name {
            "wrong-passphrase" => decrypt_object(text(failure, "wrongPassphrase"), &base_envelope)
                .expect_err("wrong passphrase must fail"),
            "tampered-aad-object-key" => {
                let mut parts = decode_envelope(&base_envelope).expect("decode");
                parts.header.object_key = text(failure, "aadObjectKey").to_owned();
                let tampered =
                    encode_envelope(&parts.header, &parts.iv, &parts.ciphertext).expect("encode");
                decrypt_object(text(base, "passphrase"), &tampered).expect_err("aad must fail")
            }
            _ => decrypt_object(
                text(base, "passphrase"),
                &b64(failure, "tamperedEnvelopeBase64"),
            )
            .expect_err("tampered envelope must fail"),
        };
        assert_eq!(error.code(), expected, "failure vector {name}");
    }
}

#[test]
fn conversation_is_gzipped_by_default() {
    let message = r#"{"id":"22222222-2222-4222-8222-222222222222","role":"user","parts":[{"type":"text","text":"synthetic repeated content"}]}"#;
    let messages = std::iter::repeat_n(message, 200)
        .collect::<Vec<_>>()
        .join(",");
    let plaintext =
        format!(r#"{{"id":"11111111-1111-4111-8111-111111111111","messages":[{messages}]}}"#);
    let envelope = encrypt(
        ObjectType::Conversation,
        "conversations/00112233445566778899aabbccddeeff.enc",
        plaintext.as_bytes(),
        None,
    );
    let parts = decode_envelope(&envelope).expect("decode");
    assert!(
        parts.ciphertext.len() < plaintext.len() + TAG_LEN,
        "conversation ciphertext should be smaller than the uncompressed plaintext"
    );

    let decrypted = decrypt_object(PASSPHRASE, &envelope).expect("decrypt");
    assert_eq!(decrypted.plaintext.as_slice(), plaintext.as_bytes());
}

#[test]
fn manifest_and_attachment_are_not_gzipped() {
    let manifest = br#"{"format":"chatui.backup.manifest","formatVersion":1}"#;
    let manifest_envelope = encrypt(ObjectType::Manifest, "manifest.enc", manifest, None);
    let manifest_parts = decode_envelope(&manifest_envelope).expect("decode manifest");
    assert_eq!(manifest_parts.ciphertext.len(), manifest.len() + TAG_LEN);

    let attachment = b"raw attachment bytes that are already compressed";
    let attachment_envelope = encrypt(
        ObjectType::Attachment,
        "attachments/0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef.enc",
        attachment,
        None,
    );
    let attachment_parts = decode_envelope(&attachment_envelope).expect("decode attachment");
    assert_eq!(
        attachment_parts.ciphertext.len(),
        attachment.len() + TAG_LEN
    );
}

#[test]
fn attachment_compression_is_rejected() {
    let error = encrypt_object(EncryptOptions {
        passphrase: PASSPHRASE,
        salt: &SALT,
        object_type: ObjectType::Attachment,
        object_key: "attachments/0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef.enc",
        plaintext: b"raw",
        compress: Some(true),
        iv: None,
        iterations: None,
    })
    .expect_err("attachment compression must be rejected");
    assert_eq!(error, EnvelopeError::CompressionNotAllowed);
}

#[test]
fn verification_token_round_trip() {
    let token = create_verification_token(PASSPHRASE, &SALT).expect("token");
    assert!(verify_passphrase(PASSPHRASE, &token));
    assert!(!verify_passphrase("wrong horse battery staple", &token));

    let mut tampered = token.clone();
    let last = tampered.len() - 1;
    tampered[last] ^= 0x01;
    assert!(!verify_passphrase(PASSPHRASE, &tampered));
}

#[test]
fn envelope_magic_is_not_the_secret_file_magic() {
    assert_eq!(MAGIC, b"CHATUI1\n");
    assert_ne!(MAGIC, b"CHUIENC1");
}

#[test]
fn malformed_envelopes_are_rejected() {
    assert_eq!(
        decode_envelope(b"short").expect_err("short"),
        EnvelopeError::MalformedEnvelope
    );

    let mut zero_header = MAGIC.to_vec();
    zero_header.extend_from_slice(&0u32.to_be_bytes());
    zero_header.extend_from_slice(&[0u8; IV_LEN + TAG_LEN]);
    assert_eq!(
        decode_envelope(&zero_header).expect_err("zero header"),
        EnvelopeError::MalformedEnvelope
    );

    let mut too_short = MAGIC.to_vec();
    too_short.extend_from_slice(&1u32.to_be_bytes());
    too_short.push(b'{');
    too_short.extend_from_slice(&[0u8; IV_LEN]);
    assert_eq!(
        decode_envelope(&too_short).expect_err("missing tag"),
        EnvelopeError::MalformedEnvelope
    );
}

fn timestamp(value: &str) -> Timestamp {
    Timestamp::parse(value).expect("valid timestamp")
}

fn conversation() -> Conversation {
    Conversation {
        id: "0190f5a0-7a00-7000-8000-000000000001".to_owned(),
        title: "Synthetic backup conversation".to_owned(),
        system_prompt: "You are a synthetic test assistant.".to_owned(),
        provider_id: "openrouter".to_owned(),
        model_id: "synthetic/model-1".to_owned(),
        model_label: "Synthetic Model 1".to_owned(),
        params: ChatParams::default(),
        pinned: false,
        archived: false,
        temporary: false,
        created_at: timestamp("2026-01-01T00:00:00.000Z"),
        updated_at: timestamp("2026-01-01T00:05:00.000Z"),
        last_message_at: timestamp("2026-01-01T00:03:00.000Z"),
        message_count: 2,
        last_error: None,
        sync: None,
        storage_key: "00112233445566778899aabbccddeeff".to_owned(),
        revision: 3,
    }
}

fn message(id: &str, sequence: u64, text: &str) -> Message {
    Message {
        id: id.to_owned(),
        conversation_id: conversation().id,
        sequence,
        role: MessageRole::User,
        parts: vec![ContentPart::Text {
            text: text.to_owned(),
        }],
        status: MessageStatus::Complete,
        provider_id: Some("openrouter".to_owned()),
        model_id: Some("synthetic/model-1".to_owned()),
        model_label: Some("Synthetic Model 1".to_owned()),
        usage: Some(Usage {
            input_tokens: Some(12),
            output_tokens: Some(8),
            total_tokens: Some(20),
            cost_usd: Some(0.0001),
            estimated: false,
        }),
        finish_reason: None,
        error: None,
        latency_ms: Some(120),
        first_token_ms: Some(40),
        created_at: timestamp("2026-01-01T00:01:00.000Z"),
        updated_at: timestamp("2026-01-01T00:01:00.000Z"),
        deleted_at: None,
    }
}

fn attachment() -> Attachment {
    Attachment {
        id: "0190f5a0-7a00-7000-8000-000000000020".to_owned(),
        message_id: Some("0190f5a0-7a00-7000-8000-000000000011".to_owned()),
        kind: AttachmentKind::Image,
        mime_type: "image/png".to_owned(),
        name: "synthetic.png".to_owned(),
        size: 1234,
        sha256: "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef".to_owned(),
        storage_key: "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef".to_owned(),
        width: None,
        height: None,
        created_at: timestamp("2026-01-01T00:01:00.000Z"),
    }
}

fn tombstone() -> Tombstone {
    Tombstone {
        id: "tombstone-1".to_owned(),
        conversation_id: "0190f5a0-7a00-7000-8000-000000000002".to_owned(),
        message_id: None,
        deleted_at: timestamp("2026-01-01T00:04:00.000Z"),
        device_id: "device-synthetic-0001".to_owned(),
    }
}

#[test]
fn manifest_builds_and_round_trips() {
    let body = build_manifest(
        "device-synthetic-0001",
        timestamp("2026-01-01T00:00:00.000Z"),
        timestamp("2026-01-01T00:05:00.000Z"),
        &[conversation()],
        &[attachment()],
        &[tombstone()],
    )
    .expect("build manifest");

    assert_eq!(body.format_version, MANIFEST_FORMAT_VERSION);
    assert_eq!(body.conversations.len(), 1);
    assert_eq!(
        body.conversations[0].object_key,
        "conversations/00112233445566778899aabbccddeeff.enc"
    );
    assert_eq!(
        body.attachments[0].object_key,
        "attachments/0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef.enc"
    );
    assert_eq!(body.tombstones.len(), 1);

    let bytes = serde_json::to_vec(&body).expect("serialize manifest");
    let parsed = parse_manifest(&bytes).expect("parse manifest");
    assert_eq!(parsed, body);
}

#[test]
fn manifest_fixture_parses_and_validates() {
    let path = concat!(
        env!("CARGO_MANIFEST_DIR"),
        "/../../../contracts/fixtures/backup/manifest.json"
    );
    let bytes = std::fs::read(path).expect("read manifest fixture");
    let body = parse_manifest(&bytes).expect("parse manifest fixture");
    assert_eq!(body.format_version, 1);
    assert_eq!(body.conversations.len(), 1);
    assert_eq!(body.attachments.len(), 1);
    assert_eq!(body.tombstones.len(), 1);
}

#[test]
fn manifest_rejects_unsupported_version_and_bad_keys() {
    let mut body = build_manifest(
        "device-synthetic-0001",
        timestamp("2026-01-01T00:00:00.000Z"),
        timestamp("2026-01-01T00:05:00.000Z"),
        &[conversation()],
        &[],
        &[],
    )
    .expect("build manifest");
    body.format_version = 2;
    let bytes = serde_json::to_vec(&body).expect("serialize manifest");
    assert_eq!(
        parse_manifest(&bytes).expect_err("version must fail"),
        BackupDataError::UnsupportedVersion
    );

    let mut invalid = conversation();
    invalid.storage_key = "not-hex".to_owned();
    assert!(matches!(
        build_manifest(
            "device-synthetic-0001",
            timestamp("2026-01-01T00:00:00.000Z"),
            timestamp("2026-01-01T00:05:00.000Z"),
            &[invalid],
            &[],
            &[],
        ),
        Err(BackupDataError::Invalid(_))
    ));
}

#[test]
fn conversation_payload_builds_and_round_trips() {
    let payload = build_conversation_payload(
        &conversation(),
        &[
            message("0190f5a0-7a00-7000-8000-000000000010", 0, "hello"),
            message("0190f5a0-7a00-7000-8000-000000000011", 1, "world"),
        ],
        &[attachment()],
    )
    .expect("build payload");

    assert_eq!(payload.storage_key, "00112233445566778899aabbccddeeff");
    assert_eq!(payload.revision, 3);
    assert_eq!(payload.messages.len(), 2);
    assert_eq!(payload.attachments.len(), 1);

    let bytes = serde_json::to_vec(&payload).expect("serialize payload");
    let parsed = parse_conversation_payload(&bytes).expect("parse payload");
    assert_eq!(parsed, payload);
}

#[test]
fn conversation_payload_fixture_parses_and_validates() {
    let path = concat!(
        env!("CARGO_MANIFEST_DIR"),
        "/../../../contracts/fixtures/backup/conversation.json"
    );
    let bytes = std::fs::read(path).expect("read conversation fixture");
    let payload = parse_conversation_payload(&bytes).expect("parse conversation fixture");
    assert_eq!(payload.id, "0190f5a0-7a00-7000-8000-000000000001");
    assert_eq!(payload.messages.len(), 2);
    assert_eq!(payload.attachments.len(), 1);
}

#[test]
fn conversation_payload_excludes_secrets_and_provider_keys() {
    let payload = build_conversation_payload(&conversation(), &[message("m1", 0, "hello")], &[])
        .expect("build payload");
    let json = serde_json::to_string(&payload).expect("serialize payload");
    for forbidden in ["apiKey", "api_key", "secret", "Authorization", "password"] {
        assert!(
            !json.contains(forbidden),
            "payload unexpectedly contains {forbidden}"
        );
    }
}
