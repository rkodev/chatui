/**
 * Web backup scheduling.
 *
 * Browsers do not offer a reliable background sync primitive. This scheduler
 * therefore treats foreground scheduling as the guaranteed path:
 *
 * - a foreground interval while the app tab is open,
 * - a `visibilitychange` check when the tab becomes visible again,
 * - an optional `Periodic Background Sync` registration when the browser
 *   exposes it and the user has granted permission.
 *
 * The scheduler only calls the encrypted sync engine's `backupNow()`. It never
 * reads conversation content and never talks to storage directly, so it cannot
 * introduce a plaintext upload path.
 */

/** The only engine surface the scheduler needs. */
export interface BackupSchedulerEngine {
  backupNow(): Promise<unknown>
}

/** Minimal document surface used for the visibility trigger. */
export interface SchedulerDocument {
  visibilityState?: string
  addEventListener(type: 'visibilitychange', listener: () => void): void
  removeEventListener(type: 'visibilitychange', listener: () => void): void
}

/** Timer surface, injectable so tests do not depend on real time. */
export interface SchedulerTimers {
  setInterval(handler: () => void, timeout: number): unknown
  clearInterval(handle: unknown): void
}

/** The subset of `PeriodicSyncManager` this scheduler uses. */
export interface PeriodicSyncManagerLike {
  register(tag: string, options?: { minInterval?: number }): Promise<void>
  unregister(tag: string): Promise<void>
}

/** A service worker registration that may expose `periodicSync`. */
export interface PeriodicSyncRegistrationLike {
  periodicSync?: PeriodicSyncManagerLike
}

/** Minimal service worker container surface. */
export interface ServiceWorkerContainerLike {
  ready?: Promise<PeriodicSyncRegistrationLike>
  getRegistration?(): Promise<PeriodicSyncRegistrationLike | undefined>
}

/** Minimal permissions surface used to check periodic sync permission. */
export interface PermissionsLike {
  query(descriptor: { name: string }): Promise<{ state: string }>
}

/** Public scheduler surface used by the settings flow. */
export interface BackupSchedulerLike {
  schedule(): Promise<void>
  cancel(): Promise<void>
  runIfDue(): Promise<boolean>
}

export interface BackupSchedulerOptions {
  engine: BackupSchedulerEngine
  /** Reads the persisted enable/disable setting. */
  isEnabled: () => boolean | Promise<boolean>
  /** Clock in milliseconds. Defaults to `Date.now`. */
  now?: () => number
  /** Foreground tick interval. Defaults to 5 minutes. */
  intervalMs?: number
  /** Minimum time between automatic runs. Defaults to 15 minutes. */
  dueAfterMs?: number
  /** Document used for the visibility trigger. Defaults to `document`. */
  document?: SchedulerDocument | null
  /** Timer implementation. Defaults to the global timers. */
  timers?: SchedulerTimers
  /** Service worker container. Defaults to `navigator.serviceWorker`. */
  serviceWorker?: ServiceWorkerContainerLike | null
  /** Permissions API. Defaults to `navigator.permissions`. */
  permissions?: PermissionsLike | null
  /** Called when an automatic run fails. Errors never escape the scheduler. */
  onError?: (error: unknown) => void
  /** Called after a successful automatic run. */
  onRun?: (result: unknown) => void
}

/** Foreground tick interval. */
export const DEFAULT_FOREGROUND_INTERVAL_MS = 5 * 60 * 1000
/** Minimum time between automatic runs. */
export const DEFAULT_DUE_AFTER_MS = 15 * 60 * 1000
/** Periodic Background Sync tag. */
export const PERIODIC_SYNC_TAG = 'chatui-backup'
/** Requested minimum interval for Periodic Background Sync. */
export const PERIODIC_SYNC_MIN_INTERVAL_MS = 12 * 60 * 60 * 1000

const defaultTimers: SchedulerTimers = {
  setInterval: (handler, timeout) => globalThis.setInterval(handler, timeout),
  clearInterval: (handle) =>
    globalThis.clearInterval(handle as ReturnType<typeof globalThis.setInterval>),
}

function defaultDocument(): SchedulerDocument | null {
  return typeof document === 'undefined'
    ? null
    : (document as unknown as SchedulerDocument)
}

function defaultServiceWorker(): ServiceWorkerContainerLike | null {
  if (typeof navigator === 'undefined' || !('serviceWorker' in navigator)) {
    return null
  }

  return navigator.serviceWorker as unknown as ServiceWorkerContainerLike
}

function defaultPermissions(): PermissionsLike | null {
  if (typeof navigator === 'undefined' || !('permissions' in navigator)) {
    return null
  }

  return navigator.permissions as unknown as PermissionsLike
}

/**
 * Foreground-first backup scheduler with an optional periodic sync hook.
 *
 * `schedule()` is idempotent. `cancel()` clears the interval, removes the
 * visibility listener, and unregisters the periodic sync tag. `runIfDue()`
 * checks the enable setting and the due window before calling the engine.
 */
