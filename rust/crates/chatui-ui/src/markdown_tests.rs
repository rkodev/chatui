//! Markdown rendering, safety, link, image, fence, and math tests.

use pulldown_cmark::Alignment;

use crate::markdown::{
    Block, ImageRef, Inline, LinkAction, LinkRef, RenderOptions, RenderedMarkdown,
    close_incomplete_fence, render_markdown, sanitize_image_url, sanitize_link_url,
};

fn options() -> RenderOptions {
    RenderOptions {
        highlight_code: false,
        ..RenderOptions::default()
    }
}

fn collect_links(document: &RenderedMarkdown) -> Vec<LinkRef> {
    let mut links = Vec::new();

    for block in &document.blocks {
        collect_block_links(block, &mut links);
    }

    links
}

fn collect_block_links(block: &Block, links: &mut Vec<LinkRef>) {
    match block {
        Block::Paragraph(inlines) | Block::Heading { inlines, .. } => {
            collect_inline_links(inlines, links);
        }
        Block::BlockQuote { blocks, .. } | Block::FootnoteDefinition { blocks, .. } => {
            for block in blocks {
                collect_block_links(block, links);
            }
        }
        Block::List { items, .. } => {
            for item in items {
                for block in item {
                    collect_block_links(block, links);
                }
            }
        }
        Block::Table { header, rows, .. } => {
            for cell in header {
                collect_inline_links(cell, links);
            }

            for row in rows {
                for cell in row {
                    collect_inline_links(cell, links);
                }
            }
        }
        _ => {}
    }
}

fn collect_inline_links(inlines: &[Inline], links: &mut Vec<LinkRef>) {
    for inline in inlines {
        match inline {
            Inline::Link(link) => {
                links.push(link.clone());
                collect_inline_links(&link.children, links);
            }
            Inline::Emphasis(children)
            | Inline::Strong(children)
            | Inline::Strikethrough(children) => collect_inline_links(children, links),
            _ => {}
        }
    }
}

fn collect_images(document: &RenderedMarkdown) -> Vec<ImageRef> {
    let mut images = Vec::new();

    for block in &document.blocks {
        collect_block_images(block, &mut images);
    }

    images
}

fn collect_block_images(block: &Block, images: &mut Vec<ImageRef>) {
    match block {
        Block::Paragraph(inlines) | Block::Heading { inlines, .. } => {
            collect_inline_images(inlines, images);
        }
        Block::BlockQuote { blocks, .. } | Block::FootnoteDefinition { blocks, .. } => {
            for block in blocks {
                collect_block_images(block, images);
            }
        }
        Block::List { items, .. } => {
            for item in items {
                for block in item {
                    collect_block_images(block, images);
                }
            }
        }
        Block::Table { header, rows, .. } => {
            for cell in header {
                collect_inline_images(cell, images);
            }

            for row in rows {
                for cell in row {
                    collect_inline_images(cell, images);
                }
            }
        }
        _ => {}
    }
}

fn collect_inline_images(inlines: &[Inline], images: &mut Vec<ImageRef>) {
    for inline in inlines {
        match inline {
            Inline::Image(image) => images.push(image.clone()),
            Inline::Emphasis(children)
            | Inline::Strong(children)
            | Inline::Strikethrough(children) => collect_inline_images(children, images),
            Inline::Link(link) => collect_inline_images(&link.children, images),
            _ => {}
        }
    }
}

#[test]
fn gfm_renders_headings_lists_tables_and_task_lists() {
    let source = "# Title\n\n- [x] done\n- [ ] todo\n\n| Left | Center | Right |\n| :--- | :----: | ----: |\n| 1 | 2 | 3 |\n";
    let document = render_markdown(source, &options());

    assert!(matches!(
        document.blocks[0],
        Block::Heading { level: 1, .. }
    ));
    assert!(matches!(
        document.blocks[1],
        Block::List { ordered: false, .. }
    ));
    assert!(matches!(document.blocks[2], Block::Table { .. }));

    if let Block::Table {
        alignments,
        header,
        rows,
    } = &document.blocks[2]
    {
        assert_eq!(
            alignments,
            &[Alignment::Left, Alignment::Center, Alignment::Right]
        );
        assert_eq!(header.len(), 3);
        assert_eq!(rows.len(), 1);
    }

    let text = document.plain_text();
    assert!(text.contains("Title"));
    assert!(text.contains("[x] done"));
    assert!(text.contains("[ ] todo"));
    assert!(text.contains("Left | Center | Right"));
}

