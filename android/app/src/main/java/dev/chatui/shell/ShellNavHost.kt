package dev.chatui.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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
import dev.chatui.feature.backup.BackupPlaceholder
import dev.chatui.feature.chat.ChatPlaceholder
import dev.chatui.feature.conversations.ConversationEntryPoint
import dev.chatui.feature.conversations.ConversationRoute
import dev.chatui.feature.conversations.ConversationService
import dev.chatui.feature.onboarding.OnboardingEntryPoint
import dev.chatui.feature.onboarding.OnboardingRoute
import dev.chatui.feature.providers.ProvidersPlaceholder
import dev.chatui.feature.settings.SettingsPlaceholder

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
                navController.navigate(ShellDestination.Chat.route) {
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
        ChatPlaceholder(
            modifier = Modifier.testTag(ChatDestinationTestTags.conversation(conversationId)),
        )
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
    composable(ShellDestination.Providers.route) { ProvidersPlaceholder() }
    composable(ShellDestination.Settings.route) { SettingsPlaceholder() }
    composable(ShellDestination.Backup.route) { BackupPlaceholder() }
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
