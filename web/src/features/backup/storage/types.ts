/**
 * Storage adapter boundary for encrypted backup objects.
 *
 * Adapters only ever receive opaque bytes. Callers must encrypt with the
 * envelope codec before calling `putObject`; no adapter accepts a string body.
 */

export type StorageErrorCode =
  | 'auth'
  | 'permission'
  | 'not_found'
  | 'conflict'
  | 'network'
  | 'server'

export interface StorageErrorOptions {
  /** HTTP status when the failure came from a response. */
  status?: number
  /** Underlying error, for example a rejected `fetch` promise. */
  cause?: unknown
}

export class StorageError extends Error {
  readonly code: StorageErrorCode
  readonly status?: number

  constructor(code: StorageErrorCode, message: string, options: StorageErrorOptions = {}) {
    super(message, options.cause === undefined ? undefined : { cause: options.cause })
    this.name = 'StorageError'
    this.code = code
    this.status = options.status
  }
}

export interface StorageObject {
  /** Logical object key relative to the adapter's configured prefix. */
  key: string
  /** Object bytes. `headObject` and `listObjects` return an empty array. */
  bytes: Uint8Array
  /** Raw `ETag` response header, including quotes when the provider sends them. */
  etag?: string
  /** Raw `Last-Modified` response header. */
  lastModified?: string
}

export interface PutObjectOptions {
  contentType?: string
  /** Send `If-Match` to require the current ETag. */
  ifMatch?: string
  /** Send `If-None-Match: *` (or another value) to require absence. */
  ifNoneMatch?: string
}

export interface ListObjectsOptions {
  /** Logical key prefix, relative to the adapter's configured prefix. */
  prefix?: string
  continuationToken?: string
  maxKeys?: number
}

export interface ListObjectsResult {
  objects: StorageObject[]
  nextContinuationToken?: string
}

export interface StorageAdapter {
  /** Verify credentials and bucket access without writing an object. */
  testConnection(): Promise<void>
  getObject(key: string): Promise<StorageObject>
  putObject(key: string, bytes: Uint8Array, options?: PutObjectOptions): Promise<StorageObject>
  headObject(key: string): Promise<StorageObject>
  deleteObject(key: string): Promise<void>
  listObjects(options?: ListObjectsOptions): Promise<ListObjectsResult>
}
