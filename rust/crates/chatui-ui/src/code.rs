//! Bounded syntax highlighting for fenced code blocks.
//!
//! Highlighting uses `syntect` with one fixed theme and an explicit language
//! allowlist. Unsupported languages and highlighting failures fall back to
//! plain text. The renderer never executes code and never emits HTML.

use std::sync::OnceLock;

use syntect::easy::HighlightLines;
use syntect::highlighting::{FontStyle, Style, ThemeSet};
use syntect::parsing::SyntaxSet;
use syntect::util::LinesWithEndings;

use crate::tokens::Rgba;

/// Languages accepted by the highlighter.
///
/// The list mirrors the web client's bounded set. It is intentionally small so
/// the desktop client does not ship every Sublime grammar.
pub const SUPPORTED_LANGUAGES: &[&str] = &[
    "bash",
    "c",
    "cpp",
    "csharp",
    "css",
    "diff",
    "go",
    "html",
    "java",
    "javascript",
    "json",
    "jsx",
    "kotlin",
    "markdown",
    "php",
    "python",
    "ruby",
    "rust",
    "shellscript",
    "sql",
    "swift",
    "toml",
    "tsx",
    "typescript",
    "xml",
    "yaml",
];

/// The single theme used for every code block.
pub const THEME_NAME: &str = "base16-ocean.dark";

/// A highlighted span of code.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct HighlightSpan {
    /// Exact source text for this span.
    pub text: String,
    /// Foreground color from the bounded theme.
    pub color: Rgba,
    /// Bold font style.
    pub bold: bool,
    /// Italic font style.
    pub italic: bool,
    /// Underline font style.
    pub underline: bool,
}

/// Highlighted code split into lines and spans.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct HighlightedCode {
    /// Normalized language name.
    pub language: String,
    /// One entry per source line, each containing styled spans.
    pub lines: Vec<Vec<HighlightSpan>>,
    /// Default foreground color from the theme.
    pub foreground: Rgba,
    /// Default background color from the theme.
    pub background: Rgba,
}

/// How a code block lays out long lines.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum CodeWrap {
    /// Wrap long lines.
    Wrap,
    /// Scroll long lines horizontally.
    Scroll,
}

/// Presentation state for a code block.
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub struct CodeBlockView {
    /// Whether long lines wrap.
    pub wrap: bool,
}

impl CodeBlockView {
    /// Create a view with horizontal scrolling (the default).
    pub const fn new() -> Self {
        Self { wrap: false }
    }

    /// Toggle between wrap and scroll.
    pub fn toggle_wrap(&mut self) {
        self.wrap = !self.wrap;
    }

    /// Current layout mode.
    pub const fn mode(&self) -> CodeWrap {
        if self.wrap {
            CodeWrap::Wrap
        } else {
            CodeWrap::Scroll
        }
    }
}

/// A fenced code block.
///
/// `code` is the exact source text. `highlighted` is presentation-only and is
/// never used for copy.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct CodeBlock {
    /// Original fence info string, trimmed. `None` for indented or unlabeled
    /// blocks.
    pub language: Option<String>,
    /// Exact source text.
    pub code: String,
    /// Bounded highlighting, when the language is supported.
    pub highlighted: Option<HighlightedCode>,
}

impl CodeBlock {
    /// Build a code block and highlight it when the language is supported.
    pub fn new(code: impl Into<String>, language: Option<String>) -> Self {
        Self::with_highlighting(code, language, true)
    }

    /// Build a code block, optionally skipping highlighting.
    pub fn with_highlighting(
        code: impl Into<String>,
        language: Option<String>,
        highlight: bool,
    ) -> Self {
        let code = code.into();
        let language = language.and_then(|value| {
            let token = value.split_whitespace().next().unwrap_or("");

            if token.is_empty() {
                None
            } else {
                Some(token.to_owned())
            }
        });
        let highlighted = if highlight {
            highlight_code(&code, language.as_deref())
        } else {
            None
        };

        Self {
            language,
            code,
            highlighted,
        }
    }

    /// Exact source text for the copy action.
    pub fn copy_text(&self) -> &str {
        &self.code
    }

    /// Visible language label.
    pub fn language_label(&self) -> &str {
        self.language.as_deref().unwrap_or("Code")
    }

    /// Whether bounded highlighting is available.
    pub fn is_highlighted(&self) -> bool {
        self.highlighted.is_some()
    }
}

