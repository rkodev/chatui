//! Shared retry policy, abort-aware sleep, and inactivity watchdog.
//!
//! The policy mirrors section 7.9 of the implementation spec: at most three
//! attempts including the first, exponential backoff with positive jitter, an
//! 800 ms base, an 8 s cap, and a provider `Retry-After` value that wins over
//! the local backoff. A request is never retried after text or reasoning has
//! been emitted.
//!
//! This module is provider-agnostic. Adapters decide which failures are
//! retryable and report whether they emitted partial output; the helpers here
//! only compute delays, sleep, and enforce the attempt limit.

use std::future::Future;
use std::sync::Mutex;
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::Duration;

use thiserror::Error;
use tokio::sync::watch;
use tokio::time::Instant;
use tokio_util::sync::CancellationToken;

use crate::error::{NetworkError, parse_retry_after};

/// Maximum number of attempts, including the first request.
pub const RETRY_MAX_ATTEMPTS: u32 = 3;

/// First backoff delay before jitter.
pub const RETRY_BASE_DELAY: Duration = Duration::from_millis(800);

/// Upper bound for a computed backoff delay.
pub const RETRY_MAX_DELAY: Duration = Duration::from_secs(8);

/// Fraction of the exponential delay added as positive jitter.
pub const RETRY_JITTER_RATIO: f64 = 0.25;

/// Default inactivity timeout for a streaming response.
pub const DEFAULT_INACTIVITY_TIMEOUT: Duration = Duration::from_secs(120);

/// Retry timing policy.
#[derive(Debug, Clone, PartialEq)]
pub struct RetryPolicy {
    /// Maximum attempts including the first request.
    pub max_attempts: u32,
    /// First backoff delay before jitter.
    pub base_delay: Duration,
    /// Upper bound for a computed backoff delay.
    pub max_delay: Duration,
    /// Fraction of the exponential delay added as positive jitter.
    pub jitter_ratio: f64,
}

impl Default for RetryPolicy {
    fn default() -> Self {
        Self {
            max_attempts: RETRY_MAX_ATTEMPTS,
            base_delay: RETRY_BASE_DELAY,
            max_delay: RETRY_MAX_DELAY,
            jitter_ratio: RETRY_JITTER_RATIO,
        }
    }
}

/// Whether an attempt emitted text or reasoning before it failed.
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub struct PartialOutput {
    /// Text was emitted to the caller.
    pub text: bool,
    /// Reasoning was emitted to the caller.
    pub reasoning: bool,
}

impl PartialOutput {
    /// No output was emitted.
    pub fn none() -> Self {
        Self::default()
    }

    /// Text was emitted.
    pub fn text() -> Self {
        Self {
            text: true,
            reasoning: false,
        }
    }

    /// Reasoning was emitted.
    pub fn reasoning() -> Self {
        Self {
            text: false,
            reasoning: true,
        }
    }

    /// Record that text was emitted.
    pub fn mark_text(&mut self) {
        self.text = true;
    }

    /// Record that reasoning was emitted.
    pub fn mark_reasoning(&mut self) {
        self.reasoning = true;
    }

    /// Return whether any text or reasoning was emitted.
    pub fn has_any(self) -> bool {
        self.text || self.reasoning
    }
}

/// A failed attempt with the metadata the retry loop needs.
#[derive(Debug, Clone, PartialEq)]
pub struct AttemptFailure {
    /// The normalized transport error.
    pub error: NetworkError,
    /// Output emitted before the failure.
    pub partial_output: PartialOutput,
    /// Provider-supplied retry delay, when present.
    pub retry_after: Option<Duration>,
}

impl AttemptFailure {
    /// Build a failure with no partial output and no `Retry-After`.
    pub fn new(error: NetworkError) -> Self {
        Self {
            error,
            partial_output: PartialOutput::none(),
            retry_after: None,
        }
    }

    /// Attach the partial-output state observed before the failure.
    pub fn with_partial_output(mut self, partial_output: PartialOutput) -> Self {
        self.partial_output = partial_output;
        self
    }

