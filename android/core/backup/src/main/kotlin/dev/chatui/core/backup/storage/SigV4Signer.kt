package dev.chatui.core.backup.storage

import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import okhttp3.HttpUrl

/** Static credentials for SigV4. Never log or persist these values. */
data class SigV4Credentials(
    val accessKeyId: String,
    val secretAccessKey: String,
    val sessionToken: String? = null,
)

/** Methods the backup adapter signs. */
enum class SigV4Method {
    GET,
    PUT,
    HEAD,
    DELETE,
}

/** Inputs for one signed request. */
data class SigV4SignOptions(
    val method: SigV4Method,
    val url: HttpUrl,
    val region: String,
    val credentials: SigV4Credentials,
    /** Extra request headers. Header names are matched case-insensitively. */
    val headers: Map<String, String> = emptyMap(),
    /** Request body. Omit for GET, HEAD, and DELETE. */
    val payload: ByteArray? = null,
    val service: String = SigV4Signer.SERVICE,
    /** Fixed clock for tests. Defaults to the current time. */
    val date: Instant = Instant.now(),
)

/** A signed request ready for OkHttp. */
data class SignedRequest(
    val method: SigV4Method,
    val url: HttpUrl,
    /** Lowercase header names plus `authorization`. */
    val headers: Map<String, String>,
)

/** Canonical request and its signed-header list. */
data class CanonicalRequest(
    val canonicalRequest: String,
    val signedHeaders: String,
)

/**
 * Minimal AWS Signature Version 4 signer for S3-compatible object storage.
 *
 * This object deliberately has no cloud SDK dependency. It implements the
 * canonical request, string to sign, signing key, and `Authorization` header
 * with `javax.crypto.Mac` and `MessageDigest` so the Android client can talk
 * directly to a bucket.
 */
object SigV4Signer {
    const val ALGORITHM: String = "AWS4-HMAC-SHA256"
    const val SERVICE: String = "s3"
    const val TERMINATOR: String = "aws4_request"

