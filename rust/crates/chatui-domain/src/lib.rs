//! Domain entities, identifiers, errors, and stream events.
//!
//! The types in this crate mirror the shared JSON shapes in
//! `docs/IMPLEMENTATION.md` sections 6 and 7.2. They are deliberately free of
//! network, storage, UI, and OS dependencies so the same shapes can be used by
//! exports, imports, fixtures, and provider adapters.

pub mod attachment;
pub mod conversation;
pub mod errors;
pub mod ids;
pub mod message;
pub mod model;
pub mod provider;
pub mod settings;
pub mod stream;
pub mod time;
pub mod tombstone;

pub use attachment::{Attachment, AttachmentKind};
pub use conversation::{ChatParams, Conversation, ReasoningEffort, SyncMeta, SyncState};
pub use errors::{ChatError, ChatErrorCode};
pub use ids::{UuidV7Error, is_uuid_v7, new_id, validate_uuid_v7};
pub use message::{ContentPart, FinishReason, Message, MessageRole, MessageStatus, Usage};
pub use model::{InputModality, Model, ModelCapability, ModelPricing, ModelSource, OutputModality};
pub use provider::{BrowserCors, Provider, ProviderAuthType, ProviderKind};
pub use settings::{
    BackupBackend, BackupConfig, BackupEncryption, BackupKdf, ChatFontSize, Density, SendKey,
    Settings, Theme,
};
pub use stream::StreamEvent;
pub use time::{Timestamp, format_rfc3339, is_rfc3339_utc, now_rfc3339, parse_rfc3339};
pub use tombstone::Tombstone;

#[cfg(test)]
mod dependency_tests {
    /// The domain crate must stay free of I/O, storage, UI, and OS crates.
    #[test]
    fn manifest_has_no_io_dependencies() {
        let manifest = include_str!("../Cargo.toml");
        let forbidden = [
            "reqwest",
            "hyper",
            "tokio",
            "async-std",
            "sqlx",
            "rusqlite",
            "diesel",
            "keyring",
            "slint",
            "gtk",
            "winit",
            "directories",
            "notify",
            "std::fs",
        ];

        for name in forbidden {
            assert!(
                !manifest.contains(name),
                "chatui-domain must not depend on {name}"
            );
        }
    }
}
