package dev.chatui.core.model

import com.google.common.truth.Truth.assertThat
import java.time.Instant
import kotlinx.serialization.encodeToString
import org.junit.Test

class InstantSerializationTest {
    @Test
    fun `serializes whole seconds as ISO 8601 UTC`() {
        val instant = Instant.parse("2026-11-02T12:34:56Z")

        val encoded = ChatUiJson.encodeToString(InstantIso8601Serializer, instant)

        assertThat(encoded).isEqualTo("\"2026-11-02T12:34:56Z\"")
    }

    @Test
    fun `serializes fractional seconds as ISO 8601 UTC`() {
        val instant = Instant.parse("2026-11-02T12:34:56.789Z")

        val encoded = ChatUiJson.encodeToString(InstantIso8601Serializer, instant)

        assertThat(encoded).isEqualTo("\"2026-11-02T12:34:56.789Z\"")
    }

    @Test
    fun `round trips through the serializer`() {
        val instant = Instant.parse("2026-11-02T12:34:56.123456Z")

        val encoded = ChatUiJson.encodeToString(InstantIso8601Serializer, instant)
        val decoded = ChatUiJson.decodeFromString(InstantIso8601Serializer, encoded)

        assertThat(decoded).isEqualTo(instant)
    }

    @Test
    fun `rejects non ISO 8601 input`() {
        val failure = runCatching {
            ChatUiJson.decodeFromString(InstantIso8601Serializer, "\"2026-11-02 12:34:56\"")
        }

        assertThat(failure.isFailure).isTrue()
    }
}
