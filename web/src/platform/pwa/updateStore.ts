import { create } from 'zustand'
import type { ServiceWorkerRegistrationLike } from './register'

export interface PwaUpdateState {
  /** The registration whose waiting worker can be activated. */
  registration: ServiceWorkerRegistrationLike | null
  /** True after the user dismissed the current prompt. */
  dismissed: boolean
  /** True when the user asked to update during an active stream. */
  deferred: boolean
  markWaiting: (registration: ServiceWorkerRegistrationLike) => void
  dismiss: () => void
  defer: () => void
  clearDeferred: () => void
}

/**
 * Update prompt state shared by the production registration in `main.tsx` and
 * the prompt component. Keeping the registration here means the prompt never
 * initiates its own registration and can be tested in isolation.
 */
export const usePwaUpdateStore = create<PwaUpdateState>((set) => ({
  registration: null,
  dismissed: false,
  deferred: false,
  markWaiting: (registration) =>
    set({ registration, dismissed: false, deferred: false }),
  dismiss: () => set({ dismissed: true, deferred: false }),
  defer: () => set({ deferred: true }),
  clearDeferred: () => set({ deferred: false }),
}))
