import { create } from 'zustand'

export interface SidebarStore {
  isOpen: boolean
  open: () => void
  close: () => void
  toggle: () => void
}

/**
 * Ephemeral sidebar visibility. No conversation list or navigation state lives
 * here; those belong to feature stores in later milestones.
 */
export const useSidebarStore = create<SidebarStore>((set) => ({
  isOpen: false,
  open: () => set({ isOpen: true }),
  close: () => set({ isOpen: false }),
  toggle: () => set((state) => ({ isOpen: !state.isOpen })),
}))
