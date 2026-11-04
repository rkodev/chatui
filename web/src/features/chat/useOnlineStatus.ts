import { useEffect, useState } from 'react'

/**
 * Track browser connectivity through `navigator.onLine` and online/offline
 * events. The initial value is read synchronously so the first render already
 * disables sending when the browser is offline.
 */
export function useOnlineStatus(): boolean {
  const [online, setOnline] = useState(() =>
    typeof navigator === 'undefined' ? true : navigator.onLine,
  )

  useEffect(() => {
    const update = () => setOnline(navigator.onLine)

    window.addEventListener('online', update)
    window.addEventListener('offline', update)

    return () => {
      window.removeEventListener('online', update)
      window.removeEventListener('offline', update)
    }
  }, [])

  return online
}