#[test]
fn raw_html_is_inert_text() {
    let source = "<script>alert('xss')</script>\n\nhello <img src=x onerror=alert(1)> world\n\n<div>block</div>";
    let document = render_markdown(source, &options());
    let text = document.plain_text();

    assert!(text.contains("<script>alert('xss')</script>"));
    assert!(text.contains("<img src=x onerror=alert(1)>"));
    assert!(text.contains("<div>block</div>"));
    assert!(
        document
            .blocks
            .iter()
            .any(|block| matches!(block, Block::Html(_)))
    );
    assert!(document.blocks.iter().any(|block| {
        matches!(
            block,
            Block::Paragraph(inlines)
                if inlines.iter().any(|inline| matches!(inline, Inline::Text(text) if text.contains("<img")))
        )
    }));
}

#[test]
fn unsafe_link_schemes_are_rejected() {
    let source = "[js](javascript:alert(1)) [data](data:text/html;base64,PHNjcmlwdD4=) [file](file:///etc/passwd) [proto](//evil.example) [rel](/relative) [ok](https://example.com) [mail](mailto:a@example.com) [anchor](#section)";
    let document = render_markdown(source, &options());
    let links = collect_links(&document);

    assert_eq!(links.len(), 8);
    assert!(links[0].is_blocked());
    assert!(links[1].is_blocked());
    assert!(links[2].is_blocked());
    assert!(links[3].is_blocked());
    assert!(links[4].is_blocked());
    assert_eq!(links[5].url.as_deref(), Some("https://example.com"));
    assert!(links[5].is_external());
    assert_eq!(links[5].action(), LinkAction::OpenExternal);
    assert_eq!(links[6].url.as_deref(), Some("mailto:a@example.com"));
    assert!(links[6].is_external());
    assert_eq!(links[6].action(), LinkAction::OpenExternal);
    assert_eq!(links[7].url.as_deref(), Some("#section"));
    assert!(!links[7].is_external());
    assert_eq!(links[7].action(), LinkAction::InAppAnchor);
    assert_eq!(links[0].action(), LinkAction::Blocked);
}

#[test]
fn link_allowlist_rejects_credentials_and_missing_hosts() {
    assert!(sanitize_link_url("https://user:pass@example.com").is_none());
    assert!(sanitize_link_url("http://").is_none());
    assert!(sanitize_link_url("mailto:").is_none());
    assert_eq!(
        sanitize_link_url("https://example.com/path?q=1#frag"),
        Some("https://example.com/path?q=1#frag".to_owned())
    );
    assert_eq!(sanitize_link_url("#anchor"), Some("#anchor".to_owned()));
}

#[test]
fn image_allowlist_rejects_non_http_sources() {
    assert!(sanitize_image_url("mailto:a@example.com").is_none());
    assert!(sanitize_image_url("#anchor").is_none());
    assert!(sanitize_image_url("data:image/png;base64,AAAA").is_none());
    assert_eq!(
        sanitize_image_url("https://example.com/a.png"),
        Some("https://example.com/a.png".to_owned())
    );
}

#[test]
fn remote_images_are_blocked_by_default_and_allowed_by_setting() {
    let source = "![alt](https://example.com/a.png) ![bad](javascript:alert(1)) ![data](data:image/png;base64,AAAA)";
    let document = render_markdown(source, &options());
    let images = collect_images(&document);

    assert_eq!(images.len(), 3);
    assert!(images[0].is_blocked());
    assert!(images[0].can_load());
    assert_eq!(
        images[0].source.as_deref(),
        Some("https://example.com/a.png")
    );
    assert!(!images[1].can_load());
    assert!(images[1].source.is_none());
    assert!(!images[2].can_load());
    assert!(images[2].source.is_none());

    let allowed = render_markdown(
        source,
        &RenderOptions {
            load_remote_images: true,
            highlight_code: false,
        },
    );
    let images = collect_images(&allowed);

    assert!(!images[0].is_blocked());
    assert!(images[1].is_blocked());
    assert!(images[2].is_blocked());
}

