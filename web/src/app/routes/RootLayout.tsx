import { Link, Outlet, useRouterState } from '@tanstack/react-router'
import { lazy, Suspense } from 'react'
import { useSidebarStore } from '../stores'
import { Button } from '../../ui/primitives'
import { ConversationHistoryProvider } from '../../features/conversations/ConversationHistoryProvider'
import { conversationIdFromPathname } from '../../features/conversations/routeConversationId'
import { SettingsProvider } from '../../features/settings/SettingsProvider'

// The history sidebar is not part of the first paint. Loading it on demand
// keeps conversation list/search code out of the initial entry chunk.
const ConversationSidebarContainer = lazy(() =>
  import('../../features/conversations/ConversationSidebarContainer').then((module) => ({
    default: module.ConversationSidebarContainer,
  })),
)

function RootLayoutContent() {
  const pathname = useRouterState({ select: (state) => state.location.pathname })
  const activeConversationId = conversationIdFromPathname(pathname)
  const toggleSidebar = useSidebarStore((state) => state.toggle)

  return (
    <div className="app-shell">
      <header className="app-shell__header">
        <div className="app-shell__header-content">
          <h1 className="app-shell__title">ChatUI</h1>
          <div className="app-shell__header-actions">
            <Link to="/settings" className="app-shell__settings-link">
              Settings
            </Link>
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
        </div>
      </header>
      <div className="app-shell__body">
        <Suspense fallback={null}>
          <ConversationSidebarContainer activeConversationId={activeConversationId} />
        </Suspense>
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
    <SettingsProvider>
      <ConversationHistoryProvider>
        <RootLayoutContent />
      </ConversationHistoryProvider>
    </SettingsProvider>
  )
}
