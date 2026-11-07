//! Framework-independent data-screen view model.
//!
//! The view model owns storage usage, export options, import mode, clear-data
//! confirmation, stored-key summaries, and the last operation result. It never
//! holds a secret value: stored keys are represented by non-secret metadata
//! only.
//!
//! Export defaults to excluding attachment bytes. Import defaults to merge and
//! requires an explicit confirmation before replace. Clear-all requires the
//! exact typed phrase [`CLEAR_ALL_CONFIRMATION`].

use chatui_contracts::ChatExport;
use chatui_domain::{Conversation, Provider};
use chatui_storage::EXPORT_PLAINTEXT_WARNING;

use crate::data::error::{DataError, DataErrorCopy};
use crate::data::route::{DataNavigation, DataSection};

/// Exact phrase required before clearing all data.
pub const CLEAR_ALL_CONFIRMATION: &str = "DELETE ALL DATA";

/// Plaintext warning shown before any export.
pub const EXPORT_WARNING: &str = EXPORT_PLAINTEXT_WARNING;

/// What a full export contains.
pub const EXPORT_INCLUDES: &[&str] = &[
    "Conversations",
    "Messages",
    "Providers",
    "Models",
    "Settings",
];

/// What an export never contains, or contains only on request.
pub const EXPORT_EXCLUDES: &[&str] = &[
    "API keys and secrets",
    "Attachment file contents unless you opt in",
    "Backup credentials",
];

/// Usage note shown above the storage table.
pub const USAGE_NOTE: &str = "Sizes are estimated from stored records and attachment metadata. Attachment file contents are not read to calculate this.";

/// One usage row: record count plus an approximate byte size.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub struct StorageUsageEntry {
    /// Number of records.
    pub count: u64,
    /// Approximate size in bytes.
    pub bytes: u64,
}

/// Storage usage shown on the data screen.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub struct StorageUsage {
    /// Conversations.
    pub conversations: StorageUsageEntry,
    /// Messages.
    pub messages: StorageUsageEntry,
    /// Attachment metadata and deduplicated attachment bytes.
    pub attachments: StorageUsageEntry,
    /// Stored secret metadata.
    pub secrets: StorageUsageEntry,
    /// Providers.
    pub providers: StorageUsageEntry,
    /// Models.
    pub models: StorageUsageEntry,
    /// Sum of every row.
    pub total_bytes: u64,
}

impl StorageUsage {
    /// Build usage from the six rows and compute the total.
    pub fn new(
        conversations: StorageUsageEntry,
        messages: StorageUsageEntry,
        attachments: StorageUsageEntry,
        secrets: StorageUsageEntry,
        providers: StorageUsageEntry,
        models: StorageUsageEntry,
    ) -> Self {
        let total_bytes = conversations.bytes
            + messages.bytes
            + attachments.bytes
            + secrets.bytes
            + providers.bytes
            + models.bytes;

        Self {
            conversations,
            messages,
            attachments,
            secrets,
            providers,
            models,
            total_bytes,
        }
    }

    /// Rows in display order.
    pub fn rows(&self) -> [(&'static str, StorageUsageEntry); 6] {
        [
            ("Conversations", self.conversations),
            ("Messages", self.messages),
            ("Attachments", self.attachments),
            ("Stored keys", self.secrets),
            ("Providers", self.providers),
            ("Models", self.models),
        ]
    }
}

/// Non-secret summary of one provider key.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ProviderKeySummary {
    /// Provider ID.
    pub id: String,
    /// Provider display label.
    pub label: String,
    /// Provider preset ID.
    pub preset_id: String,
    /// Whether a key is stored for this provider.
    pub has_key: bool,
    /// Non-secret key label from provider metadata, when present.
    pub key_label: Option<String>,
}

impl ProviderKeySummary {
    /// Build a summary from a provider record.
    pub fn from_provider(provider: &Provider) -> Self {
        Self {
            id: provider.id.clone(),
            label: provider.label.clone(),
            preset_id: provider.preset_id.clone(),
            has_key: provider.secret_id.is_some(),
            key_label: key_label(provider),
        }
    }

    /// Detail line shown under the provider label.
    pub fn detail(&self) -> String {
        match &self.key_label {
            Some(label) if !label.is_empty() => format!("{} · {label}", self.preset_id),
            _ => self.preset_id.clone(),
        }
    }
}

