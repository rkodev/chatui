package dev.chatui.shell

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.MoreVert
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
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
import dev.chatui.core.designsystem.TitleBarStyle
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
import dev.chatui.feature.conversations.ConversationListViewModel
import dev.chatui.feature.conversations.ConversationRoute
import dev.chatui.feature.providers.ProviderManagementService
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
    const val MoreButton = "shell-more-button"
    const val BackButton = "shell-back-button"
    const val ScreenContent = "shell-screen-content"
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
    windowInsets: WindowInsets = WindowInsets.safeDrawing,
) {
    ChatUiTheme(
        themeMode = settings.theme,
        density = settings.density,
        dynamicColor = settings.dynamicColor,
        hapticsEnabled = settings.hapticFeedback,
    ) {
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
            Box(
                modifier = modifier.fillMaxSize()
                    .testTag(ShellTestTags.appearance(settings.theme, settings.density)),
            ) {
                ShellScaffold(
                    navController = navController,
                    startDestination = destination,
                    conversationService = service,
                    windowInsets = windowInsets,
                )
            }
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
    chatTopBarState: ChatTopBarState? = null,
    modelPickerState: AppModelPickerState? = null,
    providerManagementService: ProviderManagementService? = null,
) {
    val activity = LocalActivity.current
    val backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    val expanded = activity != null &&
        calculateWindowSizeClass(activity).widthSizeClass == WindowWidthSizeClass.Expanded
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val effectiveRoute = currentRoute ?: startDestination
    val currentDestination = ShellDestination.entries.firstOrNull { it.matches(effectiveRoute) }
    val chat = currentDestination == ShellDestination.Chat
    val historyRoot = currentDestination == ShellDestination.Conversations
    // Onboarding is a first-run, isolated experience: no top bar, menu button,
    // sidebar, or drawer. The start destination is used until the NavHost has
    // reported its first back-stack entry so the chrome never flashes on launch.
    val isolated = isIsolatedShellRoute(currentRoute ?: startDestination)
    val service = conversationService ?: rememberConversationService()
    val shortcuts = chatShortcutState ?: remember { ChatShortcutState() }
    val historyViewModel: ConversationListViewModel? = if (isolated) null else viewModel(
        key = "shell-history",
        factory = ConversationListViewModel.factory(service),
    )
    val historyState = historyViewModel?.state?.collectAsStateWithLifecycle()?.value
    val conversations = historyState?.groups.orEmpty().flatMap { it.conversations }
    val historyListState = rememberLazyListState()
    val currentConversationId = backStackEntry
        ?.arguments
        ?.getString(ChatDestination.ARG_CONVERSATION_ID)
    var helpOpen by remember { mutableStateOf(false) }
    var moreMenuExpanded by remember(currentRoute) { mutableStateOf(false) }
    val topBarState = chatTopBarState ?: remember { ChatTopBarState() }
    val snackbarHostState = remember { SnackbarHostState() }
    val moreFocus = remember { FocusRequester() }
    val modelFocus = remember { FocusRequester() }
    val pickerHost: AppModelPickerState = modelPickerState ?: viewModel(key = "app-model-picker")

    val navigate: (String) -> Unit = { route ->
        scope.launch { drawerState.close() }
        navController.navigate(route) { launchSingleTop = true }
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
        overlayOpen = {
            helpOpen || moreMenuExpanded || pickerHost.request != null || shortcuts.overlayOpen
        },
        onSearch = {
            navController.navigateToShellRoute(ShellDestination.Conversations.route)
            scope.launch { drawerState.close() }
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
        LocalAppModelPicker provides pickerHost,
    ) {
        val rootModifier = Modifier
            .fillMaxSize()
            .semantics { testTagsAsResourceId = true }
            .testTag(ShellTestTags.Root)
            .let { base -> if (isolated) base else base.shellShortcutHandler(actions) }

        Box(modifier = rootModifier) {
            BackHandler(enabled = !isolated && !chat && !historyRoot) {
                if (!navController.popBackStack()) {
                    navController.navigateToShellRoute(ShellDestination.Conversations.route)
                }
            }
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
                sidebarVisible = !isolated && (expanded || chat),
                sidebarWindowInsets = windowInsets,
                modifier = modifier.fillMaxSize(),
                sidebar = {
                    ShellSidebar(
                        currentRoute = currentRoute,
                        conversationService = service,
                        listViewModel = historyViewModel,
                        listState = historyListState,
                        onNavigate = navigate,
                        onNewChat = actions.onNewChat,
                    )
                },
                mainContent = {
                    ChatUiScaffold(
                        modifier = Modifier.fillMaxSize(),
                        contentWindowInsets = windowInsets,
                        showTopBar = !isolated,
                        titleBarStyle = TitleBarStyle.Compact,
                        title = if (chat) topBarState.title ?: "Chat" else shellTitle(effectiveRoute),
                        subtitle = if (chat) topBarState.subtitle else null,
                        onTitleClick = if (chat) topBarState.onOpenModelPicker else null,
                        titleContentDescription = if (chat && topBarState.onOpenModelPicker != null) {
                            "Change model"
                        } else {
                            null
                        },
                        titleModifier = Modifier.focusRequester(modelFocus),
                        subtitleTestTag = if (chat && topBarState.onOpenModelPicker != null) {
                            ChatScreenTestTags.ModelChip
                        } else {
                            null
                        },
                        snackbarHost = { ChatUiSnackbarHost(hostState = snackbarHostState) },
                        navigationIcon = when {
                            isolated || (expanded && (chat || historyRoot)) -> null
                            chat -> {
                                {
                                    IconAction(
                                        icon = Icons.Rounded.Menu,
                                        contentDescription = "Open navigation",
                                        onClick = { scope.launch { drawerState.open() } },
                                        modifier = Modifier.testTag(ShellTestTags.MenuButton),
                                    )
                                }
                            }
                            historyRoot -> null
                            else -> {
                                {
                                    IconAction(
                                        icon = Icons.AutoMirrored.Rounded.ArrowBack,
                                        contentDescription = "Back",
                                        onClick = {
                                            if (backDispatcher != null) {
                                                backDispatcher.onBackPressed()
                                            } else if (!navController.popBackStack()) {
                                                navController.navigateToShellRoute(
                                                    ShellDestination.Conversations.route,
                                                )
                                            }
                                        },
                                        modifier = Modifier.testTag(ShellTestTags.BackButton),
                                    )
                                }
                            }
                        },
                        actions = {
                            if (!isolated) {
                                Box {
                                    IconAction(
                                        icon = Icons.Rounded.MoreVert,
                                        contentDescription = "More",
                                        onClick = { moreMenuExpanded = true },
                                        modifier = Modifier
                                            .focusRequester(moreFocus)
                                            .testTag(ShellTestTags.MoreButton),
                                    )
                                    val dismiss = {
                                        moreMenuExpanded = false
                                        moreFocus.requestFocus()
                                        Unit
                                    }
                                    DropdownMenu(
                                        expanded = moreMenuExpanded,
                                        onDismissRequest = dismiss,
                                        modifier = Modifier.testTag(ChatScreenTestTags.MoreMenu),
                                    ) {
                                        if (chat) {
                                            topBarState.onOpenSettings?.let { openSettings ->
                                                DropdownMenuItem(
                                                    text = { Text("Conversation settings") },
                                                    onClick = { dismiss(); openSettings() },
                                                    modifier = Modifier.testTag(ChatScreenTestTags.ConversationSettings),
                                                )
                                            }
                                            topBarState.onShare?.let { share ->
                                                DropdownMenuItem(
                                                    text = { Text("Share as text") },
                                                    onClick = { dismiss(); share(ConversationShareFormat.TEXT) },
                                                    modifier = Modifier.testTag(ChatScreenTestTags.ShareText),
                                                )
                                                DropdownMenuItem(
                                                    text = { Text("Share as Markdown") },
                                                    onClick = { dismiss(); share(ConversationShareFormat.MARKDOWN) },
                                                    modifier = Modifier.testTag(ChatScreenTestTags.ShareMarkdown),
                                                )
                                            }
                                            DropdownMenuItem(
                                                text = { Text("New chat") },
                                                onClick = { dismiss(); actions.onNewChat() },
                                            )
                                        } else if (historyRoot) {
                                            DropdownMenuItem(
                                                text = { Text("Providers") },
                                                onClick = { dismiss(); navigate(ShellDestination.Providers.route) },
                                            )
                                            DropdownMenuItem(
                                                text = { Text("App settings") },
                                                onClick = { dismiss(); navigate(ShellDestination.Settings.route) },
                                            )
                                        }
                                        DropdownMenuItem(
                                            text = { Text("Keyboard shortcuts") },
                                            onClick = { dismiss(); helpOpen = true },
                                            modifier = Modifier.testTag(ShellTestTags.ShortcutHelpButton),
                                        )
                                    }
                                }
                            }
                        },
                    ) { innerPadding ->
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(innerPadding)
                                .consumeWindowInsets(innerPadding)
                                .testTag(ShellTestTags.ScreenContent),
                        ) {
                            ShellNavHost(
                                navController = navController,
                                startDestination = startDestination,
                                conversationService = service,
                                historyContent = {
                                    if (expanded) {
                                        ConversationsPlaceholder()
                                    } else {
                                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                                            ConversationRoute(
                                                service = service,
                                                listViewModel = historyViewModel,
                                                listState = historyListState,
                                                onNewChatRequested = actions.onNewChat,
                                                onOpenConversation = { navigate(ChatDestination.createRoute(it)) },
                                                modifier = Modifier.widthIn(max = 760.dp),
                                            )
                                        }
                                    }
                                },
                                onOpenModelPicker = { id, onApplied ->
                                    pickerHost.openConversation(id, onApplied)
                                },
                            )
                        }
                    }
                },
            )

            if (helpOpen) {
                ShortcutHelpDialog(onDismiss = {
                    helpOpen = false
                    moreFocus.requestFocus()
                })
            }
            AppModelPickerHost(
                state = pickerHost,
                service = providerManagementService,
                onClosed = { target ->
                    if (chat && target is dev.chatui.feature.providers.ModelPickerTarget.Conversation &&
                        target.conversationId == currentConversationId && topBarState.onOpenModelPicker != null
                    ) {
                        modelFocus.requestFocus()
                    }
                },
            )
        }
    }
}

private fun shellTitle(route: String): String = when (route) {
    ProviderModelsDestination.ROUTE -> "Manage models"
    SettingsDestination.APPEARANCE -> "Appearance"
    SettingsDestination.CHAT_DEFAULTS -> "Chat defaults"
    SettingsDestination.DATA -> "Data & storage"
    SettingsDestination.BACKUP, ShellDestination.Backup.route -> "Encrypted backup"
    SettingsDestination.ABOUT -> "About and privacy"
    else -> ShellDestination.entries.firstOrNull { it.matches(route) }?.title ?: "ChatUI"
}

/** True when the shell should render only the destination content. */
internal fun isIsolatedShellRoute(route: String?): Boolean =
    route == ShellDestination.Onboarding.route

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
