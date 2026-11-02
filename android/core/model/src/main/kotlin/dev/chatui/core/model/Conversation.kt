@file:UseSerializers(InstantIso8601Serializer::class)

package dev.chatui.core.model

import java.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import kotlinx.serialization.json.JsonObject

/** Reasoning effort control from spec section 6.2. */
@Serializable
enum class ReasoningEffort {
    @SerialName("low")
    LOW,

    @SerialName("medium")
    MEDIUM,

    @SerialName("high")
    HIGH,
}

/**
 * Chat parameters from spec section 6.2.
 *
 * Provider-specific request options live in [extra], namespaced by preset. The
 * core domain never interprets those values; adapters validate and map them.
 */
@Serializable
data class ChatParams(
    val temperature: Double?,
    val topP: Double?,
    val maxOutputTokens: Int?,
    val stop: List<String>,
    val reasoningEffort: ReasoningEffort?,
    val streaming: Boolean,
    val extra: JsonObject,
)

/**
 * Provisional sync state placeholder.
 *
 * The spec references `SyncMeta` and `syncState` without defining their final
 * shape. M0 only needs a documented placeholder so [Conversation.sync] has a
 * type; no sync behavior is implemented. M5 owns the final shape, including
 * conflict resolution, tombstones, and remote revision semantics.
 */
@Serializable
enum class SyncState {
    @SerialName("local")
    LOCAL,

    @SerialName("pending")
    PENDING,

    @SerialName("synced")
    SYNCED,

    @SerialName("conflict")
    CONFLICT,
}

/**
 * Provisional sync metadata placeholder. See [SyncState].
 *
 * [lastSyncedAt] is the last successful sync timestamp, or `null` when never
 * synced. [remoteRevision] is an opaque remote revision identifier, or `null`
 * when never synced.
 */
@Serializable
data class SyncMeta(
    val state: SyncState,
    val lastSyncedAt: Instant?,
    val remoteRevision: String?,
)

/** Conversation entity from spec section 6.2. */
@Serializable
data class Conversation(
    val id: String,
    val title: String,
    val systemPrompt: String,
    val providerId: String,
    val modelId: String,
    val modelLabel: String,
    val params: ChatParams,
    val pinned: Boolean,
    val archived: Boolean,
    val temporary: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
    val lastMessageAt: Instant,
    val messageCount: Int,
    val lastError: ChatError?,
    val sync: SyncMeta?,
)
