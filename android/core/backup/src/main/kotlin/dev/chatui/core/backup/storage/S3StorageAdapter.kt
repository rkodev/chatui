package dev.chatui.core.backup.storage

import java.io.IOException
import java.time.Instant
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.w3c.dom.Element

/** Configuration for an S3-compatible bucket. */
data class S3StorageConfig(
    /** Service endpoint, for example `https://s3.us-east-1.amazonaws.com`. */
    val endpoint: String,
    val bucket: String,
    val region: String,
    val accessKeyId: String,
    val secretAccessKey: String,
    val sessionToken: String? = null,
    /** Optional key prefix, for example `chatui/backups`. */
    val prefix: String? = null,
    /**
     * Use `endpoint/bucket/key` instead of `bucket.endpoint/key`.
     * Defaults to path-style for non-AWS endpoints.
     */
    val forcePathStyle: Boolean? = null,
    /** Injectable client for tests. */
    val client: OkHttpClient = OkHttpClient(),
    /** Injectable clock for tests. */
    val now: () -> Instant = Instant::now,
    /** Delay between read-after-write retries. Defaults to 100 ms. */
    val retryDelayMs: Long = 100,
    /** Number of HEAD attempts after a write that omits an ETag. */
    val readAfterWriteAttempts: Int = 3,
)

/**
 * S3-compatible storage adapter.
 *
 * Supports AWS S3, Contabo Object Storage, Cloudflare R2, MinIO, and Backblaze
 * B2 through a custom endpoint. Requests are signed with SigV4 and sent
 * directly from the client; there is no server proxy.
 *
 * The adapter only accepts [ByteArray] bodies. Callers must encrypt with the
 * backup envelope codec before writing.
 */
class S3StorageAdapter(config: S3StorageConfig) : StorageAdapter {
    private val endpoint: HttpUrl = config.endpoint.toHttpUrl()
    private val bucket: String = config.bucket
    private val region: String = config.region
    private val credentials = SigV4Credentials(
        accessKeyId = config.accessKeyId,
        secretAccessKey = config.secretAccessKey,
        sessionToken = config.sessionToken,
    )
    private val prefix: String = (config.prefix ?: "").trim('/')
    private val forcePathStyle: Boolean =
        config.forcePathStyle ?: !isAwsEndpoint(endpoint.host)
    private val client: OkHttpClient = config.client
    private val now: () -> Instant = config.now
    private val retryDelayMs: Long = config.retryDelayMs
    private val readAfterWriteAttempts: Int = config.readAfterWriteAttempts

    init {
        require(bucket.isNotBlank()) { "S3 bucket must not be empty" }
        require(region.isNotBlank()) { "S3 region must not be empty" }
        require(config.accessKeyId.isNotEmpty() && config.secretAccessKey.isNotEmpty()) {
            "S3 access key id and secret access key are required"
        }
        require(retryDelayMs >= 0) { "retryDelayMs must not be negative" }
        require(readAfterWriteAttempts > 0) { "readAfterWriteAttempts must be positive" }
    }

    override suspend fun testConnection() {
        withResponse(SigV4Method.HEAD, bucketUrl()) { response ->
            ensureOk(response, "HEAD bucket")
        }
    }

