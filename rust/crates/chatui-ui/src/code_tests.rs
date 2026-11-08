//! Bounded syntax-highlighting and code-copy tests.

use crate::code::{
    CodeBlock, CodeBlockView, CodeWrap, SUPPORTED_LANGUAGES, THEME_NAME, highlight_code,
    normalize_language,
};

#[test]
fn supported_language_set_is_bounded() {
    assert!(SUPPORTED_LANGUAGES.len() <= 32);

    for language in SUPPORTED_LANGUAGES {
        assert!(
            normalize_language(Some(language)).is_some(),
            "supported language should normalize: {language}"
        );
    }
}

#[test]
fn language_aliases_normalize() {
    assert_eq!(normalize_language(Some("rs")), Some("rust"));
    assert_eq!(normalize_language(Some("py")), Some("python"));
    assert_eq!(normalize_language(Some("js")), Some("javascript"));
    assert_eq!(normalize_language(Some("sh")), Some("shellscript"));
    assert_eq!(normalize_language(Some(".ts")), Some("typescript"));
    assert_eq!(normalize_language(Some("unknown")), None);
    assert_eq!(normalize_language(None), None);
}

#[test]
fn highlighting_returns_spans_for_supported_language() {
    let highlighted = highlight_code("fn main() {}\n", Some("rust")).expect("rust highlighting");

    assert_eq!(highlighted.language, "rust");
    assert!(!highlighted.lines.is_empty());
    assert!(
        highlighted
            .lines
            .iter()
            .flatten()
            .any(|span| span.text.contains("fn"))
    );
    assert!(highlighted.foreground.a > 0);
    assert!(highlighted.background.a > 0);
}

#[test]
fn unsupported_language_falls_back_to_plain_text() {
    assert!(highlight_code("hello", Some("not-a-language")).is_none());
    assert!(highlight_code("hello", None).is_none());
}

#[test]
fn code_block_copy_returns_exact_source() {
    let block = CodeBlock::new("let x = 1;\n", Some("rust".to_owned()));

    assert_eq!(block.copy_text(), "let x = 1;\n");
    assert_eq!(block.language_label(), "rust");
    assert!(block.is_highlighted());
}

#[test]
fn code_block_without_highlighting_keeps_source() {
    let block = CodeBlock::with_highlighting("let x = 1;\n", Some("rust".to_owned()), false);

    assert!(!block.is_highlighted());
    assert_eq!(block.copy_text(), "let x = 1;\n");
}

#[test]
fn language_label_defaults_to_code() {
    let block = CodeBlock::new("x", None);

    assert_eq!(block.language_label(), "Code");
    assert_eq!(block.language, None);
}

#[test]
fn code_block_wrap_toggle_switches_between_wrap_and_scroll() {
    let mut view = CodeBlockView::new();

    assert_eq!(view.mode(), CodeWrap::Scroll);
    view.toggle_wrap();
    assert_eq!(view.mode(), CodeWrap::Wrap);
    view.toggle_wrap();
    assert_eq!(view.mode(), CodeWrap::Scroll);
}

#[test]
fn theme_is_fixed() {
    assert_eq!(THEME_NAME, "base16-ocean.dark");
}
