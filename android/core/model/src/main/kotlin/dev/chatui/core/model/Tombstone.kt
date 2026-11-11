@file:UseSerializers(InstantIso8601Serializer::class)

package dev.chatui.core.model

import java.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers

/**
 * Deletion tombstone from spec 13.5.
 *
 * A tombstone records that a conversation or message was deleted on a device.
 * It is kept until the remote manifest confirms the deletion, then the local
 * row may be hard-deleted after the retention window.
 */
@Serializable
data class Tombstone(
    val id: String,
    val conversationId: String,
    val messageId: String?,
    val deletedAt: Instant,
    val deviceId: String,
)