#[test]
fn incomplete_fence_renders_as_code_block() {
    let source = "```rust\nfn main() {\n    println!(\"hi\");";
    let document = render_markdown(source, &options());

    assert_eq!(document.blocks.len(), 1);

    match &document.blocks[0] {
        Block::Code(code) => {
            assert_eq!(code.language.as_deref(), Some("rust"));
            assert!(code.code.contains("fn main()"));
            assert_eq!(code.copy_text(), code.code);
        }
        other => panic!("expected code block, got {other:?}"),
    }
}

#[test]
fn close_incomplete_fence_appends_only_when_needed() {
    assert_eq!(
        close_incomplete_fence("```rust\ncode"),
        "```rust\ncode\n```"
    );
    assert_eq!(
        close_incomplete_fence("```rust\ncode\n"),
        "```rust\ncode\n```"
    );
    assert_eq!(
        close_incomplete_fence("```rust\ncode\n```"),
        "```rust\ncode\n```"
    );
    assert_eq!(close_incomplete_fence("~~~\ncode"), "~~~\ncode\n~~~");
}

#[test]
fn code_copy_returns_exact_source() {
    let source = "```python\nprint('hi')\n```";
    let document = render_markdown(source, &options());

    match &document.blocks[0] {
        Block::Code(code) => {
            assert_eq!(code.copy_text(), "print('hi')\n");
            assert_eq!(code.language_label(), "python");
        }
        other => panic!("expected code block, got {other:?}"),
    }
}

#[test]
fn fenced_code_is_highlighted_when_enabled() {
    let source = "```rust\nfn main() {}\n```";
    let document = render_markdown(source, &RenderOptions::default());

    match &document.blocks[0] {
        Block::Code(code) => {
            assert!(code.is_highlighted());
            let highlighted = code.highlighted.as_ref().expect("highlighted code");
            assert_eq!(highlighted.language, "rust");
            assert!(!highlighted.lines.is_empty());
        }
        other => panic!("expected code block, got {other:?}"),
    }
}

#[test]
fn math_falls_back_to_bounded_raw_source() {
    let source = "Inline $x^2$ and display $$\\frac{1}{2}$$.\n\n```math\n\\sum_{i=0}^n i\n```";
    let document = render_markdown(source, &options());
    let text = document.plain_text();

    assert!(text.contains("$x^2$"));
    assert!(text.contains("$$"));
    assert!(text.contains("\\frac{1}{2}"));
    assert!(text.contains("\\sum_{i=0}^n i"));
    assert!(
        document
            .blocks
            .iter()
            .any(|block| matches!(block, Block::Math(_)))
    );
}

#[test]
fn ordered_list_start_is_preserved() {
    let document = render_markdown("3. three\n4. four", &options());

    match &document.blocks[0] {
        Block::List {
            ordered: true,
            start: Some(3),
            items,
        } => assert_eq!(items.len(), 2),
        other => panic!("expected ordered list, got {other:?}"),
    }
}

#[test]
fn blockquote_and_thematic_break_render() {
    let document = render_markdown("> quote\n\n---", &options());

    assert!(matches!(document.blocks[0], Block::BlockQuote { .. }));
    assert!(matches!(document.blocks[1], Block::ThematicBreak));
}

#[test]
fn empty_document_is_empty() {
    let document = render_markdown("", &RenderOptions::default());

    assert!(document.is_empty());
    assert_eq!(document.plain_text(), "");
}

#[test]
fn xss_payloads_are_inert() {
    let payloads = [
        "<script>alert(1)</script>",
        "<img src=x onerror=alert(1)>",
        "<iframe src=\"https://evil.example\"></iframe>",
        "<svg onload=alert(1)>",
    ];

    for payload in payloads {
        let document = render_markdown(payload, &options());
        let text = document.plain_text();

        assert!(
            text.contains(payload.trim()),
            "payload should be visible as text: {payload}"
        );
        assert!(
            document
                .blocks
                .iter()
                .all(|block| !matches!(block, Block::Code(_)))
        );
    }
}