fn key_label(provider: &Provider) -> Option<String> {
    let value = provider.metadata.as_ref()?.get("key")?;
    let label = value.get("label")?.as_str()?.trim();

    if label.is_empty() {
        None
    } else {
        Some(label.to_owned())
    }
}

/// Counts returned after clearing all data.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub struct ClearSummary {
    /// Conversations removed.
    pub conversations: usize,
    /// Messages removed.
    pub messages: usize,
    /// Attachments removed.
    pub attachments: usize,
    /// Stored keys removed.
    pub secrets: usize,
    /// Providers removed.
    pub providers: usize,
    /// Models removed.
    pub models: usize,
}

/// Counts returned after an import.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub struct ImportSummaryView {
    /// Applied mode.
    pub mode: ImportMode,
    /// Conversations written.
    pub conversations: usize,
    /// Messages written.
    pub messages: usize,
    /// Attachments written.
    pub attachments: usize,
    /// Providers written.
    pub providers: usize,
    /// Models written.
    pub models: usize,
    /// Attachments whose bytes were restored.
    pub attachment_bytes: usize,
    /// Attachments whose bytes were absent from the archive.
    pub skipped_attachment_bytes: usize,
}

/// Export format for a per-conversation export.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum ExportFormat {
    /// `chatui.export` JSON.
    #[default]
    Json,
    /// Human-readable Markdown.
    Markdown,
}

impl ExportFormat {
    /// Every format in picker order.
    pub const ALL: [Self; 2] = [Self::Json, Self::Markdown];

    /// Visible label.
    pub const fn label(self) -> &'static str {
        match self {
            Self::Json => "JSON",
            Self::Markdown => "Markdown",
        }
    }

    /// File extension.
    pub const fn extension(self) -> &'static str {
        match self {
            Self::Json => "json",
            Self::Markdown => "md",
        }
    }

    /// MIME type.
    pub const fn mime_type(self) -> &'static str {
        match self {
            Self::Json => "application/json",
            Self::Markdown => "text/markdown",
        }
    }

    /// Picker index.
    pub const fn index(self) -> usize {
        match self {
            Self::Json => 0,
            Self::Markdown => 1,
        }
    }

    /// Resolve a format from a picker index.
    pub const fn from_index(index: usize) -> Self {
        match index {
            1 => Self::Markdown,
            _ => Self::Json,
        }
    }
}

/// How an import applies an archive to local data.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum ImportMode {
    /// Keep local records and upsert archive records by ID. This is the default.
    #[default]
    Merge,
    /// Clear the imported data stores first, then write the archive.
    Replace,
}

impl ImportMode {
    /// Every mode in picker order.
    pub const ALL: [Self; 2] = [Self::Merge, Self::Replace];

    /// Visible label.
    pub const fn label(self) -> &'static str {
        match self {
            Self::Merge => "Merge (default)",
            Self::Replace => "Replace",
        }
    }

    /// Whether this mode deletes local data.
    pub const fn is_replace(self) -> bool {
        matches!(self, Self::Replace)
    }

    /// Picker index.
    pub const fn index(self) -> usize {
        match self {
            Self::Merge => 0,
            Self::Replace => 1,
        }
    }

    /// Resolve a mode from a picker index.
    pub const fn from_index(index: usize) -> Self {
        match index {
            1 => Self::Replace,
            _ => Self::Merge,
        }
    }
}

/// A request to build an export.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ExportRequest {
    /// Conversation ID for a per-conversation export, or `None` for all data.
    pub conversation_id: Option<String>,
    /// Output format.
    pub format: ExportFormat,
    /// Whether attachment bytes are included.
    pub include_attachment_bytes: bool,
}

impl ExportRequest {
    /// Full JSON export with attachment bytes excluded.
    pub fn all() -> Self {
        Self {
            conversation_id: None,
            format: ExportFormat::Json,
            include_attachment_bytes: false,
        }
    }

    /// Per-conversation export.
    pub fn conversation(conversation_id: impl Into<String>, format: ExportFormat) -> Self {
        Self {
            conversation_id: Some(conversation_id.into()),
            format,
            include_attachment_bytes: false,
        }
    }
}

