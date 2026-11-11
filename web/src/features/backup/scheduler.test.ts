import { describe, expect, it, vi } from 'vitest'
import {
  BackupScheduler,
  DEFAULT_DUE_AFTER_MS,
  DEFAULT_FOREGROUND_INTERVAL_MS,
  PERIODIC_SYNC_MIN_INTERVAL_MS,
  PERIODIC_SYNC_TAG,
  type SchedulerDocument,
  type SchedulerTimers,
  type ServiceWorkerContainerLike,
} from './scheduler'

const START = 1_000_000

function createEngine() {
  const backupNow = vi.fn(async () => ({ ok: true }))
  return { engine: { backupNow }, backupNow }
}

function createTimers() {
  let handler: (() => void) | null = null
  let interval: number | null = null

  const setInterval = vi.fn((next: () => void, timeout: number) => {
    handler = next
    interval = timeout
    return 1
  })
  const clearInterval = vi.fn(() => {
    handler = null
  })

  const timers: SchedulerTimers = { setInterval, clearInterval }

  return {
    timers,
    setInterval,
    clearInterval,
    tick: () => handler?.(),
    get interval() {
      return interval
    },
    get active() {
      return handler !== null
    },
  }
}

function createDocument(visibilityState = 'visible') {
  let listener: (() => void) | null = null

  const addEventListener = vi.fn((_type: 'visibilitychange', next: () => void) => {
    listener = next
  })
  const removeEventListener = vi.fn(() => {
    listener = null
  })

  const doc: SchedulerDocument = {
    visibilityState,
    addEventListener,
    removeEventListener,
  }

  return {
    doc,
    addEventListener,
    removeEventListener,
    fire: () => listener?.(),
    get listener() {
      return listener
    },
  }
}

async function flush(): Promise<void> {
  await Promise.resolve()
  await Promise.resolve()
}

function createScheduler(
  overrides: Partial<ConstructorParameters<typeof BackupScheduler>[0]> = {},
) {
  const clock = { value: START }
  const { engine, backupNow } = createEngine()
  const scheduler = new BackupScheduler({
    engine,
    isEnabled: () => true,
    now: () => clock.value,
    document: null,
    serviceWorker: null,
    permissions: null,
    ...overrides,
  })

  return { scheduler, clock, engine, backupNow }
}

