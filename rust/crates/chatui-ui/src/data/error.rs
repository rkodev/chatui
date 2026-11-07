//! Data-screen failures and user-facing copy.
//!
//! Data operations are local and non-secret. Error copy keeps raw storage and
//! import diagnostics in `details` and never echoes a secret value.

use std::fmt;

use chatui_storage::{ImportError, StorageError};

/// A failure raised by a data-screen operation.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum DataError {
    /// A storage operation failed.
    Storage(String),
    /// The submitted value is missing or invalid.
    InvalidInput(String),
    /// A referenced record does not exist.
    NotFound(String),
    /// An export could not be built.
    Export(String),
    /// An import archive was rejected.
    Import(String),
    /// A destructive action needs explicit confirmation.
    ConfirmationRequired(String),
}

impl DataError {
    /// Return the user-safe message.
    pub fn message(&self) -> &str {
        match self {
            Self::Storage(message)
            | Self::InvalidInput(message)
            | Self::NotFound(message)
            | Self::Export(message)
            | Self::Import(message)
            | Self::ConfirmationRequired(message) => message,
        }
    }
}

impl fmt::Display for DataError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter.write_str(self.message())
    }
}

impl std::error::Error for DataError {}

impl From<StorageError> for DataError {
    fn from(error: StorageError) -> Self {
        Self::Storage(error.to_string())
    }
}

impl From<ImportError> for DataError {
    fn from(error: ImportError) -> Self {
        Self::Import(error.to_string())
    }
}

/// Plain-language copy shown for a data-screen failure.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DataErrorCopy {
    /// Short heading.
    pub title: String,
    /// Actionable message.
    pub message: String,
    /// Optional non-secret diagnostic details.
    pub details: Option<String>,
}

impl DataErrorCopy {
    fn new(title: impl Into<String>, message: impl Into<String>, details: Option<String>) -> Self {
        Self {
            title: title.into(),
            message: message.into(),
            details,
        }
    }
}

/// Map any data-screen failure to plain-language, actionable copy.
pub fn describe_data_error(error: &DataError) -> DataErrorCopy {
    match error {
        DataError::Storage(message) => DataErrorCopy::new(
            "Could not read local data",
            "ChatUI could not read or change local data on this device. Try again.",
            Some(message.clone()),
        ),
        DataError::InvalidInput(message) => {
            DataErrorCopy::new("Check the data setting", message.clone(), None)
        }
        DataError::NotFound(message) => {
            DataErrorCopy::new("Data unavailable", message.clone(), None)
        }
        DataError::Export(message) => DataErrorCopy::new(
            "Could not export data",
            "ChatUI could not build the export. Nothing was written.",
            Some(message.clone()),
        ),
        DataError::Import(message) => DataErrorCopy::new(
            "Import rejected",
            "The archive was rejected and local data was not changed.",
            Some(message.clone()),
        ),
        DataError::ConfirmationRequired(message) => {
            DataErrorCopy::new("Confirmation required", message.clone(), None)
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn storage_copy_keeps_raw_details_out_of_the_message() {
        let copy = describe_data_error(&DataError::Storage("database is locked".to_owned()));

        assert_eq!(copy.title, "Could not read local data");
        assert!(!copy.message.contains("database"));
        assert_eq!(copy.details.as_deref(), Some("database is locked"));
    }

    #[test]
    fn import_copy_states_that_local_data_is_unchanged() {
        let copy = describe_data_error(&DataError::Import("invalid JSON".to_owned()));

        assert_eq!(copy.title, "Import rejected");
        assert!(copy.message.contains("not changed"));
        assert_eq!(copy.details.as_deref(), Some("invalid JSON"));
    }

    #[test]
    fn confirmation_copy_is_actionable() {
        let copy = describe_data_error(&DataError::ConfirmationRequired(
            "Type DELETE ALL DATA to confirm.".to_owned(),
        ));

        assert_eq!(copy.title, "Confirmation required");
        assert!(copy.message.contains("DELETE ALL DATA"));
        assert_eq!(copy.details, None);
    }
}
