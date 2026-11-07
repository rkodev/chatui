//! Fixture-driven adapter and contract conformance tests.
//!
//! This package is the shared oracle for every later Rust adapter, parser,
//! catalog, and export task. It reads `contracts/fixtures/**` from the
//! repository at test time; it never copies fixture content into Rust source
//! and never rewrites expected vectors.
//!
//! # Fixture root
//!
//! [`ConformanceFixtures::repository`] resolves the repository root from
//! `CARGO_MANIFEST_DIR`. The package lives at `rust/tests/conformance`, so the
//! root is three ancestors above the manifest directory. Fixtures are read from
//! `<root>/contracts/fixtures`.
//!
//! # Example
//!
//! ```
//! use chatui_conformance::ConformanceFixtures;
//!
//! let fixtures = ConformanceFixtures::repository();
//! let streams = fixtures.stream_fixtures().expect("stream fixtures load");
//! assert_eq!(streams.len(), 11);
//!
//! let expectations = fixtures.stream_expectations().expect("expected vectors load");
//! assert_eq!(expectations.len(), 11);
//! ```
//!
//! # Adding an adapter-specific case
//!
//! Do not edit the shared vectors. Add the adapter-specific assertion in the
//! provider crate and load the shared input through this API. See
//! `docs/operations/M3.5/conformance.md`.

pub mod error;
pub mod errors;
pub mod expected;
pub mod fixtures;
pub mod models;
pub mod requests;
pub mod root;
pub mod streams;

pub use error::FixtureError;
pub use errors::{ErrorCase, ErrorFixture, ErrorFixtureBody, ErrorFixtureKind, ErrorVectors};
pub use expected::{
    AnthropicThinking, AnthropicThinkingCase, CapabilityCase, CapabilityExpected,
    CapabilityInference, CapabilityPreset, CatalogCase, CatalogDefaults, CatalogEntry,
    CatalogVectors, FallbackCase, FallbackCatalogEntry, FallbackExpected, FallbackModels,
    FallbackPreset, GeminiFinishCase, GeminiFinishReasons, KeyExpectation, KeyInfo, MathCase,
    MathExpected, MathSegment, MathVectors, RateLimit,
};
pub use fixtures::{ConformanceFixtures, FixtureInventory};
pub use models::{ModelFixture, ModelFixtureKind};
pub use requests::{
    DefaultModelCase, DefaultModelCatalogEntry, DefaultModelExpected, KNOWN_PROVIDER_IDS,
    NormalizedMessage, NormalizedRequest, RequestExpectation, RequestKind, RequestVector,
    ResolvedAttachment,
};
pub use root::FixtureRoot;
pub use streams::{ExpectedChatError, ExpectedStreamEvent, StreamExpectation, StreamFixture};

/// Expected number of provider presets in `contracts/providers.json`.
pub const EXPECTED_PRESET_COUNT: usize = 14;

/// Expected number of JSON Schemas in `contracts/schema/`.
pub const EXPECTED_SCHEMA_COUNT: usize = 5;

/// Expected number of raw stream fixtures.
pub const EXPECTED_STREAM_FIXTURE_COUNT: usize = 11;

/// Expected number of raw model fixtures.
pub const EXPECTED_MODEL_FIXTURE_COUNT: usize = 6;

/// Expected number of raw error fixtures.
pub const EXPECTED_ERROR_FIXTURE_COUNT: usize = 28;

/// Expected number of request vectors.
pub const EXPECTED_REQUEST_VECTOR_COUNT: usize = 22;

/// Expected number of expected normalized vectors.
pub const EXPECTED_VECTOR_COUNT: usize = 21;
