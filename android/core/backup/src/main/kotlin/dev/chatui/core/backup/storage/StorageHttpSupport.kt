package dev.chatui.core.backup.storage

import java.io.IOException
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.w3c.dom.Document
import org.w3c.dom.Element

/** Map an HTTP status to the shared typed storage error code. */
internal fun storageStatusToCode(status: Int): StorageErrorCode = when (status) {
    401 -> StorageErrorCode.AUTH
    403 -> StorageErrorCode.PERMISSION
    404 -> StorageErrorCode.NOT_FOUND
    409, 412 -> StorageErrorCode.CONFLICT
    else -> StorageErrorCode.SERVER
}

/** Build a typed error from a non-successful response. */
internal fun storageError(action: String, response: Response): StorageError {
    val detail = runCatching { response.body.string().trim().take(500) }
        .getOrDefault("")
    val suffix = if (detail.isEmpty()) "" else ": $detail"
    return StorageError(
        code = storageStatusToCode(response.code),
        message = "$action failed with HTTP ${response.code}$suffix",
        status = response.code,
    )
}

/** Throw a typed error when the response is not successful. */
internal fun Response.ensureStorageOk(action: String) {
    if (!isSuccessful) {
        throw storageError(action, this)
    }
}

/** Execute a request on the IO dispatcher and map transport failures. */
internal suspend fun OkHttpClient.executeStorage(request: Request): Response =
    withContext(Dispatchers.IO) {
        try {
            newCall(request).execute()
        } catch (error: IOException) {
            throw StorageError(
                code = StorageErrorCode.NETWORK,
                message = "Network request failed for ${request.method} ${request.url.encodedPath}",
                cause = error,
            )
        }
    }

/** RFC 3986 percent-encode one path segment. */
internal fun encodeStoragePathSegment(value: String): String {
    val builder = StringBuilder(value.length)
    for (byte in value.toByteArray(Charsets.UTF_8)) {
        val char = byte.toInt() and 0xFF
        if (
            char in 'a'.code..'z'.code ||
            char in 'A'.code..'Z'.code ||
            char in '0'.code..'9'.code ||
            char == '-'.code ||
            char == '_'.code ||
            char == '.'.code ||
            char == '~'.code
        ) {
            builder.append(char.toChar())
        } else {
            builder.append('%')
            builder.append(HEX[char shr 4])
            builder.append(HEX[char and 0x0F])
        }
    }
    return builder.toString()
}

/** Encode a slash-separated object key, preserving path separators. */
internal fun encodeStorageKeyPath(key: String): String =
    key.split('/').joinToString(separator = "/") { encodeStoragePathSegment(it) }

private val HEX = "0123456789ABCDEF".toCharArray()

/** Parse an XML document with DTDs and external entities disabled. */
internal fun parseStorageXml(xml: String): Document {
    val factory = DocumentBuilderFactory.newInstance()
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
    factory.isNamespaceAware = true
    return try {
        factory.newDocumentBuilder().parse(xml.byteInputStream(Charsets.UTF_8))
    } catch (error: Exception) {
        throw StorageError(
            StorageErrorCode.SERVER,
            "Storage response is not valid XML",
            cause = error,
        )
    }
}

/** First descendant text for [tag], trimmed, or null when absent/empty. */
internal fun Element.childText(tag: String): String? {
    val nodes = getElementsByTagName(tag)
    if (nodes.length == 0) {
        return null
    }
    return nodes.item(0).textContent?.trim()?.takeIf { it.isNotEmpty() }
}

/** First descendant text for a namespace-qualified local name. */
internal fun Element.childTextNs(namespace: String, localName: String): String? {
    val nodes = getElementsByTagNameNS(namespace, localName)
    if (nodes.length == 0) {
        return null
    }
    return nodes.item(0).textContent?.trim()?.takeIf { it.isNotEmpty() }
}

/** Retry a read-after-write probe while the provider reports NOT_FOUND. */
internal suspend fun readAfterWrite(
    attempts: Int,
    retryDelayMs: Long,
    block: suspend () -> StorageObject,
): StorageObject {
    var lastError: StorageError? = null
    for (attempt in 0 until attempts) {
        try {
            return block()
        } catch (error: StorageError) {
            lastError = error
            if (error.code != StorageErrorCode.NOT_FOUND) {
                throw error
            }
            if (attempt < attempts - 1 && retryDelayMs > 0) {
                delay(retryDelayMs * (attempt + 1))
            }
        }
    }
    throw lastError ?: StorageError(
        StorageErrorCode.NOT_FOUND,
        "Object was not visible after write",
    )
}