/// A built export ready for the caller to save.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ExportArtifact {
    /// Suggested file name.
    pub filename: String,
    /// MIME type.
    pub mime_type: String,
    /// File contents.
    pub content: String,
    /// Non-secret summary of what was exported.
    pub summary: ExportSummary,
}

/// Non-secret summary of an export.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub struct ExportSummary {
    /// Conversations exported.
    pub conversations: usize,
    /// Messages exported.
    pub messages: usize,
    /// Attachments exported.
    pub attachments: usize,
    /// Providers exported.
    pub providers: usize,
    /// Models exported.
    pub models: usize,
    /// Whether settings were included.
    pub includes_settings: bool,
    /// Whether attachment bytes were included.
    pub includes_attachment_bytes: bool,
    /// Size of the encoded artifact in bytes.
    pub bytes: usize,
}

impl ExportSummary {
    /// Summarize a `chatui.export` document.
    pub fn from_export(export: &ChatExport, include_attachment_bytes: bool, bytes: usize) -> Self {
        Self {
            conversations: export.conversations.len(),
            messages: export.messages.len(),
            attachments: export.attachments.len(),
            providers: export.providers.len(),
            models: export.models.len(),
            includes_settings: true,
            includes_attachment_bytes: include_attachment_bytes,
            bytes,
        }
    }
}

/// A request to import an archive.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ImportRequest {
    /// Archive text.
    pub text: String,
    /// Applied mode.
    pub mode: ImportMode,
}

/// A request to clear all data.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ClearRequest {
    /// Typed confirmation phrase.
    pub confirmation: String,
}

/// A request to remove one stored key.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct RemoveKeyRequest {
    /// Provider whose key should be removed.
    pub provider_id: String,
}

/// A conversation option in the export picker.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ConversationOption {
    /// Conversation ID.
    pub id: String,
    /// Display title.
    pub title: String,
}

impl ConversationOption {
    /// Build an option from a conversation record.
    pub fn from_conversation(conversation: &Conversation) -> Self {
        let title = if conversation.title.trim().is_empty() {
            "Untitled conversation".to_owned()
        } else {
            conversation.title.clone()
        };

        Self {
            id: conversation.id.clone(),
            title,
        }
    }
}

/// A usage row rendered by the view.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct UsageRowView {
    /// Row label.
    pub label: String,
    /// Record count.
    pub count: String,
    /// Human-readable size.
    pub size: String,
}

/// A stored-key row rendered by the view.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct KeyRowView {
    /// Provider ID.
    pub id: String,
    /// Provider label.
    pub label: String,
    /// Preset and key label.
    pub detail: String,
    /// Whether a key is stored.
    pub has_key: bool,
}

/// A long-running data operation.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum DataOperation {
    /// Refresh storage usage.
    RefreshUsage,
    /// Export all data.
    ExportAll,
    /// Export one conversation.
    ExportConversation,
    /// Import an archive.
    Import,
    /// Clear all data.
    ClearAll,
    /// Remove one stored key.
    RemoveKey,
}

impl DataOperation {
    /// Progress label.
    pub const fn label(self) -> &'static str {
        match self {
            Self::RefreshUsage => "Refreshing usage…",
            Self::ExportAll => "Preparing export…",
            Self::ExportConversation => "Preparing conversation export…",
            Self::Import => "Importing…",
            Self::ClearAll => "Clearing data…",
            Self::RemoveKey => "Removing key…",
        }
    }
}

/// Backup placeholder copy and link.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DataBackupPlaceholder {
    /// Section title.
    pub title: &'static str,
    /// Primary message.
    pub message: &'static str,
    /// Future-behavior detail.
    pub detail: &'static str,
    /// Whether backup is available in this release.
    pub available: bool,
    /// Link label.
    pub link_label: &'static str,
    /// Link target.
    pub link_target: &'static str,
}

/// What an export includes, excludes, and the plaintext risk.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ExportDocumentation {
    /// Included entity kinds.
    pub includes: Vec<&'static str>,
    /// Excluded or opt-in data.
    pub excludes: Vec<&'static str>,
    /// Plaintext warning.
    pub plaintext_warning: &'static str,
}

