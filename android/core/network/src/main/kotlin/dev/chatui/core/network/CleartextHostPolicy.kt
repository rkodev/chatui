package dev.chatui.core.network

import java.io.IOException
import java.net.InetAddress
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Central cleartext policy for local providers.
 *
 * Android network-security-config XML cannot express RFC1918, IPv6 ULA, or
 * link-local CIDR ranges, so the manifest enables base cleartext only as the
 * platform-level enabler. This policy is the real allowlist and is applied to
 * every cleartext request by [CleartextPolicyInterceptor].
 *
 * Allowed cleartext hosts:
 * - loopback: `localhost`, `127.0.0.0/8`, `::1`
 * - Android emulator host loopback: `10.0.2.2`
 * - mDNS names ending in `.local`
 * - RFC1918 IPv4: `10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16`
 * - IPv6 unique-local `fc00::/7` and link-local `fe80::/10`
 *
 * Every other cleartext host is refused. HTTPS is always allowed and is never
 * resolved or otherwise inspected by this policy.
 */
object CleartextHostPolicy {
    /**
     * Return true when [host] is an approved local cleartext host literal or a
     * `.local` name.
     *
     * This is the pure, DNS-free part of the policy. Use
     * [isAllowedCleartextRequest] when a hostname must be resolved and checked
     * against the approved address ranges.
     */
    fun isAllowedCleartextHost(host: String): Boolean {
        val normalized = normalizeHost(host)
        if (normalized.isEmpty()) return false
        if (normalized == "localhost") return true
        if (normalized.endsWith(".local")) return true

        val address = parseIpLiteral(normalized) ?: return false
        return isAllowedCleartextAddress(address)
    }

    /**
     * Return true when a request with [scheme] and [host] may use cleartext.
     *
     * HTTPS is always allowed. HTTP is allowed only when the host is an
     * approved literal or a `.local` name whose resolved addresses are all in
     * the approved local ranges. Any other scheme is refused.
     */
    fun isAllowedCleartextRequest(
        scheme: String,
        host: String,
        resolver: (String) -> List<InetAddress> = ::resolveHostAddresses,
    ): Boolean = when (scheme.lowercase()) {
        "https" -> true
        "http" -> isAllowedCleartextHostResolved(host, resolver)
        else -> false
    }

    /** Return true when [url] may be requested with its current scheme. */
    fun isAllowedCleartextUrl(
        url: HttpUrl,
        resolver: (String) -> List<InetAddress> = ::resolveHostAddresses,
    ): Boolean = isAllowedCleartextRequest(url.scheme, url.host, resolver)

    /** Return true when [address] is inside an approved local range. */
    fun isAllowedCleartextAddress(address: InetAddress): Boolean {
        if (address.isLoopbackAddress) return true

        return when (address.address.size) {
            4 -> isAllowedIpv4(address.address)
            16 -> isAllowedIpv6(address.address)
            else -> false
        }
    }

    private fun isAllowedCleartextHostResolved(
        host: String,
        resolver: (String) -> List<InetAddress>,
    ): Boolean {
        val normalized = normalizeHost(host)
        if (!isAllowedCleartextHost(normalized)) return false

        // A literal address is already fully checked; never resolve it.
        if (parseIpLiteral(normalized) != null) return true

        val addresses = try {
            resolver(normalized)
        } catch (_: Exception) {
            return false
        }

        if (addresses.isEmpty()) return false
        return addresses.all(::isAllowedCleartextAddress)
    }

    private fun isAllowedIpv4(bytes: ByteArray): Boolean {
        val first = bytes[0].toInt() and 0xff
        val second = bytes[1].toInt() and 0xff

        return when {
            first == 10 -> true
            first == 172 && second in 16..31 -> true
            first == 192 && second == 168 -> true
            else -> false
        }
    }

    private fun isAllowedIpv6(bytes: ByteArray): Boolean {
        val first = bytes[0].toInt() and 0xff
        val second = bytes[1].toInt() and 0xff

        val uniqueLocal = (first and 0xfe) == 0xfc
        val linkLocal = first == 0xfe && (second and 0xc0) == 0x80
        return uniqueLocal || linkLocal
    }
}

/** A cleartext request was refused by [CleartextHostPolicy]. */
class CleartextNotAllowedException(message: String) : IOException(message)

/**
 * Refuse cleartext requests to public hosts before OkHttp opens a connection.
 *
 * HTTPS requests pass through unchanged. The interceptor is installed on the
 * shared production client so every provider adapter is covered.
 */
class CleartextPolicyInterceptor(
    private val resolver: (String) -> List<InetAddress> = ::resolveHostAddresses,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url

        if (!CleartextHostPolicy.isAllowedCleartextUrl(url, resolver)) {
            throw CleartextNotAllowedException(
                "Cleartext HTTP is only allowed for local providers. " +
                    "Use HTTPS for ${url.host}.",
            )
        }

        return chain.proceed(request)
    }
}

internal fun resolveHostAddresses(host: String): List<InetAddress> =
    InetAddress.getAllByName(host).toList()

private fun normalizeHost(host: String): String =
    host.trim()
        .removePrefix("[")
        .removeSuffix("]")
        .trimEnd('.')
        .lowercase()

private val IPV6_LITERAL = Regex("^[0-9a-fA-F:.]+$")

/**
 * Parse an IPv4 or IPv6 literal without performing DNS resolution.
 *
 * Returns null for hostnames so callers can decide whether to resolve them.
 */
internal fun parseIpLiteral(host: String): InetAddress? {
    val normalized = normalizeHost(host)
    if (normalized.isEmpty()) return null

    if (normalized.contains(':')) {
        val withoutZone = normalized.substringBefore('%')
        if (!IPV6_LITERAL.matches(withoutZone)) return null
        return try {
            InetAddress.getByName(withoutZone)
        } catch (_: Exception) {
            null
        }
    }

    val parts = normalized.split('.')
    if (parts.size != 4) return null

    val bytes = ByteArray(4)
    for ((index, part) in parts.withIndex()) {
        if (part.isEmpty() || part.length > 3 || part.any { !it.isDigit() }) return null
        val value = part.toIntOrNull() ?: return null
        if (value !in 0..255) return null
        bytes[index] = value.toByte()
    }

    return try {
        InetAddress.getByAddress(bytes)
    } catch (_: Exception) {
        null
    }
}
