package dev.chatui.core.designsystem

import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.common.truth.Truth.assertThat
import dev.chatui.core.model.ChatFontSize
import dev.chatui.core.model.Density
import dev.chatui.core.model.Theme
import org.junit.Test

class DesignSystemTest {
    @Test
    fun `light palette defines every semantic color token`() {
        assertThat(LightColors.bgCanvas.toArgb()).isEqualTo(0xFFFFFFFF.toInt())
        assertThat(LightColors.bgSurface.toArgb()).isEqualTo(0xFFF7F7F8.toInt())
        assertThat(LightColors.bgSurfaceRaised.toArgb()).isEqualTo(0xFFFFFFFF.toInt())
        assertThat(LightColors.bgUserBubble.toArgb()).isEqualTo(0xFFE8F0FE.toInt())
        assertThat(LightColors.bgCode.toArgb()).isEqualTo(0xFFF3F4F6.toInt())
        assertThat(LightColors.fgPrimary.toArgb()).isEqualTo(0xFF1A1A1A.toInt())
        assertThat(LightColors.fgSecondary.toArgb()).isEqualTo(0xFF4A4A4A.toInt())
        assertThat(LightColors.fgMuted.toArgb()).isEqualTo(0xFF5F6368.toInt())
        assertThat(LightColors.fgOnAccent.toArgb()).isEqualTo(0xFFFFFFFF.toInt())
        assertThat(LightColors.accent.toArgb()).isEqualTo(0xFF1A56DB.toInt())
        assertThat(LightColors.accentHover.toArgb()).isEqualTo(0xFF1E40AF.toInt())
        assertThat(LightColors.borderSubtle.toArgb()).isEqualTo(0xFFD1D5DB.toInt())
        assertThat(LightColors.borderStrong.toArgb()).isEqualTo(0xFF6B7280.toInt())
        assertThat(LightColors.danger.toArgb()).isEqualTo(0xFFB91C1C.toInt())
        assertThat(LightColors.warning.toArgb()).isEqualTo(0xFF92400E.toInt())
        assertThat(LightColors.success.toArgb()).isEqualTo(0xFF166534.toInt())
    }

    @Test
    fun `dark palette defines every semantic color token`() {
        assertThat(DarkColors.bgCanvas.toArgb()).isEqualTo(0xFF0F1115.toInt())
        assertThat(DarkColors.bgSurface.toArgb()).isEqualTo(0xFF171A21.toInt())
        assertThat(DarkColors.bgSurfaceRaised.toArgb()).isEqualTo(0xFF1F232B.toInt())
        assertThat(DarkColors.bgUserBubble.toArgb()).isEqualTo(0xFF1E3A5F.toInt())
        assertThat(DarkColors.bgCode.toArgb()).isEqualTo(0xFF111827.toInt())
        assertThat(DarkColors.fgPrimary.toArgb()).isEqualTo(0xFFF3F4F6.toInt())
        assertThat(DarkColors.fgSecondary.toArgb()).isEqualTo(0xFFC7CBD1.toInt())
        assertThat(DarkColors.fgMuted.toArgb()).isEqualTo(0xFF9CA3AF.toInt())
        assertThat(DarkColors.fgOnAccent.toArgb()).isEqualTo(0xFF0B1020.toInt())
        assertThat(DarkColors.accent.toArgb()).isEqualTo(0xFF60A5FA.toInt())
        assertThat(DarkColors.accentHover.toArgb()).isEqualTo(0xFF93C5FD.toInt())
        assertThat(DarkColors.borderSubtle.toArgb()).isEqualTo(0xFF2F3542.toInt())
        assertThat(DarkColors.borderStrong.toArgb()).isEqualTo(0xFF6B7280.toInt())
        assertThat(DarkColors.danger.toArgb()).isEqualTo(0xFFF87171.toInt())
        assertThat(DarkColors.warning.toArgb()).isEqualTo(0xFFFBBF24.toInt())
        assertThat(DarkColors.success.toArgb()).isEqualTo(0xFF4ADE80.toInt())
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
        assertThat(light.primaryContainer).isEqualTo(LightColors.bgUserBubble)
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

        val reduced = ChatUiMotion.forReducedMotion(reducedMotion = true)
        assertThat(reduced.fastMillis).isEqualTo(0)
        assertThat(reduced.normalMillis).isEqualTo(0)
        assertThat(reduced.reducedMotion).isTrue()
    }

    @Test
    fun `zero animator duration scale means reduced motion`() {
        assertThat(isReducedMotion(0f)).isTrue()
        assertThat(isReducedMotion(0.5f)).isFalse()
        assertThat(isReducedMotion(1f)).isFalse()
    }
}
