export {
  MAX_FILE_NAME_LENGTH,
  MAX_IMAGE_BYTES,
  MAX_IMAGE_EDGE,
  MAX_TEXT_BYTES,
  SUPPORTED_IMAGE_MIME_TYPES,
  SUPPORTED_TEXT_EXTENSIONS,
  SUPPORTED_TEXT_MIME_TYPES,
  fileExtension,
  formatAttachmentSize,
  sanitizeFileName,
  validateAttachmentFile,
  type AttachmentFileInfo,
  type AttachmentKind,
  type AttachmentValidationCode,
  type AttachmentValidationError,
  type ValidatedAttachmentFile,
} from './attachmentRules'
export {
  outputMimeTypeFor,
  processImage,
  scaledDimensions,
  type Dimensions,
  type ImageProcessor,
  type ImageProcessorInput,
  type ProcessedImage,
} from './imageProcessing'
export {
  AttachmentService,
  isTextAttachment,
  type AddAttachmentsResult,
  type AttachmentServiceOptions,
} from './attachmentService'
export { attachmentSupport, type AttachmentSupport } from './attachmentSupport'
export {
  AttachmentChips,
  MessageAttachments,
  type AttachmentChipsProps,
  type AttachmentView,
} from './AttachmentChips'
export { attachmentViewsFromParts } from './attachmentViews'
