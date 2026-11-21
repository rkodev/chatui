use slint::StyledText;

use super::message_list::NativeMarkdownBlock;
use crate::markdown::{
    Block, Inline, LinkAction, RenderOptions, RenderedMarkdown, render_markdown,
};

pub(super) fn render_native(source: &str) -> Vec<NativeMarkdownBlock> {
    let document = render_markdown(
        source,
        &RenderOptions {
            highlight_code: false,
            ..RenderOptions::default()
        },
    );
    let mut output = Vec::new();
    project_blocks(&document.blocks, 0, false, &mut output);
    output
}

fn project_blocks(
    blocks: &[Block],
    indent: usize,
    quote: bool,
    output: &mut Vec<NativeMarkdownBlock>,
) {
    for block in blocks {
        let mut native = NativeMarkdownBlock {
            indent: indent.min(4) as i32,
            quote,
            ..NativeMarkdownBlock::default()
        };
        match block {
            Block::Paragraph(inlines) | Block::Heading { inlines, .. } => {
                let mut markdown = safe_inlines(inlines);
                if let Block::Heading { level, .. } = block {
                    native.heading = i32::from(*level);
                    markdown = format!("**{markdown}**");
                }
                native.text = StyledText::from_markdown(&markdown)
                    .unwrap_or_else(|_| StyledText::from_plain_text(&plain_block(block)));
            }
            Block::Code(code) => {
                native.code = true;
                native.label = code
                    .language
                    .as_ref()
                    .map(|language| format!("{language} code"))
                    .unwrap_or_else(|| "Code".to_owned())
                    .into();
                native.source = code.code.clone().into();
                native.text = StyledText::from_plain_text(&code.code);
            }
            Block::Math(math) => {
                native.code = true;
                native.label = "Math source".into();
                native.source = math.source.clone().into();
                native.text = StyledText::from_plain_text(&math.source);
            }
            Block::Table { .. } => {
                let text = plain_block(block);
                native.code = true;
                native.label = "Table".into();
                native.source = text.clone().into();
                native.text = StyledText::from_plain_text(&text);
            }
            Block::BlockQuote { blocks, .. } => {
                project_blocks(blocks, indent, true, output);
                continue;
            }
            Block::List {
                ordered,
                start,
                items,
            } => {
                for (index, item) in items.iter().enumerate() {
                    let first = output.len();
                    project_blocks(item, indent + 1, quote, output);
                    if let Some(first) = output.get_mut(first) {
                        first.marker = if *ordered {
                            format!("{}.", start.unwrap_or(1).saturating_add(index as u64))
                        } else {
                            "-".to_owned()
                        }
                        .into();
                    }
                }
                continue;
            }
            Block::ThematicBreak => native.rule = true,
            Block::Html(text) => native.text = StyledText::from_plain_text(text),
            Block::FootnoteDefinition { label, blocks } => {
                let first = output.len();
                project_blocks(blocks, indent, quote, output);
                if let Some(first) = output.get_mut(first) {
                    first.marker = format!("[{label}]").into();
                }
                continue;
            }
        }
        output.push(native);
    }
}

fn plain_block(block: &Block) -> String {
    RenderedMarkdown {
        blocks: vec![block.clone()],
    }
    .plain_text()
    .trim_end_matches('\n')
    .to_owned()
}

fn escape_text(text: &str, output: &mut String) {
    for character in text.chars() {
        if character.is_ascii_punctuation() {
            output.push('\\');
        }
        output.push(character);
    }
}

fn code_span(text: &str, output: &mut String) {
    let longest = text
        .split(|character| character != '`')
        .map(str::len)
        .max()
        .unwrap_or(0);
    let fence = "`".repeat(longest + 1);
    let pad = text.starts_with('`')
        || text.ends_with('`')
        || (text.starts_with(' ') && text.ends_with(' ') && !text.chars().all(char::is_whitespace));
    output.push_str(&fence);
    if pad {
        output.push(' ');
    }
    output.push_str(text);
    if pad {
        output.push(' ');
    }
    output.push_str(&fence);
}

