package dev.chatui.core.designsystem

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** Shimmering placeholder block. */
@Composable
fun Skeleton(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(LocalDimens.current.radiusXs),
) {
    val colors = LocalChatUiColors.current
    val reduced = LocalReducedMotion.current
    val brush: Brush = if (reduced) {
        SolidColor(colors.skeletonBase)
    } else {
        val transition = rememberInfiniteTransition(label = "skeleton")
        val progress by transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1_200),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "skeletonShimmer",
        )
        val x = progress * 1_000f
        Brush.linearGradient(
            colors = listOf(
                colors.skeletonBase,
                colors.skeletonHighlight,
                colors.skeletonBase,
            ),
            start = Offset(x, 0f),
            end = Offset(x + 400f, 400f),
        )
    }
    Box(modifier = modifier.background(brush = brush, shape = shape))
}

/** A short column of skeleton rows for list loading states. */
@Composable
fun SkeletonList(
    modifier: Modifier = Modifier,
    count: Int = 3,
) {
    val dimens = LocalDimens.current
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(dimens.space3),
    ) {
        repeat(count) {
            Skeleton(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(dimens.rowMinHeight),
            )
        }
    }
}

/** Centered empty state with an optional icon and action. */
@Composable
fun EmptyState(
    title: String,
    modifier: Modifier = Modifier,
    body: String? = null,
    icon: ImageVector? = null,
    action: (@Composable () -> Unit)? = null,
) {
    val colors = LocalChatUiColors.current
    val dimens = LocalDimens.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(dimens.sectionGap),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(dimens.space3),
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = colors.fgMuted,
                modifier = Modifier.size(40.dp),
            )
        }
        Text(
            text = title,
            style = Typography.title3,
            color = colors.fgPrimary,
            textAlign = TextAlign.Center,
        )
        if (body != null) {
            Text(
                text = body,
                style = Typography.subhead,
                color = colors.fgSecondary,
                textAlign = TextAlign.Center,
            )
        }
        if (action != null) {
            Spacer(Modifier.height(dimens.space1))
            action()
        }
    }
}

/** Centered error state with a retry action. */
@Composable
fun ErrorState(
    title: String,
    modifier: Modifier = Modifier,
    body: String? = null,
    icon: ImageVector = Icons.Rounded.ErrorOutline,
    retryLabel: String = "Retry",
    onRetry: (() -> Unit)? = null,
) {
    EmptyState(
        title = title,
        modifier = modifier,
        body = body,
        icon = icon,
        action = onRetry?.let {
            {
                PrimaryButton(text = retryLabel, onClick = it)
            }
        },
    )
}

/** Severity of an [InlineBanner]. */
enum class BannerSeverity { Info, Success, Warning, Error }

/** Inline banner that reserves its space and never overlays content. */
@Composable
fun InlineBanner(
    severity: BannerSeverity,
    title: String,
    modifier: Modifier = Modifier,
    body: String? = null,
    action: (@Composable () -> Unit)? = null,
) {
    val colors = LocalChatUiColors.current
    val dimens = LocalDimens.current
    val accent = when (severity) {
        BannerSeverity.Info -> colors.accent
        BannerSeverity.Success -> colors.success
        BannerSeverity.Warning -> colors.warning
        BannerSeverity.Error -> colors.danger
    }
    val icon = when (severity) {
        BannerSeverity.Info -> Icons.Rounded.Info
        BannerSeverity.Success -> Icons.Rounded.Info
        BannerSeverity.Warning -> Icons.Rounded.Info
        BannerSeverity.Error -> Icons.Rounded.ErrorOutline
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(accent.copy(alpha = 0.12f), RoundedCornerShape(dimens.radiusMd))
            .padding(dimens.cardPadding),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(dimens.space3))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = Typography.headline,
                color = colors.fgPrimary,
            )
            if (body != null) {
                Text(
                    text = body,
                    style = Typography.subhead,
                    color = colors.fgSecondary,
                )
            }
            if (action != null) {
                Spacer(Modifier.height(dimens.space2))
                action()
            }
        }
    }
}
