import { useNavigate, useRouterState } from '@tanstack/react-router'
import { useEffect, useRef, useState } from 'react'
import type { Conversation } from '../../domain'
import { useSidebarStore } from '../../app/stores'
import { useOnlineStatus } from '../chat/useOnlineStatus'
import { ConversationSidebar } from './ConversationSidebar'
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
  const close = useSidebarStore((state) => state.close)
  const history = useConversationHistory({
    service,
    activeConversationId,
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

  return (
    <ConversationSidebar
      conversations={history.conversations}
      activeConversationId={activeConversationId}
      status={history.status}
      error={history.error}
      online={online}
      pendingDelete={history.pendingDelete}
      search={{ ...search, onQueryChange: setSearchQuery }}
      onNewChat={() => void handleNewChat()}
      onSelect={handleSelect}
      onRename={(conversation, title) => history.rename(conversation.id, title)}
      onConfirmDelete={history.confirmDelete}
      onUndoDelete={history.undoDelete}
      onClose={close}
      isOpen={isOpen}
    />
  )
}
