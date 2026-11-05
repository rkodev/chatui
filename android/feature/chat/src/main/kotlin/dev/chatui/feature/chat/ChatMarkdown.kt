package dev.chatui.feature.chat

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
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
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.findChildOfType
import org.intellij.markdown.ast.getTextInNode
import org.intellij.markdown.flavours.gfm.GFMTokenTypes

/** Test tags exposed by rendered Markdown. */
object ChatMarkdownTestTags {
    const val BlockedImage = "chat-markdown-blocked-image"
    const val LoadImage = "chat-markdown-load-image"
    const val OpenImage = "chat-markdown-open-image"
    const val MathBlock = "chat-markdown-math-block"
}

/**
 * Full Markdown source for the current [MarkdownBody].
 *
 * The Markdown library passes a paragraph-local `content` to inline image
 * components, but image node offsets are relative to the full source. This
 * local keeps the safe image resolver aligned with the parsed document.
 */
private val LocalMarkdownContent = compositionLocalOf { "" }

/**
 * Safe Markdown renderer for chat message content.
 *
 * GFM (tables, task lists, strikethrough, autolinks) is enabled. Raw HTML is
 * shown as inert text and never parsed, remote images render as blocked
 * placeholders unless [loadRemoteImages] or a per-message load action allows
 * them, links outside the M1 allowlist are inert, and an unterminated fence is
 * closed for rendering so streaming code never shows raw backticks.
 *
 * Math is rendered as a documented raw-source fallback: standalone `$$` blocks
 * and fenced `math`/`latex`/`tex` blocks show their exact source in a bounded
 * block, while inline `$...$` math stays in the Markdown flow as raw text. No
 * math typesetting library is bundled, so hostile TeX can never execute.
 */
@Composable
fun ChatMarkdown(
    content: String,
    modifier: Modifier = Modifier,
    chatFontSize: ChatFontSize = ChatFontSize.MD,
    parseImmediately: Boolean = false,
    loadRemoteImages: Boolean = false,
) {
    val rendered = remember(content) { closeIncompleteFence(content) }
    val blocks = remember(rendered) { splitBlockMath(rendered) }

    if (blocks.size == 1 && blocks.first() is MathBlock.Text) {
        MarkdownBody(
            content = (blocks.first() as MathBlock.Text).text,
            modifier = modifier,
            chatFontSize = chatFontSize,
            parseImmediately = parseImmediately,
            loadRemoteImages = loadRemoteImages,
        )
    } else {
        Column(
            modifier = modifier,
            verticalArrangement = Arrangement.spacedBy(LocalDimens.current.space1),
        ) {
            blocks.forEach { block ->
                when (block) {
                    is MathBlock.Text -> MarkdownBody(
                        content = block.text,
                        chatFontSize = chatFontSize,
                        parseImmediately = parseImmediately,
                        loadRemoteImages = loadRemoteImages,
                    )

                    is MathBlock.Math -> ChatMath(source = block.source, display = true)
                }
            }
        }
    }
}

