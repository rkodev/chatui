package dev.chatui.core.database.repository

import com.google.common.truth.Truth.assertThat
import dev.chatui.core.database.DatabaseTestBase
import dev.chatui.core.database.TestData
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ProviderRepositoryTest : DatabaseTestBase() {
    private val clock = Clock.fixed(
        Instant.parse("2026-11-02T12:00:00Z"),
        ZoneOffset.UTC,
    )

    private val repository: ProviderRepository
        get() = ProviderRepository(db.providerDao(), clock)

    @Test
    fun `upsert preserving flags keeps enabled and secret`() = runTest {
        val original = RepositoryTestData.provider(
            enabled = true,
            secretId = "secret-1",
        )
        repository.upsert(original)

        val merged = repository.upsertPreservingFlags(
            original.copy(
                label = "Updated",
                enabled = false,
                secretId = null,
                createdAt = RepositoryTestData.NOW.plusSeconds(100),
            ),
        )

        assertThat(merged.label).isEqualTo("Updated")
        assertThat(merged.enabled).isTrue()
        assertThat(merged.secretId).isEqualTo("secret-1")
        assertThat(merged.createdAt).isEqualTo(RepositoryTestData.NOW)
        assertThat(repository.get(original.id)).isEqualTo(merged)
    }

    @Test
    fun `upsert preserving flags uses incoming secret when none is stored`() = runTest {
        val original = RepositoryTestData.provider(secretId = null)
        repository.upsert(original)

        val merged = repository.upsertPreservingFlags(
            original.copy(secretId = "secret-2"),
        )

        assertThat(merged.secretId).isEqualTo("secret-2")
    }

    @Test
    fun `set enabled updates the flag and timestamp`() = runTest {
        repository.upsert(RepositoryTestData.provider(enabled = true))

        assertThat(repository.setEnabled("provider-1", false)).isTrue()

        val updated = repository.get("provider-1")!!
        assertThat(updated.enabled).isFalse()
        assertThat(updated.updatedAt).isEqualTo(clock.instant())
    }

    @Test
    fun `set enabled on a missing provider returns false`() = runTest {
        assertThat(repository.setEnabled("missing", false)).isFalse()
    }

    @Test
    fun `delete removes models and secrets`() = runTest {
        repository.upsert(RepositoryTestData.provider(secretId = "secret-1"))
        db.modelDao().insert(TestData.model())
        db.secretDao().upsert(TestData.secret())

        repository.delete("provider-1")

        assertThat(repository.get("provider-1")).isNull()
        assertThat(db.modelDao().getById("model-1")).isNull()
        assertThat(db.secretDao().getById("secret-1")).isNull()
    }
}