    override suspend fun getObject(key: String): StorageObject =
        withResponse(SigV4Method.GET, objectUrl(key)) { response ->
            ensureOk(response, "GET $key")
            StorageObject(
                key = key,
                bytes = response.body.bytes(),
                etag = response.header("ETag"),
                lastModified = response.header("Last-Modified"),
            )
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

        val headers = mutableMapOf("content-type" to options.contentType)
        options.ifMatch?.let { headers["if-match"] = it }
        options.ifNoneMatch?.let { headers["if-none-match"] = it }

        val written = withResponse(
            method = SigV4Method.PUT,
            url = objectUrl(key),
            headers = headers,
            payload = encryptedBytes,
        ) { response ->
            ensureOk(response, "PUT $key")
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

        // Some providers omit ETag on PUT. Re-read conservatively so callers can
        // use the value for the next conditional manifest rewrite.
        return try {
            val head = headAfterWrite(key)
            written.copy(
                etag = head.etag,
                lastModified = head.lastModified ?: written.lastModified,
            )
        } catch (error: StorageError) {
            if (error.code == StorageErrorCode.NOT_FOUND) written else throw error
        }
    }

    override suspend fun headObject(key: String): StorageObject =
        withResponse(SigV4Method.HEAD, objectUrl(key)) { response ->
            ensureOk(response, "HEAD $key")
            StorageObject(
                key = key,
                bytes = ByteArray(0),
                etag = response.header("ETag"),
                lastModified = response.header("Last-Modified"),
            )
        }

    override suspend fun deleteObject(key: String) {
        withResponse(SigV4Method.DELETE, objectUrl(key)) { response ->
            ensureOk(response, "DELETE $key")
        }
    }

    override suspend fun listObjects(options: ListObjectsOptions): ListObjectsResult {
        val builder = bucketUrl().newBuilder().addQueryParameter("list-type", "2")
        val fullPrefix = fullPrefix(options.prefix)
        if (fullPrefix.isNotEmpty()) {
            builder.addQueryParameter("prefix", fullPrefix)
        }
        options.continuationToken?.let {
            builder.addQueryParameter("continuation-token", it)
        }
        options.maxKeys?.let {
            builder.addQueryParameter("max-keys", it.toString())
        }

        return withResponse(SigV4Method.GET, builder.build()) { response ->
            ensureOk(response, "LIST bucket")
            parseListResponse(response.body.string())
        }
    }

    /** Path-style or virtual-hosted bucket URL. Visible for URL tests. */
    internal fun bucketUrl(): HttpUrl {
        val builder = endpoint.newBuilder().query(null).fragment(null)
        if (forcePathStyle) {
            builder.encodedPath(bucketPath())
        } else {
            builder.host("$bucket.${endpoint.host}")
            builder.encodedPath(basePathOrRoot())
        }
        return builder.build()
    }

    /** Path-style or virtual-hosted object URL. Visible for URL tests. */
    internal fun objectUrl(key: String): HttpUrl {
        val encodedKey = encodeKeyPath(fullKey(key))
        val builder = endpoint.newBuilder().query(null).fragment(null)
        if (forcePathStyle) {
            builder.encodedPath("${bucketPath()}/$encodedKey")
        } else {
            builder.host("$bucket.${endpoint.host}")
            val base = basePath()
            builder.encodedPath(if (base.isEmpty()) "/$encodedKey" else "$base/$encodedKey")
        }
        return builder.build()
    }

    private suspend fun <T> withResponse(
        method: SigV4Method,
        url: HttpUrl,
        headers: Map<String, String> = emptyMap(),
        payload: ByteArray? = null,
        block: (Response) -> T,
    ): T = withContext(Dispatchers.IO) {
        val signed = SigV4Signer.sign(
            SigV4SignOptions(
                method = method,
                url = url,
                region = region,
                credentials = credentials,
                headers = headers,
                payload = payload,
                date = now(),
            ),
        )

        val requestBuilder = Request.Builder().url(signed.url)
        for ((name, value) in signed.headers) {
            // OkHttp sets Host from the URL. Signing still includes it.
            if (name == "host") {
                continue
            }
            requestBuilder.header(name, value)
        }
        requestBuilder.method(method.name, payload?.toRequestBody(null))

        val response = try {
            client.newCall(requestBuilder.build()).execute()
        } catch (error: IOException) {
            throw networkError(method, url, error)
        }

        try {
            response.use(block)
        } catch (error: IOException) {
            throw networkError(method, url, error)
        }
    }

    private fun ensureOk(response: Response, action: String) {
        if (response.isSuccessful) {
            return
        }
        val detail = runCatching { response.body.string().trim().take(500) }
            .getOrDefault("")
        val suffix = if (detail.isEmpty()) "" else ": $detail"
        throw StorageError(
            code = statusToCode(response.code),
            message = "$action failed with HTTP ${response.code}$suffix",
            status = response.code,
        )
    }

    private suspend fun headAfterWrite(key: String): StorageObject {
        var lastError: StorageError? = null
        for (attempt in 0 until readAfterWriteAttempts) {
            try {
                return headObject(key)
            } catch (error: StorageError) {
                lastError = error
                if (error.code != StorageErrorCode.NOT_FOUND) {
                    throw error
                }
                if (attempt < readAfterWriteAttempts - 1 && retryDelayMs > 0) {
                    delay(retryDelayMs * (attempt + 1))
                }
            }
        }
        throw lastError ?: StorageError(
            StorageErrorCode.NOT_FOUND,
            "HEAD $key failed after write",
        )
    }

    private fun parseListResponse(xml: String): ListObjectsResult {
        val document = try {
            val factory = DocumentBuilderFactory.newInstance()
            configureSecureXml(factory)
            factory.isNamespaceAware = false
            factory.newDocumentBuilder().parse(xml.byteInputStream(Charsets.UTF_8))
        } catch (error: Exception) {
            throw StorageError(
                StorageErrorCode.SERVER,
                "List response is not valid XML",
                cause = error,
            )
        }

        val contents = document.getElementsByTagName("Contents")
        val objects = (0 until contents.length).map { index ->
            val node = contents.item(index) as Element
            val actualKey = childText(node, "Key")
                ?: throw StorageError(
                    StorageErrorCode.SERVER,
                    "List response entry is missing Key",
                )
            StorageObject(
                key = logicalKey(actualKey),
                bytes = ByteArray(0),
                etag = childText(node, "ETag"),
                lastModified = childText(node, "LastModified"),
            )
        }

        return ListObjectsResult(
            objects = objects,
            nextContinuationToken = childText(document.documentElement, "NextContinuationToken"),
        )
    }

    private fun configureSecureXml(factory: DocumentBuilderFactory) {
        // Android's Expat parser does not implement every JAXP feature. Apply
        // each hardening option best-effort so listing still works on Android
        // while JVM tests reject DTDs and external entities.
        runCatching { factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true) }
        runCatching {
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }
        runCatching {
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
        }
        runCatching {
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        }
        runCatching {
            factory.setAttribute("http://javax.xml.XMLConstants/property/accessExternalDTD", "")
        }
        runCatching {
            factory.setAttribute(
                "http://javax.xml.XMLConstants/property/accessExternalSchema",
                "",
            )
        }
        factory.isExpandEntityReferences = false
        factory.isXIncludeAware = false
    }

