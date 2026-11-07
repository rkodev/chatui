//! Cancellation primitives shared by every provider request.
//!
//! The desktop client uses [`tokio_util::sync::CancellationToken`] so a user
//! stop, a window close, or the inactivity watchdog can cancel a request and
//! every stream read that races the token. [`Cancellation`] is a small,
//! cloneable wrapper that keeps the token type out of adapter signatures.

pub use tokio_util::sync::CancellationToken;

/// A cloneable cancellation handle.
///
/// Clones share the same underlying token. Cancelling any clone cancels every
/// clone and every child token.
#[derive(Debug, Clone, Default)]
pub struct Cancellation {
    token: CancellationToken,
}

impl Cancellation {
    /// Create a new, uncancelled handle.
    pub fn new() -> Self {
        Self::default()
    }

    /// Return a clone of the underlying token.
    pub fn token(&self) -> CancellationToken {
        self.token.clone()
    }

    /// Cancel the request.
    pub fn cancel(&self) {
        self.token.cancel();
    }

    /// Return whether the request has been cancelled.
    pub fn is_cancelled(&self) -> bool {
        self.token.is_cancelled()
    }

    /// Wait until the request is cancelled.
    pub async fn cancelled(&self) {
        self.token.cancelled().await;
    }

    /// Create a child handle that is cancelled when this handle is cancelled.
    pub fn child(&self) -> Self {
        Self {
            token: self.token.child_token(),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn clones_share_cancellation() {
        let cancellation = Cancellation::new();
        let clone = cancellation.clone();

        assert!(!cancellation.is_cancelled());
        clone.cancel();
        assert!(cancellation.is_cancelled());
        assert!(clone.is_cancelled());
    }

    #[tokio::test]
    async fn cancelled_future_resolves_after_cancel() {
        let cancellation = Cancellation::new();
        let cancel_for_task = cancellation.clone();

        tokio::spawn(async move {
            tokio::time::sleep(std::time::Duration::from_millis(10)).await;
            cancel_for_task.cancel();
        });

        tokio::time::timeout(std::time::Duration::from_secs(1), cancellation.cancelled())
            .await
            .expect("cancelled future should resolve");
    }

    #[tokio::test]
    async fn child_is_cancelled_with_parent() {
        let parent = Cancellation::new();
        let child = parent.child();

        parent.cancel();
        assert!(child.is_cancelled());
    }

    #[tokio::test]
    async fn cancelling_child_does_not_cancel_parent() {
        let parent = Cancellation::new();
        let child = parent.child();

        child.cancel();
        assert!(child.is_cancelled());
        assert!(!parent.is_cancelled());
    }
}
