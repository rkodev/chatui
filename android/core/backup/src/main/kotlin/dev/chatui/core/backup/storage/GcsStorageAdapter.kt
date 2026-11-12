package dev.chatui.core.backup.storage

import java.security.KeyFactory
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Instant
import java.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Configuration for a Google Cloud Storage bucket. */
data class GcsStorageConfig(
    /** Service account JSON containing `client_email`, `private_key`, and `token_uri`. */
    val serviceAccountJson: String,
    val bucket: String,
    /** Optional key prefix, for example `chatui/backups`. */
    val prefix: String? = null,
    /** Storage JSON API endpoint. */
    val endpoint: String = "https://storage.googleapis.com",
    /** Optional OAuth token endpoint override for tests. */
    val tokenEndpoint: String? = null,
    /** Injectable client for tests. */
    val client: OkHttpClient = OkHttpClient(),
    /** Injectable clock for tests. */
    val now: () -> Instant = Instant::now,
    /** Delay between read-after-write retries. Defaults to 100 ms. */
    val retryDelayMs: Long = 100,
    /** Number of metadata attempts after a write that omits a generation. */
    val readAfterWriteAttempts: Int = 3,
)

/**
 * Google Cloud Storage adapter using the JSON API.
 *
 * Authentication uses a service account JSON key: the adapter signs a JWT with
 * the service account private key, exchanges it for an OAuth2 access token, and
 * caches the token until shortly before expiry. Conditional writes use GCS
 * object generations (`ifGenerationMatch` / `ifGenerationNotMatch`).
 *
 * The adapter only accepts [ByteArray] bodies, so callers must encrypt with the
 * backup envelope codec before writing.
 */
class GcsStorageAdapter(config: GcsStorageConfig) : StorageAdapter {
    private val json = Json { ignoreUnknownKeys = true }
    private val serviceAccount: GcsServiceAccount = json.decodeFromString(
        GcsServiceAccount.serializer(),
        config.serviceAccountJson,
    )
    private val bucket: String = config.bucket
    private val prefix: String = (config.prefix ?: "").trim('/')
    private val endpoint: String = config.endpoint.trimEnd('/')
    private val tokenUri: String = config.tokenEndpoint ?: serviceAccount.token_uri
    private val client: OkHttpClient = config.client
    private val now: () -> Instant = config.now
    private val retryDelayMs: Long = config.retryDelayMs
    private val readAfterWriteAttempts: Int = config.readAfterWriteAttempts
    private val privateKey: PrivateKey = parsePrivateKey(serviceAccount.private_key)

    private var cachedToken: String? = null
    private var cachedTokenExpiry: Instant? = null

    init {
        require(bucket.isNotBlank()) { "GCS bucket must not be empty" }
        require(serviceAccount.client_email.isNotBlank()) {
            "GCS service account client_email must not be empty"
        }
        require(retryDelayMs >= 0) { "retryDelayMs must not be negative" }
        require(readAfterWriteAttempts > 0) { "readAfterWriteAttempts must be positive" }
    }

    override suspend fun testConnection() {
        val request = authorized(bucketUrl()).get().build()
        client.executeStorage(request).use { response ->
            response.ensureStorageOk("GET bucket")
        }
    }

