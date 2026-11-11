package dev.chatui.core.backup.storage

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/** Configuration for an Azure Blob container. */
data class AzureBlobStorageConfig(
    val accountName: String,
    /** Shared account key. Mutually exclusive with [sasToken]. */
    val accountKey: String? = null,
    /** SAS token, with or without a leading `?`. Mutually exclusive with [accountKey]. */
    val sasToken: String? = null,
    val container: String,
    /** Optional endpoint override, for example an Azurite emulator URL. */
    val endpoint: String? = null,
    /** Optional key prefix, for example `chatui/backups`. */
    val prefix: String? = null,
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
 * Azure Blob Storage adapter using block blobs.
 *
 * Authentication is either a shared account key (SharedKey signature) or a SAS
 * token. Requests go directly from the client to Azure; there is no server
 * proxy. The adapter only accepts [ByteArray] bodies, so callers must encrypt
 * with the backup envelope codec before writing.
 */
class AzureBlobStorageAdapter(config: AzureBlobStorageConfig) : StorageAdapter {
    private val accountName: String = config.accountName
    private val accountKey: String? = config.accountKey?.takeIf { it.isNotBlank() }
    private val sasToken: String? = config.sasToken
        ?.trim()
        ?.removePrefix("?")
        ?.takeIf { it.isNotEmpty() }
    private val container: String = config.container
    private val endpoint: String = config.endpoint?.trimEnd('/')
        ?: "https://$accountName.blob.core.windows.net"
    private val prefix: String = (config.prefix ?: "").trim('/')
    private val client: OkHttpClient = config.client
    private val now: () -> Instant = config.now
    private val retryDelayMs: Long = config.retryDelayMs
    private val readAfterWriteAttempts: Int = config.readAfterWriteAttempts

    init {
        require(accountName.isNotBlank()) { "Azure account name must not be empty" }
        require(container.isNotBlank()) { "Azure container must not be empty" }
        require(accountKey != null || sasToken != null) {
            "Azure account key or SAS token is required"
        }
        require(retryDelayMs >= 0) { "retryDelayMs must not be negative" }
        require(readAfterWriteAttempts > 0) { "readAfterWriteAttempts must be positive" }
    }

    override suspend fun testConnection() {
        val url = withSas(
            containerUrl().newBuilder()
                .addQueryParameter("restype", "container")
                .build(),
        )
        withResponse("HEAD", url) { response ->
            response.ensureStorageOk("HEAD container")
        }
    }

    override suspend fun getObject(key: String): StorageObject =
        withResponse("GET", withSas(objectUrl(key))) { response ->
            response.ensureStorageOk("GET $key")
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

        val headers = mutableMapOf(
            "content-type" to options.contentType,
            "x-ms-blob-type" to "BlockBlob",
        )
        options.ifMatch?.let { headers["if-match"] = it }
        options.ifNoneMatch?.let { headers["if-none-match"] = it }

        val written = withResponse(
            method = "PUT",
            url = withSas(objectUrl(key)),
            headers = headers,
            payload = encryptedBytes,
        ) { response ->
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

    override suspend fun headObject(key: String): StorageObject =
        withResponse("HEAD", withSas(objectUrl(key))) { response ->
            response.ensureStorageOk("HEAD $key")
            StorageObject(
                key = key,
                bytes = ByteArray(0),
                etag = response.header("ETag"),
                lastModified = response.header("Last-Modified"),
            )
        }

    override suspend fun deleteObject(key: String) {
        withResponse("DELETE", withSas(objectUrl(key))) { response ->
            response.ensureStorageOk("DELETE $key")
        }
    }

    override suspend fun listObjects(options: ListObjectsOptions): ListObjectsResult {
        val builder = containerUrl().newBuilder()
            .addQueryParameter("restype", "container")
            .addQueryParameter("comp", "list")
        val fullPrefix = fullPrefix(options.prefix)
        if (fullPrefix.isNotEmpty()) {
            builder.addQueryParameter("prefix", fullPrefix)
        }
        options.continuationToken?.let { builder.addQueryParameter("marker", it) }
        options.maxKeys?.let { builder.addQueryParameter("maxresults", it.toString()) }

        return withResponse("GET", withSas(builder.build())) { response ->
            response.ensureStorageOk("LIST container")
            parseListResponse(response.body.string())
        }
    }

    /** Container URL. Visible for URL tests. */
    internal fun containerUrl(): HttpUrl =
        endpoint.toHttpUrl().newBuilder()
            .addPathSegment(container)
            .build()

    /** Object URL. Visible for URL tests. */
    internal fun objectUrl(key: String): HttpUrl {
        val builder = containerUrl().newBuilder()
        for (segment in fullKey(key).split('/')) {
            builder.addPathSegment(segment)
        }
        return builder.build()
    }

    private suspend fun <T> withResponse(
        method: String,
        url: HttpUrl,
        headers: Map<String, String> = emptyMap(),
        payload: ByteArray? = null,
        block: (Response) -> T,
    ): T = withContext(Dispatchers.IO) {
        val request = buildRequest(method, url, headers, payload)
        val response = try {
            client.newCall(request).execute()
        } catch (error: java.io.IOException) {
            throw StorageError(
                StorageErrorCode.NETWORK,
                "Network request failed for $method ${url.encodedPath}",
                cause = error,
            )
        }
        try {
            response.use(block)
        } catch (error: java.io.IOException) {
            throw StorageError(
                StorageErrorCode.NETWORK,
                "Network request failed for $method ${url.encodedPath}",
                cause = error,
            )
        }
    }

    private fun buildRequest(
        method: String,
        url: HttpUrl,
        headers: Map<String, String>,
        payload: ByteArray?,
    ): Request {
        val allHeaders = mutableMapOf<String, String>()
        allHeaders["x-ms-date"] = DateTimeFormatter.RFC_1123_DATE_TIME
            .withZone(ZoneOffset.UTC)
            .format(now())
        allHeaders["x-ms-version"] = AZURE_API_VERSION
        allHeaders.putAll(headers)

        val builder = Request.Builder().url(url)
        if (accountKey != null) {
            val signature = sign(method, url, allHeaders, payload)
            builder.header("Authorization", "SharedKey $accountName:$signature")
        }
        for ((name, value) in allHeaders) {
            builder.header(name, value)
        }
        builder.method(method, payload?.toRequestBody(null))
        return builder.build()
    }

    private fun sign(
        method: String,
        url: HttpUrl,
        headers: Map<String, String>,
        payload: ByteArray?,
    ): String {
        val key = accountKey ?: error("Shared key is not configured")
        val stringToSign = buildStringToSign(method, url, headers, payload)
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(Base64.getDecoder().decode(key), "HmacSHA256"))
        return Base64.getEncoder().encodeToString(
            mac.doFinal(stringToSign.toByteArray(Charsets.UTF_8)),
        )
    }

    private fun buildStringToSign(
        method: String,
        url: HttpUrl,
        headers: Map<String, String>,
        payload: ByteArray?,
    ): String {
        val contentLength = if (payload != null) payload.size.toString() else ""
        val canonicalHeaders = headers
            .filterKeys { it.lowercase().startsWith("x-ms-") }
            .toSortedMap()
            .entries
            .joinToString("") { "${it.key.lowercase()}:${it.value.trim()}\n" }

        val canonicalResource = buildString {
            append('/').append(accountName)
            append(url.encodedPath)
            url.queryParameterNames.sorted().forEach { name ->
                val values = url.queryParameterValues(name).joinToString(",")
                append('\n').append(name.lowercase()).append(':').append(values)
            }
        }

        return listOf(
            method,
            "", // Content-Encoding
            "", // Content-Language
            contentLength,
            "", // Content-MD5
            headers["content-type"].orEmpty(),
            "", // Date
            "", // If-Modified-Since
            headers["if-match"].orEmpty(),
            headers["if-none-match"].orEmpty(),
            "", // If-Unmodified-Since
            "", // Range
        ).joinToString("\n") + "\n" + canonicalHeaders + canonicalResource
    }

    private fun withSas(url: HttpUrl): HttpUrl {
        val sas = sasToken ?: return url
        val query = listOfNotNull(url.encodedQuery, sas).joinToString("&")
        return url.newBuilder().encodedQuery(query).build()
    }

    private fun parseListResponse(xml: String): ListObjectsResult {
        val document = parseStorageXml(xml)
        val blobs = document.getElementsByTagName("Blob")
        val objects = (0 until blobs.length).mapNotNull { index ->
            val node = blobs.item(index) as? org.w3c.dom.Element ?: return@mapNotNull null
            val actualKey = node.childText("Name") ?: return@mapNotNull null
            StorageObject(
                key = logicalKey(actualKey),
                bytes = ByteArray(0),
                etag = node.childText("Etag"),
                lastModified = node.childText("Last-Modified"),
            )
        }
        return ListObjectsResult(
            objects = objects,
            nextContinuationToken = document.documentElement.childText("NextMarker"),
        )
    }

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

    private companion object {
        const val AZURE_API_VERSION = "2021-12-02"
    }
}
