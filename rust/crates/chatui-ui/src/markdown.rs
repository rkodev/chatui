//! Safe GFM Markdown rendering.
//!
//! The renderer is framework-independent. It turns Markdown into a bounded
//! document model that a Slint view can map to native text and code widgets.
//! It never enables raw HTML, never executes scripts, and never loads a remote
//! image unless the caller explicitly allows it.
//!
//! Safety boundaries:
//!
//! - Raw HTML is kept as text (`Block::Html` or `Inline::Text`), never parsed.
//! - Links are limited to `http`, `https`, `mailto`, and in-app `#anchors`.
//! - Images are limited to `http` and `https` and are blocked by default.
//! - Code is highlighted with a bounded language set and copied from the exact
//!   source text, never from highlighted markup.
//! - Math has no bundled renderer; it falls back to bounded raw source.

use pulldown_cmark::{
    Alignment, BlockQuoteKind, CodeBlockKind, Event, Options, Parser, Tag, TagEnd,
};
use url::Url;

use crate::code::CodeBlock;
use crate::math::{MathSource, is_math_language, math_fallback};

/// A table row: one inline list per cell.
pub type TableRow = Vec<Vec<Inline>>;

/// Options for rendering Markdown.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct RenderOptions {
    /// Allow remote images to load. Defaults to `false` (spec 11.6).
    pub load_remote_images: bool,
    /// Highlight fenced code blocks. Defaults to `true`.
    pub highlight_code: bool,
}

impl Default for RenderOptions {
    fn default() -> Self {
        Self {
            load_remote_images: false,
            highlight_code: true,
        }
    }
}

/// A rendered Markdown document.
#[derive(Debug, Clone, PartialEq)]
pub struct RenderedMarkdown {
    /// Top-level blocks in source order.
    pub blocks: Vec<Block>,
}

impl RenderedMarkdown {
    /// Whether the document has no blocks.
    pub fn is_empty(&self) -> bool {
        self.blocks.is_empty()
    }

    /// Plain-text projection used for copy and tests.
    ///
    /// Raw HTML is included as text. Links contribute their label, images
    /// contribute their alt text, and math contributes its raw source.
    pub fn plain_text(&self) -> String {
        let mut output = String::new();
        write_blocks(&mut output, &self.blocks);
        output
    }
}

/// A block-level Markdown element.
#[derive(Debug, Clone, PartialEq)]
pub enum Block {
    /// A paragraph.
    Paragraph(Vec<Inline>),
    /// A heading with its level (1-6).
    Heading {
        /// Heading level.
        level: u8,
        /// Heading content.
        inlines: Vec<Inline>,
    },
    /// A fenced or indented code block.
    Code(CodeBlock),
    /// Math shown as bounded raw source.
    Math(MathSource),
    /// A block quote, optionally a GFM alert kind.
    BlockQuote {
        /// GFM alert kind, when present.
        kind: Option<BlockQuoteKind>,
        /// Quoted blocks.
        blocks: Vec<Block>,
    },
    /// An ordered or unordered list.
    List {
        /// Whether the list is ordered.
        ordered: bool,
        /// First number for an ordered list.
        start: Option<u64>,
        /// One block list per item.
        items: Vec<Vec<Block>>,
    },
    /// A table.
    Table {
        /// Column alignments.
        alignments: Vec<Alignment>,
        /// Header cells.
        header: TableRow,
        /// Body rows.
        rows: Vec<TableRow>,
    },
    /// A thematic break.
    ThematicBreak,
    /// Raw HTML source, rendered as text.
    Html(String),
    /// A footnote definition.
    FootnoteDefinition {
        /// Footnote label.
        label: String,
        /// Definition blocks.
        blocks: Vec<Block>,
    },
}

/// An inline Markdown element.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Inline {
    /// Plain text.
    Text(String),
    /// Inline code.
    Code(String),
    /// Emphasis.
    Emphasis(Vec<Inline>),
    /// Strong emphasis.
    Strong(Vec<Inline>),
    /// Strikethrough.
    Strikethrough(Vec<Inline>),
    /// A link, possibly blocked.
    Link(LinkRef),
    /// An image, possibly blocked.
    Image(ImageRef),
    /// Math shown as bounded raw source.
    Math(MathSource),
    /// A soft line break.
    SoftBreak,
    /// A hard line break.
    HardBreak,
    /// A task-list checkbox.
    TaskListMarker(bool),
    /// A footnote reference.
    FootnoteReference(String),
}

