package dev.chatui.core.model

import com.google.common.truth.Truth.assertThat
import java.util.Random
import org.junit.Test

class UuidV7Test {
    @Test
    fun `generated ids have UUIDv7 shape`() {
        repeat(1_000) {
            val id = newId()
            assertThat(isUuidV7(id)).isTrue()
            assertThat(id).hasLength(36)
            assertThat(id[14]).isEqualTo('7')
            assertThat(id[19]).isIn(listOf('8', '9', 'a', 'b'))
        }
    }

    @Test
    fun `generated ids are unique`() {
        val ids = List(10_000) { newId() }
        assertThat(ids.toSet()).hasSize(ids.size)
    }

    @Test
    fun `ids sort by timestamp`() {
        var now = 1_000L
        val generator = UuidV7Generator(random = Random(42), clock = { now })

        val first = generator.generate()
        now = 2_000L
        val second = generator.generate()

        assertThat(first < second).isTrue()
    }

    @Test
    fun `ids generated in the same millisecond are unique`() {
        val generator = UuidV7Generator(random = Random(7), clock = { 1_000L })
        val ids = List(1_000) { generator.generate() }

        assertThat(ids.toSet()).hasSize(ids.size)
        assertThat(ids.all { isUuidV7(it) }).isTrue()
    }

    @Test
    fun `isUuidV7 rejects other versions and malformed values`() {
        assertThat(isUuidV7("0190a0b0-0000-4000-8000-000000000001")).isFalse()
        assertThat(isUuidV7("0190a0b0-0000-7000-c000-000000000001")).isFalse()
        assertThat(isUuidV7("not-a-uuid")).isFalse()
        assertThat(isUuidV7("")).isFalse()
    }
}
