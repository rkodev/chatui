//! The public conformance fixture API.
//!
//! [`ConformanceFixtures`] is the single entry point later adapter, parser,
//! catalog, and export tasks use. It resolves `contracts/fixtures` from the
//! repository root and returns typed values for every fixture family.

use chatui_contracts::{ChatExport, SchemaName, provider_presets};
use serde_json::Value;

use crate::error::FixtureError;
use crate::errors::{ErrorFixture, ErrorFixtureBody, ErrorFixtureKind, ErrorVectors};
use crate::expected::{
    AnthropicThinking, CapabilityInference, CatalogVectors, FallbackModels, GeminiFinishReasons,
    KeyExpectation, MathVectors,
};
use crate::models::{ModelFixture, ModelFixtureKind};
use crate::requests::RequestVector;
use crate::root::FixtureRoot;
use crate::streams::{StreamExpectation, StreamFixture};

/// Counts for every shared fixture family.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct FixtureInventory {
    /// Provider presets in `contracts/providers.json`.
    pub presets: usize,
    /// JSON Schemas in `contracts/schema/`.
    pub schemas: usize,
    /// Raw stream fixtures.
    pub streams: usize,
    /// Raw model fixtures.
    pub models: usize,
    /// Raw error fixtures.
    pub errors: usize,
    /// Request vectors.
    pub requests: usize,
    /// Expected normalized vectors.
    pub expected: usize,
}

/// Typed access to every shared fixture family.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ConformanceFixtures {
    root: FixtureRoot,
}

impl ConformanceFixtures {
    /// Resolve fixtures from the repository containing this package.
    pub fn repository() -> Self {
        Self {
            root: FixtureRoot::repository(),
        }
    }

    /// Use an explicit repository root.
    pub fn from_repository_root(root: impl Into<std::path::PathBuf>) -> Self {
        Self {
            root: FixtureRoot::from_repository_root(root),
        }
    }

    /// Return the underlying fixture root.
    pub fn root(&self) -> &FixtureRoot {
        &self.root
    }

    /// Load every raw stream fixture.
    pub fn stream_fixtures(&self) -> Result<Vec<StreamFixture>, FixtureError> {
        self.root
            .list_files("streams")?
            .into_iter()
            .map(|name| {
                let contents = self.root.read(format!("streams/{name}"))?;
                Ok(StreamFixture { name, contents })
            })
            .collect()
    }

    /// Load every raw model fixture.
    pub fn model_fixtures(&self) -> Result<Vec<ModelFixture>, FixtureError> {
        self.root
            .list_json_files("models")?
            .into_iter()
            .map(|name| {
                let document = self.root.read_json(format!("models/{name}"))?;
                Ok(ModelFixture {
                    kind: ModelFixtureKind::from_name(&name),
                    name,
                    document,
                })
            })
            .collect()
    }

    /// Load every raw error fixture.
    pub fn error_fixtures(&self) -> Result<Vec<ErrorFixture>, FixtureError> {
        self.root
            .list_files("errors")?
            .into_iter()
            .map(|name| {
                let kind = ErrorFixtureKind::from_name(&name);
                let body = match kind {
                    ErrorFixtureKind::HttpJson => {
                        ErrorFixtureBody::Json(self.root.read_json(format!("errors/{name}"))?)
                    }
                    ErrorFixtureKind::StreamSse
                    | ErrorFixtureKind::StreamNdjson
                    | ErrorFixtureKind::Other => {
                        ErrorFixtureBody::Stream(self.root.read(format!("errors/{name}"))?)
                    }
                };
                Ok(ErrorFixture { name, kind, body })
            })
            .collect()
    }

    /// Load every request vector.
    pub fn request_vectors(&self) -> Result<Vec<RequestVector>, FixtureError> {
        self.root
            .list_json_files("requests")?
            .into_iter()
            .map(|name| {
                let mut vector: RequestVector = self.root.read_json(format!("requests/{name}"))?;
                vector.file = name;
                Ok(vector)
            })
            .collect()
    }

    /// List every expected-vector file name.
    pub fn expected_files(&self) -> Result<Vec<String>, FixtureError> {
        self.root.list_json_files("expected")
    }

    /// Load every expected stream vector.
    ///
    /// Expected files without an `events` array are skipped; they are loaded by
    /// their dedicated typed loader instead.
    pub fn stream_expectations(&self) -> Result<Vec<StreamExpectation>, FixtureError> {
        let mut expectations = Vec::new();

        for file in self.expected_files()? {
            let relative = format!("expected/{file}");
            let path = self.root.path(&relative);
            let contents = self.root.read(&relative)?;
            let value: Value = serde_json::from_str(&contents)
                .map_err(|source| FixtureError::Json { path, source })?;

            if value.get("events").is_none() {
                continue;
            }

            let mut expectation: StreamExpectation =
                serde_json::from_value(value).map_err(|source| FixtureError::Json {
                    path: self.root.path(&relative),
                    source,
                })?;
            expectation.file = file;
            expectations.push(expectation);
        }

        Ok(expectations)
    }

    /// Load the OpenRouter key introspection expectation.
    pub fn key_expectation(&self) -> Result<KeyExpectation, FixtureError> {
        self.root.read_json("expected/openrouter-key.json")
    }

    /// Load catalog mapping vectors.
    pub fn catalogs(&self) -> Result<CatalogVectors, FixtureError> {
        self.root.read_json("expected/catalogs.json")
    }

    /// Load capability inference vectors.
    pub fn capability_inference(&self) -> Result<CapabilityInference, FixtureError> {
        self.root.read_json("expected/capability-inference.json")
    }

    /// Load fallback model vectors.
    pub fn fallback_models(&self) -> Result<FallbackModels, FixtureError> {
        self.root.read_json("expected/fallback-models.json")
    }

    /// Load math segmentation vectors.
    pub fn math(&self) -> Result<MathVectors, FixtureError> {
        self.root.read_json("expected/math.json")
    }

    /// Load Gemini finish-reason mapping vectors.
    pub fn gemini_finish_reasons(&self) -> Result<GeminiFinishReasons, FixtureError> {
        self.root.read_json("expected/gemini-finish-reasons.json")
    }

    /// Load synthetic Anthropic thinking/text vectors.
    pub fn anthropic_thinking(&self) -> Result<AnthropicThinking, FixtureError> {
        self.root.read_json("expected/anthropic-thinking.json")
    }

    /// Load OpenRouter error vectors.
    pub fn openrouter_errors(&self) -> Result<ErrorVectors, FixtureError> {
        self.root.read_json("expected/openrouter-errors.json")
    }

    /// Load non-OpenRouter error vectors.
    pub fn provider_errors(&self) -> Result<ErrorVectors, FixtureError> {
        self.root.read_json("expected/provider-errors.json")
    }

    /// Load the shared export vector through `chatui-contracts`.
    pub fn export(&self) -> Result<ChatExport, FixtureError> {
        self.root.read_json("expected/chatui-export.json")
    }

    /// Count every fixture family.
    pub fn inventory(&self) -> Result<FixtureInventory, FixtureError> {
        Ok(FixtureInventory {
            presets: provider_presets().len(),
            schemas: SchemaName::ALL.len(),
            streams: self.stream_fixtures()?.len(),
            models: self.model_fixtures()?.len(),
            errors: self.error_fixtures()?.len(),
            requests: self.request_vectors()?.len(),
            expected: self.expected_files()?.len(),
        })
    }
}
