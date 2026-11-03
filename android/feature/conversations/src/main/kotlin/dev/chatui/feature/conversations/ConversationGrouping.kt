package dev.chatui.feature.conversations

import dev.chatui.core.model.Conversation
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/** Date buckets used by the conversation history list. */
enum class ConversationGroupKey(val label: String) {
    TODAY("Today"),
    YESTERDAY("Yesterday"),
    PREVIOUS_7_DAYS("Previous 7 days"),
    PREVIOUS_30_DAYS("Previous 30 days"),
    OLDER("Older"),
}

/** One rendered history section. Empty sections are omitted. */
data class ConversationGroup(
    val key: ConversationGroupKey,
    val label: String,
    val conversations: List<Conversation>,
)

/**
 * Return the history bucket for [instant] relative to [now].
 *
 * Calendar days are compared in [zoneId] so daylight-saving transitions do not
 * shift a conversation into the wrong bucket. Future timestamps are treated as
 * Today so clock skew cannot hide a conversation.
 */
fun historyGroupForInstant(
    instant: Instant,
    now: Instant,
    zoneId: ZoneId = ZoneId.systemDefault(),
): ConversationGroupKey {
    val nowDate = now.atZone(zoneId).toLocalDate()
    val date = instant.atZone(zoneId).toLocalDate()
    val dayDifference = ChronoUnit.DAYS.between(date, nowDate)

    return when {
        dayDifference <= 0L -> ConversationGroupKey.TODAY
        dayDifference == 1L -> ConversationGroupKey.YESTERDAY
        dayDifference <= 7L -> ConversationGroupKey.PREVIOUS_7_DAYS
        dayDifference <= 30L -> ConversationGroupKey.PREVIOUS_30_DAYS
        else -> ConversationGroupKey.OLDER
    }
}

/**
 * Group conversations into the required date buckets.
 *
 * Groups are returned in display order and empty groups are omitted. Within a
 * group, conversations are newest-first by `lastMessageAt`. The input list is
 * not mutated.
 */
fun groupConversationsByDate(
    conversations: List<Conversation>,
    now: Instant,
    zoneId: ZoneId = ZoneId.systemDefault(),
): List<ConversationGroup> {
    val buckets = ConversationGroupKey.entries.associateWith { mutableListOf<Conversation>() }

    conversations.forEach { conversation ->
        val key = historyGroupForInstant(conversation.lastMessageAt, now, zoneId)
        buckets.getValue(key).add(conversation)
    }

    return ConversationGroupKey.entries.mapNotNull { key ->
        val items = buckets.getValue(key).sortedByDescending { it.lastMessageAt }
        if (items.isEmpty()) {
            null
        } else {
            ConversationGroup(key = key, label = key.label, conversations = items)
        }
    }
}

/**
 * Format the timestamp shown on a conversation row.
 *
 * Today shows the local time, the current year shows month/day, and older
 * conversations include the year.
 */
fun formatConversationTimestamp(
    instant: Instant,
    now: Instant,
    zoneId: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(),
): String {
    val date = instant.atZone(zoneId)
    val nowDate = now.atZone(zoneId)

    return when {
        date.toLocalDate() == nowDate.toLocalDate() ->
            date.format(DateTimeFormatter.ofPattern("HH:mm", locale))

        date.year == nowDate.year ->
            date.format(DateTimeFormatter.ofPattern("MMM d", locale))

        else ->
            date.format(DateTimeFormatter.ofPattern("yyyy MMM d", locale))
    }
}