export class BackupScheduler implements BackupSchedulerLike {
  private readonly engine: BackupSchedulerEngine
  private readonly isEnabled: () => boolean | Promise<boolean>
  private readonly now: () => number
  private readonly intervalMs: number
  private readonly dueAfterMs: number
  private readonly document: SchedulerDocument | null
  private readonly timers: SchedulerTimers
  private readonly serviceWorker: ServiceWorkerContainerLike | null
  private readonly permissions: PermissionsLike | null
  private readonly onError: ((error: unknown) => void) | undefined
  private readonly onRun: ((result: unknown) => void) | undefined

  private timer: unknown = null
  private visibilityListener: (() => void) | null = null
  private lastRunAt: number | null = null
  private running = false
  private scheduled = false
  private periodicRegistered = false

  constructor(options: BackupSchedulerOptions) {
    this.engine = options.engine
    this.isEnabled = options.isEnabled
    this.now = options.now ?? (() => Date.now())
    this.intervalMs = options.intervalMs ?? DEFAULT_FOREGROUND_INTERVAL_MS
    this.dueAfterMs = options.dueAfterMs ?? DEFAULT_DUE_AFTER_MS
    this.document = options.document === undefined ? defaultDocument() : options.document
    this.timers = options.timers ?? defaultTimers
    this.serviceWorker =
      options.serviceWorker === undefined ? defaultServiceWorker() : options.serviceWorker
    this.permissions =
      options.permissions === undefined ? defaultPermissions() : options.permissions
    this.onError = options.onError
    this.onRun = options.onRun
  }

  /** Start foreground scheduling and try to register periodic sync. */
  async schedule(): Promise<void> {
    if (this.scheduled) {
      return
    }

    if (!(await this.isEnabled())) {
      return
    }

    this.scheduled = true
    this.timer = this.timers.setInterval(() => {
      void this.runIfDue()
    }, this.intervalMs)

    if (this.document !== null) {
      const listener = () => {
        if (this.document?.visibilityState === 'hidden') {
          return
        }
        void this.runIfDue()
      }
      this.visibilityListener = listener
      this.document.addEventListener('visibilitychange', listener)
    }

    await this.registerPeriodicSync()
  }

  /** Stop all scheduling and unregister periodic sync when it was registered. */
  async cancel(): Promise<void> {
    this.scheduled = false

    if (this.timer !== null) {
      this.timers.clearInterval(this.timer)
      this.timer = null
    }

    if (this.document !== null && this.visibilityListener !== null) {
      this.document.removeEventListener('visibilitychange', this.visibilityListener)
      this.visibilityListener = null
    }

    await this.unregisterPeriodicSync()
  }

  /**
   * Run a backup when backup is enabled and the due window has elapsed.
   *
   * Returns `true` only when the engine actually ran. Concurrent calls are
   * ignored, and engine failures are reported through `onError` without
   * escaping so a timer tick cannot produce an unhandled rejection.
   */
  async runIfDue(): Promise<boolean> {
    if (this.running) {
      return false
    }

    this.running = true

    try {
      if (!(await this.isEnabled())) {
        return false
      }

      const now = this.now()
      if (this.lastRunAt !== null && now - this.lastRunAt < this.dueAfterMs) {
        return false
      }

      const result = await this.engine.backupNow()
      this.lastRunAt = this.now()
      this.onRun?.(result)
      return true
    } catch (error) {
      this.onError?.(error)
      return false
    } finally {
      this.running = false
    }
  }

  private async resolveRegistration(): Promise<PeriodicSyncRegistrationLike | null> {
    const container = this.serviceWorker
    if (container === null) {
      return null
    }

    try {
      if (container.ready !== undefined) {
        return await container.ready
      }
      if (container.getRegistration !== undefined) {
        return (await container.getRegistration()) ?? null
      }
    } catch {
      // A missing or rejected registration falls back to foreground only.
    }

    return null
  }

  private async registerPeriodicSync(): Promise<void> {
    const registration = await this.resolveRegistration()
    if (registration === null || !('periodicSync' in registration)) {
      return
    }

    const periodicSync = registration.periodicSync
    if (periodicSync === undefined) {
      return
    }

    try {
      if (this.permissions !== null) {
        const status = await this.permissions.query({ name: 'periodic-background-sync' })
        if (status.state !== 'granted') {
          return
        }
      }

      await periodicSync.register(PERIODIC_SYNC_TAG, {
        minInterval: PERIODIC_SYNC_MIN_INTERVAL_MS,
      })
      this.periodicRegistered = true
    } catch {
      // Unsupported permission names, denied permission, or registration
      // failures all fall back silently to foreground scheduling.
    }
  }

  private async unregisterPeriodicSync(): Promise<void> {
    if (!this.periodicRegistered) {
      return
    }

    this.periodicRegistered = false

    try {
      const registration = await this.resolveRegistration()
      await registration?.periodicSync?.unregister(PERIODIC_SYNC_TAG)
    } catch {
      // Best-effort cleanup; foreground scheduling is already stopped.
    }
  }
}
