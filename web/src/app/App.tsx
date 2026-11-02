import { RouterProvider } from '@tanstack/react-router'
import { router as defaultRouter, type AppRouter } from './router'

interface AppProps {
  router?: AppRouter
}

/**
 * Application entry component. The router is injectable so tests can supply a
 * memory history while the browser uses history routing.
 */
export function App({ router = defaultRouter }: AppProps = {}) {
  return <RouterProvider router={router} />
}
