package dev.chatui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chatui.core.designsystem.LocalChatUiColors
import dev.chatui.core.designsystem.LocalDimens
import dev.chatui.core.designsystem.PrimaryButton
import dev.chatui.core.designsystem.SettingsRow
import dev.chatui.core.designsystem.Typography
import dev.chatui.feature.conversations.ConversationRoute
import dev.chatui.feature.conversations.ConversationService
import dev.chatui.feature.conversations.ConversationListViewModel

/** Width of the permanent sidebar at expanded widths. */
val ShellSidebarWidth: Dp = 280.dp

/**
 * Width-aware shell layout.
 *
 * At expanded widths the [sidebar] is rendered permanently next to the
 * [mainContent]. Below the breakpoint the sidebar becomes a modal drawer that
 * the top app bar opens. The drawer sheet has rounded trailing corners and an
 * animated scrim.
 */
@Composable
fun AdaptiveShell(
    expanded: Boolean,
    sidebar: @Composable () -> Unit,
    mainContent: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    drawerState: DrawerState = rememberDrawerState(DrawerValue.Closed),
    sidebarVisible: Boolean = true,
    sidebarWindowInsets: WindowInsets = WindowInsets.safeDrawing,
) {
    val colors = LocalChatUiColors.current
    if (expanded) {
        Surface(
            modifier = modifier.fillMaxSize(),
            color = colors.bgCanvas,
            contentColor = colors.fgPrimary,
        ) {
            Row(Modifier.fillMaxSize().testTag(ShellTestTags.ExpandedLayout)) {
                if (sidebarVisible) {
                    Box(
                        modifier = Modifier
                            .width(ShellSidebarWidth)
                            .fillMaxHeight()
                            .windowInsetsPadding(sidebarWindowInsets)
                            .testTag(ShellTestTags.Sidebar),
                    ) {
                        sidebar()
                    }
                    VerticalDivider()
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .testTag(ShellTestTags.MainContent),
                ) {
                    mainContent()
                }
            }
        }
    } else {
        ModalNavigationDrawer(
            drawerState = drawerState,
            modifier = modifier
                .fillMaxSize()
                .testTag(ShellTestTags.CompactLayout),
            scrimColor = colors.fgPrimary.copy(alpha = 0.32f),
            gesturesEnabled = sidebarVisible,
            drawerContent = {
                if (sidebarVisible) {
                    ModalDrawerSheet(
                        windowInsets = sidebarWindowInsets.union(WindowInsets.ime),
                        drawerShape = RoundedCornerShape(
                            topEnd = 16.dp,
                            bottomEnd = 16.dp,
                        ),
                        drawerContainerColor = colors.bgCanvas,
                        modifier = Modifier.testTag(ShellTestTags.Drawer),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .testTag(ShellTestTags.Sidebar),
                        ) {
                            if (drawerState.isOpen || drawerState.isAnimationRunning) sidebar()
                        }
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
 * host owns the title and sole New chat action. Only Providers and Settings
 * remain in the footer; short windows move that chrome into the scrolling list.
 */
@Composable
fun ShellSidebar(
    currentRoute: String?,
    conversationService: ConversationService,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier,
    onNewChat: (() -> Unit)? = null,
    listViewModel: ConversationListViewModel? = null,
    listState: LazyListState = rememberLazyListState(),
) {
    val colors = LocalChatUiColors.current
    val dimens = LocalDimens.current

    val footer: @Composable () -> Unit = {
        Column {
            HorizontalDivider(color = colors.separator, thickness = 0.5.dp)
            listOf(ShellSidebarDestination.Providers, ShellSidebarDestination.Settings).forEach { destination ->
                SidebarRow(
                    destination = destination,
                    selected = destination.matches(currentRoute),
                    onClick = { onNavigate(destination.route) },
                )
            }
        }
    }
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val short = maxHeight < 480.dp || LocalDensity.current.fontScale >= 1.5f
        Column(Modifier.fillMaxSize()) {
            ConversationRoute(
                service = conversationService,
                listViewModel = listViewModel,
                listState = listState,
                showNewChat = onNewChat == null,
                onNewChatRequested = onNewChat,
                onOpenConversation = { onNavigate(ChatDestination.createRoute(it)) },
                modifier = Modifier.weight(1f),
                header = {
                    Column(verticalArrangement = Arrangement.spacedBy(dimens.densitySpace2)) {
                        Text("Chats", style = Typography.title2, color = colors.fgPrimary)
                        if (onNewChat != null) {
                            PrimaryButton(
                                text = "New chat",
                                onClick = onNewChat,
                                modifier = Modifier.fillMaxWidth().testTag(ShellTestTags.NewChat),
                            )
                        }
                        if (short) footer()
                    }
                },
            )
            if (!short) footer()
        }
    }
}

@Composable
private fun SidebarRow(
    destination: ShellSidebarDestination,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalChatUiColors.current
    val dimens = LocalDimens.current

    SettingsRow(
        label = destination.label,
        icon = destination.icon,
        onClick = onClick,
        showDivider = false,
        modifier = Modifier
            .padding(horizontal = dimens.space1, vertical = 2.dp)
            .clip(RoundedCornerShape(12.dp))
            .then(
                if (selected) {
                    Modifier.background(colors.accentSoft)
                } else {
                    Modifier
                },
            )
            .testTag(ShellTestTags.navigationItem(destination.route)),
    )
}

/** Destinations exposed by the sidebar and drawer. */
enum class ShellSidebarDestination(
    val route: String,
    val label: String,
    val icon: ImageVector,
    val section: SidebarSection,
) {
    Conversations(
        ShellDestination.Conversations.route,
        "Conversations",
        Icons.AutoMirrored.Rounded.Chat,
        SidebarSection.Chats,
    ),
    Providers(
        ShellDestination.Providers.route,
        "Providers",
        Icons.Rounded.Extension,
        SidebarSection.Library,
    ),
    Data(
        SettingsDestination.DATA,
        "Data",
        Icons.Rounded.Storage,
        SidebarSection.Library,
    ),
    Backup(
        ShellDestination.Backup.route,
        "Backup",
        Icons.Rounded.Backup,
        SidebarSection.Library,
    ),
    Settings(
        ShellDestination.Settings.route,
        "Settings",
        Icons.Rounded.Settings,
        SidebarSection.Settings,
    ),
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

    companion object {
        /** Rows shown in the Library section. */
        val library: List<ShellSidebarDestination> =
            entries.filter { it.section == SidebarSection.Library }

        /** Rows shown in the Settings section. */
        val settings: List<ShellSidebarDestination> =
            entries.filter { it.section == SidebarSection.Settings }
    }
}

/** Sidebar grouping. */
enum class SidebarSection {
    Chats,
    Library,
    Settings,
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
