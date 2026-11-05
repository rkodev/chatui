package dev.chatui.core.database.repository

import dev.chatui.core.database.entity.ConversationEntity
import dev.chatui.core.database.entity.AttachmentEntity
import dev.chatui.core.database.entity.MessageEntity
import dev.chatui.core.database.entity.ModelEntity
import dev.chatui.core.database.entity.ProviderEntity
import dev.chatui.core.model.BrowserCors
import dev.chatui.core.model.Attachment
import dev.chatui.core.model.AttachmentKind
import dev.chatui.core.model.ChatError
import dev.chatui.core.model.ChatParams
import dev.chatui.core.model.ChatUiJson
import dev.chatui.core.model.ContentPart
import dev.chatui.core.model.Conversation
import dev.chatui.core.model.FinishReason
import dev.chatui.core.model.InputModality
import dev.chatui.core.model.Message
import dev.chatui.core.model.MessageRole
import dev.chatui.core.model.MessageStatus
import dev.chatui.core.model.Model
import dev.chatui.core.model.ModelCapability
import dev.chatui.core.model.ModelPricing
import dev.chatui.core.model.ModelSource
import dev.chatui.core.model.OutputModality
import dev.chatui.core.model.Provider
import dev.chatui.core.model.ProviderAuthType
import dev.chatui.core.model.ProviderKind
import dev.chatui.core.model.SyncMeta
import dev.chatui.core.model.SyncState
import dev.chatui.core.model.Usage
import java.time.Instant
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonObject

/**
 * Entity/domain mapping for the repository layer.
 *
 * Structured values are stored as JSON using [ChatUiJson] so the domain model
 * can evolve without a Room migration. Enum columns store the contract serial
 * name (for example `openai-compatible`), matching the shared fixtures and the
 * existing DAO test data.
 */

internal fun ConversationEntity.toDomain(): Conversation = Conversation(
    id = id,
    title = title,
    systemPrompt = systemPrompt,
    providerId = providerId,
    modelId = modelId,
    modelLabel = modelLabel,
    params = ChatUiJson.decodeFromString(ChatParams.serializer(), paramsJson),
    pinned = pinned,
    archived = archived,
    temporary = temporary,
    createdAt = Instant.parse(createdAt),
    updatedAt = Instant.parse(updatedAt),
    lastMessageAt = Instant.parse(lastMessageAt),
    messageCount = messageCount,
    lastError = lastErrorJson?.let {
        ChatUiJson.decodeFromString(ChatError.serializer(), it)
    },
    sync = syncState?.let { stored ->
        SyncMeta(
            state = parseSyncState(stored),
            // The v1 schema only has a state column. M5 owns the full sync
            // metadata shape and will add columns when sync is implemented.
            lastSyncedAt = null,
            remoteRevision = null,
        )
    },
)

internal fun Conversation.toEntity(): ConversationEntity = ConversationEntity(
    id = id,
    title = title,
    systemPrompt = systemPrompt,
    providerId = providerId,
    modelId = modelId,
    modelLabel = modelLabel,
    paramsJson = ChatUiJson.encodeToString(ChatParams.serializer(), params),
    pinned = pinned,
    archived = archived,
    temporary = temporary,
    createdAt = createdAt.toString(),
    updatedAt = updatedAt.toString(),
    lastMessageAt = lastMessageAt.toString(),
    messageCount = messageCount,
    lastErrorJson = lastError?.let {
        ChatUiJson.encodeToString(ChatError.serializer(), it)
    },
    syncState = sync?.let { serialName(SyncState.serializer(), it.state) },
)

internal fun AttachmentEntity.toDomain(): Attachment = Attachment(
    id = id,
    messageId = messageId,
    kind = parseSerialName(AttachmentKind.serializer(), kind),
    mimeType = mimeType,
    name = name,
    size = size,
    sha256 = sha256,
    storageKey = storageKey,
    width = width,
    height = height,
    createdAt = Instant.parse(createdAt),
)

