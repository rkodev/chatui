package dev.chatui.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp

/** Filled accent button for the primary action on a screen. */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = LocalChatUiColors.current
    val dimens = LocalDimens.current
    Button(
        onClick = onClick,
        modifier = modifier.sizeIn(
            minWidth = dimens.touchTargetMin,
            minHeight = dimens.touchTargetMin,
        ),
        enabled = enabled,
        shape = RoundedCornerShape(dimens.radiusMd),
        colors = ButtonDefaults.buttonColors(
            containerColor = colors.accent,
            contentColor = colors.fgOnAccent,
        ),
    ) {
        Text(text = text, style = Typography.headline)
    }
}

/** Outlined button for secondary actions. */
@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = LocalChatUiColors.current
    val dimens = LocalDimens.current
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.sizeIn(
            minWidth = dimens.touchTargetMin,
            minHeight = dimens.touchTargetMin,
        ),
        enabled = enabled,
        shape = RoundedCornerShape(dimens.radiusMd),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.accent),
    ) {
        Text(text = text, style = Typography.headline)
    }
}

/** Standard 48 dp icon action. */
@Composable
fun IconAction(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = LocalChatUiColors.current.fgPrimary,
) {
    IconButton(
        onClick = onClick,
        modifier = modifier
            .sizeIn(
                minWidth = LocalDimens.current.touchTargetMin,
                minHeight = LocalDimens.current.touchTargetMin,
            )
            .semantics { this.contentDescription = contentDescription },
        enabled = enabled,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
        )
    }
}

/** Circular filled icon button for send and other prominent actions. */
@Composable
fun CircleIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    containerColor: Color = LocalChatUiColors.current.accent,
    contentColor: Color = LocalChatUiColors.current.fgOnAccent,
) {
    IconButton(
        onClick = onClick,
        modifier = modifier
            .sizeIn(
                minWidth = LocalDimens.current.touchTargetMin,
                minHeight = LocalDimens.current.touchTargetMin,
            )
            .background(containerColor, CircleShape),
        enabled = enabled,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = contentColor,
        )
    }
}

/**
 * A pending action keeps its label and rejects duplicate activation.
 */
@Composable
fun LoadingButton(
    text: String,
    loading: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = LocalChatUiColors.current
    val dimens = LocalDimens.current
    Button(
        onClick = onClick,
        modifier = modifier
            .sizeIn(minWidth = dimens.touchTargetMin, minHeight = dimens.touchTargetMin)
            .semantics { if (loading) stateDescription = "In progress" },
        enabled = enabled && !loading,
        shape = RoundedCornerShape(dimens.radiusMd),
        colors = ButtonDefaults.buttonColors(
            containerColor = colors.accent,
            contentColor = colors.fgOnAccent,
        ),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(dimens.space2),
        ) {
            if (loading) {
                if (LocalReducedMotion.current) {
                    CircularProgressIndicator(
                        progress = { 0.75f },
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                    )
                }
            }
            Text(text = text, style = Typography.headline)
        }
    }
}
