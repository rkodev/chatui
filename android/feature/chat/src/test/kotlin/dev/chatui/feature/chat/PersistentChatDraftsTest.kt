package dev.chatui.feature.chat

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PersistentChatDraftsTest {
    private class FakeDraftPersistence(
        var stored: Map<String, String> = emptyMap(),
    ) : ChatDraftPersistence {
        var saves: Int = 0

        override suspend fun load(): Map<String, String> = stored

        override suspend fun save(drafts: Map<String, String>) {
            stored = drafts
            saves += 1
        }
    }

    @Test
    fun `drafts persist across a new store instance`() = runTest {
        val persistence = FakeDraftPersistence()
        val first = PersistentChatDrafts(persistence, this, debounceMs = 0)
        first.setDraft("conversation-1", "hello")
        first.flush()

        val second = PersistentChatDrafts.create(persistence, this, debounceMs = 0)

        assertThat(second.draftFor("conversation-1")).isEqualTo("hello")
    }

    @Test
    fun `drafts are restored per conversation without leaking`() = runTest {
        val persistence = FakeDraftPersistence()
        val first = PersistentChatDrafts(persistence, this, debounceMs = 0)
        first.setDraft("conversation-1", "one")
        first.setDraft("conversation-2", "two")
        first.flush()

        val second = PersistentChatDrafts.create(persistence, this, debounceMs = 0)

        assertThat(second.draftFor("conversation-1")).isEqualTo("one")
        assertThat(second.draftFor("conversation-2")).isEqualTo("two")
        assertThat(second.draftFor("conversation-3")).isEmpty()
    }

    @Test
    fun `clear removes the entry and persists the removal`() = runTest {
        val persistence = FakeDraftPersistence()
        val store = PersistentChatDrafts(persistence, this, debounceMs = 0)
        store.setDraft("conversation-1", "hello")
        store.flush()

        store.clearDraft("conversation-1")
        store.flush()

        assertThat(store.draftFor("conversation-1")).isEmpty()
        assertThat(persistence.stored).isEmpty()
    }

    @Test
    fun `exceeding the conversation cap evicts the oldest drafts`() = runTest {
        val persistence = FakeDraftPersistence()
        val store = PersistentChatDrafts(
            persistence = persistence,
            scope = this,
            debounceMs = 0,
            maxDrafts = 2,
        )

        store.setDraft("conversation-1", "one")
        store.setDraft("conversation-2", "two")
        store.setDraft("conversation-3", "three")
        store.flush()

        assertThat(store.draftFor("conversation-1")).isEmpty()
        assertThat(store.draftFor("conversation-2")).isEqualTo("two")
        assertThat(store.draftFor("conversation-3")).isEqualTo("three")
        assertThat(persistence.stored.keys).containsExactly("conversation-2", "conversation-3")
    }

    @Test
    fun `rewriting a draft refreshes its recency before eviction`() = runTest {
        val persistence = FakeDraftPersistence()
        val store = PersistentChatDrafts(
            persistence = persistence,
            scope = this,
            debounceMs = 0,
            maxDrafts = 2,
        )

        store.setDraft("conversation-1", "one")
        store.setDraft("conversation-2", "two")
        store.setDraft("conversation-1", "one updated")
        store.setDraft("conversation-3", "three")
        store.flush()

        assertThat(store.draftFor("conversation-1")).isEqualTo("one updated")
        assertThat(store.draftFor("conversation-2")).isEmpty()
        assertThat(store.draftFor("conversation-3")).isEqualTo("three")
    }

    @Test
    fun `setting an empty draft clears the entry`() = runTest {
        val persistence = FakeDraftPersistence()
        val store = PersistentChatDrafts(persistence, this, debounceMs = 0)
        store.setDraft("conversation-1", "hello")
        store.flush()

        store.setDraft("conversation-1", "")
        store.flush()

        assertThat(store.draftFor("conversation-1")).isEmpty()
        assertThat(persistence.stored).isEmpty()
    }

    @Test
    fun `exceeding the per-draft cap truncates safely`() = runTest {
        val persistence = FakeDraftPersistence()
        val store = PersistentChatDrafts(
            persistence = persistence,
            scope = this,
            debounceMs = 0,
            maxDraftLength = 5,
        )

        store.setDraft("conversation-1", "abcdefgh")
        store.flush()

        assertThat(store.draftFor("conversation-1")).isEqualTo("abcde")
        assertThat(persistence.stored).containsExactly("conversation-1", "abcde")
    }

    @Test
    fun `writes are debounced and coalesced`() = runTest {
        val persistence = FakeDraftPersistence()
        val store = PersistentChatDrafts(persistence, this, debounceMs = 500)

        store.setDraft("conversation-1", "a")
        store.setDraft("conversation-1", "ab")
        advanceTimeBy(499)
        runCurrent()
        assertThat(persistence.saves).isEqualTo(0)

        advanceTimeBy(1)
        runCurrent()

        assertThat(persistence.saves).isEqualTo(1)
        assertThat(persistence.stored).containsExactly("conversation-1", "ab")
    }

    @Test
    fun `only draft text is persisted with no secret or attachment data`() = runTest {
        val persistence = FakeDraftPersistence()
        val store = PersistentChatDrafts(persistence, this, debounceMs = 0)

        store.setDraft("conversation-1", "hello")
        store.flush()

        assertThat(persistence.stored).containsExactly("conversation-1", "hello")
        assertThat(persistence.stored.keys.none { key ->
            key.contains("secret") ||
                key.contains("api_key") ||
                key.contains("attachment") ||
                key.contains("password")
        }).isTrue()
    }
}
