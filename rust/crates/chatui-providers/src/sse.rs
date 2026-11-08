//! Provider-agnostic Server-Sent Events (SSE) framing.
//!
//! This module understands only SSE framing. It never parses provider JSON:
//! every `data:` value is handed to the caller as a raw string. Adapters own
//! JSON, `[DONE]` sentinels, and provider-specific event names.
//!
//! The reader normalizes CRLF, LF, and lone-CR line endings before splitting
//! events on a blank line. A trailing `\r` is held until the next chunk so a
//! CRLF pair split across chunks is treated as one line ending. Only `data:`
//! fields are collected; comments and unknown fields are ignored. Multiple
//! `data:` lines are joined with a single newline, and the final event is
//! flushed when the stream ends without a trailing blank line.
//!
//! The incomplete-event buffer is bounded by [`DEFAULT_MAX_SSE_BUFFER_BYTES`]
//! or a caller-supplied limit. Exceeding it returns [`SseError::BufferOverflow`]
//! instead of growing without bound.

use std::fmt;

use futures_util::{Stream, StreamExt};
use thiserror::Error;
use tokio_util::sync::CancellationToken;

/// Default maximum number of bytes retained for an incomplete event.
///
/// A complete event is drained as soon as its terminating blank line arrives,
/// so this limit only applies to data that has not yet formed a complete
/// event. It protects against a provider that never sends a blank line or that
/// sends an unbounded `data:` line.
pub const DEFAULT_MAX_SSE_BUFFER_BYTES: usize = 1024 * 1024;

/// A typed SSE framing failure.
#[derive(Debug, Clone, PartialEq, Eq, Error)]
pub enum SseError {
    /// An incomplete event grew past the configured buffer limit.
    #[error("SSE buffer exceeded the {max_bytes} byte limit")]
    BufferOverflow {
        /// The configured limit in bytes.
        max_bytes: usize,
    },
    /// The caller cancelled the read.
    #[error("SSE read was cancelled")]
    Cancelled,
    /// The underlying byte stream failed.
    #[error("SSE stream failed: {message}")]
    Stream {
        /// Display message from the underlying stream error.
        message: String,
    },
    /// A complete event was not valid UTF-8.
    #[error("SSE stream contained invalid UTF-8")]
    InvalidUtf8,
}

/// Read an SSE byte stream until it ends or `cancel` is triggered.
///
/// `on_event` is called once per complete event with the concatenated `data:`
/// value. The value is raw: this function does not parse JSON or interpret
/// `[DONE]`. Empty data values and comment-only events are not emitted.
///
/// `max_buffer` bounds the bytes retained for an incomplete event. A complete
/// event is drained before the limit is checked, so a complete event larger
/// than the limit is still delivered; only an event that remains incomplete
/// past the limit fails with [`SseError::BufferOverflow`].
///
/// Cancellation is checked before every read and races the pending read, so a
/// cancelled token stops the reader promptly without polling the stream again.
pub async fn read_sse<S, B, E, F>(
    stream: S,
    on_event: F,
    cancel: &CancellationToken,
    max_buffer: usize,
) -> Result<(), SseError>
where
    S: Stream<Item = Result<B, E>>,
    B: AsRef<[u8]>,
    E: fmt::Display,
    F: FnMut(&str),
{
    if cancel.is_cancelled() {
        return Err(SseError::Cancelled);
    }

    let mut parser = Parser::new(on_event, max_buffer);
    let mut stream = std::pin::pin!(stream);

    loop {
        if cancel.is_cancelled() {
            return Err(SseError::Cancelled);
        }

        let item = tokio::select! {
            biased;
            _ = cancel.cancelled() => return Err(SseError::Cancelled),
            item = stream.next() => item,
        };

        match item {
            Some(Ok(chunk)) => parser.push_chunk(chunk.as_ref())?,
            Some(Err(error)) => {
                if cancel.is_cancelled() {
                    return Err(SseError::Cancelled);
                }
                return Err(SseError::Stream {
                    message: error.to_string(),
                });
            }
            None => break,
        }
    }

    parser.finish()
}

struct Parser<F> {
    on_event: F,
    max_buffer: usize,
    /// Complete lines of the current event, each terminated by `\n`.
    event: Vec<u8>,
    /// The current line, without its line ending.
    line: Vec<u8>,
    /// A `\r` at the end of the previous chunk may be the first half of CRLF.
    pending_cr: bool,
}

impl<F: FnMut(&str)> Parser<F> {
    fn new(on_event: F, max_buffer: usize) -> Self {
        Self {
            on_event,
            max_buffer,
            event: Vec::new(),
            line: Vec::new(),
            pending_cr: false,
        }
    }

    fn push_chunk(&mut self, chunk: &[u8]) -> Result<(), SseError> {
        for &byte in chunk {
            if self.pending_cr {
                self.pending_cr = false;
                self.end_line()?;
                if byte == b'\n' {
                    continue;
                }
            }

            match byte {
                b'\r' => self.pending_cr = true,
                b'\n' => self.end_line()?,
                _ => self.line.push(byte),
            }
        }

        self.check_overflow()
    }

    fn finish(&mut self) -> Result<(), SseError> {
        if self.pending_cr {
            self.pending_cr = false;
            self.end_line()?;
        }

        if !self.line.is_empty() {
            self.end_line()?;
        }

        self.emit_event()
    }

    fn end_line(&mut self) -> Result<(), SseError> {
        if self.line.is_empty() {
            self.emit_event()?;
        } else {
            self.event.extend_from_slice(&self.line);
            self.event.push(b'\n');
            self.line.clear();
        }
        Ok(())
    }

    fn emit_event(&mut self) -> Result<(), SseError> {
        if self.event.is_empty() {
            return Ok(());
        }

        let mut joined = Vec::new();
        let mut has_data = false;

        for line in self.event.split(|&byte| byte == b'\n') {
            if line.is_empty() || line[0] == b':' {
                continue;
            }

            let (field, value) = match line.iter().position(|&byte| byte == b':') {
                Some(index) => (&line[..index], &line[index + 1..]),
                None => (line, &[][..]),
            };

            if field != b"data".as_slice() {
                continue;
            }

            let value = if value.first() == Some(&b' ') {
                &value[1..]
            } else {
                value
            };

            if has_data {
                joined.push(b'\n');
            }
            joined.extend_from_slice(value);
            has_data = true;
        }

        self.event.clear();

        if !has_data || joined.is_empty() {
            return Ok(());
        }

        let data = std::str::from_utf8(&joined).map_err(|_| SseError::InvalidUtf8)?;
        (self.on_event)(data);
        Ok(())
    }

    fn check_overflow(&self) -> Result<(), SseError> {
        if self.event.len() + self.line.len() > self.max_buffer {
            return Err(SseError::BufferOverflow {
                max_bytes: self.max_buffer,
            });
        }
        Ok(())
    }
}
