//! Native file dialogs and drag-and-drop routing.
//!
//! Dialogs use `rfd` (GTK3 on Linux, native on macOS/Windows). Drag-and-drop
//! paths are converted to the same [`AttachmentFileInfo`] values the composer
//! uses, validated with the shared attachment rules, and stored through the
//! content-addressed attachment store.

use std::collections::HashMap;
use std::path::{Path, PathBuf};

use chatui_storage::AttachmentStore;
use chatui_ui::chat::{
    AttachmentError, AttachmentFileInfo, AttachmentValidationError, ComposerState,
    PendingAttachment, ValidatedAttachmentFile, store_pending_attachment, validate_attachment_file,
};

/// Extensions offered by the attachment picker.
pub const ATTACHMENT_EXTENSIONS: &[&str] = &[
    "png", "jpg", "jpeg", "webp", "gif", "txt", "md", "json", "csv", "log", "rs", "ts", "tsx",
    "js", "jsx", "py", "html", "css", "toml", "yaml", "yml",
];

/// A native dialog and file I/O backend.
pub trait DialogService {
    /// Pick one attachment file.
    fn pick_attachment(&self) -> Option<PathBuf>;
    /// Pick an export destination.
    fn pick_export_path(&self, suggested_name: &str) -> Option<PathBuf>;
    /// Pick an import source.
    fn pick_import_path(&self) -> Option<PathBuf>;
    /// Read a UTF-8 text file.
    fn read_text(&self, path: &Path) -> Result<String, DialogError>;
    /// Write a UTF-8 text file.
    fn write_text(&self, path: &Path, contents: &str) -> Result<(), DialogError>;
}

/// The native `rfd` dialog backend.
#[derive(Debug, Clone, Copy, Default)]
pub struct NativeDialogService;

impl DialogService for NativeDialogService {
    fn pick_attachment(&self) -> Option<PathBuf> {
        rfd::FileDialog::new()
            .add_filter("Images and text", ATTACHMENT_EXTENSIONS)
            .pick_file()
    }

    fn pick_export_path(&self, suggested_name: &str) -> Option<PathBuf> {
        rfd::FileDialog::new()
            .set_file_name(suggested_name)
            .add_filter("ChatUI JSON export", &["json"])
            .add_filter("Markdown", &["md"])
            .save_file()
    }

    fn pick_import_path(&self) -> Option<PathBuf> {
        rfd::FileDialog::new()
            .add_filter("ChatUI export", &["json"])
            .pick_file()
    }

    fn read_text(&self, path: &Path) -> Result<String, DialogError> {
        std::fs::read_to_string(path).map_err(DialogError::from)
    }

    fn write_text(&self, path: &Path, contents: &str) -> Result<(), DialogError> {
        std::fs::write(path, contents).map_err(DialogError::from)
    }
}

/// An in-memory dialog backend for tests and headless runs.
#[derive(Debug, Default)]
pub struct MemoryDialogService {
    attachment: std::cell::RefCell<Option<PathBuf>>,
    export: std::cell::RefCell<Option<PathBuf>>,
    import: std::cell::RefCell<Option<PathBuf>>,
    files: std::cell::RefCell<HashMap<PathBuf, String>>,
}

impl MemoryDialogService {
    /// Create an empty backend.
    pub fn new() -> Self {
        Self::default()
    }

    /// Queue an attachment pick result.
    pub fn with_attachment(self, path: impl Into<PathBuf>) -> Self {
        *self.attachment.borrow_mut() = Some(path.into());
        self
    }

    /// Queue an export destination.
    pub fn with_export(self, path: impl Into<PathBuf>) -> Self {
        *self.export.borrow_mut() = Some(path.into());
        self
    }

    /// Queue an import source.
    pub fn with_import(self, path: impl Into<PathBuf>) -> Self {
        *self.import.borrow_mut() = Some(path.into());
        self
    }

    /// Seed a readable file.
    pub fn with_file(self, path: impl Into<PathBuf>, contents: impl Into<String>) -> Self {
        self.files.borrow_mut().insert(path.into(), contents.into());
        self
    }

    /// Read a file written through [`DialogService::write_text`].
    pub fn written(&self, path: &Path) -> Option<String> {
        self.files.borrow().get(path).cloned()
    }
}

