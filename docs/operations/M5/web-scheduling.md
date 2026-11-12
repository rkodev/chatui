# Web backup scheduling and platform constraints

The web client schedules backups with a foreground-first design. It does not
claim a background sync guarantee that browsers do not provide. This document
describes exactly when a backup can run, what each browser supports, and what
the user must do.

## Summary

- **Guaranteed:** a due check while the ChatUI tab is open, on a foreground
  interval and when the tab becomes visible again.
- **Best-effort:** `Periodic Background Sync` registration when the browser
  exposes it and the user has granted permission.
- **Never:** a server component, a hidden network destination, or a plaintext
  upload path. The scheduler only calls the encrypted sync engine.

## Browser support

| Browser | Foreground interval | Visibility trigger | Periodic Background Sync | Notes |
| --- | --- | --- | --- | --- |
| Chrome / Edge (desktop) | Yes | Yes | Feature-detected; requires an installed PWA, granted permission, and browser engagement heuristics | Chrome may throttle timers in background tabs. |
| Chrome (Android) | Yes | Yes | Feature-detected; same installed-PWA and permission requirements | The OS may defer periodic sync to save battery. |
| Safari (macOS / iOS) | Yes | Yes | No | No Periodic Background Sync API. Foreground only. |
| Firefox | Yes | Yes | No | No Periodic Background Sync API. Foreground only. |
| Other Chromium browsers | Yes | Yes | Depends on the vendor | Treated as optional and feature-detected. |

The support table reflects the APIs the client checks, not a promise that a
particular browser version will run a backup in the background. Browser
vendors change heuristics without notice.

## Why full background sync is not guaranteed

There is no cross-browser standard for running arbitrary work while a web app is
closed. The relevant constraints are:

1. **No universal API.** `Periodic Background Sync` is Chromium-only and is not
   implemented by Safari or Firefox.
2. **Permission and engagement gates.** Even on Chromium, registration can be
   denied or ignored. The browser may require the app to be installed, the user
   to grant permission, and the site to meet engagement heuristics.
3. **OS scheduling.** The operating system may defer or skip periodic work to
   protect battery and network. The requested interval is a minimum cadence, not
   an exact schedule.
4. **Tab throttling.** Background tabs have their timers throttled. The client
   therefore also checks on `visibilitychange` when the tab becomes visible.
5. **Passphrase handling.** The backup passphrase is never persisted. After a
   page reload the in-memory passphrase is gone, so an automatic run cannot
   encrypt or decrypt until the user re-enters it. This is intentional.

Because of these constraints, the client treats foreground scheduling as the
authoritative path and periodic sync as an optional enhancement.

## What runs when

### On app open

When the backup settings screen loads and backup is enabled, the scheduler
starts a foreground interval (default: every 5 minutes) and registers the
visibility listener. The first due check runs on the next interval tick or
visibility change. A backup is considered due when at least 15 minutes have
passed since the last successful automatic run.

### On visibility change

When the tab becomes visible again, the scheduler runs the same due check. This
covers the common case where the browser throttled timers while the tab was in
the background. Hidden transitions do not trigger a run.

### On periodic sync when available

If the service worker registration exposes `periodicSync` and the
`periodic-background-sync` permission is granted, the scheduler registers the
`chatui-backup` tag with a 12-hour minimum interval. Registration failures,
unsupported permission names, and denied permissions fall back silently to
foreground scheduling.

The current service worker only caches the app shell. It does not yet perform a
backup on a `periodicsync` event, so the registration is a forward-compatible
hook rather than a working background backup. The foreground scheduler remains
the path that actually runs a backup. This document will be updated if a later
task adds a service worker backup handler.

### When backup is disabled

Disabling backup cancels the foreground interval, removes the visibility
listener, and unregisters the periodic sync tag when it was registered. A
disabled scheduler also refuses to run a due check even if a timer fires.

## How the user can force a backup

Open **Settings → Backup** and use **Back up now**. The manual action calls the
same encrypted sync engine as the scheduler and works regardless of the due
window. The status panel shows the last backup time, pending changes, the last
error, and approximate local storage usage.

## Implementation notes

- `web/src/features/backup/scheduler.ts` implements `schedule()`, `cancel()`,
  and `runIfDue()`.
- The scheduler depends only on an engine with `backupNow()`. It never reads
  conversation content and never talks to a storage adapter directly, so it
  cannot introduce a plaintext path.
- `BackupSettingsService` wires enable/disable to `schedule()`/`cancel()` and
  starts scheduling on load when backup is already enabled.
- The scheduler is created by `createBackupSettingsService()`. It runs while the
  app tab is open after the backup settings have been loaded in that session.
  A page reload requires the user to visit backup settings again and re-enter
  the passphrase before automatic runs can succeed.
