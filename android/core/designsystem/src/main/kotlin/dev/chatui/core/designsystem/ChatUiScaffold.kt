package dev.chatui.core.designsystem

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp

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
    content: @Composable (PaddingValues) -> Unit,
) {
    val scrollOffset = when {
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
            LargeTitleBar(
                title = title,
                subtitle = subtitle,
                navigationIcon = navigationIcon,
                actions = actions,
                collapseFraction = collapseFraction,
                onSubtitleClick = onSubtitleClick,
                subtitleTestTag = subtitleTestTag,
            )
        },
        snackbarHost = snackbarHost,
        content = content,
    )
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
                    .height(barHeight)
                    .statusBarsPadding()
                    .padding(horizontal = dimens.screenPadding),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(collapsedHeight),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    navigationIcon?.invoke()
                    Text(
                        text = title,
                        style = if (collapsed) Typography.headline else Typography.largeTitle,
                        color = colors.fgPrimary,
                        maxLines = 1,
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
                        maxLines = 1,
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
