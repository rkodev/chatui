import { describe, expect, it } from 'vitest'
import { MAX_IMAGE_EDGE } from './attachmentRules'
import { outputMimeTypeFor, scaledDimensions } from './imageProcessing'

describe('imageProcessing', () => {
  it('downscales the longest edge to 2048 px and keeps the aspect ratio', () => {
    expect(scaledDimensions(4096, 2048)).toEqual({ width: MAX_IMAGE_EDGE, height: 1024 })
    expect(scaledDimensions(1024, 4096)).toEqual({ width: 512, height: MAX_IMAGE_EDGE })
  })

  it('never upscales smaller images', () => {
    expect(scaledDimensions(800, 600)).toEqual({ width: 800, height: 600 })
  })

  it('guards against malformed dimensions', () => {
    expect(scaledDimensions(0, 0)).toEqual({ width: 0, height: 0 })
    expect(scaledDimensions(Number.NaN, 100)).toEqual({ width: Number.NaN, height: 100 })
  })

  it('re-encodes JPEG as JPEG and alpha-capable formats as WebP', () => {
    expect(outputMimeTypeFor('image/jpeg')).toBe('image/jpeg')
    expect(outputMimeTypeFor('image/png')).toBe('image/webp')
    expect(outputMimeTypeFor('image/gif')).toBe('image/webp')
  })
})
