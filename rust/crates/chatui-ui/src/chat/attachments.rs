//! Attachment validation, image processing, and content-addressed storage.
//!
//! The composer accepts images (PNG, JPEG, WebP, GIF) and plain text files.
//! Validation classifies input by MIME type and extension only; it never
//! executes or renders file content. Images are decoded, downscaled so the
//! longest edge is at most [`MAX_IMAGE_EDGE`], and re-encoded. Re-encoding drops
//! EXIF and other container metadata, including GPS and timestamps.
//!
//! Bytes are written through [`chatui_storage::AttachmentStore`], which is
//! content-addressed by SHA-256. The composer keeps [`PendingAttachment`]
//! metadata until the message is sent; the metadata row is inserted only after
//! the message exists, because the storage schema requires a message ID.
//!
//! Limits mirror the web and Android clients and spec section 3.6.

use std::io::Cursor;

use chatui_domain::{Attachment, AttachmentKind, Timestamp};
use chatui_storage::{AttachmentStore, StorageError};
use image::codecs::jpeg::JpegEncoder;
use image::codecs::webp::WebPEncoder;
use image::imageops::FilterType;
use image::metadata::Orientation;
use image::{DynamicImage, ExtendedColorType, ImageDecoder, ImageEncoder, ImageReader};

/// Maximum image size accepted before downscaling.
pub const MAX_IMAGE_BYTES: u64 = 20 * 1024 * 1024;
/// Maximum plain-text file size.
pub const MAX_TEXT_BYTES: u64 = 2 * 1024 * 1024;
/// Longest edge of a stored image, in pixels.
pub const MAX_IMAGE_EDGE: u32 = 2048;
/// Maximum display-name length, in characters.
pub const MAX_FILE_NAME_LENGTH: usize = 255;
/// JPEG re-encode quality.
pub const OUTPUT_QUALITY: u8 = 90;

/// Image MIME types accepted by the composer.
pub const SUPPORTED_IMAGE_MIME_TYPES: &[&str] =
    &["image/png", "image/jpeg", "image/webp", "image/gif"];

/// Text MIME types accepted by the composer.
pub const SUPPORTED_TEXT_MIME_TYPES: &[&str] = &[
    "text/plain",
    "text/markdown",
    "text/csv",
    "text/xml",
    "text/html",
    "text/css",
    "application/json",
    "application/xml",
    "application/javascript",
];

/// Source-file extensions that are safe to treat as plain text.
pub const SUPPORTED_TEXT_EXTENSIONS: &[&str] = &[
    "txt", "md", "markdown", "json", "jsonc", "csv", "tsv", "log", "yml", "yaml", "xml", "html",
    "htm", "css", "scss", "less", "js", "jsx", "mjs", "cjs", "ts", "tsx", "py", "rb", "go", "rs",
    "java", "kt", "kts", "c", "h", "cpp", "hpp", "cc", "cs", "php", "swift", "sh", "bash", "zsh",
    "toml", "ini", "cfg", "sql", "gradle", "dart", "lua", "r",
];

const IMAGE_EXTENSIONS: &[(&str, &str)] = &[
    ("png", "image/png"),
    ("jpg", "image/jpeg"),
    ("jpeg", "image/jpeg"),
    ("webp", "image/webp"),
    ("gif", "image/gif"),
];

/// Why an attachment was rejected.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum AttachmentValidationCode {
    /// The file is empty.
    Empty,
    /// The file exceeds the image or text limit.
    TooLarge,
    /// The file is neither a supported image nor a supported text file.
    UnsupportedType,
    /// The file has no usable display name.
    MissingName,
}

/// One validation failure with user-facing copy.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct AttachmentValidationError {
    /// Machine-readable reason.
    pub code: AttachmentValidationCode,
    /// Actionable user-facing message.
    pub message: String,
}

impl AttachmentValidationError {
    /// Build a validation error.
    pub fn new(code: AttachmentValidationCode, message: impl Into<String>) -> Self {
        Self {
            code,
            message: message.into(),
        }
    }
}

impl std::fmt::Display for AttachmentValidationError {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        formatter.write_str(&self.message)
    }
}

impl std::error::Error for AttachmentValidationError {}

