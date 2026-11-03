package dev.chatui.core.database.repository

import com.google.common.truth.Truth.assertThat
import dev.chatui.core.database.DatabaseTestBase
import dev.chatui.core.model.ModelSource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ModelRepositoryTest : DatabaseTestBase() {
    private val repository: ModelRepository
        get() = ModelRepository(db.modelDao())

    @Test
    fun `insert and read model`() = runTest {
        val model = RepositoryTestData.model()

        repository.insert(model)

        assertThat(repository.get(model.id)).isEqualTo(model)
        assertThat(repository.findByRemoteId(model.providerId, model.remoteId))
            .isEqualTo(model)
    }

    @Test
    fun `observe provider models maps entities to domain`() = runTest {
        repository.insert(RepositoryTestData.model(id = "model-b", remoteId = "b", label = "B"))
        repository.insert(RepositoryTestData.model(id = "model-a", remoteId = "a", label = "A"))

        val labels = repository.observeForProvider("provider-1").first().map { it.label }

        assertThat(labels).containsExactly("A", "B").inOrder()
    }

    @Test
    fun `upsert preserving flags keeps hidden pinned manual source and createdAt`() = runTest {
        val original = RepositoryTestData.model(
            hidden = true,
            pinned = true,
            source = ModelSource.DISCOVERED,
        )
        repository.insert(original)

        val merged = repository.upsertPreservingFlags(
            original.copy(
                label = "Updated",
                hidden = false,
                pinned = false,
                source = ModelSource.MANUAL,
                createdAt = RepositoryTestData.NOW.plusSeconds(100),
            ),
        )

        assertThat(merged.label).isEqualTo("Updated")
        assertThat(merged.hidden).isTrue()
        assertThat(merged.pinned).isTrue()
        assertThat(merged.source).isEqualTo(ModelSource.MANUAL)
        assertThat(merged.createdAt).isEqualTo(RepositoryTestData.NOW)
        assertThat(repository.get(original.id)).isEqualTo(merged)
    }

    @Test
    fun `catalog refresh keeps a manual model manual`() = runTest {
        val manual = RepositoryTestData.model(
            hidden = true,
            pinned = true,
            source = ModelSource.MANUAL,
        )
        repository.insert(manual)

        val merged = repository.upsertPreservingFlags(
            manual.copy(
                label = "Catalog label",
                hidden = false,
                pinned = false,
                source = ModelSource.DISCOVERED,
            ),
        )

        assertThat(merged.label).isEqualTo("Catalog label")
        assertThat(merged.hidden).isTrue()
        assertThat(merged.pinned).isTrue()
        assertThat(merged.source).isEqualTo(ModelSource.MANUAL)
    }

    @Test
    fun `upsert preserving flags matches by remote id`() = runTest {
        val original = RepositoryTestData.model(id = "model-1", remoteId = "shared")
        repository.insert(original)

        val merged = repository.upsertPreservingFlags(
            RepositoryTestData.model(id = "model-2", remoteId = "shared", label = "Updated"),
        )

        assertThat(merged.id).isEqualTo("model-1")
        assertThat(repository.get("model-1")!!.label).isEqualTo("Updated")
        assertThat(repository.get("model-2")).isNull()
    }

    @Test
    fun `delete for provider removes only that provider models`() = runTest {
        repository.insert(RepositoryTestData.model(id = "model-1", providerId = "provider-1"))
        repository.insert(
            RepositoryTestData.model(
                id = "model-2",
                providerId = "provider-2",
                remoteId = "remote-2",
            ),
        )

        repository.deleteForProvider("provider-1")

        assertThat(repository.get("model-1")).isNull()
        assertThat(repository.get("model-2")).isNotNull()
    }
}
