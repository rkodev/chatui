/**
 * Production-only service worker registration and update detection.
 *
 * The worker is never registered in development. In production the app waits
 * for a new worker to reach the `installed` state while an old worker still
 * controls the page, then surfaces an update prompt. Activation is explicit:
 * the prompt posts `SKIP_WAITING` only when no stream is active.
 */

export interface ServiceWorkerLike {
  state: string
  postMessage(message: unknown): void
  addEventListener(type: 'statechange', listener: () => void): void
}

export interface ServiceWorkerRegistrationLike {
  waiting: ServiceWorkerLike | null
  installing: ServiceWorkerLike | null
  addEventListener(type: 'updatefound', listener: () => void): void
}

export interface ServiceWorkerContainerLike {
  controller: ServiceWorkerLike | null
  register(
    scriptURL: string,
    options?: { scope?: string },
  ): Promise<ServiceWorkerRegistrationLike>
  addEventListener(type: 'controllerchange', listener: () => void): void
}

export interface RegisterServiceWorkerOptions {
  /** Defaults to `import.meta.env.PROD`. Tests pass an explicit value. */
  isProduction?: boolean
  /** Defaults to `navigator.serviceWorker` when available. */
  container?: ServiceWorkerContainerLike | null
  /** Called when a new worker is installed and waiting to activate. */
  onUpdateReady?: (registration: ServiceWorkerRegistrationLike) => void
  /** Called when the page is claimed by a new worker. */
  onControllerChange?: () => void
}

function defaultContainer(): ServiceWorkerContainerLike | null {
  if (typeof navigator === 'undefined' || !('serviceWorker' in navigator)) {
    return null
  }

  return navigator.serviceWorker as unknown as ServiceWorkerContainerLike
}

/**
 * Tell a waiting worker to activate.
 *
 * The page only calls this after the update prompt confirms no stream is
 * active. The worker activates on `SKIP_WAITING`; the page reloads on the
 * resulting `controllerchange` event.
 */
export function activateWaitingWorker(
  registration: ServiceWorkerRegistrationLike,
): boolean {
  if (!registration.waiting) {
    return false
  }

  registration.waiting.postMessage({ type: 'SKIP_WAITING' })
  return true
}

/**
 * Register `/sw.js` in production and report waiting updates.
 *
 * Returns `null` without touching the browser when the app is not a production
 * build, the browser lacks service worker support, or registration is skipped.
 */
export async function registerServiceWorker(
  options: RegisterServiceWorkerOptions = {},
): Promise<ServiceWorkerRegistrationLike | null> {
  const isProduction = options.isProduction ?? import.meta.env.PROD

  if (!isProduction) {
    return null
  }

  const container = options.container === undefined ? defaultContainer() : options.container

  if (!container) {
    return null
  }

  const registration = await container.register('/sw.js', { scope: '/' })

  const notifyIfWaiting = () => {
    if (registration.waiting) {
      options.onUpdateReady?.(registration)
    }
  }

  // A worker may already be waiting when the page loads after an update.
  notifyIfWaiting()

  registration.addEventListener('updatefound', () => {
    const installing = registration.installing

    if (!installing) {
      return
    }

    installing.addEventListener('statechange', () => {
      // The first install has no controller. Only prompt when an existing
      // worker is being replaced.
      if (installing.state === 'installed' && container.controller) {
        options.onUpdateReady?.(registration)
      }
    })
  })

  if (options.onControllerChange) {
    container.addEventListener('controllerchange', options.onControllerChange)
  }

  return registration
}
