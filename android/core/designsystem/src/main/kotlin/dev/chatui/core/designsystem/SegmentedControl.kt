package dev.chatui.core.designsystem

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/**
 * Two-or-more segment control with a selected pill.
 *
 * Selection is hoisted: the caller owns [selectedIndex] and updates it from
 * [onSelected]. Each segment springs to 0.96 while pressed and fires a light
 * haptic tick on selection.
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
    val haptics = rememberHaptics()
    val outerShape = RoundedCornerShape(dimens.radiusXs)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.bgGrouped, outerShape)
            .padding(2.dp),
    ) {
        options.forEachIndexed { index, option ->
            val selected = index == selectedIndex
            val interactionSource = remember { MutableInteractionSource() }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .sizeIn(minWidth = dimens.touchTargetMin, minHeight = dimens.touchTargetMin)
                    .pressScale(interactionSource)
                    .clip(RoundedCornerShape(dimens.radiusXs - 2.dp))
                    .background(if (selected) colors.bgSurfaceRaised else Color.Transparent)
                    .selectable(
                        selected = selected,
                        role = Role.Tab,
                        interactionSource = interactionSource,
                        indication = LocalIndication.current,
                    ) {
                        haptics.tick()
                        onSelected(index)
                    }
                    .padding(horizontal = dimens.space1, vertical = dimens.densitySpace2),
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
