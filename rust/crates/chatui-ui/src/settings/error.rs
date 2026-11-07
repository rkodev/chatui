//! Settings failures and the user-facing copy for them.
//!
//! Settings changes are local and non-secret. The copy helper keeps raw storage
//! diagnostics in `details` and never echoes a provider key because settings
//! never contain one.

use std::fmt;

use chatui_storage::StorageError;

/// A failure raised while loading or saving settings.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum SettingsError {
    /// A settings row could not be read or written.
    Storage(String),
    /// The submitted setting is missing or invalid.
    InvalidInput(String),
    /// A referenced provider or model does not exist.
    NotFound(String),
}

impl SettingsError {
    /// Return the user-safe message.
    pub fn message(&self) -> &str {
        match self {
            Self::Storage(message) | Self::InvalidInput(message) | Self::NotFound(message) => {
                message
            }
        }
    }
}

impl fmt::Display for SettingsError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter.write_str(self.message())
    }
}

impl std::error::Error for SettingsError {}

impl From<StorageError> for SettingsError {
    fn from(error: StorageError) -> Self {
        Self::Storage(error.to_string())
    }
}

/// Plain-language copy shown for a settings failure.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SettingsErrorCopy {
    /// Short heading.
    pub title: String,
    /// Actionable message.
    pub message: String,
    /// Optional non-secret diagnostic details.
    pub details: Option<String>,
}

impl SettingsErrorCopy {
    fn new(title: impl Into<String>, message: impl Into<String>, details: Option<String>) -> Self {
        Self {
            title: title.into(),
            message: message.into(),
            details,
        }
    }
}

/// Map any settings failure to plain-language, actionable copy.
pub fn describe_settings_error(error: &SettingsError) -> SettingsErrorCopy {
    match error {
        SettingsError::Storage(message) => SettingsErrorCopy::new(
            "Could not save settings",
            "ChatUI could not save the settings change on this device. Try again.",
            Some(message.clone()),
        ),
        SettingsError::InvalidInput(message) => {
            SettingsErrorCopy::new("Check the setting", message.clone(), None)
        }
        SettingsError::NotFound(message) => {
            SettingsErrorCopy::new("Setting unavailable", message.clone(), None)
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn storage_copy_keeps_raw_details_out_of_the_message() {
        let copy =
            describe_settings_error(&SettingsError::Storage("database is locked".to_owned()));

        assert_eq!(copy.title, "Could not save settings");
        assert!(!copy.message.contains("database"));
        assert_eq!(copy.details.as_deref(), Some("database is locked"));
    }

    #[test]
    fn invalid_input_copy_is_actionable() {
        let copy = describe_settings_error(&SettingsError::InvalidInput(
            "Enter a number or leave the field blank.".to_owned(),
        ));

        assert_eq!(copy.title, "Check the setting");
        assert!(copy.message.contains("Enter a number"));
        assert_eq!(copy.details, None);
    }

    #[test]
    fn not_found_copy_names_the_missing_setting() {
        let copy = describe_settings_error(&SettingsError::NotFound(
            "Provider p1 was not found.".to_owned(),
        ));

        assert_eq!(copy.title, "Setting unavailable");
        assert!(copy.message.contains("p1"));
    }
}
