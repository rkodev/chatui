//! Foreground backup scheduling for the desktop app.
//!
//! The desktop app has no OS service or background daemon. While the app is
//! running, [`BackupScheduler`] ticks on a `tokio` interval and calls the
//! encrypted [`BackupService`] when a backup is due. When the app closes, the
//! runtime and the interval task stop, so no backup runs.
//!
//! The scheduler only depends on [`BackupService::backup_now`]. It never reads
//! conversation content and never talks to a storage adapter directly, so it
//! cannot introduce a plaintext upload path.
//!
//! The passphrase is never persisted. [`ScheduledBackupService`] keeps it in
//! memory for the current session only, so an automatic run after a restart
//! skips until the user enters the passphrase again.

use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use chatui_secrets::SecretStore;
use chatui_ui::settings::{BackupError, BackupForm, BackupService, BackupStatus, RestoreOutcome};
use tokio::runtime::Handle;
use tokio::task::JoinHandle;

use crate::services::ServiceRuntime;
use crate::services::backup::BackupServiceAdapter;

/// Foreground tick interval. Matches the web client's five-minute cadence.
pub const DEFAULT_INTERVAL: Duration = Duration::from_secs(5 * 60);

/// Minimum time between automatic runs. Matches the web client's due window.
pub const DEFAULT_DUE_AFTER: Duration = Duration::from_secs(15 * 60);

/// A clock used to decide whether a backup is due.
///
/// Tests inject a fake clock; production uses [`Instant::now`].
pub type NowFn = Arc<dyn Fn() -> Instant + Send + Sync>;

/// Result of a due check.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum BackupRunOutcome {
    /// The backup ran successfully.
    Ran,
    /// The due window has not elapsed since the last successful run.
    NotDue,
    /// Backup is disabled, so the scheduler refused to run.
    Disabled,
    /// No passphrase is available in memory for this session.
    NoPassphrase,
    /// Another due check is already running.
    Busy,
    /// The backup failed. The message is safe to show in the UI.
    Failed(String),
}

struct SchedulerState {
    enabled: bool,
    passphrase: Option<String>,
    last_run: Option<Instant>,
    handle: Option<JoinHandle<()>>,
}

struct SchedulerInner {
    runner: Arc<dyn BackupService>,
    runtime: Handle,
    interval: Duration,
    due_after: Duration,
    now: NowFn,
    running: AtomicBool,
    state: Mutex<SchedulerState>,
}

/// A foreground, interval-based backup scheduler.
///
/// `schedule()` is idempotent. `cancel()` stops future ticks and makes
/// `run_if_due()` refuse to run. An in-flight blocking backup may still finish
/// after `cancel()`; it cannot start a new one.
#[derive(Clone)]
pub struct BackupScheduler {
    inner: Arc<SchedulerInner>,
}

impl BackupScheduler {
    /// Create a scheduler over the shared desktop runtime.
    pub fn new(
        runtime: Handle,
        runner: Arc<dyn BackupService>,
        interval: Duration,
        due_after: Duration,
    ) -> Self {
        Self::with_clock(runtime, runner, interval, due_after, Arc::new(Instant::now))
    }

    /// Create a scheduler with an injected clock. Tests use this.
    pub fn with_clock(
        runtime: Handle,
        runner: Arc<dyn BackupService>,
        interval: Duration,
        due_after: Duration,
        now: NowFn,
    ) -> Self {
        Self {
            inner: Arc::new(SchedulerInner {
                runner,
                runtime,
                interval,
                due_after,
                now,
                running: AtomicBool::new(false),
                state: Mutex::new(SchedulerState {
                    enabled: false,
                    passphrase: None,
                    last_run: None,
                    handle: None,
                }),
            }),
        }
    }

