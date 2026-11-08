//! Single-instance forwarding for `chatui://` deep links.
//!
//! Unix platforms use a local Unix-domain socket in the runtime directory. The
//! first instance binds the socket and accepts forwarded links; later instances
//! connect and write the link, then exit. This is a local IPC endpoint, not a
//! network destination.
//!
//! Windows named-pipe forwarding is not implemented in this task. The no-op
//! backend reports [`SingleInstanceError::Unsupported`] so the caller can start
//! normally and record the per-OS blocker.

use std::path::PathBuf;
use std::time::Duration;

/// A single-instance backend.
pub trait SingleInstance {
    /// Acquire the instance lock.
    fn acquire(&self) -> Result<InstanceGuard, SingleInstanceError>;
    /// Forward a message to the running instance.
    fn forward(&self, message: &str) -> Result<(), SingleInstanceError>;
}

/// The running instance's listener.
pub struct InstanceGuard {
    #[cfg(unix)]
    inner: UnixGuard,
}

impl InstanceGuard {
    /// Try to receive a forwarded message without blocking.
    pub fn try_recv(&self) -> Option<String> {
        #[cfg(unix)]
        {
            self.inner.receiver.try_recv().ok()
        }
        #[cfg(not(unix))]
        {
            None
        }
    }

    /// Receive a forwarded message, waiting up to `timeout`.
    pub fn recv_timeout(&self, timeout: Duration) -> Option<String> {
        #[cfg(unix)]
        {
            match self.inner.receiver.recv_timeout(timeout) {
                Ok(message) => Some(message),
                Err(std::sync::mpsc::RecvTimeoutError::Timeout)
                | Err(std::sync::mpsc::RecvTimeoutError::Disconnected) => None,
            }
        }
        #[cfg(not(unix))]
        {
            let _ = timeout;
            None
        }
    }
}

#[cfg(unix)]
struct UnixGuard {
    socket_path: PathBuf,
    receiver: std::sync::mpsc::Receiver<String>,
    shutdown: std::sync::Arc<std::sync::atomic::AtomicBool>,
    thread: Option<std::thread::JoinHandle<()>>,
}

#[cfg(unix)]
impl Drop for InstanceGuard {
    fn drop(&mut self) {
        self.inner
            .shutdown
            .store(true, std::sync::atomic::Ordering::Relaxed);
        if let Some(thread) = self.inner.thread.take() {
            let _ = thread.join();
        }
        let _ = std::fs::remove_file(&self.inner.socket_path);
    }
}

/// A Unix-domain-socket single-instance backend.
#[cfg(unix)]
#[derive(Debug, Clone)]
pub struct UnixSingleInstance {
    socket_path: PathBuf,
}

#[cfg(unix)]
impl UnixSingleInstance {
    /// Create a backend at `socket_path`.
    pub fn new(socket_path: impl Into<PathBuf>) -> Self {
        Self {
            socket_path: socket_path.into(),
        }
    }

    /// The default per-user socket path.
    pub fn default_path() -> PathBuf {
        let base = std::env::var("XDG_RUNTIME_DIR")
            .ok()
            .filter(|value| !value.is_empty())
            .map(PathBuf::from)
            .unwrap_or_else(std::env::temp_dir);
        let user = std::env::var("USER").unwrap_or_else(|_| "user".to_owned());
        let user: String = user
            .chars()
            .filter(|character| {
                character.is_ascii_alphanumeric() || *character == '-' || *character == '_'
            })
            .collect();
        let user = if user.is_empty() {
            "user".to_owned()
        } else {
            user
        };
        base.join(format!("chatui-{user}.sock"))
    }

    /// The socket path.
    pub fn socket_path(&self) -> &std::path::Path {
        &self.socket_path
    }
}

#[cfg(unix)]
impl SingleInstance for UnixSingleInstance {
    fn acquire(&self) -> Result<InstanceGuard, SingleInstanceError> {
        use std::os::unix::net::{UnixListener, UnixStream};

        match UnixListener::bind(&self.socket_path) {
            Ok(listener) => InstanceGuard::from_listener(self.socket_path.clone(), listener),
            Err(error) if error.kind() == std::io::ErrorKind::AddrInUse => {
                if UnixStream::connect(&self.socket_path).is_ok() {
                    return Err(SingleInstanceError::AlreadyRunning);
                }
                let _ = std::fs::remove_file(&self.socket_path);
                let listener = UnixListener::bind(&self.socket_path)?;
                InstanceGuard::from_listener(self.socket_path.clone(), listener)
            }
            Err(error) => Err(SingleInstanceError::Io(error)),
        }
    }

    fn forward(&self, message: &str) -> Result<(), SingleInstanceError> {
        use std::io::Write;
        use std::os::unix::net::UnixStream;

        let mut stream = UnixStream::connect(&self.socket_path)?;
        stream.write_all(message.as_bytes())?;
        stream.flush()?;
        Ok(())
    }
}

