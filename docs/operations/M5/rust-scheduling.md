# Rust desktop backup scheduling and limitations

The desktop client schedules backups only while the ChatUI process is running.
It does not install an OS service, a launch agent, or a background daemon. This
document describes exactly when a backup can run, what happens when the app is
closed, and what the user must do.

## Summary

- **Guaranteed:** a due check on a foreground interval while the desktop app is
  open and backup is enabled.
- **Manual:** "Back up now" in the backup settings screen, regardless of the due
  window.
- **Never:** an OS service, a background daemon, a hidden network destination,
  or a plaintext upload path. The scheduler only calls the encrypted sync
  engine through `BackupService::backup_now`.
- **Not guaranteed:** a backup while the app is closed, suspended, or killed.

## When backup runs

### On app open

When the backup settings screen loads, `ScheduledBackupService::start_if_enabled`
reads the persisted configuration. If backup is enabled, it starts a `tokio`
interval task. The first due check runs after one interval tick, not
immediately.

### On the foreground interval

The scheduler ticks every **5 minutes** (`DEFAULT_INTERVAL`). A backup is due
when at least **15 minutes** (`DEFAULT_DUE_AFTER`) have passed since the last
successful automatic run. A tick inside the due window is skipped. A failed run
does not advance the due window, so the next tick retries.

The interval is a cadence, not an exact schedule. The operating system may
delay timers while the machine is busy, and a suspended or sleeping machine
does not run timers at all. When the machine wakes, the next tick runs the due
check.

### When backup is disabled

Disabling backup cancels the interval task and forgets the in-memory
passphrase. A disabled scheduler refuses to run even if a tick was already
queued. The persisted configuration and remote data are kept.

## What happens when the app is closed

Nothing runs. The scheduler lives inside the desktop process and is driven by
the shared `tokio` runtime. Closing the app drops the runtime and aborts the
interval task. There is no OS service, no launch agent, no cron entry, and no
background daemon, so a closed app cannot back up.

This is intentional. The desktop client does not claim a background sync
guarantee that it cannot keep.

## How the user can force a backup

Open **Settings → Backup** and use **Back up now**. The manual action calls the
same encrypted sync engine as the scheduler and works regardless of the due
window. The status panel shows the last backup time, pending changes, the last
error, and approximate local storage usage.

## Platform limitations

1. **The app must be running.** There is no background execution after the
   process exits.
2. **The passphrase is never persisted.** The scheduler keeps the passphrase in
   memory for the current session only. After a restart, automatic runs skip
   with `NoPassphrase` until the user enters the passphrase again. This is
   intentional and matches the web and Android clients.
3. **Sleep and suspend pause timers.** A laptop that sleeps for hours does not
   run backups during sleep. The next tick after wake performs the due check.
4. **Timers are best-effort.** A busy or throttled process may run a tick late.
   The due window prevents a burst of catch-up runs.
5. **No OS service or daemon.** The client does not install a service, a launch
   agent, a scheduled task, or a cron entry. Adding one would be a separate
   product decision.
6. **No plaintext path.** The scheduler depends only on the `BackupService`
   trait. It never reads conversation content and never talks to a storage
   adapter directly. The encrypted sync engine remains the only upload path.

## Implementation notes

- `rust/apps/desktop/src/services/backup_scheduler.rs` implements
  `BackupScheduler` with `schedule()`, `cancel()`, and `run_if_due()`.
- `BackupScheduler` uses `tokio::time::interval_at` on the shared desktop
  runtime. The blocking `BackupService::backup_now` call runs through
  `tokio::task::spawn_blocking` so it never blocks a runtime worker thread.
- `ScheduledBackupService` wraps a `BackupService` and wires enable/disable:
  enabling schedules and stores the passphrase, disabling cancels and clears
  it. Manual backup, restore, and delete also refresh the in-memory passphrase.
- `ScheduledBackupService::from_runtime` builds the production service over
  `ServiceRuntime` and the selected secret store. The desktop composition should
  use it when the backup settings screen is wired.
- `ServiceRuntime::handle()` exposes the shared runtime handle for the interval
  task. No second runtime or pool is created.
- Tests use a fake clock and short intervals. They cover due and not-due runs,
  disable/cancel, missing passphrase, failure retry, and the absence of any
  non-encrypted service call.
