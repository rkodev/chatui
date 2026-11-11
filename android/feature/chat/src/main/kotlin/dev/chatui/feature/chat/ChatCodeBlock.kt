package dev.chatui.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.WrapText
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.chatui.core.designsystem.LocalChatUiColors
import dev.chatui.core.designsystem.LocalDimens
import dev.chatui.core.designsystem.Typography
import dev.chatui.core.designsystem.rememberHaptics
import dev.snipme.highlights.Highlights
import dev.snipme.highlights.model.BoldHighlight
import dev.snipme.highlights.model.CodeHighlight
import dev.snipme.highlights.model.ColorHighlight
import dev.snipme.highlights.model.SyntaxThemes
import kotlinx.coroutines.delay

/** Test tags exposed by a fenced code block. */
object ChatCodeBlockTestTags {
    const val Code = "chat-code-block-code"
    const val Language = "chat-code-block-language"
    const val Copy = "chat-code-block-copy"
    const val Wrap = "chat-code-block-wrap"
}

/**
 * Fenced code block with a language pill, exact-text copy, and bounded
 * highlighting.
 *
 * Copy uses the original source text, never the highlighted spans. Unknown
 * languages and highlighter failures fall back to plain monospace text.
 */
@Composable
fun ChatCodeBlock(
    code: String,
    language: String?,
    modifier: Modifier = Modifier,
    style: TextStyle = Typography.codeStyle,
    onCopy: ((String) -> Unit)? = null,
) {
    val dimens = LocalDimens.current
    val colors = LocalChatUiColors.current
    val haptics = rememberHaptics()
    val darkMode = isSystemInDarkTheme()
    val normalized = remember(language) { normalizeCodeLanguage(language) }
    val highlighted = remember(code, normalized, darkMode) {
        highlightMarkdownCode(code, normalized, darkMode)
    }
    val label = remember(language) { codeLanguageLabel(language) }
    var copied by remember(code) { mutableStateOf(false) }
    var wrapped by remember(code) { mutableStateOf(false) }
    @Suppress("DEPRECATION")
    val clipboard = LocalClipboardManager.current
    val shape = RoundedCornerShape(12.dp)

    LaunchedEffect(copied) {
        if (copied) {
            delay(COPIED_RESET_MS)
            copied = false
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = dimens.space1)
            .background(color = colors.bgCode, shape = shape),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = dimens.space3, end = dimens.space1, top = dimens.space1),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = RoundedCornerShape(percent = 50),
                color = colors.bgSurfaceRaised,
            ) {
                Text(
                    text = label,
                    style = Typography.caption,
                    color = colors.fgSecondary,
                    modifier = Modifier
                        .padding(horizontal = dimens.space2, vertical = 2.dp)
                        .testTag(ChatCodeBlockTestTags.Language),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(dimens.space1)) {
                IconButton(
                    onClick = { wrapped = !wrapped },
                    modifier = Modifier
                        .heightIn(min = dimens.touchTargetMin)
                        .testTag(ChatCodeBlockTestTags.Wrap)
                        .semantics {
                            contentDescription = if (wrapped) "Scroll" else "Wrap"
                        },
                ) {
                    Icon(
                        imageVector = if (wrapped) Icons.Rounded.Code else Icons.Rounded.WrapText,
                        contentDescription = null,
                        tint = colors.fgSecondary,
                    )
                }
                IconButton(
                    onClick = {
                        if (onCopy != null) {
                            onCopy(code)
                        } else {
                            clipboard.setText(AnnotatedString(code))
                        }
                        haptics.confirm()
                        copied = true
                    },
                    modifier = Modifier
                        .heightIn(min = dimens.touchTargetMin)
                        .testTag(ChatCodeBlockTestTags.Copy)
                        .semantics {
                            contentDescription = if (copied) "Copied" else "Copy code"
                        },
                ) {
                    Icon(
                        imageVector = if (copied) Icons.Rounded.Check else Icons.Rounded.ContentCopy,
                        contentDescription = null,
                        tint = colors.fgSecondary,
                    )
                }
            }
        }

        Box(modifier = Modifier.fillMaxWidth()) {
            SelectionContainer {
                val codeModifier = if (wrapped) {
                    Modifier
                        .fillMaxWidth()
                        .padding(dimens.space3)
                } else {
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(dimens.space3)
                }
                Text(
                    text = highlighted ?: AnnotatedString(code),
                    style = style,
                    softWrap = wrapped,
                    modifier = codeModifier.testTag(ChatCodeBlockTestTags.Code),
                )
            }
            if (!wrapped) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .fillMaxHeight()
                        .width(24.dp)
                        .background(
                            Brush.horizontalGradient(
                                colors = listOf(Color.Transparent, colors.bgCode),
                            ),
                        ),
                )
            }
        }
    }
}

private const val COPIED_RESET_MS = 1_500L

/**
 * Highlight [code] with the bounded M1 language set.
 *
 * Returns null when the language is unsupported so the caller can render plain
 * text. Highlighting runs synchronously over a small grammar set and never
 * throws into composition.
 */
internal fun highlightMarkdownCode(
    code: String,
    language: dev.snipme.highlights.model.SyntaxLanguage?,
    darkMode: Boolean,
): AnnotatedString? {
    if (language == null || code.isEmpty()) return null

    val highlights = runCatching {
        Highlights.Builder()
            .code(code)
            .language(language)
            .theme(SyntaxThemes.default(darkMode))
            .build()
            .getHighlights()
    }.getOrNull() ?: return null

    return buildAnnotatedStringFromHighlights(code, highlights)
}

private fun buildAnnotatedStringFromHighlights(
    code: String,
    highlights: List<CodeHighlight>,
): AnnotatedString {
    val builder = AnnotatedString.Builder(code)

    highlights
        .sortedBy { it.location.start }
        .forEach { highlight ->
            val start = highlight.location.start.coerceIn(0, code.length)
            val end = highlight.location.end.coerceIn(start, code.length)
            if (start == end) return@forEach

            when (highlight) {
                is ColorHighlight -> builder.addStyle(
                    SpanStyle(color = Color(0xFF000000.toInt() or highlight.rgb)),
                    start,
                    end,
                )

                is BoldHighlight -> builder.addStyle(
                    SpanStyle(fontWeight = FontWeight.Bold),
                    start,
                    end,
                )
            }
        }

    return builder.toAnnotatedString()
}
