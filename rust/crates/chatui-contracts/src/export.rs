//! Chat export types matching `contracts/schema/chat-export.schema.json`.
//!
//! Export fields are strict: [`ChatExport`] rejects unknown top-level fields and
//! [`ChatExport::validate`] applies the full schema, which rejects unknown
//! nested fields. Catalog types are tolerant by contrast; see the crate-level
//! contract evolution policy.

use chatui_domain::{Conversation, Message, Model, Provider, Settings, Timestamp};
use serde::{Deserialize, Serialize};

use crate::schema::{SchemaError, SchemaName, schemas};

/// The only supported export format identifier.
pub const EXPORT_FORMAT: &str = "chatui.export";

/// The current export format version.
pub const EXPORT_FORMAT_VERSION: u32 = 1;

/// Settings embedded in an export.
///
/// The desktop client reuses the shared domain settings shape so exports,
/// imports, and fixtures cannot drift into a second serialization shape.
pub type ExportSettings = Settings;

/// Attachment metadata embedded in an export.
///
/// Binary data is optional. When present it is base64-encoded in `dataBase64`.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ExportAttachment {
    /// Local attachment ID.
    pub id: String,
    /// Original file name.
    pub name: String,
    /// MIME type.
    pub mime_type: String,
    /// SHA-256 content hash.
    pub sha256: String,
    /// Size in bytes.
    pub size: u64,
    /// Base64-encoded content, or `None` when the export omits bytes.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub data_base64: Option<String>,
}

/// A complete ChatUI export document.
///
/// Unknown top-level fields are rejected. Use [`ChatExport::validate`] before
/// importing to apply the full schema, including nested strictness.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ChatExport {
    /// Format identifier. Always [`EXPORT_FORMAT`] for documents this crate
    /// creates.
    pub format: String,
    /// Integer format version.
    pub format_version: u32,
    /// Export timestamp.
    pub exported_at: Timestamp,
    /// Semantic application version string.
    pub app_version: String,
    /// Exported conversations.
    pub conversations: Vec<Conversation>,
    /// Exported messages.
    pub messages: Vec<Message>,
    /// Exported attachment metadata.
    pub attachments: Vec<ExportAttachment>,
    /// Exported providers.
    pub providers: Vec<Provider>,
    /// Exported models.
    pub models: Vec<Model>,
    /// Exported settings.
    pub settings: ExportSettings,
}

impl ChatExport {
    /// Build an export with the current format identifier and version.
    #[allow(clippy::too_many_arguments)]
    pub fn new(
        exported_at: Timestamp,
        app_version: impl Into<String>,
        conversations: Vec<Conversation>,
        messages: Vec<Message>,
        attachments: Vec<ExportAttachment>,
        providers: Vec<Provider>,
        models: Vec<Model>,
        settings: ExportSettings,
    ) -> Self {
        Self {
            format: EXPORT_FORMAT.to_owned(),
            format_version: EXPORT_FORMAT_VERSION,
            exported_at,
            app_version: app_version.into(),
            conversations,
            messages,
            attachments,
            providers,
            models,
            settings,
        }
    }

    /// Validate this export against the full export schema.
    pub fn validate(&self) -> Result<(), SchemaError> {
        let value = serde_json::to_value(self)
            .map_err(|error| SchemaError::Serialize(error.to_string()))?;
        schemas().validate(SchemaName::ChatExport, &value)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::{Value, json};

    fn fixture() -> Value {
        serde_json::from_str(include_str!(
            "../../../../contracts/fixtures/expected/chatui-export.json"
        ))
        .expect("export fixture is valid JSON")
    }

    #[test]
    fn fixture_deserializes_and_round_trips_exactly() {
        let value = fixture();
        let export: ChatExport =
            serde_json::from_value(value.clone()).expect("fixture deserializes");

        assert_eq!(export.format, EXPORT_FORMAT);
        assert_eq!(export.format_version, EXPORT_FORMAT_VERSION);
        assert_eq!(export.app_version, "0.1.0");
        assert_eq!(export.conversations.len(), 1);
        assert_eq!(export.messages.len(), 2);
        assert_eq!(export.attachments.len(), 2);

        let round_tripped = serde_json::to_value(&export).expect("export serializes");
        assert_eq!(round_tripped, value);
    }

    #[test]
    fn export_validates_against_the_full_schema() {
        let export: ChatExport = serde_json::from_value(fixture()).expect("fixture deserializes");
        export.validate().expect("export satisfies the schema");
    }

    #[test]
    fn unknown_top_level_export_fields_are_rejected() {
        let mut value = fixture();
        value["futureField"] = json!(true);

        let error =
            serde_json::from_value::<ChatExport>(value).expect_err("unknown field rejected");
        assert!(error.to_string().contains("unknown field"));
    }

    #[test]
    fn unknown_nested_export_fields_are_rejected_by_schema() {
        let mut value = fixture();
        value["conversations"][0]["futureField"] = json!(true);

        assert!(schemas().validate(SchemaName::ChatExport, &value).is_err());
    }

    #[test]
    fn export_attachment_data_base64_is_optional() {
        let attachment: ExportAttachment = serde_json::from_value(json!({
            "id": "attachment-1",
            "name": "notes.txt",
            "mimeType": "text/plain",
            "sha256": "abcdef",
            "size": 17
        }))
        .expect("attachment without dataBase64 deserializes");

        assert_eq!(attachment.data_base64, None);
        let value = serde_json::to_value(&attachment).expect("attachment serializes");
        assert!(value.get("dataBase64").is_none());
    }

    #[test]
    fn unknown_model_catalog_fields_are_ignored() {
        let mut value = fixture()["models"][0].clone();
        value["futureCatalogField"] = json!({ "nested": [true, null] });

        let model: Model =
            serde_json::from_value(value).expect("model catalog fields are tolerant");
        assert_eq!(model.remote_id, "gpt-4o-mini");
    }

    #[test]
    fn new_sets_the_format_and_version() {
        let export = ChatExport::new(
            Timestamp::parse("2026-11-04T12:00:00.000Z").expect("timestamp"),
            "0.1.0",
            Vec::new(),
            Vec::new(),
            Vec::new(),
            Vec::new(),
            Vec::new(),
            Settings {
                theme: chatui_domain::Theme::System,
                density: chatui_domain::Density::Comfortable,
                chat_font_size: chatui_domain::ChatFontSize::Md,
                send_key: chatui_domain::SendKey::Enter,
                default_provider_id: None,
                default_model_id: None,
                default_system_prompt: String::new(),
                default_params: chatui_domain::ChatParams::default(),
                remember_keys_on_web: false,
                show_token_usage: true,
                load_remote_images: false,
                last_active_conversation_id: None,
                backup: None,
                onboarding_completed: false,
            },
        );

        assert_eq!(export.format, EXPORT_FORMAT);
        assert_eq!(export.format_version, EXPORT_FORMAT_VERSION);
        export.validate().expect("empty export is valid");
    }
}
