import { Outlet, useRouterState } from '@tanstack/react-router'
import { useSidebarStore } from '../stores'
import { Button } from '../../ui/primitives'
import {
  ConversationHistoryProvider,
  ConversationSidebarContainer,
  conversationIdFromPathname,
} from '../../features/conversations'

function RootLayoutContent() {
  const pathname = useRouterState({ select: (state) => state.location.pathname })
  const activeConversationId = conversationIdFromPathname(pathname)
  const toggleSidebar = useSidebarStore((state) => state.toggle)

  return (
    <div className="app-shell">
      <header className="app-shell__header">
        <div className="app-shell__header-content">
          <h1 className="app-shell__title">ChatUI</h1>
          <Button
            variant="ghost"
            size="sm"
            className="app-shell__menu"
            onClick={toggleSidebar}
            aria-label="Open history"
          >
            History
          </Button>
        </div>
      </header>
      <div className="app-shell__body">
        <ConversationSidebarContainer activeConversationId={activeConversationId} />
        <main className="app-shell__main">
          <Outlet />
        </main>
      </div>
    </div>
  )
}

/**
 * Application shell with the conversation history sidebar.
 *
 * The history provider runs startup recovery before the routed content
 * renders, so a message left streaming by a previous session is never shown as
 * an active response.
 */
export function RootLayout() {
  return (
    <ConversationHistoryProvider>
      <RootLayoutContent />
    </ConversationHistoryProvider>
  )
}
