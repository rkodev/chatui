//! Math fallback tests.

use crate::math::{MAX_MATH_SOURCE_CHARS, is_math_language, math_fallback};

#[test]
fn math_fallback_preserves_source_and_delimiters() {
    let inline = math_fallback("x^2", false);

    assert_eq!(inline.source, "x^2");
    assert!(!inline.display);
    assert!(!inline.truncated);
    assert_eq!(inline.raw(), "$x^2$");
    assert_eq!(inline.fallback_label(), "Math (raw source)");
    assert!(inline.is_fallback());

    let display = math_fallback("\\frac{1}{2}", true);

    assert_eq!(display.raw(), "$$\n\\frac{1}{2}\n$$");
}

#[test]
fn math_source_is_bounded() {
    let long = "x".repeat(MAX_MATH_SOURCE_CHARS + 10);
    let math = math_fallback(&long, false);

    assert!(math.truncated);
    assert_eq!(math.source.chars().count(), MAX_MATH_SOURCE_CHARS);
}

#[test]
fn math_language_detection() {
    assert!(is_math_language("math"));
    assert!(is_math_language("LaTeX"));
    assert!(is_math_language("tex"));
    assert!(is_math_language("katex"));
    assert!(!is_math_language("rust"));
}
