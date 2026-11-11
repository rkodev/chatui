package dev.chatui.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Two-or-more segment control with a selected pill.
 *
 * Selection is hoisted: the caller owns [selectedIndex] and updates it from
 * [onSelected].
 */
@Composable
fun SegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalChatUiColors.current
    val dimens = LocalDimens.current
    val outerShape = RoundedCornerShape(dimens.radiusXs)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.bgGrouped, outerShape)
            .padding(2.dp),
    ) {
        options.forEachIndexed { index, option ->
            val selected = index == selectedIndex
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(dimens.radiusXs - 2.dp))
                    .background(if (selected) colors.bgSurfaceRaised else Color.Transparent)
                    .clickable { onSelected(index) }
                    .padding(vertical = dimens.space2),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = option,
                    style = Typography.subhead,
                    color = if (selected) colors.fgPrimary else colors.fgSecondary,
                )
            }
        }
    }
}
