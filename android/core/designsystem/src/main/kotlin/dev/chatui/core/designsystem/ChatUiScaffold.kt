package dev.chatui.core.designsystem

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp

enum class TitleBarStyle { Large, Compact }

/**
 * Shared screen scaffold with a large-title bar that collapses as the caller's
 * scroll state moves.
 *
 * Pass either a [ScrollState] or a [LazyListState] so the bar can follow the
 * content. The bar uses a translucent canvas surface and never hides content:
 * the content receives the scaffold padding. A hairline separator fades in as
 * the title collapses, and [navigationIcon] renders a leading action (for
 * example the compact-width menu button).
 */
@Composable
fun ChatUiScaffold(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    navigationIcon: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    snackbarHost: @Composable () -> Unit = {},
    scrollState: ScrollState? = null,
    lazyListState: LazyListState? = null,
    contentWindowInsets: WindowInsets = WindowInsets.systemBars,
    onSubtitleClick: (() -> Unit)? = null,
    subtitleTestTag: String? = null,
    showTopBar: Boolean = true,
    titleBarStyle: TitleBarStyle = TitleBarStyle.Large,
    onTitleClick: (() -> Unit)? = null,
    titleContentDescription: String? = null,
    titleModifier: Modifier = Modifier,
    content: @Composable (PaddingValues) -> Unit,
) {
    val scrollOffset = when {
        titleBarStyle == TitleBarStyle.Compact -> null
        lazyListState != null -> remember(lazyListState) {
            derivedStateOf {
                lazyListState.firstVisibleItemIndex * 1_000 +
                    lazyListState.firstVisibleItemScrollOffset
            }
        }

        scrollState != null -> remember(scrollState) {
            derivedStateOf { scrollState.value }
        }

        else -> null
    }
    val collapseRangePx = with(LocalDensity.current) { 64.dp.toPx() }
    val collapseFraction = scrollOffset
        ?.let { (it.value / collapseRangePx).coerceIn(0f, 1f) }
        ?: 0f

    Scaffold(
        modifier = modifier,
        contentWindowInsets = contentWindowInsets,
        topBar = {
            if (showTopBar) {
                if (titleBarStyle == TitleBarStyle.Compact) {
                    CompactTitleBar(
                        title = title,
                        subtitle = subtitle,
                        navigationIcon = navigationIcon,
                        actions = actions,
                        onClick = onTitleClick ?: onSubtitleClick,
                        controlDescription = titleContentDescription,
                        contentWindowInsets = contentWindowInsets,
                        controlModifier = titleModifier.then(
                            if (subtitleTestTag != null) Modifier.testTag(subtitleTestTag) else Modifier,
                        ),
                    )
                } else {
                    LargeTitleBar(
                        title = title,
                        subtitle = subtitle,
                        navigationIcon = navigationIcon,
                        actions = actions,
                        collapseFraction = collapseFraction,
                        onSubtitleClick = onSubtitleClick,
                        subtitleTestTag = subtitleTestTag,
                    )
                }
            }
        },
        snackbarHost = snackbarHost,
        content = content,
    )
}

@Composable
private fun CompactTitleBar(
    title: String,
    subtitle: String?,
    navigationIcon: (@Composable () -> Unit)?,
    actions: @Composable RowScope.() -> Unit,
    onClick: (() -> Unit)?,
    controlDescription: String?,
    contentWindowInsets: WindowInsets,
    controlModifier: Modifier,
) {
    val colors = LocalChatUiColors.current
    val dimens = LocalDimens.current
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val availableHeight = maxHeight - with(density) {
            (contentWindowInsets.getTop(density) + contentWindowInsets.getBottom(density)).toDp()
        }
        val short = availableHeight < 240.dp
        val tight = availableHeight < 160.dp
        val modelOnly = short && subtitle != null
        val showSubtitle = subtitle != null && !short
        val titleLines = if (density.fontScale > 1f && !short) 2 else 1
        Surface(color = colors.bgCanvas, contentColor = colors.fgPrimary) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .heightIn(
                        min = when {
                            tight -> dimens.touchTargetMin
                            showSubtitle -> 72.dp
                            else -> 56.dp
                        },
                    )
                    .padding(horizontal = dimens.narrowScreenPadding)
                    .testTag("chat-ui-top-bar"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                navigationIcon?.invoke()
                Row(
                    modifier = controlModifier
                        .weight(1f)
                        .heightIn(min = dimens.touchTargetMin)
                        .then(
                            if (onClick != null) {
                                Modifier.clickable(
                                    role = Role.Button,
                                    onClickLabel = controlDescription,
                                    onClick = onClick,
                                )
                            } else {
                                Modifier
                            },
                        )
                        .semantics(mergeDescendants = true) {
                            if (controlDescription != null) contentDescription = controlDescription
                            if (modelOnly) stateDescription = title
                        }
                        .padding(vertical = if (tight) 0.dp else dimens.space1),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (modelOnly) subtitle.orEmpty() else title,
                            style = Typography.title3,
                            color = colors.fgPrimary,
                            maxLines = titleLines,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (showSubtitle) {
                            Text(
                                text = subtitle.orEmpty(),
                                style = Typography.footnote,
                                color = colors.fgSecondary,
                                maxLines = titleLines,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    if (onClick != null) {
                        Icon(
                            imageVector = Icons.Rounded.ExpandMore,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = colors.fgSecondary,
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, content = actions)
            }
        }
    }
}

@Composable
private fun LargeTitleBar(
    title: String,
    subtitle: String?,
    navigationIcon: (@Composable () -> Unit)?,
    actions: @Composable RowScope.() -> Unit,
    collapseFraction: Float,
    onSubtitleClick: (() -> Unit)?,
    subtitleTestTag: String?,
) {
    val colors = LocalChatUiColors.current
    val dimens = LocalDimens.current
    val expandedHeight = 96.dp
    val collapsedHeight = 56.dp
    val barHeight = lerp(expandedHeight, collapsedHeight, collapseFraction)
    val collapsed = collapseFraction > 0.5f

    Surface(
        color = colors.bgCanvas.copy(alpha = 0.92f),
        contentColor = colors.fgPrimary,
    ) {
        Box {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = barHeight)
                    .statusBarsPadding()
                    .padding(horizontal = dimens.screenPadding),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = collapsedHeight),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    navigationIcon?.invoke()
                    Text(
                        text = title,
                        style = if (collapsed) Typography.headline else Typography.largeTitle,
                        color = colors.fgPrimary,
                        maxLines = if (collapsed) 1 else 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        content = actions,
                    )
                }
                if (subtitle != null && !collapsed) {
                    Text(
                        text = subtitle,
                        style = Typography.subhead,
                        color = colors.fgSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .then(
                                if (onSubtitleClick != null) {
                                    Modifier.clickable(onClick = onSubtitleClick)
                                } else {
                                    Modifier
                                },
                            )
                            .then(
                                if (subtitleTestTag != null) {
                                    Modifier.testTag(subtitleTestTag)
                                } else {
                                    Modifier
                                },
                            ),
                    )
                }
            }
            HorizontalDivider(
                color = colors.separator,
                thickness = 0.5.dp,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .alpha(collapseFraction),
            )
        }
    }
}
