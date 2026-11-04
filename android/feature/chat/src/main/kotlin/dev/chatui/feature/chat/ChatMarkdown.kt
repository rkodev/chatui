package dev.chatui.feature.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.compose.LocalMarkdownComponents
import com.mikepenz.markdown.compose.MarkdownElement
import com.mikepenz.markdown.compose.components.MarkdownComponentModel
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownCodeBlock
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.elements.MarkdownCheckBox
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.markdownAnnotator
import com.mikepenz.markdown.model.rememberMarkdownState
import com.mikepenz.markdown.utils.getUnescapedTextInNode
import com.mikepenz.markdown.utils.resolveImageAlt
import com.mikepenz.markdown.utils.resolveImageLink
import dev.chatui.core.designsystem.LocalDimens
import dev.chatui.core.designsystem.Typography
import dev.chatui.core.model.ChatFontSize
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.findChildOfType
import org.intellij.markdown.ast.getTextInNode
import org.intellij.markdown.flavours.gfm.GFMTokenTypes

/** Test tags exposed by rendered Markdown. */
object ChatMarkdownTestTags {
    const val BlockedImage = "chat-markdown-blocked-image"
}

/**
 * Safe Markdown renderer for chat message content.
 *
 * GFM (tables, task lists, strikethrough, autolinks) is enabled. Raw HTML is
 * shown as inert text and never parsed, remote images render as blocked
 * placeholders, links outside the M1 allowlist are inert, and an unterminated
 * fence is closed for rendering so streaming code never shows raw backticks.
 */
@Composable
fun ChatMarkdown(
    content: String,
    modifier: Modifier = Modifier,
    chatFontSize: ChatFontSize = ChatFontSize.MD,
    parseImmediately: Boolean = false,
) {
    val rendered = remember(content) { closeIncompleteFence(content) }
    val bodyStyle = Typography.chatStyle(chatFontSize)
    val typography = markdownTypography(
        text = bodyStyle,
        paragraph = bodyStyle,
        ordered = bodyStyle,
        bullet = bodyStyle,
        list = bodyStyle,
        quote = bodyStyle.copy(fontStyle = FontStyle.Italic),
        table = bodyStyle,
        code = Typography.codeStyle,
        inlineCode = bodyStyle.copy(
            fontFamily = FontFamily.Monospace,
            fontSize = (bodyStyle.fontSize.value - 2).coerceAtLeast(10f).sp,
        ),
        textLink = androidx.compose.ui.text.TextLinkStyles(
            style = bodyStyle.copy(
                textDecoration = TextDecoration.Underline,
            ).toSpanStyle(),
        ),
    )
    val annotator = remember {
        markdownAnnotator(annotate = { content, child ->
            annotateSafeMarkdown(this, content, child)
        })
    }

    val markdownState = rememberMarkdownState(
        content = rendered,
        retainState = true,
        immediate = parseImmediately,
    )

    Markdown(
        markdownState = markdownState,
        modifier = modifier,
        typography = typography,
        annotator = annotator,
        components = markdownComponents(
            checkbox = { model -> MarkdownCheckBox(model.content, model.node, model.typography.text) },
            codeFence = { model -> BlockFencedCode(model) },
            codeBlock = { model ->
                MarkdownCodeBlock(
                    content = model.content,
                    node = model.node,
                    style = model.typography.code,
                ) { code, language, style ->
                    ChatCodeBlock(
                        code = code.removeSuffix("\n"),
                        language = language,
                        style = style,
                    )
                }
            },
            image = { model -> BlockedImage(model) },
            inlineImage = { model -> BlockedInlineImage(model) },
            custom = { type, model ->
                if (type == MarkdownElementTypes.HTML_BLOCK) {
                    RawHtmlText(model)
                } else {
                    val components = LocalMarkdownComponents.current
                    model.node.children.forEach { child ->
                        MarkdownElement(child, components, model.content)
                    }
                }
            },
        ),
    )
}

/**
 * Annotator hook for inline content.
 *
 * Images become inert text, raw HTML tokens keep their source text visible,
 * and links outside the allowlist are rendered as plain text.
 */
