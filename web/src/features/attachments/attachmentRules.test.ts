import { describe, expect, it } from 'vitest'
import {
  MAX_IMAGE_BYTES,
  MAX_TEXT_BYTES,
  formatAttachmentSize,
  sanitizeFileName,
  validateAttachmentFile,
} from './attachmentRules'

describe('attachmentRules', () => {
  it('accepts supported image MIME types', () => {
    const result = validateAttachmentFile({ name: 'pixel.png', type: 'image/png', size: 128 })

    expect(result).toEqual({
      ok: true,
      value: { kind: 'image', mimeType: 'image/png', name: 'pixel.png' },
    })
  })

  it('accepts source files with an empty declared MIME type by extension', () => {
    const result = validateAttachmentFile({ name: 'main.ts', type: '', size: 128 })

    expect(result).toEqual({
      ok: true,
      value: { kind: 'file', mimeType: 'text/plain', name: 'main.ts' },
    })
  })

  it('rejects unsupported types without guessing from an unsafe MIME type', () => {
    const result = validateAttachmentFile({
      name: 'payload.exe',
      type: 'application/x-msdownload',
      size: 128,
    })

    expect(result.ok).toBe(false)

    if (!result.ok) {
      expect(result.error.code).toBe('unsupported_type')
    }
  })

  it('rejects empty and oversized files', () => {
    const empty = validateAttachmentFile({ name: 'notes.txt', type: 'text/plain', size: 0 })
    const image = validateAttachmentFile({
      name: 'huge.png',
      type: 'image/png',
      size: MAX_IMAGE_BYTES + 1,
    })
    const text = validateAttachmentFile({
      name: 'huge.txt',
      type: 'text/plain',
      size: MAX_TEXT_BYTES + 1,
    })

    expect(empty.ok).toBe(false)
    expect(image.ok).toBe(false)
    expect(text.ok).toBe(false)

    if (!image.ok) {
      expect(image.error.code).toBe('too_large')
    }
    if (!text.ok) {
      expect(text.error.code).toBe('too_large')
    }
  })

  it('sanitizes path traversal and control characters in names', () => {
    expect(sanitizeFileName('../../etc/passwd')).toBe('passwd')
    expect(sanitizeFileName('bad\u0000name.txt')).toBe('badname.txt')
    expect(sanitizeFileName('   ')).toBe('attachment')
  })

  it('formats sizes for chips', () => {
    expect(formatAttachmentSize(512)).toBe('512 B')
    expect(formatAttachmentSize(2048)).toBe('2.0 KB')
    expect(formatAttachmentSize(3 * 1024 * 1024)).toBe('3.0 MB')
  })
})
