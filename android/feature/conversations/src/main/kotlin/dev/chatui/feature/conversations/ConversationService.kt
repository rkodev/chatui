package dev.chatui.feature.conversations

import dagger.Binds
import dagger.Module
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.chatui.core.database.repository.ConversationRepository
import dev.chatui.core.database.repository.MessageRepository
import dev.chatui.core.database.repository.ModelRepository
import dev.chatui.core.database.repository.ProviderRepository
import dev.chatui.core.datastore.settings.SettingsDataStore
import dev.chatui.core.model.Conversation
import dev.chatui.core.model.Model
import dev.chatui.core.model.OutputModality
import dev.chatui.core.model.Provider
import dev.chatui.core.model.newId
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** Title used before the first user message supplies one. */
const val DEFAULT_CONVERSATION_TITLE: String = "New chat"

/** Result of creating a conversation from the current typed defaults. */
sealed interface CreateConversationResult {
    data class Created(val conversation: Conversation) : CreateConversationResult

    data class NotConfigured(val message: String) : CreateConversationResult
}

/** Stable conversation failure codes for UI mapping. */
enum class ConversationServiceErrorCode {
    NOT_FOUND,
    INVALID_INPUT,
}

/** Service-level failure that is safe to show to the UI. */
class ConversationServiceException(
    val code: ConversationServiceErrorCode,
    message: String,
    val conversationId: String? = null,
) : Exception(message)

/**
 * Conversation list, creation, rename, delete, and last-active facade.
 *
 * The facade owns new-chat default resolution and last-active tracking so the
 * ViewModel and composables never touch repositories or DataStore directly.
 */
interface ConversationService {
    fun observeConversations(): Flow<List<Conversation>>

    /**
     * Observe archived conversations for the archive view.
     *
     * The default keeps lightweight test doubles source-compatible; the
     * production implementation overrides it with the archived repository flow.
     */
    fun observeArchivedConversations(): Flow<List<Conversation>> = emptyFlow()

    fun observeLastActiveConversationId(): Flow<String?>

    suspend fun createConversation(title: String? = null): CreateConversationResult

    suspend fun renameConversation(id: String, title: String): Conversation

    /**
     * Pin or unpin a conversation.
     *
     * The default keeps lightweight test doubles source-compatible; the
     * production implementation persists the flag and returns the updated row.
     */
    suspend fun setPinned(id: String, pinned: Boolean): Conversation? = null

    /**
     * Archive or unarchive a conversation.
     *
     * The default keeps lightweight test doubles source-compatible; the
     * production implementation persists the flag and returns the updated row.
     */
    suspend fun setArchived(id: String, archived: Boolean): Conversation? = null

    suspend fun deleteConversation(id: String)

    suspend fun setLastActiveConversation(id: String?)

    suspend fun getLastActiveConversation(): Conversation?

    suspend fun getConversation(id: String): Conversation?

    /**
     * Search active conversation titles and message text.
     *
     * The default keeps lightweight test doubles source-compatible; the
     * production implementation overrides it with the FTS-backed search.
     */
    suspend fun searchConversations(
        query: String,
        limit: Int = 50,
    ): List<ConversationSearchResult> = emptyList()
}

/**
 * Production facade backed by the application-scoped repositories and typed
 * settings.
 *
 * New chats prefer the configured default provider/model. If either is missing
 * or disabled, the first enabled provider and its first non-hidden text model
 * are used. When nothing can be resolved, a typed `NotConfigured` result is
 * returned instead of throwing.
 */
