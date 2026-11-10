import { describe, expect, it, vi } from 'vitest'
import {
  activateWaitingWorker,
  registerServiceWorker,
  type ServiceWorkerContainerLike,
  type ServiceWorkerLike,
  type ServiceWorkerRegistrationLike,
} from './register'

class FakeWorker implements ServiceWorkerLike {
  state = 'installing'
  readonly messages: unknown[] = []
  private readonly listeners = new Set<() => void>()

  postMessage(message: unknown): void {
    this.messages.push(message)
  }

  addEventListener(_type: 'statechange', listener: () => void): void {
    this.listeners.add(listener)
  }

  setState(state: string): void {
    this.state = state

    for (const listener of this.listeners) {
      listener()
    }
  }
}

class FakeRegistration implements ServiceWorkerRegistrationLike {
  waiting: ServiceWorkerLike | null = null
  installing: ServiceWorkerLike | null = null
  private readonly listeners = new Set<() => void>()

  addEventListener(_type: 'updatefound', listener: () => void): void {
    this.listeners.add(listener)
  }

  emitUpdateFound(): void {
    for (const listener of this.listeners) {
      listener()
    }
  }
}

class FakeContainer implements ServiceWorkerContainerLike {
  controller: ServiceWorkerLike | null = null
  readonly register = vi.fn(async () => this.registration)
  private readonly listeners = new Set<() => void>()

  readonly registration: FakeRegistration

  constructor(registration: FakeRegistration) {
    this.registration = registration
  }

  addEventListener(_type: 'controllerchange', listener: () => void): void {
    this.listeners.add(listener)
  }

  emitControllerChange(): void {
    for (const listener of this.listeners) {
      listener()
    }
  }
}

describe('registerServiceWorker', () => {
  it('does not register in development', async () => {
    const registration = new FakeRegistration()
    const container = new FakeContainer(registration)

    await expect(
      registerServiceWorker({ isProduction: false, container }),
    ).resolves.toBeNull()
    expect(container.register).not.toHaveBeenCalled()
  })

  it('does not register when the browser lacks service worker support', async () => {
    await expect(
      registerServiceWorker({ isProduction: true, container: null }),
    ).resolves.toBeNull()
  })

  it('registers /sw.js at the root scope in production', async () => {
    const registration = new FakeRegistration()
    const container = new FakeContainer(registration)

    const result = await registerServiceWorker({ isProduction: true, container })

    expect(result).toBe(registration)
    expect(container.register).toHaveBeenCalledWith('/sw.js', { scope: '/' })
  })

  it('prompts when a worker is already waiting on load', async () => {
    const registration = new FakeRegistration()
    registration.waiting = new FakeWorker()
    const container = new FakeContainer(registration)
    const onUpdateReady = vi.fn()

    await registerServiceWorker({ isProduction: true, container, onUpdateReady })

    expect(onUpdateReady).toHaveBeenCalledWith(registration)
  })

  it('prompts when a replacement worker installs over a controlled page', async () => {
    const registration = new FakeRegistration()
    const container = new FakeContainer(registration)
    container.controller = new FakeWorker()
    const onUpdateReady = vi.fn()

    await registerServiceWorker({ isProduction: true, container, onUpdateReady })

    const installing = new FakeWorker()
    registration.installing = installing
    registration.emitUpdateFound()

    installing.setState('installed')

    expect(onUpdateReady).toHaveBeenCalledWith(registration)
  })

  it('does not prompt on the first install while the page is uncontrolled', async () => {
    const registration = new FakeRegistration()
    const container = new FakeContainer(registration)
    const onUpdateReady = vi.fn()

    await registerServiceWorker({ isProduction: true, container, onUpdateReady })

    const installing = new FakeWorker()
    registration.installing = installing
    registration.emitUpdateFound()

    installing.setState('installed')

    expect(onUpdateReady).not.toHaveBeenCalled()
  })

  it('reports controller changes when a callback is supplied', async () => {
    const registration = new FakeRegistration()
    const container = new FakeContainer(registration)
    const onControllerChange = vi.fn()

    await registerServiceWorker({ isProduction: true, container, onControllerChange })
    container.emitControllerChange()

    expect(onControllerChange).toHaveBeenCalledTimes(1)
  })
})

describe('activateWaitingWorker', () => {
  it('posts SKIP_WAITING to the waiting worker', () => {
    const waiting = new FakeWorker()
    const registration = new FakeRegistration()
    registration.waiting = waiting

    expect(activateWaitingWorker(registration)).toBe(true)
    expect(waiting.messages).toEqual([{ type: 'SKIP_WAITING' }])
  })

  it('does nothing when no worker is waiting', () => {
    expect(activateWaitingWorker(new FakeRegistration())).toBe(false)
  })
})
