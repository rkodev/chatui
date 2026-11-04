import {
  createBrowserHistory,
  createRootRoute,
  createRoute,
  createRouter,
  redirect,
  type RouterHistory,
} from '@tanstack/react-router'
import { ChatRoute, ConversationSettingsRoute } from '../features/chat'
import { HomeRoute, OnboardingRoute } from '../features/onboarding'
import {
  AboutSettingsScreen,
  AppearanceSettingsScreen,
  BackupSettingsScreen,
  ChatSettingsRoute,
  DataSettingsScreen,
  ModelsSettingsRoute,
  SettingsLayout,
} from '../features/settings'
import { RootLayout } from './routes/RootLayout'
import {
  NotFoundPlaceholder,
  SettingsProviderDetailPlaceholder,
  SettingsProvidersPlaceholder,
} from './routes/placeholders'

const rootRoute = createRootRoute({
  component: RootLayout,
  notFoundComponent: NotFoundPlaceholder,
})

const indexRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/',
  component: HomeRoute,
})

const chatRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: 'chat/$conversationId',
  component: ChatRoute,
})

const conversationSettingsRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: 'chat/$conversationId/settings',
  component: ConversationSettingsRoute,
})

const settingsRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: 'settings',
  component: SettingsLayout,
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
  component: AppearanceSettingsScreen,
})

const settingsChatRoute = createRoute({
  getParentRoute: () => settingsRoute,
  path: 'chat',
  component: ChatSettingsRoute,
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
  component: OnboardingRoute,
})

const settingsProviderDetailRoute = createRoute({
  getParentRoute: () => settingsProvidersRoute,
  path: '$providerId',
  component: SettingsProviderDetailPlaceholder,
})

const settingsModelsRoute = createRoute({
  getParentRoute: () => settingsRoute,
  path: 'models',
  component: ModelsSettingsRoute,
})

const settingsDataRoute = createRoute({
  getParentRoute: () => settingsRoute,
  path: 'data',
  component: DataSettingsScreen,
})

const settingsBackupRoute = createRoute({
  getParentRoute: () => settingsRoute,
  path: 'backup',
  component: BackupSettingsScreen,
})

const settingsAboutRoute = createRoute({
  getParentRoute: () => settingsRoute,
  path: 'about',
  component: AboutSettingsScreen,
})

const routeTree = rootRoute.addChildren([
  indexRoute,
  chatRoute,
  conversationSettingsRoute,
  settingsRoute.addChildren([
    settingsIndexRoute,
    settingsAppearanceRoute,
    settingsChatRoute,
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
