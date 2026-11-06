package dev.chatui.core.database.export

import dev.chatui.core.database.repository.AttachmentRepository
import dev.chatui.core.database.repository.ConversationRepository
import dev.chatui.core.database.repository.MessageRepository
import dev.chatui.core.database.repository.ModelRepository
import dev.chatui.core.database.repository.ProviderRepository
import dev.chatui.core.model.Attachment
import dev.chatui.core.model.ContentPart
import dev.chatui.core.model.Conversation
import dev.chatui.core.model.Message
import dev.chatui.core.model.Model
import dev.chatui.core.model.Provider
import dev.chatui.core.model.Settings

/**
 * Raw data collected for an export.
 *
 * This mirrors the contract arrays before the feature-level formatter reduces
 * attachments and adds optional bytes. It deliberately has no secrets field:
 * exports never read the `secrets` table, so key material cannot enter an
 * archive by accident.
 */
data class ExportSnapshot(
    val conversations: List<Conversation>,
    val messages: List<Message>,
    val attachments: List<Attachment>,
    val providers: List<Provider>,
    val models: List<Model>,
    val settings: Settings,
)

/** Supplies the non-secret settings that belong in an export. */
fun interface ExportSettingsSource {
    suspend fun current(): Settings
}

/** Read surface used by the export formatter and its tests. */
interface ExportReaderLike {
    suspend fun listConversations(): List<Conversation>

    suspend fun readAll(): ExportSnapshot

    suspend fun readConversation(conversationId: String): ExportSnapshot?
}

/**
 * Repository-level reader for export data.
 *
 * Conversations are ordered by creation time (then id), messages by sequence
 * within the corresponding conversation, and attachment/provider/model arrays
 * by their creation keys. Preserving these deterministic orders means an
 * export followed by an import can reconstruct the same history order.
 *
 * The reader never opens the secret store. Provider `secretId` values are
 * opaque references only.
 */
class ExportReader(
    private val conversations: ConversationRepository,
    private val messages: MessageRepository,
    private val attachments: AttachmentRepository,
    private val providers: ProviderRepository,
    private val models: ModelRepository,
    private val settings: ExportSettingsSource,
) : ExportReaderLike {
    /** Conversations newest-first for the export entry points. */
    override suspend fun listConversations(): List<Conversation> =
        conversations.all().sortedWith(
            compareByDescending<Conversation> { it.updatedAt }
                .thenByDescending { it.id }
                .thenBy { it.createdAt },
        )

    override suspend fun readAll(): ExportSnapshot {
        val allConversations = conversations.all().sortedWith(
            compareBy<Conversation> { it.createdAt }.thenBy { it.id },
        )
        val conversationOrder = allConversations
            .mapIndexed { index, conversation -> conversation.id to index }
            .toMap()

        return ExportSnapshot(
            conversations = allConversations,
            messages = messages.all().sortedWith(
                compareBy<Message> {
                    conversationOrder[it.conversationId] ?: Int.MAX_VALUE
                }
                    .thenBy { it.sequence }
                    .thenBy { it.id },
            ),
            attachments = attachments.all().sortedWith(
                compareBy<Attachment> { it.createdAt }.thenBy { it.id },
            ),
            providers = providers.all().sortedWith(
                compareBy<Provider> { it.createdAt }.thenBy { it.id },
            ),
            models = models.all().sortedWith(
                compareBy<Model> { it.providerId }
                    .thenBy { it.remoteId }
                    .thenBy { it.id },
            ),
            settings = settings.current(),
        )
    }

    /**
     * Read one conversation with its messages and only the attachments those
     * messages reference. Historical model/provider snapshots stay on the
     * conversation and messages untouched.
     */
    override suspend fun readConversation(conversationId: String): ExportSnapshot? {
        val conversation = conversations.get(conversationId) ?: return null
        val conversationMessages = messages.allForConversation(conversationId)
            .sortedWith(compareBy<Message> { it.sequence }.thenBy { it.id })
        val referenced = referencedAttachmentIds(conversationMessages)

        return ExportSnapshot(
            conversations = listOf(conversation),
            messages = conversationMessages,
            attachments = attachments.all()
                .filter { it.id in referenced }
                .sortedWith(compareBy<Attachment> { it.createdAt }.thenBy { it.id }),
            providers = providers.all().sortedWith(
                compareBy<Provider> { it.createdAt }.thenBy { it.id },
            ),
            models = models.all().sortedWith(
                compareBy<Model> { it.providerId }
                    .thenBy { it.remoteId }
                    .thenBy { it.id },
            ),
            settings = settings.current(),
        )
    }
}

private fun referencedAttachmentIds(messages: List<Message>): Set<String> = messages
    .flatMap { it.parts }
    .mapNotNull { part ->
        when (part) {
            is ContentPart.Image -> part.attachmentId
            is ContentPart.File -> part.attachmentId
            else -> null
        }
    }
    .toSet()
