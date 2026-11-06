/**
 * Attachment validation rules shared by the picker, drag/drop, and paste paths.
 *
 * Limits are deliberately conservative and documented in the feature README.
 * Validation never executes or renders file content; it only classifies bytes
 * by MIME type and file extension.
 */

export const MAX_IMAGE_BYTES = 20 * 1024 * 1024
export const MAX_TEXT_BYTES = 2 * 1024 * 1024
export const MAX_IMAGE_EDGE = 2048
export const MAX_FILE_NAME_LENGTH = 255

export const SUPPORTED_IMAGE_MIME_TYPES = [
  'image/png',
  'image/jpeg',
  'image/webp',
  'image/gif',
] as const

export const SUPPORTED_TEXT_MIME_TYPES = [
  'text/plain',
  'text/markdown',
  'text/csv',
  'text/xml',
  'text/html',
  'text/css',
  'application/json',
  'application/xml',
  'application/javascript',
] as const

/** Source-file extensions that are safe to treat as plain text. */
export const SUPPORTED_TEXT_EXTENSIONS = [
  'txt',
  'md',
  'markdown',
  'json',
  'jsonc',
  'csv',
  'tsv',
  'log',
  'yml',
  'yaml',
  'xml',
  'html',
  'htm',
  'css',
  'scss',
  'less',
  'js',
  'jsx',
  'mjs',
  'cjs',
  'ts',
  'tsx',
  'py',
  'rb',
  'go',
  'rs',
  'java',
  'kt',
  'kts',
  'c',
  'h',
  'cpp',
  'hpp',
  'cc',
  'cs',
  'php',
  'swift',
  'sh',
  'bash',
  'zsh',
  'toml',
  'ini',
  'cfg',
  'sql',
  'gradle',
  'dart',
  'lua',
  'r',
] as const

const TEXT_EXTENSIONS = new Set<string>(SUPPORTED_TEXT_EXTENSIONS)
const IMAGE_MIME_TYPES = new Set<string>(SUPPORTED_IMAGE_MIME_TYPES)
const TEXT_MIME_TYPES = new Set<string>(SUPPORTED_TEXT_MIME_TYPES)

const IMAGE_EXTENSIONS: Record<string, string> = {
  png: 'image/png',
  jpg: 'image/jpeg',
  jpeg: 'image/jpeg',
  webp: 'image/webp',
  gif: 'image/gif',
}

export type AttachmentKind = 'image' | 'file'

export type AttachmentValidationCode = 'empty' | 'too_large' | 'unsupported_type' | 'missing_name'

export interface AttachmentValidationError {
  code: AttachmentValidationCode
  message: string
}

export interface AttachmentFileInfo {
  name: string
  type: string
  size: number
}

export interface ValidatedAttachmentFile {
  kind: AttachmentKind
  mimeType: string
  name: string
}

export function fileExtension(name: string): string {
  const dot = name.lastIndexOf('.')
  return dot < 0 ? '' : name.slice(dot + 1).toLowerCase()
}

export function sanitizeFileName(name: string): string {
  const base = name.split(/[\\/]/).pop() ?? ''
  const cleaned = Array.from(base)
    .filter((character) => {
      const code = character.charCodeAt(0)
      return code >= 0x20 && code !== 0x7f
    })
    .join('')
    .replace(/\s+/g, ' ')
    .trim()

  if (cleaned === '') {
    return 'attachment'
  }

  return Array.from(cleaned).slice(0, MAX_FILE_NAME_LENGTH).join('')
}

function mimeTypeFor(file: AttachmentFileInfo): string {
  const declared = file.type.toLowerCase().trim()

  if (declared !== '') {
    return declared
  }

  const extension = fileExtension(file.name)

  if (extension in IMAGE_EXTENSIONS) {
    return IMAGE_EXTENSIONS[extension]
  }

  if (TEXT_EXTENSIONS.has(extension)) {
    return extension === 'md' || extension === 'markdown'
      ? 'text/markdown'
      : extension === 'csv' || extension === 'tsv'
        ? 'text/csv'
        : extension === 'json' || extension === 'jsonc'
          ? 'application/json'
          : extension === 'xml'
            ? 'application/xml'
            : extension === 'html' || extension === 'htm'
              ? 'text/html'
              : extension === 'css' || extension === 'scss' || extension === 'less'
                ? 'text/css'
                : 'text/plain'
  }

  return ''
}

/**
 * Classify a file and reject unsupported, empty, or oversized input.
 *
 * The extension fallback matters on browsers that report an empty MIME type
 * for source files. An unknown declared MIME type is never overridden by the
 * extension, so a disguised executable cannot be accepted as text.
 */
export function validateAttachmentFile(
  file: AttachmentFileInfo,
): { ok: true; value: ValidatedAttachmentFile } | { ok: false; error: AttachmentValidationError } {
  const name = sanitizeFileName(file.name)

  if (name === '' || name === 'attachment') {
    return {
      ok: false,
      error: { code: 'missing_name', message: 'The file has no usable name.' },
    }
  }

  if (file.size <= 0) {
    return {
      ok: false,
      error: { code: 'empty', message: `${name} is empty.` },
    }
  }

  const declared = file.type.toLowerCase().trim()
  const extension = fileExtension(name)
  const imageByMime = IMAGE_MIME_TYPES.has(declared)
  const imageByExtension = declared === '' && extension in IMAGE_EXTENSIONS
  const textByMime = TEXT_MIME_TYPES.has(declared)
  const textByExtension = declared === '' && TEXT_EXTENSIONS.has(extension)

  if (imageByMime || imageByExtension) {
    if (file.size > MAX_IMAGE_BYTES) {
      return {
        ok: false,
        error: {
          code: 'too_large',
          message: `${name} is larger than the ${MAX_IMAGE_BYTES / (1024 * 1024)} MB image limit.`,
        },
      }
    }

    return {
      ok: true,
      value: {
        kind: 'image',
        mimeType: imageByMime ? declared : IMAGE_EXTENSIONS[extension],
        name: extension === '' ? `${name}.png` : name,
      },
    }
  }

  if (textByMime || textByExtension) {
    if (file.size > MAX_TEXT_BYTES) {
      return {
        ok: false,
        error: {
          code: 'too_large',
          message: `${name} is larger than the ${MAX_TEXT_BYTES / (1024 * 1024)} MB text-file limit.`,
        },
      }
    }

    return {
      ok: true,
      value: {
        kind: 'file',
        mimeType: textByMime ? declared : mimeTypeFor(file),
        name,
      },
    }
  }

  return {
    ok: false,
    error: {
      code: 'unsupported_type',
      message: `${name} is not a supported image or text file.`,
    },
  }
}

/** Human-readable size for chips and message rendering. */
export function formatAttachmentSize(bytes: number): string {
  if (bytes < 1024) {
    return `${bytes} B`
  }

  if (bytes < 1024 * 1024) {
    return `${(bytes / 1024).toFixed(1)} KB`
  }

  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
}