    /// Start the foreground interval. Calling this twice does not duplicate it.
    pub fn schedule(&self) {
        let mut state = self.lock();
        state.enabled = true;
        if state.handle.is_some() {
            return;
        }

        let scheduler = self.clone();
        let interval = self.inner.interval;
        let handle = self.inner.runtime.spawn(async move {
            let start = tokio::time::Instant::now() + interval;
            let mut ticker = tokio::time::interval_at(start, interval);
            ticker.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);
            loop {
                ticker.tick().await;
                let scheduler = scheduler.clone();
                // `BackupService::backup_now` blocks on the shared runtime, so
                // it must not run on a runtime worker thread.
                let _ = tokio::task::spawn_blocking(move || scheduler.run_if_due()).await;
            }
        });
        state.handle = Some(handle);
    }

    /// Stop the foreground interval and refuse future due checks.
    pub fn cancel(&self) {
        let mut state = self.lock();
        state.enabled = false;
        if let Some(handle) = state.handle.take() {
            handle.abort();
        }
    }

    /// Whether an interval task is currently scheduled.
    pub fn is_scheduled(&self) -> bool {
        self.lock().handle.is_some()
    }

    /// Store the in-memory passphrase for automatic runs.
    pub fn set_passphrase(&self, passphrase: Option<String>) {
        self.lock().passphrase = passphrase;
    }

    /// Forget the in-memory passphrase.
    pub fn clear_passphrase(&self) {
        self.lock().passphrase = None;
    }

    /// Run a backup when enabled and the due window has elapsed.
    ///
    /// This is synchronous because the desktop service boundary is synchronous.
    /// The interval task calls it through `spawn_blocking`.
    pub fn run_if_due(&self) -> BackupRunOutcome {
        if self.inner.running.swap(true, Ordering::SeqCst) {
            return BackupRunOutcome::Busy;
        }

        let outcome = self.run_if_due_inner();
        self.inner.running.store(false, Ordering::SeqCst);
        outcome
    }

    fn run_if_due_inner(&self) -> BackupRunOutcome {
        let (enabled, passphrase, last_run) = {
            let state = self.lock();
            (state.enabled, state.passphrase.clone(), state.last_run)
        };

        if !enabled {
            return BackupRunOutcome::Disabled;
        }

        let now = (self.inner.now)();
        if let Some(last_run) = last_run
            && now.saturating_duration_since(last_run) < self.inner.due_after
        {
            return BackupRunOutcome::NotDue;
        }

        let Some(passphrase) = passphrase else {
            return BackupRunOutcome::NoPassphrase;
        };

        match self.inner.runner.backup_now(&passphrase) {
            Ok(_) => {
                self.lock().last_run = Some((self.inner.now)());
                BackupRunOutcome::Ran
            }
            Err(error) => BackupRunOutcome::Failed(error.message().to_owned()),
        }
    }

    fn lock(&self) -> std::sync::MutexGuard<'_, SchedulerState> {
        self.inner
            .state
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
    }
}

/// A [`BackupService`] that schedules automatic backups while the app is open.
///
/// Enabling backup starts the scheduler and remembers the passphrase for the
/// session. Disabling backup cancels the scheduler and forgets the passphrase.
/// Manual actions also refresh the in-memory passphrase so a later automatic
/// run can use it.
pub struct ScheduledBackupService {
    inner: Arc<dyn BackupService>,
    scheduler: BackupScheduler,
}

impl ScheduledBackupService {
    /// Wrap a backup service with a scheduler.
    pub fn new(inner: Arc<dyn BackupService>, scheduler: BackupScheduler) -> Self {
        Self { inner, scheduler }
    }

    /// Build the production service over the shared desktop runtime.
    pub fn from_runtime(runtime: &ServiceRuntime, secrets: Arc<dyn SecretStore>) -> Self {
        let inner: Arc<dyn BackupService> =
            Arc::new(BackupServiceAdapter::from_runtime(runtime, secrets));
        let scheduler = BackupScheduler::new(
            runtime.handle(),
            Arc::clone(&inner),
            DEFAULT_INTERVAL,
            DEFAULT_DUE_AFTER,
        );
        Self::new(inner, scheduler)
    }

    /// The scheduler, exposed for status and tests.
    pub fn scheduler(&self) -> &BackupScheduler {
        &self.scheduler
    }

    /// Start scheduling when the persisted configuration is enabled.
    ///
    /// Call this once when the backup settings screen loads. It returns whether
    /// backup is enabled. A session without a passphrase still schedules, but
    /// automatic runs skip with [`BackupRunOutcome::NoPassphrase`] until the
    /// user enters the passphrase.
    pub fn start_if_enabled(&self) -> Result<bool, BackupError> {
        let enabled = self.inner.config()?.is_some_and(|config| config.enabled);
        if enabled {
            self.scheduler.schedule();
        } else {
            self.scheduler.cancel();
        }
        Ok(enabled)
    }
}

