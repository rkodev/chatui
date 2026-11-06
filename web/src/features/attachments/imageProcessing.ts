import { MAX_IMAGE_EDGE } from './attachmentRules'

export interface ImageProcessorInput {
  bytes: Uint8Array
  mimeType: string
}

export interface ProcessedImage {
  bytes: Uint8Array
  mimeType: string
  width: number
  height: number
}

export interface ImageProcessor {
  (input: ImageProcessorInput): Promise<ProcessedImage>
}

export interface Dimensions {
  width: number
  height: number
}

/**
 * Scale dimensions so the longest edge is at most `maxEdge`.
 *
 * Upscaling never happens. Non-positive or non-finite inputs are returned
 * unchanged so a malformed decoder result cannot produce a zero-size canvas.
 */
export function scaledDimensions(
  width: number,
  height: number,
  maxEdge: number = MAX_IMAGE_EDGE,
): Dimensions {
  if (!Number.isFinite(width) || !Number.isFinite(height) || width <= 0 || height <= 0) {
    return { width, height }
  }

  const longest = Math.max(width, height)

  if (longest <= maxEdge) {
    return { width: Math.round(width), height: Math.round(height) }
  }

  const ratio = maxEdge / longest
  return {
    width: Math.max(1, Math.round(width * ratio)),
    height: Math.max(1, Math.round(height * ratio)),
  }
}

/**
 * Prefer WebP for images that may have transparency, JPEG for opaque photos.
 * Both are re-encoded, which removes EXIF and other container metadata.
 */
export function outputMimeTypeFor(inputMimeType: string): string {
  return inputMimeType === 'image/jpeg' ? 'image/jpeg' : 'image/webp'
}

function canvasToBlob(canvas: HTMLCanvasElement, mimeType: string): Promise<Blob | null> {
  return new Promise((resolve) => {
    canvas.toBlob((blob) => resolve(blob), mimeType, 0.9)
  })
}

async function decode(
  bytes: Uint8Array,
  mimeType: string,
): Promise<ImageBitmap | HTMLImageElement | null> {
  const blob = new Blob([new Uint8Array(bytes)], { type: mimeType })

  if (typeof createImageBitmap === 'function') {
    try {
      return await createImageBitmap(blob, { imageOrientation: 'from-image' })
    } catch {
      return null
    }
  }

  if (typeof document === 'undefined' || typeof URL.createObjectURL !== 'function') {
    return null
  }

  const url = URL.createObjectURL(blob)

  try {
    const image = new Image()
    image.src = url
    await image.decode()
    return image
  } catch {
    return null
  } finally {
    // The image has decoded by the time the promise settles; revoking is safe.
    setTimeout(() => URL.revokeObjectURL(url), 0)
  }
}

function dispose(image: ImageBitmap | HTMLImageElement): void {
  if (typeof ImageBitmap !== 'undefined' && image instanceof ImageBitmap) {
    image.close()
  }
}

/**
 * Decode, downscale, and re-encode an image so the stored bytes contain no
 * EXIF or other source metadata. When the browser cannot decode the image, the
 * original bytes are returned unchanged rather than losing the attachment.
 */
export const processImage: ImageProcessor = async ({ bytes, mimeType }) => {
  if (typeof document === 'undefined') {
    return { bytes, mimeType, width: 0, height: 0 }
  }

  const image = await decode(bytes, mimeType)

  if (image === null) {
    return { bytes, mimeType, width: 0, height: 0 }
  }

  try {
    const dimensions = scaledDimensions(image.width, image.height)
    const canvas = document.createElement('canvas')
    canvas.width = dimensions.width
    canvas.height = dimensions.height

    const context = canvas.getContext('2d')

    if (context === null) {
      return { bytes, mimeType, width: image.width, height: image.height }
    }

    context.drawImage(image, 0, 0, dimensions.width, dimensions.height)

    let outputMimeType = outputMimeTypeFor(mimeType)
    let blob = await canvasToBlob(canvas, outputMimeType)

    if (blob === null && outputMimeType !== 'image/png') {
      outputMimeType = 'image/png'
      blob = await canvasToBlob(canvas, outputMimeType)
    }

    if (blob === null) {
      return { bytes, mimeType, width: image.width, height: image.height }
    }

    return {
      bytes: new Uint8Array(await blob.arrayBuffer()),
      mimeType: blob.type || outputMimeType,
      width: dimensions.width,
      height: dimensions.height,
    }
  } finally {
    dispose(image)
  }
}