    /// Attach a provider-supplied retry delay.
    pub fn with_retry_after(mut self, retry_after: Option<Duration>) -> Self {
        self.retry_after = retry_after;
        self
    }
}

/// The result of one attempt.
#[derive(Debug, Clone, PartialEq)]
pub enum AttemptOutcome<T> {
    /// The attempt succeeded.
    Success(T),
    /// The attempt failed.
    Failed(AttemptFailure),
}

/// A retry-loop failure.
#[derive(Debug, Clone, PartialEq, Error)]
pub enum RetryError {
    /// The caller cancelled the request or the retry sleep.
    #[error("retry was cancelled")]
    Cancelled,
    /// The final attempt failed, or the failure was not retryable.
    #[error(transparent)]
    Network(#[from] NetworkError),
}

/// Compute a jittered exponential backoff delay using the default jitter source.
///
/// `attempt` is 1-based and refers to the attempt that just failed. The first
/// retry starts at [`RetryPolicy::base_delay`] plus up to
/// [`RetryPolicy::jitter_ratio`] positive jitter, doubles each attempt, and
/// never exceeds [`RetryPolicy::max_delay`].
pub fn compute_retry_delay(attempt: u32, policy: &RetryPolicy) -> Duration {
    compute_retry_delay_with_jitter(attempt, policy, default_jitter())
}

/// Compute a jittered exponential backoff delay with an injected jitter value.
///
/// `jitter` is clamped to `[0, 1)`. Tests use `0.0` for the lower bound and a
/// value just below `1.0` for the upper bound.
pub fn compute_retry_delay_with_jitter(
    attempt: u32,
    policy: &RetryPolicy,
    jitter: f64,
) -> Duration {
    let exponent = attempt.saturating_sub(1);
    let exponential = policy
        .base_delay
        .saturating_mul(1u32.checked_shl(exponent).unwrap_or(u32::MAX))
        .min(policy.max_delay);
    let jitter = jitter.clamp(0.0, 1.0);
    let jittered = exponential.mul_f64(1.0 + policy.jitter_ratio * jitter);

    jittered.min(policy.max_delay)
}

/// Choose the delay for a retry.
///
/// A provider-supplied `Retry-After` value wins over the local backoff policy.
pub fn retry_delay_for(
    retry_after: Option<Duration>,
    attempt: u32,
    policy: &RetryPolicy,
) -> Duration {
    retry_after.unwrap_or_else(|| compute_retry_delay(attempt, policy))
}

/// Parse a `Retry-After` header value expressed as whole seconds.
///
/// HTTP-date values are not supported; returning `None` means "no usable
/// delay" and the local backoff policy applies.
pub fn retry_after_from_header(value: &str) -> Option<Duration> {
    parse_retry_after(value).map(Duration::from_secs_f64)
}

/// Sleep for `duration`, returning [`RetryError::Cancelled`] if `cancel` fires.
///
/// The sleep races the cancellation token, so a cancelled request does not wait
/// for the full backoff delay.
pub async fn sleep(duration: Duration, cancel: &CancellationToken) -> Result<(), RetryError> {
    if cancel.is_cancelled() {
        return Err(RetryError::Cancelled);
    }

    tokio::select! {
        biased;
        _ = cancel.cancelled() => Err(RetryError::Cancelled),
        _ = tokio::time::sleep(duration) => Ok(()),
    }
}

/// Run `attempt` under the retry policy.
///
/// `attempt` receives the 1-based attempt number. The loop stops immediately
/// when the attempt reports partial output, when the error is not retryable,
/// when the attempt limit is reached, or when `cancel` fires. A provider
/// `Retry-After` value overrides the local backoff for that retry.
pub async fn retry_with<F, Fut, T>(
    policy: &RetryPolicy,
    cancel: &CancellationToken,
    mut attempt: F,
) -> Result<T, RetryError>
where
    F: FnMut(u32) -> Fut,
    Fut: Future<Output = AttemptOutcome<T>>,
{
    let max_attempts = policy.max_attempts.max(1);

    for attempt_number in 1..=max_attempts {
        if cancel.is_cancelled() {
            return Err(RetryError::Cancelled);
        }

        match attempt(attempt_number).await {
            AttemptOutcome::Success(value) => return Ok(value),
            AttemptOutcome::Failed(failure) => {
                if cancel.is_cancelled() {
                    return Err(RetryError::Cancelled);
                }

                let exhausted = attempt_number >= max_attempts;
                if failure.partial_output.has_any() || !failure.error.retryable || exhausted {
                    return Err(RetryError::Network(failure.error));
                }

                let delay = retry_delay_for(failure.retry_after, attempt_number, policy);
                sleep(delay, cancel).await?;
            }
        }
    }

    unreachable!("the retry loop always returns on the final attempt")
}

/// An inactivity watchdog for a streaming response.
///
/// Call [`InactivityWatchdog::reset`] on every received chunk and race
/// [`InactivityWatchdog::expired`] against the next stream read. When the
/// timeout elapses, `expired` cancels the shared token and records
/// [`InactivityWatchdog::timed_out`] so the caller can return a retryable
/// [`NetworkError::timeout`] instead of a user cancellation.
#[derive(Debug)]
pub struct InactivityWatchdog {
    timeout: Duration,
    cancel: CancellationToken,
    last_activity: Mutex<Instant>,
    activity: watch::Sender<u64>,
    timed_out: AtomicBool,
}

impl InactivityWatchdog {
    /// Build a watchdog with an explicit timeout.
    pub fn new(timeout: Duration, cancel: CancellationToken) -> Self {
        let (activity, _receiver) = watch::channel(0u64);
        Self {
            timeout,
            cancel,
            last_activity: Mutex::new(Instant::now()),
            activity,
            timed_out: AtomicBool::new(false),
        }
    }

