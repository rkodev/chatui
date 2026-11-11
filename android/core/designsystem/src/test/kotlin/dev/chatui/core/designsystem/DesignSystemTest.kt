package dev.chatui.core.designsystem

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.common.truth.Truth.assertThat
import dev.chatui.core.model.ChatFontSize
import dev.chatui.core.model.Density
import dev.chatui.core.model.Theme
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import org.junit.Test

class DesignSystemTest {
    @Test
    fun `light palette defines every semantic color token`() {
        assertThat(LightColors.bgCanvas.toArgb()).isEqualTo(0xFFFFFFFF.toInt())
        assertThat(LightColors.bgSurface.toArgb()).isEqualTo(0xFFF2F2F7.toInt())
        assertThat(LightColors.bgSurfaceRaised.toArgb()).isEqualTo(0xFFFFFFFF.toInt())
        assertThat(LightColors.bgUserBubble.toArgb()).isEqualTo(0xFFE5F0FF.toInt())
        assertThat(LightColors.bgCode.toArgb()).isEqualTo(0xFFF2F2F7.toInt())
        assertThat(LightColors.fgPrimary.toArgb()).isEqualTo(0xFF1C1C1E.toInt())
        assertThat(LightColors.fgSecondary.toArgb()).isEqualTo(0xFF3C3C43.toInt())
        assertThat(LightColors.fgMuted.toArgb()).isEqualTo(0xFF6C6C70.toInt())
        assertThat(LightColors.fgOnAccent.toArgb()).isEqualTo(0xFFFFFFFF.toInt())
        assertThat(LightColors.accent.toArgb()).isEqualTo(0xFF007AFF.toInt())
        assertThat(LightColors.accentHover.toArgb()).isEqualTo(0xFF0062CC.toInt())
        assertThat(LightColors.borderSubtle.toArgb()).isEqualTo(0xFFE5E5EA.toInt())
        assertThat(LightColors.borderStrong.toArgb()).isEqualTo(0xFF8E8E93.toInt())
        assertThat(LightColors.danger.toArgb()).isEqualTo(0xFFD70015.toInt())
        assertThat(LightColors.warning.toArgb()).isEqualTo(0xFFB25000.toInt())
        assertThat(LightColors.success.toArgb()).isEqualTo(0xFF248A3D.toInt())
        assertThat(LightColors.bgGrouped.toArgb()).isEqualTo(0xFFF2F2F7.toInt())
        assertThat(LightColors.bgInput.toArgb()).isEqualTo(0xFFF2F2F7.toInt())
        assertThat(LightColors.separator.toArgb()).isEqualTo(0xFFE5E5EA.toInt())
        assertThat(LightColors.accentSoft.toArgb()).isEqualTo(0xFFE5F0FF.toInt())
        assertThat(LightColors.onAccentSoft.toArgb()).isEqualTo(0xFF004A99.toInt())
        assertThat(LightColors.scrim.toArgb()).isEqualTo(0x66000000)
        assertThat(LightColors.skeletonBase.toArgb()).isEqualTo(0xFFE5E5EA.toInt())
        assertThat(LightColors.skeletonHighlight.toArgb()).isEqualTo(0xFFF2F2F7.toInt())
    }