/// Data-screen view model.
#[derive(Debug, Clone, PartialEq)]
pub struct DataViewModel {
    /// Current section.
    pub section: DataNavigation,
    /// Latest storage usage.
    pub usage: Option<StorageUsage>,
    /// Conversations available for per-conversation export.
    pub conversations: Vec<ConversationOption>,
    /// Selected conversation ID.
    pub selected_conversation_id: Option<String>,
    /// Whether attachment bytes are included in JSON exports.
    pub include_attachment_bytes: bool,
    /// Per-conversation export format.
    pub export_format: ExportFormat,
    /// Import mode.
    pub import_mode: ImportMode,
    /// Whether the user confirmed replace mode.
    pub replace_confirmed: bool,
    /// Archive text to import.
    pub import_text: String,
    /// Typed clear-all confirmation.
    pub clear_confirmation: String,
    /// Stored-key summaries.
    pub provider_keys: Vec<ProviderKeySummary>,
    /// Provider ID awaiting a second removal click.
    pub pending_key_removal: Option<String>,
    /// Current long-running operation.
    pub busy: Option<DataOperation>,
    /// Current error copy.
    pub error: Option<DataErrorCopy>,
    /// Current status line.
    pub status: Option<String>,
    /// Last export summary.
    pub last_export: Option<ExportSummary>,
    /// Last import summary.
    pub last_import: Option<ImportSummaryView>,
    /// Last clear summary.
    pub last_clear: Option<ClearSummary>,
}

impl DataViewModel {
    /// Build an empty view model.
    pub fn new() -> Self {
        Self {
            section: DataNavigation::new(),
            usage: None,
            conversations: Vec::new(),
            selected_conversation_id: None,
            include_attachment_bytes: false,
            export_format: ExportFormat::Json,
            import_mode: ImportMode::Merge,
            replace_confirmed: false,
            import_text: String::new(),
            clear_confirmation: String::new(),
            provider_keys: Vec::new(),
            pending_key_removal: None,
            busy: None,
            error: None,
            status: None,
            last_export: None,
            last_import: None,
            last_clear: None,
        }
    }

    /// Replace the usage snapshot.
    pub fn set_usage(&mut self, usage: StorageUsage) {
        self.usage = Some(usage);
    }

    /// Replace the conversation options, preserving the selection when possible.
    pub fn set_conversations(&mut self, conversations: Vec<Conversation>) {
        self.conversations = conversations
            .iter()
            .map(ConversationOption::from_conversation)
            .collect();

        let selection_is_valid = self
            .selected_conversation_id
            .as_deref()
            .is_some_and(|id| self.conversations.iter().any(|option| option.id == id));

        if !selection_is_valid {
            self.selected_conversation_id =
                self.conversations.first().map(|option| option.id.clone());
        }
    }

    /// Replace the stored-key summaries.
    pub fn set_provider_keys(&mut self, keys: Vec<ProviderKeySummary>) {
        self.provider_keys = keys;
        self.pending_key_removal = None;
    }

    /// Select a conversation for export.
    pub fn select_conversation(&mut self, id: impl Into<String>) {
        let id = id.into();
        if self.conversations.iter().any(|option| option.id == id) {
            self.selected_conversation_id = Some(id);
        }
    }

    /// Set the attachment-byte opt-in.
    pub fn set_include_attachment_bytes(&mut self, include: bool) {
        self.include_attachment_bytes = include;
    }

    /// Set the per-conversation export format.
    pub fn set_export_format(&mut self, format: ExportFormat) {
        self.export_format = format;
    }

    /// Set the import mode.
    pub fn set_import_mode(&mut self, mode: ImportMode) {
        self.import_mode = mode;
        if !mode.is_replace() {
            self.replace_confirmed = false;
        }
    }

    /// Set the replace confirmation.
    pub fn set_replace_confirmed(&mut self, confirmed: bool) {
        self.replace_confirmed = confirmed;
    }

    /// Set the archive text.
    pub fn set_import_text(&mut self, text: impl Into<String>) {
        self.import_text = text.into();
    }

    /// Set the typed clear-all confirmation.
    pub fn set_clear_confirmation(&mut self, phrase: impl Into<String>) {
        self.clear_confirmation = phrase.into();
    }

    /// Whether the typed clear-all phrase matches exactly.
    pub fn clear_confirmed(&self) -> bool {
        self.clear_confirmation == CLEAR_ALL_CONFIRMATION
    }

    /// Whether an import can run.
    pub fn can_import(&self) -> bool {
        !self.import_text.trim().is_empty()
            && (!self.import_mode.is_replace() || self.replace_confirmed)
    }

