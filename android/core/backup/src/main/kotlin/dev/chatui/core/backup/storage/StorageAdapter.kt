package dev.chatui.core.backup.storage

/**
 * Storage adapter boundary for encrypted backup objects.
 *
 * Adapters only ever receive opaque bytes. Callers must encrypt with the
 * envelope codec before calling [StorageAdapter.putObject]; no adapter accepts
 * a string body or a plaintext upload path.
 */
enum class StorageErrorCode {
    AUTH,
    PERMISSION,
    NOT_FOUND,
    CONFLICT,
    NETWORK,
    SERVER,
}

/** Typed storage failure. [message] is safe to show to a user. */
class StorageError(
    val code: StorageErrorCode,
    message: String,
    val status: Int? = null,
    cause: Throwable? = null,
) : Exception(message, cause)

/** One object returned by a storage adapter. */
data class StorageObject(
    /** Logical object key relative to the adapter's configured prefix. */
    val key: String,
    /** Object bytes. [StorageAdapter.headObject] and [StorageAdapter.listObjects] return an empty array. */
    val bytes: ByteArray,
    /** Raw `ETag` response header, including quotes when the provider sends them. */
    val etag: String? = null,
    /** Raw `Last-Modified` response header. */
    val lastModified: String? = null,
)

/** Options for a conditional object write. */
data class PutObjectOptions(
    val contentType: String = "application/octet-stream",
    /** Send `If-Match` to require the current ETag. */
    val ifMatch: String? = null,
    /** Send `If-None-Match: *` (or another value) to require absence. */
    val ifNoneMatch: String? = null,
)

/** Options for a bucket listing. */
data class ListObjectsOptions(
    /** Logical key prefix, relative to the adapter's configured prefix. */
    val prefix: String? = null,
    val continuationToken: String? = null,
    val maxKeys: Int? = null,
)

/** One page of a bucket listing. */
data class ListObjectsResult(
    val objects: List<StorageObject>,
    val nextContinuationToken: String? = null,
)

/**
 * Direct client-to-storage adapter. There is no server proxy.
 *
 * Every write takes a [ByteArray] so callers cannot pass a plaintext string.
 * The adapter does not encrypt; encryption belongs to the envelope codec.
 */
interface StorageAdapter {
    /** Verify credentials and bucket access without writing an object. */
    suspend fun testConnection()

    suspend fun getObject(key: String): StorageObject

    suspend fun putObject(
        key: String,
        encryptedBytes: ByteArray,
        options: PutObjectOptions = PutObjectOptions(),
    ): StorageObject

    suspend fun headObject(key: String): StorageObject

    suspend fun deleteObject(key: String)

    suspend fun listObjects(options: ListObjectsOptions = ListObjectsOptions()): ListObjectsResult
}
