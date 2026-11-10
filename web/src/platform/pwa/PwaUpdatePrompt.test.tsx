import { act, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { PwaUpdatePrompt } from './PwaUpdatePrompt'
import type {
  ServiceWorkerLike,
  ServiceWorkerRegistrationLike,
} from './register'
import { usePwaUpdateStore } from './updateStore'

function createWaitingWorker() {
  return {
    state: 'installed',
    postMessage: vi.fn(),
    addEventListener: vi.fn(),
  } satisfies ServiceWorkerLike
}

function createRegistration(waiting: ServiceWorkerLike | null) {
  return {
    waiting,
    installing: null,
    addEventListener: vi.fn(),
  } satisfies ServiceWorkerRegistrationLike
}

let controllerEvents: EventTarget

beforeEach(() => {
  usePwaUpdateStore.setState({
    registration: null,
    dismissed: false,
    deferred: false,
  })

  controllerEvents = new EventTarget()
  Object.defineProperty(navigator, 'serviceWorker', {
    configurable: true,
    value: controllerEvents,
  })
})

describe('PwaUpdatePrompt', () => {
  it('renders nothing without a waiting update', () => {
    render(<PwaUpdatePrompt isStreamActive={() => false} />)

    expect(screen.queryByText(/new version of ChatUI/i)).not.toBeInTheDocument()
  })

  it('activates the waiting worker when no stream is active', async () => {
    const user = userEvent.setup()
    const waiting = createWaitingWorker()
    usePwaUpdateStore.getState().markWaiting(createRegistration(waiting))

    render(<PwaUpdatePrompt isStreamActive={() => false} />)

    await user.click(screen.getByRole('button', { name: 'Update now' }))

    expect(waiting.postMessage).toHaveBeenCalledWith({ type: 'SKIP_WAITING' })
  })

  it('defers the update and never activates during an active stream', async () => {
    const user = userEvent.setup()
    const waiting = createWaitingWorker()
    usePwaUpdateStore.getState().markWaiting(createRegistration(waiting))

    render(<PwaUpdatePrompt isStreamActive={() => true} />)

    await user.click(screen.getByRole('button', { name: 'Update now' }))

    expect(waiting.postMessage).not.toHaveBeenCalled()
    expect(
      screen.getByText(/update is paused until the current response finishes/i),
    ).toBeInTheDocument()
  })

  it('can be dismissed without activating the worker', async () => {
    const user = userEvent.setup()
    const waiting = createWaitingWorker()
    usePwaUpdateStore.getState().markWaiting(createRegistration(waiting))

    render(<PwaUpdatePrompt isStreamActive={() => false} />)
    await user.click(screen.getByRole('button', { name: 'Dismiss' }))

    expect(waiting.postMessage).not.toHaveBeenCalled()
    expect(screen.queryByText(/new version of ChatUI/i)).not.toBeInTheDocument()
  })

  it('reloads on controllerchange only after the user requests the update', async () => {
    const user = userEvent.setup()
    const reload = vi.fn()
    const waiting = createWaitingWorker()
    usePwaUpdateStore.getState().markWaiting(createRegistration(waiting))

    render(<PwaUpdatePrompt isStreamActive={() => false} reload={reload} />)

    act(() => {
      controllerEvents.dispatchEvent(new Event('controllerchange'))
    })
    expect(reload).not.toHaveBeenCalled()

    await user.click(screen.getByRole('button', { name: 'Update now' }))

    act(() => {
      controllerEvents.dispatchEvent(new Event('controllerchange'))
    })

    expect(reload).toHaveBeenCalledTimes(1)
  })
})