impl DialogService for MemoryDialogService {
    fn pick_attachment(&self) -> Option<PathBuf> {
        self.attachment.borrow().clone()
    }

    fn pick_export_path(&self, _suggested_name: &str) -> Option<PathBuf> {
        self.export.borrow().clone()
    }

    fn pick_import_path(&self) -> Option<PathBuf> {
        self.import.borrow().clone()
    }

    fn read_text(&self, path: &Path) -> Result<String, DialogError> {
        self.files
            .borrow()
            .get(path)
            .cloned()
            .ok_or_else(|| DialogError::NotFound(path.to_path_buf()))
    }

    fn write_text(&self, path: &Path, contents: &str) -> Result<(), DialogError> {
        self.files
            .borrow_mut()
            .insert(path.to_path_buf(), contents.to_owned());
        Ok(())
    }
}

/// A dialog or file I/O error.
#[derive(Debug)]
pub enum DialogError {
    /// Filesystem error.
    Io(std::io::Error),
    /// The requested file does not exist in the backend.
    NotFound(PathBuf),
}

impl std::fmt::Display for DialogError {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::Io(error) => write!(formatter, "file dialog I/O error: {error}"),
            Self::NotFound(path) => write!(formatter, "file not found: {}", path.display()),
        }
    }
}

impl std::error::Error for DialogError {}

impl From<std::io::Error> for DialogError {
    fn from(error: std::io::Error) -> Self {
        Self::Io(error)
    }
}

/// Convert a dropped path to attachment metadata.
///
/// The MIME type is left empty so the shared validator infers it from the
/// extension. Directories and unreadable paths are ignored.
pub fn attachment_info(path: &Path) -> Option<AttachmentFileInfo> {
    let metadata = std::fs::metadata(path).ok()?;
    if !metadata.is_file() {
        return None;
    }
    let name = path.file_name()?.to_string_lossy().into_owned();
    Some(AttachmentFileInfo::new(name, "", metadata.len()))
}

/// Convert dropped paths to attachment metadata.
pub fn route_dropped_files(paths: &[PathBuf]) -> Vec<AttachmentFileInfo> {
    paths
        .iter()
        .filter_map(|path| attachment_info(path))
        .collect()
}

/// Validate dropped paths with the shared attachment rules.
pub fn validate_dropped_files(
    paths: &[PathBuf],
) -> Vec<Result<ValidatedAttachmentFile, AttachmentValidationError>> {
    route_dropped_files(paths)
        .iter()
        .map(validate_attachment_file)
        .collect()
}

/// Store dropped files and add them to the composer.
///
/// Returns a summary suitable for a status notice. The composer is only
/// mutated for files that pass validation and storage.
pub fn add_dropped_files_to_composer(
    composer: &mut ComposerState,
    store: &AttachmentStore,
    paths: &[PathBuf],
    id_prefix: &str,
) -> AttachmentDropSummary {
    let mut summary = AttachmentDropSummary::default();

    for (index, path) in paths.iter().enumerate() {
        let Some(info) = attachment_info(path) else {
            summary.rejected += 1;
            summary
                .errors
                .push(format!("{} is not a readable file.", path.display()));
            continue;
        };

        let validated = match validate_attachment_file(&info) {
            Ok(validated) => validated,
            Err(error) => {
                summary.rejected += 1;
                summary.errors.push(error.to_string());
                continue;
            }
        };

        let bytes = match std::fs::read(path) {
            Ok(bytes) => bytes,
            Err(error) => {
                summary.rejected += 1;
                summary
                    .errors
                    .push(format!("{} could not be read: {error}", path.display()));
                continue;
            }
        };

        let id = format!("{id_prefix}-{index}");
        match store_pending_attachment(store, id, validated, &bytes) {
            Ok(attachment) => {
                composer.add_attachment(attachment);
                summary.accepted += 1;
            }
            Err(error) => {
                summary.rejected += 1;
                summary.errors.push(error.to_string());
            }
        }
    }

    summary
}

/// The result of routing dropped files into the composer.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct AttachmentDropSummary {
    /// Files accepted and added.
    pub accepted: usize,
    /// Files rejected.
    pub rejected: usize,
    /// User-safe rejection messages.
    pub errors: Vec<String>,
}