@Composable
private fun MarkdownBody(
    content: String,
    modifier: Modifier = Modifier,
    chatFontSize: ChatFontSize,
    parseImmediately: Boolean,
    loadRemoteImages: Boolean,
) {
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
        content = content,
        retainState = true,
        immediate = parseImmediately,
    )

    CompositionLocalProvider(LocalMarkdownContent provides content) {
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
                image = { model -> BlockedImage(model, loadRemoteImages) },
                inlineImage = { model -> BlockedInlineImage(model, loadRemoteImages) },
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
    if (isMathLanguage(language)) {
        ChatMath(source = code, display = true)
    } else {
        ChatCodeBlock(code = code, language = language, style = model.typography.code)
    }
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

/** A block of Markdown text or a standalone raw-source math block. */
internal sealed interface MathBlock {
    data class Text(val text: String) : MathBlock

    data class Math(val source: String) : MathBlock
}

/**
 * Split standalone `$$` math blocks out of Markdown.
 *
 * Fenced code is left untouched, and inline `$$...$$` or `$...$` math stays in
 * the surrounding text so the Markdown renderer can show it as raw source.
 * An unterminated `$$` block is also left as text.
 */
internal fun splitBlockMath(markdown: String): List<MathBlock> {
    if (markdown.isEmpty()) return listOf(MathBlock.Text(markdown))

    val lines = markdown.split('\n')
    val blocks = mutableListOf<MathBlock>()
    val text = StringBuilder()
    var fenceMarker: Char? = null
    var fenceLength = 0
    var index = 0

    fun flushText() {
        if (text.isNotEmpty()) {
            blocks += MathBlock.Text(text.toString())
            text.clear()
        }
    }

    while (index < lines.size) {
        val line = lines[index]
        val fence = FENCE_PATTERN.find(line)

        if (fenceMarker == null && fence != null) {
            fenceMarker = fence.groupValues[1].first()
            fenceLength = fence.groupValues[1].length
            text.appendLine(line)
            index++
            continue
        }

        if (fenceMarker != null) {
            text.appendLine(line)
            if (fence != null) {
                val marker = fence.groupValues[1].first()
                val length = fence.groupValues[1].length
                if (marker == fenceMarker &&
                    length >= fenceLength &&
                    fence.groupValues[2].isBlank()
                ) {
                    fenceMarker = null
                }
            }
            index++
            continue
        }

        if (line.trim() == "$$") {
            val end = (index + 1 until lines.size).firstOrNull { lines[it].trim() == "$$" }
            if (end != null) {
                flushText()
                val source = lines.subList(index + 1, end).joinToString("\n").trim('\n')
                blocks += MathBlock.Math(source)
                index = end + 1
                continue
            }
        }

        text.appendLine(line)
        index++
    }

    flushText()
    return blocks.ifEmpty { listOf(MathBlock.Text(markdown)) }
}

private fun isMathLanguage(language: String?): Boolean =
    language?.trim()?.lowercase() in setOf("math", "latex", "tex")

/**
 * Raw-source math block.
 *
 * No math typesetting library is bundled. The exact TeX source is shown in a
 * bounded, non-executable block so invalid or hostile input cannot break the
 * message or run code.
 */
@Composable
internal fun ChatMath(
    source: String,
    display: Boolean,
    modifier: Modifier = Modifier,
) {
    val dimens = LocalDimens.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = dimens.space1)
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.small,
            )
            .padding(dimens.space3)
            .testTag(ChatMarkdownTestTags.MathBlock),
        verticalArrangement = Arrangement.spacedBy(dimens.space1),
    ) {
        Text(
            text = "Math (raw source)",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = if (display) "$$\n$source\n$$" else "$$source$",
            style = Typography.codeStyle,
        )
    }
}

@Composable
private fun BlockedImage(model: MarkdownComponentModel, loadRemoteImages: Boolean) {
    val markdown = LocalMarkdownContent.current
    val alt = remember(markdown, model.node) {
        model.node.resolveImageAlt(markdown)?.trim().orEmpty()
    }
    val destination = remember(markdown, model.node) {
        model.node.resolveImageLink(markdown, null)
    }
    BlockedImageContent(alt = alt, destination = destination, loadRemoteImages = loadRemoteImages)
}

@Composable
private fun BlockedInlineImage(model: MarkdownComponentModel, loadRemoteImages: Boolean) {
    val markdown = LocalMarkdownContent.current
    val alt = remember(markdown, model.node) {
        model.node.resolveImageAlt(markdown)?.trim().orEmpty()
    }
    val destination = remember(markdown, model.node) {
        model.node.resolveImageLink(markdown, null)
    }
    BlockedImageContent(alt = alt, destination = destination, loadRemoteImages = loadRemoteImages)
}

@Composable
private fun BlockedImageContent(
    alt: String,
    destination: String?,
    loadRemoteImages: Boolean,
) {
    val dimens = LocalDimens.current
    val safeUrl = remember(destination) { sanitizeImageUrl(destination) }
    val uriHandler = LocalUriHandler.current
    var loadRequested by remember(safeUrl) { mutableStateOf(false) }

    if (safeUrl != null && (loadRemoteImages || loadRequested)) {
        RemoteImage(url = safeUrl, alt = alt)
        return
    }

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
            TextButton(
                onClick = { loadRequested = true },
                modifier = Modifier.testTag(ChatMarkdownTestTags.LoadImage),
            ) {
                Text("Load image")
            }
            TextButton(
                onClick = { uriHandler.openUri(safeUrl) },
                modifier = Modifier.testTag(ChatMarkdownTestTags.OpenImage),
            ) {
                Text("Open image")
            }
        }
    }
}

@Composable
private fun RemoteImage(url: String, alt: String) {
    val image by produceState<ImageBitmap?>(initialValue = null, url) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                URL(url).openStream().use { stream ->
                    BitmapFactory.decodeStream(stream)?.asImageBitmap()
                }
            }.getOrNull()
        }
    }

    val bitmap = image
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = alt.ifBlank { "Remote image" },
            modifier = Modifier.fillMaxWidth(),
            contentScale = ContentScale.Fit,
        )
    } else {
        Text(
            text = "Image unavailable.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
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

/** Only absolute http(s) image sources may ever be loaded. */
internal fun sanitizeImageUrl(url: String?): String? =
    sanitizeMarkdownUrl(url)?.takeIf {
        it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true)
    }