    /// Build a watchdog with the 120 second default timeout.
    pub fn with_default_timeout(cancel: CancellationToken) -> Self {
        Self::new(DEFAULT_INACTIVITY_TIMEOUT, cancel)
    }

    /// Return the configured inactivity timeout.
    pub fn timeout(&self) -> Duration {
        self.timeout
    }

    /// Return whether the watchdog has fired.
    pub fn timed_out(&self) -> bool {
        self.timed_out.load(Ordering::SeqCst)
    }

    /// Record activity and restart the inactivity window.
    pub fn reset(&self) {
        *self
            .last_activity
            .lock()
            .expect("watchdog activity mutex poisoned") = Instant::now();

        let next = {
            let current = self.activity.borrow();
            *current + 1
        };
        let _ = self.activity.send(next);
    }

    /// Wait until the inactivity timeout elapses.
    ///
    /// On expiry this cancels the shared token and sets
    /// [`InactivityWatchdog::timed_out`]. The caller should return
    /// [`NetworkError::timeout`], which is retryable.
    pub async fn expired(&self) {
        let mut activity = self.activity.subscribe();

        loop {
            let last = *self
                .last_activity
                .lock()
                .expect("watchdog activity mutex poisoned");
            let deadline = last + self.timeout;
            let now = Instant::now();

            if now >= deadline {
                self.timed_out.store(true, Ordering::SeqCst);
                self.cancel.cancel();
                return;
            }

            tokio::select! {
                _ = tokio::time::sleep(deadline - now) => {}
                changed = activity.changed() => {
                    if changed.is_err() {
                        return;
                    }
                }
            }
        }
    }
}

/// Return a pseudo-random jitter value in `[0, 1)`.
///
/// The value only needs to spread retries; it is not used for security. Tests
/// inject a deterministic value through [`compute_retry_delay_with_jitter`].
fn default_jitter() -> f64 {
    use std::time::{SystemTime, UNIX_EPOCH};

    let nanos = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|duration| duration.subsec_nanos())
        .unwrap_or(0);
    let mixed = nanos.wrapping_mul(1_664_525).wrapping_add(1_013_904_223);

    f64::from(mixed >> 8) / f64::from(1u32 << 24)
}

#[cfg(test)]
mod tests {
    use std::sync::Arc;
    use std::sync::atomic::{AtomicU32, Ordering};