impl BackupService for ScheduledBackupService {
    fn config(&self) -> Result<Option<chatui_domain::BackupConfig>, BackupError> {
        self.inner.config()
    }

    fn status(&self) -> Result<BackupStatus, BackupError> {
        self.inner.status()
    }

    fn test_connection(&self, form: &BackupForm) -> Result<(), BackupError> {
        self.inner.test_connection(form)
    }

    fn enable(&self, form: &BackupForm) -> Result<BackupStatus, BackupError> {
        let status = self.inner.enable(form)?;
        self.scheduler.set_passphrase(Some(form.passphrase.clone()));
        self.scheduler.schedule();
        Ok(status)
    }

    fn disable(&self) -> Result<BackupStatus, BackupError> {
        let status = self.inner.disable()?;
        self.scheduler.cancel();
        self.scheduler.clear_passphrase();
        Ok(status)
    }

    fn backup_now(&self, passphrase: &str) -> Result<BackupStatus, BackupError> {
        self.scheduler.set_passphrase(Some(passphrase.to_owned()));
        self.inner.backup_now(passphrase)
    }

    fn restore(&self, passphrase: &str, replace: bool) -> Result<RestoreOutcome, BackupError> {
        self.scheduler.set_passphrase(Some(passphrase.to_owned()));
        self.inner.restore(passphrase, replace)
    }

