package dev.chatui.core.database.attachment

import com.google.common.truth.Truth.assertThat
import dev.chatui.core.database.DatabaseTestBase
import dev.chatui.core.database.TestData
import dev.chatui.core.database.repository.AttachmentRepository
import dev.chatui.core.database.repository.MessageRepository
import dev.chatui.core.model.AttachmentKind
import dev.chatui.core.model.ContentPart
import dev.chatui.core.model.Message
import dev.chatui.core.model.MessageRole
import dev.chatui.core.model.MessageStatus
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AttachmentServiceTest : DatabaseTestBase() {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val now = Instant.parse("2026-11-05T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private var nextId = 0

    private lateinit var repository: AttachmentRepository
    private lateinit var messages: MessageRepository
    private lateinit var storage: AttachmentStorage
    private lateinit var service: AttachmentService

    @Before
    fun setUpService() {
        repository = AttachmentRepository(db.attachmentDao())
        messages = MessageRepository(db.messageDao(), clock)
        storage = AttachmentStorage(temporaryFolder.newFolder("files"))
        service = AttachmentService(
            repository = repository,
            messages = messages,
            storage = storage,
            imageProcessor = FakeImageProcessor(),
            clock = clock,
            newId = { "attachment-${nextId++}" },
        )
    }

    @Test
    fun `adds images and text files with content-addressed storage`() = kotlinx.coroutines.test.runTest {
        val imageBytes = byteArrayOf(1, 2, 3, 4)
        val textBytes = "hello".toByteArray()

        val result = service.addFiles(
            listOf(
                AttachmentInput("photo.png", "image/png", imageBytes.size.toLong(), imageBytes),
                AttachmentInput("notes.txt", "text/plain", textBytes.size.toLong(), textBytes),
            ),
        )

        assertThat(result.errors).isEmpty()
        assertThat(result.attachments).hasSize(2)

        val image = result.attachments.first { it.kind == AttachmentKind.IMAGE }
        assertThat(image.width).isEqualTo(10)
        assertThat(image.height).isEqualTo(10)
        assertThat(image.sha256).isEqualTo(sha256(imageBytes))
        assertThat(image.storageKey).isEqualTo("attachments/${sha256(imageBytes)}")
        assertThat(storage.fileFor(image.storageKey).readBytes()).isEqualTo(imageBytes)

        val text = result.attachments.first { it.kind == AttachmentKind.FILE }
        assertThat(text.mimeType).isEqualTo("text/plain")
        assertThat(storage.fileFor(text.storageKey).readBytes()).isEqualTo(textBytes)
    }

    @Test
    fun `rejects invalid input without writing metadata`() = kotlinx.coroutines.test.runTest {
        val result = service.addFiles(
            listOf(
                AttachmentInput("empty.txt", "text/plain", 0, ByteArray(0)),
                AttachmentInput("tool.exe", "application/octet-stream", 4, byteArrayOf(1, 2, 3, 4)),
            ),
        )

        assertThat(result.attachments).isEmpty()
        assertThat(result.errors.map { it.code }).containsExactly(
            AttachmentValidationCode.EMPTY,
            AttachmentValidationCode.UNSUPPORTED_TYPE,
        )
        assertThat(repository.all()).isEmpty()
    }

    @Test
    fun `metadata survives a new service instance`() = kotlinx.coroutines.test.runTest {
        val bytes = "persisted".toByteArray()
        val added = service.addFiles(
            listOf(AttachmentInput("notes.txt", "text/plain", bytes.size.toLong(), bytes)),
        ).attachments.single()

        val reloaded = AttachmentService(
            repository = repository,
            messages = messages,
            storage = storage,
            imageProcessor = FakeImageProcessor(),
            clock = clock,
        )

        assertThat(reloaded.get(added.id)).isEqualTo(added)
        assertThat(reloaded.forMessage("message-1")).isEmpty()
    }

    @Test
    fun `resolves bytes for image and file parts`() = kotlinx.coroutines.test.runTest {
        val imageBytes = byteArrayOf(9, 8, 7)
        val textBytes = "body".toByteArray()
        val added = service.addFiles(
            listOf(
                AttachmentInput("photo.png", "image/png", imageBytes.size.toLong(), imageBytes),
                AttachmentInput("notes.txt", "text/plain", textBytes.size.toLong(), textBytes),
            ),
        ).attachments

        val image = added.first { it.kind == AttachmentKind.IMAGE }
        val file = added.first { it.kind == AttachmentKind.FILE }
        val message = messageWithParts(
            ContentPart.Image(image.id, image.mimeType, image.name),
            ContentPart.File(file.id, file.mimeType, file.name),
        )

        val resolved = service.resolveForMessages(listOf(message))

        assertThat(resolved[image.id]?.dataBase64)
            .isEqualTo(Base64.getEncoder().encodeToString(imageBytes))
        assertThat(resolved[file.id]?.dataBase64)
            .isEqualTo(Base64.getEncoder().encodeToString(textBytes))
    }

    @Test
    fun `removing an attachment deletes unreferenced bytes`() = kotlinx.coroutines.test.runTest {
        val bytes = "remove me".toByteArray()
        val added = service.addFiles(
            listOf(AttachmentInput("notes.txt", "text/plain", bytes.size.toLong(), bytes)),
        ).attachments.single()

        service.remove(added.id)

        assertThat(repository.get(added.id)).isNull()
        assertThat(storage.fileFor(added.storageKey).exists()).isFalse()
    }

    @Test
    fun `attaching draft rows binds them to a message`() = kotlinx.coroutines.test.runTest {
        val bytes = "draft".toByteArray()
        val added = service.addFiles(
            listOf(AttachmentInput("notes.txt", "text/plain", bytes.size.toLong(), bytes)),
        ).attachments.single()

        service.attachToMessage(listOf(added.id), "message-1")

        assertThat(repository.get(added.id)?.messageId).isEqualTo("message-1")
    }

    @Test
    fun `cleanup removes unbound rows and unreferenced bytes`() = kotlinx.coroutines.test.runTest {
        db.conversationDao().upsert(TestData.conversation())
        db.messageDao().insertMessage(TestData.message(id = "message-1", sequence = 1))

        val draft = service.addFiles(
            listOf(AttachmentInput("draft.txt", "text/plain", 5, "draft".toByteArray())),
        ).attachments.single()
        val bound = service.addFiles(
            listOf(AttachmentInput("bound.txt", "text/plain", 5, "bound".toByteArray())),
            messageId = "message-1",
        ).attachments.single()
        storage.put("orphan".toByteArray(), "orphan-hash")

        val deleted = service.cleanupOrphans()

        assertThat(repository.get(draft.id)).isNull()
        assertThat(repository.get(bound.id)).isNotNull()
        assertThat(storage.fileFor(draft.storageKey).exists()).isFalse()
        assertThat(storage.fileFor(bound.storageKey).exists()).isTrue()
        assertThat(storage.fileFor("attachments/orphan-hash").exists()).isFalse()
        assertThat(deleted).contains("attachments/orphan-hash")
    }

    @Test
    fun `deleteForMessages removes metadata and bytes for the given messages only`() =
        kotlinx.coroutines.test.runTest {
            db.conversationDao().upsert(TestData.conversation())
            db.messageDao().insertMessage(TestData.message(id = "message-1", sequence = 1))
            db.messageDao().insertMessage(TestData.message(id = "message-2", sequence = 2))

            val first = service.addFiles(
                listOf(AttachmentInput("first.txt", "text/plain", 5, "first".toByteArray())),
                messageId = "message-1",
            ).attachments.single()
            val second = service.addFiles(
                listOf(AttachmentInput("second.txt", "text/plain", 6, "second".toByteArray())),
                messageId = "message-2",
            ).attachments.single()

            service.deleteForMessages(listOf("message-1"))

            assertThat(repository.get(first.id)).isNull()
            assertThat(storage.fileFor(first.storageKey).exists()).isFalse()
            assertThat(repository.get(second.id)).isNotNull()
            assertThat(storage.fileFor(second.storageKey).exists()).isTrue()
        }

    @Test
    fun `removing one of two rows sharing a hash keeps the shared bytes`() =
        kotlinx.coroutines.test.runTest {
            val bytes = "shared".toByteArray()
            val added = service.addFiles(
                listOf(
                    AttachmentInput("one.txt", "text/plain", bytes.size.toLong(), bytes),
                    AttachmentInput("two.txt", "text/plain", bytes.size.toLong(), bytes),
                ),
            ).attachments

            assertThat(added.map { it.sha256 }.distinct()).hasSize(1)
            val storageKey = added.first().storageKey

            service.remove(added.first().id)

            assertThat(repository.get(added.first().id)).isNull()
            assertThat(repository.get(added.last().id)).isNotNull()
            assertThat(storage.fileFor(storageKey).exists()).isTrue()

            service.remove(added.last().id)

            assertThat(storage.fileFor(storageKey).exists()).isFalse()
        }

    @Test
    fun `resolving missing metadata or bytes fails instead of sending a malformed request`() =
        kotlinx.coroutines.test.runTest {
            val bytes = "resolve".toByteArray()
            val added = service.addFiles(
                listOf(AttachmentInput("notes.txt", "text/plain", bytes.size.toLong(), bytes)),
            ).attachments.single()
            val message = messageWithParts(
                ContentPart.File(added.id, added.mimeType, added.name),
            )

            repository.deleteById(added.id)
            val missingMetadata = runCatching {
                service.resolveForMessages(listOf(message))
            }.exceptionOrNull()

            assertThat(missingMetadata).isInstanceOf(IllegalStateException::class.java)
            assertThat(missingMetadata!!.message).contains("missing from local storage")

            val restored = service.addFiles(
                listOf(AttachmentInput("notes.txt", "text/plain", bytes.size.toLong(), bytes)),
            ).attachments.single()
            storage.delete(restored.storageKey)
            val missingBytes = runCatching {
                service.resolveForMessages(
                    listOf(messageWithParts(ContentPart.File(restored.id, restored.mimeType, restored.name))),
                )
            }.exceptionOrNull()

            assertThat(missingBytes).isInstanceOf(IllegalStateException::class.java)
            assertThat(missingBytes!!.message).contains("bytes are missing")
        }

    @Test
    fun `getDataUrl returns a local data URL for images only`() =
        kotlinx.coroutines.test.runTest {
            val imageBytes = byteArrayOf(4, 5, 6)
            val fileBytes = "text".toByteArray()
            val added = service.addFiles(
                listOf(
                    AttachmentInput("photo.png", "image/png", imageBytes.size.toLong(), imageBytes),
                    AttachmentInput("notes.txt", "text/plain", fileBytes.size.toLong(), fileBytes),
                ),
            ).attachments
            val image = added.first { it.kind == AttachmentKind.IMAGE }
            val file = added.first { it.kind == AttachmentKind.FILE }

            val dataUrl = service.getDataUrl(image.id)

            assertThat(dataUrl).startsWith("data:image/png;base64,")
            assertThat(dataUrl!!.substringAfter(","))
                .isEqualTo(Base64.getEncoder().encodeToString(imageBytes))
            assertThat(service.getDataUrl(file.id)).isNull()
            assertThat(service.getDataUrl("missing")).isNull()
        }

    @Test
    fun `cleanup only removes rows created before the cutoff`() =
        kotlinx.coroutines.test.runTest {
            val draft = service.addFiles(
                listOf(AttachmentInput("draft.txt", "text/plain", 5, "draft".toByteArray())),
            ).attachments.single()

            service.cleanupOrphans(before = now.minusSeconds(1))

            assertThat(repository.get(draft.id)).isNotNull()
            assertThat(storage.fileFor(draft.storageKey).exists()).isTrue()

            service.cleanupOrphans(before = now.plusSeconds(1))

            assertThat(repository.get(draft.id)).isNull()
            assertThat(storage.fileFor(draft.storageKey).exists()).isFalse()
        }

    private fun messageWithParts(vararg parts: ContentPart): Message = Message(
        id = "message-1",
        conversationId = "conversation-1",
        sequence = 1,
        role = MessageRole.USER,
        parts = parts.toList(),
        status = MessageStatus.COMPLETE,
        providerId = null,
        modelId = null,
        modelLabel = null,
        usage = null,
        finishReason = null,
        error = null,
        latencyMs = null,
        firstTokenMs = null,
        createdAt = now,
        updatedAt = now,
        deletedAt = null,
    )

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xFF) }
}

private class FakeImageProcessor : ImageProcessor {
    override suspend fun process(bytes: ByteArray, mimeType: String): ProcessedImage =
        ProcessedImage(bytes = bytes, mimeType = mimeType, width = 10, height = 10)
}
