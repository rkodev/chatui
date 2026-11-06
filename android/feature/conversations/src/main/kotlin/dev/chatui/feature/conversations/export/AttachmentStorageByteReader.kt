package dev.chatui.feature.conversations.export

import dev.chatui.core.database.attachment.AttachmentStorage

/** Reads attachment bytes from the content-addressed local store. */
class AttachmentStorageByteReader(
    private val storage: AttachmentStorage,
) : AttachmentByteReader {
    override suspend fun get(storageKey: String): ByteArray? = storage.read(storageKey)
}