    fn delete_remote(&self, passphrase: &str, confirmation: &str) -> Result<(), BackupError> {
        self.scheduler.set_passphrase(Some(passphrase.to_owned()));
        self.inner.delete_remote(passphrase, confirmation)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::AtomicUsize;

    use chatui_domain::{BackupBackend, BackupConfig, BackupEncryption, BackupKdf};
    use chatui_secrets::SessionStore;

    struct FakeBackupService {
        enabled: AtomicBool,
        fail: AtomicBool,
        backup_calls: AtomicUsize,
        other_calls: AtomicUsize,
        last_passphrase: Mutex<Option<String>>,
    }

    impl FakeBackupService {
        fn new() -> Self {
            Self {
                enabled: AtomicBool::new(false),
                fail: AtomicBool::new(false),
                backup_calls: AtomicUsize::new(0),
                other_calls: AtomicUsize::new(0),
                last_passphrase: Mutex::new(None),
            }
        }

        fn set_enabled(&self, enabled: bool) {
            self.enabled.store(enabled, Ordering::SeqCst);
        }

        fn set_fail(&self, fail: bool) {
            self.fail.store(fail, Ordering::SeqCst);
        }

        fn backup_calls(&self) -> usize {
            self.backup_calls.load(Ordering::SeqCst)
        }

        fn other_calls(&self) -> usize {
            self.other_calls.load(Ordering::SeqCst)
        }

        fn last_passphrase(&self) -> Option<String> {
            self.last_passphrase
                .lock()
                .unwrap_or_else(|poisoned| poisoned.into_inner())
                .clone()
        }

        fn status(&self) -> BackupStatus {
            BackupStatus {
                enabled: self.enabled.load(Ordering::SeqCst),
                ..BackupStatus::default()
            }
        }

        fn config(&self) -> BackupConfig {
            BackupConfig {
                enabled: self.enabled.load(Ordering::SeqCst),
                backend: BackupBackend::S3,
                endpoint: Some("https://s3.example.com".to_owned()),
                region: Some("us-east-1".to_owned()),
                bucket: "chatui-backups".to_owned(),
                prefix: "backups".to_owned(),
                credential_secret_id: None,
                encryption: BackupEncryption::Aes256Gcm,
                kdf: BackupKdf::Pbkdf2Sha256,
                kdf_salt: None,
                last_sync_at: None,
                last_manifest_etag: None,
                device_id: "device-1".to_owned(),
            }
        }
    }

    impl BackupService for FakeBackupService {
        fn config(&self) -> Result<Option<BackupConfig>, BackupError> {
            Ok(Some(self.config()))
        }

        fn status(&self) -> Result<BackupStatus, BackupError> {
            Ok(self.status())
        }

        fn test_connection(&self, _form: &BackupForm) -> Result<(), BackupError> {
            self.other_calls.fetch_add(1, Ordering::SeqCst);
            Ok(())
        }

        fn enable(&self, _form: &BackupForm) -> Result<BackupStatus, BackupError> {
            self.set_enabled(true);
            Ok(self.status())
        }

        fn disable(&self) -> Result<BackupStatus, BackupError> {
            self.set_enabled(false);
            Ok(self.status())
        }

        fn backup_now(&self, passphrase: &str) -> Result<BackupStatus, BackupError> {
            self.backup_calls.fetch_add(1, Ordering::SeqCst);
            *self
                .last_passphrase
                .lock()
                .unwrap_or_else(|poisoned| poisoned.into_inner()) = Some(passphrase.to_owned());
            if self.fail.load(Ordering::SeqCst) {
                return Err(BackupError::Storage("network down".to_owned()));
            }
            Ok(self.status())
        }

        fn restore(
            &self,
            _passphrase: &str,
            _replace: bool,
        ) -> Result<RestoreOutcome, BackupError> {
            self.other_calls.fetch_add(1, Ordering::SeqCst);
            Ok(RestoreOutcome::default())
        }

        fn delete_remote(&self, _passphrase: &str, _confirmation: &str) -> Result<(), BackupError> {
            self.other_calls.fetch_add(1, Ordering::SeqCst);
            Ok(())
        }
    }

    fn form() -> BackupForm {
        BackupForm {
            backend: BackupBackend::S3,
            endpoint: "https://s3.example.com".to_owned(),
            region: "us-east-1".to_owned(),
            bucket: "chatui-backups".to_owned(),
            prefix: "backups".to_owned(),
            access_key_id: "AKIAEXAMPLE".to_owned(),
            secret_access_key: "super-secret".to_owned(),
            passphrase: "correct horse battery staple".to_owned(),
        }
    }

    fn fake_clock() -> (Arc<Mutex<Instant>>, NowFn) {
        let clock = Arc::new(Mutex::new(Instant::now()));
        let now = {
            let clock = Arc::clone(&clock);
            Arc::new(move || {
                *clock
                    .lock()
                    .unwrap_or_else(|poisoned| poisoned.into_inner())
            })
        };
        (clock, now)
    }

    fn scheduler(
        runtime: &tokio::runtime::Runtime,
        fake: Arc<FakeBackupService>,
        interval: Duration,
        due_after: Duration,
        now: NowFn,
    ) -> BackupScheduler {
        BackupScheduler::with_clock(runtime.handle().clone(), fake, interval, due_after, now)
    }

    fn wait_until(mut condition: impl FnMut() -> bool, timeout: Duration) -> bool {
        let start = Instant::now();
        while start.elapsed() < timeout {
            if condition() {
                return true;
            }
            std::thread::sleep(Duration::from_millis(5));
        }
        condition()
    }

    #[test]
    fn runs_when_due_and_skips_inside_the_due_window() {
        let runtime = tokio::runtime::Runtime::new().expect("runtime");
        let fake = Arc::new(FakeBackupService::new());
        let (clock, now) = fake_clock();
        let scheduler = scheduler(
            &runtime,
            Arc::clone(&fake),
            Duration::from_secs(3_600),
            DEFAULT_DUE_AFTER,
            now,
        );
        scheduler.schedule();
        scheduler.set_passphrase(Some("passphrase".to_owned()));

        assert_eq!(scheduler.run_if_due(), BackupRunOutcome::Ran);
        assert_eq!(fake.backup_calls(), 1);

        *clock.lock().expect("clock") += DEFAULT_DUE_AFTER - Duration::from_millis(1);
        assert_eq!(scheduler.run_if_due(), BackupRunOutcome::NotDue);
        assert_eq!(fake.backup_calls(), 1);

        *clock.lock().expect("clock") += Duration::from_millis(1);
        assert_eq!(scheduler.run_if_due(), BackupRunOutcome::Ran);
        assert_eq!(fake.backup_calls(), 2);
    }

    #[test]
    fn disabled_scheduler_refuses_to_run() {
        let runtime = tokio::runtime::Runtime::new().expect("runtime");
        let fake = Arc::new(FakeBackupService::new());
        let (_clock, now) = fake_clock();
        let scheduler = scheduler(
            &runtime,
            Arc::clone(&fake),
            Duration::from_secs(3_600),
            DEFAULT_DUE_AFTER,
            now,
        );
        scheduler.set_passphrase(Some("passphrase".to_owned()));

        assert_eq!(scheduler.run_if_due(), BackupRunOutcome::Disabled);
        assert_eq!(fake.backup_calls(), 0);
    }

    #[test]
    fn cancel_stops_the_interval_and_future_runs() {
        let runtime = tokio::runtime::Runtime::new().expect("runtime");
        let fake = Arc::new(FakeBackupService::new());
        let (_clock, now) = fake_clock();
        let scheduler = scheduler(
            &runtime,
            Arc::clone(&fake),
            Duration::from_millis(20),
            Duration::ZERO,
            now,
        );
        scheduler.schedule();
        scheduler.set_passphrase(Some("passphrase".to_owned()));

        assert!(
            wait_until(|| fake.backup_calls() >= 1, Duration::from_secs(2)),
            "the interval did not run a backup"
        );

        scheduler.cancel();
        assert!(!scheduler.is_scheduled());

        // Let an in-flight blocking run finish before recording the count.
        std::thread::sleep(Duration::from_millis(50));
        let count = fake.backup_calls();
        std::thread::sleep(Duration::from_millis(100));
        assert_eq!(fake.backup_calls(), count);
        assert_eq!(scheduler.run_if_due(), BackupRunOutcome::Disabled);
    }

    #[test]
    fn schedule_is_idempotent() {
        let runtime = tokio::runtime::Runtime::new().expect("runtime");
        let fake = Arc::new(FakeBackupService::new());
        let (_clock, now) = fake_clock();
        let scheduler = scheduler(
            &runtime,
            fake,
            Duration::from_secs(3_600),
            DEFAULT_DUE_AFTER,
            now,
        );

        scheduler.schedule();
        scheduler.schedule();
        assert!(scheduler.is_scheduled());
    }

    #[test]
    fn missing_passphrase_skips_without_calling_the_service() {
        let runtime = tokio::runtime::Runtime::new().expect("runtime");
        let fake = Arc::new(FakeBackupService::new());
        let (_clock, now) = fake_clock();
        let scheduler = scheduler(
            &runtime,
            Arc::clone(&fake),
            Duration::from_secs(3_600),
            DEFAULT_DUE_AFTER,
            now,
        );
        scheduler.schedule();

        assert_eq!(scheduler.run_if_due(), BackupRunOutcome::NoPassphrase);
        assert_eq!(fake.backup_calls(), 0);
    }

    #[test]
    fn failure_does_not_advance_the_due_window() {
        let runtime = tokio::runtime::Runtime::new().expect("runtime");
        let fake = Arc::new(FakeBackupService::new());
        let (_clock, now) = fake_clock();
        let scheduler = scheduler(
            &runtime,
            Arc::clone(&fake),
            Duration::from_secs(3_600),
            DEFAULT_DUE_AFTER,
            now,
        );
        scheduler.schedule();
        scheduler.set_passphrase(Some("passphrase".to_owned()));

        fake.set_fail(true);
        assert!(matches!(
            scheduler.run_if_due(),
            BackupRunOutcome::Failed(message) if message == "network down"
        ));

        fake.set_fail(false);
        assert_eq!(scheduler.run_if_due(), BackupRunOutcome::Ran);
        assert_eq!(fake.backup_calls(), 2);
    }

    #[test]
    fn scheduler_only_calls_the_encrypted_backup_entry_point() {
        let runtime = tokio::runtime::Runtime::new().expect("runtime");
        let fake = Arc::new(FakeBackupService::new());
        let (_clock, now) = fake_clock();
        let scheduler = scheduler(
            &runtime,
            Arc::clone(&fake),
            Duration::from_secs(3_600),
            DEFAULT_DUE_AFTER,
            now,
        );
        scheduler.schedule();
        scheduler.set_passphrase(Some("correct horse battery staple".to_owned()));

        assert_eq!(scheduler.run_if_due(), BackupRunOutcome::Ran);
        assert_eq!(fake.backup_calls(), 1);
        assert_eq!(fake.other_calls(), 0);
        assert_eq!(
            fake.last_passphrase().as_deref(),
            Some("correct horse battery staple")
        );
    }

    #[test]
    fn enable_schedules_and_disable_cancels() {
        let runtime = tokio::runtime::Runtime::new().expect("runtime");
        let fake = Arc::new(FakeBackupService::new());
        let (_clock, now) = fake_clock();
        let scheduler = scheduler(
            &runtime,
            Arc::clone(&fake),
            Duration::from_secs(3_600),
            DEFAULT_DUE_AFTER,
            now,
        );
        let inner: Arc<dyn BackupService> = fake.clone();
        let service = ScheduledBackupService::new(inner, scheduler);

        service.enable(&form()).expect("enable");
        assert!(service.scheduler().is_scheduled());
        assert_eq!(service.scheduler().run_if_due(), BackupRunOutcome::Ran);
        assert_eq!(fake.backup_calls(), 1);

        service.disable().expect("disable");
        assert!(!service.scheduler().is_scheduled());
        assert_eq!(service.scheduler().run_if_due(), BackupRunOutcome::Disabled);
    }

    #[test]
    fn start_if_enabled_follows_the_persisted_setting() {
        let runtime = tokio::runtime::Runtime::new().expect("runtime");
        let fake = Arc::new(FakeBackupService::new());
        let (_clock, now) = fake_clock();
        let scheduler = scheduler(
            &runtime,
            Arc::clone(&fake),
            Duration::from_secs(3_600),
            DEFAULT_DUE_AFTER,
            now,
        );
        let inner: Arc<dyn BackupService> = fake.clone();
        let service = ScheduledBackupService::new(inner, scheduler);

        fake.set_enabled(true);
        assert!(service.start_if_enabled().expect("start"));
        assert!(service.scheduler().is_scheduled());

        fake.set_enabled(false);
        assert!(!service.start_if_enabled().expect("stop"));
        assert!(!service.scheduler().is_scheduled());
    }

    #[test]
    fn a_restarted_session_schedules_but_skips_without_a_passphrase() {
        let runtime = tokio::runtime::Runtime::new().expect("runtime");
        let fake = Arc::new(FakeBackupService::new());
        let (_clock, now) = fake_clock();
        let scheduler = scheduler(
            &runtime,
            Arc::clone(&fake),
            Duration::from_secs(3_600),
            DEFAULT_DUE_AFTER,
            now,
        );
        let inner: Arc<dyn BackupService> = fake.clone();
        let service = ScheduledBackupService::new(inner, scheduler);

        fake.set_enabled(true);
        assert!(service.start_if_enabled().expect("start"));
        assert_eq!(
            service.scheduler().run_if_due(),
            BackupRunOutcome::NoPassphrase
        );
        assert_eq!(fake.backup_calls(), 0);
    }

    #[test]
    fn manual_backup_refreshes_the_in_memory_passphrase() {
        let runtime = tokio::runtime::Runtime::new().expect("runtime");
        let fake = Arc::new(FakeBackupService::new());
        let (_clock, now) = fake_clock();
        let scheduler = scheduler(
            &runtime,
            Arc::clone(&fake),
            Duration::from_secs(3_600),
            Duration::ZERO,
            now,
        );
        let inner: Arc<dyn BackupService> = fake.clone();
        let service = ScheduledBackupService::new(inner, scheduler);

        service.scheduler().schedule();
        service.backup_now("manual passphrase").expect("backup");
        assert_eq!(fake.backup_calls(), 1);

        assert_eq!(service.scheduler().run_if_due(), BackupRunOutcome::Ran);
        assert_eq!(fake.backup_calls(), 2);
        assert_eq!(fake.last_passphrase().as_deref(), Some("manual passphrase"));
    }

    #[test]
    fn from_runtime_starts_disabled_without_configuration() {
        let directory = tempfile::tempdir().expect("temporary directory");
        let runtime =
            ServiceRuntime::open_at(directory.path().join("chatui.sqlite3"), directory.path())
                .expect("runtime");
        let secrets = Arc::new(SessionStore::new());
        let service = ScheduledBackupService::from_runtime(&runtime, secrets);

        assert!(!service.start_if_enabled().expect("start"));
        assert!(!service.scheduler().is_scheduled());
    }
}
