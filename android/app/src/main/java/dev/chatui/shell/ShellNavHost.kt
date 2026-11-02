package dev.chatui.shell

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import dev.chatui.feature.backup.BackupPlaceholder
import dev.chatui.feature.chat.ChatPlaceholder
import dev.chatui.feature.conversations.ConversationsPlaceholder
import dev.chatui.feature.onboarding.OnboardingPlaceholder
import dev.chatui.feature.providers.ProvidersPlaceholder
import dev.chatui.feature.settings.SettingsPlaceholder

/** Registers every shell destination. Shared by the app and navigation tests. */
fun NavGraphBuilder.shellDestinations() {
    composable(ShellDestination.Onboarding.route) { OnboardingPlaceholder() }
    composable(ShellDestination.Chat.route) { ChatPlaceholder() }
    composable(ShellDestination.Conversations.route) { ConversationsPlaceholder() }
    composable(ShellDestination.Providers.route) { ProvidersPlaceholder() }
    composable(ShellDestination.Settings.route) { SettingsPlaceholder() }
    composable(ShellDestination.Backup.route) { BackupPlaceholder() }
}

/** Navigation host for the placeholder shell. */
@Composable
fun ShellNavHost(
    navController: NavHostController,
    modifier: Modifier = Modifier,
) {
    NavHost(
        navController = navController,
        startDestination = ShellDestination.Conversations.route,
        modifier = modifier,
    ) {
        shellDestinations()
    }
}