/// The action a UI should take for a link.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum LinkAction {
    /// Open the URL in the system browser.
    OpenExternal,
    /// Navigate to an in-app anchor.
    InAppAnchor,
    /// Do nothing; the link was rejected by the allowlist.
    Blocked,
}

/// A link with an allowlisted URL.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct LinkRef {
    /// Safe URL, or `None` when the scheme is rejected.
    pub url: Option<String>,
    /// Optional link title.
    pub title: Option<String>,
    /// Link label.
    pub children: Vec<Inline>,
}

impl LinkRef {
    /// Whether the link was rejected by the allowlist.
    pub fn is_blocked(&self) -> bool {
        self.url.is_none()
    }

    /// Whether the link leaves the app.
    pub fn is_external(&self) -> bool {
        matches!(
            self.url.as_deref(),
            Some(url)
                if url.starts_with("http://")
                    || url.starts_with("https://")
                    || url.starts_with("mailto:")
        )
    }

    /// Explicit action for the UI action layer.
    pub fn action(&self) -> LinkAction {
        match self.url.as_deref() {
            None => LinkAction::Blocked,
            Some(url) if url.starts_with('#') => LinkAction::InAppAnchor,
            Some(_) => LinkAction::OpenExternal,
        }
    }
}

/// An image with a bounded source.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ImageRef {
    /// Alt text.
    pub alt: String,
    /// Optional image title.
    pub title: Option<String>,
    /// Safe `http`/`https` source, or `None` when rejected.
    pub source: Option<String>,
    /// Whether the image is allowed to load.
    pub loaded: bool,
}

impl ImageRef {
    /// Whether the image is shown as a blocked placeholder.
    pub fn is_blocked(&self) -> bool {
        !self.loaded
    }

    /// Whether a per-message "Load image" action can unblock it.
    pub fn can_load(&self) -> bool {
        self.source.is_some() && !self.loaded
    }
}

/// Render Markdown with the given options.
pub fn render_markdown(source: &str, options: &RenderOptions) -> RenderedMarkdown {
    let closed = close_incomplete_fence(source);
    let mut parser_options = Options::empty();
    parser_options.insert(Options::ENABLE_TABLES);
    parser_options.insert(Options::ENABLE_TASKLISTS);
    parser_options.insert(Options::ENABLE_STRIKETHROUGH);
    parser_options.insert(Options::ENABLE_MATH);
    parser_options.insert(Options::ENABLE_GFM);

    let events: Vec<Event<'_>> = Parser::new_ext(&closed, parser_options).collect();
    let mut position = 0;
    let blocks = parse_blocks(&events, &mut position, None, options);

    RenderedMarkdown { blocks }
}

/// Render Markdown with default options.
pub fn render_markdown_default(source: &str) -> RenderedMarkdown {
    render_markdown(source, &RenderOptions::default())
}

/// Return a safe link URL, or `None` when the URL is not allowed.
///
/// Allowed: absolute `http`, `https`, and `mailto` URLs plus in-app `#anchor`
/// links. Relative paths, protocol-relative URLs, credentials in the URL, and
/// every other scheme are rejected.
pub fn sanitize_link_url(url: &str) -> Option<String> {
    let trimmed = url.trim();

    if trimmed.is_empty() {
        return None;
    }

    if trimmed.starts_with('#') {
        return Some(trimmed.to_owned());
    }

    let parsed = Url::parse(trimmed).ok()?;

    match parsed.scheme() {
        "http" | "https" => {
            if parsed.host_str().is_none()
                || !parsed.username().is_empty()
                || parsed.password().is_some()
            {
                return None;
            }

            Some(trimmed.to_owned())
        }
        "mailto" => {
            if parsed.path().is_empty() {
                return None;
            }

            Some(trimmed.to_owned())
        }
        _ => None,
    }
}

/// Return a safe remote-image URL, or `None` when the URL is not allowed.
///
/// Images may only point at absolute `http` or `https` URLs. `mailto`,
/// anchors, relative paths, data URLs, and every other scheme are rejected.
pub fn sanitize_image_url(url: &str) -> Option<String> {
    let safe = sanitize_link_url(url)?;
    let parsed = Url::parse(&safe).ok()?;

    if parsed.scheme() == "http" || parsed.scheme() == "https" {
        Some(safe)
    } else {
        None
    }
}