internal fun Attachment.toEntity(): AttachmentEntity = AttachmentEntity(
    id = id,
    messageId = messageId,
    kind = serialName(AttachmentKind.serializer(), kind),
    mimeType = mimeType,
    name = name,
    size = size,
    sha256 = sha256,
    storageKey = storageKey,
    width = width,
    height = height,
    createdAt = createdAt.toString(),
)

internal fun MessageEntity.toDomain(): Message = Message(    id = id,
    conversationId = conversationId,
    sequence = sequence,
    role = parseSerialName(MessageRole.serializer(), role),
    parts = ChatUiJson.decodeFromString(
        ListSerializer(ContentPart.serializer()),
        partsJson,
    ),
    status = parseSerialName(MessageStatus.serializer(), status),
    providerId = providerId,
    modelId = modelId,
    modelLabel = modelLabel,
    usage = usageJson?.let { ChatUiJson.decodeFromString(Usage.serializer(), it) },
    finishReason = finishReason?.let {
        parseSerialName(FinishReason.serializer(), it)
    },
    error = errorJson?.let { ChatUiJson.decodeFromString(ChatError.serializer(), it) },
    latencyMs = latencyMs,
    firstTokenMs = firstTokenMs,
    createdAt = Instant.parse(createdAt),
    updatedAt = Instant.parse(updatedAt),
    deletedAt = deletedAt?.let(Instant::parse),
)

internal fun Message.toEntity(): MessageEntity = MessageEntity(
    id = id,
    conversationId = conversationId,
    sequence = sequence,
    role = serialName(MessageRole.serializer(), role),
    partsJson = ChatUiJson.encodeToString(
        ListSerializer(ContentPart.serializer()),
        parts,
    ),
    status = serialName(MessageStatus.serializer(), status),
    providerId = providerId,
    modelId = modelId,
    modelLabel = modelLabel,
    usageJson = usage?.let { ChatUiJson.encodeToString(Usage.serializer(), it) },
    finishReason = finishReason?.let { serialName(FinishReason.serializer(), it) },
    errorJson = error?.let { ChatUiJson.encodeToString(ChatError.serializer(), it) },
    latencyMs = latencyMs,
    firstTokenMs = firstTokenMs,
    createdAt = createdAt.toString(),
    updatedAt = updatedAt.toString(),
    deletedAt = deletedAt?.toString(),
)

internal fun ProviderEntity.toDomain(): Provider = Provider(
    id = id,
    presetId = presetId,
    label = label,
    kind = parseSerialName(ProviderKind.serializer(), kind),
    baseUrl = baseUrl,
    authType = parseSerialName(ProviderAuthType.serializer(), authType),
    secretId = secretId,
    headers = ChatUiJson.decodeFromString(
        MapSerializer(String.serializer(), String.serializer()),
        headersJson,
    ),
    enabled = enabled,
    browserCors = parseSerialName(BrowserCors.serializer(), browserCors),
    metadata = metadataJson?.let {
        ChatUiJson.decodeFromString(JsonObject.serializer(), it)
    },
    createdAt = Instant.parse(createdAt),
    updatedAt = Instant.parse(updatedAt),
)

internal fun Provider.toEntity(): ProviderEntity = ProviderEntity(
    id = id,
    presetId = presetId,
    label = label,
    kind = serialName(ProviderKind.serializer(), kind),
    baseUrl = baseUrl,
    authType = serialName(ProviderAuthType.serializer(), authType),
    secretId = secretId,
    headersJson = ChatUiJson.encodeToString(
        MapSerializer(String.serializer(), String.serializer()),
        headers,
    ),
    enabled = enabled,
    browserCors = serialName(BrowserCors.serializer(), browserCors),
    metadataJson = metadata?.let {
        ChatUiJson.encodeToString(JsonObject.serializer(), it)
    },
    createdAt = createdAt.toString(),
    updatedAt = updatedAt.toString(),
)