    @Test
    fun `dark palette defines every semantic color token`() {
        assertThat(DarkColors.bgCanvas.toArgb()).isEqualTo(0xFF0B0B0F.toInt())
        assertThat(DarkColors.bgSurface.toArgb()).isEqualTo(0xFF1C1C1E.toInt())
        assertThat(DarkColors.bgSurfaceRaised.toArgb()).isEqualTo(0xFF2C2C2E.toInt())
        assertThat(DarkColors.bgUserBubble.toArgb()).isEqualTo(0xFF103A5E.toInt())
        assertThat(DarkColors.bgCode.toArgb()).isEqualTo(0xFF1C1C1E.toInt())
        assertThat(DarkColors.fgPrimary.toArgb()).isEqualTo(0xFFFFFFFF.toInt())
        assertThat(DarkColors.fgSecondary.toArgb()).isEqualTo(0xFFD1D1D6.toInt())
        assertThat(DarkColors.fgMuted.toArgb()).isEqualTo(0xFF8E8E93.toInt())
        assertThat(DarkColors.fgOnAccent.toArgb()).isEqualTo(0xFF0B1020.toInt())
        assertThat(DarkColors.accent.toArgb()).isEqualTo(0xFF0A84FF.toInt())
        assertThat(DarkColors.accentHover.toArgb()).isEqualTo(0xFF409CFF.toInt())
        assertThat(DarkColors.borderSubtle.toArgb()).isEqualTo(0xFF38383A.toInt())
        assertThat(DarkColors.borderStrong.toArgb()).isEqualTo(0xFF8E8E93.toInt())
        assertThat(DarkColors.danger.toArgb()).isEqualTo(0xFFFF453A.toInt())
        assertThat(DarkColors.warning.toArgb()).isEqualTo(0xFFFFD60A.toInt())
        assertThat(DarkColors.success.toArgb()).isEqualTo(0xFF30D158.toInt())
        assertThat(DarkColors.bgGrouped.toArgb()).isEqualTo(0xFF1C1C1E.toInt())
        assertThat(DarkColors.bgInput.toArgb()).isEqualTo(0xFF1C1C1E.toInt())
        assertThat(DarkColors.separator.toArgb()).isEqualTo(0xFF38383A.toInt())
        assertThat(DarkColors.accentSoft.toArgb()).isEqualTo(0xFF103A5E.toInt())
        assertThat(DarkColors.onAccentSoft.toArgb()).isEqualTo(0xFF9CCBFF.toInt())
        assertThat(DarkColors.scrim.toArgb()).isEqualTo(0x99000000.toInt())
        assertThat(DarkColors.skeletonBase.toArgb()).isEqualTo(0xFF2C2C2E.toInt())
        assertThat(DarkColors.skeletonHighlight.toArgb()).isEqualTo(0xFF3A3A3C.toInt())
    }

    @Test
    fun `light and dark palettes are distinct`() {
        assertThat(LightColors).isNotEqualTo(DarkColors)
    }

    @Test
    fun `material color scheme maps semantic roles`() {
        val light = LightColors.toMaterialColorScheme(isDark = false)
        assertThat(light.primary).isEqualTo(LightColors.accent)
        assertThat(light.onPrimary).isEqualTo(LightColors.fgOnAccent)
        assertThat(light.primaryContainer).isEqualTo(LightColors.accentSoft)
        assertThat(light.onPrimaryContainer).isEqualTo(LightColors.onAccentSoft)
        assertThat(light.background).isEqualTo(LightColors.bgCanvas)
        assertThat(light.onBackground).isEqualTo(LightColors.fgPrimary)
        assertThat(light.surface).isEqualTo(LightColors.bgSurface)
        assertThat(light.onSurface).isEqualTo(LightColors.fgPrimary)
        assertThat(light.surfaceVariant).isEqualTo(LightColors.bgCode)
        assertThat(light.onSurfaceVariant).isEqualTo(LightColors.fgSecondary)
        assertThat(light.surfaceContainer).isEqualTo(LightColors.bgSurfaceRaised)
        assertThat(light.outline).isEqualTo(LightColors.borderStrong)
        assertThat(light.outlineVariant).isEqualTo(LightColors.borderSubtle)
        assertThat(light.error).isEqualTo(LightColors.danger)
        assertThat(light.tertiary).isEqualTo(LightColors.warning)

        val dark = DarkColors.toMaterialColorScheme(isDark = true)
        assertThat(dark.primary).isEqualTo(DarkColors.accent)
        assertThat(dark.background).isEqualTo(DarkColors.bgCanvas)
        assertThat(dark.surface).isEqualTo(DarkColors.bgSurface)
        assertThat(dark.error).isEqualTo(DarkColors.danger)
    }

