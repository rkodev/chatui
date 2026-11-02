import { useToastStore } from '../../app/stores'

/**
 * Convenience hook for shell and feature code. It exposes the store actions
 * without leaking the store implementation into callers.
 */
export function useToast() {
  const toasts = useToastStore((state) => state.toasts)
  const addToast = useToastStore((state) => state.addToast)
  const dismissToast = useToastStore((state) => state.dismissToast)
  const clearToasts = useToastStore((state) => state.clearToasts)

  return { toasts, addToast, dismissToast, clearToasts }
}