/// Normalize a Markdown fence info string to a supported language.
///
/// Aliases mirror the web client. Unknown languages return `None` so the
/// renderer shows plain text instead of guessing.
pub fn normalize_language(language: Option<&str>) -> Option<&'static str> {
    let language = language?;
    let normalized = language.trim().to_ascii_lowercase();
    let normalized = normalized.strip_prefix('.').unwrap_or(&normalized);

    if normalized.is_empty() {
        return None;
    }

    let canonical = match normalized {
        "bash" => "bash",
        "c" => "c",
        "c++" | "cc" | "cpp" => "cpp",
        "cs" | "csharp" => "csharp",
        "css" => "css",
        "diff" => "diff",
        "go" | "golang" => "go",
        "html" => "html",
        "java" => "java",
        "js" | "javascript" => "javascript",
        "json" => "json",
        "jsx" => "jsx",
        "kt" | "kotlin" => "kotlin",
        "md" | "markdown" => "markdown",
        "php" => "php",
        "py" | "python" => "python",
        "rb" | "ruby" => "ruby",
        "rs" | "rust" => "rust",
        "sh" | "shell" | "shellscript" | "zsh" => "shellscript",
        "sql" => "sql",
        "swift" => "swift",
        "toml" => "toml",
        "ts" | "typescript" => "typescript",
        "tsx" => "tsx",
        "xml" => "xml",
        "yml" | "yaml" => "yaml",
        _ => return None,
    };

    Some(canonical)
}

/// Highlight code with the bounded language set.
///
/// Returns `None` for unsupported languages and for any highlighting failure.
/// Callers must fall back to plain text in that case.
pub fn highlight_code(code: &str, language: Option<&str>) -> Option<HighlightedCode> {
    let language = normalize_language(language)?;
    let syntax_set = syntax_set();
    let theme_set = theme_set();
    let theme = theme_set.themes.get(THEME_NAME)?;
    let token = syntect_token(language);
    let syntax = syntax_set
        .find_syntax_by_token(token)
        .or_else(|| syntax_set.find_syntax_by_extension(token))?;
    let mut highlighter = HighlightLines::new(syntax, theme);
    let mut lines: Vec<Vec<HighlightSpan>> = Vec::new();
    let mut current: Vec<HighlightSpan> = Vec::new();

    for line in LinesWithEndings::from(code) {
        let ranges = highlighter.highlight_line(line, syntax_set).ok()?;

        for (style, text) in ranges {
            let mut text = text;

            while let Some(index) = text.find('\n') {
                let (before, after) = text.split_at(index);
                let before = before.strip_suffix('\r').unwrap_or(before);

                if !before.is_empty() {
                    current.push(span(before, style));
                }

                lines.push(std::mem::take(&mut current));
                text = &after[1..];
            }

            if !text.is_empty() {
                current.push(span(text, style));
            }
        }
    }

    if !current.is_empty() || lines.is_empty() {
        lines.push(current);
    }

    Some(HighlightedCode {
        language: language.to_owned(),
        lines,
        foreground: rgba(theme.settings.foreground?),
        background: rgba(theme.settings.background?),
    })
}

fn syntect_token(language: &str) -> &str {
    match language {
        "bash" | "shellscript" => "sh",
        "csharp" => "cs",
        "javascript" => "js",
        "kotlin" => "kt",
        "markdown" => "md",
        "python" => "py",
        "ruby" => "rb",
        "rust" => "rs",
        "typescript" => "ts",
        other => other,
    }
}

fn span(text: &str, style: Style) -> HighlightSpan {
    HighlightSpan {
        text: text.to_owned(),
        color: rgba(style.foreground),
        bold: style.font_style.contains(FontStyle::BOLD),
        italic: style.font_style.contains(FontStyle::ITALIC),
        underline: style.font_style.contains(FontStyle::UNDERLINE),
    }
}

fn rgba(color: syntect::highlighting::Color) -> Rgba {
    Rgba::from_rgba(color.r, color.g, color.b, color.a)
}

fn syntax_set() -> &'static SyntaxSet {
    static SYNTAXES: OnceLock<SyntaxSet> = OnceLock::new();

    SYNTAXES.get_or_init(SyntaxSet::load_defaults_newlines)
}

fn theme_set() -> &'static ThemeSet {
    static THEMES: OnceLock<ThemeSet> = OnceLock::new();

    THEMES.get_or_init(ThemeSet::load_defaults)
}