    /// Whether clear-all can run.
    pub fn can_clear(&self) -> bool {
        self.clear_confirmed()
    }

    /// Whether a per-conversation export can run.
    pub fn can_export_conversation(&self) -> bool {
        self.selected_conversation_id.is_some()
    }

    /// Whether any stored key can be removed.
    pub fn can_remove_key(&self) -> bool {
        self.provider_keys.iter().any(|key| key.has_key)
    }

    /// Build a full-export request.
    pub fn export_all_request(&self) -> ExportRequest {
        ExportRequest {
            conversation_id: None,
            format: ExportFormat::Json,
            include_attachment_bytes: self.include_attachment_bytes,
        }
    }

    /// Build a per-conversation export request.
    pub fn export_conversation_request(&self) -> Option<ExportRequest> {
        let conversation_id = self.selected_conversation_id.clone()?;
        let format = self.export_format;

        Some(ExportRequest {
            conversation_id: Some(conversation_id),
            format,
            include_attachment_bytes: self.include_attachment_bytes && format == ExportFormat::Json,
        })
    }

    /// Build an import request, enforcing the replace confirmation.
    pub fn import_request(&self) -> Result<ImportRequest, DataError> {
        if self.import_text.trim().is_empty() {
            return Err(DataError::InvalidInput(
                "Paste a chatui.export archive before importing.".to_owned(),
            ));
        }

        if self.import_mode.is_replace() && !self.replace_confirmed {
            return Err(DataError::ConfirmationRequired(
                "Confirm that replace deletes local data before importing.".to_owned(),
            ));
        }

        Ok(ImportRequest {
            text: self.import_text.clone(),
            mode: self.import_mode,
        })
    }

    /// Build a clear-all request, enforcing the typed confirmation.
    pub fn clear_request(&self) -> Result<ClearRequest, DataError> {
        if !self.clear_confirmed() {
            return Err(DataError::ConfirmationRequired(format!(
                "Type {CLEAR_ALL_CONFIRMATION} to confirm."
            )));
        }

        Ok(ClearRequest {
            confirmation: self.clear_confirmation.clone(),
        })
    }

    /// Build a per-key removal request.
    pub fn remove_key_request(&self, provider_id: &str) -> Result<RemoveKeyRequest, DataError> {
        let key = self
            .provider_keys
            .iter()
            .find(|key| key.id == provider_id)
            .ok_or_else(|| DataError::NotFound(format!("Provider {provider_id} was not found.")))?;

        if !key.has_key {
            return Err(DataError::InvalidInput(
                "This provider has no stored key to remove.".to_owned(),
            ));
        }

        Ok(RemoveKeyRequest {
            provider_id: provider_id.to_owned(),
        })
    }

    /// Record a successful export.
    pub fn apply_export(&mut self, artifact: &ExportArtifact) {
        self.last_export = Some(artifact.summary);
        self.status = Some(format!(
            "Exported {} ({}).",
            artifact.filename,
            format_bytes(artifact.summary.bytes as u64)
        ));
        self.error = None;
        self.busy = None;
    }

    /// Record a successful import.
    pub fn apply_import(&mut self, summary: ImportSummaryView) {
        self.last_import = Some(summary);
        self.status = Some(format!(
            "Imported {} conversation(s), {} message(s), and {} attachment(s) in {} mode.",
            summary.conversations,
            summary.messages,
            summary.attachments,
            summary.mode.label()
        ));
        self.import_text.clear();
        self.replace_confirmed = false;
        self.error = None;
        self.busy = None;
    }

    /// Record a successful clear-all.
    pub fn apply_clear(&mut self, summary: ClearSummary) {
        self.last_clear = Some(summary);
        self.status = Some(format!(
            "Removed {} conversation(s), {} message(s), {} attachment(s), and {} stored key(s).",
            summary.conversations, summary.messages, summary.attachments, summary.secrets
        ));
        self.clear_confirmation.clear();
        self.error = None;
        self.busy = None;
    }

    /// Record a successful per-key removal.
    pub fn apply_remove_key(&mut self, provider_id: &str) {
        if let Some(key) = self
            .provider_keys
            .iter_mut()
            .find(|key| key.id == provider_id)
        {
            key.has_key = false;
            key.key_label = None;
        }

        self.pending_key_removal = None;
        self.status =
            Some("Key removed. Add a new key before sending with this provider.".to_owned());
        self.error = None;
        self.busy = None;
    }

