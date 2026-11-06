import type { ExportArtifact } from './exportService'

/**
 * Trigger a browser download for a generated export artifact.
 *
 * The artifact is wrapped in a Blob so large exports are not placed in a data
 * URL. The object URL is revoked immediately after the synthetic click; the
 * browser has already claimed the blob by then.
 */
export function downloadTextFile(
  artifact: ExportArtifact,
  doc: Document = document,
  urlApi: Pick<typeof URL, 'createObjectURL' | 'revokeObjectURL'> = URL,
): void {
  const blob = new Blob([artifact.content], { type: `${artifact.mimeType};charset=utf-8` })
  const url = urlApi.createObjectURL(blob)
  const anchor = doc.createElement('a')

  anchor.href = url
  anchor.download = artifact.filename
  anchor.rel = 'noopener'
  anchor.style.display = 'none'

  doc.body.append(anchor)

  try {
    anchor.click()
  } finally {
    anchor.remove()
    urlApi.revokeObjectURL(url)
  }
}
