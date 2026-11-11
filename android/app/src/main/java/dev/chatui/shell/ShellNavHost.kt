package dev.chatui.shell

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
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
import dev.chatui.core.designsystem.LocalReducedMotion
import dev.chatui.core.designsystem.MotionSpecs
import dev.chatui.core.model.Conversation
import dev.chatui.feature.backup.BackupEntryPoint
import dev.chatui.feature.backup.BackupSettingsRoute
import dev.chatui.feature.backup.BackupSettingsService
import dev.chatui.feature.chat.ChatEntryPoint
import dev.chatui.feature.chat.ChatPlaceholder
import dev.chatui.feature.chat.ChatRoute
import dev.chatui.feature.conversations.ConversationEntryPoint
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
import kotlinx.coroutines.CancellationException

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
                try {
                    resolvedConversationService.setLastActiveConversation(conversationId)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Throwable) {
                    // A deep link to a missing conversation still renders the
                    // not-found state. Never persist a pointer to a
                    // conversation that does not exist.
                }
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
        // The conversation history lives in the adaptive sidebar/drawer so the
        // main content can keep the current chat or destination.
        ConversationsPlaceholder()
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
            } else if (section == SettingsSection.BACKUP) {
                BackupSettingsRoute(service = rememberBackupSettingsService())
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
    composable(ShellDestination.Backup.route) {
        BackupSettingsRoute(service = rememberBackupSettingsService())
    }
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

@Composable
private fun rememberBackupSettingsService(): BackupSettingsService {
    val context = LocalContext.current
    return remember(context.applicationContext) {
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            BackupEntryPoint::class.java,
        ).backupSettingsService()
    }
}

/**
 * First-run routing: onboarding until setup completes, then a pending external
 * intent, then the last-active conversation when it still exists, otherwise the
 * conversation list.
 *
 * [lastActiveConversation] must be the result of
 * [ConversationService.getLastActiveConversation] so a stale pointer is never
 * opened. [hasPendingExternalIntent] covers deep links and shares that arrived
 * before the shell existed; those are routed by the intent collector after the
 * shell starts.
 */
fun shellStartDestination(
    settings: AndroidSettings,
    lastActiveConversation: Conversation? = null,
    hasPendingExternalIntent: Boolean = false,
): String {
    if (!settings.onboardingCompleted) {
        return ShellDestination.Onboarding.route
    }

    if (hasPendingExternalIntent) {
        return ShellDestination.Conversations.route
    }

    return lastActiveConversation?.let { ChatDestination.createRoute(it.id) }
        ?: ShellDestination.Conversations.route
}

/**
 * Resolves the cold-start destination against the conversation store.
 *
 * Onboarding wins over everything. A pending deep link or share wins over
 * last-active restore so the external intent is not raced by a restored chat.
 * A missing or deleted last-active conversation is cleared by
 * [ConversationService.getLastActiveConversation] and falls back to the list.
 */
suspend fun resolveShellStartDestination(
    settings: AndroidSettings,
    conversationService: ConversationService,
    hasPendingExternalIntent: Boolean,
): String {
    if (!settings.onboardingCompleted) {
        return ShellDestination.Onboarding.route
    }

    if (hasPendingExternalIntent) {
        return ShellDestination.Conversations.route
    }

    val lastActive = conversationService.getLastActiveConversation()
    return shellStartDestination(
        settings = settings,
        lastActiveConversation = lastActive,
    )
}

/** True when [route] is a chat destination with a conversation argument. */
private fun isChatRoute(route: String?): Boolean = route?.startsWith("chat/") == true

/** True when [route] is one of the top-level shell destinations. */
private fun isTopLevelRoute(route: String?): Boolean =
    ShellDestination.entries.any { it.route == route }

/**
 * Navigation host for the shell.
 *
 * Lateral navigation uses a shared-axis X slide, top-level destination
 * switches use a fade-through, and opening a chat uses a subtle scale + fade.
 * Every transition snaps when the system disables animations.
 */
@Composable
fun ShellNavHost(
    navController: NavHostController,
    modifier: Modifier = Modifier,
    startDestination: String = ShellDestination.Conversations.route,
    conversationService: ConversationService? = null,
) {
    val reducedMotion = LocalReducedMotion.current

    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = modifier,
        enterTransition = {
            when {
                reducedMotion -> EnterTransition.None
                isChatRoute(targetState.destination.route) ->
                    scaleIn(
                        initialScale = 0.96f,
                        animationSpec = MotionSpecs.emphasized(),
                    ) + fadeIn(animationSpec = MotionSpecs.emphasized())

                isTopLevelRoute(initialState.destination.route) &&
                    isTopLevelRoute(targetState.destination.route) ->
                    fadeIn(animationSpec = MotionSpecs.fadeThrough())

                else -> slideInHorizontally(
                    initialOffsetX = { it / 4 },
                    animationSpec = MotionSpecs.sharedAxis(),
                ) + fadeIn(animationSpec = MotionSpecs.sharedAxis())
            }
        },
        exitTransition = {
            when {
                reducedMotion -> ExitTransition.None
                isChatRoute(initialState.destination.route) ->
                    scaleOut(
                        targetScale = 0.96f,
                        animationSpec = MotionSpecs.emphasized(),
                    ) + fadeOut(animationSpec = MotionSpecs.emphasized())

                isTopLevelRoute(initialState.destination.route) &&
                    isTopLevelRoute(targetState.destination.route) ->
                    fadeOut(animationSpec = MotionSpecs.fadeThrough())

                else -> slideOutHorizontally(
                    targetOffsetX = { -it / 4 },
                    animationSpec = MotionSpecs.sharedAxis(),
                ) + fadeOut(animationSpec = MotionSpecs.sharedAxis())
            }
        },
        popEnterTransition = {
            if (reducedMotion) {
                EnterTransition.None
            } else {
                slideInHorizontally(
                    initialOffsetX = { -it / 4 },
                    animationSpec = MotionSpecs.sharedAxis(),
                ) + fadeIn(animationSpec = MotionSpecs.sharedAxis())
            }
        },
        popExitTransition = {
            if (reducedMotion) {
                ExitTransition.None
            } else {
                slideOutHorizontally(
                    targetOffsetX = { it / 4 },
                    animationSpec = MotionSpecs.sharedAxis(),
                ) + fadeOut(animationSpec = MotionSpecs.sharedAxis())
            }
        },
    ) {
        shellDestinations(navController, conversationService)
    }
}
