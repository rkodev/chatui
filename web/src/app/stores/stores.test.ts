import { beforeEach, describe, expect, it } from 'vitest'
import { useDialogStore } from './dialogs'
import { useDraftStore } from './drafts'
import { useSidebarStore } from './sidebar'
import { useToastStore } from './toasts'

beforeEach(() => {
  useSidebarStore.setState({ isOpen: false })
  useDialogStore.setState({ activeDialogId: null })
  useToastStore.setState({ toasts: [] })
  useDraftStore.setState({ drafts: {} })
})

describe('shell stores', () => {
  it('sidebar tracks only open state', () => {
    expect(Object.keys(useSidebarStore.getState()).sort()).toEqual([
      'close',
      'isOpen',
      'open',
      'toggle',
    ])
    expect(useSidebarStore.getState().isOpen).toBe(false)

    useSidebarStore.getState().open()
    expect(useSidebarStore.getState().isOpen).toBe(true)

    useSidebarStore.getState().toggle()
    expect(useSidebarStore.getState().isOpen).toBe(false)

    useSidebarStore.getState().close()
    expect(useSidebarStore.getState().isOpen).toBe(false)
  })

  it('dialogs track only the active dialog identity', () => {
    expect(Object.keys(useDialogStore.getState()).sort()).toEqual([
      'activeDialogId',
      'closeDialog',
      'openDialog',
    ])
    expect(useDialogStore.getState().activeDialogId).toBeNull()

    useDialogStore.getState().openDialog('confirm-delete')
    expect(useDialogStore.getState().activeDialogId).toBe('confirm-delete')

    useDialogStore.getState().closeDialog()
    expect(useDialogStore.getState().activeDialogId).toBeNull()
  })

  it('toasts add, dismiss, and clear ephemeral entries', () => {
    expect(Object.keys(useToastStore.getState()).sort()).toEqual([
      'addToast',
      'clearToasts',
      'dismissToast',
      'toasts',
    ])

    const generatedId = useToastStore.getState().addToast({ message: 'Saved' })
    expect(useToastStore.getState().toasts).toEqual([
      { id: generatedId, message: 'Saved', tone: 'info' },
    ])

    useToastStore.getState().addToast({ id: 'custom', message: 'Failed', tone: 'error' })
    expect(useToastStore.getState().toasts).toHaveLength(2)

    useToastStore.getState().dismissToast(generatedId)
    expect(useToastStore.getState().toasts).toEqual([
      { id: 'custom', message: 'Failed', tone: 'error' },
    ])

    useToastStore.getState().clearToasts()
    expect(useToastStore.getState().toasts).toEqual([])
  })

  it('drafts are keyed by conversation ID', () => {
    expect(Object.keys(useDraftStore.getState()).sort()).toEqual([
      'clearDraft',
      'drafts',
      'setDraft',
    ])

    useDraftStore.getState().setDraft('conversation-1', 'hello')
    useDraftStore.getState().setDraft('conversation-2', 'world')
    expect(useDraftStore.getState().drafts).toEqual({
      'conversation-1': 'hello',
      'conversation-2': 'world',
    })

    useDraftStore.getState().clearDraft('conversation-1')
    expect(useDraftStore.getState().drafts).toEqual({ 'conversation-2': 'world' })
  })
})
