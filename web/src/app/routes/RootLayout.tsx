import { Link, Outlet, useRouterState } from '@tanstack/react-router'
import { lazy, Suspense } from 'react'
import { useSidebarStore } from '../stores'
import { Button } from '../../ui/primitives'
import { Icon } from '../../ui/Icon'
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
  const sidebarOpen = useSidebarStore((state) => state.isOpen)

  return (
    <div className="app-shell">
      <a href="#main-content" className="app-shell__skip-link">
        Skip to content
      </a>
      <header className="app-shell__header">
        <div className="app-shell__header-content">
          <div className="app-shell__brand">
            <span className="app-shell__brand-mark">
              <Icon name="chat" size={21} />
            </span>
            <div>
              <h1 className="app-shell__title">ChatUI</h1>
              <p className="app-shell__tagline">Your models. Your space.</p>
            </div>
          </div>
          <div className="app-shell__header-actions">
            <span className="app-shell__local">
              <Icon name="shield" size={15} />
              Local-first, always
            </span>
            <Link
              to="/settings"
              className="app-shell__settings-link"
              activeProps={{ 'data-active': 'true' }}
              aria-label="Settings"
            >
              <Icon name="settings" size={16} />
              <span className="app-shell__settings-label">Settings</span>
            </Link>
            <Button
              variant="ghost"
              size="sm"
              className="app-shell__menu"
              onClick={toggleSidebar}
              aria-label="Open history"
              aria-expanded={sidebarOpen}
              aria-controls="conversation-history"
            >
              <Icon name="menu" size={18} />
              History
            </Button>
          </div>
        </div>
      </header>
      <div className="app-shell__body">
        <Suspense
          fallback={
            <div className="app-shell__sidebar-placeholder" aria-hidden="true">
              <span />
              <span />
            </div>
          }
        >
          <ConversationSidebarContainer activeConversationId={activeConversationId} />
        </Suspense>
        <main className="app-shell__main" id="main-content" tabIndex={-1}>
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
