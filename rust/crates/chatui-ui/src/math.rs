//! Math fallback for message content.
//!
//! No safe Rust TeX renderer is bundled in this milestone. Math is therefore
//! shown as bounded raw source with a clear fallback label. A malformed or
//! oversized formula can never break the surrounding message.

/// Maximum number of characters kept from a math source.
pub const MAX_MATH_SOURCE_CHARS: usize = 4096;

/// A bounded math source shown as raw text.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct MathSource {
    /// Raw TeX source, truncated to [`MAX_MATH_SOURCE_CHARS`].
    pub source: String,
    /// Whether this is display math (`$$...$$`) rather than inline math.
    pub display: bool,
    /// Whether the source was truncated.
    pub truncated: bool,
}

impl MathSource {
    /// Bound and store a math source.
    pub fn new(source: &str, display: bool) -> Self {
        let mut bounded = String::new();
        let mut truncated = false;

        for (index, character) in source.chars().enumerate() {
            if index >= MAX_MATH_SOURCE_CHARS {
                truncated = true;
                break;
            }

            bounded.push(character);
        }

        Self {
            source: bounded,
            display,
            truncated,
        }
    }

    /// Raw source with the original delimiters restored.
    pub fn raw(&self) -> String {
        if self.display {
            format!("$$\n{}\n$$", self.source)
        } else {
            format!("${}$", self.source)
        }
    }

    /// Visible fallback label.
    pub const fn fallback_label(&self) -> &'static str {
        "Math (raw source)"
    }

    /// Math is always a fallback in this milestone.
    pub const fn is_fallback(&self) -> bool {
        true
    }
}

/// Build a bounded math fallback.
pub fn math_fallback(source: &str, display: bool) -> MathSource {
    MathSource::new(source, display)
}

/// Whether a fenced code language should be treated as math.
pub fn is_math_language(language: &str) -> bool {
    matches!(
        language.trim().to_ascii_lowercase().as_str(),
        "math" | "latex" | "tex" | "katex"
    )
}
