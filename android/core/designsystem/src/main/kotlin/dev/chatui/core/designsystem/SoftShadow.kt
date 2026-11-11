package dev.chatui.core.designsystem

import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp

/**
 * Draws a low-alpha ambient shadow behind [shape].
 *
 * Material's tonal surfaces are preferred over heavy shadows; this helper is
 * for the few raised cards and sheets that need a soft edge. It uses
 * [graphicsLayer] so the shadow is drawn without affecting layout.
 */
fun Modifier.softShadow(elevation: Dp, shape: Shape): Modifier = graphicsLayer {
    this.shape = shape
    shadowElevation = elevation.toPx()
    clip = false
    ambientShadowColor = Color.Black.copy(alpha = 0.12f)
    spotShadowColor = Color.Black.copy(alpha = 0.12f)
}
