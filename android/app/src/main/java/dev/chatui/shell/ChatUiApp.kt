package dev.chatui.shell

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dagger.hilt.android.EntryPointAccessors
import dev.chatui.core.datastore.settings.AndroidSettings
import dev.chatui.core.designsystem.ChatUiScaffold
import dev.chatui.core.designsystem.ChatUiSnackbarHost
import dev.chatui.core.designsystem.ChatUiTheme
import dev.chatui.core.designsystem.IconAction
import dev.chatui.core.designsystem.LocalSnackbarHostState
import dev.chatui.core.model.Density
import dev.chatui.core.model.Theme
import dev.chatui.feature.chat.ChatDrafts
import dev.chatui.feature.chat.ChatEntryPoint
import dev.chatui.feature.chat.ChatScreenTestTags
import dev.chatui.feature.chat.ChatShortcutState
import dev.chatui.feature.chat.ChatTopBarState
import dev.chatui.feature.chat.ConversationShareFormat
import dev.chatui.feature.chat.LocalChatShortcutState
import dev.chatui.feature.chat.LocalChatTopBarState
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
    const val NewChat = "shell-new-chat"
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
    var shareMenuExpanded by remember { mutableStateOf(false) }
    val topBarState = remember { ChatTopBarState() }
    val snackbarHostState = remember { SnackbarHostState() }
    // Destinations do not expose their scroll state yet, so the large title
    // follows a hoisted state. UX-03 can wire the chat list state into this.
    val scrollState = rememberScrollState()

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

    CompositionLocalProvider(
        LocalChatShortcutState provides shortcuts,
        LocalChatTopBarState provides topBarState,
        LocalSnackbarHostState provides snackbarHostState,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .testTag(ShellTestTags.Root)
                .shellShortcutHandler(actions),
        ) {
            ChatUiScaffold(
                modifier = modifier.fillMaxSize(),
                // Consume safe-drawing once at the shell boundary. The large
                // title bar handles the top/status-bar and cutout insets; the
                // content receives the remaining bottom and horizontal insets
                // through `innerPadding`.
                contentWindowInsets = windowInsets,
                title = topBarState.title ?: currentDestination?.title ?: "ChatUI",
                subtitle = topBarState.subtitle,
                scrollState = scrollState,
                onSubtitleClick = topBarState.onOpenModelPicker,
                subtitleTestTag = if (topBarState.subtitle != null) {
                    ChatScreenTestTags.ModelChip
                } else {
                    null
                },
                snackbarHost = {
                    ChatUiSnackbarHost(hostState = snackbarHostState)
                },
                navigationIcon = if (!expanded) {
                    {
                        IconAction(
                            icon = Icons.Rounded.Menu,
                            contentDescription = "Open navigation",
                            onClick = { scope.launch { drawerState.open() } },
                            modifier = Modifier.testTag(ShellTestTags.MenuButton),
                        )
                    }
                } else {
                    null
                },
                actions = {
                    topBarState.onShare?.let { onShare ->
                        Box {
                            IconAction(
                                icon = Icons.Rounded.Share,
                                contentDescription = "Share conversation",
                                onClick = { shareMenuExpanded = true },
                                modifier = Modifier.testTag(ChatScreenTestTags.Share),
                            )
                            DropdownMenu(
                                expanded = shareMenuExpanded,
                                onDismissRequest = { shareMenuExpanded = false },
                                modifier = Modifier.testTag(ChatScreenTestTags.ShareMenu),
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Share as text") },
                                    onClick = {
                                        shareMenuExpanded = false
                                        onShare(ConversationShareFormat.TEXT)
                                    },
                                    modifier = Modifier.testTag(ChatScreenTestTags.ShareText),
                                )
                                DropdownMenuItem(
                                    text = { Text("Share as Markdown") },
                                    onClick = {
                                        shareMenuExpanded = false
                                        onShare(ConversationShareFormat.MARKDOWN)
                                    },
                                    modifier = Modifier.testTag(ChatScreenTestTags.ShareMarkdown),
                                )
                            }
                        }
                    }
                    topBarState.onOpenSettings?.let { onSettings ->
                        IconAction(
                            icon = Icons.Rounded.Settings,
                            contentDescription = "Conversation settings",
                            onClick = onSettings,
                            modifier = Modifier.testTag(
                                ChatScreenTestTags.ConversationSettings,
                            ),
                        )
                    }
                    IconAction(
                        icon = Icons.Rounded.Keyboard,
                        contentDescription = "Keyboard shortcuts",
                        onClick = { helpOpen = true },
                        modifier = Modifier.testTag(ShellTestTags.ShortcutHelpButton),
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
                            onNewChat = actions.onNewChat,
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
