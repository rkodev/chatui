package dev.chatui.core.database.attachment

import java.io.File

/**
 * Content-addressed attachment byte storage.
 *
 * Files live under `filesDir/attachments/<sha256>`. Writes are atomic: bytes
 * are written to a temporary file and renamed into place, so a crash cannot
 * leave a partially written attachment that later looks valid.
 */
class AttachmentStorage(private val root: File) {
    init {
        root.mkdirs()
    }

    /** Absolute file for a stored attachment key. */
    fun fileFor(storageKey: String): File = File(root, storageKey)

    /** Write [bytes] under the content hash and return the storage key. */
    fun put(bytes: ByteArray, sha256: String): String {
        val storageKey = storageKeyFor(sha256)
        val target = fileFor(storageKey)
        target.parentFile?.mkdirs()

        if (target.exists() && target.length() == bytes.size.toLong()) {
            return storageKey
        }

        val temporary = File(target.parentFile, "$sha256.tmp")
        temporary.writeBytes(bytes)

        if (!temporary.renameTo(target)) {
            target.writeBytes(bytes)
            temporary.delete()
        }

        return storageKey
    }

    /** Read stored bytes, or null when the file is missing. */
    fun read(storageKey: String): ByteArray? {
        val file = fileFor(storageKey)
        return if (file.isFile) file.readBytes() else null
    }

    /** Delete one stored file. Missing files are ignored. */
    fun delete(storageKey: String) {
        fileFor(storageKey).delete()
    }

    /**
     * Delete every stored file whose hash is not in [referencedSha256].
     *
     * Returns the deleted storage keys. Temporary files are removed too.
     */
    fun cleanupOrphans(referencedSha256: Set<String>): List<String> {
        val directory = File(root, ATTACHMENTS_DIRECTORY)
        val files = directory.listFiles() ?: return emptyList()
        val deleted = mutableListOf<String>()

        files.forEach { file ->
            if (!file.isFile) return@forEach

            val name = file.name
            val hash = name.removeSuffix(TEMPORARY_SUFFIX)
            if (hash !in referencedSha256) {
                if (file.delete()) {
                    deleted += "$ATTACHMENTS_DIRECTORY/$name"
                }
            }
        }

        return deleted
    }

    companion object {
        const val ATTACHMENTS_DIRECTORY: String = "attachments"
        private const val TEMPORARY_SUFFIX: String = ".tmp"

        fun storageKeyFor(sha256: String): String = "$ATTACHMENTS_DIRECTORY/$sha256"
    }
}
