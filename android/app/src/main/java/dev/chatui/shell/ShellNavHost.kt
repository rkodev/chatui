package dev.chatui.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import dagger.hilt.android.EntryPointAccessors
import dev.chatui.core.datastore.settings.AndroidSettings
import dev.chatui.core.datastore.settings.SettingsDataStore
import dev.chatui.feature.backup.BackupPlaceholder
import dev.chatui.feature.chat.ChatEntryPoint
import dev.chatui.feature.chat.ChatPlaceholder
import dev.chatui.feature.chat.ChatRoute
import dev.chatui.feature.conversations.ConversationEntryPoint
import dev.chatui.feature.conversations.ConversationRoute
import dev.chatui.feature.conversations.ConversationService
import dev.chatui.feature.onboarding.OnboardingEntryPoint
import dev.chatui.feature.onboarding.OnboardingRoute
import dev.chatui.feature.providers.ConversationSettingsRoute
import dev.chatui.feature.providers.ProviderDetailRoute
import dev.chatui.feature.providers.ProviderListRoute
import dev.chatui.feature.providers.ProviderManagementEntryPoint
import dev.chatui.feature.providers.ProviderManagementService
import dev.chatui.feature.settings.DataControlsService
import dev.chatui.feature.settings.SettingsEntryPoint
import dev.chatui.feature.settings.SettingsRoute
import dev.chatui.feature.settings.SettingsSection

/**
 * Registers every shell destination. Shared by the app and navigation tests.
 *
 * [conversationService] is injectable so composition tests can render the
 * conversation list without a Hilt application. Production passes null and the
 * destination resolves the service from the singleton graph.
 */
