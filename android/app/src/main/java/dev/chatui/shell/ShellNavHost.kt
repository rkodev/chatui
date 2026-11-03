package dev.chatui.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import dagger.hilt.android.EntryPointAccessors
import dev.chatui.core.datastore.settings.AndroidSettings
import dev.chatui.feature.backup.BackupPlaceholder
import dev.chatui.feature.chat.ChatPlaceholder
import dev.chatui.feature.conversations.ConversationsPlaceholder
import dev.chatui.feature.onboarding.OnboardingEntryPoint
import dev.chatui.feature.onboarding.OnboardingRoute
import dev.chatui.feature.providers.ProvidersPlaceholder
import dev.chatui.feature.settings.SettingsPlaceholder

/** Registers every shell destination. Shared by the app and navigation tests. */
fun NavGraphBuilder.shellDestinations(navController: NavHostController) {
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
    composable(ShellDestination.Conversations.route) { ConversationsPlaceholder() }
    composable(ShellDestination.Providers.route) { ProvidersPlaceholder() }
    composable(ShellDestination.Settings.route) { SettingsPlaceholder() }
    composable(ShellDestination.Backup.route) { BackupPlaceholder() }
}

/** First-run routing: onboarding until setup completes, then conversations. */
fun shellStartDestination(settings: AndroidSettings): String =
    if (settings.onboardingCompleted) {
        ShellDestination.Conversations.route
    } else {
        ShellDestination.Onboarding.route
    }

/** Navigation host for the shell. */
@Composable
fun ShellNavHost(
    navController: NavHostController,
    modifier: Modifier = Modifier,
    startDestination: String = ShellDestination.Conversations.route,
) {
    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = modifier,
    ) {
        shellDestinations(navController)
    }
}
