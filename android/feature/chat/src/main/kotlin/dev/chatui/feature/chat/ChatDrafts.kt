package dev.chatui.feature.chat

import dev.chatui.core.datastore.settings.SettingsDataStore
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Per-conversation composer drafts.
 *
 * Drafts are kept outside the ViewModel so navigating between conversations
 * restores the text the user typed. [PersistentChatDrafts] additionally
 * survives process death; [InMemoryChatDrafts] is the process-local fallback
 * used by tests and previews.
 */
interface ChatDrafts {
    fun draftFor(conversationId: String): String

    fun setDraft(conversationId: String, text: String)

    fun clearDraft(conversationId: String)
}

/** Thread-safe in-memory draft store used by the application and tests. */
class InMemoryChatDrafts : ChatDrafts {
    private val drafts = ConcurrentHashMap<String, String>()

    override fun draftFor(conversationId: String): String = drafts[conversationId].orEmpty()

    override fun setDraft(conversationId: String, text: String) {
        if (text.isEmpty()) {
            drafts.remove(conversationId)
        } else {
            drafts[conversationId] = text
        }
    }

    override fun clearDraft(conversationId: String) {
        drafts.remove(conversationId)
    }
}

/**
 * Persistence boundary for [PersistentChatDrafts].
 *
 * Implementations must only store non-secret draft text keyed by conversation
 * id. Attachments, provider keys, and other secrets never pass through here.
 */
interface ChatDraftPersistence {
    suspend fun load(): Map<String, String>

    suspend fun save(drafts: Map<String, String>)
}

/** Persists drafts in the non-secret typed settings DataStore. */
class SettingsChatDraftPersistence(
    private val settings: SettingsDataStore,
) : ChatDraftPersistence {
    override suspend fun load(): Map<String, String> = settings.drafts()

    override suspend fun save(drafts: Map<String, String>) = settings.setDrafts(drafts)
}

/**
 * Draft store that keeps a synchronous in-memory cache and persists writes on
 * a debounce.
 *
 * The cache is bounded by [maxDrafts] conversations and [maxDraftLength]
 * characters per draft. When the conversation cap is exceeded the least
 * recently updated drafts are evicted. Persisted values are loaded once at
 * construction so [draftFor] can stay synchronous.
 */
class PersistentChatDrafts(
    private val persistence: ChatDraftPersistence,
    private val scope: CoroutineScope,
    private val debounceMs: Long = DEFAULT_DEBOUNCE_MS,
    private val maxDrafts: Int = DEFAULT_MAX_DRAFTS,
    private val maxDraftLength: Int = DEFAULT_MAX_DRAFT_LENGTH,
    initialDrafts: Map<String, String> = emptyMap(),
) : ChatDrafts {
    private data class Entry(val text: String, val updatedAt: Long)

    private val lock = Any()
    private val drafts = ConcurrentHashMap<String, Entry>()
    private val sequence = AtomicLong()
    private val saveGeneration = AtomicLong()

    init {
        initialDrafts.forEach { (conversationId, text) ->
            if (text.isNotEmpty()) {
                drafts[conversationId] = Entry(bound(text), sequence.incrementAndGet())
            }
        }
        synchronized(lock) { evictIfNeeded() }
    }

    override fun draftFor(conversationId: String): String =
        drafts[conversationId]?.text.orEmpty()

    override fun setDraft(conversationId: String, text: String) {
        if (text.isEmpty()) {
            clearDraft(conversationId)
            return
        }
        synchronized(lock) {
            drafts[conversationId] = Entry(bound(text), sequence.incrementAndGet())
            evictIfNeeded()
        }
        scheduleSave()
    }

    override fun clearDraft(conversationId: String) {
        synchronized(lock) { drafts.remove(conversationId) }
        scheduleSave()
    }

    /** Persists the current cache immediately, cancelling any pending debounce. */
    suspend fun flush() {
        saveGeneration.incrementAndGet()
        persistSnapshot()
    }

    private fun bound(text: String): String =
        if (text.length > maxDraftLength) text.take(maxDraftLength) else text

    private fun evictIfNeeded() {
        while (drafts.size > maxDrafts) {
            val oldest = drafts.entries.minByOrNull { it.value.updatedAt } ?: return
            drafts.remove(oldest.key)
        }
    }

    private fun scheduleSave() {
        val generation = saveGeneration.incrementAndGet()
        scope.launch {
            delay(debounceMs)
            if (saveGeneration.get() == generation) {
                persistSnapshot()
            }
        }
    }

    private suspend fun persistSnapshot() {
        val snapshot = synchronized(lock) {
            drafts.mapValues { (_, entry) -> entry.text }
        }
        try {
            persistence.save(snapshot)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            // Draft persistence is best-effort; the in-memory cache stays usable.
        }
    }

    companion object {
        /** Debounce before a draft write reaches disk. */
        const val DEFAULT_DEBOUNCE_MS: Long = 500L

        /** Maximum number of conversations with a stored draft. */
        const val DEFAULT_MAX_DRAFTS: Int = 50

        /** Maximum characters stored per draft. */
        const val DEFAULT_MAX_DRAFT_LENGTH: Int = 32_000

        /**
         * Loads persisted drafts once and returns a ready-to-use store.
         *
         * The load is a single small DataStore read. It runs on the IO
         * dispatcher so the caller is not blocked on disk I/O.
         */
        fun create(
            persistence: ChatDraftPersistence,
            scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            debounceMs: Long = DEFAULT_DEBOUNCE_MS,
            maxDrafts: Int = DEFAULT_MAX_DRAFTS,
            maxDraftLength: Int = DEFAULT_MAX_DRAFT_LENGTH,
        ): PersistentChatDrafts {
            val loaded = try {
                runBlocking(Dispatchers.IO) { persistence.load() }
            } catch (_: Throwable) {
                emptyMap()
            }
            return PersistentChatDrafts(
                persistence = persistence,
                scope = scope,
                debounceMs = debounceMs,
                maxDrafts = maxDrafts,
                maxDraftLength = maxDraftLength,
                initialDrafts = loaded,
            )
        }
    }
}
