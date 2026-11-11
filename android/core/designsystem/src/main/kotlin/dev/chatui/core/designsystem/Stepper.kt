package dev.chatui.core.designsystem

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** Numeric stepper with minus and plus actions. */
@Composable
fun Stepper(
    value: Int,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    min: Int = 0,
    max: Int = Int.MAX_VALUE,
    step: Int = 1,
) {
    val colors = LocalChatUiColors.current
    val haptics = rememberHaptics()
    val decreaseInteraction = remember { MutableInteractionSource() }
    val increaseInteraction = remember { MutableInteractionSource() }
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = {
                haptics.tick()
                onValueChange((value - step).coerceAtLeast(min))
            },
            enabled = value > min,
            interactionSource = decreaseInteraction,
            modifier = Modifier.pressScale(decreaseInteraction),
        ) {
            Icon(
                imageVector = Icons.Rounded.Remove,
                contentDescription = "Decrease",
                tint = colors.fgPrimary,
            )
        }
        Text(
            text = value.toString(),
            style = Typography.headline,
            color = colors.fgPrimary,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(min = 40.dp),
        )
        IconButton(
            onClick = {
                haptics.tick()
                onValueChange((value + step).coerceAtMost(max))
            },
            enabled = value < max,
            interactionSource = increaseInteraction,
            modifier = Modifier.pressScale(increaseInteraction),
        ) {
            Icon(
                imageVector = Icons.Rounded.Add,
                contentDescription = "Increase",
                tint = colors.fgPrimary,
            )
        }
    }
}
