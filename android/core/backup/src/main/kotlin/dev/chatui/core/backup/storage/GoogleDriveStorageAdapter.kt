package dev.chatui.core.backup.storage

import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink

/** Configuration for a Google Drive folder. */
data class GoogleDriveStorageConfig(
    /** OAuth2 access token with Drive file scope. */
    val accessToken: String,
    /** `appDataFolder` or a user-selected folder id. */
    val folderId: String = "appDataFolder",
    /** Optional key prefix, for example `chatui/backups`. */
    val prefix: String? = null,
    /** Drive v3 API endpoint. */
    val endpoint: String = "https://www.googleapis.com/drive/v3",
    /** Drive v3 upload endpoint. */
    val uploadEndpoint: String = "https://www.googleapis.com/upload/drive/v3",
    /** Injectable client for tests. */
    val client: OkHttpClient = OkHttpClient(),
    /** Delay between read-after-write retries. Defaults to 100 ms. */
    val retryDelayMs: Long = 100,
    /** Number of metadata attempts after a write. */
    val readAfterWriteAttempts: Int = 3,
)

/**
 * Google Drive adapter using the Drive v3 REST API.
 *
 * Drive has no ETag/`If-Match` precondition. The adapter therefore uses the
 * file `md5Checksum` as the logical ETag, checks it before a conditional write,
 * and re-reads metadata after every write. A missing or changed checksum is
 * reported as a typed conflict so the sync engine can retry conservatively.
 *
 * The adapter only accepts [ByteArray] bodies, so callers must encrypt with the
 * backup envelope codec before writing.
 */
class GoogleDriveStorageAdapter(config: GoogleDriveStorageConfig) : StorageAdapter {
    private val json = Json { ignoreUnknownKeys = true }
    private val accessToken: String = config.accessToken
    private val folderId: String = config.folderId.ifBlank { "appDataFolder" }
    private val prefix: String = (config.prefix ?: "").trim('/')
    private val endpoint: String = config.endpoint.trimEnd('/')
    private val uploadEndpoint: String = config.uploadEndpoint.trimEnd('/')
    private val client: OkHttpClient = config.client
    private val retryDelayMs: Long = config.retryDelayMs
    private val readAfterWriteAttempts: Int = config.readAfterWriteAttempts

    init {
        require(accessToken.isNotBlank()) { "Google Drive access token must not be empty" }
        require(retryDelayMs >= 0) { "retryDelayMs must not be negative" }
        require(readAfterWriteAttempts > 0) { "readAfterWriteAttempts must be positive" }
    }

    override suspend fun testConnection() {
        val url = "$endpoint/files".toHttpUrl().newBuilder()
            .addQueryParameter("pageSize", "1")
            .addQueryParameter("fields", "files(id)")
            .build()
        val request = authorized(url).get().build()
        client.executeStorage(request).use { response ->
            response.ensureStorageOk("LIST files")
        }
    }

