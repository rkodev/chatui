import { useNavigate, useRouterState } from '@tanstack/react-router'
import { useEffect, useRef, useState } from 'react'
import type { Conversation } from '../../domain'
import { useSidebarStore } from '../../app/stores'
import { copyText } from '../chat/clipboard'
import { useOnlineStatus } from '../chat/useOnlineStatus'
import { ShortcutsHelpDialog, useGlobalShortcuts } from '../shortcuts'
import { ConversationSidebar, type ConversationSidebarView } from './ConversationSidebar'
import { useConversationHistoryService } from './historyContext'
import { useConversationHistory } from './useConversationHistory'
import { useConversationSearch } from './useConversationSearch'

export interface ConversationSidebarContainerProps {
  activeConversationId: string | null
}

/**
 * Connects the presentational sidebar to storage, routing, and connectivity.
 *
 * New chat always creates a fresh conversation, so it never reuses a
 * conversation that is currently sending. Selecting a conversation closes the
 * mobile drawer before navigating.
 */
export function ConversationSidebarContainer({
  activeConversationId,
}: ConversationSidebarContainerProps) {
  const service = useConversationHistoryService()
  const navigate = useNavigate()
  const pathname = useRouterState({ select: (state) => state.location.pathname })
  const online = useOnlineStatus()
  const isOpen = useSidebarStore((state) => state.isOpen)
  const open = useSidebarStore((state) => state.open)
  const close = useSidebarStore((state) => state.close)
  const [view, setView] = useState<ConversationSidebarView>('active')
  const [shortcutsOpen, setShortcutsOpen] = useState(false)
  const history = useConversationHistory({
    service,
    activeConversationId,
    showArchived: view === 'archived',
    onDeleteCommitted: (conversation) => {
      if (conversation.id === activeConversationId) {
        navigate({ to: '/', replace: true })
      }
    },
  })
  const previousPathname = useRef(pathname)
  const refreshHistory = history.refresh
  const [searchQuery, setSearchQuery] = useState('')
  const search = useConversationSearch({ service, query: searchQuery })

  useEffect(() => {
    if (previousPathname.current === pathname) {
      return
    }

    previousPathname.current = pathname
    void refreshHistory()
  }, [pathname, refreshHistory])

  const handleNewChat = async () => {
    const conversation = await history.createNewChat()

    if (conversation) {
      setSearchQuery('')
      close()
      navigate({
        to: '/chat/$conversationId',
        params: { conversationId: conversation.id },
      })
    }
  }

  const handleSelect = (conversation: Conversation) => {
    setSearchQuery('')
    close()
    navigate({
      to: '/chat/$conversationId',
      params: { conversationId: conversation.id },
    })
  }

  const handleTogglePin = (conversation: Conversation) => {
    void history.setPinned(conversation.id, !conversation.pinned)
  }

  const handleToggleArchive = async (conversation: Conversation) => {
    await history.setArchived(conversation.id, !conversation.archived)

    if (!conversation.archived && conversation.id === activeConversationId) {
      navigate({ to: '/', replace: true })
    }
  }

  const handleSearchShortcut = () => {
    open()
    window.setTimeout(() => {
      document.getElementById('conversation-search')?.focus()
    }, 0)
  }

  const handleCopyLastResponse = () => {
    const responses = document.querySelectorAll<HTMLElement>('article[data-role="assistant"]')
    const last = responses.item(responses.length - 1)

    if (!last) {
      return
    }

    const text = last.dataset.messageText ?? last.textContent ?? ''

    if (text.trim() !== '') {
      void copyText(text)
    }
  }

  const handleNavigateConversation = (direction: 'previous' | 'next') => {
    const conversations = history.conversations

    if (conversations.length === 0) {
      return
    }

    const currentIndex = conversations.findIndex(
      (conversation) => conversation.id === activeConversationId,
    )
    const nextIndex =
      currentIndex === -1
        ? 0
        : Math.min(
            conversations.length - 1,
            Math.max(0, currentIndex + (direction === 'next' ? 1 : -1)),
          )
    const target = conversations[nextIndex]

    if (target && target.id !== activeConversationId) {
      handleSelect(target)
    }
  }

  const handleStopStreaming = () => {
    close()

    if (shortcutsOpen) {
      setShortcutsOpen(false)
    }

    window.dispatchEvent(new CustomEvent('chatui:stop-streaming'))
  }

  useGlobalShortcuts({
    onSearch: handleSearchShortcut,
    onNewChat: () => void handleNewChat(),
    onToggleHelp: () => setShortcutsOpen((current) => !current),
    onCopyLastResponse: handleCopyLastResponse,
    onNavigateConversation: handleNavigateConversation,
    onStopStreaming: handleStopStreaming,
  })

  return (
    <>
      <ConversationSidebar
        conversations={history.conversations}
        activeConversationId={activeConversationId}
        status={history.status}
        error={history.error}
        online={online}
        pendingDelete={history.pendingDelete}
        search={{ ...search, onQueryChange: setSearchQuery }}
        view={view}
        onViewChange={setView}
        onNewChat={() => void handleNewChat()}
        onSelect={handleSelect}
        onRename={(conversation, title) => history.rename(conversation.id, title)}
        onTogglePin={handleTogglePin}
        onToggleArchive={(conversation) => void handleToggleArchive(conversation)}
        onConfirmDelete={history.confirmDelete}
        onUndoDelete={history.undoDelete}
        onClose={close}
        isOpen={isOpen}
      />
      <ShortcutsHelpDialog open={shortcutsOpen} onOpenChange={setShortcutsOpen} />
    </>
  )
}
