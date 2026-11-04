import { describe, expect, it } from 'vitest'
import { createTestModel } from '../providers/testSupport'
import { createTestAttachment } from '../chat/testSupport'
import { attachmentSupport } from './attachmentSupport'

describe('attachmentSupport', () => {
  it('allows images only when the model accepts image input', () => {
    const image = createTestAttachment({ kind: 'image' })
    const vision = createTestModel({ inputModalities: ['text', 'image'] })
    const textOnly = createTestModel({ inputModalities: ['text'] })

    expect(attachmentSupport([image], vision).unsupportedCount).toBe(0)
    expect(attachmentSupport([image], textOnly)).toMatchObject({
      images: false,
      unsupportedCount: 1,
    })
    expect(attachmentSupport([image], textOnly).message).toMatch(/cannot receive images/i)
  })

  it('treats text files as supported whenever text input is available', () => {
    const textFile = createTestAttachment({ kind: 'file', mimeType: 'text/plain' })

    expect(attachmentSupport([textFile], createTestModel({ inputModalities: ['text'] }))).toEqual({
      images: false,
      files: true,
      unsupportedCount: 0,
      message: null,
    })
  })
})
