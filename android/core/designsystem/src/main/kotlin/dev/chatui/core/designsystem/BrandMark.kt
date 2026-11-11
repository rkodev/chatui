package dev.chatui.core.designsystem

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Brand mark used by the splash screen and the About screen.
 *
 * The mark is drawn in Compose so it scales cleanly and follows the accent
 * token. It is decorative but exposes a content description for TalkBack.
 */
@Composable
fun BrandMark(
    modifier: Modifier = Modifier,
    size: Dp = 72.dp,
    color: Color = LocalChatUiColors.current.accent,
) {
    Canvas(
        modifier = modifier
            .size(size)
            .semantics { contentDescription = "ChatUI" },
    ) {
        val width = this.size.width
        val height = this.size.height
        val radius = this.size.minDimension / 2f
        drawCircle(color = color, radius = radius)

        val left = width * 0.28f
        val top = height * 0.30f
        val right = width * 0.72f
        val bottom = height * 0.62f
        val corner = width * 0.08f
        val bubble = Path().apply {
            moveTo(left + corner, top)
            lineTo(right - corner, top)
            quadraticBezierTo(right, top, right, top + corner)
            lineTo(right, bottom - corner)
            quadraticBezierTo(right, bottom, right - corner, bottom)
            lineTo(width * 0.46f, bottom)
            lineTo(width * 0.36f, height * 0.72f)
            lineTo(width * 0.36f, bottom)
            lineTo(left + corner, bottom)
            quadraticBezierTo(left, bottom, left, bottom - corner)
            lineTo(left, top + corner)
            quadraticBezierTo(left, top, left + corner, top)
            close()
        }
        drawPath(path = bubble, color = Color.White)

        val dotY = (top + bottom) / 2f
        val dotRadius = width * 0.025f
        listOf(0.40f, 0.50f, 0.60f).forEach { fraction ->
            drawCircle(
                color = color,
                radius = dotRadius,
                center = Offset(width * fraction, dotY),
            )
        }
    }
}