    @Test
    fun `dynamic accent derives from scheme while status colors stay fixed`() {
        val scheme = lightColorScheme(
            primary = Color(0xFF123456),
            onPrimary = Color(0xFFFFFFFF),
            primaryContainer = Color(0xFFABCDEF),
            onPrimaryContainer = Color(0xFF001122),
        )

        val colors = LightColors.withDynamicAccent(scheme)

        assertThat(colors.accent).isEqualTo(scheme.primary)
        assertThat(colors.accentSoft).isEqualTo(scheme.primaryContainer)
        assertThat(colors.onAccentSoft).isEqualTo(scheme.onPrimaryContainer)
        assertThat(colors.fgOnAccent).isEqualTo(scheme.onPrimary)
        assertThat(colors.danger).isEqualTo(LightColors.danger)
        assertThat(colors.warning).isEqualTo(LightColors.warning)
        assertThat(colors.success).isEqualTo(LightColors.success)
    }

    @Test
    fun `body text meets contrast in both themes`() {
        assertThat(contrastRatio(LightColors.fgPrimary, LightColors.bgCanvas))
            .isAtLeast(4.5)
        assertThat(contrastRatio(DarkColors.fgPrimary, DarkColors.bgCanvas))
            .isAtLeast(4.5)
    }

    @Test
    fun `body text meets contrast on every surface in both themes`() {
        listOf(LightColors, DarkColors).forEach { colors ->
            listOf(colors.bgCanvas, colors.bgSurface, colors.bgSurfaceRaised).forEach { surface ->
                assertThat(contrastRatio(colors.fgPrimary, surface)).isAtLeast(4.5)
                assertThat(contrastRatio(colors.fgSecondary, surface)).isAtLeast(4.5)
            }
        }
    }

    @Test
    fun `body text meets contrast with dynamic color in both themes`() {
        val lightScheme = lightColorScheme(
            primary = Color(0xFF6750A4),
            onPrimary = Color(0xFFFFFFFF),
            primaryContainer = Color(0xFFEADDFF),
            onPrimaryContainer = Color(0xFF21005D),
        )
        val darkScheme = darkColorScheme(
            primary = Color(0xFFD0BCFF),
            onPrimary = Color(0xFF381E72),
            primaryContainer = Color(0xFF4F378B),
            onPrimaryContainer = Color(0xFFEADDFF),
        )

        listOf(
            LightColors.withDynamicAccent(lightScheme),
            DarkColors.withDynamicAccent(darkScheme),
        ).forEach { colors ->
            listOf(colors.bgCanvas, colors.bgSurface, colors.bgSurfaceRaised).forEach { surface ->
                assertThat(contrastRatio(colors.fgPrimary, surface)).isAtLeast(4.5)
                assertThat(contrastRatio(colors.fgSecondary, surface)).isAtLeast(4.5)
            }
            assertThat(contrastRatio(colors.onAccentSoft, colors.accentSoft)).isAtLeast(4.5)
        }
    }

    @Test
    fun `dimens expose spacing and radius tokens`() {
        assertThat(ComfortableDimens.space1).isEqualTo(4.dp)
        assertThat(ComfortableDimens.space2).isEqualTo(8.dp)
        assertThat(ComfortableDimens.space3).isEqualTo(12.dp)
        assertThat(ComfortableDimens.space4).isEqualTo(16.dp)
        assertThat(ComfortableDimens.space5).isEqualTo(24.dp)
        assertThat(ComfortableDimens.space6).isEqualTo(32.dp)
        assertThat(ComfortableDimens.space7).isEqualTo(48.dp)
        assertThat(ComfortableDimens.radiusSm).isEqualTo(6.dp)
        assertThat(ComfortableDimens.radiusMd).isEqualTo(10.dp)
        assertThat(ComfortableDimens.radiusLg).isEqualTo(16.dp)
        assertThat(ComfortableDimens.radiusFull).isEqualTo(9999.dp)
        assertThat(ComfortableDimens.touchTargetMin).isEqualTo(48.dp)
    }

