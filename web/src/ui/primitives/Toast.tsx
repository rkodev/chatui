import * as ToastPrimitive from '@radix-ui/react-toast'
import type { ReactNode } from 'react'
import { useToastStore, type Toast as ToastRecord } from '../../app/stores'
import './primitives.css'

export interface ToastProps {
  toast: ToastRecord
  onDismiss: (id: string) => void
  duration?: number
}

/**
 * A single toast item. Radix supplies the live-region semantics and the
 * auto-dismiss timer; the shell store remains the source of truth for the
 * queue, so closing a toast removes it from the store.
 */
export function Toast({ toast, onDismiss, duration = 5000 }: ToastProps) {
  return (
    <ToastPrimitive.Root
      open
      duration={duration}
      onOpenChange={(open) => {
        if (!open) {
          onDismiss(toast.id)
        }
      }}
      className="ui-toast"
      data-tone={toast.tone}
    >
      <ToastPrimitive.Title className="ui-toast__title">{toast.message}</ToastPrimitive.Title>
      <ToastPrimitive.Close className="ui-toast__close" aria-label="Dismiss notification">
        <span aria-hidden="true">×</span>
      </ToastPrimitive.Close>
    </ToastPrimitive.Root>
  )
}

export interface ToastProviderProps {
  children: ReactNode
  duration?: number
  label?: string
}

/**
 * Renders the shell toast queue and its viewport. The provider is mounted once
 * near the application root; feature code only calls `useToast().addToast`.
 */
export function ToastProvider({
  children,
  duration = 5000,
  label = 'Notifications',
}: ToastProviderProps) {
  const toasts = useToastStore((state) => state.toasts)
  const dismissToast = useToastStore((state) => state.dismissToast)

  return (
    <ToastPrimitive.Provider>
      {children}
      {toasts.map((toast) => (
        <Toast key={toast.id} toast={toast} onDismiss={dismissToast} duration={duration} />
      ))}
      <ToastPrimitive.Viewport className="ui-toast__viewport" label={label} />
    </ToastPrimitive.Provider>
  )
}
