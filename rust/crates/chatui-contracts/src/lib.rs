//! Shared contract loading, schemas, export types, and app configuration.
//!
//! `contracts/` is the only source of provider presets, JSON Schemas, and
//! export shapes. This crate embeds those files with `include_str!` and exposes
//! typed accessors. It never contains a second preset list or a second
//! serialization shape.
//!
//! # Contract evolution policy
//!
//! The desktop client follows the shared contract rules in
//! `docs/IMPLEMENTATION.md` section 5.3 and the M3.5 plan section 3.2 (D-09).
//!
//! - **Catalog fields are tolerant.** Provider presets and raw model catalog
//!   envelopes ignore unknown fields. A provider can add metadata without
//!   breaking older clients, and the desktop client must not fail on additive
//!   catalog changes. [`providers::ProviderPreset`] therefore does not use
//!   `deny_unknown_fields`.
//! - **Export fields are strict.** [`export::ChatExport`] rejects unknown
//!   top-level fields, and [`export::ChatExport::validate`] applies
//!   `chat-export.schema.json`, which rejects unknown nested fields. Imports
//!   must validate before any database write.
//! - **Breaking changes need a version bump.** Removing or renaming a field,
//!   changing an enum value, or changing a required field is a breaking change.
//!   It requires a `contractVersion` bump in the affected schema and migration
//!   notes in the same change. Additive catalog fields do not need a bump.
//! - **`providers.json` has no version field.** Additive preset fields are safe
//!   because catalog parsing is tolerant. Removing or renaming a preset field
//!   requires a schema change and a coordinated client update.
//! - **Do not copy presets into Rust source.** Load them through
//!   [`providers::provider_catalog`] or [`providers::provider_presets`]. Tests
//!   audit the crate source for duplicated preset URLs.
//! - **App configuration is deployment-level.** [`config::AppConfig`] carries
//!   only the public app name and origin used for attribution headers. It must
//!   not contain personal data, API keys, or secrets, and this crate never
//!   reads `.env` files.
//!
//! # Example
//!
//! ```
//! use chatui_contracts::{AppConfig, provider_catalog};
//!
//! let config = AppConfig::new("ChatUI", "https://chatui.example").expect("valid config");
//! let preset = provider_catalog().get("openrouter").expect("openrouter preset");
//! let headers = config.attribution_headers(preset);
//!
//! assert_eq!(headers["X-Title"], "ChatUI");
//! ```

pub mod config;
pub mod export;
pub mod providers;
pub mod schema;

pub use config::{APP_NAME_PLACEHOLDER, APP_ORIGIN_PLACEHOLDER, AppConfig, AppConfigError};
pub use export::{
    ChatExport, EXPORT_FORMAT, EXPORT_FORMAT_VERSION, ExportAttachment, ExportSettings,
};
pub use providers::{
    CUSTOM_PRESET_ID, DEFAULT_PRESET_ID, KeyValidation, KeyValidationMethod, ModelDiscovery,
    ModelDiscoveryFormat, PROVIDERS_JSON, ProviderAuth, ProviderCatalog, ProviderCatalogError,
    ProviderPreset, REQUIRED_PRESET_IDS, provider_catalog, provider_presets,
};
pub use schema::{SchemaError, SchemaName, SchemaSet, schemas, validate_schema};