    /// Record a failure.
    pub fn set_error(&mut self, error: &DataError) {
        self.error = Some(crate::data::error::describe_data_error(error));
        self.busy = None;
    }

    /// Set the current operation.
    pub fn set_busy(&mut self, operation: DataOperation) {
        self.busy = Some(operation);
        self.error = None;
        self.status = None;
    }

    /// Clear the current operation.
    pub fn clear_busy(&mut self) {
        self.busy = None;
    }

    /// Usage rows for the view.
    pub fn usage_rows(&self) -> Vec<UsageRowView> {
        let Some(usage) = &self.usage else {
            return Vec::new();
        };

        usage
            .rows()
            .into_iter()
            .map(|(label, entry)| UsageRowView {
                label: label.to_owned(),
                count: entry.count.to_string(),
                size: format_bytes(entry.bytes),
            })
            .collect()
    }

    /// Total usage as a human-readable string.
    pub fn usage_total(&self) -> String {
        self.usage
            .map(|usage| format_bytes(usage.total_bytes))
            .unwrap_or_else(|| "0 B".to_owned())
    }

    /// Conversation titles for the export picker.
    pub fn conversation_display(&self) -> Vec<String> {
        self.conversations
            .iter()
            .map(|option| option.title.clone())
            .collect()
    }

    /// Selected conversation index.
    pub fn selected_conversation_index(&self) -> usize {
        self.selected_conversation_id
            .as_deref()
            .and_then(|id| self.conversations.iter().position(|option| option.id == id))
            .unwrap_or(0)
    }

    /// Stored-key rows for the view.
    pub fn key_rows(&self) -> Vec<KeyRowView> {
        self.provider_keys
            .iter()
            .map(|key| KeyRowView {
                id: key.id.clone(),
                label: key.label.clone(),
                detail: key.detail(),
                has_key: key.has_key,
            })
            .collect()
    }

    /// Backup placeholder copy.
    pub const fn backup_placeholder(&self) -> DataBackupPlaceholder {
        DataBackupPlaceholder {
            title: "Backup",
            message: "ChatUI does not back up or sync data in this release. Everything stays on this device until you export it.",
            detail: "When backup arrives it will encrypt data before upload, keep provider keys out of backup metadata, and use a user-owned destination. No backup credentials are stored today.",
            available: false,
            link_label: "Open backup settings",
            link_target: "/settings/backup",
        }
    }

    /// Export documentation shown on the export section.
    pub fn export_documentation(&self) -> ExportDocumentation {
        ExportDocumentation {
            includes: EXPORT_INCLUDES.to_vec(),
            excludes: EXPORT_EXCLUDES.to_vec(),
            plaintext_warning: EXPORT_WARNING,
        }
    }

    /// Last export summary line.
    pub fn last_export_text(&self) -> String {
        match self.last_export {
            Some(summary) => format!(
                "Last export: {} conversation(s), {} message(s), {} attachment(s), {} provider(s), {} model(s), {}.",
                summary.conversations,
                summary.messages,
                summary.attachments,
                summary.providers,
                summary.models,
                format_bytes(summary.bytes as u64)
            ),
            None => String::new(),
        }
    }

    /// Last import summary line.
    pub fn last_import_text(&self) -> String {
        match self.last_import {
            Some(summary) => format!(
                "Last import ({}): {} conversation(s), {} message(s), {} attachment(s), {} provider(s), {} model(s).",
                summary.mode.label(),
                summary.conversations,
                summary.messages,
                summary.attachments,
                summary.providers,
                summary.models
            ),
            None => String::new(),
        }
    }

    /// Last clear summary line.
    pub fn last_clear_text(&self) -> String {
        match self.last_clear {
            Some(summary) => format!(
                "Last clear: {} conversation(s), {} message(s), {} attachment(s), {} stored key(s).",
                summary.conversations, summary.messages, summary.attachments, summary.secrets
            ),
            None => String::new(),
        }
    }

    /// Current section.
    pub fn section(&self) -> DataSection {
        self.section.section()
    }
}

impl Default for DataViewModel {
    fn default() -> Self {
        Self::new()
    }
}

