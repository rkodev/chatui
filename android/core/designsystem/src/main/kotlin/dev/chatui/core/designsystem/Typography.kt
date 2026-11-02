package dev.chatui.core.designsystem

import androidx.compose.material3.Typography as MaterialTypography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import dev.chatui.core.model.ChatFontSize

/**
 * Semantic typography tokens from spec section 11.12.
 *
 * The property names are the Compose form of the shared dotted token names:
 * `font.body.size` -> [bodySize], `font.heading.size-1` -> [headingSize1].
 * Sizes use `sp`; the web tokens use `rem` with a 16px root, so the numeric
 * values match at the default font scale.
 */
object Typography {
    val bodyFamily: FontFamily = FontFamily.Default
    val bodySize: TextUnit = 16.sp
    val bodyLineHeight: TextUnit = 24.sp

    val codeFamily: FontFamily = FontFamily.Monospace
    val codeSize: TextUnit = 14.sp
    val codeLineHeight: TextUnit = 20.sp

    val headingFamily: FontFamily = bodyFamily
    val headingSize: TextUnit = 20.sp
    val headingSize1: TextUnit = 32.sp
    val headingSize2: TextUnit = 24.sp
    val headingSize3: TextUnit = 20.sp
    val headingLineHeight: TextUnit = 25.sp
    val headingLineHeight1: TextUnit = 40.sp
    val headingLineHeight2: TextUnit = 30.sp
    val headingLineHeight3: TextUnit = 25.sp
    val headingWeight: FontWeight = FontWeight.SemiBold

    val chatSizeSm: TextUnit = 14.sp
    val chatSizeMd: TextUnit = 16.sp
    val chatSizeLg: TextUnit = 18.sp
    val chatLineHeightSm: TextUnit = 20.sp
    val chatLineHeightMd: TextUnit = 24.sp
    val chatLineHeightLg: TextUnit = 29.sp

    val bodyStyle: TextStyle = TextStyle(
        fontFamily = bodyFamily,
        fontSize = bodySize,
        lineHeight = bodyLineHeight,
    )

    val codeStyle: TextStyle = TextStyle(
        fontFamily = codeFamily,
        fontSize = codeSize,
        lineHeight = codeLineHeight,
    )

    val headingStyle1: TextStyle = TextStyle(
        fontFamily = headingFamily,
        fontSize = headingSize1,
        lineHeight = headingLineHeight1,
        fontWeight = headingWeight,
    )

    val headingStyle2: TextStyle = TextStyle(
        fontFamily = headingFamily,
        fontSize = headingSize2,
        lineHeight = headingLineHeight2,
        fontWeight = headingWeight,
    )

    val headingStyle3: TextStyle = TextStyle(
        fontFamily = headingFamily,
        fontSize = headingSize3,
        lineHeight = headingLineHeight3,
        fontWeight = headingWeight,
    )

    /** Chat text style for the persisted chat font-size preference. */
    fun chatStyle(chatFontSize: ChatFontSize): TextStyle = when (chatFontSize) {
        ChatFontSize.SM -> TextStyle(
            fontFamily = bodyFamily,
            fontSize = chatSizeSm,
            lineHeight = chatLineHeightSm,
        )

        ChatFontSize.MD -> TextStyle(
            fontFamily = bodyFamily,
            fontSize = chatSizeMd,
            lineHeight = chatLineHeightMd,
        )

        ChatFontSize.LG -> TextStyle(
            fontFamily = bodyFamily,
            fontSize = chatSizeLg,
            lineHeight = chatLineHeightLg,
        )
    }

    /**
     * Maps the semantic styles onto Material 3 typography roles.
     *
     * Material 3 has no separate heading scale, so the display and headline
     * roles reuse the three heading styles. Body and label roles reuse the body
     * style with weight or size adjustments.
     */
    fun toMaterialTypography(): MaterialTypography = MaterialTypography(
        displayLarge = headingStyle1,
        displayMedium = headingStyle1,
        displaySmall = headingStyle2,
        headlineLarge = headingStyle1,
        headlineMedium = headingStyle2,
        headlineSmall = headingStyle3,
        titleLarge = headingStyle3,
        titleMedium = bodyStyle.copy(fontWeight = FontWeight.SemiBold),
        titleSmall = bodyStyle.copy(fontWeight = FontWeight.Medium),
        bodyLarge = bodyStyle,
        bodyMedium = bodyStyle,
        bodySmall = bodyStyle.copy(fontSize = 14.sp, lineHeight = 20.sp),
        labelLarge = bodyStyle.copy(fontWeight = FontWeight.Medium),
        labelMedium = bodyStyle.copy(fontSize = 14.sp, lineHeight = 20.sp),
        labelSmall = bodyStyle.copy(fontSize = 12.sp, lineHeight = 16.sp),
    )
}
