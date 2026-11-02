import { create } from 'zustand'

export interface DraftStore {
  drafts: Record<string, string>
  setDraft: (conversationId: string, text: string) => void
  clearDraft: (conversationId: string) => void
}

/**
 * Per-conversation composer drafts keyed by conversation ID. Drafts are
 * ephemeral in M0; persistence belongs to the composer feature in M1.
 */
export const useDraftStore = create<DraftStore>((set) => ({
  drafts: {},
  setDraft: (conversationId, text) =>
    set((state) => ({
      drafts: { ...state.drafts, [conversationId]: text },
    })),
  clearDraft: (conversationId) =>
    set((state) => {
      const drafts = { ...state.drafts }
      delete drafts[conversationId]
      return { drafts }
    }),
}))
