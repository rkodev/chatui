//! Raw model catalog fixtures under `contracts/fixtures/models/`.

use serde_json::Value;

/// The provider envelope shape of a model fixture.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ModelFixtureKind {
    /// OpenRouter `{ "data": [...] }` catalog.
    OpenrouterModels,
    /// OpenAI-compatible `{ "object": "list", "data": [...] }` catalog.
    OpenaiModels,
    /// Anthropic `{ "data": [...], "has_more": ... }` catalog.
    AnthropicModels,
    /// Gemini `{ "models": [...] }` catalog.
    GeminiModels,
    /// Ollama `{ "models": [...] }` tags response.
    OllamaTags,
    /// OpenRouter `{ "data": { ... } }` key introspection response.
    OpenrouterKey,
    /// A future fixture shape the harness does not classify yet.
    Other,
}

impl ModelFixtureKind {
    /// Classify a model fixture by file name.
    pub fn from_name(name: &str) -> Self {
        match name {
            "openrouter-models.json" => Self::OpenrouterModels,
            "openai-models.json" => Self::OpenaiModels,
            "anthropic-models.json" => Self::AnthropicModels,
            "gemini-models.json" => Self::GeminiModels,
            "ollama-tags.json" => Self::OllamaTags,
            "openrouter-key.json" => Self::OpenrouterKey,
            _ => Self::Other,
        }
    }
}

/// A raw model fixture and its classified envelope shape.
#[derive(Debug, Clone, PartialEq)]
pub struct ModelFixture {
    /// File name, such as `openai-models.json`.
    pub name: String,
    /// Classified envelope shape.
    pub kind: ModelFixtureKind,
    /// Parsed JSON document.
    pub document: Value,
}

impl ModelFixture {
    /// Return the catalog entries when the envelope has a `data` or `models`
    /// array. Key introspection responses return `None`.
    pub fn entries(&self) -> Option<&[Value]> {
        self.document
            .get("data")
            .and_then(Value::as_array)
            .or_else(|| self.document.get("models").and_then(Value::as_array))
            .map(Vec::as_slice)
    }
}