    @Test
    fun `dimens expose layout shape and elevation tokens`() {
        assertThat(ComfortableDimens.screenPadding).isEqualTo(20.dp)
        assertThat(ComfortableDimens.sectionGap).isEqualTo(28.dp)
        assertThat(ComfortableDimens.rowMinHeight).isEqualTo(52.dp)
        assertThat(ComfortableDimens.cardPadding).isEqualTo(16.dp)
        assertThat(ComfortableDimens.bubblePaddingH).isEqualTo(14.dp)
        assertThat(ComfortableDimens.bubblePaddingV).isEqualTo(10.dp)
        assertThat(ComfortableDimens.radiusXs).isEqualTo(8.dp)
        assertThat(ComfortableDimens.radiusXl).isEqualTo(28.dp)
        assertThat(ComfortableDimens.bubbleRadius).isEqualTo(20.dp)
        assertThat(ComfortableDimens.bubbleTailRadius).isEqualTo(4.dp)
        assertThat(ComfortableDimens.elevation1).isEqualTo(1.dp)
        assertThat(ComfortableDimens.elevation2).isEqualTo(3.dp)
        assertThat(ComfortableDimens.elevation3).isEqualTo(8.dp)
    }

    @Test
    fun `comfortable and compact density expose distinct values`() {
        assertThat(ComfortableDimens.densitySpace1).isEqualTo(4.dp)
        assertThat(ComfortableDimens.densitySpace7).isEqualTo(48.dp)
        assertThat(ComfortableDimens.controlHeight).isEqualTo(40.dp)
        assertThat(ComfortableDimens.gap).isEqualTo(12.dp)
        assertThat(ComfortableDimens.padding).isEqualTo(16.dp)

        assertThat(CompactDimens.densitySpace1).isEqualTo(2.dp)
        assertThat(CompactDimens.densitySpace2).isEqualTo(4.dp)
        assertThat(CompactDimens.densitySpace3).isEqualTo(8.dp)
        assertThat(CompactDimens.densitySpace4).isEqualTo(12.dp)
        assertThat(CompactDimens.densitySpace5).isEqualTo(16.dp)
        assertThat(CompactDimens.densitySpace6).isEqualTo(24.dp)
        assertThat(CompactDimens.densitySpace7).isEqualTo(32.dp)
        assertThat(CompactDimens.controlHeight).isEqualTo(32.dp)
        assertThat(CompactDimens.gap).isEqualTo(8.dp)
        assertThat(CompactDimens.padding).isEqualTo(12.dp)
    }

    @Test
    fun `density preference selects the matching token set`() {
        assertThat(Density.COMFORTABLE.toDimens()).isEqualTo(ComfortableDimens)
        assertThat(Density.COMPACT.toDimens()).isEqualTo(CompactDimens)
    }

    @Test
    fun `typography exposes body code and heading tokens`() {
        assertThat(Typography.bodySize).isEqualTo(16.sp)
        assertThat(Typography.bodyLineHeight).isEqualTo(24.sp)
        assertThat(Typography.codeSize).isEqualTo(14.sp)
        assertThat(Typography.headingSize).isEqualTo(20.sp)
        assertThat(Typography.headingSize1).isEqualTo(32.sp)
        assertThat(Typography.headingSize2).isEqualTo(24.sp)
        assertThat(Typography.headingSize3).isEqualTo(20.sp)
        assertThat(Typography.headingLineHeight).isEqualTo(25.sp)
        assertThat(Typography.headingWeight).isEqualTo(FontWeight.SemiBold)
        assertThat(Typography.bodyStyle.fontSize).isEqualTo(16.sp)
        assertThat(Typography.codeStyle.fontSize).isEqualTo(14.sp)
        assertThat(Typography.headingStyle1.fontSize).isEqualTo(32.sp)
    }

