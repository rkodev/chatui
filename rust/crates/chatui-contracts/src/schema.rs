//! JSON Schema loading and validation for the shared contracts.
//!
//! Every schema is embedded with `include_str!` and compiled once per
//! [`SchemaSet`]. The crate-level contract evolution policy explains when a
//! schema change needs a `contractVersion` bump.

use std::collections::BTreeMap;
use std::fmt;
use std::sync::OnceLock;

use serde_json::Value;

/// The five shared contract schemas.
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Hash)]
pub enum SchemaName {
    /// `contracts/schema/backup-manifest.schema.json`.
    BackupManifest,
    /// `contracts/schema/chat-export.schema.json`.
    ChatExport,
    /// `contracts/schema/model-info.schema.json`.
    ModelInfo,
    /// `contracts/schema/provider-preset.schema.json`.
    ProviderPreset,
    /// `contracts/schema/settings.schema.json`.
    Settings,
}

impl SchemaName {
    /// Every schema in a stable order.
    pub const ALL: [Self; 5] = [
        Self::BackupManifest,
        Self::ChatExport,
        Self::ModelInfo,
        Self::ProviderPreset,
        Self::Settings,
    ];

    /// Return the schema file name.
    pub const fn file_name(self) -> &'static str {
        match self {
            Self::BackupManifest => "backup-manifest.schema.json",
            Self::ChatExport => "chat-export.schema.json",
            Self::ModelInfo => "model-info.schema.json",
            Self::ProviderPreset => "provider-preset.schema.json",
            Self::Settings => "settings.schema.json",
        }
    }

    /// Return the embedded schema source.
    pub const fn source(self) -> &'static str {
        match self {
            Self::BackupManifest => {
                include_str!("../../../../contracts/schema/backup-manifest.schema.json")
            }
            Self::ChatExport => {
                include_str!("../../../../contracts/schema/chat-export.schema.json")
            }
            Self::ModelInfo => include_str!("../../../../contracts/schema/model-info.schema.json"),
            Self::ProviderPreset => {
                include_str!("../../../../contracts/schema/provider-preset.schema.json")
            }
            Self::Settings => include_str!("../../../../contracts/schema/settings.schema.json"),
        }
    }
}

impl fmt::Display for SchemaName {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter.write_str(self.file_name())
    }
}

/// Errors returned while loading or applying a schema.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum SchemaError {
    /// The embedded schema is not valid JSON.
    Parse {
        /// Schema that failed to parse.
        schema: SchemaName,
        /// Parser message.
        message: String,
    },
    /// The schema could not be compiled.
    Compile {
        /// Schema that failed to compile.
        schema: SchemaName,
        /// Compiler message.
        message: String,
    },
    /// The instance does not satisfy the schema.
    Validation {
        /// Schema that rejected the instance.
        schema: SchemaName,
        /// Validation message.
        message: String,
    },
    /// The instance could not be serialized before validation.
    Serialize(String),
    /// The schema is not present in the set.
    Missing(SchemaName),
}

impl fmt::Display for SchemaError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::Parse { schema, message } => {
                write!(formatter, "schema {schema} is not valid JSON: {message}")
            }
            Self::Compile { schema, message } => {
                write!(formatter, "schema {schema} failed to compile: {message}")
            }
            Self::Validation { schema, message } => {
                write!(formatter, "instance does not satisfy {schema}: {message}")
            }
            Self::Serialize(message) => {
                write!(formatter, "instance is not serializable: {message}")
            }
            Self::Missing(schema) => write!(formatter, "schema {schema} is not loaded"),
        }
    }
}

impl std::error::Error for SchemaError {}

/// A compiled set of every shared schema.
#[derive(Debug, Clone)]
pub struct SchemaSet {
    validators: BTreeMap<SchemaName, jsonschema::Validator>,
}

impl SchemaSet {
    /// Parse and compile every embedded schema.
    pub fn load() -> Result<Self, SchemaError> {
        let mut validators = BTreeMap::new();

        for name in SchemaName::ALL {
            let schema: Value =
                serde_json::from_str(name.source()).map_err(|error| SchemaError::Parse {
                    schema: name,
                    message: error.to_string(),
                })?;

            let validator = jsonschema::options()
                .should_validate_formats(true)
                .build(&schema)
                .map_err(|error| SchemaError::Compile {
                    schema: name,
                    message: error.to_string(),
                })?;

            validators.insert(name, validator);
        }

        Ok(Self { validators })
    }

