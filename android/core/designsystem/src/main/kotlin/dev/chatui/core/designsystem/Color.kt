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
    val bgGrouped: Color = bgSurface,
    val bgInput: Color = bgSurface,
    val separator: Color = borderSubtle,
    val accentSoft: Color = bgUserBubble,
    val onAccentSoft: Color = fgPrimary,
    val scrim: Color = Color(0x66000000),
    val skeletonBase: Color = borderSubtle,
    val skeletonHighlight: Color = bgSurface,
)

/** Light palette from the design review section 4.1. */
val LightColors: ChatUiColors = ChatUiColors(
    bgCanvas = Color(0xFFFFFFFF),
    bgSurface = Color(0xFFF2F2F7),
    bgSurfaceRaised = Color(0xFFFFFFFF),
    bgUserBubble = Color(0xFFE5F0FF),
    bgCode = Color(0xFFF2F2F7),
    fgPrimary = Color(0xFF1C1C1E),
    fgSecondary = Color(0xFF3C3C43),
    fgMuted = Color(0xFF6C6C70),
    fgOnAccent = Color(0xFFFFFFFF),
    accent = Color(0xFF007AFF),
    accentHover = Color(0xFF0062CC),
    borderSubtle = Color(0xFFE5E5EA),
    borderStrong = Color(0xFF8E8E93),
    danger = Color(0xFFD70015),
    warning = Color(0xFFB25000),
    success = Color(0xFF248A3D),
    bgGrouped = Color(0xFFF2F2F7),
    bgInput = Color(0xFFF2F2F7),
    separator = Color(0xFFE5E5EA),
    accentSoft = Color(0xFFE5F0FF),
    onAccentSoft = Color(0xFF004A99),
    scrim = Color(0x66000000),
    skeletonBase = Color(0xFFE5E5EA),
    skeletonHighlight = Color(0xFFF2F2F7),
)

/** Dark palette from the design review section 4.1. */
val DarkColors: ChatUiColors = ChatUiColors(
    bgCanvas = Color(0xFF0B0B0F),
    bgSurface = Color(0xFF1C1C1E),
    bgSurfaceRaised = Color(0xFF2C2C2E),
    bgUserBubble = Color(0xFF103A5E),
    bgCode = Color(0xFF1C1C1E),
    fgPrimary = Color(0xFFFFFFFF),
    fgSecondary = Color(0xFFD1D1D6),
    fgMuted = Color(0xFF8E8E93),
    fgOnAccent = Color(0xFF0B1020),
    accent = Color(0xFF0A84FF),
    accentHover = Color(0xFF409CFF),
    borderSubtle = Color(0xFF38383A),
    borderStrong = Color(0xFF8E8E93),
    danger = Color(0xFFFF453A),
    warning = Color(0xFFFFD60A),
    success = Color(0xFF30D158),
    bgGrouped = Color(0xFF1C1C1E),
    bgInput = Color(0xFF1C1C1E),
    separator = Color(0xFF38383A),
    accentSoft = Color(0xFF103A5E),
    onAccentSoft = Color(0xFF9CCBFF),
    scrim = Color(0x99000000),
    skeletonBase = Color(0xFF2C2C2E),
    skeletonHighlight = Color(0xFF3A3A3C),
)

/**
 * Derives the accent roles from a Material You scheme while keeping the
 * semantic status colors fixed. Used only when dynamic color is enabled.
 */
fun ChatUiColors.withDynamicAccent(scheme: ColorScheme): ChatUiColors = copy(
    accent = scheme.primary,
    accentHover = scheme.primary,
    accentSoft = scheme.primaryContainer,
    onAccentSoft = scheme.onPrimaryContainer,
    bgUserBubble = scheme.primaryContainer,
    fgOnAccent = scheme.onPrimary,
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
        primaryContainer = accentSoft,
        onPrimaryContainer = onAccentSoft,
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
