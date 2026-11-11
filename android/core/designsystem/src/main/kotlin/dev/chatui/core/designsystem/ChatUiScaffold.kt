package dev.chatui.core.designsystem

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp

/**
 * Shared screen scaffold with a large-title bar that collapses as the caller's
 * scroll state moves.
 *
 * Pass either a [ScrollState] or a [LazyListState] so the bar can follow the
 * content. The bar uses a translucent canvas surface and never hides content:
 * the content receives the scaffold padding.
 */
@Composable
fun ChatUiScaffold(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
    snackbarHost: @Composable () -> Unit = {},
    scrollState: ScrollState? = null,
    lazyListState: LazyListState? = null,
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
        topBar = {
            LargeTitleBar(
                title = title,
                subtitle = subtitle,
                actions = actions,
                collapseFraction = collapseFraction,
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
    actions: @Composable RowScope.() -> Unit,
    collapseFraction: Float,
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
                )
            }
        }
    }
}
