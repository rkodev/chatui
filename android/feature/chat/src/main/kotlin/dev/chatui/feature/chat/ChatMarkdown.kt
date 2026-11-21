package dev.chatui.feature.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.size.Scale
import com.mikepenz.markdown.compose.LocalMarkdownComponents
import com.mikepenz.markdown.compose.MarkdownElement
import com.mikepenz.markdown.compose.MarkdownSuccess
import com.mikepenz.markdown.compose.components.MarkdownComponentModel
import com.mikepenz.markdown.compose.components.MarkdownComponents
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownCodeBlock
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.elements.MarkdownCheckBox
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.State
import com.mikepenz.markdown.model.markdownAnnotator
import com.mikepenz.markdown.model.parseMarkdown
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
    const val LoadImage = "chat-markdown-load-image"
    const val OpenImage = "chat-markdown-open-image"
    const val MathBlock = "chat-markdown-math-block"
    const val RemoteImage = "chat-markdown-remote-image"
    const val LoadingImage = "chat-markdown-loading-image"
    const val LoadedImage = "chat-markdown-loaded-image"
    const val ImageError = "chat-markdown-image-error"
    const val RetryImage = "chat-markdown-retry-image"
}

/**
 * Full Markdown source for the current [MarkdownBody].
 *
 * The Markdown library passes a paragraph-local `content` to inline image
 * components, but image node offsets are relative to the full source. This
 * local keeps the safe image resolver aligned with the parsed document.
 */
private val LocalMarkdownContent = compositionLocalOf { "" }
private val LocalMarkdownImageOwner = compositionLocalOf<String?> { null }
internal val LocalMarkdownImageLoader = compositionLocalOf<ImageLoader?> { null }

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
    imageOwnerKey: String? = null,
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
            imageOwnerKey = imageOwnerKey,
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
                        imageOwnerKey = imageOwnerKey,
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
    imageOwnerKey: String?,
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

    val components = markdownComponents(
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
    )
    val success: @Composable (State.Success, MarkdownComponents, Modifier) -> Unit =
        { parsed, successComponents, contentModifier ->
            // Retained AST offsets belong to their parsed source, not a newer input.
            CompositionLocalProvider(LocalMarkdownContent provides parsed.content) {
                MarkdownSuccess(state = parsed, components = successComponents, modifier = contentModifier)
            }
        }

    CompositionLocalProvider(
        LocalMarkdownImageOwner provides imageOwnerKey,
    ) {
        if (parseImmediately) {
            // rememberMarkdownState only parses the initial input synchronously; later
            // updates go through a background dispatcher that the test clock cannot
            // await. Active streams must reflect the latest token in the same frame,
            // so parse each update synchronously instead.
            val state = remember(content) { parseMarkdown(content) }
            Markdown(
                state = state,
                modifier = modifier,
                typography = typography,
                annotator = annotator,
                success = success,
                components = components,
            )
        } else {
            val markdownState = rememberMarkdownState(
                content = content,
                retainState = true,
                immediate = false,
            )
            Markdown(
                markdownState = markdownState,
                modifier = modifier,
                typography = typography,
                annotator = annotator,
                success = success,
                components = components,
            )
        }
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
    val owner = LocalMarkdownImageOwner.current
    var loadRequested by remember(owner, safeUrl) { mutableStateOf(false) }

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
                modifier = Modifier.heightIn(min = dimens.touchTargetMin).testTag(ChatMarkdownTestTags.LoadImage),
            ) {
                Text("Load image")
            }
            TextButton(
                onClick = { uriHandler.openUri(safeUrl) },
                modifier = Modifier.heightIn(min = dimens.touchTargetMin).testTag(ChatMarkdownTestTags.OpenImage),
            ) {
                Text("Open image")
            }
        }
    }
}

@Composable
private fun RemoteImage(url: String, alt: String) {
    val context = LocalContext.current.applicationContext
    val density = LocalDensity.current
    val dimens = LocalDimens.current
    val owner = LocalMarkdownImageOwner.current
    val imageLoader = LocalMarkdownImageLoader.current ?: SingletonImageLoader.get(context)
    var retry by remember(owner, url) { mutableIntStateOf(0) }
    BoxWithConstraints(Modifier.fillMaxWidth().testTag(ChatMarkdownTestTags.RemoteImage)) {
        val displayWidth = maxWidth.coerceIn(1.dp, 760.dp)
        val displayHeight = maxHeight.coerceIn(1.dp, 320.dp)
        val widthPx = with(density) { displayWidth.roundToPx().coerceAtLeast(1) }
        val heightPx = with(density) { displayHeight.roundToPx().coerceAtLeast(1) }
        val request = remember(context, url, widthPx, heightPx) {
            ImageRequest.Builder(context)
                .data(url)
                .size(widthPx, heightPx)
                .scale(Scale.FIT)
                .build()
        }
        var status by remember(owner, request, retry) { mutableStateOf(RemoteImageStatus.Pending) }
        Box(Modifier.fillMaxWidth()) {
            key(owner, request, retry) {
                AsyncImage(
                    model = request,
                    imageLoader = imageLoader,
                    contentDescription = if (status == RemoteImageStatus.Ready) alt.ifBlank { "Remote image" } else null,
                    contentScale = ContentScale.Fit,
                    onLoading = { status = RemoteImageStatus.Pending },
                    onSuccess = { status = RemoteImageStatus.Ready },
                    onError = { status = RemoteImageStatus.Failed },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp.coerceAtMost(displayHeight), max = displayHeight)
                        .then(
                            if (status == RemoteImageStatus.Ready) {
                                Modifier.testTag(ChatMarkdownTestTags.LoadedImage)
                            } else {
                                Modifier
                            },
                        ),
                )
            }
            if (status != RemoteImageStatus.Ready) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = dimens.space2)
                        .testTag(
                            if (status == RemoteImageStatus.Pending) {
                                ChatMarkdownTestTags.LoadingImage
                            } else {
                                ChatMarkdownTestTags.ImageError
                            },
                        ),
                    verticalArrangement = Arrangement.spacedBy(dimens.space1),
                ) {
                    if (alt.isNotBlank()) Text(blockedImageLabel(alt), style = Typography.footnote)
                    Text(
                        text = if (status == RemoteImageStatus.Pending) "Loading image..." else "Image unavailable.",
                        style = Typography.footnote,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (status == RemoteImageStatus.Failed) {
                        TextButton(
                            onClick = { retry++ },
                            modifier = Modifier.heightIn(min = dimens.touchTargetMin)
                                .testTag(ChatMarkdownTestTags.RetryImage),
                        ) {
                            Text("Retry image")
                        }
                    }
                }
            }
        }
    }
}

private enum class RemoteImageStatus { Pending, Ready, Failed }

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
