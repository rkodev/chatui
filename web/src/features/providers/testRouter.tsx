import {
  createMemoryHistory,
  createRootRoute,
  createRoute,
  createRouter,
} from '@tanstack/react-router'
import type { ReactNode } from 'react'

/**
 * Build a minimal memory router for component tests that use `Link` or
 * `useParams`. Extra paths keep navigation targets resolvable without pulling
 * the full application route tree into every test.
 */
export function createTestRouter(
  component: () => ReactNode,
  path: string,
  initialEntry: string,
  extraPaths: string[] = [],
) {
  const rootRoute = createRootRoute()
  const routes = [
    createRoute({
      getParentRoute: () => rootRoute,
      path,
      component,
    }),
    ...extraPaths.map((extraPath) =>
      createRoute({
        getParentRoute: () => rootRoute,
        path: extraPath,
        component: () => null,
      }),
    ),
  ]

  return createRouter({
    routeTree: rootRoute.addChildren(routes),
    history: createMemoryHistory({ initialEntries: [initialEntry] }),
  })
}
