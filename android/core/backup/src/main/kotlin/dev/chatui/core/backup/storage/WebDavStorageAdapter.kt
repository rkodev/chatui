package dev.chatui.core.backup.storage

import java.net.URI
import java.net.URLDecoder
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.w3c.dom.Element

/** Configuration for a WebDAV collection. */
data class WebDavStorageConfig(
    /** Base collection URL, for example `https://dav.example.com/remote.php/dav/files/me`. */
    val baseUrl: String,
    val username: String,
    val password: String,
    /** Optional key prefix, for example `chatui/backups`. */
    val prefix: String? = null,
    /** Injectable client for tests. */
    val client: OkHttpClient = OkHttpClient(),
    /** Delay between read-after-write retries. Defaults to 100 ms. */
    val retryDelayMs: Long = 100,
    /** Number of HEAD attempts after a write that omits an ETag. */
    val readAfterWriteAttempts: Int = 3,
)

/**
 * WebDAV adapter using Basic authentication.
 *
 * WebDAV has the weakest consistency guarantees of the supported backends. The
 * adapter never assumes read-after-write: after a PUT it re-reads metadata with
 * bounded retries, and conditional writes use `If-Match` / `If-None-Match`
 * where the server supports them.
 *
 * The adapter only accepts [ByteArray] bodies, so callers must encrypt with the
 * backup envelope codec before writing.
 */
class WebDavStorageAdapter(config: WebDavStorageConfig) : StorageAdapter {
    private val base: HttpUrl = config.baseUrl.trimEnd('/').toHttpUrl()
    private val authorization: String = Credentials.basic(config.username, config.password)
    private val prefix: String = (config.prefix ?: "").trim('/')
    private val client: OkHttpClient = config.client
    private val retryDelayMs: Long = config.retryDelayMs
    private val readAfterWriteAttempts: Int = config.readAfterWriteAttempts

    init {
        require(config.username.isNotEmpty()) { "WebDAV username must not be empty" }
        require(config.password.isNotEmpty()) { "WebDAV password must not be empty" }
        require(retryDelayMs >= 0) { "retryDelayMs must not be negative" }
        require(readAfterWriteAttempts > 0) { "readAfterWriteAttempts must be positive" }
    }

    override suspend fun testConnection() {
        val request = authorized(collectionUrl())
            .method("PROPFIND", propfindBody())
            .header("Depth", "0")
            .build()
        client.executeStorage(request).use { response ->
            response.ensureStorageOk("PROPFIND collection")
        }
    }

    override suspend fun getObject(key: String): StorageObject {
        val request = authorized(objectUrl(key)).get().build()
        return client.executeStorage(request).use { response ->
            response.ensureStorageOk("GET $key")
            StorageObject(
                key = key,
                bytes = response.body.bytes(),
                etag = response.header("ETag"),
                lastModified = response.header("Last-Modified"),
            )
        }
    }

    override suspend fun putObject(
        key: String,
        encryptedBytes: ByteArray,
        options: PutObjectOptions,
    ): StorageObject {
        if (options.ifMatch != null && options.ifNoneMatch != null) {
            throw StorageError(
                StorageErrorCode.CONFLICT,
                "Use either ifMatch or ifNoneMatch, not both",
            )
        }

        val builder = authorized(objectUrl(key))
            .put(encryptedBytes.toRequestBody(options.contentType.toMediaType()))
        options.ifMatch?.let { builder.header("If-Match", it) }
        options.ifNoneMatch?.let { builder.header("If-None-Match", it) }

        val written = client.executeStorage(builder.build()).use { response ->
            response.ensureStorageOk("PUT $key")
            StorageObject(
                key = key,
                bytes = encryptedBytes,
                etag = response.header("ETag"),
                lastModified = response.header("Last-Modified"),
            )
        }

        if (written.etag != null) {
            return written
        }

        return try {
            val head = readAfterWrite(readAfterWriteAttempts, retryDelayMs) {
                headObject(key)
            }
            written.copy(
                etag = head.etag,
                lastModified = head.lastModified ?: written.lastModified,
            )
        } catch (error: StorageError) {
            if (error.code == StorageErrorCode.NOT_FOUND) written else throw error
        }
    }

    override suspend fun headObject(key: String): StorageObject {
        val request = authorized(objectUrl(key)).head().build()
        return client.executeStorage(request).use { response ->
            response.ensureStorageOk("HEAD $key")
            StorageObject(
                key = key,
                bytes = ByteArray(0),
                etag = response.header("ETag"),
                lastModified = response.header("Last-Modified"),
            )
        }
    }

