package dev.chatui.shell

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dagger.hilt.android.EntryPointAccessors
import dev.chatui.core.datastore.settings.AndroidSettings
import dev.chatui.core.designsystem.ChatUiTheme
import dev.chatui.core.model.Density
import dev.chatui.core.model.Theme
import dev.chatui.feature.conversations.ConversationEntryPoint
import dev.chatui.feature.conversations.ConversationService
import kotlinx.coroutines.launch

/** Test tags exposed by the shell for composition and navigation tests. */
object ShellTestTags {
    const val MenuButton = "shell-menu-button"
    const val Sidebar = "shell-sidebar"
    const val Drawer = "shell-drawer"
    const val MainContent = "shell-main-content"
    const val ExpandedLayout = "shell-expanded-layout"
    const val CompactLayout = "shell-compact-layout"
    const val ConversationsPlaceholder = "shell-conversations-placeholder"

    fun appearance(theme: Theme, density: Density): String =
        "shell-appearance-${theme.name}-${density.name}"

    fun navigationItem(route: String): String = "shell-nav-$route"
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

/**
 * Scaffold, top bar, and adaptive navigation for the shell.
 *
 * At width >= 840 dp the conversation sidebar is permanent. Below that width
 * the same sidebar is a modal drawer opened from the top app bar. Bottom
 * navigation is removed per the M3-00.2 decision; every destination remains
 * reachable from the sidebar.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3WindowSizeClassApi::class)
@Composable
fun ShellScaffold(
    navController: NavHostController,
    modifier: Modifier = Modifier,
    startDestination: String = ShellDestination.Conversations.route,
    conversationService: ConversationService? = null,
    windowInsets: WindowInsets = WindowInsets.safeDrawing,
) {
    val activity = LocalActivity.current
    val expanded = activity != null &&
        calculateWindowSizeClass(activity).widthSizeClass == WindowWidthSizeClass.Expanded
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val currentDestination = ShellDestination.entries.firstOrNull { it.matches(currentRoute) }
    val service = conversationService ?: rememberConversationService()

    val navigate: (String) -> Unit = { route ->
        scope.launch { drawerState.close() }
        navController.navigateToShellRoute(route)
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        // Consume safe-drawing once at the shell boundary. The top app bar
        // handles the top/status-bar and cutout insets; the content receives
        // the remaining bottom and horizontal insets through `innerPadding`.
        contentWindowInsets = windowInsets,
        topBar = {
            TopAppBar(
                title = { Text(currentDestination?.title ?: "ChatUI") },
                windowInsets = windowInsets.only(
                    WindowInsetsSides.Top + WindowInsetsSides.Horizontal,
                ),
                navigationIcon = {
                    if (!expanded) {
                        IconButton(
                            onClick = { scope.launch { drawerState.open() } },
                            modifier = Modifier.testTag(ShellTestTags.MenuButton),
                        ) {
                            Text("☰")
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        // Fallback drawer handler. ModalNavigationDrawer registers its own
        // predictive-back callback later in the composition, so that callback
        // keeps the predictive animation and this handler only runs if the
        // drawer implementation does not consume back itself.
        BackHandler(
            enabled = appHandlesBack(
                BackState(drawerOpen = !expanded && drawerState.isOpen),
            ),
        ) {
            scope.launch { drawerState.close() }
        }

        AdaptiveShell(
            expanded = expanded,
            drawerState = drawerState,
            modifier = Modifier
                .padding(innerPadding)
                // Mark the shell padding as consumed so descendants (for
                // example the chat IME padding) do not add it a second time.
                .consumeWindowInsets(innerPadding),
            sidebar = {
                ShellSidebar(
                    currentRoute = currentRoute,
                    conversationService = service,
                    onNavigate = navigate,
                )
            },
            mainContent = {
                ShellNavHost(
                    navController = navController,
                    startDestination = startDestination,
                    conversationService = conversationService,
                )
            },
        )
    }
}

@Composable
private fun rememberConversationService(): ConversationService {
    val context = LocalContext.current
    return remember(context.applicationContext) {
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            ConversationEntryPoint::class.java,
        ).conversationService()
    }
}

private fun NavHostController.navigateToShellRoute(route: String) {
    if (route.startsWith("chat/")) {
        navigate(route) {
            launchSingleTop = true
        }
    } else {
        navigate(route) {
            popUpTo(graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }
}
