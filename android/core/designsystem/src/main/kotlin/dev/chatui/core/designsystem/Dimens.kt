package dev.chatui.core.designsystem

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Semantic spacing, radius, and density tokens from spec section 11.12.
 *
 * The property names are the Compose form of the shared dotted token names:
 * `space.1` -> [space1], `radius.sm` -> [radiusSm]. The `density*` values are
 * the density-aware aliases used by shell and feature layouts; they mirror the
 * web `--density-space-*` custom properties.
 */
@Immutable
data class Dimens(
    val space1: Dp = 4.dp,
    val space2: Dp = 8.dp,
    val space3: Dp = 12.dp,
    val space4: Dp = 16.dp,
    val space5: Dp = 24.dp,
    val space6: Dp = 32.dp,
    val space7: Dp = 48.dp,
    val radiusSm: Dp = 6.dp,
    val radiusMd: Dp = 10.dp,
    val radiusLg: Dp = 16.dp,
    val radiusFull: Dp = 9999.dp,
    val densitySpace1: Dp = space1,
    val densitySpace2: Dp = space2,
    val densitySpace3: Dp = space3,
    val densitySpace4: Dp = space4,
    val densitySpace5: Dp = space5,
    val densitySpace6: Dp = space6,
    val densitySpace7: Dp = space7,
    val controlHeight: Dp = 40.dp,
    val gap: Dp = space3,
    val padding: Dp = space4,
    val touchTargetMin: Dp = 48.dp,
    val screenPadding: Dp = 20.dp,
    val sectionGap: Dp = 28.dp,
    val rowMinHeight: Dp = 52.dp,
    val cardPadding: Dp = 16.dp,
    val bubblePaddingH: Dp = 14.dp,
    val bubblePaddingV: Dp = 10.dp,
    val radiusXs: Dp = 8.dp,
    val radiusXl: Dp = 28.dp,
    val bubbleRadius: Dp = 20.dp,
    val bubbleTailRadius: Dp = 4.dp,
    val elevation1: Dp = 1.dp,
    val elevation2: Dp = 3.dp,
    val elevation3: Dp = 8.dp,
)

/** Comfortable density: the default spacing scale. */
val ComfortableDimens: Dimens = Dimens()

/** Compact density: reduced spacing and control height from `layout.css`. */
val CompactDimens: Dimens = Dimens(
    densitySpace1 = 2.dp,
    densitySpace2 = 4.dp,
    densitySpace3 = 8.dp,
    densitySpace4 = 12.dp,
    densitySpace5 = 16.dp,
    densitySpace6 = 24.dp,
    densitySpace7 = 32.dp,
    controlHeight = 32.dp,
    gap = 8.dp,
    padding = 12.dp,
)
