import { create } from 'zustand'

export type ToastTone = 'info' | 'success' | 'warning' | 'error'

export interface Toast {
  id: string
  message: string
  tone: ToastTone
}

export interface ToastInput {
  message: string
  tone?: ToastTone
  id?: string
}

export interface ToastStore {
  toasts: Toast[]
  addToast: (input: ToastInput) => string
  dismissToast: (id: string) => void
  clearToasts: () => void
}

let toastSequence = 0

function nextToastId(): string {
  toastSequence += 1
  return `toast-${toastSequence}`
}

/**
 * Ephemeral toast queue. Toasts are not persisted and carry no feature data
 * beyond the message and tone needed to render them.
 */
export const useToastStore = create<ToastStore>((set) => ({
  toasts: [],
  addToast: (input) => {
    const toast: Toast = {
      id: input.id ?? nextToastId(),
      message: input.message,
      tone: input.tone ?? 'info',
    }

    set((state) => ({ toasts: [...state.toasts, toast] }))
    return toast.id
  },
  dismissToast: (id) =>
    set((state) => ({ toasts: state.toasts.filter((toast) => toast.id !== id) })),
  clearToasts: () => set({ toasts: [] }),
}))