    /// Return the number of compiled schemas.
    pub fn len(&self) -> usize {
        self.validators.len()
    }

    /// Return true when no schemas are loaded.
    pub fn is_empty(&self) -> bool {
        self.validators.is_empty()
    }

    /// Return true when `name` is loaded.
    pub fn contains(&self, name: SchemaName) -> bool {
        self.validators.contains_key(&name)
    }

    /// Validate `instance` against `name`.
    pub fn validate(&self, name: SchemaName, instance: &Value) -> Result<(), SchemaError> {
        let validator = self
            .validators
            .get(&name)
            .ok_or(SchemaError::Missing(name))?;

        validator
            .validate(instance)
            .map_err(|error| SchemaError::Validation {
                schema: name,
                message: error.to_string(),
            })
    }

    /// Return true when `instance` satisfies `name`.
    pub fn is_valid(&self, name: SchemaName, instance: &Value) -> bool {
        self.validators
            .get(&name)
            .is_some_and(|validator| validator.is_valid(instance))
    }
}

static SCHEMAS: OnceLock<SchemaSet> = OnceLock::new();

/// Return the compiled schema set, loading it once.
///
/// # Panics
///
/// Panics when an embedded schema is invalid. Contract tests catch this before
/// release; callers that need recoverable errors should use [`SchemaSet::load`].
pub fn schemas() -> &'static SchemaSet {
    SCHEMAS.get_or_init(|| SchemaSet::load().expect("bundled schemas must compile"))
}

/// Validate `instance` against a shared schema.
pub fn validate_schema(name: SchemaName, instance: &Value) -> Result<(), SchemaError> {
    schemas().validate(name, instance)
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    fn export_fixture() -> Value {
        serde_json::from_str(include_str!(
            "../../../../contracts/fixtures/expected/chatui-export.json"
        ))
        .expect("export fixture is valid JSON")
    }

    #[test]
    fn all_five_schemas_load_and_compile() {
        let set = SchemaSet::load().expect("schemas compile");
        assert_eq!(set.len(), 5);
        assert!(!set.is_empty());

        for name in SchemaName::ALL {
            assert!(set.contains(name), "missing schema {name}");
        }
    }

    #[test]
    fn export_fixture_validates_against_chat_export_schema() {
        schemas()
            .validate(SchemaName::ChatExport, &export_fixture())
            .expect("export fixture satisfies the export schema");
    }

    #[test]
    fn every_provider_preset_validates_against_provider_preset_schema() {
        let presets: Vec<Value> =
            serde_json::from_str(include_str!("../../../../contracts/providers.json"))
                .expect("providers are valid JSON");

        assert_eq!(presets.len(), 14);
        for preset in presets {
            schemas()
                .validate(SchemaName::ProviderPreset, &preset)
                .expect("provider preset satisfies the preset schema");
        }
    }

    #[test]
    fn export_settings_and_model_validate_against_their_schemas() {
        let fixture = export_fixture();

        schemas()
            .validate(SchemaName::Settings, &fixture["settings"])
            .expect("settings satisfy the settings schema");
        schemas()
            .validate(SchemaName::ModelInfo, &fixture["models"][0])
            .expect("model satisfies the model-info schema");
    }

    #[test]
    fn backup_manifest_schema_accepts_a_valid_manifest() {
        let manifest = json!({
            "contractVersion": 1,
            "v": 1,
            "createdAt": "2026-11-04T12:00:00.000Z",
            "updatedAt": "2026-11-04T12:00:00.000Z",
            "objectType": "manifest",
            "objectKey": "backups/manifest.json",
            "alg": "AES-256-GCM",
            "kdf": "argon2id",
            "iterations": 3,
            "salt": "c2FsdA=="
        });

        schemas()
            .validate(SchemaName::BackupManifest, &manifest)
            .expect("manifest satisfies the backup schema");
    }

    #[test]
    fn invalid_export_is_rejected() {
        let mut fixture = export_fixture();
        fixture["format"] = json!("not-chatui");

        assert!(
            schemas()
                .validate(SchemaName::ChatExport, &fixture)
                .is_err()
        );
    }

    #[test]
    fn unknown_nested_export_fields_are_rejected() {
        let mut fixture = export_fixture();
        fixture["conversations"][0]["futureField"] = json!(true);

        assert!(
            schemas()
                .validate(SchemaName::ChatExport, &fixture)
                .is_err()
        );
    }
}
