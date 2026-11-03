package dev.chatui.shell

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dev.chatui.core.datastore.settings.AndroidSettings
import dev.chatui.core.designsystem.ChatUiTheme
import dev.chatui.core.model.Density
import dev.chatui.core.model.Theme
import dev.chatui.feature.conversations.ConversationService

/** Test tags exposed by the shell for composition and navigation tests. */
object ShellTestTags {
    fun appearance(theme: Theme, density: Density): String =
        "shell-appearance-${theme.name}-${density.name}"
}

/**
 * Themed application shell.
 *
 * The persisted theme and density are applied before the navigation host is
 * composed, so every destination renders with the selected tokens.
 */
@Composable
fun ChatUiApp(
    settings: AndroidSettings,
    modifier: Modifier = Modifier,
    conversationService: ConversationService? = null,
) {
    ChatUiTheme(themeMode = settings.theme, density = settings.density) {
        val navController = rememberNavController()
        ShellScaffold(
            navController = navController,
            startDestination = shellStartDestination(settings),
            conversationService = conversationService,
            modifier = modifier.testTag(
                ShellTestTags.appearance(settings.theme, settings.density),
            ),
        )
    }
}

/** Scaffold, top bar, and bottom navigation for the shell. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShellScaffold(
    navController: NavHostController,
    modifier: Modifier = Modifier,
    startDestination: String = ShellDestination.Conversations.route,
    conversationService: ConversationService? = null,
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val currentDestination = ShellDestination.entries.firstOrNull { it.matches(currentRoute) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(title = { Text(currentDestination?.title ?: "ChatUI") })
        },
        bottomBar = {
            NavigationBar {
                ShellDestination.entries.forEach { destination ->
                    NavigationBarItem(
                        selected = destination.matches(currentRoute),
                        onClick = { navController.navigateToShellDestination(destination) },
                        icon = { Text(destination.shortLabel) },
                        label = { Text(destination.label) },
                    )
                }
            }
        },
    ) { innerPadding ->
        ShellNavHost(
            navController = navController,
            modifier = Modifier.padding(innerPadding),
            startDestination = startDestination,
            conversationService = conversationService,
        )
    }
}

private fun NavHostController.navigateToShellDestination(destination: ShellDestination) {
    navigate(destination.route) {
        popUpTo(graph.findStartDestination().id) {
            saveState = true
        }
        launchSingleTop = true
        restoreState = true
    }
}