    use super::*;
    use crate::error::NetworkErrorKind;

    fn fast_policy() -> RetryPolicy {
        RetryPolicy {
            base_delay: Duration::from_millis(1),
            max_delay: Duration::from_millis(2),
            ..RetryPolicy::default()
        }
    }

    #[test]
    fn retry_policy_defaults_match_spec() {
        let policy = RetryPolicy::default();
        assert_eq!(policy.max_attempts, 3);
        assert_eq!(policy.base_delay, Duration::from_millis(800));
        assert_eq!(policy.max_delay, Duration::from_secs(8));
        assert_eq!(policy.jitter_ratio, 0.25);
    }

    #[test]
    fn jitter_stays_within_documented_bounds() {
        let policy = RetryPolicy::default();

        for attempt in 1..=6 {
            let low = compute_retry_delay_with_jitter(attempt, &policy, 0.0);
            let high = compute_retry_delay_with_jitter(attempt, &policy, 0.999_999);
            let exponential = policy
                .base_delay
                .saturating_mul(1u32.checked_shl(attempt - 1).unwrap_or(u32::MAX))
                .min(policy.max_delay);

            assert!(low >= exponential, "attempt {attempt} low {low:?}");
            assert!(high >= low, "attempt {attempt} high {high:?} low {low:?}");
            assert!(
                high <= policy.max_delay,
                "attempt {attempt} high {high:?} exceeds cap"
            );
            assert!(
                high <= exponential.mul_f64(1.0 + policy.jitter_ratio),
                "attempt {attempt} high {high:?} exceeds jitter bound"
            );
        }
    }

    #[test]
    fn retry_after_overrides_local_backoff() {
        let policy = fast_policy();
        let retry_after = Some(Duration::from_secs(2));

        assert_eq!(
            retry_delay_for(retry_after, 1, &policy),
            Duration::from_secs(2)
        );

        let local = retry_delay_for(None, 1, &policy);
        assert!(local >= policy.base_delay);
        assert!(local <= policy.max_delay);
    }

    #[test]
    fn parses_retry_after_header_seconds() {
        assert_eq!(retry_after_from_header("3"), Some(Duration::from_secs(3)));
        assert_eq!(
            retry_after_from_header(" 12 "),
            Some(Duration::from_secs(12))
        );
        assert_eq!(
            retry_after_from_header("Wed, 21 Oct 2015 07:28:00 GMT"),
            None
        );
        assert_eq!(retry_after_from_header(""), None);
    }

    #[tokio::test]
    async fn retry_stops_after_three_attempts() {
        let policy = fast_policy();
        let cancel = CancellationToken::new();
        let attempts = Arc::new(AtomicU32::new(0));
        let attempts_for_closure = Arc::clone(&attempts);

        let result: Result<(), RetryError> = retry_with(&policy, &cancel, move |_| {
            let attempts = Arc::clone(&attempts_for_closure);
            async move {
                attempts.fetch_add(1, Ordering::SeqCst);
                AttemptOutcome::Failed(AttemptFailure::new(NetworkError::timeout()))
            }
        })
        .await;

        assert!(matches!(
            result,
            Err(RetryError::Network(error)) if error.kind == NetworkErrorKind::Timeout
        ));
        assert_eq!(attempts.load(Ordering::SeqCst), 3);
    }

    #[tokio::test]
    async fn no_retry_after_partial_output() {
        let policy = fast_policy();
        let cancel = CancellationToken::new();
        let attempts = Arc::new(AtomicU32::new(0));
        let attempts_for_closure = Arc::clone(&attempts);

        let result: Result<(), RetryError> = retry_with(&policy, &cancel, move |_| {
            let attempts = Arc::clone(&attempts_for_closure);
            async move {
                attempts.fetch_add(1, Ordering::SeqCst);
                AttemptOutcome::Failed(
                    AttemptFailure::new(NetworkError::timeout())
                        .with_partial_output(PartialOutput::text()),
                )
            }
        })
        .await;

        assert!(matches!(result, Err(RetryError::Network(_))));
        assert_eq!(attempts.load(Ordering::SeqCst), 1);
    }

