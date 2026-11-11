package dev.chatui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chatui.core.designsystem.BrandMark
import dev.chatui.core.designsystem.GroupedSection
import dev.chatui.core.designsystem.LocalChatUiColors
import dev.chatui.core.designsystem.LocalDimens
import dev.chatui.core.designsystem.PrimaryButton
import dev.chatui.core.designsystem.SettingsRow
import dev.chatui.core.designsystem.Typography
import dev.chatui.feature.conversations.ConversationRoute
import dev.chatui.feature.conversations.ConversationService

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
        val colors = LocalChatUiColors.current
        ModalNavigationDrawer(
            drawerState = drawerState,
            modifier = modifier
                .fillMaxSize()
                .testTag(ShellTestTags.CompactLayout),
            scrimColor = colors.fgPrimary.copy(alpha = 0.32f),
            drawerContent = {
                ModalDrawerSheet(
                    // The shell already consumed safe-drawing insets before
                    // rendering the drawer, so the sheet must not add them
                    // again.
                    windowInsets = WindowInsets(0, 0, 0, 0),
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
 * destination list is grouped into Chats, Library, and Settings sections with
 * icon-led rows and a selected accent-soft pill.
 */
@Composable
fun ShellSidebar(
    currentRoute: String?,
    conversationService: ConversationService,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier,
    onNewChat: () -> Unit = {},
) {
    val colors = LocalChatUiColors.current
    val dimens = LocalDimens.current

    Column(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = dimens.screenPadding,
                    vertical = dimens.space3,
                ),
            verticalArrangement = Arrangement.spacedBy(dimens.space3),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BrandMark(size = 28.dp)
                Spacer(Modifier.width(dimens.space2))
                Text(
                    text = "ChatUI",
                    style = Typography.title2,
                    color = colors.fgPrimary,
                )
            }
            PrimaryButton(
                text = "New chat",
                onClick = onNewChat,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(ShellTestTags.NewChat),
            )
        }
        HorizontalDivider(color = colors.separator, thickness = 0.5.dp)

        GroupedSection(title = "Chats") {
            ShellSidebarDestination.Conversations.let { destination ->
                SidebarRow(
                    destination = destination,
                    selected = destination.matches(currentRoute),
                    onClick = { onNavigate(destination.route) },
                )
            }
        }

        ConversationRoute(
            service = conversationService,
            onOpenConversation = { conversationId ->
                onNavigate(ChatDestination.createRoute(conversationId))
            },
            modifier = Modifier.weight(1f),
        )

        GroupedSection(title = "Library") {
            ShellSidebarDestination.library.forEach { destination ->
                SidebarRow(
                    destination = destination,
                    selected = destination.matches(currentRoute),
                    onClick = { onNavigate(destination.route) },
                )
            }
        }

        GroupedSection(title = "Settings") {
            ShellSidebarDestination.settings.forEach { destination ->
                SidebarRow(
                    destination = destination,
                    selected = destination.matches(currentRoute),
                    onClick = { onNavigate(destination.route) },
                )
            }
        }

        Spacer(Modifier.height(dimens.space3))
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
