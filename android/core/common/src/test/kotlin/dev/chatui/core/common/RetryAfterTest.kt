package dev.chatui.core.common

import com.google.common.truth.Truth.assertThat
import java.time.Instant
import org.junit.Test

class RetryAfterTest {
    @Test
    fun `parses delay seconds`() {
        assertThat(RetryAfter.parseSeconds("12")).isEqualTo(12L)
        assertThat(RetryAfter.parseSeconds(" 0 ")).isEqualTo(0L)
    }

    @Test
    fun `clamps negative delay seconds`() {
        assertThat(RetryAfter.parseSeconds("-5")).isEqualTo(0L)
    }

    @Test
    fun `parses HTTP date relative to now`() {
        val now = Instant.parse("2026-11-02T12:00:00Z")

        assertThat(RetryAfter.parseSeconds("Mon, 02 Nov 2026 12:00:30 GMT", now)).isEqualTo(30L)
    }

    @Test
    fun `returns null for absent or invalid values`() {
        assertThat(RetryAfter.parseSeconds(null)).isNull()
        assertThat(RetryAfter.parseSeconds("")).isNull()
        assertThat(RetryAfter.parseSeconds("not-a-date")).isNull()
    }
}
