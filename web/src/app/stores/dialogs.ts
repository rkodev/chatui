import { create } from 'zustand'

export interface DialogStore {
  activeDialogId: string | null
  openDialog: (id: string) => void
  closeDialog: () => void
}

/**
 * Generic dialog identity only. Dialog content and feature-specific state stay
 * with the component that owns the dialog.
 */
export const useDialogStore = create<DialogStore>((set) => ({
  activeDialogId: null,
  openDialog: (id) => set({ activeDialogId: id }),
  closeDialog: () => set({ activeDialogId: null }),
}))