internal fun ModelEntity.toDomain(): Model = Model(
    id = id,
    providerId = providerId,
    remoteId = remoteId,
    label = label,
    contextWindow = contextWindow,
    maxOutputTokens = maxOutputTokens,
    capabilities = ChatUiJson.decodeFromString(
        ListSerializer(ModelCapability.serializer()),
        capabilitiesJson,
    ),
    pricing = pricingJson?.let {
        ChatUiJson.decodeFromString(ModelPricing.serializer(), it)
    },
    canonicalSlug = canonicalSlug,
    inputModalities = ChatUiJson.decodeFromString(
        ListSerializer(InputModality.serializer()),
        inputModalitiesJson,
    ),
    outputModalities = ChatUiJson.decodeFromString(
        ListSerializer(OutputModality.serializer()),
        outputModalitiesJson,
    ),
    supportedParameters = ChatUiJson.decodeFromString(
        ListSerializer(String.serializer()),
        supportedParametersJson,
    ),
    metadata = metadataJson?.let {
        ChatUiJson.decodeFromString(JsonObject.serializer(), it)
    },
    hidden = hidden,
    pinned = pinned,
    source = parseSerialName(ModelSource.serializer(), source),
    createdAt = Instant.parse(createdAt),
    updatedAt = Instant.parse(updatedAt),
)

internal fun Model.toEntity(): ModelEntity = ModelEntity(
    id = id,
    providerId = providerId,
    remoteId = remoteId,
    label = label,
    contextWindow = contextWindow,
    maxOutputTokens = maxOutputTokens,
    capabilitiesJson = ChatUiJson.encodeToString(
        ListSerializer(ModelCapability.serializer()),
        capabilities,
    ),
    pricingJson = pricing?.let {
        ChatUiJson.encodeToString(ModelPricing.serializer(), it)
    },
    canonicalSlug = canonicalSlug,
    inputModalitiesJson = ChatUiJson.encodeToString(
        ListSerializer(InputModality.serializer()),
        inputModalities,
    ),
    outputModalitiesJson = ChatUiJson.encodeToString(
        ListSerializer(OutputModality.serializer()),
        outputModalities,
    ),
    supportedParametersJson = ChatUiJson.encodeToString(
        ListSerializer(String.serializer()),
        supportedParameters,
    ),
    metadataJson = metadata?.let {
        ChatUiJson.encodeToString(JsonObject.serializer(), it)
    },
    hidden = hidden,
    pinned = pinned,
    source = serialName(ModelSource.serializer(), source),
    createdAt = createdAt.toString(),
    updatedAt = updatedAt.toString(),
)

/**
 * Plain text indexed by FTS for a message.
 *
 * Text and reasoning are the primary searchable content. Tool calls, tool
 * results, image alt text, and file names are included so search can find
 * messages whose visible content is not a text part.
 */
internal fun searchableText(parts: List<ContentPart>): String = parts.mapNotNull { part ->
    when (part) {
        is ContentPart.Text -> part.text
        is ContentPart.Reasoning -> part.text
        is ContentPart.ToolCall -> listOf(part.name, part.argumentsJson).joinToString(" ")
        is ContentPart.ToolResult -> part.content
        is ContentPart.Image -> part.alt
        is ContentPart.File -> part.name
    }
}.filter { it.isNotBlank() }
    .joinToString("\n")

internal fun MessageStatus.toStorageValue(): String =
    serialName(MessageStatus.serializer(), this)

private fun <T> serialName(serializer: KSerializer<T>, value: T): String =
    ChatUiJson.encodeToString(serializer, value).trim('"')

private fun <T> parseSerialName(serializer: KSerializer<T>, value: String): T =
    ChatUiJson.decodeFromString(serializer, "\"$value\"")

private fun parseSyncState(value: String): SyncState =
    runCatching { parseSerialName(SyncState.serializer(), value) }
        .getOrElse { SyncState.valueOf(value.uppercase().replace('-', '_')) }
