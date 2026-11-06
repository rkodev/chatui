package dev.chatui.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chatui.feature.conversations.ConversationRoute
import dev.chatui.feature.conversations.ConversationService

/** Width of the permanent sidebar at expanded widths. */
val ShellSidebarWidth: Dp = 280.dp

/**
 * Width-aware shell layout.
 *
 * At expanded widths the [sidebar] is rendered permanently next to the
 * [mainContent]. Below the breakpoint the sidebar becomes a modal drawer that
 * the top app bar opens.
 */
@Composable
fun AdaptiveShell(
    expanded: Boolean,
    sidebar: @Composable () -> Unit,
    mainContent: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    drawerState: DrawerState = rememberDrawerState(DrawerValue.Closed),
) {
    if (expanded) {
        Row(
            modifier = modifier
                .fillMaxSize()
                .testTag(ShellTestTags.ExpandedLayout),
        ) {
            Box(
                modifier = Modifier
                    .width(ShellSidebarWidth)
                    .fillMaxHeight()
                    .testTag(ShellTestTags.Sidebar),
            ) {
                sidebar()
            }
            VerticalDivider()
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .testTag(ShellTestTags.MainContent),
            ) {
                mainContent()
            }
        }
    } else {
        ModalNavigationDrawer(
            drawerState = drawerState,
            modifier = modifier
                .fillMaxSize()
                .testTag(ShellTestTags.CompactLayout),
            drawerContent = {
                ModalDrawerSheet(
                    // The shell already consumed safe-drawing insets before
                    // rendering the drawer, so the sheet must not add them
                    // again.
                    windowInsets = WindowInsets(0, 0, 0, 0),
                    modifier = Modifier.testTag(ShellTestTags.Drawer),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag(ShellTestTags.Sidebar),
                    ) {
                        sidebar()
                    }
                }
            },
            content = {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag(ShellTestTags.MainContent),
                ) {
                    mainContent()
                }
            },
        )
    }
}

/**
 * Sidebar and drawer content.
 *
 * The conversation history is the existing [ConversationRoute], so grouping,
 * search, pin, archive, delete, and new-chat behavior are not duplicated. The
 * destination list keeps providers, settings, data, and backup reachable after
 * bottom navigation is removed.
 */
@Composable
fun ShellSidebar(
    currentRoute: String?,
    conversationService: ConversationService,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        ConversationRoute(
            service = conversationService,
            onOpenConversation = { conversationId ->
                onNavigate(ChatDestination.createRoute(conversationId))
            },
            modifier = Modifier.weight(1f),
        )
        HorizontalDivider()
        ShellSidebarDestination.entries.forEach { destination ->
            NavigationDrawerItem(
                label = { Text(destination.label) },
                selected = destination.matches(currentRoute),
                onClick = { onNavigate(destination.route) },
                modifier = Modifier.testTag(
                    ShellTestTags.navigationItem(destination.route),
                ),
            )
        }
    }
}

/** Destinations exposed by the sidebar and drawer. */
enum class ShellSidebarDestination(
    val route: String,
    val label: String,
) {
    Conversations(ShellDestination.Conversations.route, "Conversations"),
    Providers(ShellDestination.Providers.route, "Providers"),
    Settings(ShellDestination.Settings.route, "Settings"),
    Data(SettingsDestination.DATA, "Data"),
    Backup(ShellDestination.Backup.route, "Backup"),
    ;

    /** True when [route] belongs to this sidebar destination. */
    fun matches(route: String?): Boolean = when (this) {
        Conversations -> ShellDestination.Conversations.matches(route)
        Providers -> ShellDestination.Providers.matches(route)
        Settings -> route == ShellDestination.Settings.route ||
            (
                route?.startsWith("settings/") == true &&
                    route != SettingsDestination.DATA &&
                    route != SettingsDestination.BACKUP
                )
        Data -> route == SettingsDestination.DATA
        Backup -> ShellDestination.Backup.matches(route)
    }
}

/** Main-content placeholder shown when the conversation list lives in the sidebar. */
@Composable
fun ConversationsPlaceholder(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .testTag(ShellTestTags.ConversationsPlaceholder),
        contentAlignment = Alignment.Center,
    ) {
        Text("Select a conversation or start a new chat.")
    }
}