class DefaultConversationService @Inject constructor(
    private val conversations: ConversationRepository,
    private val messages: MessageRepository,
    private val providers: ProviderRepository,
    private val models: ModelRepository,
    private val settings: SettingsDataStore,
    private val clock: Clock,
) : ConversationService {
    /** Overridable id generator for deterministic tests. */
    internal var generateId: () -> String = ::newId

    override fun observeConversations(): Flow<List<Conversation>> =
        conversations.observeActive()

    override fun observeArchivedConversations(): Flow<List<Conversation>> =
        conversations.observeArchived()

    override fun observeLastActiveConversationId(): Flow<String?> =
        settings.settings.map { it.lastActiveConversationId }

    override suspend fun createConversation(title: String?): CreateConversationResult {
        val current = settings.current()
        val provider = resolveProvider(current.defaultProviderId)
            ?: return CreateConversationResult.NotConfigured(
                "No provider is configured. Add a provider before starting a chat.",
            )

        val model = resolveModel(provider, current.defaultModelId)
            ?: return CreateConversationResult.NotConfigured(
                "No model is available for ${provider.label}. " +
                    "Refresh the model catalog before starting a chat.",
            )

        val now = clock.instant()
        val conversation = Conversation(
            id = generateId(),
            title = title?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_CONVERSATION_TITLE,
            systemPrompt = current.defaultSystemPrompt,
            providerId = provider.id,
            modelId = model.id,
            modelLabel = model.label,
            params = current.defaultParams,
            pinned = false,
            archived = false,
            temporary = false,
            createdAt = now,
            updatedAt = now,
            lastMessageAt = now,
            messageCount = 0,
            lastError = null,
            sync = null,
        )

        conversations.create(conversation)
        settings.setLastActiveConversation(conversation.id)
        return CreateConversationResult.Created(conversation)
    }

    override suspend fun renameConversation(id: String, title: String): Conversation {
        val trimmed = title.trim()

        if (trimmed.isEmpty()) {
            throw ConversationServiceException(
                ConversationServiceErrorCode.INVALID_INPUT,
                "Conversation title must not be empty.",
                id,
            )
        }

        if (!conversations.rename(id, trimmed)) {
            throw ConversationServiceException(
                ConversationServiceErrorCode.NOT_FOUND,
                "Conversation $id was not found.",
                id,
            )
        }

        return conversations.get(id)
            ?: throw ConversationServiceException(
                ConversationServiceErrorCode.NOT_FOUND,
                "Conversation $id was not found.",
                id,
            )
    }

    override suspend fun deleteConversation(id: String) {
        conversations.delete(id)

        if (settings.current().lastActiveConversationId == id) {
            settings.setLastActiveConversation(null)
        }
    }

    override suspend fun setPinned(id: String, pinned: Boolean): Conversation {
        if (!conversations.setPinned(id, pinned)) {
            throw ConversationServiceException(
                ConversationServiceErrorCode.NOT_FOUND,
                "Conversation $id was not found.",
                id,
            )
        }

        return conversations.get(id)
            ?: throw ConversationServiceException(
                ConversationServiceErrorCode.NOT_FOUND,
                "Conversation $id was not found.",
                id,
            )
    }

    override suspend fun setArchived(id: String, archived: Boolean): Conversation {
        if (!conversations.setArchived(id, archived)) {
            throw ConversationServiceException(
                ConversationServiceErrorCode.NOT_FOUND,
                "Conversation $id was not found.",
                id,
            )
        }

        return conversations.get(id)
            ?: throw ConversationServiceException(
                ConversationServiceErrorCode.NOT_FOUND,
                "Conversation $id was not found.",
                id,
            )
    }

    override suspend fun setLastActiveConversation(id: String?) {
        if (id != null && conversations.get(id) == null) {
            throw ConversationServiceException(
                ConversationServiceErrorCode.NOT_FOUND,
                "Conversation $id was not found.",
                id,
            )
        }

        settings.setLastActiveConversation(id)
    }

    override suspend fun getLastActiveConversation(): Conversation? {
        val id = settings.current().lastActiveConversationId ?: return null
        val conversation = conversations.get(id)

        if (conversation == null) {
            settings.setLastActiveConversation(null)
            return null
        }

        return conversation
    }

    override suspend fun getConversation(id: String): Conversation? = conversations.get(id)

    override suspend fun searchConversations(
        query: String,
        limit: Int,
    ): List<ConversationSearchResult> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()

        val titleMatches = conversations.searchByTitle(trimmed, limit)
        val ftsQuery = buildFtsQuery(trimmed)
        val messageHits = if (ftsQuery == null) {
            emptyList()
        } else {
            messages.searchWithConversations(ftsQuery, limit)
        }

        val grouped = linkedMapOf<String, MutableSearchResult>()
        titleMatches.forEach { conversation ->
            grouped.getOrPut(conversation.id) { MutableSearchResult(conversation) }
                .titleMatched = true
        }
        messageHits.forEach { hit ->
            val result = grouped.getOrPut(hit.conversation.id) {
                MutableSearchResult(hit.conversation)
            }
            result.messageMatches += ConversationSearchMessageMatch(
                messageId = hit.message.id,
                sequence = hit.message.sequence,
                role = hit.message.role,
                snippet = searchSnippet(hit.message, trimmed),
                createdAt = hit.message.createdAt,
            )
        }

        return grouped.values
            .map { it.toResult() }
            .sortedWith(
                compareByDescending<ConversationSearchResult> { it.conversation.pinned }
                    .thenByDescending { it.conversation.lastMessageAt },
            )
            .take(limit)
    }

    private class MutableSearchResult(val conversation: Conversation) {
        var titleMatched: Boolean = false
        val messageMatches = mutableListOf<ConversationSearchMessageMatch>()

        fun toResult(): ConversationSearchResult = ConversationSearchResult(
            conversation = conversation,
            titleMatched = titleMatched,
            messageMatches = messageMatches.sortedByDescending { it.sequence },
        )
    }

    private suspend fun resolveProvider(preferredId: String?): Provider? {
        if (preferredId != null) {
            val preferred = providers.get(preferredId)
            if (preferred?.enabled == true) {
                return preferred
            }
        }

        return providers.observeAll().first().firstOrNull { it.enabled }
    }

    private suspend fun resolveModel(provider: Provider, preferredId: String?): Model? {
        val providerModels = models.forProvider(provider.id)

        if (preferredId != null) {
            providerModels.firstOrNull { it.id == preferredId }?.let { return it }
        }

        return providerModels
            .filter { model ->
                !model.hidden && OutputModality.TEXT in model.outputModalities
            }
            .minByOrNull { it.remoteId }
    }
}

/** Binds the conversation facade into the singleton graph. */
@Module
@InstallIn(SingletonComponent::class)
abstract class ConversationModule {
    @Binds
    @Singleton
    abstract fun bindConversationService(
        impl: DefaultConversationService,
    ): ConversationService
}

/**
 * Entry point used by the shell to obtain the conversation facade without
 * depending on Hilt ViewModel navigation helpers.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface ConversationEntryPoint {
    fun conversationService(): ConversationService
}