impl AttachmentDropSummary {
    /// A short status line.
    pub fn notice(&self) -> String {
        if self.accepted == 0 && self.rejected == 0 {
            return "No files were dropped.".to_owned();
        }
        if self.rejected == 0 {
            return format!(
                "Added {} attachment{}.",
                self.accepted,
                if self.accepted == 1 { "" } else { "s" }
            );
        }
        if self.accepted == 0 {
            return format!(
                "No attachments added. {}",
                self.errors.first().cloned().unwrap_or_default()
            );
        }
        format!(
            "Added {} attachment{}; {} rejected.",
            self.accepted,
            if self.accepted == 1 { "" } else { "s" },
            self.rejected
        )
    }
}

/// Store a single validated file.
pub fn store_validated_file(
    store: &AttachmentStore,
    id: impl Into<String>,
    file: ValidatedAttachmentFile,
    bytes: &[u8],
) -> Result<PendingAttachment, AttachmentError> {
    store_pending_attachment(store, id, file, bytes)
}

#[cfg(test)]
mod tests {
    use super::*;
    use chatui_domain::settings::SendKey;

    #[test]
    fn memory_dialog_returns_queued_paths() {
        let service = MemoryDialogService::new()
            .with_attachment("/tmp/a.png")
            .with_export("/tmp/export.json")
            .with_import("/tmp/import.json");
        assert_eq!(service.pick_attachment(), Some(PathBuf::from("/tmp/a.png")));
        assert_eq!(
            service.pick_export_path("chatui-export.json"),
            Some(PathBuf::from("/tmp/export.json"))
        );
        assert_eq!(
            service.pick_import_path(),
            Some(PathBuf::from("/tmp/import.json"))
        );
    }

    #[test]
    fn memory_dialog_reads_and_writes_text() {
        let service = MemoryDialogService::new().with_file("/tmp/in.json", "{\"ok\":true}");
        assert_eq!(
            service.read_text(Path::new("/tmp/in.json")).expect("read"),
            "{\"ok\":true}"
        );
        service
            .write_text(Path::new("/tmp/out.json"), "{\"written\":true}")
            .expect("write");
        assert_eq!(
            service.written(Path::new("/tmp/out.json")).as_deref(),
            Some("{\"written\":true}")
        );
        assert!(matches!(
            service.read_text(Path::new("/tmp/missing.json")),
            Err(DialogError::NotFound(_))
        ));
    }

    #[test]
    fn routes_only_regular_files() {
        let directory = tempfile::tempdir().expect("tempdir");
        let file = directory.path().join("note.txt");
        std::fs::write(&file, "hello").expect("write");
        let directory_path = directory.path().to_path_buf();

        let files = route_dropped_files(&[file.clone(), directory_path]);
        assert_eq!(files.len(), 1);
        assert_eq!(files[0].name, "note.txt");
        assert_eq!(files[0].size, 5);
    }

    #[test]
    fn validates_supported_and_rejects_unsupported_files() {
        let directory = tempfile::tempdir().expect("tempdir");
        let text = directory.path().join("note.txt");
        let binary = directory.path().join("program.exe");
        std::fs::write(&text, "hello").expect("write text");
        std::fs::write(&binary, "MZ").expect("write binary");

        let results = validate_dropped_files(&[text, binary]);
        assert!(results[0].is_ok());
        assert!(results[1].is_err());
    }

    #[test]
    fn adds_dropped_files_to_the_composer() {
        let directory = tempfile::tempdir().expect("tempdir");
        let file = directory.path().join("note.txt");
        std::fs::write(&file, "hello").expect("write");
        let store = AttachmentStore::new(directory.path().join("data"));
        let mut composer = ComposerState::with_defaults("conversation", SendKey::Enter);

        let summary = add_dropped_files_to_composer(&mut composer, &store, &[file], "drop");

        assert_eq!(summary.accepted, 1);
        assert_eq!(summary.rejected, 0);
        assert_eq!(composer.attachments().len(), 1);
        assert_eq!(composer.attachments()[0].name, "note.txt");
    }

    #[test]
    fn drop_summary_is_user_safe() {
        let summary = AttachmentDropSummary {
            accepted: 2,
            rejected: 1,
            errors: vec!["bad.exe is not a supported image or text file.".to_owned()],
        };
        assert_eq!(summary.notice(), "Added 2 attachments; 1 rejected.");
        assert_eq!(
            AttachmentDropSummary::default().notice(),
            "No files were dropped."
        );
    }
}
