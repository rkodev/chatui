import type { Attachment, InputModality, Model } from '../../domain'

export interface AttachmentSupport {
  images: boolean
  files: boolean
  unsupportedCount: number
  message: string | null
}

function modalitiesFor(model: Model | null | undefined): Set<InputModality> {
  return new Set(model?.inputModalities ?? ['text'])
}

/**
 * Compare pending attachments with the selected model's input modalities.
 *
 * Images require the `image` modality. Text files serialize as text headers,
 * so any model that accepts text can receive them; this keeps a text-only
 * model warning focused on the attachment that would actually be lost.
 */
export function attachmentSupport(
  attachments: readonly Attachment[],
  model: Model | null | undefined,
): AttachmentSupport {
  const modalities = modalitiesFor(model)
  const images = modalities.has('image')
  const files = modalities.has('file') || modalities.has('text')
  const unsupported = attachments.filter(
    (attachment) =>
      (attachment.kind === 'image' && !images) || (attachment.kind === 'file' && !files),
  )

  let message: string | null = null

  if (unsupported.some((attachment) => attachment.kind === 'image')) {
    message =
      'This model cannot receive images. Remove the image attachments or choose a vision model.'
  } else if (unsupported.length > 0) {
    message = 'This model cannot receive file attachments. Remove them or choose another model.'
  }

  return {
    images,
    files,
    unsupportedCount: unsupported.length,
    message,
  }
}