private fun annotateSafeMarkdown(
    builder: AnnotatedString.Builder,
    content: String,
    child: org.intellij.markdown.ast.ASTNode,
): Boolean = with(builder) {
    when (child.type) {
        MarkdownElementTypes.IMAGE -> {
            val alt = child.resolveImageAlt(content)?.trim().orEmpty()
            append("${blockedImageLabel(alt)} $REMOTE_IMAGE_NOTE")
            true
        }

        MarkdownTokenTypes.HTML_TAG,
        MarkdownTokenTypes.HTML_BLOCK_CONTENT,
        -> {
            append(child.getTextInNode(content))
            true
        }

        MarkdownElementTypes.INLINE_LINK -> {
            val destination = child
                .findChildOfType(MarkdownElementTypes.LINK_DESTINATION)
                ?.getUnescapedTextInNode(content)
            if (sanitizeMarkdownUrl(destination) == null) {
                val text = child
                    .findChildOfType(MarkdownElementTypes.LINK_TEXT)
                    ?.getUnescapedTextInNode(content)
                append(text ?: child.getTextInNode(content).toString())
                true
            } else {
                false
            }
        }

        GFMTokenTypes.GFM_AUTOLINK,
        MarkdownElementTypes.AUTOLINK,
        -> {
            val destination = child.getUnescapedTextInNode(content)
            if (sanitizeMarkdownUrl(destination) == null) {
                append(destination)
                true
            } else {
                false
            }
        }

        else -> false
    }
}

@Composable
private fun BlockFencedCode(model: MarkdownComponentModel) {
    val (code, language) = remember(model.content, model.node) {
        extractFencedCode(model.content, model.node)
    }
    ChatCodeBlock(code = code, language = language, style = model.typography.code)
}

private val CLOSING_FENCE = Regex("^ {0,3}(`{3,}|~{3,})\\s*$")

/** Extract the exact source text and language from a CODE_FENCE node. */
internal fun extractFencedCode(
    content: String,
    node: org.intellij.markdown.ast.ASTNode,
): Pair<String, String?> {
    val lines = node.getTextInNode(content).toString().split('\n').toMutableList()
    val opening = lines.firstOrNull().orEmpty()
    val language = FENCE_PATTERN.find(opening)
        ?.groupValues
        ?.get(2)
        ?.trim()
        ?.substringBefore(' ')
        ?.takeIf { it.isNotEmpty() }

    if (lines.isNotEmpty()) lines.removeAt(0)
    if (lines.size >= 2 && lines.last().isBlank() && CLOSING_FENCE.matches(lines[lines.lastIndex - 1])) {
        lines.removeAt(lines.lastIndex)
    }
    if (lines.isNotEmpty() && CLOSING_FENCE.matches(lines.last())) {
        lines.removeAt(lines.lastIndex)
    }

    return lines.joinToString("\n") to language
}

@Composable
private fun BlockedImage(model: MarkdownComponentModel) {
    val dimens = LocalDimens.current
    val alt = remember(model.content, model.node) {
        model.node.resolveImageAlt(model.content)?.trim().orEmpty()
    }
    val destination = remember(model.content, model.node) {
        model.node.resolveImageLink(model.content, null)
    }
    val safeUrl = remember(destination) { sanitizeMarkdownUrl(destination) }
    val uriHandler = LocalUriHandler.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = dimens.space1)
            .testTag(ChatMarkdownTestTags.BlockedImage),
        verticalArrangement = Arrangement.spacedBy(dimens.space1),
    ) {
        Text(
            text = blockedImageLabel(alt),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = REMOTE_IMAGE_NOTE,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (safeUrl != null) {
            TextButton(onClick = { uriHandler.openUri(safeUrl) }) {
                Text("Open image")
            }
        }
    }
}

@Composable
private fun BlockedInlineImage(model: MarkdownComponentModel) {
    Text(
        text = blockedImageLabel(""),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun RawHtmlText(model: MarkdownComponentModel) {
    Text(
        text = model.node.getTextInNode(model.content).toString(),
        style = model.typography.text,
    )
}

private const val REMOTE_IMAGE_NOTE = "Remote images are blocked."

private fun blockedImageLabel(alt: String): String =
    if (alt.isNotBlank()) "[Image: $alt]" else "[Image]"
