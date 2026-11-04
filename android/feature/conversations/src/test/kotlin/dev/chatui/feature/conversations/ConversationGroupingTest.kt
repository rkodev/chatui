package dev.chatui.feature.conversations

import com.google.common.truth.Truth.assertThat
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale
import org.junit.Test

class ConversationGroupingTest {
    private val now = Instant.parse("2026-11-03T12:00:00Z")
    private val zone = ZoneOffset.UTC

    @Test
    fun `groups conversations into the required buckets`() {
        val conversations = listOf(
            testConversation(id = "today", lastMessageAt = Instant.parse("2026-11-03T08:00:00Z")),
            testConversation(
                id = "yesterday",
                lastMessageAt = Instant.parse("2026-11-02T23:00:00Z"),
            ),
            testConversation(
                id = "previous-7",
                lastMessageAt = Instant.parse("2026-10-30T10:00:00Z"),
            ),
            testConversation(
                id = "previous-30",
                lastMessageAt = Instant.parse("2026-10-10T10:00:00Z"),
            ),
            testConversation(id = "older", lastMessageAt = Instant.parse("2026-09-01T10:00:00Z")),
        )

        val groups = groupConversationsByDate(conversations, now, zone)

        assertThat(groups.map { it.key }).containsExactly(
            ConversationGroupKey.TODAY,
            ConversationGroupKey.YESTERDAY,
            ConversationGroupKey.PREVIOUS_7_DAYS,
            ConversationGroupKey.PREVIOUS_30_DAYS,
            ConversationGroupKey.OLDER,
        ).inOrder()
        assertThat(groups.map { it.label }).containsExactly(
            "Today",
            "Yesterday",
            "Previous 7 days",
            "Previous 30 days",
            "Older",
        ).inOrder()
        assertThat(groups.flatMap { group -> group.conversations.map { it.id } })
            .containsExactly("today", "yesterday", "previous-7", "previous-30", "older")
    }

    @Test
    fun `empty buckets are omitted`() {
        val groups = groupConversationsByDate(
            listOf(testConversation(id = "today", lastMessageAt = now)),
            now,
            zone,
        )

        assertThat(groups).hasSize(1)
        assertThat(groups.single().key).isEqualTo(ConversationGroupKey.TODAY)
    }

    @Test
    fun `conversations are newest first inside a group`() {
        val groups = groupConversationsByDate(
            listOf(
                testConversation(id = "older-today", lastMessageAt = Instant.parse("2026-11-03T01:00:00Z")),
                testConversation(id = "newer-today", lastMessageAt = Instant.parse("2026-11-03T11:00:00Z")),
            ),
            now,
            zone,
        )

        assertThat(groups.single().conversations.map { it.id })
            .containsExactly("newer-today", "older-today")
            .inOrder()
    }

    @Test
    fun `future timestamps are treated as today`() {
        val groups = groupConversationsByDate(
            listOf(testConversation(id = "future", lastMessageAt = Instant.parse("2026-11-04T01:00:00Z"))),
            now,
            zone,
        )

        assertThat(groups.single().key).isEqualTo(ConversationGroupKey.TODAY)
    }

    @Test
    fun `bucket boundaries match the required day ranges`() {
        assertThat(historyGroupForInstant(Instant.parse("2026-11-02T12:00:00Z"), now, zone))
            .isEqualTo(ConversationGroupKey.YESTERDAY)
        assertThat(historyGroupForInstant(Instant.parse("2026-10-27T12:00:00Z"), now, zone))
            .isEqualTo(ConversationGroupKey.PREVIOUS_7_DAYS)
        assertThat(historyGroupForInstant(Instant.parse("2026-10-26T12:00:00Z"), now, zone))
            .isEqualTo(ConversationGroupKey.PREVIOUS_30_DAYS)
        assertThat(historyGroupForInstant(Instant.parse("2026-10-04T12:00:00Z"), now, zone))
            .isEqualTo(ConversationGroupKey.PREVIOUS_30_DAYS)
        assertThat(historyGroupForInstant(Instant.parse("2026-10-03T12:00:00Z"), now, zone))
            .isEqualTo(ConversationGroupKey.OLDER)
    }

    @Test
    fun `timestamp formatting uses time today and date for older rows`() {
        assertThat(
            formatConversationTimestamp(
                instant = Instant.parse("2026-11-03T08:30:00Z"),
                now = now,
                zoneId = zone,
                locale = Locale.US,
            ),
        ).isEqualTo("08:30")

        assertThat(
            formatConversationTimestamp(
                instant = Instant.parse("2026-10-30T08:30:00Z"),
                now = now,
                zoneId = zone,
                locale = Locale.US,
            ),
        ).isEqualTo("Oct 30")

        assertThat(
            formatConversationTimestamp(
                instant = Instant.parse("2025-10-30T08:30:00Z"),
                now = now,
                zoneId = zone,
                locale = Locale.US,
            ),
        ).isEqualTo("2025 Oct 30")
    }
}