    override suspend fun getObject(key: String): StorageObject {
        val request = authorized(mediaUrl(key)).get().build()
        return client.executeStorage(request).use { response ->
            response.ensureStorageOk("GET $key")
            StorageObject(
                key = key,
                bytes = response.body.bytes(),
                etag = response.header("x-goog-generation") ?: response.header("ETag"),
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

        val request = authorized(uploadUrl(key, options))
            .post(encryptedBytes.toRequestBody(null))
            .header("Content-Type", options.contentType)
            .build()

        val written = client.executeStorage(request).use { response ->
            response.ensureStorageOk("PUT $key")
            parseMetadata(key, response.body.string(), encryptedBytes)
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
        val request = authorized(metadataUrl(key)).get().build()
        return client.executeStorage(request).use { response ->
            response.ensureStorageOk("HEAD $key")
            parseMetadata(key, response.body.string())
        }
    }

    override suspend fun deleteObject(key: String) {
        val request = authorized(metadataUrl(key)).delete().build()
        client.executeStorage(request).use { response ->
            response.ensureStorageOk("DELETE $key")
        }
    }

    override suspend fun listObjects(options: ListObjectsOptions): ListObjectsResult {
        val request = authorized(listUrl(options)).get().build()
        return client.executeStorage(request).use { response ->
            response.ensureStorageOk("LIST bucket")
            parseListResponse(response.body.string())
        }
    }

    /** Bucket metadata URL. Visible for URL tests. */
    internal fun bucketUrl(): HttpUrl = "$endpoint/storage/v1/b/$bucket".toHttpUrl()

    /** Object metadata URL. Visible for URL tests. */
    internal fun metadataUrl(key: String): HttpUrl =
        "$endpoint/storage/v1/b/$bucket/o/${encodeStoragePathSegment(fullKey(key))}".toHttpUrl()

    /** Object media URL. Visible for URL tests. */
    internal fun mediaUrl(key: String): HttpUrl =
        metadataUrl(key).newBuilder().addQueryParameter("alt", "media").build()

    private fun uploadUrl(key: String, options: PutObjectOptions): HttpUrl {
        val builder = "$endpoint/upload/storage/v1/b/$bucket/o".toHttpUrl().newBuilder()
            .addQueryParameter("uploadType", "media")
            .addQueryParameter("name", fullKey(key))
        options.ifMatch?.let { builder.addQueryParameter("ifGenerationMatch", it) }
        options.ifNoneMatch?.let {
            if (it == "*") {
                builder.addQueryParameter("ifGenerationMatch", "0")
            } else {
                builder.addQueryParameter("ifGenerationNotMatch", it)
            }
        }
        return builder.build()
    }

    private fun listUrl(options: ListObjectsOptions): HttpUrl {
        val builder = "$endpoint/storage/v1/b/$bucket/o".toHttpUrl().newBuilder()
        val fullPrefix = fullPrefix(options.prefix)
        if (fullPrefix.isNotEmpty()) {
            builder.addQueryParameter("prefix", fullPrefix)
        }
        options.continuationToken?.let { builder.addQueryParameter("pageToken", it) }
        options.maxKeys?.let { builder.addQueryParameter("maxResults", it.toString()) }
        return builder.build()
    }

    private suspend fun authorized(url: HttpUrl): Request.Builder =
        Request.Builder().url(url).header("Authorization", "Bearer ${accessToken()}")

    private suspend fun accessToken(): String {
        val token = cachedToken
        val expiry = cachedTokenExpiry
        if (token != null && expiry != null && now().isBefore(expiry.minusSeconds(60))) {
            return token
        }

        val form = FormBody.Builder()
            .add("grant_type", "urn:ietf:params:oauth:grant-type:jwt-bearer")
            .add("assertion", buildJwt())
            .build()
        val request = Request.Builder().url(tokenUri).post(form).build()

        return client.executeStorage(request).use { response ->
            response.ensureStorageOk("OAuth token exchange")
            val body = json.parseToJsonElement(response.body.string()).jsonObject
            val access = body.string("access_token")
                ?: throw StorageError(
                    StorageErrorCode.AUTH,
                    "OAuth token response is missing access_token",
                )
            val expiresIn = body.string("expires_in")?.toLongOrNull() ?: 3600
            cachedToken = access
            cachedTokenExpiry = now().plusSeconds(expiresIn)
            access
        }
    }

    private fun buildJwt(): String {
        val issuedAt = now().epochSecond
        val header = """{"alg":"RS256","typ":"JWT"}"""
        val claims = buildJsonObject {
            put("iss", serviceAccount.client_email)
            put("scope", GCS_SCOPE)
            put("aud", tokenUri)
            put("iat", issuedAt)
            put("exp", issuedAt + 3600)
        }.toString()
        val signingInput = "${base64Url(header.toByteArray(Charsets.UTF_8))}." +
            base64Url(claims.toByteArray(Charsets.UTF_8))
        val signature = Signature.getInstance("SHA256withRSA")
        signature.initSign(privateKey)
        signature.update(signingInput.toByteArray(Charsets.UTF_8))
        return "$signingInput.${base64Url(signature.sign())}"
    }

    private fun parseMetadata(
        key: String,
        jsonText: String,
        bytes: ByteArray = ByteArray(0),
    ): StorageObject {
        val body = json.parseToJsonElement(jsonText).jsonObject
        return StorageObject(
            key = key,
            bytes = bytes,
            etag = body.string("generation") ?: body.string("etag"),
            lastModified = body.string("updated"),
        )
    }

    private fun parseListResponse(jsonText: String): ListObjectsResult {
        val body = json.parseToJsonElement(jsonText).jsonObject
        val items = body["items"]?.jsonArray ?: emptyList()
        val objects = items.mapNotNull { item ->
            val entry = item.jsonObject
            val name = entry.string("name") ?: return@mapNotNull null
            StorageObject(
                key = logicalKey(name),
                bytes = ByteArray(0),
                etag = entry.string("generation") ?: entry.string("etag"),
                lastModified = entry.string("updated"),
            )
        }
        return ListObjectsResult(
            objects = objects,
            nextContinuationToken = body.string("nextPageToken"),
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

    private fun parsePrivateKey(pem: String): PrivateKey {
        val normalized = pem
            .replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replace("\\n", "\n")
            .replace(Regex("\\s"), "")
        val der = Base64.getDecoder().decode(normalized)
        return KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(der))
    }

    private fun base64Url(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private companion object {
        const val GCS_SCOPE = "https://www.googleapis.com/auth/devstorage.read_write"
    }
}

@Serializable
private data class GcsServiceAccount(
    val client_email: String,
    val private_key: String,
    val token_uri: String = "https://oauth2.googleapis.com/token",
)

private fun JsonObject.string(name: String): String? =
    (this[name] as? JsonPrimitive)?.contentOrNull