/// Close an unterminated fenced code block for rendering only.
///
/// Streaming text can end in the middle of a fence. Appending the matching
/// closing fence lets the parser render a code block instead of flashing raw
/// backticks. The original text is never mutated.
pub fn close_incomplete_fence(markdown: &str) -> String {
    let mut open: Option<(char, usize)> = None;

    for line in markdown.split('\n') {
        let Some((character, length, rest)) = fence_marker(line) else {
            continue;
        };

        match open {
            None => open = Some((character, length)),
            Some((open_character, open_length)) => {
                if character == open_character && length >= open_length && rest.trim().is_empty() {
                    open = None;
                }
            }
        }
    }

    match open {
        None => markdown.to_owned(),
        Some((character, length)) => {
            let closing = character.to_string().repeat(length);

            if markdown.ends_with('\n') {
                format!("{markdown}{closing}")
            } else {
                format!("{markdown}\n{closing}")
            }
        }
    }
}

fn fence_marker(line: &str) -> Option<(char, usize, &str)> {
    let trimmed = line.trim_start_matches(' ');
    let indent = line.len() - trimmed.len();

    if indent > 3 {
        return None;
    }

    let character = trimmed.chars().next()?;

    if character != '`' && character != '~' {
        return None;
    }

    let length = trimmed
        .chars()
        .take_while(|value| *value == character)
        .count();

    if length < 3 {
        return None;
    }

    Some((character, length, &trimmed[length..]))
}

