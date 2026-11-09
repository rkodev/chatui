package dev.chatui.shell

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dagger.hilt.android.EntryPointAccessors
import dev.chatui.core.datastore.settings.AndroidSettings
import dev.chatui.core.designsystem.ChatUiTheme
import dev.chatui.core.designsystem.LocalDimens
import dev.chatui.core.model.Density
import dev.chatui.core.model.Theme
import dev.chatui.feature.chat.ChatDrafts
import dev.chatui.feature.chat.ChatEntryPoint
import dev.chatui.feature.chat.ChatShortcutState
import dev.chatui.feature.chat.LocalChatShortcutState
import dev.chatui.feature.conversations.ConversationEntryPoint
import dev.chatui.feature.conversations.ConversationService
import dev.chatui.feature.conversations.CreateConversationResult
import dev.chatui.intent.ExternalIntentHandler
import dev.chatui.intent.ExternalIntentOutcome
import dev.chatui.intent.ExternalIntentRouter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/** Test tags exposed by the shell for composition and navigation tests. */
object ShellTestTags {
    const val Root = "shell-root"
    const val MenuButton = "shell-menu-button"
    const val ShortcutHelpButton = "shell-shortcut-help-button"
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
    externalIntentRouter: ExternalIntentRouter? = null,
    chatDrafts: ChatDrafts? = null,
) {
    ChatUiTheme(themeMode = settings.theme, density = settings.density) {
        val navController = rememberNavController()
        val service = conversationService ?: rememberConversationService()
        val router = externalIntentRouter ?: remember { ExternalIntentRouter() }
        val context = LocalContext.current
        val draftsProvider: () -> ChatDrafts = {
            chatDrafts ?: EntryPointAccessors.fromApplication(
                context.applicationContext,
                ChatEntryPoint::class.java,
            ).chatDependencies().drafts
        }

        // Resolve the cold-start destination once per activity creation. The
        // conversation store is queried so a stale last-active pointer is
        // cleared before the shell renders, and a pending deep link is not
        // raced by last-active restore.
        var startDestination by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(Unit) {
            startDestination = resolveShellStartDestination(
                settings = settings,
                conversationService = service,
                hasPendingExternalIntent = router.pending.value != null,
            )
        }

        // Collect after the start destination is known so a cold-start share
        // or deep link can navigate as soon as the shell is ready. Capture the
        // resolved destination in the effect key and block: the start-destination
        // resolver runs in another effect during the same apply pass, and reading
        // the live state here would let this effect navigate before the NavHost
        // has composed and set its graph.
        val resolvedStartDestination = startDestination
        LaunchedEffect(
            router,
            service,
            navController,
            settings.onboardingCompleted,
            resolvedStartDestination,
        ) {
            if (resolvedStartDestination == null) return@LaunchedEffect

            router.pending.collect { action ->
                if (action == null) return@collect

                val handler = ExternalIntentHandler(
                    router = router,
                    conversationService = service,
                    drafts = draftsProvider(),
                )
                when (val outcome = handler.handle(action, settings.onboardingCompleted)) {
                    is ExternalIntentOutcome.NavigateToChat ->
                        navController.navigateToShellRoute(outcome.route)

                    ExternalIntentOutcome.OpenProviders ->
                        navController.navigateToShellRoute(ShellDestination.Providers.route)

                    ExternalIntentOutcome.OpenOnboarding ->
                        navController.navigateToShellRoute(ShellDestination.Onboarding.route)
                }
            }
        }

        startDestination?.let { destination ->
            ShellScaffold(
                navController = navController,
                startDestination = destination,
                conversationService = service,
                modifier = modifier.testTag(
                    ShellTestTags.appearance(settings.theme, settings.density),
                ),
            )
        }
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
    chatShortcutState: ChatShortcutState? = null,
    shortcutActions: ShellShortcutActions? = null,
) {
    val activity = LocalActivity.current
    val dimens = LocalDimens.current
    val expanded = activity != null &&
        calculateWindowSizeClass(activity).widthSizeClass == WindowWidthSizeClass.Expanded
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val currentDestination = ShellDestination.entries.firstOrNull { it.matches(currentRoute) }
    val service = conversationService ?: rememberConversationService()
    val shortcuts = chatShortcutState ?: remember { ChatShortcutState() }
    val conversationFlow = remember(service) { service.observeConversations() }
    val conversations by conversationFlow.collectAsStateWithLifecycle(
        initialValue = emptyList(),
    )
    val currentConversationId = backStackEntry
        ?.arguments
        ?.getString(ChatDestination.ARG_CONVERSATION_ID)
    var helpOpen by remember { mutableStateOf(false) }

    val navigate: (String) -> Unit = { route ->
        scope.launch { drawerState.close() }
        navController.navigateToShellRoute(route)
    }

    fun navigateConversation(delta: Int): Boolean {
        if (conversations.isEmpty()) return false

        val currentIndex = conversations.indexOfFirst { it.id == currentConversationId }
        val targetIndex = when {
            currentIndex < 0 -> if (delta > 0) 0 else conversations.lastIndex
            else -> (currentIndex + delta).coerceIn(0, conversations.lastIndex)
        }
        val target = conversations[targetIndex]
        if (target.id == currentConversationId) return false

        navController.navigateToShellRoute(ChatDestination.createRoute(target.id))
        return true
    }

    val actions = shortcutActions ?: ShellShortcutActions(
        overlayOpen = { helpOpen || shortcuts.overlayOpen },
        onSearch = {
            navController.navigateToShellRoute(ShellDestination.Conversations.route)
            if (!expanded) scope.launch { drawerState.open() }
        },
        onNewChat = {
            scope.launch {
                try {
                    when (val result = service.createConversation()) {
                        is CreateConversationResult.Created ->
                            navController.navigateToShellRoute(
                                ChatDestination.createRoute(result.conversation.id),
                            )

                        is CreateConversationResult.NotConfigured ->
                            navController.navigateToShellRoute(ShellDestination.Providers.route)
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Throwable) {
                    // The list owns the detailed error copy. Send the user to
                    // provider setup so the failure is actionable.
                    navController.navigateToShellRoute(ShellDestination.Providers.route)
                }
            }
        },
        onStopOrClose = {
            when {
                shortcuts.stop() -> true
                helpOpen -> {
                    helpOpen = false
                    true
                }

                !expanded && drawerState.isOpen -> {
                    scope.launch { drawerState.close() }
                    true
                }

                else -> false
            }
        },
        onCopyLastResponse = { shortcuts.copyLastResponse() },
        onPreviousConversation = { navigateConversation(-1) },
        onNextConversation = { navigateConversation(1) },
    )

    CompositionLocalProvider(LocalChatShortcutState provides shortcuts) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .testTag(ShellTestTags.Root)
                .shellShortcutHandler(actions),
        ) {
            Scaffold(
                modifier = modifier.fillMaxSize(),
                // Consume safe-drawing once at the shell boundary. The top app
                // bar handles the top/status-bar and cutout insets; the content
                // receives the remaining bottom and horizontal insets through
                // `innerPadding`.
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
                                    modifier = Modifier
                                        .size(dimens.touchTargetMin)
                                        .testTag(ShellTestTags.MenuButton)
                                        .semantics {
                                            contentDescription = "Open navigation"
                                        },
                                ) {
                                    Text("☰", modifier = Modifier.clearAndSetSemantics {})
                                }
                            }
                        },
                        actions = {
                            IconButton(
                                onClick = { helpOpen = true },
                                modifier = Modifier
                                    .size(dimens.touchTargetMin)
                                    .testTag(ShellTestTags.ShortcutHelpButton)
                                    .semantics {
                                        contentDescription = "Keyboard shortcuts"
                                    },
                            ) {
                                Text("⌨", modifier = Modifier.clearAndSetSemantics {})
                            }
                        },
                    )
                },
            ) { innerPadding ->
                // Fallback drawer handler. ModalNavigationDrawer registers its
                // own predictive-back callback later in the composition, so
                // that callback keeps the predictive animation and this handler
                // only runs if the drawer implementation does not consume back
                // itself.
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
                        // example the chat IME padding) do not add it a second
                        // time.
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

            if (helpOpen) {
                ShortcutHelpDialog(onDismiss = { helpOpen = false })
            }
        }
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