    @Test
    fun `typography exposes the full type ramp`() {
        assertThat(Typography.largeTitle.fontSize).isEqualTo(34.sp)
        assertThat(Typography.largeTitle.lineHeight).isEqualTo(41.sp)
        assertThat(Typography.largeTitle.fontWeight).isEqualTo(FontWeight.Bold)
        assertThat(Typography.title1.fontSize).isEqualTo(28.sp)
        assertThat(Typography.title1.lineHeight).isEqualTo(34.sp)
        assertThat(Typography.title2.fontSize).isEqualTo(22.sp)
        assertThat(Typography.title2.lineHeight).isEqualTo(28.sp)
        assertThat(Typography.title3.fontSize).isEqualTo(20.sp)
        assertThat(Typography.title3.lineHeight).isEqualTo(25.sp)
        assertThat(Typography.headline.fontSize).isEqualTo(17.sp)
        assertThat(Typography.headline.lineHeight).isEqualTo(22.sp)
        assertThat(Typography.body.fontSize).isEqualTo(17.sp)
        assertThat(Typography.body.lineHeight).isEqualTo(24.sp)
        assertThat(Typography.callout.fontSize).isEqualTo(16.sp)
        assertThat(Typography.callout.lineHeight).isEqualTo(21.sp)
        assertThat(Typography.subhead.fontSize).isEqualTo(15.sp)
        assertThat(Typography.subhead.lineHeight).isEqualTo(20.sp)
        assertThat(Typography.footnote.fontSize).isEqualTo(13.sp)
        assertThat(Typography.footnote.lineHeight).isEqualTo(18.sp)
        assertThat(Typography.caption.fontSize).isEqualTo(12.sp)
        assertThat(Typography.caption.lineHeight).isEqualTo(16.sp)
    }

    @Test
    fun `chat font size styles map to tokens`() {
        assertThat(Typography.chatStyle(ChatFontSize.SM).fontSize).isEqualTo(14.sp)
        assertThat(Typography.chatStyle(ChatFontSize.MD).fontSize).isEqualTo(16.sp)
        assertThat(Typography.chatStyle(ChatFontSize.LG).fontSize).isEqualTo(18.sp)
    }

    @Test
    fun `theme mode resolves light dark and system`() {
        assertThat(resolveDarkTheme(Theme.SYSTEM, systemInDarkTheme = true)).isTrue()
        assertThat(resolveDarkTheme(Theme.SYSTEM, systemInDarkTheme = false)).isFalse()
        assertThat(resolveDarkTheme(Theme.LIGHT, systemInDarkTheme = true)).isFalse()
        assertThat(resolveDarkTheme(Theme.DARK, systemInDarkTheme = false)).isTrue()
    }

    @Test
    fun `reduced motion zeroes animation durations`() {
        val standard = ChatUiMotion.forReducedMotion(reducedMotion = false)
        assertThat(standard.fastMillis).isEqualTo(120)
        assertThat(standard.normalMillis).isEqualTo(200)
        assertThat(standard.reducedMotion).isFalse()
        assertThat(standard.fadeThroughMillis).isEqualTo(180)
        assertThat(standard.sharedAxisMillis).isEqualTo(220)
        assertThat(standard.emphasizedMillis).isEqualTo(300)

        val reduced = ChatUiMotion.forReducedMotion(reducedMotion = true)
        assertThat(reduced.fastMillis).isEqualTo(0)
        assertThat(reduced.normalMillis).isEqualTo(0)
        assertThat(reduced.reducedMotion).isTrue()
        assertThat(reduced.fadeThroughMillis).isEqualTo(0)
        assertThat(reduced.sharedAxisMillis).isEqualTo(0)
        assertThat(reduced.emphasizedMillis).isEqualTo(0)
    }

    @Test
    fun `zero animator duration scale means reduced motion`() {
        assertThat(isReducedMotion(0f)).isTrue()
        assertThat(isReducedMotion(0.5f)).isFalse()
        assertThat(isReducedMotion(1f)).isFalse()
    }

    private fun contrastRatio(foreground: Color, background: Color): Double {
        val first = foreground.relativeLuminance()
        val second = background.relativeLuminance()
        val lighter = max(first, second)
        val darker = min(first, second)
        return (lighter + 0.05) / (darker + 0.05)
    }

    private fun Color.relativeLuminance(): Double {
        fun channel(value: Float): Double {
            val normalized = value.toDouble()
            return if (normalized <= 0.03928) {
                normalized / 12.92
            } else {
                ((normalized + 0.055) / 1.055).pow(2.4)
            }
        }
        return 0.2126 * channel(red) +
            0.7152 * channel(green) +
            0.0722 * channel(blue)
    }
}