    override suspend fun deleteObject(key: String) {
        val request = authorized(objectUrl(key)).delete().build()
        client.executeStorage(request).use { response ->
            if (response.code == 404) {
                return
            }
            response.ensureStorageOk("DELETE $key")
        }
    }

    override suspend fun listObjects(options: ListObjectsOptions): ListObjectsResult {
        val request = authorized(collectionUrl())
            .method("PROPFIND", propfindBody())
            .header("Depth", "1")
            .build()
        return client.executeStorage(request).use { response ->
            response.ensureStorageOk("PROPFIND collection")
            parseListResponse(response.body.string(), options.prefix)
        }
    }

    /** Collection URL. Visible for URL tests. */
    internal fun collectionUrl(): HttpUrl {
        val builder = base.newBuilder()
        if (prefix.isNotEmpty()) {
            for (segment in prefix.split('/')) {
                builder.addPathSegment(segment)
            }
        }
        builder.addPathSegment("")
        return builder.build()
    }

    /** Object URL. Visible for URL tests. */
    internal fun objectUrl(key: String): HttpUrl {
        val builder = base.newBuilder()
        for (segment in fullKey(key).split('/')) {
            builder.addPathSegment(segment)
        }
        return builder.build()
    }

    private fun authorized(url: HttpUrl): Request.Builder =
        Request.Builder().url(url).header("Authorization", authorization)

    private fun propfindBody() = PROPFIND_BODY.toRequestBody(
        "application/xml; charset=utf-8".toMediaType(),
    )

    private fun parseListResponse(xml: String, requestedPrefix: String?): ListObjectsResult {
        val document = parseStorageXml(xml)
        val responses = document.getElementsByTagNameNS(DAV_NS, "response")
        val basePath = base.encodedPath.trimEnd('/')
        val objects = mutableListOf<StorageObject>()

        for (index in 0 until responses.length) {
            val response = responses.item(index) as? Element ?: continue
            val href = response.childTextNs(DAV_NS, "href") ?: continue
            val path = decodeHrefPath(href)
            if (path.endsWith("/")) {
                continue
            }
            if (path.trimEnd('/') == collectionUrl().encodedPath.trimEnd('/')) {
                continue
            }
            val resourceType = response.getElementsByTagNameNS(DAV_NS, "resourcetype")
            if (resourceType.length > 0) {
                val element = resourceType.item(0) as? Element
                if (element != null &&
                    element.getElementsByTagNameNS(DAV_NS, "collection").length > 0
                ) {
                    continue
                }
            }

            val relative = relativePath(path, basePath) ?: continue
            val logical = logicalKey(relative)
            if (requestedPrefix != null && !logical.startsWith(requestedPrefix)) {
                continue
            }
            objects += StorageObject(
                key = logical,
                bytes = ByteArray(0),
                etag = response.childTextNs(DAV_NS, "getetag"),
                lastModified = response.childTextNs(DAV_NS, "getlastmodified"),
            )
        }

        return ListObjectsResult(objects = objects, nextContinuationToken = null)
    }

    private fun decodeHrefPath(href: String): String {
        val rawPath = runCatching { URI(href).path }.getOrNull() ?: href
        return runCatching { URLDecoder.decode(rawPath, "UTF-8") }.getOrDefault(rawPath)
    }

    private fun relativePath(path: String, basePath: String): String? {
        if (basePath.isEmpty()) {
            return path.trimStart('/')
        }
        val normalizedBase = basePath.trimEnd('/')
        if (path == normalizedBase) {
            return ""
        }
        val prefixWithSlash = "$normalizedBase/"
        if (!path.startsWith(prefixWithSlash)) {
            return null
        }
        return path.removePrefix(prefixWithSlash)
    }

    private fun fullKey(key: String): String {
        val normalized = key.trimStart('/')
        if (normalized.isEmpty()) {
            throw StorageError(StorageErrorCode.SERVER, "Object key must not be empty")
        }
        return if (prefix.isEmpty()) normalized else "$prefix/$normalized"
    }

    private fun logicalKey(relative: String): String {
        if (prefix.isEmpty()) {
            return relative
        }
        val prefixWithSlash = "$prefix/"
        return if (relative.startsWith(prefixWithSlash)) {
            relative.removePrefix(prefixWithSlash)
        } else {
            relative
        }
    }

    private companion object {
        const val DAV_NS = "DAV:"
        val PROPFIND_BODY = """
            <?xml version="1.0" encoding="utf-8" ?>
            <d:propfind xmlns:d="DAV:">
              <d:prop>
                <d:displayname/>
                <d:getetag/>
                <d:getlastmodified/>
                <d:resourcetype/>
              </d:prop>
            </d:propfind>
        """.trimIndent()
    }
}
