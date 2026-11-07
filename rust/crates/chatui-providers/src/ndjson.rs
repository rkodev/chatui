//! Provider-agnostic newline-delimited JSON (NDJSON) framing.
//!
//! This module understands only line framing. It never parses provider JSON:
//! every non-blank line is handed to the caller as a raw string. Adapters own
//! JSON, Ollama's `done` flag, and usage fields.
//!
//! Lines are split on `\n`, and a trailing `\r` is stripped so LF and CRLF both
//! work, including a CRLF pair split across chunks. Blank lines (empty or
//! whitespace-only) are ignored. The final line is flushed when the stream ends
//! without a trailing newline.
//!
//! The incomplete-line buffer is bounded by [`DEFAULT_MAX_NDJSON_BUFFER_BYTES`]
//! or a caller-supplied limit. Exceeding it returns
//! [`NdjsonError::BufferOverflow`] instead of growing without bound.

use std::fmt;

use futures_util::{Stream, StreamExt};
use thiserror::Error;
use tokio_util::sync::CancellationToken;

/// Default maximum number of bytes retained for an incomplete line.
///
/// A complete line is drained as soon as its terminating newline arrives, so
/// this limit only applies to data that has not yet formed a complete line. It
/// protects against a provider that never sends a newline or that sends an
/// unbounded line.
pub const DEFAULT_MAX_NDJSON_BUFFER_BYTES: usize = 1024 * 1024;

/// A typed NDJSON framing failure.
#[derive(Debug, Clone, PartialEq, Eq, Error)]
pub enum NdjsonError {
    /// An incomplete line grew past the configured buffer limit.
    #[error("NDJSON line exceeded the {max_bytes} byte limit")]
    BufferOverflow {
        /// The configured limit in bytes.
        max_bytes: usize,
    },
    /// The caller cancelled the read.
    #[error("NDJSON read was cancelled")]
    Cancelled,
    /// The underlying byte stream failed.
    #[error("NDJSON stream failed: {message}")]
    Stream {
        /// Display message from the underlying stream error.
        message: String,
    },
    /// A complete line was not valid UTF-8.
    #[error("NDJSON stream contained invalid UTF-8")]
    InvalidUtf8,
}

/// Read an NDJSON byte stream until it ends or `cancel` is triggered.
///
/// `on_line` is called once per complete non-blank line with the raw line
/// contents. The value is raw: this function does not parse JSON or interpret
/// Ollama's `done` flag. Empty and whitespace-only lines are not emitted.
///
/// `max_line_bytes` bounds the bytes retained for an incomplete line. A
/// complete line is drained before the limit is checked, so a complete line
/// larger than the limit is still delivered; only a line that remains
/// incomplete past the limit fails with [`NdjsonError::BufferOverflow`].
///
/// Cancellation is checked before every read and races the pending read, so a
/// cancelled token stops the reader promptly without polling the stream again.
pub async fn read_ndjson<S, B, E, F>(
    stream: S,
    on_line: F,
    cancel: &CancellationToken,
    max_line_bytes: usize,
) -> Result<(), NdjsonError>
where
    S: Stream<Item = Result<B, E>>,
    B: AsRef<[u8]>,
    E: fmt::Display,
    F: FnMut(&str),
{
    if cancel.is_cancelled() {
        return Err(NdjsonError::Cancelled);
    }

    let mut parser = Parser::new(on_line, max_line_bytes);
    let mut stream = std::pin::pin!(stream);

    loop {
        if cancel.is_cancelled() {
            return Err(NdjsonError::Cancelled);
        }

        let item = tokio::select! {
            biased;
            _ = cancel.cancelled() => return Err(NdjsonError::Cancelled),
            item = stream.next() => item,
        };

        match item {
            Some(Ok(chunk)) => parser.push_chunk(chunk.as_ref())?,
            Some(Err(error)) => {
                if cancel.is_cancelled() {
                    return Err(NdjsonError::Cancelled);
                }
                return Err(NdjsonError::Stream {
                    message: error.to_string(),
                });
            }
            None => break,
        }
    }

    parser.finish()
}

struct Parser<F> {
    on_line: F,
    max_line_bytes: usize,
    /// The current line, without its line ending.
    line: Vec<u8>,
}

impl<F: FnMut(&str)> Parser<F> {
    fn new(on_line: F, max_line_bytes: usize) -> Self {
        Self {
            on_line,
            max_line_bytes,
            line: Vec::new(),
        }
    }

    fn push_chunk(&mut self, chunk: &[u8]) -> Result<(), NdjsonError> {
        for &byte in chunk {
            if byte == b'\n' {
                self.end_line()?;
            } else {
                self.line.push(byte);
            }
        }

        self.check_overflow()
    }

    fn finish(&mut self) -> Result<(), NdjsonError> {
        if !self.line.is_empty() {
            self.end_line()?;
        }
        Ok(())
    }

    fn end_line(&mut self) -> Result<(), NdjsonError> {
        let mut line = std::mem::take(&mut self.line);

        if line.last() == Some(&b'\r') {
            line.pop();
        }

        if line.is_empty() || line.iter().all(u8::is_ascii_whitespace) {
            self.line = line;
            self.line.clear();
            return Ok(());
        }

        let text = std::str::from_utf8(&line).map_err(|_| NdjsonError::InvalidUtf8)?;
        (self.on_line)(text);

        self.line = line;
        self.line.clear();
        Ok(())
    }

    fn check_overflow(&self) -> Result<(), NdjsonError> {
        if self.line.len() > self.max_line_bytes {
            return Err(NdjsonError::BufferOverflow {
                max_bytes: self.max_line_bytes,
            });
        }
        Ok(())
    }
}
