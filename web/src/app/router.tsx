import {
  createBrowserHistory,
  createRootRoute,
  createRoute,
  createRouter,
  lazyRouteComponent,
  redirect,
  type RouterHistory,
} from '@tanstack/react-router'
import { SettingsLayout } from '../features/settings/SettingsLayout'
import { RootLayout } from './routes/RootLayout'
import { NotFoundPlaceholder } from './routes/placeholders'

// Route components are loaded on demand so the initial entry chunk only ships
// the shell. Settings, provider management, onboarding, and the chat view each
// live in their own chunk and are fetched when their route is first visited.
const HomeRoute = lazyRouteComponent(
  () => import('../features/onboarding/OnboardingRoute'),
  'HomeRoute',
)
const OnboardingRoute = lazyRouteComponent(
  () => import('../features/onboarding/OnboardingRoute'),
  'OnboardingRoute',
)
const ChatRoute = lazyRouteComponent(() => import('../features/chat/ChatRoute'), 'ChatRoute')
const ConversationSettingsRoute = lazyRouteComponent(
  () => import('../features/chat/ConversationSettingsRoute'),
  'ConversationSettingsRoute',
)
const AppearanceSettingsScreen = lazyRouteComponent(
  () => import('../features/settings/AppearanceSettingsScreen'),
  'AppearanceSettingsScreen',
)
const ChatSettingsRoute = lazyRouteComponent(
  () => import('../features/settings/ChatSettingsScreen'),
  'ChatSettingsRoute',
)
const ModelsSettingsRoute = lazyRouteComponent(
  () => import('../features/settings/ModelsSettingsScreen'),
  'ModelsSettingsRoute',
)
const DataSettingsScreen = lazyRouteComponent(
  () => import('../features/settings/DataSettingsScreen'),
  'DataSettingsScreen',
)
const BackupSettingsScreen = lazyRouteComponent(
  () => import('../features/settings/BackupSettingsScreen'),
  'BackupSettingsScreen',
)
const AboutSettingsScreen = lazyRouteComponent(
  () => import('../features/settings/AboutSettingsScreen'),
  'AboutSettingsScreen',
)
const ProvidersSettingsRoute = lazyRouteComponent(
  () => import('../features/providers/ProviderRoutes'),
  'ProvidersSettingsRoute',
)
const ProviderDetailRoute = lazyRouteComponent(
  () => import('../features/providers/ProviderRoutes'),
  'ProviderDetailRoute',
)

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
  component: ProvidersSettingsRoute,
})

const settingsProviderNewRoute = createRoute({
  getParentRoute: () => settingsProvidersRoute,
  path: 'new',
  component: OnboardingRoute,
})

const settingsProviderDetailRoute = createRoute({
  getParentRoute: () => settingsProvidersRoute,
  path: '$providerId',
  component: ProviderDetailRoute,
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
