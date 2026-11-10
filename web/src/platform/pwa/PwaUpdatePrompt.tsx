import { useEffect, useRef } from 'react'
import { Button } from '../../ui/primitives'
import { hasActiveStream } from './activeStream'
import { activateWaitingWorker } from './register'
import { usePwaUpdateStore } from './updateStore'
import './pwa.css'

export interface PwaUpdatePromptProps {
  /** Stream check, injectable for tests. */
  isStreamActive?: () => boolean
  /** Reload action, injectable for tests. */
  reload?: () => void
}

/**
 * Non-blocking update prompt.
 *
 * The prompt appears when a new service worker is waiting. Choosing Update
 * activates the worker only when no response is streaming. If a stream is
 * active the prompt stays visible and explains that the update is deferred, so
 * the page never reloads mid-response.
 */
export function PwaUpdatePrompt({
  isStreamActive = hasActiveStream,
  reload,
}: PwaUpdatePromptProps = {}) {
  const registration = usePwaUpdateStore((state) => state.registration)
  const dismissed = usePwaUpdateStore((state) => state.dismissed)
  const deferred = usePwaUpdateStore((state) => state.deferred)
  const dismiss = usePwaUpdateStore((state) => state.dismiss)
  const defer = usePwaUpdateStore((state) => state.defer)
  const clearDeferred = usePwaUpdateStore((state) => state.clearDeferred)
  const reloadRequested = useRef(false)

  useEffect(() => {
    if (typeof navigator === 'undefined' || !('serviceWorker' in navigator)) {
      return
    }

    const handleControllerChange = () => {
      if (!reloadRequested.current) {
        return
      }

      reloadRequested.current = false
      const reloadPage = reload ?? (() => window.location.reload())
      reloadPage()
    }

    navigator.serviceWorker.addEventListener('controllerchange', handleControllerChange)

    return () => {
      navigator.serviceWorker.removeEventListener('controllerchange', handleControllerChange)
    }
  }, [reload])

  if (!registration || dismissed) {
    return null
  }

  const handleUpdate = () => {
    if (isStreamActive()) {
      defer()
      return
    }

    clearDeferred()
    reloadRequested.current = true

    if (!activateWaitingWorker(registration)) {
      reloadRequested.current = false
    }
  }

  return (
    <aside className="pwa-update" role="status" aria-live="polite" aria-label="Update available">
      <p className="pwa-update__message">
        A new version of ChatUI is available.
        {deferred ? ' The update is paused until the current response finishes.' : ''}
      </p>
      <div className="pwa-update__actions">
        <Button size="sm" onClick={handleUpdate}>
          Update now
        </Button>
        <Button size="sm" variant="ghost" onClick={dismiss}>
          Dismiss
        </Button>
      </div>
    </aside>
  )
}
