import {
  createBrowserHistory,
  createRootRoute,
  createRoute,
  createRouter,
  redirect,
  type RouterHistory,
} from '@tanstack/react-router'
import { RootLayout } from './routes/RootLayout'
import {
  ChatPlaceholder,
  NoConversationPlaceholder,
  NotFoundPlaceholder,
  SettingsAboutPlaceholder,
  SettingsAppearancePlaceholder,
  SettingsBackupPlaceholder,
  SettingsDataPlaceholder,
  SettingsModelsPlaceholder,
  SettingsProviderDetailPlaceholder,
  SettingsProviderNewPlaceholder,
  SettingsProvidersPlaceholder,
} from './routes/placeholders'

const rootRoute = createRootRoute({
  component: RootLayout,
  notFoundComponent: NotFoundPlaceholder,
})

const indexRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/',
  component: NoConversationPlaceholder,
})

const chatRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: 'chat/$conversationId',
  component: ChatPlaceholder,
})

const settingsRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: 'settings',
})

const settingsIndexRoute = createRoute({
  getParentRoute: () => settingsRoute,
  path: '/',
  beforeLoad: () => {
    throw redirect({ to: '/settings/appearance' })
  },
})

const settingsAppearanceRoute = createRoute({
  getParentRoute: () => settingsRoute,
  path: 'appearance',
  component: SettingsAppearancePlaceholder,
})

const settingsProvidersRoute = createRoute({
  getParentRoute: () => settingsRoute,
  path: 'providers',
})

const settingsProvidersIndexRoute = createRoute({
  getParentRoute: () => settingsProvidersRoute,
  path: '/',
  component: SettingsProvidersPlaceholder,
})

const settingsProviderNewRoute = createRoute({
  getParentRoute: () => settingsProvidersRoute,
  path: 'new',
  component: SettingsProviderNewPlaceholder,
})

const settingsProviderDetailRoute = createRoute({
  getParentRoute: () => settingsProvidersRoute,
  path: '$providerId',
  component: SettingsProviderDetailPlaceholder,
})

const settingsModelsRoute = createRoute({
  getParentRoute: () => settingsRoute,
  path: 'models',
  component: SettingsModelsPlaceholder,
})

const settingsDataRoute = createRoute({
  getParentRoute: () => settingsRoute,
  path: 'data',
  component: SettingsDataPlaceholder,
})

const settingsBackupRoute = createRoute({
  getParentRoute: () => settingsRoute,
  path: 'backup',
  component: SettingsBackupPlaceholder,
})

const settingsAboutRoute = createRoute({
  getParentRoute: () => settingsRoute,
  path: 'about',
  component: SettingsAboutPlaceholder,
})

const routeTree = rootRoute.addChildren([
  indexRoute,
  chatRoute,
  settingsRoute.addChildren([
    settingsIndexRoute,
    settingsAppearanceRoute,
    settingsProvidersRoute.addChildren([
      settingsProvidersIndexRoute,
      settingsProviderNewRoute,
      settingsProviderDetailRoute,
    ]),
    settingsModelsRoute,
    settingsDataRoute,
    settingsBackupRoute,
    settingsAboutRoute,
  ]),
])

/**
 * Build the application router. Tests pass a memory history; the app uses
 * browser history so URLs are real paths rather than hash fragments.
 */
export function createAppRouter(history: RouterHistory = createBrowserHistory()) {
  return createRouter({
    routeTree,
    history,
  })
}

export const router = createAppRouter()

export type AppRouter = ReturnType<typeof createAppRouter>

declare module '@tanstack/react-router' {
  interface Register {
    router: AppRouter
  }
}
