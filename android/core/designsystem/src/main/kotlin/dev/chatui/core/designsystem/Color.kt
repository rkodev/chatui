package dev.chatui.core.designsystem

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * Semantic color tokens from spec section 11.12.
 *
 * The property names are the Compose form of the shared dotted token names:
 * `color.bg.canvas` -> [bgCanvas], `color.fg.on-accent` -> [fgOnAccent], and so
 * on. Values mirror `web/src/styles/themes.css` so both clients render the same
 * palette.
 */
@Immutable
data class ChatUiColors(
    val bgCanvas: Color,
    val bgSurface: Color,
    val bgSurfaceRaised: Color,
    val bgUserBubble: Color,
    val bgCode: Color,
    val fgPrimary: Color,
    val fgSecondary: Color,
    val fgMuted: Color,
    val fgOnAccent: Color,
    val accent: Color,
    val accentHover: Color,
    val borderSubtle: Color,
    val borderStrong: Color,
    val danger: Color,
    val warning: Color,
    val success: Color,
)

/** Light palette from `web/src/styles/themes.css`. */
val LightColors: ChatUiColors = ChatUiColors(
    bgCanvas = Color(0xFFFFFFFF),
    bgSurface = Color(0xFFF7F7F8),
    bgSurfaceRaised = Color(0xFFFFFFFF),
    bgUserBubble = Color(0xFFE8F0FE),
    bgCode = Color(0xFFF3F4F6),
    fgPrimary = Color(0xFF1A1A1A),
    fgSecondary = Color(0xFF4A4A4A),
    fgMuted = Color(0xFF5F6368),
    fgOnAccent = Color(0xFFFFFFFF),
    accent = Color(0xFF1A56DB),
    accentHover = Color(0xFF1E40AF),
    borderSubtle = Color(0xFFD1D5DB),
    borderStrong = Color(0xFF6B7280),
    danger = Color(0xFFB91C1C),
    warning = Color(0xFF92400E),
    success = Color(0xFF166534),
)

/** Dark palette from `web/src/styles/themes.css`. */
val DarkColors: ChatUiColors = ChatUiColors(
    bgCanvas = Color(0xFF0F1115),
    bgSurface = Color(0xFF171A21),
    bgSurfaceRaised = Color(0xFF1F232B),
    bgUserBubble = Color(0xFF1E3A5F),
    bgCode = Color(0xFF111827),
    fgPrimary = Color(0xFFF3F4F6),
    fgSecondary = Color(0xFFC7CBD1),
    fgMuted = Color(0xFF9CA3AF),
    fgOnAccent = Color(0xFF0B1020),
    accent = Color(0xFF60A5FA),
    accentHover = Color(0xFF93C5FD),
    borderSubtle = Color(0xFF2F3542),
    borderStrong = Color(0xFF6B7280),
    danger = Color(0xFFF87171),
    warning = Color(0xFFFBBF24),
    success = Color(0xFF4ADE80),
)

/**
 * Maps the semantic palette onto Material 3 color roles.
 *
 * Material 3 has no dedicated roles for the user bubble, code background,
 * accent-hover, or success. Those tokens stay available through
 * [ChatUiColors]; the closest Material roles are filled here so stock
 * components inherit the palette. The mapping is intentionally documented in
 * the task handoff notes for review in M4.
 */
fun ChatUiColors.toMaterialColorScheme(isDark: Boolean): ColorScheme {
    val scheme = if (isDark) darkColorScheme() else lightColorScheme()
    return scheme.copy(
        primary = accent,
        onPrimary = fgOnAccent,
        primaryContainer = bgUserBubble,
        onPrimaryContainer = fgPrimary,
        secondary = accentHover,
        onSecondary = fgOnAccent,
        tertiary = warning,
        onTertiary = fgOnAccent,
        background = bgCanvas,
        onBackground = fgPrimary,
        surface = bgSurface,
        onSurface = fgPrimary,
        surfaceVariant = bgCode,
        onSurfaceVariant = fgSecondary,
        surfaceContainer = bgSurfaceRaised,
        surfaceContainerHigh = bgSurfaceRaised,
        surfaceContainerHighest = bgSurfaceRaised,
        outline = borderStrong,
        outlineVariant = borderSubtle,
        error = danger,
        onError = fgOnAccent,
    )
}