#[cfg(unix)]
impl InstanceGuard {
    fn from_listener(
        socket_path: PathBuf,
        listener: std::os::unix::net::UnixListener,
    ) -> Result<Self, SingleInstanceError> {
        use std::io::Read;
        use std::sync::Arc;
        use std::sync::atomic::{AtomicBool, Ordering};
        use std::sync::mpsc;

        listener.set_nonblocking(true)?;
        let (sender, receiver) = mpsc::channel();
        let shutdown = Arc::new(AtomicBool::new(false));
        let thread_shutdown = Arc::clone(&shutdown);
        let thread = std::thread::spawn(move || {
            while !thread_shutdown.load(Ordering::Relaxed) {
                match listener.accept() {
                    Ok((mut stream, _)) => {
                        let mut message = String::new();
                        let _ = stream.read_to_string(&mut message);
                        if !message.is_empty() {
                            let _ = sender.send(message);
                        }
                    }
                    Err(error) if error.kind() == std::io::ErrorKind::WouldBlock => {
                        std::thread::sleep(Duration::from_millis(25));
                    }
                    Err(_) => break,
                }
            }
        });

        Ok(Self {
            inner: UnixGuard {
                socket_path,
                receiver,
                shutdown,
                thread: Some(thread),
            },
        })
    }
}

/// A backend for platforms without a single-instance implementation.
#[derive(Debug, Clone, Copy, Default)]
pub struct NoopSingleInstance;

impl SingleInstance for NoopSingleInstance {
    fn acquire(&self) -> Result<InstanceGuard, SingleInstanceError> {
        Err(SingleInstanceError::Unsupported)
    }

    fn forward(&self, _message: &str) -> Result<(), SingleInstanceError> {
        Err(SingleInstanceError::Unsupported)
    }
}

/// A single-instance error.
#[derive(Debug)]
pub enum SingleInstanceError {
    /// Another instance already owns the socket.
    AlreadyRunning,
    /// The platform is not supported.
    Unsupported,
    /// Local IPC error.
    Io(std::io::Error),
}

impl std::fmt::Display for SingleInstanceError {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::AlreadyRunning => formatter.write_str("another ChatUI instance is running"),
            Self::Unsupported => formatter.write_str("single-instance forwarding is unsupported"),
            Self::Io(error) => write!(formatter, "single-instance I/O error: {error}"),
        }
    }
}

impl std::error::Error for SingleInstanceError {}

impl From<std::io::Error> for SingleInstanceError {
    fn from(error: std::io::Error) -> Self {
        Self::Io(error)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn noop_reports_unsupported() {
        let backend = NoopSingleInstance;
        assert!(matches!(
            backend.acquire(),
            Err(SingleInstanceError::Unsupported)
        ));
        assert!(matches!(
            backend.forward("chatui://chat/abc"),
            Err(SingleInstanceError::Unsupported)
        ));
    }

    #[cfg(unix)]
    #[test]
    fn acquire_creates_a_socket() {
        let directory = tempfile::tempdir().expect("tempdir");
        let path = directory.path().join("chatui.sock");
        let backend = UnixSingleInstance::new(&path);
        let guard = backend.acquire().expect("acquire");
        assert!(path.exists());
        drop(guard);
        assert!(!path.exists());
    }

    #[cfg(unix)]
    #[test]
    fn second_acquire_reports_already_running() {
        let directory = tempfile::tempdir().expect("tempdir");
        let path = directory.path().join("chatui.sock");
        let backend = UnixSingleInstance::new(&path);
        let _guard = backend.acquire().expect("first acquire");
        assert!(matches!(
            backend.acquire(),
            Err(SingleInstanceError::AlreadyRunning)
        ));
    }

    #[cfg(unix)]
    #[test]
    fn forward_delivers_a_message() {
        let directory = tempfile::tempdir().expect("tempdir");
        let path = directory.path().join("chatui.sock");
        let backend = UnixSingleInstance::new(&path);
        let guard = backend.acquire().expect("acquire");
        backend
            .forward("chatui://chat/warm-start")
            .expect("forward");
        assert_eq!(
            guard.recv_timeout(Duration::from_secs(2)),
            Some("chatui://chat/warm-start".to_owned())
        );
    }

    #[cfg(unix)]
    #[test]
    fn stale_socket_is_replaced() {
        let directory = tempfile::tempdir().expect("tempdir");
        let path = directory.path().join("chatui.sock");
        std::fs::write(&path, b"stale").expect("write stale file");
        let backend = UnixSingleInstance::new(&path);
        let guard = backend.acquire().expect("acquire over stale file");
        assert!(path.exists());
        drop(guard);
    }
}