// Re-encode the safe AST, never raw message HTML, for Slint's inline renderer.
fn safe_inlines(inlines: &[Inline]) -> String {
    let mut output = String::new();
    for inline in inlines {
        match inline {
            Inline::Text(text) => escape_text(text, &mut output),
            Inline::Code(text) => code_span(text, &mut output),
            Inline::Emphasis(children)
            | Inline::Strong(children)
            | Inline::Strikethrough(children) => {
                let delimiter = match inline {
                    Inline::Emphasis(_) => "*",
                    Inline::Strong(_) => "**",
                    _ => "~~",
                };
                output.push_str(delimiter);
                output.push_str(&safe_inlines(children));
                output.push_str(delimiter);
            }
            Inline::Link(link) => {
                let url = link
                    .url
                    .as_deref()
                    .filter(|_| link.action() == LinkAction::OpenExternal)
                    .and_then(|url| url::Url::parse(url).ok())
                    .map(|url| {
                        url.to_string()
                            .replace('<', "%3C")
                            .replace('>', "%3E")
                            .replace('\\', "%5C")
                    });
                if let Some(url) = url {
                    output.push('[');
                    output.push_str(&safe_inlines(&link.children));
                    output.push_str("](<");
                    output.push_str(&url);
                    output.push_str(">)");
                } else {
                    output.push_str(&safe_inlines(&link.children));
                }
            }
            Inline::Image(image) => {
                let label = if image.alt.is_empty() {
                    "Image"
                } else {
                    &image.alt
                };
                if let Some(source) = image.source.as_deref() {
                    let link = crate::markdown::LinkRef {
                        url: Some(source.to_owned()),
                        title: None,
                        children: vec![Inline::Text(format!("Image: {label}"))],
                    };
                    output.push_str(&safe_inlines(&[Inline::Link(link)]));
                } else {
                    escape_text(label, &mut output);
                }
            }
            Inline::Math(math) => code_span(&math.source, &mut output),
            Inline::SoftBreak => output.push('\n'),
            Inline::HardBreak => output.push_str("  \n"),
            Inline::TaskListMarker(checked) => {
                escape_text(if *checked { "[x] " } else { "[ ] " }, &mut output)
            }
            Inline::FootnoteReference(label) => escape_text(&format!("[{label}]"), &mut output),
        }
    }
    output
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn raw_html_and_blocked_links_stay_literal_and_inert() {
        let literal = "<font color=\"red\">not markup</font>";
        let blocks = render_native(literal);
        assert_eq!(blocks[0].text, StyledText::from_plain_text(literal));

        let blocks = render_native("[unsafe](javascript:alert(1))");
        assert_eq!(blocks[0].text, StyledText::from_plain_text("unsafe"));
    }

    #[test]
    fn code_and_lists_keep_exact_sources_and_structure() {
        let blocks =
            render_native("# Heading\n\n1. First\n2. Second\n\n```rust\nlet value = \"<u>\";\n```");
        assert_eq!(blocks[0].heading, 1);
        assert_eq!(blocks[1].marker, "1.");
        assert_eq!(blocks[2].marker, "2.");
        assert!(blocks[3].code);
        assert_eq!(blocks[3].source, "let value = \"<u>\";\n");
    }

    #[test]
    fn literal_markdown_and_inline_backticks_are_not_reinterpreted() {
        let inlines = [
            Inline::Text("**literal** <u> &lt;".to_owned()),
            Inline::Code("`a`".to_owned()),
        ];
        let styled = StyledText::from_markdown(&safe_inlines(&inlines)).expect("safe markdown");
        let expected = StyledText::from_markdown("\\*\\*literal\\*\\* \\<u\\> \\&lt\\;`` `a` ``")
            .expect("expected markdown");
        assert_eq!(styled, expected);
    }
}
