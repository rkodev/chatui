//! Attachment validation, image processing, and content-addressed storage tests.

use std::io::Cursor;

use chatui_domain::{AttachmentKind, Timestamp};
use chatui_storage::AttachmentStore;
use image::codecs::jpeg::JpegEncoder;
use image::{ExtendedColorType, ImageEncoder, RgbaImage};

use super::attachments::{
    AttachmentFileInfo, AttachmentValidationCode, MAX_IMAGE_BYTES, MAX_IMAGE_EDGE, MAX_TEXT_BYTES,
    clipboard_images, dropped_files, format_attachment_size, process_image, scaled_dimensions,
    store_pending_attachment, validate_attachment_file,
};

fn png_bytes(width: u32, height: u32) -> Vec<u8> {
    let image = RgbaImage::from_fn(width, height, |x, y| {
        image::Rgba([(x % 256) as u8, (y % 256) as u8, 128, 255])
    });
    let mut bytes = Vec::new();
    image
        .write_to(&mut Cursor::new(&mut bytes), image::ImageFormat::Png)
        .expect("encode png");
    bytes
}

fn jpeg_bytes(width: u32, height: u32) -> Vec<u8> {
    let image = RgbaImage::from_fn(width, height, |x, y| {
        image::Rgba([(x % 256) as u8, (y % 256) as u8, 128, 255])
    });
    let rgb = image::DynamicImage::ImageRgba8(image).to_rgb8();
    let mut bytes = Vec::new();
    JpegEncoder::new_with_quality(&mut bytes, 90)
        .write_image(rgb.as_raw(), width, height, ExtendedColorType::Rgb8)
        .expect("encode jpeg");
    bytes
}

fn jpeg_with_exif(width: u32, height: u32) -> Vec<u8> {
    let jpeg = jpeg_bytes(width, height);
    // Minimal big-endian TIFF header with an empty IFD0. The JPEG decoder reads
    // this as an EXIF APP1 segment and the re-encoder must drop it.
    let exif_payload = b"Exif\0\0MM\0*\0\0\0\x08\0\0\0\0\0\0\0\0";
    let segment_length = (exif_payload.len() + 2) as u16;
    let mut with_exif = Vec::with_capacity(jpeg.len() + exif_payload.len() + 4);
    with_exif.extend_from_slice(&jpeg[..2]);
    with_exif.push(0xFF);
    with_exif.push(0xE1);
    with_exif.extend_from_slice(&segment_length.to_be_bytes());
    with_exif.extend_from_slice(exif_payload);
    with_exif.extend_from_slice(&jpeg[2..]);
    with_exif
}

#[test]
fn validates_supported_images_and_text() {
    let image = validate_attachment_file(&AttachmentFileInfo::new("photo.png", "image/png", 1024))
        .expect("image");
    assert_eq!(image.kind, AttachmentKind::Image);
    assert_eq!(image.mime_type, "image/png");
    assert_eq!(image.name, "photo.png");

    let text =
        validate_attachment_file(&AttachmentFileInfo::new("notes.md", "", 1024)).expect("text");
    assert_eq!(text.kind, AttachmentKind::File);
    assert_eq!(text.mime_type, "text/markdown");

    let source =
        validate_attachment_file(&AttachmentFileInfo::new("main.rs", "", 1024)).expect("source");
    assert_eq!(source.kind, AttachmentKind::File);
    assert_eq!(source.mime_type, "text/plain");
}

#[test]
fn rejects_empty_oversized_and_unsupported_files() {
    let empty = validate_attachment_file(&AttachmentFileInfo::new("empty.txt", "text/plain", 0))
        .expect_err("empty");
    assert_eq!(empty.code, AttachmentValidationCode::Empty);

    let oversized = validate_attachment_file(&AttachmentFileInfo::new(
        "big.png",
        "image/png",
        MAX_IMAGE_BYTES + 1,
    ))
    .expect_err("oversized image");
    assert_eq!(oversized.code, AttachmentValidationCode::TooLarge);

    let oversized_text = validate_attachment_file(&AttachmentFileInfo::new(
        "big.txt",
        "text/plain",
        MAX_TEXT_BYTES + 1,
    ))
    .expect_err("oversized text");
    assert_eq!(oversized_text.code, AttachmentValidationCode::TooLarge);

    let unsupported = validate_attachment_file(&AttachmentFileInfo::new(
        "app.exe",
        "application/octet-stream",
        10,
    ))
    .expect_err("unsupported");
    assert_eq!(unsupported.code, AttachmentValidationCode::UnsupportedType);

    let missing = validate_attachment_file(&AttachmentFileInfo::new("", "text/plain", 10))
        .expect_err("missing name");
    assert_eq!(missing.code, AttachmentValidationCode::MissingName);
}