    override suspend fun getObject(key: String): StorageObject {
        val file = findFile(fullKey(key))
            ?: throw StorageError(StorageErrorCode.NOT_FOUND, "GET $key failed: object not found")
        val url = "$endpoint/files/${file.id}".toHttpUrl().newBuilder()
            .addQueryParameter("alt", "media")
            .build()
        val request = authorized(url).get().build()
        return client.executeStorage(request).use { response ->
            response.ensureStorageOk("GET $key")
            StorageObject(
                key = key,
                bytes = response.body.bytes(),
                etag = file.etag,
                lastModified = file.modifiedTime,
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

        val name = fullKey(key)
        val existing = findFile(name)

        options.ifMatch?.let { expected ->
            if (existing == null || existing.etag != expected) {
                throw StorageError(
                    StorageErrorCode.CONFLICT,
                    "PUT $key failed: remote object changed",
                )
            }
        }
        options.ifNoneMatch?.let { expected ->
            if (existing != null && (expected == "*" || existing.etag == expected)) {
                throw StorageError(
                    StorageErrorCode.CONFLICT,
                    "PUT $key failed: remote object already exists",
                )
            }
        }

        val written = if (existing != null) {
            updateFile(key, existing.id, encryptedBytes, options.contentType)
        } else {
            createFile(key, name, encryptedBytes, options.contentType)
        }

        return try {
            val head = readAfterWrite(readAfterWriteAttempts, retryDelayMs) {
                headObject(key)
            }
            written.copy(
                etag = head.etag ?: written.etag,
                lastModified = head.lastModified ?: written.lastModified,
            )
        } catch (error: StorageError) {
            if (error.code == StorageErrorCode.NOT_FOUND) written else throw error
        }
    }

    override suspend fun headObject(key: String): StorageObject {
        val file = findFile(fullKey(key))
            ?: throw StorageError(StorageErrorCode.NOT_FOUND, "HEAD $key failed: object not found")
        return StorageObject(
            key = key,
            bytes = ByteArray(0),
            etag = file.etag,
            lastModified = file.modifiedTime,
        )
    }

    override suspend fun deleteObject(key: String) {
        val file = findFile(fullKey(key)) ?: return
        val request = authorized("$endpoint/files/${file.id}".toHttpUrl()).delete().build()
        client.executeStorage(request).use { response ->
            if (response.code == 404) {
                return
            }
            response.ensureStorageOk("DELETE $key")
        }
    }

    override suspend fun listObjects(options: ListObjectsOptions): ListObjectsResult {
        val objects = mutableListOf<StorageObject>()
        var pageToken = options.continuationToken
        while (true) {
            val (files, next) = listFiles(pageToken)
            for (file in files) {
                val logical = logicalKey(file.name)
                if (options.prefix != null && !logical.startsWith(options.prefix)) {
                    continue
                }
                objects += StorageObject(
                    key = logical,
                    bytes = ByteArray(0),
                    etag = file.etag,
                    lastModified = file.modifiedTime,
                )
                if (options.maxKeys != null && objects.size >= options.maxKeys) {
                    return ListObjectsResult(objects, next)
                }
            }
            if (next == null) {
                break
            }
            pageToken = next
        }
        return ListObjectsResult(objects, null)
    }

    private suspend fun findFile(name: String): DriveFile? {
        val query = "name = '${escapeQuery(name)}' and " +
            "'${escapeQuery(folderId)}' in parents and trashed = false"
        val url = "$endpoint/files".toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("fields", "files(id,name,md5Checksum,modifiedTime)")
            .addQueryParameter("pageSize", "1")
            .build()
        val request = authorized(url).get().build()
        return client.executeStorage(request).use { response ->
            response.ensureStorageOk("FIND $name")
            val body = json.parseToJsonElement(response.body.string()).jsonObject
            val files = body["files"]?.jsonArray ?: emptyList()
            files.firstOrNull()?.let { parseFile(it.jsonObject) }
        }
    }

    private suspend fun listFiles(pageToken: String?): Pair<List<DriveFile>, String?> {
        val builder = "$endpoint/files".toHttpUrl().newBuilder()
            .addQueryParameter(
                "q",
                "'${escapeQuery(folderId)}' in parents and trashed = false",
            )
            .addQueryParameter(
                "fields",
                "nextPageToken,files(id,name,md5Checksum,modifiedTime)",
            )
            .addQueryParameter("pageSize", "1000")
        pageToken?.let { builder.addQueryParameter("pageToken", it) }
        val request = authorized(builder.build()).get().build()
        return client.executeStorage(request).use { response ->
            response.ensureStorageOk("LIST files")
            val body = json.parseToJsonElement(response.body.string()).jsonObject
            val files = body["files"]?.jsonArray ?: emptyList()
            val parsed = files.mapNotNull { parseFile(it.jsonObject) }
            parsed to body.string("nextPageToken")
        }
    }

    private suspend fun createFile(
        key: String,
        name: String,
        encryptedBytes: ByteArray,
        contentType: String,
    ): StorageObject {
        val boundary = "chatui-${UUID.randomUUID()}"
        val metadata = buildJsonObject {
            put("name", name)
            put("parents", buildJsonArray { add(JsonPrimitive(folderId)) })
        }.toString()
        val body = MultipartRelatedBody(metadata, encryptedBytes, boundary, contentType)
        val url = "$uploadEndpoint/files".toHttpUrl().newBuilder()
            .addQueryParameter("uploadType", "multipart")
            .addQueryParameter("fields", "id,name,md5Checksum,modifiedTime")
            .build()
        val request = authorized(url).post(body).build()
        return client.executeStorage(request).use { response ->
            response.ensureStorageOk("PUT $name")
            val file = parseFile(response.body.string())
                ?: throw StorageError(StorageErrorCode.SERVER, "Drive create response is invalid")
            StorageObject(
                key = key,
                bytes = encryptedBytes,
                etag = file.etag,
                lastModified = file.modifiedTime,
            )
        }
    }

    private suspend fun updateFile(
        key: String,
        fileId: String,
        encryptedBytes: ByteArray,
        contentType: String,
    ): StorageObject {
        val url = "$uploadEndpoint/files/$fileId".toHttpUrl().newBuilder()
            .addQueryParameter("uploadType", "media")
            .addQueryParameter("fields", "id,name,md5Checksum,modifiedTime")
            .build()
        val request = authorized(url)
            .patch(encryptedBytes.toRequestBody(contentType.toMediaType()))
            .build()
        return client.executeStorage(request).use { response ->
            response.ensureStorageOk("PUT $fileId")
            val file = parseFile(response.body.string())
                ?: throw StorageError(StorageErrorCode.SERVER, "Drive update response is invalid")
            StorageObject(
                key = key,
                bytes = encryptedBytes,
                etag = file.etag,
                lastModified = file.modifiedTime,
            )
        }
    }

    private fun parseFile(body: JsonObject): DriveFile? {
        val id = body.string("id") ?: return null
        val name = body.string("name") ?: return null
        return DriveFile(
            id = id,
            name = name,
            md5 = body.string("md5Checksum"),
            modifiedTime = body.string("modifiedTime"),
        )
    }

    private fun parseFile(jsonText: String): DriveFile? =
        parseFile(json.parseToJsonElement(jsonText).jsonObject)

    private fun authorized(url: HttpUrl): Request.Builder =
        Request.Builder().url(url).header("Authorization", "Bearer $accessToken")

    private fun fullKey(key: String): String {
        val normalized = key.trimStart('/')
        if (normalized.isEmpty()) {
            throw StorageError(StorageErrorCode.SERVER, "Object key must not be empty")
        }
        return if (prefix.isEmpty()) normalized else "$prefix/$normalized"
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

    private fun escapeQuery(value: String): String =
        value.replace("\\", "\\\\").replace("'", "\\'")

    private data class DriveFile(
        val id: String,
        val name: String,
        val md5: String?,
        val modifiedTime: String?,
    ) {
        val etag: String? get() = md5 ?: modifiedTime
    }
}

private class MultipartRelatedBody(
    private val metadataJson: String,
    private val bytes: ByteArray,
    private val boundary: String,
    private val contentType: String,
) : RequestBody() {
    override fun contentType(): MediaType =
        "multipart/related; boundary=$boundary".toMediaType()

    override fun writeTo(sink: BufferedSink) {
        sink.writeUtf8("--$boundary\r\n")
        sink.writeUtf8("Content-Type: application/json; charset=UTF-8\r\n\r\n")
        sink.writeUtf8(metadataJson)
        sink.writeUtf8("\r\n--$boundary\r\n")
        sink.writeUtf8("Content-Type: $contentType\r\n\r\n")
        sink.write(bytes)
        sink.writeUtf8("\r\n--$boundary--\r\n")
    }
}

private fun JsonObject.string(name: String): String? =
    (this[name] as? JsonPrimitive)?.contentOrNull
