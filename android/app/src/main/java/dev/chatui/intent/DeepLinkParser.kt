package dev.chatui.intent

import android.content.Intent
import android.net.Uri
import dev.chatui.core.model.isUuidV7

/**
 * Parses inbound `chatui://chat/<id>` deep links.
 *
 * The parser is deliberately pure so the accepted shape is testable without an
 * activity. Only a single canonical UUIDv7 path segment is accepted; query
 * parameters, fragments, user info, ports, extra path segments, and any other
 * scheme or host are rejected. A rejected link returns `null` and is ignored.
 */
object DeepLinkParser {
    /** Scheme registered by the manifest deep-link filter. */
    const val SCHEME: String = "chatui"

    /** Host registered by the manifest deep-link filter. */
    const val HOST: String = "chat"

    /**
     * Returns the conversation id from an `ACTION_VIEW` intent, or `null` when
     * the intent is not a supported deep link.
     */
    fun parse(intent: Intent?): String? {
        if (intent == null) return null
        if (intent.action != Intent.ACTION_VIEW) return null

        return parse(intent.data)
    }

    /** Returns the conversation id from [uri], or `null` when it is malformed. */
    fun parse(uri: Uri?): String? {
        if (uri == null) return null

        return parse(
            scheme = uri.scheme,
            host = uri.host,
            pathSegments = uri.pathSegments,
            query = uri.query,
            fragment = uri.fragment,
            userInfo = uri.userInfo,
            port = uri.port,
        )
    }

    /**
     * Pure validation entry point.
     *
     * [pathSegments] must contain exactly one non-empty segment and that
     * segment must be a canonical UUIDv7. Any query, fragment, user info, or
     * explicit port is rejected so a link cannot smuggle extra data.
     */
    fun parse(
        scheme: String?,
        host: String?,
        pathSegments: List<String>?,
        query: String? = null,
        fragment: String? = null,
        userInfo: String? = null,
        port: Int = -1,
    ): String? {
        if (!scheme.equals(SCHEME, ignoreCase = true)) return null
        if (!host.equals(HOST, ignoreCase = true)) return null
        if (query != null) return null
        if (fragment != null) return null
        if (userInfo != null) return null
        if (port != -1) return null
        if (pathSegments == null || pathSegments.size != 1) return null

        val conversationId = pathSegments.single()
        if (conversationId.isBlank()) return null
        if (!isUuidV7(conversationId)) return null

        return conversationId
    }
}