    private val AMZ_DATE_FORMAT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)
    private val WHITESPACE = Regex("\\s+")
    private val HEX = "0123456789ABCDEF".toCharArray()
    private val HEX_LOWER = "0123456789abcdef".toCharArray()

    /**
     * Sign a request and return the URL, method, and headers to pass to OkHttp.
     *
     * The returned headers include `host`, `x-amz-date`,
     * `x-amz-content-sha256`, an optional `x-amz-security-token`, and
     * `authorization`.
     */
    fun sign(options: SigV4SignOptions): SignedRequest {
        require(options.credentials.accessKeyId.isNotEmpty()) {
            "SigV4 requires an access key id"
        }
        require(options.credentials.secretAccessKey.isNotEmpty()) {
            "SigV4 requires a secret access key"
        }
        require(options.region.isNotEmpty()) { "SigV4 requires a region" }

        val amzDate = formatAmzDate(options.date)
        val dateStamp = amzDate.substring(0, 8)
        val payloadHash = sha256Hex(options.payload ?: ByteArray(0))

        val headers = LinkedHashMap<String, String>()
        options.headers.forEach { (name, value) ->
            headers[name.lowercase()] = value
        }
        headers["host"] = hostHeader(options.url)
        headers["x-amz-content-sha256"] = payloadHash
        headers["x-amz-date"] = amzDate
        options.credentials.sessionToken
            ?.takeIf { it.isNotEmpty() }
            ?.let { headers["x-amz-security-token"] = it }

        val canonical = buildCanonicalRequest(
            method = options.method,
            url = options.url,
            headers = headers,
            payloadHash = payloadHash,
        )
        val scope = "$dateStamp/${options.region}/${options.service}/$TERMINATOR"
        val stringToSign = buildStringToSign(
            amzDate = amzDate,
            scope = scope,
            canonicalRequestHash = sha256Hex(canonical.canonicalRequest.toByteArray(Charsets.UTF_8)),
        )
        val signingKey = deriveSigningKey(
            secretAccessKey = options.credentials.secretAccessKey,
            dateStamp = dateStamp,
            region = options.region,
            service = options.service,
        )
        val signature = toHex(
            hmacSha256(signingKey, stringToSign.toByteArray(Charsets.UTF_8)),
        )

        headers["authorization"] =
            "$ALGORITHM Credential=${options.credentials.accessKeyId}/$scope, " +
                "SignedHeaders=${canonical.signedHeaders}, Signature=$signature"

        return SignedRequest(
            method = options.method,
            url = options.url,
            headers = headers,
        )
    }

    /** RFC 3986 encoding as required by SigV4. */
    fun encodeRfc3986(value: String): String {
        val bytes = value.toByteArray(Charsets.UTF_8)
        val output = StringBuilder(bytes.size)
        for (byte in bytes) {
            val unsigned = byte.toInt() and 0xFF
            val character = unsigned.toChar()
            if (
                character in 'A'..'Z' ||
                character in 'a'..'z' ||
                character in '0'..'9' ||
                character == '-' ||
                character == '_' ||
                character == '.' ||
                character == '~'
            ) {
                output.append(character)
            } else {
                output.append('%')
                output.append(HEX[unsigned ushr 4])
                output.append(HEX[unsigned and 0x0F])
            }
        }
        return output.toString()
    }

    /** Lowercase hex SHA-256 of [data]. */
    fun sha256Hex(data: ByteArray): String =
        toHex(MessageDigest.getInstance("SHA-256").digest(data))

    /** Lowercase hex SHA-256 of the UTF-8 bytes of [data]. */
    fun sha256Hex(data: String): String = sha256Hex(data.toByteArray(Charsets.UTF_8))

    /** `YYYYMMDDTHHMMSSZ` without milliseconds. */
    fun formatAmzDate(date: Instant): String = AMZ_DATE_FORMAT.format(date)

    /** Derive the SigV4 signing key for a date, region, and service. */
    fun deriveSigningKey(
        secretAccessKey: String,
        dateStamp: String,
        region: String,
        service: String = SERVICE,
    ): ByteArray {
        val kDate = hmacSha256(
            ("AWS4$secretAccessKey").toByteArray(Charsets.UTF_8),
            dateStamp.toByteArray(Charsets.UTF_8),
        )
        val kRegion = hmacSha256(kDate, region.toByteArray(Charsets.UTF_8))
        val kService = hmacSha256(kRegion, service.toByteArray(Charsets.UTF_8))
        return hmacSha256(kService, TERMINATOR.toByteArray(Charsets.UTF_8))
    }

    /** Build the canonical request and signed-header list. */
    fun buildCanonicalRequest(
        method: SigV4Method,
        url: HttpUrl,
        headers: Map<String, String>,
        payloadHash: String,
    ): CanonicalRequest {
        val normalized = headers.entries.associate { (name, value) ->
            name.lowercase() to value
        }
        val names = normalized.keys.sorted()
        val canonicalHeaders = names.joinToString(separator = "") { name ->
            "${name}:${normalizeHeaderValue(normalized.getValue(name))}\n"
        }
        val signedHeaders = names.joinToString(separator = ";")
        val canonicalRequest = listOf(
            method.name,
            canonicalUri(url),
            canonicalQuery(url),
            canonicalHeaders,
            signedHeaders,
            payloadHash,
        ).joinToString(separator = "\n")

        return CanonicalRequest(
            canonicalRequest = canonicalRequest,
            signedHeaders = signedHeaders,
        )
    }

    /** Build the SigV4 string to sign. */
    fun buildStringToSign(
        amzDate: String,
        scope: String,
        canonicalRequestHash: String,
    ): String = listOf(ALGORITHM, amzDate, scope, canonicalRequestHash)
        .joinToString(separator = "\n")

    private fun canonicalUri(url: HttpUrl): String {
        val segments = url.pathSegments
        if (segments.isEmpty()) {
            return "/"
        }
        return "/" + segments.joinToString(separator = "/") { encodeRfc3986(it) }
    }

    private fun canonicalQuery(url: HttpUrl): String {
        val pairs = mutableListOf<Pair<String, String>>()
        for (name in url.queryParameterNames) {
            val values = url.queryParameterValues(name)
            if (values.isNullOrEmpty()) {
                pairs += encodeRfc3986(name) to ""
            } else {
                for (value in values) {
                    pairs += encodeRfc3986(name) to encodeRfc3986(value ?: "")
                }
            }
        }
        pairs.sortWith(compareBy({ it.first }, { it.second }))
        return pairs.joinToString(separator = "&") { (name, value) -> "$name=$value" }
    }

    private fun hostHeader(url: HttpUrl): String {
        val defaultPort = if (url.isHttps) 443 else 80
        return if (url.port == defaultPort) url.host else "${url.host}:${url.port}"
    }

    private fun normalizeHeaderValue(value: String): String =
        value.trim().replace(WHITESPACE, " ")

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    private fun toHex(bytes: ByteArray): String {
        val output = StringBuilder(bytes.size * 2)
        for (byte in bytes) {
            val unsigned = byte.toInt() and 0xFF
            output.append(HEX_LOWER[unsigned ushr 4])
            output.append(HEX_LOWER[unsigned and 0x0F])
        }
        return output.toString()
    }
}