/// Human-readable byte size for storage guidance.
pub fn format_bytes(bytes: u64) -> String {
    if bytes == 0 {
        return "0 B".to_owned();
    }

    const UNITS: [&str; 5] = ["B", "KB", "MB", "GB", "TB"];
    let mut value = bytes as f64;
    let mut unit = 0;

    while value >= 1024.0 && unit < UNITS.len() - 1 {
        value /= 1024.0;
        unit += 1;
    }

    let digits = if unit == 0 || value >= 100.0 { 0 } else { 1 };
    format!("{value:.digits$} {}", UNITS[unit])
}

#[cfg(test)]
mod tests {
    use super::*;
    use chatui_domain::{
        BrowserCors, ChatParams, Model, ModelCapability, ModelSource, ProviderAuthType,
        ProviderKind, Timestamp,
    };

    fn provider(id: &str, label: &str) -> Provider {
        let now = Timestamp::now();
        Provider {
            id: id.to_owned(),
            preset_id: "custom".to_owned(),
            label: label.to_owned(),
            kind: ProviderKind::OpenaiCompatible,
            base_url: "https://example.com/v1".to_owned(),
            auth_type: ProviderAuthType::Bearer,
            secret_id: None,
            headers: std::collections::BTreeMap::new(),
            enabled: true,
            browser_cors: BrowserCors::Unknown,
            metadata: None,
            created_at: now,
            updated_at: now,
        }
    }

    fn conversation(id: &str, title: &str) -> Conversation {
        let now = Timestamp::now();
        Conversation {
            id: id.to_owned(),
            title: title.to_owned(),
            system_prompt: String::new(),
            provider_id: "p1".to_owned(),
            model_id: "m1".to_owned(),
            model_label: "Model One".to_owned(),
            params: ChatParams::default(),
            pinned: false,
            archived: false,
            temporary: false,
            created_at: now,
            updated_at: now,
            last_message_at: now,
            message_count: 0,
            last_error: None,
            sync: None,
        }
    }

    fn model(id: &str) -> Model {
        let now = Timestamp::now();
        Model {
            id: id.to_owned(),
            provider_id: "p1".to_owned(),
            remote_id: "remote-1".to_owned(),
            label: "Model One".to_owned(),
            context_window: Some(128_000),
            max_output_tokens: Some(4_096),
            capabilities: vec![ModelCapability::Text],
            pricing: None,
            canonical_slug: None,
            input_modalities: Vec::new(),
            output_modalities: Vec::new(),
            supported_parameters: Vec::new(),
            metadata: None,
            hidden: false,
            pinned: false,
            source: ModelSource::Manual,
            created_at: now,
            updated_at: now,
        }
    }

    #[test]
    fn usage_rows_and_total_are_displayed() {
        let usage = StorageUsage::new(
            StorageUsageEntry {
                count: 2,
                bytes: 100,
            },
            StorageUsageEntry {
                count: 4,
                bytes: 200,
            },
            StorageUsageEntry {
                count: 1,
                bytes: 300,
            },
            StorageUsageEntry {
                count: 1,
                bytes: 50,
            },
            StorageUsageEntry {
                count: 1,
                bytes: 25,
            },
            StorageUsageEntry {
                count: 3,
                bytes: 75,
            },
        );
        let mut view_model = DataViewModel::new();
        view_model.set_usage(usage);

        let rows = view_model.usage_rows();
        assert_eq!(rows.len(), 6);
        assert_eq!(rows[0].label, "Conversations");
        assert_eq!(rows[0].count, "2");
        assert_eq!(rows[3].label, "Stored keys");
        assert_eq!(view_model.usage_total(), format_bytes(750));
    }

    #[test]
    fn export_defaults_exclude_attachment_bytes() {
        let view_model = DataViewModel::new();
        let request = view_model.export_all_request();

        assert!(!request.include_attachment_bytes);
        assert_eq!(request.format, ExportFormat::Json);
        assert_eq!(request.conversation_id, None);
    }

    #[test]
    fn markdown_conversation_export_never_includes_attachment_bytes() {
        let mut view_model = DataViewModel::new();
        view_model.set_conversations(vec![conversation("c1", "First")]);
        view_model.set_include_attachment_bytes(true);
        view_model.set_export_format(ExportFormat::Markdown);

        let request = view_model
            .export_conversation_request()
            .expect("conversation selected");
        assert_eq!(request.format, ExportFormat::Markdown);
        assert!(!request.include_attachment_bytes);
    }