    #[tokio::test]
    async fn no_retry_for_non_retryable_error() {
        let policy = fast_policy();
        let cancel = CancellationToken::new();
        let attempts = Arc::new(AtomicU32::new(0));
        let attempts_for_closure = Arc::clone(&attempts);

        let result: Result<(), RetryError> = retry_with(&policy, &cancel, move |_| {
            let attempts = Arc::clone(&attempts_for_closure);
            async move {
                attempts.fetch_add(1, Ordering::SeqCst);
                AttemptOutcome::Failed(AttemptFailure::new(NetworkError::from_status(401, None)))
            }
        })
        .await;

        assert!(matches!(
            result,
            Err(RetryError::Network(error)) if error.status == Some(401)
        ));
        assert_eq!(attempts.load(Ordering::SeqCst), 1);
    }

    #[tokio::test]
    async fn cancellation_interrupts_sleep() {
        let cancel = CancellationToken::new();
        let cancel_for_task = cancel.clone();

        tokio::spawn(async move {
            tokio::time::sleep(Duration::from_millis(10)).await;
            cancel_for_task.cancel();
        });

        let result = tokio::time::timeout(
            Duration::from_secs(1),
            sleep(Duration::from_secs(30), &cancel),
        )
        .await
        .expect("sleep should be interrupted by cancellation");

        assert_eq!(result, Err(RetryError::Cancelled));
    }

    #[tokio::test]
    async fn cancellation_interrupts_retry_loop() {
        let policy = RetryPolicy {
            base_delay: Duration::from_secs(30),
            max_delay: Duration::from_secs(30),
            ..RetryPolicy::default()
        };
        let cancel = CancellationToken::new();
        let cancel_for_task = cancel.clone();

        tokio::spawn(async move {
            tokio::time::sleep(Duration::from_millis(10)).await;
            cancel_for_task.cancel();
        });

        let result: Result<(), RetryError> = tokio::time::timeout(
            Duration::from_secs(1),
            retry_with(&policy, &cancel, |_| async {
                AttemptOutcome::Failed(AttemptFailure::new(NetworkError::timeout()))
            }),
        )
        .await
        .expect("retry loop should stop when cancelled");

        assert_eq!(result, Err(RetryError::Cancelled));
    }

    #[tokio::test]
    async fn watchdog_fires_with_retryable_timeout() {
        let cancel = CancellationToken::new();
        let watchdog = InactivityWatchdog::new(Duration::from_millis(20), cancel.clone());

        tokio::time::timeout(Duration::from_secs(1), watchdog.expired())
            .await
            .expect("watchdog should fire");

        assert!(watchdog.timed_out());
        assert!(cancel.is_cancelled());
        assert!(NetworkError::timeout().retryable);
    }

    #[tokio::test]
    async fn watchdog_resets_on_activity() {
        let cancel = CancellationToken::new();
        let watchdog = Arc::new(InactivityWatchdog::new(
            Duration::from_millis(50),
            cancel.clone(),
        ));
        let watchdog_for_task = Arc::clone(&watchdog);

        let resetter = tokio::spawn(async move {
            for _ in 0..5 {
                tokio::time::sleep(Duration::from_millis(10)).await;
                watchdog_for_task.reset();
            }
        });

        resetter.await.expect("resetter should finish");
        assert!(
            !cancel.is_cancelled(),
            "activity should keep the watchdog alive"
        );

        tokio::time::timeout(Duration::from_secs(1), watchdog.expired())
            .await
            .expect("watchdog should fire after activity stops");

        assert!(watchdog.timed_out());
        assert!(cancel.is_cancelled());
    }

    #[test]
    fn partial_output_tracks_text_and_reasoning() {
        let mut partial = PartialOutput::none();
        assert!(!partial.has_any());

        partial.mark_text();
        assert!(partial.has_any());
        assert!(partial.text);
        assert!(!partial.reasoning);

        let mut partial = PartialOutput::reasoning();
        partial.mark_text();
        assert!(partial.text);
        assert!(partial.reasoning);
    }
}