    private fun childText(parent: Element, tag: String): String? {
        val nodes = parent.getElementsByTagName(tag)
        if (nodes.length == 0) {
            return null
        }
        return nodes.item(0).textContent?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun statusToCode(status: Int): StorageErrorCode = when (status) {
        401 -> StorageErrorCode.AUTH
        403 -> StorageErrorCode.PERMISSION
        404 -> StorageErrorCode.NOT_FOUND
        409, 412 -> StorageErrorCode.CONFLICT
        else -> StorageErrorCode.SERVER
    }

    private fun networkError(
        method: SigV4Method,
        url: HttpUrl,
        cause: IOException,
    ): StorageError = StorageError(
        code = StorageErrorCode.NETWORK,
        message = "Network request failed for ${method.name} ${url.encodedPath}",
        cause = cause,
    )

    private fun bucketPath(): String {
        val base = basePath()
        return if (base.isEmpty()) "/$bucket" else "$base/$bucket"
    }

    private fun basePath(): String {
        val path = endpoint.encodedPath.trimEnd('/')
        return if (path == "/") "" else path
    }

    private fun basePathOrRoot(): String {
        val base = basePath()
        return if (base.isEmpty()) "/" else base
    }

    private fun encodeKeyPath(key: String): String =
        key.split('/').joinToString(separator = "/") { SigV4Signer.encodeRfc3986(it) }

    private fun fullKey(key: String): String {
        val normalized = key.trimStart('/')
        if (normalized.isEmpty()) {
            throw StorageError(StorageErrorCode.SERVER, "Object key must not be empty")
        }
        return if (prefix.isEmpty()) normalized else "$prefix/$normalized"
    }

    private fun fullPrefix(prefix: String?): String {
        val extra = (prefix ?: "").trimStart('/')
        if (this.prefix.isEmpty()) {
            return extra
        }
        return if (extra.isEmpty()) "${this.prefix}/" else "${this.prefix}/$extra"
    }

    private fun logicalKey(actualKey: String): String {
        if (prefix.isEmpty()) {
            return actualKey
        }
        val prefixWithSlash = "$prefix/"
        return if (actualKey.startsWith(prefixWithSlash)) {
            actualKey.removePrefix(prefixWithSlash)
        } else {
            actualKey
        }
    }
}

private fun isAwsEndpoint(hostname: String): Boolean =
    hostname == "s3.amazonaws.com" ||
        Regex("^s3[.-][a-z0-9-]+\\.amazonaws\\.com$").matches(hostname) ||
        hostname.endsWith(".amazonaws.com")
