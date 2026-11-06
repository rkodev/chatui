import type { ContentPart } from '../../domain'
import type { AttachmentView } from './AttachmentChips'

/** Map message content parts to the chip view model. */
export function attachmentViewsFromParts(parts: readonly ContentPart[]): AttachmentView[] {
  const views: AttachmentView[] = []

  for (const part of parts) {
    if (part.type === 'image') {
      views.push({
        id: part.attachmentId,
        kind: 'image',
        name: part.alt.trim() === '' ? 'Image attachment' : part.alt,
        mimeType: part.mimeType,
        size: null,
      })
    } else if (part.type === 'file') {
      views.push({
        id: part.attachmentId,
        kind: 'file',
        name: part.name,
        mimeType: part.mimeType,
        size: null,
      })
    }
  }

  return views
}