/// A file offered by the picker, drag-and-drop, or clipboard.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct AttachmentFileInfo {
    /// Original file name.
    pub name: String,
    /// Declared MIME type, or an empty string when the platform does not know.
    pub mime_type: String,
    /// Size in bytes.
    pub size: u64,
}

impl AttachmentFileInfo {
    /// Build file information.
    pub fn new(name: impl Into<String>, mime_type: impl Into<String>, size: u64) -> Self {
        Self {
            name: name.into(),
            mime_type: mime_type.into(),
            size,
        }
    }
}

/// A file that passed validation.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ValidatedAttachmentFile {
    /// Attachment kind.
    pub kind: AttachmentKind,
    /// Normalized MIME type.
    pub mime_type: String,
    /// Sanitized display name.
    pub name: String,
}

/// Attachment metadata held by the composer before the message is sent.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PendingAttachment {
    /// Local attachment ID.
    pub id: String,
    /// Attachment kind.
    pub kind: AttachmentKind,
    /// MIME type of the stored bytes.
    pub mime_type: String,
    /// Sanitized display name.
    pub name: String,
    /// Stored size in bytes.
    pub size: u64,
    /// SHA-256 content hash.
    pub sha256: String,
    /// Content-addressed storage key.
    pub storage_key: String,
    /// Image width in pixels, or `None` for non-images.
    pub width: Option<u32>,
    /// Image height in pixels, or `None` for non-images.
    pub height: Option<u32>,
}

impl PendingAttachment {
    /// Build the persisted attachment metadata once the message exists.
    pub fn to_attachment(&self, message_id: Option<String>, created_at: Timestamp) -> Attachment {
        Attachment {
            id: self.id.clone(),
            message_id,
            kind: self.kind,
            mime_type: self.mime_type.clone(),
            name: self.name.clone(),
            size: self.size,
            sha256: self.sha256.clone(),
            storage_key: self.storage_key.clone(),
            width: self.width,
            height: self.height,
            created_at,
        }
    }
}

/// Bytes produced by image processing.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ProcessedImage {
    /// Re-encoded bytes.
    pub bytes: Vec<u8>,
    /// MIME type of the re-encoded bytes.
    pub mime_type: String,
    /// Output width in pixels, or `0` when the image could not be decoded.
    pub width: u32,
    /// Output height in pixels, or `0` when the image could not be decoded.
    pub height: u32,
}

/// A failure while validating or storing an attachment.
#[derive(Debug)]
pub enum AttachmentError {
    /// The file failed validation.
    Validation(AttachmentValidationError),
    /// The content-addressed store failed.
    Storage(StorageError),
}

impl std::fmt::Display for AttachmentError {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::Validation(error) => write!(formatter, "{error}"),
            Self::Storage(error) => write!(formatter, "{error}"),
        }
    }
}

impl std::error::Error for AttachmentError {
    fn source(&self) -> Option<&(dyn std::error::Error + 'static)> {
        match self {
            Self::Validation(error) => Some(error),
            Self::Storage(error) => Some(error),
        }
    }
}

impl From<AttachmentValidationError> for AttachmentError {
    fn from(error: AttachmentValidationError) -> Self {
        Self::Validation(error)
    }
}

impl From<StorageError> for AttachmentError {
    fn from(error: StorageError) -> Self {
        Self::Storage(error)
    }
}

/// Lower-case extension without the dot, or an empty string.
pub fn file_extension(name: &str) -> &str {
    match name.rsplit_once('.') {
        Some((_, extension)) => extension,
        None => "",
    }
}

/// Strip path separators and control characters from a display name.
pub fn sanitize_file_name(name: &str) -> String {
    let base = name.rsplit(['/', '\\']).next().unwrap_or("");
    let cleaned = base
        .chars()
        .filter(|character| {
            let code = *character as u32;
            code >= 0x20 && code != 0x7f
        })
        .collect::<String>()
        .split_whitespace()
        .collect::<Vec<_>>()
        .join(" ");

    if cleaned.is_empty() {
        return "attachment".to_owned();
    }

    cleaned.chars().take(MAX_FILE_NAME_LENGTH).collect()
}

fn image_mime_for_extension(extension: &str) -> Option<&'static str> {
    IMAGE_EXTENSIONS
        .iter()
        .find(|(candidate, _)| *candidate == extension)
        .map(|(_, mime_type)| *mime_type)
}