fn parse_blocks(
    events: &[Event<'_>],
    position: &mut usize,
    stop: Option<TagEnd>,
    options: &RenderOptions,
) -> Vec<Block> {
    let mut blocks = Vec::new();
    let mut pending: Vec<Inline> = Vec::new();

    while let Some(event) = events.get(*position) {
        if let Event::End(end) = event {
            if stop == Some(*end) {
                *position += 1;
                break;
            }

            *position += 1;
            continue;
        }

        match event {
            Event::Start(Tag::Paragraph) => {
                flush_pending(&mut blocks, &mut pending);
                *position += 1;
                let inlines = parse_inlines(events, position, TagEnd::Paragraph, options);
                blocks.push(Block::Paragraph(inlines));
            }
            Event::Start(Tag::Heading { level, .. }) => {
                flush_pending(&mut blocks, &mut pending);
                let heading_level = *level;
                let level = heading_level as u8;
                *position += 1;
                let inlines =
                    parse_inlines(events, position, TagEnd::Heading(heading_level), options);
                blocks.push(Block::Heading { level, inlines });
            }
            Event::Start(Tag::CodeBlock(kind)) => {
                flush_pending(&mut blocks, &mut pending);
                *position += 1;
                let mut code = String::new();

                while let Some(event) = events.get(*position) {
                    match event {
                        Event::Text(text) => {
                            code.push_str(text);
                            *position += 1;
                        }
                        Event::End(TagEnd::CodeBlock) => {
                            *position += 1;
                            break;
                        }
                        _ => *position += 1,
                    }
                }

                let language = match kind {
                    CodeBlockKind::Fenced(info) => {
                        info.split_whitespace().next().map(str::to_owned)
                    }
                    CodeBlockKind::Indented => None,
                };

                if language.as_deref().is_some_and(is_math_language) {
                    let source = code.strip_suffix('\n').unwrap_or(&code);
                    blocks.push(Block::Math(math_fallback(source, true)));
                } else {
                    blocks.push(Block::Code(CodeBlock::with_highlighting(
                        code,
                        language,
                        options.highlight_code,
                    )));
                }
            }
            Event::Start(Tag::BlockQuote(kind)) => {
                flush_pending(&mut blocks, &mut pending);
                let kind = *kind;
                *position += 1;
                let inner = parse_blocks(events, position, Some(TagEnd::BlockQuote(kind)), options);
                blocks.push(Block::BlockQuote {
                    kind,
                    blocks: inner,
                });
            }
            Event::Start(Tag::List(start)) => {
                flush_pending(&mut blocks, &mut pending);
                let start = *start;
                *position += 1;
                let mut items = Vec::new();

                while let Some(event) = events.get(*position) {
                    match event {
                        Event::End(TagEnd::List(_)) => {
                            *position += 1;
                            break;
                        }
                        Event::Start(Tag::Item) => {
                            *position += 1;
                            items.push(parse_blocks(events, position, Some(TagEnd::Item), options));
                        }
                        _ => *position += 1,
                    }
                }

                blocks.push(Block::List {
                    ordered: start.is_some(),
                    start,
                    items,
                });
            }
            Event::Start(Tag::Table(alignments)) => {
                flush_pending(&mut blocks, &mut pending);
                let alignments = alignments.clone();
                *position += 1;
                let (header, rows) = parse_table(events, position, options);
                blocks.push(Block::Table {
                    alignments,
                    header,
                    rows,
                });
            }
            Event::Start(Tag::HtmlBlock) => {
                flush_pending(&mut blocks, &mut pending);
                *position += 1;
                let mut html = String::new();

                while let Some(event) = events.get(*position) {
                    match event {
                        Event::Html(text) => {
                            html.push_str(text);
                            *position += 1;
                        }
                        Event::End(TagEnd::HtmlBlock) => {
                            *position += 1;
                            break;
                        }
                        _ => *position += 1,
                    }
                }

                blocks.push(Block::Html(html));
            }
            Event::Start(Tag::FootnoteDefinition(label)) => {
                flush_pending(&mut blocks, &mut pending);
                let label = label.to_string();
                *position += 1;
                let inner =
                    parse_blocks(events, position, Some(TagEnd::FootnoteDefinition), options);
                blocks.push(Block::FootnoteDefinition {
                    label,
                    blocks: inner,
                });
            }
            Event::Start(Tag::MetadataBlock(_)) => {
                flush_pending(&mut blocks, &mut pending);
                *position += 1;

                while let Some(event) = events.get(*position) {
                    if matches!(event, Event::End(TagEnd::MetadataBlock(_))) {
                        *position += 1;
                        break;
                    }

                    *position += 1;
                }
            }
            Event::Rule => {
                flush_pending(&mut blocks, &mut pending);
                blocks.push(Block::ThematicBreak);
                *position += 1;
            }
            Event::Html(text) | Event::InlineHtml(text) => {
                pending.push(Inline::Text(text.to_string()));
                *position += 1;
            }
            Event::Text(text) => {
                pending.push(Inline::Text(text.to_string()));
                *position += 1;
            }
            Event::Code(text) => {
                pending.push(Inline::Code(text.to_string()));
                *position += 1;
            }
            Event::InlineMath(text) => {
                pending.push(Inline::Math(math_fallback(text, false)));
                *position += 1;
            }
            Event::DisplayMath(text) => {
                pending.push(Inline::Math(math_fallback(text, true)));
                *position += 1;
            }
            Event::SoftBreak => {
                pending.push(Inline::SoftBreak);
                *position += 1;
            }
            Event::HardBreak => {
                pending.push(Inline::HardBreak);
                *position += 1;
            }
            Event::TaskListMarker(checked) => {
                pending.push(Inline::TaskListMarker(*checked));
                *position += 1;
            }
            Event::FootnoteReference(label) => {
                pending.push(Inline::FootnoteReference(label.to_string()));
                *position += 1;
            }
            Event::Start(Tag::Emphasis) => {
                *position += 1;
                let children = parse_inlines(events, position, TagEnd::Emphasis, options);
                pending.push(Inline::Emphasis(children));
            }
            Event::Start(Tag::Strong) => {
                *position += 1;
                let children = parse_inlines(events, position, TagEnd::Strong, options);
                pending.push(Inline::Strong(children));
            }
            Event::Start(Tag::Strikethrough) => {
                *position += 1;
                let children = parse_inlines(events, position, TagEnd::Strikethrough, options);
                pending.push(Inline::Strikethrough(children));
            }
            Event::Start(Tag::Superscript) => {
                *position += 1;
                let children = parse_inlines(events, position, TagEnd::Superscript, options);
                pending.push(Inline::Text(inline_plain_text(&children)));
            }
            Event::Start(Tag::Subscript) => {
                *position += 1;
                let children = parse_inlines(events, position, TagEnd::Subscript, options);
                pending.push(Inline::Text(inline_plain_text(&children)));
            }
            Event::Start(Tag::Link {
                dest_url, title, ..
            }) => {
                let destination = dest_url.to_string();
                let title = optional_title(title);
                *position += 1;
                let children = parse_inlines(events, position, TagEnd::Link, options);
                pending.push(Inline::Link(link_ref(&destination, title, children)));
            }
            Event::Start(Tag::Image {
                dest_url, title, ..
            }) => {
                let destination = dest_url.to_string();
                let title = optional_title(title);
                *position += 1;
                let children = parse_inlines(events, position, TagEnd::Image, options);
                let alt = inline_plain_text(&children);
                pending.push(Inline::Image(image_ref(&destination, title, alt, options)));
            }
            Event::Start(Tag::Item) => {
                *position += 1;
                let inner = parse_blocks(events, position, Some(TagEnd::Item), options);
                blocks.extend(inner);
            }
            Event::Start(_) => *position += 1,
            Event::End(_) => *position += 1,
        }
    }

    flush_pending(&mut blocks, &mut pending);
    blocks
}

fn parse_inlines(
    events: &[Event<'_>],
    position: &mut usize,
    stop: TagEnd,
    options: &RenderOptions,
) -> Vec<Inline> {
    let mut inlines = Vec::new();

    while let Some(event) = events.get(*position) {
        match event {
            Event::End(end) if *end == stop => {
                *position += 1;
                break;
            }
            Event::End(_) => *position += 1,
            Event::Text(text) => {
                inlines.push(Inline::Text(text.to_string()));
                *position += 1;
            }
            Event::Code(text) => {
                inlines.push(Inline::Code(text.to_string()));
                *position += 1;
            }
            Event::InlineMath(text) => {
                inlines.push(Inline::Math(math_fallback(text, false)));
                *position += 1;
            }
            Event::DisplayMath(text) => {
                inlines.push(Inline::Math(math_fallback(text, true)));
                *position += 1;
            }
            Event::Html(text) | Event::InlineHtml(text) => {
                inlines.push(Inline::Text(text.to_string()));
                *position += 1;
            }
            Event::SoftBreak => {
                inlines.push(Inline::SoftBreak);
                *position += 1;
            }
            Event::HardBreak => {
                inlines.push(Inline::HardBreak);
                *position += 1;
            }
            Event::TaskListMarker(checked) => {
                inlines.push(Inline::TaskListMarker(*checked));
                *position += 1;
            }
            Event::FootnoteReference(label) => {
                inlines.push(Inline::FootnoteReference(label.to_string()));
                *position += 1;
            }
            Event::Start(Tag::Emphasis) => {
                *position += 1;
                let children = parse_inlines(events, position, TagEnd::Emphasis, options);
                inlines.push(Inline::Emphasis(children));
            }
            Event::Start(Tag::Strong) => {
                *position += 1;
                let children = parse_inlines(events, position, TagEnd::Strong, options);
                inlines.push(Inline::Strong(children));
            }
            Event::Start(Tag::Strikethrough) => {
                *position += 1;
                let children = parse_inlines(events, position, TagEnd::Strikethrough, options);
                inlines.push(Inline::Strikethrough(children));
            }
            Event::Start(Tag::Superscript) => {
                *position += 1;
                let children = parse_inlines(events, position, TagEnd::Superscript, options);
                inlines.push(Inline::Text(inline_plain_text(&children)));
            }
            Event::Start(Tag::Subscript) => {
                *position += 1;
                let children = parse_inlines(events, position, TagEnd::Subscript, options);
                inlines.push(Inline::Text(inline_plain_text(&children)));
            }
            Event::Start(Tag::Link {
                dest_url, title, ..
            }) => {
                let destination = dest_url.to_string();
                let title = optional_title(title);
                *position += 1;
                let children = parse_inlines(events, position, TagEnd::Link, options);
                inlines.push(Inline::Link(link_ref(&destination, title, children)));
            }
            Event::Start(Tag::Image {
                dest_url, title, ..
            }) => {
                let destination = dest_url.to_string();
                let title = optional_title(title);
                *position += 1;
                let children = parse_inlines(events, position, TagEnd::Image, options);
                let alt = inline_plain_text(&children);
                inlines.push(Inline::Image(image_ref(&destination, title, alt, options)));
            }
            Event::Start(Tag::Paragraph) => {
                *position += 1;
                let children = parse_inlines(events, position, TagEnd::Paragraph, options);
                inlines.extend(children);
            }
            Event::Start(_) => *position += 1,
            Event::Rule => *position += 1,
        }
    }

    inlines
}

fn parse_table(
    events: &[Event<'_>],
    position: &mut usize,
    options: &RenderOptions,
) -> (TableRow, Vec<TableRow>) {
    let mut header = Vec::new();
    let mut rows = Vec::new();

    while let Some(event) = events.get(*position) {
        match event {
            Event::End(TagEnd::Table) => {
                *position += 1;
                break;
            }
            Event::Start(Tag::TableHead) => {
                *position += 1;
                header = parse_table_row(events, position, TagEnd::TableHead, options);
            }
            Event::Start(Tag::TableRow) => {
                *position += 1;
                rows.push(parse_table_row(events, position, TagEnd::TableRow, options));
            }
            _ => *position += 1,
        }
    }

    (header, rows)
}

fn parse_table_row(
    events: &[Event<'_>],
    position: &mut usize,
    stop: TagEnd,
    options: &RenderOptions,
) -> TableRow {
    let mut cells = Vec::new();

    while let Some(event) = events.get(*position) {
        match event {
            Event::End(end) if *end == stop => {
                *position += 1;
                break;
            }
            Event::Start(Tag::TableCell) => {
                *position += 1;
                cells.push(parse_inlines(events, position, TagEnd::TableCell, options));
            }
            _ => *position += 1,
        }
    }

    cells
}

fn flush_pending(blocks: &mut Vec<Block>, pending: &mut Vec<Inline>) {
    if !pending.is_empty() {
        blocks.push(Block::Paragraph(std::mem::take(pending)));
    }
}

fn optional_title(title: &str) -> Option<String> {
    if title.is_empty() {
        None
    } else {
        Some(title.to_owned())
    }
}

fn link_ref(destination: &str, title: Option<String>, children: Vec<Inline>) -> LinkRef {
    LinkRef {
        url: sanitize_link_url(destination),
        title,
        children,
    }
}

fn image_ref(
    destination: &str,
    title: Option<String>,
    alt: String,
    options: &RenderOptions,
) -> ImageRef {
    let source = sanitize_image_url(destination);
    let loaded = source.is_some() && options.load_remote_images;

    ImageRef {
        alt,
        title,
        source,
        loaded,
    }
}

fn inline_plain_text(inlines: &[Inline]) -> String {
    let mut output = String::new();
    write_inlines(&mut output, inlines);
    output
}

fn write_blocks(output: &mut String, blocks: &[Block]) {
    for (index, block) in blocks.iter().enumerate() {
        if index > 0 {
            output.push('\n');
        }

        write_block(output, block);
    }
}

fn write_block(output: &mut String, block: &Block) {
    match block {
        Block::Paragraph(inlines) => write_inlines(output, inlines),
        Block::Heading { inlines, .. } => write_inlines(output, inlines),
        Block::Code(code) => output.push_str(&code.code),
        Block::Math(math) => output.push_str(&math.raw()),
        Block::BlockQuote { blocks, .. } => write_blocks(output, blocks),
        Block::List {
            ordered,
            start,
            items,
        } => {
            for (index, item) in items.iter().enumerate() {
                if index > 0 {
                    output.push('\n');
                }

                if *ordered {
                    let number = (*start).unwrap_or(1) + index as u64;
                    output.push_str(&format!("{number}. "));
                } else {
                    output.push_str("- ");
                }

                write_blocks(output, item);
            }
        }
        Block::Table { header, rows, .. } => {
            write_table_row(output, header);

            for row in rows {
                output.push('\n');
                write_table_row(output, row);
            }
        }
        Block::ThematicBreak => output.push_str("---"),
        Block::Html(html) => output.push_str(html),
        Block::FootnoteDefinition { label, blocks } => {
            output.push_str(&format!("[^{label}]: "));
            write_blocks(output, blocks);
        }
    }
}

fn write_table_row(output: &mut String, cells: &[Vec<Inline>]) {
    for (index, cell) in cells.iter().enumerate() {
        if index > 0 {
            output.push_str(" | ");
        }

        write_inlines(output, cell);
    }
}

fn write_inlines(output: &mut String, inlines: &[Inline]) {
    for inline in inlines {
        match inline {
            Inline::Text(text) => output.push_str(text),
            Inline::Code(code) => output.push_str(code),
            Inline::Emphasis(children)
            | Inline::Strong(children)
            | Inline::Strikethrough(children) => write_inlines(output, children),
            Inline::Link(link) => write_inlines(output, &link.children),
            Inline::Image(image) => output.push_str(&image.alt),
            Inline::Math(math) => output.push_str(&math.raw()),
            Inline::SoftBreak | Inline::HardBreak => output.push('\n'),
            Inline::TaskListMarker(checked) => {
                output.push_str(if *checked { "[x] " } else { "[ ] " });
            }
            Inline::FootnoteReference(label) => output.push_str(&format!("[^{label}]")),
        }
    }
}
