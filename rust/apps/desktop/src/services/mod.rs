//! Desktop service foundation.
//!
//! This module owns the single `tokio` runtime, the SQLite pool, the shared
//! repositories, startup recovery, secret-store selection, and the synchronous
//! bridge used by the Slint-facing adapters.
//!
//! Later adapter tasks must reuse [`ServiceRuntime`] and must not create a
//! second runtime or pool.

// The re-exports are the public surface for later adapter tasks. This binary
// crate does not consume them yet, so suppress the unused-import lint here.
#![allow(unused_imports)]

mod runtime;
mod secrets;
mod sync;

pub use runtime::{ServiceRuntime, ServiceRuntimeError};
pub use secrets::{
    SecretStoreError, default_secret_store, select_secret_store, session_only_secret_store,
};
pub use sync::{SyncBridge, SyncSettingsStore};