fun NavGraphBuilder.shellDestinations(
    navController: NavHostController,
    conversationService: ConversationService? = null,
) {
    composable(ShellDestination.Onboarding.route) {
        val context = LocalContext.current
        val service = remember(context.applicationContext) {
            EntryPointAccessors.fromApplication(
                context.applicationContext,
                OnboardingEntryPoint::class.java,
            ).onboardingService()
        }

        OnboardingRoute(
            service = service,
            onConnected = {
                navController.navigate(ShellDestination.Conversations.route) {
                    popUpTo(ShellDestination.Onboarding.route) { inclusive = true }
                    launchSingleTop = true
                }
            },
        )
    }
    composable(ShellDestination.Chat.route) { ChatPlaceholder() }
    composable(
        route = ChatDestination.ROUTE,
        arguments = listOf(
            navArgument(ChatDestination.ARG_CONVERSATION_ID) {
                type = NavType.StringType
            },
        ),
    ) { entry ->
        val conversationId = entry.arguments?.getString(ChatDestination.ARG_CONVERSATION_ID)
        val context = LocalContext.current

        if (conversationId == null) {
            ChatPlaceholder(
                modifier = Modifier.testTag(ChatDestinationTestTags.conversation(null)),
            )
        } else {
            val dependencies = remember(context.applicationContext) {
                EntryPointAccessors.fromApplication(
                    context.applicationContext,
                    ChatEntryPoint::class.java,
                ).chatDependencies()
            }
            val resolvedConversationService = conversationService ?: remember(
                context.applicationContext,
            ) {
                EntryPointAccessors.fromApplication(
                    context.applicationContext,
                    ConversationEntryPoint::class.java,
                ).conversationService()
            }
            var showSettings by remember(conversationId) { mutableStateOf(false) }

            LaunchedEffect(conversationId) {
                resolvedConversationService.setLastActiveConversation(conversationId)
            }

            ChatRoute(
                conversationId = conversationId,
                dependencies = dependencies,
                onBack = { navController.popBackStack() },
                onOpenProviders = {
                    navController.navigate(ShellDestination.Providers.route) {
                        launchSingleTop = true
                    }
                },
                onOpenConversationSettings = { showSettings = true },
                modifier = Modifier.testTag(
                    ChatDestinationTestTags.conversation(conversationId),
                ),
            )

            if (showSettings) {
                ConversationSettingsRoute(
                    service = rememberProviderManagementService(),
                    conversationId = conversationId,
                    onDismiss = { showSettings = false },
                    onDeleted = {
                        showSettings = false
                        navController.popBackStack()
                    },
                )
            }
        }
    }
    composable(ShellDestination.Conversations.route) {
        val context = LocalContext.current
        val service = conversationService ?: remember(context.applicationContext) {
            EntryPointAccessors.fromApplication(
                context.applicationContext,
                ConversationEntryPoint::class.java,
            ).conversationService()
        }

        ConversationRoute(
            service = service,
            onOpenConversation = { conversationId ->
                navController.navigate(ChatDestination.createRoute(conversationId)) {
                    launchSingleTop = true
                }
            },
        )
    }
    composable(ShellDestination.Providers.route) {
        val service = rememberProviderManagementService()

        ProviderListRoute(
            service = service,
            onOpenProvider = { providerId ->
                navController.navigate(ProviderDestination.createRoute(providerId)) {
                    launchSingleTop = true
                }
            },
        )
    }
    composable(
        route = ProviderDestination.ROUTE,
        arguments = listOf(
            navArgument(ProviderDestination.ARG_PROVIDER_ID) {
                type = NavType.StringType
            },
        ),
    ) { entry ->
        val providerId = entry.arguments?.getString(ProviderDestination.ARG_PROVIDER_ID)
        val service = rememberProviderManagementService()

        if (providerId == null) {
            ProviderListRoute(
                service = service,
                onOpenProvider = { id ->
                    navController.navigate(ProviderDestination.createRoute(id)) {
                        launchSingleTop = true
                    }
                },
            )
        } else {
            ProviderDetailRoute(service = service, providerId = providerId)
        }
    }
    composable(ShellDestination.Settings.route) {
        SettingsRoute(
            section = SettingsSection.ROOT,
            settingsStore = rememberSettingsDataStore(),
            service = rememberProviderManagementService(),
            dataControlsService = rememberDataControlsService(),
            onOpenSection = { section ->
                navController.navigate(SettingsDestination.routeFor(section)) {
                    launchSingleTop = true
                }
            },
            onOpenProviders = {
                navController.navigate(ShellDestination.Providers.route) {
                    launchSingleTop = true
                }
            },
        )
    }
    listOf(
        SettingsSection.APPEARANCE to SettingsDestination.APPEARANCE,
        SettingsSection.CHAT_DEFAULTS to SettingsDestination.CHAT_DEFAULTS,
        SettingsSection.DATA to SettingsDestination.DATA,
        SettingsSection.BACKUP to SettingsDestination.BACKUP,
        SettingsSection.ABOUT to SettingsDestination.ABOUT,
    ).forEach { (section, route) ->
        composable(route) {
            if (section == SettingsSection.DATA) {
                DataSettingsDestination(
                    navController = navController,
                    settingsStore = rememberSettingsDataStore(),
                    providerService = rememberProviderManagementService(),
                    dataControlsService = rememberDataControlsService(),
                )
            } else {
                SettingsRoute(
                    section = section,
                    settingsStore = rememberSettingsDataStore(),
                    service = rememberProviderManagementService(),
                    dataControlsService = rememberDataControlsService(),
                    onOpenSection = { target ->
                        navController.navigate(SettingsDestination.routeFor(target)) {
                            launchSingleTop = true
                        }
                    },
                    onOpenProviders = {
                        navController.navigate(ShellDestination.Providers.route) {
                            launchSingleTop = true
                        }
                    },
                )
            }
        }
    }
    composable(ShellDestination.Backup.route) { BackupPlaceholder() }
}

@Composable
private fun rememberSettingsDataStore(): SettingsDataStore {
    val context = LocalContext.current
    return remember(context.applicationContext) {
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            SettingsEntryPoint::class.java,
        ).settingsDataStore()
    }
}

@Composable
private fun rememberProviderManagementService(): ProviderManagementService {
    val context = LocalContext.current
    return remember(context.applicationContext) {
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            ProviderManagementEntryPoint::class.java,
        ).providerManagementService()
    }
}

@Composable
private fun rememberDataControlsService(): DataControlsService {
    val context = LocalContext.current
    return remember(context.applicationContext) {
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            SettingsEntryPoint::class.java,
        ).dataControlsService()
    }
}

/**
 * First-run routing: onboarding until setup completes, then the last-active
 * conversation when one is recorded, otherwise the conversation list.
 */
fun shellStartDestination(settings: AndroidSettings): String {
    if (!settings.onboardingCompleted) {
        return ShellDestination.Onboarding.route
    }

    val lastActive = settings.lastActiveConversationId?.takeIf { it.isNotBlank() }
    return if (lastActive != null) {
        ChatDestination.createRoute(lastActive)
    } else {
        ShellDestination.Conversations.route
    }
}

/** Navigation host for the shell. */
@Composable
fun ShellNavHost(
    navController: NavHostController,
    modifier: Modifier = Modifier,
    startDestination: String = ShellDestination.Conversations.route,
    conversationService: ConversationService? = null,
) {
    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = modifier,
    ) {
        shellDestinations(navController, conversationService)
    }
}
