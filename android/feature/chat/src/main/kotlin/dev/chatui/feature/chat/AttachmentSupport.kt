package dev.chatui.feature.chat

import dev.chatui.core.model.Attachment
import dev.chatui.core.model.AttachmentKind
import dev.chatui.core.model.InputModality

/** Whether the selected model can receive the pending attachments. */
data class AttachmentSupport(
    val images: Boolean,
    val files: Boolean,
    val unsupportedCount: Int,
    val message: String?,
) {
    val supported: Boolean
        get() = unsupportedCount == 0
}

/**
 * Compare pending attachments with the selected model's input modalities.
 *
 * Images require the `image` modality. Text files serialize as text headers, so
 * any model that accepts text can receive them; this keeps a text-only model
 * warning focused on the attachment that would actually be lost.
 */
fun attachmentSupport(
    attachments: List<Attachment>,
    inputModalities: List<InputModality>,
): AttachmentSupport {
    val modalities = inputModalities.toSet().ifEmpty { setOf(InputModality.TEXT) }
    val images = InputModality.IMAGE in modalities
    val files = InputModality.FILE in modalities || InputModality.TEXT in modalities
    val unsupported = attachments.filter { attachment ->
        (attachment.kind == AttachmentKind.IMAGE && !images) ||
            (attachment.kind == AttachmentKind.FILE && !files)
    }

    val message = when {
        unsupported.any { it.kind == AttachmentKind.IMAGE } ->
            "This model cannot receive images. Remove the image attachments or choose a vision model."
        unsupported.isNotEmpty() ->
            "This model cannot receive file attachments. Remove them or choose another model."
        else -> null
    }

    return AttachmentSupport(
        images = images,
        files = files,
        unsupportedCount = unsupported.size,
        message = message,
    )
}