fn mime_type_for_extension(extension: &str) -> &'static str {
    match extension {
        "md" | "markdown" => "text/markdown",
        "csv" | "tsv" => "text/csv",
        "json" | "jsonc" => "application/json",
        "xml" => "application/xml",
        "html" | "htm" => "text/html",
        "css" | "scss" | "less" => "text/css",
        _ => "text/plain",
    }
}

/// Classify a file and reject unsupported, empty, or oversized input.
///
/// The extension fallback matters when the platform reports an empty MIME type
/// for source files. An unknown declared MIME type is never overridden by the
/// extension, so a disguised executable cannot be accepted as text.
pub fn validate_attachment_file(
    file: &AttachmentFileInfo,
) -> Result<ValidatedAttachmentFile, AttachmentValidationError> {
    let name = sanitize_file_name(&file.name);

    if name == "attachment" {
        return Err(AttachmentValidationError::new(
            AttachmentValidationCode::MissingName,
            "The file has no usable name.",
        ));
    }

    if file.size == 0 {
        return Err(AttachmentValidationError::new(
            AttachmentValidationCode::Empty,
            format!("{name} is empty."),
        ));
    }

    let declared = file.mime_type.trim().to_ascii_lowercase();
    let extension = file_extension(&name);
    let image_by_mime = SUPPORTED_IMAGE_MIME_TYPES.contains(&declared.as_str());
    let image_by_extension = declared.is_empty() && image_mime_for_extension(extension).is_some();
    let text_by_mime = SUPPORTED_TEXT_MIME_TYPES.contains(&declared.as_str());
    let text_by_extension = declared.is_empty() && SUPPORTED_TEXT_EXTENSIONS.contains(&extension);

    if image_by_mime || image_by_extension {
        if file.size > MAX_IMAGE_BYTES {
            return Err(AttachmentValidationError::new(
                AttachmentValidationCode::TooLarge,
                format!(
                    "{name} is larger than the {} MB image limit.",
                    MAX_IMAGE_BYTES / (1024 * 1024)
                ),
            ));
        }

        let mime_type = if image_by_mime {
            declared
        } else {
            image_mime_for_extension(extension)
                .unwrap_or("image/png")
                .to_owned()
        };

        return Ok(ValidatedAttachmentFile {
            kind: AttachmentKind::Image,
            mime_type,
            name: if extension.is_empty() {
                format!("{name}.png")
            } else {
                name
            },
        });
    }

    if text_by_mime || text_by_extension {
        if file.size > MAX_TEXT_BYTES {
            return Err(AttachmentValidationError::new(
                AttachmentValidationCode::TooLarge,
                format!(
                    "{name} is larger than the {} MB text-file limit.",
                    MAX_TEXT_BYTES / (1024 * 1024)
                ),
            ));
        }

        return Ok(ValidatedAttachmentFile {
            kind: AttachmentKind::File,
            mime_type: if text_by_mime {
                declared
            } else {
                mime_type_for_extension(extension).to_owned()
            },
            name,
        });
    }

    Err(AttachmentValidationError::new(
        AttachmentValidationCode::UnsupportedType,
        format!("{name} is not a supported image or text file."),
    ))
}

/// Human-readable size for chips and message rendering.
pub fn format_attachment_size(bytes: u64) -> String {
    if bytes < 1024 {
        return format!("{bytes} B");
    }

    if bytes < 1024 * 1024 {
        return format!("{:.1} KB", bytes as f64 / 1024.0);
    }

    format!("{:.1} MB", bytes as f64 / (1024.0 * 1024.0))
}

/// Scale dimensions so the longest edge is at most `max_edge`.
///
/// Upscaling never happens. Zero dimensions are returned unchanged so a
/// malformed decoder result cannot produce a zero-size image.
pub fn scaled_dimensions(width: u32, height: u32, max_edge: u32) -> (u32, u32) {
    if width == 0 || height == 0 {
        return (width, height);
    }

    let longest = width.max(height);

    if longest <= max_edge {
        return (width, height);
    }

    let ratio = f64::from(max_edge) / f64::from(longest);
    (
        ((f64::from(width) * ratio).round() as u32).max(1),
        ((f64::from(height) * ratio).round() as u32).max(1),
    )
}

