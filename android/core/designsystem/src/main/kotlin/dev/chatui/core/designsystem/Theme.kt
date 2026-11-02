package dev.chatui.core.designsystem

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import dev.chatui.core.model.Density
import dev.chatui.core.model.Theme

/** Current semantic palette. Defaults to light so previews render without a theme. */
val LocalChatUiColors = staticCompositionLocalOf { LightColors }

/** Current spacing and radius tokens for the selected density. */
val LocalDimens = staticCompositionLocalOf { ComfortableDimens }

/** Current semantic typography tokens. */
val LocalChatUiTypography = staticCompositionLocalOf { Typography }

/** Current motion tokens, including the reduced-motion variant. */
val LocalChatUiMotion = staticCompositionLocalOf { ChatUiMotion.Standard }

/** True when the system or an explicit override disables animation. */
val LocalReducedMotion = staticCompositionLocalOf { false }

/** Resolves the persisted theme mode against the current system setting. */
fun resolveDarkTheme(themeMode: Theme, systemInDarkTheme: Boolean): Boolean =
    when (themeMode) {
        Theme.SYSTEM -> systemInDarkTheme
        Theme.LIGHT -> false
        Theme.DARK -> true
    }

/** Selects the token set for a persisted density preference. */
fun Density.toDimens(): Dimens = when (this) {
    Density.COMFORTABLE -> ComfortableDimens
    Density.COMPACT -> CompactDimens
}

/**
 * Applies the ChatUI design system to [content].
 *
 * [themeMode] follows the persisted `Theme` preference; [Density] selects the
 * comfortable or compact token set. [reducedMotion] is an optional override for
 * previews and tests; when null the system animator duration scale is observed.
 */
@Composable
fun ChatUiTheme(
    themeMode: Theme = Theme.SYSTEM,
    density: Density = Density.COMFORTABLE,
    reducedMotion: Boolean? = null,
    content: @Composable () -> Unit,
) {
    val darkTheme = resolveDarkTheme(themeMode, isSystemInDarkTheme())
    val colors = if (darkTheme) DarkColors else LightColors
    val dimens = density.toDimens()
    val systemReducedMotion = rememberReducedMotion()
    val motion = ChatUiMotion.forReducedMotion(reducedMotion ?: systemReducedMotion)

    CompositionLocalProvider(
        LocalChatUiColors provides colors,
        LocalDimens provides dimens,
        LocalChatUiTypography provides Typography,
        LocalChatUiMotion provides motion,
        LocalReducedMotion provides motion.reducedMotion,
    ) {
        MaterialTheme(
            colorScheme = colors.toMaterialColorScheme(darkTheme),
            typography = Typography.toMaterialTypography(),
            content = content,
        )
    }
}