#[test]
fn sanitizes_file_names() {
    let file = validate_attachment_file(&AttachmentFileInfo::new(
        "../../etc/passwd.txt",
        "text/plain",
        10,
    ))
    .expect("valid");
    assert_eq!(file.name, "passwd.txt");

    let control =
        validate_attachment_file(&AttachmentFileInfo::new("a\u{0}b.txt", "text/plain", 10))
            .expect("valid");
    assert_eq!(control.name, "ab.txt");
}

#[test]
fn scaled_dimensions_never_upscales() {
    assert_eq!(scaled_dimensions(100, 50, MAX_IMAGE_EDGE), (100, 50));
    assert_eq!(scaled_dimensions(4096, 2048, MAX_IMAGE_EDGE), (2048, 1024));
    assert_eq!(scaled_dimensions(1000, 3000, MAX_IMAGE_EDGE), (683, 2048));
    assert_eq!(scaled_dimensions(0, 0, MAX_IMAGE_EDGE), (0, 0));
}

#[test]
fn process_image_downscales_to_2048_and_strips_metadata() {
    let bytes = png_bytes(3000, 1000);
    let processed = process_image(&bytes, "image/png");

    assert_eq!(processed.width, 2048);
    assert_eq!(processed.height, 683);
    assert_eq!(processed.mime_type, "image/webp");

    let decoded = image::load_from_memory(&processed.bytes).expect("decode processed image");
    assert_eq!(decoded.width(), 2048);
    assert_eq!(decoded.height(), 683);

    let with_exif = jpeg_with_exif(100, 100);
    assert!(
        with_exif.windows(6).any(|window| window == b"Exif\0\0"),
        "test fixture did not contain EXIF"
    );
    let processed = process_image(&with_exif, "image/jpeg");
    assert_eq!(processed.mime_type, "image/jpeg");
    assert!(
        !processed
            .bytes
            .windows(6)
            .any(|window| window == b"Exif\0\0"),
        "re-encoded image retained EXIF metadata"
    );
}

#[test]
fn process_image_returns_original_bytes_when_undecodable() {
    let bytes = b"not an image";
    let processed = process_image(bytes, "image/png");

    assert_eq!(processed.bytes, bytes);
    assert_eq!(processed.mime_type, "image/png");
    assert_eq!(processed.width, 0);
    assert_eq!(processed.height, 0);
}

#[test]
fn store_pending_attachment_uses_content_addressed_storage() {
    let directory = tempfile::tempdir().expect("temporary directory");
    let store = AttachmentStore::new(directory.path());
    let file = validate_attachment_file(&AttachmentFileInfo::new("notes.txt", "text/plain", 5))
        .expect("valid");

    let pending = store_pending_attachment(&store, "attachment-1", file, b"hello").expect("store");

    assert_eq!(pending.size, 5);
    assert_eq!(pending.sha256, pending.storage_key);
    assert_eq!(pending.storage_key.len(), 64);
    assert_eq!(
        store.get(&pending.storage_key).expect("read bytes"),
        b"hello"
    );

    let attachment = pending.to_attachment(
        Some("message-1".to_owned()),
        Timestamp::parse("2026-01-01T00:00:00.000Z").expect("timestamp"),
    );
    assert_eq!(attachment.message_id.as_deref(), Some("message-1"));
    assert_eq!(attachment.kind, AttachmentKind::File);
    assert_eq!(attachment.storage_key, pending.storage_key);
}

#[test]
fn clipboard_paste_accepts_images_only() {
    let files = vec![
        AttachmentFileInfo::new("photo.png", "image/png", 10),
        AttachmentFileInfo::new("notes.txt", "text/plain", 10),
    ];

    let images = clipboard_images(&files);
    assert_eq!(images.len(), 1);
    assert_eq!(images[0].name, "photo.png");
    assert_eq!(dropped_files(&files).len(), 2);
}

#[test]
fn format_attachment_size_is_human_readable() {
    assert_eq!(format_attachment_size(512), "512 B");
    assert_eq!(format_attachment_size(2048), "2.0 KB");
    assert_eq!(format_attachment_size(2 * 1024 * 1024), "2.0 MB");
}
