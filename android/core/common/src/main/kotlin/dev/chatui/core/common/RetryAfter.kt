package dev.chatui.core.common

import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * `Retry-After` header parsing hook.
 *
 * Supports both forms allowed by RFC 9110: delay-seconds and an HTTP-date.
 * Returns `null` when the value is absent or unparseable.
 */
object RetryAfter {
    fun parseSeconds(value: String?, now: Instant = Instant.now()): Long? {
        if (value.isNullOrBlank()) return null
        val trimmed = value.trim()

        trimmed.toLongOrNull()?.let { return it.coerceAtLeast(0) }

        return try {
            val target = ZonedDateTime.parse(trimmed, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
            Duration.between(now, target).seconds.coerceAtLeast(0)
        } catch (_: DateTimeParseException) {
            null
        }
    }
}
