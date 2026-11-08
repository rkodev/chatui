//! Clipboard copy for messages and code blocks.
//!
//! Copy is exact: the text is not trimmed, reflowed, or decorated with hidden
//! metadata. Empty payloads are rejected so a menu action cannot silently clear
//! the clipboard.

use std::cell::RefCell;

use chatui_domain::message::{ContentPart, Message, MessageRole};
use chatui_ui::code::CodeBlock;

/// What kind of content is being copied.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum ClipboardKind {
    /// A message response.
    Message,
    /// A code block.
    Code,
}

/// A validated clipboard payload.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ClipboardPayload {
    /// Exact text to copy.
    pub text: String,
    /// Payload kind.
    pub kind: ClipboardKind,
}

/// A clipboard backend.
pub trait ClipboardService {
    /// Copy exact text.
    fn copy_text(&self, text: &str) -> Result<(), ClipboardError>;
}

/// The native system clipboard.
#[derive(Debug, Clone, Copy, Default)]
pub struct NativeClipboard;

impl ClipboardService for NativeClipboard {
    fn copy_text(&self, text: &str) -> Result<(), ClipboardError> {
        let mut clipboard = arboard::Clipboard::new().map_err(|_| ClipboardError::Unavailable)?;
        clipboard
            .set_text(text.to_owned())
            .map_err(|_| ClipboardError::Unavailable)?;
        Ok(())
    }
}

/// An in-memory clipboard for tests and headless runs.
#[derive(Debug, Default)]
pub struct MemoryClipboard {
    text: RefCell<Option<String>>,
}

impl MemoryClipboard {
    /// Create an empty clipboard.
    pub fn new() -> Self {
        Self::default()
    }

    /// The last copied text.
    pub fn text(&self) -> Option<String> {
        self.text.borrow().clone()
    }
}

impl ClipboardService for MemoryClipboard {
    fn copy_text(&self, text: &str) -> Result<(), ClipboardError> {
        *self.text.borrow_mut() = Some(text.to_owned());
        Ok(())
    }
}

/// A clipboard error.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ClipboardError {
    /// There is nothing to copy.
    Empty,
    /// The system clipboard is unavailable.
    Unavailable,
}

impl std::fmt::Display for ClipboardError {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::Empty => formatter.write_str("there is nothing to copy"),
            Self::Unavailable => formatter.write_str("the system clipboard is unavailable"),
        }
    }
}

impl std::error::Error for ClipboardError {}

/// Extract the exact text parts of a message.
///
/// Reasoning, tool calls, tool results, and attachment metadata are excluded.
pub fn message_text(message: &Message) -> String {
    message
        .parts
        .iter()
        .filter_map(|part| match part {
            ContentPart::Text { text } => Some(text.as_str()),
            _ => None,
        })
        .collect::<Vec<_>>()
        .join("\n")
}

/// Build a clipboard payload for a message.
pub fn copy_message(message: &Message) -> Result<ClipboardPayload, ClipboardError> {
    let text = message_text(message);
    if text.trim().is_empty() {
        return Err(ClipboardError::Empty);
    }
    Ok(ClipboardPayload {
        text,
        kind: ClipboardKind::Message,
    })
}

/// Build a clipboard payload for the last assistant response.
pub fn copy_last_response(messages: &[Message]) -> Result<ClipboardPayload, ClipboardError> {
    messages
        .iter()
        .rev()
        .filter(|message| message.role == MessageRole::Assistant)
        .find_map(|message| copy_message(message).ok())
        .ok_or(ClipboardError::Empty)
}

/// Build a clipboard payload for a code block.
pub fn copy_code_block(block: &CodeBlock) -> Result<ClipboardPayload, ClipboardError> {
    if block.code.is_empty() {
        return Err(ClipboardError::Empty);
    }
    Ok(ClipboardPayload {
        text: block.code.clone(),
        kind: ClipboardKind::Code,
    })
}

/// Copy a payload through a clipboard backend.
pub fn copy_payload(
    service: &dyn ClipboardService,
    payload: &ClipboardPayload,
) -> Result<(), ClipboardError> {
    service.copy_text(&payload.text)
}

#[cfg(test)]
mod tests {
    use super::*;
    use chatui_domain::message::MessageStatus;
    use chatui_domain::time::Timestamp;

    fn timestamp() -> Timestamp {
        Timestamp::parse("2026-11-07T00:00:00.000Z").expect("timestamp")
    }

    fn message(role: MessageRole, parts: Vec<ContentPart>) -> Message {
        Message {
            id: "message".to_owned(),
            conversation_id: "conversation".to_owned(),
            sequence: 1,
            role,
            parts,
            status: MessageStatus::Complete,
            provider_id: None,
            model_id: None,
            model_label: None,
            usage: None,
            finish_reason: None,
            error: None,
            latency_ms: None,
            first_token_ms: None,
            created_at: timestamp(),
            updated_at: timestamp(),
            deleted_at: None,
        }
    }

    fn text_message(role: MessageRole, text: &str) -> Message {
        message(
            role,
            vec![ContentPart::Text {
                text: text.to_owned(),
            }],
        )
    }

    #[test]
    fn message_text_uses_only_text_parts() {
        let message = message(
            MessageRole::Assistant,
            vec![
                ContentPart::Reasoning {
                    text: "hidden reasoning".to_owned(),
                },
                ContentPart::Text {
                    text: "visible answer".to_owned(),
                },
                ContentPart::ToolCall {
                    id: "tool".to_owned(),
                    name: "lookup".to_owned(),
                    arguments_json: "{}".to_owned(),
                },
            ],
        );
        assert_eq!(message_text(&message), "visible answer");
    }

    #[test]
    fn copy_message_rejects_empty_text() {
        let message = text_message(MessageRole::Assistant, "   ");
        assert_eq!(copy_message(&message), Err(ClipboardError::Empty));
    }

    #[test]
    fn copy_last_response_finds_the_last_assistant_message() {
        let messages = vec![
            text_message(MessageRole::User, "question"),
            text_message(MessageRole::Assistant, "first answer"),
            text_message(MessageRole::User, "follow-up"),
            text_message(MessageRole::Assistant, "second answer"),
        ];
        let payload = copy_last_response(&messages).expect("payload");
        assert_eq!(payload.text, "second answer");
        assert_eq!(payload.kind, ClipboardKind::Message);
    }

    #[test]
    fn copy_last_response_skips_empty_assistant_messages() {
        let messages = vec![
            text_message(MessageRole::Assistant, "answer"),
            text_message(MessageRole::Assistant, ""),
        ];
        let payload = copy_last_response(&messages).expect("payload");
        assert_eq!(payload.text, "answer");
    }

    #[test]
    fn copy_code_block_is_exact() {
        let block = CodeBlock::new(
            "fn main() {\n    println!(\"hi\");\n}",
            Some("rust".to_owned()),
        );
        let payload = copy_code_block(&block).expect("payload");
        assert_eq!(payload.text, block.code);
        assert_eq!(payload.kind, ClipboardKind::Code);
    }

    #[test]
    fn memory_clipboard_stores_exact_text() {
        let clipboard = MemoryClipboard::new();
        let payload = ClipboardPayload {
            text: "  exact text  ".to_owned(),
            kind: ClipboardKind::Message,
        };
        copy_payload(&clipboard, &payload).expect("copy");
        assert_eq!(clipboard.text().as_deref(), Some("  exact text  "));
    }
}