/// Prefer WebP for images that may have transparency, JPEG for opaque photos.
pub fn output_mime_type_for(input_mime_type: &str) -> &'static str {
    if input_mime_type.eq_ignore_ascii_case("image/jpeg") {
        "image/jpeg"
    } else {
        "image/webp"
    }
}

/// Decode, downscale, and re-encode an image.
///
/// Re-encoding drops EXIF and other container metadata. When the image cannot
/// be decoded, the original bytes are returned unchanged rather than losing the
/// attachment.
pub fn process_image(bytes: &[u8], mime_type: &str) -> ProcessedImage {
    match decode_and_process(bytes, mime_type) {
        Ok(processed) => processed,
        Err(_) => ProcessedImage {
            bytes: bytes.to_vec(),
            mime_type: mime_type.to_owned(),
            width: 0,
            height: 0,
        },
    }
}

fn decode_and_process(bytes: &[u8], mime_type: &str) -> Result<ProcessedImage, image::ImageError> {
    let reader = ImageReader::new(Cursor::new(bytes)).with_guessed_format()?;
    let mut decoder = reader.into_decoder()?;
    let orientation = decoder.orientation().unwrap_or(Orientation::NoTransforms);
    let mut image = DynamicImage::from_decoder(decoder)?;
    image.apply_orientation(orientation);

    let (width, height) = scaled_dimensions(image.width(), image.height(), MAX_IMAGE_EDGE);
    let resized = if width != image.width() || height != image.height() {
        image.resize_exact(width, height, FilterType::Lanczos3)
    } else {
        image
    };

    let output_mime_type = output_mime_type_for(mime_type);
    let mut output = Vec::new();

    if output_mime_type == "image/jpeg" {
        let rgb = resized.to_rgb8();
        JpegEncoder::new_with_quality(&mut output, OUTPUT_QUALITY).write_image(
            rgb.as_raw(),
            rgb.width(),
            rgb.height(),
            ExtendedColorType::Rgb8,
        )?;
    } else {
        let rgba = resized.to_rgba8();
        WebPEncoder::new_lossless(&mut output).write_image(
            rgba.as_raw(),
            rgba.width(),
            rgba.height(),
            ExtendedColorType::Rgba8,
        )?;
    }

    Ok(ProcessedImage {
        bytes: output,
        mime_type: output_mime_type.to_owned(),
        width,
        height,
    })
}

/// Store validated bytes in the content-addressed attachment store.
///
/// Images are downscaled and re-encoded before hashing. The returned metadata
/// is not yet linked to a message; call [`PendingAttachment::to_attachment`]
/// after the message is persisted.
pub fn store_pending_attachment(
    store: &AttachmentStore,
    id: impl Into<String>,
    file: ValidatedAttachmentFile,
    bytes: &[u8],
) -> Result<PendingAttachment, AttachmentError> {
    let (bytes, mime_type, width, height) = match file.kind {
        AttachmentKind::Image => {
            let processed = process_image(bytes, &file.mime_type);
            let width = (processed.width > 0).then_some(processed.width);
            let height = (processed.height > 0).then_some(processed.height);
            (processed.bytes, processed.mime_type, width, height)
        }
        AttachmentKind::File => (bytes.to_vec(), file.mime_type.clone(), None, None),
    };

    let storage_key = store.put(&bytes)?;

    Ok(PendingAttachment {
        id: id.into(),
        kind: file.kind,
        mime_type,
        name: file.name,
        size: bytes.len() as u64,
        sha256: storage_key.clone(),
        storage_key,
        width,
        height,
    })
}

/// Files accepted from a clipboard paste.
///
/// Clipboard paste accepts images only; pasted text is handled by the text
/// editor itself.
pub fn clipboard_images(files: &[AttachmentFileInfo]) -> Vec<AttachmentFileInfo> {
    files
        .iter()
        .filter(|file| file.mime_type.to_ascii_lowercase().starts_with("image/"))
        .cloned()
        .collect()
}

/// Files accepted from a drag-and-drop gesture.
///
/// Drag-and-drop accepts every file and lets validation reject unsupported
/// types with a user-facing message.
pub fn dropped_files(files: &[AttachmentFileInfo]) -> Vec<AttachmentFileInfo> {
    files.to_vec()
}
