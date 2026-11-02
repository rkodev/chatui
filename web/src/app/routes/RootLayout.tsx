import { Outlet } from '@tanstack/react-router'

/**
 * Minimal application shell. Feature navigation and chrome arrive in later
 * milestones; this layout only provides the routed outlet.
 */
export function RootLayout() {
  return (
    <div className="app-shell">
      <header className="app-shell__header">
        <h1 className="app-shell__title">ChatUI</h1>
      </header>
      <main className="app-shell__main">
        <Outlet />
      </main>
    </div>
  )
}