describe('BackupScheduler', () => {
  it('runs a backup when due and skips while inside the due window', async () => {
    const { scheduler, clock, backupNow } = createScheduler()

    await expect(scheduler.runIfDue()).resolves.toBe(true)
    expect(backupNow).toHaveBeenCalledTimes(1)

    clock.value += DEFAULT_DUE_AFTER_MS - 1
    await expect(scheduler.runIfDue()).resolves.toBe(false)
    expect(backupNow).toHaveBeenCalledTimes(1)

    clock.value += 1
    await expect(scheduler.runIfDue()).resolves.toBe(true)
    expect(backupNow).toHaveBeenCalledTimes(2)
  })

  it('skips when backup is disabled and resumes when enabled', async () => {
    let enabled = false
    const { scheduler, backupNow } = createScheduler({ isEnabled: () => enabled })

    await expect(scheduler.runIfDue()).resolves.toBe(false)
    expect(backupNow).not.toHaveBeenCalled()

    enabled = true
    await expect(scheduler.runIfDue()).resolves.toBe(true)
    expect(backupNow).toHaveBeenCalledTimes(1)
  })

  it('does not start a foreground interval while disabled', async () => {
    const timers = createTimers()
    const { scheduler } = createScheduler({
      isEnabled: () => false,
      timers: timers.timers,
    })

    await scheduler.schedule()

    expect(timers.setInterval).not.toHaveBeenCalled()
  })

  it('starts a foreground interval and runs on each tick', async () => {
    const timers = createTimers()
    const { scheduler, backupNow } = createScheduler({ timers: timers.timers })

    await scheduler.schedule()

    expect(timers.setInterval).toHaveBeenCalledTimes(1)
    expect(timers.interval).toBe(DEFAULT_FOREGROUND_INTERVAL_MS)

    timers.tick()
    await flush()

    expect(backupNow).toHaveBeenCalledTimes(1)
  })

  it('is idempotent when schedule is called twice', async () => {
    const timers = createTimers()
    const { scheduler } = createScheduler({ timers: timers.timers })

    await scheduler.schedule()
    await scheduler.schedule()

    expect(timers.setInterval).toHaveBeenCalledTimes(1)
  })

  it('checks due state on visibility change only when the tab is visible', async () => {
    const doc = createDocument('hidden')
    const { scheduler, backupNow } = createScheduler({ document: doc.doc })

    await scheduler.schedule()
    expect(doc.addEventListener).toHaveBeenCalledWith('visibilitychange', expect.any(Function))

    doc.fire()
    await flush()
    expect(backupNow).not.toHaveBeenCalled()

    doc.doc.visibilityState = 'visible'
    doc.fire()
    await flush()
    expect(backupNow).toHaveBeenCalledTimes(1)
  })

  it('cancels the interval and visibility listener when disabled', async () => {
    const timers = createTimers()
    const doc = createDocument()
    const { scheduler, backupNow } = createScheduler({
      timers: timers.timers,
      document: doc.doc,
    })

    await scheduler.schedule()
    await scheduler.cancel()

    expect(timers.clearInterval).toHaveBeenCalledWith(1)
    expect(doc.removeEventListener).toHaveBeenCalledWith(
      'visibilitychange',
      expect.any(Function),
    )
    expect(timers.active).toBe(false)

    timers.tick()
    doc.fire()
    await flush()
    expect(backupNow).not.toHaveBeenCalled()
  })

  it('falls back safely when the service worker or periodic sync is unavailable', async () => {
    const timers = createTimers()

    const withoutWorker = createScheduler({ timers: timers.timers })
    await expect(withoutWorker.scheduler.schedule()).resolves.toBeUndefined()

    const withoutPeriodicSync = createScheduler({
      timers: timers.timers,
      serviceWorker: { ready: Promise.resolve({}) },
    })
    await expect(withoutPeriodicSync.scheduler.schedule()).resolves.toBeUndefined()
  })

  it('does not register periodic sync when permission is not granted', async () => {
    const register = vi.fn(async () => undefined)
    const serviceWorker: ServiceWorkerContainerLike = {
      ready: Promise.resolve({ periodicSync: { register, unregister: vi.fn() } }),
    }
    const permissions = { query: vi.fn(async () => ({ state: 'denied' })) }
    const { scheduler } = createScheduler({ serviceWorker, permissions })

    await scheduler.schedule()

    expect(permissions.query).toHaveBeenCalledWith({ name: 'periodic-background-sync' })
    expect(register).not.toHaveBeenCalled()
  })

  it('registers and unregisters periodic sync when supported and granted', async () => {
    const register = vi.fn(async () => undefined)
    const unregister = vi.fn(async () => undefined)
    const serviceWorker: ServiceWorkerContainerLike = {
      ready: Promise.resolve({ periodicSync: { register, unregister } }),
    }
    const permissions = { query: vi.fn(async () => ({ state: 'granted' })) }
    const { scheduler } = createScheduler({ serviceWorker, permissions })

    await scheduler.schedule()

    expect(register).toHaveBeenCalledWith(PERIODIC_SYNC_TAG, {
      minInterval: PERIODIC_SYNC_MIN_INTERVAL_MS,
    })

    await scheduler.cancel()

    expect(unregister).toHaveBeenCalledWith(PERIODIC_SYNC_TAG)
  })

  it('swallows periodic sync registration failures', async () => {
    const serviceWorker: ServiceWorkerContainerLike = {
      ready: Promise.resolve({
        periodicSync: {
          register: vi.fn(async () => {
            throw new Error('not allowed')
          }),
          unregister: vi.fn(async () => undefined),
        },
      }),
    }
    const permissions = { query: vi.fn(async () => ({ state: 'granted' })) }
    const { scheduler } = createScheduler({ serviceWorker, permissions })

    await expect(scheduler.schedule()).resolves.toBeUndefined()
  })

  it('reports engine failures without rejecting the timer tick', async () => {
    const onError = vi.fn()
    const { scheduler, backupNow } = createScheduler({ onError })
    backupNow.mockRejectedValueOnce(new Error('network down'))

    await expect(scheduler.runIfDue()).resolves.toBe(false)
    expect(onError).toHaveBeenCalledTimes(1)
  })

  it('ignores concurrent due checks', async () => {
    let resolveRun!: (value: unknown) => void
    const pending = new Promise<unknown>((resolve) => {
      resolveRun = resolve
    })
    const backupNow = vi.fn(() => pending)
    const { scheduler } = createScheduler({ engine: { backupNow } })

    const first = scheduler.runIfDue()
    await expect(scheduler.runIfDue()).resolves.toBe(false)

    resolveRun({ ok: true })
    await expect(first).resolves.toBe(true)
    expect(backupNow).toHaveBeenCalledTimes(1)
  })

  it('delegates to the encrypted engine with no plaintext arguments', async () => {
    const { scheduler, backupNow } = createScheduler()

    await scheduler.runIfDue()

    expect(backupNow).toHaveBeenCalledWith()
  })
})