    #[test]
    fn import_defaults_to_merge_and_replace_needs_confirmation() {
        let mut view_model = DataViewModel::new();
        view_model.set_import_text("{}");

        assert_eq!(view_model.import_mode, ImportMode::Merge);
        assert!(view_model.import_request().is_ok());

        view_model.set_import_mode(ImportMode::Replace);
        assert!(matches!(
            view_model.import_request(),
            Err(DataError::ConfirmationRequired(_))
        ));

        view_model.set_replace_confirmed(true);
        assert!(view_model.import_request().is_ok());
    }

    #[test]
    fn clear_requires_the_exact_typed_phrase() {
        let mut view_model = DataViewModel::new();
        assert!(matches!(
            view_model.clear_request(),
            Err(DataError::ConfirmationRequired(_))
        ));

        view_model.set_clear_confirmation("delete all data");
        assert!(!view_model.clear_confirmed());
        assert!(view_model.clear_request().is_err());

        view_model.set_clear_confirmation(CLEAR_ALL_CONFIRMATION);
        assert!(view_model.clear_confirmed());
        assert!(view_model.clear_request().is_ok());
    }

    #[test]
    fn per_key_removal_requires_a_stored_key() {
        let mut provider = provider("p1", "One");
        provider.secret_id = Some("provider-p1".to_owned());
        let mut view_model = DataViewModel::new();
        view_model.set_provider_keys(vec![ProviderKeySummary::from_provider(&provider)]);

        assert!(view_model.remove_key_request("p1").is_ok());
        assert!(matches!(
            view_model.remove_key_request("missing"),
            Err(DataError::NotFound(_))
        ));

        view_model.apply_remove_key("p1");
        assert!(!view_model.provider_keys[0].has_key);
        assert!(matches!(
            view_model.remove_key_request("p1"),
            Err(DataError::InvalidInput(_))
        ));
    }

    #[test]
    fn conversation_selection_survives_refresh() {
        let mut view_model = DataViewModel::new();
        view_model.set_conversations(vec![
            conversation("c1", "First"),
            conversation("c2", "Second"),
        ]);
        view_model.select_conversation("c2");
        assert_eq!(view_model.selected_conversation_index(), 1);

        view_model.set_conversations(vec![
            conversation("c2", "Second"),
            conversation("c3", "Third"),
        ]);
        assert_eq!(view_model.selected_conversation_id.as_deref(), Some("c2"));
        assert_eq!(view_model.selected_conversation_index(), 0);
    }

    #[test]
    fn backup_placeholder_and_export_documentation_are_explicit() {
        let view_model = DataViewModel::new();
        let backup = view_model.backup_placeholder();
        assert!(!backup.available);
        assert_eq!(backup.link_target, "/settings/backup");
        assert!(backup.message.contains("does not back up"));

        let docs = view_model.export_documentation();
        assert!(docs.includes.contains(&"Conversations"));
        assert!(docs.includes.contains(&"Settings"));
        assert!(docs.excludes.iter().any(|line| line.contains("secrets")));
        assert!(docs.plaintext_warning.contains("plaintext"));
    }

    #[test]
    fn format_bytes_uses_binary_units() {
        assert_eq!(format_bytes(0), "0 B");
        assert_eq!(format_bytes(512), "512 B");
        assert_eq!(format_bytes(1024), "1.0 KB");
        assert_eq!(format_bytes(1024 * 1024), "1.0 MB");
    }

    #[test]
    fn provider_key_summary_reads_non_secret_metadata() {
        let mut provider = provider("p1", "One");
        provider.secret_id = Some("provider-p1".to_owned());
        provider.metadata = Some(serde_json::json!({
            "key": { "label": "Personal key" }
        }));

        let summary = ProviderKeySummary::from_provider(&provider);
        assert!(summary.has_key);
        assert_eq!(summary.key_label.as_deref(), Some("Personal key"));
        assert_eq!(summary.detail(), "custom · Personal key");
    }

    #[test]
    fn model_helper_is_available_for_fixtures() {
        let model = model("m1");
        assert_eq!(model.id, "m1");
    }
}
